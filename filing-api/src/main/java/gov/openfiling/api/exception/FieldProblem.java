package gov.openfiling.api.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/** One entry of a problem's {@code errors} array. */
public record FieldProblem(
        @Schema(example = "documents") String field,
        @Schema(example = "Claims (CLM) is required") String message) {
}
