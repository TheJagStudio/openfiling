package gov.openfiling.api.repository;

import gov.openfiling.api.model.ApplicationStatus;

/** Projection for {@code select status, count(*) ... group by status}. */
public interface StatusCountRow {

    ApplicationStatus getStatus();

    long getTotal();
}
