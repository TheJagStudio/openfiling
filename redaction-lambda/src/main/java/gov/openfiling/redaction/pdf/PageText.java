package gov.openfiling.redaction.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * The extracted text of one page plus, for every character, its box in page display space (points, origin at the
 * top-left of the rendered page, i.e. what {@code PDFRenderer} draws). Separator characters inserted by the
 * extractor (spaces between words, line breaks) have no box ({@code NaN}).
 *
 * @param number 1-based page number
 * @param text   page text
 * @param x0     left edge per character
 * @param y0     top edge per character
 * @param x1     right edge per character
 * @param y1     bottom edge per character
 * @param width  display width of the page in points
 * @param height display height of the page in points
 */
public record PageText(int number, String text, float[] x0, float[] y0, float[] x1, float[] y1,
                       float width, float height) {

    /** An axis-aligned rectangle in page display space (points). */
    public record Rect(float x0, float y0, float x1, float y1) {
        public float width() {
            return x1 - x0;
        }

        public float height() {
            return y1 - y0;
        }
    }

    public boolean hasBox(int index) {
        return !Float.isNaN(x0[index]);
    }

    /**
     * Rectangles covering the characters {@code [start, end)}: one rectangle per visual line (characters whose
     * vertical extents overlap are merged), so a value wrapped over two lines yields two boxes.
     */
    public List<Rect> rectsFor(int start, int end) {
        List<Rect> rects = new ArrayList<>();
        float rx0 = Float.NaN;
        float ry0 = 0;
        float rx1 = 0;
        float ry1 = 0;
        for (int i = Math.max(0, start); i < Math.min(end, text.length()); i++) {
            if (!hasBox(i)) {
                continue;
            }
            boolean sameLine = !Float.isNaN(rx0) && y0[i] < ry1 && y1[i] > ry0 && x0[i] >= rx0 - 2;
            if (sameLine) {
                rx0 = Math.min(rx0, x0[i]);
                ry0 = Math.min(ry0, y0[i]);
                rx1 = Math.max(rx1, x1[i]);
                ry1 = Math.max(ry1, y1[i]);
            } else {
                if (!Float.isNaN(rx0)) {
                    rects.add(new Rect(rx0, ry0, rx1, ry1));
                }
                rx0 = x0[i];
                ry0 = y0[i];
                rx1 = x1[i];
                ry1 = y1[i];
            }
        }
        if (!Float.isNaN(rx0)) {
            rects.add(new Rect(rx0, ry0, rx1, ry1));
        }
        return rects;
    }
}
