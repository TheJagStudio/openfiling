package gov.openfiling.api.exception;

/** 403 &mdash; the caller can see the resource but may not perform this operation on it. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
