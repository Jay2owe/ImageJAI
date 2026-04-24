package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.process.LUT;
import org.junit.Test;

import java.awt.image.IndexColorModel;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the three Gemma-tools read-only TCP commands introduced by
 * {@code docs/tcp_upgrade/07_gemma_tools_server.md}:
 * {@code get_roi_state}, {@code get_display_state}, {@code get_console}.
 * <p>
 * The handlers read Fiji static state directly (RoiManager, WindowManager,
 * ConsoleCapture). In a headless test environment no image is open and no
 * RoiManager exists, so these tests cover the null-state JSON shape plus
 * the shared helpers (LUT heuristic, console ring buffer, install idempotency).
 * Full RoiManager + CompositeImage coverage needs a live Fiji session and
 * lives in integration tests.
 */
public class TCPCommandServerGemmaToolsTest {

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // get_roi_state
    // -----------------------------------------------------------------------

    @Test
    public void getRoiStateReturnsEmptyShapeWhenNoRoiManager() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleGetRoiState(
                new JsonObject(), TCPCommandServer.DEFAULT_CAPS);
        assertTrue("ok when no RoiManager", resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(0, result.get("count").getAsInt());
        assertEquals(-1, result.get("selectedIndex").getAsInt());
        assertEquals(0, result.getAsJsonArray("rois").size());
    }

    // -----------------------------------------------------------------------
    // get_display_state
    // -----------------------------------------------------------------------

    @Test
    public void getDisplayStateReturnsNullImageWhenNoneOpen() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleGetDisplayState(
                new JsonObject(), TCPCommandServer.DEFAULT_CAPS);
        assertTrue("ok when no image open", resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertTrue("activeImage must be JSON null with no image",
                result.get("activeImage").isJsonNull());
        // With no image there is no cursor/channel/frame data — the handler
        // should omit the numeric fields rather than fabricating zeros that
        // could be read as "z=1, c=1 but no image".
        assertFalse(result.has("c"));
        assertFalse(result.has("z"));
        assertFalse(result.has("t"));
    }

    // -----------------------------------------------------------------------
    // get_console
    // -----------------------------------------------------------------------

    @Test
    public void getConsoleReturnsTailWhenCaptureInstalled() {
        ConsoleCapture.resetForTests();
        try {
            ConsoleCapture.appendStdoutForTests("hello world\n");
            ConsoleCapture.appendStderrForTests(
                    "java.lang.NullPointerException\n  at Foo.bar(Foo.java:42)\n");

            TCPCommandServer server = newServer();
            JsonObject req = new JsonObject();
            req.addProperty("tail", 2000);

            JsonObject resp = server.handleGetConsole(req, TCPCommandServer.DEFAULT_CAPS);
            assertTrue(resp.get("ok").getAsBoolean());
            JsonObject result = resp.getAsJsonObject("result");
            assertTrue("stdout contains injected line",
                    result.get("stdout").getAsString().contains("hello world"));
            assertTrue("stderr contains injected stack trace",
                    result.get("stderr").getAsString().contains("NullPointerException"));
            assertTrue("combined includes both streams when both present",
                    result.get("combined").getAsString().contains("hello world")
                    && result.get("combined").getAsString().contains("NullPointerException"));
            assertFalse("not truncated — tail exceeds buffered size",
                    result.get("truncated").getAsBoolean());
            assertTrue(result.get("installed").getAsBoolean());
        } finally {
            ConsoleCapture.uninstall();
        }
    }

    @Test
    public void getConsoleUsesDefaultTailWhenOmitted() {
        ConsoleCapture.resetForTests();
        try {
            ConsoleCapture.appendStdoutForTests("a-line\n");
            TCPCommandServer server = newServer();
            JsonObject resp = server.handleGetConsole(
                    new JsonObject(), TCPCommandServer.DEFAULT_CAPS);
            assertTrue(resp.get("ok").getAsBoolean());
            JsonObject result = resp.getAsJsonObject("result");
            assertTrue(result.get("stdout").getAsString().contains("a-line"));
        } finally {
            ConsoleCapture.uninstall();
        }
    }

