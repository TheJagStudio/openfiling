package gov.openfiling.redaction.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;

import gov.openfiling.redaction.pdf.ExtractedDocument;
import gov.openfiling.redaction.pdf.PageText;

/**
 * Asks Claude to find PII / CUI the rules cannot recognise (names of private individuals, medical details, unlabelled
 * identifiers …). The document text is sent in page-range chunks (≤ {@code maxChunkChars} each) with
 * {@code === PAGE n ===} markers; Claude answers with structured output ({@link ClaudeFindings}). Every returned
 * finding is validated — its text must really occur on the page it names — and then located (all occurrences on that
 * page). A refusal, a truncated answer or an API error degrades only that chunk to rules-only and is noted.
 */
public class ClaudeDetector implements SensitiveDataDetector {

    private static final Logger log = LoggerFactory.getLogger(ClaudeDetector.class);

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    static final long MAX_TOKENS = 16_000L;
    static final int MAX_TEXT_LENGTH = 300;
    static final int MAX_OCCURRENCES_PER_FINDING = 50;

    /** Stable (cache-friendly) system prompt; the document text only ever goes into the user message. */
    static final String SYSTEM_PROMPT = """
            You review the extracted text of documents filed with a patent office. The office publishes a public copy \
            of each document, so you must find every span that has to be redacted from that public copy.

            Flag (exact substrings):
            - personal data of private individuals: home or street addresses, personal phone numbers, personal e-mail \
            addresses, dates of birth, Social Security / taxpayer numbers, passport, driver's licence or other \
            government ID numbers, bank account / routing / payment card numbers, medical or health information, \
            and the names of private individuals who appear only in such a personal-data context (for example an \
            emergency contact or a bank account holder);
            - Controlled Unclassified Information and similar markings: CUI banners and portion markings, \
            "FOR OFFICIAL USE ONLY", DoD distribution statements B-F, and export-control markings or legends \
            (ITAR, EAR99, ECCN codes).

            Do NOT flag: names of inventors, applicants, assignees, patent attorneys or agents and examiners; authors, \
            titles and numbers of cited patents and publications (prior art); company, university and government \
            agency names; city/state/country of residence as printed on a patent; application, patent, docket and \
            publication numbers; and any technical content (claims, measurements, part numbers, chemical formulas, \
            reference numerals, code). These are public record.

            The text is divided by lines of the form "=== PAGE n ===". For each finding return the page number n of \
            the page it appears on and the text copied verbatim exactly as it appears there — no paraphrasing, no \
            normalisation, no ellipsis — keeping it as short as possible (only the sensitive value, not its label). \
            List a value once per page even when it repeats. Keep each reason short and never repeat the sensitive \
            value inside the reason. Return an empty findings list when nothing needs redaction.""";

    private final ClaudeClient client;
    private final String model;
    private final OutputConfig.Effort effort;
    private final boolean fallbacks;
    private final int maxChunkChars;
    private final RuleBasedDetector scrubber = new RuleBasedDetector();

    public ClaudeDetector(ClaudeClient client, String model, OutputConfig.Effort effort, boolean fallbacks,
                          int maxChunkChars) {
        this.client = client;
        this.model = model;
        this.effort = effort;
        this.fallbacks = fallbacks;
        this.maxChunkChars = maxChunkChars;
    }

    public String model() {
        return model;
    }

