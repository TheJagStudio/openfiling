package gov.openfiling.api.controller;

import gov.openfiling.api.dto.DownloadResponse;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.PublicApplicationDetail;
import gov.openfiling.api.dto.PublicApplicationSummary;
import gov.openfiling.api.dto.PublicStats;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.service.PublicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicService publicService;

    @GetMapping("/applications")
    @Operation(summary = "Search published applications")
    public PageResponse<PublicApplicationSummary> search(
            @Parameter(description = "Title, abstract or application number contains") @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) ApplicationType type,
            @Valid @ParameterObject PageParams paging) {
        return publicService.search(q, type, paging);
    }

    @GetMapping("/applications/{applicationNumber}")
    @Operation(summary = "TSDR-style public status of a published application",
            description = "Accepts 19000123 or 19/000,123 (URL-encode the slash).")
    public PublicApplicationDetail detail(@PathVariable String applicationNumber) {
        return publicService.detail(applicationNumber);
    }

    /** The display form {@code 19/000,123} with an unencoded slash arrives as two path segments. */
    @GetMapping("/applications/{series:\\d{2}}/{serial}")
    @Operation(summary = "Public status by display number (e.g. /public/applications/19/000,123)")
    public PublicApplicationDetail detailByDisplayNumber(@PathVariable String series, @PathVariable String serial) {
        return publicService.detail(series + "/" + serial);
    }

    @GetMapping("/documents/{docId}/download")
    @Operation(summary = "Pre-signed GET of a redacted public copy")
    public DownloadResponse download(@PathVariable UUID docId) {
        return publicService.download(docId);
    }

    @GetMapping("/stats")
    @Operation(summary = "Portal statistics")
    public PublicStats stats() {
        return publicService.stats();
    }
}
