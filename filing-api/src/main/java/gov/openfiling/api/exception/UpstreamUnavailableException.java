package gov.openfiling.api.exception;

/** 503 &mdash; a dependency (object storage, redaction function) could not be reached. */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
