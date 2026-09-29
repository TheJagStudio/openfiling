package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.DashboardResponse;
import gov.openfiling.api.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;

    @GetMapping
    @Operation(summary = "Totals, action items and recent applications (plus the examiner block for examiners)")
    public DashboardResponse dashboard(@AuthenticationPrincipal Jwt jwt) {
        return dashboard.dashboard(Principals.viewer(jwt));
    }
}
