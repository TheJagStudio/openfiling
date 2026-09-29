package gov.openfiling.redaction.detect;

import java.time.Duration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;

/**
 * The real {@link ClaudeClient}: official Anthropic Java SDK (OkHttp transport, 3 SDK-level retries with back-off
 * for 408/409/429/5xx and connection errors, 5 minute timeout). SDK exceptions are caught most-specific first and
 * translated into {@link ClaudeUnavailableException} with a short message — the caller degrades to rules-only.
 */
public class AnthropicMessageClient implements ClaudeClient, AutoCloseable {

    private final AnthropicClient client;

    public AnthropicMessageClient(String apiKey) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(3)
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    @Override
    public Reply create(StructuredMessageCreateParams<ClaudeFindings> params) {
        try {
            StructuredMessage<ClaudeFindings> message = client.messages().create(params);
            String stopReason = message.stopReason().map(StopReason::asString).orElse("unknown");
            if (!message.stopReason().map(StopReason.END_TURN::equals).orElse(false)) {
                return new Reply(stopReason, null); // refusal / max_tokens: never read partial content
            }
            ClaudeFindings findings = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElse(null);
            return new Reply(stopReason, findings);
        } catch (RateLimitException e) {
            throw new ClaudeUnavailableException("rate limited (429)", e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new ClaudeUnavailableException("API key rejected (" + e.statusCode() + ")", e);
        } catch (BadRequestException e) {
            throw new ClaudeUnavailableException("request rejected (400)", e);
        } catch (InternalServerException e) {
            throw new ClaudeUnavailableException("API server error (" + e.statusCode() + ")", e);
        } catch (AnthropicServiceException e) {
            throw new ClaudeUnavailableException("API error (" + e.statusCode() + ")", e);
        } catch (AnthropicIoException e) {
            throw new ClaudeUnavailableException("network error", e);
        } catch (AnthropicInvalidDataException e) {
            throw new ClaudeUnavailableException("invalid structured output", e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
