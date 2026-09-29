package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(@Schema(example = "Ada Lovelace") @NotBlank @Size(max = 100) String displayName) {
}
