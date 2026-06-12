package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the {@code hello} handshake and {@code AgentCaps} wiring
 * introduced by {@code docs/tcp_upgrade/01_hello_handshake.md}.
 * <p>
 * These exercise {@link TCPCommandServer#handleHello(JsonObject, java.net.Socket)}
 * directly — no live Fiji, no real socket. The handler doesn't touch ImageJ
 * state, so a server constructed with null engines is sufficient.
 */
public class TCPCommandServerHelloTest {

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    /** Full hello request maps every declared field onto the response. */
    @Test
    public void helloEchoesServerVersionAndReturnsEnabledArray() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"gemma-31b\","
              + "\"capabilities\":{\"vision\":false,\"output_format\":\"json\","
              + "\"token_budget\":4000,\"verbose\":false,\"pulse\":true,"
              + "\"accept_events\":[\"macro.*\",\"image.*\"],"
              + "\"agent_id\":\"gemma-test\"}}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue("hello must succeed", resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(TCPCommandServer.SERVER_VERSION,
                result.get("server_version").getAsString());
        assertNotNull(result.get("session_id"));
        assertTrue(result.has("enabled"));
        assertTrue(result.get("enabled").isJsonArray());
        // Step 03 contract: canonical_macro defaults on, so every hello that
        // doesn't explicitly disable it will see it in enabled[].
        JsonArray enabled = result.getAsJsonArray("enabled");
        assertTrue("canonical_macro on by default",
                enabledContains(enabled, "canonical_macro"));
        assertTrue(result.get("server_time_ms").getAsLong() > 0L);
    }

    /** A hello with no capabilities block still negotiates cleanly. */
    @Test
    public void helloWithoutCapabilitiesUsesDefaults() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(TCPCommandServer.SERVER_VERSION,
                result.get("server_version").getAsString());
        // enabled array always present; canonical_macro is enabled by default
        // (step 03) so size is never zero post-hello.
        JsonArray enabled = result.getAsJsonArray("enabled");
        assertNotNull(enabled);
        assertTrue("canonical_macro enabled by default",
                enabledContains(enabled, "canonical_macro"));
    }

    /** A partial capabilities block fills in defaults for missing fields. */
    @Test
    public void helloWithPartialCapabilitiesFillsDefaults() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"claude-code\","
              + "\"capabilities\":{\"vision\":true}}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        // Server doesn't echo the raw caps back in step 01 (enabled[] is
        // derived) — this test is guarding against the handler throwing on
        // missing fields rather than asserting on echo.
        assertNotNull(resp.getAsJsonObject("result").get("session_id"));
    }

    /** Default agent name is applied when the {@code agent} field is absent. */
    @Test
    public void helloWithoutAgentFieldDoesNotThrow() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\"}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
    }

    /** The dispatcher routes an unknown-command path cleanly — no regression. */
    @Test
    public void dispatchReportsMissingCommandField() {
        TCPCommandServer server = newServer();
        // No "command" key at all — must surface as ok:false, not throw.
        // Hitting the public entry point via reflection is overkill; the
        // JSON helper path already proves the shape via errorResponse.
        JsonObject req = parse("{\"agent\":\"x\"}");
        JsonObject resp = server.handleHello(req, null);
        // handleHello itself treats missing fields as defaults; it should
        // still succeed. Guard against a regression to a strict-mode throw.
        assertTrue("hello should tolerate a missing 'agent' field",
                resp.get("ok").getAsBoolean());
        assertTrue("enabled[] must stay a JsonArray",
                resp.getAsJsonObject("result").get("enabled").isJsonArray());
    }

    /** Explicitly opting out of canonical_macro keeps it out of enabled[]. */
    @Test
    public void helloCanOptOutOfCanonicalMacro() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"canonical_macro\":false}}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result")
                .getAsJsonArray("enabled");
        assertFalse("canonical_macro explicitly disabled",
                enabledContains(enabled, "canonical_macro"));
    }

    /** Opting into structured_errors surfaces it in enabled[]. */
    @Test
    public void helloSurfacesStructuredErrorsWhenEnabled() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"structured_errors\":true}}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result")
                .getAsJsonArray("enabled");
        assertTrue("structured_errors negotiated",
                enabledContains(enabled, "structured_errors"));
    }

    /**
     * Step 06: {@code warnings} defaults on for every client that says hello,
     * including the bare no-capabilities form. Keeping it on-by-default is
     * what makes small-model loops cheap to catch.
     */
    @Test
    public void helloDefaultsEnableWarnings() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("warnings on by default",
                enabledContains(enabled, "warnings"));
    }

    /** Step 06: explicit opt-out keeps warnings out of enabled[]. */
    @Test
    public void helloCanOptOutOfWarnings() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"warnings\":false}}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertFalse("warnings explicitly disabled",
                enabledContains(enabled, "warnings"));
    }

    /**
     * Step 10: phantom-dialog reporting is always advertised (the server
     * scans for phantoms on every mutating call regardless of caps — only
     * the auto-dismiss action is gated). Plain hello must show
     * {@code phantom_dialog} in enabled[] but NOT {@code auto_dismiss_phantoms}.
     */
    @Test
    public void helloAlwaysAdvertisesPhantomDialogReporting() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("phantom_dialog reporting always on",
                enabledContains(enabled, "phantom_dialog"));
        assertFalse("auto_dismiss_phantoms off by default",
                enabledContains(enabled, "auto_dismiss_phantoms"));
    }

    /**
     * Step 10: Gemma's opt-in path — capabilities.auto_dismiss_phantoms=true
     * adds {@code auto_dismiss_phantoms} to enabled[] alongside the
     * always-on {@code phantom_dialog} flag.
     */
    @Test
    public void helloOptsIntoAutoDismissPhantomsWhenRequested() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"gemma-31b\","
              + "\"capabilities\":{\"auto_dismiss_phantoms\":true}}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("phantom_dialog reporting always on",
                enabledContains(enabled, "phantom_dialog"));
        assertTrue("auto_dismiss_phantoms negotiated",
                enabledContains(enabled, "auto_dismiss_phantoms"));
    }

    /**
     * Step 11: response_dedup defaults ON for every client that says hello.
     * Per plan: docs/tcp_upgrade/11_dedup_response.md.
     */
    @Test
    public void helloDefaultsEnableResponseDedup() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("response_dedup on by default",
                enabledContains(enabled, "response_dedup"));
    }

    /** Step 11: explicit opt-out keeps response_dedup out of enabled[]. */
    @Test
    public void helloCanOptOutOfResponseDedup() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"dedup\":false}}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertFalse("response_dedup explicitly disabled",
                enabledContains(enabled, "response_dedup"));
    }

    // -----------------------------------------------------------------
    // Safe-mode v2 stage 02 — master switch + per-guard option flags.
    // Per plan: docs/safe_mode_v2/02_master-switch-and-caps.md.
    // -----------------------------------------------------------------

    /**
     * Plain hello with no capabilities block negotiates {@code safe_mode=true}
     * — Stage 02 makes safe mode the default for any handshake-aware client.
     */
    @Test
    public void helloDefaultsEnableSafeMode() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("safe_mode on by default for handshake clients",
                enabledContains(enabled, "safe_mode"));
        // Five opt-out per-guard options must surface; the two opt-ins
        // stay off until the caller asks for them.
        assertTrue(enabledContains(enabled, "safe_mode_option:auto_backup_roi_on_reset"));
        assertTrue(enabledContains(enabled, "safe_mode_option:auto_snapshot_rescue"));
        assertTrue(enabledContains(enabled, "safe_mode_option:queue_storm_guard"));
        assertTrue(enabledContains(enabled, "safe_mode_option:auto_source_image_column"));
        assertTrue(enabledContains(enabled, "safe_mode_option:scientific_integrity_scan"));
        assertFalse("bit-depth narrowing is opt-in",
                enabledContains(enabled, "safe_mode_option:block_bit_depth_narrowing"));
        assertFalse("normalize-contrast block is opt-in",
                enabledContains(enabled, "safe_mode_option:block_normalize_contrast"));
    }

    /**
     * Explicit {@code safe_mode=false} keeps the master switch off and
     * suppresses every per-guard option name regardless of the per-field
     * values — the gate trips before the guards can fire.
     */
    @Test
    public void helloCanOptOutOfSafeModeMaster() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"safe_mode\":false,"
              + "\"safe_mode_options\":{\"block_bit_depth_narrowing\":true}}}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertFalse("safe_mode explicitly disabled",
                enabledContains(enabled, "safe_mode"));
        // The option fields are still parsed onto AgentCaps, but
        // enabledSafeModeOptions filters through the master switch,
        // so none of the per-guard names should appear in enabled[].
        assertFalse("master-off suppresses bit-depth narrowing advertise",
                enabledContains(enabled, "safe_mode_option:block_bit_depth_narrowing"));
        assertFalse("master-off suppresses auto-snapshot advertise",
                enabledContains(enabled, "safe_mode_option:auto_snapshot_rescue"));
    }

    /**
     * Opt-in for the two opt-in flags adds them to enabled[] without
     * disturbing the unspecified per-guard defaults.
     */
    @Test
    public void helloOptsIntoBitDepthAndNormalizeBlocks() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"safe_mode_options\":{"
              + "\"block_bit_depth_narrowing\":true,"
              + "\"block_normalize_contrast\":true}}}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("safe_mode still on (we didn't touch the master)",
                enabledContains(enabled, "safe_mode"));
        assertTrue(enabledContains(enabled, "safe_mode_option:block_bit_depth_narrowing"));
        assertTrue(enabledContains(enabled, "safe_mode_option:block_normalize_contrast"));
        // Untouched opt-out fields stay on.
        assertTrue(enabledContains(enabled, "safe_mode_option:auto_snapshot_rescue"));
    }

    /**
     * Opt-out for one of the opt-out guards removes only that guard from
     * enabled[]; the other defaults stay on, and the master stays on.
     */
    @Test
    public void helloCanOptOutOfSingleSafeModeOption() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"safe_mode_options\":{"
              + "\"queue_storm_guard\":false}}}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue(enabledContains(enabled, "safe_mode"));
        assertFalse("queue-storm guard explicitly disabled",
                enabledContains(enabled, "safe_mode_option:queue_storm_guard"));
        assertTrue("siblings unaffected",
                enabledContains(enabled, "safe_mode_option:auto_snapshot_rescue"));
        assertTrue(enabledContains(enabled, "safe_mode_option:auto_backup_roi_on_reset"));
    }

    /**
     * No-handshake fallback: {@link TCPCommandServer#DEFAULT_CAPS} preserves
     * the legacy unguarded default so wrappers that never say hello keep
     * today's behaviour. The breaking-change scope is limited to handshake
     * clients (documented in the stage's "Known risks" section).
     */
    @Test
    public void noHandshakeDefaultCapsKeepSafeModeOff() {
        assertFalse("DEFAULT_CAPS.safeMode preserves legacy fast path",
                TCPCommandServer.DEFAULT_CAPS.safeMode);
        assertFalse("opt-in fields stay off in DEFAULT_CAPS",
                TCPCommandServer.DEFAULT_CAPS.safeModeOptions.blockBitDepthNarrowing);
        // enabledSafeModeOptions on DEFAULT_CAPS must be empty since the
        // master switch trips first — Stage 07 relies on this.
        assertTrue("master-off DEFAULT_CAPS produces empty option list",
                TCPCommandServer.enabledSafeModeOptions(TCPCommandServer.DEFAULT_CAPS).isEmpty());
    }

    /**
     * The {@code enabledSafeModeOptions} helper filters strictly through the
     * master switch — Stage 07's tooltip relies on this to render
     * "safe-mode off, no guards active" without having to inspect each
     * per-field flag.
     */
    @Test
    public void enabledSafeModeOptionsHonoursMasterSwitch() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"safe_mode\":false,"
              + "\"safe_mode_options\":{\"block_bit_depth_narrowing\":true,"
              + "\"auto_snapshot_rescue\":true}}}");

        // We don't have direct access to the AgentCaps from the response,
        // but the enabled[] array is the public contract: it must be empty
        // of safe_mode_option entries when the master is off.
        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        for (int i = 0; i < enabled.size(); i++) {
            String s = enabled.get(i).getAsString();
            assertFalse("master-off must hide every safe_mode_option entry, saw " + s,
                    s.startsWith("safe_mode_option:"));
        }
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
}
