package imagejai.engine.security;

import imagejai.config.PrivacyPosture;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the printable Data Governance statement under AI_Exports.
 */
public final class DataHandlingStatementGenerator {
    private static final float LEFT = 44f;
    private static final float RIGHT = 44f;
    private static final float TOP = 46f;
    private static final float BOTTOM = 24f;
    private static final float BODY_SIZE = 9.0f;
    private static final float BODY_LEADING = 10.7f;
    private static final float HEADING_SIZE = 10.5f;
    private static final float HEADING_LEADING = 13.0f;
    private static final float TITLE_SIZE = 15.0f;
    private static final float TITLE_LEADING = 18.0f;
    private static final String REPO_URL = "github.com/Jay2owe/ImageJAI";

    private final Path projectFolder;
    private final PrivacyPosture posture;
    private final Clock clock;
    private final String version;

    public DataHandlingStatementGenerator(Path projectFolder, PrivacyPosture posture) {
        this(projectFolder, posture, Clock.systemDefaultZone(), defaultVersion());
    }

    DataHandlingStatementGenerator(Path projectFolder, PrivacyPosture posture,
                                   Clock clock, String version) {
        if (projectFolder == null) {
            throw new IllegalArgumentException("projectFolder is required");
        }
        this.projectFolder = projectFolder.toAbsolutePath().normalize();
        this.posture = posture == null ? PrivacyPosture.defaultPosture() : posture;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.version = version == null || version.trim().isEmpty()
                ? defaultVersion()
                : version.trim();
    }

    public Path generate() throws IOException {
        LocalDate generatedDate = LocalDate.now(clock);
        Path outDir = projectFolder.resolve("AI_Exports");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("DataHandlingStatement_" + projectName()
                + "_" + DateTimeFormatter.BASIC_ISO_DATE.format(generatedDate)
                + ".pdf");

        AuditSummary summary = AuditLog.summaryFor(projectFolder);
        try (PDDocument doc = new PDDocument()) {
            FontSet fonts = FontSet.load(doc);
            PdfWriter writer = new PdfWriter(doc, fonts);
            writer.startPage();
            writeHeader(writer, generatedDate);
            writeSection1Purpose(writer);
            writeSection2DataFlow(writer);
            writeSection3Pseudonymisation(writer);
            writeSection4VendorTerms(writer);
            writeSection5AuditSummary(writer, summary);
            writeSection6OptOut(writer);
            writeSection7Limitations(writer);
            writeFooter(writer);
            writer.close();
            doc.save(out.toFile());
        }
        return out;
    }

    private void writeHeader(PdfWriter writer, LocalDate generatedDate) throws IOException {
        writer.writeTitle("ImageJAI — Data Handling Statement");
        writer.writeBody("Project: " + projectName());
        writer.writeBody("Generated: " + generatedDate
                + "    Posture in force: " + posture.label());
        writer.writeBody("ImageJAI version: " + version);
        writer.rule();
        writer.blank(4f);
    }

    private void writeSection1Purpose(PdfWriter writer) throws IOException {
        writer.section("1. Purpose");
        writer.writeBody("This statement summarises the data-handling posture of the "
                + "ImageJAI plugin for the named project. Suitable for Research Ethics "
                + "Committee amendments and Data Management Plans.");
        writer.blank();
    }

    private void writeSection2DataFlow(PdfWriter writer) throws IOException {
        writer.section("2. Data flow");
        writer.bullet("Images are read from " + projectFolder + " on the local machine.", 0);
        writer.bullet("The user selects files (and series-within-files) via the "
                + "Browse Files dialog or Fiji's File menu. Identifiable filenames "
                + "remain on the local machine.", 0);
        writer.bullet("The ImageJAI TCP server (localhost:7746) exposes commands "
                + "to the selected agent CLI.", 0);
        writer.bullet("Outbound responses pass through the PseudonymisationFilter "
                + "before reaching the agent process.", 0);
        writer.bullet("The agent CLI communicates with the configured model "
                + "endpoint (see §4).", 0);
        writer.blank();
    }

