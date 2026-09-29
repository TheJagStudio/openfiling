package gov.openfiling.api.service;

import gov.openfiling.api.dto.CategoryCount;
import gov.openfiling.api.dto.RedactionCallbackRequest;
import gov.openfiling.api.dto.RedactionCounts;
import gov.openfiling.api.exception.BadRequestException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.RedactionJson;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.Redaction;
import gov.openfiling.api.model.RedactionStatus;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.RedactionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Redaction status callbacks from the Lambda and redaction statistics. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RedactionService {

    public static final String LAMBDA_ACTOR = "redaction-lambda";

    private final DocumentRepository documents;
    private final RedactionRepository redactions;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    /**
     * Upserts the redaction row (callbacks may arrive before {@code /complete}), notifies the owner on
     * a terminal status and audits. Never stores or logs document text.
     */
    @Transactional
    public void handleCallback(RedactionCallbackRequest callback) {
        if (callback.status() == RedactionStatus.PENDING) {
            throw new BadRequestException("status must be PROCESSING, COMPLETED or FAILED");
        }
        Document doc = documents.findWithApplicationById(callback.documentId())
                .orElseThrow(() -> NotFoundException.of("Document", callback.documentId()));
        Redaction r = redactions.findById(doc.getId()).orElseGet(() -> Redaction.pending(doc.getId()));
        Instant now = clock.instant();
        r.setStatus(callback.status());
        switch (callback.status()) {
            case PROCESSING -> {
                r.setStartedAt(now);
                r.setCompletedAt(null);
                r.setError(null);
                r.setAttempts(r.getAttempts() + 1);
            }
            case COMPLETED -> {
                r.setCompletedAt(now);
                r.setError(null);
                if (r.getStartedAt() == null) {
                    r.setStartedAt(callback.durationMs() == null ? now : now.minusMillis(callback.durationMs()));
                }
            }
            case FAILED -> {
                r.setCompletedAt(now);
                String error = callback.error() == null || callback.error().isBlank() ? "Redaction failed" : callback.error();
                r.setError(error.length() <= 1000 ? error : error.substring(0, 1000));
            }
            default -> {
                // PENDING rejected above
            }
        }
        if (callback.engine() != null) {
            r.setEngine(callback.engine());
        }
        if (callback.model() != null) {
            r.setModel(callback.model());
        }
        if (callback.pageCount() != null) {
            r.setPageCount(callback.pageCount());
        }
        if (callback.redactionCount() != null) {
            r.setRedactionCount(callback.redactionCount());
        }
        if (callback.categories() != null) {
            r.setCategories(RedactionJson.writeCategories(callback.categories()));
        }
        if (callback.publicCopyBucket() != null) {
            r.setPublicCopyBucket(callback.publicCopyBucket());
        }
        if (callback.publicCopyKey() != null) {
            r.setPublicCopyKey(callback.publicCopyKey());
        }
        if (callback.reportKey() != null) {
            r.setReportKey(callback.reportKey());
        }
        if (callback.durationMs() != null) {
            r.setDurationMs(callback.durationMs());
        }
        redactions.save(r);

        if (callback.status() == RedactionStatus.COMPLETED || callback.status() == RedactionStatus.FAILED) {
            onTerminal(doc, r, callback.categories());
        }
    }

    public RedactionCounts counts() {
        return new RedactionCounts(
                redactions.countByStatus(RedactionStatus.PENDING),
                redactions.countByStatus(RedactionStatus.PROCESSING),
                redactions.countByStatus(RedactionStatus.COMPLETED),
                redactions.countByStatus(RedactionStatus.FAILED));
    }

    private void onTerminal(Document doc, Redaction r, List<CategoryCount> categories) {
        PatentApplication app = doc.getApplication();
        boolean completed = r.getStatus() == RedactionStatus.COMPLETED;
        String what = doc.getDocCode().displayName() + " '" + doc.getFileName() + "'";
        String message = completed
                ? "Public copy of " + what + " is ready (" + (r.getRedactionCount() == null ? 0 : r.getRedactionCount()) + " redactions)."
                : "Redaction of " + what + " failed: " + r.getError();
        notifications.notify(app.getOwner().getId(), app, completed ? "REDACTION_COMPLETED" : "REDACTION_FAILED",
                NotificationService.label(app) + (completed ? ": public copy ready" : ": redaction failed"), message);
        audit.record(completed ? AuditAction.REDACTION_COMPLETED : AuditAction.REDACTION_FAILED, null, LAMBDA_ACTOR,
                DocumentService.RESOURCE, doc.getId(), AuditService.details(
                        "applicationId", app.getId(),
                        "engine", r.getEngine(),
                        "model", r.getModel(),
                        "pageCount", r.getPageCount(),
                        "redactionCount", r.getRedactionCount(),
                        "categories", categories,
                        "durationMs", r.getDurationMs(),
                        "error", completed ? null : r.getError()));
        log.info("Redaction of document {} {}", doc.getId(), completed ? "completed" : "failed");
    }
}
