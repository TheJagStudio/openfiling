package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.storage.DocumentStorage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Verifies an object uploaded through a pre-signed URL: it exists, its size is within the limit and
 * equals the declared size, its content type is PDF and its first bytes are the {@code %PDF-} magic.
 * Only metadata and 5 bytes are read; the file never passes through the API.
 */
@Component
@RequiredArgsConstructor
public class DocumentVerifier {

    static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final DocumentStorage storage;
    private final AppProperties.Storage props;

    public sealed interface Result permits Verified, Rejected, Missing {
    }

    public record Verified(DocumentStorage.ObjectHead head) implements Result {
    }

    public record Rejected(String reason) implements Result {
    }

    public record Missing() implements Result {
    }

    public Result verify(Document document) {
        Optional<DocumentStorage.ObjectHead> found = storage.head(document.getStorageBucket(), document.getStorageKey());
        if (found.isEmpty()) {
            return new Missing();
        }
        DocumentStorage.ObjectHead head = found.get();
        if (head.contentLength() > props.maxUploadBytes()) {
            return new Rejected("The file is larger than the " + props.maxUploadBytes() / (1024 * 1024) + " MiB limit");
        }
        if (head.contentLength() != document.getSizeBytes()) {
            return new Rejected("Uploaded size " + head.contentLength() + " bytes does not match the declared size "
                    + document.getSizeBytes() + " bytes");
        }
        if (head.contentType() != null && !head.contentType().toLowerCase(Locale.ROOT).startsWith("application/pdf")) {
            return new Rejected("The file must be uploaded with Content-Type application/pdf");
        }
        byte[] prefix = storage.readPrefix(document.getStorageBucket(), document.getStorageKey(), PDF_MAGIC.length);
        if (!Arrays.equals(prefix, PDF_MAGIC)) {
            return new Rejected("The file is not a PDF (missing %PDF- header)");
        }
        return new Verified(head);
    }
}
