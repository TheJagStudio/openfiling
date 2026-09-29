package gov.openfiling.api.exception;

import java.util.List;

/** 422 from {@code /complete}: the uploaded object failed verification; the document is now REJECTED. */
public class DocumentRejectedException extends BusinessRuleException {

    public DocumentRejectedException(String reason) {
        super("The uploaded file was rejected: " + reason, List.of(new FieldProblem("file", reason)));
    }
}
