package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponse(
        @Schema(example = "eyJraWQiOiJ...") String accessToken,
        @Schema(example = "900", description = "Seconds until the access token expires") long expiresIn,
        @Schema(description = "Opaque refresh token (rotated on every use)") String refreshToken,
        @Schema(example = "604800") long refreshExpiresIn,
        @Schema(example = "Bearer") String tokenType,
        UserResponse user) {

    public static AuthResponse bearer(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn,
            UserResponse user) {
        return new AuthResponse(accessToken, expiresIn, refreshToken, refreshExpiresIn, "Bearer", user);
    }

    @Override
    public String toString() {
        return "AuthResponse[expiresIn=" + expiresIn + ", user=" + (user == null ? null : user.id()) + "]";
    }
}
