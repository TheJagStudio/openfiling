package gov.openfiling.redaction.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

class LocalLambdaRuntimeTest {

    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private LocalLambdaRuntime runtime;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Echo handler: records the event and answers {"echo": <event>}; "fail" makes it throw. */
    private final RequestStreamHandler handler = new RequestStreamHandler() {
        @Override
        public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
            String event = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            invocations.add(event);
            if (event.contains("fail")) {
                throw new IOException("handler \"failed\"");
            }
            output.write(("{\"echo\":" + event + "}").getBytes(StandardCharsets.UTF_8));
        }
    };

    private LocalLambdaRuntime start(int maxConcurrent, int queue) throws IOException {
        runtime = new LocalLambdaRuntime(0, handler, true, maxConcurrent, queue).start();
        return runtime;
    }

    @AfterEach
    void stop() {
        gate.countDown();
        if (runtime != null) {
            runtime.close();
        }
    }

    private HttpResponse<String> post(String path, String invocationType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + runtime.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (invocationType != null) {
            request.header("X-Amz-Invocation-Type", invocationType);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void synchronousInvocationReturnsHandlerOutput() throws Exception {
        start(4, 10);
        HttpResponse<String> response = post(LocalLambdaRuntime.INVOKE_PATH, null, "{\"Records\":[]}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"echo\":{\"Records\":[]}}");
        assertThat(post(LocalLambdaRuntime.INVOKE_PATH, "RequestResponse", "{}").statusCode()).isEqualTo(200);

        HttpResponse<String> failed = post(LocalLambdaRuntime.INVOKE_PATH, null, "{\"fail\":true}");
        assertThat(failed.statusCode()).isEqualTo(200);
        assertThat(failed.headers().firstValue("X-Amz-Function-Error")).contains("Unhandled");
        assertThat(failed.body()).contains("\"errorType\":\"IOException\"").contains("handler \\\"failed\\\"");
    }

    @Test
    void eventInvocationIsAcceptedAndRunsAsynchronously() throws Exception {
        start(1, 1);
        gate = new CountDownLatch(1);
        HttpResponse<String> first = post(LocalLambdaRuntime.INVOKE_PATH, "Event", "{\"n\":1}");
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.headers().firstValue("X-Amzn-RequestId")).isPresent();
        assertThat(invocations).isEmpty(); // still blocked in the handler
        Thread.sleep(100);
        assertThat(post(LocalLambdaRuntime.INVOKE_PATH, "Event", "{\"n\":2}").statusCode()).isEqualTo(202); // queued
        assertThat(post(LocalLambdaRuntime.INVOKE_PATH, "Event", "{\"n\":3}").statusCode()).isEqualTo(429); // queue full
        gate.countDown();
        assertThat(runtime.awaitIdle(5000)).isTrue();
        assertThat(invocations).containsExactly("{\"n\":1}", "{\"n\":2}");
        assertThat(post(LocalLambdaRuntime.INVOKE_PATH, "Event", "{\"fail\":1}").statusCode()).isEqualTo(202);
        assertThat(runtime.awaitIdle(5000)).isTrue();
    }

    @Test
    void healthDryRunAndUnknownRoutes() throws Exception {
        start(4, 10);
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + runtime.port() + "/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"UP\",\"ai\":true}");
        assertThat(post(LocalLambdaRuntime.INVOKE_PATH, "DryRun", "{}").statusCode()).isEqualTo(204);
        assertThat(post("/2015-03-31/functions/other/invocations", null, "{}").statusCode()).isEqualTo(404);
        assertThat(post("/nowhere", null, "{}").statusCode()).isEqualTo(404);
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + runtime.port()
                + LocalLambdaRuntime.INVOKE_PATH)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(invocations).isEmpty();
    }
}
