package gov.openfiling.s3.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import gov.openfiling.s3.auth.SigV4;
import gov.openfiling.s3.auth.TestSigner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end tests against the running emulator using the real AWS SDK v2, configured exactly like filing-api
 * (path-style, endpoint override, no chunked encoding, checksums only WHEN_REQUIRED).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3ApiIntegrationTest {

    private static final String DOCS = "openfiling-documents";
    private static final String COPIES = "openfiling-public-copies";
    private static final String AK = "openfiling-local";
    private static final String SK = "openfiling-local-secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path ROOT;
    private static final HttpServer STUB;
    private static final ConcurrentLinkedQueue<Captured> CAPTURED = new ConcurrentLinkedQueue<>();

    record Captured(String path, Map<String, String> headers, String body) {
    }

    static {
        try {
            ROOT = Files.createTempDirectory("s3-emulator-it");
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/", exchange -> {
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
                CAPTURED.add(new Captured(exchange.getRequestURI().getPath(), headers,
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().startsWith("/redaction") ? 202 : 204, -1);
                exchange.close();
            });
            STUB.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String stub = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("app.storage-root", ROOT::toString);
        registry.add("app.notification-retry.initial-backoff", () -> "20ms");
        registry.add("app.notifications[0].name", () -> "redaction");
        registry.add("app.notifications[0].bucket", () -> DOCS);
        registry.add("app.notifications[0].prefix", () -> "public/");
        registry.add("app.notifications[0].suffix", () -> ".pdf");
        registry.add("app.notifications[0].url", () -> stub + "/redaction/2015-03-31/functions/openfiling-redaction/invocations");
        registry.add("app.notifications[0].headers[X-Amz-Invocation-Type]", () -> "Event");
        registry.add("app.notifications[1].name", () -> "filing-api");
        registry.add("app.notifications[1].bucket", () -> DOCS);
        registry.add("app.notifications[1].url", () -> stub + "/internal/v1/storage/events");
        registry.add("app.notifications[1].headers[X-Internal-Token]", () -> "test-token");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private URI endpoint() {
        return URI.create("http://localhost:" + port);
    }

    private S3Client client(String secret) {
        return S3Client.builder()
                .endpointOverride(endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(AK, secret)))
                .forcePathStyle(true)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    private S3Presigner presigner() {
        return S3Presigner.builder()
                .endpointOverride(endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(AK, SK)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @BeforeEach
    void clearCaptured() {
        CAPTURED.clear();
    }

    private static byte[] pdf(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 9);
        return bytes;
    }

    // ---------------------------------------------------------------- SDK S3Client

    @Test
    void putGetHeadListDeleteWithTheSdk() {
        try (S3Client s3 = client(SK)) {
            String key = "restricted/" + UUID.randomUUID() + "/doc (1)+ü x.pdf";
            byte[] body = pdf(200_000);
            PutObjectResponse put = s3.putObject(b -> b.bucket(DOCS).key(key).contentType("application/pdf")
                    .metadata(Map.of("origin", "it")), RequestBody.fromBytes(body));
            assertThat(put.eTag()).startsWith("\"").hasSize(34);

            HeadObjectResponse head = s3.headObject(b -> b.bucket(DOCS).key(key));
            assertThat(head.contentLength()).isEqualTo(body.length);
            assertThat(head.contentType()).isEqualTo("application/pdf");
            assertThat(head.eTag()).isEqualTo(put.eTag());
            assertThat(head.metadata()).containsEntry("origin", "it");
            assertThat(head.lastModified()).isBefore(Instant.now().plusSeconds(5));

            ResponseBytes<GetObjectResponse> got = s3.getObjectAsBytes(b -> b.bucket(DOCS).key(key));
            assertThat(got.asByteArray()).isEqualTo(body);

            ListObjectsV2Response list = s3.listObjectsV2(b -> b.bucket(DOCS).prefix(key.substring(0, 48)));
            assertThat(list.contents()).extracting(S3Object::key).containsExactly(key);
            assertThat(list.contents().getFirst().size()).isEqualTo(body.length);

            s3.deleteObject(b -> b.bucket(DOCS).key(key));
            s3.deleteObject(b -> b.bucket(DOCS).key(key)); // idempotent
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(DOCS).key(key))).isInstanceOf(NoSuchKeyException.class);
            assertThatThrownBy(() -> s3.getObject(b -> b.bucket(DOCS).key(key))).isInstanceOf(NoSuchKeyException.class);
        }
    }

    @Test
    void rangedGetReturnsPartialContent() {
        try (S3Client s3 = client(SK)) {
            String key = "restricted/range/" + UUID.randomUUID() + ".pdf";
            s3.putObject(b -> b.bucket(DOCS).key(key).contentType("application/pdf"), RequestBody.fromBytes(pdf(1000)));
            ResponseBytes<GetObjectResponse> first5 = s3.getObjectAsBytes(b -> b.bucket(DOCS).key(key).range("bytes=0-4"));
            assertThat(first5.asUtf8String()).isEqualTo("%PDF-");
            assertThat(first5.response().contentRange()).isEqualTo("bytes 0-4/1000");
            assertThat(first5.response().contentLength()).isEqualTo(5);
        }
    }

    @Test
    void listPaginationAndBucketChecks() {
        try (S3Client s3 = client(SK)) {
            String prefix = "public/page-" + UUID.randomUUID() + "/";
            for (int i = 0; i < 5; i++) {
                int n = i;
                s3.putObject(b -> b.bucket(COPIES).key(prefix + "f" + n + ".json"), RequestBody.fromString("{}"));
            }
            List<String> keys = new ArrayList<>();
            s3.listObjectsV2Paginator(b -> b.bucket(COPIES).prefix(prefix).maxKeys(2))
                    .contents().forEach(o -> keys.add(o.key()));
            assertThat(keys).hasSize(5).isSorted();

            ListObjectsV2Response folders = s3.listObjectsV2(b -> b.bucket(COPIES).prefix("public/").delimiter("/"));
            assertThat(folders.commonPrefixes()).extracting(p -> p.prefix()).contains(prefix);

            s3.headBucket(b -> b.bucket(DOCS));
            assertThatThrownBy(() -> s3.headBucket(b -> b.bucket("no-such-bucket"))).isInstanceOf(NoSuchBucketException.class);
            assertThatThrownBy(() -> s3.listObjectsV2(b -> b.bucket("no-such-bucket"))).isInstanceOf(NoSuchBucketException.class);
            assertThat(s3.listBuckets().buckets()).extracting(b -> b.name()).containsExactly(DOCS, COPIES);
        }
    }

    @Test
    void wrongSecretIsSignatureDoesNotMatch() {
        try (S3Client s3 = client("wrong-secret")) {
            assertThatThrownBy(() -> s3.headBucket(b -> b.bucket(DOCS)))
                    .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
            assertThatThrownBy(() -> s3.putObject(b -> b.bucket(DOCS).key("restricted/x.pdf"), RequestBody.fromString("x")))
                    .isInstanceOfSatisfying(S3Exception.class, e -> {
                        assertThat(e.statusCode()).isEqualTo(403);
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("SignatureDoesNotMatch");
                    });
        }
    }

    // ---------------------------------------------------------------- pre-signed URLs (browser flow)

    private PresignedPutObjectRequest presignPut(String key, Duration ttl) {
        try (S3Presigner presigner = presigner()) {
            return presigner.presignPutObject(p -> p.signatureDuration(ttl)
                    .putObjectRequest(o -> o.bucket(DOCS).key(key).contentType("application/pdf")));
        }
    }

    private HttpResponse<String> put(String url, String contentType, byte[] body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void presignedPutThenPresignedGet() throws Exception {
        String key = "restricted/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/claims.pdf";
        byte[] body = pdf(50_000);
        PresignedPutObjectRequest presigned = presignPut(key, Duration.ofMinutes(15));
        assertThat(presigned.signedHeaders()).containsKey("content-type");
        HttpResponse<String> response = put(presigned.url().toString(), "application/pdf", body, "http://localhost:4200");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("ETag")).isPresent();
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:4200");
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains("ETag");
        assertThat(response.headers().firstValue("x-amz-request-id")).isPresent();

        PresignedGetObjectRequest get;
        try (S3Presigner presigner = presigner()) {
            get = presigner.presignGetObject(p -> p.signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(o -> o.bucket(DOCS).key(key)
                            .responseContentDisposition("attachment; filename=\"claims.pdf\"")));
        }
        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(get.url().toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(body);
        assertThat(download.headers().firstValue("Content-Disposition")).contains("attachment; filename=\"claims.pdf\"");
        assertThat(download.headers().firstValue("Content-Type")).contains("application/pdf");

        HttpResponse<byte[]> ranged = http.send(HttpRequest.newBuilder(get.url().toURI()).header("Range", "bytes=-4").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(ranged.statusCode()).isEqualTo(206);
        assertThat(ranged.body()).hasSize(4);
    }

    @Test
    void presignedPutWithWrongContentTypeIsRejected() throws Exception {
        PresignedPutObjectRequest presigned = presignPut("restricted/ct/" + UUID.randomUUID() + ".pdf", Duration.ofMinutes(15));
        HttpResponse<String> response = put(presigned.url().toString(), "text/plain", pdf(100), null);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("<Code>SignatureDoesNotMatch</Code>").contains("<RequestId>");
    }

    @Test
    void tamperedPresignedUrlIsRejected() throws Exception {
        String key = "restricted/tamper/" + UUID.randomUUID() + ".pdf";
        String url = presignPut(key, Duration.ofMinutes(15)).url().toString();
        HttpResponse<String> otherKey = put(url.replace("tamper/", "tampered/"), "application/pdf", pdf(10), null);
        assertThat(otherKey.statusCode()).isEqualTo(403);
        assertThat(otherKey.body()).contains("SignatureDoesNotMatch");
        HttpResponse<String> longer = put(url.replace("X-Amz-Expires=900", "X-Amz-Expires=3600"), "application/pdf", pdf(10), null);
        assertThat(longer.statusCode()).isEqualTo(403);
        assertThat(longer.body()).contains("SignatureDoesNotMatch");
        HttpResponse<String> unsigned = put("http://localhost:" + port + "/" + DOCS + "/" + key, "application/pdf", pdf(10), null);
        assertThat(unsigned.statusCode()).isEqualTo(403);
        assertThat(unsigned.body()).contains("AccessDenied");
    }

    @Test
    void expiredPresignedUrlIsRejected() throws Exception {
        String url = presignPut("restricted/exp/" + UUID.randomUUID() + ".pdf", Duration.ofSeconds(1)).url().toString();
        Thread.sleep(2100);
        HttpResponse<String> response = put(url, "application/pdf", pdf(10), null);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("<Code>AccessDenied</Code>").contains("Request has expired");
    }

    // ---------------------------------------------------------------- CORS

    @Test
    void corsPreflightNeedsNoSignature() throws Exception {
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + DOCS + "/public/x.pdf?X-Amz-Algorithm=x"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:4200");
        assertThat(ok.headers().firstValue("Access-Control-Allow-Methods")).contains("GET, PUT, HEAD");
        assertThat(ok.headers().firstValue("Access-Control-Allow-Headers")).contains("content-type");
        assertThat(ok.headers().firstValue("Access-Control-Expose-Headers")).contains("ETag, x-amz-request-id");
        assertThat(ok.headers().firstValue("Access-Control-Max-Age")).contains("3600");

        HttpResponse<String> evil = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + DOCS + "/public/x.pdf"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "PUT")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(evil.statusCode()).isEqualTo(403);
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

        HttpResponse<String> delete = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + DOCS + "/public/x.pdf"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "DELETE")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(delete.statusCode()).isEqualTo(403);

        HttpResponse<String> noOrigin = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + DOCS))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noOrigin.statusCode()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- event notifications

    @Test
    void publicPdfUploadNotifiesRedactionAndFilingApi() throws Exception {
        String appId = UUID.randomUUID().toString();
        String docId = UUID.randomUUID().toString();
        String key = "public/" + appId + "/" + docId + "/my spec+v1.pdf";
        byte[] body = pdf(4096);
        HttpResponse<String> response = put(presignPut(key, Duration.ofMinutes(15)).url().toString(), "application/pdf", body, null);
        assertThat(response.statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).until(() -> CAPTURED.size() >= 2);
        Captured redaction = CAPTURED.stream().filter(c -> c.path().startsWith("/redaction")).findFirst().orElseThrow();
        Captured filing = CAPTURED.stream().filter(c -> c.path().equals("/internal/v1/storage/events")).findFirst().orElseThrow();
        assertThat(redaction.path()).endsWith("/2015-03-31/functions/openfiling-redaction/invocations");
        assertThat(redaction.headers()).containsEntry("x-amz-invocation-type", "Event")
                .containsEntry("content-type", "application/json");
        assertThat(filing.headers()).containsEntry("x-internal-token", "test-token");

        JsonNode record = JSON.readTree(redaction.body()).get("Records").get(0);
        assertThat(record.get("eventName").asString()).isEqualTo("ObjectCreated:Put");
        assertThat(record.get("awsRegion").asString()).isEqualTo("us-east-1");
        assertThat(record.at("/s3/configurationId").asString()).isEqualTo("redaction");
        assertThat(record.at("/s3/bucket/name").asString()).isEqualTo(DOCS);
        String encodedKey = record.at("/s3/object/key").asString();
        assertThat(encodedKey).isEqualTo("public/" + appId + "/" + docId + "/my+spec%2Bv1.pdf");
        assertThat(URLDecoder.decode(encodedKey, StandardCharsets.UTF_8)).isEqualTo(key);
        assertThat(record.at("/s3/object/size").asLong()).isEqualTo(body.length);
        assertThat("\"" + record.at("/s3/object/eTag").asString() + "\"").isEqualTo(response.headers().firstValue("ETag").orElseThrow());
        assertThat(JSON.readTree(filing.body()).at("/Records/0/s3/configurationId").asString()).isEqualTo("filing-api");
    }

    @Test
    void restrictedUploadOnlyNotifiesFilingApi() throws Exception {
        try (S3Client s3 = client(SK)) {
            s3.putObject(b -> b.bucket(DOCS).key("restricted/" + UUID.randomUUID() + "/oath.pdf"), RequestBody.fromBytes(pdf(10)));
            s3.putObject(b -> b.bucket(COPIES).key("public/" + UUID.randomUUID() + "/public-copy.pdf"), RequestBody.fromBytes(pdf(10)));
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> CAPTURED.size() >= 1);
        Thread.sleep(300);
        assertThat(CAPTURED).extracting(Captured::path).containsExactly("/internal/v1/storage/events");
    }

    // ---------------------------------------------------------------- hand-signed edge cases

    private HttpResponse<String> signedRequest(String method, String path, String query, byte[] body, String payloadHash,
                                               Map<String, String> extraHeaders) throws Exception {
        Map<String, String> signedHeaders = new HashMap<>(extraHeaders);
        signedHeaders.put("host", "localhost:" + port);
        Map<String, String> auth = TestSigner.sign(method, path, query, signedHeaders, payloadHash, AK, SK, "us-east-1", Instant.now());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + (query == null ? "" : "?" + query)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        auth.forEach(request::header);
        extraHeaders.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bodyHashMismatchIsRejected() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response = signedRequest("PUT", "/" + DOCS + "/restricted/hash.pdf", null, body,
                SigV4.sha256Hex("other".getBytes(StandardCharsets.UTF_8)), Map.of());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("XAmzContentSHA256Mismatch");

        HttpResponse<String> ok = signedRequest("PUT", "/" + DOCS + "/restricted/hash.pdf", null, body,
                SigV4.sha256Hex(body), Map.of("x-amz-meta-note", "a"));
        assertThat(ok.statusCode()).isEqualTo(200);

        HttpResponse<String> getWithBody = signedRequest("GET", "/" + DOCS + "/restricted/hash.pdf", null, null,
                SigV4.sha256Hex("x".getBytes(StandardCharsets.UTF_8)), Map.of());
        assertThat(getWithBody.statusCode()).isEqualTo(400);
    }

    @Test
    void streamingPayloadsAndUnsupportedOperationsAre501() throws Exception {
        HttpResponse<String> streaming = signedRequest("PUT", "/" + DOCS + "/restricted/s.pdf", null, "x".getBytes(StandardCharsets.UTF_8),
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", Map.of());
        assertThat(streaming.statusCode()).isEqualTo(501);
        assertThat(streaming.body()).contains("NotImplemented");

        assertThat(signedRequest("POST", "/" + DOCS + "/restricted/s.pdf", "uploads", null, SigV4.EMPTY_SHA256, Map.of())
                .statusCode()).isEqualTo(501);
        assertThat(signedRequest("GET", "/" + DOCS, "acl", null, SigV4.EMPTY_SHA256, Map.of()).statusCode()).isEqualTo(501);
        assertThat(signedRequest("PUT", "/" + DOCS, null, null, SigV4.EMPTY_SHA256, Map.of()).statusCode()).isEqualTo(409);
        assertThat(signedRequest("PUT", "/new-bucket", null, null, SigV4.EMPTY_SHA256, Map.of()).statusCode()).isEqualTo(501);
        assertThat(signedRequest("DELETE", "/" + DOCS, null, null, SigV4.EMPTY_SHA256, Map.of()).statusCode()).isEqualTo(501);
        assertThat(signedRequest("DELETE", "/", null, null, SigV4.EMPTY_SHA256, Map.of()).statusCode()).isEqualTo(501);
        assertThat(signedRequest("PUT", "/" + DOCS + "/restricted/copy.pdf", null, null, SigV4.EMPTY_SHA256,
                Map.of("x-amz-copy-source", "/" + DOCS + "/a")).statusCode()).isEqualTo(501);
        HttpResponse<String> badKey = signedRequest("PUT", "/" + DOCS + "/restricted/x.meta.json", null,
                new byte[0], SigV4.EMPTY_SHA256, Map.of());
        assertThat(badKey.statusCode()).isEqualTo(400);
        assertThat(badKey.body()).contains("InvalidArgument");
    }

    @Test
    void listV1ConditionalsAndInvalidParameters() throws Exception {
        try (S3Client s3 = client(SK)) {
            s3.putObject(b -> b.bucket(COPIES).key("v1/a b.json"), RequestBody.fromString("{}"));
            s3.putObject(b -> b.bucket(COPIES).key("v1/c.json"), RequestBody.fromString("{}"));
            assertThat(s3.listObjects(b -> b.bucket(COPIES).prefix("v1/").maxKeys(1)).contents())
                    .extracting(S3Object::key).containsExactly("v1/a b.json");
        }
        HttpResponse<String> v1 = signedRequest("GET", "/" + COPIES, "prefix=v1%2F&max-keys=1", null, SigV4.EMPTY_SHA256, Map.of());
        assertThat(v1.body()).contains("<NextMarker>v1/a b.json</NextMarker>").contains("<IsTruncated>true</IsTruncated>");
        assertThat(signedRequest("GET", "/" + COPIES, "list-type=2&max-keys=abc", null, SigV4.EMPTY_SHA256, Map.of())
                .statusCode()).isEqualTo(400);
        assertThat(signedRequest("GET", "/" + COPIES, "list-type=2&max-keys=-1", null, SigV4.EMPTY_SHA256, Map.of())
                .statusCode()).isEqualTo(400);
        assertThat(signedRequest("GET", "/" + COPIES, "list-type=2&encoding-type=base64", null, SigV4.EMPTY_SHA256, Map.of())
                .statusCode()).isEqualTo(400);
        HttpResponse<String> withStart = signedRequest("GET", "/" + COPIES, "list-type=2&prefix=v1%2F&start-after=v1%2Fa&delimiter=%2F",
                null, SigV4.EMPTY_SHA256, Map.of());
        assertThat(withStart.body()).contains("<StartAfter>v1/a</StartAfter>").contains("<Delimiter>/</Delimiter>");

        HttpResponse<String> head = signedRequest("GET", "/" + COPIES + "/v1/c.json", null, null, SigV4.EMPTY_SHA256, Map.of());
        String etag = head.headers().firstValue("ETag").orElseThrow();
        assertThat(signedRequest("GET", "/" + COPIES + "/v1/c.json", null, null, SigV4.EMPTY_SHA256,
                Map.of("if-none-match", etag)).statusCode()).isEqualTo(304);
        assertThat(signedRequest("GET", "/" + COPIES + "/v1/c.json", null, null, SigV4.EMPTY_SHA256,
                Map.of("if-match", "\"nope\"")).statusCode()).isEqualTo(412);
        assertThat(signedRequest("GET", "/" + COPIES + "/v1/c.json", null, null, SigV4.EMPTY_SHA256,
                Map.of("range", "bytes=50-")).statusCode()).isEqualTo(416);
    }

    // ---------------------------------------------------------------- admin

    @Test
    void adminEndpointsNeedNoSignature() throws Exception {
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/_emulator/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(health.body()).get("status").asString()).isEqualTo("UP");
        HttpResponse<String> buckets = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/_emulator/buckets"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = JSON.readTree(buckets.body());
        assertThat(node.get(0).get("name").asString()).isEqualTo(DOCS);
        assertThat(node.get(0).has("objectCount")).isTrue();
        assertThat(node.get(0).has("totalBytes")).isTrue();
        HttpResponse<String> actuator = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(actuator.statusCode()).isEqualTo(200);
    }
}
