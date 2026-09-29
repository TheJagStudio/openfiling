package gov.openfiling.api.service;

import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.ExaminerStats;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.StatusCount;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.StatusCountRow;
import gov.openfiling.api.repository.spec.ApplicationSpecifications;
import gov.openfiling.api.repository.spec.SortSupport;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Examination queue (every filed application) and examiner statistics. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ExaminerService {

    public enum Assigned { ME, UNASSIGNED, ANY;

        public static Assigned parse(String raw) {
            return raw == null || raw.isBlank() ? ANY : valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record QueueFilter(String q, Collection<ApplicationStatus> statuses, Assigned assigned, String artUnit,
            ApplicationType type, LocalDate filedFrom, LocalDate filedTo) {
    }

    static final Map<String, String> SORTS = Map.of(
            "filingDate", "filingDate", "updatedAt", "updatedAt", "applicationNumber", "applicationNumber", "status", "status");

    static final Set<ApplicationStatus> ACTIVE = EnumSet.copyOf(
            Arrays.stream(ApplicationStatus.values()).filter(ApplicationStatus::isActive).toList());

    private final ApplicationRepository applications;
    private final ApplicationViews views;
    private final RedactionService redactions;
    private final Clock clock;

    public PageResponse<ApplicationSummary> queue(Viewer viewer, QueueFilter f, PageParams paging) {
        Pageable pageable = SortSupport.pageable(paging, SORTS, "filingDate", Sort.Direction.ASC, "id");
        ApplicationSpecifications.Filter filter = new ApplicationSpecifications.Filter(
                null, true, false, f.q(), true, false, f.statuses(), f.type(),
                f.assigned() == Assigned.ME ? viewer.id() : null,
                f.assigned() == Assigned.UNASSIGNED,
                f.artUnit(), f.filedFrom(), f.filedTo());
        Page<PatentApplication> page = applications.findAll(ApplicationSpecifications.matching(filter), pageable);
        return PageResponse.of(page, views.summaries(page.getContent(), viewer));
    }

    public ExaminerStats stats(Viewer viewer) {
        return new ExaminerStats(
                filedByStatus(),
                applications.countByExaminerIsNullAndStatusIn(ACTIVE),
                applications.countByExaminer_IdAndStatusIn(viewer.id(), ACTIVE),
                applications.countByExaminer_IdAndStatusIn(viewer.id(), ApplicationStatus.AWAITING_EXAMINER),
                averagePendencyDays(),
                redactions.counts());
    }

    public List<StatusCount> filedByStatus() {
        return toStatusCounts(applications.countFiledByStatus());
    }

    public Double averagePendencyDays() {
        Double avg = applications.averagePendencyDays(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        return avg == null ? null : Math.round(avg * 10.0) / 10.0;
    }

    static List<StatusCount> toStatusCounts(List<StatusCountRow> rows) {
        return rows.stream()
                .map(r -> new StatusCount(r.getStatus(), r.getTotal()))
                .sorted(Comparator.comparing(StatusCount::status))
                .toList();
    }
}
