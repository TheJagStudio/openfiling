package gov.openfiling.redaction;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.openfiling.redaction.S3EventParser.S3Record;
import gov.openfiling.redaction.callback.CallbackClient;
import gov.openfiling.redaction.callback.CallbackPayload;
import gov.openfiling.redaction.detect.DetectionResult;
import gov.openfiling.redaction.detect.Finding;
import gov.openfiling.redaction.detect.FindingMerger;
import gov.openfiling.redaction.detect.SensitiveDataDetector;
import gov.openfiling.redaction.pdf.ExtractedDocument;
import gov.openfiling.redaction.pdf.PageText;
import gov.openfiling.redaction.pdf.PdfRedactor;
import gov.openfiling.redaction.pdf.PdfTextExtractor;
import gov.openfiling.redaction.storage.ObjectStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redacts one document: callback PROCESSING → GetObject → PDFBox text + glyph boxes → rules ∪ Claude → merged
 * findings → raster Public Copy → PutObject (copy + report) → callback COMPLETED, or FAILED with a short error.
 * Logs contain ids, counts and categories only — never document text or detected values.
 */
public class RedactionPipeline {

    private static final Logger log = LoggerFactory.getLogger(RedactionPipeline.class);
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    public static final String ENGINE_AI = "CLAUDE+RULES";
    public static final String ENGINE_RULES = "RULES";

    /** Result entry of the handler output. */
    public record RecordResult(String documentId, String status, Integer redactionCount, String error) {
    }

    /** A failure whose message is safe to report (short, no document content). */
    static final class RedactionFailure extends Exception {
        RedactionFailure(String message) {
            super(message);
        }
    }

    private final RedactionConfig config;
    private final ObjectStore store;
    private final SensitiveDataDetector rules;
    private final SensitiveDataDetector ai;
    private final String aiModel;
    private final CallbackClient callback;
    private final JsonMapper json;
    private final Clock clock;
    private final PdfTextExtractor extractor = new PdfTextExtractor();
    private final PdfRedactor redactor;

    public RedactionPipeline(RedactionConfig config, ObjectStore store, SensitiveDataDetector rules,
                             SensitiveDataDetector ai, String aiModel, CallbackClient callback, JsonMapper json,
                             Clock clock) {
        this.config = config;
        this.store = store;
        this.rules = rules;
        this.ai = ai;
        this.aiModel = aiModel;
        this.callback = callback;
        this.json = json;
        this.clock = clock;
        this.redactor = new PdfRedactor(config.renderDpi());
    }

    public boolean aiEnabled() {
        return ai != null;
    }

    /** Processes one S3 record; empty when the record is not a public original in the documents bucket. */
    public Optional<RecordResult> process(S3Record record) {
        if (!config.documentsBucket().equals(record.bucket()) || !record.key().startsWith("public/")) {
            log.info("Skipping s3://{}/{}: not a public original", record.bucket(), record.key());
            return Optional.empty();
        }
        if (!record.eventName().isEmpty() && !record.eventName().startsWith("ObjectCreated")) {
            log.info("Skipping event {} for s3://{}/{}", record.eventName(), record.bucket(), record.key());
            return Optional.empty();
        }
        String[] segments = record.key().split("/");
        if (segments.length != 4 || !ID.matcher(segments[1]).matches() || !ID.matcher(segments[2]).matches()) {
            log.warn("Skipping s3://{}/{}: key is not public/<applicationId>/<documentId>/<file>",
                    record.bucket(), record.key());
            return Optional.empty();
        }
        return Optional.of(redact(record, segments[1], segments[2]));
    }

