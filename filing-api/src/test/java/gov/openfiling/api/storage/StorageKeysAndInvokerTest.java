package gov.openfiling.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.exception.UpstreamUnavailableException;
import gov.openfiling.api.model.DocumentVisibility;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class StorageKeysAndInvokerTest {

    @Test
    void originalKey_prefixByVisibilityAndSanitizedName() {
        UUID app = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID doc = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(StorageKeys.originalKey(DocumentVisibility.PUBLIC, app, doc, "Spec final.pdf"))
                .isEqualTo("public/" + app + "/" + doc + "/Spec-final.pdf");
        assertThat(StorageKeys.originalKey(DocumentVisibility.RESTRICTED, app, doc, "oath.PDF"))
                .startsWith("restricted/").endsWith("/oath.pdf");
    }

    @Test
    void sanitizeFileName_stripsPathsAccentsTraversalAndLength() {
        assertThat(StorageKeys.sanitizeFileName("..\\..\\etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(StorageKeys.sanitizeFileName("Résumé (v2)!!.pdf")).isEqualTo("Resume-v2.pdf");
        assertThat(StorageKeys.sanitizeFileName("....pdf")).isEqualTo("document.pdf");
        assertThat(StorageKeys.sanitizeFileName(null)).isEqualTo("document.pdf");
        assertThat(StorageKeys.sanitizeFileName("a".repeat(300) + ".pdf")).hasSize(124);
        assertThat(StorageKeys.cleanDisplayName("C:\\x\\My\u0007 Spec.pdf")).isEqualTo("My Spec.pdf");
        assertThat(StorageKeys.cleanDisplayName("/")).isEqualTo("document.pdf");
    }

    @Test
    void eventKeys_encodeLikeS3AndDecode() {
        String key = "public/a b/c+d/é.pdf";
        String encoded = StorageKeys.encodeForEvent(key);

        assertThat(encoded).isEqualTo("public/a+b/c%2Bd/%C3%A9.pdf");
        assertThat(StorageKeys.decodeEventKey(encoded)).isEqualTo(key);
    }

    @Test
    @SuppressWarnings("unchecked")
    void syntheticEvent_matchesS3NotificationShape() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");

        Map<String, Object> event = RedactionInvoker.syntheticEvent("openfiling-documents", "public/a/b/my spec.pdf", 42, "etag", now);

        Map<String, Object> record = ((List<Map<String, Object>>) event.get("Records")).getFirst();
        assertThat(record).containsEntry("eventName", "ObjectCreated:Put").containsEntry("eventSource", "aws:s3")
                .containsEntry("eventTime", "2026-09-29T12:00:00Z");
        Map<String, Object> s3 = (Map<String, Object>) record.get("s3");
        assertThat((Map<String, Object>) s3.get("bucket")).containsEntry("name", "openfiling-documents");
        assertThat((Map<String, Object>) s3.get("object")).containsEntry("key", "public/a/b/my+spec.pdf").containsEntry("size", 42L);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void invokeAndHealth_unreachableFunction_reportDownAndThrowUnavailable() throws Exception {
        int port = freePort();
        AppProperties.Redaction props = new AppProperties.Redaction(
                "http://127.0.0.1:" + port + "/2015-03-31/functions/openfiling-redaction/invocations",
                "http://127.0.0.1:" + port + "/health", Duration.ofMillis(500), Duration.ofMillis(500));
        AppProperties.Storage storage = new AppProperties.Storage("", "", "us-east-1", "", "", true, "openfiling-documents",
                "openfiling-public-copies", Duration.ofMinutes(15), Duration.ofMinutes(5), 1, 1, Duration.ofHours(1));
        RedactionInvoker invoker = new RedactionInvoker(RestClient.builder(), props, storage, Clock.systemUTC());

        assertThat(invoker.health().up()).isFalse();
        assertThatThrownBy(() -> invoker.invoke("openfiling-documents", "public/a/b/c.pdf", 1, "e"))
                .isInstanceOf(UpstreamUnavailableException.class);
        assertThat(invoker.documentsBucket()).isEqualTo("openfiling-documents");
    }
}
