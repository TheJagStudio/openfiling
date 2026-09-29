package gov.openfiling.api.service;

import gov.openfiling.api.dto.CountResponse;
import gov.openfiling.api.dto.NotificationResponse;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.Notification;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.repository.NotificationRepository;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications (the SES/SNS e-mail fan-out would hang off {@link #notify}). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    @Transactional
    public void notify(UUID userId, PatentApplication app, String type, String title, String message) {
        if (userId == null) {
            return;
        }
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setApplication(app);
        n.setType(type);
        n.setTitle(truncate(title, 200));
        n.setMessage(truncate(message, 1000));
        n.setCreatedAt(clock.instant());
        notifications.save(n);
    }

    public PageResponse<NotificationResponse> list(Viewer viewer, boolean unreadOnly, PageParams paging) {
        Pageable pageable = PageRequest.of(paging.pageOrDefault(), paging.sizeOrDefault(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        Page<Notification> page = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNull(viewer.id(), pageable)
                : notifications.findByUserId(viewer.id(), pageable);
        return PageResponse.of(page, NotificationService::toResponse);
    }

    public CountResponse unreadCount(Viewer viewer) {
        return new CountResponse(notifications.countByUserIdAndReadAtIsNull(viewer.id()));
    }

    @Transactional
    public void markRead(Viewer viewer, UUID id) {
        Notification n = notifications.findByIdAndUserId(id, viewer.id())
                .orElseThrow(() -> NotFoundException.of("Notification", id));
        if (n.getReadAt() == null) {
            n.setReadAt(clock.instant());
        }
    }

    @Transactional
    public void markAllRead(Viewer viewer) {
        notifications.markAllRead(viewer.id(), clock.instant());
    }

    static NotificationResponse toResponse(Notification n) {
        PatentApplication app = n.getApplication();
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                app == null ? null : app.getId(), app == null ? null : app.getApplicationNumber(),
                n.getReadAt() != null, n.getCreatedAt());
    }

    /** "19/000,123" or the title for drafts; used as notification title prefix. */
    public static String label(PatentApplication app) {
        return app.getApplicationNumber() != null ? ApplicationNumbers.display(app.getApplicationNumber()) : "\"" + app.getTitle() + "\"";
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
