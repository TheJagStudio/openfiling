package gov.openfiling.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "application_inventors")
@Getter
@Setter
public class ApplicationInventor {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private PatentApplication application;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "given_name", nullable = false, length = 100)
    private String givenName;

    @Column(name = "family_name", nullable = false, length = 100)
    private String familyName;

    @Column(length = 100)
    private String city;

    @Column(length = 50)
    private String state;

    @Column(length = 2)
    private String country;

    @Column(length = 2)
    private String citizenship;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public String fullName() {
        return givenName + " " + familyName;
    }

    @Override
    public String toString() {
        return "ApplicationInventor[id=" + id + ", sequence=" + sequence + "]";
    }
}
