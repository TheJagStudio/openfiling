package gov.openfiling.redaction.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gov.openfiling.redaction.TestPdfs;
import gov.openfiling.redaction.detect.Finding;
import gov.openfiling.redaction.detect.FindingMerger;
import gov.openfiling.redaction.detect.RuleBasedDetector;

class PdfRedactorTest {

    @TempDir
    Path dir;

    @Test
    void producesImageOnlyCopyWithoutSensitiveTextOrMetadata() throws Exception {
        byte[] pdf = TestPdfs.pdf(List.of(
                List.of("CUI//SP-EXPT", "Inventor e-mail: jane.inventor@example.com", "SSN: 078-05-1120",
                        "A widget comprising a sprocket."),
                List.of("Second page without secrets.")));
        Path out = dir.resolve("copy.pdf");
        int redactions;
        try (PDDocument source = Loader.loadPDF(pdf)) {
            ExtractedDocument text = new PdfTextExtractor().extract(source);
            List<Finding> findings = FindingMerger.merge(new RuleBasedDetector().detect(text).findings());
            redactions = findings.size();
            assertThat(redactions).isEqualTo(3);
            new PdfRedactor(100).redact(source, text, findings, PdfRedactor.footer(redactions), out);
        }

        try (PDDocument copy = Loader.loadPDF(out.toFile())) {
            assertThat(copy.getNumberOfPages()).isEqualTo(2);
            assertThat(copy.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.LETTER.getWidth());
            assertThat(copy.getPage(0).getMediaBox().getHeight()).isEqualTo(PDRectangle.LETTER.getHeight());
            String extracted = new PDFTextStripper().getText(copy);
            assertThat(extracted).doesNotContain("078-05-1120").doesNotContain("jane.inventor")
                    .doesNotContain("CUI").doesNotContain("sprocket").doesNotContain("Second page");
            // the footer is the only text left
            assertThat(extracted.strip().lines().map(String::strip).distinct().toList())
                    .containsExactly("PUBLIC COPY — sanitized by OpenFiling — 3 redactions");
            assertThat(copy.getDocumentInformation().getAuthor()).isNull();
            assertThat(copy.getDocumentInformation().getTitle()).isNull();
            assertThat(copy.getDocumentInformation().getCOSObject().keySet())
                    .doesNotContain(COSName.AUTHOR, COSName.TITLE, COSName.CREATION_DATE);
            assertThat(copy.getDocumentCatalog().getMetadata()).isNull();
            assertThat(copy.getDocumentCatalog().getCOSObject().containsKey(COSName.METADATA)).isFalse();
            PDPage page = copy.getPage(0);
            assertThat(page.getResources().getXObjectNames()).hasSize(1);
            assertThat(page.getAnnotations()).isEmpty();

            // the SSN area is black in the rendered copy
            BufferedImage image = new PDFRenderer(copy).renderImageWithDPI(0, 72);
            float baseline = PDRectangle.LETTER.getHeight() - TestPdfs.TOP_BASELINE + 2 * TestPdfs.LEADING;
            int darkPixels = 0;
            for (int x = 110; x < 150; x++) {
                int rgb = image.getRGB(x, (int) (baseline - 4));
                if ((rgb & 0xFF) < 60 && ((rgb >> 8) & 0xFF) < 60 && ((rgb >> 16) & 0xFF) < 60) {
                    darkPixels++;
                }
            }
            assertThat(darkPixels).isGreaterThan(30);
        }
        assertThat(Files.size(out)).isPositive();
    }

    @Test
    void renderScaleIsCappedForHugePages() {
        PdfRedactor redactor = new PdfRedactor(150);
        PageText letter = new PageText(1, "", new float[0], new float[0], new float[0], new float[0], 612, 792);
        assertThat(redactor.scaleFor(letter)).isEqualTo(150f / 72f);
        PageText eSize = new PageText(1, "", new float[0], new float[0], new float[0], new float[0], 34 * 72, 44 * 72);
        float scale = redactor.scaleFor(eSize);
        assertThat(scale).isLessThan(150f / 72f);
        assertThat((double) eSize.width() * scale * eSize.height() * scale).isLessThanOrEqualTo(PdfRedactor.MAX_PIXELS * 1.0001);
    }

    @Test
    void rejectsSillyDpi() {
        assertThatThrownBy(() -> new PdfRedactor(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PdfRedactor(1200)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PdfRedactor.footer(0)).isEqualTo("PUBLIC COPY — sanitized by OpenFiling — 0 redactions");
    }
}
