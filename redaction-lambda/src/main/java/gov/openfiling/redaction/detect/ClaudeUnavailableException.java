package gov.openfiling.redaction.detect;

/** The Claude call failed; the message is a short, document-free description (e.g. "rate limited (429)"). */
public class ClaudeUnavailableException extends RuntimeException {

    public ClaudeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
