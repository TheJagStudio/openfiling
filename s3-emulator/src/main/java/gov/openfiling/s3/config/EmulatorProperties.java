package gov.openfiling.s3.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code app.*} settings of the emulator.
 *
 * @param storageRoot       folder holding one sub-folder per bucket
 * @param region            the only region accepted in credential scopes
 * @param accessKey         the only access key id
 * @param secretKey         its secret
 * @param buckets           buckets created at startup
 * @param maxObjectBytes    PutObject size limit ({@code EntityTooLarge} above)
 * @param clockSkew         allowed difference between request time and server time
 * @param cors              browser CORS policy
 * @param notifications     S3 event notification targets
 * @param notificationRetry delivery attempts and back-off
 */
@ConfigurationProperties(prefix = "app")
public record EmulatorProperties(
        @DefaultValue("../data/buckets") String storageRoot,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("openfiling-local") String accessKey,
        @DefaultValue("openfiling-local-secret") String secretKey,
        @DefaultValue({"openfiling-documents", "openfiling-public-copies"}) List<String> buckets,
        @DefaultValue("1GB") DataSize maxObjectBytes,
        @DefaultValue("15m") Duration clockSkew,
        @DefaultValue Cors cors,
        List<Notification> notifications,
        @DefaultValue NotificationRetry notificationRetry) {

    public EmulatorProperties {
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
    }

    /** CORS: allowed origins, methods {@code GET, PUT, HEAD}, any request header, exposed ETag / request id. */
    public record Cors(@DefaultValue("http://localhost:4200") List<String> allowedOrigins,
                       @DefaultValue({"GET", "PUT", "HEAD"}) List<String> allowedMethods,
                       @DefaultValue({"ETag", "x-amz-request-id"}) List<String> exposedHeaders,
                       @DefaultValue("3600") long maxAgeSeconds) {
    }

    /**
     * One event notification target (the S3 bucket notification configuration equivalent).
     *
     * @param name    configurationId in the event JSON
     * @param bucket  bucket filter
     * @param prefix  key prefix filter (optional)
     * @param suffix  key suffix filter (optional)
     * @param url     endpoint receiving a POST of the S3 event JSON
     * @param headers extra request headers (e.g. X-Internal-Token, X-Amz-Invocation-Type)
     */
    public record Notification(String name, String bucket, String prefix, String suffix, String url,
                               Map<String, String> headers) {

        public Notification {
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public boolean matches(String eventBucket, String key) {
            return bucket != null && bucket.equals(eventBucket) && key.startsWith(prefix) && key.endsWith(suffix);
        }
    }

    public record NotificationRetry(@DefaultValue("3") int attempts,
                                    @DefaultValue("500ms") Duration initialBackoff,
                                    @DefaultValue("10s") Duration requestTimeout) {
    }
}
