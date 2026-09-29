package gov.openfiling.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import gov.openfiling.api.model.RedactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Redaction Lambda status callback. {@code PENDING} is not a valid callback status. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RedactionCallbackRequest(
        @NotNull UUID documentId,
        @Schema(example = "COMPLETED") @NotNull RedactionStatus status,
        @Schema(example = "CLAUDE+RULES") @Size(max = 20) String engine,
        @Schema(example = "claude-opus-5-5") @Size(max = 64) String model,
        @PositiveOrZero Integer pageCount,
        @PositiveOrZero Integer redactionCount,
        @Size(max = 50) List<@Valid @NotNull CategoryCount> categories,
        @Size(max = 63) String publicCopyBucket,
        @Size(max = 1024) String publicCopyKey,
        @Size(max = 1024) String reportKey,
        @Size(max = 4000) String error,
        @PositiveOrZero Long durationMs) {
}
