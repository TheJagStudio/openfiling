package gov.openfiling.redaction.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;

import gov.openfiling.redaction.pdf.ExtractedDocument;
import gov.openfiling.redaction.pdf.PageText;

class ClaudeDetectorTest {

    /** Fake seam: records requests, answers with a scripted function (never calls the real API). */
    static final class FakeClient implements ClaudeClient {
        final List<StructuredMessageCreateParams<ClaudeFindings>> requests = new ArrayList<>();
        final Function<Integer, Reply> script;

        FakeClient(Function<Integer, Reply> script) {
            this.script = script;
        }

        @Override
        public Reply create(StructuredMessageCreateParams<ClaudeFindings> params) {
            requests.add(params);
            return script.apply(requests.size());
        }
    }

    private static PageText page(int number, String text) {
        float[] nan = new float[text.length()];
        java.util.Arrays.fill(nan, Float.NaN);
        return new PageText(number, text, nan, nan, nan, nan, 612, 792);
    }

    private static final ExtractedDocument DOC = new ExtractedDocument(List.of(
            page(1, "Inventors: Ada Lovelace, London.\nEmergency contact: Maria Gonzalez-Smith\nMaria called twice."),
            page(2, "Patient history: type 2 diabetes diagnosed 2019.\nThe rotor spins."),
            page(3, "")));

    private static ClaudeFindings.Item item(int page, String text, Category category, String reason) {
        return new ClaudeFindings.Item(page, text, category, reason);
    }

    private static ClaudeDetector detector(ClaudeClient client, int chunkChars) {
        return new ClaudeDetector(client, "claude-opus-5-5", OutputConfig.Effort.MEDIUM, true, chunkChars);
    }

    @Test
    void validatedFindingsAreLocatedOnTheirPage() {
        FakeClient client = new FakeClient(n -> new ClaudeClient.Reply("end_turn", new ClaudeFindings(List.of(
                item(1, "Maria Gonzalez-Smith", Category.PERSON_NAME, "Private emergency contact Maria Gonzalez-Smith"),
                item(2, "type 2 diabetes", Category.MEDICAL, "Health condition"),
                item(1, "type 2 diabetes", Category.MEDICAL, "wrong page -> dropped"),
                item(2, "not on the page", Category.OTHER_SENSITIVE, "hallucinated -> dropped"),
                item(9, "The rotor", Category.OTHER_SENSITIVE, "page outside chunk -> dropped"),
                item(1, "   ", Category.OTHER_SENSITIVE, "blank -> dropped"),
                item(1, "Maria", Category.PERSON_NAME, null)))));
        DetectionResult result = detector(client, 60_000).detect(DOC);

        assertThat(client.requests).hasSize(1); // both non-empty pages fit into one chunk; page 3 is blank
        assertThat(result.degraded()).isFalse();
        List<String> values = result.findings().stream()
                .map(f -> DOC.page(f.page()).text().substring(f.start(), f.end())).toList();
        assertThat(values).containsExactlyInAnyOrder("Maria Gonzalez-Smith", "type 2 diabetes", "Maria", "Maria");
        assertThat(result.findings()).extracting(Finding::source).containsOnly(Source.CLAUDE);
        assertThat(result.findings()).extracting(Finding::reason)
                .contains("Private emergency contact [REDACTED]", "Health condition", "Identified by Claude")
                .noneMatch(r -> r.contains("Gonzalez"));
        assertThat(result.notes()).containsExactly("4 Claude finding(s) dropped because the text was not found on the named page");
    }

    @Test
    void whitespaceDifferencesAndWordBoundariesAreHandled() {
        ExtractedDocument doc = new ExtractedDocument(List.of(page(1, "Contact: John\nSmith (not Johnson)")));
        FakeClient client = new FakeClient(n -> new ClaudeClient.Reply("end_turn", new ClaudeFindings(List.of(
                item(1, "John Smith", Category.PERSON_NAME, "contact"),
                item(1, "John", Category.PERSON_NAME, "contact")))));
        DetectionResult result = detector(client, 60_000).detect(doc);
        assertThat(result.findings()).extracting(f -> doc.page(1).text().substring(f.start(), f.end()))
                .containsExactly("John\nSmith", "John"); // "Johnson" is not hit
    }

    @Test
    void refusalMaxTokensAndErrorsDegradeOnlyTheirChunk() {
        // 1000-char budget and ~700-char pages -> one chunk per page (pages 1 and 2)
        String filler = "\n" + "The rotor spins in the housing.\n".repeat(20);
        ExtractedDocument bigPages = new ExtractedDocument(List.of(
                page(1, DOC.page(1).text() + filler), page(2, DOC.page(2).text() + filler)));
        FakeClient refusal = new FakeClient(n -> n == 1
                ? new ClaudeClient.Reply("refusal", null)
                : new ClaudeClient.Reply("end_turn", new ClaudeFindings(List.of(item(2, "type 2 diabetes", Category.MEDICAL, "health")))));
        DetectionResult r1 = detector(refusal, 1000).detect(bigPages);
        assertThat(refusal.requests).hasSize(2);
        assertThat(r1.degraded()).isTrue();
        assertThat(r1.findings()).hasSize(1);
        assertThat(r1.notes()).anyMatch(n -> n.contains("pages 1-1") && n.contains("refusal"));

        DetectionResult r2 = detector(new FakeClient(n -> new ClaudeClient.Reply("max_tokens", null)), 60_000).detect(DOC);
        assertThat(r2.degraded()).isTrue();
        assertThat(r2.notes()).anyMatch(n -> n.contains("max_tokens"));

        DetectionResult r3 = detector(new FakeClient(n -> {
            throw new ClaudeUnavailableException("rate limited (429)", null);
        }), 60_000).detect(DOC);
        assertThat(r3.degraded()).isTrue();
        assertThat(r3.findings()).isEmpty();
        assertThat(r3.notes()).anyMatch(n -> n.contains("rate limited (429)"));

        DetectionResult r4 = detector(new FakeClient(n -> {
            throw new IllegalStateException("boom");
        }), 60_000).detect(DOC);
        assertThat(r4.degraded()).isTrue();
        assertThat(r4.notes()).anyMatch(n -> n.contains("IllegalStateException"));

        DetectionResult r5 = detector(new FakeClient(n -> new ClaudeClient.Reply("end_turn", null)), 60_000).detect(DOC);
        assertThat(r5.degraded()).isTrue();
    }

