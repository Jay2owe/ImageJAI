package imagejai.engine.security;

import imagejai.config.PrivacyPosture;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DataHandlingStatementGeneratorTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void generateCreatesDocumentedPdfWithRequiredGovernanceText()
            throws Exception {
        Path project = tmp.newFolder("MOAB2_AF488").toPath();
        Path csv = project.resolve("AI_Exports").resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);
        log.append(new AuditRow(Instant.parse("2026-05-22T12:00:00Z"),
                "s1", "get_state", PrivacyPosture.PSEUDONYMISED,
                "anthropic.claude-code", "", 120, 40, "",
                true, Collections.singletonList("path"), ""));
        log.append(new AuditRow(Instant.parse("2026-05-22T12:00:01Z"),
                "s1", "visual.granted", PrivacyPosture.PSEUDONYMISED,
                "", "", 0, 0, "", false,
                Collections.<String>emptyList(), "reason='inspect focus'"));
        log.append(new AuditRow(Instant.parse("2026-05-22T12:00:02Z"),
                "s1", "posture.downshift", PrivacyPosture.PSEUDONYMISED,
                "", "", 0, 0, "", false,
                Collections.<String>emptyList(), "from=Standard to=Pseudonymised"));
        log.flushForTest();
        log.shutdownAndAwait(100);

        Clock clock = Clock.fixed(Instant.parse("2026-05-22T08:30:00Z"),
                ZoneOffset.UTC);
        DataHandlingStatementGenerator generator =
                new DataHandlingStatementGenerator(project,
                        PrivacyPosture.PSEUDONYMISED, clock, "0.X.Y");

        Path pdf = generator.generate();

        assertEquals(project.resolve("AI_Exports")
                .resolve("DataHandlingStatement_MOAB2_AF488_20260522.pdf"), pdf);
        assertTrue(Files.isRegularFile(pdf));

        String text;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertTrue(doc.getNumberOfPages() == 1 || doc.getNumberOfPages() == 2);
            text = new PDFTextStripper().getText(doc);
        }
        String normalised = text.replaceAll("\\s+", " ");

        assertContains(normalised, "Pseudonymisation");
        assertContains(normalised, "UK GDPR Art. 4(5)");
        assertContains(normalised, "On-premises");
        assertContains(normalised, "imagejai_audit.csv");
        assertContains(normalised, "Anthropic");
        assertContains(normalised, "OpenAI");
        assertContains(normalised, "Google Gemini");
        assertContains(normalised, "Ollama");
        assertContains(normalised, "series-within-file");
        assertContains(normalised, "visual override");
        assertContains(normalised,
                "PROMPTS AND OUTPUTS ARE USED FOR TRAINING. NOT RECOMMENDED FOR RESEARCH DATA.");
        assertContains(normalised, "Rows in this project to date: 3");
        assertContains(normalised, "Pseudonymised calls: 1");
        assertContains(normalised, "Visual override grants: 1");
        assertContains(normalised, "Posture downshifts: 1");
    }

    @Test
    public void freshProjectShowsZeroAuditRows() throws Exception {
        Path project = tmp.newFolder("fresh_project").toPath();
        Clock clock = Clock.fixed(Instant.parse("2026-05-22T08:30:00Z"),
                ZoneOffset.UTC);
        DataHandlingStatementGenerator generator =
                new DataHandlingStatementGenerator(project,
                        PrivacyPosture.ON_PREMISES, clock, "0.X.Y");

        Path pdf = generator.generate();

        String text;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertTrue(doc.getNumberOfPages() == 1 || doc.getNumberOfPages() == 2);
            text = new PDFTextStripper().getText(doc);
        }
        assertContains(text.replaceAll("\\s+", " "),
                "Rows in this project to date: 0");
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue("Missing expected PDF text: " + needle, haystack.contains(needle));
    }
}
