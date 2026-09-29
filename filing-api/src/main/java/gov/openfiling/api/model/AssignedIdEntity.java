package gov.openfiling.api.model;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import java.util.UUID;

import org.springframework.data.domain.Persistable;

/**
 * Entities whose UUID is assigned by the application and that have no {@code @Version}: tells Spring
 * Data that a freshly constructed instance is new, so {@code save} persists it (no SELECT + merge,
 * and the caller keeps the managed instance).
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        newEntity = false;
    }
}
