package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record StatusEventResponse(
        long id,
        @Schema(example = "ISSUE_NON_FINAL_REJECTION") String eventCode,
        ApplicationStatus fromStatus,
        ApplicationStatus toStatus,
        @Schema(example = "Non-final rejection mailed") String description,
        String note,
        Actor actor,
        Instant createdAt) {

    public record Actor(@Schema(example = "Grace Examiner") String displayName, @Schema(example = "EXAMINER") String role) {
    }
}