    private void writeSection3Pseudonymisation(PdfWriter writer) throws IOException {
        writer.section("3. Pseudonymisation scheme (UK GDPR Art. 4(5))");
        writer.writeBody("The following are tokenised before leaving the JVM in "
                + "Pseudonymised and On-premises postures:");
        writer.bullet("File and folder paths (whole-file and series-within-file)", 1);
        writer.bullet("OME-XML elements: Experimenter, Description, StageLabel, "
                + "AcquisitionDate, InstrumentRef, InstrumentSerialNumber, Annotation", 1);
        writer.bullet("Results-table columns: Label, Slice (when non-numeric)", 1);
        writer.bullet("Dialog and window titles", 1);
        writer.bullet("Error and log messages", 1);
        writer.blank(3f);
        writer.writeBody("Image-pixel handling is differentiated by source:");
        writer.bullet("Microscopy image content: downsampled to ≤512×512 and "
                + "text burn-ins (Incucyte timestamps, scanner labels) masked "
                + "before transmission.", 1);
        writer.bullet("GUI / dialog / window screenshots: refused and replaced "
                + "with a hash placeholder.", 1);
        writer.bullet("On-demand visual override: the agent may request "
                + "full-resolution pixel access for one call; the user must "
                + "explicitly grant; burn-in mask still applied; the grant and "
                + "consumption are logged in the audit trail.", 1);
        writer.blank(3f);
        writer.writeBody("The token map is held in JVM memory only and destroyed "
                + "at session end.");
        writer.blank();
    }

    private void writeSection4VendorTerms(PdfWriter writer) throws IOException {
        writer.section("4. Vendor contractual posture");
        for (VendorTermsRegistry.VendorTerm term : VendorTermsRegistry.ALL) {
            writer.writeBodyBold(term.vendor() + ":");
            writer.writeBody(vendorStatement(term), 12f);
        }
        writer.blank();
    }

    private void writeSection5AuditSummary(PdfWriter writer, AuditSummary summary)
            throws IOException {
        AuditSummary s = summary == null
                ? new AuditSummary(null, 0, null, null, 0L, 0L, 0, 0, 0, 0,
                        null, null, null)
                : summary;
        writer.section("5. Audit trail");
        writer.writeBody("Location: AI_Exports/imagejai_audit.csv");
        writer.writeBody("Rows in this project to date: " + s.rowCount());
        writer.writeBody("Date range: " + dateRange(s.first(), s.last()));
        writer.writeBody("Of which:");
        writer.writeBody("Pseudonymised calls: " + s.pseudonymised(), 12f);
        writer.writeBody("Visual override grants: " + s.visualOverrideGrants(), 12f);
        writer.writeBody("Posture downshifts: " + s.downshifts(), 12f);
        writer.blank();
    }

    private void writeSection6OptOut(PdfWriter writer) throws IOException {
        writer.section("6. Opt-out and escalation");
        writer.writeBody("Set the Privacy Posture to On-premises via the folder "
                + "banner or the launcher Configuration Pane. In this mode the "
                + "agent dropdown is filtered to local-binary agents only, "
                + "*-cloud Ollama tags are refused, and the visual override is "
                + "unavailable.");
        writer.blank();
    }

    private void writeSection7Limitations(PdfWriter writer) throws IOException {
        writer.section("7. Limitations");
        writer.bullet("Pseudonymisation is not anonymisation. The mapping is "
                + "reversible to anyone with live JVM access during a session.", 0);
        writer.bullet("The pseudonymisation filter governs TCP responses. For "
                + "external CLIs (Claude Code in a separate terminal), filenames "
                + "typed directly into the agent chat are NOT intercepted. The "
                + "Browse Files dialog and the embedded terminal's outbound "
                + "prompt scrubber mitigate this.", 0);
        writer.bullet("Burn-in text detection uses a fast heuristic catching "
                + "~90% of cases; configurable per-format masks cover known "
                + "microscope vendors (Incucyte, Aperio).", 0);
        writer.bullet("This statement is generated automatically from posture "
                + "and audit metadata at the moment of generation.", 0);
        writer.blank();
    }

    private void writeFooter(PdfWriter writer) throws IOException {
        writer.blank(4f);
        writer.writeBody("Generated by ImageJAI v" + version + " — " + REPO_URL);
    }

    private String vendorStatement(VendorTermsRegistry.VendorTerm term) {
        String displayUrl = term.displayUrl();
        String statement = term.statement();
        boolean quoted = term.vendor().startsWith("Anthropic")
                || term.vendor().startsWith("OpenAI")
                || term.vendor().equals("Google Gemini API (paid)");
        String body = quoted ? "\"" + statement + "\"" : statement;
        return displayUrl.isEmpty() ? body : body + " — " + displayUrl;
    }

