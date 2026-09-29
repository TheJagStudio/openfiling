package gov.openfiling.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Metadata of a PDF stored in S3; the bytes never pass through the API. */
@Entity
@Table(name = "documents")
@Getter
@Setter
public class Document extends AssignedIdEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private PatentApplication application;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_code", nullable = false, length = 8)
    private DocumentCode docCode;

    @Column(length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DocumentVisibility visibility;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DocumentSource source;

    @Column(name = "storage_bucket", nullable = false, length = 63)
    private String storageBucket;

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    @Column(length = 80)
    private String etag;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "upload_expires_at")
    private Instant uploadExpiresAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isPublic() {
        return visibility == DocumentVisibility.PUBLIC;
    }

    public UUID uploadedById() {
        return uploadedBy == null ? null : uploadedBy.getId();
    }

    @Override
    public String toString() {
        return "Document[id=" + id + ", docCode=" + docCode + ", status=" + status + "]";
    }
}
