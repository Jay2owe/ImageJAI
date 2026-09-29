// Live smoke test for the ImageJ-AI test automation bridge.
//
// Runs the bridge the way a harness will: real startup system properties, real
// AutomationPolicy parsing, real ready file, real instrumented event queue, and
// a real ij.gui.GenericDialog driven over a real loopback socket.
//
// It never touches the user's Fiji installation or home directory: the caller
// must pass an isolated -Duser.home and an isolated workspace. See the header
// of test-scripts/automation_bridge_smoke.ps1 for the exact invocation.
//
// Usage (from the project root, after `mvn test-compile`):
//   java -cp <test classpath> \
//        -Duser.home=<temp home> \
//        -Dimagejai.testAutomation.enabled=true \
//        -Dimagejai.testAutomation.workspace=<temp workspace> \
//        -Dimagejai.testAutomation.readyFile=<temp workspace>/ready.json \
//        -Dimagejai.testAutomation.port=0 \
//        test-scripts/automation_bridge_smoke.java

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import imagejai.engine.TCPCommandServer;
import imagejai.engine.automation.AutomationPolicy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

public class automation_bridge_smoke {

    private static int port;
    private static String token;
    private static String session;
    private static int checks;

    public static void main(String[] args) throws Exception {
        AutomationPolicy policy = AutomationPolicy.current();
        require(policy.isEnabled(),
                "policy armed from real startup properties (" + policy.disabledReason() + ")");

        TCPCommandServer server = new TCPCommandServer(
                policy.requestedPort() >= 0 ? policy.requestedPort() : 0,
                null, null, null, null);
        server.start(null);
        for (int i = 0; i < 200 && !server.isRunning(); i++) Thread.sleep(25L);
        require(server.isRunning(), "TCP server bound on loopback");
        port = server.getPort();

        try {
            JsonObject ready = readReadyFile(policy.readyFile());
            require(ready.get("port").getAsInt() == port,
                    "ready file reports the actual bound port " + port);
            require(ready.get("instance_id").getAsString().equals(policy.instanceId()),
                    "ready file carries this instance's identity");
            require(!ready.toString().contains("token"),
                    "ready file contains no credential");

            token = new String(Files.readAllBytes(Paths.get(
                    System.getProperty("user.home"), ".imagejai", "server-token")),
                    StandardCharsets.UTF_8).trim();
            require(token.length() >= 32, "isolated installation token loaded");

            JsonObject refused = call(request("get_ui_tree"));
            require("session_required".equals(errorCode(refused)),
                    "unauthenticated get_ui_tree refused");

            JsonObject hello = hello(false);
            require(!enabled(hello).contains(AutomationPolicy.CAPABILITY),
                    "capability absent when the session did not ask");
            session = hello.get("session_id").getAsString();
            require("test_automation_disabled".equals(errorCode(call(request("get_ui_tree")))),
                    "gated command refused without the negotiated capability");

            hello = hello(true);
            session = hello.get("session_id").getAsString();
            JsonObject automation = hello.getAsJsonObject("automation");
            require(automation.get("granted").getAsBoolean(), "capability granted");
            require(AutomationPolicy.PROTOCOL_VERSION.equals(
                    automation.get("protocol_version").getAsString()),
                    "protocol version negotiated: " + AutomationPolicy.PROTOCOL_VERSION);
            require(!automation.get("physical_input").getAsBoolean(),
                    "bridge declares it cannot produce physical input");
            require(automation.get("instrumented_event_queue").getAsBoolean(),
                    "AWT dispatch instrumentation installed");

            // A real ImageJ GenericDialog, on a real AWT peer, driven end to end.
            final AtomicReference<ij.gui.GenericDialog> dialogRef =
                    new AtomicReference<ij.gui.GenericDialog>();
            final AtomicReference<Boolean> cancelled = new AtomicReference<Boolean>();
            final AtomicReference<Double> sigma = new AtomicReference<Double>();
            Thread dialogThread = new Thread(new Runnable() {
                @Override public void run() {
                    ij.gui.GenericDialog gd = new ij.gui.GenericDialog("Smoke Blur");
                    gd.addNumericField("Sigma", 2.0, 1);
                    gd.addCheckbox("Preview", false);
                    gd.addChoice("Method", new String[] {"Otsu", "Li", "Triangle"}, "Otsu");
                    dialogRef.set(gd);
                    gd.showDialog();
                    cancelled.set(gd.wasCanceled());
                    if (!gd.wasCanceled()) sigma.set(gd.getNextNumber());
                }
            }, "smoke-generic-dialog");
            dialogThread.setDaemon(true);
            dialogThread.start();
            for (int i = 0; i < 200 && (dialogRef.get() == null
                    || !dialogRef.get().isShowing()); i++) {
                Thread.sleep(25L);
            }
            require(dialogRef.get() != null && dialogRef.get().isShowing(),
                    "real GenericDialog on screen");

            JsonObject waitForDialog = request("wait_for_ui_state");
            waitForDialog.add("predicate", JsonParser.parseString(
                    "{\"window_present\":{\"title_equals\":\"Smoke Blur\"}}"));
            waitForDialog.addProperty("timeout_ms", 10_000);
            JsonObject appeared = result(call(waitForDialog));
            require(appeared.get("satisfied").getAsBoolean(),
                    "wait_for_ui_state saw the dialog appear");

            JsonObject tree = result(call(request("get_ui_tree")));
            long generation = tree.get("generation").getAsLong();
            JsonObject dialogWindow = window(tree, "Smoke Blur");
            require(dialogWindow != null, "dialog present in the UI tree");
            require("dialog".equals(dialogWindow.get("role").getAsString())
                    && dialogWindow.get("modal").getAsBoolean(),
                    "dialog classified as a modal dialog");
            require("imagej".equals(dialogWindow.get("owner").getAsString()),
                    "dialog attributed to ImageJ, not to ImageJ-AI");

            JsonObject sigmaField = byRole(dialogWindow, "text");
            require(sigmaField != null && "Sigma".equals(sigmaField.get("label").getAsString()),
                    "numeric field resolved with its exact label");
            JsonObject checkbox = byRole(dialogWindow, "checkbox");
            require(checkbox != null && "Preview".equals(checkbox.get("label").getAsString()),
                    "checkbox resolved with its exact label");
            JsonObject choice = byRole(dialogWindow, "combo");
            require(choice != null && choice.getAsJsonArray("items").size() == 3,
                    "choice resolved with all three options");

            JsonObject trace = result(call(withField(request("start_ui_trace"),
                    "action_id", "smoke:set-sigma")));
            String traceId = trace.get("trace_id").getAsString();

            JsonObject setText = request("perform_ui_action");
            setText.addProperty("node_id", sigmaField.get("id").getAsString());
            setText.addProperty("generation", generation);
            setText.addProperty("action", "set_text");
            setText.addProperty("value", "4.5");
            setText.addProperty("trace_id", traceId);
            JsonObject typed = result(call(setText));
            require(typed.getAsJsonObject("dispatch").get("count").getAsInt() == 1,
                    "set_text dispatched exactly once on the event thread");
            require("4.5".equals(typed.getAsJsonObject("post_state").get("value").getAsString()),
                    "set_text changed the field the plugin will read");

            JsonObject setChecked = request("perform_ui_action");
            setChecked.addProperty("node_id", checkbox.get("id").getAsString());
            setChecked.addProperty("generation", generation);
            setChecked.addProperty("action", "set_selected");
            setChecked.addProperty("value", true);
            require(result(call(setChecked)).getAsJsonObject("post_state")
                    .get("selected").getAsBoolean(), "checkbox selected");

            JsonObject selectItem = request("perform_ui_action");
            selectItem.addProperty("node_id", choice.get("id").getAsString());
            selectItem.addProperty("generation", generation);
            selectItem.addProperty("action", "select_item");
            selectItem.addProperty("value", "Triangle");
            require("Triangle".equals(result(call(selectItem))
                    .getAsJsonObject("post_state").get("value").getAsString()),
                    "choice selected by exact value");

            JsonObject substring = request("perform_ui_action");
            substring.addProperty("node_id", choice.get("id").getAsString());
            substring.addProperty("generation", generation);
            substring.addProperty("action", "select_item");
            substring.addProperty("value", "Tri");
            require("ui_target_not_actionable".equals(errorCode(call(substring))),
                    "substring selection refused");

            JsonObject capture = request("capture_ui");
            capture.addProperty("window_id", dialogWindow.get("id").getAsString());
            capture.addProperty("generation", generation);
            capture.addProperty("include_bytes", false);
            JsonObject captured = result(call(capture));
            require(captured.get("sha256").getAsString().length() == 64
                    && captured.get("byte_length").getAsInt() > 0,
                    "dialog rendered in process (" + captured.get("width").getAsInt()
                            + "x" + captured.get("height").getAsInt() + ")");

            JsonObject okButton = byLabel(dialogWindow, "OK");
            require(okButton != null, "OK button resolved");
            JsonObject click = request("perform_ui_action");
            click.addProperty("node_id", okButton.get("id").getAsString());
            click.addProperty("generation", generation);
            click.addProperty("action", "activate");
            click.addProperty("trace_id", traceId);
            require(result(call(click)).getAsJsonObject("dispatch")
                    .get("count").getAsInt() == 1, "OK activated exactly once");

            dialogThread.join(10_000L);
            require(Boolean.FALSE.equals(cancelled.get()),
                    "GenericDialog completed through its own OK path");
            require(sigma.get() != null && Math.abs(sigma.get() - 4.5) < 1e-9,
                    "the plugin read back the value the bridge typed: " + sigma.get());

            JsonObject idle = request("wait_for_ui_idle");
            idle.addProperty("timeout_ms", 10_000);
            idle.addProperty("quiet_ms", 100);
            idle.addProperty("trace_id", traceId);
            JsonObject settled = result(call(idle));
            require(settled.get("idle").getAsBoolean(),
                    "UI reached idle after the dialog closed");
            require(settled.getAsJsonObject("checks").get("awt_instrumented").getAsBoolean(),
                    "idle used the instrumented dispatch signal");

            JsonObject metrics = result(call(withField(withField(
                    request("stop_ui_trace"), "trace_id", traceId),
                    "phase", "terminal_state_observed")));
            require(metrics.get("awt_events").getAsInt() > 0,
                    "trace recorded " + metrics.get("awt_events").getAsInt()
                            + " AWT events, max queue delay "
                            + metrics.get("edt_queue_ms_max").getAsDouble() + " ms");
            require(metrics.getAsJsonArray("phases").size() >= 3,
                    "trace recorded action and idle phases");

            JsonObject stale = request("perform_ui_action");
            stale.addProperty("node_id", okButton.get("id").getAsString());
            stale.addProperty("generation", generation);
            stale.addProperty("action", "activate");
            require("stale_ui_target".equals(errorCode(call(stale))),
                    "disposed dialog target refused as stale");

            System.out.println();
            System.out.println("SMOKE PASS: " + checks + " checks");
        } finally {
            server.stop();
            Path readyFile = policy.readyFile();
            if (Files.exists(readyFile)) {
                System.out.println("FAIL readiness retired on stop");
                System.exit(1);
            }
            System.out.println("ok   readiness retired on stop");
        }
        System.exit(0);
    }

