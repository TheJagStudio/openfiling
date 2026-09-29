package gov.openfiling.api.dto;

import gov.openfiling.api.model.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** Partial update: {@code null} fields are left unchanged; {@code artUnit: ""} clears the art unit. */
public record AdminUpdateUserRequest(
        @Size(min = 1) Set<@NotNull Role> roles,
        Boolean enabled,
        @Schema(example = "2100") @Pattern(regexp = "^(\\d{4})?$", message = "must be a 4-digit art unit") String artUnit,
        Boolean unlock) {
}
