package gov.openfiling.redaction;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import gov.openfiling.redaction.storage.ObjectStore;

/** Test double for S3. */
public class InMemoryObjectStore implements ObjectStore {

    public record Stored(byte[] content, String contentType) {
    }

    public final Map<String, Stored> objects = new ConcurrentHashMap<>();

    public void put(String bucket, String key, byte[] content) {
        objects.put(bucket + "/" + key, new Stored(content, "application/pdf"));
    }

    public Stored get(String bucket, String key) {
        return objects.get(bucket + "/" + key);
    }

    @Override
    public long download(String bucket, String key, Path target, long maxBytes) throws IOException {
        Stored stored = objects.get(bucket + "/" + key);
        if (stored == null) {
            throw new FileNotFoundException("missing");
        }
        if (stored.content().length > maxBytes) {
            throw new ObjectTooLargeException(stored.content().length, maxBytes);
        }
        Files.write(target, stored.content());
        return stored.content().length;
    }

    @Override
    public void upload(String bucket, String key, Path source, String contentType) throws IOException {
        objects.put(bucket + "/" + key, new Stored(Files.readAllBytes(source), contentType));
    }

    @Override
    public void upload(String bucket, String key, byte[] content, String contentType) {
        objects.put(bucket + "/" + key, new Stored(content, contentType));
    }
}
