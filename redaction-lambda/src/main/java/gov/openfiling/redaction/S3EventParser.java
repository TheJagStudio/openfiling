package gov.openfiling.redaction;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses the S3 event notification JSON (real S3 or the emulator) into records with decoded keys. */
public final class S3EventParser {

    /** One {@code Records[]} entry. {@code key} is URL-decoded (S3 form encoding: {@code +} is a space). */
    public record S3Record(String eventName, String bucket, String key, long size) {
    }

    private final JsonMapper json;

    public S3EventParser(JsonMapper json) {
        this.json = json;
    }

    public List<S3Record> parse(byte[] event) {
        JsonNode root;
        try {
            root = json.readTree(event);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Event is not valid JSON");
        }
        JsonNode records = root == null ? null : root.get("Records");
        if (records == null || !records.isArray()) {
            throw new IllegalArgumentException("Event has no Records array");
        }
        List<S3Record> result = new ArrayList<>();
        for (JsonNode record : records) {
            JsonNode s3 = record.path("s3");
            String bucket = s3.path("bucket").path("name").asString(null);
            String rawKey = s3.path("object").path("key").asString(null);
            if (bucket == null || rawKey == null) {
                continue;
            }
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            result.add(new S3Record(record.path("eventName").asString(""), bucket, key,
                    s3.path("object").path("size").asLong(-1)));
        }
        return result;
    }
}
