package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import imagejai.engine.EventBus;

import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The gated command surface of the test automation bridge.
 *
 * <p>Everything the external harness can reach in-process goes through here.
 * The handler owns request validation, the two-key gate (startup policy plus a
 * negotiated authenticated capability), bounds, and structured errors; the
 * services own the Swing work. Keeping it in this package is deliberate: the
 * TCP server is already eleven thousand lines and only needs to delegate.</p>
 *
 * <h2>Semantic versus physical</h2>
 * <p>Every action here is <em>semantic</em>: it calls the component's own API on
 * the event thread. That proves the listener path, the model update, and the
 * resulting state, and it is deterministic. It does <em>not</em> prove focus,
 * hit-testing, z-order, or that a real pointer could reach the control. Those
 * require the external harness's physical input channel. The bridge never
 * synthesises native mouse or keyboard events, and never accepts a desktop
 * coordinate.</p>
 */
public final class AutomationCommandHandler {

    public static final String ERR_DISABLED = "test_automation_disabled";
    public static final String ERR_INVALID = "invalid_request";
    public static final String ERR_TRACE_UNKNOWN = "ui_trace_unknown";
    public static final String ERR_TRACE_CAPACITY = "ui_trace_capacity";
    public static final String ERR_EDT_TIMEOUT = "ui_edt_timeout";

    public static final String REASON_CAP_NOT_NEGOTIATED = "capability_not_negotiated";

    /**
     * The parts of the request lifecycle the TCP server owns: EDT operation
     * admission with its poll-don't-replay contract, and operation polling.
     */
    public interface Host {
        /**
         * Poll an operation this request refers to by {@code operation_id}.
         *
         * @return the poll response, or {@code null} when the request is not a poll
         */
        JsonObject pollExistingOperation(String command);

        /**
         * Run {@code work} on the event thread through the session-owned EDT
         * operation registry. A caller that times out receives
         * {@code operation_in_progress} plus an {@code operation_id} to poll;
         * the mutation is never resubmitted.
         */
        JsonObject submitEdtOperation(String command, Supplier<JsonObject> work,
                                      long timeoutMs);
    }

    private final AutomationPolicy policy;
    private final UiAutomationService service;
    private final UiIdleMonitor idleMonitor;
    private final UiCaptureService captureService;
    private final UiPerformanceMonitor performanceMonitor;
    private final EventBus eventBus;

    public AutomationCommandHandler(AutomationPolicy policy, UiAutomationService service,
                                    UiIdleMonitor idleMonitor,
                                    UiCaptureService captureService,
                                    UiPerformanceMonitor performanceMonitor,
                                    EventBus eventBus) {
        this.policy = policy == null ? AutomationPolicy.disabled(null) : policy;
        this.service = service;
        this.idleMonitor = idleMonitor;
        this.captureService = captureService;
        this.performanceMonitor = performanceMonitor;
        this.eventBus = eventBus;
    }

    public AutomationPolicy policy() { return policy; }
    public UiAutomationService service() { return service; }
    public UiPerformanceMonitor performanceMonitor() { return performanceMonitor; }

    /** True when {@code command} belongs to the gated automation surface. */
    public static boolean handles(String command) {
        return command != null && AutomationPolicy.COMMANDS.contains(command);
    }

