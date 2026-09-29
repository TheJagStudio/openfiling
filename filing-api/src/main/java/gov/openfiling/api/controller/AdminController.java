package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.AdminCreateUserRequest;
import gov.openfiling.api.dto.AdminUpdateUserRequest;
import gov.openfiling.api.dto.AdminUserResponse;
import gov.openfiling.api.dto.AuditEventResponse;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.SystemStatusResponse;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService admin;

    @GetMapping("/users")
    @Operation(summary = "Users (newest first) with application counts")
    public PageResponse<AdminUserResponse> users(
            @Parameter(description = "Email or name contains") @RequestParam(required = false) @Size(max = 254) String q,
            @RequestParam(required = false) Role role,
            @Valid @ParameterObject PageParams paging) {
        return admin.users(q, role, paging);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user with roles (e.g. an examiner)")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    public AdminUserResponse createUser(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AdminCreateUserRequest request) {
        return admin.createUser(Principals.viewer(jwt), request);
    }

    @PatchMapping("/users/{id}")
    @Operation(summary = "Change roles, enable/disable, art unit, unlock")
    @ApiResponse(responseCode = "422", description = "Cannot remove own ADMIN role or disable self")
    public AdminUserResponse updateUser(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody AdminUpdateUserRequest request) {
        return admin.updateUser(Principals.viewer(jwt), id, request);
    }

    @GetMapping("/audit")
    @Operation(summary = "Audit log, newest first")
    public PageResponse<AuditEventResponse> audit(
            @Parameter(description = "Actor user id, or e-mail contains") @RequestParam(required = false) @Size(max = 254) String actor,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) @Size(max = 40) String resourceType,
            @RequestParam(required = false) @Size(max = 64) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Valid @ParameterObject PageParams paging) {
        return admin.audit(actor, action, resourceType, resourceId, from, to, paging);
    }

    @GetMapping("/system")
    @Operation(summary = "Health of database, storage and the redaction function, plus counts")
    public SystemStatusResponse system() {
        return admin.system();
    }
}
