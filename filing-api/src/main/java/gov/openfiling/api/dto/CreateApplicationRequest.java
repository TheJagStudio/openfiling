package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.EntityStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateApplicationRequest(
        @Schema(example = "Solid-state battery electrolyte with ceramic interlayer") @NotBlank @Size(min = 5, max = 500) String title,
        @NotNull ApplicationType applicationType,
        @NotNull EntityStatus entityStatus,
        @Size(max = 5000) String abstractText,
        @Schema(example = "Electrochemistry") @Size(max = 200) String technologyField,
        @Schema(example = "ACME-0042") @Size(max = 50) String attorneyDocketNumber,
        @NotNull @Size(min = 1, max = 20) List<@Valid @NotNull InventorDto> inventors) implements ApplicationFields {
}
