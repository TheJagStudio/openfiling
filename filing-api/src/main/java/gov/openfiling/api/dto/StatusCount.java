package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record StatusCount(ApplicationStatus status, @Schema(example = "4") long count) {
}
