package gov.openfiling.api.dto;

import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request for a pre-signed upload URL. Only PDFs are accepted. */
public record CreateUploadRequest(
        @Schema(example = "SPEC") @NotNull DocumentCode docCode,
        @Schema(example = "specification.pdf") @NotBlank @Size(max = 255)
        @Pattern(regexp = "(?i)^[^/\\\\\\p{Cntrl}]+\\.pdf$", message = "must be a file name ending in .pdf")
        String fileName,
        @Schema(example = "application/pdf") @NotBlank
        @Pattern(regexp = "^application/pdf$", message = "must be application/pdf")
        String contentType,
        @Schema(example = "48213") @NotNull @Positive Long sizeBytes,
        @Schema(description = "Defaults to the document code's default visibility") DocumentVisibility visibility,
        @Size(max = 200) String description) {
}
