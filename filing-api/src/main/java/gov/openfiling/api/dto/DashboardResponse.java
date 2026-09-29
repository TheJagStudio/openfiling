package gov.openfiling.api.dto;

import java.util.List;

public record DashboardResponse(
        Totals totals,
        List<StatusCount> byStatus,
        List<ApplicationSummary> actionRequired,
        List<ApplicationSummary> recent,
        ExaminerDashboard examiner) {

    public record Totals(long all, long drafts, long pending, long actionRequired, long issued, long abandoned) {
    }

    public record ExaminerDashboard(
            long unassigned, long myDocket, long awaitingMyAction, Double avgPendencyDays, List<StatusCount> byStatus) {
    }
}
