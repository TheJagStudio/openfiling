package gov.openfiling.s3.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.openfiling.s3.S3Exception;
import gov.openfiling.s3.auth.SigV4;
import gov.openfiling.s3.auth.SigV4Verifier;
import gov.openfiling.s3.auth.SignableRequest;
import gov.openfiling.s3.auth.VerifiedRequest;
import gov.openfiling.s3.notify.NotificationPublisher;
import gov.openfiling.s3.storage.FileSystemObjectStore;
import gov.openfiling.s3.storage.FileSystemObjectStore.OpenObject;
import gov.openfiling.s3.storage.ListPage;
import gov.openfiling.s3.storage.ObjectMeta;
import gov.openfiling.s3.storage.PutRequest;
import gov.openfiling.s3.storage.StoredObject;

/**
 * The S3 REST API (path-style): {@code /{bucket}} and {@code /{bucket}/{key}}. Every request is SigV4-verified
 * before anything else happens. Supported: PutObject, GetObject (Range, response-* overrides), HeadObject,
 * DeleteObject, ListObjectsV2 (and V1), HeadBucket, ListBuckets. Everything else answers {@code 501 NotImplemented}.
 */
@RestController
public class S3Controller {

    private static final Logger log = LoggerFactory.getLogger(S3Controller.class);