    @Test
    void chunksRespectTheCharacterBudgetAndPageMarkers() {
        String longPage = ("line of text\n").repeat(400); // ~5200 chars
        ExtractedDocument doc = new ExtractedDocument(List.of(page(1, "short"), page(2, longPage), page(3, "tail")));
        ClaudeDetector detector = detector(new FakeClient(n -> null), 2000);
        List<ClaudeDetector.Chunk> chunks = detector.chunks(doc);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(2000));
        assertThat(chunks.getFirst().text()).startsWith("=== PAGE 1 ===\nshort\n");
        assertThat(chunks.getFirst().firstPage()).isEqualTo(1);
        assertThat(chunks.getLast().lastPage()).isEqualTo(3);
        assertThat(chunks.stream().filter(c -> c.text().contains("=== PAGE 2 ===")).count()).isGreaterThan(2);
        assertThat(String.join("", chunks.stream().map(ClaudeDetector.Chunk::text).toList()).replaceAll("=== PAGE \\d+ ===\n", "")
                .replace("\n", "")).isEqualTo("short" + longPage.replace("\n", "") + "tail");
    }

    @Test
    void requestUsesStructuredOutputEffortAndServerSideFallback() {
        ClaudeDetector detector = detector(new FakeClient(n -> null), 60_000);
        StructuredMessageCreateParams<ClaudeFindings> params = detector.buildParams(new ClaudeDetector.Chunk(1, 2, "=== PAGE 1 ===\nabc\n"));
        MessageCreateParams raw = params.rawParams();
        assertThat(raw.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(raw.maxTokens()).isEqualTo(16_000L);
        assertThat(raw.system().orElseThrow().string()).contains(ClaudeDetector.SYSTEM_PROMPT);
        OutputConfig output = raw.outputConfig().orElseThrow();
        assertThat(output.effort()).contains(OutputConfig.Effort.MEDIUM);
        assertThat(output.format()).isPresent();
        String schema = output.format().orElseThrow().schema().toString();
        assertThat(schema).contains("findings").contains("category").contains("PERSON_NAME").contains("EXPORT_CONTROLLED");
        assertThat(raw.thinking()).isEmpty();
        assertThat(raw.temperature()).isEmpty();
        assertThat(raw._additionalHeaders().values("anthropic-beta")).containsExactly(ClaudeDetector.FALLBACK_BETA);
        assertThat(raw._additionalBodyProperties()).containsEntry("fallbacks", JsonValue.from("default"));
        assertThat(raw.messages()).hasSize(1);
        assertThat(raw.messages().getFirst().content().toString()).contains("<document>").contains("abc");

        StructuredMessageCreateParams<ClaudeFindings> noFallback = new ClaudeDetector(new FakeClient(n -> null),
                "claude-opus-5-5", OutputConfig.Effort.LOW, false, 60_000).buildParams(new ClaudeDetector.Chunk(1, 1, "x"));
        assertThat(noFallback.rawParams()._additionalHeaders().values("anthropic-beta")).isEmpty();
        assertThat(noFallback.rawParams()._additionalBodyProperties()).doesNotContainKey("fallbacks");
        assertThat(detector.model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void parsesEffort() {
        assertThat(ClaudeDetector.parseEffort("low")).isEqualTo(OutputConfig.Effort.LOW);
        assertThat(ClaudeDetector.parseEffort(" HIGH ")).isEqualTo(OutputConfig.Effort.HIGH);
        assertThat(ClaudeDetector.parseEffort("xhigh")).isEqualTo(OutputConfig.Effort.XHIGH);
        assertThat(ClaudeDetector.parseEffort("max")).isEqualTo(OutputConfig.Effort.MAX);
        assertThat(ClaudeDetector.parseEffort("medium")).isEqualTo(OutputConfig.Effort.MEDIUM);
        assertThat(ClaudeDetector.parseEffort("bogus")).isEqualTo(OutputConfig.Effort.MEDIUM);
        assertThat(ClaudeDetector.parseEffort(null)).isEqualTo(OutputConfig.Effort.MEDIUM);
    }

    @Test
    void reasonsAreScrubbedAndCapped() {
        ClaudeDetector detector = detector(new FakeClient(n -> null), 60_000);
        assertThat(detector.sanitizeReason("personal mail bob@example.org of Bob", "Bob"))
                .isEqualTo("personal mail [REDACTED] of [REDACTED]");
        assertThat(detector.sanitizeReason("x".repeat(400), "y")).hasSize(160).endsWith("...");
        assertThat(detector.sanitizeReason(" ", "y")).isEqualTo("Identified by Claude");
        assertThat(new ClaudeFindings(null).findings()).isEmpty();
    }
}
