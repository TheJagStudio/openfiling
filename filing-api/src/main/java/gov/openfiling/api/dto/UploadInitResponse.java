package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/** The new PENDING_UPLOAD document plus the pre-signed PUT the browser must perform. */
public record UploadInitResponse(DocumentResponse document, Upload upload) {

    public record Upload(
            @Schema(example = "http://localhost:9000/openfiling-documents/public/…?X-Amz-Signature=…") String url,
            @Schema(example = "PUT") String method,
            @Schema(example = "{\"Content-Type\":\"application/pdf\"}") Map<String, String> headers,
            Instant expiresAt) {
    }
}
