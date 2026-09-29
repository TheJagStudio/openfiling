package gov.openfiling.s3.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.openfiling.s3.storage.BucketStats;
import gov.openfiling.s3.storage.FileSystemObjectStore;

/** Unauthenticated emulator admin endpoints (not part of the S3 API). */
@RestController
public class AdminController {

    private final FileSystemObjectStore store;

    public AdminController(FileSystemObjectStore store) {
        this.store = store;
    }

    @GetMapping("/_emulator/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "storageRoot", store.root().toString(), "buckets", store.bucketNames());
    }

    @GetMapping("/_emulator/buckets")
    public List<BucketStats> buckets() throws IOException {
        return store.stats();
    }
}
