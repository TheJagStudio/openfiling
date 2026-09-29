package gov.openfiling.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import gov.openfiling.redaction.callback.CallbackClient;
import gov.openfiling.redaction.callback.CallbackPayload;
import gov.openfiling.redaction.detect.Category;
import gov.openfiling.redaction.detect.Finding;
import gov.openfiling.redaction.detect.Source;
import tools.jackson.databind.json.JsonMapper;

/** Config, event parsing, report and callback client. */
class SupportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void configDefaultsFollowTheContract() {
        RedactionConfig config = RedactionConfig.fromEnv(Map.of());
        assertThat(config.documentsBucket()).isEqualTo("openfiling-documents");
        assertThat(config.publicBucket()).isEqualTo("openfiling-public-copies");
        assertThat(config.s3Endpoint()).isNull();
        assertThat(config.s3Region()).isEqualTo("us-east-1");
        assertThat(config.s3PathStyle()).isTrue();
        assertThat(config.claudeModel()).isEqualTo("claude-opus-5-5");
        assertThat(config.claudeEffort()).isEqualTo("medium");
        assertThat(config.callbackUrl()).isEqualTo("http://localhost:8080/internal/v1/redactions/events");
        assertThat(config.internalToken()).isEqualTo("dev-internal-token-change-me");
        assertThat(config.renderDpi()).isEqualTo(150);
        assertThat(config.maxPages()).isEqualTo(500);
        assertThat(config.maxChunkChars()).isEqualTo(60_000);
        assertThat(config.aiActive()).isFalse();
        assertThat(config.claudeFallbacks()).isTrue();
    }

    @Test
    void configOverridesAndSecretFreeToString() {
        RedactionConfig config = RedactionConfig.fromEnv(Map.of(
                "S3_ENDPOINT", "http://localhost:9000", "ANTHROPIC_API_KEY", "sk-ant-secret-value",
                "S3_SECRET_KEY", "s3-secret-value", "S3_PATH_STYLE", "false", "CLAUDE_FALLBACKS", "false",
                "RENDER_DPI", "200", "CALLBACK_URL", " "));
        assertThat(config.s3Endpoint()).isEqualTo("http://localhost:9000");
        assertThat(config.aiActive()).isTrue();
        assertThat(config.s3PathStyle()).isFalse();
        assertThat(config.claudeFallbacks()).isFalse();
        assertThat(config.renderDpi()).isEqualTo(200);
        assertThat(config.callbackUrl()).isEqualTo("http://localhost:8080/internal/v1/redactions/events");
        assertThat(config.toString()).doesNotContain("sk-ant-secret-value").doesNotContain("s3-secret-value").contains("ai=true");
        assertThat(RedactionConfig.fromEnv(Map.of("ANTHROPIC_API_KEY", "k", "REDACTION_AI_ENABLED", "false")).aiActive()).isFalse();
    }

    @Test
    void parsesS3EventsAndDecodesKeys() {
        String event = """
                {"Records":[
                  {"eventName":"ObjectCreated:Put","s3":{"bucket":{"name":"b"},"object":{"key":"public/a/b/my+spec%2Bv1.pdf","size":42}}},
                  {"s3":{"bucket":{"name":"b"}}}
                ]}""";
        List<S3EventParser.S3Record> records = new S3EventParser(JSON).parse(event.getBytes());
        assertThat(records).containsExactly(new S3EventParser.S3Record("ObjectCreated:Put", "b", "public/a/b/my spec+v1.pdf", 42));
    }

    @Test
    void reportCountsCategoriesInEnumOrder() {
        List<Finding> findings = List.of(
                new Finding(1, 0, 11, Category.SSN, Source.RULES, "ssn"),
                new Finding(1, 20, 30, Category.PERSON_NAME, Source.CLAUDE, "name"),
                new Finding(2, 0, 11, Category.SSN, Source.RULES, "ssn"));
        RedactionReport report = RedactionReport.of("d", "RULES", null, 2, findings, List.of("n"), Instant.parse("2026-09-29T12:00:00Z"));
        assertThat(report.categories()).containsExactly(new CallbackPayload.CategoryCount("PERSON_NAME", 1),
                new CallbackPayload.CategoryCount("SSN", 2));
        assertThat(report.findings()).extracting(RedactionReport.Entry::length).containsExactly(11, 10, 11);
        assertThat(report.redactionCount()).isEqualTo(3);
        assertThat(report.generatedAt()).isEqualTo("2026-09-29T12:00:00Z");
    }

    @Test
    void callbackRetriesTransientErrorsOnly() throws Exception {
        try (CallbackStub stub = new CallbackStub()) {
            CallbackClient client = new CallbackClient(stub.url(), "tok", JSON, 3, Duration.ofMillis(5));
            stub.failFirst = 2;
            assertThat(client.send(CallbackPayload.processing("doc", "RULES", null))).isTrue();
            assertThat(stub.requests()).isEqualTo(3);
            assertThat(stub.calls.getFirst().token()).isEqualTo("tok");
            assertThat(stub.calls.getFirst().body().get("status").asString()).isEqualTo("PROCESSING");

            stub.status = 404;
            assertThat(client.send(CallbackPayload.failed("doc", "RULES", null, 1, "x", 5))).isFalse();
            assertThat(stub.requests()).isEqualTo(4); // not retried

            stub.failFirst = 100;
            assertThat(client.send(CallbackPayload.processing("doc", "RULES", null))).isFalse();
            assertThat(stub.requests()).isEqualTo(7);
        }
        assertThat(new CallbackClient(null, "t", JSON, 3, Duration.ofMillis(5)).send(CallbackPayload.processing("d", "RULES", null))).isFalse();
        assertThat(new CallbackClient("http://127.0.0.1:1/x", "t", JSON, 2, Duration.ofMillis(5))
                .send(CallbackPayload.processing("d", "RULES", null))).isFalse();
    }
}
