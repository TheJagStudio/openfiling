package gov.openfiling.api.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed {@code app.*} configuration. Records holding secrets mask them in {@code toString}. */
public final class AppProperties {

    private AppProperties() {
    }

    @ConfigurationProperties("app.jwt")
    public record Jwt(
            @DefaultValue("openfiling") String issuer,
            @DefaultValue("15m") Duration accessTokenTtl,
            @DefaultValue("7d") Duration refreshTokenTtl,
            @DefaultValue("../data/keys") String keyDir) {

        /** Relative paths resolve against the working directory (the service folder in dev). */
        public Path keyPath() {
            return Path.of(keyDir);
        }
    }

    @ConfigurationProperties("app.security")
    public record Security(
            @DefaultValue("dev-internal-token-change-me") String internalToken,
            @DefaultValue("5") int maxFailedLogins,
            @DefaultValue("15m") Duration lockoutDuration,
            @DefaultValue("20") int authRateLimitPerMinute) {

        @Override
        public String toString() {
            return "Security[internalToken=***, maxFailedLogins=" + maxFailedLogins + ", lockoutDuration="
                    + lockoutDuration + ", authRateLimitPerMinute=" + authRateLimitPerMinute + "]";
        }
    }

    @ConfigurationProperties("app.cors")
    public record Cors(@DefaultValue("http://localhost:4200") List<String> allowedOrigins) {
    }

    @ConfigurationProperties("app.storage")
    public record Storage(
            @DefaultValue("http://localhost:9000") String endpoint,
            @DefaultValue("") String publicEndpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("openfiling-local") String accessKey,
            @DefaultValue("openfiling-local-secret") String secretKey,
            @DefaultValue("true") boolean pathStyle,
            @DefaultValue("openfiling-documents") String documentsBucket,
            @DefaultValue("openfiling-public-copies") String publicBucket,
            @DefaultValue("15m") Duration uploadUrlTtl,
            @DefaultValue("5m") Duration downloadUrlTtl,
            @DefaultValue("524288000") long maxUploadBytes,
            @DefaultValue("60") int maxDocumentsPerApplication,
            @DefaultValue("1h") Duration staleUploadGrace) {

        public boolean hasEndpointOverride() {
            return endpoint != null && !endpoint.isBlank();
        }

        /** Endpoint used in pre-signed URLs: {@code public-endpoint}, else {@code endpoint} (blank = AWS). */
        public String presignEndpoint() {
            return publicEndpoint != null && !publicEndpoint.isBlank() ? publicEndpoint : endpoint;
        }

        public boolean hasStaticCredentials() {
            return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
        }

        @Override
        public String toString() {
            return "Storage[endpoint=" + endpoint + ", publicEndpoint=" + publicEndpoint + ", region=" + region
                    + ", accessKey=" + accessKey + ", secretKey=***, pathStyle=" + pathStyle + ", documentsBucket="
                    + documentsBucket + ", publicBucket=" + publicBucket + ", uploadUrlTtl=" + uploadUrlTtl
                    + ", downloadUrlTtl=" + downloadUrlTtl + ", maxUploadBytes=" + maxUploadBytes
                    + ", maxDocumentsPerApplication=" + maxDocumentsPerApplication
                    + ", staleUploadGrace=" + staleUploadGrace + "]";
        }
    }

    @ConfigurationProperties("app.redaction")
    public record Redaction(
            @DefaultValue("http://localhost:9001/2015-03-31/functions/openfiling-redaction/invocations") String invokeUrl,
            @DefaultValue("http://localhost:9001/health") String healthUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout) {
    }

    @ConfigurationProperties("app.seed")
    public record Seed(@DefaultValue("false") boolean enabled) {
    }
}
