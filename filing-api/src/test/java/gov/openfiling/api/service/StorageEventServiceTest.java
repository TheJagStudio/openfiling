package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.dto.S3EventNotification;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageEventServiceTest {

    @Mock
    DocumentService documents;

    StorageEventService service;

    @BeforeEach
    void setUp() {
        service = new StorageEventService(documents, DocumentServiceTest.PROPS);
    }

    private static S3EventNotification.Record record(String eventName, String bucket, String key) {
        return new S3EventNotification.Record(eventName, "2026-09-29T12:00:00.000Z",
                new S3EventNotification.S3(new S3EventNotification.Bucket(bucket), new S3EventNotification.S3Object(key, 10L, "e")));
    }

    @Test
    void handle_objectCreatedOnDocumentsBucket_decodesKeyAndVerifies() {
        given(documents.completeFromStorageEvent("public/a/b/my spec+v2.pdf")).willReturn(DocumentService.EventOutcome.VERIFIED);

        Map<DocumentService.EventOutcome, Integer> outcomes = service.handle(new S3EventNotification(List.of(
                record("ObjectCreated:Put", "openfiling-documents", "public/a/b/my+spec%2Bv2.pdf"))));

        assertThat(outcomes).containsEntry(DocumentService.EventOutcome.VERIFIED, 1);
    }

    @Test
    void handle_otherBucketsEventsAndMalformedRecords_areIgnored() {
        Map<DocumentService.EventOutcome, Integer> outcomes = service.handle(new S3EventNotification(List.of(
                record("ObjectCreated:Put", "openfiling-public-copies", "public/a/b/public-copy.pdf"),
                record("ObjectRemoved:Delete", "openfiling-documents", "public/a/b/c.pdf"),
                new S3EventNotification.Record("ObjectCreated:Put", null, null))));

        assertThat(outcomes).containsEntry(DocumentService.EventOutcome.IGNORED, 3);
        verify(documents, never()).completeFromStorageEvent(anyString());
    }

    @Test
    void handle_failingRecord_doesNotAbortTheBatch() {
        given(documents.completeFromStorageEvent("k1")).willThrow(new IllegalStateException("db down"));
        given(documents.completeFromStorageEvent("k2")).willReturn(DocumentService.EventOutcome.REJECTED);

        Map<DocumentService.EventOutcome, Integer> outcomes = service.handle(new S3EventNotification(List.of(
                record("ObjectCreated:Put", "openfiling-documents", "k1"),
                record("ObjectCreated:CompleteMultipartUpload", "openfiling-documents", "k2"))));

        assertThat(outcomes).containsEntry(DocumentService.EventOutcome.IGNORED, 1).containsEntry(DocumentService.EventOutcome.REJECTED, 1);
    }

    @Test
    void handle_noRecords_returnsEmpty() {
        assertThat(service.handle(new S3EventNotification(null))).isEmpty();
    }
}
