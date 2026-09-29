package gov.openfiling.api.exception;

/** 401 for unknown, expired, revoked or reused refresh tokens (one message for all cases). */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("The refresh token is invalid or expired; sign in again");
    }
}
