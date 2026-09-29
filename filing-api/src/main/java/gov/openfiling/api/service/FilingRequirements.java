package gov.openfiling.api.service;

import gov.openfiling.api.exception.FieldProblem;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.repository.DocumentRepository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Filing completeness: every (non-expired) document UPLOADED and the required document codes present
 * (UTILITY/PLANT: SPEC, CLM, ABST; DESIGN: SPEC, DRW; PROVISIONAL: SPEC). Used by submission and by
 * {@code CURE_MISSING_PARTS}.
 */
@Component
@RequiredArgsConstructor
public class FilingRequirements {

    public static final String FIELD = "documents";

    private final DocumentRepository documents;

    public List<FieldProblem> problems(PatentApplication app) {
        List<FieldProblem> problems = new ArrayList<>();
        Set<DocumentCode> uploaded = EnumSet.noneOf(DocumentCode.class);
        for (Document d : documents.findByApplication_IdAndStatusNotOrderByCreatedAtDesc(app.getId(), DocumentStatus.EXPIRED)) {
            switch (d.getStatus()) {
                case UPLOADED -> uploaded.add(d.getDocCode());
                case PENDING_UPLOAD -> problems.add(new FieldProblem(FIELD,
                        d.getDocCode().displayName() + " '" + d.getFileName() + "' has not finished uploading"));
                case REJECTED -> problems.add(new FieldProblem(FIELD,
                        d.getDocCode().displayName() + " '" + d.getFileName() + "' was rejected; delete it or upload a new file"));
                default -> {
                    // EXPIRED is filtered by the query
                }
            }
        }
        for (DocumentCode required : app.getApplicationType().getRequiredDocuments()) {
            if (!uploaded.contains(required)) {
                problems.add(new FieldProblem(FIELD, required.displayName() + " is required"));
            }
        }
        return problems;
    }
}