    /** Parses {@code CLAUDE_EFFORT}; unknown values fall back to {@code medium}. */
    public static OutputConfig.Effort parseEffort(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    // ---------------------------------------------------------------- chunking

    /** A slice of the document sent in one request. */
    record Chunk(int firstPage, int lastPage, String text) {
    }

    List<Chunk> chunks(ExtractedDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int first = -1;
        int last = -1;
        for (PageText page : document.pages()) {
            if (page.text().isBlank()) {
                continue;
            }
            List<String> parts = split(page.text(), Math.max(1000, maxChunkChars - 100));
            for (String part : parts) {
                String block = "=== PAGE " + page.number() + " ===\n" + part + "\n";
                if (!current.isEmpty() && current.length() + block.length() > maxChunkChars) {
                    chunks.add(new Chunk(first, last, current.toString()));
                    current.setLength(0);
                    first = -1;
                }
                if (first < 0) {
                    first = page.number();
                }
                last = page.number();
                current.append(block);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(new Chunk(first, last, current.toString()));
        }
        return chunks;
    }

    private static List<String> split(String text, int size) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start + size / 2) {
                    end = newline + 1;
                }
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    // ---------------------------------------------------------------- request

    StructuredMessageCreateParams<ClaudeFindings> buildParams(Chunk chunk) {
        StructuredMessageCreateParams.Builder<ClaudeFindings> builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM_PROMPT)
                .outputConfig(StructuredOutputConfig.<ClaudeFindings>builder()
                        .effort(effort)
                        .format(ClaudeFindings.class)
                        .build())
                .addUserMessage("Document text, pages " + chunk.firstPage() + "-" + chunk.lastPage()
                        + ". Find every span that must be redacted from the public copy.\n\n<document>\n"
                        + chunk.text() + "</document>");
        if (fallbacks) {
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    // ---------------------------------------------------------------- detection

    @Override
    public DetectionResult detect(ExtractedDocument document) {
        List<Chunk> chunks = chunks(document);
        List<Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int degraded = 0;
        int dropped = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String label = "pages " + chunk.firstPage() + "-" + chunk.lastPage();
            ClaudeClient.Reply reply;
            try {
                reply = client.create(buildParams(chunk));
            } catch (ClaudeUnavailableException e) {
                degraded++;
                notes.add("Claude unavailable for " + label + " (" + e.getMessage() + "); rules only for these pages");
                log.warn("Claude call failed for chunk {}/{} ({}): {}", i + 1, chunks.size(), label, e.getMessage());
                continue;
            } catch (RuntimeException e) {
                degraded++;
                notes.add("Claude call failed for " + label + " (" + e.getClass().getSimpleName() + "); rules only for these pages");
                log.warn("Claude call failed for chunk {}/{} ({}): {}", i + 1, chunks.size(), label, e.getClass().getName());
                continue;
            }
            if (!"end_turn".equals(reply.stopReason()) || reply.findings() == null) {
                degraded++;
                notes.add("Claude returned no usable answer for " + label + " (stop reason: " + reply.stopReason()
                        + "); rules only for these pages");
                log.warn("Claude chunk {}/{} ({}) stopped with {}", i + 1, chunks.size(), label, reply.stopReason());
                continue;
            }
            for (ClaudeFindings.Item item : reply.findings().findings()) {
                List<Finding> located = locate(document, chunk, item);
                if (located.isEmpty()) {
                    dropped++;
                }
                findings.addAll(located);
            }
        }
        if (dropped > 0) {
            notes.add(dropped + " Claude finding(s) dropped because the text was not found on the named page");
        }
        log.info("Claude analysed {} chunk(s): {} finding span(s), {} degraded chunk(s), {} dropped",
                chunks.size(), findings.size(), degraded, dropped);
        return new DetectionResult(findings, notes, degraded > 0);
    }

    /** Validates a returned finding and maps it to every occurrence on its page (empty = invalid). */
    List<Finding> locate(ExtractedDocument document, Chunk chunk, ClaudeFindings.Item item) {
        if (item == null || item.category() == null || item.text() == null) {
            return List.of();
        }
        String text = item.text().strip();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH || item.page() < chunk.firstPage()
                || item.page() > chunk.lastPage() || item.page() > document.pageCount()) {
            return List.of();
        }
        String pageText = document.page(item.page()).text();
        String reason = sanitizeReason(item.reason(), text);
        List<Finding> result = new ArrayList<>();
        Matcher m = flexiblePattern(text).matcher(pageText);
        while (m.find() && result.size() < MAX_OCCURRENCES_PER_FINDING) {
            if (m.end() > m.start()) {
                result.add(new Finding(item.page(), m.start(), m.end(), item.category(), Source.CLAUDE, reason));
            }
        }
        return result;
    }

    /**
     * Exact text, but any run of whitespace may match any run of whitespace (line wraps in the extracted text), and
     * a value that starts/ends with a letter or digit must not continue a longer word ("Jo" never hits "Johnson").
     */
    static Pattern flexiblePattern(String text) {
        String[] tokens = text.split("\\s+");
        StringBuilder regex = new StringBuilder();
        if (Character.isLetterOrDigit(text.charAt(0))) {
            regex.append("(?<![\\p{L}\\p{N}])");
        }
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(tokens[i]));
        }
        if (Character.isLetterOrDigit(text.charAt(text.length() - 1))) {
            regex.append("(?![\\p{L}\\p{N}])");
        }
        return Pattern.compile(regex.toString());
    }

    /** The reason goes into the report: strip the value (and anything rule-detectable) and cap its length. */
    String sanitizeReason(String reason, String value) {
        if (reason == null || reason.isBlank()) {
            return "Identified by Claude";
        }
        String cleaned = reason.replace(value, "[REDACTED]").replaceAll("[\\r\\n\\t]+", " ").strip();
        cleaned = scrubber.mask(cleaned);
        return cleaned.length() > 160 ? cleaned.substring(0, 157) + "..." : cleaned;
    }
}