    /**
     * Handle one gated command.
     *
     * @param capabilityGranted whether this authenticated session negotiated
     *                          {@code capabilities.test_automation=true}
     */
    public JsonObject handle(String command, JsonObject request,
                             boolean capabilityGranted, Host host) {
        if (!policy.isEnabled()) return disabled(policy.disabledReason());
        if (!capabilityGranted) return disabled(REASON_CAP_NOT_NEGOTIATED);
        if (request == null) return invalid("A request object is required.");
        if (service == null) return disabled("bridge_not_initialised");

        try {
            if ("get_ui_tree".equals(command)) return getUiTree(request);
            if ("get_ui_component".equals(command)) return getUiComponent(request);
            if ("perform_ui_action".equals(command)) return performUiAction(request, host);
            if ("wait_for_ui_state".equals(command)) return waitForUiState(request);
            if ("wait_for_ui_idle".equals(command)) return waitForUiIdle(request);
            if ("capture_ui".equals(command)) return captureUi(request, host);
            if ("start_ui_trace".equals(command)) return startTrace(request);
            if ("stop_ui_trace".equals(command)) return stopTrace(request);
            if ("get_ui_metrics".equals(command)) return getMetrics(request);
            return invalid("Unknown automation command: " + command);
        } catch (UiAutomationService.EdtTimeoutException timeout) {
            return error(ERR_EDT_TIMEOUT, timeout.getMessage(), "operation", false, null);
        } catch (RuntimeException failure) {
            return error("ui_internal_error",
                    "The bridge failed to complete the command: "
                            + failure.getClass().getSimpleName(),
                    "internal", false, null);
        }
    }

    /** Capability descriptor published in the {@code hello} reply when granted. */
    public JsonObject capabilityDescriptor() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        json.addProperty("instance_id", policy.instanceId());
        json.addProperty("workspace_id", policy.workspaceId());
        json.addProperty("pid", AutomationReadyFile.currentPid());
        JsonArray commands = new JsonArray();
        for (String command : AutomationPolicy.COMMANDS) {
            commands.add(new JsonPrimitive(command));
        }
        json.add("commands", commands);
        JsonArray actions = new JsonArray();
        for (String action : new String[] {
                UiNode.ACTION_FOCUS, UiNode.ACTION_ACTIVATE, UiNode.ACTION_SET_TEXT,
                UiNode.ACTION_SET_SELECTED, UiNode.ACTION_SET_NUMBER,
                UiNode.ACTION_SELECT_ITEM, UiNode.ACTION_SELECT_TAB,
                UiNode.ACTION_SELECT_ROW, UiNode.ACTION_EXPAND,
                UiNode.ACTION_COLLAPSE, UiNode.ACTION_CLOSE_WINDOW}) {
            actions.add(new JsonPrimitive(action));
        }
        json.add("semantic_actions", actions);
        json.addProperty("physical_input", false);
        JsonObject limits = new JsonObject();
        limits.addProperty("max_tree_nodes", AutomationPolicy.MAX_TREE_NODES);
        limits.addProperty("max_tree_depth", AutomationPolicy.MAX_TREE_DEPTH);
        limits.addProperty("max_text_chars", AutomationPolicy.MAX_TEXT_CHARS);
        limits.addProperty("max_items_per_node", AutomationPolicy.MAX_ITEMS_PER_NODE);
        limits.addProperty("max_command_timeout_ms", AutomationPolicy.MAX_COMMAND_TIMEOUT_MS);
        limits.addProperty("max_wait_timeout_ms", AutomationPolicy.MAX_WAIT_TIMEOUT_MS);
        limits.addProperty("max_capture_dimension", AutomationPolicy.MAX_CAPTURE_DIMENSION);
        limits.addProperty("max_capture_bytes", AutomationPolicy.MAX_CAPTURE_BYTES);
        limits.addProperty("max_open_traces", AutomationPolicy.MAX_OPEN_TRACES);
        limits.addProperty("max_retained_traces", AutomationPolicy.MAX_RETAINED_TRACES);
        json.add("limits", limits);
        json.addProperty("instrumented_event_queue",
                performanceMonitor != null && performanceMonitor.isInstrumented());
        return json;
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    private JsonObject getUiTree(JsonObject request)
            throws UiAutomationService.EdtTimeoutException {
        UiAutomationService.SnapshotOptions options =
                new UiAutomationService.SnapshotOptions();
        String windowId = optString(request, "window_id", null);
        if (windowId != null && !UiIdentityRegistry.looksLikeId(windowId)) {
            return invalid("window_id must be an opaque id issued by get_ui_tree.");
        }
        options.windowId(windowId);
        options.includeHidden(optBool(request, "include_hidden", false));
        options.maxNodes(optInt(request, "max_nodes", AutomationPolicy.MAX_TREE_NODES));
        options.maxDepth(optInt(request, "max_depth", AutomationPolicy.MAX_TREE_DEPTH));
        long timeoutMs = commandTimeout(request);
        UiTreeSnapshot snapshot = service.snapshot(options, timeoutMs);
        JsonObject result = snapshot.toJson();
        if (windowId != null && snapshot.windows().isEmpty()) {
            return error(UiAutomationService.ERR_UNKNOWN,
                    "No showing window has that id; take a fresh get_ui_tree snapshot.",
                    "state", true, detail("window_id", windowId));
        }
        return success(result);
    }

