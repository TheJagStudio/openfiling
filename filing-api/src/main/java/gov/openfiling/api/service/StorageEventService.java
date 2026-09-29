package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.S3EventNotification;
import gov.openfiling.api.storage.StorageKeys;

import java.util.EnumMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles S3 {@code ObjectCreated:*} notifications for the documents bucket. Deliberately
 * non-transactional: each record is verified in its own transaction, so one bad record cannot undo
 * the others, and redelivered events are no-ops (idempotent).
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RequiredArgsConstructor
public class StorageEventService {

    private final DocumentService documents;
    private final AppProperties.Storage props;

    public Map<DocumentService.EventOutcome, Integer> handle(S3EventNotification event) {
        Map<DocumentService.EventOutcome, Integer> outcomes = new EnumMap<>(DocumentService.EventOutcome.class);
        for (S3EventNotification.Record record : event.safeRecords()) {
            DocumentService.EventOutcome outcome = handle(record);
            outcomes.merge(outcome, 1, Integer::sum);
        }
        return outcomes;
    }

    private DocumentService.EventOutcome handle(S3EventNotification.Record record) {
        if (!record.isObjectCreated() || record.s3() == null || record.s3().bucket() == null || record.s3().object() == null
                || record.s3().object().key() == null || !props.documentsBucket().equals(record.s3().bucket().name())) {
            return DocumentService.EventOutcome.IGNORED;
        }
        String key = StorageKeys.decodeEventKey(record.s3().object().key());
        try {
            return documents.completeFromStorageEvent(key);
        } catch (RuntimeException e) {
            log.warn("Storage event for an object in {} could not be processed: {}", record.s3().bucket().name(), e.getMessage());
            return DocumentService.EventOutcome.IGNORED;
        }
    }
}
