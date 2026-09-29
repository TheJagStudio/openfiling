package gov.openfiling.redaction.pdf;

import java.util.List;

/** Text and glyph geometry of every page of a PDF, in page order. */
public record ExtractedDocument(List<PageText> pages) {

    public ExtractedDocument {
        pages = List.copyOf(pages);
    }

    public int pageCount() {
        return pages.size();
    }

    /** 1-based page access. */
    public PageText page(int number) {
        return pages.get(number - 1);
    }

    public long totalChars() {
        return pages.stream().mapToLong(p -> p.text().length()).sum();
    }
}
