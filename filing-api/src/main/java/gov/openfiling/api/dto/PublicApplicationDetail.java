package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.StatusCategory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public (TSDR-style) view of a published application: summary fields plus public-safe details. */
public record PublicApplicationDetail(
        String applicationNumber,
        String applicationNumberDisplay,
        String title,
        ApplicationType applicationType,
        ApplicationStatus status,
        String statusLabel,
        StatusCategory statusCategory,
        LocalDate filingDate,
        String publicationNumber,
        LocalDate publicationDate,
        String patentNumber,
        LocalDate issueDate,
        String firstNamedInventor,
        String abstractSnippet,
        String abstractText,
        String technologyField,
        List<Inventor> inventors,
        String examinerName,
        String artUnit,
        List<Event> history,
        List<PublicDocument> documents) {

    public record Inventor(String name, String city, String state, String country) {
    }

    public record Event(String eventCode, String description, ApplicationStatus toStatus, Instant createdAt) {
    }

    public record PublicDocument(
            UUID id, DocumentCode docCode, String docCodeLabel, Integer pageCount, Integer redactionCount,
            Instant uploadedAt, boolean publicCopyAvailable) {
    }
}