    private JsonObject getUiComponent(JsonObject request)
            throws UiAutomationService.EdtTimeoutException {
        String nodeId = optString(request, "node_id", optString(request, "window_id", null));
        if (nodeId == null) return invalid("node_id (or window_id) is required.");
        long generation = optLong(request, "generation", -1L);
        UiAutomationService.Resolution resolution = service.resolve(nodeId, generation);
        if (!resolution.isOk()) return fromResolution(resolution, generation);
        boolean includeChildren = optBool(request, "include_children", false);
        JsonObject node = resolution.isMenuTarget()
                ? service.describeMenu(resolution.menuComponent(), commandTimeout(request))
                : service.describe(resolution.component(), includeChildren,
                        commandTimeout(request));
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        result.addProperty("generation", service.identities().generation());
        result.add("node", node);
        return success(result);
    }

    private JsonObject performUiAction(JsonObject request, Host host) {
        if (host != null) {
            JsonObject poll = host.pollExistingOperation("perform_ui_action");
            if (poll != null) return poll;
        }
        final String nodeId = optString(request, "node_id", null);
        if (nodeId == null) return invalid("node_id is required.");
        if (!request.has("generation")) {
            return invalid("generation is required for a mutation; echo the value "
                    + "from the get_ui_tree snapshot you targeted.");
        }
        final long generation = optLong(request, "generation", -1L);
        if (generation < 0L) return invalid("generation must be a non-negative integer.");
        final String action = optString(request, "action", null);
        if (action == null) return invalid("action is required.");

        UiAutomationService.Resolution resolution = service.resolve(nodeId, generation);
        if (!resolution.isOk()) return fromResolution(resolution, generation);

        final Component component = resolution.component();
        final java.awt.MenuComponent menuComponent = resolution.menuComponent();
        final Window window = resolution.window();
        final JsonElement value = request.get("value");
        final Integer index = request.has("index")
                ? Integer.valueOf(optInt(request, "index", 0)) : null;
        final UiAutomationService.ActionRequest actionRequest =
                new UiAutomationService.ActionRequest(action, value, index);
        final String traceId = optString(request, "trace_id", null);
        final long submittedAtNanos = System.nanoTime();
        long timeoutMs = commandTimeout(request);

        Supplier<JsonObject> work = new Supplier<JsonObject>() {
            @Override public JsonObject get() {
                mark(traceId, "action_dispatched");
                JsonObject response = menuComponent != null
                        ? service.performMenuAction(menuComponent, window,
                                actionRequest, generation, submittedAtNanos)
                        : service.performAction(component, window,
                                actionRequest, generation, submittedAtNanos);
                mark(traceId, "action_completed");
                return response;
            }
        };
        if (host == null) {
            // Direct in-process use (tests, embedded callers): the work still
            // has to reach the event thread, just without operation handoff.
            try {
                return service.callOnEdt(work, timeoutMs);
            } catch (UiAutomationService.EdtTimeoutException timeout) {
                return error(ERR_EDT_TIMEOUT, timeout.getMessage(), "operation", false, null);
            }
        }
        return host.submitEdtOperation("perform_ui_action", work, timeoutMs);
    }

