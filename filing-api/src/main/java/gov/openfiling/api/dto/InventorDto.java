package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Inventor (request and response). Country / citizenship are ISO-3166 alpha-2 codes. */
public record InventorDto(
        @Schema(example = "Ada") @NotBlank @Size(max = 100) String givenName,
        @Schema(example = "Lovelace") @NotBlank @Size(max = 100) String familyName,
        @Schema(example = "Alexandria") @Size(max = 100) String city,
        @Schema(example = "VA") @Size(max = 50) String state,
        @Schema(example = "US") @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter country code") String country,
        @Schema(example = "US") @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter country code") String citizenship) {
}
