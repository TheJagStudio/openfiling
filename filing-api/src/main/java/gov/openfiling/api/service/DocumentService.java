package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.CreateUploadRequest;
import gov.openfiling.api.dto.DocumentResponse;
import gov.openfiling.api.dto.DownloadResponse;
import gov.openfiling.api.dto.UploadInitResponse;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.DocumentRejectedException;
import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentSource;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.DocumentVisibility;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.Redaction;
import gov.openfiling.api.model.RedactionStatus;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.RedactionRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;
import gov.openfiling.api.storage.DocumentStorage;
import gov.openfiling.api.storage.RedactionInvoker;
import gov.openfiling.api.storage.StorageKeys;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct-to-S3 document flow: issue a pre-signed PUT, verify the uploaded object ({@code /complete}
 * or the storage event, whichever comes first), pre-signed downloads, deletion, redaction retry and
 * the stale-upload sweep.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DocumentService {

    public static final String RESOURCE = "DOCUMENT";
    public static final String COPY_ORIGINAL = "original";
    public static final String COPY_PUBLIC = "public";
    public static final String PDF = "application/pdf";
    static final int EXPIRY_BATCH = 200;

    private final DocumentRepository documents;
    private final RedactionRepository redactions;
    private final UserRepository users;
    private final ApplicationAccess access;
    private final WorkflowPolicy policy;
    private final DocumentVerifier verifier;
    private final DocumentViews views;
    private final DocumentStorage storage;
    private final RedactionInvoker redactionInvoker;
    private final HistoryService history;
    private final NotificationService notifications;
    private final AuditService audit;
    private final AppProperties.Storage props;
    private final Clock clock;

    /** Outcome of processing one storage event record. */
    public enum EventOutcome { VERIFIED, REJECTED, IGNORED }

    public List<DocumentResponse> list(Viewer viewer, UUID applicationId) {
        PatentApplication app = access.loadVisible(applicationId, viewer);
        return views.responses(documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED));
    }

    public DocumentResponse get(Viewer viewer, UUID documentId) {
        return views.response(loadVisible(documentId, viewer));
    }

    @Transactional
    public UploadInitResponse initiateUpload(Viewer viewer, UUID applicationId, CreateUploadRequest request) {
        PatentApplication app = access.loadVisible(applicationId, viewer);
        DocumentCode code = request.docCode();
        Set<DocumentCode> allowed = policy.uploadableCodes(app, viewer);
        if (!allowed.contains(code)) {
            throw new ForbiddenException("You cannot upload " + code.displayName() + " while the application is in status "
                    + app.getStatus().name());
        }
        if (request.sizeBytes() > props.maxUploadBytes()) {
            throw BusinessRuleException.of("sizeBytes", "The file exceeds the maximum size of " + props.maxUploadBytes() + " bytes");
        }
        if (documents.countByApplication_IdAndStatusNot(app.getId(), DocumentStatus.EXPIRED) >= props.maxDocumentsPerApplication()) {
            throw BusinessRuleException.of("documents",
                    "An application can hold at most " + props.maxDocumentsPerApplication() + " documents");
        }
        User uploader = users.findById(viewer.id()).orElseThrow(() -> NotFoundException.of("User", viewer.id()));
        DocumentVisibility visibility = request.visibility() != null ? request.visibility() : code.getDefaultVisibility();
        Instant now = clock.instant();

        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setApplication(app);
        doc.setDocCode(code);
        doc.setDescription(request.description() == null || request.description().isBlank() ? null : request.description().trim());
        doc.setVisibility(visibility);
        doc.setFileName(StorageKeys.cleanDisplayName(request.fileName()));
        doc.setContentType(PDF);
        doc.setSizeBytes(request.sizeBytes());
        doc.setStatus(DocumentStatus.PENDING_UPLOAD);
        doc.setSource(code.getSource());
        doc.setStorageBucket(props.documentsBucket());
        doc.setStorageKey(StorageKeys.originalKey(visibility, app.getId(), doc.getId(), request.fileName()));
        doc.setUploadedBy(uploader);
        doc.setUploadExpiresAt(now.plus(props.uploadUrlTtl()));
        documents.saveAndFlush(doc);

        DocumentStorage.PresignedUrl url = storage.presignUpload(doc.getStorageBucket(), doc.getStorageKey(), PDF, props.uploadUrlTtl());
        audit.record(AuditAction.DOCUMENT_UPLOAD_URL_ISSUED, viewer, RESOURCE, doc.getId(), AuditService.details(
                "applicationId", app.getId(), "docCode", code, "visibility", visibility, "sizeBytes", doc.getSizeBytes()));
        return new UploadInitResponse(views.response(doc, null),
                new UploadInitResponse.Upload(url.url(), "PUT", Map.of("Content-Type", PDF), url.expiresAt()));
    }

    /**
     * Verifies the uploaded object (idempotent). A failed verification commits the REJECTED state
     * (object deleted) and then answers 422.
     */
    @Transactional(noRollbackFor = DocumentRejectedException.class)
    public DocumentResponse complete(Viewer viewer, UUID documentId) {
        // Lock first so the state we check is current (the storage event may be verifying concurrently).
        Document doc = documents.lockById(documentId).orElseThrow(() -> NotFoundException.of("Document", documentId));
        if (!policy.canView(doc.getApplication(), viewer)) {
            throw NotFoundException.of("Document", documentId);
        }
        if (!viewer.id().equals(doc.uploadedById())) {
            throw new ForbiddenException("Only the user who started the upload can complete it");
        }
        switch (doc.getStatus()) {
            case UPLOADED -> {
                return views.response(doc);
            }
            case REJECTED -> throw new DocumentRejectedException(doc.getRejectionReason());
            case EXPIRED -> throw new ConflictException("The upload window expired; start a new upload");
            default -> {
                // PENDING_UPLOAD: verify below
            }
        }
        DocumentVerifier.Result result = verifier.verify(doc);
        switch (result) {
            case DocumentVerifier.Missing ignored ->
                    throw new ConflictException("The file has not been uploaded yet; PUT it to the upload URL first");
            case DocumentVerifier.Rejected rejected -> {
                markRejected(doc, rejected.reason(), viewer.id(), viewer.email());
                throw new DocumentRejectedException(rejected.reason());
            }
            case DocumentVerifier.Verified verified -> markUploaded(doc, verified.head(), viewer.id(), viewer.email());
        }
        return views.response(doc);
    }

    /** Storage event path: same verification for a PENDING_UPLOAD document with this key; idempotent. */
    @Transactional
    public EventOutcome completeFromStorageEvent(String storageKey) {
        Document doc = documents.lockByStorageKey(storageKey).orElse(null);
        if (doc == null || doc.getStatus() != DocumentStatus.PENDING_UPLOAD) {
            return EventOutcome.IGNORED;
        }
        return switch (verifier.verify(doc)) {
            case DocumentVerifier.Missing ignored -> EventOutcome.IGNORED;
            case DocumentVerifier.Rejected rejected -> {
                markRejected(doc, rejected.reason(), null, AuditService.SYSTEM);
                yield EventOutcome.REJECTED;
            }
            case DocumentVerifier.Verified verified -> {
                markUploaded(doc, verified.head(), null, AuditService.SYSTEM);
                yield EventOutcome.VERIFIED;
            }
        };
    }

    /** Pre-signed GET (original: whoever may see the application; public copy: also anyone if published). */
    @Transactional
    public DownloadResponse download(Viewer viewer, UUID documentId, String copy) {
        boolean publicCopy = COPY_PUBLIC.equalsIgnoreCase(copy);
        Document doc = documents.findWithApplicationById(documentId).orElseThrow(() -> NotFoundException.of("Document", documentId));
        PatentApplication app = doc.getApplication();
        boolean visible = policy.canView(app, viewer);
        Redaction redaction = publicCopy ? redactions.findById(doc.getId()).orElse(null) : null;
        boolean publiclyAvailable = publicCopy && app.isPublished() && doc.isPublic() && redaction != null
                && redaction.isPublicCopyAvailable();
        if (!visible && !publiclyAvailable) {
            throw NotFoundException.of("Document", documentId);
        }
        if (doc.getStatus() != DocumentStatus.UPLOADED) {
            throw new ConflictException("The document has not been uploaded");
        }
        DocumentStorage.PresignedUrl url;
        String fileName;
        if (publicCopy) {
            if (!doc.isPublic() || redaction == null || !redaction.isPublicCopyAvailable()) {
                throw new ConflictException("No public copy is available for this document yet");
            }
            fileName = "public-copy-" + doc.getFileName();
            url = storage.presignDownload(publicBucket(redaction), redaction.getPublicCopyKey(), fileName, PDF, props.downloadUrlTtl());
        } else {
            fileName = doc.getFileName();
            url = storage.presignDownload(doc.getStorageBucket(), doc.getStorageKey(), fileName, doc.getContentType(),
                    props.downloadUrlTtl());
        }
        audit.record(AuditAction.DOCUMENT_DOWNLOAD, viewer, RESOURCE, doc.getId(), AuditService.details(
                "copy", publicCopy ? COPY_PUBLIC : COPY_ORIGINAL, "applicationId", app.getId()));
        return new DownloadResponse(url.url(), url.expiresAt(), fileName);
    }

    @Transactional
    public void delete(Viewer viewer, UUID documentId) {
        Document doc = loadVisible(documentId, viewer);
        PatentApplication app = doc.getApplication();
        if (!policy.canDeleteDocument(doc, app, viewer)) {
            if (policy.isOwner(app, viewer) || viewer.id().equals(doc.uploadedById())) {
                throw new ConflictException("Uploaded documents of a filed application cannot be deleted");
            }
            throw new ForbiddenException("Only the applicant can delete documents");
        }
        List<String[]> objects = objectsOf(doc, redactions.findById(doc.getId()).orElse(null));
        redactions.deleteByDocumentIds(List.of(doc.getId()));
        documents.deleteById(doc.getId());
        deleteObjectsAfterCommit(objects);
        audit.record(AuditAction.DOCUMENT_DELETED, viewer, RESOURCE, documentId, AuditService.details(
                "applicationId", app.getId(), "docCode", doc.getDocCode(), "status", doc.getStatus()));
    }

    /** Examiner: re-invokes the redaction function for an uploaded PUBLIC document. */
    @Transactional
    public DocumentResponse retryRedaction(Viewer viewer, UUID documentId) {
        if (!viewer.isExaminer()) {
            throw new ForbiddenException("Only examiners can retry redactions");
        }
        Document doc = loadVisible(documentId, viewer);
        if (!doc.isPublic() || doc.getStatus() != DocumentStatus.UPLOADED) {
            throw new ConflictException("Only uploaded PUBLIC documents are redacted");
        }
        Redaction redaction = redactions.findById(doc.getId()).orElseGet(() -> Redaction.pending(doc.getId()));
        redaction.setStatus(RedactionStatus.PENDING);
        redaction.setError(null);
        redaction.setCompletedAt(null);
        redactions.saveAndFlush(redaction);
        // Inside the transaction: if the function cannot be reached, the reset is rolled back (503).
        redactionInvoker.invoke(doc.getStorageBucket(), doc.getStorageKey(), doc.getSizeBytes(), doc.getEtag());
        audit.record(AuditAction.REDACTION_RETRIED, viewer, RESOURCE, doc.getId(),
                AuditService.details("attempts", redaction.getAttempts()));
        return views.response(doc, redaction);
    }

    /** PENDING_UPLOAD documents older than {@code upload_expires_at + grace} become EXPIRED. */
    @Transactional
    public int expireStaleUploads() {
        Instant cutoff = clock.instant().minus(props.staleUploadGrace());
        List<Document> stale = documents.findStale(DocumentStatus.PENDING_UPLOAD, cutoff, PageRequest.of(0, EXPIRY_BATCH));
        for (Document doc : stale) {
            doc.setStatus(DocumentStatus.EXPIRED);
            deleteObjectsAfterCommit(List.<String[]>of(new String[] {doc.getStorageBucket(), doc.getStorageKey()}));
        }
        if (!stale.isEmpty()) {
            log.info("Expired {} stale upload(s)", stale.size());
        }
        return stale.size();
    }

    /** Deletes every document row of an application and, after commit, its objects. */
    @Transactional
    public int purgeForApplication(UUID applicationId) {
        List<Document> docs = documents.findByApplication_Id(applicationId);
        if (docs.isEmpty()) {
            return 0;
        }
        List<UUID> ids = docs.stream().map(Document::getId).toList();
        java.util.Map<UUID, Redaction> byId = new java.util.HashMap<>();
        redactions.findAllById(ids).forEach(r -> byId.put(r.getDocumentId(), r));
        List<String[]> objects = new ArrayList<>();
        docs.forEach(d -> objects.addAll(objectsOf(d, byId.get(d.getId()))));
        redactions.deleteByDocumentIds(ids);
        documents.deleteByApplicationId(applicationId);
        deleteObjectsAfterCommit(objects);
        return docs.size();
    }

    // ---- internals ----------------------------------------------------------------------------

    private Document loadVisible(UUID documentId, Viewer viewer) {
        Document doc = documents.findWithApplicationById(documentId).orElseThrow(() -> NotFoundException.of("Document", documentId));
        if (!policy.canView(doc.getApplication(), viewer)) {
            throw NotFoundException.of("Document", documentId);
        }
        return doc;
    }

    private void markUploaded(Document doc, DocumentStorage.ObjectHead head, UUID actorId, String actorEmail) {
        Instant now = clock.instant();
        doc.setStatus(DocumentStatus.UPLOADED);
        doc.setSizeBytes(head.contentLength());
        doc.setEtag(head.etag());
        doc.setUploadedAt(now);
        doc.setRejectionReason(null);
        if (doc.isPublic() && !redactions.existsById(doc.getId())) {
            // The redaction callback may already have created the row; otherwise start PENDING.
            redactions.save(Redaction.pending(doc.getId()));
        }
        documents.saveAndFlush(doc);

        PatentApplication app = doc.getApplication();
        User uploader = doc.getUploadedBy();
        String role = doc.getSource() == DocumentSource.EXAMINER ? HistoryService.ROLE_EXAMINER : HistoryService.ROLE_INVENTOR;
        String description = doc.getDocCode().displayName() + " uploaded: " + doc.getFileName();
        history.record(app, StatusEvent.DOCUMENT_UPLOADED, app.getStatus(), null, description, null, uploader, role);
        if (app.getStatus().isFiled()) {
            UUID recipient = app.isOwnedBy(doc.uploadedById()) ? app.examinerId() : app.getOwner().getId();
            if (recipient != null && !recipient.equals(doc.uploadedById())) {
                notifications.notify(recipient, app, StatusEvent.DOCUMENT_UPLOADED,
                        NotificationService.label(app) + ": new document", description);
            }
        }
        audit.record(AuditAction.DOCUMENT_UPLOADED, actorId, actorEmail, RESOURCE, doc.getId(), AuditService.details(
                "applicationId", app.getId(), "docCode", doc.getDocCode(), "sizeBytes", doc.getSizeBytes(),
                "via", actorId == null ? "storage-event" : "complete"));
    }

    private void markRejected(Document doc, String reason, UUID actorId, String actorEmail) {
        doc.setStatus(DocumentStatus.REJECTED);
        doc.setRejectionReason(reason.length() <= 500 ? reason : reason.substring(0, 500));
        documents.saveAndFlush(doc);
        try {
            storage.delete(doc.getStorageBucket(), doc.getStorageKey());
        } catch (RuntimeException e) {
            log.warn("Could not delete rejected object of document {}: {}", doc.getId(), e.getMessage());
        }
        audit.record(AuditAction.DOCUMENT_REJECTED, actorId, actorEmail, RESOURCE, doc.getId(),
                AuditService.details("applicationId", doc.getApplication().getId(), "reason", reason));
    }

    private List<String[]> objectsOf(Document doc, Redaction redaction) {
        List<String[]> objects = new ArrayList<>();
        objects.add(new String[] {doc.getStorageBucket(), doc.getStorageKey()});
        if (redaction != null) {
            if (redaction.getPublicCopyKey() != null) {
                objects.add(new String[] {publicBucket(redaction), redaction.getPublicCopyKey()});
            }
            if (redaction.getReportKey() != null) {
                objects.add(new String[] {publicBucket(redaction), redaction.getReportKey()});
            }
        }
        return objects;
    }

    private String publicBucket(Redaction redaction) {
        return redaction.getPublicCopyBucket() != null ? redaction.getPublicCopyBucket() : props.publicBucket();
    }

    private void deleteObjectsAfterCommit(List<String[]> objects) {
        AfterCommit.run("delete " + objects.size() + " object(s)", () -> objects.forEach(o -> storage.delete(o[0], o[1])));
    }
}