    @Test
    public void getConsoleMarksTruncatedWhenBufferExceedsTail() {
        ConsoleCapture.resetForTests();
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) sb.append("0123456789");
            ConsoleCapture.appendStdoutForTests(sb.toString());
            TCPCommandServer server = newServer();
            JsonObject req = new JsonObject();
            req.addProperty("tail", 50);
            JsonObject resp = server.handleGetConsole(req, TCPCommandServer.DEFAULT_CAPS);
            JsonObject result = resp.getAsJsonObject("result");
            assertTrue("50-byte tail of a 1000-byte buffer reports truncated",
                    result.get("truncated").getAsBoolean());
            assertEquals(50, result.get("stdout").getAsString().length());
        } finally {
            ConsoleCapture.uninstall();
        }
    }

    @Test
    public void getConsoleWithoutCaptureReturnsEmptyStrings() {
        // Make sure capture is NOT installed for this test.
        ConsoleCapture.uninstall();
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleGetConsole(
                new JsonObject(), TCPCommandServer.DEFAULT_CAPS);
        assertTrue(resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals("", result.get("stdout").getAsString());
        assertEquals("", result.get("stderr").getAsString());
        assertFalse(result.get("installed").getAsBoolean());
    }

    // -----------------------------------------------------------------------
    // hello advertises the new commands
    // -----------------------------------------------------------------------

    @Test
    public void helloAdvertisesGemmaToolsCommandsUnconditionally() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");
        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("get_roi_state always enabled",
                enabledContains(enabled, "get_roi_state"));
        assertTrue("get_display_state always enabled",
                enabledContains(enabled, "get_display_state"));
        assertTrue("get_console always enabled",
                enabledContains(enabled, "get_console"));
    }

    @Test
    public void helloAdvertisesGemmaToolsEvenWhenOtherCapsDisabled() {
        // These three are meant to be always-on — not behind any capability
        // flag. A client that opts out of every named-feature should still
        // see them in enabled[].
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"structured_errors\":false,"
              + "\"canonical_macro\":false,\"fuzzy_match\":false,"
              + "\"state_delta\":false,\"pulse\":false,\"warnings\":false}}");
        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue(enabledContains(enabled, "get_roi_state"));
        assertTrue(enabledContains(enabled, "get_display_state"));
        assertTrue(enabledContains(enabled, "get_console"));
    }

    // -----------------------------------------------------------------------
    // ConsoleCapture install / uninstall lifecycle
    // -----------------------------------------------------------------------

    @Test
    public void consoleCaptureInstallIsIdempotent() {
        ConsoleCapture.uninstall();
        PrintStream beforeAll = System.out;
        try {
            ConsoleCapture.install();
            PrintStream firstWrap = System.out;
            assertNotSame("install replaced System.out", beforeAll, firstWrap);

            ConsoleCapture.install(); // second call is a no-op
            assertSame("second install does not re-wrap", firstWrap, System.out);
        } finally {
            ConsoleCapture.uninstall();
        }
        assertSame("uninstall restored the pre-install stream",
                beforeAll, System.out);
    }

    @Test
    public void consoleCaptureTeesSystemOutToRingBuffer() {
        ConsoleCapture.uninstall();
        PrintStream savedOut = System.out;
        try {
            ConsoleCapture.install();
            // Write via System.out — the tee should forward to the original
            // stream AND append to the ring buffer. We just verify the
            // buffer side here; the forwarding side is "didn't break stdout
            // in the test console".
            System.out.println("MARKER_FROM_TEST_1234");
            String tail = ConsoleCapture.tailStdout(-1);
            assertTrue("System.out routed into ring buffer",
                    tail.contains("MARKER_FROM_TEST_1234"));
        } finally {
            ConsoleCapture.uninstall();
        }
        assertSame(savedOut, System.out);
    }

    // -----------------------------------------------------------------------
    // LUT heuristic
    // -----------------------------------------------------------------------

    @Test
    public void lutHeuristicIdentifiesGraysAndPureChannels() {
        assertEquals("Grays", TCPCommandServer.lutNameOrHeuristic(grayRampLut()));
        assertEquals("Red", TCPCommandServer.lutNameOrHeuristic(
                pureChannelLut(true, false, false)));
        assertEquals("Green", TCPCommandServer.lutNameOrHeuristic(
                pureChannelLut(false, true, false)));
        assertEquals("Blue", TCPCommandServer.lutNameOrHeuristic(
                pureChannelLut(false, false, true)));
    }

    @Test
    public void lutHeuristicLabelsUnknownPaletteAsCustom() {
        // Build a LUT that doesn't match any known palette: oscillating.
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        for (int i = 0; i < 256; i++) {
            r[i] = (byte) ((i * 7) & 0xff);
            g[i] = (byte) ((i * 13) & 0xff);
            b[i] = (byte) ((i * 29) & 0xff);
        }
        LUT lut = new LUT(new IndexColorModel(8, 256, r, g, b), 0, 255);
        // Strip any name metadata that newer IJ versions might attach.
        assertEquals("custom", TCPCommandServer.lutNameOrHeuristic(lut));
    }

    @Test
    public void lutHeuristicHandlesNullSafely() {
        assertEquals("custom", TCPCommandServer.lutNameOrHeuristic(null));
    }

    // -----------------------------------------------------------------------
    // RingBuffer — exercise the wraparound path directly
    // -----------------------------------------------------------------------

    @Test
    public void ringBufferReadTailWrapsAroundCorrectly() {
        ConsoleCapture.RingBuffer buf = new ConsoleCapture.RingBuffer(8);
        // Write 12 bytes into an 8-byte ring — oldest 4 must be overwritten.
        byte[] payload = "abcdefghijkl".getBytes();
        buf.append(payload);
        assertEquals(12L, buf.totalWritten());
        assertEquals(8L, buf.size());
        assertEquals("efghijkl", buf.readTail(-1));
        assertEquals("ijkl", buf.readTail(4));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }

    private static boolean enabledContains(JsonArray arr, String name) {
        for (int i = 0; i < arr.size(); i++) {
            if (name.equals(arr.get(i).getAsString())) return true;
        }
        return false;
    }

    private static LUT grayRampLut() {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        for (int i = 0; i < 256; i++) {
            r[i] = (byte) i;
            g[i] = (byte) i;
            b[i] = (byte) i;
        }
        return new LUT(new IndexColorModel(8, 256, r, g, b), 0, 255);
    }

    private static LUT pureChannelLut(boolean red, boolean green, boolean blue) {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        for (int i = 0; i < 256; i++) {
            if (red) r[i] = (byte) i;
            if (green) g[i] = (byte) i;
            if (blue) b[i] = (byte) i;
        }
        return new LUT(new IndexColorModel(8, 256, r, g, b), 0, 255);
    }
}
