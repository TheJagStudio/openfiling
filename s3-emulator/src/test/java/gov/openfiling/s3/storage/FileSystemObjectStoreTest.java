package gov.openfiling.s3.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gov.openfiling.s3.S3Exception;
import gov.openfiling.s3.auth.SigV4;
import tools.jackson.databind.json.JsonMapper;

class FileSystemObjectStoreTest {

    private static final String BUCKET = "openfiling-documents";

    @TempDir
    Path root;
    FileSystemObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(root, List.of(BUCKET, "openfiling-public-copies"), 1024,
                JsonMapper.builder().build(), Clock.systemUTC());
        store.initialize();
    }

    private ObjectMeta put(String key, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return store.put(BUCKET, key, new ByteArrayInputStream(bytes),
                new PutRequest(bytes.length, "application/pdf", Map.of("a", "1"), null, null));
    }

    @Test
    void putWritesObjectAndSidecarAndReadsBack() throws Exception {
        ObjectMeta meta = put("public/a/b/doc.pdf", "%PDF-1.7 hello");
        assertThat(meta.etag()).isEqualTo(java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest("%PDF-1.7 hello".getBytes(StandardCharsets.UTF_8))));
        assertThat(meta.quotedEtag()).startsWith("\"").endsWith("\"");
        assertThat(root.resolve(BUCKET).resolve("public/a/b/doc.pdf")).hasContent("%PDF-1.7 hello");
        assertThat(root.resolve(BUCKET).resolve("public/a/b/doc.pdf.meta.json")).exists();
        try (Stream<Path> tmp = Files.list(root.resolve(".tmp"))) {
            assertThat(tmp).isEmpty();
        }

        StoredObject head = store.head(BUCKET, "public/a/b/doc.pdf").orElseThrow();
        assertThat(head.meta().contentType()).isEqualTo("application/pdf");
        assertThat(head.meta().userMetadata()).containsEntry("a", "1");
        try (FileSystemObjectStore.OpenObject open = store.open(BUCKET, "public/a/b/doc.pdf").orElseThrow()) {
            assertThat(new String(Channels.newInputStream(open.channel()).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("%PDF-1.7 hello");
        }
        assertThat(store.head(BUCKET, "public/a/b/missing.pdf")).isEmpty();
        assertThat(store.open(BUCKET, "public/a/b/missing.pdf")).isEmpty();
    }

    @Test
    void overwriteReplacesContentAndDefaultsContentType() throws Exception {
        put("k.pdf", "one");
        byte[] two = "two!".getBytes(StandardCharsets.UTF_8);
        ObjectMeta meta = store.put(BUCKET, "k.pdf", new ByteArrayInputStream(two), new PutRequest(4, null, null, null, null));
        assertThat(meta.contentType()).isEqualTo("binary/octet-stream");
        assertThat(store.head(BUCKET, "k.pdf").orElseThrow().meta().contentLength()).isEqualTo(4);
    }

    @Test
    void verifiesDeclaredSha256AndContentMd5() throws Exception {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        String sha = SigV4.sha256Hex(body);
        store.put(BUCKET, "ok", new ByteArrayInputStream(body), new PutRequest(3, "text/plain", null, sha,
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body))));

        assertCode(() -> store.put(BUCKET, "bad", new ByteArrayInputStream(body),
                new PutRequest(3, "text/plain", null, SigV4.EMPTY_SHA256, null)), "XAmzContentSHA256Mismatch");
        assertCode(() -> store.put(BUCKET, "bad", new ByteArrayInputStream(body),
                new PutRequest(3, "text/plain", null, null, Base64.getEncoder().encodeToString(new byte[16]))), "BadDigest");
        assertCode(() -> store.put(BUCKET, "bad", new ByteArrayInputStream(body),
                new PutRequest(3, "text/plain", null, null, "***")), "InvalidDigest");
        assertCode(() -> store.put(BUCKET, "bad", new ByteArrayInputStream(body),
                new PutRequest(5, "text/plain", null, null, null)), "IncompleteBody");
        assertThat(store.head(BUCKET, "bad")).isEmpty();
        try (Stream<Path> tmp = Files.list(root.resolve(".tmp"))) {
            assertThat(tmp).isEmpty();
        }
    }

    @Test
    void enforcesMaximumSize() {
        assertCode(() -> store.put(BUCKET, "big", new ByteArrayInputStream(new byte[2000]),
                new PutRequest(2000, null, null, null, null)), "EntityTooLarge");
        assertCode(() -> store.put(BUCKET, "big", new ByteArrayInputStream(new byte[2000]),
                new PutRequest(-1, null, null, null, null)), "EntityTooLarge");
    }

    @Test
    void validatesBucketsAndKeys() {
        assertCode(() -> put("../escape", "x"), "InvalidArgument");
        assertCode(() -> put("a/./b", "x"), "InvalidArgument");
        assertCode(() -> put("/leading", "x"), "InvalidArgument");
        assertCode(() -> put("a\\b", "x"), "InvalidArgument");
        assertCode(() -> put("a\u0001b", "x"), "InvalidArgument");
        assertCode(() -> put("a//b", "x"), "InvalidArgument");
        assertCode(() -> put("folder/", "x"), "InvalidArgument");
        assertCode(() -> put("doc.pdf.meta.json", "x"), "InvalidArgument");
        assertCode(() -> put("", "x"), "InvalidArgument");
        assertCode(() -> put("k".repeat(1025), "x"), "KeyTooLongError");
        assertCode(() -> store.head("missing-bucket", "k"), "NoSuchBucket");
        assertCode(() -> store.head("Bad_Bucket", "k"), "InvalidBucketName");
        assertThat(store.bucketExists("openfiling-public-copies")).isTrue();
        assertThat(store.bucketExists("nope")).isFalse();
    }

    @Test
    void keyCannotBeBothObjectAndFolder() throws Exception {
        put("a/b", "x");
        assertCode(() -> put("a/b/c", "y"), "InvalidArgument");
        put("x/y/z", "z");
        assertCode(() -> put("x/y", "y"), "InvalidArgument");
    }

    @Test
    void deleteIsIdempotentAndPrunesEmptyFolders() throws Exception {
        put("public/app/doc/file.pdf", "x");
        assertThat(store.delete(BUCKET, "public/app/doc/file.pdf")).isTrue();
        assertThat(store.delete(BUCKET, "public/app/doc/file.pdf")).isFalse();
        assertThat(root.resolve(BUCKET).resolve("public")).doesNotExist();
        assertThat(root.resolve(BUCKET)).exists();
        put("dir/file", "x");
        assertThat(store.delete(BUCKET, "dir")).isFalse();
    }

    @Test
    void listsWithPrefixDelimiterAndPagination() throws Exception {
        put("public/1/a.pdf", "a");
        put("public/1/b.pdf", "bb");
        put("public/2/c.pdf", "ccc");
        put("restricted/1/d.pdf", "dddd");
        put("top.pdf", "e");

        ListPage all = store.list(BUCKET, null, null, null, 1000);
        assertThat(all.objects()).extracting(StoredObject::key)
                .containsExactly("public/1/a.pdf", "public/1/b.pdf", "public/2/c.pdf", "restricted/1/d.pdf", "top.pdf");
        assertThat(all.truncated()).isFalse();

        ListPage prefixed = store.list(BUCKET, "public/1/", null, null, 1000);
        assertThat(prefixed.objects()).extracting(StoredObject::key).containsExactly("public/1/a.pdf", "public/1/b.pdf");

        ListPage partial = store.list(BUCKET, "public/1/b", null, null, 1000);
        assertThat(partial.objects()).extracting(StoredObject::key).containsExactly("public/1/b.pdf");

        ListPage folders = store.list(BUCKET, "", "/", null, 1000);
        assertThat(folders.commonPrefixes()).containsExactly("public/", "restricted/");
        assertThat(folders.objects()).extracting(StoredObject::key).containsExactly("top.pdf");

        ListPage page1 = store.list(BUCKET, "", null, null, 2);
        assertThat(page1.objects()).hasSize(2);
        assertThat(page1.truncated()).isTrue();
        String start = FileSystemObjectStore.decodeContinuationToken(page1.nextContinuationToken());
        ListPage page2 = store.list(BUCKET, "", null, start, 2);
        assertThat(page2.objects()).extracting(StoredObject::key).containsExactly("public/2/c.pdf", "restricted/1/d.pdf");

        ListPage foldersPaged = store.list(BUCKET, "", "/", null, 1);
        assertThat(foldersPaged.commonPrefixes()).containsExactly("public/");
        ListPage foldersNext = store.list(BUCKET, "", "/",
                FileSystemObjectStore.decodeContinuationToken(foldersPaged.nextContinuationToken()), 1);
        assertThat(foldersNext.commonPrefixes()).containsExactly("restricted/");

        assertThat(store.list(BUCKET, "nothing/here/", null, null, 10).objects()).isEmpty();
        assertThat(store.list(BUCKET, "../x/", null, null, 10).objects()).isEmpty();
        assertCode(() -> FileSystemObjectStore.decodeContinuationToken("%%%"), "InvalidArgument");
    }

    @Test
    void statsCountObjectsAndBytes() throws Exception {
        put("a", "12345");
        put("b/c", "12");
        List<BucketStats> stats = store.stats();
        assertThat(stats).containsExactly(new BucketStats(BUCKET, 2, 7), new BucketStats("openfiling-public-copies", 0, 0));
        assertThat(store.maxObjectBytes()).isEqualTo(1024);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
