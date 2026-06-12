package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the Step 15 TCP wiring: hello handshake exposes the
 * five undo / branch command names, capability gating works, and the typed
 * UNDO_DISABLED / UNDO_NOT_FOUND error replies travel through the standard
 * envelope. Per plan: docs/tcp_upgrade/15_undo_stack_api.md.
 */
public class TCPCommandServerUndoTest {

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }

    private static boolean enabledContains(JsonArray arr, String name) {
        for (int i = 0; i < arr.size(); i++) {
            if (name.equals(arr.get(i).getAsString())) return true;
        }
        return false;
    }

    private static UndoFrame syntheticFrame(String callId, String title) {
        byte[] compressed = new byte[64];
        return new UndoFrame(callId, title,
                4, 4, 1, 1, 1, 8,
                compressed, compressed.length,
                Collections.<UndoFrame.RoiSnapshot>emptyList(),
                "",
                System.currentTimeMillis(), false);
    }

    private static TCPCommandServer.AgentCaps capsWithUndo(boolean undoOn) {
        TCPCommandServer.AgentCaps c = new TCPCommandServer.AgentCaps();
        c.undo = undoOn;
        // Tests assert on the structured error code field; enable the
        // structured-errors path that the AgentCaps default leaves off so
        // ErrorReply.buildJsonElement returns a JsonObject rather than a
        // bare message string. Real callers that opt into undo would
        // typically also opt into structured errors — the two flags pair
        // cleanly.
        c.structuredErrors = true;
        return c;
    }

    // -------------------------------------------------------------------
    // Hello handshake
    // -------------------------------------------------------------------

    @Test
    public void helloDefaultsAdvertiseRewindBranchButNotUndo() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");

        // Commands always advertised so clients can feature-detect.
        assertTrue("rewind always advertised",
                enabledContains(enabled, "rewind"));
        assertTrue("branch always advertised",
                enabledContains(enabled, "branch"));
        assertTrue("branch_list always advertised",
                enabledContains(enabled, "branch_list"));
        assertTrue("branch_switch always advertised",
                enabledContains(enabled, "branch_switch"));
        assertTrue("branch_delete always advertised",
                enabledContains(enabled, "branch_delete"));
        // Undo cap not surfaced unless explicitly opted in.
        assertFalse("undo cap NOT advertised by default",
                enabledContains(enabled, "undo"));
    }

    @Test
    public void helloOptInSurfacesUndoCap() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"undo\":true}}");

        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("undo advertised after opt-in",
                enabledContains(enabled, "undo"));
    }

    // -------------------------------------------------------------------
    // Capability gate
    // -------------------------------------------------------------------

    @Test
    public void rewindWithUndoOffReturnsUndoDisabledError() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleRewind(
                parse("{\"command\":\"rewind\",\"n\":1}"), capsWithUndo(false));

        assertTrue(resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.get("success").getAsBoolean());
        JsonObject err = result.getAsJsonObject("error");
        assertEquals("UNDO_DISABLED", err.get("code").getAsString());
    }

    @Test
    public void branchWithUndoOffReturnsUndoDisabledError() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleBranch(
                parse("{\"command\":\"branch\"}"), capsWithUndo(false));
        JsonObject err = resp.getAsJsonObject("result").getAsJsonObject("error");
        assertEquals("UNDO_DISABLED", err.get("code").getAsString());
    }

    @Test
    public void branchListIsAlwaysCallableEvenWithUndoOff() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleBranchList(
                parse("{\"command\":\"branch_list\"}"), capsWithUndo(false));
        assertTrue(resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals("main", result.get("activeBranch").getAsString());
        JsonArray branches = result.getAsJsonArray("branches");
        assertEquals(1, branches.size());
    }

    // -------------------------------------------------------------------
    // Rewind
    // -------------------------------------------------------------------

    @Test
    public void rewindByCallIdConsumesFrameAndReportsRewoundCount() {
        TCPCommandServer server = newServer();
        server.sessionUndo.pushFrame(syntheticFrame("c-1", "img.tif"));
        server.sessionUndo.pushFrame(syntheticFrame("c-2", "img.tif"));

        JsonObject resp = server.handleRewind(
                parse("{\"command\":\"rewind\",\"to_call_id\":\"c-2\"}"),
                capsWithUndo(true));
        JsonObject result = resp.getAsJsonObject("result");
        assertNotNull(result);
        // 1 frame popped (only c-2; c-1 stays). pixelsRestored is false in
        // the test environment because no real ImagePlus is open.
        assertEquals(1, result.get("rewound").getAsInt());
        assertEquals("img.tif", result.get("activeImage").getAsString());
        assertEquals(1, result.get("framesRemaining").getAsInt());
    }

    @Test
    public void rewindMissingCallIdReturnsUndoNotFound() {
        TCPCommandServer server = newServer();
        server.sessionUndo.pushFrame(syntheticFrame("c-1", "img.tif"));
        JsonObject resp = server.handleRewind(
                parse("{\"command\":\"rewind\",\"to_call_id\":\"never\"}"),
                capsWithUndo(true));
        JsonObject err = resp.getAsJsonObject("result").getAsJsonObject("error");
        assertEquals("UNDO_NOT_FOUND", err.get("code").getAsString());
    }

    @Test
    public void rewindOnEmptyStackReturnsUndoNotFound() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleRewind(
                parse("{\"command\":\"rewind\",\"image_title\":\"img.tif\","
                    + "\"n\":1}"),
                capsWithUndo(true));
        JsonObject err = resp.getAsJsonObject("result").getAsJsonObject("error");
        assertEquals("UNDO_NOT_FOUND", err.get("code").getAsString());
    }

    @Test
    public void rewindAttachesDiskSideEffectWarningWhenFrameContainsDiskWrite() {
        TCPCommandServer server = newServer();
        byte[] compressed = new byte[16];
        UndoFrame f = new UndoFrame("c-1", "img.tif",
                4, 4, 1, 1, 1, 8,
                compressed, compressed.length,
                Collections.<UndoFrame.RoiSnapshot>emptyList(),
                "",
                System.currentTimeMillis(),
                /* diskSideEffect = */ true);
        server.sessionUndo.pushFrame(f);

        JsonObject resp = server.handleRewind(
                parse("{\"command\":\"rewind\",\"image_title\":\"img.tif\","
                    + "\"n\":1}"),
                capsWithUndo(true));
        JsonObject result = resp.getAsJsonObject("result");
        assertNotNull("disk warning attached",
                result.get("diskSideEffectWarning"));
    }

    // -------------------------------------------------------------------
    // Branches
    // -------------------------------------------------------------------

    @Test
    public void branchCreatesNewBranchAndSwitchesActive() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleBranch(
                parse("{\"command\":\"branch\",\"from_call_id\":\"c-1\"}"),
                capsWithUndo(true));
        JsonObject result = resp.getAsJsonObject("result");
        String branchId = result.get("branchId").getAsString();
        assertTrue(branchId.startsWith("b-"));
        assertEquals(branchId, result.get("activeBranch").getAsString());
        assertEquals(2, result.get("totalBranches").getAsInt());
    }

    @Test
    public void branchSwitchToUnknownReturnsUndoNotFound() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleBranchSwitch(
                parse("{\"command\":\"branch_switch\",\"branch_id\":\"b-99\"}"),
                capsWithUndo(true));
        JsonObject err = resp.getAsJsonObject("result").getAsJsonObject("error");
        assertEquals("UNDO_NOT_FOUND", err.get("code").getAsString());
    }

    @Test
    public void branchDeleteRefusesMain() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleBranchDelete(
                parse("{\"command\":\"branch_delete\",\"branch_id\":\"main\"}"),
                capsWithUndo(true));
        JsonObject err = resp.getAsJsonObject("result").getAsJsonObject("error");
        assertEquals("UNDO_PROTECTED_BRANCH", err.get("code").getAsString());
    }

    @Test
    public void rewindRefusesToCrossScriptBoundary() {
        TCPCommandServer server = newServer();
        // c-1 macro frame, then a script-boundary, then c-2 macro frame.
        server.sessionUndo.pushFrame(syntheticFrame("c-1", "img.tif"));
        server.sessionUndo.pushBoundary("img.tif", "boundary-1");
        server.sessionUndo.pushFrame(syntheticFrame("c-2", "img.tif"));

        // n=1 lands on c-2, no boundary in range → succeeds.
        JsonObject ok = server.handleRewind(
                parse("{\"command\":\"rewind\",\"image_title\":\"img.tif\","
                    + "\"n\":1}"),
                capsWithUndo(true));
        assertTrue(ok.getAsJsonObject("result").get("rewound").getAsInt() >= 1);

        // After consuming c-2, the next pop would cross the boundary.
        JsonObject blocked = server.handleRewind(
                parse("{\"command\":\"rewind\",\"image_title\":\"img.tif\","
                    + "\"n\":2}"),
                capsWithUndo(true));
        JsonObject err = blocked.getAsJsonObject("result").getAsJsonObject("error");
        assertEquals("UNDO_SCRIPT_BOUNDARY", err.get("code").getAsString());
    }

    @Test
    public void rewindByCallIdRefusesToCrossScriptBoundary() {
        TCPCommandServer server = newServer();
        server.sessionUndo.pushFrame(syntheticFrame("c-1", "img.tif"));
        server.sessionUndo.pushBoundary("img.tif", "boundary-1");
        server.sessionUndo.pushFrame(syntheticFrame("c-2", "img.tif"));

        JsonObject blocked = server.handleRewind(
                parse("{\"command\":\"rewind\",\"image_title\":\"img.tif\","
                    + "\"to_call_id\":\"c-1\"}"),
                capsWithUndo(true));
        JsonObject err = blocked.getAsJsonObject("result").getAsJsonObject("error");
        assertEquals("UNDO_SCRIPT_BOUNDARY", err.get("code").getAsString());
    }

    @Test
    public void rewindWhileMacroInFlightReturnsUndoBusy() {
        TCPCommandServer server = newServer();
        server.sessionUndo.pushFrame(syntheticFrame("c-1", "img.tif"));
        // Reflect into the package-private macroInFlight counter to simulate
        // a concurrent execute_macro / run_script. We could spin up a real
        // thread but the precondition is a single counter read.
        try {
            java.lang.reflect.Field f =
                    TCPCommandServer.class.getDeclaredField("macroInFlight");
            f.setAccessible(true);
            java.util.concurrent.atomic.AtomicInteger ctr =
                    (java.util.concurrent.atomic.AtomicInteger) f.get(server);
            ctr.incrementAndGet();
            try {
                JsonObject resp = server.handleRewind(
                        parse("{\"command\":\"rewind\",\"image_title\":\"img.tif\","
                            + "\"n\":1}"),
                        capsWithUndo(true));
                JsonObject err = resp.getAsJsonObject("result")
                        .getAsJsonObject("error");
                assertEquals("UNDO_BUSY", err.get("code").getAsString());
            } finally {
                ctr.decrementAndGet();
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void branchListEnumeratesBranchesWithFrameCounts() {
        TCPCommandServer server = newServer();
        server.sessionUndo.pushFrame(syntheticFrame("c-1", "img.tif"));
        server.handleBranch(parse("{\"command\":\"branch\"}"), capsWithUndo(true));

        JsonObject resp = server.handleBranchList(
                parse("{\"command\":\"branch_list\"}"), capsWithUndo(true));
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("branches").size());
        // Both branches contain the same frame because branch deep-copies.
        for (int i = 0; i < 2; i++) {
            JsonObject b = result.getAsJsonArray("branches").get(i)
                    .getAsJsonObject();
            assertEquals(1, b.get("frames").getAsInt());
        }
    }
}
