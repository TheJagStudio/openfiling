package gov.openfiling.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import gov.openfiling.redaction.callback.CallbackClient;
import gov.openfiling.redaction.detect.DetectionResult;
import gov.openfiling.redaction.detect.RuleBasedDetector;
import gov.openfiling.redaction.detect.SensitiveDataDetector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RedactionHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DOCS = "openfiling-documents";
    private static final String COPIES = "openfiling-public-copies";
    private static final String SSN = "078-05-1120";
    private static final String EMAIL = "jane.inventor@example.com";

    private CallbackStub stub;
    private InMemoryObjectStore store;

    @BeforeEach
    void setUp() throws Exception {
        stub = new CallbackStub();
        store = new InMemoryObjectStore();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private RedactionConfig config(int maxPages) {
        Map<String, String> env = new HashMap<>();
        env.put("CALLBACK_URL", stub.url());
        env.put("INTERNAL_TOKEN", "test-token");
        env.put("RENDER_DPI", "72");
        env.put("MAX_PAGES", String.valueOf(maxPages));
        return RedactionConfig.fromEnv(env);
    }

    private RedactionHandler handler(RedactionConfig config, SensitiveDataDetector ai) {
        CallbackClient callback = new CallbackClient(config.callbackUrl(), config.internalToken(), JSON, 3, Duration.ofMillis(10));
        return new RedactionHandler(new RedactionPipeline(config, store, new RuleBasedDetector(), ai,
                ai == null ? null : "claude-opus-5-5", callback, JSON, Clock.systemUTC()));
    }

    static byte[] event(String bucket, String key) {
        String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("%2F", "/");
        return ("{\"Records\":[{\"eventVersion\":\"2.1\",\"eventSource\":\"aws:s3\",\"eventName\":\"ObjectCreated:Put\","
                + "\"s3\":{\"bucket\":{\"name\":\"" + bucket + "\"},\"object\":{\"key\":\"" + encoded + "\",\"size\":10}}}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] specimen() throws Exception {
        return TestPdfs.pdf(List.of(
                List.of("CUI//SP-EXPT", "Inventor contact: " + EMAIL, "SSN: " + SSN),
                List.of("DETAILED DESCRIPTION", "A widget comprising a sprocket.")));
    }

    @Test
    void redactsAPublicOriginalAndReportsProgress() throws Exception {
        String app = UUID.randomUUID().toString();
        String doc = UUID.randomUUID().toString();
        String key = "public/" + app + "/" + doc + "/spec with space.pdf";
        store.put(DOCS, key, specimen());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        handler(config(500), null).handleRequest(new ByteArrayInputStream(event(DOCS, key)), out, null);
        JsonNode output = JSON.readTree(out.toByteArray());
        assertThat(output.get("processed").asInt()).isEqualTo(1);
        JsonNode result = output.get("results").get(0);
        assertThat(result.get("documentId").asString()).isEqualTo(doc);
        assertThat(result.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(result.get("redactionCount").asInt()).isEqualTo(3);

        // callbacks: PROCESSING then COMPLETED, with the internal token
        assertThat(stub.calls).hasSize(2);
        assertThat(stub.calls).extracting(CallbackStub.Call::token).containsOnly("test-token");
        JsonNode processing = stub.calls.get(0).body();
        assertThat(processing.get("status").asString()).isEqualTo("PROCESSING");
        assertThat(processing.get("documentId").asString()).isEqualTo(doc);
        assertThat(processing.get("engine").asString()).isEqualTo("RULES");
        JsonNode completed = stub.calls.get(1).body();
        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.get("engine").asString()).isEqualTo("RULES");
        assertThat(completed.get("model").isNull()).isTrue();
        assertThat(completed.get("pageCount").asInt()).isEqualTo(2);
        assertThat(completed.get("redactionCount").asInt()).isEqualTo(3);
        assertThat(completed.get("publicCopyBucket").asString()).isEqualTo(COPIES);
        assertThat(completed.get("publicCopyKey").asString()).isEqualTo("public/" + app + "/" + doc + "/public-copy.pdf");
        assertThat(completed.get("reportKey").asString()).isEqualTo("public/" + app + "/" + doc + "/redaction-report.json");
        assertThat(completed.get("durationMs").asLong()).isNotNegative();
        assertThat(completed.get("categories").toString())
                .contains("{\"category\":\"EMAIL\",\"count\":1}", "{\"category\":\"SSN\",\"count\":1}",
                        "{\"category\":\"CUI_MARKING\",\"count\":1}");

        // public copy: same page count, no sensitive or original text
        InMemoryObjectStore.Stored copy = store.get(COPIES, "public/" + app + "/" + doc + "/public-copy.pdf");
        assertThat(copy.contentType()).isEqualTo("application/pdf");
        try (PDDocument pdf = Loader.loadPDF(copy.content())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).doesNotContain(SSN).doesNotContain(EMAIL).doesNotContain("sprocket")
                    .contains("PUBLIC COPY");
        }

        // report: categories and lengths, never values
        InMemoryObjectStore.Stored reportObject = store.get(COPIES, "public/" + app + "/" + doc + "/redaction-report.json");
        assertThat(reportObject.contentType()).isEqualTo("application/json");
        String reportJson = new String(reportObject.content(), StandardCharsets.UTF_8);
        assertThat(reportJson).doesNotContain(SSN).doesNotContain(EMAIL).doesNotContain("jane").doesNotContain("SP-EXPT");
        JsonNode report = JSON.readTree(reportJson);
        assertThat(report.get("documentId").asString()).isEqualTo(doc);
        assertThat(report.get("engine").asString()).isEqualTo("RULES");
        assertThat(report.get("pageCount").asInt()).isEqualTo(2);
        assertThat(report.get("redactionCount").asInt()).isEqualTo(3);
        assertThat(report.get("findings")).hasSize(3);
        JsonNode first = report.get("findings").get(0);
        assertThat(first.get("page").asInt()).isEqualTo(1);
        assertThat(first.get("source").asString()).isEqualTo("RULES");
        assertThat(first.has("length")).isTrue();
        assertThat(first.has("reason")).isTrue();
        assertThat(report.get("generatedAt").asString()).isNotBlank();
        assertThat(report.get("notes").toString()).contains("rules only");
    }

    @Test
    void skipsRecordsThatAreNotPublicOriginals() throws Exception {
        RedactionHandler handler = handler(config(500), null);
        assertThat(handler.handle(event(DOCS, "restricted/a/b/oath.pdf")).processed()).isZero();
        assertThat(handler.handle(event(COPIES, "public/a/b/public-copy.pdf")).processed()).isZero();
        assertThat(handler.handle(event(DOCS, "public/not-enough-segments.pdf")).processed()).isZero();
        assertThat(handler.handle(event(DOCS, "public/a/b/c/d.pdf")).processed()).isZero();
        assertThat(handler.handle(event(DOCS, "public/a b/c/d.pdf")).processed()).isZero();
        byte[] removed = new String(event(DOCS, "public/a/b/c.pdf"), StandardCharsets.UTF_8)
                .replace("ObjectCreated:Put", "ObjectRemoved:Delete").getBytes(StandardCharsets.UTF_8);
        assertThat(handler.handle(removed).processed()).isZero();
        assertThat(stub.calls).isEmpty();
        assertThat(handler.aiEnabled()).isFalse();
    }

    @Test
    void brokenMissingEncryptedAndOversizedPdfsFail() throws Exception {
        RedactionHandler handler = handler(config(1), null);
        store.put(DOCS, "public/a/broken/x.pdf", "%PDF-1.7 this is not really a pdf".getBytes(StandardCharsets.UTF_8));
        store.put(DOCS, "public/a/encrypted/x.pdf", TestPdfs.encrypted(List.of(List.of("secret"))));
        store.put(DOCS, "public/a/toolong/x.pdf", TestPdfs.pdf(List.of(List.of("one"), List.of("two"))));
        store.put(DOCS, "public/a/blank/x.pdf", TestPdfs.pdf(List.of(List.of())));

        assertFailed(handler, "public/a/broken/x.pdf", "broken", "PDF could not be parsed (malformed or corrupt)");
        assertFailed(handler, "public/a/encrypted/x.pdf", "encrypted", "PDF is encrypted (password protected)");
        assertFailed(handler, "public/a/toolong/x.pdf", "toolong", "too many pages (2 > 1)");
        assertFailed(handler, "public/a/missing/x.pdf", "missing", "original object not found");
        assertFailed(handler, "public/a/blank/x.pdf", "blank", "no extractable text (scanned or image-only PDF); OCR is not supported");

        Map<String, String> env = new HashMap<>();
        env.put("CALLBACK_URL", stub.url());
        env.put("MAX_OBJECT_BYTES", "10");
        RedactionHandler tiny = handler(RedactionConfig.fromEnv(env), null);
        store.put(DOCS, "public/a/big/x.pdf", specimen());
        assertFailed(tiny, "public/a/big/x.pdf", "big", null);
        assertThat(stub.calls.getLast().body().get("error").asString()).startsWith("object is too large");
    }

    private void assertFailed(RedactionHandler handler, String key, String documentId, String error) {
        stub.calls.clear();
        RedactionHandler.Output output = handler.handle(event(DOCS, key));
        assertThat(output.processed()).isEqualTo(1);
        assertThat(output.results().getFirst().status()).isEqualTo("FAILED");
        assertThat(output.results().getFirst().documentId()).isEqualTo(documentId);
        assertThat(stub.calls).extracting(c -> c.body().get("status").asString()).containsExactly("PROCESSING", "FAILED");
        if (error != null) {
            assertThat(output.results().getFirst().error()).isEqualTo(error);
            assertThat(stub.calls.getLast().body().get("error").asString()).isEqualTo(error);
        }
        assertThat(store.get(COPIES, "public/a/" + documentId + "/public-copy.pdf")).isNull();
    }

    @Test
    void aiFindingsAreMergedAndDegradationDowngradesTheEngine() throws Exception {
        store.put(DOCS, "public/a/doc1/x.pdf", specimen());
        SensitiveDataDetector healthyAi = document -> {
            String text = document.page(2).text();
            int start = text.indexOf("sprocket");
            return DetectionResult.of(List.of(new gov.openfiling.redaction.detect.Finding(2, start, start + 8,
                    gov.openfiling.redaction.detect.Category.OTHER_SENSITIVE, gov.openfiling.redaction.detect.Source.CLAUDE, "test")));
        };
        RedactionHandler.Output ok = handler(config(500), healthyAi).handle(event(DOCS, "public/a/doc1/x.pdf"));
        assertThat(ok.results().getFirst().redactionCount()).isEqualTo(4);
        JsonNode completed = stub.calls.getLast().body();
        assertThat(completed.get("engine").asString()).isEqualTo("CLAUDE+RULES");
        assertThat(completed.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(stub.calls.getFirst().body().get("engine").asString()).isEqualTo("CLAUDE+RULES");

        stub.calls.clear();
        SensitiveDataDetector degradedAi = document -> new DetectionResult(List.of(), List.of("Claude refused pages 1-2"), true);
        RedactionHandler.Output degraded = handler(config(500), degradedAi).handle(event(DOCS, "public/a/doc1/x.pdf"));
        assertThat(degraded.results().getFirst().redactionCount()).isEqualTo(3);
        assertThat(stub.calls.getLast().body().get("engine").asString()).isEqualTo("RULES");
        String report = new String(store.get(COPIES, "public/a/doc1/redaction-report.json").content(), StandardCharsets.UTF_8);
        assertThat(report).contains("Claude refused pages 1-2").contains("\"engine\":\"RULES\"");

        stub.calls.clear();
        SensitiveDataDetector crashingAi = document -> {
            throw new IllegalStateException("boom");
        };
        RedactionHandler.Output crashed = handler(config(500), crashingAi).handle(event(DOCS, "public/a/doc1/x.pdf"));
        assertThat(crashed.results().getFirst().status()).isEqualTo("COMPLETED");
        assertThat(stub.calls.getLast().body().get("engine").asString()).isEqualTo("RULES");
    }

    @Test
    void invalidEventsAreRejected() {
        RedactionHandler handler = handler(config(500), null);
        assertThatThrownBy(() -> handler.handle("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle("{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(handler.handle("{\"Records\":[{\"s3\":{}}]}".getBytes(StandardCharsets.UTF_8)).processed()).isZero();
    }

    @Test
    void productionWiringFromEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("S3_ENDPOINT", "http://localhost:9000");
        env.put("CALLBACK_URL", stub.url());
        RedactionPipeline rulesOnly = RedactionHandler.fromEnvironment(env);
        assertThat(rulesOnly.aiEnabled()).isFalse();

        env.put("ANTHROPIC_API_KEY", "sk-ant-test-not-used");
        env.put("CLAUDE_EFFORT", "high");
        RedactionPipeline withAi = RedactionHandler.fromEnvironment(env);
        assertThat(withAi.aiEnabled()).isTrue();

        env.put("REDACTION_AI_ENABLED", "false");
        assertThat(RedactionHandler.fromEnvironment(env).aiEnabled()).isFalse();
        env.remove("S3_ENDPOINT");
        assertThat(RedactionHandler.fromEnvironment(env).aiEnabled()).isFalse();
    }
}
