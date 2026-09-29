package gov.openfiling.api.exception;

/** 404 &mdash; also used for resources the caller may not see (never leak existence). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String what, Object id) {
        return new NotFoundException(what + " '" + id + "' was not found");
    }
}
