package gov.openfiling.s3.storage;

/** Admin view of a bucket. */
public record BucketStats(String name, long objectCount, long totalBytes) {
}
