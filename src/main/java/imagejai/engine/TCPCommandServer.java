package imagejai.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.measure.Calibration;
import ij.process.ImageStatistics;
import imagejai.config.Constants;
import imagejai.ui.ChatPanelController;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * TCP server that accepts JSON commands from external clients (CLI agents,
 * scripts) and dispatches them to the engine layer.
 * <p>
 * Protocol: each connection sends one JSON command (UTF-8, newline-terminated)
 * and receives one JSON response back, then the connection is closed.
 */
public class TCPCommandServer {

    /**
     * Listener for server lifecycle events, used to show status in the UI.
     */
    public interface ServerListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onClientConnected(String clientInfo);
        void onCommandReceived(String command);
        void onError(String error);
    }

    private static final Gson GSON = new GsonBuilder().create();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MACRO_TIMEOUT_MS = 30000;
    private static final long PIPELINE_TIMEOUT_MS = 60000;

    // Phase 2: event-bus subscription caps.
    private static final int MAX_SUBSCRIBERS = 8;
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 256;
    private static final long SUBSCRIBER_HEARTBEAT_MS = 30_000L;
    private final AtomicInteger activeSubscribers = new AtomicInteger(0);
    private final EventBus eventBus = EventBus.getInstance();
    // Monotonic macro-id counter so TCP-path execute_macro emits a well-formed
    // macro.started/macro.completed pair like CommandEngine does.
    private static final java.util.concurrent.atomic.AtomicLong MACRO_ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Commands treated as pure readers — eligible for hash dedup via the
     * optional {@code if_none_match} request field. Every entry must be a
     * command that does not mutate ImageJ state.
     */
    private static final Set<String> READONLY_COMMANDS = new HashSet<String>(Arrays.asList(
            "ping",
            "get_state",
            "get_image_info",
            "get_log",
            "get_results_table",
            "get_histogram",
            "get_open_windows",
            "get_metadata",
            "get_dialogs",
            "get_state_context",
            "get_progress",
            "get_friction_log",
            "get_friction_patterns",
            "intent_list",
            "job_status",
            "job_list",
            "list_reactive_rules",
            "reactive_stats"
    ));

    private final int port;
    private final CommandEngine commandEngine;
    private final StateInspector stateInspector;
    private final PipelineBuilder pipelineBuilder;
    private final ExplorationEngine explorationEngine;
    private final FrictionLog frictionLog = new FrictionLog();
    private final IntentRouter intentRouter = new IntentRouter();
    private final JobRegistry jobRegistry;
    // Phase 8: reactive rules engine. Subscribes to the bus, fires rule
    // actions in response to matching events. Lifecycle tied to the TCP
    // server — {@link #start} / {@link #stop}.
    private final ReactiveEngine reactiveEngine;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running;
    private ServerListener listener;

    // Cached 3D Viewer universe reference — survives across TCP calls
    private volatile Object cached3DUniverse;

    // Phase 7: GUI_ACTION dispatcher. Constructed eagerly with a null
    // controller so {@link #dispatch} never NPEs; the plugin upgrades it via
    // {@link #setChatPanelController} once the chat panel is built.
    private volatile GuiActionDispatcher guiActionDispatcher = new GuiActionDispatcher(null);

    public TCPCommandServer(int port, CommandEngine commandEngine,
                            StateInspector stateInspector,
                            PipelineBuilder pipelineBuilder,
                            ExplorationEngine explorationEngine) {
        this.port = port;
        this.commandEngine = commandEngine;
        this.stateInspector = stateInspector;
        this.pipelineBuilder = pipelineBuilder;
        this.explorationEngine = explorationEngine;
        this.jobRegistry = new JobRegistry(commandEngine);
        this.reactiveEngine = new ReactiveEngine(
                eventBus, commandEngine, intentRouter, guiActionDispatcher);
    }

    /** Phase 3: expose the job registry (primarily for tests). */
    public JobRegistry getJobRegistry() {
        return jobRegistry;
    }

    /**
     * Start the TCP server on a background daemon thread.
     *
     * @param listener callback for server events (may be null)
     */
    public void start(ServerListener listener) {
        this.listener = listener;
        running = true;

        // Phase 8: start the reactive rules engine alongside the socket so
        // rules fire from the moment the plugin is up, regardless of whether
        // any TCP client ever connects.
        try {
            reactiveEngine.start();
        } catch (Throwable t) {
            System.err.println("[ImageJAI-TCP] Reactive engine start failed: " + t.getMessage());
        }

        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        }, "imagej-ai-tcp-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Stop the server and close the listening socket.
     */
    public void stop() {
        running = false;
        // Phase 8: stop the reactive engine first so it unsubscribes from the
        // bus and tears down the WatchService thread cleanly before the rest
        // of the plugin shuts down.
        try {
            reactiveEngine.stop();
        } catch (Exception e) {
            System.err.println("[ImageJAI-TCP] Error stopping reactive engine: " + e.getMessage());
        }
        // Phase 3: cancel every running async job before tearing down — an
        // orphaned worker thread would otherwise outlive the TCP surface and
        // keep publishing events into a silent bus.
        try {
            jobRegistry.shutdown();
        } catch (Exception e) {
            System.err.println("[ImageJAI-TCP] Error shutting down job registry: " + e.getMessage());
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                System.err.println("[ImageJAI-TCP] Error closing server socket: " + e.getMessage());
            }
        }
        if (listener != null) {
            listener.onServerStopped();
        }
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getPort() {
        return port;
    }

    /** Phase 2: number of active subscribe-stream sockets. Primarily used for tests. */
    public int getActiveSubscriberCount() {
        return activeSubscribers.get();
    }

    /**
     * Phase 7: attach the chat panel as the controller for {@code gui_action}
     * commands. Safe to call multiple times — the latest controller wins.
     * Pass {@code null} to disable GUI dispatch (e.g. when the panel closes).
     */
    public void setChatPanelController(ChatPanelController controller) {
        this.guiActionDispatcher = new GuiActionDispatcher(controller);
        // Keep the reactive engine's dispatcher ref in sync so gui_action
        // rules reach the newly-attached chat panel, not the null-controller
        // stub we constructed at startup.
        this.reactiveEngine.setGuiDispatcher(this.guiActionDispatcher);
    }

    /** Phase 7: visible for tests. */
    GuiActionDispatcher getGuiActionDispatcher() {
        return guiActionDispatcher;
    }

    // -----------------------------------------------------------------------
    // Server main loop
    // -----------------------------------------------------------------------

    private void runServer() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            System.err.println("[ImageJAI-TCP] Server listening on port " + port);
            if (listener != null) {
                listener.onServerStarted(port);
            }

            while (running) {
                try {
                    final Socket clientSocket = serverSocket.accept();
                    String clientInfo = clientSocket.getRemoteSocketAddress().toString();
                    if (listener != null) {
                        listener.onClientConnected(clientInfo);
                    }

                    Thread clientThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleClient(clientSocket);
                        }
                    }, "imagej-ai-tcp-client");
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (SocketException e) {
                    // Expected when server is stopped
                    if (running) {
                        System.err.println("[ImageJAI-TCP] Accept error: " + e.getMessage());
                    }
                }
            }
        } catch (java.net.BindException e) {
            String msg = "Port " + port + " already in use";
            System.err.println("[ImageJAI-TCP] " + msg);
            if (listener != null) {
                listener.onError(msg);
            }
        } catch (Exception e) {
            if (running) {
                String msg = "Server error: " + e.getMessage();
                System.err.println("[ImageJAI-TCP] " + msg);
                if (listener != null) {
                    listener.onError(msg);
                }
            }
        } finally {
            running = false;
        }
    }

    // -----------------------------------------------------------------------
    // Client handling
    // -----------------------------------------------------------------------

    private void handleClient(Socket socket) {
        BufferedReader reader = null;
        PrintWriter writer = null;
        try {
            socket.setSoTimeout(60000); // 60s read timeout
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), UTF8));
            writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), UTF8), true);

            // Read one line (max 1MB enforced by checking length)
            String line = readLine(reader);
            if (line == null || line.trim().isEmpty()) {
                writer.println(errorJson("Empty request"));
                return;
            }

            if (line.length() > Constants.TCP_MAX_MESSAGE_SIZE) {
                writer.println(errorJson("Request too large (max " + Constants.TCP_MAX_MESSAGE_SIZE + " bytes)"));
                return;
            }

            // Phase 2: intercept "subscribe" — upgrade to a streaming channel
            // instead of the standard request/response cycle.
            String trimmed = line.trim();
            if (isSubscribeCommand(trimmed)) {
                JsonObject req;
                try {
                    req = new JsonParser().parse(trimmed).getAsJsonObject();
                } catch (Exception e) {
                    writer.println(errorJson("Invalid JSON: " + e.getMessage()));
                    return;
                }
                if (listener != null) {
                    listener.onCommandReceived("subscribe");
                }
                handleSubscribeStream(socket, req);
                return; // finally closes the socket
            }

            // Parse and dispatch
            JsonObject response = dispatch(trimmed);
            writer.println(GSON.toJson(response));

        } catch (Exception e) {
            System.err.println("[ImageJAI-TCP] Client error: " + e.getMessage());
            if (writer != null) {
                try {
                    writer.println(errorJson("Server error: " + e.getMessage()));
                } catch (Exception ignored) {
                    // Client may have disconnected
                }
            }
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {}
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    /** Cheap peek: true if the incoming line is a subscribe command. */
    private boolean isSubscribeCommand(String jsonStr) {
        if (jsonStr == null) return false;
        // Fast path — avoid full JSON parse on non-subscribe commands.
        return jsonStr.contains("\"subscribe\"") && jsonStr.contains("\"command\"");
    }

    /**
     * Phase 2: long-lived subscribe stream. The socket is held open and event
     * frames are written as newline-terminated JSON: {@code {"event": ..., "data": ..., "ts": ..., "seq": ...}}.
     * <p>
     * Contract:
     * <ul>
     *   <li>Enforces a hard cap of {@value #MAX_SUBSCRIBERS} concurrent subscribers.</li>
     *   <li>Per-socket bounded queue of {@value #SUBSCRIBER_QUEUE_CAPACITY} frames; overflow drops
     *       the oldest frame and injects an {@code event_dropped} sentinel.</li>
     *   <li>Heartbeat {@code {"event": "heartbeat"}} every {@value #SUBSCRIBER_HEARTBEAT_MS} ms when
     *       no other traffic flowed in that window.</li>
     *   <li>Unsubscribes and releases the slot when the socket closes or the server stops.</li>
     * </ul>
     */
    private void handleSubscribeStream(final Socket socket, JsonObject req) {
        final OutputStream rawOut;
        try {
            rawOut = socket.getOutputStream();
        } catch (IOException e) {
            return;
        }

        // Disable read timeout — subscriptions are long-lived write-only streams.
        try {
            socket.setSoTimeout(0);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
        } catch (Exception ignore) {}

        // Reserve a subscriber slot. Roll back if we exceed the cap.
        int newCount = activeSubscribers.incrementAndGet();
        if (newCount > MAX_SUBSCRIBERS) {
            activeSubscribers.decrementAndGet();
            writeRawJson(rawOut, errorJsonObject(
                    "Subscriber cap reached (max " + MAX_SUBSCRIBERS + ")"));
            return;
        }

        // Parse requested topic patterns. Default to "*" when omitted or empty.
        final List<String> patterns = new ArrayList<String>();
        JsonElement topicsEl = req.get("topics");
        if (topicsEl != null && topicsEl.isJsonArray()) {
            JsonArray arr = topicsEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement t = arr.get(i);
                if (t != null && t.isJsonPrimitive()) {
                    String s = t.getAsString();
                    if (s != null && !s.isEmpty()) patterns.add(s);
                }
            }
        }
        if (patterns.isEmpty()) patterns.add("*");

        // Per-socket bounded queue with drop-oldest semantics.
        final LinkedBlockingDeque<JsonObject> queue =
                new LinkedBlockingDeque<JsonObject>(SUBSCRIBER_QUEUE_CAPACITY);
        final Object queueLock = new Object();

        final EventBus.Listener listener = new EventBus.Listener() {
            @Override
            public void onEvent(JsonObject frame) {
                synchronized (queueLock) {
                    if (queue.offerLast(frame)) return;
                    // Overflow: drop oldest, inject sentinel, retry with the new frame.
                    JsonObject dropped = queue.pollFirst();
                    JsonObject sentinel = new JsonObject();
                    sentinel.addProperty("event", "event_dropped");
                    JsonObject data = new JsonObject();
                    if (dropped != null) {
                        if (dropped.has("event")) {
                            data.addProperty("oldest_event",
                                    dropped.get("event").getAsString());
                        }
                        if (dropped.has("seq")) {
                            data.addProperty("oldest_seq",
                                    dropped.get("seq").getAsLong());
                        }
                    }
                    data.addProperty("queue_capacity", SUBSCRIBER_QUEUE_CAPACITY);
                    sentinel.add("data", data);
                    sentinel.addProperty("ts", System.currentTimeMillis());
                    sentinel.addProperty("seq", eventBus.nextSeq());
                    // Defensive: if the deque is *still* full (extreme bursts),
                    // keep discarding until both sentinel and new frame fit.
                    while (!queue.offerLast(sentinel)) {
                        if (queue.pollFirst() == null) break;
                    }
                    while (!queue.offerLast(frame)) {
                        if (queue.pollFirst() == null) break;
                    }
                }
            }
        };

        // Register the listener for every requested pattern.
        for (String p : patterns) eventBus.subscribe(p, listener);

        // Initial "subscribed" ack frame so the client can confirm connection.
        JsonObject ack = new JsonObject();
        ack.addProperty("event", "subscribed");
        JsonObject ackData = new JsonObject();
        JsonArray patternsArr = new JsonArray();
        for (String p : patterns) patternsArr.add(p);
        ackData.add("topics", patternsArr);
        ackData.addProperty("active_subscribers", activeSubscribers.get());
        ackData.addProperty("max_subscribers", MAX_SUBSCRIBERS);
        ack.add("data", ackData);
        ack.addProperty("ts", System.currentTimeMillis());
        ack.addProperty("seq", eventBus.nextSeq());

        try {
            writeFrame(rawOut, ack);
        } catch (IOException e) {
            eventBus.unsubscribe(listener);
            activeSubscribers.decrementAndGet();
            return;
        }

        // Main pump: pull frames until the socket dies; inject heartbeats
        // during idle windows.
        long lastSent = System.currentTimeMillis();
        try {
            while (running && !socket.isClosed()) {
                long now = System.currentTimeMillis();
                long sinceLastSent = now - lastSent;
                long waitMs = SUBSCRIBER_HEARTBEAT_MS - sinceLastSent;
                if (waitMs <= 0) {
                    // Heartbeat time.
                    JsonObject hb = new JsonObject();
                    hb.addProperty("event", "heartbeat");
                    hb.add("data", new JsonObject());
                    hb.addProperty("ts", now);
                    hb.addProperty("seq", eventBus.nextSeq());
                    try {
                        writeFrame(rawOut, hb);
                    } catch (IOException e) {
                        break;
                    }
                    lastSent = now;
                    continue;
                }

                JsonObject frame;
                try {
                    frame = queue.pollFirst(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (frame == null) continue; // back to heartbeat check
                try {
                    writeFrame(rawOut, frame);
                } catch (IOException e) {
                    break; // socket died
                }
                lastSent = System.currentTimeMillis();
            }
        } finally {
            eventBus.unsubscribe(listener);
            activeSubscribers.decrementAndGet();
        }
    }

    /** Write a JSON frame followed by '\n' to the raw output stream. */
    private void writeFrame(OutputStream out, JsonObject frame) throws IOException {
        byte[] bytes = (GSON.toJson(frame) + "\n").getBytes(UTF8);
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
    }

    /** Best-effort raw JSON write — swallows IO errors. Used for rejection frames. */
    private void writeRawJson(OutputStream out, JsonObject obj) {
        try {
            writeFrame(out, obj);
        } catch (IOException ignore) {}
    }

    private JsonObject errorJsonObject(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    /**
     * Read a single line from the reader, enforcing max size.
     */
    private String readLine(BufferedReader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            sb.append((char) c);
            if (sb.length() > Constants.TCP_MAX_MESSAGE_SIZE) {
                return sb.toString(); // Will be rejected by size check
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // -----------------------------------------------------------------------
    // Command dispatch
    // -----------------------------------------------------------------------

    private JsonObject dispatch(String jsonStr) {
        JsonObject request;
        try {
            request = new JsonParser().parse(jsonStr).getAsJsonObject();
        } catch (Exception e) {
            return errorResponse("Invalid JSON: " + e.getMessage());
        }
        return dispatch(request);
    }

    /**
     * Dispatch a parsed request. Post-processing adds hash dedup for readonly
     * commands and records friction on failure responses.
     */
    private JsonObject dispatch(JsonObject request) {
        JsonElement cmdElement = request.get("command");
        if (cmdElement == null || !cmdElement.isJsonPrimitive()) {
            return errorResponse("Missing 'command' field");
        }
        String command = cmdElement.getAsString();

        if (listener != null) {
            listener.onCommandReceived(command);
        }

        JsonObject response = dispatchCore(command, request);

        // Phase 7: surface a handler-attached gui_action piggyback into the
        // outer response. Handlers may set result.gui_action = {...}; this
        // path lifts it to top-level so external clients can react without
        // knowing whether the field came from the handler or the dispatcher.
        // For v1 only the dedicated gui_action command writes a top-level
        // entry directly; this hook is infrastructure future phases can tap.
        if (response != null) {
            promoteGuiActionPiggyback(response);
        }

        // Phase 1: readonly fast-path — hash successful readonly results and
        // short-circuit repeat callers that supply a matching if_none_match.
        if (response != null && READONLY_COMMANDS.contains(command)) {
            response = applyReadonlyDedup(request, response);
        }

        // Phase 6: record failures to the friction log. Counts both transport-level
        // failures (ok:false) and operation-level failures (ok:true but
        // result.success:false — macros / scripts can fail inside a successful
        // protocol response).
        if (response != null) {
            try {
                recordFrictionIfFailure(command, request, response);
            } catch (Exception ignore) {
                // friction logging is best-effort
            }
        }

        return response;
    }

    private JsonObject dispatchCore(String command, JsonObject request) {
        if ("ping".equals(command)) {
            return handlePing();
        } else if ("execute_macro".equals(command)) {
            return handleExecuteMacro(request);
        } else if ("get_state".equals(command)) {
            return handleGetState();
        } else if ("get_image_info".equals(command)) {
            return handleGetImageInfo();
        } else if ("get_results_table".equals(command)) {
            return handleGetResultsTable();
        } else if ("capture_image".equals(command)) {
            return handleCaptureImage(request);
        } else if ("run_pipeline".equals(command)) {
            return handleRunPipeline(request);
        } else if ("explore_thresholds".equals(command)) {
            return handleExploreThresholds(request);
        } else if ("get_state_context".equals(command)) {
            return handleGetStateContext();
        } else if ("get_log".equals(command)) {
            return handleGetLog();
        } else if ("get_histogram".equals(command)) {
            return handleGetHistogram();
        } else if ("get_open_windows".equals(command)) {
            return handleGetOpenWindows();
        } else if ("get_metadata".equals(command)) {
            return handleGetMetadata();
        } else if ("batch".equals(command)) {
            return handleBatch(request);
        } else if ("run".equals(command)) {
            return handleRunChain(request);
        } else if ("get_pixels".equals(command)) {
            return handleGetPixels(request);
        } else if ("3d_viewer".equals(command)) {
            return handle3DViewer(request);
        } else if ("get_dialogs".equals(command)) {
            return handleGetDialogs();
        } else if ("close_dialogs".equals(command)) {
            return handleCloseDialogs(request);
        } else if ("close_windows".equals(command)) {
            return handleCloseDialogs(request);
        } else if ("probe_command".equals(command)) {
            return handleProbeCommand(request);
        } else if ("run_script".equals(command)) {
            return handleRunScript(request);
        } else if ("interact_dialog".equals(command)) {
            return handleInteractDialog(request);
        } else if ("get_progress".equals(command)) {
            return handleGetProgress();
        } else if ("get_friction_log".equals(command)) {
            return handleGetFrictionLog(request);
        } else if ("get_friction_patterns".equals(command)) {
            return handleGetFrictionPatterns();
        } else if ("clear_friction_log".equals(command)) {
            return handleClearFrictionLog();
        } else if ("intent".equals(command)) {
            return handleIntent(request);
        } else if ("intent_teach".equals(command)) {
            return handleIntentTeach(request);
        } else if ("intent_list".equals(command)) {
            return handleIntentList();
        } else if ("intent_forget".equals(command)) {
            return handleIntentForget(request);
        } else if ("gui_action".equals(command)) {
            return handleGuiAction(request);
        } else if ("execute_macro_async".equals(command)) {
            return handleExecuteMacroAsync(request);
        } else if ("job_status".equals(command)) {
            return handleJobStatus(request);
        } else if ("job_cancel".equals(command)) {
            return handleJobCancel(request);
        } else if ("job_list".equals(command)) {
            return handleJobList();
        } else if ("list_reactive_rules".equals(command)) {
            return handleListReactiveRules();
        } else if ("reactive_stats".equals(command)) {
            return handleReactiveStats();
        } else if ("reactive_enable".equals(command)) {
            return handleReactiveToggle(request, true);
        } else if ("reactive_disable".equals(command)) {
            return handleReactiveToggle(request, false);
        } else if ("reactive_reload".equals(command)) {
            return handleReactiveReload();
        } else {
            return errorResponse("Unknown command: " + command);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 8: reactive rules TCP surface
    // -----------------------------------------------------------------------

    private JsonObject handleListReactiveRules() {
        JsonObject result = new JsonObject();
        result.addProperty("locked", reactiveEngine.isLocked());
        JsonArray rules = new JsonArray();
        for (ReactiveEngine.Rule r : reactiveEngine.getRules()) {
            rules.add(reactiveRuleToJson(r));
        }
        result.add("rules", rules);
        JsonArray qu = new JsonArray();
        for (ReactiveEngine.Quarantined q : reactiveEngine.getQuarantined()) {
            JsonObject o = new JsonObject();
            o.addProperty("path", q.path);
            o.addProperty("error", q.error != null ? q.error : "");
            qu.add(o);
        }
        result.add("quarantined", qu);
        return successResponse(result);
    }

    private JsonObject handleReactiveStats() {
        JsonObject result = new JsonObject();
        result.addProperty("locked", reactiveEngine.isLocked());
        JsonObject hits = new JsonObject();
        long total = 0L;
        for (ReactiveEngine.Rule r : reactiveEngine.getRules()) {
            hits.addProperty(r.name, r.hits);
            total += r.hits;
        }
        result.add("hits", hits);
        result.addProperty("total", total);
        result.addProperty("quarantined", reactiveEngine.getQuarantined().size());
        return successResponse(result);
    }

    private JsonObject handleReactiveToggle(JsonObject request, boolean enabled) {
        JsonElement nameEl = request.get("name");
        if (nameEl == null || !nameEl.isJsonPrimitive()) {
            return errorResponse("Missing 'name' field");
        }
        String name = nameEl.getAsString();
        boolean found = reactiveEngine.setEnabled(name, enabled);
        if (!found) {
            return errorResponse("No reactive rule named '" + name + "'");
        }
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("enabled", enabled);
        return successResponse(result);
    }

    private JsonObject handleReactiveReload() {
        try {
            reactiveEngine.reload();
        } catch (Exception e) {
            return errorResponse("Reload failed: " + e.getMessage());
        }
        JsonObject result = new JsonObject();
        result.addProperty("rules", reactiveEngine.getRules().size());
        result.addProperty("quarantined", reactiveEngine.getQuarantined().size());
        return successResponse(result);
    }

    private JsonObject reactiveRuleToJson(ReactiveEngine.Rule r) {
        JsonObject o = new JsonObject();
        o.addProperty("name", r.name);
        o.addProperty("description", r.description != null ? r.description : "");
        o.addProperty("enabled", r.enabled);
        o.addProperty("priority", r.priority);
        o.addProperty("event", r.event);
        o.addProperty("hits", r.hits);
        o.addProperty("lastFired", r.lastFired);
        o.addProperty("sourceFile", r.sourceFile);
        if (r.rateLimitSpec != null) {
            o.addProperty("rate_limit", r.rateLimitSpec);
        }
        if (r.actions != null) {
            o.addProperty("actions", r.actions.size());
        }
        return o;
    }

    /** Phase 8: visible for tests. */
    ReactiveEngine getReactiveEngine() {
        return reactiveEngine;
    }

    /**
     * Phase 7: top-level {@code gui_action} command. Delegates to
     * {@link GuiActionDispatcher}, whose response already carries an
     * {@code ok} field — standard post-processing (dedup, friction logging)
     * treats it like any other command response.
     */
    private JsonObject handleGuiAction(JsonObject request) {
        return guiActionDispatcher.dispatch(request);
    }

    /**
     * Phase 7: promote a {@code gui_action} field nested inside {@code result}
     * to the top-level response. Idempotent — top-level wins if both exist.
     * Future phases (e.g. macro/pipeline handlers) can attach a piggyback
     * sentinel without knowing how the outer envelope is built.
     */
    private static void promoteGuiActionPiggyback(JsonObject response) {
        if (response == null) return;
        if (response.has("gui_action")) return;
        JsonElement resultEl = response.get("result");
        if (resultEl == null || !resultEl.isJsonObject()) return;
        JsonObject result = resultEl.getAsJsonObject();
        JsonElement piggyback = result.get("gui_action");
        if (piggyback == null) return;
        response.add("gui_action", piggyback);
        result.remove("gui_action");
    }

    /**
     * Keys stripped before hashing readonly results because they tick on every
     * call for reasons unrelated to user-visible Fiji state. Without this, the
     * dedup cache would miss on every {@code get_state} / {@code get_metadata}
     * call, defeating Phase 1 entirely.
     */
    private static final Set<String> HASH_EXCLUDED_KEYS = new HashSet<String>(Arrays.asList(
            "usedMB",     // JVM free-memory ticks continuously
            "freeMB",     // JVM free-memory ticks continuously
            "percent"     // get_progress percentage during long ops
    ));

    /**
     * For readonly responses: compute an MD5 hash over the canonical JSON of
     * the {@code result} field and either (a) return the full payload plus
     * {@code hash}, or (b) if the caller's {@code if_none_match} matches,
     * strip the payload and return {@code unchanged: true}.
     *
     * <p>The hash is computed over a canonicalised copy of the result
     * (volatile fields stripped, JsonObject keys sorted) so that logically
     * identical state always hashes identically even though the wire payload
     * preserves insertion order.
     */
    private JsonObject applyReadonlyDedup(JsonObject request, JsonObject response) {
        if (response == null) return null;
        JsonElement okEl = response.get("ok");
        if (okEl == null || !okEl.isJsonPrimitive() || !okEl.getAsBoolean()) {
            return response; // don't dedup error responses
        }

        JsonElement result = response.get("result");
        String hash = md5Hex(canonicalForHash(result));

        String ifNone = null;
        JsonElement ifNoneEl = request.get("if_none_match");
        if (ifNoneEl != null && ifNoneEl.isJsonPrimitive()) {
            ifNone = ifNoneEl.getAsString();
        }

        if (ifNone != null && ifNone.equals(hash)) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("unchanged", true);
            r.addProperty("hash", hash);
            return r;
        }

        response.addProperty("hash", hash);
        return response;
    }

    /**
     * Produce a deterministic canonical JSON form suitable for hashing:
     * volatile keys dropped, JsonObject keys sorted alphabetically. Input is
     * not mutated.
     */
    private static String canonicalForHash(JsonElement el) {
        if (el == null) return "";
        return GSON.toJson(canonicalise(el));
    }

    private static JsonElement canonicalise(JsonElement el) {
        if (el == null || el.isJsonNull()) return JsonNull.INSTANCE;
        if (el.isJsonPrimitive()) return el;
        if (el.isJsonArray()) {
            JsonArray src = el.getAsJsonArray();
            JsonArray dst = new JsonArray();
            for (int i = 0; i < src.size(); i++) dst.add(canonicalise(src.get(i)));
            return dst;
        }
        // Object — sort keys, strip volatile.
        JsonObject src = el.getAsJsonObject();
        java.util.TreeMap<String, JsonElement> sorted = new java.util.TreeMap<String, JsonElement>();
        for (Map.Entry<String, JsonElement> e : src.entrySet()) {
            String k = e.getKey();
            if (HASH_EXCLUDED_KEYS.contains(k)) continue;
            sorted.put(k, canonicalise(e.getValue()));
        }
        JsonObject dst = new JsonObject();
        for (Map.Entry<String, JsonElement> e : sorted.entrySet()) {
            dst.add(e.getKey(), e.getValue());
        }
        return dst;
    }

    private void recordFrictionIfFailure(String command, JsonObject request, JsonObject response) {
        // Never log meta-queries about friction; that would self-reference and churn.
        if ("get_friction_log".equals(command)
                || "get_friction_patterns".equals(command)
                || "clear_friction_log".equals(command)) {
            return;
        }

        JsonElement okEl = response.get("ok");
        boolean transportOk = okEl != null && okEl.isJsonPrimitive() && okEl.getAsBoolean();

        String error = null;
        if (!transportOk) {
            JsonElement errEl = response.get("error");
            error = (errEl != null && errEl.isJsonPrimitive()) ? errEl.getAsString() : "unknown error";
        } else {
            // Inspect result for nested success:false (macros/scripts can fail
            // inside a transport-ok response). Guard against non-boolean
            // primitives — Gson's getAsBoolean() happily parses strings.
            JsonElement resultEl = response.get("result");
            if (resultEl != null && resultEl.isJsonObject()) {
                JsonObject r = resultEl.getAsJsonObject();
                JsonElement successEl = r.get("success");
                if (successEl != null
                        && successEl.isJsonPrimitive()
                        && successEl.getAsJsonPrimitive().isBoolean()
                        && !successEl.getAsBoolean()) {
                    JsonElement errEl = r.get("error");
                    error = (errEl != null && errEl.isJsonPrimitive())
                            ? errEl.getAsString() : "operation failed";
                }
            }
        }

        if (error == null) return;
        frictionLog.record(command, summariseArgs(request), error);
    }

    private String summariseArgs(JsonObject request) {
        if (request == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : request.entrySet()) {
            String k = e.getKey();
            if ("command".equals(k) || "if_none_match".equals(k)) continue;
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            String s;
            try {
                s = v.isJsonPrimitive() ? v.getAsString() : v.toString();
            } catch (Exception ex) {
                s = v.toString();
            }
            if (s == null) s = "";
            if (s.length() > 80) s = s.substring(0, 80) + "...";
            if (sb.length() > 0) sb.append(" ");
            sb.append(k).append("=").append(s);
            if (sb.length() > 240) {
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int v = b & 0xff;
                if (v < 0x10) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // -----------------------------------------------------------------------
    // Command handlers
    // -----------------------------------------------------------------------

    private JsonObject handlePing() {
        return successResponse(new JsonPrimitive("pong"));
    }

    private JsonObject handleGetProgress() {
        final JsonObject result = new JsonObject();
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    ij.ImageJ ijInstance = IJ.getInstance();
                    if (ijInstance == null) {
                        result.addProperty("active", false);
                        result.addProperty("status", "");
                        latch.countDown();
                        return;
                    }

                    // Read progress bar via reflection
                    boolean showBar = false;
                    double percent = 0;
                    try {
                        java.lang.reflect.Field pbField = ijInstance.getClass().getDeclaredField("progressBar");
                        pbField.setAccessible(true);
                        Object pb = pbField.get(ijInstance);
                        if (pb != null) {
                            java.lang.reflect.Field showField = pb.getClass().getDeclaredField("showBar");
                            showField.setAccessible(true);
                            showBar = showField.getBoolean(pb);

                            java.lang.reflect.Field widthField = pb.getClass().getDeclaredField("width");
                            widthField.setAccessible(true);
                            int barWidth = widthField.getInt(pb);

                            java.lang.reflect.Field canvasWidthField = pb.getClass().getDeclaredField("canvasWidth");
                            canvasWidthField.setAccessible(true);
                            int canvasWidth = canvasWidthField.getInt(pb);

                            if (canvasWidth > 0) {
                                percent = (barWidth * 100.0) / canvasWidth;
                            }
                        }
                    } catch (Exception e) {
                        // reflection failed — leave defaults
                    }

                    // Read status line text via reflection
                    String statusText = "";
                    try {
                        java.lang.reflect.Field slField = ijInstance.getClass().getDeclaredField("statusLine");
                        slField.setAccessible(true);
                        Object sl = slField.get(ijInstance);
                        if (sl instanceof javax.swing.JLabel) {
                            statusText = ((javax.swing.JLabel) sl).getText();
                            if (statusText == null) statusText = "";
                        }
                    } catch (Exception e) {
                        // reflection failed
                    }

                    result.addProperty("active", showBar);
                    result.addProperty("percent", Math.round(percent));
                    result.addProperty("status", statusText);
                } catch (Exception e) {
                    result.addProperty("active", false);
                    result.addProperty("status", "error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return errorResponse("Progress check timed out");
        }

        return successResponse(result);
    }

    private JsonObject handleExecuteMacro(JsonObject request) {
        JsonElement codeElement = request.get("code");
        if (codeElement == null || !codeElement.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for execute_macro");
        }
        final String code = codeElement.getAsString();

        // Gate image.* events while this macro runs. The agent's event
        // subscribers react to image.opened by sending get_image_info /
        // get_histogram — those wrap work in SwingUtilities.invokeLater
        // which contends with Duplicate's own imp.show() EDT work. The
        // contention can leave WindowManager.currentImage pointing at
        // the source window when the macro's next line runs, so
        // setAutoThreshold / Convert to Mask land on the wrong image.
        // Suppressing image.* removes the trigger entirely. macro.* and
        // dialog.* still flow so the agent's progress / error paths work.
        eventBus.pushSuppress("image.*");
        try {
        // Run IJ.runMacro on a worker thread so the TCP handler can keep polling
        // for dialogs / timeout and return structured failure feedback instead
        // of leaving the client blocked until its socket times out.
        JsonObject result = new JsonObject();
        boolean success = false;
        String failureMessage = null;
        String macroReturn = null;
        JsonArray dialogs = null;

        // Phase 2: emit macro.started so event subscribers can react.
        long macroId = MACRO_ID_SEQ.incrementAndGet();
        try {
            JsonObject startData = new JsonObject();
            startData.addProperty("macro_id", macroId);
            String preview = code.trim();
            if (preview.length() > 160) preview = preview.substring(0, 160) + "...";
            startData.addProperty("preview", preview);
            startData.addProperty("source", "tcp");
            eventBus.publish("macro.started", startData);
        } catch (Throwable ignore) {}

        // B4: snapshot log length + prior error messages before runMacro so
        // we can diff afterwards. IJ.runMacro does NOT throw when a macro
        // calls IJ.error(...) or hits e.g. setAutoThreshold("NonExistent") —
        // it returns normally while IJ.error prints to the log / shows a
        // dialog. Without this diff the handler would report success and the
        // friction log would never see the failure. We snapshot the prior
        // error message so a stale error from a previous macro can't be
        // mis-attributed to this call.
        int logLenBefore = 0;
        try {
            String preLog = IJ.getLog();
            logLenBefore = preLog != null ? preLog.length() : 0;
        } catch (Throwable ignore) {}
        String priorInterpError = readInterpreterErrorMessage();
        String priorIjError = readIjErrorMessage();

        long startTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return IJ.runMacro(code);
                }
            });

            while (true) {
                try {
                    macroReturn = future.get(150, TimeUnit.MILLISECONDS);
                    success = true;
                    break;
                } catch (TimeoutException e) {
                    dialogs = safeDetectOpenDialogs();
                    // ImageJ opens the "Macro Error" dialog a fraction of a second
                    // before Interpreter.getErrorMessage() / IJ.getErrorMessage()
                    // populate. Grant a 50 ms grace window on that specific dialog
                    // so detectIjMacroError can read the settled signals instead
                    // of falling through to the generic blocking-dialog stub.
                    if (hasMacroErrorDialog(dialogs)) {
                        try { Thread.sleep(50); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        dialogs = safeDetectOpenDialogs();
                    }
                    String detected = detectIjMacroError(logLenBefore, priorInterpError, priorIjError, dialogs);
                    if (detected != null) {
                        future.cancel(true);
                        failureMessage = detected;
                        break;
                    }
                    // Any modal dialog means the macro is blocked waiting for
                    // interaction — e.g. run("Gaussian Blur...") with no args
                    // opens the GenericDialog. Surface it now (next poll, ≤150ms)
                    // instead of waiting the full MACRO_TIMEOUT_MS.
                    String blocking = detectBlockingDialog(dialogs);
                    if (blocking != null) {
                        future.cancel(true);
                        // Actively dismiss the blocking dialog so it does not
                        // linger on screen and block subsequent macros. The
                        // dismiss runs on the EDT and waits up to 2 s and
                        // captures the title+body of every window it closes
                        // so the agent can inspect silent popups.
                        JsonArray dismissedCaptured = new JsonArray();
                        int dismissed = dismissOpenDialogsCapturing(null, dismissedCaptured);
                        if (dismissed > 0) {
                            result.addProperty("dialogsAutoDismissed", dismissed);
                            if (dismissedCaptured.size() > 0) {
                                result.add("dismissedDialogs", dismissedCaptured);
                            }
                            // Only append the "probe the plugin" suffix for
                            // plugin-dialog cases. Macro Error popups already
                            // carry a compile-error-oriented hint inside the
                            // detectBlockingDialog message; appending the
                            // "probe the plugin" line there would contradict it.
                            if (hasMacroErrorDialog(dialogs)) {
                                failureMessage = blocking
                                        + " — the dialog has been auto-dismissed by the server.";
                            } else {
                                failureMessage = blocking
                                        + " — the dialog has been auto-dismissed by the server; "
                                        + "probe the plugin and re-run with explicit args.";
                            }
                            // Refresh the dialogs snapshot so the outgoing response
                            // reflects post-dismiss state (usually empty).
                            dialogs = safeDetectOpenDialogs();
                        } else {
                            failureMessage = blocking;
                        }
                        break;
                    }
                    if ((System.currentTimeMillis() - startTime) > MACRO_TIMEOUT_MS) {
                        future.cancel(true);
                        failureMessage = "Macro execution timed out after " + MACRO_TIMEOUT_MS + "ms";
                        break;
                    }
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String msg = cause != null ? cause.getMessage() : e.getMessage();
                    failureMessage = "Macro error: " + (msg != null ? msg : "unknown error");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        } finally {
            executor.shutdownNow();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        if (success) {
            result.addProperty("success", true);
            result.addProperty("output", macroReturn != null ? macroReturn : "");
            result.addProperty("executionTimeMs", elapsed);

            try {
                ImageInfo active = stateInspector.getActiveImageInfo();
                if (active != null) {
                    JsonArray newImages = new JsonArray();
                    newImages.add(active.getTitle());
                    result.add("newImages", newImages);
                }
                String csv = stateInspector.getResultsTableCSV();
                if (csv != null && !csv.isEmpty()) {
                    result.addProperty("resultsTable", csv);
                }
                stateInspector.checkResultsTableChange();
            } catch (Exception ignore) {
                // State inspection is best-effort
            }

            dialogs = safeDetectOpenDialogs();
            String detected = detectIjMacroError(logLenBefore, priorInterpError, priorIjError, dialogs);
            if (detected != null) {
                success = false;
                failureMessage = detected;
            }
        }

        if (!success) {
            result.addProperty("success", false);
            result.addProperty("error", failureMessage != null ? failureMessage : "Unknown macro error");
        }

        if (dialogs == null) dialogs = safeDetectOpenDialogs();
        if (dialogs != null && dialogs.size() > 0) {
            result.add("dialogs", dialogs);
        }

        publishTcpMacroCompleted(macroId, success, elapsed, failureMessage);
        return successResponse(result);
        } finally {
            eventBus.popSuppress("image.*");
        }
    }

    private JsonArray safeDetectOpenDialogs() {
        try {
            return detectOpenDialogs();
        } catch (Exception ignore) {
            return null;
        }
    }

    private void publishTcpMacroCompleted(long macroId, boolean success, long elapsed, String error) {
        try {
            JsonObject doneData = new JsonObject();
            doneData.addProperty("macro_id", macroId);
            doneData.addProperty("success", success);
            doneData.addProperty("executionTimeMs", elapsed);
            if (!success && error != null) {
                doneData.addProperty("error", error);
            }
            eventBus.publish("macro.completed", doneData);
        } catch (Throwable ignore) {}
    }

    /**
     * B4: detect IJ-reported errors from a macro that returned normally.
     * Returns a best-effort error string, or null if no error signal fires.
     * Conservative — only trips on lines that look like ImageJ's own error
     * markers, never on arbitrary print() output.
     *
     * <p>Signals consulted, in order:
     * <ol>
     *   <li>{@code ij.macro.Interpreter.getErrorMessage()} — the macro
     *       interpreter records the most recent runtime error here even when
     *       it chose to keep running to completion rather than throw.</li>
     *   <li>{@code IJ.getErrorMessage()} — catches errors routed through
     *       {@code IJ.error(...)} (plugin-side, non-interpreter failures).</li>
     *   <li>Tail of {@code IJ.getLog()} added during this call — catches
     *       log-only errors emitted by plugins that don't set the above.</li>
     *   <li>Any dialog classified {@code type=error} by
     *       {@link #detectOpenDialogs()} — reused, not re-scanned.</li>
     * </ol>
     * All four are wrapped in try/catch — any reflection or API failure falls
     * through rather than false-positively reporting an error.
     */
    private String detectIjMacroError(int logLenBefore,
                                      String priorInterpError,
                                      String priorIjError,
                                      JsonArray dialogs) {
        // Signal 1: ij.macro.Interpreter error message. Only report if it
        // differs from the snapshot taken before runMacro — otherwise a stale
        // error from a prior run would be mis-attributed to this call.
        String curInterp = readInterpreterErrorMessage();
        if (curInterp != null && !curInterp.isEmpty()
                && !curInterp.equals(priorInterpError)) {
            String trimmed = curInterp.trim();
            if (trimmed.length() > 240) trimmed = trimmed.substring(0, 240) + "...";
            return "Macro error (interpreter): " + trimmed;
        }

        // Signal 2: IJ.getErrorMessage() — catches IJ.error() sinks outside
        // the interpreter (plugin-reported errors). Same snapshot-diff guard.
        String curIj = readIjErrorMessage();
        if (curIj != null && !curIj.isEmpty()
                && !curIj.equals(priorIjError)) {
            String trimmed = curIj.trim();
            if (trimmed.length() > 240) trimmed = trimmed.substring(0, 240) + "...";
            return "Macro error (IJ.error): " + trimmed;
        }

        // Signal 3: tail of IJ.getLog() added during this call.
        try {
            String postLog = IJ.getLog();
            if (postLog != null && postLog.length() > logLenBefore) {
                String added = postLog.substring(logLenBefore);
                // Line-by-line scan — keep bounded to avoid huge allocations.
                if (added.length() > 16384) {
                    added = added.substring(added.length() - 16384);
                }
                String[] lines = added.split("\\r?\\n");
                for (int i = 0; i < lines.length; i++) {
                    String ln = lines[i].trim();
                    if (ln.isEmpty()) continue;
                    if (ln.startsWith("Error in macro")
                            || ln.startsWith("Error:")
                            || ln.startsWith("Exception:")
                            || ln.startsWith("Exception in ")) {
                        String snippet = ln;
                        if (snippet.length() > 240) snippet = snippet.substring(0, 240) + "...";
                        return "Macro error (log): " + snippet;
                    }
                }
            }
        } catch (Throwable ignore) {}

        // Signal 4: any dialog detected with type=error.
        if (dialogs != null) {
            for (int i = 0; i < dialogs.size(); i++) {
                try {
                    JsonElement el = dialogs.get(i);
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject d = el.getAsJsonObject();
                    JsonElement typeEl = d.get("type");
                    if (typeEl != null
                            && typeEl.isJsonPrimitive()
                            && "error".equals(typeEl.getAsString())) {
                        String title = "";
                        JsonElement tEl = d.get("title");
                        if (tEl != null && tEl.isJsonPrimitive()) title = tEl.getAsString();
                        String text = "";
                        JsonElement txtEl = d.get("text");
                        if (txtEl != null && txtEl.isJsonPrimitive()) text = txtEl.getAsString();
                        String combined = (title + ": " + text).trim();
                        if (combined.length() > 240) combined = combined.substring(0, 240) + "...";
                        return "Macro error (dialog): " + combined;
                    }
                } catch (Throwable ignore) {}
            }
        }

        // Signal 5: "Macro Error" dialog fallback. ImageJ's Macro Error popup
        // carries only a "Show Debug Window" checkbox as its body — useless to
        // the agent — but the real compile/runtime error has usually been
        // written to the log with a prefix we don't recognise (e.g. "Undefined
        // variable in line 7 (...)"). When signals 1–4 all missed AND a
        // Macro Error dialog is open, surface the last non-empty log line
        // added during this call as the error text.
        if (hasMacroErrorDialog(dialogs)) {
            try {
                String postLog = IJ.getLog();
                if (postLog != null && postLog.length() > logLenBefore) {
                    String added = postLog.substring(logLenBefore);
                    if (added.length() > 16384) {
                        added = added.substring(added.length() - 16384);
                    }
                    String[] lines = added.split("\\r?\\n");
                    String lastNonEmpty = null;
                    for (int i = lines.length - 1; i >= 0; i--) {
                        String ln = lines[i].trim();
                        if (!ln.isEmpty()) { lastNonEmpty = ln; break; }
                    }
                    if (lastNonEmpty != null) {
                        if (lastNonEmpty.length() > 400) {
                            lastNonEmpty = lastNonEmpty.substring(0, 400) + "...";
                        }
                        return "Macro error (log tail, Debug dialog open): " + lastNonEmpty;
                    }
                }
            } catch (Throwable ignore) {}
        }

        return null;
    }

    /**
     * Detect when a macro is blocked on a modal dialog that isn't classified
     * as an error. Returns a concise failure message naming the dialog, or
     * null if no blocking dialog is present. Trips on e.g. {@code
     * run("Gaussian Blur...")} called without an argument string — ImageJ
     * opens the GenericDialog and the macro thread waits on OK/Cancel. Error
     * dialogs are handled by {@link #detectIjMacroError} first, so we skip
     * {@code type=error} here.
     */
    private String detectBlockingDialog(JsonArray dialogs) {
        if (dialogs == null) return null;
        for (int i = 0; i < dialogs.size(); i++) {
            try {
                JsonElement el = dialogs.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject d = el.getAsJsonObject();
                JsonElement modalEl = d.get("modal");
                if (modalEl == null || !modalEl.isJsonPrimitive()
                        || !modalEl.getAsBoolean()) continue;
                JsonElement typeEl = d.get("type");
                if (typeEl != null && typeEl.isJsonPrimitive()
                        && "error".equals(typeEl.getAsString())) {
                    continue;
                }
                String title = "";
                JsonElement tEl = d.get("title");
                if (tEl != null && tEl.isJsonPrimitive()) title = tEl.getAsString();
                String body = "";
                JsonElement bEl = d.get("text");
                if (bEl != null && bEl.isJsonPrimitive()) body = bEl.getAsString();
                StringBuilder msg = new StringBuilder("Macro paused on modal dialog: ");
                msg.append(title.isEmpty() ? "(untitled)" : title);
                if (!body.isEmpty()) {
                    String trimmed = body.length() > 400 ? body.substring(0, 400) + "…" : body;
                    msg.append(" — body: ").append(trimmed);
                }
                // Branch the remediation hint on dialog kind: a Macro Error
                // popup is a compile/runtime failure in the macro itself, so
                // "supply parameters via run(name, args)" is misleading — the
                // agent needs to re-read the macro code, not probe a plugin.
                // The ImageJ Log window is often empty for compile errors
                // (detectIjMacroError's Signal 5 already harvested it if any
                // lines were written), so do not promise content there.
                if ("Macro Error".equals(title)) {
                    msg.append(" — the macro itself failed to compile or run; "
                            + "re-read the macro source for syntax/typo/"
                            + "argument-count bugs (do not probe a plugin — "
                            + "this is not a missing-args case; the ImageJ "
                            + "log is usually empty for compile errors)");
                } else {
                    msg.append(" — supply parameters via run(name, args) or "
                            + "dismiss via interact_dialog/close_dialogs");
                }
                return msg.toString();
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /**
     * True when any open dialog has the title {@code "Macro Error"}. Used to
     * grant a brief settle window before reading error signals — see the
     * polling loop above.
     */
    private boolean hasMacroErrorDialog(JsonArray dialogs) {
        if (dialogs == null) return false;
        for (int i = 0; i < dialogs.size(); i++) {
            try {
                JsonElement el = dialogs.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonElement tEl = el.getAsJsonObject().get("title");
                if (tEl != null && tEl.isJsonPrimitive()
                        && "Macro Error".equals(tEl.getAsString())) {
                    return true;
                }
            } catch (Throwable ignore) {}
        }
        return false;
    }

    /**
     * Best-effort read of {@code ij.macro.Interpreter}'s current error message.
     * Returns the message string, or {@code null} if unreadable. Tries method
     * variants (static / instance) and falls back to a public static field —
     * whichever your ImageJ build exposes.
     */
    private String readInterpreterErrorMessage() {
        try {
            Class<?> interp = Class.forName("ij.macro.Interpreter");
            try {
                java.lang.reflect.Method m = interp.getMethod("getErrorMessage");
                Object em;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    em = m.invoke(null);
                } else {
                    java.lang.reflect.Method getInst = interp.getMethod("getInstance");
                    Object instance = getInst.invoke(null);
                    em = (instance != null) ? m.invoke(instance) : null;
                }
                if (em instanceof String) return (String) em;
            } catch (NoSuchMethodException ignoreInner) {
                try {
                    java.lang.reflect.Field f = interp.getField("errorMessage");
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        Object em = f.get(null);
                        if (em instanceof String) return (String) em;
                    }
                } catch (Throwable ignoreField) {}
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * Best-effort read of {@code IJ.getErrorMessage()}. Returns the message or
     * {@code null} if unavailable.
     */
    private String readIjErrorMessage() {
        try {
            java.lang.reflect.Method m = IJ.class.getMethod("getErrorMessage");
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                Object em = m.invoke(null);
                if (em instanceof String) return (String) em;
            }
        } catch (NoSuchMethodException ignore) {
        } catch (Throwable ignore) {}
        return null;
    }

    private JsonObject handleRunScript(JsonObject request) {
        JsonElement langElement = request.get("language");
        String language = langElement != null && langElement.isJsonPrimitive()
                ? langElement.getAsString()
                : "groovy";

        JsonElement codeElement = request.get("code");
        if (codeElement == null || !codeElement.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for run_script");
        }
        final String code = codeElement.getAsString();

        JsonObject result = new JsonObject();

        try {
            long startTime = System.currentTimeMillis();

            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName(language);

            if (engine == null) {
                return errorResponse("ScriptEngine not found for language: " + language
                        + ". Available: groovy, jython, javascript");
            }

            Object scriptResult = engine.eval(code);
            long elapsed = System.currentTimeMillis() - startTime;

            result.addProperty("success", true);
            result.addProperty("language", language);
            result.addProperty("output", scriptResult != null ? scriptResult.toString() : "");
            result.addProperty("executionTimeMs", elapsed);

        } catch (ScriptException e) {
            result.addProperty("success", false);
            result.addProperty("error", "Script error: " + e.getMessage());
            result.addProperty("language", language);
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", "Error: " + e.getMessage());
        }

        try {
            JsonArray dialogs = detectOpenDialogs();
            if (dialogs.size() > 0) {
                result.add("dialogs", dialogs);
            }
        } catch (Exception ignore) {
        }

        return successResponse(result);
    }

    private JsonObject handleGetState() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject state = new JsonObject();

                    // Active image
                    ImageInfo active = stateInspector.getActiveImageInfo();
                    if (active != null) {
                        state.add("activeImage", imageInfoToJson(active));
                    } else {
                        state.add("activeImage", null);
                    }

                    // All images
                    List<ImageInfo> allImages = stateInspector.getAllImages();
                    JsonArray imagesArray = new JsonArray();
                    for (ImageInfo info : allImages) {
                        imagesArray.add(imageInfoToJson(info));
                    }
                    state.add("allImages", imagesArray);

                    // Results table
                    state.addProperty("resultsTable", stateInspector.getResultsTableCSV());

                    // Memory
                    MemoryInfo mem = stateInspector.getMemoryInfo();
                    JsonObject memJson = new JsonObject();
                    memJson.addProperty("usedMB", mem.getUsedMB());
                    memJson.addProperty("maxMB", mem.getMaxMB());
                    memJson.addProperty("freeMB", mem.getFreeMB());
                    memJson.addProperty("openImageCount", mem.getOpenImageCount());
                    state.add("memory", memJson);

                    holder[0] = state;
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(10000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting state");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetImageInfo() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImageInfo info = stateInspector.getActiveImageInfo();
                    if (info != null) {
                        holder[0] = imageInfoToJson(info);
                    } else {
                        holder[0] = null;
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting image info");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if (holder[0] == null) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetResultsTable() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = stateInspector.getResultsTableCSV();
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting results table");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse(new JsonPrimitive((String) holder[0]));
    }

    private JsonObject handleCaptureImage(JsonObject request) {
        JsonElement maxSizeElement = request.get("maxSize");
        final int maxSize = (maxSizeElement != null && maxSizeElement.isJsonPrimitive())
                ? maxSizeElement.getAsInt()
                : Constants.MAX_THUMBNAIL_SIZE;

        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ij.ImagePlus imp = ij.WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        byte[] png = ImageCapture.captureImage(imp, maxSize);
                        if (png == null) {
                            holder[0] = "CAPTURE_FAILED";
                        } else {
                            JsonObject result = new JsonObject();
                            result.addProperty("base64", base64Encode(png));
                            result.addProperty("width", imp.getWidth());
                            result.addProperty("height", imp.getHeight());
                            holder[0] = result;
                        }
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(10000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out capturing image");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Capture error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        if ("CAPTURE_FAILED".equals(holder[0])) {
            return errorResponse("Failed to capture image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleRunPipeline(JsonObject request) {
        JsonElement stepsElement = request.get("steps");
        if (stepsElement == null || !stepsElement.isJsonArray()) {
            return errorResponse("Missing 'steps' array for run_pipeline");
        }

        JsonArray stepsArray = stepsElement.getAsJsonArray();
        final List<PipelineBuilder.PipelineStep> steps = new ArrayList<PipelineBuilder.PipelineStep>();
        for (int i = 0; i < stepsArray.size(); i++) {
            JsonObject stepObj = stepsArray.get(i).getAsJsonObject();
            String desc = stepObj.has("description") ? stepObj.get("description").getAsString() : "Step " + (i + 1);
            String code = stepObj.has("code") ? stepObj.get("code").getAsString() : "";
            steps.add(new PipelineBuilder.PipelineStep(i + 1, desc, code));
        }

        if (steps.isEmpty()) {
            return errorResponse("Empty steps array");
        }

        PipelineBuilder.Pipeline pipeline = new PipelineBuilder.Pipeline("TCP Pipeline", steps);

        // executePipeline calls commandEngine.executeMacro() which handles EDT
        // dispatch internally, so call directly from TCP handler thread.
        try {
            pipelineBuilder.executePipeline(pipeline, null);
        } catch (Exception e) {
            return errorResponse("Pipeline error: " + e.getMessage());
        }

        PipelineBuilder.Pipeline result = pipeline;
        JsonObject resultJson = new JsonObject();
        resultJson.addProperty("status", result.status);
        JsonArray stepsResult = new JsonArray();
        for (PipelineBuilder.PipelineStep step : result.steps) {
            JsonObject stepJson = new JsonObject();
            stepJson.addProperty("index", step.index);
            stepJson.addProperty("description", step.description);
            stepJson.addProperty("status", step.status);
            stepJson.addProperty("executionTimeMs", step.executionTimeMs);
            if (step.result != null && !step.result.isSuccess()) {
                stepJson.addProperty("error", step.result.getError());
            }
            stepsResult.add(stepJson);
        }
        resultJson.add("steps", stepsResult);
        return successResponse(resultJson);
    }

    private JsonObject handleExploreThresholds(JsonObject request) {
        JsonElement methodsElement = request.get("methods");
        final String[] methods;
        if (methodsElement != null && methodsElement.isJsonArray()) {
            JsonArray arr = methodsElement.getAsJsonArray();
            methods = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                methods[i] = arr.get(i).getAsString();
            }
        } else {
            methods = null; // Will use defaults
        }

        // exploreThresholds calls commandEngine.executeMacro() which handles EDT
        // dispatch internally, so call directly from TCP handler thread.
        ExplorationEngine.ExplorationReport report;
        try {
            report = explorationEngine.exploreThresholds(methods);
        } catch (Exception e) {
            return errorResponse("Exploration error: " + e.getMessage());
        }
        JsonObject resultJson = new JsonObject();
        if (report.recommended != null) {
            resultJson.addProperty("recommended", report.recommended.methodName);
        }
        resultJson.addProperty("reasoning", report.reasoning);

        JsonArray resultsArray = new JsonArray();
        for (ExplorationEngine.ExplorationResult r : report.results) {
            JsonObject rJson = new JsonObject();
            rJson.addProperty("method", r.methodName);
            rJson.addProperty("success", r.success);
            rJson.addProperty("objectCount", r.objectCount);
            rJson.addProperty("meanArea", r.meanArea);
            rJson.addProperty("meanCircularity", r.meanCircularity);
            rJson.addProperty("coverage", r.coverage);
            rJson.addProperty("summary", r.summary);
            if (r.thumbnail != null && r.thumbnail.length > 0) {
                rJson.addProperty("thumbnail", base64Encode(r.thumbnail));
            }
            resultsArray.add(rJson);
        }
        resultJson.add("results", resultsArray);
        return successResponse(resultJson);
    }

    private JsonObject handleGetStateContext() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = stateInspector.buildStateContext();
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting state context");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse(new JsonPrimitive((String) holder[0]));
    }

    private JsonObject handleGetLog() {
        String log = IJ.getLog();
        return successResponse(new JsonPrimitive(log != null ? log : ""));
    }

    private JsonObject handleGetHistogram() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImagePlus imp = WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        ImageStatistics stats = imp.getStatistics();
                        JsonObject result = new JsonObject();
                        result.addProperty("min", stats.min);
                        result.addProperty("max", stats.max);
                        result.addProperty("mean", stats.mean);
                        result.addProperty("stdDev", stats.stdDev);
                        result.addProperty("nPixels", (long) stats.pixelCount);

                        JsonArray bins = new JsonArray();
                        if (stats.histogram != null) {
                            for (int i = 0; i < stats.histogram.length; i++) {
                                bins.add(new JsonPrimitive(stats.histogram[i]));
                            }
                        }
                        result.add("bins", bins);
                        holder[0] = result;
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting histogram");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetOpenWindows() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject result = new JsonObject();

                    // Image windows
                    JsonArray images = new JsonArray();
                    int[] ids = WindowManager.getIDList();
                    if (ids != null) {
                        for (int i = 0; i < ids.length; i++) {
                            ImagePlus imp = WindowManager.getImage(ids[i]);
                            if (imp != null) {
                                images.add(new JsonPrimitive(imp.getTitle()));
                            }
                        }
                    }
                    result.add("images", images);

                    // Non-image windows
                    JsonArray nonImages = new JsonArray();
                    Frame[] frames = WindowManager.getNonImageWindows();
                    if (frames != null) {
                        for (int i = 0; i < frames.length; i++) {
                            String title = frames[i].getTitle();
                            if (title != null && !title.isEmpty()) {
                                nonImages.add(new JsonPrimitive(title));
                            }
                        }
                    }
                    result.add("nonImages", nonImages);

                    holder[0] = result;
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting open windows");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetMetadata() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImagePlus imp = WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        JsonObject result = new JsonObject();
                        result.addProperty("title", imp.getTitle());

                        // Info property (often contains Bio-Formats metadata)
                        String info = (String) imp.getProperty("Info");
                        result.addProperty("info", info != null ? info : "");

                        // All properties
                        JsonObject propsJson = new JsonObject();
                        Properties props = imp.getProperties();
                        if (props != null) {
                            Enumeration<?> names = props.propertyNames();
                            while (names.hasMoreElements()) {
                                String key = names.nextElement().toString();
                                Object val = props.get(key);
                                if (val != null) {
                                    propsJson.addProperty(key, val.toString());
                                }
                            }
                        }
                        result.add("properties", propsJson);

                        // Calibration
                        Calibration cal = imp.getCalibration();
                        if (cal != null) {
                            JsonObject calJson = new JsonObject();
                            calJson.addProperty("pixelWidth", cal.pixelWidth);
                            calJson.addProperty("pixelHeight", cal.pixelHeight);
                            calJson.addProperty("pixelDepth", cal.pixelDepth);
                            calJson.addProperty("unit", cal.getUnit());
                            calJson.addProperty("timeUnit", cal.getTimeUnit());
                            calJson.addProperty("frameInterval", cal.frameInterval);
                            result.add("calibration", calJson);
                        }

                        holder[0] = result;
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting metadata");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleBatch(JsonObject request) {
        JsonElement commandsElement = request.get("commands");
        if (commandsElement == null || !commandsElement.isJsonArray()) {
            return errorResponse("Missing 'commands' array for batch");
        }

        JsonArray commands = commandsElement.getAsJsonArray();
        JsonArray results = new JsonArray();

        for (int i = 0; i < commands.size(); i++) {
            JsonElement elem = commands.get(i);
            if (elem.isJsonObject()) {
                JsonObject subResult = dispatch(elem.getAsJsonObject());
                results.add(subResult);
            } else {
                results.add(errorResponse("Invalid batch command at index " + i));
            }
        }

        return successResponse(results);
    }

    // -----------------------------------------------------------------------
    // Phase 4: ||| shorthand chain
    // -----------------------------------------------------------------------

    /**
     * Execute a {@code |||}-delimited chain of commands in order. Each segment
     * is parsed by {@link BatchParser} into a command object and dispatched
     * through the normal pipeline. Halts on the first failure by default;
     * pass {@code "halt_on_error": false} to execute the whole chain best-effort.
     *
     * <p>Request:
     * <pre>
     *   {"command": "run", "chain": "run('Blobs (25K)') ||| run('Invert') ||| capture inverted"}
     *   {"command": "run", "chain": "...", "halt_on_error": false}
     * </pre>
     *
     * <p>Response:
     * <pre>
     *   {"ok": true, "result": {"results": [...], "executed": N, "total": M, "halted": bool}}
     * </pre>
     */
    private JsonObject handleRunChain(JsonObject request) {
        JsonElement chainEl = request.get("chain");
        if (chainEl == null || !chainEl.isJsonPrimitive()) {
            return errorResponse("Missing 'chain' string for run command");
        }
        boolean haltOnError = true;
        JsonElement haltEl = request.get("halt_on_error");
        if (haltEl != null && haltEl.isJsonPrimitive()) {
            haltOnError = haltEl.getAsBoolean();
        }

        List<JsonObject> segments;
        try {
            segments = BatchParser.parse(chainEl.getAsString());
        } catch (Exception e) {
            return errorResponse("Chain parse error: " + e.getMessage());
        }
        if (segments.isEmpty()) {
            // No segments after split is a no-op, not a failure. Returning ok
            // avoids polluting the friction log when an agent passes a blank
            // or whitespace-only chain.
            JsonObject empty = new JsonObject();
            empty.add("results", new JsonArray());
            empty.addProperty("executed", 0);
            empty.addProperty("total", 0);
            empty.addProperty("halted", false);
            empty.addProperty("note", "empty chain — no segments after splitting on '|||'");
            return successResponse(empty);
        }

        JsonArray results = new JsonArray();
        boolean halted = false;
        int firstFailureIdx = -1;

        for (int i = 0; i < segments.size(); i++) {
            JsonObject subReq = segments.get(i);
            JsonObject subResp = dispatch(subReq);
            results.add(subResp);

            boolean failed = isFailure(subResp);
            if (failed && firstFailureIdx < 0) firstFailureIdx = i;
            if (failed && haltOnError) {
                halted = true;
                break;
            }
        }

        JsonObject out = new JsonObject();
        out.add("results", results);
        out.addProperty("executed", results.size());
        out.addProperty("total", segments.size());
        out.addProperty("halted", halted);
        if (firstFailureIdx >= 0) {
            out.addProperty("firstFailureIndex", firstFailureIdx);
        }
        return successResponse(out);
    }

    private boolean isFailure(JsonObject resp) {
        if (resp == null) return true;
        JsonElement okEl = resp.get("ok");
        if (okEl == null || !okEl.isJsonPrimitive() || !okEl.getAsBoolean()) return true;
        JsonElement r = resp.get("result");
        if (r != null && r.isJsonObject()) {
            JsonElement success = r.getAsJsonObject().get("success");
            if (success != null && success.isJsonPrimitive() && !success.getAsBoolean()) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Phase 6: friction log queries
    // -----------------------------------------------------------------------

    private JsonObject handleGetFrictionLog(JsonObject request) {
        int limit = 20;
        JsonElement limEl = request.get("limit");
        if (limEl != null && limEl.isJsonPrimitive()) {
            try {
                limit = Math.max(1, Math.min(FrictionLog.CAPACITY, limEl.getAsInt()));
            } catch (Exception ignore) {}
        }

        List<FrictionLog.FailureEntry> recent = frictionLog.recent(limit);
        JsonArray arr = new JsonArray();
        for (FrictionLog.FailureEntry e : recent) {
            JsonObject o = new JsonObject();
            o.addProperty("ts", e.ts);
            o.addProperty("command", e.command);
            o.addProperty("args", e.argsSummary);
            o.addProperty("error", e.error);
            o.addProperty("normalised", e.normalisedError);
            arr.add(o);
        }

        JsonObject result = new JsonObject();
        result.addProperty("total", frictionLog.size());
        result.addProperty("returned", arr.size());
        result.add("entries", arr);
        return successResponse(result);
    }

    private JsonObject handleGetFrictionPatterns() {
        List<FrictionLog.Pattern> patterns = frictionLog.patterns();
        JsonArray arr = new JsonArray();
        for (FrictionLog.Pattern p : patterns) {
            JsonObject o = new JsonObject();
            o.addProperty("command", p.command);
            o.addProperty("normalised", p.normalisedError);
            o.addProperty("sample", p.sampleError);
            o.addProperty("count", p.count);
            o.addProperty("firstTs", p.firstTs);
            o.addProperty("lastTs", p.lastTs);
            arr.add(o);
        }
        JsonObject result = new JsonObject();
        result.addProperty("windowMs", FrictionLog.WINDOW_MS);
        result.addProperty("threshold", FrictionLog.PATTERN_THRESHOLD);
        result.add("patterns", arr);
        return successResponse(result);
    }

    private JsonObject handleClearFrictionLog() {
        int before = frictionLog.size();
        frictionLog.clear();
        JsonObject result = new JsonObject();
        result.addProperty("cleared", before);
        return successResponse(result);
    }

    // -----------------------------------------------------------------------
    // Phase 3: async job commands
    // -----------------------------------------------------------------------

    /**
     * Submit a macro for asynchronous execution. Returns immediately with a
     * job id; progress/completion/failure are published via the event bus as
     * {@code job.*} events (Phase 2), and the client can also poll
     * {@code job_status}. For short macros prefer the synchronous
     * {@code execute_macro} — this command only wins when the caller cannot
     * afford to block the socket.
     */
    private JsonObject handleExecuteMacroAsync(JsonObject request) {
        JsonElement codeEl = request.get("code");
        if (codeEl == null || !codeEl.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for execute_macro_async");
        }
        String code = codeEl.getAsString();
        JobRegistry.Job job = jobRegistry.submit(code);
        JsonObject result = new JsonObject();
        result.addProperty("job_id", job.id);
        result.addProperty("state", job.state);
        result.addProperty("startedAt", job.startedAt);
        return successResponse(result);
    }

    private JsonObject handleJobStatus(JsonObject request) {
        JsonElement idEl = request.get("job_id");
        if (idEl == null || !idEl.isJsonPrimitive()) {
            return errorResponse("Missing 'job_id' for job_status");
        }
        String id = idEl.getAsString();
        JobRegistry.Job j = jobRegistry.get(id);
        if (j == null) return errorResponse("Unknown job_id: " + id);
        return successResponse(jobRegistry.toJson(j));
    }

    private JsonObject handleJobCancel(JsonObject request) {
        JsonElement idEl = request.get("job_id");
        if (idEl == null || !idEl.isJsonPrimitive()) {
            return errorResponse("Missing 'job_id' for job_cancel");
        }
        String id = idEl.getAsString();
        JobRegistry.Job j = jobRegistry.get(id);
        if (j == null) return errorResponse("Unknown job_id: " + id);
        boolean signalled = jobRegistry.cancel(id);
        JsonObject result = new JsonObject();
        result.addProperty("job_id", id);
        result.addProperty("cancelled", signalled);
        result.addProperty("state", j.state);
        return successResponse(result);
    }

    private JsonObject handleJobList() {
        List<JobRegistry.Job> all = jobRegistry.list();
        JsonArray arr = new JsonArray();
        for (JobRegistry.Job j : all) arr.add(jobRegistry.toJson(j));
        JsonObject result = new JsonObject();
        result.addProperty("count", arr.size());
        result.add("jobs", arr);
        return successResponse(result);
    }

    // -----------------------------------------------------------------------
    // Phase 5: intent router
    // -----------------------------------------------------------------------

    /**
     * Resolve a phrase via {@link IntentRouter}. On hit, builds a synthetic
     * {@code execute_macro} request and dispatches it — the regular macro
     * path handles macro.started / macro.completed events, dialog capture,
     * and friction logging. The outer response is wrapped with
     * {@code mapped_to} describing which mapping fired. On miss, returns
     * {@code {ok: false, miss: true, suggestion: null}}.
     */
    private JsonObject handleIntent(JsonObject request) {
        JsonElement phraseEl = request.get("phrase");
        if (phraseEl == null || !phraseEl.isJsonPrimitive()) {
            return errorResponse("Missing 'phrase' field for intent");
        }
        String phrase = phraseEl.getAsString();

        java.util.Optional<IntentRouter.Resolved> opt = intentRouter.resolve(phrase);
        if (!opt.isPresent()) {
            JsonObject miss = new JsonObject();
            miss.addProperty("ok", false);
            miss.addProperty("miss", true);
            miss.add("suggestion", JsonNull.INSTANCE);
            return miss;
        }
        IntentRouter.Resolved resolved = opt.get();

        JsonObject macroReq = new JsonObject();
        macroReq.addProperty("command", "execute_macro");
        macroReq.addProperty("code", resolved.macro);
        JsonObject execResp = handleExecuteMacro(macroReq);

        JsonObject mappedTo = new JsonObject();
        mappedTo.addProperty("pattern", resolved.mapping.patternSrc);
        if (resolved.mapping.description != null) {
            mappedTo.addProperty("description", resolved.mapping.description);
        }
        mappedTo.addProperty("macro", resolved.macro);
        mappedTo.addProperty("hits", resolved.mapping.hits);
        execResp.add("mapped_to", mappedTo);
        return execResp;
    }

    private JsonObject handleIntentTeach(JsonObject request) {
        JsonElement phraseEl = request.get("phrase");
        JsonElement macroEl = request.get("macro");
        if (phraseEl == null || !phraseEl.isJsonPrimitive()) {
            return errorResponse("Missing 'phrase' field for intent_teach");
        }
        if (macroEl == null || !macroEl.isJsonPrimitive()) {
            return errorResponse("Missing 'macro' field for intent_teach");
        }
        String phrase = phraseEl.getAsString();
        String macro = macroEl.getAsString();
        String description = null;
        JsonElement descEl = request.get("description");
        if (descEl != null && descEl.isJsonPrimitive()) {
            description = descEl.getAsString();
        }

        IntentRouter.Mapping m;
        try {
            m = intentRouter.teach(phrase, macro, description);
        } catch (IllegalArgumentException e) {
            return errorResponse(e.getMessage());
        }

        JsonObject result = new JsonObject();
        result.addProperty("saved", true);
        result.add("mapping", intentRouter.mappingToJson(m));
        result.addProperty("path", intentRouter.getStorePath().toString());
        return successResponse(result);
    }

    private JsonObject handleIntentList() {
        return successResponse(intentRouter.list());
    }

    private JsonObject handleIntentForget(JsonObject request) {
        JsonElement phraseEl = request.get("phrase");
        if (phraseEl == null || !phraseEl.isJsonPrimitive()) {
            return errorResponse("Missing 'phrase' field for intent_forget");
        }
        boolean removed = intentRouter.forget(phraseEl.getAsString());
        JsonObject result = new JsonObject();
        result.addProperty("removed", removed);
        return successResponse(result);
    }

    // -----------------------------------------------------------------------
    // JSON helpers
    // -----------------------------------------------------------------------

    private JsonObject successResponse(JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("result", result);
        return response;
    }

    private JsonObject errorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", message);

        // ALWAYS attach open dialogs to error responses.
        // Errors are exactly when dialogs are most likely to appear.
        try {
            JsonArray dialogs = detectOpenDialogs();
            if (dialogs.size() > 0) {
                response.add("dialogs", dialogs);
            }
        } catch (Exception ignore) {
            // Best-effort
        }

        return response;
    }

    private String errorJson(String message) {
        JsonObject response = errorResponse(message);
        return GSON.toJson(response);
    }

    private JsonObject executionResultToJson(ExecutionResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("success", result.isSuccess());
        if (result.isSuccess()) {
            json.addProperty("output", result.getOutput() != null ? result.getOutput() : "");
            json.addProperty("resultsTable", result.getResultsTable() != null ? result.getResultsTable() : "");
            JsonArray newImages = new JsonArray();
            for (String img : result.getNewImages()) {
                newImages.add(new JsonPrimitive(img));
            }
            json.add("newImages", newImages);
            json.addProperty("executionTimeMs", result.getExecutionTimeMs());
        } else {
            json.addProperty("error", result.getError() != null ? result.getError() : "Unknown error");
        }
        return json;
    }

    private JsonObject imageInfoToJson(ImageInfo info) {
        JsonObject json = new JsonObject();
        json.addProperty("title", info.getTitle());
        json.addProperty("width", info.getWidth());
        json.addProperty("height", info.getHeight());
        json.addProperty("type", info.getType());
        json.addProperty("slices", info.getSlices());
        json.addProperty("channels", info.getChannels());
        json.addProperty("frames", info.getFrames());
        json.addProperty("calibration", info.getCalibration());
        json.addProperty("isStack", info.isStack());
        json.addProperty("isHyperstack", info.isHyperstack());
        return json;
    }

    /**
     * Base64-encode a byte array. Java 8 compatible using javax.xml.bind
     * or manual implementation since java.util.Base64 requires Java 8 update.
     */
    private static String base64Encode(byte[] data) {
        // java.util.Base64 is available in Java 8
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    /**
     * Return raw pixel data for the active image (or a region of it).
     * Supports optional parameters: x, y, width, height, slice.
     * Returns base64-encoded raw pixel values as floats (4 bytes each),
     * plus metadata for reconstruction.
     *
     * Request:
     *   {"command": "get_pixels"}                              — full current slice
     *   {"command": "get_pixels", "slice": 5}                  — full slice 5
     *   {"command": "get_pixels", "x":10, "y":10, "width":100, "height":100}  — region
     *   {"command": "get_pixels", "allSlices": true}           — entire stack
     */
    private JsonObject handleGetPixels(JsonObject request) {
        // Parse optional parameters
        final int reqX = request.has("x") ? request.get("x").getAsInt() : -1;
        final int reqY = request.has("y") ? request.get("y").getAsInt() : -1;
        final int reqW = request.has("width") ? request.get("width").getAsInt() : -1;
        final int reqH = request.has("height") ? request.get("height").getAsInt() : -1;
        final int reqSlice = request.has("slice") ? request.get("slice").getAsInt() : -1;
        final boolean allSlices = request.has("allSlices") && request.get("allSlices").getAsBoolean();

        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImagePlus imp = WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                        return;
                    }

                    int imgW = imp.getWidth();
                    int imgH = imp.getHeight();
                    int nSlices = imp.getStackSize();

                    // Determine region
                    int x = reqX >= 0 ? Math.min(reqX, imgW - 1) : 0;
                    int y = reqY >= 0 ? Math.min(reqY, imgH - 1) : 0;
                    int w = reqW > 0 ? Math.min(reqW, imgW - x) : imgW - x;
                    int h = reqH > 0 ? Math.min(reqH, imgH - y) : imgH - y;

                    // Determine slices to extract
                    int startSlice, endSlice;
                    if (allSlices) {
                        startSlice = 1;
                        endSlice = nSlices;
                    } else if (reqSlice > 0) {
                        startSlice = Math.min(reqSlice, nSlices);
                        endSlice = startSlice;
                    } else {
                        startSlice = imp.getCurrentSlice();
                        endSlice = startSlice;
                    }
                    int sliceCount = endSlice - startSlice + 1;

                    // Safety: limit total pixels to avoid OOM
                    long totalPixels = (long) w * h * sliceCount;
                    if (totalPixels > 4000000) { // ~16MB as floats
                        holder[0] = new Exception("Region too large: " + totalPixels
                                + " pixels. Max 4M. Use x/y/width/height to crop.");
                        return;
                    }

                    // Extract pixel values as floats
                    float[] allPixels = new float[w * h * sliceCount];
                    int offset = 0;
                    for (int s = startSlice; s <= endSlice; s++) {
                        imp.setSliceWithoutUpdate(s);
                        ij.process.ImageProcessor ip = imp.getProcessor();
                        for (int py = y; py < y + h; py++) {
                            for (int px = x; px < x + w; px++) {
                                allPixels[offset++] = ip.getPixelValue(px, py);
                            }
                        }
                    }

                    // Convert float array to bytes then base64
                    byte[] rawBytes = new byte[allPixels.length * 4];
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(rawBytes);
                    buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (float v : allPixels) {
                        buf.putFloat(v);
                    }
                    String b64 = base64Encode(rawBytes);

                    JsonObject result = new JsonObject();
                    result.addProperty("x", x);
                    result.addProperty("y", y);
                    result.addProperty("width", w);
                    result.addProperty("height", h);
                    result.addProperty("sliceStart", startSlice);
                    result.addProperty("sliceEnd", endSlice);
                    result.addProperty("sliceCount", sliceCount);
                    result.addProperty("nPixels", allPixels.length);
                    result.addProperty("type", imp.getBitDepth() + "-bit");
                    result.addProperty("encoding", "base64_float32_le");
                    result.addProperty("data", b64);

                    holder[0] = result;
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(30000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting pixels");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    /**
     * Control the 3D Viewer via reflection (avoids compile-time dependency).
     *
     * Actions:
     *   {"command": "3d_viewer", "action": "status"}
     *   {"command": "3d_viewer", "action": "add", "image": "title", "type": "volume", "threshold": 50}
     *   {"command": "3d_viewer", "action": "list"}
     *   {"command": "3d_viewer", "action": "snapshot", "width": 512, "height": 512}
     *   {"command": "3d_viewer", "action": "close"}
     *
     * type: "volume" (0), "orthoslice" (1), "surface" (2), "surface_plot" (3)
     */
    private JsonObject handle3DViewer(JsonObject request) {
        String action = request.has("action") ? request.get("action").getAsString() : "status";

        // Run directly on TCP handler thread — 3D Viewer operations (especially
        // addContent) block for a long time during rendering. Running on EDT
        // would freeze the entire UI. The 3D Viewer manages its own threading.
        try {
            JsonObject result = dispatch3DViewer(action, request);
            return successResponse(result);
        } catch (Exception e) {
            return errorResponse("3D Viewer error: " + e.getMessage());
        }
    }

    private JsonObject dispatch3DViewer(String action, JsonObject request) throws Exception {
        // Use reflection to access ij3d classes
        Class<?> universeClass;
        try {
            universeClass = Class.forName("ij3d.Image3DUniverse");
        } catch (ClassNotFoundException e) {
            JsonObject result = new JsonObject();
            result.addProperty("error", "3D Viewer plugin not installed");
            result.addProperty("installed", false);
            return result;
        }

        // Get or create the universe instance — check cache first
        Object universe = cached3DUniverse;

        // Verify cached reference is still valid (window might have been closed)
        if (universe != null) {
            try {
                java.lang.reflect.Method getCanvas = universeClass.getMethod("getCanvas");
                Object canvas = getCanvas.invoke(universe);
                if (canvas == null) {
                    universe = null; // Universe was closed
                    cached3DUniverse = null;
                }
            } catch (Exception e) {
                universe = null;
                cached3DUniverse = null;
            }
        }

        // Try the static accessor if no cached reference
        if (universe == null) {
            try {
                Class<?> viewerClass = Class.forName("ij3d.ImageJ_3D_Viewer");
                java.lang.reflect.Method getUniv = viewerClass.getMethod("getUniverse");
                universe = getUniv.invoke(null);
                if (universe != null) {
                    cached3DUniverse = universe;
                }
            } catch (Exception ignore) {
                // No universe available
            }
        }

        JsonObject result = new JsonObject();

        if ("status".equals(action)) {
            result.addProperty("installed", true);
            result.addProperty("open", universe != null);
            if (universe != null) {
                try {
                    // getContents() returns Iterator or Collection
                    java.lang.reflect.Method getContents = universeClass.getMethod("getContents");
                    Object contents = getContents.invoke(universe);
                    int count = 0;
                    JsonArray contentNames = new JsonArray();
                    if (contents instanceof java.util.Collection) {
                        for (Object c : (java.util.Collection<?>) contents) {
                            java.lang.reflect.Method getName = c.getClass().getMethod("getName");
                            String name = (String) getName.invoke(c);
                            contentNames.add(new JsonPrimitive(name != null ? name : "unnamed"));
                            count++;
                        }
                    }
                    result.addProperty("contentCount", count);
                    result.add("contents", contentNames);
                } catch (Exception e) {
                    result.addProperty("contentError", e.getMessage());
                }
            }
            return result;

        } else if ("add".equals(action)) {
            String imageName = request.has("image") ? request.get("image").getAsString() : null;
            if (imageName == null) {
                result.addProperty("error", "Missing 'image' parameter");
                return result;
            }

            // Find the ImagePlus
            ImagePlus imp = WindowManager.getImage(imageName);
            if (imp == null) {
                result.addProperty("error", "Image not found: " + imageName);
                return result;
            }

            String typeStr = request.has("type") ? request.get("type").getAsString() : "volume";
            int threshold = request.has("threshold") ? request.get("threshold").getAsInt() : 50;
            int resamplingFactor = request.has("resampling") ? request.get("resampling").getAsInt() : 1;

            // Map type string to int: volume=0, orthoslice=1, surface=2, surface_plot=3
            int typeInt = 0;
            if ("orthoslice".equals(typeStr)) typeInt = 1;
            else if ("surface".equals(typeStr)) typeInt = 2;
            else if ("surface_plot".equals(typeStr)) typeInt = 3;

            // Create universe if needed
            if (universe == null) {
                java.lang.reflect.Constructor<?> ctor = universeClass.getConstructor();
                universe = ctor.newInstance();
                cached3DUniverse = universe; // Cache immediately
                java.lang.reflect.Method show = universeClass.getMethod("show");
                show.invoke(universe);
                // Store it via the static setter if available
                try {
                    Class<?> viewerClass = Class.forName("ij3d.ImageJ_3D_Viewer");
                    java.lang.reflect.Field univField = viewerClass.getDeclaredField("univ");
                    univField.setAccessible(true);
                    univField.set(null, universe);
                } catch (Exception ignore) {}
            }

            // Try multiple addContent signatures — API varies between versions
            // Use method scanning instead of getMethod() to avoid classloader mismatches
            // (ImagePlus.class from our classloader may differ from the 3D Viewer's)
            Object content = null;
            String methodUsed = "";

            // Collect all addContent methods
            java.lang.reflect.Method method2 = null; // (ImagePlus, int)
            java.lang.reflect.Method method3 = null; // (ImagePlus, int, int)
            java.lang.reflect.Method method7 = null; // (ImagePlus, Color3f, String, int, boolean[], int, int)
            for (java.lang.reflect.Method m : universeClass.getMethods()) {
                if (!"addContent".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[1] == int.class) {
                    method2 = m;
                } else if (params.length == 3 && params[1] == int.class && params[2] == int.class) {
                    method3 = m;
                } else if (params.length == 7 && params[2] == String.class) {
                    method7 = m;
                }
            }

            // Attempt 1: full signature with Color3f
            if (method7 != null) {
                try {
                    Class<?> color3fClass = method7.getParameterTypes()[1];
                    java.lang.reflect.Constructor<?> colorCtor = color3fClass.getConstructor(float.class, float.class, float.class);
                    Object white = colorCtor.newInstance(1.0f, 1.0f, 1.0f);
                    boolean[] channels = new boolean[]{true, true, true};
                    content = method7.invoke(universe, imp, white, imageName,
                            threshold, channels, resamplingFactor, typeInt);
                    methodUsed = "addContent(7-arg)";
                } catch (Exception e1) {
                    // fall through
                }
            }
            // Attempt 2: addContent(ImagePlus, int, int)
            if (content == null && method3 != null) {
                try {
                    content = method3.invoke(universe, imp, typeInt, resamplingFactor);
                    methodUsed = "addContent(ImagePlus, int, int)";
                } catch (Exception e2) {
                    // fall through
                }
            }
            // Attempt 3: addContent(ImagePlus, int)
            if (content == null && method2 != null) {
                try {
                    content = method2.invoke(universe, imp, typeInt);
                    methodUsed = "addContent(ImagePlus, int)";
                } catch (Exception e3) {
                    // fall through
                }
            }
            if (content == null) {
                StringBuilder methods = new StringBuilder();
                for (java.lang.reflect.Method m : universeClass.getMethods()) {
                    if ("addContent".equals(m.getName())) {
                        methods.append(m.toString()).append("; ");
                    }
                }
                result.addProperty("error", "addContent failed. Available: " + methods.toString());
                return result;
            }

            if (content != null) {
                // Set threshold if applicable
                try {
                    java.lang.reflect.Method setThreshold = content.getClass().getMethod("setThreshold", int.class);
                    setThreshold.invoke(content, threshold);
                } catch (Exception ignore) {}

                try {
                    java.lang.reflect.Method getName = content.getClass().getMethod("getName");
                    result.addProperty("added", (String) getName.invoke(content));
                } catch (Exception ignore) {
                    result.addProperty("added", imageName);
                }
                result.addProperty("success", true);
                result.addProperty("method", methodUsed);
            } else {
                // List available methods for debugging
                StringBuilder methods = new StringBuilder();
                for (java.lang.reflect.Method m : universeClass.getMethods()) {
                    if ("addContent".equals(m.getName())) {
                        methods.append(m.toString()).append("; ");
                    }
                }
                result.addProperty("error", "addContent returned null via " + methodUsed);
                result.addProperty("availableMethods", methods.toString());
            }
            return result;

        } else if ("list".equals(action)) {
            if (universe == null) {
                result.addProperty("open", false);
                result.add("contents", new JsonArray());
                return result;
            }
            result.addProperty("open", true);
            try {
                java.lang.reflect.Method getContents = universeClass.getMethod("getContents");
                Object contents = getContents.invoke(universe);
                JsonArray contentList = new JsonArray();
                if (contents instanceof java.util.Collection) {
                    for (Object c : (java.util.Collection<?>) contents) {
                        JsonObject entry = new JsonObject();
                        try {
                            java.lang.reflect.Method getName = c.getClass().getMethod("getName");
                            entry.addProperty("name", (String) getName.invoke(c));
                        } catch (Exception ignore) {}
                        try {
                            java.lang.reflect.Method isVisible = c.getClass().getMethod("isVisible");
                            entry.addProperty("visible", (Boolean) isVisible.invoke(c));
                        } catch (Exception ignore) {}
                        contentList.add(entry);
                    }
                }
                result.add("contents", contentList);
            } catch (Exception e) {
                result.addProperty("error", e.getMessage());
            }
            return result;

        } else if ("snapshot".equals(action)) {
            if (universe == null) {
                result.addProperty("error", "3D Viewer not open");
                return result;
            }
            int width = request.has("width") ? request.get("width").getAsInt() : 512;
            int height = request.has("height") ? request.get("height").getAsInt() : 512;

            try {
                java.lang.reflect.Method takeSnapshot = universeClass.getMethod("takeSnapshot", int.class, int.class);
                Object snapshot = takeSnapshot.invoke(universe, width, height);
                if (snapshot instanceof ImagePlus) {
                    ((ImagePlus) snapshot).show();
                    result.addProperty("success", true);
                    result.addProperty("title", ((ImagePlus) snapshot).getTitle());
                } else {
                    result.addProperty("error", "Snapshot did not return an ImagePlus");
                }
            } catch (Exception e) {
                result.addProperty("error", "Snapshot failed: " + e.getMessage());
            }
            return result;

        } else if ("capture".equals(action)) {
            // Screenshot the 3D Viewer window using java.awt.Robot.
            // Brings the window to front first to avoid overlapping windows.
            if (universe == null) {
                result.addProperty("error", "3D Viewer not open");
                return result;
            }
            try {
                // Find the 3D Viewer window
                java.awt.Window viewerWindow = null;
                java.awt.Window[] allWindows = java.awt.Window.getWindows();
                for (java.awt.Window w : allWindows) {
                    if (w.isShowing() && w.getClass().getName().contains("ImageWindow3D")) {
                        viewerWindow = w;
                        break;
                    }
                }
                if (viewerWindow == null) {
                    for (java.awt.Window w : allWindows) {
                        if (w.isShowing() && w instanceof java.awt.Frame) {
                            String title = ((java.awt.Frame) w).getTitle();
                            if (title != null && title.contains("3D")) {
                                viewerWindow = w;
                                break;
                            }
                        }
                    }
                }

                if (viewerWindow == null) {
                    result.addProperty("error", "Could not find 3D Viewer window");
                    return result;
                }

                // Bring window to front and wait for it to render
                final java.awt.Window finalWindow = viewerWindow;
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        finalWindow.toFront();
                        finalWindow.requestFocus();
                    }
                });
                Thread.sleep(500); // Wait for window to come to front and repaint

                // Capture just the content area (exclude title bar and borders)
                java.awt.Rectangle bounds = viewerWindow.getBounds();
                java.awt.Insets insets = viewerWindow.getInsets();
                java.awt.Rectangle contentBounds = new java.awt.Rectangle(
                        bounds.x + insets.left,
                        bounds.y + insets.top,
                        bounds.width - insets.left - insets.right,
                        bounds.height - insets.top - insets.bottom
                );

                java.awt.Robot robot = new java.awt.Robot();
                java.awt.image.BufferedImage screenshot = robot.createScreenCapture(contentBounds);

                // Convert to ImagePlus and show
                ImagePlus snap = new ImagePlus("3D_Render", screenshot);
                snap.show();

                result.addProperty("success", true);
                result.addProperty("title", "3D_Render");
                result.addProperty("width", contentBounds.width);
                result.addProperty("height", contentBounds.height);
            } catch (Exception e) {
                result.addProperty("error", "Capture failed: " + e.getMessage());
            }
            return result;

        } else if ("fit".equals(action) || "reset_view".equals(action)) {
            if (universe == null) {
                result.addProperty("error", "3D Viewer not open");
                return result;
            }
            try {
                // Try resetView first — fits all content into view
                java.lang.reflect.Method resetView = universeClass.getMethod("resetView");
                resetView.invoke(universe);
                result.addProperty("success", true);
            } catch (Exception e) {
                // Try centerSelected as fallback
                try {
                    java.lang.reflect.Method cs = universeClass.getMethod("centerSelected",
                            Class.forName("ij3d.Content"));
                    java.lang.reflect.Method getSelected = universeClass.getMethod("getSelected");
                    Object selected = getSelected.invoke(universe);
                    if (selected != null) {
                        cs.invoke(universe, selected);
                    }
                    result.addProperty("success", true);
                } catch (Exception e2) {
                    result.addProperty("error", "Fit failed: " + e.getMessage());
                }
            }
            return result;

        } else if ("close".equals(action)) {
            if (universe != null) {
                try {
                    java.lang.reflect.Method close = universeClass.getMethod("close");
                    close.invoke(universe);
                    result.addProperty("closed", true);
                } catch (Exception e) {
                    result.addProperty("error", "Close failed: " + e.getMessage());
                }
            } else {
                result.addProperty("closed", false);
                result.addProperty("error", "3D Viewer not open");
            }
            return result;

        } else {
            result.addProperty("error", "Unknown action: " + action + ". Use: status, add, list, snapshot, close");
            return result;
        }
    }

    private JsonObject handleGetDialogs() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = detectOpenDialogs();
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out detecting dialogs");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }

        JsonObject result = new JsonObject();
        result.add("dialogs", (JsonArray) holder[0]);
        return successResponse(result);
    }

    /**
     * Scan all open windows for dialogs and extract their text content.
     * Returns a JsonArray of dialog objects with title, text, type, and buttons.
     */
    private JsonArray detectOpenDialogs() {
        JsonArray dialogs = new JsonArray();
        Window[] windows = Window.getWindows();

        for (Window win : windows) {
            if (!win.isShowing()) continue;

            // Only interested in Dialog windows (error popups, prompts, etc.)
            if (!(win instanceof Dialog)) continue;

            Dialog dlg = (Dialog) win;
            String title = dlg.getTitle();
            if (title == null) title = "";

            // Skip the AI Assistant window itself
            if (title.contains("AI Assistant")) continue;

            // Extract all text from the dialog's components
            StringBuilder textContent = new StringBuilder();
            List<String> buttonLabels = new ArrayList<String>();
            extractDialogContent(dlg, textContent, buttonLabels);

            JsonObject dialogInfo = new JsonObject();
            dialogInfo.addProperty("title", title);
            dialogInfo.addProperty("text", textContent.toString().trim());
            dialogInfo.addProperty("modal", dlg.isModal());

            JsonArray buttons = new JsonArray();
            for (String label : buttonLabels) {
                buttons.add(new JsonPrimitive(label));
            }
            dialogInfo.add("buttons", buttons);

            // Classify dialog type
            String text = textContent.toString().toLowerCase();
            if (text.contains("error") || text.contains("exception") || text.contains("failed")) {
                dialogInfo.addProperty("type", "error");
            } else if (text.contains("warning") || text.contains("caution")) {
                dialogInfo.addProperty("type", "warning");
            } else if (buttonLabels.contains("OK") && buttonLabels.contains("Cancel")) {
                dialogInfo.addProperty("type", "prompt");
            } else {
                dialogInfo.addProperty("type", "info");
            }

            dialogs.add(dialogInfo);
        }

        return dialogs;
    }

    /**
     * Recursively extract ALL readable content from a dialog's component tree.
     * Reads labels, text fields, dropdowns, checkboxes, sliders, spinners,
     * text areas, and buttons — everything needed to understand any dialog.
     */
    private void extractDialogContent(Container container, StringBuilder text, List<String> buttons) {
        for (Component comp : container.getComponents()) {
            // --- Labels ---
            if (comp instanceof javax.swing.JLabel) {
                String s = ((javax.swing.JLabel) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }
            } else if (comp instanceof java.awt.Label) {
                String s = ((java.awt.Label) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }

            // --- Buttons ---
            } else if (comp instanceof javax.swing.JButton) {
                String s = ((javax.swing.JButton) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    buttons.add(s.trim());
                }
            } else if (comp instanceof java.awt.Button) {
                String s = ((java.awt.Button) comp).getLabel();
                if (s != null && !s.trim().isEmpty()) {
                    buttons.add(s.trim());
                }

            // --- Text input fields ---
            } else if (comp instanceof javax.swing.JTextField) {
                String s = ((javax.swing.JTextField) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append("[field: ").append(s.trim()).append("]\n");
                }
            } else if (comp instanceof java.awt.TextField) {
                String s = ((java.awt.TextField) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append("[field: ").append(s.trim()).append("]\n");
                }

            // --- Text areas ---
            } else if (comp instanceof javax.swing.JTextArea) {
                String s = ((javax.swing.JTextArea) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }
            } else if (comp instanceof java.awt.TextArea) {
                String s = ((java.awt.TextArea) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }

            // --- Dropdowns / Choice ---
            } else if (comp instanceof javax.swing.JComboBox) {
                javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) comp;
                Object selected = combo.getSelectedItem();
                if (selected != null) {
                    text.append("[dropdown: ").append(selected.toString()).append("]\n");
                }
            } else if (comp instanceof java.awt.Choice) {
                String s = ((java.awt.Choice) comp).getSelectedItem();
                if (s != null) {
                    text.append("[dropdown: ").append(s).append("]\n");
                }

            // --- Checkboxes ---
            } else if (comp instanceof javax.swing.JCheckBox) {
                javax.swing.JCheckBox cb = (javax.swing.JCheckBox) comp;
                String label = cb.getText();
                if (label != null && !label.trim().isEmpty()) {
                    text.append("[checkbox: ").append(label.trim())
                        .append(" = ").append(cb.isSelected() ? "ON" : "OFF").append("]\n");
                }
            } else if (comp instanceof java.awt.Checkbox) {
                java.awt.Checkbox cb = (java.awt.Checkbox) comp;
                String label = cb.getLabel();
                if (label != null && !label.trim().isEmpty()) {
                    text.append("[checkbox: ").append(label.trim())
                        .append(" = ").append(cb.getState() ? "ON" : "OFF").append("]\n");
                }

            // --- Sliders ---
            } else if (comp instanceof javax.swing.JSlider) {
                javax.swing.JSlider slider = (javax.swing.JSlider) comp;
                text.append("[slider: ").append(slider.getValue())
                    .append(" (").append(slider.getMinimum())
                    .append("-").append(slider.getMaximum()).append(")]\n");
            } else if (comp instanceof java.awt.Scrollbar) {
                java.awt.Scrollbar sb = (java.awt.Scrollbar) comp;
                text.append("[scrollbar: ").append(sb.getValue())
                    .append(" (").append(sb.getMinimum())
                    .append("-").append(sb.getMaximum()).append(")]\n");

            // --- Spinners ---
            } else if (comp instanceof javax.swing.JSpinner) {
                javax.swing.JSpinner spinner = (javax.swing.JSpinner) comp;
                text.append("[spinner: ").append(spinner.getValue()).append("]\n");
            }

            // Catch-all: try reflection for unknown components (e.g. MultiLineLabel)
            // that have getText(), getLabel(), or getMessage() methods
            if (text.indexOf(comp.getClass().getSimpleName()) < 0) {
                // Only if we haven't already extracted from this component type above
                boolean alreadyHandled = (comp instanceof javax.swing.JLabel)
                        || (comp instanceof java.awt.Label)
                        || (comp instanceof javax.swing.JButton)
                        || (comp instanceof java.awt.Button)
                        || (comp instanceof javax.swing.JTextField)
                        || (comp instanceof java.awt.TextField)
                        || (comp instanceof javax.swing.JTextArea)
                        || (comp instanceof java.awt.TextArea)
                        || (comp instanceof javax.swing.JComboBox)
                        || (comp instanceof java.awt.Choice)
                        || (comp instanceof javax.swing.JCheckBox)
                        || (comp instanceof java.awt.Checkbox)
                        || (comp instanceof javax.swing.JSlider)
                        || (comp instanceof java.awt.Scrollbar)
                        || (comp instanceof javax.swing.JSpinner);

                if (!alreadyHandled) {
                    int lenBefore = text.length();

                    // Try getText()
                    try {
                        java.lang.reflect.Method m = comp.getClass().getMethod("getText");
                        Object val = m.invoke(comp);
                        if (val != null && !val.toString().trim().isEmpty()) {
                            text.append(val.toString().trim()).append("\n");
                        }
                    } catch (Exception ignore) {}

                    // Try getLabel()
                    try {
                        java.lang.reflect.Method m = comp.getClass().getMethod("getLabel");
                        Object val = m.invoke(comp);
                        if (val != null && !val.toString().trim().isEmpty()) {
                            text.append(val.toString().trim()).append("\n");
                        }
                    } catch (Exception ignore) {}

                    // Try getMessage()
                    try {
                        java.lang.reflect.Method m = comp.getClass().getMethod("getMessage");
                        Object val = m.invoke(comp);
                        if (val != null && !val.toString().trim().isEmpty()) {
                            text.append(val.toString().trim()).append("\n");
                        }
                    } catch (Exception ignore) {}

                    // If no public getter yielded anything, fall back to
                    // private-field reflection. Required for
                    // ij.gui.MultiLineLabel — the Canvas subclass that
                    // GenericDialog.addMessage uses to host the real compile/
                    // runtime error text on the Macro Error dialog. Its text
                    // lives in private fields (text2 / lines[]) with no
                    // public getter on many ImageJ builds, so the method
                    // probes above silently miss it.
                    if (text.length() == lenBefore) {
                        String fieldText = readFieldText(comp);
                        if (fieldText != null && !fieldText.isEmpty()) {
                            text.append(fieldText).append("\n");
                        }
                    }
                }
            }

            // Recurse into child containers
            if (comp instanceof Container) {
                extractDialogContent((Container) comp, text, buttons);
            }
        }
    }

    /**
     * Last-resort text extraction for Component types that carry their
     * text in private fields instead of a public getter. Walks the
     * declared-field hierarchy (skipping Object) and returns the first
     * non-empty String found under a common name — {@code text2, text,
     * label, msg, message} — or a newline-joined {@code lines[]} if
     * present. The primary motivator is {@code ij.gui.MultiLineLabel},
     * which hosts the real compile/runtime error text on the Macro Error
     * dialog but has no public {@code getText()} on many ImageJ builds.
     * Returns {@code null} if nothing usable was found.
     */
    private String readFieldText(Component comp) {
        final String[] stringFields = { "text2", "text", "label", "msg", "message" };
        Class<?> cls = comp.getClass();
        while (cls != null && cls != Object.class) {
            for (String fieldName : stringFields) {
                try {
                    java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object val = f.get(comp);
                    if (val instanceof String) {
                        String s = ((String) val).trim();
                        if (!s.isEmpty()) return s;
                    }
                } catch (Exception ignore) {}
            }
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("lines");
                f.setAccessible(true);
                Object val = f.get(comp);
                if (val instanceof String[]) {
                    StringBuilder sb = new StringBuilder();
                    for (String ln : (String[]) val) {
                        if (ln != null && !ln.trim().isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(ln.trim());
                        }
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            } catch (Exception ignore) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * For custom (non-GenericDialog) dialogs where we cannot flip a
     * {@code wasCanceled} flag, scan the component tree for a button
     * whose label is a common cancel variant and programmatically fire
     * its action. Used by {@link #handleProbeCommand} so probing does
     * not accidentally execute the plugin.
     */
    private void clickCancelButton(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof java.awt.Button) {
                String lbl = ((java.awt.Button) c).getLabel();
                if (isCancelLabel(lbl)) {
                    ((java.awt.Button) c).dispatchEvent(
                            new java.awt.event.ActionEvent(c,
                                    java.awt.event.ActionEvent.ACTION_PERFORMED,
                                    lbl));
                    return;
                }
            } else if (c instanceof javax.swing.JButton) {
                String lbl = ((javax.swing.JButton) c).getText();
                if (isCancelLabel(lbl)) {
                    ((javax.swing.JButton) c).doClick();
                    return;
                }
            }
            if (c instanceof Container) {
                clickCancelButton((Container) c);
            }
        }
    }

    private boolean isCancelLabel(String lbl) {
        if (lbl == null) return false;
        String l = lbl.trim().toLowerCase();
        return l.equals("cancel") || l.equals("close") || l.equals("no");
    }

    private JsonObject handleCloseDialogs(JsonObject request) {
        JsonElement patternElement = request.get("pattern");
        final String pattern = (patternElement != null && patternElement.isJsonPrimitive())
                ? patternElement.getAsString()
                : null;

        int closed = dismissOpenDialogs(pattern);

        JsonObject result = new JsonObject();
        result.addProperty("closedCount", closed);
        return successResponse(result);
    }

    /**
     * Dismiss every non-protected modal dialog / transient frame, optionally
     * filtered by a case-insensitive title substring. Returns the count.
     *
     * Skips the main ImageJ window, the AI Assistant panel, and image
     * windows. Runs on the EDT and waits up to 2 s for completion — so it
     * can be called from background command threads (including the macro
     * watchdog inside handleExecuteMacro) without EDT violations.
     */
    private int dismissOpenDialogs(final String pattern) {
        return dismissOpenDialogsCapturing(pattern, null);
    }

    /**
     * Same as {@link #dismissOpenDialogs(String)} but records the title and
     * a short body snippet of every window it disposes, so callers can
     * surface "these popups were auto-closed during your run" in the response.
     * {@code captured} may be null when the caller does not need the detail.
     */
    private int dismissOpenDialogsCapturing(final String pattern, final JsonArray captured) {
        final int[] closedCount = new int[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    java.awt.Window[] windows = java.awt.Window.getWindows();
                    for (java.awt.Window win : windows) {
                        if (win.isShowing() && (win instanceof java.awt.Dialog || win instanceof java.awt.Frame)) {
                            String title = "";
                            if (win instanceof java.awt.Dialog) title = ((java.awt.Dialog) win).getTitle();
                            else if (win instanceof java.awt.Frame) title = ((java.awt.Frame) win).getTitle();

                            if (title == null) title = "";

                            // Never close the main ImageJ/Fiji window or the AI Assistant window
                            if (title.equals("ImageJ") || title.equals("Fiji")
                                    || title.contains("AI Assistant")
                                    || title.contains("ImageJ")
                                    || title.contains("Startup")) {
                                continue;
                            }

                            // Also protect by checking if this is the IJ main frame
                            if (win == IJ.getInstance()) {
                                continue;
                            }

                            // Don't close image windows
                            if (win instanceof ImageWindow) {
                                continue;
                            }

                            if (pattern == null || title.toLowerCase().contains(pattern.toLowerCase())) {
                                if (captured != null) {
                                    try {
                                        JsonObject entry = new JsonObject();
                                        entry.addProperty("title", title);
                                        String body = extractDialogBody(win);
                                        if (body != null && !body.isEmpty()) {
                                            if (body.length() > 400) body = body.substring(0, 400) + "…";
                                            entry.addProperty("body", body);
                                        }
                                        captured.add(entry);
                                    } catch (Throwable ignore) {}
                                }
                                win.setVisible(false);
                                win.dispose();
                                closedCount[0]++;
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return closedCount[0];
    }

    /**
     * Best-effort scrape of a dialog's text content for the
     * {@code dismissedDialogs} report. Reuses {@link #extractDialogContent}
     * to walk child components, then collapses whitespace to a single line.
     * Returns an empty string when nothing is readable.
     */
    private String extractDialogBody(java.awt.Window win) {
        if (!(win instanceof java.awt.Container)) return "";
        StringBuilder text = new StringBuilder();
        java.util.ArrayList<String> buttons = new java.util.ArrayList<String>();
        try {
            extractDialogContent((java.awt.Container) win, text, buttons);
        } catch (Throwable ignore) {
            return "";
        }
        String raw = text.toString();
        if (raw.isEmpty()) return "";
        // Collapse any run of whitespace (including embedded newlines) to a
        // single space so the captured body fits on one line.
        return raw.replaceAll("\\s+", " ").trim();
    }

    // -----------------------------------------------------------------------
    // Plugin probing — discover dialog fields and macro argument syntax
    // -----------------------------------------------------------------------

    private JsonObject handleProbeCommand(JsonObject request) {
        JsonElement pluginElement = request.get("plugin");
        if (pluginElement == null || !pluginElement.isJsonPrimitive()) {
            return errorResponse("Missing 'plugin' parameter");
        }
        final String pluginName = pluginElement.getAsString();

        // Snapshot currently showing dialogs so we can detect new ones
        final java.util.Set<Window> existing = new java.util.HashSet<Window>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) existing.add(w);
        }

        // Launch plugin on a new thread — it will show its dialog
        Thread pluginThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    IJ.doCommand(pluginName);
                } catch (Exception e) {
                    // Plugin failed — that's OK for probing
                }
            }
        });
        pluginThread.setDaemon(true);
        pluginThread.start();

        // Poll for a new dialog to appear (up to 5 seconds)
        Dialog newDialog = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(150); } catch (InterruptedException e) { break; }
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && !existing.contains(w) && w instanceof Dialog) {
                    newDialog = (Dialog) w;
                    break;
                }
            }
            if (newDialog != null) break;
        }

        JsonObject result = new JsonObject();
        result.addProperty("plugin", pluginName);

        if (newDialog == null) {
            result.addProperty("hasDialog", false);
            result.addProperty("note", "No dialog appeared within 5 seconds. "
                    + "Plugin may have no parameters, may have already executed, "
                    + "or may require an open image.");
            return successResponse(result);
        }

        // Small delay to let dialog fully render its components
        try { Thread.sleep(200); } catch (InterruptedException e) {}

        result.addProperty("hasDialog", true);
        result.addProperty("dialogTitle", newDialog.getTitle() != null ? newDialog.getTitle() : "");

        // Check if it's a GenericDialog (or subclass like NonBlockingGenericDialog)
        boolean isGD = false;
        try {
            isGD = Class.forName("ij.gui.GenericDialog").isInstance(newDialog);
        } catch (Exception e) {}

        if (isGD) {
            result.addProperty("dialogType", "GenericDialog");
            JsonArray fields = probeGenericDialogFields(newDialog);
            result.add("fields", fields);
            result.addProperty("macro_syntax", buildMacroSyntax(pluginName, fields));
        } else {
            result.addProperty("dialogType", "custom");
            StringBuilder text = new StringBuilder();
            List<String> buttons = new ArrayList<String>();
            extractDialogContent(newDialog, text, buttons);
            result.addProperty("dialog_text", text.toString().trim());
            JsonArray btnArray = new JsonArray();
            for (String b : buttons) btnArray.add(new JsonPrimitive(b));
            result.add("buttons", btnArray);
        }

        // Cancel the dialog before disposing. GenericDialog.dispose() does
        // NOT set the private wasCanceled flag, so the calling plugin reads
        // gd.wasCanceled() == false, collects the default field values, and
        // executes the plugin against the active image. Probing is supposed
        // to be side-effect-free, so we flip wasCanceled via field reflection
        // (walking the class hierarchy to cover NonBlockingGenericDialog and
        // other subclasses) before disposing. For custom non-GenericDialog
        // dialogs we fall back to clicking a Cancel-style button if the
        // plugin provides one.
        final Dialog dlg = newDialog;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                boolean canceled = false;
                Class<?> c = dlg.getClass();
                while (c != null && c != Object.class) {
                    try {
                        java.lang.reflect.Field f = c.getDeclaredField("wasCanceled");
                        f.setAccessible(true);
                        f.setBoolean(dlg, true);
                        canceled = true;
                        break;
                    } catch (NoSuchFieldException nsf) {
                        c = c.getSuperclass();
                    } catch (Exception ignore) {
                        break;
                    }
                }
                if (!canceled) {
                    clickCancelButton(dlg);
                }
                dlg.dispose();
            }
        });

        // Brief wait for disposal to complete
        try { Thread.sleep(200); } catch (InterruptedException e) {}

        return successResponse(result);
    }

    /**
     * Extract structured field information from a GenericDialog.
     * Returns a JSON array of field objects with type, label, default, options, and macro_key.
     */
    private JsonArray probeGenericDialogFields(Dialog dlg) {
        JsonArray fields = new JsonArray();
        try {
            // Walk up class hierarchy to find GenericDialog
            Class<?> gdClass = dlg.getClass();
            while (gdClass != null && !"GenericDialog".equals(gdClass.getSimpleName())) {
                gdClass = gdClass.getSuperclass();
            }
            if (gdClass == null) return fields;

            // --- Numeric fields ---
            try {
                java.lang.reflect.Method m = gdClass.getMethod("getNumericFields");
                @SuppressWarnings("unchecked")
                java.util.Vector<java.awt.TextField> numFields =
                        (java.util.Vector<java.awt.TextField>) m.invoke(dlg);
                if (numFields != null) {
                    for (java.awt.TextField tf : numFields) {
                        JsonObject f = new JsonObject();
                        f.addProperty("type", "numeric");
                        f.addProperty("default", tf.getText());
                        String label = findFieldLabel(tf);
                        f.addProperty("label", label);
                        f.addProperty("macro_key", labelToMacroKey(label));
                        fields.add(f);
                    }
                }
            } catch (Exception e) {}

            // --- String fields ---
            try {
                java.lang.reflect.Method m = gdClass.getMethod("getStringFields");
                @SuppressWarnings("unchecked")
                java.util.Vector<java.awt.TextField> strFields =
                        (java.util.Vector<java.awt.TextField>) m.invoke(dlg);
                if (strFields != null) {
                    for (java.awt.TextField tf : strFields) {
                        JsonObject f = new JsonObject();
                        f.addProperty("type", "string");
                        f.addProperty("default", tf.getText());
                        String label = findFieldLabel(tf);
                        f.addProperty("label", label);
                        f.addProperty("macro_key", labelToMacroKey(label));
                        fields.add(f);
                    }
                }
            } catch (Exception e) {}

            // --- Checkboxes ---
            try {
                java.lang.reflect.Method m = gdClass.getMethod("getCheckboxes");
                @SuppressWarnings("unchecked")
                java.util.Vector<java.awt.Checkbox> boxes =
                        (java.util.Vector<java.awt.Checkbox>) m.invoke(dlg);
                if (boxes != null) {
                    for (java.awt.Checkbox cb : boxes) {
                        JsonObject f = new JsonObject();
                        f.addProperty("type", "checkbox");
                        f.addProperty("label", cb.getLabel() != null ? cb.getLabel() : "");
                        f.addProperty("default", cb.getState());
                        f.addProperty("macro_key", labelToMacroKey(cb.getLabel()));
                        fields.add(f);
                    }
                }
            } catch (Exception e) {}

            // --- Choices (dropdowns) ---
            try {
                java.lang.reflect.Method m = gdClass.getMethod("getChoices");
                @SuppressWarnings("unchecked")
                java.util.Vector<java.awt.Choice> choices =
                        (java.util.Vector<java.awt.Choice>) m.invoke(dlg);
                if (choices != null) {
                    for (java.awt.Choice ch : choices) {
                        JsonObject f = new JsonObject();
                        f.addProperty("type", "choice");
                        f.addProperty("default", ch.getSelectedItem() != null ? ch.getSelectedItem() : "");
                        String label = findFieldLabel(ch);
                        f.addProperty("label", label);
                        f.addProperty("macro_key", labelToMacroKey(label));
                        // List ALL options
                        JsonArray opts = new JsonArray();
                        for (int i = 0; i < ch.getItemCount(); i++) {
                            opts.add(new JsonPrimitive(ch.getItem(i)));
                        }
                        f.add("options", opts);
                        fields.add(f);
                    }
                }
            } catch (Exception e) {}

            // --- Sliders ---
            try {
                java.lang.reflect.Method m = gdClass.getMethod("getSliders");
                @SuppressWarnings("unchecked")
                java.util.Vector<java.awt.Scrollbar> sliders =
                        (java.util.Vector<java.awt.Scrollbar>) m.invoke(dlg);
                if (sliders != null) {
                    for (java.awt.Scrollbar sb : sliders) {
                        JsonObject f = new JsonObject();
                        f.addProperty("type", "slider");
                        f.addProperty("value", sb.getValue());
                        f.addProperty("min", sb.getMinimum());
                        f.addProperty("max", sb.getMaximum());
                        String label = findFieldLabel(sb);
                        f.addProperty("label", label);
                        f.addProperty("macro_key", labelToMacroKey(label));
                        fields.add(f);
                    }
                }
            } catch (Exception e) {}

        } catch (Exception e) {
            // Couldn't access GenericDialog methods
        }
        return fields;
    }

    /**
     * Find the label associated with a field component in a GenericDialog.
     * Walks backwards from the field's own position to find the nearest
     * preceding Label — the original implementation returned the first
     * Label in the panel, which mislabelled every field after the first
     * (all of Analyze Particles' numeric, string and choice fields ended
     * up sharing the label "Size (pixel^2):").
     */
    private String findFieldLabel(java.awt.Component field) {
        java.awt.Container parent = field.getParent();
        if (parent == null) return "";

        // Same-panel case: locate the field's own index then walk backwards
        // for the closest preceding Label. Panels created by GenericDialog
        // usually contain only one Label + one field, but some composite
        // panels hold more — we want the one directly before this field.
        java.awt.Component[] siblings = parent.getComponents();
        int fieldIdx = -1;
        for (int i = 0; i < siblings.length; i++) {
            if (siblings[i] == field) {
                fieldIdx = i;
                break;
            }
        }
        if (fieldIdx > 0) {
            for (int j = fieldIdx - 1; j >= 0; j--) {
                if (siblings[j] instanceof java.awt.Label) {
                    String text = ((java.awt.Label) siblings[j]).getText();
                    if (text != null && !text.trim().isEmpty()) {
                        return text.trim();
                    }
                }
            }
        }

        // Sliders / some composite fields sit in their own Panel. Walk the
        // grandparent backwards for the Label that precedes this Panel,
        // stopping at the previous Panel boundary.
        java.awt.Container grandparent = parent.getParent();
        if (grandparent != null) {
            java.awt.Component[] comps = grandparent.getComponents();
            for (int i = 0; i < comps.length; i++) {
                if (comps[i] == parent) {
                    for (int j = i - 1; j >= 0; j--) {
                        if (comps[j] instanceof java.awt.Label) {
                            String text = ((java.awt.Label) comps[j]).getText();
                            if (text != null && !text.trim().isEmpty()) {
                                return text.trim();
                            }
                        }
                        if (comps[j] instanceof java.awt.Panel) break;
                    }
                }
            }
        }

        return "";
    }

    /**
     * Convert a dialog field label to the ImageJ macro argument key.
     * Follows ImageJ Recorder conventions: strip trailing colon,
     * strip any parenthetical suffix ("Size (pixel^2):" → "size"),
     * lowercase, spaces to underscores.
     */
    private String labelToMacroKey(String label) {
        if (label == null || label.isEmpty()) return "";
        String key = label.trim();
        if (key.endsWith(":")) key = key.substring(0, key.length() - 1).trim();
        if (key.endsWith("=")) key = key.substring(0, key.length() - 1).trim();
        // Strip a trailing "(unit)" qualifier so "Size (pixel^2)" → "size"
        // and "Radius (pixels)" → "radius" — the macro recorder form.
        key = key.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        key = key.toLowerCase().replace(' ', '_');
        return key;
    }

    /**
     * Generate example macro syntax from probed fields.
     */
    private String buildMacroSyntax(String pluginName, JsonArray fields) {
        if (fields.size() == 0) {
            return "run(\"" + pluginName + "\");";
        }

        StringBuilder args = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            JsonObject f = fields.get(i).getAsJsonObject();
            String key = f.has("macro_key") ? f.get("macro_key").getAsString() : "";
            if (key.isEmpty()) continue;
            String type = f.get("type").getAsString();

            if ("checkbox".equals(type)) {
                // Checked checkboxes: include key name; unchecked: omit entirely
                if (f.has("default") && f.get("default").getAsBoolean()) {
                    if (args.length() > 0) args.append(" ");
                    args.append(key);
                }
                continue;
            }

            if (args.length() > 0) args.append(" ");

            String val = "";
            if ("numeric".equals(type)) {
                val = f.has("default") ? f.get("default").getAsString() : "0";
            } else if ("slider".equals(type)) {
                val = f.has("value") ? String.valueOf(f.get("value").getAsInt()) : "0";
            } else if ("string".equals(type) || "choice".equals(type)) {
                val = f.has("default") ? f.get("default").getAsString() : "";
            }

            if (val.contains(" ")) {
                args.append(key).append("=[").append(val).append("]");
            } else {
                args.append(key).append("=").append(val);
            }
        }

        return "run(\"" + pluginName + "\", \"" + args.toString() + "\");";
    }

    // -----------------------------------------------------------------------
    // Dialog interaction — click buttons, toggle checkboxes, set fields, etc.
    // -----------------------------------------------------------------------

    /**
     * Interact with components inside an open dialog window.
     *
     * JSON protocol:
     *   {"command": "interact_dialog", "action": "list_components"}
     *   {"command": "interact_dialog", "action": "list_components", "dialog": "IHF Analysis Pipeline"}
     *   {"command": "interact_dialog", "action": "click_button", "target": "OK"}
     *   {"command": "interact_dialog", "action": "set_checkbox", "target": "3D Object Analysis", "value": true}
     *   {"command": "interact_dialog", "action": "set_text", "target": "sigma", "value": "2.5"}
     *   {"command": "interact_dialog", "action": "set_text", "index": 0, "value": "hello"}
     *   {"command": "interact_dialog", "action": "set_dropdown", "target": "Method", "value": "Otsu"}
     *   {"command": "interact_dialog", "action": "set_slider", "index": 0, "value": 128}
     *   {"command": "interact_dialog", "action": "set_spinner", "index": 0, "value": 42}
     *   {"command": "interact_dialog", "action": "set_scrollbar", "index": 0, "value": 50}
     *   {"command": "interact_dialog", "action": "focus_tab", "target": "Advanced"}
     *   {"command": "interact_dialog", "action": "get_component", "type": "checkbox", "index": 2}
     *
     * "dialog" is optional — if omitted, targets the topmost visible dialog.
     * "target" matches by label/text (case-insensitive substring).
     * "index" selects the Nth component of that type (0-based).
     * Both "target" and "index" can be used together for disambiguation.
     */
    private JsonObject handleInteractDialog(JsonObject request) {
        JsonElement actionElement = request.get("action");
        if (actionElement == null || !actionElement.isJsonPrimitive()) {
            return errorResponse("Missing 'action' field for interact_dialog");
        }
        final String action = actionElement.getAsString();

        // Find the target dialog
        JsonElement dialogElement = request.get("dialog");
        final String dialogTitle = (dialogElement != null && dialogElement.isJsonPrimitive())
                ? dialogElement.getAsString() : null;

        // Target matching
        JsonElement targetElement = request.get("target");
        final String target = (targetElement != null && targetElement.isJsonPrimitive())
                ? targetElement.getAsString() : null;

        JsonElement indexElement = request.get("index");
        final int index = (indexElement != null && indexElement.isJsonPrimitive())
                ? indexElement.getAsInt() : -1;

        // Value for set operations
        JsonElement valueElement = request.get("value");

        // Type filter for get_component
        JsonElement typeElement = request.get("type");
        final String typeFilter = (typeElement != null && typeElement.isJsonPrimitive())
                ? typeElement.getAsString() : null;

        // Execute on EDT
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final JsonElement valEl = valueElement;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    // Find the dialog
                    Dialog dlg = findDialog(dialogTitle);
                    if (dlg == null && !"list_components".equals(action)) {
                        holder[0] = errorResponse("No matching dialog found"
                                + (dialogTitle != null ? " ('" + dialogTitle + "')" : ""));
                        return;
                    }

                    if ("list_components".equals(action)) {
                        holder[0] = listInteractableComponents(dlg, dialogTitle);
                    } else if ("click_button".equals(action)) {
                        holder[0] = doClickButton(dlg, target, index);
                    } else if ("set_checkbox".equals(action)) {
                        boolean val = (valEl != null && valEl.isJsonPrimitive()) ? valEl.getAsBoolean() : true;
                        holder[0] = doSetCheckbox(dlg, target, index, val);
                    } else if ("toggle_checkbox".equals(action)) {
                        holder[0] = doToggleCheckbox(dlg, target, index);
                    } else if ("set_text".equals(action)) {
                        String val = (valEl != null && valEl.isJsonPrimitive()) ? valEl.getAsString() : "";
                        holder[0] = doSetTextField(dlg, target, index, val);
                    } else if ("set_dropdown".equals(action)) {
                        String val = (valEl != null && valEl.isJsonPrimitive()) ? valEl.getAsString() : "";
                        holder[0] = doSetDropdown(dlg, target, index, val);
                    } else if ("set_slider".equals(action)) {
                        int val = (valEl != null && valEl.isJsonPrimitive()) ? valEl.getAsInt() : 0;
                        holder[0] = doSetSlider(dlg, target, index, val);
                    } else if ("set_spinner".equals(action)) {
                        String val = (valEl != null && valEl.isJsonPrimitive()) ? valEl.getAsString() : "0";
                        holder[0] = doSetSpinner(dlg, target, index, val);
                    } else if ("set_scrollbar".equals(action)) {
                        int val = (valEl != null && valEl.isJsonPrimitive()) ? valEl.getAsInt() : 0;
                        holder[0] = doSetScrollbar(dlg, target, index, val);
                    } else if ("focus_tab".equals(action)) {
                        holder[0] = doFocusTab(dlg, target, index);
                    } else if ("get_component".equals(action)) {
                        holder[0] = doGetComponent(dlg, typeFilter, target, index);
                    } else {
                        holder[0] = errorResponse("Unknown interact_dialog action: " + action);
                    }
                } catch (Exception e) {
                    holder[0] = errorResponse("interact_dialog error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("interact_dialog timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        return (JsonObject) holder[0];
    }

    /**
     * Find a dialog by title (case-insensitive substring match).
     * If title is null, returns the topmost visible dialog.
     */
    private Dialog findDialog(String title) {
        Window[] windows = Window.getWindows();
        Dialog topmost = null;

        for (Window win : windows) {
            if (!win.isShowing() || !(win instanceof Dialog)) continue;
            Dialog dlg = (Dialog) win;
            String dlgTitle = dlg.getTitle();
            if (dlgTitle == null) dlgTitle = "";

            // Skip protected windows
            if (dlgTitle.contains("AI Assistant")) continue;

            if (title != null) {
                if (dlgTitle.toLowerCase().contains(title.toLowerCase())) {
                    return dlg;
                }
            } else {
                // Pick the topmost (last in array tends to be topmost)
                topmost = dlg;
            }
        }

        return topmost;
    }

    // --- Structured component inventory ---

    /**
     * Index for tracking a component with its metadata.
     */
    private static class ComponentEntry {
        String type;
        String label;         // text on the component itself
        String nearestLabel;  // nearest preceding label text
        int typeIndex;        // 0-based index among components of same type
        Component component;

        JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", type);
            obj.addProperty("label", label != null ? label : "");
            obj.addProperty("nearestLabel", nearestLabel != null ? nearestLabel : "");
            obj.addProperty("index", typeIndex);
            // Add current value
            if (component instanceof javax.swing.JCheckBox) {
                obj.addProperty("checked", ((javax.swing.JCheckBox) component).isSelected());
            } else if (component instanceof java.awt.Checkbox) {
                obj.addProperty("checked", ((java.awt.Checkbox) component).getState());
            } else if (component instanceof javax.swing.JToggleButton) {
                obj.addProperty("selected", ((javax.swing.JToggleButton) component).isSelected());
            } else if (component instanceof javax.swing.JTextField) {
                obj.addProperty("value", ((javax.swing.JTextField) component).getText());
            } else if (component instanceof java.awt.TextField) {
                obj.addProperty("value", ((java.awt.TextField) component).getText());
            } else if (component instanceof javax.swing.JComboBox) {
                javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) component;
                Object sel = combo.getSelectedItem();
                obj.addProperty("value", sel != null ? sel.toString() : "");
                JsonArray options = new JsonArray();
                for (int i = 0; i < combo.getItemCount(); i++) {
                    Object item = combo.getItemAt(i);
                    options.add(new JsonPrimitive(item != null ? item.toString() : ""));
                }
                obj.add("options", options);
            } else if (component instanceof java.awt.Choice) {
                java.awt.Choice choice = (java.awt.Choice) component;
                obj.addProperty("value", choice.getSelectedItem());
                JsonArray options = new JsonArray();
                for (int i = 0; i < choice.getItemCount(); i++) {
                    options.add(new JsonPrimitive(choice.getItem(i)));
                }
                obj.add("options", options);
            } else if (component instanceof javax.swing.JSlider) {
                javax.swing.JSlider s = (javax.swing.JSlider) component;
                obj.addProperty("value", s.getValue());
                obj.addProperty("min", s.getMinimum());
                obj.addProperty("max", s.getMaximum());
            } else if (component instanceof java.awt.Scrollbar) {
                java.awt.Scrollbar s = (java.awt.Scrollbar) component;
                obj.addProperty("value", s.getValue());
                obj.addProperty("min", s.getMinimum());
                obj.addProperty("max", s.getMaximum());
            } else if (component instanceof javax.swing.JSpinner) {
                javax.swing.JSpinner sp = (javax.swing.JSpinner) component;
                obj.addProperty("value", sp.getValue().toString());
                javax.swing.SpinnerModel model = sp.getModel();
                if (model instanceof javax.swing.SpinnerNumberModel) {
                    javax.swing.SpinnerNumberModel nm = (javax.swing.SpinnerNumberModel) model;
                    Comparable<?> min = nm.getMinimum();
                    Comparable<?> max = nm.getMaximum();
                    Number step = nm.getStepSize();
                    if (min != null) obj.addProperty("min", min.toString());
                    if (max != null) obj.addProperty("max", max.toString());
                    if (step != null) obj.addProperty("step", step.toString());
                }
            } else if (component instanceof javax.swing.JTextArea) {
                obj.addProperty("value", ((javax.swing.JTextArea) component).getText());
            } else if (component instanceof java.awt.TextArea) {
                obj.addProperty("value", ((java.awt.TextArea) component).getText());
            }
            // Add enabled state
            obj.addProperty("enabled", component.isEnabled());
            obj.addProperty("visible", component.isVisible());
            return obj;
        }
    }

    /**
     * Walk a container tree and build a flat list of all interactable components
     * with labels, types, indices, and current values.
     */
    private List<ComponentEntry> indexComponents(Container container) {
        List<ComponentEntry> entries = new ArrayList<ComponentEntry>();
        // Counters per type for indexing
        java.util.Map<String, Integer> typeCounts = new java.util.LinkedHashMap<String, Integer>();

        String[] lastLabel = new String[]{""};
        indexComponentsRecursive(container, entries, typeCounts, lastLabel);
        return entries;
    }

    private void indexComponentsRecursive(Container container, List<ComponentEntry> entries,
                                          java.util.Map<String, Integer> typeCounts,
                                          String[] lastLabel) {
        for (Component comp : container.getComponents()) {
            // Track labels for association
            String labelText = null;
            if (comp instanceof javax.swing.JLabel) {
                labelText = ((javax.swing.JLabel) comp).getText();
            } else if (comp instanceof java.awt.Label) {
                labelText = ((java.awt.Label) comp).getText();
            }
            if (labelText != null && !labelText.trim().isEmpty()) {
                lastLabel[0] = labelText.trim();
            }

            // Identify interactable component type
            String type = null;
            String ownLabel = null;

            if (comp instanceof javax.swing.JButton) {
                type = "button";
                ownLabel = ((javax.swing.JButton) comp).getText();
            } else if (comp instanceof java.awt.Button) {
                type = "button";
                ownLabel = ((java.awt.Button) comp).getLabel();
            } else if (comp instanceof javax.swing.JCheckBox) {
                type = "checkbox";
                ownLabel = ((javax.swing.JCheckBox) comp).getText();
            } else if (comp instanceof java.awt.Checkbox) {
                type = "checkbox";
                ownLabel = ((java.awt.Checkbox) comp).getLabel();
            } else if (comp instanceof javax.swing.JToggleButton) {
                // Catches toggle switches, radio-style buttons, etc.
                // (JCheckBox extends JToggleButton so this comes after)
                type = "toggle";
                ownLabel = ((javax.swing.JToggleButton) comp).getText();
            } else if (comp instanceof javax.swing.JRadioButton) {
                type = "radio";
                ownLabel = ((javax.swing.JRadioButton) comp).getText();
            } else if (comp instanceof javax.swing.JTextField
                    && !(comp instanceof javax.swing.JPasswordField)) {
                type = "text";
                ownLabel = null; // text fields don't have own label
            } else if (comp instanceof java.awt.TextField) {
                type = "text";
            } else if (comp instanceof javax.swing.JTextArea) {
                type = "textarea";
            } else if (comp instanceof java.awt.TextArea) {
                type = "textarea";
            } else if (comp instanceof javax.swing.JComboBox) {
                type = "dropdown";
            } else if (comp instanceof java.awt.Choice) {
                type = "dropdown";
            } else if (comp instanceof javax.swing.JSlider) {
                type = "slider";
            } else if (comp instanceof java.awt.Scrollbar) {
                type = "scrollbar";
            } else if (comp instanceof javax.swing.JSpinner) {
                type = "spinner";
            } else if (comp instanceof javax.swing.JTabbedPane) {
                type = "tabs";
                javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) comp;
                ownLabel = "selected=" + tabs.getTitleAt(tabs.getSelectedIndex());
            }

            if (type != null && comp.isVisible()) {
                ComponentEntry entry = new ComponentEntry();
                entry.type = type;
                entry.label = (ownLabel != null) ? ownLabel.trim() : null;
                entry.nearestLabel = lastLabel[0];
                entry.component = comp;

                Integer count = typeCounts.get(type);
                if (count == null) count = 0;
                entry.typeIndex = count;
                typeCounts.put(type, count + 1);

                entries.add(entry);
            }

            // Recurse
            if (comp instanceof Container) {
                indexComponentsRecursive((Container) comp, entries, typeCounts, lastLabel);
            }
        }
    }

    /**
     * Find a component matching type, target text, and/or index.
     */
    private ComponentEntry findComponent(List<ComponentEntry> entries, String type,
                                         String target, int index) {
        List<ComponentEntry> candidates = new ArrayList<ComponentEntry>();
        for (ComponentEntry e : entries) {
            if (type != null && !e.type.equals(type)) continue;
            if (target != null) {
                boolean matchesLabel = e.label != null
                        && e.label.toLowerCase().contains(target.toLowerCase());
                boolean matchesNearest = e.nearestLabel != null
                        && e.nearestLabel.toLowerCase().contains(target.toLowerCase());
                if (!matchesLabel && !matchesNearest) continue;
            }
            candidates.add(e);
        }

        if (candidates.isEmpty()) return null;

        if (index >= 0 && index < candidates.size()) {
            return candidates.get(index);
        }
        // Default: return first match
        return candidates.get(0);
    }

    // --- List components ---

    private JsonObject listInteractableComponents(Dialog specificDialog, String dialogTitle) {
        JsonObject result = new JsonObject();
        JsonArray dialogsArr = new JsonArray();

        if (specificDialog != null) {
            // List components for a specific dialog
            JsonObject dlgObj = buildDialogComponentList(specificDialog);
            dialogsArr.add(dlgObj);
        } else {
            // List all dialogs and their components
            Window[] windows = Window.getWindows();
            for (Window win : windows) {
                if (!win.isShowing() || !(win instanceof Dialog)) continue;
                Dialog dlg = (Dialog) win;
                String title = dlg.getTitle();
                if (title != null && title.contains("AI Assistant")) continue;
                JsonObject dlgObj = buildDialogComponentList(dlg);
                dialogsArr.add(dlgObj);
            }
        }

        result.add("dialogs", dialogsArr);
        return successResponse(result);
    }

    private JsonObject buildDialogComponentList(Dialog dlg) {
        JsonObject dlgObj = new JsonObject();
        dlgObj.addProperty("title", dlg.getTitle() != null ? dlg.getTitle() : "");
        dlgObj.addProperty("modal", dlg.isModal());

        // Get window bounds
        java.awt.Rectangle bounds = dlg.getBounds();
        dlgObj.addProperty("x", bounds.x);
        dlgObj.addProperty("y", bounds.y);
        dlgObj.addProperty("width", bounds.width);
        dlgObj.addProperty("height", bounds.height);

        List<ComponentEntry> entries = indexComponents(dlg);
        JsonArray components = new JsonArray();
        for (ComponentEntry e : entries) {
            components.add(e.toJson());
        }
        dlgObj.add("components", components);

        return dlgObj;
    }

    // --- Click button ---

    private JsonObject doClickButton(Dialog dlg, String target, int index) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "button", target, index);
        if (entry == null) {
            return errorResponse("No button found matching target='" + target + "' index=" + index);
        }

        if (entry.component instanceof javax.swing.JButton) {
            ((javax.swing.JButton) entry.component).doClick();
        } else if (entry.component instanceof java.awt.Button) {
            // AWT Button — dispatch an ActionEvent
            java.awt.Button btn = (java.awt.Button) entry.component;
            java.awt.event.ActionEvent evt = new java.awt.event.ActionEvent(
                    btn, java.awt.event.ActionEvent.ACTION_PERFORMED, btn.getActionCommand());
            for (java.awt.event.ActionListener al : btn.getActionListeners()) {
                al.actionPerformed(evt);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("clicked", entry.label != null ? entry.label : "button[" + entry.typeIndex + "]");
        return successResponse(result);
    }

    // --- Set checkbox ---

    private JsonObject doSetCheckbox(Dialog dlg, String target, int index, boolean value) {
        List<ComponentEntry> entries = indexComponents(dlg);

        // Try checkbox first, then toggle buttons
        ComponentEntry entry = findComponent(entries, "checkbox", target, index);
        if (entry == null) {
            entry = findComponent(entries, "toggle", target, index);
        }

        if (entry == null) {
            return errorResponse("No checkbox/toggle found matching target='" + target + "' index=" + index);
        }

        String compLabel = entry.label != null ? entry.label : "checkbox[" + entry.typeIndex + "]";
        boolean oldValue;

        if (entry.component instanceof javax.swing.JCheckBox) {
            javax.swing.JCheckBox cb = (javax.swing.JCheckBox) entry.component;
            oldValue = cb.isSelected();
            if (cb.isSelected() != value) {
                cb.doClick();
            }
        } else if (entry.component instanceof java.awt.Checkbox) {
            java.awt.Checkbox cb = (java.awt.Checkbox) entry.component;
            oldValue = cb.getState();
            cb.setState(value);
            // Fire ItemEvent for listeners
            java.awt.event.ItemEvent evt = new java.awt.event.ItemEvent(
                    cb, java.awt.event.ItemEvent.ITEM_STATE_CHANGED, cb.getLabel(),
                    value ? java.awt.event.ItemEvent.SELECTED : java.awt.event.ItemEvent.DESELECTED);
            for (java.awt.event.ItemListener il : cb.getItemListeners()) {
                il.itemStateChanged(evt);
            }
        } else if (entry.component instanceof javax.swing.JToggleButton) {
            javax.swing.JToggleButton tb = (javax.swing.JToggleButton) entry.component;
            oldValue = tb.isSelected();
            if (tb.isSelected() != value) {
                tb.doClick();
            }
        } else {
            return errorResponse("Component is not a checkbox/toggle: " + entry.type);
        }

        JsonObject result = new JsonObject();
        result.addProperty("component", compLabel);
        result.addProperty("oldValue", oldValue);
        result.addProperty("newValue", value);
        return successResponse(result);
    }

    // --- Toggle checkbox (flip current state) ---

    private JsonObject doToggleCheckbox(Dialog dlg, String target, int index) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "checkbox", target, index);
        if (entry == null) {
            entry = findComponent(entries, "toggle", target, index);
        }
        if (entry == null) {
            return errorResponse("No checkbox/toggle found matching target='" + target + "' index=" + index);
        }

        boolean newValue;
        if (entry.component instanceof javax.swing.JCheckBox) {
            javax.swing.JCheckBox cb = (javax.swing.JCheckBox) entry.component;
            cb.doClick();
            newValue = cb.isSelected();
        } else if (entry.component instanceof java.awt.Checkbox) {
            java.awt.Checkbox cb = (java.awt.Checkbox) entry.component;
            cb.setState(!cb.getState());
            newValue = cb.getState();
            java.awt.event.ItemEvent evt = new java.awt.event.ItemEvent(
                    cb, java.awt.event.ItemEvent.ITEM_STATE_CHANGED, cb.getLabel(),
                    newValue ? java.awt.event.ItemEvent.SELECTED : java.awt.event.ItemEvent.DESELECTED);
            for (java.awt.event.ItemListener il : cb.getItemListeners()) {
                il.itemStateChanged(evt);
            }
        } else if (entry.component instanceof javax.swing.JToggleButton) {
            javax.swing.JToggleButton tb = (javax.swing.JToggleButton) entry.component;
            tb.doClick();
            newValue = tb.isSelected();
        } else {
            return errorResponse("Component is not a checkbox/toggle");
        }

        JsonObject result = new JsonObject();
        result.addProperty("component", entry.label != null ? entry.label : "checkbox[" + entry.typeIndex + "]");
        result.addProperty("newValue", newValue);
        return successResponse(result);
    }

    // --- Set text field ---

    private JsonObject doSetTextField(Dialog dlg, String target, int index, String value) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "text", target, index);
        if (entry == null) {
            // Also try textarea
            entry = findComponent(entries, "textarea", target, index);
        }
        if (entry == null) {
            return errorResponse("No text field found matching target='" + target + "' index=" + index);
        }

        String oldValue = "";
        if (entry.component instanceof javax.swing.JTextField) {
            javax.swing.JTextField tf = (javax.swing.JTextField) entry.component;
            oldValue = tf.getText();
            tf.setText(value);
            // Fire action event to notify listeners
            tf.postActionEvent();
        } else if (entry.component instanceof java.awt.TextField) {
            java.awt.TextField tf = (java.awt.TextField) entry.component;
            oldValue = tf.getText();
            tf.setText(value);
            // Fire TextEvent
            java.awt.event.ActionEvent evt = new java.awt.event.ActionEvent(
                    tf, java.awt.event.ActionEvent.ACTION_PERFORMED, value);
            for (java.awt.event.ActionListener al : tf.getActionListeners()) {
                al.actionPerformed(evt);
            }
        } else if (entry.component instanceof javax.swing.JTextArea) {
            javax.swing.JTextArea ta = (javax.swing.JTextArea) entry.component;
            oldValue = ta.getText();
            ta.setText(value);
        } else if (entry.component instanceof java.awt.TextArea) {
            java.awt.TextArea ta = (java.awt.TextArea) entry.component;
            oldValue = ta.getText();
            ta.setText(value);
        }

        JsonObject result = new JsonObject();
        result.addProperty("nearestLabel", entry.nearestLabel != null ? entry.nearestLabel : "");
        result.addProperty("oldValue", oldValue);
        result.addProperty("newValue", value);
        return successResponse(result);
    }

    // --- Set dropdown / choice ---

    private JsonObject doSetDropdown(Dialog dlg, String target, int index, String value) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "dropdown", target, index);
        if (entry == null) {
            return errorResponse("No dropdown found matching target='" + target + "' index=" + index);
        }

        String oldValue = "";
        boolean found = false;

        if (entry.component instanceof javax.swing.JComboBox) {
            javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) entry.component;
            Object sel = combo.getSelectedItem();
            oldValue = sel != null ? sel.toString() : "";

            // Try exact match first, then case-insensitive substring
            for (int i = 0; i < combo.getItemCount(); i++) {
                Object item = combo.getItemAt(i);
                if (item != null && item.toString().equals(value)) {
                    combo.setSelectedIndex(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (int i = 0; i < combo.getItemCount(); i++) {
                    Object item = combo.getItemAt(i);
                    if (item != null && item.toString().toLowerCase().contains(value.toLowerCase())) {
                        combo.setSelectedIndex(i);
                        found = true;
                        break;
                    }
                }
            }
        } else if (entry.component instanceof java.awt.Choice) {
            java.awt.Choice choice = (java.awt.Choice) entry.component;
            oldValue = choice.getSelectedItem();

            for (int i = 0; i < choice.getItemCount(); i++) {
                if (choice.getItem(i).equals(value)) {
                    choice.select(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (int i = 0; i < choice.getItemCount(); i++) {
                    if (choice.getItem(i).toLowerCase().contains(value.toLowerCase())) {
                        choice.select(i);
                        found = true;
                        break;
                    }
                }
            }
        }

        if (!found) {
            return errorResponse("Value '" + value + "' not found in dropdown options");
        }

        JsonObject result = new JsonObject();
        result.addProperty("nearestLabel", entry.nearestLabel != null ? entry.nearestLabel : "");
        result.addProperty("oldValue", oldValue);
        result.addProperty("newValue", value);
        return successResponse(result);
    }

    // --- Set slider ---

    private JsonObject doSetSlider(Dialog dlg, String target, int index, int value) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "slider", target, index);
        if (entry == null) {
            return errorResponse("No slider found matching target='" + target + "' index=" + index);
        }

        if (!(entry.component instanceof javax.swing.JSlider)) {
            return errorResponse("Component is not a JSlider");
        }

        javax.swing.JSlider slider = (javax.swing.JSlider) entry.component;
        int oldValue = slider.getValue();
        int clamped = Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), value));
        slider.setValue(clamped);

        JsonObject result = new JsonObject();
        result.addProperty("nearestLabel", entry.nearestLabel != null ? entry.nearestLabel : "");
        result.addProperty("oldValue", oldValue);
        result.addProperty("newValue", clamped);
        result.addProperty("min", slider.getMinimum());
        result.addProperty("max", slider.getMaximum());
        return successResponse(result);
    }

    // --- Set spinner ---

    private JsonObject doSetSpinner(Dialog dlg, String target, int index, String value) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "spinner", target, index);
        if (entry == null) {
            return errorResponse("No spinner found matching target='" + target + "' index=" + index);
        }

        if (!(entry.component instanceof javax.swing.JSpinner)) {
            return errorResponse("Component is not a JSpinner");
        }

        javax.swing.JSpinner spinner = (javax.swing.JSpinner) entry.component;
        String oldValue = spinner.getValue().toString();

        javax.swing.SpinnerModel model = spinner.getModel();
        try {
            if (model instanceof javax.swing.SpinnerNumberModel) {
                // Parse as number
                try {
                    spinner.setValue(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    spinner.setValue(Double.parseDouble(value));
                }
            } else if (model instanceof javax.swing.SpinnerListModel) {
                spinner.setValue(value);
            } else {
                // Try setting directly
                spinner.setValue(value);
            }
        } catch (Exception e) {
            return errorResponse("Failed to set spinner value: " + e.getMessage());
        }

        JsonObject result = new JsonObject();
        result.addProperty("nearestLabel", entry.nearestLabel != null ? entry.nearestLabel : "");
        result.addProperty("oldValue", oldValue);
        result.addProperty("newValue", spinner.getValue().toString());
        return successResponse(result);
    }

    // --- Set scrollbar ---

    private JsonObject doSetScrollbar(Dialog dlg, String target, int index, int value) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "scrollbar", target, index);
        if (entry == null) {
            return errorResponse("No scrollbar found matching target='" + target + "' index=" + index);
        }

        if (!(entry.component instanceof java.awt.Scrollbar)) {
            return errorResponse("Component is not a Scrollbar");
        }

        java.awt.Scrollbar sb = (java.awt.Scrollbar) entry.component;
        int oldValue = sb.getValue();
        int clamped = Math.max(sb.getMinimum(), Math.min(sb.getMaximum(), value));
        sb.setValue(clamped);

        // Fire adjustment event
        java.awt.event.AdjustmentEvent evt = new java.awt.event.AdjustmentEvent(
                sb, java.awt.event.AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED,
                java.awt.event.AdjustmentEvent.TRACK, clamped);
        for (java.awt.event.AdjustmentListener al : sb.getAdjustmentListeners()) {
            al.adjustmentValueChanged(evt);
        }

        JsonObject result = new JsonObject();
        result.addProperty("nearestLabel", entry.nearestLabel != null ? entry.nearestLabel : "");
        result.addProperty("oldValue", oldValue);
        result.addProperty("newValue", clamped);
        result.addProperty("min", sb.getMinimum());
        result.addProperty("max", sb.getMaximum());
        return successResponse(result);
    }

    // --- Focus tab in JTabbedPane ---

    private JsonObject doFocusTab(Dialog dlg, String target, int index) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, "tabs", null, -1);
        if (entry == null) {
            return errorResponse("No tabbed pane found in dialog");
        }

        if (!(entry.component instanceof javax.swing.JTabbedPane)) {
            return errorResponse("Component is not a JTabbedPane");
        }

        javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) entry.component;
        int oldIndex = tabs.getSelectedIndex();

        if (index >= 0 && index < tabs.getTabCount()) {
            tabs.setSelectedIndex(index);
        } else if (target != null) {
            boolean found = false;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                String tabTitle = tabs.getTitleAt(i);
                if (tabTitle != null && tabTitle.toLowerCase().contains(target.toLowerCase())) {
                    tabs.setSelectedIndex(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return errorResponse("No tab matching '" + target + "'");
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("oldTab", tabs.getTitleAt(oldIndex));
        result.addProperty("newTab", tabs.getTitleAt(tabs.getSelectedIndex()));
        result.addProperty("tabCount", tabs.getTabCount());
        JsonArray tabNames = new JsonArray();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabNames.add(new JsonPrimitive(tabs.getTitleAt(i)));
        }
        result.add("tabs", tabNames);
        return successResponse(result);
    }

    // --- Get single component details ---

    private JsonObject doGetComponent(Dialog dlg, String typeFilter, String target, int index) {
        List<ComponentEntry> entries = indexComponents(dlg);
        ComponentEntry entry = findComponent(entries, typeFilter, target, index);
        if (entry == null) {
            return errorResponse("No component found matching type='" + typeFilter
                    + "' target='" + target + "' index=" + index);
        }
        return successResponse(entry.toJson());
    }
}
