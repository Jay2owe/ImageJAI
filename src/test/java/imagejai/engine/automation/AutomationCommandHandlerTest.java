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
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * The gated command surface: the two-key gate, request validation, and the
 * shape of every reply an independent Python client has to parse.
 */
public class AutomationCommandHandlerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private AutomationBridge bridge;
    private AutomationCommandHandler handler;
    private JFrame frame;
    private JButton okButton;
    private JCheckBox preview;

    private AutomationPolicy armedPolicy() throws Exception {
        File workspace = temp.newFolder();
        Properties properties = new Properties();
        properties.setProperty(AutomationPolicy.PROP_ENABLED, "true");
        properties.setProperty(AutomationPolicy.PROP_WORKSPACE,
                workspace.getAbsolutePath());
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(workspace, "ready.json").getAbsolutePath());
        return AutomationPolicy.fromProperties(properties);
    }

    @Before
    public void setUp() throws Exception {
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());
        bridge = AutomationBridge.forPolicy(armedPolicy(), null);
        handler = bridge.handler();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                frame = new JFrame("Handler Fixture");
                JPanel content = new JPanel();
                okButton = new JButton("OK");
                preview = new JCheckBox("Preview");
                content.add(okButton);
                content.add(preview);
                frame.getContentPane().add(content);
                frame.pack();
                frame.setLocation(-3000, -3000);
                frame.setVisible(true);
            }
        });
        bridge.service().setWindowSupplierForTest(() -> new java.awt.Window[] {frame});
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

    // -----------------------------------------------------------------------
    // The gate
    // -----------------------------------------------------------------------

    @Test
    public void withoutTheStartupPropertyEverythingIsRefusedIdentically() {
        AutomationBridge inert = AutomationBridge.forPolicy(
                AutomationPolicy.disabled(AutomationPolicy.REASON_ENABLED_ABSENT), null);
        try {
            for (String command : AutomationPolicy.COMMANDS) {
                JsonObject response = inert.handler().handle(command,
                        new JsonObject(), true, null);
                assertFalse(command, response.get("ok").getAsBoolean());
                JsonObject error = response.getAsJsonObject("error");
                assertEquals(command, AutomationCommandHandler.ERR_DISABLED,
                        error.get("code").getAsString());
                assertEquals("authorization", error.get("category").getAsString());
                assertFalse(error.get("retry_safe").getAsBoolean());
                assertEquals(AutomationPolicy.REASON_ENABLED_ABSENT,
                        error.getAsJsonObject("details").get("reason").getAsString());
            }
        } finally {
            inert.close();
        }
    }

    @Test
    public void thePropertyAloneIsNotEnoughWithoutTheNegotiatedCapability() {
        JsonObject response = handler.handle("get_ui_tree", new JsonObject(),
                false, null);
        assertFalse(response.get("ok").getAsBoolean());
        JsonObject error = response.getAsJsonObject("error");
        assertEquals(AutomationCommandHandler.ERR_DISABLED, error.get("code").getAsString());
        assertEquals(AutomationCommandHandler.REASON_CAP_NOT_NEGOTIATED,
                error.getAsJsonObject("details").get("reason").getAsString());
    }

    @Test
    public void aRefusalNeverLeaksTheWorkspacePath() {
        AutomationBridge inert = AutomationBridge.forPolicy(
                AutomationPolicy.disabled(
                        AutomationPolicy.REASON_READY_FILE_OUTSIDE_WORKSPACE), null);
        try {
            String encoded = inert.handler()
                    .handle("get_ui_tree", new JsonObject(), true, null).toString();
            assertFalse(encoded.contains(File.separator + "Users"));
            assertFalse(encoded.contains(temp.getRoot().getName()));
        } finally {
            inert.close();
        }
    }

    @Test
    public void theCapabilityDescriptorIsSelfDescribingForAnIndependentClient() {
        JsonObject descriptor = handler.capabilityDescriptor();
        assertEquals(AutomationPolicy.PROTOCOL_VERSION,
                descriptor.get("protocol_version").getAsString());
        assertEquals(bridge.policy().instanceId(),
                descriptor.get("instance_id").getAsString());
        assertEquals(bridge.policy().workspaceId(),
                descriptor.get("workspace_id").getAsString());
        assertEquals(AutomationPolicy.COMMANDS.size(),
                descriptor.getAsJsonArray("commands").size());
        assertTrue(descriptor.getAsJsonArray("semantic_actions").size() >= 10);
        // The bridge states plainly that it cannot produce physical input, so a
        // harness cannot mistake a semantic activate for a real click.
        assertFalse(descriptor.get("physical_input").getAsBoolean());
        JsonObject limits = descriptor.getAsJsonObject("limits");
        assertEquals(AutomationPolicy.MAX_TREE_NODES,
                limits.get("max_tree_nodes").getAsInt());
        assertEquals(AutomationPolicy.MAX_CAPTURE_BYTES,
                limits.get("max_capture_bytes").getAsInt());
        assertTrue(descriptor.has("instrumented_event_queue"));
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    @Test
    public void getUiTreeReturnsAVersionedSnapshotOfOwnedWindows() {
        JsonObject result = ok(handler.handle("get_ui_tree", new JsonObject(), true, null));
        assertEquals(AutomationPolicy.PROTOCOL_VERSION,
                result.get("protocol_version").getAsString());
        assertTrue(result.get("generation").getAsLong() > 0L);
        assertEquals(1, result.get("window_count").getAsInt());
        assertTrue(result.get("node_count").getAsInt() >= 3);
        assertFalse(result.get("truncated").getAsBoolean());
        assertNotNull(result.getAsJsonObject("environment"));

        JsonObject window = result.getAsJsonArray("windows").get(0).getAsJsonObject();
        assertEquals("Handler Fixture", window.get("title").getAsString());
        assertTrue(window.get("id").getAsString().startsWith("w-"));
    }

    @Test
    public void getUiTreeCanBeScopedAndRejectsAMalformedWindowId() {
        JsonObject tree = ok(handler.handle("get_ui_tree", new JsonObject(), true, null));
        String windowId = tree.getAsJsonArray("windows").get(0)
                .getAsJsonObject().get("id").getAsString();

        JsonObject request = new JsonObject();
        request.addProperty("window_id", windowId);
        assertEquals(1, ok(handler.handle("get_ui_tree", request, true, null))
                .get("window_count").getAsInt());

        request.addProperty("window_id", "../../etc/passwd");
        JsonObject rejected = handler.handle("get_ui_tree", request, true, null);
        assertFalse(rejected.get("ok").getAsBoolean());
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                rejected.getAsJsonObject("error").get("code").getAsString());

        request.addProperty("window_id", "w-deadbeef-9999");
        JsonObject unknown = handler.handle("get_ui_tree", request, true, null);
        assertFalse(unknown.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_UNKNOWN,
                unknown.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void getUiComponentReturnsOneNodeAndRequiresAnIdentity() {
        String nodeId = idOf("OK");
        JsonObject request = new JsonObject();
        request.addProperty("node_id", nodeId);
        JsonObject result = ok(handler.handle("get_ui_component", request, true, null));
        JsonObject node = result.getAsJsonObject("node");
        assertEquals(nodeId, node.get("id").getAsString());
        assertEquals(UiNode.ROLE_BUTTON, node.get("role").getAsString());
        assertFalse("children are opt-in on a single-node read", node.has("children"));

        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("get_ui_component", new JsonObject(),
                        true, null)));
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    @Test
    public void aMutationWithoutAGenerationIsRefused() {
        JsonObject request = new JsonObject();
        request.addProperty("node_id", idOf("OK"));
        request.addProperty("action", UiNode.ACTION_ACTIVATE);
        JsonObject response = handler.handle("perform_ui_action", request, true, null);
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals(AutomationCommandHandler.ERR_INVALID, errorCode(response));
        assertTrue(response.getAsJsonObject("error").get("message").getAsString()
                .contains("generation is required"));
    }

    @Test
    public void aStaleGenerationIsRefusedWithBothGenerationsReported() {
        JsonObject tree = ok(handler.handle("get_ui_tree", new JsonObject(), true, null));
        long generation = tree.get("generation").getAsLong();

        JsonObject request = new JsonObject();
        request.addProperty("node_id", idOf("OK"));
        request.addProperty("generation", generation - 1);
        request.addProperty("action", UiNode.ACTION_ACTIVATE);

        JsonObject response = handler.handle("perform_ui_action", request, true, null);
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_STALE, errorCode(response));
        JsonObject details = response.getAsJsonObject("error").getAsJsonObject("details");
        assertEquals(generation - 1, details.get("requested_generation").getAsLong());
        assertEquals(generation, details.get("current_generation").getAsLong());
    }

    @Test
    public void aSemanticActionRunsOnceAndReturnsPreAndPostEvidence() {
        JsonObject tree = ok(handler.handle("get_ui_tree", new JsonObject(), true, null));
        JsonObject request = new JsonObject();
        request.addProperty("node_id", idOf("Preview"));
        request.addProperty("generation", tree.get("generation").getAsLong());
        request.addProperty("action", UiNode.ACTION_SET_SELECTED);
        request.addProperty("value", true);

        JsonObject result = ok(handler.handle("perform_ui_action", request, true, null));
        assertEquals(UiNode.ACTION_SET_SELECTED, result.get("action").getAsString());
        assertEquals(1, result.getAsJsonObject("dispatch").get("count").getAsInt());
        assertFalse(result.getAsJsonObject("pre_state").get("selected").getAsBoolean());
        assertTrue(result.getAsJsonObject("post_state").get("selected").getAsBoolean());
        assertTrue(result.getAsJsonObject("delta").has("selected"));
        assertTrue(preview.isSelected());
    }

    // -----------------------------------------------------------------------
    // Waits
    // -----------------------------------------------------------------------

    @Test
    public void waitForUiStateNeedsANonEmptyPredicate() {
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("wait_for_ui_state", new JsonObject(),
                        true, null)));

        JsonObject empty = new JsonObject();
        empty.add("predicate", new JsonObject());
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("wait_for_ui_state", empty, true, null)));

        JsonObject both = new JsonObject();
        both.add("predicate", parse("{\"showing\":true}"));
        both.addProperty("node_id", idOf("OK"));
        both.addProperty("window_id", "w-x-1");
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("wait_for_ui_state", both, true, null)));
    }

    @Test
    public void anAlreadyTrueStatePredicateReturnsImmediately() {
        JsonObject request = new JsonObject();
        request.addProperty("node_id", idOf("OK"));
        request.add("predicate", parse("{\"showing\":true,\"enabled\":true}"));
        request.addProperty("timeout_ms", 2_000);

        JsonObject result = ok(handler.handle("wait_for_ui_state", request, true, null));
        assertTrue(result.get("satisfied").getAsBoolean());
        assertEquals(1, result.get("polls").getAsInt());
        assertEquals(0, result.getAsJsonArray("unsatisfied").size());
        assertTrue(result.getAsJsonObject("observed").get("showing").getAsBoolean());
    }

    @Test
    public void anUnreachablePredicateTimesOutAndNamesWhatFailed() {
        JsonObject request = new JsonObject();
        request.addProperty("node_id", idOf("OK"));
        request.add("predicate", parse("{\"enabled\":false}"));
        request.addProperty("timeout_ms", 120);
        request.addProperty("poll_interval_ms", 20);

        JsonObject result = ok(handler.handle("wait_for_ui_state", request, true, null));
        assertFalse(result.get("satisfied").getAsBoolean());
        assertTrue(contains(result.getAsJsonArray("unsatisfied"), "enabled"));
        assertTrue(result.get("polls").getAsInt() >= 1);
        assertTrue(result.get("waited_ms").getAsDouble() > 0.0d);
    }

    @Test
    public void aPredicateCanWaitForAWindowWithoutKnowingItsIdentity() {
        JsonObject request = new JsonObject();
        request.add("predicate",
                parse("{\"window_present\":{\"title_equals\":\"Handler Fixture\"}}"));
        request.addProperty("timeout_ms", 1_000);

        JsonObject result = ok(handler.handle("wait_for_ui_state", request, true, null));
        assertTrue(result.get("satisfied").getAsBoolean());
        JsonObject match = result.getAsJsonObject("observed")
                .getAsJsonObject("window_present");
        assertEquals(1, match.get("match_count").getAsInt());
        assertFalse(match.get("ambiguous").getAsBoolean());
        assertTrue(match.getAsJsonArray("matches").get(0).getAsJsonObject()
                .get("window_id").getAsString().startsWith("w-"));

        JsonObject absent = new JsonObject();
        absent.add("predicate",
                parse("{\"window_present\":{\"title_equals\":\"Nothing Like This\"}}"));
        absent.addProperty("timeout_ms", 100);
        JsonObject missing = ok(handler.handle("wait_for_ui_state", absent, true, null));
        assertFalse(missing.get("satisfied").getAsBoolean());
        assertTrue(contains(missing.getAsJsonArray("unsatisfied"), "window_present"));
    }

    @Test
    public void waitForUiIdleReportsItsCheckedConditions() {
        JsonObject request = new JsonObject();
        request.addProperty("timeout_ms", 2_000);
        request.addProperty("quiet_ms", 30);

        JsonObject result = ok(handler.handle("wait_for_ui_idle", request, true, null));
        assertTrue(result.toString(), result.get("idle").getAsBoolean());
        assertTrue(result.getAsJsonObject("checks").get("edt_barrier").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("blockers").size());
        assertEquals(30, result.get("quiet_ms").getAsInt());
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    @Test
    public void captureUiRequiresAnIdentityAndAGeneration() {
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("capture_ui", new JsonObject(), true, null)));

        JsonObject noGeneration = new JsonObject();
        noGeneration.addProperty("node_id", idOf("OK"));
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("capture_ui", noGeneration, true, null)));
    }

    @Test
    public void captureUiRendersTheRequestedNode() {
        JsonObject tree = ok(handler.handle("get_ui_tree", new JsonObject(), true, null));
        JsonObject request = new JsonObject();
        request.addProperty("node_id", idOf("OK"));
        request.addProperty("generation", tree.get("generation").getAsLong());
        request.addProperty("max_dimension", 64);
        request.addProperty("include_bytes", false);

        JsonObject result = ok(handler.handle("capture_ui", request, true, null));
        assertEquals(UiCaptureService.SOURCE, result.get("source").getAsString());
        assertEquals("component", result.get("mode").getAsString());
        assertEquals(64, result.get("sha256").getAsString().length());
        assertFalse(result.has("base64"));
    }

    // -----------------------------------------------------------------------
    // Traces
    // -----------------------------------------------------------------------

    @Test
    public void traceLifecycleIsCorrelatedToTheHarnessActionId() {
        JsonObject start = new JsonObject();
        start.addProperty("action_id", "scenario-1:step-3:click-ok");
        JsonObject started = ok(handler.handle("start_ui_trace", start, true, null));
        String traceId = started.get("trace_id").getAsString();
        assertEquals("scenario-1:step-3:click-ok", started.get("action_id").getAsString());

        JsonObject metrics = new JsonObject();
        metrics.addProperty("trace_id", traceId);
        assertTrue(ok(handler.handle("get_ui_metrics", metrics, true, null))
                .get("open").getAsBoolean());

        JsonObject stop = new JsonObject();
        stop.addProperty("trace_id", traceId);
        stop.addProperty("phase", "terminal_state_observed");
        JsonObject stopped = ok(handler.handle("stop_ui_trace", stop, true, null));
        assertFalse(stopped.get("open").getAsBoolean());
        assertEquals("scenario-1:step-3:click-ok", stopped.get("action_id").getAsString());
        assertEquals(1, stopped.getAsJsonArray("phases").size());
        assertTrue(stopped.has("max_edt_delay_ms"));
        assertTrue(stopped.has("edt_queue_ms_max"));

        assertEquals(AutomationCommandHandler.ERR_TRACE_UNKNOWN,
                errorCode(handler.handle("stop_ui_trace", stop, true, null)));
    }

    @Test
    public void startingATraceWithoutAnActionIdIsRefused() {
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("start_ui_trace", new JsonObject(), true, null)));
    }

    @Test
    public void sessionWideMetricsAreAvailableWithoutATrace() {
        JsonObject summary = ok(handler.handle("get_ui_metrics", new JsonObject(),
                true, null));
        assertTrue(summary.has("instrumented"));
        assertTrue(summary.has("max_edt_delay_ms"));
        assertEquals(0, summary.get("open_traces").getAsInt());
    }

    @Test
    public void aPollIsAnsweredBeforeFreshRequestFieldsAreValidated() {
        // A poll carries only operation_id. If the handler validated node_id
        // and generation first, a client that timed out could never collect its
        // result — and the only other way to get one would be resubmitting the
        // mutation, which is exactly what the contract forbids.
        final java.util.List<String> polled = new java.util.ArrayList<String>();
        AutomationCommandHandler.Host host = new AutomationCommandHandler.Host() {
            @Override public JsonObject pollExistingOperation(String command) {
                polled.add(command);
                JsonObject response = new JsonObject();
                response.addProperty("ok", true);
                response.add("result", new JsonObject());
                return response;
            }

            @Override public JsonObject submitEdtOperation(
                    String command, java.util.function.Supplier<JsonObject> work,
                    long timeoutMs) {
                throw new AssertionError("a poll must not resubmit the work");
            }
        };

        JsonObject poll = new JsonObject();
        poll.addProperty("operation_id", "edt_9Qm2xJ0pLc7hVb1sNt4uYzA");
        assertTrue(handler.handle("perform_ui_action", poll, true, host)
                .get("ok").getAsBoolean());
        assertTrue(handler.handle("capture_ui", poll, true, host)
                .get("ok").getAsBoolean());
        assertEquals(java.util.Arrays.asList("perform_ui_action", "capture_ui"),
                polled);
    }

    @Test
    public void anUnknownAutomationCommandIsAValidationFailure() {
        assertEquals(AutomationCommandHandler.ERR_INVALID,
                errorCode(handler.handle("delete_everything", new JsonObject(),
                        true, null)));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private JsonObject ok(JsonObject response) {
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        return response.getAsJsonObject("result");
    }

    private static String errorCode(JsonObject response) {
        assertFalse(response.toString(), response.get("ok").getAsBoolean());
        return response.getAsJsonObject("error").get("code").getAsString();
    }

    private String idOf(String label) {
        JsonObject tree = ok(handler.handle("get_ui_tree", new JsonObject(), true, null));
        for (JsonElement window : tree.getAsJsonArray("windows")) {
            JsonObject found = search(window.getAsJsonObject(), label);
            if (found != null) return found.get("id").getAsString();
        }
        throw new AssertionError("no node labelled " + label);
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

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (value.equals(element.getAsString())) return true;
        }
        return false;
    }
}
