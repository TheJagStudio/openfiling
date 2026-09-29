package gov.openfiling.api.model;

import java.util.EnumSet;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Application type with its number series and the document codes required for filing. */
@Getter
@RequiredArgsConstructor
public enum ApplicationType {
    UTILITY("Utility", "19", EnumSet.of(DocumentCode.SPEC, DocumentCode.CLM, DocumentCode.ABST)),
    DESIGN("Design", "29", EnumSet.of(DocumentCode.SPEC, DocumentCode.DRW)),
    PLANT("Plant", "19", EnumSet.of(DocumentCode.SPEC, DocumentCode.CLM, DocumentCode.ABST)),
    PROVISIONAL("Provisional", "63", EnumSet.of(DocumentCode.SPEC));

    private final String label;
    private final String series;
    private final Set<DocumentCode> requiredDocuments;
}
