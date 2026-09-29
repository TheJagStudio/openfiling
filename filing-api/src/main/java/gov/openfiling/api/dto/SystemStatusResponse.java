package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record SystemStatusResponse(List<Component> components, Counts counts, Redactions redactions) {

    public record Component(
            @Schema(example = "storage") String name,
            @Schema(example = "UP") String status,
            @Schema(example = "Bucket openfiling-documents reachable") String detail) {

        public static Component up(String name, String detail) {
            return new Component(name, "UP", detail);
        }

        public static Component down(String name, String detail) {
            return new Component(name, "DOWN", detail);
        }
    }

    public record Counts(long users, long applications, long filedApplications, long documents, long storageBytes) {
    }

    public record Redactions(
            long pending, long processing, long completed, long failed, long totalRedactions, List<CategoryCount> byCategory) {
    }
}
