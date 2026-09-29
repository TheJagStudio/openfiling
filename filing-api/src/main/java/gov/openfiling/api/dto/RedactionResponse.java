package gov.openfiling.api.dto;

import gov.openfiling.api.model.RedactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record RedactionResponse(
        RedactionStatus status,
        @Schema(example = "CLAUDE+RULES", nullable = true) String engine,
        @Schema(example = "claude-opus-5-5", nullable = true) String model,
        Integer pageCount,
        Integer redactionCount,
        List<CategoryCount> categories,
        Instant startedAt,
        Instant completedAt,
        String error,
        boolean publicCopyAvailable) {
}
