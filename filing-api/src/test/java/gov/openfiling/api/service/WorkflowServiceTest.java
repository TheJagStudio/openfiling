package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.dto.ApplicationDetail;
import gov.openfiling.api.dto.WorkflowActionRequest;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapperImpl;
import gov.openfiling.api.mapper.InventorMapperImpl;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.model.WorkflowAction;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.StatusEventRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    ApplicationRepository applications;

    @Mock
    UserRepository users;

    @Mock
    DocumentRepository documents;

    @Mock
    StatusEventRepository statusEvents;

    @Mock
    HistoryService history;

    @Mock
    NotificationService notifications;

    @Mock
    AuditService audit;

    WorkflowService service;
    User owner;
    User examiner;

    @BeforeEach
    void setUp() {
        WorkflowPolicy policy = new WorkflowPolicy();
        ApplicationViews views = new ApplicationViews(policy, new AccountMapperImpl(), new InventorMapperImpl(), documents, TestData.CLOCK);
        service = new WorkflowService(new ApplicationAccess(applications, policy), policy, applications, users, documents,
                statusEvents, new FilingRequirements(documents), history, notifications, audit, views, TestData.CLOCK);
        owner = TestData.inventor("Ada");
        examiner = TestData.examiner("Grace");
        lenient().when(applications.saveAndFlush(any(PatentApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
        lenient().when(users.findById(examiner.getId())).thenReturn(Optional.of(examiner));
    }

    private PatentApplication stored(ApplicationStatus status, User assignee) {
        PatentApplication app = TestData.application(owner, status);
        if (assignee != null) {
            TestData.assigned(app, assignee);
        }
        given(applications.findWithPartiesById(app.getId())).willReturn(Optional.of(app));
        return app;
    }

    private static WorkflowActionRequest request(WorkflowAction action, String note) {
        return new WorkflowActionRequest(action, note, null, null, 3L);
    }

    @Test
    void perform_claim_assignsExaminerAndArtUnitWithoutStatusChange() {
        PatentApplication app = stored(ApplicationStatus.SUBMITTED, null);
        app.setArtUnit(null);

        ApplicationDetail detail = service.perform(TestData.viewer(examiner), app.getId(), request(WorkflowAction.CLAIM, null));

        assertThat(detail.status()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(detail.examiner().displayName()).isEqualTo("Grace");
        assertThat(detail.artUnit()).isEqualTo("2100");
        verify(history).record(eq(app), eq("CLAIM"), eq(ApplicationStatus.SUBMITTED), isNull(), eq("Assigned to examiner Grace"),
                isNull(), eq(examiner), eq(HistoryService.ROLE_EXAMINER));
        verify(notifications).notify(eq(owner.getId()), eq(app), eq("CLAIM"), anyString(), anyString());
        verify(audit).record(eq(AuditAction.WORKFLOW_ACTION), any(Viewer.class), eq(ApplicationService.RESOURCE), eq(app.getId()), any());
    }

    @Test
    void perform_release_clearsExaminerAndNotifiesOwner() {
        PatentApplication app = stored(ApplicationStatus.DOCKETED, examiner);

        ApplicationDetail detail = service.perform(TestData.viewer(examiner), app.getId(), request(WorkflowAction.RELEASE, null));

        assertThat(detail.examiner()).isNull();
        verify(notifications).notify(eq(owner.getId()), eq(app), eq("RELEASE"), anyString(), anyString());
    }

    @Test
    void perform_noteRequiredButMissing_throws422() {
        PatentApplication app = stored(ApplicationStatus.UNDER_EXAMINATION, examiner);

        assertThatThrownBy(() -> service.perform(TestData.viewer(examiner), app.getId(),
                request(WorkflowAction.ISSUE_NON_FINAL_REJECTION, "  ")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors().getFirst().field()).isEqualTo("note"));
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.UNDER_EXAMINATION);
    }

    @Test
    void perform_staleVersion_throwsConflict() {
        PatentApplication app = stored(ApplicationStatus.SUBMITTED, null);

        assertThatThrownBy(() -> service.perform(TestData.viewer(examiner), app.getId(),
                new WorkflowActionRequest(WorkflowAction.START_PRE_EXAM, null, null, null, 1L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void perform_notAllowedForRole_throwsForbidden() {
        PatentApplication app = stored(ApplicationStatus.SUBMITTED, null);

        assertThatThrownBy(() -> service.perform(TestData.viewer(owner), app.getId(), request(WorkflowAction.START_PRE_EXAM, null)))
                .isInstanceOf(ForbiddenException.class);
        verify(applications, never()).saveAndFlush(any());
    }

    @Test
    void perform_invisibleApplication_throwsNotFound() {
        PatentApplication app = stored(ApplicationStatus.DRAFT, null);

        assertThatThrownBy(() -> service.perform(TestData.viewer(examiner), app.getId(), request(WorkflowAction.CLAIM, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void perform_rejectionWithAttachment_changesStatusAndNotesAttachment() {
        PatentApplication app = stored(ApplicationStatus.UNDER_EXAMINATION, examiner);
        Document ctnf = TestData.document(app, DocumentCode.CTNF, DocumentStatus.UPLOADED, examiner);
        given(documents.findAllById(any())).willReturn(List.of(ctnf));

        ApplicationDetail detail = service.perform(TestData.viewer(examiner), app.getId(),
                new WorkflowActionRequest(WorkflowAction.ISSUE_NON_FINAL_REJECTION, "Claims 1-3 anticipated", null, List.of(ctnf.getId()), 3L));

        assertThat(detail.status()).isEqualTo(ApplicationStatus.NON_FINAL_REJECTION);
        verify(history).record(eq(app), eq("ISSUE_NON_FINAL_REJECTION"), eq(ApplicationStatus.UNDER_EXAMINATION),
                eq(ApplicationStatus.NON_FINAL_REJECTION), eq("Non-final rejection mailed (CTNF attached)"),
                eq("Claims 1-3 anticipated"), eq(examiner), eq(HistoryService.ROLE_EXAMINER));
    }

    @Test
    void perform_attachmentWithWrongCode_throws422() {
        PatentApplication app = stored(ApplicationStatus.UNDER_EXAMINATION, examiner);
        Document noa = TestData.document(app, DocumentCode.NOA, DocumentStatus.UPLOADED, examiner);
        given(documents.findAllById(any())).willReturn(List.of(noa));

        assertThatThrownBy(() -> service.perform(TestData.viewer(examiner), app.getId(),
                new WorkflowActionRequest(WorkflowAction.ISSUE_FINAL_REJECTION, "Final", null, List.of(noa.getId()), 3L)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void perform_attachmentOfOtherApplicationOrNotUploaded_throws422() {
        PatentApplication app = stored(ApplicationStatus.UNDER_EXAMINATION, examiner);
        PatentApplication other = TestData.application(owner, ApplicationStatus.UNDER_EXAMINATION);
        Document foreign = TestData.document(other, DocumentCode.CTNF, DocumentStatus.UPLOADED, examiner);
        Document pending = TestData.document(app, DocumentCode.CTNF, DocumentStatus.PENDING_UPLOAD, examiner);
        UUID missing = UUID.randomUUID();
        given(documents.findAllById(any())).willReturn(List.of(foreign, pending));

        assertThatThrownBy(() -> service.perform(TestData.viewer(examiner), app.getId(), new WorkflowActionRequest(
                WorkflowAction.ISSUE_NON_FINAL_REJECTION, "x", null, List.of(foreign.getId(), pending.getId(), missing), 3L)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors()).hasSize(3));
    }

    @Test
    void perform_documentsOnActionThatAcceptsNone_throws422() {
        PatentApplication app = stored(ApplicationStatus.SUBMITTED, null);

        assertThatThrownBy(() -> service.perform(TestData.viewer(examiner), app.getId(), new WorkflowActionRequest(
                WorkflowAction.START_PRE_EXAM, null, null, List.of(UUID.randomUUID()), 3L)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void perform_fileResponseWithoutNewResp_throws422() {
        PatentApplication app = stored(ApplicationStatus.NON_FINAL_REJECTION, examiner);
        StatusEvent rejection = new StatusEvent();
        rejection.setCreatedAt(TestData.NOW.minus(Duration.ofDays(3)));
        given(statusEvents.findFirstByApplicationIdAndToStatusOrderByCreatedAtDescIdDesc(app.getId(), ApplicationStatus.NON_FINAL_REJECTION))
                .willReturn(Optional.of(rejection));
        given(documents.existsByApplication_IdAndDocCodeAndStatusAndUploadedAtAfter(app.getId(), DocumentCode.RESP,
                DocumentStatus.UPLOADED, rejection.getCreatedAt())).willReturn(false);

        assertThatThrownBy(() -> service.perform(TestData.viewer(owner), app.getId(), request(WorkflowAction.FILE_RESPONSE, "Amended")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Response to Office Action (RESP)");
    }

    @Test
    void perform_fileResponseWithResp_movesToResponseFiledAndNotifiesExaminer() {
        PatentApplication app = stored(ApplicationStatus.FINAL_REJECTION, examiner);
        given(statusEvents.findFirstByApplicationIdAndToStatusOrderByCreatedAtDescIdDesc(any(), any())).willReturn(Optional.empty());
        given(documents.existsByApplication_IdAndDocCodeAndStatusAndUploadedAtAfter(app.getId(), DocumentCode.RESP,
                DocumentStatus.UPLOADED, Instant.EPOCH)).willReturn(true);

        ApplicationDetail detail = service.perform(TestData.viewer(owner), app.getId(), request(WorkflowAction.FILE_RESPONSE, "Amended"));

        assertThat(detail.status()).isEqualTo(ApplicationStatus.RESPONSE_FILED);
        verify(notifications).notify(eq(examiner.getId()), eq(app), eq("FILE_RESPONSE"), anyString(), anyString());
        verify(history).record(eq(app), eq("FILE_RESPONSE"), any(), any(), anyString(), eq("Amended"), eq(owner),
                eq(HistoryService.ROLE_INVENTOR));
    }

    @Test
    void perform_docket_assignsActorAndRequestedArtUnit() {
        PatentApplication app = stored(ApplicationStatus.PRE_EXAM, null);

        ApplicationDetail detail = service.perform(TestData.viewer(examiner), app.getId(),
                new WorkflowActionRequest(WorkflowAction.DOCKET, null, "3600", null, 3L));

        assertThat(detail.status()).isEqualTo(ApplicationStatus.DOCKETED);
        assertThat(detail.examiner().id()).isEqualTo(examiner.getId());
        assertThat(detail.artUnit()).isEqualTo("3600");
    }

    @Test
    void perform_publish_assignsPublicationNumber() {
        PatentApplication app = stored(ApplicationStatus.DOCKETED, examiner);
        given(applications.nextPublicationSerial()).willReturn(12345L);

        ApplicationDetail detail = service.perform(TestData.viewer(examiner), app.getId(), request(WorkflowAction.PUBLISH, null));

        assertThat(detail.published()).isTrue();
        assertThat(detail.publicationNumber()).isEqualTo("US20260012345A1");
        assertThat(detail.publicationDate()).isEqualTo(TestData.TODAY);
        assertThat(detail.status()).isEqualTo(ApplicationStatus.DOCKETED);
    }

    @Test
    void perform_issuePatent_neverPublished_isB1AndAutoPublishes() {
        PatentApplication app = stored(ApplicationStatus.ALLOWED, examiner);
        given(applications.nextPatentSerial()).willReturn(12500001L);

        ApplicationDetail detail = service.perform(TestData.viewer(examiner), app.getId(), request(WorkflowAction.ISSUE_PATENT, null));

        assertThat(detail.status()).isEqualTo(ApplicationStatus.ISSUED);
        assertThat(detail.patentNumber()).isEqualTo("US12500001B1");
        assertThat(detail.issueDate()).isEqualTo(TestData.TODAY);
        assertThat(detail.published()).isTrue();
        assertThat(detail.availableActions()).isEmpty();
    }

    @Test
    void perform_issuePatent_previouslyPublished_isB2() {
        PatentApplication app = stored(ApplicationStatus.ALLOWED, examiner);
        app.setPublished(true);
        given(applications.nextPatentSerial()).willReturn(12500002L);
        User root = TestData.admin("Root");
        given(users.findById(root.getId())).willReturn(Optional.of(root));

        assertThat(service.perform(TestData.viewer(root), app.getId(), request(WorkflowAction.ISSUE_PATENT, null))
                .patentNumber()).isEqualTo("US12500002B2");
    }

    @Test
    void perform_cureMissingPartsStillIncomplete_throws422() {
        PatentApplication app = stored(ApplicationStatus.INCOMPLETE, null);
        given(documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED)).willReturn(List.of());

        assertThatThrownBy(() -> service.perform(TestData.viewer(owner), app.getId(), request(WorkflowAction.CURE_MISSING_PARTS, null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void perform_cureMissingPartsComplete_returnsToPreExam() {
        PatentApplication app = stored(ApplicationStatus.INCOMPLETE, null);
        given(documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED)).willReturn(List.of(
                TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner),
                TestData.document(app, DocumentCode.CLM, DocumentStatus.UPLOADED, owner),
                TestData.document(app, DocumentCode.ABST, DocumentStatus.UPLOADED, owner)));

        assertThat(service.perform(TestData.viewer(owner), app.getId(), request(WorkflowAction.CURE_MISSING_PARTS, null)).status())
                .isEqualTo(ApplicationStatus.PRE_EXAM);
        verify(notifications, never()).notify(any(), any(), any(), any(), any()); // unassigned: nobody to notify
    }

    @Test
    void perform_expressAbandonmentByOwner_isTerminal() {
        PatentApplication app = stored(ApplicationStatus.UNDER_EXAMINATION, examiner);

        ApplicationDetail detail = service.perform(TestData.viewer(owner), app.getId(), request(WorkflowAction.ABANDON, "No longer pursuing"));

        assertThat(detail.status()).isEqualTo(ApplicationStatus.ABANDONED);
        verify(history).record(eq(app), eq("ABANDON"), any(), eq(ApplicationStatus.ABANDONED),
                eq("Abandoned (express abandonment by the applicant)"), anyString(), eq(owner), eq(HistoryService.ROLE_INVENTOR));
    }

    @Test
    void perform_startExaminationAndAllowByAdmin_recordsAdminRole() {
        User admin = gov.openfiling.api.service.TestData.user("Root", gov.openfiling.api.model.Role.ADMIN);
        given(users.findById(admin.getId())).willReturn(Optional.of(admin));
        PatentApplication app = stored(ApplicationStatus.UNDER_EXAMINATION, examiner);

        service.perform(TestData.viewer(admin), app.getId(), request(WorkflowAction.ALLOW, null));

        verify(history).record(eq(app), eq("ALLOW"), any(), eq(ApplicationStatus.ALLOWED), eq("Notice of allowance mailed"), isNull(),
                eq(admin), eq(HistoryService.ROLE_ADMIN));
    }
}
