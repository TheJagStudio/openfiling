package gov.openfiling.api.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Document codes (label, who files it, default visibility). */
@Getter
@RequiredArgsConstructor
public enum DocumentCode {
    SPEC("Specification", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "Written description of the invention"),
    CLM("Claims", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "The claims defining the scope of protection"),
    ABST("Abstract", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "Short technical summary (150 words or less)"),
    DRW("Drawings", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "Drawings or figures"),
    ADS("Application Data Sheet", DocumentSource.APPLICANT, DocumentVisibility.RESTRICTED,
            "Bibliographic data about the application and applicants"),
    OATH("Oath or Declaration", DocumentSource.APPLICANT, DocumentVisibility.RESTRICTED,
            "Inventor oath or declaration"),
    IDS("Information Disclosure Statement", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "Prior art known to the applicant"),
    TRNA("Transmittal Letter", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "Cover letter for the filing"),
    RESP("Response to Office Action", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "Applicant reply to a rejection"),
    MISC("Miscellaneous Incoming Letter", DocumentSource.APPLICANT, DocumentVisibility.PUBLIC,
            "Any other applicant correspondence"),
    CTNF("Non-Final Rejection", DocumentSource.EXAMINER, DocumentVisibility.PUBLIC,
            "Non-final office action"),
    CTFR("Final Rejection", DocumentSource.EXAMINER, DocumentVisibility.PUBLIC,
            "Final office action"),
    NOA("Notice of Allowance", DocumentSource.EXAMINER, DocumentVisibility.PUBLIC,
            "Notice of allowance and fees due"),
    NTMP("Notice to File Missing Parts", DocumentSource.EXAMINER, DocumentVisibility.PUBLIC,
            "Notice listing parts missing from the filing"),
    EXMISC("Examiner Miscellaneous", DocumentSource.EXAMINER, DocumentVisibility.PUBLIC,
            "Other examiner correspondence");

    public static final Set<DocumentCode> APPLICANT_CODES = bySource(DocumentSource.APPLICANT);
    public static final Set<DocumentCode> EXAMINER_CODES = bySource(DocumentSource.EXAMINER);
    /** What the owner may upload while a rejection is outstanding. */
    public static final Set<DocumentCode> RESPONSE_CODES = EnumSet.of(RESP, CLM, SPEC, DRW, IDS, MISC);
    /** What the owner may upload in other pending statuses. */
    public static final Set<DocumentCode> PENDING_CODES = EnumSet.of(IDS, MISC);

    private final String label;
    private final DocumentSource source;
    private final DocumentVisibility defaultVisibility;
    private final String description;

    /** e.g. {@code Claims (CLM)}. */
    public String displayName() {
        return label + " (" + name() + ")";
    }

    private static Set<DocumentCode> bySource(DocumentSource source) {
        return Arrays.stream(values())
                .filter(c -> c.source == source)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DocumentCode.class)));
    }
}
