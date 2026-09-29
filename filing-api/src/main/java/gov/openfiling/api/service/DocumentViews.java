package gov.openfiling.api.service;

import gov.openfiling.api.dto.DocumentResponse;
import gov.openfiling.api.dto.RedactionResponse;
import gov.openfiling.api.mapper.AccountMapper;
import gov.openfiling.api.mapper.RedactionJson;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentVisibility;
import gov.openfiling.api.model.Redaction;
import gov.openfiling.api.repository.RedactionRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Document DTOs; redaction rows are loaded in one batch per list. */
@Component
@RequiredArgsConstructor
public class DocumentViews {

    private final RedactionRepository redactions;
    private final AccountMapper accounts;

    public List<DocumentResponse> responses(List<Document> docs) {
        if (docs.isEmpty()) {
            return List.of();
        }
        Map<java.util.UUID, Redaction> byId = redactions.findAllById(docs.stream().map(Document::getId).toList()).stream()
                .collect(Collectors.toMap(Redaction::getDocumentId, Function.identity()));
        return docs.stream().map(d -> response(d, byId.get(d.getId()))).toList();
    }

    public DocumentResponse response(Document d) {
        return response(d, d.isPublic() ? redactions.findById(d.getId()).orElse(null) : null);
    }

    public DocumentResponse response(Document d, Redaction redaction) {
        return new DocumentResponse(
                d.getId(),
                d.getApplication().getId(),
                d.getDocCode(),
                d.getDocCode().getLabel(),
                d.getDescription(),
                d.getVisibility(),
                d.getFileName(),
                d.getContentType(),
                d.getSizeBytes(),
                d.getStatus(),
                d.getSource(),
                accounts.toParty(d.getUploadedBy()),
                d.getCreatedAt(),
                d.getUploadedAt(),
                d.getRejectionReason(),
                d.getVisibility() == DocumentVisibility.RESTRICTED ? null : redaction(redaction));
    }

    public static RedactionResponse redaction(Redaction r) {
        if (r == null) {
            return null;
        }
        return new RedactionResponse(r.getStatus(), r.getEngine(), r.getModel(), r.getPageCount(), r.getRedactionCount(),
                RedactionJson.parseCategories(r.getCategories()), r.getStartedAt(), r.getCompletedAt(), r.getError(),
                r.isPublicCopyAvailable());
    }
}
