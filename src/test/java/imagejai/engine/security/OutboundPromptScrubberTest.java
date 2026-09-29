package imagejai.engine.security;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    public void nonMatchingPromptPassesThroughUnchanged() {
        PathTokenMap map = new PathTokenMap(bytes(6));
        map.tokenForPathString("C:\\study\\subject_017.lif");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        String out = scrubber.scrub("measure the current image");

        assertEquals("measure the current image", out);
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
    public void failedWriteRollbackRetainsBufferedPromptForRetry() {
        PathTokenMap map = new PathTokenMap(bytes(9));
        String raw = "C:\\study\\retry_subject.lif";
        String token = map.tokenForPathString(raw);
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);
        scrubber.filter(("open " + raw).getBytes(StandardCharsets.UTF_8));

        OutboundPromptScrubber.PreparedWrite failed =
                scrubber.prepare("\r".getBytes(StandardCharsets.UTF_8));
        failed.rollback();
        OutboundPromptScrubber.PreparedWrite retry =
                scrubber.prepare("\r".getBytes(StandardCharsets.UTF_8));
        String retried = new String(retry.bytes(), StandardCharsets.UTF_8);
        retry.commit();

        assertTrue(retried.startsWith("\u0015"));
        assertTrue(retried.contains(token));
        assertFalse(retried.contains("retry_subject"));
    }

    @Test
    public void oversizedNoNewlineWriteFailsClosedAndPreservesBufferedState() {
        PathTokenMap map = new PathTokenMap(bytes(10));
        String raw = "C:\\study\\bounded_subject.lif";
        String token = map.tokenForPathString(raw);
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);
        scrubber.filter(("open " + raw).getBytes(StandardCharsets.UTF_8));

        try {
            scrubber.prepare(new byte[OutboundPromptScrubber.MAX_WRITE_BYTES + 1]);
            fail("oversized terminal write should be rejected");
        } catch (OutboundPromptScrubber.PromptLimitException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
        }

        String enter = new String(scrubber.filter(new byte[] {'\r'}),
                StandardCharsets.UTF_8);
        assertTrue(enter.startsWith("\u0015"));
        assertTrue(enter.contains(token));
        assertFalse(enter.contains("bounded_subject"));
    }

    @Test
    public void partialLineCapRollsBackOnlyRejectedChunkAndStillAllowsEditing() {
        PathTokenMap map = new PathTokenMap(bytes(11));
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);
        byte[] full = new byte[OutboundPromptScrubber.MAX_PARTIAL_LINE_CHARS];
        java.util.Arrays.fill(full, (byte) 'a');
        scrubber.filter(full);

        try {
            scrubber.prepare(new byte[] {'b'});
            fail("line beyond the cap should be rejected");
        } catch (OutboundPromptScrubber.PromptLimitException expected) {
            // Expected: the rejected byte is not retained.
        }

        scrubber.filter(new byte[] {'\b'});
        String enter = new String(scrubber.filter(new byte[] {'\r'}),
                StandardCharsets.UTF_8);
        assertEquals("\r", enter);
    }

    @Test
    public void rollbackRestoresMixedBackspaceAppendAndLineClearEdits() {
        PathTokenMap map = new PathTokenMap(bytes(12));
        String raw = "C:\\study\\rollback_subject.lif";
        String token = map.tokenForPathString(raw);
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);
        scrubber.filter(("open " + raw).getBytes(StandardCharsets.UTF_8));

        OutboundPromptScrubber.PreparedWrite failed = scrubber.prepare(
                new byte[] {'\b', 'X', '\r', 'n', 'e', 'w'});
        failed.rollback();
        String retry = new String(scrubber.filter(new byte[] {'\r'}),
                StandardCharsets.UTF_8);

        assertTrue(retry.contains(token));
        assertFalse(retry.contains("rollback_subject"));
        assertFalse(retry.contains("new"));
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
