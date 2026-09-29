package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.ReferenceResponse;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.EntityStatus;

import java.util.Arrays;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Enumerations and limits for clients (static per deployment). */
@Service
@RequiredArgsConstructor
public class ReferenceService {

    private final AppProperties.Storage storage;

    public ReferenceResponse reference() {
        return new ReferenceResponse(
                Arrays.stream(ApplicationStatus.values())
                        .map(s -> new ReferenceResponse.StatusRef(s.name(), s.getLabel(), s.getCategory(), s.isTerminal(), s.getDescription()))
                        .toList(),
                Arrays.stream(DocumentCode.values())
                        .map(c -> new ReferenceResponse.DocumentCodeRef(c.name(), c.getLabel(), c.getSource(), c.getDefaultVisibility(),
                                c.getDescription()))
                        .toList(),
                Arrays.stream(ApplicationType.values()).map(t -> new ReferenceResponse.CodeLabel(t.name(), t.getLabel())).toList(),
                Arrays.stream(EntityStatus.values()).map(e -> new ReferenceResponse.CodeLabel(e.name(), e.getLabel())).toList(),
                new ReferenceResponse.Limits(storage.maxUploadBytes(), storage.maxDocumentsPerApplication(),
                        List.of(DocumentService.PDF)));
    }
}
