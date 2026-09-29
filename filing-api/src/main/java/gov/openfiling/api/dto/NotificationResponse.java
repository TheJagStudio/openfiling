package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        @Schema(example = "ISSUE_NON_FINAL_REJECTION") String type,
        @Schema(example = "19/000,123: Non-final rejection mailed") String title,
        String message,
        UUID applicationId,
        @Schema(example = "19000123") String applicationNumber,
        boolean read,
        Instant createdAt) {
}
