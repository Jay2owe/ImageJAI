package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 04 of {@code docs/safe_mode_v2/} — verify the per-image
 * queue-storm guard. Catches the ghost-dialog scenario where an agent
 * fires a second {@code execute_macro} against an image whose previous
 * macro is parked on a Fiji modal dialog. Different-image macros stay
 * concurrent; {@code interact_dialog} bypasses the guard so the agent
 * can recover. Tests use the package-private {@link
 * TCPCommandServer#inFlightByImage} map and {@link
 * TCPCommandServer#executeMacroForTest} seam to keep the suite headless.
 */
public class QueueStormGuardTest {

    private TCPCommandServer server;

    @Before
    public void setUp() {
        server = new TCPCommandServer(0, null, null, null, null);
    }

    @After
    public void tearDown() {
        TCPCommandServer.executeMacroForTest = null;
    }

    @Test
    public void resolveTargetImageTitleParsesLastSelectImageCall() {
        assertEquals("imageA",
                TCPCommandServer.resolveTargetImageTitle(
                        "selectImage(\"imageA\");"));
        assertEquals("imageB",
                TCPCommandServer.resolveTargetImageTitle(
                        "selectImage(\"imageA\");\nrun(\"Gaussian Blur...\");\n"
                                + "selectImage(\"imageB\");"));
        assertEquals("ch2",
                TCPCommandServer.resolveTargetImageTitle(
                        "selectWindow(\"ch2\");"));
        assertNull(TCPCommandServer.resolveTargetImageTitle("0;"));
        assertNull(TCPCommandServer.resolveTargetImageTitle(""));
        assertNull(TCPCommandServer.resolveTargetImageTitle(null));
        // selectImage(int id) form is heuristic-skipped — string only.
        assertNull(TCPCommandServer.resolveTargetImageTitle("selectImage(42);"));
    }

    /**
     * Macro on imageA paused on a 'Convert Stack?' dialog → second
     * {@code execute_macro} on the same image returns
     * {@code QUEUE_STORM_BLOCKED} carrying the blocking macro id and
     * dialog title. The test seam is NOT set so the early-exit happens
     * before any real Fiji call.
     */
    @Test
    public void sameImagePausedBlocksSecondMacro() {
        // Simulate a previous execute_macro paused on a dialog.
        TCPCommandServer.ActiveMacro paused =
                new TCPCommandServer.ActiveMacro("42", TCPCommandServer.MacroState.PAUSED_ON_DIALOG);
        paused.dialogTitle = "Convert Stack?";
        server.inFlightByImage.put("imageA", paused);

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.structuredErrors = true;
        // queueStormGuard defaults to true on the SafeModeOptions struct.

        JsonObject req = parse(
                "{\"command\":\"execute_macro\","
                        + "\"code\":\"selectImage(\\\"imageA\\\"); run(\\\"Gaussian Blur...\\\");\"}");

        JsonObject resp = server.dispatch(req, caps);
        assertTrue("transport-level ok", resp.get("ok").getAsBoolean());

        JsonObject result = resp.getAsJsonObject("result");
        assertNotNull("result envelope present", result);
        assertFalse("inner success must be false", result.get("success").getAsBoolean());

        JsonObject error = result.getAsJsonObject("error");
        assertNotNull("structured error object", error);
        assertEquals("QUEUE_STORM_BLOCKED", error.get("code").getAsString());
        assertEquals("blocked", error.get("category").getAsString());
        assertFalse("retry_safe must be false", error.get("retry_safe").getAsBoolean());
        assertEquals("42", error.get("blocking_macro_id").getAsString());
        assertEquals("Convert Stack?", error.get("blocking_dialog_title").getAsString());
        assertEquals("imageA", error.get("target_image").getAsString());
        assertTrue("recovery_hint mentions interact_dialog",
                error.get("recovery_hint").getAsString().contains("interact_dialog"));
    }

    /**
     * Different-image macro must fall through the queue-storm check and
     * reach the test seam (which stands in for the real macro path).
     */
    @Test
    public void differentImageRunsConcurrently() {
        TCPCommandServer.ActiveMacro paused =
                new TCPCommandServer.ActiveMacro("99", TCPCommandServer.MacroState.PAUSED_ON_DIALOG);
        paused.dialogTitle = "Convert Stack?";
        server.inFlightByImage.put("imageA", paused);

        final AtomicBoolean stubInvoked = new AtomicBoolean(false);
        TCPCommandServer.executeMacroForTest =
                new java.util.function.BiFunction<JsonObject,
                        TCPCommandServer.AgentCaps, JsonObject>() {
                    @Override
                    public JsonObject apply(JsonObject req,
                                            TCPCommandServer.AgentCaps caps) {
                        stubInvoked.set(true);
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        JsonObject ok = new JsonObject();
                        ok.addProperty("ok", true);
                        ok.add("result", result);
                        return ok;
                    }
                };

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.structuredErrors = true;

        JsonObject req = parse(
                "{\"command\":\"execute_macro\","
                        + "\"code\":\"selectImage(\\\"imageB\\\"); run(\\\"Gaussian Blur...\\\");\"}");

        JsonObject resp = server.dispatch(req, caps);
        assertTrue("transport-level ok", resp.get("ok").getAsBoolean());
        assertTrue("test seam must be reached for different-image macro",
                stubInvoked.get());
        assertTrue("inner success from stub",
                resp.getAsJsonObject("result").get("success").getAsBoolean());
    }

    /**
     * {@code interact_dialog} must NEVER be queue-storm-blocked even
     * when an in-flight macro on the targeted image is paused — that's
     * the recovery path the agent uses to dismiss the dialog. The test
     * verifies the response does not carry a QUEUE_STORM_BLOCKED error
     * code; whether the dispatch itself succeeds or fails for some
     * other reason (no Fiji running, no dialog to find) is irrelevant.
     */
    @Test
    public void interactDialogBypassesQueueStormGuard() {
        TCPCommandServer.ActiveMacro paused =
                new TCPCommandServer.ActiveMacro("7", TCPCommandServer.MacroState.PAUSED_ON_DIALOG);
        paused.dialogTitle = "Convert Stack?";
        server.inFlightByImage.put("imageA", paused);

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.structuredErrors = true;

        JsonObject req = parse(
                "{\"command\":\"interact_dialog\","
                        + "\"action\":\"click_button\","
                        + "\"target\":\"No\"}");

        JsonObject resp = server.dispatch(req, caps);
        // Either ok=true with some result, or ok=false with a "no
        // dialog" error — but in NEITHER case does QUEUE_STORM_BLOCKED
        // appear. interact_dialog never goes through the guard.
        String wire = resp.toString();
        assertFalse("interact_dialog must never carry QUEUE_STORM_BLOCKED: " + wire,
                wire.contains("QUEUE_STORM_BLOCKED"));
    }

    /**
     * Master switch off → no block. The guard inherits {@link
     * TCPCommandServer#DEFAULT_CAPS}-style behaviour even with the
     * inFlight map populated.
     */
    @Test
    public void safeModeOffDisablesGuard() {
        TCPCommandServer.ActiveMacro paused =
                new TCPCommandServer.ActiveMacro("13", TCPCommandServer.MacroState.PAUSED_ON_DIALOG);
        paused.dialogTitle = "Convert Stack?";
        server.inFlightByImage.put("imageA", paused);

        final AtomicBoolean stubInvoked = new AtomicBoolean(false);
        TCPCommandServer.executeMacroForTest =
                new java.util.function.BiFunction<JsonObject,
                        TCPCommandServer.AgentCaps, JsonObject>() {
                    @Override
                    public JsonObject apply(JsonObject req,
                                            TCPCommandServer.AgentCaps caps) {
                        stubInvoked.set(true);
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        JsonObject ok = new JsonObject();
                        ok.addProperty("ok", true);
                        ok.add("result", result);
                        return ok;
                    }
                };

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = false;  // master switch off
        caps.structuredErrors = true;

        JsonObject req = parse(
                "{\"command\":\"execute_macro\","
                        + "\"code\":\"selectImage(\\\"imageA\\\");\"}");

        JsonObject resp = server.dispatch(req, caps);
        assertTrue("safe_mode=false must fall through to legacy path",
                stubInvoked.get());
        assertTrue("transport-level ok", resp.get("ok").getAsBoolean());
    }

    /**
     * Per-guard option off → no block, even with master switch on.
     */
    @Test
    public void queueStormGuardFlagOffDisablesBlock() {
        TCPCommandServer.ActiveMacro paused =
                new TCPCommandServer.ActiveMacro("21", TCPCommandServer.MacroState.PAUSED_ON_DIALOG);
        paused.dialogTitle = "Convert Stack?";
        server.inFlightByImage.put("imageA", paused);

        final AtomicBoolean stubInvoked = new AtomicBoolean(false);
        TCPCommandServer.executeMacroForTest =
                new java.util.function.BiFunction<JsonObject,
                        TCPCommandServer.AgentCaps, JsonObject>() {
                    @Override
                    public JsonObject apply(JsonObject req,
                                            TCPCommandServer.AgentCaps caps) {
                        stubInvoked.set(true);
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        JsonObject ok = new JsonObject();
                        ok.addProperty("ok", true);
                        ok.add("result", result);
                        return ok;
                    }
                };

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.safeModeOptions.queueStormGuard = false;  // per-guard opt-out
        caps.structuredErrors = true;

        JsonObject req = parse(
                "{\"command\":\"execute_macro\","
                        + "\"code\":\"selectImage(\\\"imageA\\\");\"}");

        JsonObject resp = server.dispatch(req, caps);
        assertTrue("queueStormGuard=false must fall through",
                stubInvoked.get());
        assertTrue("transport-level ok", resp.get("ok").getAsBoolean());
    }

    /**
     * In-flight entry in {@link TCPCommandServer.MacroState#RUNNING}
     * (not paused) must NOT block — that's the legacy serialised path
     * still gated by the global MACRO_MUTEX. Only PAUSED_ON_DIALOG
     * triggers the guard.
     */
    @Test
    public void runningStateDoesNotBlock() {
        TCPCommandServer.ActiveMacro running =
                new TCPCommandServer.ActiveMacro("33", TCPCommandServer.MacroState.RUNNING);
        server.inFlightByImage.put("imageA", running);

        final AtomicBoolean stubInvoked = new AtomicBoolean(false);
        TCPCommandServer.executeMacroForTest =
                new java.util.function.BiFunction<JsonObject,
                        TCPCommandServer.AgentCaps, JsonObject>() {
                    @Override
                    public JsonObject apply(JsonObject req,
                                            TCPCommandServer.AgentCaps caps) {
                        stubInvoked.set(true);
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        JsonObject ok = new JsonObject();
                        ok.addProperty("ok", true);
                        ok.add("result", result);
                        return ok;
                    }
                };

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.structuredErrors = true;

        JsonObject req = parse(
                "{\"command\":\"execute_macro\","
                        + "\"code\":\"selectImage(\\\"imageA\\\");\"}");

        JsonObject resp = server.dispatch(req, caps);
        assertTrue("RUNNING state must fall through to MACRO_MUTEX path",
                stubInvoked.get());
        assertTrue("transport-level ok", resp.get("ok").getAsBoolean());
    }

    /**
     * Legacy non-structured-errors caller still gets a sensible block
     * envelope — plain string in the {@code error} field, no JSON
     * object. Mirrors the legacy reply shape used elsewhere.
     */
    @Test
    public void legacyErrorShapeForUnstructuredClients() {
        TCPCommandServer.ActiveMacro paused =
                new TCPCommandServer.ActiveMacro("55", TCPCommandServer.MacroState.PAUSED_ON_DIALOG);
        paused.dialogTitle = "Convert Stack?";
        server.inFlightByImage.put("imageA", paused);

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.structuredErrors = false;  // legacy

        JsonObject req = parse(
                "{\"command\":\"execute_macro\","
                        + "\"code\":\"selectImage(\\\"imageA\\\");\"}");

        JsonObject resp = server.dispatch(req, caps);
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.get("success").getAsBoolean());
        // error must be a string primitive, not an object.
        assertTrue("legacy error must be a string",
                result.get("error").isJsonPrimitive());
        String msg = result.get("error").getAsString();
        assertTrue("error mentions blocking macro id: " + msg,
                msg.contains("#55"));
        assertTrue("error mentions dialog title: " + msg,
                msg.contains("Convert Stack?"));
    }

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }
}
