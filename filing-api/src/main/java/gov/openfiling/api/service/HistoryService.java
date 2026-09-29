package gov.openfiling.api.service;

import gov.openfiling.api.dto.StatusEventResponse;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.StatusEventRepository;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Append-only prosecution history ({@code status_events}). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HistoryService {

    public static final String ROLE_INVENTOR = "INVENTOR";
    public static final String ROLE_EXAMINER = "EXAMINER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SYSTEM = "SYSTEM";

    private final StatusEventRepository events;
    private final ApplicationAccess access;
    private final Clock clock;

    @Transactional
    public StatusEvent record(PatentApplication app, String eventCode, ApplicationStatus from, ApplicationStatus to,
            String description, String note, User actor, String actorRole) {
        StatusEvent event = new StatusEvent();
        event.setApplicationId(app.getId());
        event.setEventCode(eventCode);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setDescription(description.length() <= 300 ? description : description.substring(0, 300));
        event.setNote(note == null || note.isBlank() ? null : note.trim());
        event.setActor(actor);
        event.setActorRole(actorRole);
        event.setCreatedAt(clock.instant());
        return events.save(event);
    }

    /** Oldest first. */
    public List<StatusEventResponse> history(Viewer viewer, UUID applicationId) {
        PatentApplication app = access.loadVisible(applicationId, viewer);
        return events.findByApplicationIdOrderByCreatedAtAscIdAsc(app.getId()).stream().map(HistoryService::toResponse).toList();
    }

    static StatusEventResponse toResponse(StatusEvent e) {
        StatusEventResponse.Actor actor = e.getActor() == null
                ? new StatusEventResponse.Actor("OpenFiling", e.getActorRole() == null ? ROLE_SYSTEM : e.getActorRole())
                : new StatusEventResponse.Actor(e.getActor().getDisplayName(), e.getActorRole());
        return new StatusEventResponse(e.getId() == null ? 0 : e.getId(), e.getEventCode(), e.getFromStatus(), e.getToStatus(),
                e.getDescription(), e.getNote(), actor, e.getCreatedAt());
    }

    /** Role recorded for an action taken in examiner capacity. */
    public static String examinerRole(Viewer viewer) {
        return viewer.roles().contains(gov.openfiling.api.model.Role.EXAMINER) ? ROLE_EXAMINER : ROLE_ADMIN;
    }
}
