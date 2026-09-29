package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record DownloadResponse(
        @Schema(example = "http://localhost:9000/openfiling-documents/…?X-Amz-Signature=…") String url,
        Instant expiresAt,
        @Schema(example = "spec.pdf") String fileName) {
}
