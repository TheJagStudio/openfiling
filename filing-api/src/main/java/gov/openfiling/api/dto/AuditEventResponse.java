package gov.openfiling.api.dto;

import gov.openfiling.api.model.AuditAction;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record AuditEventResponse(
        long id,
        Instant occurredAt,
        UUID actorId,
        String actorEmail,
        AuditAction action,
        String resourceType,
        String resourceId,
        String ip,
        String userAgent,
        String requestId,
        JsonNode details) {
}
