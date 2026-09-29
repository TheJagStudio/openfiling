package gov.openfiling.redaction.local;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import gov.openfiling.redaction.RedactionHandler;

/**
 * Minimal local stand-in for the AWS Lambda Invoke API (port {@code ${PORT:9001}}):
 * <ul>
 *   <li>{@code POST /2015-03-31/functions/openfiling-redaction/invocations} — with {@code X-Amz-Invocation-Type: Event}
 *       the invocation is queued on a bounded executor (max 4 concurrent) and answered {@code 202}; {@code DryRun}
 *       answers {@code 204}; otherwise ({@code RequestResponse}) it runs synchronously and returns the handler output
 *       with {@code 200} (a handler exception is reported like Lambda: {@code 200} + {@code X-Amz-Function-Error}).</li>
 *   <li>{@code GET /health} — {@code {"status":"UP","ai":true|false}}.</li>
 * </ul>
 */
public class LocalLambdaRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalLambdaRuntime.class);
    public static final String FUNCTION_NAME = "openfiling-redaction";
    static final String INVOKE_PATH = "/2015-03-31/functions/" + FUNCTION_NAME + "/invocations";

    private final HttpServer server;
    private final RequestStreamHandler handler;
    private final boolean aiEnabled;
    private final ThreadPoolExecutor asyncExecutor;
    private final ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalLambdaRuntime(int port, RequestStreamHandler handler, boolean aiEnabled, int maxConcurrent,
                              int queueCapacity) throws IOException {
        this.handler = handler;
        this.aiEnabled = aiEnabled;
        this.asyncExecutor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().name("redaction-", 1).daemon(true).factory());
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::route);
        server.setExecutor(requestExecutor);
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9001"));
        RedactionHandler handler = new RedactionHandler();
        LocalLambdaRuntime runtime = new LocalLambdaRuntime(port, handler, handler.aiEnabled(), 4, 100);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        runtime.start();
    }

    public LocalLambdaRuntime start() {
        server.start();
        log.info("Local Lambda runtime listening on http://localhost:{}{} (ai={})", port(), INVOKE_PATH, aiEnabled);
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void route(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/health".equals(path)) {
                respond(exchange, 200, "{\"status\":\"UP\",\"ai\":" + aiEnabled + "}");
            } else if (path.startsWith("/2015-03-31/functions/") && path.endsWith("/invocations")) {
                if (!"POST".equals(method)) {
                    respond(exchange, 405, error("InvalidRequestContentException", "Use POST"));
                } else if (!INVOKE_PATH.equals(path)) {
                    respond(exchange, 404, error("ResourceNotFoundException", "Function not found: " + path));
                } else {
                    invoke(exchange);
                }
            } else {
                respond(exchange, 404, error("ResourceNotFoundException", "No route for " + method + " " + path));
            }
        } catch (RuntimeException e) {
            log.error("Local runtime request failed", e);
        }
    }

    private void invoke(HttpExchange exchange) throws IOException {
        byte[] event = exchange.getRequestBody().readAllBytes();
        String type = exchange.getRequestHeaders().getFirst("X-Amz-Invocation-Type");
        if ("DryRun".equalsIgnoreCase(type)) {
            respond(exchange, 204, null);
            return;
        }
        if ("Event".equalsIgnoreCase(type)) {
            try {
                asyncExecutor.execute(() -> runQuietly(event));
            } catch (RejectedExecutionException e) {
                respond(exchange, 429, error("TooManyRequestsException", "Async invocation queue is full"));
                return;
            }
            exchange.getResponseHeaders().set("X-Amzn-RequestId", java.util.UUID.randomUUID().toString());
            respond(exchange, 202, null);
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            handler.handleRequest(new ByteArrayInputStream(event), out, null);
            respond(exchange, 200, out.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            exchange.getResponseHeaders().set("X-Amz-Function-Error", "Unhandled");
            respond(exchange, 200, "{\"errorType\":\"" + e.getClass().getSimpleName() + "\",\"errorMessage\":\""
                    + escape(e.getMessage()) + "\"}");
        }
    }

    private void runQuietly(byte[] event) {
        try {
            handler.handleRequest(new ByteArrayInputStream(event), OutputStream.nullOutputStream(), null);
        } catch (Exception e) {
            log.error("Async invocation failed: {}", e.getClass().getName());
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String error(String type, String message) {
        return "{\"Type\":\"User\",\"__type\":\"" + type + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /** Waits for queued async invocations (tests / graceful shutdown). */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (asyncExecutor.getActiveCount() == 0 && asyncExecutor.getQueue().isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Override
    public void close() {
        server.stop(0);
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        requestExecutor.shutdown();
    }
}
