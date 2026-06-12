package imagejai.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ErrorReply} introduced by
 * {@code docs/tcp_upgrade/02_structured_errors.md}. Exercises both the legacy
 * string shape (caps == null or caps.structuredErrors == false) and the
 * negotiated object shape, plus the {@code classifyMacroError} rule set.
 * Pure Java — no live Fiji, no socket.
 */
public class ErrorReplyTest {

    /** Caps with structured errors OFF — every reply is a plain string. */
    @Test
    public void legacyCapsReturnsJsonPrimitiveString() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = false;

        JsonElement out = new ErrorReply()
                .code(ErrorReply.CODE_IMAGE_NOT_OPEN)
                .category(ErrorReply.CAT_STATE)
                .retrySafe(true)
                .message("No image open")
                .recoveryHint("Open an image first, then retry.")
                .buildJsonElement(caps);

        assertTrue("legacy shape is a JsonPrimitive string",
                out.isJsonPrimitive() && out.getAsJsonPrimitive().isString());
        assertEquals("No image open", out.getAsString());
    }

    /** Null caps also yield legacy string — defensive default. */
    @Test
    public void nullCapsReturnsJsonPrimitiveString() {
        JsonElement out = new ErrorReply()
                .message("legacy path")
                .buildJsonElement(null);
        assertTrue(out.isJsonPrimitive());
        assertEquals("legacy path", out.getAsString());
    }

    /** Caps with structured errors ON — full typed object with all fields. */
    @Test
    public void structuredCapsReturnsJsonObjectWithAllFields() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;

        JsonObject suggested = new JsonObject();
        suggested.addProperty("command", "probe_command");
        JsonObject args = new JsonObject();
        args.addProperty("plugin", "Gaussian Blur...");
        suggested.add("args", args);

        JsonObject sideEffects = new JsonObject();
        sideEffects.addProperty("resultsChanged", true);

        JsonElement out = new ErrorReply()
                .code(ErrorReply.CODE_MACRO_BLOCKED_ON_DIALOG)
                .category(ErrorReply.CAT_BLOCKED)
                .retrySafe(false)
                .message("Macro paused on modal dialog 'Gaussian Blur...'")
                .recoveryHint("Probe the plugin and pass explicit args.")
                .addSuggested(suggested)
                .sideEffects(sideEffects)
                .buildJsonElement(caps);

        assertTrue("structured shape is a JsonObject", out.isJsonObject());
        JsonObject o = out.getAsJsonObject();
        assertEquals("MACRO_BLOCKED_ON_DIALOG", o.get("code").getAsString());
        assertEquals("blocked", o.get("category").getAsString());
        assertFalse(o.get("retry_safe").getAsBoolean());
        assertEquals("Macro paused on modal dialog 'Gaussian Blur...'",
                o.get("message").getAsString());
        assertEquals("Probe the plugin and pass explicit args.",
                o.get("recovery_hint").getAsString());
        assertNotNull(o.get("suggested"));
        assertEquals(1, o.getAsJsonArray("suggested").size());
        assertNotNull(o.get("sideEffects"));
        assertTrue(o.getAsJsonObject("sideEffects").get("resultsChanged").getAsBoolean());
    }

    /** Optional fields (recovery_hint, suggested, sideEffects) are omitted when absent. */
    @Test
    public void structuredCapsOmitsEmptyOptionalFields() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;

        JsonObject o = new ErrorReply()
                .code(ErrorReply.CODE_IMAGE_NOT_OPEN)
                .category(ErrorReply.CAT_STATE)
                .retrySafe(true)
                .message("No image open")
                .buildJsonElement(caps)
                .getAsJsonObject();

        assertFalse("recovery_hint omitted when not set", o.has("recovery_hint"));
        assertFalse("suggested omitted when empty", o.has("suggested"));
        assertFalse("sideEffects omitted when null", o.has("sideEffects"));
    }

    // ---- classifyMacroError rule-set tests ----

    @Test
    public void classifyMacroError_dialogPause_isBlockedAndNotRetrySafe() {
        ErrorReply r = ErrorReply.classifyMacroError(
                "Macro paused on modal dialog 'Analyze Particles...'", false);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = r.buildJsonElement(caps).getAsJsonObject();
        assertEquals("MACRO_BLOCKED_ON_DIALOG", o.get("code").getAsString());
        assertEquals("blocked", o.get("category").getAsString());
        assertFalse(o.get("retry_safe").getAsBoolean());
        assertTrue("blocked-on-dialog emits a recovery_hint",
                o.has("recovery_hint"));
    }

    @Test
    public void classifyMacroError_timeout_isRuntimeAndRetrySafe() {
        ErrorReply r = ErrorReply.classifyMacroError(
                "Macro execution timed out after 30000ms", false);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = r.buildJsonElement(caps).getAsJsonObject();
        assertEquals("MACRO_RUNTIME_ERROR", o.get("code").getAsString());
        assertEquals("runtime", o.get("category").getAsString());
        assertTrue("timeout permits retry", o.get("retry_safe").getAsBoolean());
    }

    @Test
    public void classifyMacroError_outOfMemory_isRuntimeAndRetrySafe() {
        ErrorReply r = ErrorReply.classifyMacroError(
                "java.lang.OutOfMemoryError: Java heap space", false);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = r.buildJsonElement(caps).getAsJsonObject();
        assertEquals("MACRO_RUNTIME_ERROR", o.get("code").getAsString());
        assertTrue("OOM retry is safe once memory freed",
                o.get("retry_safe").getAsBoolean());
    }

    @Test
    public void classifyMacroError_unrecognizedCommand_isNotFound() {
        ErrorReply r = ErrorReply.classifyMacroError(
                "Unrecognized command: \"Laplacian\"", false);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = r.buildJsonElement(caps).getAsJsonObject();
        assertEquals("PLUGIN_NOT_FOUND", o.get("code").getAsString());
        assertEquals("not_found", o.get("category").getAsString());
        assertFalse("missing plugin — fix name before retry",
                o.get("retry_safe").getAsBoolean());
    }

    @Test
    public void classifyMacroError_syntaxError_isCompile() {
        ErrorReply r = ErrorReply.classifyMacroError(
                "Syntax error: unexpected '<<' in line 7", false);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = r.buildJsonElement(caps).getAsJsonObject();
        assertEquals("MACRO_COMPILE_ERROR", o.get("code").getAsString());
        assertEquals("compile", o.get("category").getAsString());
        assertFalse(o.get("retry_safe").getAsBoolean());
    }

    @Test
    public void classifyMacroError_unknownRuntime_defaultsToNotRetrySafe() {
        ErrorReply r = ErrorReply.classifyMacroError(
                "ArrayIndexOutOfBoundsException at line 42", false);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = r.buildJsonElement(caps).getAsJsonObject();
        assertEquals("MACRO_RUNTIME_ERROR", o.get("code").getAsString());
        assertEquals("runtime", o.get("category").getAsString());
        assertFalse("unknown runtime errors are conservative",
                o.get("retry_safe").getAsBoolean());
    }

    /** Side-effects-landed variant produces a distinct recovery hint. */
    @Test
    public void classifyMacroError_dialogPauseWithSideEffects_hasDistinctHint() {
        ErrorReply withoutSideEffects = ErrorReply.classifyMacroError(
                "Macro paused on modal dialog 'X'", false);
        ErrorReply withSideEffects = ErrorReply.classifyMacroError(
                "Macro paused on modal dialog 'X'", true);

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;

        String h1 = withoutSideEffects.buildJsonElement(caps)
                .getAsJsonObject().get("recovery_hint").getAsString();
        String h2 = withSideEffects.buildJsonElement(caps)
                .getAsJsonObject().get("recovery_hint").getAsString();

        assertFalse("distinct hints for distinct contexts", h1.equals(h2));
        assertTrue("side-effects hint warns against retry",
                h2.toLowerCase().contains("do not retry")
                || h2.toLowerCase().contains("do not resend"));
    }

    /** Legacy-string output preserves the same message across classify rules. */
    @Test
    public void legacyOutput_preservesOriginalMessageForAllClassifications() {
        String raw = "Macro paused on modal dialog 'Foo'";
        JsonElement legacy = ErrorReply.classifyMacroError(raw, false)
                .buildJsonElement(null);
        assertTrue(legacy.isJsonPrimitive());
        assertEquals(raw, legacy.getAsString());
    }

    /** classifyMacroError tolerates null input without NPE. */
    @Test
    public void classifyMacroError_nullInput_doesNotThrow() {
        ErrorReply r = ErrorReply.classifyMacroError(null, false);
        assertNotNull(r);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = r.buildJsonElement(caps).getAsJsonObject();
        assertNotNull("code is always set even on null input", o.get("code"));
        assertNotNull("message is always a string", o.get("message"));
    }
}
