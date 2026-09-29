package gov.openfiling.s3.storage;

import java.nio.file.Path;

/** An object as found on disk: key, metadata and the file that holds its bytes. */
public record StoredObject(String bucket, String key, ObjectMeta meta, Path file) {
}
