package gov.openfiling.redaction.pdf;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDFBox text extraction that keeps, for every extracted character, the box of the glyph that produced it, so that
 * a matched substring can be mapped back to rectangles on the rendered page.
 *
 * <p>Boxes use {@link TextPosition#getX()} / {@link TextPosition#getY()} (display space: page rotation applied, origin
 * top-left), which is the coordinate system of {@code PDFRenderer} output. The vertical extent is derived from the
 * font size (ascent ≈ 0.9 em above, descent ≈ 0.3 em below the baseline) because PDFBox's glyph heights are only an
 * approximation; boxes are deliberately generous since they drive what gets blacked out.
 */
public class PdfTextExtractor {

    public ExtractedDocument extract(PDDocument document) throws IOException {
        Stripper stripper = new Stripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(1);
        stripper.setEndPage(document.getNumberOfPages());
        stripper.writeText(document, new StringWriter());
        // PDFTextStripper skips pages without a content stream: add them as empty pages so indexes stay aligned.
        List<PageText> pages = new ArrayList<>(document.getNumberOfPages());
        for (int number = 1; number <= document.getNumberOfPages(); number++) {
            PageText page = stripper.pages.get(number);
            pages.add(page != null ? page : emptyPage(number, document.getPage(number - 1)));
        }
        return new ExtractedDocument(pages);
    }

    private static PageText emptyPage(int number, PDPage page) {
        float[] none = new float[0];
        float[] size = displaySize(page);
        return new PageText(number, "", none, none, none, none, size[0], size[1]);
    }

    /** Width and height of the page as displayed (crop box, rotation applied). */
    static float[] displaySize(PDPage page) {
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean swap = rotation == 90 || rotation == 270;
        return swap ? new float[] {box.getHeight(), box.getWidth()} : new float[] {box.getWidth(), box.getHeight()};
    }

    private static final class Stripper extends PDFTextStripper {

        private final java.util.Map<Integer, PageText> pages = new java.util.HashMap<>();
        private StringBuilder text;
        private FloatList x0;
        private FloatList y0;
        private FloatList x1;
        private FloatList y1;

        Stripper() {
            super();
            setLineSeparator("\n");
            setWordSeparator(" ");
            setParagraphStart("");
            setParagraphEnd("");
            setPageStart("");
            setPageEnd("");
            setArticleStart("");
            setArticleEnd("");
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            text = new StringBuilder();
            x0 = new FloatList();
            y0 = new FloatList();
            x1 = new FloatList();
            y1 = new FloatList();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            float[] size = displaySize(page);
            int number = getCurrentPageNo();
            pages.put(number, new PageText(number, text.toString(), x0.toArray(), y0.toArray(), x1.toArray(),
                    y1.toArray(), size[0], size[1]));
        }

        @Override
        protected void writeString(String ignored, List<TextPosition> positions) {
            for (TextPosition p : positions) {
                String unicode = p.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                float size = p.getFontSizeInPt() > 0 ? p.getFontSizeInPt() : Math.max(p.getHeight(), 8f);
                float left = p.getX();
                float right = left + Math.max(p.getWidth(), size * 0.25f);
                float baseline = p.getY();
                float top = baseline - Math.max(p.getHeight(), size * 0.9f);
                float bottom = baseline + size * 0.3f;
                // A glyph may map to several chars (ligatures): each char gets the glyph box.
                for (int i = 0; i < unicode.length(); i++) {
                    append(unicode.charAt(i), left, top, right, bottom);
                }
            }
        }

        @Override
        protected void writeWordSeparator() {
            appendSeparator(getWordSeparator());
        }

        @Override
        protected void writeLineSeparator() {
            appendSeparator(getLineSeparator());
        }

        private void appendSeparator(String separator) {
            for (int i = 0; i < separator.length(); i++) {
                append(separator.charAt(i), Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            }
        }

        private void append(char c, float left, float top, float right, float bottom) {
            text.append(c);
            x0.add(left);
            y0.add(top);
            x1.add(right);
            y1.add(bottom);
        }
    }

    /** Growable primitive float list (keeps per-character geometry compact). */
    static final class FloatList {
        private float[] data = new float[256];
        private int size;

        void add(float value) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
