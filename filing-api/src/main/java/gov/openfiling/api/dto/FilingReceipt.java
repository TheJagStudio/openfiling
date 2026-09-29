package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.EntityStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Printable filing receipt of a filed application. */
public record FilingReceipt(
        String applicationNumberDisplay,
        String confirmationNumber,
        String title,
        ApplicationType applicationType,
        EntityStatus entityStatus,
        LocalDate filingDate,
        Instant submittedAt,
        String signature,
        Applicant applicant,
        List<InventorDto> inventors,
        List<ReceiptDocument> documents) {

    public record Applicant(UUID id, String displayName, String email) {
    }

    public record ReceiptDocument(DocumentCode docCode, String docCodeLabel, String fileName, long sizeBytes, Instant uploadedAt) {
    }
}
