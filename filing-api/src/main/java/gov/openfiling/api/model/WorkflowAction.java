package gov.openfiling.api.model;

import java.util.EnumSet;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Workflow actions ({@code POST /applications/{id}/actions}). The rules deciding who may perform
 * which action in which status live in {@code WorkflowPolicy}; this enum only carries presentation
 * metadata and the target status.
 */
@Getter
@RequiredArgsConstructor
public enum WorkflowAction {
    CLAIM("Claim for examination", null, false, EnumSet.noneOf(DocumentCode.class), false),
    RELEASE("Release to queue", null, false, EnumSet.noneOf(DocumentCode.class), false),
    START_PRE_EXAM("Start pre-exam processing", ApplicationStatus.PRE_EXAM, false, EnumSet.noneOf(DocumentCode.class), false),
    NOTICE_MISSING_PARTS("Mail notice to file missing parts", ApplicationStatus.INCOMPLETE, true,
            EnumSet.of(DocumentCode.NTMP), false),
    CURE_MISSING_PARTS("Submit missing parts", ApplicationStatus.PRE_EXAM, false, EnumSet.noneOf(DocumentCode.class), false),
    DOCKET("Docket to art unit", ApplicationStatus.DOCKETED, false, EnumSet.noneOf(DocumentCode.class), false),
    START_EXAMINATION("Start examination", ApplicationStatus.UNDER_EXAMINATION, false, EnumSet.noneOf(DocumentCode.class), false),
    ISSUE_NON_FINAL_REJECTION("Issue non-final rejection", ApplicationStatus.NON_FINAL_REJECTION, true,
            EnumSet.of(DocumentCode.CTNF, DocumentCode.EXMISC), false),
    ISSUE_FINAL_REJECTION("Issue final rejection", ApplicationStatus.FINAL_REJECTION, true,
            EnumSet.of(DocumentCode.CTFR, DocumentCode.EXMISC), false),
    FILE_RESPONSE("File response", ApplicationStatus.RESPONSE_FILED, true, EnumSet.noneOf(DocumentCode.class), false),
    ALLOW("Mail notice of allowance", ApplicationStatus.ALLOWED, false, EnumSet.of(DocumentCode.NOA, DocumentCode.EXMISC), false),
    ISSUE_PATENT("Issue patent", ApplicationStatus.ISSUED, false, EnumSet.noneOf(DocumentCode.class), false),
    PUBLISH("Publish application", null, false, EnumSet.noneOf(DocumentCode.class), false),
    ABANDON("Abandon application", ApplicationStatus.ABANDONED, true, EnumSet.noneOf(DocumentCode.class), true);

    private final String label;
    /** Target status, or {@code null} when the action does not change the status. */
    private final ApplicationStatus toStatus;
    private final boolean requiresNote;
    /** Examiner document codes that may be attached via {@code documentIds}. */
    private final Set<DocumentCode> attachableCodes;
    private final boolean destructive;

    public boolean acceptsDocuments() {
        return !attachableCodes.isEmpty();
    }

    public boolean changesStatus() {
        return toStatus != null;
    }
}
