package imagejai.engine.security;

import imagejai.config.PrivacyPosture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DataHandlingStatementGeneratorTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void generateCreatesDocumentedHtmlWithRequiredGovernanceText()
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

        Path html = generator.generate();

        assertEquals(project.resolve("AI_Exports")
                .resolve("DataHandlingStatement_MOAB2_AF488_20260522.html"), html);
        String text = text(html);

        assertContains(text, "Pseudonymisation");
        assertContains(text, "UK GDPR Art. 4(5)");
        assertContains(text, "On-premises");
        assertContains(text, "imagejai_audit.csv");
        assertContains(text, "Anthropic");
        assertContains(text, "OpenAI");
        assertContains(text, "Google Gemini");
        assertContains(text, "Ollama");
        assertContains(text, "series-within-file");
        assertContains(text, "visual override");
        assertContains(text,
                "PROMPTS AND OUTPUTS ARE USED FOR TRAINING. NOT RECOMMENDED FOR RESEARCH DATA.");
        assertContains(text, "Rows in this project to date: 3");
        assertContains(text, "Pseudonymised calls: 1");
        assertContains(text, "Visual override grants: 1");
        assertContains(text, "Posture downshifts: 1");
        assertContains(text, "ImageJAI version: 0.X.Y");
    }

    @Test
    public void freshProjectShowsZeroAuditRows() throws Exception {
        Path project = tmp.newFolder("fresh_project").toPath();
        Clock clock = Clock.fixed(Instant.parse("2026-05-22T08:30:00Z"),
                ZoneOffset.UTC);
        Path html = new DataHandlingStatementGenerator(project,
                PrivacyPosture.ON_PREMISES, clock, "0.X.Y").generate();

        assertContains(text(html), "Rows in this project to date: 0");
    }

    @Test
    public void projectTextIsEscapedSoAFolderNameCannotInjectMarkup() throws Exception {
        // Windows forbids < and > in folder names; & and ' are the live risks.
        Path project = tmp.newFolder("Smith & Jones' lab").toPath();
        Path html = new DataHandlingStatementGenerator(project,
                PrivacyPosture.STANDARD, Clock.systemUTC(), "0.X.Y").generate();

        String markup = new String(Files.readAllBytes(html), StandardCharsets.UTF_8);
        assertFalse(markup.contains("Smith & Jones' lab"));
        assertTrue(markup.contains("Smith &amp; Jones&#39; lab"));
        assertEquals("&lt;b&gt;", DataHandlingStatementGenerator.escape("<b>"));
    }

    /** Visible text: tags dropped, entities decoded, whitespace collapsed. */
    private static String text(Path html) throws Exception {
        assertTrue(Files.isRegularFile(html));
        String markup = new String(Files.readAllBytes(html), StandardCharsets.UTF_8);
        assertTrue(markup.startsWith("<!DOCTYPE html>"));
        String body = markup.replaceAll("(?s)<style>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return body.replaceAll("\\s+", " ");
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue("Missing expected statement text: " + needle, haystack.contains(needle));
    }
}
