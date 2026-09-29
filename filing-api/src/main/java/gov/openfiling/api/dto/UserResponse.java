package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        @Schema(example = "3f1c2a7e-5b8d-4a51-9a51-0c1d2e3f4a5b") UUID id,
        @Schema(example = "inventor@openfiling.local") String email,
        @Schema(example = "Ada Inventor") String displayName,
        @Schema(example = "[\"INVENTOR\"]") List<String> roles,
        @Schema(example = "2100", nullable = true) String artUnit,
        Instant createdAt,
        Instant lastLoginAt) {
}
