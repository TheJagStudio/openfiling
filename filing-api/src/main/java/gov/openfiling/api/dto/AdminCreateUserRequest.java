package gov.openfiling.api.dto;

import gov.openfiling.api.model.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record AdminCreateUserRequest(
        @Schema(example = "grace@openfiling.local") @NotBlank @Email @Size(max = 254) String email,
        @Schema(example = "Grace Examiner") @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(min = 10, max = 128) @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        String password,
        @Schema(example = "[\"EXAMINER\"]") @NotEmpty Set<@NotNull Role> roles,
        @Schema(example = "2100") @Pattern(regexp = "^\\d{4}$", message = "must be a 4-digit art unit") String artUnit) {

    @Override
    public String toString() {
        return "AdminCreateUserRequest[email=" + email + ", roles=" + roles + "]";
    }
}
