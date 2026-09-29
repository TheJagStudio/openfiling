package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.ApplicationDetail;
import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.CreateApplicationRequest;
import gov.openfiling.api.dto.CreateUploadRequest;
import gov.openfiling.api.dto.DocumentResponse;
import gov.openfiling.api.dto.FilingReceipt;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.StatusEventResponse;
import gov.openfiling.api.dto.SubmitRequest;
import gov.openfiling.api.dto.UpdateApplicationRequest;
import gov.openfiling.api.dto.UploadInitResponse;
import gov.openfiling.api.dto.WorkflowActionRequest;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.service.ApplicationService;
import gov.openfiling.api.service.DocumentService;
import gov.openfiling.api.service.HistoryService;
import gov.openfiling.api.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/applications")
@Tag(name = "Applications")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applications;
    private final WorkflowService workflow;
    private final HistoryService history;
    private final DocumentService documents;

    @GetMapping
    @Operation(summary = "My applications", description = "Sort: updatedAt (default desc), createdAt, filingDate, title, status.")
    public PageResponse<ApplicationSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Title or application number contains") @RequestParam(required = false) @Size(max = 200) String q,
            @Parameter(description = "Comma-separated statuses") @RequestParam(required = false) List<ApplicationStatus> status,
            @RequestParam(required = false) ApplicationType type,
            @Valid @ParameterObject PageParams paging) {
        return applications.list(Principals.viewer(jwt), q, status, type, paging);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a DRAFT application")
    public ApplicationDetail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateApplicationRequest request) {
        return applications.create(Principals.viewer(jwt), request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Application detail with permissions and available actions")
    @ApiResponse(responseCode = "404", description = "Missing or not visible to the caller")
    public ApplicationDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return applications.get(Principals.viewer(jwt), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit bibliographic data (owner; DRAFT or INCOMPLETE)")
    @ApiResponse(responseCode = "409", description = "Stale version or not editable in this status")
    public ApplicationDetail update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UpdateApplicationRequest request) {
        return applications.update(Principals.viewer(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a DRAFT (owner) including its stored files")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        applications.delete(Principals.viewer(jwt), id);
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "File the application (DRAFT → SUBMITTED)")
    @ApiResponse(responseCode = "422", description = "Missing or unfinished documents (see errors[])")
    @ApiResponse(responseCode = "409", description = "Already filed or stale version")
    public ApplicationDetail submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody SubmitRequest request) {
        return applications.submit(Principals.viewer(jwt), id, request);
    }

    @PostMapping("/{id}/actions")
    @Operation(summary = "Perform a workflow action")
    @ApiResponse(responseCode = "403", description = "Not allowed for the caller's role or the application's status")
    @ApiResponse(responseCode = "409", description = "Stale version")
    @ApiResponse(responseCode = "422", description = "Missing note, invalid attachments or unmet preconditions")
    public ApplicationDetail action(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody WorkflowActionRequest request) {
        return workflow.perform(Principals.viewer(jwt), id, request);
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Prosecution history, oldest first")
    public List<StatusEventResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return history.history(Principals.viewer(jwt), id);
    }

    @GetMapping("/{id}/receipt")
    @Operation(summary = "Filing receipt")
    @ApiResponse(responseCode = "409", description = "Not filed yet")
    public FilingReceipt receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return applications.receipt(Principals.viewer(jwt), id);
    }

    @GetMapping("/{id}/documents")
    @Operation(summary = "Documents of the application (non-expired, newest first)", tags = "Documents")
    public List<DocumentResponse> documents(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return documents.list(Principals.viewer(jwt), id);
    }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start an upload: returns a pre-signed PUT URL (15 min)", tags = "Documents",
            description = "PUT the PDF bytes to upload.url with exactly upload.headers, then POST /documents/{id}/complete.")
    @ApiResponse(responseCode = "403", description = "Document code not uploadable by the caller in this status")
    @ApiResponse(responseCode = "422", description = "Too large or too many documents")
    public UploadInitResponse startUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody CreateUploadRequest request) {
        return documents.initiateUpload(Principals.viewer(jwt), id, request);
    }
}
