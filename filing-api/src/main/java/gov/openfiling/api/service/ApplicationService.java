package gov.openfiling.api.service;

import gov.openfiling.api.dto.ApplicationDetail;
import gov.openfiling.api.dto.ApplicationFields;
import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.CreateApplicationRequest;
import gov.openfiling.api.dto.FilingReceipt;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.SubmitRequest;
import gov.openfiling.api.dto.UpdateApplicationRequest;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.FieldProblem;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.InventorMapper;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.repository.spec.ApplicationSpecifications;
import gov.openfiling.api.repository.spec.SortSupport;
import gov.openfiling.api.security.Viewer;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The applicant's applications: list, create, edit, delete, submit (file), receipt. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ApplicationService {

    public static final String RESOURCE = "APPLICATION";
    static final Map<String, String> SORTS = Map.of(
            "updatedAt", "updatedAt", "createdAt", "createdAt", "filingDate", "filingDate", "title", "title", "status", "status");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApplicationRepository applications;
    private final UserRepository users;
    private final DocumentRepository documentRepository;
    private final ApplicationAccess access;
    private final ApplicationViews views;
    private final WorkflowPolicy policy;
    private final InventorMapper inventorMapper;
    private final FilingRequirements requirements;
    private final HistoryService history;
    private final NotificationService notifications;
    private final DocumentService documents;
    private final AuditService audit;
    private final Clock clock;

    /** The caller's own applications (any role). */
    public PageResponse<ApplicationSummary> list(Viewer viewer, String q, Collection<ApplicationStatus> statuses,
            ApplicationType type, PageParams paging) {
        Pageable pageable = SortSupport.pageable(paging, SORTS, "updatedAt", Sort.Direction.DESC, "id");
        Page<PatentApplication> page = applications.findAll(
                ApplicationSpecifications.matching(ApplicationSpecifications.Filter.owner(viewer.id(), q, statuses, type)), pageable);
        return PageResponse.of(page, views.summaries(page.getContent(), viewer));
    }

    @Transactional
    public ApplicationDetail create(Viewer viewer, CreateApplicationRequest request) {
        User owner = users.findById(viewer.id()).orElseThrow(() -> NotFoundException.of("User", viewer.id()));
        PatentApplication app = new PatentApplication();
        app.setId(UUID.randomUUID());
        app.setStatus(ApplicationStatus.DRAFT);
        app.setOwner(owner);
        apply(app, request);
        applications.saveAndFlush(app);
        history.record(app, StatusEvent.CREATED, null, ApplicationStatus.DRAFT, "Application created (draft)", null,
                owner, HistoryService.ROLE_INVENTOR);
        audit.record(AuditAction.APPLICATION_CREATED, viewer, RESOURCE, app.getId(),
                AuditService.details("title", app.getTitle(), "applicationType", app.getApplicationType()));
        return views.detail(app, viewer);
    }

    public ApplicationDetail get(Viewer viewer, UUID id) {
        return views.detail(access.loadVisible(id, viewer), viewer);
    }

    @Transactional
    public ApplicationDetail update(Viewer viewer, UUID id, UpdateApplicationRequest request) {
        PatentApplication app = access.loadOwned(id, viewer, "edit the application");
        if (!policy.canEdit(app, viewer)) {
            throw new ConflictException("The application can only be edited while it is DRAFT or INCOMPLETE");
        }
        checkVersion(app, request.version());
        if (app.getStatus().isFiled() && request.applicationType() != app.getApplicationType()) {
            throw BusinessRuleException.of("applicationType", "The application type cannot change after filing");
        }
        apply(app, request);
        applications.saveAndFlush(app);
        audit.record(AuditAction.APPLICATION_UPDATED, viewer, RESOURCE, app.getId(),
                AuditService.details("status", app.getStatus(), "version", app.getVersion()));
        return views.detail(app, viewer);
    }

    /** Owner, DRAFT only; removes stored objects after commit. */
    @Transactional
    public void delete(Viewer viewer, UUID id) {
        PatentApplication app = access.loadOwned(id, viewer, "delete the application");
        if (!policy.canDelete(app, viewer)) {
            throw new ConflictException("Only draft applications can be deleted");
        }
        String title = app.getTitle();
        int purged = documents.purgeForApplication(app.getId());
        applications.deleteById(app.getId());
        audit.record(AuditAction.APPLICATION_DELETED, viewer, RESOURCE, id, AuditService.details("title", title, "documents", purged));
    }

    /** DRAFT &rarr; SUBMITTED: assigns application number, confirmation number and filing date. */
    @Transactional
    public ApplicationDetail submit(Viewer viewer, UUID id, SubmitRequest request) {
        PatentApplication app = access.loadOwned(id, viewer, "submit the application");
        if (app.getStatus() != ApplicationStatus.DRAFT) {
            throw new ConflictException("The application has already been filed");
        }
        checkVersion(app, request.version());
        List<FieldProblem> problems = requirements.problems(app);
        if (!problems.isEmpty()) {
            throw new BusinessRuleException("The application is not complete", problems);
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        app.setApplicationNumber(ApplicationNumbers.applicationNumber(app.getApplicationType(), applications.nextApplicationSerial()));
        app.setConfirmationNumber(String.format("%04d", RANDOM.nextInt(10_000)));
        app.setFilingDate(today);
        app.setSubmittedAt(now);
        app.setSignature(request.signature().trim());
        app.setStatus(ApplicationStatus.SUBMITTED);
        applications.saveAndFlush(app);

        User owner = app.getOwner();
        history.record(app, StatusEvent.SUBMIT, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                "Application filed — " + ApplicationStatus.SUBMITTED.getLabel(), null, owner, HistoryService.ROLE_INVENTOR);
        String number = ApplicationNumbers.display(app.getApplicationNumber());
        notifications.notify(owner.getId(), app, StatusEvent.SUBMIT, number + ": application filed",
                "Application " + number + " was filed on " + today + " (confirmation number " + app.getConfirmationNumber() + ").");
        audit.record(AuditAction.APPLICATION_SUBMITTED, viewer, RESOURCE, app.getId(),
                AuditService.details("applicationNumber", app.getApplicationNumber()));
        log.info("Application {} filed as {}", app.getId(), app.getApplicationNumber());
        return views.detail(app, viewer);
    }

    public FilingReceipt receipt(Viewer viewer, UUID id) {
        PatentApplication app = access.loadVisible(id, viewer);
        if (!app.getStatus().isFiled()) {
            throw new ConflictException("The application has not been filed yet");
        }
        User owner = app.getOwner();
        List<FilingReceipt.ReceiptDocument> docs = documentRepository.findByApplication_IdAndStatus(app.getId(), DocumentStatus.UPLOADED)
                .stream()
                .sorted(Comparator.comparing(Document::getUploadedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(d -> new FilingReceipt.ReceiptDocument(d.getDocCode(), d.getDocCode().getLabel(), d.getFileName(),
                        d.getSizeBytes(), d.getUploadedAt()))
                .toList();
        return new FilingReceipt(
                ApplicationNumbers.display(app.getApplicationNumber()),
                app.getConfirmationNumber(),
                app.getTitle(),
                app.getApplicationType(),
                app.getEntityStatus(),
                app.getFilingDate(),
                app.getSubmittedAt(),
                app.getSignature(),
                new FilingReceipt.Applicant(owner.getId(), owner.getDisplayName(), owner.getEmail()),
                inventorMapper.toDtos(app.getInventors()),
                docs);
    }

    static void checkVersion(PatentApplication app, Long expected) {
        if (!Objects.equals(app.getVersion(), expected)) {
            throw ConflictException.staleVersion();
        }
    }

    private void apply(PatentApplication app, ApplicationFields fields) {
        app.setTitle(fields.title().trim());
        app.setApplicationType(fields.applicationType());
        app.setEntityStatus(fields.entityStatus());
        app.setAbstractText(blankToNull(fields.abstractText()));
        app.setTechnologyField(blankToNull(fields.technologyField()));
        app.setAttorneyDocketNumber(blankToNull(fields.attorneyDocketNumber()));
        app.replaceInventors(inventorMapper.toEntities(fields.inventors()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
