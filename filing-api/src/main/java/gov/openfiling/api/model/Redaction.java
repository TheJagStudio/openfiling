package gov.openfiling.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Redaction state of one PUBLIC document, keyed by the document id. Deliberately not a JPA
 * association: callbacks may arrive before {@code /complete}, and lists load redactions in one
 * batch query instead of an inverse one-to-one (which cannot be lazy).
 */
@Entity
@Table(name = "redactions")
@Getter
@Setter
public class Redaction extends AssignedIdEntity {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RedactionStatus status;

    @Column(length = 20)
    private String engine;

    @Column(length = 64)
    private String model;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "redaction_count")
    private Integer redactionCount;

    /** {@code [{"category":"SSN","count":2}]} (jsonb); written with a cast so the driver can bind a String. */
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String categories = "[]";

    @Column(name = "public_copy_bucket", length = 63)
    private String publicCopyBucket;

    @Column(name = "public_copy_key", length = 1024)
    private String publicCopyKey;

    @Column(name = "report_key", length = 1024)
    private String reportKey;

    @Column(length = 1000)
    private String error;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public UUID getId() {
        return documentId;
    }

    public boolean isPublicCopyAvailable() {
        return status == RedactionStatus.COMPLETED && publicCopyKey != null && !publicCopyKey.isBlank();
    }

    public static Redaction pending(UUID documentId) {
        Redaction r = new Redaction();
        r.setDocumentId(documentId);
        r.setStatus(RedactionStatus.PENDING);
        return r;
    }

    @Override
    public String toString() {
        return "Redaction[documentId=" + documentId + ", status=" + status + "]";
    }
}
