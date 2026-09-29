package gov.openfiling.api.dto;

/** Password policy shared by registration, password change and admin user creation (plus 10-128 chars). */
public final class PasswordPolicy {

    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d).*$";
    public static final String MESSAGE = "must contain at least one letter and one digit";

    private PasswordPolicy() {
    }
}
