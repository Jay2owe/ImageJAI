package imagejai.engine.security;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OutboundPromptScrubberTest {
    @Test
    public void scrubReplacesKnownSensitiveStrings() {
        PathTokenMap map = new PathTokenMap(bytes(1));
        String token = map.tokenForPathString("C:\\study\\subject_017.lif");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        String out = scrubber.scrub("please open C:\\study\\subject_017.lif");

        assertFalse(out.contains("subject_017"));
        assertTrue(out.contains(token));
    }

    @Test
    public void scrubDoesNotRescanInsertedTokens() {
        PathTokenMap map = new PathTokenMap(bytes(4));
        String longToken = map.tokenForSensitiveText("alpha beta", "alpha");
        String shortToken = map.tokenForSensitiveText("alpha", "label");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        String out = scrubber.scrub("alpha beta alpha");

        assertEquals(longToken + " " + shortToken, out);
    }

    @Test
    public void enterRewritesBufferedLineBeforeSending() {
        PathTokenMap map = new PathTokenMap(bytes(2));
        String token = map.tokenForPathString("C:\\study\\subject_017.lif");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        scrubber.filter("open C:\\study\\subject_017.lif".getBytes(StandardCharsets.UTF_8));
        String enter = new String(scrubber.filter("\r".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        assertTrue("line kill should precede the replacement", enter.startsWith("\u0015"));
        assertFalse(enter.contains("subject_017"));
        assertTrue(enter.contains(token));
    }

    @Test
    public void rawOverrideAllowsOneEnterWithoutRewrite() {
        PathTokenMap map = new PathTokenMap(bytes(3));
        map.tokenForPathString("C:\\study\\subject_017.lif");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        scrubber.filter("open C:\\study\\subject_017.lif".getBytes(StandardCharsets.UTF_8));
        scrubber.sendNextEnterRaw();
        String enter = new String(scrubber.filter("\r".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        assertEquals("\r", enter);
    }

    @Test
    public void promptReplacementEmitsRedactedAuditRowOnly() throws Exception {
        Path csv = Files.createTempDirectory("imagejai-prompt-audit")
                .resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);
        PathTokenMap map = new PathTokenMap(bytes(5));
        String raw = "C:\\study\\subject_017.lif";
        String token = map.tokenForPathString(raw);
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null, log);

        scrubber.filter(("open " + raw).getBytes(StandardCharsets.UTF_8));
        scrubber.filter("\r".getBytes(StandardCharsets.UTF_8));
        log.flushForTest();

        List<AuditRow> rows = log.recent(1);
        assertEquals(1, rows.size());
        AuditRow row = rows.get(0);
        assertEquals("prompt.outbound", row.command());
        assertTrue(row.redactionApplied());
        assertEquals("prompt", row.fieldsRedacted().get(0));
        assertTrue(row.redactedPayloadJson().contains(token));
        assertFalse(row.redactedPayloadJson().contains("subject_017"));
        assertFalse(row.notes().contains("subject_017"));

        log.shutdownAndAwait(100);
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }
}
