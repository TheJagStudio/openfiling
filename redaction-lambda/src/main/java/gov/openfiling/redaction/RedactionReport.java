package gov.openfiling.redaction;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import gov.openfiling.redaction.callback.CallbackPayload.CategoryCount;
import gov.openfiling.redaction.detect.Category;
import gov.openfiling.redaction.detect.Finding;

/**
 * {@code redaction-report.json} written next to the public copy. It describes <em>what kind</em> of data was
 * removed and where, never the removed values (only their length).
 */
public record RedactionReport(String documentId, String engine, String model, int pageCount, int redactionCount,
                              List<CategoryCount> categories, List<Entry> findings, List<String> notes,
                              String generatedAt) {

    public record Entry(int page, String category, String source, String reason, int length) {
    }

    public static RedactionReport of(String documentId, String engine, String model, int pageCount,
                                     List<Finding> findings, List<String> notes, Instant now) {
        List<Entry> entries = findings.stream()
                .map(f -> new Entry(f.page(), f.category().name(), f.source().name(), f.reason(), f.length()))
                .toList();
        return new RedactionReport(documentId, engine, model, pageCount, findings.size(), categoryCounts(findings),
                entries, List.copyOf(notes), now.toString());
    }

    /** {@code [{category, count}]} in category order, only categories that occur. */
    public static List<CategoryCount> categoryCounts(List<Finding> findings) {
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        findings.forEach(f -> counts.merge(f.category(), 1, Integer::sum));
        return counts.entrySet().stream().map(e -> new CategoryCount(e.getKey().name(), e.getValue())).toList();
    }
}
