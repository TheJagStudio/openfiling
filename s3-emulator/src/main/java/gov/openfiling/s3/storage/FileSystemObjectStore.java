package gov.openfiling.s3.storage;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import gov.openfiling.s3.S3Exception;
import gov.openfiling.s3.auth.SigV4;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bucket storage on the local file system: {@code <root>/<bucket>/<key>} plus a {@code <key>.meta.json} sidecar.
 * Uploads are streamed to {@code <root>/.tmp/} while MD5 (and SHA-256 when declared) are computed, verified,
 * and then atomically moved into place, so readers never observe partial objects. The object move and the
 * sidecar write happen under a per-key lock so that content and metadata always match.
 */
public class FileSystemObjectStore {

    static final String TMP_DIR = ".tmp";
    private static final int BUFFER = 64 * 1024;
    private static final int LOCK_STRIPES = 64;
    private static final HexFormat HEX = HexFormat.of();

    private final Path root;
    private final Path tmpDir;
    private final Set<String> buckets;
    private final long maxObjectBytes;
    private final JsonMapper json;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public FileSystemObjectStore(Path root, List<String> buckets, long maxObjectBytes, JsonMapper json, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.tmpDir = this.root.resolve(TMP_DIR);
        this.buckets = new LinkedHashSet<>(buckets);
        this.maxObjectBytes = maxObjectBytes;
        this.json = json;
        this.clock = clock;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        buckets.forEach(ObjectKeyValidator::validateBucket);
    }

