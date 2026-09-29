package gov.openfiling.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String email,
        String displayName,
        List<String> roles,
        String artUnit,
        boolean enabled,
        boolean locked,
        Instant lastLoginAt,
        Instant createdAt,
        long applicationCount) {
}
