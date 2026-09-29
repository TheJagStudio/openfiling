package gov.openfiling.api.storage;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.exception.UpstreamUnavailableException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Talks to the redaction function through the Lambda Invoke API shape
 * ({@code POST /2015-03-31/functions/{name}/invocations}, {@code X-Amz-Invocation-Type: Event}),
 * which the local runtime implements too. Used for "retry redaction" and the admin health check.
 */
@Slf4j
@Component
public class RedactionInvoker {

    public static final String INVOCATION_TYPE_HEADER = "X-Amz-Invocation-Type";

    private final RestClient http;
    private final AppProperties.Redaction props;
    private final AppProperties.Storage storage;
    private final Clock clock;

    public RedactionInvoker(RestClient.Builder builder, AppProperties.Redaction props, AppProperties.Storage storage, Clock clock) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(props.readTimeout());
        this.http = builder.clone().requestFactory(factory).build();
        this.props = props;
        this.storage = storage;
        this.clock = clock;
    }

    public record Health(boolean up, String detail) {
    }

    /**
     * Asynchronously (re-)invokes the function with a synthetic single-record {@code ObjectCreated:Put}
     * event for the given object.
     *
     * @throws UpstreamUnavailableException when the function cannot be reached
     */
    public void invoke(String bucket, String key, long size, String etag) {
        Map<String, Object> event = syntheticEvent(bucket, key, size, etag, clock.instant());
        try {
            http.post()
                    .uri(props.invokeUrl())
                    .header(INVOCATION_TYPE_HEADER, "Event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Redaction re-invoked for object in bucket {}", bucket);
        } catch (RestClientException e) {
            log.warn("Redaction invoke failed: {}", e.getMessage());
            throw new UpstreamUnavailableException("The redaction service is unavailable; try again later", e);
        }
    }

    public Health health() {
        try {
            JsonNode body = http.get().uri(props.healthUrl()).retrieve().body(JsonNode.class);
            String status = body == null || body.get("status") == null ? "UNKNOWN" : body.get("status").asString();
            boolean ai = body != null && body.get("ai") != null && body.get("ai").asBoolean();
            return new Health("UP".equalsIgnoreCase(status), "status " + status + (ai ? " (AI enabled)" : " (rules only)"));
        } catch (RestClientException e) {
            return new Health(false, "Unreachable: " + e.getClass().getSimpleName());
        }
    }

    static Map<String, Object> syntheticEvent(String bucket, String key, long size, String etag, Instant now) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("key", StorageKeys.encodeForEvent(key));
        object.put("size", size);
        object.put("eTag", etag == null ? "" : etag);
        object.put("sequencer", Long.toHexString(now.toEpochMilli()).toUpperCase());
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("s3SchemaVersion", "1.0");
        s3.put("configurationId", "redaction-retry");
        s3.put("bucket", Map.of("name", bucket, "arn", "arn:aws:s3:::" + bucket));
        s3.put("object", object);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("eventVersion", "2.1");
        record.put("eventSource", "aws:s3");
        record.put("awsRegion", "us-east-1");
        record.put("eventTime", DateTimeFormatter.ISO_INSTANT.format(now));
        record.put("eventName", "ObjectCreated:Put");
        record.put("requestParameters", Map.of("sourceIPAddress", "filing-api"));
        record.put("responseElements", Map.of("x-amz-request-id", UUID.randomUUID().toString()));
        record.put("s3", s3);
        return Map.of("Records", List.of(record));
    }

    String documentsBucket() {
        return storage.documentsBucket();
    }
}
