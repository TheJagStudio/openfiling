package gov.openfiling.redaction.callback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reports redaction status to filing-api ({@code POST CALLBACK_URL} with {@code X-Internal-Token}). Up to
 * {@code attempts} tries with exponential back-off on network errors and 5xx/429; other 4xx (e.g. 404 unknown
 * document) are not retried. Never throws — a lost callback must not fail the redaction itself.
 */
public class CallbackClient {

    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);

    private final String url;
    private final String internalToken;
    private final HttpClient http;
    private final JsonMapper json;
    private final int attempts;
    private final Duration initialBackoff;

    public CallbackClient(String url, String internalToken, JsonMapper json, int attempts, Duration initialBackoff) {
        this.url = url;
        this.internalToken = internalToken;
        this.json = json;
        this.attempts = Math.max(1, attempts);
        this.initialBackoff = initialBackoff;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Sends the payload; returns true when filing-api answered 2xx. */
    public boolean send(CallbackPayload payload) {
        if (url == null) {
            log.info("Callback disabled (CALLBACK_URL unset): document {} -> {}", payload.documentId(), payload.status());
            return false;
        }
        byte[] body = json.writeValueAsBytes(payload);
        Duration backoff = initialBackoff;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Token", internalToken)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status / 100 == 2) {
                    log.info("Callback {} for document {} accepted (HTTP {})", payload.status(), payload.documentId(), status);
                    return true;
                }
                if (status / 100 == 4 && status != 429 && status != 408) {
                    log.warn("Callback {} for document {} rejected with HTTP {} (not retried)",
                            payload.status(), payload.documentId(), status);
                    return false;
                }
                log.warn("Callback {} for document {} got HTTP {} (attempt {}/{})",
                        payload.status(), payload.documentId(), status, attempt, attempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Callback {} for document {} failed: {} (attempt {}/{})",
                        payload.status(), payload.documentId(), e.getClass().getSimpleName(), attempt, attempts);
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                backoff = backoff.multipliedBy(2);
            }
        }
        return false;
    }
}
