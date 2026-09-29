package gov.openfiling.api.model;

/** Portal roles. Sign-up always yields INVENTOR; EXAMINER/ADMIN are granted by an admin. */
public enum Role {
    INVENTOR,
    EXAMINER,
    ADMIN
}
