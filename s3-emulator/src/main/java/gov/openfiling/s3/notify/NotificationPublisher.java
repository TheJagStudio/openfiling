package gov.openfiling.s3.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.openfiling.s3.config.EmulatorProperties;
import gov.openfiling.s3.config.EmulatorProperties.Notification;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers S3 {@code ObjectCreated:Put} event notifications to every matching target, asynchronously on virtual
 * threads, with up to {@code attempts} tries and exponential back-off. Delivery failures are logged, never thrown
 * (as with S3, the PUT has already succeeded).
 */
public class NotificationPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final List<Notification> targets;
    private final EmulatorProperties.NotificationRetry retry;
    private final String region;
    private final HttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final ExecutorService executor;

    public NotificationPublisher(List<Notification> targets, EmulatorProperties.NotificationRetry retry, String region,
                                 HttpClient http, JsonMapper json, Clock clock, ExecutorService executor) {
        this.targets = List.copyOf(targets);
        this.retry = retry;
        this.region = region;
        this.http = http;
        this.json = json;
        this.clock = clock;
        this.executor = executor;
    }

    /** Fires one notification per matching target; the futures complete with true on delivery. */
    public List<CompletableFuture<Boolean>> objectCreated(String bucket, String key, long size, String etag) {
        return targets.stream()
                .filter(t -> t.matches(bucket, key))
                .map(t -> {
                    Map<String, Object> event = S3EventFactory.objectCreated(t.name(), region, bucket, key, size, etag,
                            clock.instant());
                    byte[] body = json.writeValueAsBytes(event);
                    return CompletableFuture.supplyAsync(() -> deliver(t, body, bucket), executor);
                })
                .toList();
    }

    boolean deliver(Notification target, byte[] body, String bucket) {
        Duration backoff = retry.initialBackoff();
        int attempts = Math.max(1, retry.attempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.url()))
                        .timeout(retry.requestTimeout())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                target.headers().forEach(request::header);
                HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 == 2) {
                    log.info("Event notification '{}' for bucket {} delivered (HTTP {}, attempt {})",
                            target.name(), bucket, response.statusCode(), attempt);
                    return true;
                }
                log.warn("Event notification '{}' rejected with HTTP {} (attempt {}/{})",
                        target.name(), response.statusCode(), attempt, attempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Event notification '{}' to {} failed: {} (attempt {}/{})",
                        target.name(), target.url(), e.toString(), attempt, attempts);
            }
            if (attempt < attempts && !sleep(backoff)) {
                return false;
            }
            backoff = backoff.multipliedBy(2);
        }
        log.error("Event notification '{}' for bucket {} given up after {} attempts", target.name(), bucket, attempts);
        return false;
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
