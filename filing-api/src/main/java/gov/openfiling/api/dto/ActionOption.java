package gov.openfiling.api.dto;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.WorkflowAction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One workflow action the viewer may perform now. {@code attachableCodes} is an addition to the contract. */
public record ActionOption(
        @Schema(example = "FILE_RESPONSE") WorkflowAction action,
        @Schema(example = "File response") String label,
        @Schema(example = "RESPONSE_FILED", nullable = true) ApplicationStatus toStatus,
        boolean requiresNote,
        boolean acceptsDocuments,
        boolean destructive,
        @Schema(example = "[\"CTNF\"]") List<DocumentCode> attachableCodes) {

    public static ActionOption of(WorkflowAction action) {
        return new ActionOption(action, action.getLabel(), action.getToStatus(), action.isRequiresNote(),
                action.acceptsDocuments(), action.isDestructive(), List.copyOf(action.getAttachableCodes()));
    }
}
