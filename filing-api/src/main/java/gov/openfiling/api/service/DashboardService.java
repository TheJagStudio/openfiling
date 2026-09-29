package gov.openfiling.api.service;

import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.DashboardResponse;
import gov.openfiling.api.dto.ExaminerStats;
import gov.openfiling.api.dto.StatusCount;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.StatusCategory;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.spec.ApplicationSpecifications;
import gov.openfiling.api.security.Viewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Role-aware dashboard: the viewer's own filings plus, for examiners, their docket. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    static final int ACTION_LIMIT = 5;
    static final int RECENT_LIMIT = 6;

    private static final Set<ApplicationStatus> ACTION_REQUIRED = EnumSet.copyOf(Arrays.stream(ApplicationStatus.values())
            .filter(s -> s.getCategory() == StatusCategory.ACTION_REQUIRED).toList());
    private static final Sort RECENT_FIRST = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"));

    private final ApplicationRepository applications;
    private final ApplicationViews views;
    private final ExaminerService examiner;

    public DashboardResponse dashboard(Viewer viewer) {
        List<StatusCount> byStatus = ExaminerService.toStatusCounts(applications.countByStatusForOwner(viewer.id()));
        Map<StatusCategory, Long> byCategory = new LinkedHashMap<>();
        long all = 0;
        for (StatusCount c : byStatus) {
            byCategory.merge(c.status().getCategory(), c.count(), Long::sum);
            all += c.count();
        }
        DashboardResponse.Totals totals = new DashboardResponse.Totals(
                all,
                byCategory.getOrDefault(StatusCategory.DRAFT, 0L),
                byCategory.getOrDefault(StatusCategory.PENDING, 0L),
                byCategory.getOrDefault(StatusCategory.ACTION_REQUIRED, 0L),
                count(byStatus, ApplicationStatus.ISSUED),
                count(byStatus, ApplicationStatus.ABANDONED));

        List<PatentApplication> actionRequired = new ArrayList<>(ownApplications(viewer.id(), ACTION_REQUIRED, ACTION_LIMIT));
        if (viewer.isExaminer()) {
            actionRequired.addAll(applications.findAll(ApplicationSpecifications.matching(new ApplicationSpecifications.Filter(
                            null, true, false, null, false, false, ApplicationStatus.AWAITING_EXAMINER, null, viewer.id(), false,
                            null, null, null)),
                    PageRequest.of(0, ACTION_LIMIT, RECENT_FIRST)).getContent());
            actionRequired.sort(Comparator.comparing(PatentApplication::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        }
        List<PatentApplication> action = actionRequired.stream().limit(ACTION_LIMIT).toList();
        List<PatentApplication> recent = ownApplications(viewer.id(), null, RECENT_LIMIT);

        DashboardResponse.ExaminerDashboard examinerBlock = null;
        if (viewer.isExaminer()) {
            ExaminerStats stats = examiner.stats(viewer);
            examinerBlock = new DashboardResponse.ExaminerDashboard(stats.unassigned(), stats.myDocket(),
                    stats.awaitingMyAction(), stats.avgPendencyDays(), stats.byStatus());
        }
        List<ApplicationSummary> actionSummaries = views.summaries(action, viewer);
        List<ApplicationSummary> recentSummaries = views.summaries(recent, viewer);
        return new DashboardResponse(totals, byStatus, actionSummaries, recentSummaries, examinerBlock);
    }

    private List<PatentApplication> ownApplications(UUID ownerId, Set<ApplicationStatus> statuses, int limit) {
        return applications.findAll(
                ApplicationSpecifications.matching(ApplicationSpecifications.Filter.owner(ownerId, null, statuses, null)),
                PageRequest.of(0, limit, RECENT_FIRST)).getContent();
    }

    private static long count(List<StatusCount> counts, ApplicationStatus status) {
        return counts.stream().filter(c -> c.status() == status).mapToLong(StatusCount::count).sum();
    }
}
