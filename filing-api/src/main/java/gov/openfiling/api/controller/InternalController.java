package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.RedactionCallbackRequest;
import gov.openfiling.api.dto.S3EventNotification;
import gov.openfiling.api.service.RedactionService;
import gov.openfiling.api.service.StorageEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service callbacks; guarded by X-Internal-Token (separate security chain). */
@RestController
@RequestMapping("/internal/v1")
@Tag(name = "Internal")
@SecurityRequirement(name = OpenApiConfig.INTERNAL)
@RequiredArgsConstructor
public class InternalController {

    private final StorageEventService storageEvents;
    private final RedactionService redactions;

    @PostMapping("/storage/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "S3 event notification (ObjectCreated) from object storage",
            description = "Verifies PENDING_UPLOAD documents whose key matches, exactly like /complete. Idempotent.")
    public void storageEvent(@RequestBody S3EventNotification event) {
        storageEvents.handle(event);
    }

    @PostMapping("/redactions/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Redaction status callback from the Lambda")
    @ApiResponse(responseCode = "404", description = "Unknown documentId")
    public void redactionEvent(@Valid @RequestBody RedactionCallbackRequest callback) {
        redactions.handleCallback(callback);
    }
}
