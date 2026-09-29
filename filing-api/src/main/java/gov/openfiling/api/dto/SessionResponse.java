package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One active refresh-token family ("session"); {@code id} is the family id. */
public record SessionResponse(
        UUID id,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        @Schema(example = "Mozilla/5.0 ...") String userAgent,
        @Schema(example = "127.0.0.1") String ip) {
}
