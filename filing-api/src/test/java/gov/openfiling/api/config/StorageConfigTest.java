package gov.openfiling.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import gov.openfiling.api.storage.DocumentStorage;
import gov.openfiling.api.storage.S3DocumentStorage;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Pre-signing is purely local (no network), so the real AWS SDK v2 clients are exercised here. */
class StorageConfigTest {

    private static AppProperties.Storage props(String endpoint, String publicEndpoint, String accessKey) {
        return new AppProperties.Storage(endpoint, publicEndpoint, "us-east-1", accessKey, "openfiling-local-secret", true,
                "openfiling-documents", "openfiling-public-copies", Duration.ofMinutes(15), Duration.ofMinutes(5), 524288000L, 60,
                Duration.ofHours(1));
    }

    @Test
    void presignUpload_localEmulator_pathStyleUrlSigningContentTypeWithoutChecksums() {
        StorageConfig config = new StorageConfig();
        AppProperties.Storage p = props("http://s3-emulator:9000", "http://localhost:9000", "openfiling-local");
        try (S3Client client = config.s3Client(p); S3Presigner presigner = config.s3Presigner(p)) {
            DocumentStorage storage = config.documentStorage(client, presigner, Clock.systemUTC());

            DocumentStorage.PresignedUrl put = storage.presignUpload("openfiling-documents", "public/a/b/my spec.pdf",
                    "application/pdf", Duration.ofMinutes(15));

            URI uri = URI.create(put.url());
            assertThat(uri.getScheme() + "://" + uri.getAuthority()).isEqualTo("http://localhost:9000");
            assertThat(uri.getRawPath()).isEqualTo("/openfiling-documents/public/a/b/my%20spec.pdf");
            String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256", "X-Amz-Expires=900", "X-Amz-SignedHeaders=content-type;host",
                    "X-Amz-Credential=openfiling-local/");
            assertThat(query).doesNotContainIgnoringCase("checksum");
            assertThat(put.method()).isEqualTo("PUT");
            assertThat(put.headers()).containsExactly(java.util.Map.entry("Content-Type", "application/pdf"));
            assertThat(put.toString()).doesNotContain("X-Amz-Signature");
        }
    }

    @Test
    void presignDownload_setsResponseContentDispositionAndType() {
        StorageConfig config = new StorageConfig();
        AppProperties.Storage p = props("http://localhost:9000", "", "openfiling-local");
        try (S3Client client = config.s3Client(p); S3Presigner presigner = config.s3Presigner(p)) {
            DocumentStorage storage = new S3DocumentStorage(client, presigner, Clock.systemUTC());

            DocumentStorage.PresignedUrl get = storage.presignDownload("openfiling-public-copies", "public/a/b/public-copy.pdf",
                    "public-copy-Spec \"v2\".pdf", "application/pdf", Duration.ofMinutes(5));

            String query = URLDecoder.decode(URI.create(get.url()).getRawQuery(), StandardCharsets.UTF_8);
            assertThat(get.url()).startsWith("http://localhost:9000/openfiling-public-copies/public/a/b/public-copy.pdf?");
            assertThat(query).contains("response-content-disposition=attachment; filename=\"public-copy-Spec 'v2'.pdf\"",
                    "response-content-type=application/pdf", "X-Amz-Expires=300");
            assertThat(get.expiresAt()).isNotNull();
        }
    }

    @Test
    void credentials_blankKeys_fallBackToDefaultChain() {
        assertThat(StorageConfig.credentials(props("", "", ""))).isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(StorageConfig.credentials(props("", "", "key"))).isInstanceOf(StaticCredentialsProvider.class);
    }

    @Test
    void storageProperties_toStringMasksSecretAndResolvesPresignEndpoint() {
        AppProperties.Storage p = props("http://s3-emulator:9000", "", "openfiling-local");

        assertThat(p.toString()).doesNotContain("openfiling-local-secret").contains("secretKey=***");
        assertThat(p.presignEndpoint()).isEqualTo("http://s3-emulator:9000");
        assertThat(props("", "", "k").hasEndpointOverride()).isFalse();
        assertThat(new AppProperties.Security("secret-token", 5, Duration.ofMinutes(15), 20).toString()).doesNotContain("secret-token");
    }
}
