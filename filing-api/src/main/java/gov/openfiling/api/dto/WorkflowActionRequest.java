package gov.openfiling.api.dto;

import gov.openfiling.api.model.WorkflowAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record WorkflowActionRequest(
        @Schema(example = "ISSUE_NON_FINAL_REJECTION") @NotNull WorkflowAction action,
        @Schema(example = "Claims 1-5 are anticipated by Smith (US 9,999,999).") @Size(max = 5000) String note,
        @Schema(example = "2100") @Pattern(regexp = "^\\d{4}$", message = "must be a 4-digit art unit") String artUnit,
        @Size(max = 20) List<@NotNull UUID> documentIds,
        @NotNull Long version) {
}
