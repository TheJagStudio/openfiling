package gov.openfiling.redaction.detect;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured output Claude must return (the JSON schema is derived from these records by the Anthropic SDK:
 * {@code {findings: [{page, text, category, reason}]}}, all properties required, no additional properties).
 * The SDK uses Jackson 2 annotations, hence {@code com.fasterxml.jackson.annotation}.
 */
@JsonClassDescription("Sensitive spans (PII or CUI) found in the supplied patent-filing text.")
public record ClaudeFindings(
        @JsonPropertyDescription("Every sensitive span found; an empty list when there is none.")
        List<Item> findings) {

    public ClaudeFindings {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    @JsonClassDescription("One sensitive span.")
    public record Item(
            @JsonPropertyDescription("Page number taken from the nearest preceding '=== PAGE n ===' marker.")
            int page,
            @JsonPropertyDescription("The exact substring as it appears on that page (verbatim, no paraphrasing, no ellipsis).")
            String text,
            @JsonPropertyDescription("Redaction category.")
            Category category,
            @JsonPropertyDescription("Short reason (max 15 words). Never repeat the sensitive value itself.")
            String reason) {
    }
}
