package gov.openfiling.api.controller;

import gov.openfiling.api.dto.ReferenceResponse;
import gov.openfiling.api.service.ReferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Reference")
@RequiredArgsConstructor
public class ReferenceController {

    private final ReferenceService reference;

    @GetMapping
    @Operation(summary = "Statuses, document codes, application types, entity statuses and upload limits")
    public ResponseEntity<ReferenceResponse> reference() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(reference.reference());
    }
}
