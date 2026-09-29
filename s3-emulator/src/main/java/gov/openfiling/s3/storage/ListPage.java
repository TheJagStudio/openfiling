package gov.openfiling.s3.storage;

import java.util.List;

/** One page of a ListObjectsV2 result. */
public record ListPage(List<StoredObject> objects, List<String> commonPrefixes, boolean truncated,
                       String nextContinuationToken) {
}
