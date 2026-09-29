package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.EntityStatus;
import gov.openfiling.api.model.StatusCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ApplicationSummary(
        UUID id,
        @Schema(example = "19000123", nullable = true) String applicationNumber,
        @Schema(example = "19/000,123", nullable = true) String applicationNumberDisplay,
        @Schema(example = "Solid-state battery electrolyte with ceramic interlayer") String title,
        ApplicationType applicationType,
        EntityStatus entityStatus,
        ApplicationStatus status,
        @Schema(example = "Non-final rejection mailed") String statusLabel,
        StatusCategory statusCategory,
        LocalDate filingDate,
        Instant createdAt,
        Instant updatedAt,
        PartyRef owner,
        @Schema(nullable = true) PartyRef examiner,
        @Schema(example = "2100") String artUnit,
        @Schema(example = "3") long documentCount,
        boolean published,
        @Schema(example = "12", nullable = true) Integer daysPending,
        @Schema(description = "True when the viewer has something to do") boolean actionRequired) {
}
