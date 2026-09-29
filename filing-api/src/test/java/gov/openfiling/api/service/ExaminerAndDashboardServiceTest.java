package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.DashboardResponse;
import gov.openfiling.api.dto.ExaminerStats;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.RedactionCounts;
import gov.openfiling.api.mapper.AccountMapperImpl;
import gov.openfiling.api.mapper.InventorMapperImpl;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.IdCountRow;
import gov.openfiling.api.repository.StatusCountRow;
import gov.openfiling.api.security.Viewer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class ExaminerAndDashboardServiceTest {

    @Mock
    ApplicationRepository applications;

    @Mock
    DocumentRepository documents;

    @Mock
    RedactionService redactions;

    ExaminerService examiner;
    DashboardService dashboard;
    User owner;
    User grace;

    @BeforeEach
    void setUp() {
        ApplicationViews views = new ApplicationViews(new WorkflowPolicy(), new AccountMapperImpl(), new InventorMapperImpl(), documents,
                TestData.CLOCK);
        examiner = new ExaminerService(applications, views, redactions, TestData.CLOCK);
        dashboard = new DashboardService(applications, views, examiner);
        owner = TestData.inventor("Ada");
        grace = TestData.examiner("Grace");
    }

    static StatusCountRow row(ApplicationStatus status, long total) {
        return new StatusCountRow() {
            @Override
            public ApplicationStatus getStatus() {
                return status;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    static IdCountRow idRow(UUID id, long total) {
        return new IdCountRow() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void queue_assignedToMe_returnsSummariesWithDocumentCounts() {
        PatentApplication app = TestData.assigned(TestData.application(owner, ApplicationStatus.DOCKETED), grace);
        given(applications.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(app), PageRequest.of(0, 20), 1));
        given(documents.countActiveByApplications(List.of(app.getId()))).willReturn(List.of(idRow(app.getId(), 4)));

        PageResponse<ApplicationSummary> page = examiner.queue(TestData.viewer(grace), new ExaminerService.QueueFilter(
                "19/000", List.of(ApplicationStatus.DOCKETED), ExaminerService.Assigned.parse("ME"), "2100", null,
                LocalDate.of(2026, 1, 1), null), PageParams.defaults());

        assertThat(page.content()).singleElement().satisfies(s -> {
            assertThat(s.documentCount()).isEqualTo(4);
            assertThat(s.actionRequired()).isTrue();
            assertThat(s.examiner().displayName()).isEqualTo("Grace");
        });
    }

    @Test
    void assigned_parse_defaultsToAny() {
        assertThat(ExaminerService.Assigned.parse(null)).isEqualTo(ExaminerService.Assigned.ANY);
        assertThat(ExaminerService.Assigned.parse(" unassigned ")).isEqualTo(ExaminerService.Assigned.UNASSIGNED);
    }

    @Test
    void stats_combinesCountsPendencyAndRedactions() {
        given(applications.countFiledByStatus()).willReturn(List.of(row(ApplicationStatus.PRE_EXAM, 2), row(ApplicationStatus.SUBMITTED, 5)));
        given(applications.countByExaminerIsNullAndStatusIn(any())).willReturn(7L);
        given(applications.countByExaminer_IdAndStatusIn(eq(grace.getId()), eq(ExaminerService.ACTIVE))).willReturn(3L);
        given(applications.countByExaminer_IdAndStatusIn(eq(grace.getId()), eq(ApplicationStatus.AWAITING_EXAMINER))).willReturn(2L);
        given(applications.averagePendencyDays(TestData.TODAY)).willReturn(41.4567);
        given(redactions.counts()).willReturn(new RedactionCounts(1, 0, 5, 1));

        ExaminerStats stats = examiner.stats(TestData.viewer(grace));

        assertThat(stats.byStatus()).extracting(s -> s.status()).containsExactly(ApplicationStatus.SUBMITTED, ApplicationStatus.PRE_EXAM);
        assertThat(stats.unassigned()).isEqualTo(7);
        assertThat(stats.myDocket()).isEqualTo(3);
        assertThat(stats.awaitingMyAction()).isEqualTo(2);
        assertThat(stats.avgPendencyDays()).isEqualTo(41.5);
        assertThat(stats.redactions().completed()).isEqualTo(5);
    }

    @Test
    void stats_noPendingApplications_nullPendency() {
        given(applications.averagePendencyDays(any())).willReturn(null);

        assertThat(examiner.averagePendencyDays()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboard_inventor_totalsByCategoryAndNoExaminerBlock() {
        Viewer viewer = TestData.viewer(owner);
        given(applications.countByStatusForOwner(owner.getId())).willReturn(List.of(
                row(ApplicationStatus.DRAFT, 2), row(ApplicationStatus.SUBMITTED, 1), row(ApplicationStatus.NON_FINAL_REJECTION, 1),
                row(ApplicationStatus.ISSUED, 1), row(ApplicationStatus.ABANDONED, 1)));
        PatentApplication rejected = TestData.application(owner, ApplicationStatus.NON_FINAL_REJECTION);
        given(applications.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(rejected)), new PageImpl<>(List.of(rejected)));

        DashboardResponse response = dashboard.dashboard(viewer);

        assertThat(response.totals()).isEqualTo(new DashboardResponse.Totals(6, 2, 1, 1, 1, 1));
        assertThat(response.actionRequired()).singleElement().satisfies(s -> assertThat(s.actionRequired()).isTrue());
        assertThat(response.recent()).hasSize(1);
        assertThat(response.examiner()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboard_examiner_mergesDocketAndAddsExaminerBlock() {
        Viewer viewer = TestData.viewer(grace);
        given(applications.countByStatusForOwner(grace.getId())).willReturn(List.of());
        PatentApplication older = TestData.assigned(TestData.application(owner, ApplicationStatus.DOCKETED), grace);
        older.setUpdatedAt(TestData.NOW.minus(Duration.ofDays(5)));
        PatentApplication newer = TestData.assigned(TestData.application(owner, ApplicationStatus.RESPONSE_FILED), grace);
        newer.setUpdatedAt(TestData.NOW.minus(Duration.ofDays(1)));
        given(applications.findAll(any(Specification.class), any(Pageable.class))).willReturn(
                new PageImpl<>(List.of()), new PageImpl<>(List.of(older, newer)), new PageImpl<>(List.of()));
        given(applications.countFiledByStatus()).willReturn(List.of());
        given(redactions.counts()).willReturn(new RedactionCounts(0, 0, 0, 0));

        DashboardResponse response = dashboard.dashboard(viewer);

        assertThat(response.totals().all()).isZero();
        assertThat(response.actionRequired()).extracting(ApplicationSummary::id).containsExactly(newer.getId(), older.getId());
        assertThat(response.examiner()).isNotNull();
    }
}
