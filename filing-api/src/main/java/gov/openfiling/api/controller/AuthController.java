package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.AuthResponse;
import gov.openfiling.api.dto.LoginRequest;
import gov.openfiling.api.dto.RefreshTokenRequest;
import gov.openfiling.api.dto.RegisterRequest;
import gov.openfiling.api.dto.UserResponse;
import gov.openfiling.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an INVENTOR account and sign in")
    @ApiResponse(responseCode = "201", description = "Registered")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    @ApiResponse(responseCode = "429", description = "Too many authentication requests from this IP")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange email + password for a 15-minute access token and a refresh token")
    @ApiResponse(responseCode = "200", description = "Authenticated")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    @ApiResponse(responseCode = "429", description = "Locked for 15 minutes after 5 failed attempts, or rate limited")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh token and get a new access token",
            description = "Reusing an already rotated refresh token revokes the whole session.")
    @ApiResponse(responseCode = "401", description = "Invalid, expired, revoked or reused refresh token")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the given refresh token")
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        auth.logout(request.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "The current user", security = @SecurityRequirement(name = OpenApiConfig.BEARER))
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(Principals.userId(jwt));
    }
}
