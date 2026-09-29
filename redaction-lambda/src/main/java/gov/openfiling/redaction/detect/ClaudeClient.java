package gov.openfiling.redaction.detect;

import com.anthropic.models.messages.StructuredMessageCreateParams;

/**
 * Seam around the Anthropic Messages API so {@link ClaudeDetector} can be tested without network calls.
 * Implementations translate every SDK failure into {@link ClaudeUnavailableException}.
 */
public interface ClaudeClient {

    /**
     * Sends the request.
     *
     * @return the stop reason and (for {@code end_turn}) the parsed findings
     * @throws ClaudeUnavailableException when the API call fails (rate limit, 5xx, network, invalid output …)
     */
    Reply create(StructuredMessageCreateParams<ClaudeFindings> params);

    /**
     * @param stopReason API stop reason ({@code end_turn}, {@code refusal}, {@code max_tokens}, …)
     * @param findings   parsed structured output, or null when the model produced none
     */
    record Reply(String stopReason, ClaudeFindings findings) {
    }
}
