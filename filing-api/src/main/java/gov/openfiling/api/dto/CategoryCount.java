package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CategoryCount(
        @Schema(example = "SSN") @NotBlank @Size(max = 40) String category,
        @Schema(example = "2") @PositiveOrZero long count) {
}
