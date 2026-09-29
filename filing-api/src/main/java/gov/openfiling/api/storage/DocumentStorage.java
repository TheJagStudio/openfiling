package gov.openfiling.api.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Object storage port for document files. The API never proxies file bytes: browsers PUT/GET
 * directly against pre-signed URLs; the API only inspects metadata and the first bytes.
 */
public interface DocumentStorage {

    /** Pre-signed PUT for exactly this content type (the header is part of the signature). */
    PresignedUrl presignUpload(String bucket, String key, String contentType, Duration ttl);

    /** Pre-signed GET that makes the browser download the object as {@code fileName}. */
    PresignedUrl presignDownload(String bucket, String key, String fileName, String contentType, Duration ttl);

    /** HeadObject; empty when the object does not exist. */
    Optional<ObjectHead> head(String bucket, String key);

    /** Ranged GetObject of the first {@code length} bytes (fewer when the object is shorter). */
    byte[] readPrefix(String bucket, String key, int length);

    /** DeleteObject (idempotent). */
    void delete(String bucket, String key);

    /** HeadBucket, for the admin system page. */
    BucketHealth bucketHealth(String bucket);

    record PresignedUrl(String url, String method, Map<String, String> headers, Instant expiresAt) {

        @Override
        public String toString() {
            // The URL carries a bearer-like signature; never log it.
            return "PresignedUrl[method=" + method + ", expiresAt=" + expiresAt + "]";
        }
    }

    record ObjectHead(long contentLength, String contentType, String etag, Instant lastModified) {
    }

    record BucketHealth(boolean up, String detail) {
    }
}
