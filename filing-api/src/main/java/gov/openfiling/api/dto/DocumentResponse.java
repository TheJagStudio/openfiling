package gov.openfiling.api.dto;

import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentSource;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.DocumentVisibility;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID applicationId,
        DocumentCode docCode,
        @Schema(example = "Specification") String docCodeLabel,
        String description,
        DocumentVisibility visibility,
        @Schema(example = "specification.pdf") String fileName,
        @Schema(example = "application/pdf") String contentType,
        @Schema(example = "48213") long sizeBytes,
        DocumentStatus status,
        DocumentSource source,
        PartyRef uploadedBy,
        Instant createdAt,
        Instant uploadedAt,
        String rejectionReason,
        @Schema(description = "null for RESTRICTED documents", nullable = true) RedactionResponse redaction) {
}
