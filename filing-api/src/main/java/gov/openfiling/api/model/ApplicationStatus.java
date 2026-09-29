package gov.openfiling.api.model;

import java.util.EnumSet;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** TSDR-style application status (label / category / terminal). */
@Getter
@RequiredArgsConstructor
public enum ApplicationStatus {
    DRAFT("Draft — not yet filed", StatusCategory.DRAFT, false,
            "The application is being prepared and has not been filed."),
    SUBMITTED("New application — awaiting pre-examination processing", StatusCategory.PENDING, false,
            "The application was filed and received an application number."),
    INCOMPLETE("Notice to file missing parts mailed", StatusCategory.ACTION_REQUIRED, false,
            "Required parts are missing; the applicant must file them."),
    PRE_EXAM("Application undergoing pre-exam processing", StatusCategory.PENDING, false,
            "Formalities review before the case is docketed."),
    DOCKETED("Docketed new case — ready for examination", StatusCategory.PENDING, false,
            "The case is assigned to an art unit and waits for examination."),
    UNDER_EXAMINATION("Under examination", StatusCategory.PENDING, false,
            "An examiner is reviewing the claims."),
    NON_FINAL_REJECTION("Non-final rejection mailed", StatusCategory.ACTION_REQUIRED, false,
            "The examiner rejected one or more claims; the applicant may respond."),
    RESPONSE_FILED("Response to office action entered", StatusCategory.PENDING, false,
            "The applicant's response waits for the examiner."),
    FINAL_REJECTION("Final rejection mailed", StatusCategory.ACTION_REQUIRED, false,
            "The examiner issued a final rejection; the applicant may respond or abandon."),
    ALLOWED("Notice of allowance mailed", StatusCategory.POSITIVE, false,
            "The claims were found allowable; the patent will issue."),
    ISSUED("Patented case", StatusCategory.POSITIVE, true,
            "The patent was granted."),
    ABANDONED("Abandoned", StatusCategory.NEGATIVE, true,
            "The application is no longer pending.");

    /** Statuses in which the assigned examiner is expected to act next. */
    public static final Set<ApplicationStatus> AWAITING_EXAMINER =
            EnumSet.of(SUBMITTED, PRE_EXAM, DOCKETED, UNDER_EXAMINATION, RESPONSE_FILED, ALLOWED);

    /** Statuses in which an application can be published (PRE_EXAM … ALLOWED). */
    public static final Set<ApplicationStatus> PUBLISHABLE =
            EnumSet.of(PRE_EXAM, DOCKETED, UNDER_EXAMINATION, NON_FINAL_REJECTION, RESPONSE_FILED, FINAL_REJECTION, ALLOWED);

    public static final Set<ApplicationStatus> TERMINAL = EnumSet.of(ISSUED, ABANDONED);

    private final String label;
    private final StatusCategory category;
    private final boolean terminal;
    private final String description;

    public boolean isFiled() {
        return this != DRAFT;
    }

    /** Filed and still pending (not ISSUED / ABANDONED). */
    public boolean isActive() {
        return isFiled() && !terminal;
    }
}
