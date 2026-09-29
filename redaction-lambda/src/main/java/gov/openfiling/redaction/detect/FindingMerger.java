package gov.openfiling.redaction.detect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deduplicates findings from all detectors: exact duplicates collapse, overlapping spans on the same page are
 * merged into their union (so every character any detector flagged stays covered). The merged finding keeps the
 * category/reason of the dominant member — rule findings win over Claude findings (they are validated), then the
 * longer span — and its source is {@code RULES} whenever a rule contributed.
 */
public final class FindingMerger {

    private static final Comparator<Finding> ORDER = Comparator.comparingInt(Finding::page)
            .thenComparingInt(Finding::start)
            .thenComparing(Comparator.comparingInt(Finding::end).reversed());

    private FindingMerger() {
    }

    public static List<Finding> merge(List<Finding> findings) {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(ORDER);
        List<Finding> merged = new ArrayList<>();
        Finding current = null;
        for (Finding next : sorted) {
            if (current != null && current.overlaps(next)) {
                current = combine(current, next);
            } else {
                if (current != null) {
                    merged.add(current);
                }
                current = next;
            }
        }
        if (current != null) {
            merged.add(current);
        }
        return merged;
    }

    private static Finding combine(Finding a, Finding b) {
        Finding dominant = dominates(a, b) ? a : b;
        Source source = a.source() == Source.RULES || b.source() == Source.RULES ? Source.RULES : Source.CLAUDE;
        return new Finding(a.page(), Math.min(a.start(), b.start()), Math.max(a.end(), b.end()), dominant.category(),
                source, dominant.reason());
    }

    private static boolean dominates(Finding a, Finding b) {
        if (a.source() != b.source()) {
            return a.source() == Source.RULES;
        }
        return a.length() >= b.length();
    }
}
