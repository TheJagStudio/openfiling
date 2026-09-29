package gov.openfiling.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

/** Append-only audit row. {@code details} never contains secrets or document contents. */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email", length = 254)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String details = "{}";

    @Override
    public String toString() {
        return "AuditEvent[id=" + id + ", action=" + action + "]";
    }
}
