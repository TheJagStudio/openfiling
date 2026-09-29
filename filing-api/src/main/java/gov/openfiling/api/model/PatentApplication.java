package gov.openfiling.api.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A patent application ({@code applications} table). */
@Entity
@Table(name = "applications")
@Getter
@Setter
public class PatentApplication {

    @Id
    private UUID id;

    @Column(name = "application_number", length = 8)
    private String applicationNumber;

    @Column(name = "confirmation_number", length = 4)
    private String confirmationNumber;

    @Column(nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_type", nullable = false, length = 16)
    private ApplicationType applicationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_status", nullable = false, length = 8)
    private EntityStatus entityStatus;

    @Column(name = "abstract_text", length = 5000)
    private String abstractText;

    @Column(name = "technology_field", length = 200)
    private String technologyField;

    @Column(name = "attorney_docket_number", length = 50)
    private String attorneyDocketNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "examiner_id")
    private User examiner;

    @Column(name = "art_unit", length = 8)
    private String artUnit;

    @Column(name = "filing_date")
    private LocalDate filingDate;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(length = 110)
    private String signature;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "publication_number", length = 20)
    private String publicationNumber;

    @Column(name = "publication_date")
    private LocalDate publicationDate;

    @Column(name = "patent_number", length = 20)
    private String patentNumber;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    @BatchSize(size = 100)
    private List<ApplicationInventor> inventors = new ArrayList<>();

    /**
     * Replaces the inventor list, updating existing rows in place by position. Hibernate flushes
     * inserts before orphan deletes, so "clear and re-add" would collide on (application_id, sequence).
     */
    public void replaceInventors(List<ApplicationInventor> replacement) {
        for (int i = 0; i < replacement.size(); i++) {
            ApplicationInventor source = replacement.get(i);
            if (i < inventors.size()) {
                ApplicationInventor target = inventors.get(i);
                target.setGivenName(source.getGivenName());
                target.setFamilyName(source.getFamilyName());
                target.setCity(source.getCity());
                target.setState(source.getState());
                target.setCountry(source.getCountry());
                target.setCitizenship(source.getCitizenship());
            } else {
                source.setApplication(this);
                source.setSequence(i + 1);
                inventors.add(source);
            }
        }
        while (inventors.size() > replacement.size()) {
            inventors.removeLast();
        }
    }

    public boolean isOwnedBy(UUID userId) {
        return owner != null && owner.getId().equals(userId);
    }

    public UUID examinerId() {
        return examiner == null ? null : examiner.getId();
    }

    @Override
    public String toString() {
        return "PatentApplication[id=" + id + ", status=" + status + "]";
    }
}
