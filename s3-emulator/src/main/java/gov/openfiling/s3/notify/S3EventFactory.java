package gov.openfiling.s3.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the S3 event notification document ({@code eventVersion 2.1}) the way Amazon S3 does, including the
 * URL-encoded object key (form encoding: space becomes {@code +}; {@code /} is left as is, like S3).
 */
public final class S3EventFactory {

    static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final AtomicLong SEQUENCER = new AtomicLong();

    private S3EventFactory() {
    }

    public static String encodeKey(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    /** Monotonic, hex, fixed-width sequencer (S3 only guarantees ordering per key; this is globally ordered). */
    static String nextSequencer(Instant eventTime) {
        long floor = eventTime.toEpochMilli() * 1000;
        long value = SEQUENCER.updateAndGet(prev -> Math.max(prev + 1, floor));
        return String.format(Locale.ROOT, "%018X", value);
    }

    public static Map<String, Object> objectCreated(String configurationId, String region, String bucket, String key,
                                                    long size, String etag, Instant eventTime) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("key", encodeKey(key));
        object.put("size", size);
        object.put("eTag", etag);
        object.put("sequencer", nextSequencer(eventTime));

        Map<String, Object> bucketNode = new LinkedHashMap<>();
        bucketNode.put("name", bucket);
        bucketNode.put("arn", "arn:aws:s3:::" + bucket);

        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("s3SchemaVersion", "1.0");
        s3.put("configurationId", configurationId);
        s3.put("bucket", bucketNode);
        s3.put("object", object);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("eventVersion", "2.1");
        record.put("eventSource", "aws:s3");
        record.put("awsRegion", region);
        record.put("eventTime", EVENT_TIME.format(eventTime));
        record.put("eventName", "ObjectCreated:Put");
        record.put("s3", s3);
        return Map.of("Records", List.of(record));
    }
}
