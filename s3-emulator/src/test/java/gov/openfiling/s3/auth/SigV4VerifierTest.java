package gov.openfiling.s3.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import gov.openfiling.s3.S3Exception;

class SigV4VerifierTest {

    /** Examples from the Amazon S3 API reference ("Signature Calculations for the Authorization Header"). */
    @Nested
    class AwsS3DocumentationExamples {

        private static final String AK = "AKIAIOSFODNN7EXAMPLE";
        private final SigV4Verifier verifier = new SigV4Verifier(
                Map.of(AK, "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"), "us-east-1", Duration.ofMinutes(15),
                Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC));

        @Test
        void getObjectWithRange() {
            VerifiedRequest result = verifier.verify(new SignableRequest("GET", "/test.txt", null, headers(
                    "host", "examplebucket.s3.amazonaws.com",
                    "range", "bytes=0-9",
                    "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                    "x-amz-date", "20130524T000000Z",
                    "authorization", "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                            + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                            + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")));
            assertThat(result.accessKey()).isEqualTo(AK);
            assertThat(result.presigned()).isFalse();
            assertThat(result.payloadHashDeclared()).isTrue();
        }

        @Test
        void putObjectWithEncodedKeyAndDateHeader() {
            VerifiedRequest result = verifier.verify(new SignableRequest("PUT", "/test%24file.text", null, headers(
                    "host", "examplebucket.s3.amazonaws.com",
                    "date", "Fri, 24 May 2013 00:00:00 GMT",
                    "x-amz-date", "20130524T000000Z",
                    "x-amz-storage-class", "REDUCED_REDUNDANCY",
                    "x-amz-content-sha256", "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072",
                    "authorization", "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                            + "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"
                            + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd")));
            assertThat(result.payloadHash()).isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
        }

        @Test
        void getBucketLifecycleWithValuelessQueryParameter() {
            verifier.verify(new SignableRequest("GET", "/", "lifecycle", headers(
                    "host", "examplebucket.s3.amazonaws.com",
                    "x-amz-date", "20130524T000000Z",
                    "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                    "authorization", "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                            + "SignedHeaders=host;x-amz-content-sha256;x-amz-date,"
                            + "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543")));
        }

        @Test
        void listObjectsWithSortedQuery() {
            verifier.verify(new SignableRequest("GET", "/", "max-keys=2&prefix=J", headers(
                    "host", "examplebucket.s3.amazonaws.com",
                    "x-amz-date", "20130524T000000Z",
                    "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                    "authorization", "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                            + "SignedHeaders=host;x-amz-content-sha256;x-amz-date,"
                            + "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7")));
        }

        @Test
        void presignedGetUrl() {
            VerifiedRequest result = verifier.verify(new SignableRequest("GET", "/test.txt",
                    "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                            + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                            + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
                    headers("host", "examplebucket.s3.amazonaws.com")));
            assertThat(result.presigned()).isTrue();
            assertThat(result.payloadHashDeclared()).isFalse();
        }

        @Test
        void tamperedPresignedUrlIsRejected() {
            assertError(() -> verifier.verify(new SignableRequest("GET", "/other.txt",
                    "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                            + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                            + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
                    headers("host", "examplebucket.s3.amazonaws.com"))), 403, "SignatureDoesNotMatch");
        }
    }

    // ------------------------------------------------------------------ own cases

    private static final String AK = "openfiling-local";
    private static final String SK = "openfiling-local-secret";
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private final SigV4Verifier verifier = new SigV4Verifier(Map.of(AK, SK), "us-east-1", Duration.ofMinutes(15),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private SignableRequest signed(String method, String path, String query, String payloadHash, String secret,
                                   String region, Instant when) {
        Map<String, String> base = Map.of("host", "localhost:9000");
        Map<String, String> auth = TestSigner.sign(method, path, query, base, payloadHash, AK, secret, region, when);
        Map<String, List<String>> all = new HashMap<>();
        base.forEach((k, v) -> all.put(k, List.of(v)));
        auth.forEach((k, v) -> all.put(k.toLowerCase(), List.of(v)));
        return new SignableRequest(method, path, query, all);
    }

    @Test
    void acceptsValidHeaderSignature() {
        VerifiedRequest result = verifier.verify(signed("GET", "/b/k%20x", "list-type=2", SigV4.EMPTY_SHA256, SK,
                "us-east-1", NOW));
        assertThat(result.payloadHash()).isEqualTo(SigV4.EMPTY_SHA256);
        assertThat(verifier.verify(signed("PUT", "/b/k", null, SigV4.UNSIGNED_PAYLOAD, SK, "us-east-1", NOW))
                .payloadHashDeclared()).isFalse();
    }

    @Test
    void rejectsWrongSecret() {
        assertError(() -> verifier.verify(signed("GET", "/b/k", null, SigV4.EMPTY_SHA256, "nope", "us-east-1", NOW)),
                403, "SignatureDoesNotMatch");
    }

    @Test
    void rejectsWrongRegionAndSkew() {
        assertError(() -> verifier.verify(signed("GET", "/b/k", null, SigV4.EMPTY_SHA256, SK, "eu-west-1", NOW)),
                400, "AuthorizationHeaderMalformed");
        assertError(() -> verifier.verify(signed("GET", "/b/k", null, SigV4.EMPTY_SHA256, SK, "us-east-1",
                NOW.minus(Duration.ofMinutes(16)))), 403, "RequestTimeTooSkewed");
        assertError(() -> verifier.verify(signed("GET", "/b/k", null, SigV4.EMPTY_SHA256, SK, "us-east-1",
                NOW.plus(Duration.ofMinutes(16)))), 403, "RequestTimeTooSkewed");
    }

    @Test
    void rejectsStreamingAndInvalidPayloadHashes() {
        assertError(() -> verifier.verify(signed("PUT", "/b/k", null, "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", SK,
                "us-east-1", NOW)), 501, "NotImplemented");
        assertError(() -> verifier.verify(signed("PUT", "/b/k", null, "not-a-hash", SK, "us-east-1", NOW)),
                400, "InvalidArgument");
    }

    @Test
    void rejectsMalformedAuthorizationHeaders() {
        assertError(() -> verifier.verify(request("authorization", "AWS AKID:signature")), 400, "InvalidRequest");
        assertError(() -> verifier.verify(request("authorization", "AWS4-HMAC-SHA256 Credential=x")),
                400, "AuthorizationHeaderMalformed");
        assertError(() -> verifier.verify(request("authorization",
                "AWS4-HMAC-SHA256 Credential=unknown/20260929/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00")),
                403, "InvalidAccessKeyId");
        assertError(() -> verifier.verify(request("authorization",
                "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=00")),
                400, "AuthorizationHeaderMalformed");
        assertError(() -> verifier.verify(request("authorization",
                "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/s3/aws5_request, SignedHeaders=host, Signature=00")),
                400, "AuthorizationHeaderMalformed");
        assertError(() -> verifier.verify(request("authorization",
                "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00")),
                403, "AccessDenied"); // missing x-amz-date
        assertError(() -> verifier.verify(request(
                "authorization", "AWS4-HMAC-SHA256 Credential=openfiling-local/20260928/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00",
                "x-amz-date", "20260929T120000Z")), 400, "AuthorizationHeaderMalformed"); // scope date mismatch
        assertError(() -> verifier.verify(request(
                "authorization", "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00",
                "x-amz-date", "20260929T120000Z")), 400, "InvalidRequest"); // missing x-amz-content-sha256
        assertError(() -> verifier.verify(request(
                "authorization", "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/s3/aws4_request, SignedHeaders=x-amz-date, Signature=00",
                "x-amz-date", "20260929T120000Z", "x-amz-content-sha256", SigV4.UNSIGNED_PAYLOAD)), 403, "AccessDenied");
        assertError(() -> verifier.verify(request(
                "authorization", "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/s3/aws4_request, SignedHeaders=Host, Signature=00",
                "x-amz-date", "20260929T120000Z", "x-amz-content-sha256", SigV4.UNSIGNED_PAYLOAD)),
                400, "AuthorizationHeaderMalformed");
        assertError(() -> verifier.verify(request(
                "authorization", "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=zz",
                "x-amz-date", "20260929T120000Z", "x-amz-content-sha256", SigV4.UNSIGNED_PAYLOAD)),
                403, "SignatureDoesNotMatch");
        assertError(() -> verifier.verify(request(
                "authorization", "AWS4-HMAC-SHA256 Credential=openfiling-local/20260929/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00",
                "x-amz-date", "yesterday")), 403, "AccessDenied");
    }

    @Test
    void rejectsAnonymousAndMixedAuthentication() {
        assertError(() -> verifier.verify(request()), 403, "AccessDenied");
        assertError(() -> verifier.verify(new SignableRequest("GET", "/b/k", "X-Amz-Signature=00",
                headers("authorization", "AWS4-HMAC-SHA256 x"))), 400, "InvalidArgument");
    }

    // ---------------------------------------------------------------- presigned

    private String presignedQuery(String amzDate, String expires, String signedHeaders, String secret, String path,
                                  Map<String, List<String>> headers) {
        String date = amzDate.substring(0, 8);
        String scope = SigV4.scope(date, "us-east-1", "s3");
        String query = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=" + SigV4.uriEncode(AK + "/" + scope, false)
                + "&X-Amz-Date=" + amzDate + "&X-Amz-Expires=" + expires + "&X-Amz-SignedHeaders="
                + SigV4.uriEncode(signedHeaders, false);
        List<String> signed = List.of(signedHeaders.split(";"));
        String canonical = SigV4.canonicalRequest("PUT", SigV4.canonicalUri(path),
                SigV4.canonicalQuery(SigV4.parseQuery(query), java.util.Set.of()),
                SigV4.canonicalHeaders(headers, signed), signedHeaders, SigV4.UNSIGNED_PAYLOAD);
        String signature = SigV4.signature(SigV4.signingKey(secret, date, "us-east-1", "s3"),
                SigV4.stringToSign(amzDate, scope, canonical));
        return query + "&X-Amz-Signature=" + signature;
    }

    @Test
    void presignedPutSignsContentType() {
        Map<String, List<String>> headers = Map.of("host", List.of("localhost:9000"),
                "content-type", List.of("application/pdf"));
        String query = presignedQuery("20260929T115500Z", "900", "content-type;host", SK, "/b/k.pdf", headers);
        assertThat(verifier.verify(new SignableRequest("PUT", "/b/k.pdf", query, headers)).presigned()).isTrue();

        Map<String, List<String>> wrongType = Map.of("host", List.of("localhost:9000"),
                "content-type", List.of("text/plain"));
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k.pdf", query, wrongType)),
                403, "SignatureDoesNotMatch");
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k.pdf", query + "&extra=1", headers)),
                403, "SignatureDoesNotMatch");
    }

    @Test
    void presignedExpiryAndValidation() {
        Map<String, List<String>> headers = Map.of("host", List.of("localhost:9000"));
        String expired = presignedQuery("20260929T113000Z", "60", "host", SK, "/b/k", headers);
        assertThatThrownBy(() -> verifier.verify(new SignableRequest("PUT", "/b/k", expired, headers)))
                .isInstanceOf(S3Exception.class).hasMessage("Request has expired");
        String future = presignedQuery("20260929T130000Z", "60", "host", SK, "/b/k", headers);
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", future, headers)), 403, "AccessDenied");
        String tooLong = presignedQuery("20260929T115500Z", "604801", "host", SK, "/b/k", headers);
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", tooLong, headers)),
                400, "AuthorizationQueryParametersError");
        String notNumber = presignedQuery("20260929T115500Z", "soon", "host", SK, "/b/k", headers);
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", notNumber, headers)),
                400, "AuthorizationQueryParametersError");
        String negative = presignedQuery("20260929T115500Z", "-5", "host", SK, "/b/k", headers);
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", negative, headers)),
                400, "AuthorizationQueryParametersError");
        String badDate = presignedQuery("20260929T1155", "60", "host", SK, "/b/k", headers);
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", badDate, headers)),
                400, "AuthorizationQueryParametersError");
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", "X-Amz-Algorithm=AWS4-HMAC-SHA256", headers)),
                400, "AuthorizationQueryParametersError");
        String wrongAlgorithm = presignedQuery("20260929T115500Z", "60", "host", SK, "/b/k", headers)
                .replace("AWS4-HMAC-SHA256", "AWS4-HMAC-SHA1");
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", wrongAlgorithm, headers)),
                400, "AuthorizationQueryParametersError");
        String badCredential = presignedQuery("20260929T115500Z", "60", "host", SK, "/b/k", headers)
                .replace("X-Amz-Credential=openfiling-local", "X-Amz-Credential=");
        assertError(() -> verifier.verify(new SignableRequest("PUT", "/b/k", badCredential, headers)),
                400, "AuthorizationQueryParametersError");
    }

    // ---------------------------------------------------------------- helpers

    private static SignableRequest request(String... kv) {
        return new SignableRequest("GET", "/b/k", null, headers(kv));
    }

    private static Map<String, List<String>> headers(String... kv) {
        Map<String, List<String>> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], List.of(kv[i + 1]));
        }
        return map;
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(S3Exception.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.status()).isEqualTo(status);
        });
    }
}
