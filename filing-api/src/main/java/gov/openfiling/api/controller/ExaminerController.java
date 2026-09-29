package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.ExaminerStats;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.service.ExaminerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/examiner")
@Tag(name = "Examiner")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@PreAuthorize("hasAnyRole('EXAMINER', 'ADMIN')")
@RequiredArgsConstructor
public class ExaminerController {

    private final ExaminerService examiner;

    @GetMapping("/queue")
    @Operation(summary = "Examination queue: every filed application",
            description = "q matches title, application number or applicant name. Sort: filingDate (default asc), updatedAt, applicationNumber, status.")
    public PageResponse<ApplicationSummary> queue(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 200) String q,
            @Parameter(description = "Comma-separated statuses") @RequestParam(required = false) List<ApplicationStatus> status,
            @Parameter(description = "me | unassigned | any (default)")
            @RequestParam(required = false) @Pattern(regexp = "(?i)me|unassigned|any", message = "must be me, unassigned or any")
            String assigned,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}", message = "must be a 4-digit art unit") String artUnit,
            @RequestParam(required = false) ApplicationType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate filedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate filedTo,
            @Valid @ParameterObject PageParams paging) {
        ExaminerService.QueueFilter filter = new ExaminerService.QueueFilter(q, status, ExaminerService.Assigned.parse(assigned),
                artUnit, type, filedFrom, filedTo);
        return examiner.queue(Principals.viewer(jwt), filter, paging);
    }

    @GetMapping("/stats")
    @Operation(summary = "Examiner statistics")
    public ExaminerStats stats(@AuthenticationPrincipal Jwt jwt) {
        return examiner.stats(Principals.viewer(jwt));
    }
}
