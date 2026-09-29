package gov.openfiling.api.exception;

/** Bad email or password. The message never reveals which one was wrong. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
