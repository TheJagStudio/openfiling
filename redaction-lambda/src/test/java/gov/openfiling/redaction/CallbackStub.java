package gov.openfiling.redaction;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stand-in for filing-api's {@code POST /internal/v1/redactions/events}. */
public final class CallbackStub implements AutoCloseable {

    public record Call(String token, JsonNode body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public volatile int status = 204;
    public volatile int failFirst;
    private final AtomicInteger requests = new AtomicInteger();

    public CallbackStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/redactions/events", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            int n = requests.incrementAndGet();
            if (n <= failFirst) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                calls.add(new Call(exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                        JSON.readTree(new String(body, StandardCharsets.UTF_8))));
                exchange.sendResponseHeaders(status, -1);
            }
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1/redactions/events";
    }

    public int requests() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
