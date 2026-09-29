package gov.openfiling.redaction.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import gov.openfiling.redaction.TestPdfs;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsPerPageTextWithGlyphBoxes() throws Exception {
        byte[] pdf = TestPdfs.pdf(List.of(
                List.of("First line of page one", "SSN 078-05-1120 here"),
                List.of("Page two text")));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            ExtractedDocument extracted = extractor.extract(doc);
            assertThat(extracted.pageCount()).isEqualTo(2);
            PageText page1 = extracted.page(1);
            assertThat(page1.text()).contains("First line of page one\nSSN 078-05-1120 here");
            assertThat(extracted.page(2).text().strip()).isEqualTo("Page two text");
            assertThat(page1.width()).isEqualTo(PDRectangle.LETTER.getWidth());
            assertThat(page1.height()).isEqualTo(PDRectangle.LETTER.getHeight());
            assertThat(page1.x0()).hasSameSizeAs(page1.text().toCharArray());
            assertThat(extracted.totalChars()).isEqualTo(page1.text().length() + extracted.page(2).text().length());

            // "F" of the first line: left edge at x = 72pt, baseline at 792 - 720 = 72pt from the top
            assertThat(page1.hasBox(0)).isTrue();
            assertThat(page1.x0()[0]).isCloseTo(TestPdfs.LEFT, within(1f));
            float baseline = PDRectangle.LETTER.getHeight() - TestPdfs.TOP_BASELINE;
            assertThat(page1.y0()[0]).isLessThan(baseline).isGreaterThan(baseline - 14);
            assertThat(page1.y1()[0]).isGreaterThan(baseline);
            // separators inserted by the extractor (line breaks) have no box; real space glyphs do
            assertThat(page1.hasBox(page1.text().indexOf('\n'))).isFalse();
            assertThat(page1.hasBox(page1.text().indexOf(' '))).isTrue();

            int start = page1.text().indexOf("078-05-1120");
            List<PageText.Rect> rects = page1.rectsFor(start, start + "078-05-1120".length());
            assertThat(rects).hasSize(1);
            PageText.Rect rect = rects.getFirst();
            float secondBaseline = baseline + TestPdfs.LEADING;
            assertThat(rect.y0()).isLessThan(secondBaseline);
            assertThat(rect.y1()).isGreaterThan(secondBaseline);
            assertThat(rect.x0()).isGreaterThan(TestPdfs.LEFT + 15); // after "SSN "
            assertThat(rect.width()).isBetween(50f, 75f); // 11 chars of 11pt Helvetica
            assertThat(rect.height()).isGreaterThan(10f);
        }
    }

    @Test
    void valueWrappedOverTwoLinesYieldsTwoRectangles() throws Exception {
        byte[] pdf = TestPdfs.pdf(List.of(List.of("Contact at 1234 Maple Grove", "Lane, Springfield")));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PageText page = extractor.extract(doc).page(1);
            int start = page.text().indexOf("1234");
            int end = page.text().indexOf("Lane") + 4;
            assertThat(page.rectsFor(start, end)).hasSize(2);
            assertThat(page.rectsFor(0, 0)).isEmpty();
        }
    }

    @Test
    void rotatedPagesReportDisplaySize() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.setRotation(90);
            doc.addPage(page);
            PageText text = extractor.extract(doc).page(1);
            assertThat(text.width()).isEqualTo(PDRectangle.LETTER.getHeight());
            assertThat(text.height()).isEqualTo(PDRectangle.LETTER.getWidth());
            assertThat(text.text()).isEmpty();
        }
    }
}
