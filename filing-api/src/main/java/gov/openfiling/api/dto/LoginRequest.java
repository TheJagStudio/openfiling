package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Schema(example = "inventor@openfiling.local") @NotBlank @Size(max = 254) String email,
        @Schema(example = "Inventor#12345") @NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]";
    }
}
