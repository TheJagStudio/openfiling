package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code /auth/refresh} and {@code /auth/logout}. */
public record RefreshTokenRequest(
        @Schema(description = "Opaque refresh token from the last AuthResponse") @NotBlank @Size(max = 200) String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=***]";
    }
}
