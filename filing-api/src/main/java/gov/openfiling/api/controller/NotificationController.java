package gov.openfiling.api.controller;

import gov.openfiling.api.config.OpenApiConfig;
import gov.openfiling.api.dto.CountResponse;
import gov.openfiling.api.dto.NotificationResponse;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    @GetMapping
    @Operation(summary = "My notifications, newest first")
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unreadOnly, @Valid @ParameterObject PageParams paging) {
        return notifications.list(Principals.viewer(jwt), unreadOnly, paging);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Number of unread notifications")
    public CountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return notifications.unreadCount(Principals.viewer(jwt));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one notification read")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        notifications.markRead(Principals.viewer(jwt), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark all notifications read")
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(Principals.viewer(jwt));
    }
}
