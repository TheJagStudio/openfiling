package gov.openfiling.s3.storage;

import java.util.Map;

/**
 * Parameters of a PutObject.
 *
 * @param contentLength        declared Content-Length (bytes)
 * @param contentType          Content-Type (defaults to binary/octet-stream)
 * @param userMetadata         {@code x-amz-meta-*} values
 * @param expectedSha256Hex    lower-case hex SHA-256 the body must have, or null when the payload is unsigned
 * @param expectedMd5Base64    optional Content-MD5 header value
 */
public record PutRequest(long contentLength, String contentType, Map<String, String> userMetadata,
                         String expectedSha256Hex, String expectedMd5Base64) {
}
