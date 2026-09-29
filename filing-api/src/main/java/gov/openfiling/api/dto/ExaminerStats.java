package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ExaminerStats(
        List<StatusCount> byStatus,
        long unassigned,
        long myDocket,
        long awaitingMyAction,
        @Schema(example = "41.5", nullable = true) Double avgPendencyDays,
        RedactionCounts redactions) {
}
