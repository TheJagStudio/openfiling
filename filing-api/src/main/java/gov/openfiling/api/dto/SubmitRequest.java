package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SubmitRequest(
        @NotNull Long version,
        @Schema(description = "The applicant certifies the filing is accurate") @NotNull @AssertTrue(message = "must be true") Boolean certify,
        @Schema(example = "/Ada Lovelace/") @NotBlank
        @Pattern(regexp = "^/[^/]{2,100}/$", message = "must be an S-signature like /Ada Lovelace/")
        String signature) {
}
