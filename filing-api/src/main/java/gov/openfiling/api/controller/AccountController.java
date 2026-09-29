package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.ChangePasswordRequest;
import gov.openfiling.api.dto.SessionResponse;
import gov.openfiling.api.dto.UpdateProfileRequest;
import gov.openfiling.api.dto.UserResponse;
import gov.openfiling.api.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Account")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequiredArgsConstructor
public class AccountController {

    private final AccountService account;

    @PatchMapping("/profile")
    @Operation(summary = "Update my display name")
    public UserResponse updateProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return account.updateProfile(Principals.viewer(jwt), request);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change my password (signs out every session)")
    @ApiResponse(responseCode = "422", description = "Current password incorrect")
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        account.changePassword(Principals.viewer(jwt), request);
    }

    @GetMapping("/sessions")
    @Operation(summary = "My active sessions (refresh-token families)")
    public List<SessionResponse> sessions(@AuthenticationPrincipal Jwt jwt) {
        return account.sessions(Principals.viewer(jwt));
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out one session")
    public void revokeSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        account.revokeSession(Principals.viewer(jwt), id);
    }

    @PostMapping("/sessions/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out everywhere")
    public void revokeAll(@AuthenticationPrincipal Jwt jwt) {
        account.revokeAll(Principals.viewer(jwt));
    }
}
