package gov.openfiling.api.dto;

import gov.openfiling.api.model.DocumentSource;
import gov.openfiling.api.model.DocumentVisibility;
import gov.openfiling.api.model.StatusCategory;

import java.util.List;

public record ReferenceResponse(
        List<StatusRef> statuses,
        List<DocumentCodeRef> documentCodes,
        List<CodeLabel> applicationTypes,
        List<CodeLabel> entityStatuses,
        Limits limits) {

    public record StatusRef(String code, String label, StatusCategory category, boolean terminal, String description) {
    }

    public record DocumentCodeRef(
            String code, String label, DocumentSource source, DocumentVisibility defaultVisibility, String description) {
    }

    public record CodeLabel(String code, String label) {
    }

    public record Limits(long maxUploadBytes, int maxDocumentsPerApplication, List<String> allowedContentTypes) {
    }
}
