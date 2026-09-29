package gov.openfiling.api.model;

/** PUBLIC documents get a redacted public copy; RESTRICTED ones are never public. */
public enum DocumentVisibility {
    PUBLIC,
    RESTRICTED
}
