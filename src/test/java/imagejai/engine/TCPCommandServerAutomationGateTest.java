package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import imagejai.engine.automation.AutomationBridge;
import imagejai.engine.automation.AutomationPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * The protocol-level gate, over a real loopback socket.
 *
 * <p>Two keys have to turn before a single automation command is answered: Fiji
 * must have been started with the automation property, and the authenticated
 * session must have asked for the capability. These tests hold one key at a
 * time and prove the surface stays shut.</p>
 */
public class TCPCommandServerAutomationGateTest {

    private static final String TOKEN = "loopback-automation-token";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private String previousRequireToken;
    private TCPCommandServer server;
    private AutomationBridge bridge;
    private JFrame frame;
    private int port;

    @Before
    public void setUp() throws Exception {
        previousRequireToken = System.getProperty("imagejai.tcp.requireToken");
        System.setProperty("imagejai.tcp.requireToken", "true");
        server = new TCPCommandServer(0, null, null, null, null);
        server.setServerTokenForTest(TOKEN);
        server.setAuditLogForTest(new imagejai.engine.security.AuditLog(
                temp.newFolder("audit").toPath().resolve("audit.csv")));
        port = startAndAwait(server);
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop();
        if (frame != null) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { frame.dispose(); }
            });
        }
        if (bridge != null) bridge.close();
        if (previousRequireToken == null) {
            System.clearProperty("imagejai.tcp.requireToken");
        } else {
            System.setProperty("imagejai.tcp.requireToken", previousRequireToken);
        }
    }

    // -----------------------------------------------------------------------
    // Property off: production Fiji
    // -----------------------------------------------------------------------

    @Test
    public void aNormalFijiNeverAdvertisesOrAnswersTheAutomationSurface()
            throws Exception {
        JsonObject hello = hello(null);
        JsonArray enabled = hello.getAsJsonArray("enabled");

        assertFalse(contains(enabled, AutomationPolicy.CAPABILITY));
        for (String command : AutomationPolicy.COMMANDS) {
            assertFalse(command, contains(enabled, command));
        }
        assertFalse("a client that did not ask gets no automation block",
                hello.has("automation"));

        String sessionId = hello.get("session_id").getAsString();
        for (String command : AutomationPolicy.COMMANDS) {
            JsonObject response = call(command, sessionId, new JsonObject());
            assertFalse(command, response.get("ok").getAsBoolean());
            assertEquals(command, "test_automation_disabled",
                    response.getAsJsonObject("error").get("code").getAsString());
        }
    }

    @Test
    public void askingForTheCapabilityOnAProductionFijiIsRefusedWithAReason()
            throws Exception {
        JsonObject hello = hello(capabilities(true));
        assertFalse(contains(hello.getAsJsonArray("enabled"),
                AutomationPolicy.CAPABILITY));

        JsonObject automation = hello.getAsJsonObject("automation");
        assertFalse(automation.get("granted").getAsBoolean());
        assertEquals(AutomationPolicy.REASON_ENABLED_ABSENT,
                automation.get("reason").getAsString());
        assertEquals(AutomationPolicy.PROP_ENABLED,
                automation.get("required_property").getAsString());
        assertEquals(AutomationPolicy.CAPABILITY,
                automation.get("required_capability").getAsString());

        JsonObject response = call("get_ui_tree",
                hello.get("session_id").getAsString(), new JsonObject());
        assertEquals("test_automation_disabled",
                response.getAsJsonObject("error").get("code").getAsString());
    }

    // -----------------------------------------------------------------------
    // Property on: harness-owned Fiji
    // -----------------------------------------------------------------------

    @Test
    public void anArmedInstanceStillRefusesASessionThatDidNotAskForIt()
            throws Exception {
        armBridge();
        JsonObject hello = hello(null);
        assertFalse(contains(hello.getAsJsonArray("enabled"),
                AutomationPolicy.CAPABILITY));

        JsonObject response = call("get_ui_tree",
                hello.get("session_id").getAsString(), new JsonObject());
        assertFalse(response.get("ok").getAsBoolean());
        JsonObject error = response.getAsJsonObject("error");
        assertEquals("test_automation_disabled", error.get("code").getAsString());
        assertEquals("capability_not_negotiated",
                error.getAsJsonObject("details").get("reason").getAsString());
    }

    @Test
    public void bothKeysTurnedAdvertisesTheSurfaceAndAnswersIt() throws Exception {
        armBridge();
        showFixtureWindow();

        JsonObject hello = hello(capabilities(true));
        JsonArray enabled = hello.getAsJsonArray("enabled");
        assertTrue(contains(enabled, AutomationPolicy.CAPABILITY));
        for (String command : AutomationPolicy.COMMANDS) {
            assertTrue(command, contains(enabled, command));
        }

        JsonObject automation = hello.getAsJsonObject("automation");
        assertTrue(automation.get("granted").getAsBoolean());
        assertEquals(AutomationPolicy.PROTOCOL_VERSION,
                automation.get("protocol_version").getAsString());
        assertEquals(bridge.policy().instanceId(),
                automation.get("instance_id").getAsString());
        assertEquals(bridge.policy().workspaceId(),
                automation.get("workspace_id").getAsString());
        assertTrue(automation.get("pid").getAsLong() > 0L);
        assertFalse(automation.get("physical_input").getAsBoolean());
        assertNotNull(automation.getAsJsonObject("limits"));
        // The handshake never echoes a credential back.
        assertFalse(hello.toString().contains(TOKEN));

        String sessionId = hello.get("session_id").getAsString();
        JsonObject tree = call("get_ui_tree", sessionId, new JsonObject());
        assertTrue(tree.toString(), tree.get("ok").getAsBoolean());
        JsonObject result = tree.getAsJsonObject("result");
        assertTrue(result.get("window_count").getAsInt() >= 1);
        assertEquals(AutomationPolicy.PROTOCOL_VERSION,
                result.get("protocol_version").getAsString());
        assertTrue(fixtureWindow(result).get("id").getAsString().startsWith("w-"));

        // A semantic action travels the full EDT-operation path and returns
        // dispatch evidence, not just "ok".
        long generation = result.get("generation").getAsLong();
        String nodeId = findButtonId(result);
        JsonObject action = new JsonObject();
        action.addProperty("node_id", nodeId);
        action.addProperty("generation", generation);
        action.addProperty("action", "activate");
        JsonObject performed = call("perform_ui_action", sessionId, action);
        assertTrue(performed.toString(), performed.get("ok").getAsBoolean());
        assertEquals(1, performed.getAsJsonObject("result")
                .getAsJsonObject("dispatch").get("count").getAsInt());
    }

    @Test
    public void anUnauthenticatedClientCannotReachTheSurfaceAtAll() throws Exception {
        armBridge();

        // No session at all.
        JsonObject bare = new JsonObject();
        bare.addProperty("command", "get_ui_tree");
        JsonObject response = exchange(bare);
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals("session_required",
                response.getAsJsonObject("error").get("code").getAsString());

        // A wrong token never negotiates a session in the first place.
        JsonObject badHello = new JsonObject();
        badHello.addProperty("command", "hello");
        badHello.addProperty("agent", "harness");
        badHello.addProperty("token", "wrong-token");
        badHello.add("capabilities", capabilities(true));
        JsonObject refused = exchange(badHello);
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("invalid_token",
                refused.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void theAutomationSurfaceIsNotPartOfTheReadOnlyCompatibilitySurface()
            throws Exception {
        armBridge();
        // A compatibility (unauthenticated) session is only possible when token
        // enforcement is off, and even then the surface must stay closed.
        System.setProperty("imagejai.tcp.requireToken", "false");
        try {
            JsonObject request = new JsonObject();
            request.addProperty("command", "get_ui_tree");
            JsonObject response = exchange(request);
            assertFalse(response.get("ok").getAsBoolean());
            assertEquals("compatibility_read_only",
                    response.getAsJsonObject("error").get("code").getAsString());
        } finally {
            System.setProperty("imagejai.tcp.requireToken", "true");
        }
    }

    @Test
    public void everyGatedCommandIsDeclaredInThePackagedManifest() {
        for (String command : AutomationPolicy.COMMANDS) {
            assertTrue(command, TCPCommandServer.knownCommands().contains(command));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void armBridge() throws Exception {
        File workspace = temp.newFolder("run");
        Properties properties = new Properties();
        properties.setProperty(AutomationPolicy.PROP_ENABLED, "true");
        properties.setProperty(AutomationPolicy.PROP_WORKSPACE,
                workspace.getAbsolutePath());
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(workspace, "ready.json").getAbsolutePath());
        AutomationPolicy policy = AutomationPolicy.fromProperties(properties);
        assertTrue(policy.isEnabled());
        bridge = AutomationBridge.forPolicy(policy, EventBus.getInstance());
        server.setAutomationBridgeForTest(bridge);
    }

    private void showFixtureWindow() throws Exception {
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                frame = new JFrame("Gate Fixture");
                JPanel content = new JPanel();
                content.add(new JButton("Run"));
                frame.getContentPane().add(content);
                frame.pack();
                frame.setLocation(-3000, -3000);
                frame.setVisible(true);
            }
        });
    }

    /** The fixture window, located by title among everything this JVM owns. */
    private static JsonObject fixtureWindow(JsonObject treeResult) {
        for (JsonElement window : treeResult.getAsJsonArray("windows")) {
            JsonObject candidate = window.getAsJsonObject();
            if (candidate.has("title")
                    && "Gate Fixture".equals(candidate.get("title").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("fixture window missing from the UI tree");
    }

    private static String findButtonId(JsonObject treeResult) {
        JsonObject button = search(fixtureWindow(treeResult), "Run");
        assertNotNull("fixture button missing from the tree", button);
        return button.get("id").getAsString();
    }

    private static JsonObject search(JsonObject node, String label) {
        JsonElement candidate = node.get("label");
        if (candidate != null && candidate.isJsonPrimitive()
                && label.equals(candidate.getAsString())) {
            return node;
        }
        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) {
                JsonObject found = search(child.getAsJsonObject(), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JsonObject capabilities(boolean testAutomation) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("test_automation", testAutomation);
        return capabilities;
    }

    private JsonObject hello(JsonObject capabilities) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("command", "hello");
        request.addProperty("agent", "imagej-test-auto");
        request.addProperty("token", TOKEN);
        if (capabilities != null) request.add("capabilities", capabilities);
        JsonObject response = exchange(request);
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        return response.getAsJsonObject("result");
    }

    private JsonObject call(String command, String sessionId, JsonObject fields)
            throws Exception {
        JsonObject request = fields.deepCopy();
        request.addProperty("command", command);
        request.addProperty("session_id", sessionId);
        request.addProperty("token", TOKEN);
        return exchange(request);
    }

    private JsonObject exchange(JsonObject request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(30_000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(request.toString());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertNotNull("server closed without replying", line);
            return JsonParser.parseString(line).getAsJsonObject();
        }
    }

    private static int startAndAwait(TCPCommandServer server) throws Exception {
        server.start(null);
        for (int attempt = 0; attempt < 200; attempt++) {
            if (server.isRunning()) return server.getPort();
            Thread.sleep(25L);
        }
        throw new IllegalStateException("TCP server did not start");
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (value.equals(element.getAsString())) return true;
        }
        return false;
    }
}