    /** Creates the storage root, the temp folder and every configured bucket folder. */
    public void initialize() {
        try {
            Files.createDirectories(tmpDir);
            for (String bucket : buckets) {
                Files.createDirectories(root.resolve(bucket));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialise storage root " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    public List<String> bucketNames() {
        return List.copyOf(buckets);
    }

    public long maxObjectBytes() {
        return maxObjectBytes;
    }

    public boolean bucketExists(String bucket) {
        return ObjectKeyValidator.isValidBucket(bucket) && buckets.contains(bucket)
                && Files.isDirectory(root.resolve(bucket));
    }

    public void requireBucket(String bucket) {
        ObjectKeyValidator.validateBucket(bucket);
        if (!bucketExists(bucket)) {
            throw S3Exception.noSuchBucket();
        }
    }

    // ---------------------------------------------------------------- PutObject

    public ObjectMeta put(String bucket, String key, InputStream body, PutRequest request) throws IOException {
        requireBucket(bucket);
        Path target = resolve(bucket, key);
        if (request.contentLength() > maxObjectBytes) {
            throw entityTooLarge();
        }
        Files.createDirectories(tmpDir);
        Path temp = tmpDir.resolve(UUID.randomUUID() + ".part");
        MessageDigest md5 = digest("MD5");
        MessageDigest sha256 = request.expectedSha256Hex() != null ? SigV4.sha256() : null;
        long total = 0;
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp), BUFFER)) {
                byte[] buffer = new byte[BUFFER];
                int n;
                while ((n = body.read(buffer)) != -1) {
                    total += n;
                    if (total > maxObjectBytes) {
                        throw entityTooLarge();
                    }
                    md5.update(buffer, 0, n);
                    if (sha256 != null) {
                        sha256.update(buffer, 0, n);
                    }
                    out.write(buffer, 0, n);
                }
            }
            if (request.contentLength() >= 0 && total != request.contentLength()) {
                throw new S3Exception(400, "IncompleteBody",
                        "You did not provide the number of bytes specified by the Content-Length HTTP header");
            }
            byte[] md5Bytes = md5.digest();
            if (sha256 != null && !MessageDigest.isEqual(HEX.formatHex(sha256.digest()).getBytes(StandardCharsets.US_ASCII),
                    request.expectedSha256Hex().getBytes(StandardCharsets.US_ASCII))) {
                throw new S3Exception(400, "XAmzContentSHA256Mismatch",
                        "The provided 'x-amz-content-sha256' header does not match what was computed.");
            }
            if (request.expectedMd5Base64() != null) {
                byte[] declared;
                try {
                    declared = Base64.getDecoder().decode(request.expectedMd5Base64().trim());
                } catch (IllegalArgumentException e) {
                    throw new S3Exception(400, "InvalidDigest", "The Content-MD5 you specified was invalid.");
                }
                if (!MessageDigest.isEqual(declared, md5Bytes)) {
                    throw new S3Exception(400, "BadDigest",
                            "The Content-MD5 you specified did not match what we received.");
                }
            }
            String contentType = request.contentType() == null || request.contentType().isBlank()
                    ? "binary/octet-stream" : request.contentType();
            ObjectMeta meta = new ObjectMeta(contentType, total, HEX.formatHex(md5Bytes),
                    Instant.now(clock).toString(), request.userMetadata());
            commit(bucket, key, target, temp, meta);
            return meta;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void commit(String bucket, String key, Path target, Path temp, ObjectMeta meta) throws IOException {
        ReentrantLock lock = lockFor(bucket, key);
        lock.lock();
        try {
            try {
                Files.createDirectories(target.getParent());
            } catch (FileAlreadyExistsException e) {
                throw keyConflict();
            }
            if (Files.isDirectory(target)) {
                throw keyConflict();
            }
            Path metaTemp = tmpDir.resolve(UUID.randomUUID() + ".meta");
            try {
                Files.write(metaTemp, json.writeValueAsBytes(meta));
                move(temp, target);
                move(metaTemp, sidecar(target));
            } finally {
                Files.deleteIfExists(metaTemp);
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- Get / Head

    /** Metadata of an object, or empty when it does not exist. */
    public Optional<StoredObject> head(String bucket, String key) throws IOException {
        requireBucket(bucket);
        Path file = resolve(bucket, key);
        ReentrantLock lock = lockFor(bucket, key);
        lock.lock();
        try {
            return readMeta(file).map(meta -> new StoredObject(bucket, key, meta, file));
        } finally {
            lock.unlock();
        }
    }

    /** An open, consistent view (metadata + channel) of an object; the caller must close the channel. */
    public record OpenObject(StoredObject object, SeekableByteChannel channel) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    public Optional<OpenObject> open(String bucket, String key) throws IOException {
        requireBucket(bucket);
        Path file = resolve(bucket, key);
        ReentrantLock lock = lockFor(bucket, key);
        lock.lock();
        try {
            Optional<ObjectMeta> meta = readMeta(file);
            if (meta.isEmpty()) {
                return Optional.empty();
            }
            SeekableByteChannel channel;
            try {
                channel = Files.newByteChannel(file, StandardOpenOption.READ);
            } catch (NoSuchFileException e) {
                return Optional.empty();
            }
            return Optional.of(new OpenObject(new StoredObject(bucket, key, meta.get(), file), channel));
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- Delete

    /** Deletes an object (idempotent). Returns true when something was removed. */
    public boolean delete(String bucket, String key) throws IOException {
        requireBucket(bucket);
        Path file = resolve(bucket, key);
        ReentrantLock lock = lockFor(bucket, key);
        lock.lock();
        boolean deleted;
        try {
            if (Files.isDirectory(file)) {
                return false;
            }
            Files.deleteIfExists(sidecar(file));
            deleted = deleteWithRetry(file);
        } finally {
            lock.unlock();
        }
        pruneEmptyParents(bucket, file);
        return deleted;
    }

    private void pruneEmptyParents(String bucket, Path file) {
        Path bucketDir = root.resolve(bucket);
        Path parent = file.getParent();
        while (parent != null && !parent.equals(bucketDir) && parent.startsWith(bucketDir)) {
            try {
                Files.delete(parent);
            } catch (IOException e) {
                return; // not empty (or already gone): stop pruning
            }
            parent = parent.getParent();
        }
    }

    // ---------------------------------------------------------------- ListObjectsV2

    public ListPage list(String bucket, String prefix, String delimiter, String startAfter, int maxKeys)
            throws IOException {
        requireBucket(bucket);
        String effectivePrefix = prefix == null ? "" : prefix;
        Path bucketDir = root.resolve(bucket);
        Path walkRoot = bucketDir;
        int slash = effectivePrefix.lastIndexOf('/');
        if (slash > 0) {
            try {
                Path candidate = bucketDir.resolve(effectivePrefix.substring(0, slash)).normalize();
                if (!candidate.startsWith(bucketDir)) {
                    return new ListPage(List.of(), List.of(), false, null);
                }
                walkRoot = candidate;
            } catch (InvalidPathException e) {
                return new ListPage(List.of(), List.of(), false, null);
            }
        }
        TreeSet<String> keys = new TreeSet<>();
        if (Files.isDirectory(walkRoot)) {
            try (Stream<Path> files = Files.walk(walkRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(ObjectKeyValidator.SIDECAR_SUFFIX))
                        .forEach(sidecarFile -> {
                            String rel = bucketDir.relativize(sidecarFile).toString().replace('\\', '/');
                            String key = rel.substring(0, rel.length() - ObjectKeyValidator.SIDECAR_SUFFIX.length());
                            if (key.startsWith(effectivePrefix)) {
                                keys.add(key);
                            }
                        });
            }
        }
        List<StoredObject> objects = new ArrayList<>();
        Set<String> commonPrefixes = new LinkedHashSet<>();
        boolean truncated = false;
        String lastReturned = null;
        for (String key : keys) {
            if (startAfter != null && key.compareTo(startAfter) <= 0) {
                continue;
            }
            String rollup = null;
            if (delimiter != null && !delimiter.isEmpty()) {
                int idx = key.indexOf(delimiter, effectivePrefix.length());
                if (idx >= 0) {
                    rollup = key.substring(0, idx + delimiter.length());
                }
            }
            if (rollup != null && commonPrefixes.contains(rollup)) {
                continue;
            }
            if (objects.size() + commonPrefixes.size() >= maxKeys) {
                truncated = true;
                break;
            }
            if (rollup != null) {
                commonPrefixes.add(rollup);
                lastReturned = rollup + Character.MAX_VALUE;
            } else {
                Path file = bucketDir.resolve(key);
                Optional<ObjectMeta> meta = readMeta(file);
                if (meta.isEmpty()) {
                    continue;
                }
                objects.add(new StoredObject(bucket, key, meta.get(), file));
                lastReturned = key;
            }
        }
        String token = truncated && lastReturned != null
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(lastReturned.getBytes(StandardCharsets.UTF_8))
                : null;
        return new ListPage(objects, List.copyOf(commonPrefixes), truncated, token);
    }

    /** Decodes a continuation token produced by {@link #list}. */
    public static String decodeContinuationToken(String token) {
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw S3Exception.invalidArgument("The continuation token provided is incorrect");
        }
    }

    // ---------------------------------------------------------------- admin

    public List<BucketStats> stats() throws IOException {
        List<BucketStats> result = new ArrayList<>();
        for (String bucket : buckets) {
            Path bucketDir = root.resolve(bucket);
            long count = 0;
            long bytes = 0;
            if (Files.isDirectory(bucketDir)) {
                try (Stream<Path> files = Files.walk(bucketDir)) {
                    for (Path sidecarFile : (Iterable<Path>) files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(ObjectKeyValidator.SIDECAR_SUFFIX))::iterator) {
                        Optional<ObjectMeta> meta = parseMeta(sidecarFile);
                        if (meta.isPresent()) {
                            count++;
                            bytes += meta.get().contentLength();
                        }
                    }
                }
            }
            result.add(new BucketStats(bucket, count, bytes));
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    /** Resolves {@code bucket/key}, guaranteeing the result stays inside the bucket folder. */
    Path resolve(String bucket, String key) {
        ObjectKeyValidator.validateKey(key);
        Path bucketDir = root.resolve(bucket);
        Path path;
        try {
            path = bucketDir.resolve(key).normalize();
        } catch (InvalidPathException e) {
            throw S3Exception.invalidArgument("Object key contains characters the local file system cannot store");
        }
        if (!path.startsWith(bucketDir) || path.equals(bucketDir)) {
            throw S3Exception.invalidArgument("Object key escapes the bucket");
        }
        return path;
    }

    private Optional<ObjectMeta> readMeta(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return parseMeta(sidecar(file));
    }

    private Optional<ObjectMeta> parseMeta(Path sidecarFile) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(sidecarFile);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        return Optional.of(json.readValue(bytes, ObjectMeta.class));
    }

    static Path sidecar(Path object) {
        return object.resolveSibling(object.getFileName() + ObjectKeyValidator.SIDECAR_SUFFIX);
    }

    private ReentrantLock lockFor(String bucket, String key) {
        return locks[Math.floorMod((bucket + '/' + key).hashCode(), LOCK_STRIPES)];
    }

    /** Atomic replace; retried briefly because Windows refuses to replace a file another reader has open. */
    private static void move(Path from, Path to) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (DirectoryNotEmptyException e) {
                throw keyConflict();
            } catch (AccessDeniedException e) {
                if (attempt >= 20) {
                    throw e;
                }
                sleepQuietly();
            } catch (FileSystemException e) {
                if (attempt >= 20 || e instanceof NoSuchFileException) {
                    throw e;
                }
                sleepQuietly();
            }
        }
    }

    private static boolean deleteWithRetry(Path file) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.deleteIfExists(file);
            } catch (AccessDeniedException e) {
                if (attempt >= 20) {
                    throw e;
                }
                sleepQuietly();
            }
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static S3Exception keyConflict() {
        return S3Exception.invalidArgument("The object key conflicts with an existing key prefix "
                + "(a key cannot be both an object and a 'folder' in the file-system emulator)");
    }

    private S3Exception entityTooLarge() {
        return new S3Exception(400, "EntityTooLarge",
                "Your proposed upload exceeds the maximum allowed size (" + maxObjectBytes + " bytes)");
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
