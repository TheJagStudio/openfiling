package gov.openfiling.api.service;

import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.security.Viewer;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Loads applications through the visibility rules: invisible and missing applications are
 * indistinguishable (404); visible-but-not-yours is 403.
 */
@Component
@RequiredArgsConstructor
public class ApplicationAccess {

    private final ApplicationRepository applications;
    private final WorkflowPolicy policy;

    public PatentApplication loadVisible(UUID id, Viewer viewer) {
        return applications.findWithPartiesById(id)
                .filter(app -> policy.canView(app, viewer))
                .orElseThrow(() -> NotFoundException.of("Application", id));
    }

    public PatentApplication loadOwned(UUID id, Viewer viewer, String operation) {
        PatentApplication app = loadVisible(id, viewer);
        if (!policy.isOwner(app, viewer)) {
            throw new ForbiddenException("Only the applicant can " + operation);
        }
        return app;
    }
}
