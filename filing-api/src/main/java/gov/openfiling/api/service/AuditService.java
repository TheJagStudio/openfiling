package gov.openfiling.api.service;

import gov.openfiling.api.mapper.RedactionJson;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.AuditEvent;
import gov.openfiling.api.repository.AuditEventRepository;
import gov.openfiling.api.security.RequestMetadata;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code audit_events}. {@link #record} joins the caller's transaction (the audit row commits
 * or rolls back with the change it describes); {@link #recordIndependent} uses {@code REQUIRES_NEW}
 * for failure events (failed logins, token reuse) that must persist even when the caller fails.
 * Captures ip / user agent / request id of the current request. Never pass secrets in {@code details}.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuditService {

    public static final String SYSTEM = "system";

    private final AuditEventRepository events;
    private final Clock clock;

    @Transactional
    public void record(AuditAction action, Viewer actor, String resourceType, Object resourceId, Map<String, ?> details) {
        save(action, actor == null ? null : actor.id(), actor == null ? SYSTEM : actor.email(), resourceType, resourceId, details);
    }

    @Transactional
    public void record(AuditAction action, UUID actorId, String actorEmail, String resourceType, Object resourceId,
            Map<String, ?> details) {
        save(action, actorId, actorEmail, resourceType, resourceId, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(AuditAction action, UUID actorId, String actorEmail, String resourceType, Object resourceId,
            Map<String, ?> details) {
        save(action, actorId, actorEmail, resourceType, resourceId, details);
    }

    /** Null-tolerant ordered map from alternating keys and values; null values are skipped. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    private void save(AuditAction action, UUID actorId, String actorEmail, String resourceType, Object resourceId,
            Map<String, ?> details) {
        RequestMetadata request = RequestMetadata.current();
        AuditEvent event = new AuditEvent();
        event.setOccurredAt(clock.instant());
        event.setActorId(actorId);
        event.setActorEmail(actorEmail == null ? null : truncate(actorEmail, 254));
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId == null ? null : truncate(resourceId.toString(), 64));
        event.setIp(request.ip());
        event.setUserAgent(request.userAgent());
        event.setRequestId(request.requestId());
        event.setDetails(RedactionJson.write(details == null ? Map.of() : details));
        events.save(event);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
