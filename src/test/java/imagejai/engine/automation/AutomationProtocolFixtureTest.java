package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Keeps the published golden fixtures honest.
 *
 * <p>The external harness is written against
 * {@code docs/automation-bridge/fixtures/*.json}. If the bridge stopped
 * emitting a documented field, or emitted it with a different JSON type, that
 * client would break at run time against a live Fiji — the most expensive place
 * to find out. Every fixture is therefore checked against a response this build
 * actually produces: every key the fixture documents must exist, with the same
 * type, in the live reply.</p>
 *
 * <p>Values are deliberately not compared. Ids, timings, and geometry are
 * environment-specific; the contract is the shape.</p>
 */
public class AutomationProtocolFixtureTest {

    private static final Path FIXTURES =
            Paths.get("docs", "automation-bridge", "fixtures");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private AutomationBridge bridge;
    private AutomationCommandHandler handler;
    private AutomationPolicy policy;
    private JFrame frame;

    @Before
    public void setUp() throws Exception {
        assumeTrue("fixtures are only reachable from the project root",
                Files.isDirectory(FIXTURES));
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());

        File workspace = temp.newFolder();
        Properties properties = new Properties();
        properties.setProperty(AutomationPolicy.PROP_ENABLED, "true");
        properties.setProperty(AutomationPolicy.PROP_WORKSPACE,
                workspace.getAbsolutePath());
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(workspace, "ready.json").getAbsolutePath());
        policy = AutomationPolicy.fromProperties(properties);
        assertTrue(policy.isEnabled());

        bridge = AutomationBridge.forPolicy(policy, null);
        handler = bridge.handler();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                frame = new JFrame("Fixture Dialog");
                JPanel content = new JPanel();
                JButton ok = new JButton("OK");
                // The published fixtures show a named button and a masked
                // credential field, so the live fixture has to have both or the
                // comparison would not actually exercise those fields.
                ok.setName("okButton");
                JLabel label = new JLabel("Sigma");
                JTextField sigma = new JTextField("2.0", 8);
                label.setLabelFor(sigma);
                javax.swing.JPasswordField apiKey =
                        new javax.swing.JPasswordField("not-a-real-credential", 8);
                apiKey.setName("apiKeyField");
                content.add(ok);
                content.add(label);
                content.add(sigma);
                content.add(apiKey);
                frame.getContentPane().add(content);
                frame.pack();
                frame.setLocation(-3000, -3000);
                frame.setVisible(true);
            }
        });
        bridge.service().setWindowSupplierForTest(
                () -> new java.awt.Window[] {frame});
    }

    @After
    public void tearDown() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { frame.dispose(); }
            });
        }
        if (bridge != null) bridge.close();
    }

    @Test
    public void readyFileMatchesItsFixture() throws Exception {
        assertTrue(AutomationReadyFile.write(policy, 53_211, "127.0.0.1",
                "1.8.0", "0.3.0"));
        JsonObject live = JsonParser.parseString(new String(
                Files.readAllBytes(policy.readyFile()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        conforms("ready-file.json", live);
        // The ready schema forbids extra keys, so the live document must not
        // grow silently either.
        assertEquals(fixture("ready-file.json").keySet(), live.keySet());
    }

    @Test
    public void grantedHandshakeBlockMatchesItsFixture() {
        JsonObject live = handler.capabilityDescriptor();
        live.addProperty("granted", true);
        conforms("hello-automation-granted.json", live);
    }

    @Test
    public void refusedHandshakeBlockMatchesItsFixture() {
        AutomationBridge inert = AutomationBridge.forPolicy(
                AutomationPolicy.disabled(AutomationPolicy.REASON_ENABLED_ABSENT), null);
        try {
            JsonObject live = new JsonObject();
            live.addProperty("granted", false);
            live.addProperty("reason", inert.policy().disabledReason());
            live.addProperty("required_property", AutomationPolicy.PROP_ENABLED);
            live.addProperty("required_capability", AutomationPolicy.CAPABILITY);
            conforms("hello-automation-refused.json", live);
        } finally {
            inert.close();
        }
    }

    @Test
    public void uiTreeMatchesItsFixture() {
        JsonObject live = result("get_ui_tree", new JsonObject());
        // The envelope and the window root, without walking into children: the
        // fixture's child list is an illustration, and a real frame's first
        // child is its root pane rather than the documented button.
        conformsIgnoringArrays("get-ui-tree.result.json", live);
        JsonObject fixtureWindow = fixture("get-ui-tree.result.json")
                .getAsJsonArray("windows").get(0).getAsJsonObject();
        assertShape("windows[0]", fixtureWindow,
                live.getAsJsonArray("windows").get(0), false);

        // Then the documented button node against the live one, in full.
        JsonObject fixtureButton = null;
        for (JsonElement child : fixtureWindow.getAsJsonArray("children")) {
            if (UiNode.ROLE_BUTTON.equals(
                    child.getAsJsonObject().get("role").getAsString())) {
                fixtureButton = child.getAsJsonObject();
            }
        }
        assertShape("windows[0].children[button]", fixtureButton,
                node(live, "OK"), true);
    }

    @Test
    public void uiComponentMatchesItsFixture() {
        // The published fixture is a masked credential field, which is where
        // the interesting guarantees live: masked, masked_length, no value, no
        // actions at all.
        JsonObject request = new JsonObject();
        request.addProperty("node_id", passwordNodeId());
        JsonObject live = result("get_ui_component", request);
        conforms("get-ui-component.result.json", live);
        JsonObject node = live.getAsJsonObject("node");
        assertTrue(node.get("masked").getAsBoolean());
        assertTrue(node.get("masked_length").getAsInt() > 0);
        assertEquals(0, node.getAsJsonArray("actions").size());
        assertTrue(!live.toString().contains("not-a-real-credential"));
    }

    @Test
    public void semanticActionMatchesItsFixture() {
        JsonObject tree = result("get_ui_tree", new JsonObject());
        JsonObject request = new JsonObject();
        request.addProperty("node_id", nodeId("Sigma"));
        request.addProperty("generation", tree.get("generation").getAsLong());
        request.addProperty("action", UiNode.ACTION_SET_TEXT);
        request.addProperty("value", "3.5");
        conforms("perform-ui-action.result.json",
                result("perform_ui_action", request));
    }

    @Test
    public void bothWaitForStateOutcomesMatchTheirFixtures() {
        JsonObject satisfied = new JsonObject();
        satisfied.addProperty("node_id", nodeId("OK"));
        satisfied.add("predicate", JsonParser.parseString("{\"showing\":true}")
                .getAsJsonObject());
        satisfied.addProperty("timeout_ms", 2_000);
        conforms("wait-for-ui-state.satisfied.json",
                result("wait_for_ui_state", satisfied));

        JsonObject timeout = new JsonObject();
        timeout.addProperty("node_id", nodeId("OK"));
        timeout.add("predicate", JsonParser.parseString("{\"enabled\":false}")
                .getAsJsonObject());
        timeout.addProperty("timeout_ms", 120);
        timeout.addProperty("poll_interval_ms", 20);
        conforms("wait-for-ui-state.timeout.json",
                result("wait_for_ui_state", timeout));
    }

    @Test
    public void idleResultMatchesItsFixture() {
        JsonObject request = new JsonObject();
        request.addProperty("timeout_ms", 2_000);
        request.addProperty("quiet_ms", 30);
        JsonObject live = result("wait_for_ui_idle", request);
        // The blocked fixture documents the same envelope plus non-empty
        // blockers, so the idle one is the strict superset check.
        conforms("wait-for-ui-idle.idle.json", live);
        conformsIgnoringArrays("wait-for-ui-idle.blocked.json", live);
    }

    @Test
    public void captureResultMatchesItsFixture() {
        JsonObject tree = result("get_ui_tree", new JsonObject());
        JsonObject request = new JsonObject();
        request.addProperty("node_id", nodeId("OK"));
        request.addProperty("generation", tree.get("generation").getAsLong());
        request.addProperty("include_bytes", false);
        conforms("capture-ui.result.json", result("capture_ui", request));
    }

    @Test
    public void traceResultMatchesItsFixture() {
        JsonObject start = new JsonObject();
        start.addProperty("action_id", "fixture:step-1");
        String traceId = result("start_ui_trace", start).get("trace_id").getAsString();

        JsonObject stop = new JsonObject();
        stop.addProperty("trace_id", traceId);
        stop.addProperty("phase", "terminal_state_observed");
        conforms("ui-trace.result.json", result("stop_ui_trace", stop));
    }

    @Test
    public void errorEnvelopesMatchTheirFixtures() {
        AutomationBridge inert = AutomationBridge.forPolicy(
                AutomationPolicy.disabled(AutomationPolicy.REASON_ENABLED_ABSENT), null);
        try {
            conformsElement("error.test-automation-disabled.json",
                    inert.handler().handle("get_ui_tree", new JsonObject(), true, null),
                    false);
        } finally {
            inert.close();
        }

        JsonObject stale = new JsonObject();
        stale.addProperty("node_id", nodeId("OK"));
        stale.addProperty("generation", 999_999L);
        stale.addProperty("action", UiNode.ACTION_ACTIVATE);
        conformsElement("error.stale-ui-target.json",
                handler.handle("perform_ui_action", stale, true, null), false);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private JsonObject result(String command, JsonObject request) {
        JsonObject response = handler.handle(command, request, true, null);
        assertTrue(command + ": " + response, response.get("ok").getAsBoolean());
        return response.getAsJsonObject("result");
    }

    private String nodeId(String label) {
        return node(result("get_ui_tree", new JsonObject()), label)
                .get("id").getAsString();
    }

    private static JsonObject node(JsonObject tree, String label) {
        for (JsonElement window : tree.getAsJsonArray("windows")) {
            JsonObject found = search(window.getAsJsonObject(), label);
            if (found != null) return found;
        }
        throw new AssertionError("no node labelled " + label);
    }

    private String passwordNodeId() {
        JsonObject tree = result("get_ui_tree", new JsonObject());
        for (JsonElement window : tree.getAsJsonArray("windows")) {
            JsonObject found = searchByRole(window.getAsJsonObject(),
                    UiNode.ROLE_PASSWORD);
            if (found != null) return found.get("id").getAsString();
        }
        throw new AssertionError("the fixture password field is missing");
    }

    private static JsonObject searchByRole(JsonObject node, String role) {
        if (role.equals(node.get("role").getAsString())) return node;
        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) {
                JsonObject found = searchByRole(child.getAsJsonObject(), role);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JsonObject search(JsonObject node, String label) {
        JsonElement candidate = node.get("label");
        if (candidate != null && candidate.isJsonPrimitive()
                && label.equals(candidate.getAsString())
                && !UiNode.ROLE_LABEL.equals(node.get("role").getAsString())) {
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

    private JsonObject fixture(String name) {
        try {
            return JsonParser.parseString(new String(
                    Files.readAllBytes(FIXTURES.resolve(name)),
                    StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException unreadable) {
            throw new AssertionError("cannot read fixture " + name, unreadable);
        }
    }

    private void conforms(String fixtureName, JsonObject live) {
        conformsElement(fixtureName, live, true);
    }

    private void conformsIgnoringArrays(String fixtureName, JsonObject live) {
        conformsElement(fixtureName, live, false);
    }

    private void conformsElement(String fixtureName, JsonObject live,
                                 boolean descendArrays) {
        assertShape(fixtureName, fixture(fixtureName), live, descendArrays);
    }

    /**
     * Every key the fixture documents must exist in the live reply with the
     * same JSON type. Extra live keys are fine — the protocol is additive
     * within a major version.
     */
    private static void assertShape(String path, JsonElement expected,
                                    JsonElement actual, boolean descendArrays) {
        if (expected.isJsonObject()) {
            if (actual == null || !actual.isJsonObject()) {
                fail(path + ": expected an object, live reply had " + actual);
            }
            JsonObject expectedObject = expected.getAsJsonObject();
            JsonObject actualObject = actual.getAsJsonObject();
            for (String key : expectedObject.keySet()) {
                JsonElement child = actualObject.get(key);
                if (child == null) {
                    fail(path + ": the live reply is missing documented field '"
                            + key + "'");
                }
                assertShape(path + "." + key, expectedObject.get(key), child,
                        descendArrays);
            }
            return;
        }
        if (expected.isJsonArray()) {
            if (actual == null || !actual.isJsonArray()) {
                fail(path + ": expected an array, live reply had " + actual);
            }
            if (!descendArrays) return;
            JsonArray expectedArray = expected.getAsJsonArray();
            JsonArray actualArray = actual.getAsJsonArray();
            if (expectedArray.size() == 0 || actualArray.size() == 0) return;
            assertShape(path + "[0]", expectedArray.get(0), actualArray.get(0),
                    descendArrays);
            return;
        }
        if (expected.isJsonNull()) return;
        if (actual == null || !actual.isJsonPrimitive()) {
            fail(path + ": expected a primitive, live reply had " + actual);
        }
        com.google.gson.JsonPrimitive expectedPrimitive = expected.getAsJsonPrimitive();
        com.google.gson.JsonPrimitive actualPrimitive = actual.getAsJsonPrimitive();
        if (expectedPrimitive.isBoolean() && !actualPrimitive.isBoolean()) {
            fail(path + ": expected a boolean, live reply had " + actualPrimitive);
        }
        if (expectedPrimitive.isNumber() && !actualPrimitive.isNumber()) {
            fail(path + ": expected a number, live reply had " + actualPrimitive);
        }
        if (expectedPrimitive.isString() && !actualPrimitive.isString()) {
            fail(path + ": expected a string, live reply had " + actualPrimitive);
        }
    }
}
