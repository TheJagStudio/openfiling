package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.dto.CategoryCount;
import gov.openfiling.api.dto.RedactionCallbackRequest;
import gov.openfiling.api.dto.RedactionCounts;
import gov.openfiling.api.exception.BadRequestException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.RedactionJson;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.Redaction;
import gov.openfiling.api.model.RedactionStatus;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.RedactionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedactionServiceTest {

    @Mock
    DocumentRepository documents;

    @Mock
    RedactionRepository redactions;

    @Mock
    NotificationService notifications;

    @Mock
    AuditService audit;

    RedactionService service;
    User owner;
    Document doc;

    @BeforeEach
    void setUp() {
        service = new RedactionService(documents, redactions, notifications, audit, TestData.CLOCK);
        owner = TestData.inventor("Ada");
        PatentApplication app = TestData.application(owner, ApplicationStatus.PRE_EXAM);
        doc = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);
    }

    private static RedactionCallbackRequest callback(UUID id, RedactionStatus status, String error) {
        return new RedactionCallbackRequest(id, status, "CLAUDE+RULES", "claude-opus-5-5", 7, 3,
                List.of(new CategoryCount("SSN", 2), new CategoryCount("PHONE", 1), new CategoryCount("SSN", 0)),
                "openfiling-public-copies", "public/a/b/public-copy.pdf", "public/a/b/redaction-report.json", error, 1500L);
    }

    private Redaction saved() {
        ArgumentCaptor<Redaction> captor = ArgumentCaptor.forClass(Redaction.class);
        verify(redactions).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void handleCallback_processingBeforeComplete_createsRowAndCountsAttempt() {
        given(documents.findWithApplicationById(doc.getId())).willReturn(Optional.of(doc));
        given(redactions.findById(doc.getId())).willReturn(Optional.empty());

        service.handleCallback(new RedactionCallbackRequest(doc.getId(), RedactionStatus.PROCESSING, null, null, null, null, null,
                null, null, null, null, null));

        Redaction r = saved();
        assertThat(r.getStatus()).isEqualTo(RedactionStatus.PROCESSING);
        assertThat(r.getAttempts()).isEqualTo(1);
        assertThat(r.getStartedAt()).isEqualTo(TestData.NOW);
        verify(notifications, never()).notify(any(), any(), any(), any(), any());
    }

    @Test
    void handleCallback_completed_storesResultNotifiesOwnerAndAudits() {
        Redaction existing = Redaction.pending(doc.getId());
        given(documents.findWithApplicationById(doc.getId())).willReturn(Optional.of(doc));
        given(redactions.findById(doc.getId())).willReturn(Optional.of(existing));

        service.handleCallback(callback(doc.getId(), RedactionStatus.COMPLETED, null));

        Redaction r = saved();
        assertThat(r.getStatus()).isEqualTo(RedactionStatus.COMPLETED);
        assertThat(r.getCompletedAt()).isEqualTo(TestData.NOW);
        assertThat(r.getStartedAt()).isEqualTo(TestData.NOW.minusMillis(1500));
        assertThat(r.getEngine()).isEqualTo("CLAUDE+RULES");
        assertThat(r.getPageCount()).isEqualTo(7);
        assertThat(r.getRedactionCount()).isEqualTo(3);
        assertThat(r.isPublicCopyAvailable()).isTrue();
        assertThat(r.getReportKey()).endsWith("redaction-report.json");
        assertThat(RedactionJson.parseCategories(r.getCategories()))
                .containsExactly(new CategoryCount("SSN", 2), new CategoryCount("PHONE", 1));
        verify(notifications).notify(eq(owner.getId()), eq(doc.getApplication()), eq("REDACTION_COMPLETED"), anyString(), anyString());
        verify(audit).record(eq(AuditAction.REDACTION_COMPLETED), isNull(), eq(RedactionService.LAMBDA_ACTOR),
                eq(DocumentService.RESOURCE), eq(doc.getId()), any());
    }

    @Test
    void handleCallback_failed_truncatesErrorNotifiesAndAudits() {
        given(documents.findWithApplicationById(doc.getId())).willReturn(Optional.of(doc));
        given(redactions.findById(doc.getId())).willReturn(Optional.empty());

        service.handleCallback(callback(doc.getId(), RedactionStatus.FAILED, "x".repeat(2000)));

        Redaction r = saved();
        assertThat(r.getStatus()).isEqualTo(RedactionStatus.FAILED);
        assertThat(r.getError()).hasSize(1000);
        verify(notifications).notify(eq(owner.getId()), any(), eq("REDACTION_FAILED"), anyString(), anyString());
        verify(audit).record(eq(AuditAction.REDACTION_FAILED), isNull(), anyString(), anyString(), eq(doc.getId()), any());
    }

    @Test
    void handleCallback_failedWithoutMessage_usesGenericError() {
        given(documents.findWithApplicationById(doc.getId())).willReturn(Optional.of(doc));
        given(redactions.findById(doc.getId())).willReturn(Optional.empty());

        service.handleCallback(callback(doc.getId(), RedactionStatus.FAILED, " "));

        assertThat(saved().getError()).isEqualTo("Redaction failed");
    }

    @Test
    void handleCallback_unknownDocument_throwsNotFound() {
        UUID id = UUID.randomUUID();
        given(documents.findWithApplicationById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback(callback(id, RedactionStatus.COMPLETED, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void handleCallback_pendingStatus_throwsBadRequest() {
        assertThatThrownBy(() -> service.handleCallback(callback(doc.getId(), RedactionStatus.PENDING, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void counts_aggregatesByStatus() {
        given(redactions.countByStatus(RedactionStatus.PENDING)).willReturn(1L);
        given(redactions.countByStatus(RedactionStatus.PROCESSING)).willReturn(2L);
        given(redactions.countByStatus(RedactionStatus.COMPLETED)).willReturn(3L);
        given(redactions.countByStatus(RedactionStatus.FAILED)).willReturn(4L);

        assertThat(service.counts()).isEqualTo(new RedactionCounts(1, 2, 3, 4));
    }
}
