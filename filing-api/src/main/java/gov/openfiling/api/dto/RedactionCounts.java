package gov.openfiling.api.dto;

public record RedactionCounts(long pending, long processing, long completed, long failed) {
}
