package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.DocumentResponse;
import gov.openfiling.api.dto.DownloadResponse;
import gov.openfiling.api.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documents;

    @PostMapping("/{docId}/complete")
    @Operation(summary = "Verify the uploaded object (size, PDF magic) and mark it UPLOADED (idempotent)")
    @ApiResponse(responseCode = "409", description = "Nothing uploaded yet, or upload window expired")
    @ApiResponse(responseCode = "422", description = "Verification failed; the document is now REJECTED")
    public DocumentResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId) {
        return documents.complete(Principals.viewer(jwt), docId);
    }

    @GetMapping("/{docId}")
    @Operation(summary = "Document metadata and redaction status")
    public DocumentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId) {
        return documents.get(Principals.viewer(jwt), docId);
    }

    @GetMapping("/{docId}/download")
    @Operation(summary = "Pre-signed GET URL (5 min) for the original or the redacted public copy")
    @ApiResponse(responseCode = "409", description = "Not uploaded, or no public copy yet")
    public DownloadResponse download(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID docId,
            @Parameter(description = "original (default) or public")
            @RequestParam(defaultValue = "original") @Pattern(regexp = "(?i)original|public", message = "must be original or public")
            String copy) {
        return documents.download(Principals.viewer(jwt), docId, copy);
    }

    @DeleteMapping("/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a document (owner: DRAFT application, or a document that never finished uploading)")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId) {
        documents.delete(Principals.viewer(jwt), docId);
    }

    @PostMapping("/{docId}/redaction/retry")
    @PreAuthorize("hasAnyRole('EXAMINER', 'ADMIN')")
    @Operation(summary = "Re-run PII/CUI redaction for a PUBLIC document (examiner)")
    @ApiResponse(responseCode = "503", description = "Redaction function unreachable")
    public DocumentResponse retryRedaction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId) {
        return documents.retryRedaction(Principals.viewer(jwt), docId);
    }
}
