package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for step 13 TCP wiring — hello handshake advertises
 * {@code graph_delta} and {@code get_image_graph}, and the
 * {@link TCPCommandServer#handleGetImageGraph()} command returns the
 * always-queryable session graph. The mutating-handler graphDelta emission
 * is covered by unit tests on {@link ImageGraph} directly. Per plan:
 * docs/tcp_upgrade/13_provenance_graph.md.
 */
public class TCPCommandServerImageGraphTest {

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

    // ------------------------------------------------------------------
    // Hello handshake advertises the new caps
    // ------------------------------------------------------------------

    @Test
    public void helloDefaultsAdvertiseGraphDeltaAndGetImageGraph() {
        TCPCommandServer server = newServer();
        JsonObject req = parse("{\"command\":\"hello\",\"agent\":\"tester\"}");

        JsonObject resp = server.handleHello(req, null);

        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("graph_delta on by default",
                enabledContains(enabled, "graph_delta"));
        assertTrue("get_image_graph always advertised",
                enabledContains(enabled, "get_image_graph"));
    }

    @Test
    public void helloCanOptOutOfGraphDeltaButKeepsGetImageGraph() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"graph_delta\":false}}");

        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");

        assertFalse("graph_delta explicitly disabled",
                enabledContains(enabled, "graph_delta"));
        assertTrue("get_image_graph still available after graph_delta opt-out",
                enabledContains(enabled, "get_image_graph"));
    }

    // ------------------------------------------------------------------
    // get_image_graph command
    // ------------------------------------------------------------------

    @Test
    public void getImageGraphReturnsEmptySnapshotBeforeAnyImageWorkHappens() {
        TCPCommandServer server = newServer();
        JsonObject resp = server.handleGetImageGraph();

        assertTrue(resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        assertNotNull(result.get("nodes"));
        assertNotNull(result.get("edges"));
        assertEquals(0, result.getAsJsonArray("nodes").size());
        assertEquals(0, result.getAsJsonArray("edges").size());
        assertEquals(0, result.get("nodeCount").getAsInt());
        assertEquals(ImageGraph.MAX_NODES, result.get("maxNodes").getAsInt());
    }

    @Test
    public void getImageGraphReflectsInjectedNodesAndEdges() {
        TCPCommandServer server = newServer();

        // Inject synthetic state directly — WindowManager is headless, but
        // the graph data structure is independent of the live image set.
        ImageGraph.Node root = server.imageGraph.addOpenedImage("raw.tif");
        server.imageGraph.addDerivedImage("raw_blur", "macro",
                "run(\"Gaussian Blur...\", \"sigma=2\");",
                Arrays.asList(root.id));

        JsonObject resp = server.handleGetImageGraph();
        JsonObject result = resp.getAsJsonObject("result");

        assertEquals(2, result.get("nodeCount").getAsInt());
        assertEquals(1, result.get("edgeCount").getAsInt());
        JsonArray nodes = result.getAsJsonArray("nodes");
        assertEquals("raw.tif", nodes.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("raw_blur", nodes.get(1).getAsJsonObject().get("title").getAsString());
        JsonObject edge = result.getAsJsonArray("edges").get(0).getAsJsonObject();
        assertEquals("Gaussian Blur...", edge.get("op").getAsString());
    }
}
