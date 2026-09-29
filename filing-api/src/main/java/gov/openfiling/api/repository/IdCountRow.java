package gov.openfiling.api.repository;

import java.util.UUID;

/** Projection for {@code select id, count(*) ... group by id} (batch counts that avoid N+1 queries). */
public interface IdCountRow {

    UUID getId();

    long getTotal();
}
