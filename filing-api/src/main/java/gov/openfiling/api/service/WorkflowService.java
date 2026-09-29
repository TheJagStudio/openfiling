package gov.openfiling.api.service;

import gov.openfiling.api.dto.ApplicationDetail;
import gov.openfiling.api.dto.WorkflowActionRequest;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.FieldProblem;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.model.WorkflowAction;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.StatusEventRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes workflow actions. Authorization and state rules come from {@link WorkflowPolicy}; this
 * class validates the request (note, attachments, version), applies the side effects, appends the
 * history event, notifies the other party and audits.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WorkflowService {

    private final ApplicationAccess access;
    private final WorkflowPolicy policy;
    private final ApplicationRepository applications;
    private final UserRepository users;
    private final DocumentRepository documents;
    private final StatusEventRepository statusEvents;
    private final FilingRequirements requirements;
    private final HistoryService history;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ApplicationViews views;
    private final Clock clock;

    @Transactional
    public ApplicationDetail perform(Viewer viewer, UUID applicationId, WorkflowActionRequest request) {
        PatentApplication app = access.loadVisible(applicationId, viewer);
        WorkflowAction action = request.action();
        policy.check(action, app, viewer);
        ApplicationService.checkVersion(app, request.version());
        if (action.isRequiresNote() && (request.note() == null || request.note().isBlank())) {
            throw BusinessRuleException.of("note", "A note is required for '" + action.getLabel() + "'");
        }
        List<Document> attachments = attachments(app, action, request.documentIds());
        boolean ownerActing = policy.isOwner(app, viewer);
        User actor = users.findById(viewer.id()).orElseThrow(() -> NotFoundException.of("User", viewer.id()));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        ApplicationStatus from = app.getStatus();
        String description = apply(app, action, request, actor, today);

        if (action.changesStatus()) {
            app.setStatus(action.getToStatus());
        }
        applications.saveAndFlush(app);

        if (!attachments.isEmpty()) {
            description += " (" + attachments.stream().map(d -> d.getDocCode().name()).distinct()
                    .collect(Collectors.joining(", ")) + " attached)";
        }
        String role = ownerActing ? HistoryService.ROLE_INVENTOR : HistoryService.examinerRole(viewer);
        history.record(app, action.name(), from, action.getToStatus(), description, request.note(), actor, role);
        notifyCounterparty(app, action, description, ownerActing, viewer.id());
        audit.record(AuditAction.WORKFLOW_ACTION, viewer, ApplicationService.RESOURCE, app.getId(), AuditService.details(
                "action", action.name(), "fromStatus", from, "toStatus", app.getStatus(),
                "documentIds", attachments.isEmpty() ? null : attachments.stream().map(Document::getId).toList()));
        log.info("Workflow action {} on application {} ({} -> {})", action, app.getId(), from, app.getStatus());
        return views.detail(app, viewer);
    }

    /** Applies action-specific side effects and returns the history description. */
    private String apply(PatentApplication app, WorkflowAction action, WorkflowActionRequest request, User actor, LocalDate today) {
        switch (action) {
            case CLAIM -> {
                app.setExaminer(actor);
                if (actor.getArtUnit() != null) {
                    app.setArtUnit(actor.getArtUnit());
                }
                return "Assigned to examiner " + actor.getDisplayName();
            }
            case RELEASE -> {
                String previous = app.getExaminer() == null ? "examiner" : app.getExaminer().getDisplayName();
                app.setExaminer(null);
                return "Released by " + previous + " to the examination queue";
            }
            case CURE_MISSING_PARTS -> {
                List<FieldProblem> problems = requirements.problems(app);
                if (!problems.isEmpty()) {
                    throw new BusinessRuleException("Required parts are still missing", problems);
                }
                return "Missing parts filed — " + ApplicationStatus.PRE_EXAM.getLabel();
            }
            case DOCKET -> {
                if (app.getExaminer() == null) {
                    app.setExaminer(actor);
                }
                String artUnit = request.artUnit() != null ? request.artUnit()
                        : app.getArtUnit() != null ? app.getArtUnit() : actor.getArtUnit();
                app.setArtUnit(artUnit);
                return ApplicationStatus.DOCKETED.getLabel() + (artUnit == null ? "" : " (art unit " + artUnit + ")");
            }
            case FILE_RESPONSE -> {
                requireResponseDocument(app);
                return ApplicationStatus.RESPONSE_FILED.getLabel();
            }
            case PUBLISH -> {
                app.setPublished(true);
                app.setPublicationNumber(ApplicationNumbers.publicationNumber(today, applications.nextPublicationSerial()));
                app.setPublicationDate(today);
                return "Pre-grant publication " + app.getPublicationNumber();
            }
            case ISSUE_PATENT -> {
                app.setPatentNumber(ApplicationNumbers.patentNumber(applications.nextPatentSerial(), app.isPublished()));
                app.setIssueDate(today);
                app.setPublished(true);
                return "Patent " + app.getPatentNumber() + " issued";
            }
            case ABANDON -> {
                return ApplicationStatus.ABANDONED.getLabel()
                        + (app.isOwnedBy(actor.getId()) ? " (express abandonment by the applicant)" : "");
            }
            default -> {
                return action.getToStatus().getLabel();
            }
        }
    }

    /** FILE_RESPONSE needs at least one RESP document uploaded after the rejection was mailed. */
    private void requireResponseDocument(PatentApplication app) {
        Instant since = statusEvents.findFirstByApplicationIdAndToStatusOrderByCreatedAtDescIdDesc(app.getId(), app.getStatus())
                .map(StatusEvent::getCreatedAt)
                .orElse(Instant.EPOCH);
        if (!documents.existsByApplication_IdAndDocCodeAndStatusAndUploadedAtAfter(
                app.getId(), DocumentCode.RESP, DocumentStatus.UPLOADED, since)) {
            throw BusinessRuleException.of("documents",
                    DocumentCode.RESP.displayName() + " must be uploaded after the rejection before filing a response");
        }
    }

    private List<Document> attachments(PatentApplication app, WorkflowAction action, List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        if (!action.acceptsDocuments()) {
            throw BusinessRuleException.of("documentIds", "'" + action.getLabel() + "' does not accept documents");
        }
        List<Document> found = documents.findAllById(new LinkedHashSet<>(documentIds));
        List<FieldProblem> problems = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(documentIds)) {
            Document doc = found.stream().filter(d -> d.getId().equals(id)).findFirst().orElse(null);
            if (doc == null || !doc.getApplication().getId().equals(app.getId())) {
                problems.add(new FieldProblem("documentIds", "Document " + id + " does not belong to this application"));
            } else if (doc.getStatus() != DocumentStatus.UPLOADED) {
                problems.add(new FieldProblem("documentIds", "Document " + id + " is not uploaded"));
            } else if (!action.getAttachableCodes().contains(doc.getDocCode())) {
                problems.add(new FieldProblem("documentIds", "Document " + id + " (" + doc.getDocCode()
                        + ") cannot be attached to '" + action.getLabel() + "'"));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleException("Invalid attachments", problems);
        }
        return found;
    }

    /** Owner &harr; assigned examiner. */
    private void notifyCounterparty(PatentApplication app, WorkflowAction action, String description, boolean ownerActing,
            UUID actorId) {
        UUID recipient = ownerActing ? app.examinerId() : app.getOwner().getId();
        if (recipient == null || recipient.equals(actorId)) {
            return;
        }
        String label = NotificationService.label(app);
        notifications.notify(recipient, app, action.name(), label + ": " + action.getLabel(),
                description + " — " + app.getTitle());
    }
}
