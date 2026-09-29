package gov.openfiling.s3.auth;

/**
 * Result of a successful signature check.
 *
 * @param accessKey   the caller's access key id
 * @param presigned   true for query-string (pre-signed URL) authentication
 * @param payloadHash {@code UNSIGNED-PAYLOAD} or the declared lower-case hex SHA-256 of the body
 */
public record VerifiedRequest(String accessKey, boolean presigned, String payloadHash) {

    /** True when the body must be hashed and compared against {@link #payloadHash()}. */
    public boolean payloadHashDeclared() {
        return !SigV4.UNSIGNED_PAYLOAD.equals(payloadHash);
    }
}
