package gov.openfiling.redaction.detect;

/**
 * A sensitive span on one page: {@code [start, end)} character offsets into that page's extracted text.
 * The value itself is never stored here (only offsets), so a finding can be logged or reported safely.
 *
 * @param page     1-based page number
 * @param start    inclusive start offset in the page text
 * @param end      exclusive end offset in the page text
 * @param category redaction category
 * @param source   detector that produced it
 * @param reason   short explanation that never contains the value
 */
public record Finding(int page, int start, int end, Category category, Source source, String reason) {

    public Finding {
        if (page < 1 || start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid finding span");
        }
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(Finding other) {
        return page == other.page && start < other.end && other.start < end;
    }
}
