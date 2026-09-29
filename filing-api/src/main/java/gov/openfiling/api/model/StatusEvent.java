package gov.openfiling.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/** One prosecution-history entry (append-only). */
@Entity
@Table(name = "status_events")
@Getter
@Setter
public class StatusEvent {

    public static final String CREATED = "CREATED";
    public static final String SUBMIT = "SUBMIT";
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "event_code", nullable = false, length = 40)
    private String eventCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32)
    private ApplicationStatus toStatus;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(length = 5000)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(name = "actor_role", length = 16)
    private String actorRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public String toString() {
        return "StatusEvent[id=" + id + ", eventCode=" + eventCode + "]";
    }
}
