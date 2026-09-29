package gov.openfiling.redaction.storage;

import java.io.IOException;
import java.nio.file.Path;

/** The two S3 operations the function needs (a seam for tests; {@link S3ObjectStore} in production). */
public interface ObjectStore {

    /**
     * Downloads {@code bucket/key} into {@code target}.
     *
     * @return the object size in bytes
     * @throws ObjectTooLargeException when the object is larger than {@code maxBytes} (checked before downloading)
     * @throws java.io.FileNotFoundException when the object does not exist
     */
    long download(String bucket, String key, Path target, long maxBytes) throws IOException;

    /** Uploads a file. */
    void upload(String bucket, String key, Path source, String contentType) throws IOException;

    /** Uploads a small in-memory object. */
    void upload(String bucket, String key, byte[] content, String contentType) throws IOException;

    /** Thrown when an object exceeds the configured size limit. */
    class ObjectTooLargeException extends IOException {
        public ObjectTooLargeException(long size, long max) {
            super("object is too large (" + size + " bytes > " + max + ")");
        }
    }
}
