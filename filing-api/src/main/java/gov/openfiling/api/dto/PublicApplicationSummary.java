package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.StatusCategory;

import java.time.LocalDate;

public record PublicApplicationSummary(
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
        String abstractSnippet) {
}
