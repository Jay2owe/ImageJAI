package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the step 14 TCP wiring — hello advertises
 * {@code ledger}, {@code ledger_lookup}, {@code ledger_confirm}; the two
 * commands round-trip through the dispatcher. Per plan:
 * docs/tcp_upgrade/14_federated_ledger.md.
 */
public class TCPCommandServerLedgerTest {

    private Path tmpDir;

    @Before
    public void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("imagejai-tcpledger-");
    }

    @After
    public void tearDown() throws IOException {
        if (tmpDir == null) return;
        List<Path> paths = new ArrayList<Path>();
        Files.walk(tmpDir).forEach(paths::add);
        paths.sort(Comparator.reverseOrder());
        for (Path p : paths) {
            try { Files.deleteIfExists(p); } catch (IOException ignore) {}
        }
    }

    private TCPCommandServer newServerWithTempLedger() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        server.setLedgerStore(new LedgerStore(tmpDir.resolve("ledger.json")));
        return server;
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

    // ------------------------------------------------------------------
    // Hello handshake advertises the new caps
    // ------------------------------------------------------------------

    @Test
    public void helloDefaultsAdvertiseLedgerAndCommands() {
        TCPCommandServer server = newServerWithTempLedger();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");

        assertTrue("ledger auto-attach on by default",
                enabledContains(enabled, "ledger"));
        assertTrue("ledger_lookup always advertised",
                enabledContains(enabled, "ledger_lookup"));
        assertTrue("ledger_confirm always advertised",
                enabledContains(enabled, "ledger_confirm"));
    }

    @Test
    public void helloCanOptOutOfAutoAttachButKeepsExplicitCommands() {
        TCPCommandServer server = newServerWithTempLedger();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"ledger\":false}}");

        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");

        assertFalse("ledger cap off when explicitly disabled",
                enabledContains(enabled, "ledger"));
        assertTrue("explicit ledger_lookup still available",
                enabledContains(enabled, "ledger_lookup"));
        assertTrue("explicit ledger_confirm still available",
                enabledContains(enabled, "ledger_confirm"));
    }

    // ------------------------------------------------------------------
    // ledger_confirm → ledger_lookup round trip
    // ------------------------------------------------------------------

    @Test
    public void confirmThenLookupReturnsTheRecordedFix() {
        TCPCommandServer server = newServerWithTempLedger();

        JsonObject confirmReq = parse(
                "{\"command\":\"ledger_confirm\","
              + "\"error_code\":\"PLUGIN_NOT_FOUND\","
              + "\"error_fragment\":\"Unrecognized command: Laplacian\","
              + "\"macro_prefix\":\"run(\\\"Laplacian\\\")\","
              + "\"fix\":\"Use run(\\\"FeatureJ Laplacian\\\") instead.\","
              + "\"example_macro\":\"run(\\\"FeatureJ Laplacian\\\");\","
              + "\"agent_id\":\"claude-code\","
              + "\"worked\":true}");

        JsonObject confirmResp = server.handleLedgerConfirm(confirmReq, null);
        assertTrue(confirmResp.get("ok").getAsBoolean());
        String fp = confirmResp.getAsJsonObject("result")
                .get("fingerprint").getAsString();
        assertEquals(32, fp.length());

        JsonObject lookupReq = parse(
                "{\"command\":\"ledger_lookup\","
              + "\"error_code\":\"PLUGIN_NOT_FOUND\","
              + "\"error_fragment\":\"Unrecognized command: Laplacian\","
              + "\"macro_prefix\":\"run(\\\"Laplacian\\\")\"}");

        JsonObject lookupResp = server.handleLedgerLookup(lookupReq);
        assertTrue(lookupResp.get("ok").getAsBoolean());
        JsonArray matches = lookupResp.getAsJsonObject("result")
                .getAsJsonArray("matches");
        assertEquals(1, matches.size());
        JsonObject match = matches.get(0).getAsJsonObject();
        assertEquals("Use run(\"FeatureJ Laplacian\") instead.",
                match.get("confirmedFix").getAsString());
        assertEquals("low", match.get("confidence").getAsString());
    }

    @Test
    public void lookupOnMissingEntryReturnsEmptyMatches() {
        TCPCommandServer server = newServerWithTempLedger();
        JsonObject req = parse(
                "{\"command\":\"ledger_lookup\","
              + "\"error_code\":\"X\","
              + "\"error_fragment\":\"never seen\","
              + "\"macro_prefix\":\"run(\\\"Foo\\\")\"}");

        JsonObject resp = server.handleLedgerLookup(req);
        assertTrue(resp.get("ok").getAsBoolean());
        assertEquals(0, resp.getAsJsonObject("result")
                .getAsJsonArray("matches").size());
        assertEquals(32, resp.getAsJsonObject("result")
                .get("fingerprint").getAsString().length());
    }

    @Test
    public void confirmUsesCapsAgentIdWhenExplicitAgentIdMissing() {
        TCPCommandServer server = newServerWithTempLedger();

        JsonObject hello = parse(
                "{\"command\":\"hello\",\"agent\":\"gemma\","
              + "\"capabilities\":{\"agent_id\":\"gemma-31b\"}}");
        server.handleHello(hello, null);

        // Construct a caps object by reading it back from the hello response
        // path — easier: call handleLedgerConfirm with the caps DEFAULT and
        // assert behaviour via the agent_id field.
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.agentId = "gemma-31b";

        JsonObject confirmReq = parse(
                "{\"command\":\"ledger_confirm\","
              + "\"error_code\":\"X\","
              + "\"error_fragment\":\"frag\","
              + "\"macro_prefix\":\"run(\\\"A\\\")\","
              + "\"fix\":\"f\",\"worked\":true}");

        JsonObject resp = server.handleLedgerConfirm(confirmReq, caps);
        JsonArray by = resp.getAsJsonObject("result").getAsJsonArray("confirmedBy");
        assertEquals(1, by.size());
        assertEquals("gemma-31b", by.get(0).getAsString());
    }
}
