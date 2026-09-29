package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.EntityStatus;
import gov.openfiling.api.model.StatusCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@link ApplicationSummary} fields plus bibliographic data, permissions and available actions. */
public record ApplicationDetail(
        UUID id,
        @Schema(example = "19000123", nullable = true) String applicationNumber,
        @Schema(example = "19/000,123", nullable = true) String applicationNumberDisplay,
        String title,
        ApplicationType applicationType,
        EntityStatus entityStatus,
        ApplicationStatus status,
        String statusLabel,
        StatusCategory statusCategory,
        LocalDate filingDate,
        Instant createdAt,
        Instant updatedAt,
        PartyRef owner,
        PartyRef examiner,
        String artUnit,
        long documentCount,
        boolean published,
        Integer daysPending,
        boolean actionRequired,
        @Schema(example = "4821") String confirmationNumber,
        String abstractText,
        String technologyField,
        String attorneyDocketNumber,
        @Schema(example = "Ada Lovelace") String firstNamedInventor,
        List<InventorDto> inventors,
        Instant submittedAt,
        @Schema(example = "/Ada Lovelace/") String signature,
        @Schema(example = "US20260012345A1") String publicationNumber,
        LocalDate publicationDate,
        @Schema(example = "US12500001B2") String patentNumber,
        LocalDate issueDate,
        @Schema(example = "3") long version,
        ApplicationPermissions permissions,
        List<ActionOption> availableActions) {
}
