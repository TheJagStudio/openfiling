package gov.openfiling.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** S3 event notification JSON (only the fields filing-api needs). Keys are URL-encoded like S3. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record S3EventNotification(@JsonProperty("Records") List<Record> records) {

    public List<Record> safeRecords() {
        return records == null ? List.of() : records;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Record(String eventName, String eventTime, S3 s3) {

        public boolean isObjectCreated() {
            return eventName != null && eventName.startsWith("ObjectCreated");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3(Bucket bucket, S3Object object) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bucket(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3Object(String key, Long size, String eTag) {
    }
}
