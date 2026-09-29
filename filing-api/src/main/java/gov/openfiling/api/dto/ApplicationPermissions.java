package gov.openfiling.api.dto;

import gov.openfiling.api.model.DocumentCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ApplicationPermissions(
        boolean canEdit,
        boolean canDelete,
        boolean canSubmit,
        boolean canUpload,
        @Schema(example = "[\"RESP\",\"IDS\"]") List<DocumentCode> uploadableCodes) {
}
