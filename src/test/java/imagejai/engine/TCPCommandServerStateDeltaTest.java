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
 * Unit tests for step 05 — {@code stateDelta} grouping and the {@code pulse}
 * one-line readout (see docs/tcp_upgrade/05_state_delta_and_pulse.md).
 * <p>
 * The hello path and the {@link TCPCommandServer.StateDelta} serialiser are
 * both exercised without a live Fiji: hello never touches ImageJ state, and
 * the serialiser is a pure data struct that takes pre-built JSON fragments.
 */
public class TCPCommandServerStateDeltaTest {

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    /** Default hello: state_delta and pulse both land in enabled[]. */
    @Test
    public void helloDefaultsEnableStateDeltaAndPulse() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("state_delta on by default", enabledContains(enabled, "state_delta"));
        assertTrue("pulse on by default", enabledContains(enabled, "pulse"));
    }

    /** Claude-style hello ({@code pulse: false}) drops pulse from enabled[]. */
    @Test
    public void helloCanOptOutOfPulse() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"claude-code\","
              + "\"capabilities\":{\"pulse\":false}}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertFalse("pulse explicitly disabled",
                enabledContains(enabled, "pulse"));
        // state_delta stays on — Claude keeps the grouped shape.
        assertTrue("state_delta still enabled",
                enabledContains(enabled, "state_delta"));
    }

    /** Legacy-shape client can opt out of state_delta grouping. */
    @Test
    public void helloCanOptOutOfStateDelta() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"legacy\","
              + "\"capabilities\":{\"state_delta\":false}}");

        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertFalse("state_delta explicitly disabled",
                enabledContains(enabled, "state_delta"));
    }

    /** With state_delta on, diff keys nest under stateDelta; flat keys disappear. */
    @Test
    public void stateDeltaGroupedWhenCapEnabled() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.stateDelta = true;

        TCPCommandServer.StateDelta d = new TCPCommandServer.StateDelta();
        JsonArray imgs = new JsonArray();
        imgs.add("blobs-1");
        d.newImages = imgs;
        d.resultsTable = "Label,Area\nA,10\n";
        d.logDelta = "processing…";

        JsonObject result = new JsonObject();
        d.applyTo(result, caps);

        assertTrue("stateDelta sub-object present", result.has("stateDelta"));
        assertFalse("flat newImages absent", result.has("newImages"));
        assertFalse("flat resultsTable absent", result.has("resultsTable"));
        assertFalse("flat logDelta absent", result.has("logDelta"));

        JsonObject sd = result.getAsJsonObject("stateDelta");
        assertEquals("blobs-1", sd.getAsJsonArray("newImages").get(0).getAsString());
        assertEquals("Label,Area\nA,10\n", sd.get("resultsTable").getAsString());
        assertEquals("processing…", sd.get("logDelta").getAsString());
    }

    /** With state_delta off, flat keys appear at top level (legacy shape). */
    @Test
    public void stateDeltaFlatWhenCapDisabled() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.stateDelta = false;

        TCPCommandServer.StateDelta d = new TCPCommandServer.StateDelta();
        JsonArray imgs = new JsonArray();
        imgs.add("blobs-1");
        d.newImages = imgs;
        d.logDelta = "ok";

        JsonObject result = new JsonObject();
        d.applyTo(result, caps);

        assertFalse("stateDelta nested object absent", result.has("stateDelta"));
        assertTrue("flat newImages present", result.has("newImages"));
        assertTrue("flat logDelta present", result.has("logDelta"));
        assertEquals("blobs-1", result.getAsJsonArray("newImages").get(0).getAsString());
    }

    /** Null caps behave like the legacy shape — mirrors the DEFAULT_CAPS fallback. */
    @Test
    public void stateDeltaNullCapsFallsBackToFlatShape() {
        TCPCommandServer.StateDelta d = new TCPCommandServer.StateDelta();
        d.resultsTable = "x,y\n1,2\n";
        JsonObject result = new JsonObject();

        d.applyTo(result, null);

        // null caps means no state_delta opt-in; the legacy shape MUST win
        // so a request that never saw hello never sees the nested object.
        assertFalse(result.has("stateDelta"));
        assertTrue(result.has("resultsTable"));
    }

    /** An empty delta writes nothing — no stray {@code "stateDelta": {}} key. */
    @Test
    public void emptyStateDeltaWritesNothing() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.stateDelta = true;
        TCPCommandServer.StateDelta d = new TCPCommandServer.StateDelta();

        JsonObject result = new JsonObject();
        d.applyTo(result, caps);

        assertEquals(0, result.size());
    }

    /**
     * {@link PulseBuilder#build()} produces a parseable one-line string even
     * with no active image. The pulse must never throw — state-collection
     * failures degrade gracefully to a stub.
     */
    @Test
    public void pulseBuildProducesStableOutputWithNoImage() {
        String pulse = PulseBuilder.build();
        assertNotNull("pulse must never be null", pulse);
        assertTrue("pulse starts with a count",
                pulse.startsWith("0 imgs") || pulse.startsWith("1 img")
                        || pulse.startsWith("pulse unavailable"));
        if (!"pulse unavailable".equals(pulse)) {
            assertTrue("pulse carries ROI segment", pulse.contains("ROI:"));
            assertTrue("pulse carries results segment", pulse.contains("results:"));
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