    private RecordResult redact(S3Record record, String applicationId, String documentId) {
        long started = clock.millis();
        String engine = ai != null ? ENGINE_AI : ENGINE_RULES;
        String model = ai != null ? aiModel : null;
        log.info("Redacting document {} (application {}, {} bytes, engine {})", documentId, applicationId,
                record.size(), engine);
        callback.send(CallbackPayload.processing(documentId, engine, model));
        Integer pageCount = null;
        Path work = null;
        try {
            work = Files.createTempDirectory("redaction-");
            Path original = work.resolve("original.pdf");
            store.download(record.bucket(), record.key(), original, config.maxObjectBytes());
            try (PDDocument document = load(original)) {
                pageCount = document.getNumberOfPages();
                if (pageCount == 0) {
                    throw new RedactionFailure("PDF has no pages");
                }
                if (pageCount > config.maxPages()) {
                    throw new RedactionFailure("too many pages (" + pageCount + " > " + config.maxPages() + ")");
                }
                ExtractedDocument text = extractor.extract(document);
                if (text.totalChars() == 0) {
                    throw new RedactionFailure("no extractable text (scanned or image-only PDF); OCR is not supported");
                }
                List<String> notes = new ArrayList<>();
                List<Finding> all = new ArrayList<>(rules.detect(text).findings());
                if (ai != null) {
                    DetectionResult aiResult = detectWithAi(text);
                    all.addAll(aiResult.findings());
                    notes.addAll(aiResult.notes());
                    if (aiResult.degraded()) {
                        engine = ENGINE_RULES;
                        model = null;
                        notes.add("Engine reported as RULES because Claude could not analyse the whole document");
                    }
                } else {
                    notes.add("AI detection disabled (no ANTHROPIC_API_KEY or REDACTION_AI_ENABLED=false): rules only");
                }
                List<Integer> blankPages = text.pages().stream().filter(p -> p.text().isBlank())
                        .map(PageText::number).toList();
                if (!blankPages.isEmpty()) {
                    notes.add("Pages without extractable text were re-rendered unchanged (not analysed): " + blankPages);
                }
                List<Finding> findings = FindingMerger.merge(all);

                Path copy = work.resolve("public-copy.pdf");
                redactor.redact(document, text, findings, PdfRedactor.footer(findings.size()), copy);
                String prefix = "public/" + applicationId + "/" + documentId + "/";
                String copyKey = prefix + "public-copy.pdf";
                String reportKey = prefix + "redaction-report.json";
                RedactionReport report = RedactionReport.of(documentId, engine, model, pageCount, findings, notes,
                        clock.instant());
                store.upload(config.publicBucket(), copyKey, copy, "application/pdf");
                store.upload(config.publicBucket(), reportKey, json.writeValueAsBytes(report), "application/json");
                long duration = clock.millis() - started;
                callback.send(new CallbackPayload(documentId, "COMPLETED", engine, model, pageCount, findings.size(),
                        report.categories(), config.publicBucket(), copyKey, reportKey, null, duration));
                log.info("Document {} redacted: {} page(s), {} redaction(s) {}, engine {}, {} ms", documentId,
                        pageCount, findings.size(), report.categories(), engine, duration);
                return new RecordResult(documentId, "COMPLETED", findings.size(), null);
            }
        } catch (RedactionFailure e) {
            return fail(documentId, engine, model, pageCount, e.getMessage(), started);
        } catch (ObjectStore.ObjectTooLargeException e) {
            return fail(documentId, engine, model, pageCount, e.getMessage(), started);
        } catch (FileNotFoundException e) {
            return fail(documentId, engine, model, pageCount, "original object not found", started);
        } catch (IOException e) {
            log.warn("Document {}: I/O failure {}", documentId, e.getClass().getName());
            return fail(documentId, engine, model, pageCount, "storage or I/O error (" + e.getClass().getSimpleName() + ")",
                    started);
        } catch (RuntimeException | OutOfMemoryError e) {
            log.error("Document {}: unexpected failure {}", documentId, e.getClass().getName());
            return fail(documentId, engine, model, pageCount, "internal error (" + e.getClass().getSimpleName() + ")",
                    started);
        } finally {
            deleteQuietly(work);
        }
    }

    private DetectionResult detectWithAi(ExtractedDocument text) {
        try {
            return ai.detect(text);
        } catch (RuntimeException e) {
            log.warn("Claude detector failed: {}", e.getClass().getName());
            return new DetectionResult(List.of(), List.of("Claude detector failed (" + e.getClass().getSimpleName()
                    + "); rules only"), true);
        }
    }

    private static PDDocument load(Path file) throws RedactionFailure {
        PDDocument document;
        try {
            document = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache());
        } catch (InvalidPasswordException e) {
            throw new RedactionFailure("PDF is encrypted (password protected)");
        } catch (IOException e) {
            throw new RedactionFailure("PDF could not be parsed (malformed or corrupt)");
        }
        if (document.isEncrypted()) {
            try {
                document.close();
            } catch (IOException ignored) {
                // closing a document we reject anyway
            }
            throw new RedactionFailure("PDF is encrypted; encrypted PDFs are not supported");
        }
        return document;
    }

    private RecordResult fail(String documentId, String engine, String model, Integer pageCount, String error,
                              long started) {
        long duration = clock.millis() - started;
        log.warn("Document {} redaction FAILED: {}", documentId, error);
        callback.send(CallbackPayload.failed(documentId, engine, model, pageCount, error, duration));
        return new RecordResult(documentId, "FAILED", null, error);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp files are best effort
        }
    }
}
