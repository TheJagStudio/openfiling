package gov.openfiling.redaction.pdf;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.openfiling.redaction.detect.Finding;

/**
 * Burns redactions into a brand-new, image-only PDF: every page of the source is rasterised at {@code dpi},
 * opaque black boxes (with a small category label) are painted over each finding, and the bitmap becomes the only
 * content of a new page of the same display size. Nothing of the original survives — no text layer, fonts,
 * annotations, attachments, JavaScript, document information or XMP metadata. The only text in the output is the
 * footer, drawn as vector text inside a white band at the bottom of each page.
 */
public class PdfRedactor {

    private static final Logger log = LoggerFactory.getLogger(PdfRedactor.class);

    /** Upper bound of pixels per rendered page (large drawings are rendered at a lower resolution). */
    static final double MAX_PIXELS = 25_000_000d;
    static final float PADDING_PT = 1.5f;
    static final float FOOTER_HEIGHT_PT = 14f;
    private static final float JPEG_QUALITY = 0.85f;

    private final int dpi;

    public PdfRedactor(int dpi) {
        if (dpi < 36 || dpi > 600) {
            throw new IllegalArgumentException("RENDER_DPI must be between 36 and 600");
        }
        this.dpi = dpi;
    }

    /** Writes the public copy to {@code target}. */
    public void redact(PDDocument source, ExtractedDocument text, List<Finding> findings, String footer, Path target)
            throws IOException {
        Map<Integer, List<Finding>> byPage = findings.stream().collect(Collectors.groupingBy(Finding::page));
        PDFRenderer renderer = new PDFRenderer(source);
        renderer.setSubsamplingAllowed(false);
        boolean labels = true;
        try (PDDocument output = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            PDType1Font footerFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int index = 0; index < source.getNumberOfPages(); index++) {
                PageText page = text.page(index + 1);
                float scale = scaleFor(page);
                BufferedImage image = renderer.renderImage(index, scale, ImageType.RGB);
                float sx = image.getWidth() / page.width();
                float sy = image.getHeight() / page.height();
                Graphics2D g = image.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                    for (Finding finding : byPage.getOrDefault(index + 1, List.of())) {
                        for (PageText.Rect rect : page.rectsFor(finding.start(), finding.end())) {
                            int x = (int) Math.floor((rect.x0() - PADDING_PT) * sx);
                            int y = (int) Math.floor((rect.y0() - PADDING_PT) * sy);
                            int w = (int) Math.ceil((rect.width() + 2 * PADDING_PT) * sx);
                            int h = (int) Math.ceil((rect.height() + 2 * PADDING_PT) * sy);
                            g.setColor(Color.BLACK);
                            g.fillRect(x, y, w, h);
                            if (labels) {
                                labels = drawLabel(g, finding.category().name(), x, y, w, h);
                            }
                        }
                    }
                } finally {
                    g.dispose();
                }
                PDRectangle size = new PDRectangle(page.width(), page.height());
                PDPage newPage = new PDPage(size);
                output.addPage(newPage);
                PDImageXObject xobject = JPEGFactory.createFromImage(output, image, JPEG_QUALITY);
                try (PDPageContentStream content = new PDPageContentStream(output, newPage)) {
                    content.drawImage(xobject, 0, 0, size.getWidth(), size.getHeight());
                    drawFooter(content, footerFont, footer, size);
                }
            }
            output.getDocumentCatalog().setMetadata(null);
            output.save(target.toFile());
        }
    }

    /** Render scale (pixels per point) honouring {@link #MAX_PIXELS}. */
    float scaleFor(PageText page) {
        double scale = dpi / 72d;
        double pixels = page.width() * scale * page.height() * scale;
        if (pixels > MAX_PIXELS) {
            scale = Math.sqrt(MAX_PIXELS / (page.width() * page.height()));
        }
        return (float) scale;
    }

    /** Draws a small white category label inside the box. Returns false when AWT text rendering is unavailable. */
    private boolean drawLabel(Graphics2D g, String label, int x, int y, int w, int h) {
        try {
            int fontPx = Math.min((int) (h * 0.7), Math.max(6, dpi / 12));
            if (fontPx < 5) {
                return true;
            }
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontPx);
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            String shown = label.replace('_', ' ');
            if (metrics.stringWidth(shown) > w - 4) {
                return true; // label does not fit: plain black box
            }
            g.setColor(Color.WHITE);
            g.drawString(shown, x + 2, y + (h + metrics.getAscent() - metrics.getDescent()) / 2);
            return true;
        } catch (Throwable e) { // headless environments without fonts (e.g. minimal Lambda images)
            log.warn("Category labels disabled: AWT text rendering unavailable ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private static void drawFooter(PDPageContentStream content, PDType1Font font, String footer, PDRectangle size)
            throws IOException {
        content.setNonStrokingColor(Color.WHITE);
        content.addRect(0, 0, size.getWidth(), FOOTER_HEIGHT_PT);
        content.fill();
        float fontSize = 7f;
        float textWidth = font.getStringWidth(footer) / 1000f * fontSize;
        content.beginText();
        content.setNonStrokingColor(new Color(64, 64, 64));
        content.setFont(font, fontSize);
        content.newLineAtOffset(Math.max(4, (size.getWidth() - textWidth) / 2), 4.5f);
        content.showText(footer);
        content.endText();
    }

    /** Footer text required by the contract. */
    public static String footer(int redactions) {
        return "PUBLIC COPY — sanitized by OpenFiling — " + redactions + " redactions";
    }
}
