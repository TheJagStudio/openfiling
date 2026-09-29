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
import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.CreateApplicationRequest;
import gov.openfiling.api.dto.FilingReceipt;
import gov.openfiling.api.dto.InventorDto;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.SubmitRequest;
import gov.openfiling.api.dto.UpdateApplicationRequest;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.FieldProblem;
import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapperImpl;
import gov.openfiling.api.mapper.InventorMapperImpl;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.EntityStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    @Mock
    ApplicationRepository applications;

    @Mock
    UserRepository users;

    @Mock
    DocumentRepository documents;

    @Mock
    HistoryService history;

    @Mock
    NotificationService notifications;

    @Mock
    DocumentService documentService;

    @Mock
    AuditService audit;

    ApplicationService service;
    User owner;
    Viewer ownerViewer;

    @BeforeEach
    void setUp() {
        WorkflowPolicy policy = new WorkflowPolicy();
        ApplicationViews views = new ApplicationViews(policy, new AccountMapperImpl(), new InventorMapperImpl(), documents, TestData.CLOCK);
        service = new ApplicationService(applications, users, documents, new ApplicationAccess(applications, policy), views, policy,
                new InventorMapperImpl(), new FilingRequirements(documents), history, notifications, documentService, audit,
                TestData.CLOCK);
        owner = TestData.inventor("Ada");
        ownerViewer = TestData.viewer(owner);
        lenient().when(applications.saveAndFlush(any(PatentApplication.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PatentApplication stored(PatentApplication app) {
        given(applications.findWithPartiesById(app.getId())).willReturn(Optional.of(app));
        return app;
    }

    private static CreateApplicationRequest createRequest() {
        return new CreateApplicationRequest("  Solid-state battery electrolyte  ", ApplicationType.UTILITY, EntityStatus.SMALL,
                "An electrolyte.", " ", "ACME-1",
                List.of(new InventorDto(" Ada ", "Lovelace", "Alexandria", "VA", "us", "us"),
                        new InventorDto("Kenji", "Watanabe", null, null, "JP", null)));
    }

    @Test
    void create_valid_persistsDraftWithInventorsHistoryAndAudit() {
        given(users.findById(owner.getId())).willReturn(Optional.of(owner));

        ApplicationDetail detail = service.create(ownerViewer, createRequest());

        ArgumentCaptor<PatentApplication> saved = ArgumentCaptor.forClass(PatentApplication.class);
        verify(applications).saveAndFlush(saved.capture());
        PatentApplication app = saved.getValue();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app.getTitle()).isEqualTo("Solid-state battery electrolyte");
        assertThat(app.getTechnologyField()).isNull();
        assertThat(app.getInventors()).hasSize(2);
        assertThat(app.getInventors().getFirst().getGivenName()).isEqualTo("Ada");
        assertThat(app.getInventors().getFirst().getCountry()).isEqualTo("US");
        assertThat(app.getInventors().get(1).getSequence()).isEqualTo(2);
        assertThat(detail.firstNamedInventor()).isEqualTo("Ada Lovelace");
        assertThat(detail.permissions().canSubmit()).isTrue();
        assertThat(detail.applicationNumber()).isNull();
        verify(history).record(eq(app), eq(StatusEvent.CREATED), isNull(), eq(ApplicationStatus.DRAFT), anyString(), isNull(),
                eq(owner), eq(HistoryService.ROLE_INVENTOR));
        verify(audit).record(eq(AuditAction.APPLICATION_CREATED), eq(ownerViewer), eq(ApplicationService.RESOURCE), eq(app.getId()), any());
    }

    @Test
    void get_ownerSeesDraft() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));

        assertThat(service.get(ownerViewer, app.getId()).status()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void get_otherInventor_throwsNotFound() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.SUBMITTED));
        Viewer stranger = TestData.viewer(TestData.inventor("Mallory"));

        assertThatThrownBy(() -> service.get(stranger, app.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void get_examinerOnDraft_throwsNotFound() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));

        assertThatThrownBy(() -> service.get(TestData.viewer(TestData.examiner("Grace")), app.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void get_examinerOnFiled_seesDetailWithActionsAndPendency() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.SUBMITTED));

        ApplicationDetail detail = service.get(TestData.viewer(TestData.examiner("Grace")), app.getId());

        assertThat(detail.applicationNumberDisplay()).isEqualTo("19/000,123");
        assertThat(detail.daysPending()).isEqualTo(12);
        assertThat(detail.permissions().canEdit()).isFalse();
        assertThat(detail.availableActions()).isNotEmpty();
    }

    @Test
    void get_missing_throwsNotFound() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        given(applications.findWithPartiesById(app.getId())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ownerViewer, app.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_ownApplications_mapsSummariesWithDocumentCounts() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.NON_FINAL_REJECTION);
        given(applications.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(app), PageRequest.of(0, 20), 1));
        given(documents.countActiveByApplications(any())).willReturn(List.of());

        PageResponse<ApplicationSummary> page = service.list(ownerViewer, "battery", List.of(ApplicationStatus.NON_FINAL_REJECTION),
                null, PageParams.of(0, 20, "title,asc"));

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().getFirst().actionRequired()).isTrue();
        assertThat(page.content().getFirst().documentCount()).isZero();
    }

    @Test
    void list_unknownSortField_throwsBadRequest() {
        assertThatThrownBy(() -> service.list(ownerViewer, null, null, null, PageParams.of(0, 20, "owner,asc")))
                .isInstanceOf(gov.openfiling.api.exception.BadRequestException.class);
    }

    private UpdateApplicationRequest updateRequest(long version, ApplicationType type) {
        return new UpdateApplicationRequest("New title for the battery", type, EntityStatus.MICRO, null, "Energy", null,
                List.of(new InventorDto("Grace", "Hopper", null, null, "US", "US")), version);
    }

    @Test
    void update_ownerDraftCurrentVersion_appliesChangesInPlace() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));
        var inventorBefore = app.getInventors().getFirst();

        ApplicationDetail detail = service.update(ownerViewer, app.getId(), updateRequest(3, ApplicationType.UTILITY));

        assertThat(detail.title()).isEqualTo("New title for the battery");
        assertThat(detail.entityStatus()).isEqualTo(EntityStatus.MICRO);
        assertThat(app.getInventors()).singleElement().isSameAs(inventorBefore);
        assertThat(inventorBefore.getGivenName()).isEqualTo("Grace");
        verify(audit).record(eq(AuditAction.APPLICATION_UPDATED), eq(ownerViewer), any(), any(), any());
    }

    @Test
    void update_staleVersion_throwsConflict() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));

        assertThatThrownBy(() -> service.update(ownerViewer, app.getId(), updateRequest(2, ApplicationType.UTILITY)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_filedPendingApplication_throwsConflict() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.PRE_EXAM));

        assertThatThrownBy(() -> service.update(ownerViewer, app.getId(), updateRequest(3, ApplicationType.UTILITY)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_incompleteTypeChange_throws422() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.INCOMPLETE));

        assertThatThrownBy(() -> service.update(ownerViewer, app.getId(), updateRequest(3, ApplicationType.DESIGN)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void update_examiner_throwsForbidden() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.INCOMPLETE));

        assertThatThrownBy(() -> service.update(TestData.viewer(TestData.examiner("G")), app.getId(), updateRequest(3, ApplicationType.UTILITY)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void delete_draft_purgesDocumentsDeletesAndAudits() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));
        given(documentService.purgeForApplication(app.getId())).willReturn(2);

        service.delete(ownerViewer, app.getId());

        verify(applications).deleteById(app.getId());
        verify(audit).record(eq(AuditAction.APPLICATION_DELETED), eq(ownerViewer), any(), eq(app.getId()), any());
    }

    @Test
    void delete_filed_throwsConflict() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.SUBMITTED));

        assertThatThrownBy(() -> service.delete(ownerViewer, app.getId())).isInstanceOf(ConflictException.class);
        verify(applications, never()).deleteById(any());
    }

    private static SubmitRequest submit(long version) {
        return new SubmitRequest(version, true, "/Ada Lovelace/");
    }

    @Test
    void submit_missingRequiredDocuments_throws422ListingEachCode() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));
        given(documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED))
                .willReturn(List.of(TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner)));

        assertThatThrownBy(() -> service.submit(ownerViewer, app.getId(), submit(3)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors()).extracting(FieldProblem::message)
                        .containsExactly("Claims (CLM) is required", "Abstract (ABST) is required"));
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void submit_pendingOrRejectedDocument_throws422() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));
        app.setApplicationType(ApplicationType.PROVISIONAL);
        given(documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED)).willReturn(List.of(
                TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner),
                TestData.document(app, DocumentCode.DRW, DocumentStatus.PENDING_UPLOAD, owner),
                TestData.document(app, DocumentCode.MISC, DocumentStatus.REJECTED, owner)));

        assertThatThrownBy(() -> service.submit(ownerViewer, app.getId(), submit(3)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors()).hasSize(2)
                        .allSatisfy(p -> assertThat(p.field()).isEqualTo("documents")));
    }

    @Test
    void submit_complete_assignsNumberConfirmationFilingDateAndNotifies() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));
        app.setApplicationType(ApplicationType.DESIGN);
        given(documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED)).willReturn(List.of(
                TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner),
                TestData.document(app, DocumentCode.DRW, DocumentStatus.UPLOADED, owner)));
        given(applications.nextApplicationSerial()).willReturn(123L);

        ApplicationDetail detail = service.submit(ownerViewer, app.getId(), submit(3));

        assertThat(detail.status()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(detail.applicationNumber()).isEqualTo("29000123");
        assertThat(detail.applicationNumberDisplay()).isEqualTo("29/000,123");
        assertThat(detail.confirmationNumber()).matches("\\d{4}");
        assertThat(detail.filingDate()).isEqualTo(TestData.TODAY);
        assertThat(detail.submittedAt()).isEqualTo(TestData.NOW);
        assertThat(detail.signature()).isEqualTo("/Ada Lovelace/");
        verify(history).record(eq(app), eq(StatusEvent.SUBMIT), eq(ApplicationStatus.DRAFT), eq(ApplicationStatus.SUBMITTED),
                anyString(), isNull(), eq(owner), eq(HistoryService.ROLE_INVENTOR));
        verify(notifications).notify(eq(owner.getId()), eq(app), eq(StatusEvent.SUBMIT), anyString(), anyString());
        verify(audit).record(eq(AuditAction.APPLICATION_SUBMITTED), eq(ownerViewer), any(), eq(app.getId()), any());
    }

    @Test
    void submit_alreadyFiled_throwsConflict() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.SUBMITTED));

        assertThatThrownBy(() -> service.submit(ownerViewer, app.getId(), submit(3))).isInstanceOf(ConflictException.class);
    }

    @Test
    void submit_staleVersion_throwsConflict() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));

        assertThatThrownBy(() -> service.submit(ownerViewer, app.getId(), submit(1))).isInstanceOf(ConflictException.class);
    }

    @Test
    void receipt_draft_throwsConflict() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.DRAFT));

        assertThatThrownBy(() -> service.receipt(ownerViewer, app.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void receipt_filed_listsUploadedDocumentsAndApplicant() {
        PatentApplication app = stored(TestData.application(owner, ApplicationStatus.SUBMITTED));
        given(documents.findByApplication_IdAndStatus(app.getId(), DocumentStatus.UPLOADED)).willReturn(List.of(
                TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner)));

        FilingReceipt receipt = service.receipt(ownerViewer, app.getId());

        assertThat(receipt.applicationNumberDisplay()).isEqualTo("19/000,123");
        assertThat(receipt.applicant().email()).isEqualTo(owner.getEmail());
        assertThat(receipt.documents()).singleElement().satisfies(d -> assertThat(d.docCodeLabel()).isEqualTo("Specification"));
        assertThat(receipt.inventors()).hasSize(1);
    }
}
