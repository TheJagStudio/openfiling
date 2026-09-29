package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.CreateUploadRequest;
import gov.openfiling.api.dto.DocumentResponse;
import gov.openfiling.api.dto.DownloadResponse;
import gov.openfiling.api.dto.UploadInitResponse;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.DocumentRejectedException;
import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.exception.UpstreamUnavailableException;
import gov.openfiling.api.mapper.AccountMapperImpl;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.DocumentVisibility;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.Redaction;
import gov.openfiling.api.model.RedactionStatus;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.RedactionRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;
import gov.openfiling.api.storage.DocumentStorage;
import gov.openfiling.api.storage.RedactionInvoker;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    static final AppProperties.Storage PROPS = new AppProperties.Storage("http://localhost:9000", "", "us-east-1", "k", "s", true,
            "openfiling-documents", "openfiling-public-copies", Duration.ofMinutes(15), Duration.ofMinutes(5), 10_000, 3,
            Duration.ofHours(1));

    @Mock
    DocumentRepository documents;

    @Mock
    RedactionRepository redactions;

    @Mock
    UserRepository users;

    @Mock
    ApplicationRepository applications;

    @Mock
    DocumentStorage storage;

    @Mock
    RedactionInvoker invoker;

    @Mock
    HistoryService history;

    @Mock
    NotificationService notifications;

    @Mock
    AuditService audit;

    DocumentService service;
    User owner;
    User examiner;
    Viewer ownerViewer;

    @BeforeEach
    void setUp() {
        WorkflowPolicy policy = new WorkflowPolicy();
        service = new DocumentService(documents, redactions, users, new ApplicationAccess(applications, policy), policy,
                new DocumentVerifier(storage, PROPS), new DocumentViews(redactions, new AccountMapperImpl()), storage, invoker, history,
                notifications, audit, PROPS, TestData.CLOCK);
        owner = TestData.inventor("Ada");
        examiner = TestData.examiner("Grace");
        ownerViewer = TestData.viewer(owner);
        lenient().when(documents.saveAndFlush(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PatentApplication storedApp(ApplicationStatus status) {
        PatentApplication app = TestData.application(owner, status);
        given(applications.findWithPartiesById(app.getId())).willReturn(Optional.of(app));
        return app;
    }

    private static CreateUploadRequest upload(DocumentCode code, long size) {
        return new CreateUploadRequest(code, "C:\\scans\\My Spec (v2).PDF", "application/pdf", size, null, " Main spec ");
    }

    // ---- initiate upload ----------------------------------------------------------------------

    @Test
    void initiateUpload_ownerDraft_createsPendingDocumentAndPresignsPut() {
        PatentApplication app = storedApp(ApplicationStatus.DRAFT);
        given(users.findById(owner.getId())).willReturn(Optional.of(owner));
        given(storage.presignUpload(eq("openfiling-documents"), anyString(), eq("application/pdf"), eq(Duration.ofMinutes(15))))
                .willReturn(new DocumentStorage.PresignedUrl("http://s3/put?sig", "PUT", Map.of("Content-Type", "application/pdf"),
                        TestData.NOW.plus(Duration.ofMinutes(15))));

        UploadInitResponse response = service.initiateUpload(ownerViewer, app.getId(), upload(DocumentCode.SPEC, 1234));

        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documents).saveAndFlush(saved.capture());
        Document doc = saved.getValue();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING_UPLOAD);
        assertThat(doc.getVisibility()).isEqualTo(DocumentVisibility.PUBLIC);
        assertThat(doc.getStorageKey()).isEqualTo("public/" + app.getId() + "/" + doc.getId() + "/My-Spec-v2.pdf");
        assertThat(doc.getFileName()).isEqualTo("My Spec (v2).PDF");
        assertThat(doc.getDescription()).isEqualTo("Main spec");
        assertThat(doc.getUploadExpiresAt()).isEqualTo(TestData.NOW.plus(Duration.ofMinutes(15)));
        assertThat(response.upload().method()).isEqualTo("PUT");
        assertThat(response.upload().headers()).containsEntry("Content-Type", "application/pdf");
        assertThat(response.upload().url()).isEqualTo("http://s3/put?sig");
        assertThat(response.document().status()).isEqualTo(DocumentStatus.PENDING_UPLOAD);
        verify(audit).record(eq(AuditAction.DOCUMENT_UPLOAD_URL_ISSUED), eq(ownerViewer), eq(DocumentService.RESOURCE), eq(doc.getId()), any());
    }

    @Test
    void initiateUpload_restrictedCodeOrOverride_usesRestrictedPrefix() {
        PatentApplication app = storedApp(ApplicationStatus.DRAFT);
        given(users.findById(owner.getId())).willReturn(Optional.of(owner));
        given(storage.presignUpload(anyString(), anyString(), anyString(), any()))
                .willReturn(new DocumentStorage.PresignedUrl("u", "PUT", Map.of(), TestData.NOW));

        service.initiateUpload(ownerViewer, app.getId(), upload(DocumentCode.OATH, 10));

        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documents).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStorageKey()).startsWith("restricted/");
        assertThat(saved.getValue().getVisibility()).isEqualTo(DocumentVisibility.RESTRICTED);
    }

    @Test
    void initiateUpload_codeNotAllowedInStatus_throwsForbidden() {
        PatentApplication app = storedApp(ApplicationStatus.PRE_EXAM);

        assertThatThrownBy(() -> service.initiateUpload(ownerViewer, app.getId(), upload(DocumentCode.SPEC, 10)))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void initiateUpload_tooLarge_throws422OnSizeBytes() {
        PatentApplication app = storedApp(ApplicationStatus.DRAFT);

        assertThatThrownBy(() -> service.initiateUpload(ownerViewer, app.getId(), upload(DocumentCode.SPEC, 10_001)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors().getFirst().field()).isEqualTo("sizeBytes"));
    }

    @Test
    void initiateUpload_tooManyDocuments_throws422() {
        PatentApplication app = storedApp(ApplicationStatus.DRAFT);
        given(documents.countByApplication_IdAndStatusNot(app.getId(), DocumentStatus.EXPIRED)).willReturn(3L);

        assertThatThrownBy(() -> service.initiateUpload(ownerViewer, app.getId(), upload(DocumentCode.SPEC, 10)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors().getFirst().field()).isEqualTo("documents"));
    }

    @Test
    void initiateUpload_invisibleApplication_throwsNotFound() {
        PatentApplication app = storedApp(ApplicationStatus.DRAFT);

        assertThatThrownBy(() -> service.initiateUpload(TestData.viewer(examiner), app.getId(), upload(DocumentCode.SPEC, 10)))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- complete ---------------------------------------------------------------------------

    private Document lockedDocument(PatentApplication app, DocumentCode code, DocumentStatus status, User uploader) {
        Document doc = TestData.document(app, code, status, uploader);
        given(documents.lockById(doc.getId())).willReturn(Optional.of(doc));
        return doc;
    }

    private void objectIs(Document doc, long size, String contentType, String magic) {
        given(storage.head(doc.getStorageBucket(), doc.getStorageKey()))
                .willReturn(Optional.of(new DocumentStorage.ObjectHead(size, contentType, "etag-1", TestData.NOW)));
        lenient().when(storage.readPrefix(doc.getStorageBucket(), doc.getStorageKey(), 5))
                .thenReturn(magic.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void complete_validPdf_marksUploadedStartsRedactionAndRecordsHistory() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        objectIs(doc, 1234, "application/pdf", "%PDF-");

        DocumentResponse response = service.complete(ownerViewer, doc.getId());

        assertThat(response.status()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(doc.getEtag()).isEqualTo("etag-1");
        assertThat(doc.getUploadedAt()).isEqualTo(TestData.NOW);
        ArgumentCaptor<Redaction> redaction = ArgumentCaptor.forClass(Redaction.class);
        verify(redactions).save(redaction.capture());
        assertThat(redaction.getValue().getStatus()).isEqualTo(RedactionStatus.PENDING);
        verify(history).record(eq(app), eq(StatusEvent.DOCUMENT_UPLOADED), eq(ApplicationStatus.DRAFT), any(),
                eq("Specification (SPEC) uploaded: spec.pdf"), any(), eq(owner), eq(HistoryService.ROLE_INVENTOR));
        verify(notifications, never()).notify(any(), any(), any(), any(), any()); // drafts: nobody to tell
        verify(audit).record(eq(AuditAction.DOCUMENT_UPLOADED), eq(owner.getId()), eq(owner.getEmail()), any(), eq(doc.getId()), any());
    }

    @Test
    void complete_filedApplication_notifiesAssignedExaminer() {
        PatentApplication app = TestData.assigned(TestData.application(owner, ApplicationStatus.PRE_EXAM), examiner);
        Document doc = lockedDocument(app, DocumentCode.IDS, DocumentStatus.PENDING_UPLOAD, owner);
        objectIs(doc, 1234, null, "%PDF-");
        given(redactions.existsById(doc.getId())).willReturn(true); // callback already created the row

        service.complete(ownerViewer, doc.getId());

        verify(redactions, never()).save(any());
        verify(notifications).notify(eq(examiner.getId()), eq(app), eq(StatusEvent.DOCUMENT_UPLOADED), anyString(), anyString());
    }

    @Test
    void complete_sizeMismatch_rejectsDeletesObjectAndThrows422() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        objectIs(doc, 999, "application/pdf", "%PDF-");

        assertThatThrownBy(() -> service.complete(ownerViewer, doc.getId()))
                .isInstanceOf(DocumentRejectedException.class)
                .hasMessageContaining("does not match the declared size");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(doc.getRejectionReason()).contains("999");
        verify(storage).delete(doc.getStorageBucket(), doc.getStorageKey());
        verify(audit).record(eq(AuditAction.DOCUMENT_REJECTED), eq(owner.getId()), any(), any(), eq(doc.getId()), any());
    }

    @Test
    void complete_badMagicBytes_rejects() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        objectIs(doc, 1234, "application/pdf", "MZ\u0090\u0000\u0003");

        assertThatThrownBy(() -> service.complete(ownerViewer, doc.getId()))
                .isInstanceOf(DocumentRejectedException.class)
                .hasMessageContaining("not a PDF");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.REJECTED);
    }

    @Test
    void complete_overLimitOrWrongContentType_rejects() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document big = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        objectIs(big, 20_000, "application/pdf", "%PDF-");
        Document html = lockedDocument(app, DocumentCode.CLM, DocumentStatus.PENDING_UPLOAD, owner);
        objectIs(html, 1234, "text/html", "%PDF-");

        assertThatThrownBy(() -> service.complete(ownerViewer, big.getId())).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.complete(ownerViewer, html.getId())).hasMessageContaining("Content-Type");
    }

    @Test
    void complete_rejectedObjectDeleteFails_stillRejects() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        objectIs(doc, 5, "application/pdf", "%PDF-");
        willThrow(new UpstreamUnavailableException("down", null)).given(storage).delete(anyString(), anyString());

        assertThatThrownBy(() -> service.complete(ownerViewer, doc.getId())).isInstanceOf(DocumentRejectedException.class);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.REJECTED);
    }

    @Test
    void complete_alreadyUploaded_isIdempotentWithoutTouchingStorage() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);

        assertThat(service.complete(ownerViewer, doc.getId()).status()).isEqualTo(DocumentStatus.UPLOADED);
        verifyNoInteractions(storage);
    }

    @Test
    void complete_alreadyRejected_repeats422() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.REJECTED, owner);
        doc.setRejectionReason("bad");

        assertThatThrownBy(() -> service.complete(ownerViewer, doc.getId())).isInstanceOf(DocumentRejectedException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void complete_expired_throwsConflict() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.EXPIRED, owner);

        assertThatThrownBy(() -> service.complete(ownerViewer, doc.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void complete_objectMissing_throwsConflictAndStaysPending() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        given(storage.head(anyString(), anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(ownerViewer, doc.getId())).isInstanceOf(ConflictException.class);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING_UPLOAD);
    }

    @Test
    void complete_notTheUploader_throwsForbidden() {
        PatentApplication app = TestData.assigned(TestData.application(owner, ApplicationStatus.PRE_EXAM), examiner);
        Document doc = lockedDocument(app, DocumentCode.IDS, DocumentStatus.PENDING_UPLOAD, owner);

        assertThatThrownBy(() -> service.complete(TestData.viewer(examiner), doc.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void complete_invisible_throwsNotFound() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = lockedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);

        assertThatThrownBy(() -> service.complete(TestData.viewer(TestData.inventor("Mallory")), doc.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- storage events -------------------------------------------------------------------------

    @Test
    void completeFromStorageEvent_pendingDocument_verifiesAsSystem() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = TestData.document(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        given(documents.lockByStorageKey(doc.getStorageKey())).willReturn(Optional.of(doc));
        objectIs(doc, 1234, "application/pdf", "%PDF-");

        assertThat(service.completeFromStorageEvent(doc.getStorageKey())).isEqualTo(DocumentService.EventOutcome.VERIFIED);
        verify(audit).record(eq(AuditAction.DOCUMENT_UPLOADED), eq(null), eq(AuditService.SYSTEM), any(), eq(doc.getId()), any());
    }

    @Test
    void completeFromStorageEvent_badFile_rejectsWithoutThrowing() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = TestData.document(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        given(documents.lockByStorageKey(doc.getStorageKey())).willReturn(Optional.of(doc));
        objectIs(doc, 1234, "application/pdf", "GIF89");

        assertThat(service.completeFromStorageEvent(doc.getStorageKey())).isEqualTo(DocumentService.EventOutcome.REJECTED);
    }

    @Test
    void completeFromStorageEvent_unknownOrAlreadyUploaded_isIgnored() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document uploaded = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        given(documents.lockByStorageKey("unknown")).willReturn(Optional.empty());
        given(documents.lockByStorageKey(uploaded.getStorageKey())).willReturn(Optional.of(uploaded));

        assertThat(service.completeFromStorageEvent("unknown")).isEqualTo(DocumentService.EventOutcome.IGNORED);
        assertThat(service.completeFromStorageEvent(uploaded.getStorageKey())).isEqualTo(DocumentService.EventOutcome.IGNORED);
        verifyNoInteractions(storage);
    }

    @Test
    void completeFromStorageEvent_objectNotVisibleYet_isIgnored() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = TestData.document(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        given(documents.lockByStorageKey(doc.getStorageKey())).willReturn(Optional.of(doc));
        given(storage.head(anyString(), anyString())).willReturn(Optional.empty());

        assertThat(service.completeFromStorageEvent(doc.getStorageKey())).isEqualTo(DocumentService.EventOutcome.IGNORED);
    }

    // ---- read / download --------------------------------------------------------------------------

    private Document storedDocument(PatentApplication app, DocumentCode code, DocumentStatus status, User uploader) {
        Document doc = TestData.document(app, code, status, uploader);
        given(documents.findWithApplicationById(doc.getId())).willReturn(Optional.of(doc));
        return doc;
    }

    private Redaction completedRedaction(Document doc) {
        Redaction r = Redaction.pending(doc.getId());
        r.setStatus(RedactionStatus.COMPLETED);
        r.setPublicCopyKey("public/x/y/public-copy.pdf");
        r.setCategories("[{\"category\":\"SSN\",\"count\":2}]");
        r.setRedactionCount(2);
        return r;
    }

    @Test
    void list_visible_returnsDocumentsWithRedactions() {
        PatentApplication app = storedApp(ApplicationStatus.DRAFT);
        Document spec = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        Document oath = TestData.document(app, DocumentCode.OATH, DocumentStatus.UPLOADED, owner);
        given(documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED))
                .willReturn(List.of(spec, oath));
        given(redactions.findAllById(any())).willReturn(List.of(completedRedaction(spec)));

        List<DocumentResponse> list = service.list(ownerViewer, app.getId());

        assertThat(list).hasSize(2);
        assertThat(list.get(0).redaction().categories()).singleElement().satisfies(c -> assertThat(c.count()).isEqualTo(2));
        assertThat(list.get(0).redaction().publicCopyAvailable()).isTrue();
        assertThat(list.get(1).redaction()).isNull();
    }

    @Test
    void get_examinerOnDraftDocument_throwsNotFound() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);

        assertThatThrownBy(() -> service.get(TestData.viewer(examiner), doc.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void download_original_presignsGetAndAudits() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.PRE_EXAM);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        given(storage.presignDownload(doc.getStorageBucket(), doc.getStorageKey(), "spec.pdf", "application/pdf", Duration.ofMinutes(5)))
                .willReturn(new DocumentStorage.PresignedUrl("http://s3/get", "GET", Map.of(), TestData.NOW.plusSeconds(300)));

        DownloadResponse response = service.download(TestData.viewer(examiner), doc.getId(), "original");

        assertThat(response.url()).isEqualTo("http://s3/get");
        assertThat(response.fileName()).isEqualTo("spec.pdf");
        verify(audit).record(eq(AuditAction.DOCUMENT_DOWNLOAD), any(Viewer.class), any(), eq(doc.getId()), any());
    }

    @Test
    void download_publicCopyOfPublishedByStranger_isAllowed() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.UNDER_EXAMINATION);
        app.setPublished(true);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        given(redactions.findById(doc.getId())).willReturn(Optional.of(completedRedaction(doc)));
        given(storage.presignDownload(eq("openfiling-public-copies"), eq("public/x/y/public-copy.pdf"), eq("public-copy-spec.pdf"),
                eq("application/pdf"), any())).willReturn(new DocumentStorage.PresignedUrl("http://s3/pub", "GET", Map.of(), TestData.NOW));

        assertThat(service.download(TestData.viewer(TestData.inventor("Reader")), doc.getId(), "PUBLIC").url()).isEqualTo("http://s3/pub");
    }

    @Test
    void download_originalByStranger_throwsNotFound() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.UNDER_EXAMINATION);
        app.setPublished(true);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);

        assertThatThrownBy(() -> service.download(TestData.viewer(TestData.inventor("Reader")), doc.getId(), "original"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void download_publicCopyNotReady_throwsConflict() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        given(redactions.findById(doc.getId())).willReturn(Optional.of(Redaction.pending(doc.getId())));

        assertThatThrownBy(() -> service.download(ownerViewer, doc.getId(), "public")).isInstanceOf(ConflictException.class);
    }

    @Test
    void download_notUploaded_throwsConflict() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);

        assertThatThrownBy(() -> service.download(ownerViewer, doc.getId(), "original")).isInstanceOf(ConflictException.class);
    }

    // ---- delete -------------------------------------------------------------------------------------

    @Test
    void delete_ownerDraftUploaded_deletesRowsAndObjectsIncludingPublicCopy() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        given(redactions.findById(doc.getId())).willReturn(Optional.of(completedRedaction(doc)));

        service.delete(ownerViewer, doc.getId());

        verify(documents).deleteById(doc.getId());
        verify(storage).delete(doc.getStorageBucket(), doc.getStorageKey());
        verify(storage).delete("openfiling-public-copies", "public/x/y/public-copy.pdf");
        verify(audit).record(eq(AuditAction.DOCUMENT_DELETED), eq(ownerViewer), any(), eq(doc.getId()), any());
    }

    @Test
    void delete_uploadedOnFiledApplication_throwsConflict() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.PRE_EXAM);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);

        assertThatThrownBy(() -> service.delete(ownerViewer, doc.getId())).isInstanceOf(ConflictException.class);
        verify(documents, never()).deleteById(any());
    }

    @Test
    void delete_byNonOwnerExaminer_throwsForbidden() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.PRE_EXAM);
        Document doc = storedDocument(app, DocumentCode.IDS, DocumentStatus.PENDING_UPLOAD, owner);

        assertThatThrownBy(() -> service.delete(TestData.viewer(examiner), doc.getId())).isInstanceOf(ForbiddenException.class);
    }

    // ---- redaction retry ----------------------------------------------------------------------------

    @Test
    void retryRedaction_examinerOnUploadedPublic_resetsAndInvokes() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.PRE_EXAM);
        Document doc = storedDocument(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        Redaction failed = Redaction.pending(doc.getId());
        failed.setStatus(RedactionStatus.FAILED);
        failed.setError("boom");
        given(redactions.findById(doc.getId())).willReturn(Optional.of(failed));

        DocumentResponse response = service.retryRedaction(TestData.viewer(examiner), doc.getId());

        assertThat(response.redaction().status()).isEqualTo(RedactionStatus.PENDING);
        assertThat(failed.getError()).isNull();
        verify(invoker).invoke(doc.getStorageBucket(), doc.getStorageKey(), 1234, null);
        verify(audit).record(eq(AuditAction.REDACTION_RETRIED), any(Viewer.class), any(), eq(doc.getId()), any());
    }

    @Test
    void retryRedaction_inventor_throwsForbidden() {
        assertThatThrownBy(() -> service.retryRedaction(ownerViewer, java.util.UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void retryRedaction_restrictedDocument_throwsConflict() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.PRE_EXAM);
        Document doc = storedDocument(app, DocumentCode.OATH, DocumentStatus.UPLOADED, owner);

        assertThatThrownBy(() -> service.retryRedaction(TestData.viewer(examiner), doc.getId())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(invoker);
    }

    // ---- housekeeping --------------------------------------------------------------------------------

    @Test
    void expireStaleUploads_marksExpiredAndDeletesObjects() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document stale = TestData.document(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner);
        given(documents.findStale(eq(DocumentStatus.PENDING_UPLOAD), eq(TestData.NOW.minus(Duration.ofHours(1))), any()))
                .willReturn(List.of(stale));

        assertThat(service.expireStaleUploads()).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(DocumentStatus.EXPIRED);
        verify(storage).delete(stale.getStorageBucket(), stale.getStorageKey());
    }

    @Test
    void purgeForApplication_deletesRowsAndAllObjects() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.DRAFT);
        Document a = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        Document b = TestData.document(app, DocumentCode.OATH, DocumentStatus.PENDING_UPLOAD, owner);
        Redaction r = completedRedaction(a);
        r.setReportKey("public/x/y/redaction-report.json");
        given(documents.findByApplication_Id(app.getId())).willReturn(List.of(a, b));
        given(redactions.findAllById(any())).willReturn(List.of(r));

        assertThat(service.purgeForApplication(app.getId())).isEqualTo(2);
        verify(documents).deleteByApplicationId(app.getId());
        verify(storage, org.mockito.Mockito.times(4)).delete(anyString(), anyString());
    }

    @Test
    void purgeForApplication_noDocuments_returnsZero() {
        given(documents.findByApplication_Id(any())).willReturn(List.of());

        assertThat(service.purgeForApplication(java.util.UUID.randomUUID())).isZero();
        verify(storage, never()).delete(anyString(), anyString());
        verify(storage, never()).readPrefix(anyString(), anyString(), anyInt());
    }
}