    private String projectName() {
        Path name = projectFolder.getFileName();
        String raw = name == null ? "project" : name.toString();
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        return cleaned.isEmpty() ? "project" : cleaned;
    }

    private static String dateRange(Instant first, Instant last) {
        if (first == null || last == null) {
            return "none";
        }
        if (first.equals(last)) {
            return first.toString();
        }
        return first + " — " + last;
    }

    private static String defaultVersion() {
        Package pkg = DataHandlingStatementGenerator.class.getPackage();
        String implementation = pkg == null ? null : pkg.getImplementationVersion();
        return implementation == null || implementation.trim().isEmpty()
                ? "0.2.0"
                : implementation.trim();
    }

    static List<String> wrapText(String text, PDFont font, float fontSize,
                                 float maxWidth) throws IOException {
        List<String> lines = new ArrayList<String>();
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            lines.add("");
            return lines;
        }
        if (maxWidth <= 0f) {
            lines.add(value);
            return lines;
        }

        String[] words = value.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (textWidth(font, fontSize, candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (textWidth(font, fontSize, word) <= maxWidth) {
                line.append(word);
            } else {
                List<String> pieces = breakLongWord(word, font, fontSize, maxWidth);
                for (int i = 0; i < pieces.size() - 1; i++) {
                    lines.add(pieces.get(i));
                }
                if (!pieces.isEmpty()) {
                    line.append(pieces.get(pieces.size() - 1));
                }
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static List<String> breakLongWord(String word, PDFont font,
                                              float fontSize, float maxWidth)
            throws IOException {
        List<String> pieces = new ArrayList<String>();
        StringBuilder piece = new StringBuilder();
        for (int i = 0; i < word.length();) {
            int cp = word.codePointAt(i);
            String next = piece.toString() + new String(Character.toChars(cp));
            if (piece.length() > 0 && textWidth(font, fontSize, next) > maxWidth) {
                pieces.add(piece.toString());
                piece.setLength(0);
            }
            piece.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    private static float textWidth(PDFont font, float fontSize, String text)
            throws IOException {
        return font.getStringWidth(text == null ? "" : text) / 1000f * fontSize;
    }

    private static final class PdfWriter {
        private final PDDocument doc;
        private final FontSet fonts;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;

        PdfWriter(PDDocument doc, FontSet fonts) {
            this.doc = doc;
            this.fonts = fonts;
        }

        void startPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            stream.setStrokingColor(new Color(190, 190, 195));
            stream.setLineWidth(0.5f);
            PDRectangle box = page.getMediaBox();
            stream.addRect(24f, 24f, box.getWidth() - 48f, box.getHeight() - 48f);
            stream.stroke();
            y = box.getHeight() - TOP;
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        void writeTitle(String text) throws IOException {
            writeLine(text, 0f, fonts.bold, TITLE_SIZE, TITLE_LEADING);
        }

        void section(String text) throws IOException {
            ensureSpace(HEADING_LEADING + BODY_LEADING);
            writeLine(text, 0f, fonts.bold, HEADING_SIZE, HEADING_LEADING);
        }

        void writeBody(String text) throws IOException {
            writeBody(text, 0f);
        }

        void writeBody(String text, float indent) throws IOException {
            writeWrapped(text, indent, fonts.regular, BODY_SIZE, BODY_LEADING);
        }

        void writeBodyBold(String text) throws IOException {
            writeWrapped(text, 0f, fonts.bold, BODY_SIZE, BODY_LEADING);
        }

        void bullet(String text, int level) throws IOException {
            float indent = level <= 0 ? 10f : 22f;
            float bulletWidth = textWidth(fonts.regular, BODY_SIZE, fonts.prepare("• "));
            List<String> lines = wrapText(fonts.prepare(text), fonts.regular, BODY_SIZE,
                    availableWidth() - indent - bulletWidth);
            for (int i = 0; i < lines.size(); i++) {
                if (i == 0) {
                    writeLine("• " + lines.get(i), indent, fonts.regular,
                            BODY_SIZE, BODY_LEADING);
                } else {
                    writeLine(lines.get(i), indent + bulletWidth, fonts.regular,
                            BODY_SIZE, BODY_LEADING);
                }
            }
        }

        void rule() throws IOException {
            ensureSpace(8f);
            stream.setStrokingColor(new Color(190, 190, 195));
            stream.setLineWidth(0.5f);
            stream.moveTo(LEFT, y);
            stream.lineTo(page.getMediaBox().getWidth() - RIGHT, y);
            stream.stroke();
            y -= 8f;
        }

        void blank() throws IOException {
            blank(5f);
        }

        void blank(float points) throws IOException {
            ensureSpace(points);
            y -= points;
        }

        private void writeWrapped(String text, float indent, PDFont font,
                                  float size, float leading) throws IOException {
            List<String> lines = wrapText(fonts.prepare(text), font, size,
                    availableWidth() - indent);
            for (String line : lines) {
                writeLine(line, indent, font, size, leading);
            }
        }

        private void writeLine(String text, float indent, PDFont font, float size,
                               float leading) throws IOException {
            ensureSpace(leading);
            stream.beginText();
            stream.setNonStrokingColor(Color.BLACK);
            stream.setFont(font, size);
            stream.newLineAtOffset(LEFT + indent, y);
            stream.showText(fonts.prepare(text));
            stream.endText();
            y -= leading;
        }

        private void ensureSpace(float needed) throws IOException {
            if (stream == null) {
                startPage();
                return;
            }
            if (y - needed < BOTTOM) {
                startPage();
            }
        }

        private float availableWidth() {
            return page.getMediaBox().getWidth() - LEFT - RIGHT;
        }
    }

    private static final class FontSet {
        final PDFont regular;
        final PDFont bold;
        final boolean unicode;

        private FontSet(PDFont regular, PDFont bold, boolean unicode) {
            this.regular = regular;
            this.bold = bold;
            this.unicode = unicode;
        }

        static FontSet load(PDDocument doc) {
            PDFont regular = loadFirstExisting(doc, false);
            PDFont bold = loadFirstExisting(doc, true);
            if (regular != null) {
                return new FontSet(regular, bold == null ? regular : bold, true);
            }
            return new FontSet(
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                    false);
        }

        String prepare(String text) {
            String out = text == null ? "" : text;
            if (unicode) {
                return out;
            }
            return out.replace("—", "-")
                    .replace("–", "-")
                    .replace("≤", "<=")
                    .replace("×", "x")
                    .replace("§", "section ")
                    .replace("•", "-");
        }

        private static PDFont loadFirstExisting(PDDocument doc, boolean bold) {
            String explicit = System.getProperty(bold
                    ? "imagejai.pdf.font.bold"
                    : "imagejai.pdf.font.regular");
            List<String> candidates = new ArrayList<String>();
            if (explicit != null && !explicit.trim().isEmpty()) {
                candidates.add(explicit.trim());
            }
            if (bold) {
                candidates.add("C:/Windows/Fonts/arialbd.ttf");
                candidates.add("C:/Windows/Fonts/segoeuib.ttf");
                candidates.add("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf");
                candidates.add("/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf");
                candidates.add("/System/Library/Fonts/Supplemental/Arial Bold.ttf");
                candidates.add("/Library/Fonts/Arial Bold.ttf");
            } else {
                candidates.add("C:/Windows/Fonts/arial.ttf");
                candidates.add("C:/Windows/Fonts/segoeui.ttf");
                candidates.add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
                candidates.add("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf");
                candidates.add("/System/Library/Fonts/Supplemental/Arial.ttf");
                candidates.add("/Library/Fonts/Arial.ttf");
            }
            for (String candidate : candidates) {
                try {
                    File file = new File(candidate);
                    if (file.isFile()) {
                        return PDType0Font.load(doc, file);
                    }
                } catch (Throwable ignore) {
                }
            }
            String javaHome = System.getProperty("java.home", "");
            String lucida = javaHome + File.separator + "lib" + File.separator
                    + "fonts" + File.separator
                    + (bold ? "LucidaSansDemiBold.ttf" : "LucidaSansRegular.ttf");
            try {
                File file = new File(lucida);
                if (file.isFile()) {
                    return PDType0Font.load(doc, file);
                }
            } catch (Throwable ignore) {
            }
            return null;
        }
    }
}
