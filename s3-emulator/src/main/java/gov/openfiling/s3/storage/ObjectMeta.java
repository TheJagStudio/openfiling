package gov.openfiling.s3.storage;

import java.util.Map;

/**
 * Sidecar metadata stored next to every object as {@code <key>.meta.json}.
 *
 * @param contentType   Content-Type given on upload
 * @param contentLength object size in bytes
 * @param etag          lower-case hex MD5 of the content (unquoted)
 * @param lastModified  ISO-8601 instant
 * @param userMetadata  {@code x-amz-meta-*} values keyed by the lower-case suffix
 */
public record ObjectMeta(String contentType, long contentLength, String etag, String lastModified,
                         Map<String, String> userMetadata) {

    public ObjectMeta {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    public String quotedEtag() {
        return '"' + etag + '"';
    }
}