    static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);
    static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final int MAX_NON_PUT_BODY = 1024 * 1024;
    private static final Set<String> OBJECT_PARAMS = Set.of("x-id", "versionId");
    private static final Set<String> BUCKET_PARAMS = Set.of("x-id", "list-type", "prefix", "delimiter", "max-keys",
            "continuation-token", "start-after", "encoding-type", "fetch-owner", "marker");
    private static final Map<String, String> RESPONSE_OVERRIDES = Map.of(
            "response-content-type", "Content-Type",
            "response-content-disposition", "Content-Disposition",
            "response-content-language", "Content-Language",
            "response-content-encoding", "Content-Encoding",
            "response-cache-control", "Cache-Control",
            "response-expires", "Expires");

    private final SigV4Verifier verifier;
    private final FileSystemObjectStore store;
    private final NotificationPublisher notifications;
    private final String region;

    public S3Controller(SigV4Verifier verifier, FileSystemObjectStore store, NotificationPublisher notifications,
                        gov.openfiling.s3.config.EmulatorProperties properties) {
        this.verifier = verifier;
        this.store = store;
        this.notifications = notifications;
        this.region = properties.region();
    }

    @RequestMapping("/**")
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String rawPath = request.getRequestURI();
        SignableRequest signable = new SignableRequest(request.getMethod(), rawPath, request.getQueryString(),
                headers(request));
        String path;
        try {
            path = SigV4.percentDecode(rawPath);
        } catch (IllegalArgumentException e) {
            throw S3Exception.invalidArgument("Malformed URI encoding");
        }
        VerifiedRequest auth = verifier.verify(signable);
        Map<String, String> query = firstValues(signable.queryParams());
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String bucket = slash < 0 ? trimmed : trimmed.substring(0, slash);
        String key = slash < 0 ? "" : trimmed.substring(slash + 1);
        String method = request.getMethod();

        if (!"PUT".equals(method) || bucket.isEmpty() || key.isEmpty()) {
            verifySmallBody(request, auth);
        }
        if (bucket.isEmpty()) {
            if (!"GET".equals(method)) {
                throw S3Exception.notImplemented("Only ListBuckets is supported on the service endpoint");
            }
            listBuckets(response);
            return;
        }
        if (key.isEmpty()) {
            rejectUnsupported(query, BUCKET_PARAMS, "bucket");
            switch (method) {
                case "HEAD" -> headBucket(bucket, response);
                case "GET" -> listObjects(bucket, query, response);
                case "PUT" -> {
                    if (store.bucketExists(bucket)) {
                        throw new S3Exception(409, "BucketAlreadyOwnedByYou",
                                "Your previous request to create the named bucket succeeded and you already own it.");
                    }
                    throw S3Exception.notImplemented("Buckets are created from configuration (app.buckets)");
                }
                default -> throw S3Exception.notImplemented("Bucket operation " + method + " is not supported");
            }
            return;
        }
        rejectUnsupported(query, OBJECT_PARAMS, "object");
        switch (method) {
            case "PUT" -> putObject(bucket, key, request, response, auth);
            case "GET" -> getObject(bucket, key, query, request, response, true);
            case "HEAD" -> getObject(bucket, key, query, request, response, false);
            case "DELETE" -> {
                store.delete(bucket, key);
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
            default -> throw S3Exception.notImplemented("Object operation " + method + " is not supported");
        }
    }

    // ---------------------------------------------------------------- objects

    private void putObject(String bucket, String key, HttpServletRequest request, HttpServletResponse response,
                           VerifiedRequest auth) throws IOException {
        if (request.getHeader("x-amz-copy-source") != null) {
            throw S3Exception.notImplemented("CopyObject is not supported");
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            throw new S3Exception(411, "MissingContentLength", "You must provide the Content-Length HTTP header.");
        }
        store.requireBucket(bucket);
        Map<String, String> userMetadata = new TreeMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-amz-meta-")) {
                userMetadata.put(lower.substring("x-amz-meta-".length()), request.getHeader(name));
            }
        }
        PutRequest put = new PutRequest(contentLength, request.getContentType(), userMetadata,
                auth.payloadHashDeclared() ? auth.payloadHash() : null, request.getHeader("Content-MD5"));
        ObjectMeta meta;
        try (InputStream body = request.getInputStream()) {
            meta = store.put(bucket, key, body, put);
        }
        log.info("PutObject {}/{} ({} bytes, {})", bucket, key, meta.contentLength(),
                auth.presigned() ? "pre-signed" : "header auth");
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("ETag", meta.quotedEtag());
        response.setContentLength(0);
        notifications.objectCreated(bucket, key, meta.contentLength(), meta.etag());
    }

    private void getObject(String bucket, String key, Map<String, String> query, HttpServletRequest request,
                           HttpServletResponse response, boolean withBody) throws IOException {
        Optional<OpenObject> opened = store.open(bucket, key);
        if (opened.isEmpty()) {
            throw S3Exception.noSuchKey();
        }
        try (OpenObject open = opened.get()) {
            StoredObject object = open.object();
            ObjectMeta meta = object.meta();
            String ifMatch = request.getHeader("If-Match");
            if (ifMatch != null && !etagMatches(ifMatch, meta)) {
                throw new S3Exception(412, "PreconditionFailed",
                        "At least one of the pre-conditions you specified did not hold");
            }
            String ifNoneMatch = request.getHeader("If-None-Match");
            if (ifNoneMatch != null && etagMatches(ifNoneMatch, meta)) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                response.setHeader("ETag", meta.quotedEtag());
                return;
            }
            long size = meta.contentLength();
            Optional<ByteRange> range = ByteRange.parse(request.getHeader("Range"), size);
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("ETag", meta.quotedEtag());
            response.setHeader("Last-Modified", HTTP_DATE.format(Instant.parse(meta.lastModified())));
            response.setContentType(meta.contentType());
            meta.userMetadata().forEach((name, value) -> response.setHeader("x-amz-meta-" + name, value));
            RESPONSE_OVERRIDES.forEach((param, header) -> {
                String value = query.get(param);
                if (value != null) {
                    response.setHeader(header, value);
                }
            });
            long first = 0;
            long length = size;
            if (range.isPresent()) {
                first = range.get().first();
                length = range.get().length();
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", "bytes " + first + "-" + range.get().last() + "/" + size);
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
            response.setContentLengthLong(length);
            if (!withBody) {
                return;
            }
            open.channel().position(first);
            InputStream in = Channels.newInputStream(open.channel());
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    private static boolean etagMatches(String header, ObjectMeta meta) {
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*") || tag.equals(meta.quotedEtag()) || tag.equals(meta.etag())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- buckets

    private void headBucket(String bucket, HttpServletResponse response) {
        store.requireBucket(bucket);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("x-amz-bucket-region", region);
        response.setContentLength(0);
    }

    private void listBuckets(HttpServletResponse response) throws IOException {
        StringBuilder sb = new StringBuilder(S3Xml.PROLOG)
                .append("<ListAllMyBucketsResult xmlns=\"").append(S3Xml.NAMESPACE).append("\">")
                .append("<Owner><ID>openfiling-local</ID><DisplayName>openfiling-local</DisplayName></Owner><Buckets>");
        for (String bucket : store.bucketNames()) {
            sb.append("<Bucket>");
            S3Xml.element(sb, "Name", bucket);
            S3Xml.element(sb, "CreationDate", "2026-01-01T00:00:00.000Z");
            sb.append("</Bucket>");
        }
        sb.append("</Buckets></ListAllMyBucketsResult>");
        S3Xml.writeXml(response, 200, sb.toString());
    }

    private void listObjects(String bucket, Map<String, String> query, HttpServletResponse response)
            throws IOException {
        boolean v2 = "2".equals(query.get("list-type"));
        String prefix = query.getOrDefault("prefix", "");
        String delimiter = query.get("delimiter");
        boolean urlEncoding = "url".equals(query.get("encoding-type"));
        if (query.containsKey("encoding-type") && !urlEncoding) {
            throw S3Exception.invalidArgument("Invalid Encoding Method specified in Request");
        }
        int maxKeys = 1000;
        if (query.containsKey("max-keys")) {
            try {
                maxKeys = Math.min(1000, Integer.parseInt(query.get("max-keys")));
            } catch (NumberFormatException e) {
                throw S3Exception.invalidArgument("Provided max-keys not an integer or within integer range");
            }
            if (maxKeys < 0) {
                throw S3Exception.invalidArgument("Argument maxKeys must be an integer between 0 and 2147483647");
            }
        }
        String continuation = query.get("continuation-token");
        String startAfter = v2 ? query.get("start-after") : query.get("marker");
        String effectiveStart = continuation != null
                ? FileSystemObjectStore.decodeContinuationToken(continuation) : startAfter;
        ListPage page = store.list(bucket, prefix, delimiter, effectiveStart, maxKeys);

        StringBuilder sb = new StringBuilder(S3Xml.PROLOG)
                .append("<ListBucketResult xmlns=\"").append(S3Xml.NAMESPACE).append("\">");
        S3Xml.element(sb, "Name", bucket);
        S3Xml.element(sb, "Prefix", enc(prefix, urlEncoding));
        if (delimiter != null) {
            S3Xml.element(sb, "Delimiter", enc(delimiter, urlEncoding));
        }
        S3Xml.element(sb, "MaxKeys", maxKeys);
        if (urlEncoding) {
            S3Xml.element(sb, "EncodingType", "url");
        }
        S3Xml.element(sb, "IsTruncated", page.truncated());
        if (v2) {
            S3Xml.element(sb, "KeyCount", page.objects().size() + page.commonPrefixes().size());
            if (continuation != null) {
                S3Xml.element(sb, "ContinuationToken", continuation);
            }
            if (startAfter != null) {
                S3Xml.element(sb, "StartAfter", enc(startAfter, urlEncoding));
            }
            if (page.nextContinuationToken() != null) {
                S3Xml.element(sb, "NextContinuationToken", page.nextContinuationToken());
            }
        } else {
            S3Xml.element(sb, "Marker", enc(startAfter == null ? "" : startAfter, urlEncoding));
            if (page.truncated() && !page.objects().isEmpty()) {
                S3Xml.element(sb, "NextMarker", enc(page.objects().getLast().key(), urlEncoding));
            }
        }
        for (StoredObject object : page.objects()) {
            sb.append("<Contents>");
            S3Xml.element(sb, "Key", enc(object.key(), urlEncoding));
            S3Xml.element(sb, "LastModified", ISO_MILLIS.format(Instant.parse(object.meta().lastModified())));
            S3Xml.element(sb, "ETag", object.meta().quotedEtag());
            S3Xml.element(sb, "Size", object.meta().contentLength());
            S3Xml.element(sb, "StorageClass", "STANDARD");
            sb.append("</Contents>");
        }
        for (String commonPrefix : page.commonPrefixes()) {
            sb.append("<CommonPrefixes>");
            S3Xml.element(sb, "Prefix", enc(commonPrefix, urlEncoding));
            sb.append("</CommonPrefixes>");
        }
        sb.append("</ListBucketResult>");
        S3Xml.writeXml(response, 200, sb.toString());
    }

    private static String enc(String value, boolean urlEncoding) {
        return urlEncoding ? java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8) : value;
    }

    // ---------------------------------------------------------------- request helpers

    /** Non-PUT requests carry (at most) a tiny body; verify it against a declared SHA-256 as S3 does. */
    private static void verifySmallBody(HttpServletRequest request, VerifiedRequest auth) throws IOException {
        if (!auth.payloadHashDeclared()) {
            return;
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_NON_PUT_BODY + 1);
        }
        if (body.length > MAX_NON_PUT_BODY) {
            throw new S3Exception(400, "MaxMessageLengthExceeded", "Your request was too big.");
        }
        if (!SigV4.constantTimeEquals(SigV4.sha256Hex(body), auth.payloadHash())) {
            throw new S3Exception(400, "XAmzContentSHA256Mismatch",
                    "The provided 'x-amz-content-sha256' header does not match what was computed.");
        }
    }

    private static void rejectUnsupported(Map<String, String> query, Set<String> allowed, String level) {
        for (String name : query.keySet()) {
            if (name.startsWith("X-Amz-") || name.startsWith("response-") || allowed.contains(name)) {
                continue;
            }
            throw S3Exception.notImplemented("The " + level + " sub-resource or parameter '" + name
                    + "' is not supported by the emulator");
        }
    }

    private static Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            List<String> values = headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
            values.addAll(Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    private static Map<String, String> firstValues(List<Map.Entry<String, String>> params) {
        Map<String, String> map = new LinkedHashMap<>();
        params.forEach(e -> map.putIfAbsent(e.getKey(), e.getValue()));
        return map;
    }

    // ---------------------------------------------------------------- errors

    @ExceptionHandler(S3Exception.class)
    public void onS3Error(S3Exception error, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (error.status() >= 500) {
            log.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), error.status(), error.code());
        } else {
            log.info("{} {} -> {} {}: {}", request.getMethod(), request.getRequestURI(), error.status(), error.code(),
                    error.getMessage());
        }
        S3Xml.writeError(request, response, error);
    }

    @ExceptionHandler(ClientAbortException.class)
    public void onClientAbort(ClientAbortException error, HttpServletRequest request) {
        log.info("{} {} aborted by the client", request.getMethod(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public void onUnexpected(Exception error, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.error("{} {} failed", request.getMethod(), request.getRequestURI(), error);
        S3Xml.writeError(request, response, new S3Exception(500, "InternalError",
                "We encountered an internal error. Please try again."));
    }
}
