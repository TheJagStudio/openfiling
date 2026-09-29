package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.DownloadResponse;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.PublicApplicationDetail;
import gov.openfiling.api.dto.PublicApplicationSummary;
import gov.openfiling.api.dto.PublicStats;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.DocumentVisibility;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.Redaction;
import gov.openfiling.api.model.RedactionStatus;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.RedactionRepository;
import gov.openfiling.api.repository.StatusEventRepository;
import gov.openfiling.api.repository.spec.ApplicationSpecifications;
import gov.openfiling.api.repository.spec.SortSupport;
import gov.openfiling.api.storage.DocumentStorage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous access: only {@code published} applications, only PUBLIC documents, and only their
 * redacted public copy &mdash; never originals, restricted documents, notes or file names.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PublicService {

    static final int SNIPPET_LENGTH = 240;
    static final Map<String, String> SORTS = Map.of(
            "publicationDate", "publicationDate", "filingDate", "filingDate", "title", "title",
            "applicationNumber", "applicationNumber", "issueDate", "issueDate");
    /** Internal bookkeeping events that are not part of the public prosecution history. */
    private static final Set<String> PRIVATE_EVENTS = Set.of(StatusEvent.CREATED, StatusEvent.DOCUMENT_UPLOADED);

    private final ApplicationRepository applications;
    private final DocumentRepository documents;
    private final RedactionRepository redactions;
    private final StatusEventRepository statusEvents;
    private final DocumentStorage storage;
    private final AuditService audit;
    private final AppProperties.Storage props;

    public PageResponse<PublicApplicationSummary> search(String q, ApplicationType type, PageParams paging) {
        Pageable pageable = SortSupport.pageable(paging, SORTS, "publicationDate", Sort.Direction.DESC, "id");
        Page<PatentApplication> page = applications.findAll(
                ApplicationSpecifications.matching(ApplicationSpecifications.Filter.published(q, type)), pageable);
        return PageResponse.of(page, PublicService::summary);
    }

    public PublicApplicationDetail detail(String applicationNumber) {
        PatentApplication app = loadPublished(applicationNumber);
        PublicApplicationSummary s = summary(app);
        List<PublicApplicationDetail.Inventor> inventors = app.getInventors().stream()
                .map(i -> new PublicApplicationDetail.Inventor(i.fullName(), i.getCity(), i.getState(), i.getCountry()))
                .toList();
        List<PublicApplicationDetail.Event> history = statusEvents.findByApplicationIdOrderByCreatedAtAscIdAsc(app.getId()).stream()
                .filter(e -> !PRIVATE_EVENTS.contains(e.getEventCode()))
                .map(e -> new PublicApplicationDetail.Event(e.getEventCode(), e.getDescription(), e.getToStatus(), e.getCreatedAt()))
                .toList();
        List<Document> publicDocs = documents.findByApplication_IdAndVisibilityAndStatusOrderByUploadedAtAsc(
                app.getId(), DocumentVisibility.PUBLIC, DocumentStatus.UPLOADED);
        Map<UUID, Redaction> byId = redactions.findAllById(publicDocs.stream().map(Document::getId).toList()).stream()
                .collect(Collectors.toMap(Redaction::getDocumentId, Function.identity()));
        List<PublicApplicationDetail.PublicDocument> docs = publicDocs.stream()
                .filter(d -> byId.containsKey(d.getId()) && byId.get(d.getId()).isPublicCopyAvailable())
                .map(d -> {
                    Redaction r = byId.get(d.getId());
                    return new PublicApplicationDetail.PublicDocument(d.getId(), d.getDocCode(), d.getDocCode().getLabel(),
                            r.getPageCount(), r.getRedactionCount(), d.getUploadedAt(), true);
                })
                .toList();
        return new PublicApplicationDetail(
                s.applicationNumber(), s.applicationNumberDisplay(), s.title(), s.applicationType(), s.status(), s.statusLabel(),
                s.statusCategory(), s.filingDate(), s.publicationNumber(), s.publicationDate(), s.patentNumber(), s.issueDate(),
                s.firstNamedInventor(), s.abstractSnippet(),
                app.getAbstractText(), app.getTechnologyField(), inventors,
                app.getExaminer() == null ? null : app.getExaminer().getDisplayName(), app.getArtUnit(), history, docs);
    }

    /** Pre-signed GET of the public copy (published + PUBLIC + COMPLETED only); audited as anonymous. */
    @Transactional
    public DownloadResponse download(UUID documentId) {
        Document doc = documents.findWithApplicationById(documentId).orElseThrow(() -> NotFoundException.of("Document", documentId));
        if (!doc.getApplication().isPublished() || !doc.isPublic() || doc.getStatus() != DocumentStatus.UPLOADED) {
            throw NotFoundException.of("Document", documentId);
        }
        Redaction redaction = redactions.findById(documentId).orElse(null);
        if (redaction == null || !redaction.isPublicCopyAvailable()) {
            throw NotFoundException.of("Document", documentId);
        }
        String fileName = "public-copy-" + doc.getFileName();
        String bucket = redaction.getPublicCopyBucket() != null ? redaction.getPublicCopyBucket() : props.publicBucket();
        DocumentStorage.PresignedUrl url = storage.presignDownload(bucket, redaction.getPublicCopyKey(), fileName,
                DocumentService.PDF, props.downloadUrlTtl());
        audit.record(AuditAction.DOCUMENT_DOWNLOAD, null, "anonymous", DocumentService.RESOURCE, doc.getId(),
                AuditService.details("copy", DocumentService.COPY_PUBLIC, "anonymous", true,
                        "applicationNumber", doc.getApplication().getApplicationNumber()));
        return new DownloadResponse(url.url(), url.expiresAt(), fileName);
    }

    public PublicStats stats() {
        return new PublicStats(
                applications.countByStatusNot(ApplicationStatus.DRAFT),
                applications.countByPublishedTrue(),
                applications.countByStatus(ApplicationStatus.ISSUED),
                redactions.countByStatus(RedactionStatus.COMPLETED),
                redactions.sumCompletedRedactions());
    }

    private PatentApplication loadPublished(String applicationNumber) {
        return ApplicationNumbers.normalize(applicationNumber)
                .flatMap(applications::findByApplicationNumber)
                .filter(PatentApplication::isPublished)
                .orElseThrow(() -> NotFoundException.of("Application", applicationNumber));
    }

    static PublicApplicationSummary summary(PatentApplication a) {
        return new PublicApplicationSummary(
                a.getApplicationNumber(),
                ApplicationNumbers.display(a.getApplicationNumber()),
                a.getTitle(),
                a.getApplicationType(),
                a.getStatus(),
                a.getStatus().getLabel(),
                a.getStatus().getCategory(),
                a.getFilingDate(),
                a.getPublicationNumber(),
                a.getPublicationDate(),
                a.getPatentNumber(),
                a.getIssueDate(),
                ApplicationViews.firstNamedInventor(a),
                snippet(a.getAbstractText()));
    }

    static String snippet(String text) {
        if (text == null) {
            return null;
        }
        String clean = text.strip().replaceAll("\\s+", " ");
        if (clean.length() <= SNIPPET_LENGTH) {
            return clean;
        }
        int cut = clean.lastIndexOf(' ', SNIPPET_LENGTH);
        return clean.substring(0, cut > SNIPPET_LENGTH / 2 ? cut : SNIPPET_LENGTH) + "…";
    }
}
