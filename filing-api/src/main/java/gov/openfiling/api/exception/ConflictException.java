package gov.openfiling.api.exception;

/** 409 &mdash; state conflicts, duplicates and stale {@code version}s. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public static ConflictException staleVersion() {
        return new ConflictException("The application was modified by someone else; reload it and try again");
    }
}
