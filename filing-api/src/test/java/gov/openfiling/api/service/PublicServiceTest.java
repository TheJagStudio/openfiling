package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.PublicApplicationDetail;
import gov.openfiling.api.dto.PublicApplicationSummary;
import gov.openfiling.api.dto.PublicStats;
import gov.openfiling.api.exception.NotFoundException;
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
import gov.openfiling.api.repository.StatusEventRepository;
import gov.openfiling.api.storage.DocumentStorage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class PublicServiceTest {

    @Mock
    ApplicationRepository applications;

    @Mock
    DocumentRepository documents;

    @Mock
    RedactionRepository redactions;

    @Mock
    StatusEventRepository statusEvents;

    @Mock
    DocumentStorage storage;

    @Mock
    AuditService audit;

    PublicService service;
    User owner;

    @BeforeEach
    void setUp() {
        service = new PublicService(applications, documents, redactions, statusEvents, storage, audit, DocumentServiceTest.PROPS);
        owner = TestData.inventor("Ada");
    }

    private PatentApplication published() {
        PatentApplication app = TestData.assigned(TestData.application(owner, ApplicationStatus.UNDER_EXAMINATION), TestData.examiner("Grace"));
        app.setPublished(true);
        app.setPublicationNumber("US20260012345A1");
        app.setAbstractText("word ".repeat(100));
        return app;
    }

    private static StatusEvent event(String code) {
        StatusEvent e = new StatusEvent();
        e.setEventCode(code);
        e.setDescription(code);
        e.setNote("private note");
        e.setCreatedAt(TestData.NOW);
        return e;
    }

    private static Redaction redaction(Document d, RedactionStatus status) {
        Redaction r = Redaction.pending(d.getId());
        r.setStatus(status);
        r.setPublicCopyKey(status == RedactionStatus.COMPLETED ? "public/a/b/public-copy.pdf" : null);
        r.setPageCount(3);
        r.setRedactionCount(2);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_publishedOnly_mapsSnippetAndFirstInventor() {
        PatentApplication app = published();
        given(applications.findAll(any(Specification.class), any(Pageable.class))).willReturn(new PageImpl<>(List.of(app)));

        PageResponse<PublicApplicationSummary> page = service.search("word", null, PageParams.defaults());

        PublicApplicationSummary s = page.content().getFirst();
        assertThat(s.firstNamedInventor()).isEqualTo("Ada Lovelace");
        assertThat(s.abstractSnippet()).endsWith("…").hasSizeLessThanOrEqualTo(PublicService.SNIPPET_LENGTH + 1);
        assertThat(s.applicationNumberDisplay()).isEqualTo("19/000,123");
    }

    @Test
    void detail_displayNumber_filtersPrivateEventsAndUnredactedDocuments() {
        PatentApplication app = published();
        given(applications.findByApplicationNumber("19000123")).willReturn(Optional.of(app));
        given(statusEvents.findByApplicationIdOrderByCreatedAtAscIdAsc(app.getId())).willReturn(List.of(
                event(StatusEvent.CREATED), event(StatusEvent.SUBMIT), event(StatusEvent.DOCUMENT_UPLOADED), event("PUBLISH")));
        Document ready = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        Document processing = TestData.document(app, DocumentCode.CLM, DocumentStatus.UPLOADED, owner);
        given(documents.findByApplication_IdAndVisibilityAndStatusOrderByUploadedAtAsc(app.getId(), DocumentVisibility.PUBLIC,
                DocumentStatus.UPLOADED)).willReturn(List.of(ready, processing));
        given(redactions.findAllById(any())).willReturn(List.of(redaction(ready, RedactionStatus.COMPLETED),
                redaction(processing, RedactionStatus.PROCESSING)));

        PublicApplicationDetail detail = service.detail("19/000,123");

        assertThat(detail.history()).extracting(PublicApplicationDetail.Event::eventCode).containsExactly("SUBMIT", "PUBLISH");
        assertThat(detail.documents()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(ready.getId());
            assertThat(d.redactionCount()).isEqualTo(2);
        });
        assertThat(detail.examinerName()).isEqualTo("Grace");
        assertThat(detail.inventors()).singleElement().satisfies(i -> assertThat(i.name()).isEqualTo("Ada Lovelace"));
    }

    @Test
    void detail_unpublished_throwsNotFound() {
        PatentApplication app = TestData.application(owner, ApplicationStatus.UNDER_EXAMINATION);
        given(applications.findByApplicationNumber("19000123")).willReturn(Optional.of(app));

        assertThatThrownBy(() -> service.detail("19000123")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void detail_malformedNumber_throwsNotFound() {
        assertThatThrownBy(() -> service.detail("abc")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void download_publishedPublicCompleted_presignsPublicCopyAndAuditsAnonymous() {
        PatentApplication app = published();
        Document doc = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        given(documents.findWithApplicationById(doc.getId())).willReturn(Optional.of(doc));
        given(redactions.findById(doc.getId())).willReturn(Optional.of(redaction(doc, RedactionStatus.COMPLETED)));
        given(storage.presignDownload(eq("openfiling-public-copies"), eq("public/a/b/public-copy.pdf"), eq("public-copy-spec.pdf"),
                eq("application/pdf"), any())).willReturn(new DocumentStorage.PresignedUrl("http://s3/pc", "GET", Map.of(), TestData.NOW));

        assertThat(service.download(doc.getId()).url()).isEqualTo("http://s3/pc");
        verify(audit).record(eq(AuditAction.DOCUMENT_DOWNLOAD), isNull(), eq("anonymous"), any(), eq(doc.getId()), any());
    }

    @Test
    void download_restrictedOrUnpublishedOrUnredacted_throwsNotFound() {
        PatentApplication app = published();
        Document restricted = TestData.document(app, DocumentCode.OATH, DocumentStatus.UPLOADED, owner);
        given(documents.findWithApplicationById(restricted.getId())).willReturn(Optional.of(restricted));
        Document pending = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
        given(documents.findWithApplicationById(pending.getId())).willReturn(Optional.of(pending));
        given(redactions.findById(pending.getId())).willReturn(Optional.of(redaction(pending, RedactionStatus.PENDING)));
        UUID unknown = UUID.randomUUID();
        given(documents.findWithApplicationById(unknown)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(restricted.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.download(pending.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.download(unknown)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void stats_countsFiledPublishedIssuedAndRedactions() {
        given(applications.countByStatusNot(ApplicationStatus.DRAFT)).willReturn(11L);
        given(applications.countByPublishedTrue()).willReturn(6L);
        given(applications.countByStatus(ApplicationStatus.ISSUED)).willReturn(1L);
        given(redactions.countByStatus(RedactionStatus.COMPLETED)).willReturn(4L);
        given(redactions.sumCompletedRedactions()).willReturn(17L);

        assertThat(service.stats()).isEqualTo(new PublicStats(11, 6, 1, 4, 17));
    }

    @Test
    void snippet_shortAndNullText() {
        assertThat(PublicService.snippet(null)).isNull();
        assertThat(PublicService.snippet("  short\n text ")).isEqualTo("short text");
    }
}
