package gov.openfiling.api.storage;

import gov.openfiling.api.model.DocumentVisibility;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Object key conventions. Originals: {@code {public|restricted}/{applicationId}/{documentId}/{file}.pdf};
 * only the {@code public/} prefix triggers the redaction function.
 */
public final class StorageKeys {

    private static final int MAX_BASE_LENGTH = 120;

    private StorageKeys() {
    }

    public static String originalKey(DocumentVisibility visibility, UUID applicationId, UUID documentId, String fileName) {
        String prefix = visibility == DocumentVisibility.PUBLIC ? "public" : "restricted";
        return prefix + "/" + applicationId + "/" + documentId + "/" + sanitizeFileName(fileName);
    }

    /** Keeps {@code [A-Za-z0-9._-]}, collapses the rest into {@code -}, always ends in {@code .pdf}. */
    public static String sanitizeFileName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (name.length() > MAX_BASE_LENGTH) {
            name = name.substring(0, MAX_BASE_LENGTH);
        }
        return (name.isEmpty() ? "document" : name) + ".pdf";
    }

    /** Display file name: path and control characters removed, whitespace trimmed. */
    public static String cleanDisplayName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1).replaceAll("\\p{Cntrl}", "").trim();
        return name.isEmpty() ? "document.pdf" : name;
    }

    /** URL-encodes a key the way S3 event notifications do (per segment, space as {@code +}). */
    public static String encodeForEvent(String key) {
        return Arrays.stream(key.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }

    /** Decodes a key from an S3 event notification. */
    public static String decodeEventKey(String key) {
        return URLDecoder.decode(key, StandardCharsets.UTF_8);
    }
}
