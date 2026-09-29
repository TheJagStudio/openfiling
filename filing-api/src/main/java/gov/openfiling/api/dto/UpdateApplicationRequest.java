package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.EntityStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Same fields as {@link CreateApplicationRequest} plus the optimistic-locking {@code version}. */
public record UpdateApplicationRequest(
        @NotBlank @Size(min = 5, max = 500) String title,
        @NotNull ApplicationType applicationType,
        @NotNull EntityStatus entityStatus,
        @Size(max = 5000) String abstractText,
        @Size(max = 200) String technologyField,
        @Size(max = 50) String attorneyDocketNumber,
        @NotNull @Size(min = 1, max = 20) List<@Valid @NotNull InventorDto> inventors,
        @NotNull Long version) implements ApplicationFields {
}
