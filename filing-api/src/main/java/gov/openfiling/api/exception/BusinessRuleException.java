package gov.openfiling.api.exception;

import java.util.List;

import lombok.Getter;

/** 422 &mdash; the request is well-formed but violates a business rule; details in {@code errors}. */
@Getter
public class BusinessRuleException extends RuntimeException {

    private final transient List<FieldProblem> errors;

    public BusinessRuleException(String message, List<FieldProblem> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public static BusinessRuleException of(String field, String message) {
        return new BusinessRuleException(message, List.of(new FieldProblem(field, message)));
    }
}
