package gov.openfiling.s3.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import gov.openfiling.s3.config.EmulatorProperties.Notification;
import gov.openfiling.s3.config.EmulatorProperties.NotificationRetry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NotificationPublisherTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int failFirst;
    private volatile String lastHeader;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            lastHeader = exchange.getRequestHeaders().getFirst("X-Test");
            int n = calls.incrementAndGet();
            exchange.sendResponseHeaders(n <= failFirst ? 500 : 204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NotificationPublisher publisher(String url, int attempts) {
        Notification target = new Notification("t", "b1", "public/", ".pdf", url, Map.of("X-Test", "yes"));
        return new NotificationPublisher(List.of(target), new NotificationRetry(attempts, Duration.ofMillis(10),
                Duration.ofSeconds(2)), "us-east-1", HttpClient.newHttpClient(), JSON, Clock.systemUTC(),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @Test
    void retriesUntilDelivered() throws Exception {
        failFirst = 2;
        try (NotificationPublisher publisher = publisher(url(), 3)) {
            List<CompletableFuture<Boolean>> futures = publisher.objectCreated("b1", "public/x.pdf", 3, "abc");
            assertThat(futures).hasSize(1);
            assertThat(futures.getFirst().get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(calls.get()).isEqualTo(3);
        assertThat(lastHeader).isEqualTo("yes");
    }

    @Test
    void givesUpAfterConfiguredAttempts() throws Exception {
        failFirst = 10;
        try (NotificationPublisher publisher = publisher(url(), 2)) {
            assertThat(publisher.objectCreated("b1", "public/x.pdf", 3, "abc").getFirst().get(10, TimeUnit.SECONDS))
                    .isFalse();
        }
        assertThat(calls.get()).isEqualTo(2);
        try (NotificationPublisher unreachable = publisher("http://127.0.0.1:1/none", 2)) {
            assertThat(unreachable.objectCreated("b1", "public/x.pdf", 3, "abc").getFirst().get(10, TimeUnit.SECONDS))
                    .isFalse();
        }
    }

    @Test
    void onlyMatchingTargetsAreNotified() {
        try (NotificationPublisher publisher = publisher(url(), 1)) {
            assertThat(publisher.objectCreated("b2", "public/x.pdf", 1, "e")).isEmpty();
            assertThat(publisher.objectCreated("b1", "restricted/x.pdf", 1, "e")).isEmpty();
            assertThat(publisher.objectCreated("b1", "public/x.txt", 1, "e")).isEmpty();
        }
    }

    @Test
    void eventDocumentMatchesTheS3Shape() {
        Map<String, Object> event = S3EventFactory.objectCreated("redaction", "us-east-1", "openfiling-documents",
                "public/a b/c+d(1).pdf", 42, "0f34", Instant.parse("2026-09-29T12:00:00Z"));
        JsonNode record = JSON.valueToTree(event).get("Records").get(0);
        assertThat(record.get("eventVersion").asString()).isEqualTo("2.1");
        assertThat(record.get("eventSource").asString()).isEqualTo("aws:s3");
        assertThat(record.get("eventTime").asString()).isEqualTo("2026-09-29T12:00:00.000Z");
        assertThat(record.get("eventName").asString()).isEqualTo("ObjectCreated:Put");
        assertThat(record.at("/s3/configurationId").asString()).isEqualTo("redaction");
        assertThat(record.at("/s3/bucket/arn").asString()).isEqualTo("arn:aws:s3:::openfiling-documents");
        assertThat(record.at("/s3/object/key").asString()).isEqualTo("public/a+b/c%2Bd%281%29.pdf");
        assertThat(record.at("/s3/object/size").asLong()).isEqualTo(42);
        assertThat(record.at("/s3/object/sequencer").asString()).hasSize(18);
        String next = S3EventFactory.nextSequencer(Instant.parse("2026-09-29T12:00:00Z"));
        assertThat(next.compareTo(record.at("/s3/object/sequencer").asString())).isPositive();
    }
}
