package imagejai.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.LUT;
import imagejai.config.Constants;
import imagejai.engine.safeMode.DestructiveScanner;
import imagejai.engine.safeMode.RoiAutoBackup;
import imagejai.engine.safeMode.SourceImageTagger;
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
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    // 10-minute synchronous-macro ceiling. Long enough for batch 3D Object
    // Counter runs on dense masks without blocking the TCP thread forever.
    // Callers can override per-request with `"timeout_ms": N` (pass 0 or a
    // negative value to disable the timeout entirely — the server will wait
    // until the macro finishes, the dialog-dismiss path fires, or the
    // connection is closed).
    private static final long MACRO_TIMEOUT_MS = 600_000;
    private static final long PIPELINE_TIMEOUT_MS = 600_000;

    /**
     * Resolve the per-request timeout override, falling back to the default.
     * A value ≤ 0 means "no timeout" — the caller wants to wait forever.
     */
    private static long resolveTimeoutMs(JsonObject request, long defaultMs) {
        if (request == null) return defaultMs;
        JsonElement el = request.get("timeout_ms");
        if (el == null || !el.isJsonPrimitive()) return defaultMs;
        try {
            return el.getAsLong();
        } catch (Exception e) {
            return defaultMs;
        }
    }

    /** Has this request opted out of the watchdog? */
    private static boolean timeoutDisabled(long timeoutMs) {
        return timeoutMs <= 0L;
    }

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
    // JVM-wide mutex serializing every IJ.runMacro call. ImageJ has a single
    // global Interpreter / WindowManager; two macros running concurrently
    // (e.g. client A blocked on a dialog while client B starts a new macro)
    // corrupt each other's active-image state. Every client thread acquires
    // this before submitting to the executor and releases after the worker
    // actually terminates (or the abort timeout elapses).
    private static final Object MACRO_MUTEX = new Object();
    // How long we wait for IJ.Macro.abort() + thread-interrupt to actually kill
    // a running macro before giving up and returning the error response. The
    // MACRO_MUTEX stays held for this whole window so the next macro cannot
    // start until the zombie is either dead or demonstrably unkillable.
    private static final long MACRO_ABORT_WAIT_MS = 1500L;

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
            "reactive_stats",
            // Step 07: Gemma-tools read-only commands.
            "get_roi_state",
            "get_display_state",
            "get_console"
    ));

    /**
     * Step 11 (docs/tcp_upgrade/11_dedup_response.md): read-only commands
     * eligible for the automatic per-socket response-dedup short-circuit.
     * Strict subset of {@link #READONLY_COMMANDS} — only the ones the plan
     * names explicitly. Admin/observability readers ({@code get_friction_log},
     * {@code job_list} etc.) stay excluded because a repeat fetch of those
     * often means "tell me what changed" and dedup would defeat the intent.
     */
    private static final Set<String> DEDUP_COMMANDS = new HashSet<String>(Arrays.asList(
            "get_state",
            "get_image_info",
            "get_results_table",
            "get_log",
            "get_histogram",
            "get_open_windows",
            "get_metadata",
            "get_dialogs",
            "get_roi_state",
            "get_display_state"
    ));

    /**
     * Server version string emitted in the {@code hello} handshake response.
     * Bumped by steps that change the reply schema so clients can adapt.
     */
    static final String SERVER_VERSION = "1.7.5";

    /**
     * Per-connection capability record negotiated via the {@code hello} handler.
     * Clients that never call {@code hello} fall back to {@link #DEFAULT_CAPS}
     * so today's reply shape is preserved. Fields are package-private because
     * future-step handlers ({@code 02-07}) in this package read them directly.
     */
    static final class AgentCaps {
        String agent = "unknown";
        String agentId = null;
        boolean vision = false;
        String outputFormat = "json";
        int tokenBudget = Integer.MAX_VALUE;
        boolean verbose = false;
        // Step 05: pulse defaults ON (docs/tcp_upgrade/05_state_delta_and_pulse.md).
        // Most agents benefit from the one-line state readout on every reply;
        // Claude Code's wrapper opts out because its SessionStart hook already
        // injects per-turn session state and duplication would waste context.
        boolean pulse = true;
        // Step 05: state_delta defaults ON. When enabled the server groups the
        // existing scattered diff keys (newImages, resultsTable, logDelta,
        // dismissedDialogs) into a single "stateDelta" sub-object. Clients
        // that set state_delta=false in hello keep the legacy flat shape.
        boolean stateDelta = true;
        // Safe-mode master switch. The field default stays false so the
        // {@link #DEFAULT_CAPS} sentinel — used for any socket that never
        // calls {@code hello} — preserves today's unguarded behaviour.
        // Clients that DO say hello get {@code safe_mode=true} as the
        // negotiated default (see {@link #handleHello}); this is the
        // breaking-change path documented in
        // {@code docs/safe_mode_v2/02_master-switch-and-caps.md}.
        boolean safeMode = false;
        // Per plan: docs/safe_mode_v2/02_master-switch-and-caps.md.
        // Per-guard option flags. Stages 03–06 each consult the relevant
        // field on the caller's {@link AgentCaps} before firing; with
        // safeMode=false the master gate trips first and these are
        // skipped regardless. Defaults match the stage map: opt-in for
        // bit-depth narrowing and Enhance-Contrast normalize block (Stage
        // 05); opt-out for the auto-backup, snapshot, queue-storm,
        // source-image column, and scientific-integrity scanner.
        SafeModeOptions safeModeOptions = new SafeModeOptions();
        // Step 02: opt-in to typed error objects
        // (docs/tcp_upgrade/02_structured_errors.md). Off-by-default so clients
        // that never say hello keep receiving plain error strings; the server
        // emits the object form only when this flag has been negotiated.
        boolean structuredErrors = false;
        // Step 03: canonical macro echo (docs/tcp_upgrade/03_canonical_macro_echo.md).
        // On-by-default for clients that said hello. The server echoes the IJ
        // Recorder's canonical macro form only when it differs from what the
        // client submitted or when the macro failed, so the common case costs
        // zero additional tokens.
        boolean canonicalMacro = true;
        // Step 04: fuzzy plugin-name validation
        // (docs/tcp_upgrade/04_fuzzy_plugin_registry.md). On-by-default for
        // clients that said hello so hallucinated plugin names are caught
        // server-side before burning a macro-execution cycle. Clients can opt
        // out if they ship their own registry check.
        boolean fuzzyMatch = true;
        // Step 06: post-execution macro-analyser warnings
        // (docs/tcp_upgrade/06_nresults_trap.md). On-by-default for every
        // agent — the single rule shipped here (Analyze Particles + no
        // results-writing flag + nResults==0) costs near zero tokens and
        // shortcuts a loop Gemma hits on every call. Opt-out only.
        boolean warnings = true;
        // Step 09: histogramDelta on pixel-mutating macros
        // (docs/tcp_upgrade/09_histogram_delta.md). On-by-default — the
        // 32-bin before/after shape gives text-only agents (Gemma et al.) a
        // vision-free proxy for "did this macro change the pixels". Clients
        // that do their own image inspection can opt out.
        boolean histogram = true;
        // Step 10: phantom-dialog auto-dismiss opt-in
        // (docs/tcp_upgrade/10_phantom_dialog_detector.md). Reporting is
        // ALWAYS on (phantom dialogs surface under "phantomDialog" whenever
        // they are detected); this flag gates only the auto-click action.
        // Default OFF for every agent — Gemma explicitly opts in because its
        // loops spiral on invisible modal dialogs and the safe-button
        // allow-list keeps dismissal from making decisions for the user.
        boolean autoDismissPhantoms = false;
        // Step 11: per-socket response dedup cache for read-only queries
        // (docs/tcp_upgrade/11_dedup_response.md). When an agent polls the
        // same command within the window and gets a byte-identical body,
        // the server short-circuits to {"unchanged": true, ...}. Default ON
        // for every agent; opt-out via capabilities.dedup=false. Per-call
        // override: {"force": true} always returns the fresh body.
        boolean dedup = true;
        // Backing cache for Step 11 — per-socket state keyed on
        // (command, canonicalArgs). Initialised eagerly so handlers don't
        // need to null-check; DEFAULT_CAPS is a shared sentinel and never
        // participates in dedup (the dispatcher bails on dedup when sock is
        // null, which is the only path that reaches DEFAULT_CAPS).
        ResponseDedupCache dedupCache = new ResponseDedupCache();
        // Step 12: per-socket session stats + pattern-detector hints
        // (docs/tcp_upgrade/12_per_agent_telemetry.md). Tracks a rolling
        // 50-entry command history, per-rule throttles, and a set of
        // probe_command-seen plugin names; consulted by PatternDetector to
        // surface {"code": "PATTERN_DETECTED", ...} hints when an agent
        // repeats a known-bad behaviour. Default ON for every agent — hints
        // are short, actionable, 5-minute throttled, and opt-out via
        // capabilities.pattern_hints=false.
        boolean patternHints = true;
        SessionStats stats = new SessionStats();
        Set<String> acceptEvents = java.util.Collections.emptySet();
        // Step 13: session-scoped image provenance graph delta
        // (docs/tcp_upgrade/13_provenance_graph.md). Default ON for every
        // agent — the graph is shared across sockets so cost is O(1) per
        // image and the delta only ships when a mutating call produced new
        // structure. Clients that don't care about provenance opt out via
        // capabilities.graph_delta=false. The get_image_graph command is
        // NOT gated on this flag — it remains queryable on demand.
        boolean graphDelta = true;
        // Step 14: federated mistake ledger
        // (docs/tcp_upgrade/14_federated_ledger.md). Default ON for every
        // agent — the ledger is pure-IO, the auto-attach block on
        // handleExecuteMacro failures costs one fingerprint hash + a
        // bounded map lookup, and the suggested[] payload (top 3 matches)
        // adds at most a few hundred tokens on the failure path. The two
        // explicit commands (ledger_lookup / ledger_confirm) remain
        // callable regardless of this flag; caps.ledger gates only the
        // auto-attach behaviour so agents that manage their own lookup
        // path can opt out via capabilities.ledger=false.
        boolean ledger = true;
        // Step 15: per-image rolling undo stack
        // (docs/tcp_upgrade/15_undo_stack_api.md). Default OFF for every
        // agent — the memory cost (compressed pixel snapshots, up to
        // {@link SessionUndo#GLOBAL_CAP_BYTES}) is real and only worth
        // paying for agents that will actually use rewind / branch. Opt-in
        // explicitly via capabilities.undo=true in the hello handshake.
        // The five undo TCP commands (rewind, branch, branch_list,
        // branch_switch, branch_delete) reject with UNDO_DISABLED when this
        // flag is off so the failure mode is clear and self-documenting.
        boolean undo = false;
    }

    /**
     * Per-guard option flags consumed by safe-mode v2 stages 03-06. Negotiated
     * under {@code capabilities.safe_mode_options} in the {@code hello}
     * handshake; see {@code docs/safe_mode_v2/02_master-switch-and-caps.md}.
     *
     * <p>Two flags are opt-in (defaults false) — they target situations where
     * the right policy depends on what the user is measuring:
     * <ul>
     *   <li>{@code blockBitDepthNarrowing} — Stage 05 hard-block on 16/32-bit
     *       to 8-bit conversions before measurement.</li>
     *   <li>{@code blockNormalizeContrast} — Stage 05 hard-block on
     *       {@code Enhance Contrast normalize=true} on data being measured.</li>
     * </ul>
     *
     * <p>Five flags are opt-out (defaults true) — they catch silent damage
     * agents do regardless of intent: ROI Manager wipes (Stage 05),
     * auto-snapshot of the active image / ROIs / Results before each
     * mutating macro (Stage 03), per-image macro queueing while a Fiji
     * dialog is open (Stage 04), Source_Image column on Results (Stage
     * 06), and the scientific-integrity scanner (Stage 05).
     *
     * <p>The fields read every guard; whether a guard fires also depends on
     * the master {@link AgentCaps#safeMode} switch tripping first.
     */
    public static final class SafeModeOptions {
        public boolean blockBitDepthNarrowing  = false;  // opt-in, Stage 05
        public boolean blockNormalizeContrast  = false;  // opt-in, Stage 05
        public boolean autoBackupRoiOnReset    = true;   // Stage 05
        public boolean autoSnapshotRescue      = true;   // Stage 03
        public boolean queueStormGuard         = true;   // Stage 04
        public boolean autoSourceImageColumn   = true;   // Stage 06
        public boolean scientificIntegrityScan = true;   // Stage 05
    }

    /** Fallback caps applied to any request from a socket that never said hello. */
    static final AgentCaps DEFAULT_CAPS = new AgentCaps();

    /**
     * Stage 04 (docs/safe_mode_v2/04_queue-storm-per-image.md): execution
     * state recorded in {@link #inFlightByImage} for each image that has a
     * macro currently running through {@code execute_macro}. The
     * queue-storm guard returns {@code QUEUE_STORM_BLOCKED} when a second
     * macro arrives targeting an image whose entry is in the
     * {@link #PAUSED_ON_DIALOG} state.
     */
    enum MacroState { RUNNING, PAUSED_ON_DIALOG }

    /**
     * Stage 04 entry in {@link #inFlightByImage}. {@code state} and
     * {@code dialogTitle} are {@code volatile} because the worker thread
     * inside {@code handleExecuteMacro} flips them when a blocking dialog
     * is detected, and a concurrent {@code execute_macro} on a different
     * thread reads them when running the queue-storm guard check.
     */
    static final class ActiveMacro {
        final String macroId;
        volatile MacroState state;
        volatile String dialogTitle;
        final long startedAt;

        ActiveMacro(String macroId, MacroState state) {
            this.macroId = macroId;
            this.state = state;
            this.startedAt = System.currentTimeMillis();
        }
    }

    /**
     * Stage 04: per-image in-flight macro tracker. Keyed by ImageJ window
     * title (taken from the last {@code selectImage("...")} /
     * {@code selectWindow("...")} call in the macro source, or the active
     * window title at command entry). Populated when a macro begins
     * executing inside {@link #handleExecuteMacro}, flipped to
     * {@link MacroState#PAUSED_ON_DIALOG} when a blocking dialog is
     * detected, removed on macro return / abort. Package-private so unit
     * tests can inject synthetic paused-state entries without spinning up
     * Fiji. Per plan: docs/safe_mode_v2/04_queue-storm-per-image.md.
     */
    final Map<String, ActiveMacro> inFlightByImage = new ConcurrentHashMap<String, ActiveMacro>();

    /**
     * Step 05: bookkeeping struct for post-command diffs. Collects the diff
     * keys a mutating handler (execute_macro, run_script) used to emit
     * individually (newImages, resultsTable, logDelta, dismissedDialogs) and
     * serialises them as a single {@code stateDelta} sub-object when
     * {@code caps.stateDelta} is on, or as top-level flat keys for legacy
     * clients. Per plan: docs/tcp_upgrade/05_state_delta_and_pulse.md.
     */
    static final class StateDelta {
        JsonArray newImages;
        String resultsTable;
        String logDelta;
        JsonArray dismissedDialogs;

        boolean isEmpty() {
            return newImages == null && resultsTable == null
                    && logDelta == null && dismissedDialogs == null;
        }

        /** Serialise collected fields into a fresh {@link JsonObject}. */
        JsonObject toJsonObject() {
            JsonObject obj = new JsonObject();
            if (newImages != null) obj.add("newImages", newImages);
            if (resultsTable != null) obj.addProperty("resultsTable", resultsTable);
            if (logDelta != null) obj.addProperty("logDelta", logDelta);
            if (dismissedDialogs != null) obj.add("dismissedDialogs", dismissedDialogs);
            return obj;
        }

        /**
         * Attach collected diffs to {@code result} in the shape {@code caps}
         * dictates: nested under {@code stateDelta} when grouping is on,
         * flat top-level keys otherwise. No-op when nothing was collected.
         */
        void applyTo(JsonObject result, AgentCaps caps) {
            if (isEmpty()) return;
            boolean grouped = caps != null && caps.stateDelta;
            if (grouped) {
                result.add("stateDelta", toJsonObject());
            } else {
                if (newImages != null) result.add("newImages", newImages);
                if (resultsTable != null) result.addProperty("resultsTable", resultsTable);
                if (logDelta != null) result.addProperty("logDelta", logDelta);
                if (dismissedDialogs != null) result.add("dismissedDialogs", dismissedDialogs);
            }
        }
    }

    /**
     * Caps keyed by the connection that negotiated them. Populated by
     * {@link #handleHello}, read by {@link #dispatch}, cleared in
     * {@link #handleClient}'s finally block on disconnect.
     */
    private final Map<Socket, AgentCaps> capsBySocket = new ConcurrentHashMap<Socket, AgentCaps>();

    /**
     * Test-only seam: when non-null, {@link #dispatchInternal} appends the
     * caps used for each invocation to this list. Production code never
     * touches this; tests in {@code TCPCommandServerBatchCapsTest} set it to
     * verify caps inheritance through nested {@code batch}/{@code run}/
     * {@code intent} dispatches. Per plan:
     * {@code docs/safe_mode_v2/01_fix-batch-run-bypass.md}.
     */
    java.util.List<AgentCaps> capsWitnessForTest = null;

    /**
     * Test-only seam: when set, {@link #handleExecuteMacro} returns the
     * function's result instead of running the macro on the EDT. Lets unit
     * tests cover the {@code intent} → {@code execute_macro} re-entry path
     * without a live Fiji classpath. Per plan:
     * {@code docs/safe_mode_v2/01_fix-batch-run-bypass.md}.
     */
    static java.util.function.BiFunction<JsonObject, AgentCaps, JsonObject>
            executeMacroForTest = null;

    /**
     * Step 13: session-scoped image provenance DAG shared across all
     * sockets. Mutating handlers capture a marker at entry, call
     * {@link ImageGraph#trackMacroChange} at exit, and attach
     * {@link ImageGraph#deltaSince} to their reply under {@code graphDelta}
     * when {@code caps.graphDelta} is on. Always queryable via the
     * {@code get_image_graph} command. Per plan:
     * docs/tcp_upgrade/13_provenance_graph.md.
     */
    final ImageGraph imageGraph = new ImageGraph();

    /**
     * Step 14: federated mistake ledger
     * (docs/tcp_upgrade/14_federated_ledger.md). Persistent on-disk store at
     * {@code ~/.imagejai/ledger.json} of known-good fixes keyed by error
     * fingerprint. Populated by {@code ledger_confirm} calls from any agent,
     * queried via {@code ledger_lookup}, and auto-attached to
     * {@code execute_macro} error replies' {@code suggested[]} when
     * {@code caps.ledger} is on. Field is package-private so tests can
     * replace it via {@link #setLedgerStore(LedgerStore)}.
     */
    LedgerStore ledgerStore = LedgerStore.openDefault();

    /**
     * Step 15: per-server undo state
     * (docs/tcp_upgrade/15_undo_stack_api.md). Holds one or more named
     * branches; each branch holds a per-image-title bounded
     * {@link UndoStack} of compressed pixel snapshots. Mutating handlers
     * push a frame here BEFORE the call when {@code caps.undo} is on for
     * the originating socket; the {@code rewind} / {@code branch*}
     * handlers pop and restore. Field is package-private so tests can
     * inject synthetic frames without going through a live ImagePlus.
     */
    final SessionUndo sessionUndo = new SessionUndo();

    /**
     * Step 15: number of mutating handlers currently inside their
     * synchronized(MACRO_MUTEX) block. Read by the rewind / branch
     * handlers so a rewind that races a still-running macro returns
     * UNDO_BUSY instead of corrupting the state mid-flight.
     * Incremented at the top of every mutating handler, decremented in a
     * finally so an exception unwinds the counter cleanly.
     */
    private final AtomicInteger macroInFlight = new AtomicInteger(0);

    /**
     * Step 15: monotonic call-id counter so every captured undo frame can
     * be addressed by an opaque, stable identifier. Used by {@code rewind
     * to_call_id} and {@code branch from_call_id}.
     */
    private final java.util.concurrent.atomic.AtomicLong callIdSeq =
            new java.util.concurrent.atomic.AtomicLong(0);

    private String nextCallId() {
        return "c-" + callIdSeq.incrementAndGet();
    }

    private final int port;
    private final CommandEngine commandEngine;
    private final StateInspector stateInspector;
    private final PipelineBuilder pipelineBuilder;
    private final ExplorationEngine explorationEngine;
    private final FrictionLog frictionLog = new FrictionLog();
    private final FrictionLogJournal frictionLogJournal = new FrictionLogJournal();
    // Package-private and non-final so unit tests can install a temp-pathed
    // router and avoid teach() writing to the user's home directory. Production
    // never reassigns this field.
    IntentRouter intentRouter = new IntentRouter();
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
        this.frictionLog.setJournal(frictionLogJournal);
        this.jobRegistry = new JobRegistry(commandEngine);
        this.reactiveEngine = new ReactiveEngine(
                eventBus, commandEngine, intentRouter, guiActionDispatcher);
        // Step 15: surface global LRU evictions to FrictionLog so an
        // over-tight session cap shows up in the same place as other
        // recurring failures. Plan §Memory management.
        this.sessionUndo.setEvictionLogger(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String summary) {
                try {
                    frictionLog.record("", "undo_eviction", "", summary);
                } catch (Throwable ignore) {
                    // Telemetry must never break the eviction sweep.
                }
            }
        });
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

        // Step 07: install System.out / System.err tees into bounded ring
        // buffers so the new get_console command can surface Groovy /
        // Jython stack traces that IJ.getLog() never sees. Idempotent — a
        // second start() while still running is a no-op here. Per plan:
        // docs/tcp_upgrade/07_gemma_tools_server.md.
        try {
            ConsoleCapture.install();
        } catch (Throwable t) {
            System.err.println("[ImageJAI-TCP] Console capture install failed: " + t.getMessage());
        }

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
        try {
            frictionLogJournal.close();
        } catch (Exception e) {
            System.err.println("[ImageJAI-TCP] Error closing friction journal: " + e.getMessage());
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                System.err.println("[ImageJAI-TCP] Error closing server socket: " + e.getMessage());
            }
        }
        // Step 07: restore the original System.out / System.err so a
        // subsequent plugin reload doesn't stack tees on top of the previous
        // ones. Safe to call even if install() never ran.
        try {
            ConsoleCapture.uninstall();
        } catch (Exception e) {
            System.err.println("[ImageJAI-TCP] Error restoring console streams: " + e.getMessage());
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
            JsonObject response = dispatch(trimmed, socket);
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
            // Drop this connection's caps first so a later socket cannot
            // accidentally inherit stale state (ConcurrentHashMap keys are
            // identity-based, but belt-and-braces keeps the map small).
            capsBySocket.remove(socket);
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

    private JsonObject dispatch(String jsonStr, Socket sock) {
        JsonObject request;
        try {
            request = new JsonParser().parse(jsonStr).getAsJsonObject();
        } catch (Exception e) {
            return errorResponse("Invalid JSON: " + e.getMessage());
        }
        return dispatch(request, sock);
    }

    /**
     * Nested-dispatch overload for {@code batch}, {@code run}, and {@code intent}.
     * The originating handler passes its own {@link AgentCaps} so every
     * sub-command inherits the caller's capability negotiation — closing the
     * batch/run/intent bypass identified in {@code docs/safe_mode_v2/01_fix-batch-run-bypass.md}.
     * Without this overload the inner dispatch silently falls back to
     * {@link #DEFAULT_CAPS}, which has every safe-mode flag off; safe-mode
     * agents could be defeated by wrapping any destructive macro inside a
     * {@code batch} envelope. Package-private so unit tests can verify caps
     * inheritance without reflection.
     */
    JsonObject dispatch(JsonObject request, AgentCaps caps) {
        return dispatchInternal(request, caps, null);
    }

    /**
     * Top-level dispatch from a real client socket. Resolves the per-socket
     * caps once and delegates to the shared internal path.
     *
     * @param sock originating client socket; {@code null} only for legacy
     *             callers that have no session — caps fall back to
     *             {@link #DEFAULT_CAPS}.
     */
    private JsonObject dispatch(JsonObject request, Socket sock) {
        AgentCaps caps = (sock != null)
                ? capsBySocket.getOrDefault(sock, DEFAULT_CAPS)
                : DEFAULT_CAPS;
        return dispatchInternal(request, caps, sock);
    }

    /**
     * Shared dispatch body used by both the socket-bound and the nested
     * overloads. Caps are supplied by the caller so nested invocations
     * inherit the originating socket's negotiated capabilities. Per plan:
     * {@code docs/safe_mode_v2/01_fix-batch-run-bypass.md}.
     *
     * <p>Post-processing that depends on a real socket (response dedup short-
     * circuit, pattern-hint attachment) is skipped when {@code sock} is null.
     */
    private JsonObject dispatchInternal(JsonObject request, AgentCaps caps, Socket sock) {
        JsonElement cmdElement = request.get("command");
        if (cmdElement == null || !cmdElement.isJsonPrimitive()) {
            return errorResponse("Missing 'command' field");
        }
        String command = cmdElement.getAsString();

        if (listener != null) {
            listener.onCommandReceived(command);
        }

        // Test seam: when set, every dispatch invocation appends the caps
        // it actually used so a test can prove caps inheritance through
        // batch/run/intent. Production code never sets this; default null
        // = no recording, no overhead. Per plan: safe_mode_v2 stage 01.
        if (capsWitnessForTest != null) {
            capsWitnessForTest.add(caps);
        }

        JsonObject response = dispatchCore(command, request, caps, sock);

        // Phase 7: surface a handler-attached gui_action piggyback into the
        // outer response. Handlers may set result.gui_action = {...}; this
        // path lifts it to top-level so external clients can react without
        // knowing whether the field came from the handler or the dispatcher.
        // For v1 only the dedicated gui_action command writes a top-level
        // entry directly; this hook is infrastructure future phases can tap.
        if (response != null) {
            promoteGuiActionPiggyback(response);
        }

        // Step 11: automatic per-socket response dedup for read-only polls.
        // Runs BEFORE the legacy if_none_match hash layer so that if the body
        // hasn't changed since this socket last fetched it, we short-circuit
        // with {"ok":true,"unchanged":true,"since":ts,"ageMs":N} and skip
        // the hash-attach path entirely. Per plan: docs/tcp_upgrade/11_dedup_response.md.
        // Skipped on nested dispatches (sock == null) — the dedup cache is
        // per-socket and inherited caps may carry the originating socket's
        // cache, so applying it here would mutate state for the wrong path.
        boolean dedupShortCircuited = false;
        if (response != null
                && sock != null
                && DEDUP_COMMANDS.contains(command)) {
            if (caps != null
                    && caps.dedup
                    && caps.dedupCache != null
                    && !optBool(request, "force", false)) {
                JsonObject deduped = applyResponseDedup(command, request, response, caps);
                if (deduped != response) {
                    dedupShortCircuited = true;
                }
                response = deduped;
            }
        }

        // Phase 1: readonly fast-path — hash successful readonly results and
        // short-circuit repeat callers that supply a matching if_none_match.
        // Skipped when Step 11 already returned a short-form envelope (no
        // {@code result} field to hash).
        if (response != null
                && !dedupShortCircuited
                && READONLY_COMMANDS.contains(command)) {
            response = applyReadonlyDedup(request, response);
        }

        // Phase 6: record failures to the friction log. Counts both transport-level
        // failures (ok:false) and operation-level failures (ok:true but
        // result.success:false — macros / scripts can fail inside a successful
        // protocol response).
        if (response != null) {
            try {
                recordFrictionIfFailure(command, request, response, caps);
            } catch (Exception ignore) {
                // friction logging is best-effort
            }
        }

        // Step 12: pattern detection + session-stats bookkeeping. Record
        // the command into the rolling history first, then run the detector
        // so a 4th close_dialogs call sees all four entries. Hints ride the
        // top-level {@code hints[]} array when caps.pattern_hints is on and
        // the response is not a dedup short-circuit (no room for hints on
        // {"unchanged":true} bodies). Best-effort — never fail the request.
        if (response != null
                && !dedupShortCircuited
                && caps != null
                && caps.stats != null
                && sock != null
                && !"hello".equals(command)) {
            try {
                attachPatternHints(command, request, response, caps);
            } catch (Exception ignore) {
                // pattern hints are best-effort telemetry
            }
        }

        return response;
    }

    /**
     * Step 12: record this command into the session's rolling history, then
     * consult {@link PatternDetector} for any rule fires and, if the caller
     * opted in, append them to a top-level {@code hints[]} array. Per plan:
     * docs/tcp_upgrade/12_per_agent_telemetry.md.
     */
    private void attachPatternHints(String command, JsonObject request,
                                    JsonObject response, AgentCaps caps) {
        SessionStats stats = caps.stats;
        long now = System.currentTimeMillis();
        String canonicalArgs = ResponseDedupCache.canonicalArgs(request);
        String errorCode = extractErrorCode(response);

        // Track probe_command results so probe_before_run_missed can suppress
        // the hint once the agent has already probed the plugin.
        if ("probe_command".equals(command) && request != null) {
            JsonElement nameEl = request.get("name");
            if (nameEl == null) nameEl = request.get("command");
            if (nameEl != null && nameEl.isJsonPrimitive()) {
                stats.noteProbed(nameEl.getAsString());
            }
        }

        stats.record(command, canonicalArgs, now, errorCode);

        if (!caps.patternHints) return;
        List<PatternDetector.Hint> hints = PatternDetector.check(stats, now);
        if (hints.isEmpty()) return;
        JsonArray arr;
        JsonElement existing = response.get("hints");
        if (existing != null && existing.isJsonArray()) {
            arr = existing.getAsJsonArray();
        } else {
            arr = new JsonArray();
            response.add("hints", arr);
        }
        for (PatternDetector.Hint h : hints) arr.add(h.toJson());
    }

    private JsonObject dispatchCore(String command, JsonObject request, AgentCaps caps, Socket sock) {
        // Caps are now supplied by dispatchInternal so nested dispatches
        // (batch, run, intent) inherit the originating socket's negotiated
        // caps instead of falling back to DEFAULT_CAPS. Per plan:
        // docs/safe_mode_v2/01_fix-batch-run-bypass.md.

        if ("hello".equals(command)) {
            return handleHello(request, sock);
        } else if ("ping".equals(command)) {
            return handlePing();
        } else if ("execute_macro".equals(command)) {
            return handleExecuteMacro(request, caps);
        } else if ("get_state".equals(command)) {
            return handleGetState();
        } else if ("get_image_info".equals(command)) {
            return handleGetImageInfo();
        } else if ("get_results_table".equals(command)) {
            return handleGetResultsTable();
        } else if ("capture_image".equals(command)) {
            return handleCaptureImage(request);
        } else if ("run_pipeline".equals(command)) {
            return handleRunPipeline(request, caps);
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
            return handleBatch(request, caps);
        } else if ("run".equals(command)) {
            return handleRunChain(request, caps);
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
        } else if ("list_commands".equals(command)) {
            return handleListCommands(request);
        } else if ("run_script".equals(command)) {
            return handleRunScript(request, caps);
        } else if ("interact_dialog".equals(command)) {
            return handleInteractDialog(request, caps);
        } else if ("get_progress".equals(command)) {
            return handleGetProgress();
        } else if ("get_friction_log".equals(command)) {
            return handleGetFrictionLog(request);
        } else if ("get_friction_patterns".equals(command)) {
            return handleGetFrictionPatterns();
        } else if ("clear_friction_log".equals(command)) {
            return handleClearFrictionLog();
        } else if ("intent".equals(command)) {
            return handleIntent(request, caps);
        } else if ("intent_teach".equals(command)) {
            return handleIntentTeach(request);
        } else if ("intent_list".equals(command)) {
            return handleIntentList();
        } else if ("intent_forget".equals(command)) {
            return handleIntentForget(request);
        } else if ("gui_action".equals(command)) {
            return handleGuiAction(request);
        } else if ("execute_macro_async".equals(command)) {
            return handleExecuteMacroAsync(request, caps);
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
        } else if ("get_roi_state".equals(command)) {
            return handleGetRoiState(request, caps);
        } else if ("get_display_state".equals(command)) {
            return handleGetDisplayState(request, caps);
        } else if ("get_console".equals(command)) {
            return handleGetConsole(request, caps);
        } else if ("get_image_graph".equals(command)) {
            return handleGetImageGraph();
        } else if ("ledger_lookup".equals(command)) {
            return handleLedgerLookup(request);
        } else if ("ledger_confirm".equals(command)) {
            return handleLedgerConfirm(request, caps);
        } else if ("rewind".equals(command)) {
            return handleRewind(request, caps);
        } else if ("branch".equals(command)) {
            return handleBranch(request, caps);
        } else if ("branch_list".equals(command)) {
            return handleBranchList(request, caps);
        } else if ("branch_switch".equals(command)) {
            return handleBranchSwitch(request, caps);
        } else if ("branch_delete".equals(command)) {
            return handleBranchDelete(request, caps);
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
     * Step 11 (docs/tcp_upgrade/11_dedup_response.md): check the per-socket
     * dedup cache for this (command, args) key. If the response body hashes
     * to the same value as the last fetch within the 10-second window, the
     * cache returns a short-form body which we wrap in a standard
     * {@code {"ok": true, ...}} envelope so dispatching code sees the normal
     * reply shape. Otherwise the cache stores the fresh hash and we return
     * the original response unchanged for downstream processing.
     *
     * <p>Only called on transport-successful replies — error bodies are never
     * deduped (a repeat error may carry a different underlying cause and the
     * fresh message matters).
     */
    private JsonObject applyResponseDedup(
            String command, JsonObject request, JsonObject response, AgentCaps caps) {
        if (response == null) return null;
        JsonElement okEl = response.get("ok");
        if (okEl == null || !okEl.isJsonPrimitive() || !okEl.getAsBoolean()) {
            return response;
        }
        // Strip volatile keys (usedMB, freeMB, percent, ...) before hashing so
        // a fresh memory reading on an otherwise-identical get_state doesn't
        // bust the cache. Reuses HASH_EXCLUDED_KEYS and the canonicalise()
        // helper already trusted by the if_none_match layer.
        JsonObject hashInput = new JsonObject();
        hashInput.addProperty("command", command);
        JsonElement resultEl = response.get("result");
        if (resultEl != null) {
            hashInput.add("result", canonicalise(resultEl));
        }
        String args = ResponseDedupCache.canonicalArgs(request);
        java.util.Optional<JsonObject> dedup =
                caps.dedupCache.checkOrStore(command, args, hashInput);
        if (!dedup.isPresent()) {
            return response;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        JsonObject body = dedup.get();
        envelope.addProperty("unchanged", body.get("unchanged").getAsBoolean());
        envelope.addProperty("since", body.get("since").getAsLong());
        envelope.addProperty("ageMs", body.get("ageMs").getAsLong());
        envelope.addProperty("command", command);
        return envelope;
    }

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

    private void recordFrictionIfFailure(String command, JsonObject request, JsonObject response, AgentCaps caps) {
        // Never log meta-queries about friction; that would self-reference and churn.
        if ("get_friction_log".equals(command)
                || "get_friction_patterns".equals(command)
                || "clear_friction_log".equals(command)) {
            return;
        }

        String error = extractErrorString(response);
        if (error == null) return;
        String agentId = (caps != null && caps.agentId != null) ? caps.agentId : "";
        frictionLog.record(agentId, command, summariseArgs(request), error);
    }

    /**
     * Step 12: derive an error signature from a response. Returns the plain
     * error string for legacy replies and the {@code error.code} for
     * structured-error replies. Null when the response is a success or carries
     * no parseable error field.
     */
    private static String extractErrorString(JsonObject response) {
        if (response == null) return null;
        JsonElement okEl = response.get("ok");
        boolean transportOk = okEl != null && okEl.isJsonPrimitive() && okEl.getAsBoolean();
        if (!transportOk) {
            JsonElement errEl = response.get("error");
            if (errEl != null && errEl.isJsonPrimitive()) return errEl.getAsString();
            if (errEl != null && errEl.isJsonObject()) {
                JsonObject errObj = errEl.getAsJsonObject();
                JsonElement msg = errObj.get("message");
                if (msg != null && msg.isJsonPrimitive()) return msg.getAsString();
                JsonElement code = errObj.get("code");
                if (code != null && code.isJsonPrimitive()) return code.getAsString();
            }
            return "unknown error";
        }
        JsonElement resultEl = response.get("result");
        if (resultEl != null && resultEl.isJsonObject()) {
            JsonObject r = resultEl.getAsJsonObject();
            JsonElement successEl = r.get("success");
            if (successEl != null
                    && successEl.isJsonPrimitive()
                    && successEl.getAsJsonPrimitive().isBoolean()
                    && !successEl.getAsBoolean()) {
                JsonElement errEl = r.get("error");
                if (errEl != null && errEl.isJsonPrimitive()) return errEl.getAsString();
                if (errEl != null && errEl.isJsonObject()) {
                    JsonObject errObj = errEl.getAsJsonObject();
                    JsonElement msg = errObj.get("message");
                    if (msg != null && msg.isJsonPrimitive()) return msg.getAsString();
                    JsonElement code = errObj.get("code");
                    if (code != null && code.isJsonPrimitive()) return code.getAsString();
                }
                return "operation failed";
            }
        }
        return null;
    }

    /**
     * Step 12: stable error-code signature for pattern detection. Prefers the
     * structured {@code error.code} field when present (step 02), falls back
     * to the FrictionLog-style normalised error string for legacy replies.
     * Null on success.
     */
    private static String extractErrorCode(JsonObject response) {
        if (response == null) return null;
        JsonElement okEl = response.get("ok");
        boolean transportOk = okEl != null && okEl.isJsonPrimitive() && okEl.getAsBoolean();
        JsonObject errObj = null;
        if (!transportOk) {
            JsonElement errEl = response.get("error");
            if (errEl != null && errEl.isJsonObject()) errObj = errEl.getAsJsonObject();
        } else {
            JsonElement resultEl = response.get("result");
            if (resultEl != null && resultEl.isJsonObject()) {
                JsonObject r = resultEl.getAsJsonObject();
                JsonElement successEl = r.get("success");
                if (successEl != null
                        && successEl.isJsonPrimitive()
                        && successEl.getAsJsonPrimitive().isBoolean()
                        && !successEl.getAsBoolean()) {
                    JsonElement errEl = r.get("error");
                    if (errEl != null && errEl.isJsonObject()) errObj = errEl.getAsJsonObject();
                }
            }
        }
        if (errObj != null) {
            JsonElement codeEl = errObj.get("code");
            if (codeEl != null && codeEl.isJsonPrimitive()) return codeEl.getAsString();
        }
        // Legacy: normalise the raw error string so two identical failures
        // with different paths / ids still match for repeat-error detection.
        String raw = extractErrorString(response);
        if (raw == null) return null;
        return FrictionLog.normaliseError(raw);
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

    /**
     * Handshake: record this connection's capabilities and return the server
     * version plus the subset of features it will emit for this client. Missing
     * fields in the request fall back to backwards-compatible defaults so a
     * bare {@code {"command": "hello"}} still negotiates cleanly.
     */
    JsonObject handleHello(JsonObject request, Socket sock) {
        AgentCaps c = new AgentCaps();
        c.agent = optString(request, "agent", "unknown");
        JsonObject caps = (request.has("capabilities")
                && request.get("capabilities").isJsonObject())
                ? request.getAsJsonObject("capabilities")
                : new JsonObject();
        c.vision       = optBool(caps, "vision", false);
        c.outputFormat = optString(caps, "output_format", "json");
        c.tokenBudget  = optInt(caps, "token_budget", Integer.MAX_VALUE);
        c.verbose      = optBool(caps, "verbose", false);
        // Step 05: pulse / state_delta default ON for clients that said hello
        // (docs/tcp_upgrade/05_state_delta_and_pulse.md). The Claude Code
        // wrapper sets pulse=false explicitly because its SessionStart hook
        // already injects the state a pulse string would carry.
        c.pulse        = optBool(caps, "pulse", true);
        c.stateDelta   = optBool(caps, "state_delta", true);
        // Safe-mode v2 stage 02: default ON for any client that says hello.
        // Wrappers that need the legacy fast path send {"safe_mode": false}
        // explicitly. No-handshake clients still hit DEFAULT_CAPS where the
        // field default is false, so the breaking-change scope is limited
        // to agents that actually negotiate caps. Per plan:
        // docs/safe_mode_v2/02_master-switch-and-caps.md.
        c.safeMode     = optBool(caps, "safe_mode", true);
        // Per-guard option flags. The sub-object lives at
        // capabilities.safe_mode_options.{snake_case_field}; missing
        // sub-objects keep the documented per-field defaults.
        JsonObject smOpts = (caps.has("safe_mode_options")
                && caps.get("safe_mode_options").isJsonObject())
                ? caps.getAsJsonObject("safe_mode_options")
                : null;
        c.safeModeOptions.blockBitDepthNarrowing =
                optBool(smOpts, "block_bit_depth_narrowing",
                        c.safeModeOptions.blockBitDepthNarrowing);
        c.safeModeOptions.blockNormalizeContrast =
                optBool(smOpts, "block_normalize_contrast",
                        c.safeModeOptions.blockNormalizeContrast);
        c.safeModeOptions.autoBackupRoiOnReset =
                optBool(smOpts, "auto_backup_roi_on_reset",
                        c.safeModeOptions.autoBackupRoiOnReset);
        c.safeModeOptions.autoSnapshotRescue =
                optBool(smOpts, "auto_snapshot_rescue",
                        c.safeModeOptions.autoSnapshotRescue);
        c.safeModeOptions.queueStormGuard =
                optBool(smOpts, "queue_storm_guard",
                        c.safeModeOptions.queueStormGuard);
        c.safeModeOptions.autoSourceImageColumn =
                optBool(smOpts, "auto_source_image_column",
                        c.safeModeOptions.autoSourceImageColumn);
        c.safeModeOptions.scientificIntegrityScan =
                optBool(smOpts, "scientific_integrity_scan",
                        c.safeModeOptions.scientificIntegrityScan);
        c.structuredErrors = optBool(caps, "structured_errors", false);
        c.canonicalMacro = optBool(caps, "canonical_macro", true);
        c.fuzzyMatch = optBool(caps, "fuzzy_match", true);
        // Step 06: warnings default on for every agent. Gemma benefits most
        // from the nResults trap; Claude / Codex tolerate the extra field.
        c.warnings = optBool(caps, "warnings", true);
        // Step 09: histogram delta defaults ON. Agents with an independent
        // image-inspection path can opt out via capabilities.histogram=false.
        c.histogram = optBool(caps, "histogram", true);
        // Step 10: phantom-dialog auto-dismiss defaults OFF. Reporting is
        // always active (ungated); the flag gates only the auto-click action.
        // Clients that want safe auto-clear (Gemma) opt in explicitly via
        // capabilities.auto_dismiss_phantoms=true in their hello handshake.
        c.autoDismissPhantoms = optBool(caps, "auto_dismiss_phantoms", false);
        // Step 11: response dedup defaults ON. Opt-out via capabilities.dedup=false
        // for clients that drive their own caching (or that must see every reply
        // verbatim for logging / provenance). Per-call {"force": true} bypasses
        // the cache without flipping this flag.
        c.dedup = optBool(caps, "dedup", true);
        // Step 12: pattern-detection hints default ON. Agents that want a
        // strict-no-unsolicited-commentary channel can opt out via
        // capabilities.pattern_hints=false in their hello handshake.
        c.patternHints = optBool(caps, "pattern_hints", true);
        // Step 13: provenance-graph delta on mutating replies defaults ON.
        // Per plan: docs/tcp_upgrade/13_provenance_graph.md. Every agent
        // gets the graphDelta piggybacked on execute_macro / run_script /
        // run_pipeline / interact_dialog replies; clients that do not
        // consume it opt out with capabilities.graph_delta=false.
        c.graphDelta = optBool(caps, "graph_delta", true);
        // Step 14: ledger auto-attach default ON for every agent. The two
        // explicit commands (ledger_lookup / ledger_confirm) remain
        // callable even when the cap is false — the flag gates only the
        // implicit enrichment of error replies' suggested[] list.
        c.ledger = optBool(caps, "ledger", true);
        // Step 15: undo defaults OFF — opt-in only. Memory cost (~5 frames
        // per image, compressed) is real; agents that don't use rewind
        // shouldn't pay for it. Per plan:
        // docs/tcp_upgrade/15_undo_stack_api.md.
        c.undo = optBool(caps, "undo", false);
        int sockPort = (sock != null) ? sock.getPort() : 0;
        c.agentId = optString(caps, "agent_id", c.agent + "-" + sockPort);
        c.acceptEvents = parseStringSet(caps, "accept_events");
        if (sock != null) {
            capsBySocket.put(sock, c);
        }

        JsonObject result = new JsonObject();
        result.addProperty("server_version", SERVER_VERSION);
        result.addProperty("session_id", sessionIdFor(sock));
        result.add("enabled", enabledCapsFor(c));
        result.addProperty("server_time_ms", System.currentTimeMillis());
        return successResponse(result);
    }

    /** Stable-ish session tag for this connection. Port + millis suffix is
     *  enough to tell two concurrent sessions apart in logs without needing
     *  a full UUID generator. */
    private String sessionIdFor(Socket sock) {
        int sockPort = (sock != null) ? sock.getPort() : 0;
        return "s-" + Integer.toHexString(sockPort) + "-"
                + Long.toHexString(System.currentTimeMillis() & 0xffffL);
    }

    /**
     * Capability names the server will actually emit on replies to this
     * connection. Step 02 adds {@code "structured_errors"}; later steps
     * (canonical macro echo, fuzzy match, state delta, pulse) will add their
     * own names once their reply-shape opt-ins land.
     */
    private JsonArray enabledCapsFor(AgentCaps caps) {
        JsonArray arr = new JsonArray();
        if (caps != null && caps.structuredErrors) {
            arr.add(new JsonPrimitive("structured_errors"));
        }
        if (caps != null && caps.canonicalMacro) {
            arr.add(new JsonPrimitive("canonical_macro"));
        }
        if (caps != null && caps.fuzzyMatch) {
            arr.add(new JsonPrimitive("fuzzy_match"));
        }
        // Step 05: surface state_delta / pulse so clients can detect the
        // new reply shape before relying on it.
        if (caps != null && caps.stateDelta) {
            arr.add(new JsonPrimitive("state_delta"));
        }
        if (caps != null && caps.pulse) {
            arr.add(new JsonPrimitive("pulse"));
        }
        // Step 06: advertise warnings so clients can detect the new
        // top-level warnings[] array on success/failure replies.
        if (caps != null && caps.warnings) {
            arr.add(new JsonPrimitive("warnings"));
        }
        // Step 09: surface histogram_delta so clients can feature-detect the
        // new top-level histogramDelta field on mutating replies.
        if (caps != null && caps.histogram) {
            arr.add(new JsonPrimitive("histogram_delta"));
        }
        // Step 10: phantom-dialog detection reporting is always on (it costs
        // one AWT scan per mutating handler and only attaches a field when a
        // new modal is on screen). Surface "phantom_dialog" unconditionally
        // so agents can feature-detect the new phantomDialog reply key; add
        // "auto_dismiss_phantoms" only when the caller explicitly opted in —
        // the agent needs to know whether the server WILL click a safe
        // button, not just whether it WILL report phantoms.
        arr.add(new JsonPrimitive("phantom_dialog"));
        if (caps != null && caps.autoDismissPhantoms) {
            arr.add(new JsonPrimitive("auto_dismiss_phantoms"));
        }
        // Step 07: the three Gemma-tools read-only commands are always on once
        // the server is up (no cap gate) so clients can feature-detect them
        // by name. Per plan: docs/tcp_upgrade/07_gemma_tools_server.md.
        arr.add(new JsonPrimitive("get_roi_state"));
        arr.add(new JsonPrimitive("get_display_state"));
        arr.add(new JsonPrimitive("get_console"));
        // Step 11: advertise response_dedup so clients can feature-detect the
        // new {"unchanged": true, "since": ts, "ageMs": N} short-form reply
        // on read-only polls. Per plan: docs/tcp_upgrade/11_dedup_response.md.
        if (caps != null && caps.dedup) {
            arr.add(new JsonPrimitive("response_dedup"));
        }
        // Step 12: advertise pattern_hints so clients can feature-detect the
        // new top-level hints[] array carrying PATTERN_DETECTED entries. Per
        // plan: docs/tcp_upgrade/12_per_agent_telemetry.md.
        if (caps != null && caps.patternHints) {
            arr.add(new JsonPrimitive("pattern_hints"));
        }
        // Step 13: advertise graph_delta so clients can feature-detect the
        // new top-level graphDelta field on mutating replies, and
        // get_image_graph unconditionally so clients can feature-detect the
        // always-available snapshot command. Per plan:
        // docs/tcp_upgrade/13_provenance_graph.md.
        if (caps != null && caps.graphDelta) {
            arr.add(new JsonPrimitive("graph_delta"));
        }
        arr.add(new JsonPrimitive("get_image_graph"));
        // Step 14: advertise "ledger" when auto-attach is on, and surface
        // the two explicit commands unconditionally so clients can
        // feature-detect them even when they've opted out of auto-attach.
        // Per plan: docs/tcp_upgrade/14_federated_ledger.md.
        if (caps != null && caps.ledger) {
            arr.add(new JsonPrimitive("ledger"));
        }
        arr.add(new JsonPrimitive("ledger_lookup"));
        arr.add(new JsonPrimitive("ledger_confirm"));
        // Step 15: surface "undo" only when this connection opted in (the
        // memory cost is the reason for the gate; an agent that hasn't said
        // it wants the cost shouldn't see the capability), but always
        // advertise the five command names so a client can discover them
        // and call hello again with undo=true to enable the snapshots.
        // Per plan: docs/tcp_upgrade/15_undo_stack_api.md.
        if (caps != null && caps.undo) {
            arr.add(new JsonPrimitive("undo"));
        }
        arr.add(new JsonPrimitive("rewind"));
        arr.add(new JsonPrimitive("branch"));
        arr.add(new JsonPrimitive("branch_list"));
        arr.add(new JsonPrimitive("branch_switch"));
        arr.add(new JsonPrimitive("branch_delete"));
        // Safe-mode v2 stage 02: advertise "safe_mode" when the master
        // switch is on, plus one entry per active per-guard option so
        // clients (and the Stage 07 status indicator) can feature-detect
        // exactly which guards will fire on this connection. Per plan:
        // docs/safe_mode_v2/02_master-switch-and-caps.md.
        if (caps != null && caps.safeMode) {
            arr.add(new JsonPrimitive("safe_mode"));
            for (String name : enabledSafeModeOptions(caps)) {
                arr.add(new JsonPrimitive(name));
            }
        }
        return arr;
    }

    /**
     * Safe-mode v2 stage 02: names of the per-guard option flags that are
     * currently active for this caller. The list is filtered through the
     * master {@link AgentCaps#safeMode} switch — when the master is off
     * the result is always empty, regardless of the per-guard fields,
     * because no guard will fire anyway.
     *
     * <p>Used in two places:
     * <ul>
     *   <li>{@link #enabledCapsFor} surfaces these names in the
     *       {@code enabled[]} array of the {@code hello} reply so a
     *       handshake-aware client can report exactly what's protecting
     *       it.</li>
     *   <li>Stage 07 (the toolbar status indicator) consumes the same
     *       list for its tooltip.</li>
     * </ul>
     *
     * <p>Names match the JSON wire form (snake_case) prefixed with
     * {@code safe_mode_option:} so they don't collide with top-level
     * capability names.
     *
     * @param caps  caller caps; {@code null} or non-safe-mode returns empty
     * @return ordered list of currently-active option names
     */
    static List<String> enabledSafeModeOptions(AgentCaps caps) {
        List<String> out = new ArrayList<String>();
        if (caps == null || !caps.safeMode || caps.safeModeOptions == null) {
            return out;
        }
        SafeModeOptions opt = caps.safeModeOptions;
        if (opt.blockBitDepthNarrowing)  out.add("safe_mode_option:block_bit_depth_narrowing");
        if (opt.blockNormalizeContrast)  out.add("safe_mode_option:block_normalize_contrast");
        if (opt.autoBackupRoiOnReset)    out.add("safe_mode_option:auto_backup_roi_on_reset");
        if (opt.autoSnapshotRescue)      out.add("safe_mode_option:auto_snapshot_rescue");
        if (opt.queueStormGuard)         out.add("safe_mode_option:queue_storm_guard");
        if (opt.autoSourceImageColumn)   out.add("safe_mode_option:auto_source_image_column");
        if (opt.scientificIntegrityScan) out.add("safe_mode_option:scientific_integrity_scan");
        return out;
    }

    // ---- Small JSON option helpers used by the hello handler. ----

    private static String optString(JsonObject obj, String key, String defaultValue) {
        if (obj == null) return defaultValue;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return defaultValue;
        try { return el.getAsString(); } catch (Exception e) { return defaultValue; }
    }

    private static boolean optBool(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null) return defaultValue;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return defaultValue;
        try { return el.getAsBoolean(); } catch (Exception e) { return defaultValue; }
    }

    private static int optInt(JsonObject obj, String key, int defaultValue) {
        if (obj == null) return defaultValue;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return defaultValue;
        try { return el.getAsInt(); } catch (Exception e) { return defaultValue; }
    }

    /**
     * Step 10: resolve the effective {@code autoDismissPhantoms} flag for a
     * single mutating-command call. Per-call overrides on the request (JSON
     * field {@code "autoDismissPhantoms"}) beat the connection-scoped
     * capability; the connection flag is the fallback when the request does
     * not mention the key at all. Keeps the gate narrow — detection always
     * runs, only the click action is opt-in.
     */
    private static boolean resolveAutoDismissPhantoms(JsonObject request, AgentCaps caps) {
        boolean base = caps != null && caps.autoDismissPhantoms;
        if (request != null && request.has("autoDismissPhantoms")) {
            JsonElement el = request.get("autoDismissPhantoms");
            if (el != null && el.isJsonPrimitive()) {
                try { return el.getAsBoolean(); }
                catch (Exception ignore) { return base; }
            }
        }
        return base;
    }

    private static Set<String> parseStringSet(JsonObject obj, String key) {
        Set<String> result = new HashSet<String>();
        if (obj == null) return result;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonArray()) return result;
        for (JsonElement item : el.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive()) {
                try { result.add(item.getAsString()); }
                catch (Exception ignore) {}
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Step 07: Gemma-tools read-only commands.
    // Per plan: docs/tcp_upgrade/07_gemma_tools_server.md
    // -----------------------------------------------------------------------

    /** Max ROI entries inlined in a single reply before truncating. */
    private static final int MAX_ROI_ENTRIES = 500;

    /** Default tail window for {@code get_console}, in bytes. */
    private static final int GET_CONSOLE_DEFAULT_TAIL = 2000;

    /**
     * {@code get_roi_state} — expose RoiManager contents directly so agents
     * stop writing macros to introspect ROI state. Returns {@code count:0,
     * rois:[]} when no RoiManager is open.
     */
    JsonObject handleGetRoiState(JsonObject request, AgentCaps caps) {
        RoiManager rm = RoiManager.getInstance();
        JsonObject out = new JsonObject();
        if (rm == null) {
            out.addProperty("count", 0);
            out.addProperty("selectedIndex", -1);
            out.add("rois", new JsonArray());
            return successResponse(out);
        }
        int count = rm.getCount();
        out.addProperty("count", count);
        out.addProperty("selectedIndex", rm.getSelectedIndex());
        Roi[] rois;
        try {
            rois = rm.getRoisAsArray();
        } catch (Exception e) {
            rois = new Roi[0];
        }
        JsonArray arr = new JsonArray();
        int limit = Math.min(rois.length, MAX_ROI_ENTRIES);
        for (int i = 0; i < limit; i++) {
            Roi roi = rois[i];
            if (roi == null) continue;
            JsonObject r = new JsonObject();
            r.addProperty("index", i);
            String name;
            try {
                name = rm.getName(i);
            } catch (Exception e) {
                name = roi.getName();
            }
            if (name != null) r.addProperty("name", name);
            r.addProperty("type", roiTypeName(roi));
            Rectangle b = roi.getBounds();
            JsonArray bounds = new JsonArray();
            if (b != null) {
                bounds.add(new JsonPrimitive(b.x));
                bounds.add(new JsonPrimitive(b.y));
                bounds.add(new JsonPrimitive(b.width));
                bounds.add(new JsonPrimitive(b.height));
            }
            r.add("bounds", bounds);
            arr.add(r);
        }
        out.add("rois", arr);
        if (rois.length > MAX_ROI_ENTRIES) {
            out.addProperty("truncated", true);
        }
        return successResponse(out);
    }

    /**
     * {@code get_display_state} — expose channel/slice/frame cursor, composite
     * mode, active-channel mask, display range and LUT so agents don't have
     * to guess or write macros to read them. Returns
     * {@code {"activeImage": null}} when no image is open.
     */
    JsonObject handleGetDisplayState(JsonObject request, AgentCaps caps) {
        ImagePlus imp = WindowManager.getCurrentImage();
        JsonObject out = new JsonObject();
        if (imp == null) {
            out.add("activeImage", JsonNull.INSTANCE);
            return successResponse(out);
        }
        out.addProperty("activeImage", imp.getTitle());
        out.addProperty("c", imp.getC());
        out.addProperty("z", imp.getZ());
        out.addProperty("t", imp.getT());
        out.addProperty("channels", imp.getNChannels());
        out.addProperty("slices", imp.getNSlices());
        out.addProperty("frames", imp.getNFrames());
        if (imp instanceof CompositeImage) {
            CompositeImage ci = (CompositeImage) imp;
            out.addProperty("compositeMode", compositeModeName(ci.getMode()));
            try {
                // IJ's CompositeImage.getActiveChannels() returns boolean[].
                // Serialise as the "111" bit-mask string documented in the
                // plan so clients can read it without decoding an array.
                out.addProperty("activeChannels",
                        activeChannelsMask(ci.getActiveChannels()));
            } catch (Exception ignore) {
                // older IJ versions may not expose this — skip silently
            }
        }
        JsonObject dr = new JsonObject();
        dr.addProperty("min", imp.getDisplayRangeMin());
        dr.addProperty("max", imp.getDisplayRangeMax());
        out.add("displayRange", dr);
        LUT lut = null;
        ImageProcessor ip = imp.getProcessor();
        if (ip != null) {
            try { lut = ip.getLut(); } catch (Exception ignore) { /* fall through */ }
        }
        if (lut != null) {
            out.addProperty("lut", lutNameOrHeuristic(lut));
        } else {
            out.add("lut", JsonNull.INSTANCE);
        }
        return successResponse(out);
    }

    /**
     * {@code get_console} — return the tail of buffered {@code System.out} /
     * {@code System.err}, so agents see Groovy / Jython stack traces that
     * never reach the ImageJ Log window. Requires {@link ConsoleCapture} to
     * have been installed at server start; returns empty strings if not.
     */
    JsonObject handleGetConsole(JsonObject request, AgentCaps caps) {
        int tail = (request != null && request.has("tail"))
                ? request.get("tail").getAsInt() : GET_CONSOLE_DEFAULT_TAIL;
        String stdout = ConsoleCapture.tailStdout(tail);
        String stderr = ConsoleCapture.tailStderr(tail);
        JsonObject out = new JsonObject();
        out.addProperty("stdout", stdout);
        out.addProperty("stderr", stderr);
        out.addProperty("combined", combineConsoleStreams(stdout, stderr));
        long stdoutBuffered = ConsoleCapture.stdoutSize();
        long stderrBuffered = ConsoleCapture.stderrSize();
        boolean truncated = tail >= 0
                && (stdoutBuffered > tail || stderrBuffered > tail);
        out.addProperty("truncated", truncated);
        out.addProperty("bufferedStdout", stdoutBuffered);
        out.addProperty("bufferedStderr", stderrBuffered);
        out.addProperty("installed", ConsoleCapture.isInstalled());
        return successResponse(out);
    }

    /**
     * Step 13: return the session-scoped image provenance DAG. Always
     * available — not gated on caps.graphDelta (which only gates delta
     * emission on mutating replies). Older history may be truncated once
     * the graph exceeds {@link ImageGraph#MAX_NODES} nodes (LRU eviction).
     * Per plan: docs/tcp_upgrade/13_provenance_graph.md.
     */
    JsonObject handleGetImageGraph() {
        return successResponse(imageGraph.snapshot());
    }

    // -----------------------------------------------------------------------
    // Step 14: federated ledger commands.
    // Per plan: docs/tcp_upgrade/14_federated_ledger.md
    // -----------------------------------------------------------------------

    /**
     * Step 14: visible for tests — swap the server's {@link LedgerStore} for
     * one that writes to a caller-owned temp directory so the unit tests
     * never touch the user's home folder. Thread-safe: the ledger is
     * only ever read via {@link #ledgerStore}, so replacing the reference
     * is atomic for all subsequent dispatches.
     */
    void setLedgerStore(LedgerStore store) {
        if (store != null) this.ledgerStore = store;
    }

    /**
     * {@code ledger_lookup}: query the federated mistake ledger for known-good
     * fixes for an error. Accepts {@code error_code}, {@code error_fragment},
     * and {@code macro_prefix} (any may be empty); returns the top matches
     * sorted by confidence × timesSeen. Works regardless of the
     * {@code caps.ledger} flag — the flag only gates implicit auto-attach.
     */
    JsonObject handleLedgerLookup(JsonObject request) {
        String errorCode     = optString(request, "error_code", "");
        String errorFragment = optString(request, "error_fragment", "");
        String macroPrefix   = optString(request, "macro_prefix", "");
        int max              = optInt(request, "max", 5);
        if (max < 1) max = 1;
        if (max > 20) max = 20;

        List<LedgerStore.Entry> hits = ledgerStore.lookup(
                errorCode, errorFragment, macroPrefix, max);

        JsonObject result = new JsonObject();
        result.addProperty("fingerprint",
                LedgerStore.fingerprint(errorCode, errorFragment, macroPrefix));
        JsonArray arr = new JsonArray();
        for (LedgerStore.Entry e : hits) {
            arr.add(LedgerStore.toSuggestedJson(e));
        }
        result.add("matches", arr);
        result.addProperty("ledgerSize", ledgerStore.size());
        return successResponse(result);
    }

    /**
     * {@code ledger_confirm}: record whether a fix worked. Updates counters
     * and {@code confirmedBy} on an existing entry, or creates a new one from
     * the supplied context. {@code caps.agentId} feeds the
     * {@code confirmedBy} set automatically when the caller did not pass an
     * explicit {@code agent_id}.
     */
    JsonObject handleLedgerConfirm(JsonObject request, AgentCaps caps) {
        String fingerprint = optString(request, "fingerprint", "");
        String errorCode   = optString(request, "error_code", "");
        String errorFrag   = optString(request, "error_fragment", "");
        String macroPrefix = optString(request, "macro_prefix", "");
        String fix         = optString(request, "fix", "");
        String example     = optString(request, "example_macro", "");
        boolean worked     = optBool(request, "worked", true);

        String agentId = optString(request, "agent_id",
                caps != null && caps.agentId != null ? caps.agentId : "");

        LedgerStore.Entry entry = ledgerStore.confirm(
                fingerprint, errorCode, errorFrag, macroPrefix,
                fix, example, agentId, worked);

        JsonObject result = new JsonObject();
        result.addProperty("fingerprint", entry.fingerprint);
        result.addProperty("timesSeen", entry.timesSeen);
        result.addProperty("confirmationsTrue", entry.confirmationsTrue);
        result.addProperty("confirmationsFalse", entry.confirmationsFalse);
        result.addProperty("confidence", LedgerStore.confidenceOf(entry));
        JsonArray by = new JsonArray();
        for (String a : entry.confirmedBy) by.add(a);
        result.add("confirmedBy", by);
        result.addProperty("ledgerSize", ledgerStore.size());
        result.addProperty("memoryOnly", ledgerStore.isMemoryOnly());
        return successResponse(result);
    }

    // -----------------------------------------------------------------------
    // Step 15: undo stack as API.
    // Per plan: docs/tcp_upgrade/15_undo_stack_api.md
    // -----------------------------------------------------------------------

    /** Build a typed error reply for an undo failure that callers wrap in
     *  the success-envelope's {@code error} slot. The shape mirrors how
     *  ledger / fuzzy-match emit structured errors so dispatch-time
     *  post-processing (friction logging, dedup) handles them uniformly. */
    private JsonObject undoErrorResponse(String code, String message,
                                         AgentCaps caps) {
        JsonObject env = new JsonObject();
        env.addProperty("ok", true);
        JsonObject body = new JsonObject();
        body.addProperty("success", false);
        ErrorReply err = new ErrorReply()
                .code(code)
                .category(ErrorReply.CAT_RUNTIME)
                .retrySafe(false)
                .message(message);
        body.add("error", err.buildJsonElement(caps));
        env.add("result", body);
        return env;
    }

    /** True when {@code caps.undo} is explicitly off — every undo command
     *  rejects with UNDO_DISABLED so the failure mode is self-documenting
     *  rather than silent ("nothing to rewind"). */
    private boolean undoDisabled(AgentCaps caps) {
        return caps == null || !caps.undo;
    }

    /**
     * {@code rewind}: pop the named image's undo stack and restore the
     * top-of-stack frame's pixels + ROI snapshot + Results CSV. Accepts
     * either {@code "n": <int>} (number of frames to walk back) or
     * {@code "to_call_id": "<id>"} (drop frames up through the matching
     * call). When {@code image_title} is omitted the active image is used.
     */
    JsonObject handleRewind(JsonObject request, AgentCaps caps) {
        if (undoDisabled(caps)) {
            return undoErrorResponse("UNDO_DISABLED",
                    "Undo is off for this connection. Send hello with "
                  + "capabilities.undo=true to enable.", caps);
        }
        if (macroInFlight.get() > 0) {
            return undoErrorResponse("UNDO_BUSY",
                    "A macro is in flight — wait for it to complete before "
                  + "rewinding.", caps);
        }

        String imageTitle = optString(request, "image_title", null);
        String toCallId = optString(request, "to_call_id", null);
        int n = optInt(request, "n", -1);

        // Resolve the title when the caller did not name one. For
        // to_call_id this is straightforward — the SessionUndo can find the
        // owning stack. For "n" rewinds we need a title, and the active
        // image is the only sane default.
        if (imageTitle == null && toCallId == null) {
            imageTitle = ImageGraph.captureActiveTitle();
            if (imageTitle == null) {
                return undoErrorResponse("UNDO_NO_TARGET",
                        "No image_title and no active image — cannot infer "
                      + "rewind target. Pass image_title or to_call_id.",
                        caps);
            }
        }

        List<UndoFrame> popped;
        // Wrap the entire frame walk + restore in MACRO_MUTEX so a concurrent
        // execute_macro that bumps macroInFlight after our fast-path check
        // cannot race the pixel write. The macroInFlight check above is
        // still the cheap early-fail; this is the correctness guarantee.
        // Plan §Failure modes: "Concurrent rewind during an in-flight macro.
        // Reject with UNDO_BUSY error — serialise via the existing macro
        // mutex." The MACRO_MUTEX block below is that serialisation.
        synchronized (MACRO_MUTEX) {
        if (toCallId != null && !toCallId.isEmpty()) {
            String resolvedTitle = imageTitle;
            if (resolvedTitle == null) {
                SessionUndo.ResolvedFrame rf = sessionUndo.resolveByCallId(toCallId);
                if (rf == null) {
                    return undoErrorResponse("UNDO_NOT_FOUND",
                            "No undo frame with call_id '" + toCallId
                          + "' on the active branch.", caps);
                }
                resolvedTitle = rf.imageTitle;
            }
            // Plan §Out-of-scope: rewinding past a script run is disallowed.
            // If a script-boundary frame sits between the top of the stack
            // and the targeted call id, refuse with UNDO_SCRIPT_BOUNDARY
            // before consuming any frames.
            UndoFrame boundary = sessionUndo.peekBoundaryBeforeCallId(
                    resolvedTitle, toCallId);
            if (boundary != null) {
                return undoErrorResponse("UNDO_SCRIPT_BOUNDARY",
                        "Cannot rewind past script-run boundary '"
                      + boundary.callId + "' on image '" + resolvedTitle
                      + "'. Groovy/Jython side effects are not reversible by"
                      + " rewind; create a branch before run_script if you"
                      + " need to explore.", caps);
            }
            popped = sessionUndo.rewindByCallId(resolvedTitle, toCallId);
            imageTitle = resolvedTitle;
            if (popped.isEmpty()) {
                return undoErrorResponse("UNDO_NOT_FOUND",
                        "No undo frame with call_id '" + toCallId
                      + "' on stack for image '" + resolvedTitle + "'.", caps);
            }
        } else {
            if (n <= 0) n = 1;
            UndoFrame boundary = sessionUndo.peekBoundaryWithin(imageTitle, n);
            if (boundary != null) {
                return undoErrorResponse("UNDO_SCRIPT_BOUNDARY",
                        "Cannot rewind past script-run boundary '"
                      + boundary.callId + "' on image '" + imageTitle
                      + "'. Reduce n or use branch_switch to a branch that"
                      + " did not run a script.", caps);
            }
            popped = sessionUndo.rewindByCount(imageTitle, n);
            if (popped.isEmpty()) {
                return undoErrorResponse("UNDO_NOT_FOUND",
                        "No undo frames on stack for image '" + imageTitle
                      + "'.", caps);
            }
        }

        // The frame we restore from is the LAST one popped. Earlier ones
        // are intermediate states the agent walked past — discarded.
        UndoFrame target = popped.get(popped.size() - 1);
        boolean restored = false;
        int restoredSlices = 0;
        String restoreError = null;
        String restoreErrorCode = null;
        try {
            ImagePlus imp = WindowManager.getImage(target.imageTitle);
            if (imp == null) {
                // Image was closed since the frame was captured — this is a
                // soft failure: the frame is still consumed (rewinding past
                // a closed image is the agent's call) but no pixel restore
                // happens. Rewind reports it cleanly so the agent can
                // re-open and re-rewind.
                restoreError = "Image '" + target.imageTitle
                        + "' is no longer open; pixels not restored.";
                restoreErrorCode = "UNDO_IMAGE_CLOSED";
            } else {
                restoredSlices = target.restorePixels(imp);
                restored = true;
            }
        } catch (IllegalArgumentException iae) {
            // restorePixels throws this when frame geometry doesn't match
            // the live image. Surface it as a typed code so the agent can
            // tell "image was resized between capture and rewind" apart
            // from a generic restore failure. Plan §Failure modes.
            restoreError = "Geometry mismatch: " + String.valueOf(iae.getMessage());
            restoreErrorCode = "UNDO_GEOMETRY_MISMATCH";
        } catch (Throwable t) {
            restoreError = "Restore failed: " + String.valueOf(t.getMessage());
            restoreErrorCode = "UNDO_RESTORE_FAILED";
        }

        // ROI restoration is best-effort and bounded — we replay the names
        // and bounding boxes only. Pixel-precise Roi geometry is a future
        // refinement.
        int restoredRois = 0;
        try {
            RoiManager rm = RoiManager.getInstance2();
            if (rm != null && !target.rois.isEmpty()) {
                rm.reset();
                for (UndoFrame.RoiSnapshot r : target.rois) {
                    rm.addRoi(new Roi(r.x, r.y, r.w, r.h));
                    int last = rm.getCount() - 1;
                    if (last >= 0 && r.name != null) {
                        try { rm.rename(last, r.name); }
                        catch (Throwable ignore) {}
                    }
                    restoredRois++;
                }
            }
        } catch (Throwable ignore) {
            // RoiManager APIs are best-effort across IJ versions.
        }

        // Results CSV restoration — wipe + replay. Fiji has no public CSV
        // import on ResultsTable; a future refinement could rebuild the
        // table from the captured CSV. v1 reports the byte count restored.
        int restoredResultsRows = 0;
        try {
            if (target.resultsCsv != null && !target.resultsCsv.isEmpty()) {
                // Count rows = newlines - 1 (header).
                int nl = 0;
                for (int i = 0; i < target.resultsCsv.length(); i++) {
                    if (target.resultsCsv.charAt(i) == '\n') nl++;
                }
                restoredResultsRows = Math.max(0, nl - 1);
            }
        } catch (Throwable ignore) {}

        JsonObject result = new JsonObject();
        result.addProperty("rewound", popped.size());
        result.addProperty("activeImage", target.imageTitle);
        result.addProperty("activeBranch", sessionUndo.activeBranchId());
        result.addProperty("restoredSlices", restoredSlices);
        result.addProperty("restoredROIs", restoredRois);
        result.addProperty("restoredResultsRows", restoredResultsRows);
        result.addProperty("pixelsRestored", restored);
        result.addProperty("framesRemaining", remainingFramesFor(imageTitle));
        if (target.diskSideEffect) {
            result.addProperty("diskSideEffectWarning",
                    "The producing macro contained a disk write (saveAs / "
                  + "IJ.save / etc). The file on disk has NOT been reverted "
                  + "by rewind.");
        }
        if (restoreError != null) {
            result.addProperty("restoreError", restoreError);
            if (restoreErrorCode != null) {
                result.addProperty("restoreErrorCode", restoreErrorCode);
            }
        }
        return successResponse(result);
        } // end synchronized (MACRO_MUTEX)
    }

    private int remainingFramesFor(String imageTitle) {
        if (imageTitle == null) return 0;
        SessionUndo.Branch active =
                sessionUndo.getBranch(sessionUndo.activeBranchId());
        if (active == null) return 0;
        UndoStack s = active.byImageTitle.get(imageTitle);
        return s == null ? 0 : s.size();
    }

    /**
     * {@code branch}: deep-copy the active branch's per-image stacks and
     * register the copy as a new branch. {@code from_call_id} is recorded
     * as a label; v1 does not truncate the copied history to that point —
     * see plan §Branch semantics for the v2 refinement.
     */
    JsonObject handleBranch(JsonObject request, AgentCaps caps) {
        if (undoDisabled(caps)) {
            return undoErrorResponse("UNDO_DISABLED",
                    "Undo is off for this connection.", caps);
        }
        String fromCallId = optString(request, "from_call_id", null);
        try {
            SessionUndo.Branch fresh = sessionUndo.createBranch(fromCallId);
            sessionUndo.switchBranch(fresh.id);
            JsonObject result = new JsonObject();
            result.addProperty("branchId", fresh.id);
            result.addProperty("baseCallId",
                    fresh.baseCallId == null ? "" : fresh.baseCallId);
            result.addProperty("activeBranch", sessionUndo.activeBranchId());
            result.addProperty("totalBranches",
                    sessionUndo.listBranches().size());
            return successResponse(result);
        } catch (IllegalStateException ise) {
            return undoErrorResponse("UNDO_BRANCH_CAP",
                    ise.getMessage(), caps);
        }
    }

    /** {@code branch_list}: enumerate every branch with its frame count and
     *  byte total. Always callable (even when undo is off — gives the
     *  client visibility into whether anyone has captured anything). */
    JsonObject handleBranchList(JsonObject request, AgentCaps caps) {
        JsonObject result = new JsonObject();
        result.addProperty("activeBranch", sessionUndo.activeBranchId());
        result.addProperty("totalBytes", sessionUndo.totalBytes());
        result.addProperty("totalFrames", sessionUndo.totalFrames());
        result.addProperty("globalEvictions", sessionUndo.globalEvictionCount());
        result.addProperty("globalCapBytes", SessionUndo.GLOBAL_CAP_BYTES);
        JsonArray arr = new JsonArray();
        for (SessionUndo.Branch b : sessionUndo.listBranches()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", b.id);
            if (b.baseCallId != null) o.addProperty("baseCallId", b.baseCallId);
            o.addProperty("createdMs", b.createdMs);
            o.addProperty("frames", b.totalFrames());
            o.addProperty("bytes", b.totalBytes());
            JsonArray titles = new JsonArray();
            for (String t : b.imageTitles()) titles.add(new JsonPrimitive(t));
            o.add("imageTitles", titles);
            arr.add(o);
        }
        result.add("branches", arr);
        return successResponse(result);
    }

    /** {@code branch_switch}: activate a different branch. Subsequent
     *  pushes / rewinds / lookups use the new active branch. */
    JsonObject handleBranchSwitch(JsonObject request, AgentCaps caps) {
        if (undoDisabled(caps)) {
            return undoErrorResponse("UNDO_DISABLED",
                    "Undo is off for this connection.", caps);
        }
        String id = optString(request, "branch_id", "");
        if (id == null || id.isEmpty()) {
            return undoErrorResponse("UNDO_BAD_REQUEST",
                    "branch_switch requires branch_id.", caps);
        }
        boolean ok = sessionUndo.switchBranch(id);
        if (!ok) {
            return undoErrorResponse("UNDO_NOT_FOUND",
                    "No branch with id '" + id + "'.", caps);
        }
        JsonObject result = new JsonObject();
        result.addProperty("activeBranch", sessionUndo.activeBranchId());
        return successResponse(result);
    }

    /** {@code branch_delete}: discard a branch's state. {@link
     *  SessionUndo#MAIN_BRANCH} cannot be deleted. */
    JsonObject handleBranchDelete(JsonObject request, AgentCaps caps) {
        if (undoDisabled(caps)) {
            return undoErrorResponse("UNDO_DISABLED",
                    "Undo is off for this connection.", caps);
        }
        String id = optString(request, "branch_id", "");
        if (id == null || id.isEmpty()) {
            return undoErrorResponse("UNDO_BAD_REQUEST",
                    "branch_delete requires branch_id.", caps);
        }
        if (SessionUndo.MAIN_BRANCH.equals(id)) {
            return undoErrorResponse("UNDO_PROTECTED_BRANCH",
                    "The 'main' branch cannot be deleted.", caps);
        }
        boolean ok = sessionUndo.deleteBranch(id);
        if (!ok) {
            return undoErrorResponse("UNDO_NOT_FOUND",
                    "No branch with id '" + id + "'.", caps);
        }
        JsonObject result = new JsonObject();
        result.addProperty("deleted", id);
        result.addProperty("activeBranch", sessionUndo.activeBranchId());
        result.addProperty("totalBranches", sessionUndo.listBranches().size());
        return successResponse(result);
    }

    /**
     * Step 15 helper: capture a frame just before a mutating handler runs,
     * but only when {@code caps.undo} is on for the originating socket and
     * an active image is present. Failures are swallowed — undo is a
     * best-effort feature; a snapshot failure must never block the macro.
     *
     * @param callId opaque id assigned to this call
     * @param macroSrc source string of the producing macro (for disk-write
     *                 detection and journaling)
     * @param caps capabilities of the originating socket
     * @return the captured frame, or null when caps.undo is off / no
     *         active image / capture failed
     */
    UndoFrame captureUndoFrameIfEnabled(String callId, String macroSrc,
                                        AgentCaps caps) {
        if (caps == null || !caps.undo) return null;
        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp == null) return null;
            String csv = null;
            try {
                csv = stateInspector != null
                        ? stateInspector.getResultsTableCSV() : null;
            } catch (Throwable ignore) {}
            RoiManager rm = RoiManager.getInstance();
            boolean diskWrite = DestructiveScanner.hasDiskWrites(macroSrc);
            UndoFrame f = UndoFrame.capture(callId, imp, rm, csv, diskWrite);
            if (f != null) sessionUndo.pushFrame(f);
            return f;
        } catch (Throwable t) {
            // Snapshot failure is non-fatal. Log and move on so the macro
            // path is unaffected.
            try {
                IJ.log("[ImageJAI-Undo] capture skipped: "
                        + String.valueOf(t.getMessage()));
            } catch (Throwable ignore) {}
            return null;
        }
    }

    /**
     * Stage 03 (docs/safe_mode_v2/03_auto-snapshot-rescue.md): take an undo
     * snapshot the agent never asked for, so that a macro which sneaks past
     * the destructive scanner and corrupts pixels at full scale is still
     * recoverable via {@code rewind}. Fires only when:
     * <ul>
     *   <li>the master safe-mode switch is on,</li>
     *   <li>{@code safe_mode_options.auto_snapshot_rescue} is on (default),</li>
     *   <li>{@code caps.undo} is OFF — the undo path already pushes a frame
     *       at this point and we must not double-snapshot.</li>
     * </ul>
     *
     * <p>Returns a {@link SessionUndo.RescueHandle} the caller is expected to
     * either {@link SessionUndo.RescueHandle#commit commit} (on macro failure
     * or thrown exception, so the user can rewind) or
     * {@link SessionUndo.RescueHandle#release release} (on macro success, so
     * we don't grow the undo ring on every successful safe-mode call).
     *
     * <p>Returns null when rescue does not apply: caps disabled, no active
     * image, virtual stack (per plan §Known risks — duplicating a virtual
     * stack would force a multi-GB read from disk), or capture failure.
     * Capture failures are swallowed and logged so a flaky snapshot path
     * never blocks the macro itself.
     */
    SessionUndo.RescueHandle captureRescueFrameIfEnabled(String callId,
                                                         String macroSrc,
                                                         AgentCaps caps) {
        if (caps == null) return null;
        if (!caps.safeMode) return null;
        SafeModeOptions opt = caps.safeModeOptions;
        if (opt == null || !opt.autoSnapshotRescue) return null;
        // The undo path already snapshots at this point. Re-snapshotting via
        // rescue would push a near-identical frame and double the per-call
        // memory cost for opt-in undo agents. Skip and let the undo frame
        // do the recovery work.
        if (caps.undo) return null;
        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp == null) return null;
            try {
                ij.ImageStack stk = imp.getStack();
                // VirtualStack.duplicate() reads the whole disk-backed
                // sequence into RAM — gigabytes for a typical light-sheet
                // dataset. Plan §Known risks: skip with a logged note rather
                // than freeze Fiji on a multi-second snapshot.
                if (stk != null && stk.isVirtual()) {
                    try {
                        IJ.log("[ImageJAI-SafeMode] rescue snapshot skipped: "
                                + "virtual stack (" + imp.getTitle() + ")");
                    } catch (Throwable ignore) {}
                    return null;
                }
            } catch (Throwable ignore) {
                // ImageStack.isVirtual is a >= IJ 1.40 API; older Fiji forks
                // throw NoSuchMethodError. Treat as "not virtual" and proceed.
            }
            String csv = null;
            try {
                csv = stateInspector != null
                        ? stateInspector.getResultsTableCSV() : null;
            } catch (Throwable ignore) {}
            RoiManager rm = RoiManager.getInstance();
            boolean diskWrite = DestructiveScanner.hasDiskWrites(macroSrc);
            UndoFrame f = UndoFrame.capture(callId, imp, rm, csv, diskWrite);
            return sessionUndo.wrapRescueFrame(f);
        } catch (Throwable t) {
            try {
                IJ.log("[ImageJAI-SafeMode] rescue capture skipped: "
                        + String.valueOf(t.getMessage()));
            } catch (Throwable ignore) {}
            return null;
        }
    }

    /**
     * Name for {@link Roi#getType()}. Kept in the server rather than pulled
     * from Roi because Roi only exposes integer constants; a readable string
     * is what agents actually want to see.
     */
    private static String roiTypeName(Roi roi) {
        if (roi == null) return "unknown";
        switch (roi.getType()) {
            case Roi.RECTANGLE: return "rectangle";
            case Roi.OVAL: return "oval";
            case Roi.POLYGON: return "polygon";
            case Roi.FREEROI: return "freehand";
            case Roi.TRACED_ROI: return "traced";
            case Roi.LINE: return "line";
            case Roi.POLYLINE: return "polyline";
            case Roi.FREELINE: return "freeline";
            case Roi.ANGLE: return "angle";
            case Roi.COMPOSITE: return "composite";
            case Roi.POINT: return "point";
            default: return "unknown";
        }
    }

    /**
     * Render {@link CompositeImage#getActiveChannels()} as a string bit-mask
     * like {@code "111"} where position {@code i} is {@code '1'} if channel
     * {@code i+1} is currently active. Matches the shape documented in the
     * Step 07 plan.
     */
    private static String activeChannelsMask(boolean[] active) {
        if (active == null || active.length == 0) return "";
        StringBuilder sb = new StringBuilder(active.length);
        for (int i = 0; i < active.length; i++) {
            sb.append(active[i] ? '1' : '0');
        }
        return sb.toString();
    }

    /** Map {@link CompositeImage#getMode()} int to a readable mode name. */
    private static String compositeModeName(int mode) {
        switch (mode) {
            case CompositeImage.COMPOSITE: return "composite";
            case CompositeImage.COLOR: return "color";
            case CompositeImage.GRAYSCALE: return "grayscale";
            default: return "unknown";
        }
    }

    /**
     * LUT naming heuristic. IJ's {@link LUT} doesn't reliably carry its source
     * name, so for the common built-ins we match byte signature; anything
     * else is labelled {@code "custom"} rather than guessing wrong.
     */
    static String lutNameOrHeuristic(LUT lut) {
        if (lut == null) return "custom";
        try {
            java.lang.reflect.Method m = lut.getClass().getMethod("getName");
            Object name = m.invoke(lut);
            if (name instanceof String) {
                String s = (String) name;
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignore) {
            // fall through to byte heuristic
        }
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        try {
            lut.getReds(r);
            lut.getGreens(g);
            lut.getBlues(b);
        } catch (Exception e) {
            return "custom";
        }
        if (isIdentityRamp(r) && isIdentityRamp(g) && isIdentityRamp(b)) return "Grays";
        if (isIdentityRamp(r) && isZeroChannel(g) && isZeroChannel(b)) return "Red";
        if (isZeroChannel(r) && isIdentityRamp(g) && isZeroChannel(b)) return "Green";
        if (isZeroChannel(r) && isZeroChannel(g) && isIdentityRamp(b)) return "Blue";
        return "custom";
    }

    private static boolean isIdentityRamp(byte[] ch) {
        for (int i = 0; i < 256; i++) {
            if ((ch[i] & 0xff) != i) return false;
        }
        return true;
    }

    private static boolean isZeroChannel(byte[] ch) {
        for (int i = 0; i < 256; i++) {
            if (ch[i] != 0) return false;
        }
        return true;
    }

    /**
     * Interleave stdout and stderr into a single stream for agents that don't
     * care which channel a line came from. The stream-ordered concatenation
     * is a best-effort ordering aid — precise interleaving is impossible
     * without per-byte timestamps, so we label each block.
     */
    private static String combineConsoleStreams(String stdout, String stderr) {
        boolean hasOut = stdout != null && !stdout.isEmpty();
        boolean hasErr = stderr != null && !stderr.isEmpty();
        if (!hasOut && !hasErr) return "";
        if (hasOut && !hasErr) return stdout;
        if (!hasOut && hasErr) return stderr;
        StringBuilder sb = new StringBuilder(stdout.length() + stderr.length() + 32);
        sb.append(stdout);
        if (!stdout.endsWith("\n")) sb.append('\n');
        sb.append(stderr);
        return sb.toString();
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

    private JsonObject handleExecuteMacro(JsonObject request, AgentCaps caps) {
        // Step 01: caps is plumbed in so later steps (02 structured errors,
        // 03 canonical macro echo, 05 pulse / stateDelta) can shape the reply
        // per agent without another signature sweep.

        JsonElement codeElement = request.get("code");
        if (codeElement == null || !codeElement.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for execute_macro");
        }
        final String code = codeElement.getAsString();
        final long macroTimeoutMs = resolveTimeoutMs(request, MACRO_TIMEOUT_MS);

        // Stage 04 (docs/safe_mode_v2/04_queue-storm-per-image.md): block a
        // second macro on the same image while a previous macro is paused
        // on a Fiji modal dialog. Resolves the target image title from the
        // last selectImage("...")/selectWindow("...") call in the macro
        // (falling back to the active window) and short-circuits with
        // QUEUE_STORM_BLOCKED if {@link #inFlightByImage} carries a
        // PAUSED_ON_DIALOG entry for that title. Macros on a different
        // image fall through. Runs BEFORE the test seam so unit tests can
        // verify the early-exit without the seam swallowing the call.
        final String queueStormTarget = isQueueStormGuardEnabled(caps)
                ? resolveTargetImageTitleWithFallback(code)
                : null;
        if (queueStormTarget != null) {
            ActiveMacro inflight = inFlightByImage.get(queueStormTarget);
            if (inflight != null && inflight.state == MacroState.PAUSED_ON_DIALOG) {
                return queueStormBlockedReply(inflight, queueStormTarget, caps);
            }
        }

        // Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md):
        // scientific-integrity scanner. Same gating shape as the queue-storm
        // guard above — master safe-mode AND the per-guard option. Runs
        // before the test seam so the rejection path is exercised by unit
        // tests with a witness map. Findings split into REJECT (block the
        // macro and return a structured-error reply) and BACKUP_THEN_ALLOW
        // (currently only the ROI wipe rule — write the backup ZIP and
        // proceed). The macro never runs when any REJECT row is present.
        if (isScientificIntegrityScanEnabled(caps)) {
            DestructiveScanner.Context scanCtx = captureScannerContext(caps);
            List<DestructiveScanner.DestructiveOp> findings =
                    DestructiveScanner.scan(code, scanCtx);
            if (!findings.isEmpty()) {
                List<DestructiveScanner.DestructiveOp> rejects =
                        DestructiveScanner.rejections(findings);
                if (!rejects.isEmpty()) {
                    return destructiveBlockedReply(rejects, caps);
                }
                for (DestructiveScanner.DestructiveOp op : DestructiveScanner.backups(findings)) {
                    if (DestructiveScanner.RULE_ROI_WIPE.equals(op.ruleId)
                            && caps.safeModeOptions != null
                            && caps.safeModeOptions.autoBackupRoiOnReset) {
                        runRoiAutoBackup(op, caps);
                    }
                }
            }
        }

        // Test seam: when set, short-circuit to a stub response so unit tests
        // can verify caps inheritance through batch/run/intent without
        // spinning up a real Fiji session. Production never sets this.
        // Per plan: docs/safe_mode_v2/01_fix-batch-run-bypass.md.
        if (executeMacroForTest != null) {
            return executeMacroForTest.apply(request, caps);
        }

        // Step 04: fuzzy plugin-name validation. Gate on caps.fuzzyMatch so
        // clients that opted out (or never said hello — DEFAULT_CAPS has the
        // field at its default, true) still get the backwards-compatible
        // path. Rejection short-circuits: no macro runs, the reply carries
        // PLUGIN_NOT_FOUND with top-3 suggestions. Corrections patch the
        // macro in place and surface an autocorrected[] array below.
        final PluginNameValidator.Result validation =
                (caps != null && caps.fuzzyMatch)
                        ? PluginNameValidator.validate(code)
                        : null;
        if (validation != null && validation.hasRejections()) {
            JsonObject rej = new JsonObject();
            rej.addProperty("success", false);
            ErrorReply err = PluginNameValidator.buildPluginNotFoundError(validation.rejections);
            rej.add("error", err.buildJsonElement(caps));
            return successResponse(rej);
        }
        final String codeToRun = (validation != null && validation.hasCorrections())
                ? validation.patchedCode
                : code;

        // Step 10: snapshot the set of modal dialogs on screen BEFORE the
        // macro runs. Any new modal that is still present after the call
        // returns is reported as a phantom dialog (and optionally
        // auto-dismissed when the agent opted in). Per plan:
        // docs/tcp_upgrade/10_phantom_dialog_detector.md. Resolve the
        // dismiss flag up-front so the per-call {@code autoDismissPhantoms}
        // override on the request has a chance to toggle behaviour even for
        // a connection whose hello never opted in.
        final Set<Window> modalBefore = PhantomDialogDetector.currentModalWindows();
        final boolean phantomAutoDismiss = resolveAutoDismissPhantoms(request, caps);

        // Step 13: snapshot the open-image set and active-image title BEFORE
        // the macro runs so the post-call diff can register new images as
        // derived nodes in the provenance graph. Per plan:
        // docs/tcp_upgrade/13_provenance_graph.md. Captured regardless of
        // caps.graphDelta — the graph itself is always maintained; the flag
        // only gates whether the reply carries a graphDelta field.
        final Set<String> graphTitlesBefore = ImageGraph.captureOpenTitles();
        final String graphActiveTitleBefore = ImageGraph.captureActiveTitle();
        final long graphMarkerBefore = imageGraph.currentMarker();

        // Step 15: capture an undo frame BEFORE the macro mutates pixels,
        // when caps.undo is on for this socket. The frame holds compressed
        // pixels + ROI snapshot + Results CSV, addressable by callId so a
        // subsequent {@code rewind to_call_id} can restore precisely. Per
        // plan: docs/tcp_upgrade/15_undo_stack_api.md. Snapshot failures
        // are swallowed so undo never blocks the macro path.
        final String undoCallId = nextCallId();
        final UndoFrame undoFrame = captureUndoFrameIfEnabled(
                undoCallId, code, caps);

        // Stage 03 (docs/safe_mode_v2/03_auto-snapshot-rescue.md): take a
        // deferred-push snapshot for safe-mode callers that have not opted
        // into the full undo path. Released on a clean success; committed on
        // failure / Throwable so a {@code rewind} can roll back damage even
        // when the agent never asked for undo.
        final SessionUndo.RescueHandle rescue = captureRescueFrameIfEnabled(
                undoCallId, code, caps);

        // Stage 06 (docs/safe_mode_v2/06_results-source-image-column.md):
        // snapshot ResultsTable.getCounter() and the active image's title
        // BEFORE the macro runs so postExec can stamp Source_Image on every
        // newly-added row. Honors the per-call escape hatch
        // ({@code // @safe_mode allow: legacy_results_format}) by skipping
        // tagger construction entirely. preExec captures the title NOW; the
        // post hook prefers the FINAL active image's title (limitation
        // documented in the plan §Known risks for mid-macro selectImage).
        final SourceImageTagger sourceImageTagger =
                (isSourceImageColumnEnabled(caps) && !SourceImageTagger.macroOptsOut(code))
                        ? new SourceImageTagger() : null;
        if (sourceImageTagger != null) {
            try { sourceImageTagger.preExec(WindowManager.getCurrentImage()); }
            catch (Throwable ignore) {}
        }

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
        // Step 03: start capturing the IJ Recorder's canonical macro text so
        // we can echo it back when it differs from what the agent submitted
        // (docs/tcp_upgrade/03_canonical_macro_echo.md). null = no capture
        // (caps opted out, or Recorder unavailable in this JVM).
        final RecorderCapture recorderCapture = (caps != null && caps.canonicalMacro)
                ? RecorderCapture.begin()
                : null;
        try {
        // Run IJ.runMacro on a worker thread so the TCP handler can keep polling
        // for dialogs / timeout and return structured failure feedback instead
        // of leaving the client blocked until its socket times out.
        JsonObject result = new JsonObject();
        // Step 05: collect diff fields into a single struct so the final
        // serialise step can pick between the grouped "stateDelta" sub-object
        // and the legacy flat top-level keys based on caps.stateDelta.
        final StateDelta delta = new StateDelta();
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

        // Snapshot active title + results-CSV length BEFORE IJ.runMacro so the
        // failure branch can tell "plugin actually produced output before the
        // dialog-pause" apart from "dialog-pause on the very first line,
        // nothing happened". Without this, mirroring the success-path snapshot
        // on failure would always report stale state as a side effect.
        String preActiveTitle = null;
        int preResultsLen = 0;
        try {
            ImageInfo preActive = stateInspector.getActiveImageInfo();
            preActiveTitle = preActive != null ? preActive.getTitle() : null;
        } catch (Throwable ignore) {}
        try {
            String preCsv = stateInspector.getResultsTableCSV();
            preResultsLen = preCsv != null ? preCsv.length() : 0;
        } catch (Throwable ignore) {}

        // Step 09: snapshot the active image's intensity distribution BEFORE
        // the macro runs so we can diff it against the post-macro snapshot.
        // Per plan: docs/tcp_upgrade/09_histogram_delta.md. The snapshot is
        // best-effort — a null / too-large active image surfaces as a skip
        // envelope (too-large) or a dropped field (no image) at the end.
        HistogramDelta.Snapshot histBefore = null;
        if (caps != null && caps.histogram) {
            try {
                histBefore = HistogramDelta.snapshot(WindowManager.getCurrentImage());
            } catch (Throwable ignore) {}
        }

        long startTime = System.currentTimeMillis();
        // Step 15: announce we're about to start mutating so a concurrent
        // rewind (from another socket) returns UNDO_BUSY rather than
        // racing the in-flight macro. Decrement happens in the matching
        // finally below so an exception unwinds the counter cleanly.
        macroInFlight.incrementAndGet();
        // Stage 04 (docs/safe_mode_v2/04_queue-storm-per-image.md): register
        // this macro in {@link #inFlightByImage} so a concurrent
        // {@code execute_macro} on the same image can detect a paused-on-
        // dialog state and short-circuit with QUEUE_STORM_BLOCKED. The
        // entry's {@code state} starts as RUNNING; it flips to
        // PAUSED_ON_DIALOG inside the blocking-dialog branch below. Always
        // recorded — not gated on caps — so a guarded second-macro
        // request still sees the in-flight state of an unguarded first
        // macro that happened to pause. Removed in the matching finally
        // below so exceptions / interrupts unwind cleanly. Resolved here
        // (not at handler entry) because the resolution may need the
        // active-image fallback, which the entry-level guard check
        // already determined.
        final String inflightKey = (queueStormTarget != null)
                ? queueStormTarget
                : resolveTargetImageTitleWithFallback(code);
        final ActiveMacro activeEntry;
        if (inflightKey != null) {
            activeEntry = new ActiveMacro(Long.toString(macroId), MacroState.RUNNING);
            inFlightByImage.put(inflightKey, activeEntry);
        } else {
            activeEntry = null;
        }
        try {
        // Serialize every execute_macro call JVM-wide. ImageJ has a single global
        // Interpreter / WindowManager — two overlapping macros (one zombied on a
        // blocking dialog, a second sent by the agent after it received the
        // server's error) corrupt each other's active-image state. Root cause of
        // the "orig gets thresholded and every Duplicate inherits" bug.
        synchronized (MACRO_MUTEX) {
        // Prior-error *messages* are now snapshotted, but a Macro Error
        // *dialog* from the previous failed call can still be sitting on the
        // AWT event queue. Dismiss it only while holding MACRO_MUTEX so a
        // second request cannot close a live Macro Error dialog that still
        // belongs to the previous synchronized caller.
        dismissOpenDialogs("Macro Error");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = null;
        try {
            future = executor.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    // Step 04: codeToRun is the fuzzy-validated macro — either
                    // the original (no corrections) or a patched string with
                    // run("name") spellings replaced by canonical names.
                    return IJ.runMacro(codeToRun);
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
                        abortMacroFuture(future);
                        failureMessage = detected;
                        break;
                    }
                    // Any modal dialog means the macro is blocked waiting for
                    // interaction — e.g. run("Gaussian Blur...") with no args
                    // opens the GenericDialog. Surface it now (next poll, ≤150ms)
                    // instead of waiting the full MACRO_TIMEOUT_MS.
                    String blocking = detectBlockingDialog(dialogs);
                    if (blocking != null) {
                        // Stage 04: flip the in-flight entry to
                        // PAUSED_ON_DIALOG BEFORE abort/dismiss so any
                        // concurrent execute_macro for the same image
                        // sees the paused state during the abort window.
                        // The dialog title rides along on the entry so
                        // the QUEUE_STORM_BLOCKED reply can name it.
                        if (activeEntry != null) {
                            activeEntry.state = MacroState.PAUSED_ON_DIALOG;
                            activeEntry.dialogTitle = firstBlockingDialogTitle(dialogs);
                        }
                        abortMacroFuture(future);
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
                                // Step 05: route through the StateDelta struct so the
                                // grouped shape is honoured. dialogsAutoDismissed (int
                                // count) stays top-level — it's a signal, not a diff.
                                delta.dismissedDialogs = dismissedCaptured;
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
                    if (!timeoutDisabled(macroTimeoutMs)
                            && (System.currentTimeMillis() - startTime) > macroTimeoutMs) {
                        abortMacroFuture(future);
                        failureMessage = "Macro execution timed out after " + macroTimeoutMs + "ms";
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
            if (future != null && !future.isDone()) abortMacroFuture(future);
            executor.shutdownNow();
            return errorResponse("Interrupted");
        } finally {
            // Belt-and-braces: if we broke out of the loop without the worker
            // actually terminating (e.g. IJ.Macro.abort() failed or the user
            // had an OpenDialog the dismiss path could not kill), force-kill
            // now so the next synchronized(MACRO_MUTEX) caller does not start
            // on top of a live interpreter.
            if (future != null && !future.isDone()) abortMacroFuture(future);
            executor.shutdownNow();
        }
        if (success) {
            dialogs = safeDetectOpenDialogs();
            String detected = detectIjMacroError(logLenBefore, priorInterpError, priorIjError, dialogs);
            if (detected != null) {
                success = false;
                failureMessage = detected;
            }
        }
        } // end synchronized (MACRO_MUTEX)
        } finally {
            // Step 15: in-flight counter must drop even if the synchronized
            // block threw — otherwise rewind locks out forever.
            macroInFlight.decrementAndGet();
            // Stage 04: drop our in-flight entry so the queue-storm guard
            // releases on the next request. Removed by-key only when the
            // current entry is still ours — defensive against a future
            // re-entrant register on the same image overwriting us, even
            // though MACRO_MUTEX precludes that today.
            if (inflightKey != null && activeEntry != null) {
                inFlightByImage.remove(inflightKey, activeEntry);
            }
        }

        // Stage 06 (docs/safe_mode_v2/06_results-source-image-column.md):
        // tag every Results row added during this macro with the FINAL
        // active image's title. Runs on both success and failure so partial
        // work (e.g. a plugin that pushed rows before a dialog-pause) still
        // gets attributed. Placed BEFORE the CSV snapshot below so the
        // {@code resultsTable} field on the TCP response also carries the
        // {@code Source_Image} column. Best-effort — a Throwable from the
        // tagger never converts a successful macro into an error reply.
        if (sourceImageTagger != null) {
            try { sourceImageTagger.postExec(WindowManager.getCurrentImage()); }
            catch (Throwable ignore) {}
        }

        long elapsed = System.currentTimeMillis() - startTime;
        // Capture any new ImageJ Log lines the macro produced via print() / IJ.log(),
        // so the agent does not need to follow every run_macro with a get_log call.
        // Snapshot was taken at logLenBefore (line ~1174). Cap matches the
        // detectIjMacroError cap (16 KB) to keep replies bounded for chatty macros.
        String logDelta = null;
        try {
            String postLog = IJ.getLog();
            if (postLog != null && postLog.length() > logLenBefore) {
                String logSlice = postLog.substring(logLenBefore);
                if (logSlice.length() > 16384) {
                    logSlice = logSlice.substring(logSlice.length() - 16384);
                }
                logDelta = logSlice;
            }
        } catch (Throwable ignore) {}
        if (success) {
            result.addProperty("success", true);
            result.addProperty("output", macroReturn != null ? macroReturn : "");
            // Step 05: logDelta flows through the StateDelta struct so it can
            // be nested under "stateDelta" when caps.stateDelta is on.
            if (logDelta != null) {
                delta.logDelta = logDelta;
            }
            result.addProperty("executionTimeMs", elapsed);
            // Step 04: surface any fuzzy corrections applied before the macro
            // ran so the agent can learn the canonical spelling.
            if (validation != null && validation.hasCorrections()) {
                result.add("autocorrected",
                        PluginNameValidator.buildAutocorrectedArray(validation.corrections));
            }

            try {
                ImageInfo active = stateInspector.getActiveImageInfo();
                if (active != null) {
                    JsonArray newImages = new JsonArray();
                    newImages.add(active.getTitle());
                    delta.newImages = newImages;
                }
                String csv = stateInspector.getResultsTableCSV();
                if (csv != null && !csv.isEmpty()) {
                    delta.resultsTable = csv;
                }
                stateInspector.checkResultsTableChange();
            } catch (Exception ignore) {
                // State inspection is best-effort
            }
        }

        if (!success) {
            // Snapshot newImages + resultsTable even on failure. A dialog-pause
            // aborts mid-macro, but earlier steps (the plugin itself, e.g.
            // "3D Objects Counter") often ran to completion and produced
            // output before whatever late step popped a dialog. Without this,
            // the agent sees only "Macro paused on modal dialog..." and retries
            // blindly instead of reading the result that already exists.
            // Only count state that CHANGED during this macro as a side effect;
            // stale pre-macro state must not soften the error.
            boolean sideEffectsLanded = false;
            // Step 02: build a sideEffects object in parallel so the structured
            // error carries the same bookkeeping. For legacy (string) replies
            // we still add newImages/resultsTable at the top level; the object
            // is only attached when caps.structuredErrors is on.
            JsonObject sideEffectsObj = new JsonObject();
            try {
                ImageInfo postActive = stateInspector.getActiveImageInfo();
                String postTitle = postActive != null ? postActive.getTitle() : null;
                if (postTitle != null && !postTitle.isEmpty()
                        && !postTitle.equals(preActiveTitle)) {
                    JsonArray newImages = new JsonArray();
                    newImages.add(postTitle);
                    // Step 05: failure-path newImages routes through the delta
                    // struct so the grouped shape stays consistent with the
                    // success path. sideEffectsObj keeps its own copy — that
                    // lives inside the structured error payload (step 02) and
                    // is a separate contract from the top-level diff shape.
                    delta.newImages = newImages;
                    JsonArray newImagesCopy = new JsonArray();
                    newImagesCopy.add(postTitle);
                    sideEffectsObj.add("newImages", newImagesCopy);
                    sideEffectsLanded = true;
                }
                String csv = stateInspector.getResultsTableCSV();
                int postLen = csv != null ? csv.length() : 0;
                if (csv != null && !csv.isEmpty() && postLen != preResultsLen) {
                    delta.resultsTable = csv;
                    sideEffectsObj.addProperty("resultsChanged", true);
                    sideEffectsLanded = true;
                }
            } catch (Exception ignore) {
                // State inspection is best-effort on the failure path.
            }
            String rawError = failureMessage != null ? failureMessage : "Unknown macro error";
            // Soften the error prefix when side effects landed AND the failure
            // was a dialog-pause (not a compile error). Leaves hard failures
            // like "Unrecognized command" with their original strong wording.
            if (sideEffectsLanded && rawError.startsWith("Macro paused on modal dialog")) {
                rawError = "Macro interrupted AFTER producing output "
                        + "(see newImages / resultsTable — the plugin's work "
                        + "landed; do NOT retry). Pause was: " + rawError;
            }
            // Step 02: emit a typed error when caps.structuredErrors is on;
            // fall back to the legacy plain-string shape otherwise. Both paths
            // carry the same raw message.
            if (logDelta != null) {
                sideEffectsObj.addProperty("logDelta", logDelta);
            }
            ErrorReply err = ErrorReply.classifyMacroError(rawError, sideEffectsLanded);
            if (sideEffectsObj.size() > 0) err.sideEffects(sideEffectsObj);
            // Step 14: auto-attach top ledger matches so the agent sees the
            // known fix on the same round-trip. Gated on caps.ledger — the
            // two explicit ledger commands still work when this is off.
            // suggested[] is only serialised by ErrorReply when
            // caps.structuredErrors is on, so legacy clients (plain-string
            // error) pay zero tokens for this enrichment.
            if (caps != null && caps.ledger && caps.structuredErrors) {
                try {
                    List<LedgerStore.Entry> hits = ledgerStore.lookup(
                            err.code(), rawError, code, 3);
                    for (LedgerStore.Entry e : hits) {
                        err.addSuggested(LedgerStore.toSuggestedJson(e));
                    }
                } catch (Throwable ignore) {
                    // Ledger IO is best-effort on the error path — a broken
                    // ledger file must never convert a macro error into a
                    // harder-to-diagnose server 500.
                }
            }
            result.addProperty("success", false);
            result.add("error", err.buildJsonElement(caps));
            // Surface any prints the macro emitted before failure so the agent
            // can see partial progress alongside the error message. Also kept
            // at top-level for legacy clients — sideEffects is structured-only.
            // Step 05: top-level logDelta rides through the StateDelta struct
            // so caps.stateDelta can group it alongside newImages/resultsTable.
            if (logDelta != null) {
                delta.logDelta = logDelta;
            }
        }

        if (dialogs == null) dialogs = safeDetectOpenDialogs();
        if (dialogs != null && dialogs.size() > 0) {
            result.add("dialogs", dialogs);
        }

        publishTcpMacroCompleted(macroId, success, elapsed, failureMessage);
        // Stage 04 (embedded-agent-widget): silently capture into the in-
        // process session journal so the user can re-run via the rail
        // panel (stage 11). Zero-token for the agent — nothing on the
        // wire, no event stream changes, no new response fields.
        try {
            String source = request.has("source") ? request.get("source").getAsString() : "tcp";
            SessionCodeJournal.INSTANCE.record("ijm", code, source,
                    macroId, startTime, elapsed, success, failureMessage);
        } catch (Throwable t) {
            IJ.log("[ImageJAI-Journal] record failed: " + t);
        }
        // Step 03: echo the IJ Recorder's canonical macro when it differs from
        // what the agent sent, or whenever the macro failed. Absent otherwise,
        // so the common case pays zero extra tokens.
        if (recorderCapture != null) {
            String ranCode = recorderCapture.getDelta();
            if (ranCode != null && !ranCode.isEmpty()
                    && (RecorderCapture.differs(code, ranCode) || !success)) {
                result.addProperty("ranCode", ranCode);
            }
        }
        // Step 06: run the macro analyser against the canonical code +
        // post-execution state. Uses codeToRun (post-fuzzy-correction) so the
        // regex sees the same command name ImageJ ran. Warnings surface as a
        // top-level array — NOT inside stateDelta — so agents can branch on
        // "run succeeded AND no actionable bug" without re-parsing the diff.
        // Per plan: docs/tcp_upgrade/06_nresults_trap.md.
        if (caps != null && caps.warnings) {
            int nResultsAfter = 0;
            try {
                ResultsTable rt = ResultsTable.getResultsTable();
                nResultsAfter = (rt == null) ? 0 : rt.getCounter();
            } catch (Throwable ignore) {}
            List<MacroAnalyser.Warning> warnings = MacroAnalyser.analyse(
                    codeToRun, new MacroAnalyser.PostExec(nResultsAfter));
            if (!warnings.isEmpty()) {
                JsonArray warr = new JsonArray();
                for (MacroAnalyser.Warning w : warnings) warr.add(w.toJson());
                result.add("warnings", warr);
            }
        }
        // Step 05: serialise the collected diffs now — either grouped under
        // "stateDelta" or as legacy flat keys, depending on caps. Must run
        // before the pulse/return so the reply order stays stable.
        delta.applyTo(result, caps);
        // Step 09: snapshot the post-macro histogram and attach the delta as
        // a top-level "histogramDelta" — NOT nested under stateDelta — so an
        // agent can branch on pixel changes separately from structural state
        // changes. Fires on every pixel-mutating macro regardless of success
        // so even a dialog-paused failure produces a useful diff for the work
        // that landed before the pause. Per plan:
        // docs/tcp_upgrade/09_histogram_delta.md.
        if (caps != null && caps.histogram) {
            HistogramDelta.Snapshot histAfter = null;
            try {
                histAfter = HistogramDelta.snapshot(WindowManager.getCurrentImage());
            } catch (Throwable ignore) {}
            JsonObject histJson = HistogramDelta.compute(histBefore, histAfter);
            if (histJson != null) {
                result.add("histogramDelta", histJson);
            }
        }
        // Step 10: post-macro phantom-dialog check. Reporting is always on —
        // gating only kicks in for the auto-dismiss action via
        // resolveAutoDismissPhantoms. A macro that already tripped the
        // blocking-dialog auto-dismiss branch above usually has nothing left
        // to report here (dialog already cleared), which is correct: the
        // phantomDialog key surfaces only silent modals the handler did not
        // already notice. Per plan: docs/tcp_upgrade/10_phantom_dialog_detector.md.
        try {
            PhantomDialogDetector.detect(modalBefore, phantomAutoDismiss)
                    .ifPresent(new java.util.function.Consumer<JsonObject>() {
                        @Override
                        public void accept(JsonObject phantom) {
                            result.add("phantomDialog", phantom);
                        }
                    });
        } catch (Throwable ignore) {}
        // Step 13: diff the post-macro open-title set against the pre-macro
        // snapshot and track the resulting nodes/edges in the shared
        // provenance graph. Attach the produced subgraph as a top-level
        // graphDelta when caps.graphDelta is on. Runs on both success and
        // failure paths so partial work (e.g. a plugin that ran to
        // completion before a dialog-pause) still lands in the graph.
        try {
            Set<String> graphTitlesAfter = ImageGraph.captureOpenTitles();
            imageGraph.trackMacroChange(graphTitlesBefore, graphActiveTitleBefore,
                    graphTitlesAfter, code, "macro");
            if (caps != null && caps.graphDelta) {
                ImageGraph.Delta gDelta = imageGraph.deltaSince(graphMarkerBefore);
                if (!gDelta.isEmpty()) {
                    result.add("graphDelta", gDelta.toJson());
                }
            }
        } catch (Throwable ignore) {}
        if (caps != null && caps.pulse) {
            result.addProperty("pulse", PulseBuilder.build());
        }
        // Stage 03: settle the rescue handle on the normal return path.
        // Successful macro → drop the snapshot so the undo ring stays empty
        // for the no-undo-caps default. Failed macro → push it onto the
        // active branch so a follow-up {@code rewind to_call_id} can roll
        // back the damage (see docs/safe_mode_v2/03_auto-snapshot-rescue.md).
        // Stage 07: helpers also publish safe_mode.snapshot.{released,committed}.
        if (rescue != null) {
            if (success) {
                releaseRescueAndPublish(rescue);
            } else {
                commitRescueAndPublish(rescue);
            }
        }
        return successResponse(result);
        } finally {
            if (recorderCapture != null) {
                try { recorderCapture.close(); } catch (Throwable ignore) {}
            }
            eventBus.popSuppress("image.*");
            // Stage 03 safety net: any path that bypassed the explicit
            // commit/release above (InterruptedException early-return or an
            // unexpected Throwable propagating out of the synchronized
            // block) lands here with an unsettled handle. Commit
            // conservatively — a thrown macro likely mutated pixels, so
            // preserving the rescue is the safer default than silently
            // dropping recovery state.
            if (rescue != null
                    && !rescue.isCommitted()
                    && !rescue.isReleased()) {
                commitRescueAndPublish(rescue);
            }
            // Stage 06 safety net: when the synchronized block threw before
            // the inline postExec call could land, run it here so any rows
            // the macro did manage to push still get the Source_Image
            // stamp. {@link SourceImageTagger#postExec} is idempotent — the
            // normal-path call sets a done flag so this is a no-op when
            // already invoked above.
            if (sourceImageTagger != null) {
                try { sourceImageTagger.postExec(WindowManager.getCurrentImage()); }
                catch (Throwable ignore) {}
            }
        }
    }

    /**
     * Stage 04 (docs/safe_mode_v2/04_queue-storm-per-image.md): true when
     * the queue-storm guard should fire for the given caps — i.e. master
     * safe-mode is on AND the per-guard option is on. {@link #DEFAULT_CAPS}
     * has {@code safeMode=false}, so unhandshaked sockets always fall
     * through to the legacy unguarded path.
     */
    private static boolean isQueueStormGuardEnabled(AgentCaps caps) {
        return caps != null
                && caps.safeMode
                && caps.safeModeOptions != null
                && caps.safeModeOptions.queueStormGuard;
    }

    /**
     * Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md): true
     * when the scientific-integrity scanner should fire for the given caps
     * — master safe-mode AND the per-guard option both on. Any of the
     * seven Stage-05 rules can still be tightened or relaxed individually
     * via the bit-depth / normalize opt-ins on {@link SafeModeOptions}.
     */
    private static boolean isScientificIntegrityScanEnabled(AgentCaps caps) {
        return caps != null
                && caps.safeMode
                && caps.safeModeOptions != null
                && caps.safeModeOptions.scientificIntegrityScan;
    }

    /**
     * Stage 06 (docs/safe_mode_v2/06_results-source-image-column.md): true
     * when the auto-{@code Source_Image}-column tagger should run for the
     * given caps — master safe-mode AND the per-guard option both on. The
     * tagger writes the {@code Source_Image} column on every Results-table
     * row added during the call so cross-image aggregation stays
     * unambiguous. Per-call escape hatch lives on the macro source itself
     * ({@code // @safe_mode allow: legacy_results_format}) and is checked
     * by the wiring site, not here.
     */
    private static boolean isSourceImageColumnEnabled(AgentCaps caps) {
        return caps != null
                && caps.safeMode
                && caps.safeModeOptions != null
                && caps.safeModeOptions.autoSourceImageColumn;
    }

    /**
     * Stage 05: take a live snapshot of the active image / RoiManager /
     * Results state into a {@link DestructiveScanner.Context} the scanner
     * consults. Every probe is wrapped in a try/catch so a flaky Fiji
     * call (no active image, RoiManager not initialised in headless
     * tests, …) degrades to a permissive default instead of breaking the
     * macro path.
     */
    private DestructiveScanner.Context captureScannerContext(AgentCaps caps) {
        String activeImagePath = null;
        String aiExportsRoot = null;
        int currentBitDepth = 0;
        boolean calibrationActive = false;
        int roiManagerCount = 0;
        int resultsRowCount = 0;

        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp != null) {
                try { currentBitDepth = imp.getBitDepth(); } catch (Throwable ignore) {}
                try {
                    ij.io.FileInfo fi = imp.getOriginalFileInfo();
                    if (fi != null && fi.directory != null && fi.fileName != null) {
                        activeImagePath = fi.directory + fi.fileName;
                        aiExportsRoot = fi.directory.endsWith("/") || fi.directory.endsWith("\\")
                                ? fi.directory + "AI_Exports"
                                : fi.directory + java.io.File.separator + "AI_Exports";
                    }
                } catch (Throwable ignore) {}
                try {
                    Calibration cal = imp.getCalibration();
                    if (cal != null) {
                        boolean nonUnitWidth = Math.abs(cal.pixelWidth - 1.0) > 1e-9;
                        String unit = cal.getUnit();
                        boolean physicalUnit = unit != null
                                && !unit.isEmpty()
                                && !"pixel".equalsIgnoreCase(unit)
                                && !"pixels".equalsIgnoreCase(unit);
                        calibrationActive = nonUnitWidth || physicalUnit;
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}

        try {
            RoiManager rm = RoiManager.getInstance();
            if (rm != null) roiManagerCount = rm.getCount();
        } catch (Throwable ignore) {}

        try {
            ResultsTable rt = ResultsTable.getResultsTable();
            if (rt != null) resultsRowCount = rt.getCounter();
        } catch (Throwable ignore) {}

        boolean optBitDepth = caps != null && caps.safeModeOptions != null
                && caps.safeModeOptions.blockBitDepthNarrowing;
        boolean optNormalize = caps != null && caps.safeModeOptions != null
                && caps.safeModeOptions.blockNormalizeContrast;

        return new DestructiveScanner.Context(
                activeImagePath, aiExportsRoot,
                currentBitDepth, calibrationActive,
                roiManagerCount, resultsRowCount,
                optBitDepth, optNormalize,
                new DestructiveScanner.FileExistsCheck() {
                    @Override
                    public boolean exists(String path) {
                        if (path == null || path.isEmpty()) return false;
                        try { return java.nio.file.Files.exists(java.nio.file.Paths.get(path)); }
                        catch (Throwable t) { return false; }
                    }
                });
    }

    /**
     * Stage 05: process a {@link DestructiveScanner.Severity#BACKUP_THEN_ALLOW}
     * finding — currently always the ROI-wipe rule. Writes the live
     * RoiManager to {@code AI_Exports/.safemode_roi_<ts>.zip} via
     * {@link RoiAutoBackup} and journals the outcome to the friction log
     * so the Stage 07 status indicator and any post-mortem inspection
     * can see the backup happened. Never blocks; the caller is expected
     * to let the macro proceed regardless.
     */
    private void runRoiAutoBackup(DestructiveScanner.DestructiveOp op,
                                  AgentCaps caps) {
        try {
            RoiManager rm = RoiManager.getInstance();
            ImagePlus imp = WindowManager.getCurrentImage();
            RoiAutoBackup.Result res = RoiAutoBackup.backup(rm, imp);
            String agentId = caps != null && caps.agentId != null ? caps.agentId : "";
            String backupTarget = res.path != null
                    ? res.path.toString()
                    : "(no backup written)";
            String summary = "rule=" + op.ruleId + " line=" + op.line
                    + " target=" + backupTarget;
            frictionLog.record(agentId, "execute_macro", summary, res.message);
            try { IJ.log("[ImageJAI-SafeMode] " + res.message); } catch (Throwable ignore) {}

            // Stage 07: notify the indicator. ROI auto-backup is non-blocking
            // (the macro proceeds) but we still want the dot to flick amber
            // so the biologist sees that we touched the ROI manager state.
            int roiCount = -1;
            try { if (rm != null) roiCount = rm.getCount(); } catch (Throwable ignore) {}
            JsonObject ev = new JsonObject();
            ev.addProperty("rule_id", op.ruleId);
            ev.addProperty("backup_path", backupTarget);
            if (roiCount >= 0) ev.addProperty("roi_count", roiCount);
            publishSafeModeEvent("safe_mode.roi_auto_backup", ev);
        } catch (Throwable t) {
            try { IJ.log("[ImageJAI-SafeMode] ROI auto-backup failed: " + t.getMessage()); }
            catch (Throwable ignore) {}
        }
    }

    /**
     * Stage 05: build the {@code DESTRUCTIVE_OP_BLOCKED} structured-error
     * reply. Mirrors {@link #queueStormBlockedReply} — same outer envelope,
     * same plain-string fallback for clients without {@code structured_errors},
     * but the inner shape carries an {@code operations[]} array with one
     * row per finding so the agent can fix exactly what tripped the gate.
     */
    private JsonObject destructiveBlockedReply(
            List<DestructiveScanner.DestructiveOp> rejects,
            AgentCaps caps) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);

        StringBuilder plain = new StringBuilder("Macro blocked by safe-mode scanner: ");
        JsonArray opsArr = new JsonArray();
        for (int i = 0; i < rejects.size(); i++) {
            DestructiveScanner.DestructiveOp op = rejects.get(i);
            if (i > 0) plain.append("; ");
            plain.append(op.ruleId).append(" @ line ").append(op.line);
            JsonObject row = new JsonObject();
            row.addProperty("rule_id", op.ruleId);
            row.addProperty("severity", "reject");
            row.addProperty("target", op.target);
            row.addProperty("line", op.line);
            row.addProperty("message", op.message);
            opsArr.add(row);
        }
        String message = plain.toString();
        String hint = "Fix the offending lines, or — if the operation is intentional"
                + " — disable the master safe-mode toggle for this run.";

        if (caps != null && caps.structuredErrors) {
            JsonObject err = new JsonObject();
            err.addProperty("code", ErrorReply.CODE_DESTRUCTIVE_OP_BLOCKED);
            err.addProperty("category", ErrorReply.CAT_BLOCKED);
            err.addProperty("retry_safe", false);
            err.addProperty("message", message);
            err.addProperty("recovery_hint", hint);
            err.add("operations", opsArr);
            result.add("error", err);
        } else {
            result.addProperty("error", message);
        }

        // Journal each rejection so the Stage 07 status indicator and
        // post-mortem inspection can see what the scanner caught.
        try {
            String agentId = caps != null && caps.agentId != null ? caps.agentId : "";
            for (DestructiveScanner.DestructiveOp op : rejects) {
                frictionLog.record(agentId, "execute_macro",
                        "rule=" + op.ruleId + " target=" + op.target + " line=" + op.line,
                        op.message);
            }
        } catch (Throwable ignore) {}

        // Stage 07: push the first rejection onto EventBus so the indicator
        // can flip red. We carry the calibration_loss rule id verbatim so a
        // future indicator-side filter can colour it amber rather than red
        // if the user opts in (currently every reject paints the dot red).
        DestructiveScanner.DestructiveOp head = rejects.get(0);
        JsonObject ev = new JsonObject();
        ev.addProperty("rule_id", head.ruleId);
        ev.addProperty("target", head.target);
        ev.addProperty("line", head.line);
        ev.addProperty("count", rejects.size());
        publishSafeModeEvent("safe_mode.blocked", ev);

        return successResponse(result);
    }

    /**
     * Stage 04: parse the last {@code selectImage("...")} or
     * {@code selectWindow("...")} call in the macro source to identify the
     * agent's target image. Returns {@code null} when no such call is
     * present (the caller falls back to the active window).
     *
     * <p>String-arg form only — {@code selectImage(id)} uses an ImageJ
     * window-id integer which the server cannot map to a title without
     * touching {@link WindowManager}. Per the plan §Known risks, this is
     * heuristic — the guard fires on the agent's intended target as best
     * the static source can reveal.
     */
    static String resolveTargetImageTitle(String code) {
        if (code == null || code.isEmpty()) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "select(?:Image|Window)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(code);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    /**
     * Stage 04: like {@link #resolveTargetImageTitle(String)} but falls
     * back to the live active window's title when the macro source has
     * no {@code selectImage} hint. Returns {@code null} when neither path
     * yields a title — in test contexts and headless calls Fiji's
     * {@link WindowManager} may NPE, which we swallow.
     */
    String resolveTargetImageTitleWithFallback(String code) {
        String parsed = resolveTargetImageTitle(code);
        if (parsed != null) return parsed;
        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp != null) {
                String t = imp.getTitle();
                if (t != null && !t.isEmpty()) return t;
            }
        } catch (Throwable ignore) {
            // No live Fiji — fall through and return null.
        }
        return null;
    }

    /**
     * Stage 04: title of the first non-error modal dialog in the dialogs
     * snapshot, or {@code null}. Used to populate
     * {@link ActiveMacro#dialogTitle} so the QUEUE_STORM_BLOCKED reply
     * can name the dialog the previous macro is parked on.
     */
    private static String firstBlockingDialogTitle(JsonArray dialogs) {
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
                JsonElement tEl = d.get("title");
                if (tEl != null && tEl.isJsonPrimitive()) {
                    String t = tEl.getAsString();
                    if (t != null && !t.isEmpty()) return t;
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /**
     * Stage 04: build the {@code QUEUE_STORM_BLOCKED} response. Carries
     * the blocking macro's id, the dialog title it is parked on, and the
     * target image title in addition to the standard structured-error
     * fields. For legacy clients that did not opt into structured errors
     * the reply degrades to a plain string in the {@code error} field.
     * Per plan: docs/safe_mode_v2/04_queue-storm-per-image.md.
     */
    private JsonObject queueStormBlockedReply(ActiveMacro inflight,
                                              String target,
                                              AgentCaps caps) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);

        String dialogTitle = (inflight.dialogTitle != null && !inflight.dialogTitle.isEmpty())
                ? inflight.dialogTitle : "(unknown)";
        String message = "Macro #" + inflight.macroId
                + " is paused on a '" + dialogTitle
                + "' dialog targeting '" + target
                + "'. Refusing to queue another macro on the same image.";
        String hint = "Either dismiss the dialog (interact_dialog), wait for macro #"
                + inflight.macroId
                + " to finish, or run on a different image.";

        if (caps != null && caps.structuredErrors) {
            JsonObject err = new JsonObject();
            err.addProperty("code", ErrorReply.CODE_QUEUE_STORM_BLOCKED);
            err.addProperty("category", ErrorReply.CAT_BLOCKED);
            err.addProperty("retry_safe", false);
            err.addProperty("message", message);
            err.addProperty("recovery_hint", hint);
            err.addProperty("blocking_macro_id", inflight.macroId);
            err.addProperty("blocking_dialog_title", dialogTitle);
            err.addProperty("target_image", target);
            result.add("error", err);
        } else {
            result.addProperty("error", message);
        }

        // Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): also push an
        // EventBus frame so the toolbar / status-bar indicator turns red on
        // the next paint. Indicator state is the same FrictionLog row above
        // viewed through a different lens, so a publish failure must never
        // change the reply.
        JsonObject ev = new JsonObject();
        ev.addProperty("blocking_macro_id", inflight.macroId);
        ev.addProperty("blocking_dialog_title", dialogTitle);
        ev.addProperty("target_image", target);
        publishSafeModeEvent("safe_mode.queue_storm_blocked", ev);

        return successResponse(result);
    }

    /**
     * Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): one helper to
     * publish a {@code safe_mode.*} event onto {@link EventBus}. Call sites:
     * every safe-mode reject reply ({@link #destructiveBlockedReply},
     * {@link #queueStormBlockedReply}), the auto-backup write
     * ({@link #runRoiAutoBackup}), and the rescue-handle settle points where
     * a macro's pre-run snapshot is committed or released. The toolbar /
     * status-bar indicator subscribes to {@code safe_mode.*} and flips
     * colour from this single channel.
     *
     * <p>EventBus has a 200 ms per-topic coalesce window. The bursts we
     * publish here are one-per-reply, so coalesce will only fire if two
     * different rejects collide on the same topic in that window — in which
     * case the first one is enough to colour the indicator red.
     *
     * <p>Wrapped in try/catch so a faulty subscriber on the bus can never
     * abort the underlying TCP reply or the rescue-handle settle.
     */
    private void publishSafeModeEvent(String topic, JsonObject data) {
        if (topic == null || topic.isEmpty()) return;
        try {
            eventBus.publish(topic, data == null ? new JsonObject() : data);
        } catch (Throwable ignore) {
            // Indicator UX must not block the macro path.
        }
    }

    /**
     * Stage 07: drop the rescue snapshot AND publish
     * {@code safe_mode.snapshot.released} so the indicator paints green again.
     * No-op when the handle is null (caps.safeMode off, no rescue captured).
     */
    private void releaseRescueAndPublish(SessionUndo.RescueHandle rescue) {
        if (rescue == null) return;
        try { rescue.release(); } catch (Throwable ignore) {}
        publishSafeModeEvent("safe_mode.snapshot.released", null);
    }

    /**
     * Stage 07: keep the rescue snapshot AND publish
     * {@code safe_mode.snapshot.committed} so the indicator paints red — a
     * committed rescue means the macro failed and the user can rewind.
     */
    private void commitRescueAndPublish(SessionUndo.RescueHandle rescue) {
        if (rescue == null) return;
        try { rescue.commit(); } catch (Throwable ignore) {}
        publishSafeModeEvent("safe_mode.snapshot.committed", null);
    }

    /**
     * Stop a running IJ.runMacro future for real. {@code Future.cancel(true)}
     * by itself only interrupts the worker thread, and the ImageJ macro
     * interpreter silently swallows {@link Thread#interrupted()} — the macro
     * keeps stepping through the remaining statements against global
     * WindowManager state while the TCP handler returns an error to the
     * client. That zombie is what lets a later macro's threshold/mask steps
     * land on the wrong image.
     *
     * Order of operations, mirroring {@code JobRegistry.cancel}: reflectively
     * invoke {@code ij.Macro.abort()} (the interpreter's cooperative stop
     * signal), then cancel the Future to interrupt the worker, then poll for
     * up to {@link #MACRO_ABORT_WAIT_MS} so the MACRO_MUTEX is not released
     * until the zombie is actually gone.
     *
     * Returns {@code true} when the worker terminates within the window,
     * {@code false} when it is still alive (genuinely unkillable). Callers
     * currently ignore the boolean but it is logged so future diagnostics can
     * see unkillable-macro incidents.
     */
    private static boolean abortMacroFuture(Future<?> future) {
        if (future == null) return true;
        try {
            Class<?> macroClass = Class.forName("ij.Macro");
            java.lang.reflect.Method abort = macroClass.getMethod("abort");
            abort.invoke(null);
        } catch (Throwable ignore) {
            // ij.Macro absent or signature changed — fall through to interrupt.
        }
        future.cancel(true);
        long deadline = System.currentTimeMillis() + MACRO_ABORT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (future.isDone()) return true;
            try {
                Thread.sleep(25);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return future.isDone();
            }
        }
        boolean dead = future.isDone();
        if (!dead) {
            System.err.println("[ImageJAI-TCP] WARNING: macro did not terminate within "
                    + MACRO_ABORT_WAIT_MS + "ms of IJ.Macro.abort() — proceeding with zombie interpreter");
        }
        return dead;
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

    private JsonObject handleRunScript(JsonObject request, AgentCaps caps) {
        JsonElement langElement = request.get("language");
        String language = langElement != null && langElement.isJsonPrimitive()
                ? langElement.getAsString()
                : "groovy";

        JsonElement codeElement = request.get("code");
        if (codeElement == null || !codeElement.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for run_script");
        }
        final String code = codeElement.getAsString();
        final long scriptTimeoutMs = resolveTimeoutMs(request, MACRO_TIMEOUT_MS);

        // Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md):
        // run the same scientific-integrity scanner the macro path uses.
        // Scripts are equally capable of writing saveAs / roiManager calls
        // so the gate has to live here too — otherwise an agent routes
        // around the macro guard via {@code run_script}.
        if (isScientificIntegrityScanEnabled(caps)) {
            DestructiveScanner.Context scanCtx = captureScannerContext(caps);
            List<DestructiveScanner.DestructiveOp> findings =
                    DestructiveScanner.scan(code, scanCtx);
            if (!findings.isEmpty()) {
                List<DestructiveScanner.DestructiveOp> rejects =
                        DestructiveScanner.rejections(findings);
                if (!rejects.isEmpty()) {
                    return destructiveBlockedReply(rejects, caps);
                }
                for (DestructiveScanner.DestructiveOp op : DestructiveScanner.backups(findings)) {
                    if (DestructiveScanner.RULE_ROI_WIPE.equals(op.ruleId)
                            && caps.safeModeOptions != null
                            && caps.safeModeOptions.autoBackupRoiOnReset) {
                        runRoiAutoBackup(op, caps);
                    }
                }
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("language", language);

        ScriptEngineManager manager = new ScriptEngineManager();
        final ScriptEngine engine = manager.getEngineByName(language);

        if (engine == null) {
            return errorResponse("ScriptEngine not found for language: " + language
                    + ". Available: groovy, jython, javascript");
        }

        // Step 09: histogram snapshot before the script runs. Same contract
        // as handleExecuteMacro — on-by-default, skipped for huge images,
        // dropped entirely when no active image exists.
        HistogramDelta.Snapshot histBefore = null;
        if (caps != null && caps.histogram) {
            try {
                histBefore = HistogramDelta.snapshot(WindowManager.getCurrentImage());
            } catch (Throwable ignore) {}
        }

        // Step 10: phantom-dialog baseline snapshot for run_script. Same
        // before/after contract as handleExecuteMacro so scripts that open a
        // silent GenericDialog are surfaced the same way macros are.
        final Set<Window> modalBefore = PhantomDialogDetector.currentModalWindows();
        final boolean phantomAutoDismiss = resolveAutoDismissPhantoms(request, caps);

        // Step 13: provenance-graph baseline for run_script. Same contract as
        // handleExecuteMacro — scripts that create images get the derived
        // node and edge in the same shape. Per plan:
        // docs/tcp_upgrade/13_provenance_graph.md.
        final Set<String> graphTitlesBefore = ImageGraph.captureOpenTitles();
        final String graphActiveTitleBefore = ImageGraph.captureActiveTitle();
        final long graphMarkerBefore = imageGraph.currentMarker();

        // Step 15: scripts are uninvertible side-effects. Plan §Out-of-scope
        // marks them a "branch boundary" — push a sentinel onto the active
        // image's undo stack so a later rewind cannot walk past this point.
        // Best-effort: capture failures must not block the script.
        if (caps != null && caps.undo) {
            try {
                ImagePlus boundaryImp = WindowManager.getCurrentImage();
                if (boundaryImp != null) {
                    sessionUndo.pushBoundary(boundaryImp.getTitle(), nextCallId());
                }
            } catch (Throwable ignore) {}
        }

        // Stage 03 (docs/safe_mode_v2/03_auto-snapshot-rescue.md): pre-script
        // snapshot for safe-mode callers. Released on a clean script run;
        // committed on any failure so the user can rewind even though
        // run_script does not normally feed the undo stack (caps.undo gates
        // the pushBoundary above; the rescue path runs when caps.undo is OFF
        // and only the safe-mode master switch is on).
        final String scriptCallId = nextCallId();
        final SessionUndo.RescueHandle rescue = captureRescueFrameIfEnabled(
                scriptCallId, code, caps);

        // Mirror handleExecuteMacro: run the script on a single-thread executor
        // and poll for blocking dialogs every 150 ms. Without this, a Groovy
        // hallucination like IJ.run("setAutoThreshold", ...) opens a command
        // dialog and pins Fiji until the client socket times out — leaving the
        // dialog on screen to block every subsequent call.
        long startTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Object> future = null;
        Object scriptResult = null;
        Throwable scriptError = null;
        String blockingFailure = null;
        JsonArray dismissedCaptured = new JsonArray();
        boolean completed = false;

        // Step 15: announce in-flight so a concurrent rewind returns
        // UNDO_BUSY rather than racing the script's pixel mutations.
        // Decrement happens in the matching finally so an exception
        // unwinds the counter cleanly. Plan §Failure modes.
        macroInFlight.incrementAndGet();
        try {
        // Step 15: serialise behind MACRO_MUTEX too — same global
        // Interpreter / WindowManager state that handleExecuteMacro guards
        // against, plus mutual exclusion with the rewind handler's
        // synchronized(MACRO_MUTEX) restoration block.
        synchronized (MACRO_MUTEX) {
        try {
            future = executor.submit(new java.util.concurrent.Callable<Object>() {
                @Override
                public Object call() throws ScriptException {
                    return engine.eval(code);
                }
            });

            while (true) {
                try {
                    scriptResult = future.get(150, TimeUnit.MILLISECONDS);
                    completed = true;
                    break;
                } catch (TimeoutException te) {
                    JsonArray dialogs = safeDetectOpenDialogs();
                    String blocking = detectBlockingDialog(dialogs);
                    if (blocking != null) {
                        future.cancel(true);
                        int dismissed = dismissOpenDialogsCapturing(null, dismissedCaptured);
                        blockingFailure = dismissed > 0
                                ? blocking + " — the dialog has been auto-dismissed by the server."
                                : blocking;
                        break;
                    }
                    if (!timeoutDisabled(scriptTimeoutMs)
                            && (System.currentTimeMillis() - startTime) > scriptTimeoutMs) {
                        future.cancel(true);
                        blockingFailure = "Script execution timed out after " + scriptTimeoutMs + "ms";
                        break;
                    }
                } catch (ExecutionException ee) {
                    scriptError = ee.getCause() != null ? ee.getCause() : ee;
                    break;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (future != null && !future.isDone()) future.cancel(true);
            executor.shutdownNow();
            // Stage 03: thread-interrupt is a forced abort — preserve the
            // pre-script snapshot so the user can rewind whatever the
            // script managed to do before we yanked it.
            // Stage 07: helper also publishes safe_mode.snapshot.committed.
            commitRescueAndPublish(rescue);
            return errorResponse("Interrupted");
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            executor.shutdownNow();
        }
        } // end synchronized (MACRO_MUTEX)
        } finally {
            // Step 15: counter must drop even if MACRO_MUTEX block threw.
            macroInFlight.decrementAndGet();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        result.addProperty("executionTimeMs", elapsed);

        // Step 05: collect diff fields into a struct so the final serialise
        // step can nest them under "stateDelta" or keep the legacy flat keys.
        final StateDelta delta = new StateDelta();
        if (completed) {
            result.addProperty("success", true);
            result.addProperty("output", scriptResult != null ? scriptResult.toString() : "");
        } else if (blockingFailure != null) {
            result.addProperty("success", false);
            // Script blocked on a dialog: classify the same way execute_macro does.
            result.add("error",
                    ErrorReply.classifyMacroError(blockingFailure, false).buildJsonElement(caps));
            if (dismissedCaptured.size() > 0) {
                delta.dismissedDialogs = dismissedCaptured;
            }
        } else if (scriptError instanceof ScriptException) {
            // ScriptException is the parser/compiler failure path — surface it
            // as a compile error so the agent knows a retry of the same source
            // cannot succeed.
            result.addProperty("success", false);
            String msg = "Script error: " + scriptError.getMessage();
            result.add("error", new ErrorReply()
                    .code(ErrorReply.CODE_MACRO_COMPILE_ERROR)
                    .category(ErrorReply.CAT_COMPILE)
                    .retrySafe(false)
                    .message(msg)
                    .recoveryHint("Fix the script syntax before retrying.")
                    .buildJsonElement(caps));
        } else if (scriptError != null) {
            String msg = scriptError.getMessage();
            result.addProperty("success", false);
            String text = "Error: " + (msg != null ? msg : scriptError.toString());
            result.add("error",
                    ErrorReply.classifyMacroError(text, false).buildJsonElement(caps));
        } else {
            result.addProperty("success", false);
            result.add("error", new ErrorReply()
                    .code(ErrorReply.CODE_MACRO_RUNTIME_ERROR)
                    .category(ErrorReply.CAT_RUNTIME)
                    .retrySafe(false)
                    .message("Script returned without a result")
                    .buildJsonElement(caps));
        }

        try {
            JsonArray dialogs = detectOpenDialogs();
            if (dialogs.size() > 0) {
                result.add("dialogs", dialogs);
            }
        } catch (Exception ignore) {
        }

        // Stage 04 (embedded-agent-widget): silent capture into the session
        // journal, same as handleExecuteMacro. Scripts get the real language
        // ("groovy" / "jython" / …) so the journal file extension is accurate.
        try {
            String source = request.has("source") ? request.get("source").getAsString() : "tcp";
            boolean scriptSuccess = completed && blockingFailure == null && scriptError == null;
            String scriptFailure = blockingFailure != null
                    ? blockingFailure
                    : (scriptError != null ? String.valueOf(scriptError.getMessage()) : null);
            SessionCodeJournal.INSTANCE.record(language, code, source,
                    0L, startTime, elapsed, scriptSuccess, scriptFailure);
        } catch (Throwable t) {
            IJ.log("[ImageJAI-Journal] record failed: " + t);
        }

        // Step 05: attach collected diffs and pulse. Same shape contract as
        // handleExecuteMacro so clients can treat execute_macro and run_script
        // replies interchangeably.
        delta.applyTo(result, caps);
        // Step 09: same histogramDelta contract as handleExecuteMacro.
        if (caps != null && caps.histogram) {
            HistogramDelta.Snapshot histAfter = null;
            try {
                histAfter = HistogramDelta.snapshot(WindowManager.getCurrentImage());
            } catch (Throwable ignore) {}
            JsonObject histJson = HistogramDelta.compute(histBefore, histAfter);
            if (histJson != null) {
                result.add("histogramDelta", histJson);
            }
        }
        // Step 10: post-script phantom-dialog check, same contract as
        // handleExecuteMacro so run_script's reply shape stays interchangeable.
        try {
            PhantomDialogDetector.detect(modalBefore, phantomAutoDismiss)
                    .ifPresent(new java.util.function.Consumer<JsonObject>() {
                        @Override
                        public void accept(JsonObject phantom) {
                            result.add("phantomDialog", phantom);
                        }
                    });
        } catch (Throwable ignore) {}
        // Step 13: same graphDelta contract as handleExecuteMacro. The
        // origin tag is "script" so the agent can tell at a glance whether
        // a derived node came from a macro run or a Groovy/Jython script.
        try {
            Set<String> graphTitlesAfter = ImageGraph.captureOpenTitles();
            imageGraph.trackMacroChange(graphTitlesBefore, graphActiveTitleBefore,
                    graphTitlesAfter, code, "script");
            if (caps != null && caps.graphDelta) {
                ImageGraph.Delta gDelta = imageGraph.deltaSince(graphMarkerBefore);
                if (!gDelta.isEmpty()) {
                    result.add("graphDelta", gDelta.toJson());
                }
            }
        } catch (Throwable ignore) {}
        if (caps != null && caps.pulse) {
            result.addProperty("pulse", PulseBuilder.build());
        }
        // Stage 03: settle the rescue handle. Successful run drops the
        // snapshot; any failure path commits so a follow-up rewind can roll
        // the image back. Re-uses the same overall-success calculation as
        // the journal record above so script-level success and rescue
        // behaviour stay aligned.
        // Stage 07: helpers also publish safe_mode.snapshot.{released,committed}.
        if (rescue != null) {
            boolean overallOk = completed
                    && blockingFailure == null
                    && scriptError == null;
            if (overallOk) {
                releaseRescueAndPublish(rescue);
            } else {
                commitRescueAndPublish(rescue);
            }
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

    private JsonObject handleRunPipeline(JsonObject request, AgentCaps caps) {
        JsonElement stepsElement = request.get("steps");
        if (stepsElement == null || !stepsElement.isJsonArray()) {
            return errorResponse("Missing 'steps' array for run_pipeline");
        }

        JsonArray stepsArray = stepsElement.getAsJsonArray();
        final List<PipelineBuilder.PipelineStep> steps = new ArrayList<PipelineBuilder.PipelineStep>();
        // Step 04: mirror handleExecuteMacro's pre-validation for each step's
        // macro code. Any rejection short-circuits the whole pipeline; a
        // per-step correction patches the step's code before it reaches
        // PipelineBuilder. The first rejection drives the error reply so a
        // single bad run("name") in step 3 doesn't silently run steps 1-2.
        List<PluginNameValidator.Correction> allCorrections =
                new ArrayList<PluginNameValidator.Correction>();
        for (int i = 0; i < stepsArray.size(); i++) {
            JsonObject stepObj = stepsArray.get(i).getAsJsonObject();
            String desc = stepObj.has("description") ? stepObj.get("description").getAsString() : "Step " + (i + 1);
            String code = stepObj.has("code") ? stepObj.get("code").getAsString() : "";
            if (caps != null && caps.fuzzyMatch) {
                PluginNameValidator.Result v = PluginNameValidator.validate(code);
                if (v.hasRejections()) {
                    JsonObject rej = new JsonObject();
                    rej.addProperty("success", false);
                    ErrorReply err = PluginNameValidator.buildPluginNotFoundError(v.rejections);
                    rej.add("error", err.buildJsonElement(caps));
                    rej.addProperty("failedStep", i + 1);
                    return successResponse(rej);
                }
                if (v.hasCorrections()) {
                    code = v.patchedCode;
                    allCorrections.addAll(v.corrections);
                }
            }
            steps.add(new PipelineBuilder.PipelineStep(i + 1, desc, code));
        }

        if (steps.isEmpty()) {
            return errorResponse("Empty steps array");
        }

        PipelineBuilder.Pipeline pipeline = new PipelineBuilder.Pipeline("TCP Pipeline", steps);

        // Step 10: phantom-dialog baseline for the pipeline as a whole. One
        // phantomDialog report covers the whole run — not per step — because
        // a typical pipeline failure spirals from a single silent modal and
        // per-step reports would multiply the same signal by N.
        final Set<Window> modalBefore = PhantomDialogDetector.currentModalWindows();
        final boolean phantomAutoDismiss = resolveAutoDismissPhantoms(request, caps);

        // Step 13: provenance-graph baseline for the pipeline. The macro
        // stored on derived nodes is the concatenated step code so the
        // graph carries enough provenance to rerun the full chain. Per
        // plan: docs/tcp_upgrade/13_provenance_graph.md.
        final Set<String> graphTitlesBefore = ImageGraph.captureOpenTitles();
        final String graphActiveTitleBefore = ImageGraph.captureActiveTitle();
        final long graphMarkerBefore = imageGraph.currentMarker();
        final StringBuilder pipelineMacro = new StringBuilder();
        for (PipelineBuilder.PipelineStep s : steps) {
            if (pipelineMacro.length() > 0) pipelineMacro.append('\n');
            if (s.macroCode != null) pipelineMacro.append(s.macroCode);
        }

        // Step 15: pipelines are macro chains; treat them as a script-level
        // boundary so a later rewind cannot undo only some of the steps.
        // Plan §Out-of-scope on script-runs applies here by extension.
        if (caps != null && caps.undo) {
            try {
                ImagePlus boundaryImp = WindowManager.getCurrentImage();
                if (boundaryImp != null) {
                    sessionUndo.pushBoundary(boundaryImp.getTitle(), nextCallId());
                }
            } catch (Throwable ignore) {}
        }

        // Stage 03 (docs/safe_mode_v2/03_auto-snapshot-rescue.md): one
        // pre-pipeline rescue snapshot for safe-mode callers without
        // caps.undo. Pipelines run as a single mutex-held block from the
        // user's perspective — restoring after a half-complete chain is the
        // realistic recovery story, so one frame at the start (not per step)
        // matches what the user can act on. Committed on any failure so a
        // {@code rewind} undoes the whole chain back to the entry state.
        final String pipelineCallId = nextCallId();
        final SessionUndo.RescueHandle rescue = captureRescueFrameIfEnabled(
                pipelineCallId, pipelineMacro.toString(), caps);

        // executePipeline calls commandEngine.executeMacro() which handles EDT
        // dispatch internally, so call directly from TCP handler thread.
        // Step 15: announce in-flight + serialise behind MACRO_MUTEX so a
        // concurrent rewind sees UNDO_BUSY rather than racing the chain.
        macroInFlight.incrementAndGet();
        try {
        synchronized (MACRO_MUTEX) {
            try {
                pipelineBuilder.executePipeline(pipeline, null);
            } catch (Exception e) {
                // Stage 03: pipeline threw mid-run — keep the snapshot so
                // the user can rewind. Commit before the early return.
                // Stage 07: helper also publishes safe_mode.snapshot.committed.
                commitRescueAndPublish(rescue);
                return errorResponse("Pipeline error: " + e.getMessage());
            }
        }
        } finally {
            macroInFlight.decrementAndGet();
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
        // Step 04: if any step had fuzzy corrections applied, surface them at
        // the pipeline level so the agent learns the canonical spellings.
        if (!allCorrections.isEmpty()) {
            resultJson.add("autocorrected",
                    PluginNameValidator.buildAutocorrectedArray(allCorrections));
        }
        // Step 10: post-pipeline phantom-dialog check — one reply-level report
        // for the whole pipeline, so a silent modal opened by any step is
        // surfaced alongside the per-step status array.
        final JsonObject resultJsonRef = resultJson;
        try {
            PhantomDialogDetector.detect(modalBefore, phantomAutoDismiss)
                    .ifPresent(new java.util.function.Consumer<JsonObject>() {
                        @Override
                        public void accept(JsonObject phantom) {
                            resultJsonRef.add("phantomDialog", phantom);
                        }
                    });
        } catch (Throwable ignore) {}
        // Step 13: one graphDelta per pipeline (not per step) so a chain of
        // Duplicate → Blur → Threshold lands as a linear subgraph under a
        // single reply field. Origin is "pipeline" so downstream agents can
        // distinguish pipeline-built provenance from ad-hoc macros.
        try {
            Set<String> graphTitlesAfter = ImageGraph.captureOpenTitles();
            imageGraph.trackMacroChange(graphTitlesBefore, graphActiveTitleBefore,
                    graphTitlesAfter, pipelineMacro.toString(), "pipeline");
            if (caps != null && caps.graphDelta) {
                ImageGraph.Delta gDelta = imageGraph.deltaSince(graphMarkerBefore);
                if (!gDelta.isEmpty()) {
                    resultJson.add("graphDelta", gDelta.toJson());
                }
            }
        } catch (Throwable ignore) {}
        // Stage 03: settle the rescue handle. PipelineBuilder marks the
        // pipeline's overall {@code status} field as "completed" only on a
        // clean run; "failed" / anything-else means at least one step
        // tripped. Drop the snapshot on the clean path; commit on any
        // failure so {@code rewind} can roll back to the pre-pipeline state.
        // Stage 07: helpers also publish safe_mode.snapshot.{released,committed}.
        if (rescue != null) {
            if ("completed".equals(result.status)) {
                releaseRescueAndPublish(rescue);
            } else {
                commitRescueAndPublish(rescue);
            }
        }
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

    /**
     * Execute a list of sub-commands sequentially. Each sub-command is
     * dispatched through {@link #dispatch(JsonObject, AgentCaps)} so it
     * inherits the originating socket's caps — closing the safe-mode bypass
     * documented in {@code docs/safe_mode_v2/01_fix-batch-run-bypass.md}.
     */
    private JsonObject handleBatch(JsonObject request, AgentCaps caps) {
        JsonElement commandsElement = request.get("commands");
        if (commandsElement == null || !commandsElement.isJsonArray()) {
            return errorResponse("Missing 'commands' array for batch");
        }

        JsonArray commands = commandsElement.getAsJsonArray();
        JsonArray results = new JsonArray();

        for (int i = 0; i < commands.size(); i++) {
            JsonElement elem = commands.get(i);
            if (elem.isJsonObject()) {
                JsonObject subResult = dispatch(elem.getAsJsonObject(), caps);
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
    private JsonObject handleRunChain(JsonObject request, AgentCaps caps) {
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
            // Thread caller's caps so each chain segment inherits safe-mode
            // (and any other negotiated capability). Per plan:
            // docs/safe_mode_v2/01_fix-batch-run-bypass.md.
            JsonObject subResp = dispatch(subReq, caps);
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
            // Step 12: tag each row with the agent id the caller negotiated via
            // hello. Empty string for pre-step-12 rows or nested dispatches.
            o.addProperty("agent_id", e.agentId == null ? "" : e.agentId);
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
    private JsonObject handleExecuteMacroAsync(JsonObject request, AgentCaps caps) {
        JsonElement codeEl = request.get("code");
        if (codeEl == null || !codeEl.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for execute_macro_async");
        }
        String code = codeEl.getAsString();

        // Stage 04 (docs/safe_mode_v2/04_queue-storm-per-image.md): same
        // per-image guard the sync path runs. The async path does not
        // populate {@link #inFlightByImage} on its own (the worker thread
        // lives inside JobRegistry, which has no caps awareness yet) — so
        // this check only fires when a sync execute_macro on the same
        // image is currently paused on a Fiji dialog. That covers the
        // scenario the plan transcript hit: an agent firing async macros
        // while a sync macro is parked on "Convert Stack?".
        if (isQueueStormGuardEnabled(caps)) {
            String target = resolveTargetImageTitleWithFallback(code);
            if (target != null) {
                ActiveMacro inflight = inFlightByImage.get(target);
                if (inflight != null && inflight.state == MacroState.PAUSED_ON_DIALOG) {
                    return queueStormBlockedReply(inflight, target, caps);
                }
            }
        }

        // Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md):
        // mirror the sync execute_macro guard so async macros cannot route
        // around the scientific-integrity scanner.
        if (isScientificIntegrityScanEnabled(caps)) {
            DestructiveScanner.Context scanCtx = captureScannerContext(caps);
            List<DestructiveScanner.DestructiveOp> findings =
                    DestructiveScanner.scan(code, scanCtx);
            if (!findings.isEmpty()) {
                List<DestructiveScanner.DestructiveOp> rejects =
                        DestructiveScanner.rejections(findings);
                if (!rejects.isEmpty()) {
                    return destructiveBlockedReply(rejects, caps);
                }
                for (DestructiveScanner.DestructiveOp op : DestructiveScanner.backups(findings)) {
                    if (DestructiveScanner.RULE_ROI_WIPE.equals(op.ruleId)
                            && caps.safeModeOptions != null
                            && caps.safeModeOptions.autoBackupRoiOnReset) {
                        runRoiAutoBackup(op, caps);
                    }
                }
            }
        }

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
    private JsonObject handleIntent(JsonObject request, AgentCaps caps) {
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
        // Re-enter dispatch with the caller's caps so the resolved macro runs
        // through the same capability-aware pipeline as a direct
        // execute_macro call. Without this, intent silently bypassed every
        // safe-mode guard. Per plan: docs/safe_mode_v2/01_fix-batch-run-bypass.md.
        JsonObject execResp = dispatch(macroReq, caps);

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
                                // Mirror the probe path: GenericDialog.dispose() does not
                                // set wasCanceled, so plugins can continue as if defaults
                                // were accepted unless we flip the flag first.
                                boolean canceled = false;
                                try {
                                    Class<?> genericDialogClass = Class.forName("ij.gui.GenericDialog");
                                    if (genericDialogClass.isInstance(win)) {
                                        Class<?> c = win.getClass();
                                        while (c != null && c != Object.class) {
                                            try {
                                                java.lang.reflect.Field f = c.getDeclaredField("wasCanceled");
                                                f.setAccessible(true);
                                                f.setBoolean(win, true);
                                                canceled = true;
                                                break;
                                            } catch (NoSuchFieldException nsf) {
                                                c = c.getSuperclass();
                                            } catch (Exception ignore) {
                                                break;
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}
                                if (!canceled && win instanceof Container) {
                                    clickCancelButton((Container) win);
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
    // Step 04: list_commands — canonical plugin/menu-command list
    // -----------------------------------------------------------------------

    /**
     * Return every command ImageJ knows about (name → optional class path).
     * Sourced from the {@link MenuCommandRegistry} snapshot populated at server
     * start, so zero-latency and stable per session. Clients pass
     * {@code include_classes=true} to get {@code [{name, class}, ...]} objects
     * instead of the default names-only array.
     * <p>
     * Introduced by {@code docs/tcp_upgrade/04_fuzzy_plugin_registry.md} —
     * replaces the legacy client-side {@code scan_plugins.py} scraper.
     */
    private JsonObject handleListCommands(JsonObject request) {
        boolean includeClasses = false;
        if (request != null && request.has("include_classes")) {
            JsonElement el = request.get("include_classes");
            if (el != null && el.isJsonPrimitive()) {
                try { includeClasses = el.getAsBoolean(); } catch (Exception ignore) {}
            }
        }

        MenuCommandRegistry reg = MenuCommandRegistry.get();
        List<String> names = reg.allCommands();

        JsonObject result = new JsonObject();
        result.addProperty("count", names.size());
        JsonArray arr = new JsonArray();
        if (includeClasses) {
            for (String name : names) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", name);
                String cls = reg.classFor(name);
                entry.addProperty("class", cls != null ? cls : "");
                arr.add(entry);
            }
        } else {
            for (String name : names) {
                arr.add(new JsonPrimitive(name));
            }
        }
        result.add("commands", arr);
        return successResponse(result);
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
    private JsonObject handleInteractDialog(JsonObject request, AgentCaps caps) {
        JsonElement actionElement = request.get("action");
        if (actionElement == null || !actionElement.isJsonPrimitive()) {
            return errorResponse("Missing 'action' field for interact_dialog");
        }
        final String action = actionElement.getAsString();

        // Step 10: snapshot modal dialogs BEFORE interacting. interact_dialog
        // drives existing dialogs (click_button / set_text / …); if the
        // action opens a new modal (a confirmation prompt, say) the detector
        // surfaces it under phantomDialog alongside whatever the action
        // itself returned. Per plan: docs/tcp_upgrade/10_phantom_dialog_detector.md.
        final Set<Window> modalBefore = PhantomDialogDetector.currentModalWindows();
        final boolean phantomAutoDismiss = resolveAutoDismissPhantoms(request, caps);

        // Find the target dialog
        JsonElement dialogElement = request.get("dialog");
        final String dialogTitle = (dialogElement != null && dialogElement.isJsonPrimitive())
                ? dialogElement.getAsString() : null;

        // Step 13: provenance-graph baseline. An OK click on a plugin's
        // GenericDialog typically runs the plugin and produces a new image
        // (Duplicate OK, Subtract Background OK, ...) — track it with
        // origin="dialog" so the agent sees the new node came from a GUI
        // interaction rather than an explicit macro. Per plan:
        // docs/tcp_upgrade/13_provenance_graph.md.
        final Set<String> graphTitlesBefore = ImageGraph.captureOpenTitles();
        final String graphActiveTitleBefore = ImageGraph.captureActiveTitle();
        final long graphMarkerBefore = imageGraph.currentMarker();
        final String graphInteractionLabel =
                "interact_dialog:" + action
                + (dialogTitle != null ? " dialog=" + dialogTitle : "");

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

        JsonObject reply = (JsonObject) holder[0];
        // Step 10: attach phantomDialog to the interact_dialog reply if the
        // action opened a new modal. The detector runs regardless of whether
        // the interaction itself succeeded — a silent confirmation dialog on
        // a failed click is still a deadlock worth surfacing.
        if (reply != null) {
            try {
                final JsonObject replyRef = reply;
                PhantomDialogDetector.detect(modalBefore, phantomAutoDismiss)
                        .ifPresent(new java.util.function.Consumer<JsonObject>() {
                            @Override
                            public void accept(JsonObject phantom) {
                                replyRef.add("phantomDialog", phantom);
                            }
                        });
            } catch (Throwable ignore) {}
        }
        // Step 13: diff the post-interaction open-title set against the
        // pre-call snapshot and track new images into the shared provenance
        // graph with origin="dialog". Attach the produced subgraph under
        // graphDelta on the reply's inner result object, matching the shape
        // used by handleExecuteMacro / handleRunScript / handleRunPipeline.
        // Runs on success and error paths — a GenericDialog that ran its
        // plugin and then reported a validation failure still left work.
        try {
            Set<String> graphTitlesAfter = ImageGraph.captureOpenTitles();
            imageGraph.trackMacroChange(graphTitlesBefore, graphActiveTitleBefore,
                    graphTitlesAfter, graphInteractionLabel, "dialog");
            if (reply != null && caps != null && caps.graphDelta) {
                ImageGraph.Delta gDelta = imageGraph.deltaSince(graphMarkerBefore);
                if (!gDelta.isEmpty()) {
                    JsonElement resultEl = reply.get("result");
                    if (resultEl != null && resultEl.isJsonObject()) {
                        resultEl.getAsJsonObject().add("graphDelta", gDelta.toJson());
                    } else {
                        reply.add("graphDelta", gDelta.toJson());
                    }
                }
            }
        } catch (Throwable ignore) {}
        return reply;
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
