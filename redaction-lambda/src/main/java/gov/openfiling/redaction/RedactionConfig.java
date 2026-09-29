package gov.openfiling.redaction;

import java.util.Locale;
import java.util.Map;

/**
 * Function configuration, read from environment variables (see the contract / README for the defaults).
 * {@link #toString()} never prints secrets.
 */
public record RedactionConfig(
        String documentsBucket,
        String publicBucket,
        String s3Endpoint,
        String s3Region,
        String s3AccessKey,
        String s3SecretKey,
        boolean s3PathStyle,
        String anthropicApiKey,
        String claudeModel,
        String claudeEffort,
        boolean aiEnabled,
        boolean claudeFallbacks,
        String callbackUrl,
        String internalToken,
        int renderDpi,
        int maxPages,
        long maxObjectBytes,
        int maxChunkChars) {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";

    public static RedactionConfig fromEnv(Map<String, String> env) {
        return new RedactionConfig(
                get(env, "DOCUMENTS_BUCKET", "openfiling-documents"),
                get(env, "PUBLIC_BUCKET", "openfiling-public-copies"),
                env.containsKey("S3_ENDPOINT") ? blankToNull(env.get("S3_ENDPOINT")) : null,
                get(env, "S3_REGION", "us-east-1"),
                get(env, "S3_ACCESS_KEY", "openfiling-local"),
                get(env, "S3_SECRET_KEY", "openfiling-local-secret"),
                bool(env, "S3_PATH_STYLE", true),
                blankToNull(env.get("ANTHROPIC_API_KEY")),
                get(env, "CLAUDE_MODEL", DEFAULT_MODEL),
                get(env, "CLAUDE_EFFORT", "medium"),
                bool(env, "REDACTION_AI_ENABLED", true),
                bool(env, "CLAUDE_FALLBACKS", true),
                blankToNull(get(env, "CALLBACK_URL", "http://localhost:8080/internal/v1/redactions/events")),
                get(env, "INTERNAL_TOKEN", "dev-internal-token-change-me"),
                integer(env, "RENDER_DPI", 150),
                integer(env, "MAX_PAGES", 500),
                Long.parseLong(get(env, "MAX_OBJECT_BYTES", String.valueOf(500L * 1024 * 1024))),
                integer(env, "CLAUDE_MAX_CHUNK_CHARS", 60_000));
    }

    /** Claude is used only when an API key is configured and {@code REDACTION_AI_ENABLED} is not false. */
    public boolean aiActive() {
        return aiEnabled && anthropicApiKey != null;
    }

    private static String get(Map<String, String> env, String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean bool(Map<String, String> env, String name, boolean fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : !value.trim().toLowerCase(Locale.ROOT).equals("false");
    }

    private static int integer(Map<String, String> env, String name, int fallback) {
        return Integer.parseInt(get(env, name, String.valueOf(fallback)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public String toString() {
        return "RedactionConfig[documentsBucket=" + documentsBucket + ", publicBucket=" + publicBucket
                + ", s3Endpoint=" + s3Endpoint + ", s3Region=" + s3Region + ", s3PathStyle=" + s3PathStyle
                + ", ai=" + aiActive() + ", claudeModel=" + claudeModel + ", claudeEffort=" + claudeEffort
                + ", claudeFallbacks=" + claudeFallbacks + ", callbackUrl=" + callbackUrl + ", renderDpi=" + renderDpi
                + ", maxPages=" + maxPages + ", maxObjectBytes=" + maxObjectBytes + "]";
    }
}
