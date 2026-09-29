package gov.openfiling.redaction.samples;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Writes the OpenFiling demo PDFs. Every person, number and address in them is fictitious (555-01xx phone numbers,
 * example.com e-mail, the well-known specimen SSN 078-05-1120, the textbook routing number 021000021).
 *
 * <pre>.\gradlew.bat generateSamples   # -> ../samples</pre>
 */
public final class SamplePdfGenerator {

    static final String TITLE = "Adaptive Thermal Management System for Solid-State Battery Packs";
    static final String CUI_BANNER = "CUI//SP-EXPT";

    private SamplePdfGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "../samples").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        for (String name : List.of("specification-with-pii.pdf", "claims.pdf", "abstract.pdf", "drawings.pdf",
                "oath-declaration.pdf")) {
            Files.deleteIfExists(dir.resolve(name));
        }
        specification(dir.resolve("specification-with-pii.pdf"));
        claims(dir.resolve("claims.pdf"));
        abstractPage(dir.resolve("abstract.pdf"));
        drawings(dir.resolve("drawings.pdf"));
        oathDeclaration(dir.resolve("oath-declaration.pdf"));
        System.out.println("Sample PDFs written to " + dir);
    }

    // ================================================================= documents

    static void specification(Path target) throws IOException {
        try (Layout doc = new Layout("Specification", null)) {
            doc.centered(TITLE.toUpperCase(), Layout.BOLD, 14);
            doc.space(6);
            doc.centered("SPECIFICATION", Layout.BOLD, 12);
            doc.space(12);
            doc.paragraph("Attorney Docket No. OFL-2026-0417US", Layout.REGULAR, 11);
            doc.paragraph("Inventors: Elena V. Marsh (Alexandria, VA, US); Tomasz K. Wierzbicki (Rockville, MD, US)", Layout.REGULAR, 11);
            doc.paragraph("Assignee: Northwind Energy Storage Laboratories, Inc.", Layout.REGULAR, 11);
            doc.space(10);
            doc.heading("CROSS-REFERENCE TO RELATED APPLICATIONS");
            doc.body("[0001] This application claims the benefit of U.S. Provisional Application No. 63/512,904, filed "
                    + "March 3, 2026, the entire contents of which are incorporated herein by reference.");
            doc.heading("FIELD");
            doc.body("[0002] The present disclosure relates to battery thermal management and, more particularly, to a "
                    + "closed-loop system that redistributes heat among the cells of a solid-state battery pack using a "
                    + "network of phase-change modules and a predictive controller.");
            doc.heading("BACKGROUND");
            doc.body("[0003] Solid-state lithium-metal cells offer higher energy density than conventional lithium-ion "
                    + "cells, but their ionic conductivity drops sharply below approximately 15 degrees C and their "
                    + "interfaces degrade above approximately 60 degrees C. Pack designers therefore need to keep every "
                    + "cell inside a narrow temperature window during fast charging and cold starts.");
            doc.body("[0004] Existing liquid-cooled plates, such as those described in U.S. Pat. No. 10,923,114 to Okafor "
                    + "et al. and in the publication by Lindqvist and Moreau (J. Power Sources, 2024), remove heat "
                    + "uniformly and cannot move heat from hot cells to cold cells. Resistive heaters add parasitic load "
                    + "and respond slowly.");
            doc.heading("SUMMARY");
            doc.body("[0005] A thermal management system includes a plurality of phase-change modules interposed "
                    + "between adjacent cells, a bidirectional thermoelectric bridge coupling each pair of modules, a "
                    + "temperature sensor array, and a controller configured to predict a per-cell temperature "
                    + "trajectory and to drive the bridges so that heat flows from cells predicted to exceed an upper "
                    + "threshold toward cells predicted to fall below a lower threshold.");

            // --- page 2: controlled technical data with the (fictitious) inventor contact block
            doc.newPage(CUI_BANNER);
            doc.boxed(List.of(
                    "EXPORT CONTROLLED - ITAR / EAR NOTICE",
                    "This document contains technical data whose export is restricted by the Arms Export Control Act",
                    "(International Traffic in Arms Regulations, ITAR). Bridge firmware: ECCN 3A001. Housing: EAR99.",
                    "Distribution statement D. Distribution authorized to DoD and U.S. DoD contractors only."));
            doc.space(8);
            doc.heading("INVENTOR CORRESPONDENCE (PERSONAL - NOT FOR PUBLICATION)");
            doc.body("Dr. Elena V. Marsh, 1427 Willow Creek Drive, Apt 3B, Alexandria, VA 22314");
            doc.body("Home phone: (555) 010-4477    Mobile: 555-010-9832");
            doc.body("Personal e-mail: elena.marsh.home@example.com");
            doc.body("Date of Birth: 04/17/1981    SSN: 078-05-1120");
            doc.body("Reimbursement: ABA Routing Number 021000021, Checking Account No. 4455667788");
            doc.body("Emergency contact: Robert J. Marsh (spouse). Medical accommodation: the inventor uses an implanted "
                    + "cardiac pacemaker; lab visits near the 3 T magnet must be avoided.");
            doc.space(6);
            doc.heading("DETAILED DESCRIPTION");
            doc.body("[0006] Referring to FIG. 1, a battery pack 100 includes cells 102a-102n arranged in a stack. Each "
                    + "phase-change module 104 comprises a paraffin-graphite composite having a melting point between "
                    + "38 degrees C and 42 degrees C, encapsulated in an aluminum pouch 106 of 0.2 mm wall thickness.");
            doc.body("[0007] A thermoelectric bridge 108 includes bismuth telluride couples 110 sandwiched between copper "
                    + "spreaders 112. When the controller 120 applies a forward current, heat is pumped from module "
                    + "104a to module 104b; a reverse current pumps heat in the opposite direction.");
            doc.body("[0008] The telemetry gateway of the test rig at 192.168.40.17 streams sensor frames every 50 ms "
                    + "to the controller 120 for model identification.");

            // --- page 3: more description (clean)
            doc.newPage(CUI_BANNER);
            doc.body("[0009] The controller 120 maintains a reduced-order thermal model of the pack in which each cell "
                    + "is represented by a lumped capacitance C_i coupled to its neighbours through conductances G_ij. "
                    + "At every control step the controller predicts the temperature trajectory over a horizon of "
                    + "120 seconds using the measured charging current and the ambient temperature.");
            doc.body("[0010] A model-predictive optimisation selects bridge currents that minimise the weighted sum of "
                    + "(a) the squared deviation of each predicted cell temperature from a set point of 35 degrees C, "
                    + "(b) the electrical energy consumed by the bridges, and (c) the rate of change of bridge current. "
                    + "Constraints limit each bridge current to 4 A and each module state of charge to between 5 and 95 "
                    + "percent latent capacity.");
            doc.body("[0011] FIG. 2 shows the latent-heat state of charge of modules 104 during a 10-minute fast "
                    + "charge at 3C. Modules adjacent to the pack centre reach 80 percent latent capacity after six "
                    + "minutes, whereupon the bridges 108 transfer heat outward to the edge modules, which remain below "
                    + "40 percent.");
            doc.body("[0012] In a cold-start mode, the controller operates the bridges in reverse so that heat stored in "
                    + "the modules during the previous drive cycle is returned to the cells, raising the coldest cell "
                    + "from minus 10 degrees C to 15 degrees C in less than four minutes without drawing grid power.");
            doc.body("[0013] FIG. 3 illustrates a fault-handling routine. If a temperature sensor 114 reports a value "
                    + "inconsistent with the model by more than 5 K, the controller flags the sensor, substitutes the "
                    + "model estimate, and limits the charging current to 1C until the discrepancy is resolved.");
            doc.body("[0014] While the invention has been described with reference to exemplary embodiments, those "
                    + "skilled in the art will understand that various changes may be made and equivalents substituted "
                    + "without departing from the scope of the claims.");

            // --- page 4: examples (clean)
            doc.newPage(null);
            doc.heading("EXAMPLES");
            doc.body("[0015] Example 1. A 48-cell pack of 20 Ah solid-state cells was charged from 10 to 80 percent "
                    + "state of charge in 11 minutes. With the bridges disabled the maximum cell-to-cell temperature "
                    + "spread was 14.2 K; with the controller active the spread was 3.1 K.");
            doc.body("[0016] Example 2. The same pack, soaked at minus 20 degrees C for twelve hours, delivered 92 "
                    + "percent of its room-temperature power within 180 seconds of key-on when the stored latent heat "
                    + "was returned by the bridges, compared with 41 percent for a resistive-heater baseline.");
            doc.body("[0017] Example 3. Over 1,500 accelerated cycles the capacity fade was 6.8 percent with the "
                    + "controller and 11.9 percent without it.");
            doc.save(target, TITLE + " - Specification", "Elena V. Marsh");
        }
    }

    static void claims(Path target) throws IOException {
        String[] claims = {
            "A thermal management system for a battery pack, comprising: a plurality of phase-change modules, each "
                    + "disposed between two adjacent cells of the battery pack; a plurality of bidirectional "
                    + "thermoelectric bridges, each thermally coupling a pair of the phase-change modules; a "
                    + "temperature sensor array; and a controller configured to predict a temperature trajectory of "
                    + "each cell and to drive the bridges such that heat flows from a first cell predicted to exceed an "
                    + "upper threshold toward a second cell predicted to fall below a lower threshold.",
            "The system of claim 1, wherein each phase-change module comprises a paraffin-graphite composite having a "
                    + "melting point between 38 degrees C and 42 degrees C.",
            "The system of claim 2, wherein the composite is encapsulated in an aluminum pouch.",
            "The system of claim 1, wherein each thermoelectric bridge comprises bismuth telluride couples between "
                    + "copper spreaders.",
            "The system of claim 1, wherein the controller predicts the temperature trajectory over a horizon of at "
                    + "least 60 seconds.",
            "The system of claim 1, wherein the controller uses a reduced-order lumped-capacitance model of the pack.",
            "The system of claim 6, wherein the controller identifies parameters of the model from sensor data "
                    + "recorded during operation.",
            "The system of claim 1, wherein the controller selects bridge currents by model-predictive optimisation.",
            "The system of claim 8, wherein the optimisation penalises electrical energy consumed by the bridges.",
            "The system of claim 8, wherein the optimisation constrains a latent-heat state of charge of each module.",
            "The system of claim 1, wherein, in a cold-start mode, the controller drives the bridges to return heat "
                    + "stored in the modules to the cells.",
            "The system of claim 1, wherein the controller limits a charging current when a sensor reading deviates "
                    + "from a model estimate by more than a threshold.",
            "The system of claim 1, wherein the cells are solid-state lithium-metal cells.",
            "A method of managing temperature in a battery pack, comprising: measuring cell temperatures; predicting a "
                    + "temperature trajectory of each cell; and driving bidirectional thermoelectric bridges between "
                    + "phase-change modules such that heat flows from cells predicted to overheat toward cells "
                    + "predicted to undercool.",
            "The method of claim 14, further comprising storing heat in the phase-change modules during charging and "
                    + "returning the stored heat to the cells during a subsequent cold start.",
            "The method of claim 14, wherein predicting comprises solving a reduced-order thermal model.",
            "The method of claim 14, further comprising substituting a model estimate for a faulty sensor reading.",
            "The method of claim 14, wherein the driving minimises a weighted sum of temperature deviation, bridge "
                    + "energy and rate of change of bridge current.",
            "A non-transitory computer-readable medium storing instructions that, when executed by a processor, cause "
                    + "the processor to perform the method of claim 14.",
            "A vehicle comprising the thermal management system of claim 1."
        };
        try (Layout doc = new Layout("Claims", null)) {
            doc.centered("CLAIMS", Layout.BOLD, 13);
            doc.space(8);
            doc.body("What is claimed is:");
            for (int i = 0; i < claims.length; i++) {
                doc.body((i + 1) + ". " + claims[i]);
            }
            doc.save(target, TITLE + " - Claims", null);
        }
    }

    static void abstractPage(Path target) throws IOException {
        try (Layout doc = new Layout("Abstract", null)) {
            doc.centered("ABSTRACT OF THE DISCLOSURE", Layout.BOLD, 13);
            doc.space(10);
            doc.body("A thermal management system for a solid-state battery pack places phase-change modules between "
                    + "adjacent cells and couples neighbouring modules with bidirectional thermoelectric bridges. A "
                    + "controller predicts each cell's temperature trajectory with a reduced-order model and drives "
                    + "the bridges so that heat moves from cells predicted to overheat to cells predicted to fall below "
                    + "their optimal window. Heat stored during fast charging is returned to the cells during cold "
                    + "starts, narrowing cell-to-cell temperature spread and reducing capacity fade.");
            doc.save(target, TITLE + " - Abstract", null);
        }
    }

    static void drawings(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            // FIG. 1: cell stack with modules and bridges
            PDPage p1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p1);
            try (PDPageContentStream c = new PDPageContentStream(doc, p1)) {
                c.setLineWidth(1.2f);
                for (int i = 0; i < 5; i++) {
                    float x = 120 + i * 80;
                    c.addRect(x, 360, 40, 220); // cell
                    c.stroke();
                    if (i < 4) {
                        c.setNonStrokingColor(new Color(215, 215, 215));
                        c.addRect(x + 45, 380, 30, 180); // phase-change module
                        c.fill();
                        c.setNonStrokingColor(Color.BLACK);
                        c.addRect(x + 45, 380, 30, 180);
                        c.stroke();
                    }
                    if (i < 3) { // bridge between this module and the next one
                        c.moveTo(x + 60, 560);
                        c.lineTo(x + 60, 620);
                        c.lineTo(x + 140, 620);
                        c.lineTo(x + 140, 560);
                        c.stroke();
                    }
                }
                c.addRect(90, 330, 440, 320); // pack housing
                c.stroke();
                label(c, regular, 10, 128, 340, "102a");
                label(c, regular, 10, 448, 340, "102n");
                label(c, regular, 10, 170, 365, "104");
                label(c, regular, 10, 250, 628, "108");
                label(c, regular, 10, 95, 655, "100");
                c.addRect(250, 180, 120, 60); // controller
                c.stroke();
                label(c, regular, 11, 275, 205, "Controller");
                label(c, regular, 10, 375, 205, "120");
                c.moveTo(310, 240);
                c.lineTo(310, 330);
                c.stroke();
                label(c, bold, 16, 270, 100, "FIG. 1");
            }

            // FIG. 2: latent state-of-charge curves
            PDPage p2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p2);
            try (PDPageContentStream c = new PDPageContentStream(doc, p2)) {
                c.setLineWidth(1.2f);
                c.moveTo(100, 250);
                c.lineTo(520, 250);
                c.moveTo(100, 250);
                c.lineTo(100, 620);
                c.stroke();
                label(c, regular, 10, 280, 225, "Charging time (min)");
                label(c, regular, 10, 40, 630, "Latent SoC (%)");
                c.setLineWidth(2f);
                c.moveTo(100, 250);
                c.curveTo(220, 420, 300, 560, 400, 580);
                c.lineTo(520, 585);
                c.stroke();
                c.setLineDashPattern(new float[] {6, 4}, 0);
                c.moveTo(100, 250);
                c.curveTo(250, 300, 380, 360, 520, 390);
                c.stroke();
                c.setLineDashPattern(new float[] {}, 0);
                label(c, regular, 10, 405, 595, "centre modules 104c");
                label(c, regular, 10, 440, 400, "edge modules 104a");
                label(c, bold, 16, 270, 100, "FIG. 2");
            }

            // FIG. 3: fault-handling flow chart
            PDPage p3 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p3);
            try (PDPageContentStream c = new PDPageContentStream(doc, p3)) {
                String[] steps = {"S1 Read sensors 114", "S2 Compare with model", "S3 Deviation > 5 K ?",
                    "S4 Flag sensor, use estimate", "S5 Limit charge to 1C"};
                for (int i = 0; i < steps.length; i++) {
                    float y = 640 - i * 100;
                    c.addRect(200, y, 220, 50);
                    c.stroke();
                    label(c, regular, 11, 215, y + 20, steps[i]);
                    if (i < steps.length - 1) {
                        c.moveTo(310, y);
                        c.lineTo(310, y - 50);
                        c.stroke();
                        c.moveTo(304, y - 42);
                        c.lineTo(310, y - 50);
                        c.lineTo(316, y - 42);
                        c.stroke();
                    }
                }
                label(c, bold, 16, 270, 100, "FIG. 3");
            }
            info(doc, TITLE + " - Drawings", null);
            doc.save(target.toFile());
        }
    }

    static void oathDeclaration(Path target) throws IOException {
        try (Layout doc = new Layout("Declaration", null)) {
            doc.centered("DECLARATION (37 CFR 1.63) FOR UTILITY OR DESIGN APPLICATION", Layout.BOLD, 12);
            doc.centered("USING AN APPLICATION DATA SHEET (37 CFR 1.76)", Layout.BOLD, 11);
            doc.space(10);
            doc.body("Title of Invention: " + TITLE);
            doc.body("As the below named inventor, I hereby declare that: This declaration is directed to the attached "
                    + "application. The above-identified application was made or authorized to be made by me. I believe "
                    + "that I am the original inventor or an original joint inventor of a claimed invention in the "
                    + "application.");
            doc.body("I hereby acknowledge that any willful false statement made in this declaration is punishable under "
                    + "18 U.S.C. 1001 by fine or imprisonment of not more than five (5) years, or both.");
            doc.heading("LEGAL NAME OF INVENTOR");
            doc.body("Elena Victoria Marsh");
            doc.heading("RESIDENCE AND MAILING ADDRESS");
            doc.body("1427 Willow Creek Drive, Apt 3B, Alexandria, VA 22314");
            doc.body("Telephone: (555) 010-4477    E-mail: elena.marsh.home@example.com");
            doc.body("Date of Birth: April 17, 1981    Citizenship: United States");
            doc.body("Passport No. X31846720 (identity verification copy retained by counsel)");
            doc.space(16);
            doc.body("Inventor signature: /Elena V. Marsh/                Date: 09/28/2026");
            doc.space(10);
            doc.body("Note: this form is filed as a RESTRICTED document and is never published.");
            doc.save(target, "Declaration - Elena V. Marsh", "Elena V. Marsh");
        }
    }

    private static void label(PDPageContentStream c, PDFont font, float size, float x, float y, String text)
            throws IOException {
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(text);
        c.endText();
    }

    static void info(PDDocument doc, String title, String author) {
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(title);
        if (author != null) {
            info.setAuthor(author);
        }
        info.setCreator("OpenFiling sample generator");
        Calendar date = new GregorianCalendar(2026, Calendar.SEPTEMBER, 28, 9, 0);
        info.setCreationDate(date);
        doc.setDocumentInformation(info);
    }

    // ================================================================= simple flowing-text layout

    /** Tiny text layout engine: word-wrapped paragraphs, headings, page numbers, optional CUI banners. */
    static final class Layout implements AutoCloseable {
        static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
        static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
        static final PDFont SANS_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        static final PDFont SANS = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        static final float MARGIN = 72;
        static final float WIDTH = PDRectangle.LETTER.getWidth() - 2 * MARGIN;

        private final PDDocument doc = new PDDocument();
        private final String name;
        private PDPageContentStream content;
        private float y;
        private int pageNo;

        Layout(String name, String banner) throws IOException {
            this.name = name;
            newPage(banner);
        }

        void newPage(String banner) throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            pageNo++;
            content = new PDPageContentStream(doc, page);
            float h = PDRectangle.LETTER.getHeight();
            if (banner != null) {
                text(SANS_BOLD, 11, (PDRectangle.LETTER.getWidth() - SANS_BOLD.getStringWidth(banner) / 1000 * 11) / 2, h - 36, banner);
                text(SANS_BOLD, 11, (PDRectangle.LETTER.getWidth() - SANS_BOLD.getStringWidth(banner) / 1000 * 11) / 2, 30, banner);
            }
            text(SANS, 9, PDRectangle.LETTER.getWidth() - MARGIN - 60, 48, name + " - page " + pageNo);
            y = h - MARGIN;
        }

        void space(float points) {
            y -= points;
        }

        void heading(String text) throws IOException {
            space(6);
            paragraph(text, BOLD, 11);
        }

        void body(String text) throws IOException {
            paragraph(text, REGULAR, 11);
            space(4);
        }

        void centered(String text, PDFont font, float size) throws IOException {
            ensure(size * 1.5f);
            float w = font.getStringWidth(text) / 1000 * size;
            text(font, size, (PDRectangle.LETTER.getWidth() - w) / 2, y, text);
            y -= size * 1.5f;
        }

        void paragraph(String text, PDFont font, float size) throws IOException {
            for (String line : wrap(text, font, size, WIDTH)) {
                ensure(size * 1.45f);
                text(font, size, MARGIN, y, line);
                y -= size * 1.45f;
            }
        }

        void boxed(List<String> lines) throws IOException {
            float size = 9.5f;
            float height = lines.size() * size * 1.5f + 10;
            ensure(height + 4);
            content.setLineWidth(1.5f);
            content.addRect(MARGIN - 6, y - height + size, WIDTH + 12, height);
            content.stroke();
            for (int i = 0; i < lines.size(); i++) {
                text(i == 0 ? SANS_BOLD : SANS, size, MARGIN, y - 2, lines.get(i));
                y -= size * 1.5f;
            }
            y -= 10;
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN) {
                newPage(null);
            }
        }

        private void text(PDFont font, float size, float x, float yPos, String text) throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(x, yPos);
            content.showText(text);
            content.endText();
        }

        static List<String> wrap(String text, PDFont font, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        void save(Path target, String title, String author) throws IOException {
            content.close();
            content = null;
            info(doc, title, author);
            doc.save(target.toFile());
        }

        @Override
        public void close() throws IOException {
            if (content != null) {
                content.close();
            }
            doc.close();
        }
    }
}