    // -----------------------------------------------------------------------

    private static JsonObject readReadyFile(Path path) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (Files.isRegularFile(path)) {
                return JsonParser.parseString(new String(
                        Files.readAllBytes(path), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("no ready file at " + path);
    }

    private static JsonObject hello(boolean automation) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("command", "hello");
        request.addProperty("agent", "automation-smoke");
        request.addProperty("token", token);
        JsonObject capabilities = new JsonObject();
        if (automation) capabilities.addProperty("test_automation", true);
        request.add("capabilities", capabilities);
        return result(call(request));
    }

    private static JsonObject request(String command) {
        JsonObject request = new JsonObject();
        request.addProperty("command", command);
        if (session != null) {
            request.addProperty("session_id", session);
            request.addProperty("token", token);
        }
        return request;
    }

    private static JsonObject withField(JsonObject request, String key, String value) {
        request.addProperty(key, value);
        return request;
    }

    private static JsonObject call(JsonObject request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(60_000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(request.toString());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            return JsonParser.parseString(reader.readLine()).getAsJsonObject();
        }
    }

    private static JsonObject result(JsonObject response) {
        if (!response.get("ok").getAsBoolean()) {
            throw new IllegalStateException("command failed: " + response);
        }
        return response.getAsJsonObject("result");
    }

    private static String errorCode(JsonObject response) {
        if (response.get("ok").getAsBoolean()) return "";
        return response.getAsJsonObject("error").get("code").getAsString();
    }

    private static java.util.List<String> enabled(JsonObject hello) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        JsonArray array = hello.getAsJsonArray("enabled");
        for (JsonElement element : array) names.add(element.getAsString());
        return names;
    }

    private static JsonObject window(JsonObject tree, String title) {
        for (JsonElement element : tree.getAsJsonArray("windows")) {
            JsonObject window = element.getAsJsonObject();
            if (window.has("title") && title.equals(window.get("title").getAsString())) {
                return window;
            }
        }
        return null;
    }

    private static JsonObject byRole(JsonObject node, String role) {
        if (role.equals(node.get("role").getAsString())) return node;
        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) {
                JsonObject found = byRole(child.getAsJsonObject(), role);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JsonObject byLabel(JsonObject node, String label) {
        JsonElement candidate = node.get("label");
        if (candidate != null && label.equals(candidate.getAsString())
                && !"label".equals(node.get("role").getAsString())) {
            return node;
        }
        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) {
                JsonObject found = byLabel(child.getAsJsonObject(), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void require(boolean condition, String description) {
        checks++;
        System.out.println((condition ? "ok   " : "FAIL ") + description);
        if (!condition) {
            System.out.println();
            System.out.println("SMOKE FAIL after " + checks + " checks");
            System.exit(1);
        }
    }
}
