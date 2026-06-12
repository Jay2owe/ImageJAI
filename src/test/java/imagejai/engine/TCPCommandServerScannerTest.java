package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import imagejai.engine.safeMode.DestructiveScanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 05 of {@code docs/safe_mode_v2/} — end-to-end verification that
 * the scanner short-circuits {@code execute_macro} with a structured
 * {@code DESTRUCTIVE_OP_BLOCKED} reply when a Stage-05 rule fires. The
 * test relies on the regex-only rules whose preconditions can be set
 * via {@link TCPCommandServer.AgentCaps} alone (the opt-in normalize
 * block), so no live image is needed.
 *
 * <p>Pairs with {@link DestructiveScannerTest} (pure scanner unit
 * tests) — that suite covers the rule logic, this one covers the wiring.
 */
public class TCPCommandServerScannerTest {

    private TCPCommandServer server;

    @Before
    public void setUp() {
        server = new TCPCommandServer(0, null, null, null, null);
    }

    @After
    public void tearDown() {
        TCPCommandServer.executeMacroForTest = null;
    }

    /**
     * Safe mode + scientific-integrity scan + opt-in normalize block ON
     * → an Enhance Contrast normalize macro short-circuits with a
     * DESTRUCTIVE_OP_BLOCKED structured-error reply.
     */
    @Test
    public void enhanceContrastNormalizeBlockedWhenSafeModeOn() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.structuredErrors = true;
        // scientificIntegrityScan is true by default on SafeModeOptions.
        caps.safeModeOptions.blockNormalizeContrast = true;

        JsonObject req = parse(
                "{\"command\": \"execute_macro\","
                        + " \"code\": \"run(\\\"Enhance Contrast\\\", \\\"saturated=0.35 normalize\\\");\"}");
        JsonObject reply = server.dispatch(req, caps);
        assertNotNull(reply);
        assertTrue("dispatch returned ok envelope", reply.get("ok").getAsBoolean());

        JsonObject result = reply.getAsJsonObject("result");
        assertFalse("inner success false", result.get("success").getAsBoolean());
        JsonObject err = result.getAsJsonObject("error");
        assertEquals(ErrorReply.CODE_DESTRUCTIVE_OP_BLOCKED, err.get("code").getAsString());
        assertEquals(ErrorReply.CAT_BLOCKED, err.get("category").getAsString());
        assertEquals(1, err.getAsJsonArray("operations").size());
        JsonObject op = err.getAsJsonArray("operations").get(0).getAsJsonObject();
        assertEquals(DestructiveScanner.RULE_NORMALIZE_CONTRAST, op.get("rule_id").getAsString());
    }

    /**
     * Same macro, master safe-mode OFF → fast path, reach the test seam
     * (which we leave unset, so the regular handler runs). The point is
     * the scanner does NOT block, which would surface as a
     * DESTRUCTIVE_OP_BLOCKED reply. Since the request reaches the live
     * macro path, the reply will not carry that error code.
     */
    @Test
    public void scannerDoesNotFireWhenSafeModeOff() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = false;  // master off
        caps.structuredErrors = true;
        caps.safeModeOptions.blockNormalizeContrast = true;

        // Test seam: short-circuit handleExecuteMacro so we don't drag in
        // a live Fiji to verify the negative case. If the scanner ran it
        // would short-circuit BEFORE the seam, so reaching the seam is
        // the assertion.
        final boolean[] seamHit = { false };
        TCPCommandServer.executeMacroForTest = (req, c) -> {
            seamHit[0] = true;
            JsonObject r = new JsonObject();
            r.addProperty("success", true);
            return wrapOk(r);
        };

        JsonObject req = parse(
                "{\"command\": \"execute_macro\","
                        + " \"code\": \"run(\\\"Enhance Contrast\\\", \\\"saturated=0.35 normalize\\\");\"}");
        JsonObject reply = server.dispatch(req, caps);
        assertNotNull(reply);
        assertTrue("test seam was hit (scanner did not block)", seamHit[0]);
    }

    /**
     * Safe mode on, opt-in flag OFF → an Enhance Contrast normalize
     * macro is allowed through (the rule is opt-in by design). Mirrors
     * the negative case in {@link DestructiveScannerTest}.
     */
    @Test
    public void enhanceContrastNormalizeAllowedWhenOptionOff() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        caps.structuredErrors = true;
        caps.safeModeOptions.blockNormalizeContrast = false;  // opt-in OFF

        final boolean[] seamHit = { false };
        TCPCommandServer.executeMacroForTest = (req, c) -> {
            seamHit[0] = true;
            JsonObject r = new JsonObject();
            r.addProperty("success", true);
            return wrapOk(r);
        };

        JsonObject req = parse(
                "{\"command\": \"execute_macro\","
                        + " \"code\": \"run(\\\"Enhance Contrast\\\", \\\"saturated=0.35 normalize\\\");\"}");
        JsonObject reply = server.dispatch(req, caps);
        assertTrue("test seam was hit (scanner did not block)", seamHit[0]);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject wrapOk(JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.add("result", result);
        return r;
    }
}