    private JsonObject waitForUiState(JsonObject request)
            throws UiAutomationService.EdtTimeoutException {
        JsonElement predicateElement = request.get("predicate");
        if (predicateElement == null || !predicateElement.isJsonObject()
                || predicateElement.getAsJsonObject().size() == 0) {
            return invalid("predicate must be a non-empty object.");
        }
        final JsonObject predicate = predicateElement.getAsJsonObject();
        final String nodeId = optString(request, "node_id", null);
        final String windowId = optString(request, "window_id", null);
        if (nodeId != null && windowId != null) {
            return invalid("Supply node_id or window_id, not both.");
        }
        long timeoutMs = Math.max(1L, Math.min(AutomationPolicy.MAX_WAIT_TIMEOUT_MS,
                optLong(request, "timeout_ms", 5_000L)));
        long pollMs = Math.max(10L, Math.min(1_000L,
                optLong(request, "poll_interval_ms", 50L)));
        long readTimeoutMs = commandTimeout(request);

        long startedAtNanos = System.nanoTime();
        long deadlineNanos = startedAtNanos + timeoutMs * 1_000_000L;
        int polls = 0;
        JsonObject lastObserved = new JsonObject();
        List<String> unsatisfied = new ArrayList<String>();

        while (true) {
            polls++;
            final String targetId = nodeId != null ? nodeId : windowId;
            JsonObject evaluation = evaluate(targetId, predicate, readTimeoutMs);
            lastObserved = evaluation.getAsJsonObject("observed");
            unsatisfied = stringList(evaluation.getAsJsonArray("unsatisfied"));
            if (unsatisfied.isEmpty()) {
                return success(waitResult(true, startedAtNanos, polls, lastObserved,
                        unsatisfied, evaluation));
            }
            if (System.nanoTime() >= deadlineNanos) {
                return success(waitResult(false, startedAtNanos, polls, lastObserved,
                        unsatisfied, evaluation));
            }
            try {
                Thread.sleep(Math.min(pollMs,
                        Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                unsatisfied.add("interrupted");
                return success(waitResult(false, startedAtNanos, polls, lastObserved,
                        unsatisfied, evaluation));
            }
        }
    }

    private JsonObject waitForUiIdle(JsonObject request) {
        if (idleMonitor == null) {
            return error("ui_idle_unavailable",
                    "The idle monitor is not running.", "state", false, null);
        }
        long timeoutMs = Math.max(1L, Math.min(AutomationPolicy.MAX_WAIT_TIMEOUT_MS,
                optLong(request, "timeout_ms", 5_000L)));
        long quietMs = optLong(request, "quiet_ms", UiIdleMonitor.DEFAULT_QUIET_MS);
        boolean requirePaintQuiet = optBool(request, "require_paint_quiet", false);
        String traceId = optString(request, "trace_id", null);
        mark(traceId, "idle_wait_started");
        JsonObject result = idleMonitor.awaitIdle(timeoutMs, quietMs, requirePaintQuiet);
        mark(traceId, result.get("idle").getAsBoolean() ? "idle_reached" : "idle_timeout");
        publish("ui.idle", idleEvent(result));
        return success(result);
    }

    private JsonObject captureUi(JsonObject request, Host host) {
        if (captureService == null) {
            return error(UiCaptureService.ERR_CAPTURE_FAILED,
                    "Capture is not available in this build.", "state", false, null);
        }
        // A poll carries only operation_id, so it has to be answered before the
        // fields a fresh capture requires are validated.
        if (host != null) {
            JsonObject poll = host.pollExistingOperation("capture_ui");
            if (poll != null) return poll;
        }
        final String nodeId = optString(request, "node_id", optString(request, "window_id", null));
        if (nodeId == null) return invalid("node_id (or window_id) is required.");
        if (!request.has("generation")) {
            return invalid("generation is required; echo the value from the "
                    + "get_ui_tree snapshot you targeted.");
        }
        final long generation = optLong(request, "generation", -1L);
        if (generation < 0L) return invalid("generation must be a non-negative integer.");
        UiAutomationService.Resolution resolution = service.resolve(nodeId, generation);
        if (!resolution.isOk()) return fromResolution(resolution, generation);
        if (resolution.isMenuTarget()) {
            // An AWT menu is painted by the window manager, not by Swing, so
            // there is nothing this process can render. Refusing is honest; a
            // blank or wrongly-sized bitmap would be treated as evidence.
            return error(UiAutomationService.ERR_UNSUPPORTED,
                    "An AWT menu is drawn outside the JVM and cannot be rendered "
                            + "in process; capture the owning window instead.",
                    "state", false, detail("node_id", nodeId));
        }

        final Component component = resolution.component();
        final Window window = resolution.window();
        final int maxDimension = optInt(request, "max_dimension",
                AutomationPolicy.MAX_CAPTURE_DIMENSION);
        final boolean includeBytes = optBool(request, "include_bytes", true);
        final String windowId = window == null ? ""
                : service.identities().windowId(window);
        final String resolvedNodeId = service.identities().nodeId(component);

        Supplier<JsonObject> work = new Supplier<JsonObject>() {
            @Override public JsonObject get() {
                return captureService.capture(component, resolvedNodeId, windowId,
                        generation, maxDimension, includeBytes);
            }
        };
        long timeoutMs = commandTimeout(request);
        if (host == null) {
            try {
                return service.callOnEdt(work, timeoutMs);
            } catch (UiAutomationService.EdtTimeoutException timeout) {
                return error(ERR_EDT_TIMEOUT, timeout.getMessage(), "operation", false, null);
            }
        }
        // Rendering does not mutate the UI, but it occupies the event thread for
        // as long as the component takes to paint, so it uses the same bounded
        // admission and poll contract as an action.
        return host.submitEdtOperation("capture_ui", work, timeoutMs);
    }

    private JsonObject startTrace(JsonObject request) {
        if (performanceMonitor == null) {
            return error("ui_trace_unavailable",
                    "Performance tracing is not available.", "state", false, null);
        }
        String actionId = optString(request, "action_id", null);
        if (actionId == null || actionId.trim().isEmpty()) {
            return invalid("action_id is required so the harness can correlate the trace.");
        }
        if (actionId.length() > 128) return invalid("action_id must be 128 chars or fewer.");
        UiPerformanceMonitor.Trace trace = performanceMonitor.start(actionId);
        if (trace == null) {
            return error(ERR_TRACE_CAPACITY, "At most "
                    + AutomationPolicy.MAX_OPEN_TRACES
                    + " traces may be open; stop one first.", "capacity", true, null);
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        result.addProperty("trace_id", trace.traceId());
        result.addProperty("action_id", trace.actionId());
        result.addProperty("instrumented", performanceMonitor.isInstrumented());
        result.addProperty("open_traces", performanceMonitor.openCount());
        return success(result);
    }

    private JsonObject stopTrace(JsonObject request) {
        if (performanceMonitor == null) {
            return error("ui_trace_unavailable",
                    "Performance tracing is not available.", "state", false, null);
        }
        String traceId = optString(request, "trace_id", null);
        if (traceId == null) return invalid("trace_id is required.");
        String phase = optString(request, "phase", null);
        if (phase != null) performanceMonitor.mark(traceId, phase);
        UiPerformanceMonitor.Trace trace = performanceMonitor.stop(traceId);
        if (trace == null) {
            return error(ERR_TRACE_UNKNOWN, "No open trace with that id.",
                    "state", true, detail("trace_id", traceId));
        }
        return success(trace.toJson());
    }

    private JsonObject getMetrics(JsonObject request) {
        if (performanceMonitor == null) {
            return error("ui_trace_unavailable",
                    "Performance tracing is not available.", "state", false, null);
        }
        String traceId = optString(request, "trace_id", null);
        if (traceId == null) return success(performanceMonitor.summaryJson());
        UiPerformanceMonitor.Trace trace = performanceMonitor.trace(traceId);
        if (trace == null) {
            return error(ERR_TRACE_UNKNOWN, "No open or retained trace with that id.",
                    "state", true, detail("trace_id", traceId));
        }
        return success(trace.toJson());
    }

    // -----------------------------------------------------------------------
    // Predicate evaluation
    // -----------------------------------------------------------------------

    /**
     * Evaluate one predicate pass. Returns
     * {@code {observed: {...}, unsatisfied: [...], generation: n}}.
     *
     * <p>Supported keys, all ANDed:</p>
     * <ul>
     *   <li>node/window targets: {@code exists}, {@code showing}, {@code enabled},
     *       {@code focused}, {@code selected}, {@code text_equals},
     *       {@code value_equals}, {@code numeric_equals}, {@code selected_index},
     *       {@code window_closed}</li>
     *   <li>no target: {@code window_present} —
     *       {@code {title_equals|title_contains|role}}</li>
     * </ul>
     */
    JsonObject evaluate(final String targetId, final JsonObject predicate,
                        long readTimeoutMs) throws UiAutomationService.EdtTimeoutException {
        return service.callOnEdt(new Supplier<JsonObject>() {
            @Override public JsonObject get() {
                return evaluateNow(targetId, predicate);
            }
        }, readTimeoutMs);
    }

    JsonObject evaluateNow(String targetId, JsonObject predicate) {
        JsonObject observed = new JsonObject();
        JsonArray unsatisfied = new JsonArray();

        if (predicate.has("window_present")) {
            JsonElement spec = predicate.get("window_present");
            JsonObject match = matchWindow(spec);
            observed.add("window_present", match);
            if (!match.get("matched").getAsBoolean()) {
                unsatisfied.add(new JsonPrimitive("window_present"));
            }
        }

        boolean needsTarget = predicate.size() > (predicate.has("window_present") ? 1 : 0);
        if (needsTarget) {
            if (targetId == null) {
                unsatisfied.add(new JsonPrimitive("node_id_required"));
            } else {
                UiAutomationService.Resolution resolution = service.resolve(targetId, -1L);
                boolean exists = resolution.isOk();
                observed.addProperty("exists", exists);
                if (predicate.has("exists")) {
                    Boolean wanted = UiAutomationService.asBoolean(predicate.get("exists"));
                    if (wanted != null && wanted.booleanValue() != exists) {
                        unsatisfied.add(new JsonPrimitive("exists"));
                    }
                }
                if (predicate.has("window_closed")) {
                    Boolean wanted =
                            UiAutomationService.asBoolean(predicate.get("window_closed"));
                    boolean closed = !exists || resolution.window() == null
                            || !resolution.window().isShowing();
                    observed.addProperty("window_closed", closed);
                    if (wanted != null && wanted.booleanValue() != closed) {
                        unsatisfied.add(new JsonPrimitive("window_closed"));
                    }
                }
                if (exists) {
                    JsonObject state = resolution.isMenuTarget()
                            ? service.semanticMenuNow(resolution.menuComponent())
                            : service.semanticNow(resolution.component());
                    for (java.util.Map.Entry<String, JsonElement> entry : state.entrySet()) {
                        observed.add(entry.getKey(), entry.getValue());
                    }
                    checkBoolean(predicate, state, "showing", unsatisfied);
                    checkBoolean(predicate, state, "enabled", unsatisfied);
                    checkBoolean(predicate, state, "focused", unsatisfied);
                    checkBoolean(predicate, state, "selected", unsatisfied);
                    checkString(predicate, state, "text_equals", "text", unsatisfied);
                    checkString(predicate, state, "value_equals", "value", unsatisfied);
                    checkNumber(predicate, state, "numeric_equals", "numeric_value",
                            unsatisfied);
                    checkNumber(predicate, state, "selected_index", "selected_index",
                            unsatisfied);
                } else {
                    for (String key : new String[] {"showing", "enabled", "focused",
                            "selected", "text_equals", "value_equals", "numeric_equals",
                            "selected_index"}) {
                        if (predicate.has(key)) unsatisfied.add(new JsonPrimitive(key));
                    }
                }
            }
        }

        JsonObject evaluation = new JsonObject();
        evaluation.add("observed", observed);
        evaluation.add("unsatisfied", unsatisfied);
        evaluation.addProperty("generation", service.identities().generation());
        return evaluation;
    }

    private JsonObject matchWindow(JsonElement spec) {
        JsonObject match = new JsonObject();
        JsonObject criteria = spec != null && spec.isJsonObject()
                ? spec.getAsJsonObject() : new JsonObject();
        String titleEquals = optString(criteria, "title_equals", null);
        String titleContains = optString(criteria, "title_contains", null);
        String role = optString(criteria, "role", null);
        boolean requireShowing = optBool(criteria, "showing", true);

        UiAutomationService.SnapshotOptions options =
                new UiAutomationService.SnapshotOptions().includeHidden(!requireShowing)
                        .maxNodes(1).maxDepth(1);
        UiTreeSnapshot snapshot = service.buildSnapshot(options);
        JsonArray matches = new JsonArray();
        for (UiTreeSnapshot.WindowEntry entry : snapshot.windows()) {
            JsonObject window = entry.toJson();
            String title = window.has("title") ? window.get("title").getAsString() : "";
            String windowRole = window.get("role").getAsString();
            if (titleEquals != null && !titleEquals.equals(title)) continue;
            if (titleContains != null && !title.contains(titleContains)) continue;
            if (role != null && !role.equals(windowRole)) continue;
            JsonObject summary = new JsonObject();
            summary.addProperty("window_id", window.get("id").getAsString());
            summary.addProperty("role", windowRole);
            summary.addProperty("title", title);
            summary.addProperty("owner", window.has("owner")
                    ? window.get("owner").getAsString() : UiAutomationService.OWNER_OTHER);
            matches.add(summary);
        }
        match.addProperty("matched", matches.size() > 0);
        match.addProperty("match_count", matches.size());
        match.addProperty("ambiguous", matches.size() > 1);
        match.add("matches", matches);
        return match;
    }

    private static void checkBoolean(JsonObject predicate, JsonObject state, String key,
                                     JsonArray unsatisfied) {
        if (!predicate.has(key)) return;
        Boolean wanted = UiAutomationService.asBoolean(predicate.get(key));
        JsonElement actual = state.get(key);
        boolean actualValue = actual != null && actual.isJsonPrimitive()
                && actual.getAsJsonPrimitive().isBoolean() && actual.getAsBoolean();
        if (wanted == null || wanted.booleanValue() != actualValue) {
            unsatisfied.add(new JsonPrimitive(key));
        }
    }

    private static void checkString(JsonObject predicate, JsonObject state,
                                    String predicateKey, String stateKey,
                                    JsonArray unsatisfied) {
        if (!predicate.has(predicateKey)) return;
        String wanted = UiAutomationService.asString(predicate.get(predicateKey));
        JsonElement actual = state.get(stateKey);
        String actualValue = actual != null && actual.isJsonPrimitive()
                ? actual.getAsString() : null;
        if (wanted == null || !wanted.equals(actualValue)) {
            unsatisfied.add(new JsonPrimitive(predicateKey));
        }
    }

    private static void checkNumber(JsonObject predicate, JsonObject state,
                                    String predicateKey, String stateKey,
                                    JsonArray unsatisfied) {
        if (!predicate.has(predicateKey)) return;
        Double wanted = UiAutomationService.asDouble(predicate.get(predicateKey));
        JsonElement actual = state.get(stateKey);
        Double actualValue = actual != null && actual.isJsonPrimitive()
                ? UiAutomationService.asDouble(actual) : null;
        if (wanted == null || actualValue == null
                || Math.abs(wanted.doubleValue() - actualValue.doubleValue()) > 1e-9d) {
            unsatisfied.add(new JsonPrimitive(predicateKey));
        }
    }

    private JsonObject waitResult(boolean satisfied, long startedAtNanos, int polls,
                                  JsonObject observed, List<String> unsatisfied,
                                  JsonObject evaluation) {
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        result.addProperty("satisfied", satisfied);
        result.addProperty("waited_ms",
                UiAutomationService.millisBetween(startedAtNanos, System.nanoTime()));
        result.addProperty("polls", polls);
        result.addProperty("generation", evaluation.get("generation").getAsLong());
        result.add("observed", observed == null ? new JsonObject() : observed);
        JsonArray remaining = new JsonArray();
        for (String key : unsatisfied) remaining.add(new JsonPrimitive(key));
        result.add("unsatisfied", remaining);
        return result;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void mark(String traceId, String phase) {
        if (traceId == null || performanceMonitor == null) return;
        performanceMonitor.mark(traceId, phase);
    }

    private void publish(String topic, JsonObject data) {
        if (eventBus == null) return;
        try {
            eventBus.publish(topic, data);
        } catch (Throwable ignored) {
            // Telemetry never breaks a command.
        }
    }

    private static JsonObject idleEvent(JsonObject idleResult) {
        JsonObject data = new JsonObject();
        data.addProperty("idle", idleResult.get("idle").getAsBoolean());
        data.addProperty("waited_ms", idleResult.get("waited_ms").getAsDouble());
        data.addProperty("blocker_count",
                idleResult.getAsJsonArray("blockers").size());
        return data;
    }

    private JsonObject fromResolution(UiAutomationService.Resolution resolution,
                                      long requestedGeneration) {
        JsonObject details = new JsonObject();
        details.addProperty("requested_generation", requestedGeneration);
        details.addProperty("current_generation", service.identities().generation());
        String category = UiAutomationService.ERR_INVALID.equals(resolution.errorCode())
                ? "validation" : "state";
        boolean retrySafe = !UiAutomationService.ERR_NOT_ACTIONABLE
                .equals(resolution.errorCode());
        return error(resolution.errorCode(), resolution.errorMessage(), category,
                retrySafe, details);
    }

    private long commandTimeout(JsonObject request) {
        long requested = optLong(request, "timeout_ms",
                AutomationPolicy.DEFAULT_COMMAND_TIMEOUT_MS);
        if (requested <= 0L) return AutomationPolicy.MAX_COMMAND_TIMEOUT_MS;
        return Math.min(AutomationPolicy.MAX_COMMAND_TIMEOUT_MS, requested);
    }

    /**
     * The one error a disabled bridge ever produces. It names the gate that
     * refused and nothing else — no path, no workspace, no token state.
     */
    public JsonObject disabled(String reason) {
        JsonObject details = new JsonObject();
        details.addProperty("reason", reason == null ? "unknown" : reason);
        details.addProperty("required_property", AutomationPolicy.PROP_ENABLED);
        details.addProperty("required_capability", AutomationPolicy.CAPABILITY);
        return error(ERR_DISABLED,
                "Test automation is not enabled for this Fiji instance and session.",
                "authorization", false, details);
    }

    static JsonObject invalid(String message) {
        return error(ERR_INVALID, message, "validation", true, null);
    }

    static JsonObject error(String code, String message, String category,
                            boolean retrySafe, JsonObject details) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "" : message);
        error.addProperty("category", category);
        error.addProperty("retry_safe", retrySafe);
        if (details != null) error.add("details", details);
        response.add("error", error);
        return response;
    }

    static JsonObject success(JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("result", result);
        return response;
    }

    private static JsonObject detail(String key, String value) {
        JsonObject details = new JsonObject();
        details.addProperty(key, value);
        return details;
    }

    private static List<String> stringList(JsonArray array) {
        List<String> values = new ArrayList<String>();
        if (array == null) return values;
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (object == null) return fallback;
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String value = element.getAsString();
        return value.isEmpty() ? fallback : value;
    }

    private static boolean optBool(JsonObject object, String key, boolean fallback) {
        if (object == null) return fallback;
        Boolean value = UiAutomationService.asBoolean(object.get(key));
        return value == null ? fallback : value.booleanValue();
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        if (object == null) return fallback;
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException notNumeric) {
            return fallback;
        }
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        if (object == null) return fallback;
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException notNumeric) {
            return fallback;
        }
    }
}
