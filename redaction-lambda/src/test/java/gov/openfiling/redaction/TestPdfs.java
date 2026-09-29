package gov.openfiling.redaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds small PDFs for tests: one page per entry, one text line per list element (Helvetica 11 pt). */
public final class TestPdfs {

    public static final float LEFT = 72f;
    public static final float TOP_BASELINE = 720f;
    public static final float LEADING = 16f;

    private TestPdfs() {
    }

    public static byte[] pdf(List<List<String>> pages) throws IOException {
        return build(pages, null);
    }

    public static byte[] encrypted(List<List<String>> pages) throws IOException {
        return build(pages, "user-secret");
    }

    private static byte[] build(List<List<String>> pages, String userPassword) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.setLeading(LEADING);
                    content.newLineAtOffset(LEFT, TOP_BASELINE);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            PDDocumentInformation info = new PDDocumentInformation();
            info.setAuthor("Jane Q. Inventor");
            info.setTitle("Secret title");
            info.setCreationDate(Calendar.getInstance());
            doc.setDocumentInformation(info);
            PDMetadata xmp = new PDMetadata(doc);
            xmp.importXMPMetadata("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><author>Jane</author></x:xmpmeta>"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(xmp);
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
