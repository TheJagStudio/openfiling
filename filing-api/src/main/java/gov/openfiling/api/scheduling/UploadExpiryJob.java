package gov.openfiling.api.scheduling;

import gov.openfiling.api.service.DocumentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 10 minutes: PENDING_UPLOAD documents whose pre-signed URL expired more than an hour ago
 * become EXPIRED and their (partial) objects are deleted. In AWS this would be an EventBridge
 * schedule; with several API instances a ShedLock-style lock would keep it single-run (the work is
 * idempotent either way).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class UploadExpiryJob {

    private final DocumentService documents;

    @Scheduled(fixedDelayString = "${app.scheduling.expiry-sweep-interval:PT10M}", initialDelayString = "PT1M")
    public void expireStaleUploads() {
        try {
            int expired;
            do {
                expired = documents.expireStaleUploads();
            } while (expired > 0 && expired == 200);
        } catch (RuntimeException e) {
            log.warn("Stale upload sweep failed: {}", e.getMessage());
        }
    }
}
