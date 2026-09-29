package gov.openfiling.api.dto;

public record PublicStats(long applicationsFiled, long published, long issued, long documentsProcessed, long redactionsApplied) {
}
