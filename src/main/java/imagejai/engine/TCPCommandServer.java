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
import imagejai.config.PrivacyPosture;
import imagejai.engine.security.AgentContextSanitizer;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import imagejai.engine.security.Brief;
import imagejai.engine.security.CaptureSource;
import imagejai.engine.security.PathTokenMap;
import imagejai.engine.security.PseudonymisationFilter;
import imagejai.engine.security.RedactionReport;
import imagejai.engine.security.SelectionBroker;
import imagejai.engine.security.VisualOverrideRegistry;
import imagejai.engine.safeMode.DestructiveScanner;
import imagejai.engine.safeMode.RoiAutoBackup;
import imagejai.engine.safeMode.SourceImageTagger;
import imagejai.ui.ChatPanelController;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final List<String> KNOWN_COMMANDS =
            CommandManifest.requestResponseNames();
    // 10-minute synchronous-macro ceiling. Long enough for batch 3D Object
    // Counter runs on dense masks without blocking the TCP thread forever.
    // Callers can override per-request with `"timeout_ms": N` (pass 0 or a
    // negative value to disable the timeout entirely — the server will wait
    // until the macro finishes, the dialog-dismiss path fires, or the
    // connection is closed).
    private static final long MACRO_TIMEOUT_MS = 600_000;
    private static final long PIPELINE_TIMEOUT_MS = 600_000;
    public static final int MAX_CONNECTION_WORKERS = 16;
    public static final int CONNECTION_QUEUE_CAPACITY = 64;
    public static final int MAX_BATCH_COMMANDS = 64;
    public static final int MAX_BATCH_DEPTH = 4;
    public static final int MAX_COMPOUND_WORK = 64;
    public static final long MAX_BATCH_RESPONSE_BYTES = 4L * 1024L * 1024L;
    public static final long MAX_RESULTS_TABLE_BYTES = 2L * 1024L * 1024L;
    public static final int MAX_CAPTURE_DIMENSION = 4096;
    public static final int MAX_CAPTURE_PNG_BYTES = 16 * 1024 * 1024;
    public static final int MAX_PROCESS_OUTPUT_BYTES = 1024 * 1024;
    public static final long METHODS_PROCESS_TIMEOUT_MS = 30_000L;
    public static final int MAX_HANDSHAKE_IDENTITY_CHARS = 256;
    public static final int MAX_HANDSHAKE_OUTPUT_FORMAT_CHARS = 64;
    public static final int MAX_ACCEPT_EVENT_TOPICS = 64;
    public static final int MAX_ACCEPT_EVENT_TOPIC_CHARS = 128;
    private static final long COMPOUND_RESPONSE_OVERHEAD_BYTES = 2048L;

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

    // -----------------------------------------------------------------------
    // Shared-token authentication (Jupyter-style ?token=...)
    // -----------------------------------------------------------------------

    /** Path of the per-install shared token: {@code ~/.imagejai/server-token}. */
    private static java.nio.file.Path tokenFilePath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) home = ".";
        return java.nio.file.Paths.get(home, ".imagejai", "server-token");
    }

    /**
     * Read the server token from {@link #tokenFilePath()} or generate a new
     * one and persist it. Token is 32 random bytes base64url-encoded — long
     * enough to resist brute force from a co-located process. File is
     * written with owner-read/write only on POSIX systems; on Windows the
     * default ACL applies (sufficient because the server only listens on
     * loopback).
     */
    private static String loadOrGenerateToken() {
        return loadOrGenerateToken(tokenFilePath());
    }

    /** Package-private seam for persistence failure and atomic-write tests. */
    static String loadOrGenerateToken(java.nio.file.Path p) {
        if (java.nio.file.Files.exists(p)) {
            try {
                if (!java.nio.file.Files.isRegularFile(p)) {
                    throw new java.io.IOException("token path is not a regular file");
                }
                long bytes = java.nio.file.Files.size(p);
                if (bytes > 4096L) {
                    throw new java.io.IOException("token file exceeds 4096 bytes");
                }
                String existing = new String(
                        java.nio.file.Files.readAllBytes(p),
                        StandardCharsets.UTF_8).trim();
                if (existing.length() < 32) {
                    throw new java.io.IOException("token file is empty or invalid");
                }
                return existing;
            } catch (java.io.IOException unreadable) {
                throw new IllegalStateException("Existing server token is unreadable: "
                        + unreadable.getMessage(), unreadable);
            }
        }
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String token = java.util.Base64.getUrlEncoder()
                .withoutPadding().encodeToString(raw);
        java.nio.file.Path pending = null;
        try {
            java.nio.file.Files.createDirectories(p.getParent());
            pending = java.nio.file.Files.createTempFile(
                    p.getParent(), ".server-token-", ".tmp");
            java.nio.file.Files.write(pending,
                    token.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            try {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                        java.util.EnumSet.of(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                java.nio.file.Files.setPosixFilePermissions(pending, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows + non-POSIX file systems do not expose POSIX modes. The
                // loopback bind plus default user-private home directory
                // ACL is the actual protection on those platforms.
            }
            java.nio.file.Files.move(pending, p,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            pending = null;

            // Do not advertise a server whose credential exists only in memory.
            // Verify the durable bytes before returning the token used by hello.
            String persisted = new String(java.nio.file.Files.readAllBytes(p),
                    StandardCharsets.UTF_8).trim();
            if (!token.equals(persisted)) {
                throw new java.io.IOException("persisted token verification failed");
            }
        } catch (java.io.IOException e) {
            if (pending != null) {
                try {
                    java.nio.file.Files.deleteIfExists(pending);
                } catch (java.io.IOException ignored) {
                    // Preserve the original persistence failure.
                }
            }
            throw new IllegalStateException("Failed to persist server token: "
                    + e.getMessage(), e);
        }
        return token;
    }

    /**
     * Whether to enforce token auth on every non-hello/non-ping command.
     * Authentication is fail-secure by default. A local installation may
     * explicitly opt into the restricted read-only compatibility surface via
     * {@code imagejai.tcp.requireToken=false} or
     * {@code IMAGEJAI_TCP_REQUIRE_TOKEN=0} while an old client is upgraded.
     */
    private static boolean tokenAuthRequired() {
        String prop = System.getProperty("imagejai.tcp.requireToken");
        if (prop != null) return !isExplicitFalse(prop);
        String env = System.getenv("IMAGEJAI_TCP_REQUIRE_TOKEN");
        return env == null || !isExplicitFalse(env);
    }

    private static boolean isExplicitFalse(String value) {
        String normalised = value == null ? "" : value.trim();
        return "0".equals(normalised)
                || "false".equalsIgnoreCase(normalised)
                || "no".equalsIgnoreCase(normalised)
                || "off".equalsIgnoreCase(normalised);
    }

    /** Constant-time string equality. */
    private static boolean tokensEqual(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // Phase 2: event-bus subscription caps.
    private static final int MAX_SUBSCRIBERS = 8;
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 256;
    private static final int MAX_SUBSCRIPTION_TOPICS = 64;
    private static final int MAX_SUBSCRIPTION_TOPIC_LENGTH = 128;
    private static final long SUBSCRIBER_HEARTBEAT_MS = 30_000L;
    private final AtomicInteger activeSubscribers = new AtomicInteger(0);
    private final Set<Socket> subscriberSockets =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final Set<Thread> subscriberThreads =
            Collections.newSetFromMap(new ConcurrentHashMap<Thread, Boolean>());
    private final Set<Socket> activeClientSockets =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
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
    private static final Set<String> DEDUP_COMMANDS =
            new HashSet<String>(CommandManifest.hashDedupNames());

    /**
     * Server version string emitted in the {@code hello} handshake response.
     * Bumped by steps that change the reply schema so clients can adapt.
     */
    static final String SERVER_VERSION = "1.8.0";

    /**
     * Per-session capability record negotiated via the {@code hello} handler.
     * Network clients that never call {@code hello} receive deliberately
     * restricted compatibility caps. Fields are package-private because
     * future-step handlers ({@code 02-07}) in this package read them directly.
     */
    static final class AgentCaps {
        String agent = "unknown";
        String agentId = null;
        String sessionId = "";
        String clientSessionId = "";
        String modelEndpoint = "";
        // True when the connecting client presented the correct shared token
        // in its hello handshake. Read by dispatchCore to gate non-hello
        // commands. Token auth is enabled by default and can only be disabled
        // through an explicit local compatibility setting.
        // Defaults to false so a forgotten/wrong token cannot accidentally
        // unlock the server when the gate is later flipped on.
        boolean authenticated = false;
        boolean compatibility = false;
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
        // Safe-mode master switch. The field default stays false for trusted
        // in-process handler calls. Network compatibility caps override it to
        // true, and handshake clients negotiate true by default.
        boolean safeMode = false;
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

    public static final class SafeModeOptions {
        public boolean blockBitDepthNarrowing = false;
        public boolean blockNormalizeContrast = false;
        public boolean autoBackupRoiOnReset = true;
        public boolean autoSnapshotRescue = true;
        public boolean queueStormGuard = true;
        public boolean autoSourceImageColumn = true;
        public boolean scientificIntegrityScan = true;
    }

    /** Trusted in-process fallback used by package-level handler tests. */
    static final AgentCaps DEFAULT_CAPS = new AgentCaps();

    /** Restricted network policy for old clients that do not send sessions. */
    private static final AgentCaps LEGACY_CAPS = legacyCaps();

    private static AgentCaps legacyCaps() {
        AgentCaps caps = new AgentCaps();
        caps.compatibility = true;
        caps.safeMode = true;
        caps.vision = false;
        caps.structuredErrors = false;
        caps.pulse = false;
        caps.stateDelta = false;
        caps.dedup = false;
        caps.patternHints = false;
        caps.graphDelta = false;
        caps.ledger = false;
        caps.undo = false;
        caps.autoDismissPhantoms = false;
        caps.acceptEvents = Collections.emptySet();
        return caps;
    }

    enum MacroState { RUNNING, PAUSED_ON_DIALOG }

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

    final Map<String, ActiveMacro> inFlightByImage =
            new ConcurrentHashMap<String, ActiveMacro>();

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
        long resultsTableOriginalBytes;
        int resultsTableReturnedRows;
        int resultsTableTotalRows;
        boolean resultsTableTruncated;
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
            if (resultsTable != null) addBoundedResultsCsv(obj);
            if (logDelta != null) addBoundedUtf8Property(obj,
                    "logDelta", logDelta, MAX_RESULTS_TABLE_BYTES);
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
                if (resultsTable != null) addBoundedResultsCsv(result);
                if (logDelta != null) addBoundedUtf8Property(result,
                        "logDelta", logDelta, MAX_RESULTS_TABLE_BYTES);
                if (dismissedDialogs != null) result.add("dismissedDialogs", dismissedDialogs);
            }
        }

        void setResultsTable(StateInspector.BoundedCsv csv) {
            if (csv == null) return;
            resultsTable = csv.text();
            resultsTableOriginalBytes = csv.originalBytes();
            resultsTableReturnedRows = csv.returnedRows();
            resultsTableTotalRows = csv.totalRows();
            resultsTableTruncated = csv.truncated();
        }

        private void addBoundedResultsCsv(JsonObject target) {
            target.addProperty("resultsTable", resultsTable);
            target.addProperty("resultsTable_truncated", resultsTableTruncated);
            target.addProperty("resultsTable_original_bytes", resultsTableOriginalBytes);
            target.addProperty("resultsTable_returned_bytes", utf8Length(resultsTable));
            target.addProperty("resultsTable_returned_rows", resultsTableReturnedRows);
            target.addProperty("resultsTable_total_rows", resultsTableTotalRows);
        }
    }

    /** Durable caps keyed by an opaque, expiring protocol session. */
    private final SessionCapsRegistry<AgentCaps> sessionRegistry;

    java.util.List<AgentCaps> capsWitnessForTest = null;

    static java.util.function.BiFunction<JsonObject, AgentCaps, JsonObject>
            executeMacroForTest = null;
    java.util.function.Function<String, ScriptEngine> scriptEngineResolverForTest = null;
    interface OpenImageOperation {
        void open(String path, int series) throws Exception;
    }
    OpenImageOperation openImageOperationForTest = null;
    java.util.function.Supplier<List<ImageGraph.ImageRef>> openImagesForTest = null;
    java.util.function.Supplier<ImagePlus> currentImageForTest = null;

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
     * Step 15: number of coordinator-backed mutating handlers in flight.
     * Read by the rewind / branch
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
    // Shared token persisted to ~/.imagejai/server-token at start(). Always
    // generated. Required by hello when token auth is enforced (see
    // tokenAuthRequired); advisory otherwise.
    private volatile String serverToken;
    private final CommandEngine commandEngine;
    private final StateInspector stateInspector;
    private final PipelineBuilder pipelineBuilder;
    private final ExplorationEngine explorationEngine;
    private final FrictionLog frictionLog = new FrictionLog();
    private final PseudonymisationFilter pseudonymisationFilter =
            PseudonymisationFilter.getInstance();
    private AuditLog auditLog = AuditLog.getInstance();
    IntentRouter intentRouter = new IntentRouter();
    private final MutationCoordinator mutationCoordinator;
    private final JobRegistry jobRegistry;
    // Phase 8: reactive rules engine. Subscribes to the bus, fires rule
    // actions in response to matching events. Lifecycle tied to the TCP
    // server — {@link #start} / {@link #stop}.
    private final ReactiveEngine reactiveEngine;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private volatile ThreadPoolExecutor connectionWorkers;
    private final AtomicLong rejectedConnections = new AtomicLong(0L);
    private final ThreadLocal<Integer> batchDepth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return Integer.valueOf(0); }
    };
    private final ThreadLocal<Long> compoundResponseBudget = new ThreadLocal<Long>() {
        @Override protected Long initialValue() { return Long.valueOf(Long.MAX_VALUE); }
    };
    private final ThreadLocal<CompoundWorkBudget> compoundWorkBudget =
            new ThreadLocal<CompoundWorkBudget>();

    private static final class CompoundWorkBudget {
        int consumed;
        boolean exhausted;

        boolean tryConsume() {
            if (consumed >= MAX_COMPOUND_WORK) {
                exhausted = true;
                return false;
            }
            consumed++;
            return true;
        }

        int remaining() { return Math.max(0, MAX_COMPOUND_WORK - consumed); }
    }
    private final Object serverLifecycleLock = new Object();
    private volatile Runnable beforeBindHookForTest;
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
        this(port, commandEngine, stateInspector, pipelineBuilder,
                explorationEngine, new SessionCapsRegistry<AgentCaps>(), null);
    }

    /**
     * Construct the TCP surface with an application-owned coordinator so
     * other in-process assistants can share the same mutation boundary.
     */
    public TCPCommandServer(int port, CommandEngine commandEngine,
                            StateInspector stateInspector,
                            PipelineBuilder pipelineBuilder,
                            ExplorationEngine explorationEngine,
                            MutationCoordinator coordinator) {
        this(port, commandEngine, stateInspector, pipelineBuilder,
                explorationEngine, new SessionCapsRegistry<AgentCaps>(), coordinator);
    }

    TCPCommandServer(int port, CommandEngine commandEngine,
                     StateInspector stateInspector,
                     PipelineBuilder pipelineBuilder,
                     ExplorationEngine explorationEngine,
                     SessionCapsRegistry<AgentCaps> sessionRegistry) {
        this(port, commandEngine, stateInspector, pipelineBuilder,
                explorationEngine, sessionRegistry, null);
    }

    TCPCommandServer(int port, CommandEngine commandEngine,
                     StateInspector stateInspector,
                     PipelineBuilder pipelineBuilder,
                     ExplorationEngine explorationEngine,
                     SessionCapsRegistry<AgentCaps> sessionRegistry,
                     MutationCoordinator coordinator) {
        this.port = port;
        this.commandEngine = commandEngine;
        this.stateInspector = stateInspector;
        this.pipelineBuilder = pipelineBuilder;
        this.explorationEngine = explorationEngine;
        this.sessionRegistry = sessionRegistry;
        // One application-owned coordinator spans every mutation surface.
        this.mutationCoordinator = coordinator != null ? coordinator
                : new MutationCoordinator();
        if (commandEngine != null) {
            commandEngine.setMutationCoordinator(mutationCoordinator);
        }

        this.jobRegistry = new JobRegistry(commandEngine, mutationCoordinator);
        this.reactiveEngine = new ReactiveEngine(
                eventBus, commandEngine, intentRouter, guiActionDispatcher,
                mutationCoordinator);
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

    /** The single coordinator shared by every mutation producer in this app. */
    public MutationCoordinator getMutationCoordinator() {
        return mutationCoordinator;
    }

    /**
     * Start the TCP server on a background daemon thread.
     *
     * @param listener callback for server events (may be null)
     */
    public void start(ServerListener listener) {
        if (running) return;
        this.listener = listener;
        running = true;
        sessionRegistry.activate();
        connectionWorkers = new ThreadPoolExecutor(
                MAX_CONNECTION_WORKERS, MAX_CONNECTION_WORKERS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(CONNECTION_QUEUE_CAPACITY),
                new ThreadFactory() {
                    private final AtomicLong sequence = new AtomicLong(0L);
                    @Override public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "imagej-ai-tcp-client-"
                                + sequence.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                }, new ThreadPoolExecutor.AbortPolicy());

        // Generate or reload the per-install shared token before opening the
        // listen socket. Always loaded — hello accepts and verifies it.
        // Enforcement (refusing non-hello commands when the token is missing)
        // is enabled by default. The explicit compatibility opt-out exposes
        // only the read-only whitelist.
        try {
            if (this.serverToken == null) {
                this.serverToken = loadOrGenerateToken();
            }
        } catch (Throwable t) {
            System.err.println("[ImageJAI-TCP] Token init failed: " + t.getMessage());
            this.serverToken = null;
            running = false;
            sessionRegistry.revokeAll();
            connectionWorkers.shutdownNow();
            connectionWorkers = null;
            if (listener != null) {
                listener.onError("TCP authentication could not be initialized");
            }
            return;
        }

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
        // Swap the listener under the same lock used by runServer's bind.
        // This prevents a delayed server thread from binding after stop().
        ServerSocket listenerSocket;
        synchronized (serverLifecycleLock) {
            running = false;
            listenerSocket = serverSocket;
            serverSocket = null;
        }
        closeQuietly(listenerSocket);
        sessionRegistry.revokeAll();
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
        // Long-lived subscribers are not accepted through ServerSocket again,
        // so closing only the listener would leave their client threads
        // blocked in queue.poll until the next heartbeat.
        for (Socket subscriber : subscriberSockets) {
            try {
                subscriber.close();
            } catch (Exception ignore) {
            }
        }
        subscriberSockets.clear();
        for (Thread subscriberThread : subscriberThreads) {
            subscriberThread.interrupt();
        }
        subscriberThreads.clear();
        shutdownConnectionWorkers();
        for (Socket client : activeClientSockets) closeQuietly(client);
        activeClientSockets.clear();
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
        if (serverSocket != null && !serverSocket.isClosed()) {
            return serverSocket.getLocalPort();
        }
        return port;
    }

    /** Package-private deterministic token seam for loopback protocol tests. */
    void setServerTokenForTest(String token) {
        this.serverToken = token;
    }

    /** Package-private audit destination seam for side-effect-free tests. */
    void setAuditLogForTest(AuditLog auditLog) {
        this.auditLog = auditLog == null ? AuditLog.getInstance() : auditLog;
    }

    /** Package-private deterministic seam for stop-before-bind regression tests. */
    void setBeforeBindHookForTest(Runnable hook) {
        this.beforeBindHookForTest = hook;
    }

    /**
     * Canonical TCP protocol command names. Stage 08 tests use this list so a
     * new dispatcher branch requires a matching governance fixture.
     */
    public static List<String> knownCommands() {
        return KNOWN_COMMANDS;
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
        ServerSocket listenerSocket = null;
        try {
            // Loopback-only bind. Any non-loopback bind would expose the
            // server to the local network. Do not change without also shipping
            // a TLS-or-equivalent transport and reviewing the trust boundary.
            Runnable beforeBind = beforeBindHookForTest;
            if (beforeBind != null) beforeBind.run();
            listenerSocket = new ServerSocket();
            listenerSocket.setReuseAddress(true);
            synchronized (serverLifecycleLock) {
                if (!running) return;
                listenerSocket.bind(new java.net.InetSocketAddress(
                        InetAddress.getLoopbackAddress(), port), 50);
                // stop() cannot interleave between bind and publication.
                serverSocket = listenerSocket;
            }
            int boundPort = listenerSocket.getLocalPort();
            System.err.println("[ImageJAI-TCP] Server listening on " +
                    InetAddress.getLoopbackAddress().getHostAddress() + ":" + boundPort);
            if (listener != null) {
                listener.onServerStarted(boundPort);
            }

            while (running) {
                try {
                    final Socket clientSocket = listenerSocket.accept();
                    ThreadPoolExecutor workers = connectionWorkers;
                    if (workers == null || workers.isShutdown()) {
                        closeQuietly(clientSocket);
                        continue;
                    }
                    ClientTask task = new ClientTask(clientSocket);
                    // Register before executor admission so stop() can close
                    // both queued and already-running client sockets.
                    activeClientSockets.add(clientSocket);
                    try {
                        workers.execute(task);
                        if (listener != null) {
                            listener.onClientConnected(
                                    clientSocket.getRemoteSocketAddress().toString());
                        }
                    } catch (RejectedExecutionException capacity) {
                        rejectedConnections.incrementAndGet();
                        writeCapacityRejection(clientSocket);
                        task.close();
                    }
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
            closeQuietly(listenerSocket);
            synchronized (serverLifecycleLock) {
                if (serverSocket == listenerSocket) serverSocket = null;
                running = false;
            }
            shutdownConnectionWorkers();
            for (Socket client : activeClientSockets) closeQuietly(client);
            activeClientSockets.clear();
        }
    }

    private synchronized void shutdownConnectionWorkers() {
        ThreadPoolExecutor workers = connectionWorkers;
        connectionWorkers = null;
        if (workers == null) return;
        List<Runnable> queued = workers.shutdownNow();
        for (Runnable task : queued) {
            if (task instanceof ClientTask) ((ClientTask) task).close();
        }
    }

    // -----------------------------------------------------------------------
    // Client handling
    // -----------------------------------------------------------------------

    private void handleClient(Socket socket) {
        PrintWriter writer = null;
        try {
            socket.setSoTimeout(60000); // 60s read timeout
            writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), UTF8), true);

            // Enforce the wire-byte cap before decoding or JSON allocation.
            RequestLine requestLine = readUtf8Line(socket.getInputStream(),
                    Constants.TCP_MAX_MESSAGE_SIZE);
            String line = requestLine == null ? null : requestLine.text;
            if (line == null || line.trim().isEmpty()) {
                writeOutbound(writer, "error", errorJson("Empty request"));
                return;
            }

            // Phase 2: intercept "subscribe" — upgrade to a streaming channel
            // instead of the standard request/response cycle.
            String trimmed = line.trim();
            JsonObject request;
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("Request must be a JSON object");
                }
                request = parsed.getAsJsonObject();
            } catch (Exception e) {
                writeOutbound(writer, "error", errorJson("Invalid JSON: "
                        + e.getMessage()));
                return;
            }

            // Upgrade only an exactly parsed command. Text elsewhere in a
            // request cannot turn a one-shot command into a stream.
            String commandName = optString(request, "command", "");
            if ("subscribe".equals(commandName)) {
                handleSubscribeStream(socket, request,
                        requestLine.byteCount);
                return; // finally closes the socket
            }

            JsonObject response = dispatch(request, socket);
            writeOutbound(writer, commandName, GSON.toJson(response));

        } catch (RequestTooLargeException e) {
            if (writer != null) {
                writeOutbound(writer, "error", errorJson("Request too large (max "
                        + Constants.TCP_MAX_MESSAGE_SIZE + " UTF-8 bytes)"));
            }
        } catch (CharacterCodingException e) {
            if (writer != null) {
                writeOutbound(writer, "error", errorJson(
                        "Request is not valid UTF-8"));
            }
        } catch (Exception e) {
            System.err.println("[ImageJAI-TCP] Client error: " + e.getMessage());
            if (writer != null) {
                try {
                    writeOutbound(writer, "error", errorJson("Server error: "
                            + e.getMessage()));
                } catch (Exception ignored) {
                    // Client may have disconnected
                }
            }
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {}
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
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
    private void handleSubscribeStream(final Socket socket, JsonObject req,
                                       int requestBytes) {
        final OutputStream rawOut;
        try {
            rawOut = socket.getOutputStream();
        } catch (IOException e) {
            return;
        }

        String sessionId = optString(req, "session_id", "");
        String token = optString(req, "token", null);
        SessionCapsRegistry.Lookup<AgentCaps> lookup =
                sessionRegistry.lookup(sessionId, token);
        req.remove("token");
        if (lookup.status() != SessionCapsRegistry.Status.VALID) {
            writeRawJson(rawOut, sessionFailure(lookup.status()));
            return;
        }
        final AgentCaps caps = lookup.caps();
        final List<String> patterns = parseSubscriptionPatterns(req);
        if (patterns == null) {
            writeRawJson(rawOut, protocolError("invalid_subscription",
                    "topics must be an array of at most "
                            + MAX_SUBSCRIPTION_TOPICS + " valid topic patterns."));
            return;
        }
        if (!subscriptionTopicsAllowed(patterns, caps.acceptEvents)) {
            writeRawJson(rawOut, protocolError("event_subscription_forbidden",
                    "The session did not negotiate every requested event topic."));
            return;
        }
        if (listener != null) {
            listener.onCommandReceived("subscribe");
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
            writeRawJson(rawOut, protocolError("subscriber_capacity",
                    "Subscriber cap reached (max " + MAX_SUBSCRIBERS + ")"));
            return;
        }
        subscriberSockets.add(socket);
        subscriberThreads.add(Thread.currentThread());

        // Per-socket bounded queue with drop-oldest semantics.
        final LinkedBlockingDeque<JsonObject> queue =
                new LinkedBlockingDeque<JsonObject>(SUBSCRIBER_QUEUE_CAPACITY);
        final AtomicLong droppedFrames = new AtomicLong(0L);
        final AtomicLong sentFrames = new AtomicLong(0L);

        final EventBus.Listener listener = new EventBus.Listener() {
            @Override
            public void onEvent(JsonObject frame) {
                JsonObject governed = governEventFrame(frame, caps, socket);
                if (governed == null) return;
                droppedFrames.addAndGet(offerSubscriberFrame(
                        queue, governed, caps, socket));
            }
        };

        // Register every pattern atomically so an accepted ack never masks a
        // partial/zero subscription at the global EventBus cap.
        if (!eventBus.subscribeAll(patterns, listener)) {
            subscriberSockets.remove(socket);
            subscriberThreads.remove(Thread.currentThread());
            activeSubscribers.decrementAndGet();
            writeRawJson(rawOut, protocolError("event_subscription_capacity",
                    "Event subscription capacity reached; no topics were registered."));
            appendSubscriptionAudit("subscribe.open", req, caps, patterns,
                    "event_bus_capacity", requestBytes, 0L, 0L);
            return;
        }
        appendSubscriptionAudit("subscribe.open", req, caps, patterns,
                "accepted", requestBytes, 0L, 0L);

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
            writeFrame(rawOut, governEventFrame(ack, caps, socket));
            sentFrames.incrementAndGet();
        } catch (IOException e) {
            eventBus.unsubscribe(listener);
            subscriberSockets.remove(socket);
            subscriberThreads.remove(Thread.currentThread());
            appendSubscriptionAudit("subscribe.close", req, caps, patterns,
                    "ack_write_failed", 0, sentFrames.get(), droppedFrames.get());
            activeSubscribers.decrementAndGet();
            return;
        }

        // Main pump: pull frames until the socket dies; inject heartbeats
        // during idle windows.
        long lastSent = System.currentTimeMillis();
        String closeReason = "client_disconnected";
        try {
            while (running && !socket.isClosed()) {
                SessionCapsRegistry.Lookup<AgentCaps> currentSession =
                        sessionRegistry.lookup(sessionId, token);
                if (currentSession.status() != SessionCapsRegistry.Status.VALID) {
                    closeReason = "session_" + currentSession.status().name()
                            .toLowerCase(Locale.ROOT);
                    break;
                }
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
                        writeFrame(rawOut, governEventFrame(hb, caps, socket));
                        sentFrames.incrementAndGet();
                    } catch (IOException e) {
                        closeReason = "write_failed";
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
                    closeReason = "interrupted";
                    break;
                }
                if (frame == null) continue; // back to heartbeat check
                SessionCapsRegistry.Lookup<AgentCaps> beforeWrite =
                        sessionRegistry.lookup(sessionId, token);
                if (beforeWrite.status() != SessionCapsRegistry.Status.VALID) {
                    closeReason = "session_" + beforeWrite.status().name()
                            .toLowerCase(Locale.ROOT);
                    break;
                }
                try {
                    writeFrame(rawOut, frame);
                    sentFrames.incrementAndGet();
                } catch (IOException e) {
                    closeReason = "write_failed";
                    break; // socket died
                }
                lastSent = System.currentTimeMillis();
            }
            if (!running) closeReason = "server_stopped";
        } finally {
            eventBus.unsubscribe(listener);
            subscriberSockets.remove(socket);
            subscriberThreads.remove(Thread.currentThread());
            appendSubscriptionAudit("subscribe.close", req, caps, patterns,
                    closeReason, 0, sentFrames.get(), droppedFrames.get());
            activeSubscribers.decrementAndGet();
        }
    }

    private final class ClientTask implements Runnable {
        private final Socket socket;
        ClientTask(Socket socket) { this.socket = socket; }
        @Override public void run() {
            try {
                handleClient(socket);
            } finally {
                activeClientSockets.remove(socket);
            }
        }
        void close() {
            activeClientSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    private void writeCapacityRejection(Socket socket) {
        if (socket == null) return;
        try {
            byte[] frame = (GSON.toJson(protocolError("connection_capacity",
                    "Connection worker capacity reached; retry later.")) + "\n")
                    .getBytes(UTF8);
            socket.getOutputStream().write(frame);
            socket.getOutputStream().flush();
        } catch (IOException ignored) { }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    public int getActiveConnectionWorkerCount() {
        ThreadPoolExecutor workers = connectionWorkers;
        return workers == null ? 0 : workers.getActiveCount();
    }

    public int getQueuedConnectionCount() {
        ThreadPoolExecutor workers = connectionWorkers;
        return workers == null ? 0 : workers.getQueue().size();
    }

    int getTrackedClientSocketCountForTest() {
        return activeClientSockets.size();
    }

    public long getRejectedConnectionCount() {
        return rejectedConnections.get();
    }

    private List<String> parseSubscriptionPatterns(JsonObject req) {
        List<String> patterns = new ArrayList<String>();
        JsonElement topics = req == null ? null : req.get("topics");
        if (topics == null || topics.isJsonNull()) {
            patterns.add("*");
            return patterns;
        }
        if (!topics.isJsonArray()) return null;
        JsonArray values = topics.getAsJsonArray();
        if (values.size() == 0 || values.size() > MAX_SUBSCRIPTION_TOPICS) return null;
        for (JsonElement value : values) {
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) return null;
            String pattern = value.getAsString();
            if (!validTopicPattern(pattern)) return null;
            if (!patterns.contains(pattern)) patterns.add(pattern);
        }
        return patterns.isEmpty() ? null : patterns;
    }

    private static boolean validTopicPattern(String value) {
        if (value == null || value.isEmpty()
                || value.length() > MAX_SUBSCRIPTION_TOPIC_LENGTH) return false;
        return "*".equals(value) || value.matches("[A-Za-z0-9_.-]+\\*?");
    }

    private static boolean subscriptionTopicsAllowed(List<String> requested,
                                                     Set<String> accepted) {
        if (requested == null || accepted == null || accepted.isEmpty()) return false;
        for (String request : requested) {
            boolean allowed = false;
            for (String grant : accepted) {
                if (patternContains(grant, request)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) return false;
        }
        return true;
    }

    private static boolean patternContains(String grant, String request) {
        if (grant == null || request == null) return false;
        if ("*".equals(grant) || grant.equals(request)) return true;
        if ("*".equals(request) || !grant.endsWith("*")) return false;
        String grantPrefix = grant.substring(0, grant.length() - 1);
        if (!request.startsWith(grantPrefix)) return false;
        return !request.endsWith("*")
                || request.substring(0, request.length() - 1)
                .startsWith(grantPrefix);
    }

    /**
     * Queue a governed frame. State-like frames replace only an older frame
     * with the same topic and identity; lifecycle frames always retain their
     * own slot. Returns the number dropped because the hard cap was reached.
     */
    int offerSubscriberFrame(LinkedBlockingDeque<JsonObject> queue,
                             JsonObject frame, AgentCaps caps, Socket socket) {
        if (queue == null || frame == null) return 0;
        synchronized (queue) {
            String key = EventBus.coalescingKey(frame);
            if (key != null) {
                java.util.Iterator<JsonObject> iterator = queue.descendingIterator();
                while (iterator.hasNext()) {
                    JsonObject previous = iterator.next();
                    if (key.equals(EventBus.coalescingKey(previous))) {
                        iterator.remove();
                        break;
                    }
                }
            }
            if (queue.offerLast(frame)) return 0;

            JsonObject oldest = queue.pollFirst();
            int dropped = oldest == null ? 0 : 1;
            while (queue.remainingCapacity() < 2) {
                if (queue.pollFirst() == null) break;
                dropped++;
            }

            JsonObject sentinel = new JsonObject();
            sentinel.addProperty("event", "event_dropped");
            JsonObject data = new JsonObject();
            if (oldest != null) {
                data.addProperty("oldest_event", safeEventTopic(
                        optString(oldest, "event", "")));
                if (oldest.has("seq") && oldest.get("seq").isJsonPrimitive()) {
                    data.addProperty("oldest_seq", oldest.get("seq").getAsLong());
                }
            }
            data.addProperty("queue_capacity", queue.size() + queue.remainingCapacity());
            data.addProperty("dropped_count", dropped);
            sentinel.add("data", data);
            sentinel.addProperty("ts", System.currentTimeMillis());
            sentinel.addProperty("seq", eventBus.nextSeq());
            sentinel = governEventFrame(sentinel, caps, socket);

            if (queue.remainingCapacity() >= 2) {
                queue.offerLast(sentinel);
            }
            queue.offerLast(frame);
            return dropped;
        }
    }

    private JsonObject governEventFrame(JsonObject source, AgentCaps caps,
                                        Socket socket) {
        JsonObject frame = source == null ? new JsonObject() : source.deepCopy();
        PrivacyPosture posture = PostureController.getInstance().current();
        String topic = optString(frame, "event", "");
        JsonObject data = frame.has("data") && frame.get("data").isJsonObject()
                ? frame.getAsJsonObject("data") : null;
        if (topic.startsWith("job.") && data != null
                && data.has(JobRegistry.EVENT_OWNER_FIELD)) {
            String owner = optString(data, JobRegistry.EVENT_OWNER_FIELD, "");
            data.remove(JobRegistry.EVENT_OWNER_FIELD);
            String subscriber = caps == null || caps.sessionId == null
                    ? "" : caps.sessionId;
            if (owner.isEmpty() || !owner.equals(subscriber)) {
                return null;
            }
        }
        boolean eventRedacted = false;
        if (posture != null && posture != PrivacyPosture.STANDARD) {
            String safeTopic = safeEventTopic(topic);
            if (!safeTopic.equals(topic)) {
                frame.addProperty("event", safeTopic);
                topic = safeTopic;
                eventRedacted = true;
            }
            eventRedacted |= pseudonymiseEventPayload(topic, data);
        }
        pseudonymisationFilter.apply(frame, "event:" + topic, posture,
                sessionKey(caps, socket));
        if (eventRedacted && frame.has("_governance")
                && frame.get("_governance").isJsonObject()) {
            JsonObject governance = frame.getAsJsonObject("_governance");
            JsonArray fields = governance.has("fields_pseudonymised")
                    && governance.get("fields_pseudonymised").isJsonArray()
                    ? governance.getAsJsonArray("fields_pseudonymised")
                    : new JsonArray();
            boolean present = false;
            for (JsonElement field : fields) {
                if (field.isJsonPrimitive()
                        && "event_sensitive".equals(field.getAsString())) {
                    present = true;
                }
            }
            if (!present) fields.add("event_sensitive");
            governance.add("fields_pseudonymised", fields);
        }
        return frame;
    }

    private boolean pseudonymiseEventPayload(String topic, JsonObject data) {
        if (data == null) return false;
        boolean changed = false;
        String[] titles = {
                "title", "window_title", "image_title", "dialog_title",
                "blocking_dialog_title", "target_image"
        };
        for (String key : titles) {
            changed |= replaceEventString(data, key, "image");
        }
        String[] sensitiveText = {
                "preview", "text", "code", "macro", "script", "error"
        };
        for (String key : sensitiveText) {
            changed |= replaceEventString(data, key, "event");
        }
        if (topic != null && topic.startsWith("dialog.")) {
            changed |= tokeniseStringArray(data, "buttons", "dialog-option");
        }
        changed |= tokeniseStringArray(data, "new_images", "image");
        changed |= tokeniseStringArray(data, "newImages", "image");
        if (topic != null && topic.startsWith("job.") && data.has("result")) {
            data.remove("result");
            data.addProperty("result_available", true);
            changed = true;
        }
        return changed;
    }

    private boolean replaceEventString(JsonObject data, String key, String prefix) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) return false;
        String original = value.getAsString();
        if (original.isEmpty()) return false;
        data.addProperty(key, pseudonymisationFilter.pathTokenMap()
                .tokenForSensitiveText(original, prefix));
        return true;
    }

    private boolean tokeniseStringArray(JsonObject data, String key, String prefix) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonArray()) return false;
        boolean changed = false;
        JsonArray array = value.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (item != null && item.isJsonPrimitive()
                    && item.getAsJsonPrimitive().isString()
                    && !item.getAsString().isEmpty()) {
                array.set(i, new JsonPrimitive(pseudonymisationFilter.pathTokenMap()
                        .tokenForSensitiveText(item.getAsString(), prefix)));
                changed = true;
            }
        }
        return changed;
    }

    private static String safeEventTopic(String topic) {
        String value = topic == null ? "" : topic;
        if ("heartbeat".equals(value) || "subscribed".equals(value)
                || "event_dropped".equals(value)) return value;
        String[] prefixes = {
                "image.", "job.", "dialog.", "macro.", "results.",
                "memory.", "safe_mode.", "gui_action.",
                "data_governance.", "reactive."
        };
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)
                    && value.matches("[a-z0-9_.-]{1,128}")) return value;
        }
        return value.isEmpty() ? "event" : "custom";
    }

    private void appendSubscriptionAudit(String command, JsonObject request,
                                         AgentCaps caps, List<String> patterns,
                                         String reason, int bytesIn,
                                         long frames, long dropped) {
        try {
            PrivacyPosture posture = PostureController.getInstance().current();
            List<String> fields = posture == PrivacyPosture.STANDARD
                    ? Collections.<String>emptyList()
                    : Collections.singletonList("stream_payload");
            StringBuilder notes = new StringBuilder();
            notes.append("topics=");
            for (int i = 0; i < patterns.size(); i++) {
                if (i > 0) notes.append(',');
                notes.append(safeAuditTopic(patterns.get(i)));
            }
            notes.append(" reason=").append(scrubAuditTokenNote(reason));
            notes.append(" frames=").append(Math.max(0L, frames));
            notes.append(" dropped=").append(Math.max(0L, dropped));
            auditLog.append(new AuditRow(
                    java.time.Instant.now(),
                    sessionAuditPseudonym(caps == null ? "" : caps.sessionId),
                    command,
                    posture,
                    "",
                    "",
                    0,
                    Math.max(0, bytesIn),
                    "",
                    posture != PrivacyPosture.STANDARD,
                    fields,
                    truncateAuditNote(notes.toString()),
                    ""));
        } catch (Throwable t) {
            System.err.println("[ImageJAI-Audit] subscription row failed: "
                    + t.getMessage());
        }
    }

    private static String sessionAuditPseudonym(String sessionId) {
        String value = sessionId == null ? "" : sessionId;
        if (value.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("session-");
            for (byte b : digest) {
                int v = b & 0xff;
                if (v < 16) out.append('0');
                out.append(Integer.toHexString(v));
                if (out.length() >= 24) break;
            }
            return out.toString();
        } catch (Exception impossible) {
            return "session-redacted";
        }
    }

    private static String safeAuditTopic(String pattern) {
        if ("*".equals(pattern)) return "*";
        String value = pattern == null ? "" : pattern;
        String[] prefixes = {
                "image.", "job.", "dialog.", "macro.", "results.",
                "memory.", "safe_mode.", "gui_action.",
                "data_governance.", "reactive."
        };
        for (String prefix : prefixes) {
            if (value.startsWith(prefix) && validTopicPattern(value)) return value;
        }
        return value.isEmpty() ? "custom" : "custom-" + sessionAuditPseudonym(value);
    }

    /** Write a JSON frame followed by '\n' to the raw output stream. */
    private void writeFrame(OutputStream out, JsonObject frame) throws IOException {
        byte[] bytes = (GSON.toJson(frame) + "\n").getBytes(UTF8);
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
        String topic = optString(frame, "event", "");
        String identity = EventBus.coalescingKey(frame);
        OutboundEvent.publishStream(topic, identity, bytes.length);
    }

    /** Best-effort raw JSON write — swallows IO errors. Used for rejection frames. */
    private void writeRawJson(OutputStream out, JsonObject obj) {
        try {
            writeFrame(out, obj);
        } catch (IOException ignore) {}
    }

    static final class RequestLine {
        final String text;
        final int byteCount;
        RequestLine(String text, int byteCount) {
            this.text = text;
            this.byteCount = byteCount;
        }
    }

    static final class RequestTooLargeException extends IOException {
        RequestTooLargeException() { super("request byte limit exceeded"); }
    }

    /** Package-private exact UTF-8 request reader used by boundary tests. */
    static RequestLine readUtf8Line(InputStream input, int maxBytes)
            throws IOException, CharacterCodingException {
        if (input == null) throw new IOException("input is required");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                Math.min(8192, maxBytes));
        int payloadBytes = 0;
        boolean sawAny = false;
        boolean pendingCarriageReturn = false;
        int next;
        while ((next = input.read()) != -1) {
            sawAny = true;
            if (next == '\n') {
                // A CR immediately before LF is framing, not payload.
                pendingCarriageReturn = false;
                break;
            }
            if (pendingCarriageReturn) {
                payloadBytes++;
                if (payloadBytes > maxBytes) throw new RequestTooLargeException();
                bytes.write('\r');
                pendingCarriageReturn = false;
            }
            if (next == '\r') {
                pendingCarriageReturn = true;
            } else {
                payloadBytes++;
                if (payloadBytes > maxBytes) throw new RequestTooLargeException();
                bytes.write(next);
            }
        }
        // A final CR at EOF is also line framing, matching readLine().
        if (!sawAny && bytes.size() == 0) return null;
        String decoded = UTF8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        return new RequestLine(decoded, payloadBytes);
    }

    // -----------------------------------------------------------------------
    // Command dispatch
    // -----------------------------------------------------------------------

    JsonObject dispatch(JsonObject request, AgentCaps caps) {
        return dispatchInternal(request, caps == null ? DEFAULT_CAPS : caps, null);
    }

    private JsonObject dispatch(JsonObject request, Socket sock) {
        if (sock == null) {
            return dispatchInternal(request, DEFAULT_CAPS, null);
        }
        String command = optString(request, "command", "");
        if ("hello".equals(command)) {
            return dispatchInternal(request, DEFAULT_CAPS, sock);
        }
        // ping intentionally remains a public liveness probe. It never
        // exposes image state or negotiated capabilities.
        if ("ping".equals(command) && !request.has("session_id")) {
            request.remove("token");
            return dispatchInternal(request, LEGACY_CAPS, sock);
        }

        String sessionId = optString(request, "session_id", "");
        String token = optString(request, "token", null);
        SessionCapsRegistry.Lookup<AgentCaps> lookup =
                sessionRegistry.lookup(sessionId, token);
        request.remove("token");
        if (lookup.status() == SessionCapsRegistry.Status.VALID) {
            return dispatchInternal(request, lookup.caps(), sock);
        }
        if (lookup.status() == SessionCapsRegistry.Status.MISSING
                && !tokenAuthRequired()) {
            return dispatchInternal(request, LEGACY_CAPS, sock);
        }
        return sessionFailure(lookup.status());
    }

    private JsonObject dispatchInternal(JsonObject request, AgentCaps caps, Socket sock) {
        final int requestBytes = jsonBytes(request);
        JsonElement cmdElement = request.get("command");
        if (cmdElement == null || !cmdElement.isJsonPrimitive()) {
            return errorResponse("Missing 'command' field");
        }
        String command = cmdElement.getAsString();

        if (listener != null) {
            listener.onCommandReceived(command);
        }

        if (capsWitnessForTest != null) {
            capsWitnessForTest.add(caps);
        }

        // Inbound reverse-resolution: outbound responses tokenise window-title
        // paths (image-XXXX.lif - SCN), so a macro the agent builds from that
        // title must have the token turned back into the real title before it
        // reaches Fiji — otherwise selectWindow() never matches an open window.
        // Scoped to code/title-bearing execution commands and gated on posture
        // inside the filter; a no-op in Standard mode. The reversed value stays
        // in the JVM and is re-tokenised on the way out by the filter.
        pseudonymisationFilter.deTokeniseRequest(request, command,
                PostureController.getInstance().current());

        JsonObject response = dispatchCore(command, request, caps, sock);

        if (response != null && caps != null && caps.compatibility) {
            JsonObject compatibility = new JsonObject();
            compatibility.addProperty("mode", "legacy_restricted");
            compatibility.addProperty("safe_mode", true);
            response.add("_session", compatibility);
        }

        // Phase 7: surface a handler-attached gui_action piggyback into the
        // outer response. Handlers may set result.gui_action = {...}; this
        // path lifts it to top-level so external clients can react without
        // knowing whether the field came from the handler or the dispatcher.
        // For v1 only the dedicated gui_action command writes a top-level
        // entry directly; this hook is infrastructure future phases can tap.
        if (response != null) {
            promoteGuiActionPiggyback(response);
        }

        PrivacyPosture privacyPosture = PostureController.getInstance().current();
        RedactionReport redactionReport = RedactionReport.passthrough(command);
        if (response != null) {
            redactionReport = pseudonymisationFilter.apply(response, command, privacyPosture,
                    sessionKey(caps, sock));
        }

        // Step 11: automatic per-socket response dedup for read-only polls.
        // Runs BEFORE the legacy if_none_match hash layer so that if the body
        // hasn't changed since this socket last fetched it, we short-circuit
        // with {"ok":true,"unchanged":true,"since":ts,"ageMs":N} and skip
        // the hash-attach path entirely. Per plan: docs/tcp_upgrade/11_dedup_response.md.
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

        if (response != null && dedupShortCircuited) {
            pseudonymisationFilter.attachGovernanceIfNeeded(response, command,
                    privacyPosture);
        }

        // Phase 1 readonly fast-path: hash successful readonly results and
        // short-circuit repeat callers that supply a matching if_none_match.
        // Skipped when Step 11 already returned a short-form envelope (no
        // {@code result} field to hash).
        if (response != null
                && !dedupShortCircuited
                && READONLY_COMMANDS.contains(command)) {
            response = applyReadonlyDedup(request, response);
            pseudonymisationFilter.attachGovernanceIfNeeded(response, command,
                    privacyPosture);
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

        if (response != null) {
            appendAuditRow(command, request, response, caps, sock, privacyPosture,
                    redactionReport, requestBytes);
        }

        return response;
    }

    private void appendAuditRow(String command,
                                JsonObject request,
                                JsonObject response,
                                AgentCaps caps,
                                Socket sock,
                                PrivacyPosture privacyPosture,
                                RedactionReport report,
                                int requestBytes) {
        try {
            if ("browse_pending_brief".equals(command) && !responsePending(response)) {
                return;
            }
            RedactionReport effectiveReport = report == null
                    ? RedactionReport.passthrough(command)
                    : report;
            PrivacyPosture rowPosture = effectiveReport.posture() == null
                    ? privacyPosture
                    : effectiveReport.posture();
            if (rowPosture == null) {
                rowPosture = PrivacyPosture.defaultPosture();
            }
            List<String> fields = new ArrayList<String>(
                    effectiveReport.fieldsPseudonymised());
            boolean redactionApplied = effectiveReport.failed()
                    || !fields.isEmpty()
                    || (rowPosture != PrivacyPosture.STANDARD
                    && response != null && response.has("_governance"));
            String sessionId = auditSessionId(request, caps, sock);
            auditLog.append(new AuditRow(
                    java.time.Instant.now(),
                    sessionId,
                    command,
                    rowPosture,
                    auditModelEndpoint(request, caps),
                    auditCaptureSource(command, response),
                    jsonBytes(response),
                    requestBytes,
                    currentImageHash(),
                    redactionApplied,
                    fields,
                    auditNotes(command, request, response, effectiveReport),
                    receiptPayload(command, response, rowPosture,
                            sessionKey(caps, sock))));
        } catch (Throwable t) {
            // Audit logging is mandatory in design but best-effort in the hot
            // response path: a CSV fault must not corrupt the TCP protocol.
            System.err.println("[ImageJAI-Audit] row build failed: " + t.getMessage());
        }
    }

    private String receiptPayload(String command, JsonObject response,
                                  PrivacyPosture posture, String sessionId) {
        if (response == null) {
            return "";
        }
        try {
            JsonObject copy = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();
            PrivacyPosture receiptPosture = posture == PrivacyPosture.STANDARD
                    ? PrivacyPosture.PSEUDONYMISED
                    : posture;
            if (receiptPosture != null && receiptPosture != PrivacyPosture.STANDARD) {
                if (!copy.has("_governance")) {
                    pseudonymisationFilter.apply(copy, command, receiptPosture, sessionId);
                }
                pseudonymisationFilter.attachGovernanceIfNeeded(copy, command, receiptPosture);
            }
            return AuditRow.capRedactedPayload(GSON.toJson(copy));
        } catch (Throwable t) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("command", command == null ? "" : command);
            fallback.addProperty("receipt_error", "redacted_payload_unavailable");
            return AuditRow.capRedactedPayload(fallback.toString());
        }
    }

    private void writeOutbound(PrintWriter writer, String command, String payload) {
        String body = payload == null ? "" : payload;
        writer.println(body);
        writer.flush();
        int newlineBytes = System.lineSeparator().getBytes(UTF8).length;
        OutboundEvent.publish(command, body.getBytes(UTF8).length + newlineBytes);
    }

    private String auditSessionId(JsonObject request, AgentCaps caps, Socket sock) {
        String fromRequest = optString(request, "session_id", "");
        if (!fromRequest.trim().isEmpty()) {
            return auditToken(fromRequest);
        }
        if (caps != null && caps.sessionId != null && !caps.sessionId.trim().isEmpty()) {
            return auditToken(caps.sessionId);
        }
        return auditToken(sessionKey(caps, sock));
    }

    private String auditModelEndpoint(JsonObject request, AgentCaps caps) {
        String endpoint = optString(request, "model_endpoint", "");
        if (endpoint.trim().isEmpty() && caps != null) {
            endpoint = caps.modelEndpoint == null ? "" : caps.modelEndpoint;
        }
        if (endpoint.trim().isEmpty() && caps != null) {
            endpoint = endpointForAgent(caps.agent);
        }
        return endpoint.trim();
    }

    private static String endpointForAgent(String agent) {
        String raw = agent == null ? "" : agent.trim();
        if (raw.isEmpty() || "unknown".equalsIgnoreCase(raw)) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("claude")) {
            return "anthropic.claude-code";
        }
        if (lower.contains("codex")) {
            return "openai.codex";
        }
        if (lower.contains("gemini")) {
            return "google.gemini-cli";
        }
        if (lower.contains("gemma") || lower.contains("ollama")) {
            return "ollama.local:" + raw;
        }
        return raw;
    }

    private static String auditCaptureSource(String command, JsonObject response) {
        if (!"capture_image".equals(command)) {
            return "";
        }
        JsonObject sourceObject = response;
        JsonElement result = response == null ? null : response.get("result");
        if (result != null && result.isJsonObject()) {
            sourceObject = result.getAsJsonObject();
        }
        String source = optString(sourceObject, "source", "");
        return CaptureSource.from(source).name().toLowerCase(Locale.ROOT);
    }

    private String auditNotes(String command, JsonObject request, JsonObject response,
                              RedactionReport report) {
        StringBuilder notes = new StringBuilder();
        if ("request_visual".equals(command)) {
            appendNote(notes, "reason", optString(request, "reason", ""));
        } else if ("capture_image".equals(command)) {
            appendNote(notes, "source", auditCaptureSource(command, response));
        } else if ("open_image_by_token".equals(command)) {
            String token = auditOpenTarget(request);
            if (!token.isEmpty()) {
                appendNote(notes, "token", token);
            }
        } else if ("open_image".equals(command)) {
            String target = auditOpenTarget(request);
            if (target.matches("(?i)image-[0-9a-f]{4,32}.*")) {
                appendNote(notes, "token", target);
            }
        } else if ("get_pending_brief".equals(command)) {
            String tokens = responseTokens(response);
            if (!tokens.isEmpty()) {
                appendNote(notes, "tokens", tokens);
            }
            String tag = responseTag(response);
            if (!tag.isEmpty()) {
                appendNote(notes, "tag", tag);
            }
        } else if ("browse_pending_brief".equals(command) && responsePending(response)) {
            appendNote(notes, "pending", "true");
        }
        if (report != null && report.failed()) {
            appendNote(notes, "redaction", "failed_closed");
        }
        if (response != null && response.has("error")) {
            appendNote(notes, "error", String.valueOf(response.get("error")));
        }
        return truncateAuditNote(notes.toString());
    }

    private static String auditOpenTarget(JsonObject request) {
        if (request == null) {
            return "";
        }
        String[] keys = {"token", "image_token", "path", "file"};
        for (String key : keys) {
            String value = optString(request, key, "");
            if (!value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean responsePending(JsonObject response) {
        JsonObject result = responseResultObject(response);
        return result != null && result.has("pending")
                && result.get("pending").isJsonPrimitive()
                && result.get("pending").getAsBoolean();
    }

    private static String responseTokens(JsonObject response) {
        JsonObject result = responseResultObject(response);
        if (result == null || !result.has("tokens")
                || !result.get("tokens").isJsonArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        JsonArray tokens = result.getAsJsonArray("tokens");
        for (JsonElement token : tokens) {
            if (token == null || !token.isJsonPrimitive()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(token.getAsString());
        }
        return out.toString();
    }

    private static String responseTag(JsonObject response) {
        JsonObject result = responseResultObject(response);
        return result == null ? "" : optString(result, "tag", "");
    }

    private static JsonObject responseResultObject(JsonObject response) {
        if (response == null) {
            return null;
        }
        JsonElement result = response.get("result");
        return result != null && result.isJsonObject()
                ? result.getAsJsonObject()
                : response;
    }

    private void appendNote(StringBuilder notes, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (notes.length() > 0) {
            notes.append(' ');
        }
        String cleaned = ("token".equals(key) || "tokens".equals(key))
                ? scrubAuditTokenNote(value)
                : scrubAuditNote(value);
        notes.append(key).append('=').append('\'')
                .append(cleaned).append('\'');
    }

    private static String scrubAuditTokenNote(String value) {
        String raw = value == null ? "" : value.trim();
        return raw.replaceAll("[^A-Za-z0-9_.:,-]+", "_");
    }

    private String scrubAuditNote(String value) {
        String scrubbed = PseudonymisationFilter.getInstance().freeTextScrubString(
                value == null ? "" : value);
        return scrubbed.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String truncateAuditNote(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String auditToken(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            return "";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }

    private static int jsonBytes(JsonObject object) {
        return object == null
                ? 0
                : object.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private String currentImageHash() {
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            if (image == null || image.getProcessor() == null) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, image.getWidth() + "x" + image.getHeight()
                    + "x" + image.getBitDepth() + ";");
            Object pixels = image.getProcessor().getPixels();
            if (pixels instanceof byte[]) {
                digest.update((byte[]) pixels);
            } else if (pixels instanceof short[]) {
                for (short value : (short[]) pixels) {
                    digest.update((byte) ((value >>> 8) & 0xff));
                    digest.update((byte) (value & 0xff));
                }
            } else if (pixels instanceof int[]) {
                for (int value : (int[]) pixels) {
                    digest.update((byte) ((value >>> 24) & 0xff));
                    digest.update((byte) ((value >>> 16) & 0xff));
                    digest.update((byte) ((value >>> 8) & 0xff));
                    digest.update((byte) (value & 0xff));
                }
            } else if (pixels instanceof float[]) {
                for (float value : (float[]) pixels) {
                    int bits = Float.floatToIntBits(value);
                    digest.update((byte) ((bits >>> 24) & 0xff));
                    digest.update((byte) ((bits >>> 16) & 0xff));
                    digest.update((byte) ((bits >>> 8) & 0xff));
                    digest.update((byte) (bits & 0xff));
                }
            } else {
                updateDigest(digest, String.valueOf(image.getStatistics().mean));
            }
            return toHex(digest.digest());
        } catch (Throwable t) {
            return "";
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 0xff;
            if (value < 0x10) {
                out.append('0');
            }
            out.append(Integer.toHexString(value));
        }
        return out.toString();
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
        // A deliberately enabled compatibility session is read-only. Keep
        // this as a whitelist so new mutating or host-code commands fail
        // closed until they are explicitly classified and authenticated.
        if (sock != null
                && caps != null
                && caps.compatibility
                && !"hello".equals(command)
                && !"ping".equals(command)
                && !READONLY_COMMANDS.contains(command)) {
            return protocolError("compatibility_read_only",
                    "This command requires an authenticated protocol session.");
        }

        // Token auth gate. The env-var/system-property switch is default-on.
        // Only hello and ping are allowed before authentication; every other
        // handler refuses with a structured auth_required error so the client
        // can see how to fix it.
        if (tokenAuthRequired()
                && sock != null
                && !"hello".equals(command)
                && !"ping".equals(command)
                && !caps.authenticated) {
            return errorResponse("auth_required: call hello with a valid token first. "
                    + "Read the token from " + tokenFilePath().toString() + ".");
        }

        if ("hello".equals(command)) {
            return handleHello(request, sock);
        } else if ("ping".equals(command)) {
            return handlePing();
        } else if ("emit_methods_table".equals(command)) {
            return handleEmitMethodsTable(request, caps);
        } else if ("execute_macro".equals(command)) {
            return handleExecuteMacro(request, caps);
        } else if ("get_state".equals(command)) {
            return handleGetState();
        } else if ("get_image_info".equals(command)) {
            return handleGetImageInfo();
        } else if ("get_results_table".equals(command)) {
            return handleGetResultsTable();
        } else if ("capture_image".equals(command)) {
            return handleCaptureImage(request, caps, sock);
        } else if ("request_visual".equals(command)) {
            return handleRequestVisual(request, caps, sock);
        } else if ("open_image".equals(command)) {
            return handleOpenImage(request, false);
        } else if ("open_image_by_token".equals(command)) {
            return handleOpenImage(request, true);
        } else if ("browse_pending_brief".equals(command)) {
            return handleBrowsePendingBrief(request, caps, sock);
        } else if ("get_pending_brief".equals(command)) {
            return handleGetPendingBrief(request, caps, sock);
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
            if (clearFrictionLogAllowed()) {
                return handleClearFrictionLog();
            }
            return errorResponse(
                    "clear_friction_log is no longer agent-callable. "
                            + "Restart Fiji with -Dimagejai.allow.clear_friction_log=true if you need it.");
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
            return handleJobStatus(request, caps);
        } else if ("job_cancel".equals(command)) {
            return handleJobCancel(request, caps);
        } else if ("job_list".equals(command)) {
            return handleJobList(caps);
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
        // Validate before parsing or storing any caller-controlled caps. A
        // supplied-but-wrong token is always an error; it never degrades to a
        // compatibility session. The null-token-server case exists only for
        // direct headless unit tests because start() initializes the token
        // before opening the listen socket.
        String supplied = optString(request, "token", null);
        request.remove("token"); // credentials must never reach audit payloads
        if (supplied != null && serverToken != null
                && !tokensEqual(serverToken, supplied)) {
            return protocolError("invalid_token", "The installation token is invalid.");
        }
        if (serverToken == null || (supplied != null
                && tokensEqual(serverToken, supplied))) {
            c.authenticated = true;
        }
        if (tokenAuthRequired() && !c.authenticated) {
            return protocolError("auth_required",
                    "hello requires the installation token.");
        }
        c.compatibility = !c.authenticated;
        c.agent = optString(request, "agent", "unknown");
        JsonObject caps = (request.has("capabilities")
                && request.get("capabilities").isJsonObject())
                ? request.getAsJsonObject("capabilities")
                : new JsonObject();
        c.sessionId = optString(request, "session_id",
                optString(caps, "session_id", ""));
        c.clientSessionId = optString(request, "client_session_id", "");
        c.modelEndpoint = optString(request, "model_endpoint",
                optString(caps, "model_endpoint", ""));
        c.vision       = optBool(caps, "vision", false);
        c.outputFormat = optString(caps, "output_format", "json");
        JsonObject helloFieldError = validateHelloField(
                "agent", c.agent, MAX_HANDSHAKE_IDENTITY_CHARS);
        if (helloFieldError != null) return helloFieldError;
        helloFieldError = validateHelloField(
                "session_id", c.sessionId, MAX_HANDSHAKE_IDENTITY_CHARS);
        if (helloFieldError != null) return helloFieldError;
        helloFieldError = validateHelloField(
                "client_session_id", c.clientSessionId,
                MAX_HANDSHAKE_IDENTITY_CHARS);
        if (helloFieldError != null) return helloFieldError;
        helloFieldError = validateHelloField(
                "model_endpoint", c.modelEndpoint, MAX_HANDSHAKE_IDENTITY_CHARS);
        if (helloFieldError != null) return helloFieldError;
        helloFieldError = validateHelloField(
                "output_format", c.outputFormat, MAX_HANDSHAKE_OUTPUT_FORMAT_CHARS);
        if (helloFieldError != null) return helloFieldError;
        c.tokenBudget  = optInt(caps, "token_budget", Integer.MAX_VALUE);
        c.verbose      = optBool(caps, "verbose", false);
        // Step 05: pulse / state_delta default ON for clients that said hello
        // (docs/tcp_upgrade/05_state_delta_and_pulse.md). The Claude Code
        // wrapper sets pulse=false explicitly because its SessionStart hook
        // already injects the state a pulse string would carry.
        c.pulse        = optBool(caps, "pulse", true);
        c.stateDelta   = optBool(caps, "state_delta", true);
        // Handshake default ON. AgentCaps itself stays false so DEFAULT_CAPS
        // preserves the no-handshake legacy path.
        c.safeMode     = optBool(caps, "safe_mode", true);
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
        if (c.compatibility) {
            // Compatibility sessions expose only the dispatcher's explicit
            // read-only whitelist and refuse privileged capability opt-ins.
            c.vision = false;
            c.safeMode = true;
            c.autoDismissPhantoms = false;
            c.undo = false;
            c.acceptEvents = Collections.emptySet();
            c.pulse = false;
            c.dedup = false;
            c.patternHints = false;
            c.graphDelta = false;
            c.ledger = false;
        }
        int sockPort = (sock != null) ? sock.getPort() : 0;
        c.agentId = optString(caps, "agent_id",
                !c.clientSessionId.isEmpty()
                        ? c.clientSessionId : c.agent + "-" + sockPort);
        helloFieldError = validateHelloField(
                "agent_id", c.agentId, MAX_HANDSHAKE_IDENTITY_CHARS);
        if (helloFieldError != null) return helloFieldError;
        if (!c.compatibility) {
            try {
                c.acceptEvents = parseStringSet(caps, "accept_events");
            } catch (IllegalArgumentException malformedEvents) {
                return protocolError("invalid_hello", malformedEvents.getMessage());
            }
        }

        SessionCapsRegistry.Created<AgentCaps> created;
        try {
            created = sessionRegistry.create(c, c.authenticated ? supplied : null);
        } catch (SessionCapsRegistry.CapacityException e) {
            return protocolError("session_capacity",
                    "The server has reached its active session limit.");
        } catch (IllegalStateException e) {
            return protocolError("server_stopping",
                    "The server is not accepting new sessions.");
        }
        c.sessionId = created.id();

        JsonObject result = new JsonObject();
        result.addProperty("server_version", SERVER_VERSION);
        result.addProperty("session_id", created.id());
        result.addProperty("expires_at", created.expiresAtEpochMillis());
        JsonArray enabled = enabledCapsFor(c);
        result.add("enabled", enabled);
        result.add("capabilities", enabled.deepCopy());
        result.addProperty("compatibility", c.compatibility);
        result.addProperty("server_time_ms", System.currentTimeMillis());
        return successResponse(result);
    }

    private String sessionKey(AgentCaps caps, Socket sock) {
        if (caps != null && caps.sessionId != null && !caps.sessionId.trim().isEmpty()) {
            return caps.sessionId.trim();
        }
        if (caps != null && caps.agentId != null && !caps.agentId.trim().isEmpty()) {
            return caps.agentId.trim();
        }
        if (caps != null && caps.agent != null && !"unknown".equals(caps.agent)) {
            return caps.agent;
        }
        return sock == null ? "default" : "socket-" + sock.getPort();
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
        if (caps != null && caps.safeMode) {
            arr.add(new JsonPrimitive("safe_mode"));
            for (String name : enabledSafeModeOptions(caps)) {
                arr.add(new JsonPrimitive(name));
            }
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
        return arr;
    }

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
        if (el == null || el.isJsonNull()) return result;
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array of strings");
        }
        if (el.getAsJsonArray().size() > MAX_ACCEPT_EVENT_TOPICS) {
            throw new IllegalArgumentException(key + " exceeds "
                    + MAX_ACCEPT_EVENT_TOPICS + " topics");
        }
        for (JsonElement item : el.getAsJsonArray()) {
            if (item == null || !item.isJsonPrimitive()
                    || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(key + " must contain only strings");
            }
            String topic = item.getAsString();
            if (topic.length() == 0 || topic.length() > MAX_ACCEPT_EVENT_TOPIC_CHARS) {
                throw new IllegalArgumentException(key + " topic length must be 1.."
                        + MAX_ACCEPT_EVENT_TOPIC_CHARS);
            }
            result.add(topic);
        }
        return result;
    }

    private JsonObject validateHelloField(String field, String value, int maxChars) {
        if (value != null && value.length() > maxChars) {
            return protocolError("invalid_hello", field + " exceeds "
                    + maxChars + " characters");
        }
        return null;
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
        out.addProperty("combined",
                AgentContextSanitizer.wrap(combineConsoleStreams(stdout, stderr), "CONSOLE"));
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

        final String rewindImageTitle = imageTitle;
        final String rewindToCallId = toCallId;
        final int rewindCount = n;
        Future<JsonObject> rewindFuture;
        try {
            MutationCoordinator.Handle<JsonObject> handle = mutationCoordinator.submit(
                    MutationCoordinator.Request.<JsonObject>builder()
                            .ownerSession(mutationOwnerOrInternal(caps))
                            .sourceKind("rewind")
                            .code(request.toString())
                            .timeoutMs(resolveTimeoutMs(request, MACRO_TIMEOUT_MS))
                            .operation(new MutationCoordinator.Operation<JsonObject>() {
                                @Override public JsonObject run() {
        String imageTitle = rewindImageTitle;
        String toCallId = rewindToCallId;
        int n = rewindCount;
        List<UndoFrame> popped;
        final UndoRestoreSummary undoRestore = new UndoRestoreSummary();
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
            try {
                popped = sessionUndo.rewindByCallIdAtomic(resolvedTitle, toCallId,
                        target -> restoreUndoFrameAtomically(target, undoRestore));
            } catch (IllegalArgumentException e) {
                return undoRestoreError(e, caps);
            } catch (Exception e) {
                return undoErrorResponse("UNDO_RESTORE_FAILED",
                        "Restore failed: " + String.valueOf(e.getMessage()), caps);
            }
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
            try {
                popped = sessionUndo.rewindByCountAtomic(imageTitle, n,
                        target -> restoreUndoFrameAtomically(target, undoRestore));
            } catch (IllegalArgumentException e) {
                return undoRestoreError(e, caps);
            } catch (Exception e) {
                return undoErrorResponse("UNDO_RESTORE_FAILED",
                        "Restore failed: " + String.valueOf(e.getMessage()), caps);
            }
            if (popped.isEmpty()) {
                return undoErrorResponse("UNDO_NOT_FOUND",
                        "No undo frames on stack for image '" + imageTitle
                      + "'.", caps);
            }
        }

        // The frame we restore from is the LAST one popped. Earlier ones
        // are intermediate states the agent walked past — discarded.
        UndoFrame target = popped.get(popped.size() - 1);
        boolean restored = undoRestore.applied;
        int restoredSlices = undoRestore.restoredPlanes;
        String restoreError = null;
        String restoreErrorCode = null;
        if (!undoRestore.applied) try {
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
        int restoredRois = undoRestore.restoredRois;
        if (!undoRestore.applied) try {
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
        int restoredResultsRows = undoRestore.restoredResultsRows;
        if (!undoRestore.applied) try {
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
                                }
                            })
                            .build());
            rewindFuture = new CoordinatorFuture<JsonObject>(handle);
        } catch (IllegalArgumentException
                 | java.util.concurrent.RejectedExecutionException e) {
            return errorResponse("Mutation admission rejected: " + e.getMessage());
        }
        try {
            return rewindFuture.get();
        } catch (InterruptedException e) {
            rewindFuture.cancel(true);
            awaitFutureTerminal(rewindFuture);
            Thread.currentThread().interrupt();
            return errorResponse("Rewind interrupted");
        } catch (ExecutionException e) {
            Throwable failure = e.getCause();
            return errorResponse("Rewind failed: "
                    + (failure == null || failure.getMessage() == null
                            ? "unknown error" : failure.getMessage()));
        }
    }

    private int remainingFramesFor(String imageTitle) {
        if (imageTitle == null) return 0;
        SessionUndo.Branch active =
                sessionUndo.getBranch(sessionUndo.activeBranchId());
        if (active == null) return 0;
        UndoStack s = active.byImageTitle.get(imageTitle);
        return s == null ? 0 : s.size();
    }

    private static final class UndoRestoreSummary {
        boolean applied;
        int restoredPlanes;
        int restoredRois;
        int restoredResultsRows;
    }

    private void restoreUndoFrameAtomically(UndoFrame target,
                                            UndoRestoreSummary summary) throws Exception {
        ImagePlus image = resolveUndoTarget(target);
        if (image == null) {
            throw new IllegalArgumentException(
                    "target image is closed: " + target.imageTitle);
        }
        UndoFrame.RestorePlan targetPlan = target.prepareRestore(image);
        String currentCsv = boundedExactResultsCsvForUndo();
        UndoFrame rollbackFrame = UndoFrame.capture(
                "rollback-" + target.callId, image, RoiManager.getRawInstance(),
                currentCsv, false);
        if (rollbackFrame == null) {
            throw new IllegalArgumentException("could not capture rollback snapshot");
        }
        UndoFrame.RestorePlan rollbackPlan = rollbackFrame.prepareRestore(image);
        try {
            int planes = targetPlan.applyPixelsAndCalibration();
            int rois = targetPlan.applySideState();
            summary.restoredPlanes = planes;
            summary.restoredRois = rois;
            summary.restoredResultsRows = targetPlan.restoredResultsRows();
            summary.applied = true;
        } catch (Throwable failure) {
            try {
                rollbackPlan.applyPixelsAndCalibration();
                rollbackPlan.applySideState();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            if (failure instanceof Exception) throw (Exception) failure;
            throw new RuntimeException(failure);
        }
    }

    private ImagePlus resolveUndoTarget(UndoFrame target) {
        ImagePlus image = target.imageId == Integer.MIN_VALUE
                ? WindowManager.getImage(target.imageTitle)
                : WindowManager.getImage(target.imageId);
        if (image == null) {
            ImagePlus current = WindowManager.getCurrentImage();
            if (current != null && (target.imageId == Integer.MIN_VALUE
                    ? target.imageTitle.equals(current.getTitle())
                    : target.imageId == current.getID())) {
                image = current;
            }
        }
        if (image == null) image = target.capturedImageFallback();
        return image;
    }

    private JsonObject undoRestoreError(IllegalArgumentException error,
                                        AgentCaps caps) {
        String message = String.valueOf(error.getMessage());
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        String code = lower.contains("closed") ? "UNDO_IMAGE_CLOSED"
                : lower.contains("identity") || lower.contains("title")
                ? "UNDO_IDENTITY_MISMATCH"
                : lower.contains("type") ? "UNDO_TYPE_MISMATCH"
                : lower.contains("raw") || lower.contains("snapshot")
                ? "UNDO_SNAPSHOT_INVALID" : "UNDO_GEOMETRY_MISMATCH";
        return undoErrorResponse(code, message, caps);
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
        final String fromCallId = optString(request, "from_call_id", null);
        final ImagePlus currentHint = WindowManager.getCurrentImage();
        try {
            MutationCoordinator.Handle<JsonObject> handle = mutationCoordinator.submit(
                    MutationCoordinator.Request.<JsonObject>builder()
                            .ownerSession(mutationOwnerOrInternal(caps))
                            .sourceKind("branch")
                            .code(request.toString())
                            .timeoutMs(resolveTimeoutMs(request, MACRO_TIMEOUT_MS))
                            .operation(new MutationCoordinator.Operation<JsonObject>() {
                                @Override public JsonObject run() {
                                    String sourceId = sessionUndo.activeBranchId();
                                    List<UndoFrame> live;
                                    try {
                                        live = captureLiveBranchCheckpoint(sourceId, currentHint);
                                        sessionUndo.setBranchCheckpoint(sourceId, live);
                                    } catch (Exception e) {
                                        return undoErrorResponse("UNDO_SNAPSHOT_INVALID",
                                                "Could not capture branch checkpoint: "
                                                        + String.valueOf(e.getMessage()), caps);
                                    }
                                    SessionUndo.Branch fresh;
                                    try {
                                        fresh = sessionUndo.createBranch(fromCallId);
                                    } catch (IllegalArgumentException e) {
                                        return undoErrorResponse("UNDO_NOT_FOUND", e.getMessage(), caps);
                                    } catch (IllegalStateException e) {
                                        return undoErrorResponse("UNDO_BRANCH_CAP", e.getMessage(), caps);
                                    }
                                    try {
                                        if (fromCallId == null || fromCallId.isEmpty()) {
                                            sessionUndo.setBranchCheckpoint(fresh.id, live);
                                        } else {
                                            restoreBranchCheckpointAtomically(fresh.id);
                                        }
                                        if (!sessionUndo.switchBranch(fresh.id)) {
                                            throw new IllegalStateException(
                                                    "new branch disappeared before checkout");
                                        }
                                    } catch (Exception e) {
                                        sessionUndo.deleteBranch(fresh.id);
                                        return undoRestoreErrorForBranch(e, caps);
                                    }
                                    JsonObject result = new JsonObject();
                                    result.addProperty("branchId", fresh.id);
                                    result.addProperty("baseCallId",
                                            fresh.baseCallId == null ? "" : fresh.baseCallId);
                                    result.addProperty("activeBranch", sessionUndo.activeBranchId());
                                    result.addProperty("checkpointFrames",
                                            sessionUndo.branchCheckpoint(fresh.id).size());
                                    result.addProperty("totalBranches",
                                            sessionUndo.listBranches().size());
                                    return successResponse(result);
                                }
                            }).build());
            return awaitBranchMutation(handle, caps);
        } catch (IllegalArgumentException | java.util.concurrent.RejectedExecutionException e) {
            return errorResponse("Mutation admission rejected: " + e.getMessage());
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
            o.addProperty("checkpointFrames", b.checkpointFrames().size());
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
        final String id = optString(request, "branch_id", "");
        final ImagePlus currentHint = WindowManager.getCurrentImage();
        if (id == null || id.isEmpty()) {
            return undoErrorResponse("UNDO_BAD_REQUEST",
                    "branch_switch requires branch_id.", caps);
        }
        if (sessionUndo.getBranch(id) == null) {
            return undoErrorResponse("UNDO_NOT_FOUND",
                    "No branch with id '" + id + "'.", caps);
        }
        if (id.equals(sessionUndo.activeBranchId())) {
            JsonObject result = new JsonObject();
            result.addProperty("activeBranch", id);
            return successResponse(result);
        }
        try {
            MutationCoordinator.Handle<JsonObject> handle = mutationCoordinator.submit(
                    MutationCoordinator.Request.<JsonObject>builder()
                            .ownerSession(mutationOwnerOrInternal(caps))
                            .sourceKind("branch_switch")
                            .code(request.toString())
                            .timeoutMs(resolveTimeoutMs(request, MACRO_TIMEOUT_MS))
                            .operation(new MutationCoordinator.Operation<JsonObject>() {
                                @Override public JsonObject run() {
                                    String sourceId = sessionUndo.activeBranchId();
                                    try {
                                        List<UndoFrame> live = captureLiveBranchCheckpoint(
                                                sourceId, currentHint);
                                        sessionUndo.setBranchCheckpoint(sourceId, live);
                                        restoreBranchCheckpointAtomically(id);
                                        if (!sessionUndo.switchBranch(id)) {
                                            throw new IllegalStateException(
                                                    "branch disappeared before checkout");
                                        }
                                    } catch (Exception e) {
                                        return undoRestoreErrorForBranch(e, caps);
                                    }
                                    JsonObject result = new JsonObject();
                                    result.addProperty("activeBranch", sessionUndo.activeBranchId());
                                    result.addProperty("checkpointFrames",
                                            sessionUndo.branchCheckpoint(id).size());
                                    return successResponse(result);
                                }
                            }).build());
            return awaitBranchMutation(handle, caps);
        } catch (IllegalArgumentException | java.util.concurrent.RejectedExecutionException e) {
            return errorResponse("Mutation admission rejected: " + e.getMessage());
        }
    }

    private JsonObject awaitBranchMutation(MutationCoordinator.Handle<JsonObject> handle,
                                           AgentCaps caps) {
        try {
            MutationCoordinator.Completion<JsonObject> completion = handle.awaitCompletion();
            if (completion.state() == MutationCoordinator.State.SUCCEEDED
                    && completion.result() != null) {
                return completion.result();
            }
            Throwable error = completion.error();
            return undoErrorResponse("UNDO_RESTORE_FAILED",
                    error == null ? "Branch mutation did not complete"
                            : String.valueOf(error.getMessage()), caps);
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            return undoErrorResponse("UNDO_RESTORE_FAILED",
                    "Branch mutation interrupted", caps);
        }
    }

    private List<UndoFrame> captureLiveBranchCheckpoint(String branchId,
                                                        ImagePlus currentHint) {
        List<UndoFrame> frames = new ArrayList<UndoFrame>();
        java.util.HashSet<Integer> capturedIds = new java.util.HashSet<Integer>();
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus image = WindowManager.getImage(id);
                if (image != null && capturedIds.add(image.getID())) {
                    frames.add(captureBranchFrame(branchId, image));
                }
            }
        }
        ImagePlus current = WindowManager.getCurrentImage();
        if (current != null && capturedIds.add(current.getID())) {
            frames.add(captureBranchFrame(branchId, current));
        }
        if (currentHint != null && capturedIds.add(currentHint.getID())) {
            frames.add(captureBranchFrame(branchId, currentHint));
        }
        return frames;
    }

    private UndoFrame captureBranchFrame(String branchId, ImagePlus image) {
        String csv = boundedExactResultsCsvForUndo();
        UndoFrame frame = UndoFrame.capture(
                "checkpoint-" + branchId + "-" + nextCallId(), image,
                RoiManager.getRawInstance(), csv, false);
        if (frame == null) {
            throw new IllegalArgumentException("could not snapshot " + image.getTitle());
        }
        return frame;
    }

    private static final class BranchRestoreEntry {
        final UndoFrame.RestorePlan target;
        final UndoFrame.RestorePlan rollback;

        BranchRestoreEntry(UndoFrame.RestorePlan target,
                           UndoFrame.RestorePlan rollback) {
            this.target = target;
            this.rollback = rollback;
        }
    }

    private void restoreBranchCheckpointAtomically(String branchId) throws Exception {
        List<UndoFrame> checkpoint = sessionUndo.branchCheckpoint(branchId);
        List<BranchRestoreEntry> entries = new ArrayList<BranchRestoreEntry>();
        String csv = boundedExactResultsCsvForUndo();
        for (UndoFrame frame : checkpoint) {
            ImagePlus image = resolveUndoTarget(frame);
            if (image == null) {
                throw new IllegalArgumentException(
                        "target image is closed: " + frame.imageTitle);
            }
            UndoFrame.RestorePlan target = frame.prepareRestore(image);
            UndoFrame rollbackFrame = UndoFrame.capture(
                    "branch-rollback-" + nextCallId(), image,
                    RoiManager.getRawInstance(), csv, false);
            if (rollbackFrame == null) {
                throw new IllegalArgumentException("could not capture rollback snapshot");
            }
            entries.add(new BranchRestoreEntry(
                    target, rollbackFrame.prepareRestore(image)));
        }

        try {
            for (BranchRestoreEntry entry : entries) {
                entry.target.applyPixelsAndCalibration();
            }
            if (!entries.isEmpty()) entries.get(0).target.applySideState();
        } catch (Throwable failure) {
            for (BranchRestoreEntry entry : entries) {
                try {
                    entry.rollback.applyPixelsAndCalibration();
                } catch (Throwable rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (!entries.isEmpty()) {
                try {
                    entries.get(0).rollback.applySideState();
                } catch (Throwable rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (failure instanceof Exception) throw (Exception) failure;
            throw new RuntimeException(failure);
        }
    }

    private JsonObject undoRestoreErrorForBranch(Exception error, AgentCaps caps) {
        if (error instanceof IllegalArgumentException) {
            JsonObject response = undoRestoreError((IllegalArgumentException) error, caps);
            JsonObject result = response.getAsJsonObject("result");
            if (result != null) {
                result.addProperty("activeBranch", sessionUndo.activeBranchId());
            }
            return response;
        }
        return undoErrorResponse("UNDO_RESTORE_FAILED",
                "Branch checkout failed: " + String.valueOf(error.getMessage()), caps);
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
            String csv = stateInspector != null
                    ? boundedExactResultsCsvForUndo() : null;
            RoiManager rm = RoiManager.getRawInstance();
            boolean diskWrite = UndoFrame.macroHasDiskWrites(macroSrc);
            UndoFrame f = UndoFrame.capture(callId, imp, rm, csv, diskWrite);
            if (f != null) sessionUndo.pushFrame(f);
            return f;
        } catch (Throwable t) {
            try {
                IJ.log("[ImageJAI-Undo] capture failed; mutation rejected: "
                        + String.valueOf(t.getMessage()));
            } catch (Throwable ignore) {}
            throw new IllegalStateException(
                    "Undo snapshot failed; mutation was not started", t);
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
            if (!latch.await(5, TimeUnit.SECONDS)) {
                actionToken.invalidate();
                return errorResponse("Progress check timed out");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
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

        final String queueStormTarget = isQueueStormGuardEnabled(caps)
                ? resolveTargetImageTitleWithFallback(code)
                : null;
        if (queueStormTarget != null) {
            ActiveMacro inflight = inFlightByImage.get(queueStormTarget);
            if (inflight != null && inflight.state == MacroState.PAUSED_ON_DIALOG) {
                return queueStormBlockedReply(inflight, queueStormTarget, caps);
            }
        }

        final boolean safetyEnabled = isScientificIntegrityScanEnabled(caps);

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
        final List<DestructiveScanner.DestructiveOp> safetyFindings = safetyEnabled
                ? DestructiveScanner.scan(codeToRun, captureScannerContext(caps))
                : java.util.Collections.<DestructiveScanner.DestructiveOp>emptyList();
        final SessionCodeJournal.DatasetBinding journalDataset =
                SessionCodeJournal.captureInitiatingDataset();

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
        final long graphMarkerBefore = imageGraph.currentMarker();

        // Step 15: capture an undo frame BEFORE the macro mutates pixels,
        // when caps.undo is on for this socket. The frame holds compressed
        // pixels + ROI snapshot + Results CSV, addressable by callId so a
        // subsequent {@code rewind to_call_id} can restore precisely. Per
        // plan: docs/tcp_upgrade/15_undo_stack_api.md. Snapshot failures
        // are swallowed so undo never blocks the macro path.
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

        // Snapshot open identities + results-CSV length BEFORE IJ.runMacro so the
        // failure branch can tell "plugin actually produced output before the
        // dialog-pause" apart from "dialog-pause on the very first line,
        // nothing happened". Without this, mirroring the success-path snapshot
        // on failure would always report stale state as a side effect.
        long preResultsLen = 0L;
        final List<ImageGraph.ImageRef> preOpenImages =
                ImageGraph.captureOpenImages();
        try {
            StateInspector.BoundedCsv preCsv = stateInspector
                    .getResultsTableCSVBounded((int) MAX_RESULTS_TABLE_BYTES);
            preResultsLen = preCsv.originalBytes();
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
        MacroMutationContext mutationContext = null;
        // Step 15: announce we're about to start mutating so a concurrent
        // rewind (from another socket) returns UNDO_BUSY rather than
        // racing the in-flight macro. Decrement happens in the matching
        // finally below so an exception unwinds the counter cleanly.
        macroInFlight.incrementAndGet();
        try {
        // MutationCoordinator owns JVM-wide serialization and worker lifetime.
        {
        Future<String> future = null;
        try {
            mutationContext = submitTcpMacroMutation(code, codeToRun,
                    macroTimeoutMs, caps, safetyEnabled, safetyFindings,
                    graphMarkerBefore);
            future = mutationContext.future;

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
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof DestructiveMacroException) {
                        publishTcpMacroCompleted(macroId, false,
                                System.currentTimeMillis() - startTime,
                                cause.getMessage());
                        return destructiveBlockedReply(
                                ((DestructiveMacroException) cause).rejections, caps);
                    }
                    if (cause instanceof MutationTimedOutException) {
                        failureMessage = "Macro execution timed out after "
                                + macroTimeoutMs + "ms";
                    } else if (cause instanceof InterruptedException) {
                        failureMessage = "Macro execution interrupted";
                    } else {
                        String msg = cause != null ? cause.getMessage() : e.getMessage();
                        failureMessage = "Macro error: "
                                + (msg != null ? msg : "unknown error");
                    }
                    break;
                }
            }
        } catch (IllegalArgumentException
                 | java.util.concurrent.RejectedExecutionException e) {
            publishTcpMacroCompleted(macroId, false,
                    System.currentTimeMillis() - startTime, e.getMessage());
            return errorResponse("Mutation admission rejected: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (future != null && !future.isDone()) future.cancel(true);
            awaitFutureTerminal(future);
            publishTcpMacroCompleted(macroId, false,
                    System.currentTimeMillis() - startTime, "Interrupted");
            return errorResponse("Interrupted");
        } finally {
            // Do not inspect or report state until the coordinator has
            // observed the real worker exit.
            if (future != null && !future.isDone()) future.cancel(true);
            awaitFutureTerminal(future);
        }
        if (success) {
            dialogs = safeDetectOpenDialogs();
            String detected = detectIjMacroError(logLenBefore, priorInterpError, priorIjError, dialogs);
            if (detected != null) {
                success = false;
                failureMessage = detected;
            }
        }
        } // end coordinator-backed macro wait block
        } finally {
            // In-flight accounting unwinds after coordinator completion.
            macroInFlight.decrementAndGet();
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
                List<ImageGraph.ImageRef> opened = newImageRefs(
                        preOpenImages, ImageGraph.captureOpenImages());
                if (!opened.isEmpty()) {
                    delta.newImages = imageTitles(opened);
                }
                StateInspector.BoundedCsv csv = stateInspector
                        .getResultsTableCSVBounded((int) MAX_RESULTS_TABLE_BYTES);
                if (!csv.text().isEmpty()) {
                    delta.setResultsTable(csv);
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
                List<ImageGraph.ImageRef> opened = newImageRefs(
                        preOpenImages, ImageGraph.captureOpenImages());
                if (!opened.isEmpty()) {
                    JsonArray newImages = imageTitles(opened);
                    // Step 05: failure-path newImages routes through the delta
                    // struct so the grouped shape stays consistent with the
                    // success path. sideEffectsObj keeps its own copy — that
                    // lives inside the structured error payload (step 02) and
                    // is a separate contract from the top-level diff shape.
                    delta.newImages = newImages;
                    sideEffectsObj.add("newImages", imageTitles(opened));
                    sideEffectsLanded = true;
                }
                StateInspector.BoundedCsv csv = stateInspector
                        .getResultsTableCSVBounded((int) MAX_RESULTS_TABLE_BYTES);
                long postLen = csv.originalBytes();
                if (!csv.text().isEmpty() && postLen != preResultsLen) {
                    delta.setResultsTable(csv);
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
            SessionCodeJournal.INSTANCE.record(journalDataset, "ijm", code, source,
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
            ImageGraph.Delta gDelta = mutationContext == null
                    ? null : mutationContext.graphDelta.get();
            if (caps != null && caps.graphDelta
                    && gDelta != null && !gDelta.isEmpty()) {
                result.add("graphDelta", gDelta.toJson());
            }
        } catch (Throwable ignore) {}
        if (caps != null && caps.pulse) {
            result.addProperty("pulse", PulseBuilder.build());
        }
        return successResponse(result);
        } finally {
            if (recorderCapture != null) {
                try { recorderCapture.close(); } catch (Throwable ignore) {}
            }
            eventBus.popSuppress("image.*");
        }
    }

    private static boolean isQueueStormGuardEnabled(AgentCaps caps) {
        return caps != null
                && caps.safeMode
                && caps.safeModeOptions != null
                && caps.safeModeOptions.queueStormGuard;
    }

    private static boolean isScientificIntegrityScanEnabled(AgentCaps caps) {
        return caps != null
                && caps.safeMode
                && caps.safeModeOptions != null
                && caps.safeModeOptions.scientificIntegrityScan;
    }

    private String boundedExactResultsCsvForUndo() {
        if (stateInspector == null) return "";
        StateInspector.BoundedCsv csv = stateInspector
                .getResultsTableCSVBounded((int) MAX_RESULTS_TABLE_BYTES);
        if (csv.truncated()) {
            throw new IllegalStateException("Results table exceeds the exact undo "
                    + "snapshot limit of " + MAX_RESULTS_TABLE_BYTES + " bytes");
        }
        return csv.text();
    }

    private static final class DestructiveMacroException
            extends MutationCoordinator.SafetyException {
        final List<DestructiveScanner.DestructiveOp> rejections;

        DestructiveMacroException(List<DestructiveScanner.DestructiveOp> rejections) {
            super("Macro blocked by safe-mode scanner");
            this.rejections = rejections;
        }
    }

    private static final class MutationTimedOutException extends Exception {
        MutationTimedOutException(String message) { super(message); }
    }

    /** Future compatibility adapter used by the legacy dialog polling loop. */
    private static final class CoordinatorFuture<T> implements Future<T> {
        private final MutationCoordinator.Handle<T> handle;

        CoordinatorFuture(MutationCoordinator.Handle<T> handle) {
            this.handle = handle;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return handle.cancel();
        }

        @Override public boolean isCancelled() {
            MutationCoordinator.State state = handle.state();
            return state == MutationCoordinator.State.CANCELLED
                    || state == MutationCoordinator.State.TIMED_OUT
                    || state == MutationCoordinator.State.CANCEL_REQUESTED
                    || state == MutationCoordinator.State.TIMEOUT_REQUESTED;
        }

        @Override public boolean isDone() { return handle.isTerminal(); }

        @Override public T get() throws InterruptedException, ExecutionException {
            return unwrap(handle.awaitCompletion());
        }

        @Override public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            MutationCoordinator.Completion<T> completion =
                    handle.awaitCompletion(timeout, unit);
            if (completion == null) throw new TimeoutException();
            return unwrap(completion);
        }

        private T unwrap(MutationCoordinator.Completion<T> completion)
                throws ExecutionException {
            if (completion.state() == MutationCoordinator.State.SUCCEEDED) {
                return completion.result();
            }
            Throwable failure = completion.error();
            if (completion.state() == MutationCoordinator.State.TIMED_OUT) {
                failure = new MutationTimedOutException("Mutation timed out");
            } else if (failure == null) {
                failure = new InterruptedException("Mutation cancelled");
            }
            throw new ExecutionException(failure);
        }
    }

    private static void awaitFutureTerminal(Future<?> future) {
        if (future == null) return;
        boolean interrupted = false;
        while (true) {
            try {
                future.get();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (ExecutionException e) {
                break;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class MacroMutationContext {
        final Future<String> future;
        final java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta;

        MacroMutationContext(Future<String> future,
                             java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta) {
            this.future = future;
            this.graphDelta = graphDelta;
        }
    }

    private static final class ScriptMutationContext {
        final Future<Object> future;
        final java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta;

        ScriptMutationContext(Future<Object> future,
                              java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta) {
            this.future = future;
            this.graphDelta = graphDelta;
        }
    }

    private static final class PipelineMutationContext {
        final Future<PipelineBuilder.Pipeline> future;
        final java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta;

        PipelineMutationContext(Future<PipelineBuilder.Pipeline> future,
                                java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta) {
            this.future = future;
            this.graphDelta = graphDelta;
        }
    }

    private MacroMutationContext submitTcpMacroMutation(
            final String submittedCode,
            final String executableCode,
            final long timeoutMs,
            final AgentCaps caps,
            final boolean safetyEnabled,
            final List<DestructiveScanner.DestructiveOp> safetyFindings,
            final long graphMarkerBefore) {
        final java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta =
                new java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta>();
        MutationCoordinator.Lifecycle<String> lifecycle =
                new MutationCoordinator.Lifecycle<String>() {
            private List<ImageGraph.ImageRef> graphImagesBefore;
            private ImageGraph.ImageRef graphActiveBefore;
            private SourceImageTagger sourceTagger;

            @Override public void checkSafety() throws Exception {
                enforceMacroSafety(safetyFindings, caps);
            }

            @Override public void beforeMutation() {
                dismissOpenDialogs("Macro Error");
                graphImagesBefore = ImageGraph.captureOpenImages();
                graphActiveBefore = ImageGraph.captureActiveImage();
                if (caps != null && caps.undo) {
                    captureUndoFrameIfEnabled(nextCallId(), submittedCode, caps);
                }
                sourceTagger = SourceImageTagger.beginIfEnabled(
                        caps != null && caps.safeMode
                                && caps.safeModeOptions != null
                                && caps.safeModeOptions.autoSourceImageColumn,
                        submittedCode, WindowManager.getCurrentImage());
            }

            @Override public void afterMutation(MutationCoordinator.Outcome<String> outcome) {
                if (sourceTagger != null) {
                    sourceTagger.postExec(WindowManager.getCurrentImage());
                }
                if (graphImagesBefore != null) {
                    imageGraph.trackImageChange(graphImagesBefore, graphActiveBefore,
                            ImageGraph.captureOpenImages(), submittedCode, "macro");
                    graphDelta.set(imageGraph.deltaSince(graphMarkerBefore));
                }
            }
        };
        MutationCoordinator.Request<String> request =
                MutationCoordinator.Request.<String>builder()
                        .ownerSession(mutationOwnerOrInternal(caps))
                        .sourceKind("macro")
                        .code(executableCode)
                        .timeoutMs(timeoutMs)
                        .safetyEnabled(safetyEnabled)
                        .undoEnabled(caps != null && caps.undo)
                        .provenanceEnabled(true)
                        .operation(new MutationCoordinator.Operation<String>() {
                            @Override public String run() { return IJ.runMacro(executableCode); }
                        })
                        .cancellationAction(new MutationCoordinator.CancellationAction() {
                            @Override public void cancel() {
                                CommandEngine.requestOwnedMacroAbort();
                            }
                        })
                        .lifecycle(lifecycle)
                        .build();
        return new MacroMutationContext(
                new CoordinatorFuture<String>(mutationCoordinator.submit(request)), graphDelta);
    }

    private ScriptMutationContext submitScriptMutation(
            final String language,
            final String code,
            final ScriptEngine engine,
            final long timeoutMs,
            final AgentCaps caps,
            final boolean safetyEnabled,
            final List<DestructiveScanner.DestructiveOp> safetyFindings,
            final long graphMarkerBefore) {
        final java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta =
                new java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta>();
        MutationCoordinator.Lifecycle<Object> lifecycle =
                new MutationCoordinator.Lifecycle<Object>() {
            private List<ImageGraph.ImageRef> graphImagesBefore;
            private ImageGraph.ImageRef graphActiveBefore;
            private SourceImageTagger sourceTagger;

            @Override public void checkSafety() throws Exception {
                enforceMacroSafety(safetyFindings, caps);
            }

            @Override public void beforeMutation() {
                graphImagesBefore = ImageGraph.captureOpenImages();
                graphActiveBefore = ImageGraph.captureActiveImage();
                if (caps != null && caps.undo) {
                    ImagePlus active = WindowManager.getCurrentImage();
                    if (active != null) {
                        sessionUndo.pushBoundary(active.getTitle(), nextCallId());
                    }
                }
                sourceTagger = SourceImageTagger.beginIfEnabled(
                        caps != null && caps.safeMode
                                && caps.safeModeOptions != null
                                && caps.safeModeOptions.autoSourceImageColumn,
                        code, WindowManager.getCurrentImage());
            }

            @Override public void afterMutation(MutationCoordinator.Outcome<Object> outcome) {
                if (sourceTagger != null) {
                    sourceTagger.postExec(WindowManager.getCurrentImage());
                }
                if (graphImagesBefore != null) {
                    imageGraph.trackImageChange(graphImagesBefore, graphActiveBefore,
                            ImageGraph.captureOpenImages(), code, "script");
                    graphDelta.set(imageGraph.deltaSince(graphMarkerBefore));
                }
            }
        };
        MutationCoordinator.Request<Object> request =
                MutationCoordinator.Request.<Object>builder()
                        .ownerSession(mutationOwnerOrInternal(caps))
                        .sourceKind("script")
                        .code(code)
                        .timeoutMs(timeoutMs)
                        .safetyEnabled(safetyEnabled)
                        .undoEnabled(caps != null && caps.undo)
                        .provenanceEnabled(true)
                        .operation(new MutationCoordinator.Operation<Object>() {
                            @Override public Object run() throws ScriptException {
                                return engine.eval(code);
                            }
                        })
                        .cancellationAction(new MutationCoordinator.CancellationAction() {
                            @Override public void cancel() {
                                CommandEngine.requestOwnedMacroAbort();
                            }
                        })
                        .lifecycle(lifecycle)
                        .build();
        return new ScriptMutationContext(
                new CoordinatorFuture<Object>(mutationCoordinator.submit(request)), graphDelta);
    }

    private PipelineMutationContext submitPipelineMutation(
            final PipelineBuilder.Pipeline pipeline,
            final String code,
            final long timeoutMs,
            final AgentCaps caps,
            final boolean safetyEnabled,
            final List<DestructiveScanner.DestructiveOp> safetyFindings,
            final long graphMarkerBefore) {
        final java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta> graphDelta =
                new java.util.concurrent.atomic.AtomicReference<ImageGraph.Delta>();
        MutationCoordinator.Lifecycle<PipelineBuilder.Pipeline> lifecycle =
                new MutationCoordinator.Lifecycle<PipelineBuilder.Pipeline>() {
            private List<ImageGraph.ImageRef> graphImagesBefore;
            private ImageGraph.ImageRef graphActiveBefore;
            private SourceImageTagger sourceTagger;

            @Override public void checkSafety() throws Exception {
                enforceMacroSafety(safetyFindings, caps);
            }

            @Override public void beforeMutation() {
                graphImagesBefore = ImageGraph.captureOpenImages();
                graphActiveBefore = ImageGraph.captureActiveImage();
                if (caps != null && caps.undo) {
                    ImagePlus active = WindowManager.getCurrentImage();
                    if (active != null) {
                        sessionUndo.pushBoundary(active.getTitle(), nextCallId());
                    }
                }
                sourceTagger = SourceImageTagger.beginIfEnabled(
                        caps != null && caps.safeMode
                                && caps.safeModeOptions != null
                                && caps.safeModeOptions.autoSourceImageColumn,
                        code, WindowManager.getCurrentImage());
            }

            @Override public void afterMutation(
                    MutationCoordinator.Outcome<PipelineBuilder.Pipeline> outcome) {
                if (sourceTagger != null) {
                    sourceTagger.postExec(WindowManager.getCurrentImage());
                }
                if (graphImagesBefore != null) {
                    imageGraph.trackImageChange(graphImagesBefore, graphActiveBefore,
                            ImageGraph.captureOpenImages(), code, "pipeline");
                    graphDelta.set(imageGraph.deltaSince(graphMarkerBefore));
                }
            }
        };
        MutationCoordinator.Request<PipelineBuilder.Pipeline> request =
                MutationCoordinator.Request.<PipelineBuilder.Pipeline>builder()
                        .ownerSession(mutationOwnerOrInternal(caps))
                        .sourceKind("pipeline")
                        .code(code)
                        .timeoutMs(timeoutMs)
                        .safetyEnabled(safetyEnabled)
                        .undoEnabled(caps != null && caps.undo)
                        .provenanceEnabled(true)
                        .operation(new MutationCoordinator.Operation<PipelineBuilder.Pipeline>() {
                            @Override public PipelineBuilder.Pipeline run() {
                                pipelineBuilder.executePipelineOnCurrentThread(pipeline, null);
                                return pipeline;
                            }
                        })
                        .cancellationAction(new MutationCoordinator.CancellationAction() {
                            @Override public void cancel() {
                                CommandEngine.requestOwnedMacroAbort();
                            }
                        })
                        .lifecycle(lifecycle)
                        .build();
        return new PipelineMutationContext(
                new CoordinatorFuture<PipelineBuilder.Pipeline>(
                        mutationCoordinator.submit(request)), graphDelta);
    }

    private void enforceMacroSafety(
            List<DestructiveScanner.DestructiveOp> findings,
            AgentCaps caps) throws DestructiveMacroException {
        List<DestructiveScanner.DestructiveOp> rejections =
                DestructiveScanner.rejections(findings);
        if (!rejections.isEmpty()) throw new DestructiveMacroException(rejections);
        for (DestructiveScanner.DestructiveOp op : DestructiveScanner.backups(findings)) {
            if (DestructiveScanner.RULE_ROI_WIPE.equals(op.ruleId)
                    && caps != null && caps.safeModeOptions != null
                    && caps.safeModeOptions.autoBackupRoiOnReset) {
                runRoiAutoBackup(op, caps);
            }
        }
    }

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
                        try {
                            return java.nio.file.Files.exists(java.nio.file.Paths.get(path));
                        } catch (Throwable t) {
                            return false;
                        }
                    }
                });
    }

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
            JsonObject ev = new JsonObject();
            ev.addProperty("rule_id", op.ruleId);
            ev.addProperty("backup_path", backupTarget);
            publishSafeModeEvent("safe_mode.roi_auto_backup", ev);
        } catch (Throwable t) {
            try { IJ.log("[ImageJAI-SafeMode] ROI auto-backup failed: " + t.getMessage()); }
            catch (Throwable ignore) {}
        }
    }

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
        String hint = "Fix the offending lines, or disable safe mode for this intentional run.";

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

        try {
            String agentId = caps != null && caps.agentId != null ? caps.agentId : "";
            for (DestructiveScanner.DestructiveOp op : rejects) {
                frictionLog.record(agentId, "execute_macro",
                        "rule=" + op.ruleId + " target=" + op.target + " line=" + op.line,
                        op.message);
            }
        } catch (Throwable ignore) {}

        if (!rejects.isEmpty()) {
            DestructiveScanner.DestructiveOp head = rejects.get(0);
            JsonObject ev = new JsonObject();
            ev.addProperty("rule_id", head.ruleId);
            ev.addProperty("target", head.target);
            ev.addProperty("line", head.line);
            ev.addProperty("count", rejects.size());
            publishSafeModeEvent("safe_mode.blocked", ev);
        }

        return successResponse(result);
    }

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

    String resolveTargetImageTitleWithFallback(String code) {
        String parsed = resolveTargetImageTitle(code);
        if (parsed != null) return parsed;
        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp != null) {
                String title = imp.getTitle();
                if (title != null && !title.isEmpty()) return title;
            }
        } catch (Throwable ignore) {}
        return null;
    }

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

        JsonObject ev = new JsonObject();
        ev.addProperty("blocking_macro_id", inflight.macroId);
        ev.addProperty("blocking_dialog_title", dialogTitle);
        ev.addProperty("target_image", target);
        publishSafeModeEvent("safe_mode.queue_storm_blocked", ev);
        return successResponse(result);
    }

    private void publishSafeModeEvent(String topic, JsonObject data) {
        if (topic == null || topic.isEmpty()) return;
        try {
            eventBus.publish(topic, data == null ? new JsonObject() : data);
        } catch (Throwable ignore) {}
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
        final boolean safetyEnabled = isScientificIntegrityScanEnabled(caps);
        final List<DestructiveScanner.DestructiveOp> safetyFindings = safetyEnabled
                ? DestructiveScanner.scanElevatedScript(code, captureScannerContext(caps))
                : java.util.Collections.<DestructiveScanner.DestructiveOp>emptyList();

        JsonObject result = new JsonObject();
        result.addProperty("language", language);

        final ScriptEngine engine = scriptEngineResolverForTest != null
                ? scriptEngineResolverForTest.apply(language)
                : new ScriptEngineManager().getEngineByName(language);

        if (engine == null) {
            return errorResponse("ScriptEngine not found for language: " + language
                    + ". Available: groovy, jython, javascript");
        }
        final SessionCodeJournal.DatasetBinding journalDataset =
                SessionCodeJournal.captureInitiatingDataset();

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
        final long graphMarkerBefore = imageGraph.currentMarker();

        // Step 15: scripts are uninvertible side-effects. Plan §Out-of-scope
        // marks them a "branch boundary" — push a sentinel onto the active
        // image's undo stack so a later rewind cannot walk past this point.
        // Best-effort: capture failures must not block the script.
        // Poll the coordinator-owned script worker for blocking dialogs every
        // 150 ms. Without this, a Groovy
        // hallucination like IJ.run("setAutoThreshold", ...) opens a command
        // dialog and pins Fiji until the client socket times out — leaving the
        // dialog on screen to block every subsequent call.
        long startTime = System.currentTimeMillis();
        ScriptMutationContext scriptMutationContext = null;
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
        // MutationCoordinator owns script serialization and worker lifetime.
        {
        try {
            scriptMutationContext = submitScriptMutation(language, code, engine,
                    scriptTimeoutMs, caps, safetyEnabled, safetyFindings,
                    graphMarkerBefore);
            future = scriptMutationContext.future;

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
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (cause instanceof DestructiveMacroException) {
                        return destructiveBlockedReply(
                                ((DestructiveMacroException) cause).rejections, caps);
                    }
                    if (cause instanceof MutationTimedOutException) {
                        blockingFailure = "Script execution timed out after "
                                + scriptTimeoutMs + "ms";
                    } else {
                        scriptError = cause;
                    }
                    break;
                }
            }
        } catch (IllegalArgumentException
                 | java.util.concurrent.RejectedExecutionException e) {
            return errorResponse("Mutation admission rejected: " + e.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (future != null && !future.isDone()) future.cancel(true);
            return errorResponse("Interrupted");
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            awaitFutureTerminal(future);
        }
        } // end coordinator-backed script wait block
        } finally {
            // Counter must drop even if coordinator execution failed.
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
            SessionCodeJournal.INSTANCE.record(journalDataset, language, code, source,
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
            ImageGraph.Delta gDelta = scriptMutationContext == null
                    ? null : scriptMutationContext.graphDelta.get();
            if (caps != null && caps.graphDelta
                    && gDelta != null && !gDelta.isEmpty()) {
                result.add("graphDelta", gDelta.toJson());
            }
        } catch (Throwable ignore) {}
        if (caps != null && caps.pulse) {
            result.addProperty("pulse", PulseBuilder.build());
        }
        return successResponse(result);
    }

    private JsonObject handleGetState() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
                    StateInspector.BoundedCsv resultsCsv = stateInspector
                            .getResultsTableCSVBounded((int) MAX_RESULTS_TABLE_BYTES);
                    addBoundedResultsCsv(state, resultsCsv);

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
                actionToken.invalidate();
                return errorResponse("Timed out getting state");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
                actionToken.invalidate();
                return errorResponse("Timed out getting image info");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = stateInspector.getResultsTableCSVBounded(
                            (int) MAX_RESULTS_TABLE_BYTES);
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                actionToken.invalidate();
                return errorResponse("Timed out getting results table");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        StateInspector.BoundedCsv csv = (StateInspector.BoundedCsv) holder[0];
        if (csv.truncated()) {
            JsonObject tooLarge = errorResponse("Results table is "
                    + csv.originalBytes()
                    + " UTF-8 bytes; direct TCP return is limited to "
                    + MAX_RESULTS_TABLE_BYTES + " bytes. Export it to AI_Exports instead.");
            tooLarge.addProperty("actual_bytes", csv.originalBytes());
            tooLarge.addProperty("limit_bytes", MAX_RESULTS_TABLE_BYTES);
            tooLarge.addProperty("total_rows", csv.totalRows());
            tooLarge.addProperty("returned_rows", 0);
            return tooLarge;
        }
        return successResponse(new JsonPrimitive(csv.text()));
    }

    private JsonObject handleCaptureImage(JsonObject request, AgentCaps caps, Socket sock) {
        final long responseBudget = compoundResponseBudget.get().longValue();
        final int capturePngLimit = maxBinaryBytesForCompoundBudget(
                responseBudget, MAX_CAPTURE_PNG_BYTES);
        if (capturePngLimit <= 0) {
            return compoundBudgetError("capture_image", responseBudget);
        }
        JsonElement maxSizeElement = request.get("maxSize");
        final int requestedMaxSize;
        try {
            requestedMaxSize = (maxSizeElement != null && maxSizeElement.isJsonPrimitive())
                    ? maxSizeElement.getAsInt()
                    : Constants.MAX_THUMBNAIL_SIZE;
        } catch (RuntimeException invalidSize) {
            return errorResponse("capture_image maxSize must be an integer");
        }
        final CaptureSource source = CaptureSource.from(
                request.has("source") ? request.get("source").getAsString() : null);
        final PrivacyPosture posture = PostureController.getInstance().current();
        final String visualSession = sessionKey(caps, sock);

        if (source.isRefusedScreenshot()) {
            JsonObject result = new JsonObject();
            result.addProperty("source", source.name());
            return successResponse(result);
        }

        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
            @Override
            public void run() {
                try {
                    ij.ImagePlus imp = ij.WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        String imageToken = visualImageToken(imp);
                        boolean fullResolutionOverride =
                                posture == PrivacyPosture.PSEUDONYMISED
                                && source == CaptureSource.ACTIVE_IMAGE_CONTENT
                                && VisualOverrideRegistry.getInstance().hasGrant(
                                        visualSession, imageToken);
                        int maxSize = Math.max(1, Math.min(MAX_CAPTURE_DIMENSION,
                                fullResolutionOverride
                                        ? MAX_CAPTURE_DIMENSION : requestedMaxSize));
                        byte[] png = source == CaptureSource.ACTIVE_IMAGE_WITH_OVERLAY
                                ? ImageCapture.captureWithOverlays(
                                        imp, maxSize, capturePngLimit)
                                : ImageCapture.captureImage(
                                        imp, maxSize, capturePngLimit);
                        if (png == null) {
                            holder[0] = "CAPTURE_FAILED";
                        } else if (png.length > capturePngLimit) {
                            holder[0] = "CAPTURE_TOO_LARGE:" + png.length;
                        } else {
                            JsonObject result = new JsonObject();
                            result.addProperty("base64", base64Encode(png));
                            result.addProperty("width", imp.getWidth());
                            result.addProperty("height", imp.getHeight());
                            result.addProperty("source", source.name());
                            if (posture == PrivacyPosture.PSEUDONYMISED
                                    && source == CaptureSource.ACTIVE_IMAGE_CONTENT) {
                                // Internal hand-off: CaptureHandler removes this
                                // after atomically consuming the exact image grant.
                                result.addProperty("_visual_image_token", imageToken);
                            }
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
                actionToken.invalidate();
                return errorResponse("Timed out capturing image");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            if (holder[0] instanceof ImageCapture.CaptureTooLargeException) {
                if (capturePngLimit < MAX_CAPTURE_PNG_BYTES) {
                    return compoundBudgetError("capture_image", responseBudget);
                }
                return errorResponse("Captured PNG exceeds " + capturePngLimit
                        + " bytes; crop or reduce maxSize.");
            }
            return errorResponse("Capture error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        if ("CAPTURE_FAILED".equals(holder[0])) {
            return errorResponse("Failed to capture image");
        }
        if (holder[0] instanceof String
                && ((String) holder[0]).startsWith("CAPTURE_TOO_LARGE:")) {
            if (capturePngLimit < MAX_CAPTURE_PNG_BYTES) {
                return compoundBudgetError("capture_image", responseBudget);
            }
            return errorResponse("Captured PNG exceeds " + capturePngLimit
                    + " bytes; crop or reduce maxSize.");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleRequestVisual(JsonObject request, AgentCaps caps, Socket sock) {
        PrivacyPosture posture = PostureController.getInstance().current();
        JsonObject result = new JsonObject();
        result.addProperty("posture", posture.label());
        if (posture == PrivacyPosture.ON_PREMISES) {
            return errorResponse("visual_override_refused_on_premises");
        }
        if (posture == PrivacyPosture.STANDARD) {
            result.addProperty("status", "not_required");
            return successResponse(result);
        }

        String reason = request.has("reason") && request.get("reason").isJsonPrimitive()
                ? request.get("reason").getAsString()
                : "";
        String session = sessionKey(caps, sock);
        ImagePlus requestedImage = currentImage();
        String imageToken = visualImageToken(requestedImage);
        VisualOverrideRegistry.PendingRequest pending =
                VisualOverrideRegistry.getInstance().request(session, reason, imageToken);
        JsonObject event = new JsonObject();
        event.addProperty("session", session);
        event.addProperty("request_id", pending.requestId);
        event.addProperty("reason", reason);
        event.addProperty("image_token", imageToken);
        event.addProperty("image_display_token", visualImageDisplayToken(requestedImage));
        eventBus.publish("data_governance.visual.requested", event);

        result.addProperty("status", "pending_user_consent");
        result.addProperty("request_id", pending.requestId);
        result.addProperty("expires_in_seconds", 60);
        return successResponse(result);
    }

    /** Stable, process-local scope for the exact image a visual request covers. */
    static String visualImageToken(ImagePlus image) {
        String identity = ImageGraph.stableIdentity(image);
        return identity == null ? "" : identity;
    }

    /** Pseudonymous path/title label for display and audit only, never grants. */
    private static String visualImageDisplayToken(ImagePlus image) {
        if (image == null) return "";
        try {
            ij.io.FileInfo info = image.getOriginalFileInfo();
            if (info != null && info.directory != null && info.fileName != null) {
                return PathTokenMap.getInstance().tokenForPath(
                        Paths.get(info.directory, info.fileName));
            }
            return PathTokenMap.getInstance().tokenForSensitiveText(
                    image.getTitle() == null ? "" : image.getTitle(), "image");
        } catch (Throwable tokenFailure) {
            return "image";
        }
    }

    private JsonObject handleOpenImage(JsonObject request, boolean tokenOnly) {
        JsonElement targetElement = firstPresent(request, "token", "image_token", "path", "file");
        if (targetElement == null || !targetElement.isJsonPrimitive()) {
            return errorResponse(tokenOnly
                    ? "Missing token for open_image_by_token"
                    : "Missing path or token for open_image");
        }

        String target = targetElement.getAsString();
        PathTokenMap.ResolvedTarget resolved = openImageByToken(target);
        if (tokenOnly && resolved == null) {
            return errorResponse("Unknown image token");
        }

        Path realPath;
        int series = -1;
        boolean tokenResolved = resolved != null;
        if (resolved != null) {
            realPath = resolved.realPath();
            series = resolved.series();
        } else {
            try {
                realPath = Paths.get(target);
            } catch (RuntimeException e) {
                return errorResponse("open_image_failed: invalid path");
            }
            if (request.has("series") && request.get("series").isJsonPrimitive()) {
                try {
                    series = request.get("series").getAsInt();
                } catch (Exception ignored) {
                    series = -1;
                }
            }
        }

        final Path normalizedPath;
        try {
            normalizedPath = realPath.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return errorResponse("open_image_failed: invalid path");
        }
        final String realPathString = normalizedPath.toString();
        final int requestedSeries = series;
        final List<ImageGraph.ImageRef> before = currentOpenImageRefs();
        final ImagePlus activeBefore = currentImage();
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        long requestedTimeout = resolveTimeoutMs(request, 30000L);
        final long timeoutMs = Math.max(1L, Math.min(120000L, requestedTimeout));
        final long deadline = System.currentTimeMillis() + timeoutMs;
        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
            @Override
            public void run() {
                try {
                    if (openImageOperationForTest != null) {
                        openImageOperationForTest.open(realPathString, requestedSeries);
                    } else {
                        if (requestedSeries >= 0) {
                            String options = "open=[" + realPathString.replace("]", "\\]") + "] "
                                    + "autoscale color_mode=Default view=Hyperstack "
                                    + "stack_order=XYCZT series_" + requestedSeries;
                            IJ.run("Bio-Formats Importer", options);
                        } else {
                            IJ.open(realPathString);
                        }
                    }
                } catch (Throwable e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            if (!latch.await(remaining, TimeUnit.MILLISECONDS)) {
                actionToken.invalidate();
                restoreActiveImage(activeBefore);
                return errorResponse("Timed out opening image");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
            restoreActiveImage(activeBefore);
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }
        if (holder[0] instanceof Throwable) {
            restoreActiveImage(activeBefore);
            return errorResponse("open_image_failed");
        }

        ImageGraph.ImageRef opened = null;
        while (System.currentTimeMillis() <= deadline) {
            opened = findRequestedOpenedImage(before, currentOpenImageRefs(), normalizedPath);
            if (opened != null) break;
            try {
                Thread.sleep(Math.min(25L,
                        Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                restoreActiveImage(activeBefore);
                Thread.currentThread().interrupt();
                return errorResponse("Interrupted");
            }
        }
        if (opened == null) {
            restoreActiveImage(activeBefore);
            return errorResponse("open_image_failed: requested image did not open");
        }
        imageGraph.addOpenedImage(opened);

        String baseToken = pseudonymisationFilter.pathTokenMap().tokenForPath(normalizedPath);
        JsonObject result = new JsonObject();
        result.addProperty("opened", true);
        result.addProperty("path_token", requestedSeries >= 0
                ? pseudonymisationFilter.pathTokenMap().tokenForSeries(normalizedPath, requestedSeries)
                : baseToken);
        result.addProperty("series", requestedSeries);
        result.addProperty("resolved_from_token", tokenResolved);
        result.addProperty("title", opened.title);
        result.addProperty("image_id", opened.identity);
        return successResponse(result);
    }

    private List<ImageGraph.ImageRef> currentOpenImageRefs() {
        return openImagesForTest != null
                ? openImagesForTest.get() : ImageGraph.captureOpenImages();
    }

    private ImagePlus currentImage() {
        return currentImageForTest != null
                ? currentImageForTest.get() : WindowManager.getCurrentImage();
    }

    static ImageGraph.ImageRef findRequestedOpenedImage(
            List<ImageGraph.ImageRef> before, List<ImageGraph.ImageRef> after,
            Path requestedPath) {
        Set<String> prior = new HashSet<String>();
        if (before != null) {
            for (ImageGraph.ImageRef ref : before) prior.add(ref.identity);
        }
        List<ImageGraph.ImageRef> added = new ArrayList<ImageGraph.ImageRef>();
        if (after != null) {
            for (ImageGraph.ImageRef ref : after) {
                if (!prior.contains(ref.identity)) added.add(ref);
            }
        }
        for (ImageGraph.ImageRef ref : added) {
            if (ref.sourcePath != null && requestedPath != null) {
                try {
                    if (Paths.get(ref.sourcePath).toAbsolutePath().normalize()
                            .equals(requestedPath.toAbsolutePath().normalize())) {
                        return ref;
                    }
                } catch (RuntimeException ignore) {}
            }
        }
        // Some readers do not retain OriginalFileInfo. A single new identity
        // created by this completed open call is still causal evidence; never
        // accept a pre-existing active image or choose among ambiguous opens.
        return added.size() == 1 && added.get(0).sourcePath == null
                ? added.get(0) : null;
    }

    static List<ImageGraph.ImageRef> newImageRefs(
            List<ImageGraph.ImageRef> before, List<ImageGraph.ImageRef> after) {
        Set<String> prior = new HashSet<String>();
        if (before != null) {
            for (ImageGraph.ImageRef ref : before) prior.add(ref.identity);
        }
        List<ImageGraph.ImageRef> added = new ArrayList<ImageGraph.ImageRef>();
        if (after != null) {
            for (ImageGraph.ImageRef ref : after) {
                if (!prior.contains(ref.identity)) added.add(ref);
            }
        }
        return added;
    }

    private static JsonArray imageTitles(List<ImageGraph.ImageRef> images) {
        JsonArray titles = new JsonArray();
        for (ImageGraph.ImageRef ref : images) titles.add(ref.title);
        return titles;
    }

    private void restoreActiveImage(final ImagePlus activeBefore) {
        if (activeBefore == null || currentImage() == activeBefore) return;
        final ImageWindow window = activeBefore.getWindow();
        if (window == null) return;
        final CountDownLatch restored = new CountDownLatch(1);
        GuiActionDispatcher.ActionToken token =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
            @Override public void run() {
                try {
                    WindowManager.setCurrentWindow(window);
                } finally {
                    restored.countDown();
                }
            }
        });
        try {
            if (!restored.await(2000L, TimeUnit.MILLISECONDS)) token.invalidate();
        } catch (InterruptedException e) {
            token.invalidate();
            Thread.currentThread().interrupt();
        }
    }

    private JsonObject handleBrowsePendingBrief(JsonObject request, AgentCaps caps, Socket sock) {
        JsonObject result = new JsonObject();
        result.addProperty("pending",
                SelectionBroker.getInstance().hasPending(briefSessionKey(request, caps, sock)));
        return successResponse(result);
    }

    private JsonObject handleGetPendingBrief(JsonObject request, AgentCaps caps, Socket sock) {
        Optional<Brief> brief = SelectionBroker.getInstance()
                .consume(briefSessionKey(request, caps, sock));
        JsonObject result = new JsonObject();
        if (!brief.isPresent()) {
            result.addProperty("pending", false);
            return successResponse(result);
        }
        Brief b = brief.get();
        result.addProperty("pending", true);
        JsonArray tokens = new JsonArray();
        for (String token : b.tokens()) {
            tokens.add(new JsonPrimitive(token));
        }
        result.add("tokens", tokens);
        result.addProperty("tag", b.tag());
        JsonElement metadata = GSON.toJsonTree(b.metadata());
        result.add("metadata", metadata == null || metadata.isJsonNull()
                ? new JsonObject()
                : metadata);
        return successResponse(result);
    }

    private String briefSessionKey(JsonObject request, AgentCaps caps, Socket sock) {
        String fromRequest = optString(request, "session_id", "");
        if (!fromRequest.trim().isEmpty()) {
            return fromRequest.trim();
        }
        return sessionKey(caps, sock);
    }

    public PathTokenMap.ResolvedTarget openImageByToken(String token) {
        return pseudonymisationFilter.pathTokenMap().resolve(token).orElse(null);
    }

    private static JsonElement firstPresent(JsonObject request, String... keys) {
        for (String key : keys) {
            JsonElement value = request.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private JsonObject handleRunPipeline(JsonObject request, AgentCaps caps) {
        JsonElement stepsElement = request.get("steps");
        if (stepsElement == null || !stepsElement.isJsonArray()) {
            return errorResponse("Missing 'steps' array for run_pipeline");
        }

        JsonArray stepsArray = stepsElement.getAsJsonArray();
        final long pipelineTimeoutMs = resolveTimeoutMs(request, PIPELINE_TIMEOUT_MS);
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
        final long graphMarkerBefore = imageGraph.currentMarker();
        final StringBuilder pipelineMacro = new StringBuilder();
        for (PipelineBuilder.PipelineStep s : steps) {
            if (pipelineMacro.length() > 0) pipelineMacro.append('\n');
            if (s.macroCode != null) pipelineMacro.append(s.macroCode);
        }
        final String pipelineCode = pipelineMacro.toString();
        final boolean safetyEnabled = isScientificIntegrityScanEnabled(caps);
        final List<DestructiveScanner.DestructiveOp> safetyFindings = safetyEnabled
                ? DestructiveScanner.scan(pipelineCode, captureScannerContext(caps))
                : java.util.Collections.<DestructiveScanner.DestructiveOp>emptyList();

        // Step 15: pipelines are macro chains; treat them as a script-level
        // boundary so a later rewind cannot undo only some of the steps.
        // Plan §Out-of-scope on script-runs applies here by extension.
        // Submit the complete chain once so no stage can interleave with
        // another mutation. In-flight accounting keeps rewind fail-closed.
        PipelineMutationContext pipelineMutationContext = null;
        macroInFlight.incrementAndGet();
        try {
            try {
                pipelineMutationContext = submitPipelineMutation(pipeline,
                        pipelineCode, pipelineTimeoutMs, caps, safetyEnabled,
                        safetyFindings, graphMarkerBefore);
                pipelineMutationContext.future.get();
            } catch (IllegalArgumentException
                     | java.util.concurrent.RejectedExecutionException e) {
                return errorResponse("Mutation admission rejected: " + e.getMessage());
            } catch (InterruptedException e) {
                if (pipelineMutationContext != null) {
                    pipelineMutationContext.future.cancel(true);
                    awaitFutureTerminal(pipelineMutationContext.future);
                }
                Thread.currentThread().interrupt();
                return errorResponse("Pipeline interrupted");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof DestructiveMacroException) {
                    return destructiveBlockedReply(
                            ((DestructiveMacroException) cause).rejections, caps);
                }
                if (cause instanceof MutationTimedOutException) {
                    return errorResponse("Pipeline timed out after "
                            + pipelineTimeoutMs + "ms");
                }
                return errorResponse("Pipeline error: "
                        + (cause == null || cause.getMessage() == null
                                ? "unknown error" : cause.getMessage()));
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
            ImageGraph.Delta gDelta = pipelineMutationContext == null
                    ? null : pipelineMutationContext.graphDelta.get();
            if (caps != null && caps.graphDelta
                    && gDelta != null && !gDelta.isEmpty()) {
                resultJson.add("graphDelta", gDelta.toJson());
            }
        } catch (Throwable ignore) {}
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
            rJson.addProperty("binaryMask", r.binaryMask);
            rJson.addProperty("metricLabel", r.metricLabel);
            if (!Double.isNaN(r.coverage)) {
                rJson.addProperty("coverage", r.coverage);
            }
            if (!Double.isNaN(r.metricValue)) {
                rJson.addProperty("metricValue", r.metricValue);
            }
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
                actionToken.invalidate();
                return errorResponse("Timed out getting state context");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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
        return successResponse(new JsonPrimitive(AgentContextSanitizer.wrap(log, "LOG")));
    }

    private JsonObject handleGetHistogram() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
                actionToken.invalidate();
                return errorResponse("Timed out getting histogram");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
                actionToken.invalidate();
                return errorResponse("Timed out getting open windows");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
            @Override
            public void run() {
                try {
                    ImagePlus imp = WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        JsonObject result = new JsonObject();
                        result.addProperty("title", imp.getTitle());

                        // Info property (often contains Bio-Formats metadata) —
                        // wrap as untrusted external text per D7 sanitiser policy.
                        String info = (String) imp.getProperty("Info");
                        result.addProperty("info", AgentContextSanitizer.wrap(info, "OME-XML"));

                        // All properties — each value is also externally-sourced
                        // and gets a per-key envelope.
                        JsonObject propsJson = new JsonObject();
                        Properties props = imp.getProperties();
                        if (props != null) {
                            Enumeration<?> names = props.propertyNames();
                            while (names.hasMoreElements()) {
                                String key = names.nextElement().toString();
                                Object val = props.get(key);
                                if (val != null) {
                                    propsJson.addProperty(key,
                                            AgentContextSanitizer.wrap(val.toString(), "META:" + key));
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
                actionToken.invalidate();
                return errorResponse("Timed out getting metadata");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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
     * D8: shell out to {@code agent/methods_table.py} so the agent can emit a
     * QUAREP-LiMi WG11-aligned Markdown methods table from the side. The
     * exporter walks the latest session log + Bio-Formats metadata + the
     * provenance graph itself; this handler just runs it and parses its
     * stdout. Mutating (writes a Markdown file) — NOT a readonly command.
     *
     * <p>Response shape: {@code {"ok":true,"result":{"path":"AI_Exports/methods.md","fieldCoverage":"21/33"}}}.
     */
    JsonObject handleEmitMethodsTable(JsonObject request, AgentCaps caps) {
        final SessionCodeJournal.DatasetBinding initiatingDataset =
                SessionCodeJournal.captureInitiatingDataset();
        try {
            Path methodsScript = resolveMethodsTableScript();
            List<String> command = new ArrayList<String>();
            command.add(resolvePythonExecutable());
            command.add(methodsScript.toString());
            JsonObject datasetJson = new JsonObject();
            if (initiatingDataset.identity != null) {
                datasetJson.addProperty("identity", initiatingDataset.identity);
            }
            if (initiatingDataset.hash != null) {
                datasetJson.addProperty("hash", initiatingDataset.hash);
            }
            datasetJson.addProperty("title", initiatingDataset.title);
            if (initiatingDataset.sourcePath != null) {
                datasetJson.addProperty("filePath", initiatingDataset.sourcePath);
            }
            datasetJson.addProperty("width", initiatingDataset.width);
            datasetJson.addProperty("height", initiatingDataset.height);
            datasetJson.addProperty("nSlices", initiatingDataset.slices);
            datasetJson.addProperty("nChannels", initiatingDataset.channels);
            datasetJson.addProperty("nFrames", initiatingDataset.frames);
            datasetJson.addProperty("bitDepth", initiatingDataset.bitDepth);
            attachMethodsSessionMetadata(datasetJson, caps);
            command.add("--dataset-json");
            command.add(datasetJson.toString());
            if (initiatingDataset.sourcePath != null) {
                Path source = Paths.get(initiatingDataset.sourcePath).toAbsolutePath().normalize();
                Path parent = source.getParent();
                if (parent != null) {
                    command.add("--out");
                    command.add(parent.resolve("AI_Exports").resolve("methods.md").toString());
                }
            }
            BoundedProcessRunner.Result execution = BoundedProcessRunner.run(
                    command, METHODS_PROCESS_TIMEOUT_MS, MAX_PROCESS_OUTPUT_BYTES);
            String output = execution.output;
            if (execution.timedOut) {
                return errorResponse("methods_table.py timed out after "
                        + METHODS_PROCESS_TIMEOUT_MS + " ms; process terminated="
                        + execution.terminated + ": " + output.trim()
                        + (execution.outputTruncated
                        ? " [output truncated at " + MAX_PROCESS_OUTPUT_BYTES + " bytes]"
                        : ""));
            }
            if (execution.exitCode != 0) {
                return errorResponse("methods_table.py exited " + execution.exitCode + ": "
                        + output.trim() + (execution.outputTruncated
                        ? " [output truncated at " + MAX_PROCESS_OUTPUT_BYTES + " bytes]"
                        : ""));
            }
            // stdout looks like:
            //   "emitted methods.md: 21/33 WG11 fields populated, 12 marked [unknown] -> /path/methods.md"
            String coverage = null;
            String path = null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(\\d+/\\d+) WG11 fields populated.*?-> (.+?)\\s*$",
                    java.util.regex.Pattern.MULTILINE).matcher(output);
            if (m.find()) {
                coverage = m.group(1);
                path = m.group(2).trim();
            }
            JsonObject result = new JsonObject();
            if (path != null) result.addProperty("path", path);
            if (coverage != null) result.addProperty("fieldCoverage", coverage);
            if (initiatingDataset.identity != null) {
                result.addProperty("datasetIdentity", initiatingDataset.identity);
            }
            if (initiatingDataset.hash != null) {
                result.addProperty("datasetHash", initiatingDataset.hash);
            }
            result.addProperty("output", output.trim());
            result.addProperty("output_truncated", execution.outputTruncated);
            result.addProperty("output_total_bytes", execution.totalOutputBytes);
            return successResponse(result);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return errorResponse("emit_methods_table failed: " + e.getMessage());
        }
    }

    static void attachMethodsSessionMetadata(JsonObject datasetJson, AgentCaps caps) {
        datasetJson.addProperty("imagejVersion", IJ.getVersion());
        if (caps == null) return;
        if (caps.sessionId != null && !caps.sessionId.trim().isEmpty()) {
            datasetJson.addProperty("tcpSessionId", caps.sessionId.trim());
        }
        if (caps.clientSessionId != null && !caps.clientSessionId.trim().isEmpty()) {
            datasetJson.addProperty("clientSessionId", caps.clientSessionId.trim());
        }
    }

    static String resolvePythonExecutable() {
        String configured = System.getenv("IMAGEJAI_PYTHON");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "python" : "python3";
    }

    static Path resolveMethodsTableScript() throws IOException {
        List<Path> workspaces = new ArrayList<Path>();
        addWorkspaceCandidate(workspaces,
                System.getProperty("imagejai.agent.workspace"));
        addWorkspaceCandidate(workspaces, System.getenv("IMAGEJAI_AGENT_WORKSPACE"));
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.trim().isEmpty()) {
            workspaces.add(Paths.get(userDir).resolve("agent"));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.trim().isEmpty()) {
            workspaces.add(Paths.get(userHome).resolve("ImageJAI").resolve("agent"));
        }
        for (Path workspace : workspaces) {
            Path script = workspace.toAbsolutePath().normalize().resolve("methods_table.py");
            if (Files.isRegularFile(script)) return script;
        }
        throw new IOException("methods_table.py not found; configure "
                + "imagejai.agent.workspace or IMAGEJAI_AGENT_WORKSPACE");
    }

    private static void addWorkspaceCandidate(List<Path> workspaces, String value) {
        if (value != null && !value.trim().isEmpty()) {
            workspaces.add(Paths.get(value.trim()));
        }
    }

    private JsonObject handleBatch(JsonObject request, AgentCaps caps) {
        JsonElement commandsElement = request.get("commands");
        if (commandsElement == null || !commandsElement.isJsonArray()) {
            return errorResponse("Missing 'commands' array for batch");
        }

        JsonArray commands = commandsElement.getAsJsonArray();
        if (commands.size() > MAX_BATCH_COMMANDS) {
            return errorResponse("Batch command count " + commands.size()
                    + " exceeds max " + MAX_BATCH_COMMANDS);
        }
        int parentDepth = batchDepth.get().intValue();
        if (parentDepth >= MAX_BATCH_DEPTH) {
            return errorResponse("Batch nesting depth exceeds max " + MAX_BATCH_DEPTH);
        }
        CompoundWorkBudget workBudget = compoundWorkBudget.get();
        boolean ownsWorkBudget = workBudget == null;
        if (ownsWorkBudget) {
            workBudget = new CompoundWorkBudget();
            compoundWorkBudget.set(workBudget);
        }
        final CompoundWorkBudget sharedWorkBudget = workBudget;
        final int workAtEntry = sharedWorkBudget.consumed;
        batchDepth.set(Integer.valueOf(parentDepth + 1));
        try {
        JsonArray results = new JsonArray();
        boolean haltOnError = request.has("halt_on_error")
                && request.get("halt_on_error").isJsonPrimitive()
                && request.get("halt_on_error").getAsBoolean();
        int firstFailureIndex = -1;
        boolean halted = false;
        long retainedBytes = 0L;
        boolean responseTruncated = false;
        int executedCount = 0;
        int budgetExhaustedAtIndex = -1;

        for (int i = 0; i < commands.size(); i++) {
            // Admission is charged before validation/dispatch so malformed or
            // throwing children cannot bypass the shared nested-work cap.
            if (!sharedWorkBudget.tryConsume()) {
                halted = true;
                budgetExhaustedAtIndex = i;
                break;
            }
            JsonElement elem = commands.get(i);
            JsonObject subResult;
            if (elem.isJsonObject()) {
                try {
                    subResult = dispatchWithCompoundBudget(
                            elem.getAsJsonObject(), caps, retainedBytes);
                } catch (Throwable failure) {
                    String detail = failure.getMessage();
                    if (detail == null || detail.trim().isEmpty()) {
                        detail = failure.getClass().getSimpleName();
                    }
                    subResult = errorResponse("Batch command at index " + i
                            + " threw: " + detail);
                }
            } else {
                subResult = errorResponse("Invalid batch command at index " + i);
            }
            executedCount++;
            JsonObject indexed = new JsonObject();
            indexed.addProperty("index", i);
            indexed.add("response", subResult);
            long responseBytes = utf8Length(GSON.toJson(indexed));
            if (retainedBytes + responseBytes > MAX_BATCH_RESPONSE_BYTES) {
                responseTruncated = true;
                halted = true;
                if (firstFailureIndex < 0) firstFailureIndex = i;
                break;
            }
            results.add(indexed);
            retainedBytes += responseBytes;
            if (isFailure(subResult) && firstFailureIndex < 0) {
                firstFailureIndex = i;
                if (haltOnError) {
                    halted = true;
                    break;
                }
            }
            if (sharedWorkBudget.exhausted) {
                halted = true;
                budgetExhaustedAtIndex = Math.min(i + 1, commands.size());
                break;
            }
        }

        JsonObject result = new JsonObject();
        result.add("results", results);
        result.addProperty("executed", executedCount);
        result.addProperty("retained_responses", results.size());
        result.addProperty("omitted_responses", executedCount - results.size());
        result.addProperty("omitted_commands", commands.size() - executedCount);
        result.addProperty("total", commands.size());
        result.addProperty("halted", halted);
        result.addProperty("response_truncated", responseTruncated);
        result.addProperty("retained_response_bytes", retainedBytes);
        result.addProperty("max_response_bytes", MAX_BATCH_RESPONSE_BYTES);
        result.addProperty("work_executed",
                sharedWorkBudget.consumed - workAtEntry);
        result.addProperty("work_budget_remaining", sharedWorkBudget.remaining());
        result.addProperty("max_work", MAX_COMPOUND_WORK);
        result.addProperty("work_budget_exhausted", sharedWorkBudget.exhausted);
        if (budgetExhaustedAtIndex >= 0) {
            result.addProperty("budget_exhausted_at_index", budgetExhaustedAtIndex);
        }
        if (firstFailureIndex >= 0) {
            result.addProperty("firstFailureIndex", firstFailureIndex);
        }
        return successResponse(result);
        } finally {
            batchDepth.set(Integer.valueOf(parentDepth));
            if (ownsWorkBudget) compoundWorkBudget.remove();
        }
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
            segments = BatchParser.parse(
                    chainEl.getAsString(), MAX_BATCH_COMMANDS);
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

        CompoundWorkBudget workBudget = compoundWorkBudget.get();
        boolean ownsWorkBudget = workBudget == null;
        if (ownsWorkBudget) {
            workBudget = new CompoundWorkBudget();
            compoundWorkBudget.set(workBudget);
        }
        final CompoundWorkBudget sharedWorkBudget = workBudget;
        final int workAtEntry = sharedWorkBudget.consumed;
        try {

        JsonArray results = new JsonArray();
        boolean halted = false;
        int firstFailureIdx = -1;
        long retainedBytes = 0L;
        int executedCount = 0;
        boolean responseTruncated = false;
        int budgetExhaustedAtIndex = -1;

        for (int i = 0; i < segments.size(); i++) {
            if (!sharedWorkBudget.tryConsume()) {
                halted = true;
                budgetExhaustedAtIndex = i;
                break;
            }
            JsonObject subReq = segments.get(i);
            JsonObject subResp = dispatchWithCompoundBudget(
                    subReq, caps, retainedBytes);
            executedCount++;
            long responseBytes = utf8Length(GSON.toJson(subResp));
            if (retainedBytes + responseBytes > MAX_BATCH_RESPONSE_BYTES) {
                responseTruncated = true;
                halted = true;
                if (firstFailureIdx < 0) firstFailureIdx = i;
                break;
            }
            results.add(subResp);
            retainedBytes += responseBytes;

            boolean failed = isFailure(subResp);
            if (failed && firstFailureIdx < 0) firstFailureIdx = i;
            if (failed && haltOnError) {
                halted = true;
                break;
            }
            if (sharedWorkBudget.exhausted) {
                halted = true;
                budgetExhaustedAtIndex = Math.min(i + 1, segments.size());
                break;
            }
        }

        JsonObject out = new JsonObject();
        out.add("results", results);
        out.addProperty("executed", executedCount);
        out.addProperty("retained_responses", results.size());
        out.addProperty("omitted_responses", executedCount - results.size());
        out.addProperty("omitted_commands", segments.size() - executedCount);
        out.addProperty("total", segments.size());
        out.addProperty("halted", halted);
        out.addProperty("response_truncated", responseTruncated);
        out.addProperty("retained_response_bytes", retainedBytes);
        out.addProperty("max_response_bytes", MAX_BATCH_RESPONSE_BYTES);
        out.addProperty("work_executed",
                sharedWorkBudget.consumed - workAtEntry);
        out.addProperty("work_budget_remaining", sharedWorkBudget.remaining());
        out.addProperty("max_work", MAX_COMPOUND_WORK);
        out.addProperty("work_budget_exhausted", sharedWorkBudget.exhausted);
        if (budgetExhaustedAtIndex >= 0) {
            out.addProperty("budget_exhausted_at_index", budgetExhaustedAtIndex);
        }
        if (firstFailureIdx >= 0) {
            out.addProperty("firstFailureIndex", firstFailureIdx);
        }
        return successResponse(out);
        } finally {
            if (ownsWorkBudget) compoundWorkBudget.remove();
        }
    }

    private JsonObject dispatchWithCompoundBudget(JsonObject request,
                                                   AgentCaps caps,
                                                   long retainedBytes) {
        long prior = compoundResponseBudget.get().longValue();
        long localRemaining = Math.max(0L,
                MAX_BATCH_RESPONSE_BYTES - Math.max(0L, retainedBytes));
        compoundResponseBudget.set(Long.valueOf(Math.min(prior, localRemaining)));
        try {
            return dispatch(request, caps);
        } finally {
            compoundResponseBudget.set(Long.valueOf(prior));
        }
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

    static boolean clearFrictionLogAllowed() {
        return "true".equalsIgnoreCase(
                System.getProperty("imagejai.allow.clear_friction_log", "false"));
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
    private JsonObject handleExecuteMacroAsync(final JsonObject request,
                                               final AgentCaps caps) {
        JsonElement codeEl = request.get("code");
        if (codeEl == null || !codeEl.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for execute_macro_async");
        }
        final String owner = mutationOwner(caps);
        if (owner == null) {
            return errorResponse("execute_macro_async requires a durable session; call hello first");
        }
        final String code = codeEl.getAsString();
        final PluginNameValidator.Result validation =
                (caps != null && caps.fuzzyMatch)
                        ? PluginNameValidator.validate(code) : null;
        if (validation != null && validation.hasRejections()) {
            JsonObject rejected = new JsonObject();
            rejected.addProperty("success", false);
            rejected.add("error", PluginNameValidator
                    .buildPluginNotFoundError(validation.rejections)
                    .buildJsonElement(caps));
            return successResponse(rejected);
        }
        final String codeToRun = validation != null && validation.hasCorrections()
                ? validation.patchedCode : code;
        final String source = optString(request, "source", "tcp-async");
        final SessionCodeJournal.DatasetBinding journalDataset =
                SessionCodeJournal.captureInitiatingDataset();
        final long timeoutMs = resolveTimeoutMs(request, MACRO_TIMEOUT_MS);
        final boolean safetyEnabled = isScientificIntegrityScanEnabled(caps);
        final boolean undoEnabled = caps != null && caps.undo;
        final List<DestructiveScanner.DestructiveOp> safetyFindings = safetyEnabled
                ? DestructiveScanner.scan(codeToRun, captureScannerContext(caps))
                : java.util.Collections.<DestructiveScanner.DestructiveOp>emptyList();

        MutationCoordinator.Lifecycle<ExecutionResult> lifecycle =
                new MutationCoordinator.Lifecycle<ExecutionResult>() {
            private List<ImageGraph.ImageRef> graphImagesBefore;
            private ImageGraph.ImageRef graphActiveBefore;
            private long graphMarkerBefore;
            private SourceImageTagger sourceTagger;
            private boolean prepared;

            @Override public void checkSafety() throws Exception {
                enforceMacroSafety(safetyFindings, caps);
            }

            @Override public void beforeMutation() {
                graphImagesBefore = ImageGraph.captureOpenImages();
                graphActiveBefore = ImageGraph.captureActiveImage();
                graphMarkerBefore = imageGraph.currentMarker();
                prepared = true;
                if (undoEnabled) {
                    captureUndoFrameIfEnabled(nextCallId(), codeToRun, caps);
                }
                sourceTagger = SourceImageTagger.beginIfEnabled(
                        caps != null && caps.safeMode
                                && caps.safeModeOptions != null
                                && caps.safeModeOptions.autoSourceImageColumn,
                        codeToRun, WindowManager.getCurrentImage());
            }

            @Override public void afterMutation(
                    MutationCoordinator.Outcome<ExecutionResult> outcome) {
                if (!prepared) return;
                if (sourceTagger != null) {
                    sourceTagger.postExec(WindowManager.getCurrentImage());
                }
                List<ImageGraph.ImageRef> after = ImageGraph.captureOpenImages();
                imageGraph.trackImageChange(graphImagesBefore, graphActiveBefore,
                        after, codeToRun, "macro");
                if (caps != null && caps.graphDelta) {
                    // Materialise the delta while still serialized so later
                    // mutations cannot move the marker before provenance is observed.
                    imageGraph.deltaSince(graphMarkerBefore);
                }
            }

            @Override public void onCompletion(
                    MutationCoordinator.Completion<ExecutionResult> completion) {
                boolean success = completion.state() == MutationCoordinator.State.SUCCEEDED
                        && completion.result() != null
                        && completion.result().isSuccess();
                String failure = completion.error() == null
                        ? null : completion.error().getMessage();
                SessionCodeJournal.INSTANCE.record(journalDataset, "ijm", codeToRun, source, 0L,
                        completion.startedAtMs(), completion.elapsedMs(), success, failure);
            }
        };

        final JobRegistry.Job job;
        try {
            job = jobRegistry.submit(codeToRun, owner, timeoutMs,
                    safetyEnabled, undoEnabled, true, lifecycle);
        } catch (IllegalArgumentException | java.util.concurrent.RejectedExecutionException e) {
            return errorResponse("Mutation admission rejected: " + e.getMessage());
        }
        JsonObject result = new JsonObject();
        result.addProperty("job_id", job.id);
        result.addProperty("state", job.state);
        result.addProperty("startedAt", job.startedAt);
        if (validation != null && validation.hasCorrections()) {
            result.add("autocorrected",
                    PluginNameValidator.buildAutocorrectedArray(validation.corrections));
        }
        return successResponse(result);
    }

    private JsonObject handleJobStatus(JsonObject request, AgentCaps caps) {
        JsonElement idEl = request.get("job_id");
        if (idEl == null || !idEl.isJsonPrimitive()) {
            return errorResponse("Missing 'job_id' for job_status");
        }
        String owner = mutationOwner(caps);
        if (owner == null) return errorResponse("job_status requires a durable session");
        String id = idEl.getAsString();
        JobRegistry.Job j = jobRegistry.get(owner, id);
        if (j == null) return errorResponse("Unknown job_id: " + id);
        return successResponse(jobRegistry.toJson(j));
    }

    private JsonObject handleJobCancel(JsonObject request, AgentCaps caps) {
        JsonElement idEl = request.get("job_id");
        if (idEl == null || !idEl.isJsonPrimitive()) {
            return errorResponse("Missing 'job_id' for job_cancel");
        }
        String owner = mutationOwner(caps);
        if (owner == null) return errorResponse("job_cancel requires a durable session");
        String id = idEl.getAsString();
        JobRegistry.Job j = jobRegistry.get(owner, id);
        if (j == null) return errorResponse("Unknown job_id: " + id);
        boolean signalled = jobRegistry.cancel(owner, id);
        JsonObject result = new JsonObject();
        result.addProperty("job_id", id);
        result.addProperty("cancelled", signalled);
        result.addProperty("state", jobRegistry.toJson(j).get("state").getAsString());
        result.addProperty("workerExited", j.handle.isWorkerExited());
        return successResponse(result);
    }

    private JsonObject handleJobList(AgentCaps caps) {
        String owner = mutationOwner(caps);
        if (owner == null) return errorResponse("job_list requires a durable session");
        List<JobRegistry.Job> all = jobRegistry.list(owner);
        JsonArray arr = new JsonArray();
        for (JobRegistry.Job j : all) arr.add(jobRegistry.toSummaryJson(j));
        JsonObject result = new JsonObject();
        result.addProperty("count", arr.size());
        result.add("jobs", arr);
        return successResponse(result);
    }

    private static String mutationOwner(AgentCaps caps) {
        if (caps == null || caps.sessionId == null || caps.sessionId.trim().isEmpty()) {
            return null;
        }
        return caps.sessionId;
    }

    private static String mutationOwnerOrInternal(AgentCaps caps) {
        String owner = mutationOwner(caps);
        return owner == null ? "__imagejai_tcp_internal__" : owner;
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

    private JsonObject protocolError(String code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("category", "authentication");
        error.addProperty("retry_safe", false);
        response.add("error", error);
        return response;
    }

    private JsonObject sessionFailure(SessionCapsRegistry.Status status) {
        if (status == SessionCapsRegistry.Status.MISSING) {
            return protocolError("session_required",
                    "This command requires a negotiated session.");
        }
        if (status == SessionCapsRegistry.Status.EXPIRED) {
            return protocolError("session_expired",
                    "The protocol session has expired; negotiate a new session.");
        }
        if (status == SessionCapsRegistry.Status.TOKEN_MISMATCH) {
            return protocolError("session_token_mismatch",
                    "The session credentials are invalid.");
        }
        if (status == SessionCapsRegistry.Status.REVOKED) {
            return protocolError("session_revoked",
                    "The protocol session has been revoked.");
        }
        return protocolError("session_unknown",
                "The protocol session is not known to this server.");
    }

    private String errorJson(String message) {
        JsonObject response = errorResponse(message);
        return GSON.toJson(response);
    }

    private JsonObject executionResultToJson(ExecutionResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("success", result.isSuccess());
        if (result.isSuccess()) {
            addBoundedUtf8Property(json, "output",
                    result.getOutput() != null ? result.getOutput() : "",
                    MAX_RESULTS_TABLE_BYTES);
            addBoundedUtf8Property(json, "resultsTable",
                    result.getResultsTable() != null ? result.getResultsTable() : "",
                    MAX_RESULTS_TABLE_BYTES);
            if (result.isResultsTableTruncated()) {
                json.addProperty("resultsTable_truncated", true);
                json.addProperty("resultsTable_original_bytes",
                        result.getResultsTableOriginalBytes());
                json.addProperty("resultsTable_returned_bytes",
                        utf8Length(json.get("resultsTable").getAsString()));
                json.addProperty("resultsTable_total_rows",
                        result.getResultsTableTotalRows());
                json.addProperty("resultsTable_returned_rows",
                        result.getResultsTableReturnedRows());
            }
            JsonArray newImages = new JsonArray();
            List<String> images = result.getNewImages() == null
                    ? Collections.<String>emptyList() : result.getNewImages();
            int retained = Math.min(images.size(), JobRegistry.MAX_RESULT_IMAGES);
            for (int i = 0; i < retained; i++) {
                String img = images.get(i) == null ? "" : images.get(i);
                if (img.length() > JobRegistry.MAX_IMAGE_NAME_CHARS) {
                    img = img.substring(0, JobRegistry.MAX_IMAGE_NAME_CHARS);
                }
                newImages.add(new JsonPrimitive(img));
            }
            json.add("newImages", newImages);
            json.addProperty("newImages_truncated", images.size() > retained);
            json.addProperty("newImages_total", images.size());
            json.addProperty("executionTimeMs", result.getExecutionTimeMs());
        } else {
            json.addProperty("error", result.getError() != null ? result.getError() : "Unknown error");
        }
        return json;
    }

    static long utf8Length(String value) {
        if (value == null || value.isEmpty()) return 0L;
        long bytes = 0L;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            bytes += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            i += Character.charCount(cp);
        }
        return bytes;
    }

    static void addBoundedUtf8Property(JsonObject target, String key,
                                       String value, long maxBytes) {
        String safe = value == null ? "" : value;
        long originalBytes = utf8Length(safe);
        if (originalBytes <= maxBytes) {
            target.addProperty(key, safe);
            target.addProperty(key + "_truncated", false);
            target.addProperty(key + "_original_bytes", originalBytes);
            return;
        }
        int chars = 0;
        long returnedBytes = 0L;
        while (chars < safe.length()) {
            int cp = safe.codePointAt(chars);
            int cpBytes = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (returnedBytes + cpBytes > maxBytes) break;
            returnedBytes += cpBytes;
            chars += Character.charCount(cp);
        }
        target.addProperty(key, safe.substring(0, chars));
        target.addProperty(key + "_truncated", true);
        target.addProperty(key + "_original_bytes", originalBytes);
        target.addProperty(key + "_returned_bytes", returnedBytes);
    }

    static void addBoundedResultsCsv(JsonObject target,
                                     StateInspector.BoundedCsv csv) {
        StateInspector.BoundedCsv safe = csv == null
                ? new StateInspector.BoundedCsv("", 0L, 0, 0, 0) : csv;
        target.addProperty("resultsTable", safe.text());
        target.addProperty("resultsTable_truncated", safe.truncated());
        target.addProperty("resultsTable_original_bytes", safe.originalBytes());
        target.addProperty("resultsTable_returned_bytes", safe.returnedBytes());
        target.addProperty("resultsTable_total_rows", safe.totalRows());
        target.addProperty("resultsTable_returned_rows", safe.returnedRows());
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

    /** Maximum raw bytes whose base64 JSON representation can fit the budget. */
    static int maxBinaryBytesForCompoundBudget(long remainingBytes, int absoluteMax) {
        if (absoluteMax <= 0) return 0;
        if (remainingBytes == Long.MAX_VALUE) return absoluteMax;
        long usable = remainingBytes - COMPOUND_RESPONSE_OVERHEAD_BYTES;
        if (usable < 4L) return 0;
        long raw = (usable / 4L) * 3L;
        return (int) Math.min((long) absoluteMax, Math.min(raw, Integer.MAX_VALUE));
    }

    private static long base64EncodedLength(long rawBytes) {
        if (rawBytes <= 0L) return 0L;
        return 4L * ((rawBytes + 2L) / 3L);
    }

    private JsonObject compoundBudgetError(String command, long remainingBytes) {
        JsonObject error = errorResponse("compound_response_budget: " + command
                + " output cannot fit the remaining compound response budget");
        error.addProperty("error_code", "compound_response_budget");
        error.addProperty("remaining_response_bytes", Math.max(0L, remainingBytes));
        return error;
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
        final long responseBudget = compoundResponseBudget.get().longValue();
        final int reqX;
        final int reqY;
        final int reqW;
        final int reqH;
        final int reqSlice;
        final boolean allSlices;
        try {
            reqX = request.has("x") ? request.get("x").getAsInt() : -1;
            reqY = request.has("y") ? request.get("y").getAsInt() : -1;
            reqW = request.has("width") ? request.get("width").getAsInt() : -1;
            reqH = request.has("height") ? request.get("height").getAsInt() : -1;
            reqSlice = request.has("slice") ? request.get("slice").getAsInt() : -1;
            allSlices = request.has("allSlices")
                    && request.get("allSlices").getAsBoolean();
        } catch (RuntimeException e) {
            return errorResponse("Invalid get_pixels parameters");
        }

        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
            @Override
            public void run() {
                ImagePlus imp = null;
                int originalC = 1, originalZ = 1, originalT = 1;
                try {
                    imp = currentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                        return;
                    }
                    originalC = imp.getC();
                    originalZ = imp.getZ();
                    originalT = imp.getT();

                    int imgW = imp.getWidth();
                    int imgH = imp.getHeight();
                    int nSlices = imp.getStackSize();
                    if (imgW <= 0 || imgH <= 0 || nSlices <= 0) {
                        holder[0] = new Exception("Image has invalid dimensions");
                        return;
                    }

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
                    long totalPixels;
                    long rawByteCount;
                    try {
                        totalPixels = Math.multiplyExact(
                                Math.multiplyExact((long) w, (long) h),
                                (long) sliceCount);
                        rawByteCount = Math.multiplyExact(totalPixels, 4L);
                    } catch (ArithmeticException overflow) {
                        holder[0] = new Exception("Requested pixel allocation overflows");
                        return;
                    }
                    if (totalPixels > 4000000L || rawByteCount > Integer.MAX_VALUE) {
                        holder[0] = new Exception("Region too large: " + totalPixels
                                + " pixels. Max 4M. Use x/y/width/height to crop.");
                        return;
                    }
                    if (responseBudget != Long.MAX_VALUE
                            && base64EncodedLength(rawByteCount)
                            + COMPOUND_RESPONSE_OVERHEAD_BYTES > responseBudget) {
                        holder[0] = "COMPOUND_BUDGET";
                        return;
                    }

                    // Allocate only after all dimensions and byte arithmetic
                    // have been bounded. Read processors directly from the
                    // stack so extraction does not navigate the live image.
                    byte[] rawBytes = new byte[(int) rawByteCount];
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(rawBytes);
                    buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (int s = startSlice; s <= endSlice; s++) {
                        ij.process.ImageProcessor ip = imp.getStack().getProcessor(s);
                        for (int py = y; py < y + h; py++) {
                            for (int px = x; px < x + w; px++) {
                                buf.putFloat(ip.getPixelValue(px, py));
                            }
                        }
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
                    result.addProperty("nPixels", totalPixels);
                    result.addProperty("type", imp.getBitDepth() + "-bit");
                    result.addProperty("encoding", "base64_float32_le");
                    result.addProperty("data", b64);

                    holder[0] = result;
                } catch (Throwable e) {
                    holder[0] = e;
                } finally {
                    if (imp != null) {
                        try {
                            imp.setPositionWithoutUpdate(originalC, originalZ, originalT);
                        } catch (Throwable ignored) {}
                    }
                    latch.countDown();
                }
            }
        });

        try {
            long timeoutMs = Math.max(1L, Math.min(120000L,
                    resolveTimeoutMs(request, 30000L)));
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                actionToken.invalidate();
                return errorResponse("Timed out getting pixels");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Throwable) {
            Throwable failure = (Throwable) holder[0];
            return errorResponse("Error: " + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        if ("COMPOUND_BUDGET".equals(holder[0])) {
            return compoundBudgetError("get_pixels", responseBudget);
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
                actionToken.invalidate();
                return errorResponse("Timed out detecting dialogs");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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
            dialogInfo.addProperty("title", AgentContextSanitizer.wrap(title, "DIALOG"));
            dialogInfo.addProperty("text",
                    AgentContextSanitizer.wrap(textContent.toString().trim(), "DIALOG"));
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
    static boolean clickCancelButton(Container root) {
        if (root == null) return false;
        for (Component c : root.getComponents()) {
            if (c instanceof java.awt.Button) {
                String lbl = ((java.awt.Button) c).getLabel();
                if (isCancelLabel(lbl)) {
                    ((java.awt.Button) c).dispatchEvent(
                            new java.awt.event.ActionEvent(c,
                                    java.awt.event.ActionEvent.ACTION_PERFORMED,
                                    lbl));
                    return true;
                }
            } else if (c instanceof javax.swing.JButton) {
                String lbl = ((javax.swing.JButton) c).getText();
                if (isCancelLabel(lbl)) {
                    ((javax.swing.JButton) c).doClick();
                    return true;
                }
            }
            if (c instanceof Container) {
                if (clickCancelButton((Container) c)) return true;
            }
        }
        return false;
    }

    static boolean hasCancelButton(Container root) {
        if (root == null) return false;
        for (Component c : root.getComponents()) {
            if (c instanceof java.awt.Button
                    && isCancelLabel(((java.awt.Button) c).getLabel())) return true;
            if (c instanceof javax.swing.JButton
                    && isCancelLabel(((javax.swing.JButton) c).getText())) return true;
            if (c instanceof Container && hasCancelButton((Container) c)) return true;
        }
        return false;
    }

    private static boolean markGenericDialogCancelled(Dialog dialog) {
        if (dialog == null) return false;
        Class<?> c = dialog.getClass();
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("wasCanceled");
                f.setAccessible(true);
                f.setBoolean(dialog, true);
                return true;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasGenericCancelFlag(Dialog dialog) {
        if (dialog == null) return false;
        Class<?> c = dialog.getClass();
        while (c != null && c != Object.class) {
            try {
                c.getDeclaredField("wasCanceled");
                return true;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isCancelLabel(String lbl) {
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
            if (!latch.await(2000, TimeUnit.MILLISECONDS)) {
                actionToken.invalidate();
                return 0;
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
            Thread.currentThread().interrupt();
            return 0;
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
            JsonObject response = errorResponse("probe_unsupported: no cancellable dialog appeared");
            response.addProperty("plugin", pluginName);
            response.addProperty("hasDialog", false);
            response.addProperty("side_effect_risk", true);
            response.addProperty("plugin_action_may_have_executed", true);
            return response;
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

        final boolean genericDialog = isGD;
        final boolean verifiedCancelRoute = genericDialog
                ? hasGenericCancelFlag(newDialog)
                : hasCancelButton(newDialog);
        if (!verifiedCancelRoute) {
            JsonObject response = errorResponse(
                    "probe_unsupported_dialog: no verified cancel/close route");
            response.addProperty("plugin", pluginName);
            response.addProperty("hasDialog", true);
            response.addProperty("dialogTitle",
                    newDialog.getTitle() == null ? "" : newDialog.getTitle());
            response.addProperty("side_effect_risk", true);
            response.addProperty("plugin_action_executed", false);
            response.addProperty("dialog_left_open", true);
            return response;
        }

        result.addProperty("side_effect_risk", true);
        result.addProperty("side_effect_risk_detail",
                "The plugin is launched until its dialog appears; initialization may have side effects.");
        result.addProperty("plugin_action_executed", false);
        result.addProperty("cancel_route", genericDialog
                ? "GenericDialog.wasCanceled" : "cancel_button");

        if (genericDialog) {
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
        final boolean[] cancelled = new boolean[1];
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        GuiActionDispatcher.ActionToken cancelToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
            @Override
            public void run() {
                try {
                    cancelled[0] = genericDialog
                            ? markGenericDialogCancelled(dlg)
                            : clickCancelButton(dlg);
                    if (cancelled[0]) dlg.dispose();
                } finally {
                    cancelLatch.countDown();
                }
            }
        });

        try {
            if (!cancelLatch.await(2000L, TimeUnit.MILLISECONDS)) {
                cancelToken.invalidate();
                JsonObject response = errorResponse("probe_cancel_timed_out");
                response.addProperty("side_effect_risk", true);
                response.addProperty("dialog_left_open", true);
                return response;
            }
        } catch (InterruptedException e) {
            cancelToken.invalidate();
            Thread.currentThread().interrupt();
            JsonObject response = errorResponse("probe_interrupted");
            response.addProperty("side_effect_risk", true);
            return response;
        }
        if (!cancelled[0]) {
            JsonObject response = errorResponse("probe_cancel_failed");
            response.addProperty("side_effect_risk", true);
            response.addProperty("dialog_left_open", true);
            return response;
        }

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
        final List<ImageGraph.ImageRef> graphImagesBefore =
                ImageGraph.captureOpenImages();
        final ImageGraph.ImageRef graphActiveBefore =
                ImageGraph.captureActiveImage();
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

        GuiActionDispatcher.ActionToken actionToken =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
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
                actionToken.invalidate();
                return errorResponse("interact_dialog timed out");
            }
        } catch (InterruptedException e) {
            actionToken.invalidate();
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
            List<ImageGraph.ImageRef> graphImagesAfter =
                    ImageGraph.captureOpenImages();
            imageGraph.trackImageChange(graphImagesBefore, graphActiveBefore,
                    graphImagesAfter, graphInteractionLabel, "dialog");
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
