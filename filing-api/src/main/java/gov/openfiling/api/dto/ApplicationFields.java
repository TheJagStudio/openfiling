package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.EntityStatus;

import java.util.List;

/** Editable bibliographic fields shared by create and update requests. */
public interface ApplicationFields {

    String title();

    ApplicationType applicationType();

    EntityStatus entityStatus();

    String abstractText();

    String technologyField();

    String attorneyDocketNumber();

    List<InventorDto> inventors();
}
