package gov.openfiling.redaction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

import gov.openfiling.redaction.RedactionPipeline.RecordResult;
import gov.openfiling.redaction.callback.CallbackClient;
import gov.openfiling.redaction.detect.AnthropicMessageClient;
import gov.openfiling.redaction.detect.ClaudeDetector;
import gov.openfiling.redaction.detect.RuleBasedDetector;
import gov.openfiling.redaction.storage.S3ObjectStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * AWS Lambda entry point ({@code gov.openfiling.redaction.RedactionHandler::handleRequest}). Input: an S3 event
 * notification; output: {@code {"processed": n, "results": [{documentId, status, redactionCount, error}]}}.
 * Records outside the documents bucket or not under {@code public/} are skipped (not counted).
 */
public class RedactionHandler implements RequestStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(RedactionHandler.class);
    static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        // Lambda: only /tmp is writable; keep PDFBox's font cache there. Rendering is headless.
        if (System.getProperty("pdfbox.fontcache") == null) {
            System.setProperty("pdfbox.fontcache", System.getProperty("java.io.tmpdir"));
        }
        System.setProperty("java.awt.headless", "true");
    }

    /** Handler output. */
    public record Output(int processed, List<RecordResult> results) {
    }

    private final RedactionPipeline pipeline;
    private final S3EventParser parser = new S3EventParser(JSON);

    /** Used by the Lambda runtime: wires everything from environment variables. */
    public RedactionHandler() {
        this(fromEnvironment(System.getenv()));
    }

    public RedactionHandler(RedactionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** Production wiring (S3 SDK client, Claude when configured, JDK HTTP callback client). */
    public static RedactionPipeline fromEnvironment(Map<String, String> env) {
        RedactionConfig config = RedactionConfig.fromEnv(env);
        log.info("Redaction function configured: {}", config);
        ClaudeDetector claude = null;
        if (config.aiActive()) {
            claude = new ClaudeDetector(new AnthropicMessageClient(config.anthropicApiKey()), config.claudeModel(),
                    ClaudeDetector.parseEffort(config.claudeEffort()), config.claudeFallbacks(), config.maxChunkChars());
        }
        CallbackClient callback = new CallbackClient(config.callbackUrl(), config.internalToken(), JSON, 3,
                Duration.ofMillis(500));
        return new RedactionPipeline(config, new S3ObjectStore(config), new RuleBasedDetector(), claude,
                config.claudeModel(), callback, JSON, Clock.systemUTC());
    }

    public boolean aiEnabled() {
        return pipeline.aiEnabled();
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        Output result = handle(input.readAllBytes());
        output.write(JSON.writeValueAsBytes(result));
    }

    public Output handle(byte[] event) {
        List<RecordResult> results = new ArrayList<>();
        for (S3EventParser.S3Record record : parser.parse(event)) {
            pipeline.process(record).ifPresent(results::add);
        }
        return new Output(results.size(), results);
    }
}
