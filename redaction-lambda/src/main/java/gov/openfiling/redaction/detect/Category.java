package gov.openfiling.redaction.detect;

/** Redaction categories (contract list); also the enum Claude must choose from in its structured output. */
public enum Category {
    PERSON_NAME,
    SSN,
    TAX_ID,
    PHONE,
    EMAIL,
    ADDRESS,
    DATE_OF_BIRTH,
    FINANCIAL_ACCOUNT,
    GOVERNMENT_ID,
    IP_ADDRESS,
    MEDICAL,
    CUI_MARKING,
    EXPORT_CONTROLLED,
    OTHER_SENSITIVE
}
