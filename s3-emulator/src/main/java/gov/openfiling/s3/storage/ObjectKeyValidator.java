package gov.openfiling.s3.storage;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import gov.openfiling.s3.S3Exception;

/**
 * Validates bucket names (S3 DNS-compatible rules) and object keys. Keys must be 1–1024 UTF-8 bytes, must not
 * start with {@code /}, contain a backslash, control characters, empty / {@code .} / {@code ..} segments, and must
 * not end with {@value #SIDECAR_SUFFIX} (reserved for the metadata sidecar files).
 */
public final class ObjectKeyValidator {

    public static final String SIDECAR_SUFFIX = ".meta.json";
    private static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");

    private ObjectKeyValidator() {
    }

    public static boolean isValidBucket(String bucket) {
        return bucket != null && BUCKET.matcher(bucket).matches() && !bucket.contains("..");
    }

    public static void validateBucket(String bucket) {
        if (!isValidBucket(bucket)) {
            throw new S3Exception(400, "InvalidBucketName", "The specified bucket is not valid.");
        }
    }

    public static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw keyError("Object key must not be empty");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new S3Exception(400, "KeyTooLongError", "Your key is too long");
        }
        if (key.startsWith("/")) {
            throw keyError("Object key must not start with '/'");
        }
        if (key.indexOf('\\') >= 0) {
            throw keyError("Object key must not contain a backslash");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw keyError("Object key must not contain control characters");
            }
        }
        if (key.endsWith(SIDECAR_SUFFIX)) {
            throw keyError("Object keys ending in '" + SIDECAR_SUFFIX + "' are reserved");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() && !key.endsWith("/")) {
                throw keyError("Object key must not contain empty path segments");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw keyError("Object key must not contain '.' or '..' segments");
            }
        }
        if (key.endsWith("/")) {
            throw keyError("Folder placeholder keys (ending in '/') are not supported");
        }
    }

    private static S3Exception keyError(String message) {
        return new S3Exception(400, "InvalidArgument", message);
    }
}
