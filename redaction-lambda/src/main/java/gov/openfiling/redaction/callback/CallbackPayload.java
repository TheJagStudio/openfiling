package gov.openfiling.redaction.callback;

import java.util.List;

/**
 * Body of {@code POST /internal/v1/redactions/events} (filing-api).
 *
 * @param status {@code PROCESSING}, {@code COMPLETED} or {@code FAILED}
 * @param error  short, document-free error message (FAILED only)
 */
public record CallbackPayload(String documentId, String status, String engine, String model, Integer pageCount,
                              Integer redactionCount, List<CategoryCount> categories, String publicCopyBucket,
                              String publicCopyKey, String reportKey, String error, Long durationMs) {

    public record CategoryCount(String category, int count) {
    }

    public static CallbackPayload processing(String documentId, String engine, String model) {
        return new CallbackPayload(documentId, "PROCESSING", engine, model, null, null, List.of(), null, null, null,
                null, null);
    }

    public static CallbackPayload failed(String documentId, String engine, String model, Integer pageCount,
                                         String error, long durationMs) {
        return new CallbackPayload(documentId, "FAILED", engine, model, pageCount, null, List.of(), null, null, null,
                error, durationMs);
    }
}
