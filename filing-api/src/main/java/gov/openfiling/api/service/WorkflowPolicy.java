package gov.openfiling.api.service;

import static gov.openfiling.api.model.ApplicationStatus.ALLOWED;
import static gov.openfiling.api.model.ApplicationStatus.DOCKETED;
import static gov.openfiling.api.model.ApplicationStatus.DRAFT;
import static gov.openfiling.api.model.ApplicationStatus.FINAL_REJECTION;
import static gov.openfiling.api.model.ApplicationStatus.INCOMPLETE;
import static gov.openfiling.api.model.ApplicationStatus.NON_FINAL_REJECTION;
import static gov.openfiling.api.model.ApplicationStatus.PRE_EXAM;
import static gov.openfiling.api.model.ApplicationStatus.RESPONSE_FILED;
import static gov.openfiling.api.model.ApplicationStatus.SUBMITTED;
import static gov.openfiling.api.model.ApplicationStatus.UNDER_EXAMINATION;

import gov.openfiling.api.dto.ActionOption;
import gov.openfiling.api.dto.ApplicationPermissions;
import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.StatusCategory;
import gov.openfiling.api.model.WorkflowAction;
import gov.openfiling.api.security.Viewer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for "who may do what, when" on an application. Pure (no I/O), so it is
 * exhaustively unit-tested, and it feeds both enforcement ({@link #check}) and the UI
 * ({@code availableActions}, {@code permissions}).
 *
 * <p>Capacities of a viewer relative to one application:
 * <ul>
 *   <li><b>owner</b> &mdash; the applicant who created it;</li>
 *   <li><b>examiner</b> &mdash; EXAMINER or ADMIN role and <em>not</em> the owner (nobody examines their own case);</li>
 *   <li><b>assigned examiner</b> &mdash; examiner capacity and either the application's examiner or an ADMIN.</li>
 * </ul>
 */
@Component
public class WorkflowPolicy {

    static final String EXAMINER_ONLY = "Only an examiner can perform this action";
    static final String ASSIGNED_ONLY = "Only the assigned examiner can perform this action";
    static final String OWNER_ONLY = "Only the applicant can perform this action";

    private static final Set<ApplicationStatus> EDITABLE = EnumSet.of(DRAFT, INCOMPLETE);
    private static final Set<DocumentStatus> DELETABLE_DOCUMENT_STATUSES =
            EnumSet.of(DocumentStatus.PENDING_UPLOAD, DocumentStatus.REJECTED, DocumentStatus.EXPIRED);

    // ---- capacities -------------------------------------------------------------------------

    public boolean isOwner(PatentApplication app, Viewer viewer) {
        return app.isOwnedBy(viewer.id());
    }

    public boolean actsAsExaminer(PatentApplication app, Viewer viewer) {
        return viewer.isExaminer() && !isOwner(app, viewer);
    }

    public boolean isAssignedExaminer(PatentApplication app, Viewer viewer) {
        return actsAsExaminer(app, viewer) && (viewer.isAdmin() || viewer.id().equals(app.examinerId()));
    }

    /** Owner sees any status; examiners/admins see everything except other users' drafts. */
    public boolean canView(PatentApplication app, Viewer viewer) {
        return isOwner(app, viewer) || (viewer.isExaminer() && app.getStatus().isFiled());
    }

    // ---- workflow actions -------------------------------------------------------------------

    public boolean isAllowed(WorkflowAction action, PatentApplication app, Viewer viewer) {
        return denialReason(action, app, viewer).isEmpty();
    }

    /** @throws ForbiddenException with the reason when the action is not allowed for role/state */
    public void check(WorkflowAction action, PatentApplication app, Viewer viewer) {
        denialReason(action, app, viewer).ifPresent(reason -> {
            throw new ForbiddenException(reason);
        });
    }

    public List<WorkflowAction> allowedActions(PatentApplication app, Viewer viewer) {
        List<WorkflowAction> allowed = new ArrayList<>();
        for (WorkflowAction action : WorkflowAction.values()) {
            if (isAllowed(action, app, viewer)) {
                allowed.add(action);
            }
        }
        return allowed;
    }

    public List<ActionOption> availableActions(PatentApplication app, Viewer viewer) {
        return allowedActions(app, viewer).stream().map(ActionOption::of).toList();
    }

    public Optional<String> denialReason(WorkflowAction action, PatentApplication app, Viewer viewer) {
        return Optional.ofNullable(evaluate(action, app, viewer));
    }

    /** @return {@code null} when allowed, otherwise a human-readable reason */
    private String evaluate(WorkflowAction action, PatentApplication app, Viewer viewer) {
        ApplicationStatus s = app.getStatus();
        boolean owner = isOwner(app, viewer);
        boolean examiner = actsAsExaminer(app, viewer);
        return switch (action) {
            case CLAIM -> {
                if (!examiner) {
                    yield EXAMINER_ONLY;
                }
                if (!s.isActive()) {
                    yield notInState(action, s);
                }
                yield app.getExaminer() != null ? "The application is already assigned to an examiner" : null;
            }
            case RELEASE -> {
                if (!examiner) {
                    yield EXAMINER_ONLY;
                }
                if (!s.isActive()) {
                    yield notInState(action, s);
                }
                if (app.getExaminer() == null) {
                    yield "The application is not assigned to an examiner";
                }
                yield assignedOnly(app, viewer);
            }
            case START_PRE_EXAM -> examinerIn(examiner, action, s, EnumSet.of(SUBMITTED));
            case NOTICE_MISSING_PARTS -> examinerIn(examiner, action, s, EnumSet.of(SUBMITTED, PRE_EXAM));
            case DOCKET -> examinerIn(examiner, action, s, EnumSet.of(PRE_EXAM));
            case ISSUE_PATENT -> examinerIn(examiner, action, s, EnumSet.of(ALLOWED));
            case START_EXAMINATION -> assignedIn(app, viewer, action, EnumSet.of(DOCKETED, RESPONSE_FILED));
            case ISSUE_NON_FINAL_REJECTION, ISSUE_FINAL_REJECTION, ALLOW ->
                    assignedIn(app, viewer, action, EnumSet.of(UNDER_EXAMINATION));
            case CURE_MISSING_PARTS -> ownerIn(owner, action, s, EnumSet.of(INCOMPLETE));
            case FILE_RESPONSE -> ownerIn(owner, action, s, EnumSet.of(NON_FINAL_REJECTION, FINAL_REJECTION));
            case PUBLISH -> {
                String reason = examinerIn(examiner, action, s, ApplicationStatus.PUBLISHABLE);
                yield reason != null ? reason : app.isPublished() ? "The application is already published" : null;
            }
            case ABANDON -> {
                if (!owner && !examiner) {
                    yield "Only the applicant or an examiner can abandon the application";
                }
                if (!s.isActive()) {
                    yield notInState(action, s);
                }
                if (owner) {
                    yield null; // express abandonment by the applicant
                }
                yield EnumSet.of(INCOMPLETE, NON_FINAL_REJECTION, FINAL_REJECTION).contains(s) ? null : notInState(action, s);
            }
        };
    }

    private static String examinerIn(boolean examiner, WorkflowAction action, ApplicationStatus s, Set<ApplicationStatus> from) {
        if (!examiner) {
            return EXAMINER_ONLY;
        }
        return from.contains(s) ? null : notInState(action, s);
    }

    private String assignedIn(PatentApplication app, Viewer viewer, WorkflowAction action, Set<ApplicationStatus> from) {
        if (!actsAsExaminer(app, viewer)) {
            return EXAMINER_ONLY;
        }
        String assigned = assignedOnly(app, viewer);
        if (assigned != null) {
            return assigned;
        }
        return from.contains(app.getStatus()) ? null : notInState(action, app.getStatus());
    }

    private static String ownerIn(boolean owner, WorkflowAction action, ApplicationStatus s, Set<ApplicationStatus> from) {
        if (!owner) {
            return OWNER_ONLY;
        }
        return from.contains(s) ? null : notInState(action, s);
    }

    private String assignedOnly(PatentApplication app, Viewer viewer) {
        return isAssignedExaminer(app, viewer) ? null : ASSIGNED_ONLY;
    }

    private static String notInState(WorkflowAction action, ApplicationStatus s) {
        return "'" + action.getLabel() + "' is not allowed while the application is in status " + s.name();
    }

    // ---- permissions ------------------------------------------------------------------------

    public boolean canEdit(PatentApplication app, Viewer viewer) {
        return isOwner(app, viewer) && EDITABLE.contains(app.getStatus());
    }

    public boolean canDelete(PatentApplication app, Viewer viewer) {
        return isOwner(app, viewer) && app.getStatus() == DRAFT;
    }

    public boolean canSubmit(PatentApplication app, Viewer viewer) {
        return isOwner(app, viewer) && app.getStatus() == DRAFT;
    }

    /**
     * Owner: DRAFT/INCOMPLETE any applicant code; a rejection outstanding RESP/CLM/SPEC/DRW/IDS/MISC;
     * other pending statuses IDS/MISC; terminal none. Assigned examiner (or admin): examiner codes on
     * any filed non-terminal application.
     */
    public Set<DocumentCode> uploadableCodes(PatentApplication app, Viewer viewer) {
        Set<DocumentCode> codes = EnumSet.noneOf(DocumentCode.class);
        ApplicationStatus s = app.getStatus();
        if (isOwner(app, viewer)) {
            if (EDITABLE.contains(s)) {
                codes.addAll(DocumentCode.APPLICANT_CODES);
            } else if (s == NON_FINAL_REJECTION || s == FINAL_REJECTION) {
                codes.addAll(DocumentCode.RESPONSE_CODES);
            } else if (s.isActive()) {
                codes.addAll(DocumentCode.PENDING_CODES);
            }
        }
        if (isAssignedExaminer(app, viewer) && s.isActive()) {
            codes.addAll(DocumentCode.EXAMINER_CODES);
        }
        return codes;
    }

    public ApplicationPermissions permissions(PatentApplication app, Viewer viewer) {
        Set<DocumentCode> codes = uploadableCodes(app, viewer);
        return new ApplicationPermissions(canEdit(app, viewer), canDelete(app, viewer), canSubmit(app, viewer),
                !codes.isEmpty(), List.copyOf(codes));
    }

    /** Owner with an ACTION_REQUIRED status, or the assigned examiner when the next step is theirs. */
    public boolean isActionRequired(PatentApplication app, Viewer viewer) {
        if (isOwner(app, viewer)) {
            return app.getStatus().getCategory() == StatusCategory.ACTION_REQUIRED;
        }
        return actsAsExaminer(app, viewer)
                && viewer.id().equals(app.examinerId())
                && ApplicationStatus.AWAITING_EXAMINER.contains(app.getStatus());
    }

    /**
     * Owner: while DRAFT, or when the document never became UPLOADED. Uploaders may also discard
     * their own failed / pending uploads (addition to the contract, e.g. an examiner's aborted upload).
     */
    public boolean canDeleteDocument(Document document, PatentApplication app, Viewer viewer) {
        boolean notUploaded = DELETABLE_DOCUMENT_STATUSES.contains(document.getStatus());
        if (isOwner(app, viewer)) {
            return app.getStatus() == DRAFT || notUploaded;
        }
        return notUploaded && viewer.id().equals(document.uploadedById());
    }
}
