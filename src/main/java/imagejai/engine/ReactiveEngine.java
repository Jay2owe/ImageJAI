package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.plugin.frame.RoiManager;
import imagejai.engine.safeMode.DestructiveScanner;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Phase 8: reactive rules engine.
 *
 * <p>Loads rule files from {@code ~/.imagej-ai/reactive/*.json}, subscribes to
 * {@link EventBus} with pattern {@code *}, and fires matching rules' action
 * lists. Supported actions: {@code execute_macro}, {@code publish_event},
 * {@code gui_action}, {@code run_intent}, {@code close_dialog}, {@code capture},
 * {@code wait}.
 *
 * <p><b>Format — JSON, not YAML.</b> The design spec (§Risks) flags the absence
 * of a YAML parser in Fiji's shipped dependency set. To honour the project's
 * "no new Maven dependencies" constraint we use JSON files instead of YAML.
 * Convert YAML rules with a one-liner:
 * <pre>
 *   python -c "import yaml,json,sys; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))" rule.yaml > rule.json
 * </pre>
 * See {@code docs/reactive_rules_format.md} for the full schema reference.
 *
 * <p><b>Lock file.</b> If {@code ~/.imagej-ai/reactive/reactive.lock} exists on
 * disk, the engine skips all dispatch until the file is removed. Useful for a
 * hard kill-switch when a rule misbehaves.
 *
 * <p><b>Graceful degrade.</b> {@code gui_action} and {@code run_intent} depend
 * on {@code GuiActionDispatcher} (Phase 7) and {@code IntentRouter} (Phase 5)
 * respectively. When those classes are not yet present the engine skips the
 * action with a clear log message instead of crashing. Dependencies are passed
 * as {@code Object} references — callers may pass {@code null}.
 *
 * <p><b>Safety.</b> Rate limiting (token-bucket, per rule) and a 1-second
 * cycle warning protect against self-triggering loops. Malformed rule files
 * are quarantined and exposed via {@link #getQuarantined()} rather than
 * blocking the rest of the rule set.
 */
public class ReactiveEngine {

    private static final Path HOME = Paths.get(System.getProperty("user.home"));
    private static final Path DEFAULT_RULES_DIR =
            HOME.resolve(".imagej-ai").resolve("reactive");

    private static final long RELOAD_DEBOUNCE_MS = 500L;
    private static final long CYCLE_WARN_WINDOW_MS = 1000L;
    static final int DEFAULT_ACTION_CAPACITY = 32;
    static final long DEFAULT_ACTION_TTL_MS = 10_000L;
    static final int DEFAULT_FAILURE_QUARANTINE_THRESHOLD = 3;
    static final int MAX_CAPTURE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CAPTURE_BASE_CHARS = 80;
    private static final Set<String> ACTION_KEYS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("execute_macro", "publish_event",
                    "gui_action", "run_intent", "close_dialog", "capture", "wait")));

    interface MutationPolicy {
        void checkSafety(String ruleName, String sourceKind, String code) throws Exception;
        void beforeMutation(String ruleName, String sourceKind, String code) throws Exception;
        void afterMutation(String ruleName, String sourceKind, String code,
                           MutationCoordinator.Outcome<?> outcome) throws Exception;
        void onCompletion(String ruleName, String sourceKind, String code,
                          MutationCoordinator.Completion<?> completion);
    }

    interface CaptureBackend {
        CaptureData capture() throws Exception;
    }

    static final class CaptureData {
        final byte[] png;
        final Path exportDir;
        CaptureData(byte[] png, Path exportDir) {
            this.png = png;
            this.exportDir = exportDir;
        }
    }

    private final EventBus bus;
    private final CommandEngine cmdEngine;
    private final MutationCoordinator mutationCoordinator;
    private final boolean ownsMutationCoordinator;
    private final Path rulesDir;
    private final Path lockFile;
    private final LongSupplier clock;
    private final int actionCapacity;
    private final long actionTtlMs;
    private final int failureQuarantineThreshold;
    private final MutationPolicy mutationPolicy;
    private final CaptureBackend captureBackend;
    private final AtomicLong captureSequence = new AtomicLong();
    // Phase 5/7 dependencies — volatile so the TCP server can swap the GUI
    // dispatcher when the chat panel controller (re)attaches. Typed as Object
    // so the engine compiles even before those classes are merged, and so the
    // caller can pass null for graceful degrade.
    private volatile Object intentRouter;
    private volatile Object guiDispatcher;

    private final CopyOnWriteArrayList<Rule> rules = new CopyOnWriteArrayList<Rule>();
    private final CopyOnWriteArrayList<Quarantined> quarantined = new CopyOnWriteArrayList<Quarantined>();
    private final ConcurrentHashMap<String, Boolean> enabledOverrides =
            new ConcurrentHashMap<String, Boolean>();

    // Cycle detector: rule.name -> (topic -> last publish ms by this rule).
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> lastPublishByRule =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>();

    private volatile boolean started;
    private volatile boolean stopping;
    private Thread watchThread;
    private WatchService watchService;
    private ExecutorService actionExecutor;
    private volatile Semaphore actionPermits;
    private EventBus.Listener listener;

    /** Construct an engine that can dispatch macros + bus events only. */
    public ReactiveEngine(EventBus bus, CommandEngine cmd) {
        this(bus, cmd, null, null);
    }

    /**
     * @param intent Phase 5 {@code IntentRouter} instance, or {@code null} to
     *               disable {@code run_intent} actions.
     * @param gui    Phase 7 {@code GuiActionDispatcher} instance, or {@code null}
     *               to disable {@code gui_action} actions.
     */
    public ReactiveEngine(EventBus bus, CommandEngine cmd, Object intent, Object gui) {
        this(bus, cmd, intent, gui, new MutationCoordinator(), true);
    }

    /**
     * Construct an engine on the application-owned mutation boundary.
     * Reactive mutations must share this coordinator with every other ImageJ
     * producer so a rule can never overlap a TCP or assistant mutation.
     */
    public ReactiveEngine(EventBus bus, CommandEngine cmd, Object intent, Object gui,
                          MutationCoordinator coordinator) {
        this(bus, cmd, intent, gui, coordinator, false);
    }

    private ReactiveEngine(EventBus bus, CommandEngine cmd, Object intent, Object gui,
                           MutationCoordinator coordinator, boolean ownsCoordinator) {
        this(bus, cmd, intent, gui, coordinator, ownsCoordinator,
                DEFAULT_RULES_DIR, systemClock(), DEFAULT_ACTION_CAPACITY,
                DEFAULT_ACTION_TTL_MS, DEFAULT_FAILURE_QUARANTINE_THRESHOLD,
                new DefaultMutationPolicy(), new DefaultCaptureBackend());
    }

    ReactiveEngine(EventBus bus, CommandEngine cmd, Object intent, Object gui,
                   MutationCoordinator coordinator, Path rulesDirectory,
                   LongSupplier clock, int actionCapacity, long actionTtlMs,
                   int failureThreshold, MutationPolicy policy,
                   CaptureBackend captureBackend) {
        this(bus, cmd, intent, gui, coordinator, false, rulesDirectory, clock,
                actionCapacity, actionTtlMs, failureThreshold, policy, captureBackend);
    }

    private ReactiveEngine(EventBus bus, CommandEngine cmd, Object intent, Object gui,
                           MutationCoordinator coordinator, boolean ownsCoordinator,
                           Path rulesDirectory, LongSupplier suppliedClock,
                           int suppliedCapacity, long suppliedTtlMs,
                           int suppliedFailureThreshold, MutationPolicy policy,
                           CaptureBackend suppliedCaptureBackend) {
        this.bus = bus;
        this.cmdEngine = cmd;
        this.intentRouter = intent;
        this.guiDispatcher = gui;
        if (coordinator == null) {
            throw new IllegalArgumentException("mutation coordinator is required");
        }
        this.mutationCoordinator = coordinator;
        this.ownsMutationCoordinator = ownsCoordinator;
        this.rulesDir = (rulesDirectory == null ? DEFAULT_RULES_DIR : rulesDirectory)
                .toAbsolutePath().normalize();
        this.lockFile = this.rulesDir.resolve("reactive.lock");
        this.clock = suppliedClock == null ? systemClock() : suppliedClock;
        this.actionCapacity = Math.max(1, suppliedCapacity);
        this.actionTtlMs = Math.max(1L, suppliedTtlMs);
        this.failureQuarantineThreshold = Math.max(1, suppliedFailureThreshold);
        this.mutationPolicy = policy == null ? new DefaultMutationPolicy() : policy;
        this.captureBackend = suppliedCaptureBackend == null
                ? new DefaultCaptureBackend() : suppliedCaptureBackend;
    }

    private static LongSupplier systemClock() {
        return new LongSupplier() {
            @Override public long getAsLong() { return System.currentTimeMillis(); }
        };
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized void start() {
        if (started) return;
        started = true;
        stopping = false;
        actionPermits = new Semaphore(actionCapacity, true);

        actionExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "imagej-ai-reactive-actions");
                t.setDaemon(true);
                return t;
            }
        });

        try {
            Files.createDirectories(rulesDir);
        } catch (IOException e) {
            logWarn("Failed to create rules dir " + rulesDir + ": " + e.getMessage());
        }

        reload();

        listener = new EventBus.Listener() {
            @Override
            public void onEvent(JsonObject frame) {
                try {
                    onBusEvent(frame);
                } catch (Throwable t) {
                    logWarn("Dispatch failed: " + t.getMessage());
                }
            }
        };
        bus.subscribe("*", listener);

        startWatcher();

        logInfo("Reactive engine started (" + rules.size() + " rules, "
                + quarantined.size() + " quarantined)");
    }

    public synchronized void stop() {
        if (!started) return;
        stopping = true;
        started = false;
        if (listener != null) {
            bus.unsubscribe(listener);
            listener = null;
        }
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignore) {}
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (actionExecutor != null) {
            List<Runnable> abandoned = actionExecutor.shutdownNow();
            for (Runnable pending : abandoned) {
                if (pending instanceof QueuedRule) {
                    ((QueuedRule) pending).releasePermit();
                }
            }
            actionExecutor = null;
        }
        logInfo("Reactive engine stopped");
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public List<Rule> getRules() {
        return new ArrayList<Rule>(rules);
    }

    public List<Quarantined> getQuarantined() {
        return new ArrayList<Quarantined>(quarantined);
    }

    public boolean isLocked() {
        return Files.exists(lockFile);
    }

    public synchronized boolean setEnabled(String name, boolean enabled) {
        for (Rule r : rules) {
            if (r.name.equals(name)) {
                r.enabled = enabled;
                enabledOverrides.put(name, Boolean.valueOf(enabled));
                return true;
            }
        }
        return false;
    }

    /** Swap the GUI dispatcher at runtime (e.g. when chat panel reattaches). */
    public void setGuiDispatcher(Object gui) {
        this.guiDispatcher = gui;
    }

    /** Swap the intent router at runtime. */
    public void setIntentRouter(Object intent) {
        this.intentRouter = intent;
    }

    // ------------------------------------------------------------------
    // Rule loading
    // ------------------------------------------------------------------

    public synchronized void reload() {
        List<Rule> loaded = new ArrayList<Rule>();
        List<Quarantined> qu = new ArrayList<Quarantined>();
        Set<String> loadedNames = new HashSet<String>();

        File dir = rulesDir.toFile();
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getAbsolutePath().compareTo(b.getAbsolutePath());
                    }
                });
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    if (!name.toLowerCase().endsWith(".json")) continue;
                    if ("reactive.lock".equals(name)) continue;
                    try {
                        Rule rule = parseRule(f);
                        if (!loadedNames.add(rule.name)) {
                            throw new IllegalArgumentException(
                                    "Duplicate rule name '" + rule.name + "'");
                        }
                        loaded.add(rule);
                    } catch (Exception e) {
                        qu.add(new Quarantined(f.getAbsolutePath(), e.getMessage() != null
                                ? e.getMessage() : e.toString()));
                        logWarn("Quarantined " + name + ": " + e.getMessage());
                    }
                }
            }
        }

        Collections.sort(loaded, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                int p = Integer.compare(a.priority, b.priority);
                if (p != 0) return p;
                return a.sourceFile.compareTo(b.sourceFile);
            }
        });

        // File configuration is authoritative unless the user explicitly set
        // a runtime override through reactive_enable/reactive_disable. Runtime
        // quarantine survives an unchanged reload but a file edit rehabilitates
        // the rule so a corrected action can run immediately.
        Map<String, Rule> existing = new HashMap<String, Rule>();
        for (Rule r : rules) existing.put(r.name, r);
        for (Rule r : loaded) {
            Boolean override = enabledOverrides.get(r.name);
            if (override != null) r.enabled = override.booleanValue();
            Rule prev = existing.get(r.name);
            if (prev != null) {
                r.hits = prev.hits;
                r.lastFired = prev.lastFired;
                if (prev.sourceModified == r.sourceModified && prev.runtimeQuarantined) {
                    r.runtimeQuarantined = true;
                    r.quarantineReason = prev.quarantineReason;
                    r.consecutiveFailures.set(prev.consecutiveFailures.get());
                    qu.add(new Quarantined(r.sourceFile, r.quarantineReason));
                }
            }
        }
        rules.clear();
        rules.addAll(loaded);
        quarantined.clear();
        quarantined.addAll(qu);
        lastPublishByRule.keySet().retainAll(loadedNames);

        for (Quarantined entry : qu) {
            publishDiagnostic("reactive.reload_error", null, entry.error);
        }
    }

    private Rule parseRule(File f) throws IOException {
        JsonObject obj;
        InputStreamReader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseReader(reader);
            if (el == null || !el.isJsonObject()) {
                throw new IllegalArgumentException("Top-level must be a JSON object");
            }
            obj = el.getAsJsonObject();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignore) {}
            }
        }

        String name = getString(obj, "name", null);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Missing required field 'name'");
        }
        String desc = getString(obj, "description", "");
        boolean enabled = getBool(obj, "enabled", true);
        if (obj.has("enabled") && (!obj.get("enabled").isJsonPrimitive()
                || !obj.getAsJsonPrimitive("enabled").isBoolean())) {
            throw new IllegalArgumentException("Field 'enabled' must be boolean");
        }
        int priority = getInt(obj, "priority", 100);

        JsonElement whenEl = obj.get("when");
        if (whenEl == null || !whenEl.isJsonObject()) {
            throw new IllegalArgumentException("Missing required object 'when'");
        }
        JsonObject when = whenEl.getAsJsonObject();
        String event = getString(when, "event", null);
        if (event == null || event.isEmpty()) {
            throw new IllegalArgumentException("Missing 'when.event'");
        }
        JsonObject where = null;
        if (when.has("where") && when.get("where").isJsonObject()) {
            where = when.getAsJsonObject("where");
        }

        JsonElement doEl = obj.get("do");
        if (doEl == null || !doEl.isJsonArray()) {
            throw new IllegalArgumentException("Missing required array 'do'");
        }
        JsonArray doArr = doEl.getAsJsonArray();
        if (doArr.size() == 0) {
            throw new IllegalArgumentException("Array 'do' must not be empty");
        }
        List<JsonObject> actions = new ArrayList<JsonObject>();
        for (int i = 0; i < doArr.size(); i++) {
            JsonElement ae = doArr.get(i);
            if (ae == null || !ae.isJsonObject()) {
                throw new IllegalArgumentException("Action #" + i + " must be an object");
            }
            JsonObject action = ae.getAsJsonObject();
            validateAction(action, i);
            actions.add(action.deepCopy());
        }

        String rateLimit = getString(obj, "rate_limit", null);
        RateLimiter rl = (rateLimit != null && !rateLimit.isEmpty())
                ? RateLimiter.parse(rateLimit)
                : null;
        if (rateLimit != null && !rateLimit.isEmpty() && rl == null) {
            throw new IllegalArgumentException("Unrecognised rate_limit: " + rateLimit);
        }

        long waitBefore = 0L;
        if (obj.has("wait_before")) {
            JsonElement wbe = obj.get("wait_before");
            if (wbe != null && wbe.isJsonPrimitive()) {
                try { waitBefore = wbe.getAsLong(); } catch (Exception ignore) {}
            }
        }
        if (waitBefore < 0L || waitBefore >= actionTtlMs) {
            throw new IllegalArgumentException("wait_before must be >= 0 and below action TTL");
        }

        Rule r = new Rule();
        r.name = name;
        r.description = desc;
        r.enabled = enabled;
        r.priority = priority;
        r.event = event;
        r.where = where;
        r.actions = actions;
        r.rateLimitSpec = rateLimit;
        r.rateLimiter = rl;
        r.waitBefore = waitBefore;
        r.sourceFile = f.getAbsolutePath();
        r.sourceModified = f.lastModified();
        return r;
    }

    private void validateAction(JsonObject action, int index) {
        if (action == null || action.entrySet().size() != 1) {
            throw new IllegalArgumentException(
                    "Action #" + index + " must contain exactly one action key");
        }
        String key = action.entrySet().iterator().next().getKey();
        JsonElement value = action.get(key);
        if (!ACTION_KEYS.contains(key)) {
            throw new IllegalArgumentException("Action #" + index
                    + " has unknown key '" + key + "'");
        }
        if ("execute_macro".equals(key) || "run_intent".equals(key)) {
            if (value == null || !value.isJsonPrimitive()
                    || value.getAsString().trim().isEmpty()) {
                throw new IllegalArgumentException("Action #" + index + " '"
                        + key + "' must be a non-empty string");
            }
        } else if ("publish_event".equals(key)) {
            if (value == null || !value.isJsonObject()
                    || getString(value.getAsJsonObject(), "topic", "").trim().isEmpty()) {
                throw new IllegalArgumentException("Action #" + index
                        + " publish_event requires a non-empty topic");
            }
        } else if ("gui_action".equals(key) || "close_dialog".equals(key)) {
            if (value == null || !value.isJsonObject()) {
                throw new IllegalArgumentException("Action #" + index + " '"
                        + key + "' must be an object");
            }
        } else if ("capture".equals(key)) {
            if (value != null && !value.isJsonNull() && !value.isJsonPrimitive()) {
                throw new IllegalArgumentException("Action #" + index
                        + " capture must be a string or null");
            }
        } else if ("wait".equals(key)) {
            long waitMs = parseWaitMs(value);
            if (waitMs < 0L || waitMs >= actionTtlMs) {
                throw new IllegalArgumentException("Action #" + index
                        + " wait must be non-negative and below action TTL");
            }
        }
    }

    // ------------------------------------------------------------------
    // Event dispatch
    // ------------------------------------------------------------------

    private void onBusEvent(JsonObject frame) {
        if (frame == null) return;
        if (!started || stopping || Files.exists(lockFile)) return;

        JsonElement tEl = frame.get("event");
        if (tEl == null || !tEl.isJsonPrimitive()) return;
        final String topic = tEl.getAsString();
        if (topic == null) return;

        final JsonObject data = (frame.has("data") && frame.get("data").isJsonObject())
                ? frame.getAsJsonObject("data")
                : new JsonObject();
        if (getBool(data, "_reactive_internal", false)) return;

        for (final Rule r : rules) {
            if (!topicMatches(r.event, topic)) continue;
            if (!whereMatches(r.where, data)) continue;
            if (!r.enabled) {
                publishDiagnostic("reactive.rejected", r, "disabled");
                continue;
            }
            if (r.runtimeQuarantined) {
                publishDiagnostic("reactive.rejected", r, "quarantined");
                continue;
            }

            // Cycle detection — did this rule publish this topic within 1s?
            long now = clock.getAsLong();
            ConcurrentHashMap<String, Long> pubMap = lastPublishByRule.get(r.name);
            if (pubMap != null) {
                Long ts = pubMap.get(topic);
                if (ts != null && (now - ts.longValue()) >= 0L
                        && (now - ts.longValue()) < CYCLE_WARN_WINDOW_MS) {
                    quarantineRuntime(r, "cycle detected on topic '" + topic + "'");
                    logWarn("Rule '" + r.name + "' matched on '" + topic
                            + "' it published " + (now - ts.longValue())
                            + "ms ago — possible feedback loop (rate_limit still enforced)");
                }
            }
            if (r.runtimeQuarantined) continue;

            if (r.rateLimiter != null && !r.rateLimiter.tryAcquire()) {
                continue;
            }
            Semaphore permits = actionPermits;
            if (permits == null || !permits.tryAcquire()) {
                publishDiagnostic("reactive.rejected", r, "queue_saturated");
                continue;
            }
            final ExecutorService ex = actionExecutor;
            if (ex == null || stopping || !started) {
                permits.release();
                publishDiagnostic("reactive.rejected", r, "engine_stopped");
                continue;
            }
            QueuedRule queued = new QueuedRule(r, frame.deepCopy(), now,
                    safeDeadline(now, actionTtlMs), permits);
            try {
                ex.execute(queued);
                r.hits++;
                r.lastFired = now;
            } catch (RejectedExecutionException rejected) {
                queued.releasePermit();
                publishDiagnostic("reactive.rejected", r, "executor_rejected");
            }
        }
    }

    private void fireRule(Rule r, JsonObject frame, long deadline) {
        String rejection = executionRejectionReason(r, deadline);
        if (rejection != null) {
            publishDiagnostic("reactive.rejected", r, rejection);
            return;
        }
        if (r.waitBefore > 0) {
            try { Thread.sleep(r.waitBefore); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        rejection = executionRejectionReason(r, deadline);
        if (rejection != null) {
            publishDiagnostic("reactive.rejected", r, rejection);
            return;
        }
        for (JsonObject action : r.actions) {
            rejection = executionRejectionReason(r, deadline);
            if (rejection != null) {
                publishDiagnostic("reactive.rejected", r, rejection);
                return;
            }
            try {
                executeAction(r, action, frame);
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                logWarn("Rule '" + r.name + "' action failed: " + t.getMessage());
                recordRuleFailure(r, t);
                return;
            }
        }
        r.consecutiveFailures.set(0);
    }

    private String executionRejectionReason(Rule r, long deadline) {
        if (!started || stopping) return "engine_stopped";
        if (Files.exists(lockFile)) return "locked";
        if (!r.enabled) return "disabled";
        if (r.runtimeQuarantined) return "quarantined";
        if (clock.getAsLong() > deadline) return "expired";
        return null;
    }

    private void recordRuleFailure(Rule rule, Throwable failure) {
        int failures = rule.consecutiveFailures.incrementAndGet();
        String detail = failure == null || failure.getMessage() == null
                ? "unknown failure" : failure.getMessage();
        publishDiagnostic("reactive.action_failed", rule,
                "failure " + failures + "/" + failureQuarantineThreshold + ": " + detail);
        if (failures >= failureQuarantineThreshold) {
            quarantineRuntime(rule, "repeated action failures: " + detail);
        }
    }

    private void quarantineRuntime(Rule rule, String reason) {
        if (rule == null) return;
        synchronized (rule) {
            if (rule.runtimeQuarantined) return;
            rule.runtimeQuarantined = true;
            rule.quarantineReason = boundedReason(reason);
        }
        quarantined.add(new Quarantined(rule.sourceFile, rule.quarantineReason));
        lastPublishByRule.remove(rule.name);
        logWarn("Quarantined rule '" + rule.name + "': " + rule.quarantineReason);
        publishDiagnostic("reactive.quarantined", rule, rule.quarantineReason);
    }

    private static String boundedReason(String reason) {
        String value = reason == null ? "" : reason;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static long safeDeadline(long now, long ttl) {
        if (Long.MAX_VALUE - now < ttl) return Long.MAX_VALUE;
        return now + ttl;
    }

    int availableActionPermitsForTest() {
        Semaphore permits = actionPermits;
        return permits == null ? 0 : permits.availablePermits();
    }

    private <T> T runGovernedMutation(final Rule rule, final String sourceKind,
                                      final String code, final boolean undoEnabled,
                                      MutationCoordinator.Operation<T> operation)
            throws Exception {
        final SessionCodeJournal.DatasetBinding journalDataset =
                SessionCodeJournal.captureInitiatingDataset();
        MutationCoordinator.Lifecycle<T> lifecycle =
                new MutationCoordinator.Lifecycle<T>() {
            @Override public void checkSafety() throws Exception {
                mutationPolicy.checkSafety(rule.name, sourceKind, code);
            }

            @Override public void beforeMutation() throws Exception {
                mutationPolicy.beforeMutation(rule.name, sourceKind, code);
            }

            @Override public void afterMutation(MutationCoordinator.Outcome<T> outcome)
                    throws Exception {
                mutationPolicy.afterMutation(rule.name, sourceKind, code, outcome);
            }

            @Override public void onCompletion(
                    MutationCoordinator.Completion<T> completion) {
                mutationPolicy.onCompletion(rule.name, sourceKind, code, completion);
                if ("reactive-macro".equals(sourceKind)
                        || "reactive-intent".equals(sourceKind)) {
                    boolean success = completion.state()
                            == MutationCoordinator.State.SUCCEEDED;
                    Throwable error = completion.error();
                    try {
                        SessionCodeJournal.INSTANCE.record(journalDataset, "ijm",
                                code == null ? "" : code, "reactive:" + rule.name,
                                0L, completion.startedAtMs(), completion.elapsedMs(),
                                success, error == null ? null : error.getMessage());
                    } catch (Throwable t) {
                        logWarn("Reactive journal record failed: " + t.getMessage());
                    }
                }
            }
        };
        MutationCoordinator.Request.Builder<T> requestBuilder =
                MutationCoordinator.Request.<T>builder()
                        .ownerSession("__imagejai_reactive__")
                        .sourceKind(sourceKind)
                        .code(code == null ? "" : code)
                        .timeoutMs(actionTtlMs)
                        .safetyEnabled(true)
                        .undoEnabled(undoEnabled)
                        .provenanceEnabled(true)
                        .operation(operation)
                        .lifecycle(lifecycle);
        if (undoEnabled) {
            requestBuilder.cancellationAction(new MutationCoordinator.CancellationAction() {
                @Override public void cancel() {
                    CommandEngine.requestOwnedMacroAbort();
                }
            });
        }
        MutationCoordinator.Request<T> request = requestBuilder.build();
        MutationCoordinator.Handle<T> handle = mutationCoordinator.submit(request);
        MutationCoordinator.Completion<T> completion;
        try {
            completion = handle.awaitCompletion();
        } catch (InterruptedException interrupted) {
            handle.cancel();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (completion.state() == MutationCoordinator.State.SUCCEEDED) {
            return completion.result();
        }
        Throwable error = completion.error();
        String detail = error == null || error.getMessage() == null
                ? completion.state().name() : error.getMessage();
        throw new IllegalStateException(sourceKind + " failed: " + detail, error);
    }

    private void executeAction(Rule r, JsonObject action, JsonObject frame) throws Exception {
        if (action.has("execute_macro")) {
            JsonElement ce = action.get("execute_macro");
            if (ce != null && ce.isJsonPrimitive()) {
                final String code = ce.getAsString();
                if (code != null && !code.isEmpty() && cmdEngine != null) {
                    ExecutionResult result = runGovernedMutation(r, "reactive-macro",
                            code, true,
                            new MutationCoordinator.Operation<ExecutionResult>() {
                                @Override public ExecutionResult run() {
                                    return cmdEngine.executeMacroOnCurrentThread(code, null);
                                }
                            });
                    if (result == null || !result.isSuccess()) {
                        throw new IllegalStateException(result == null
                                ? "macro returned no result" : result.getError());
                    }
                }
            }
            return;
        }
        if (action.has("publish_event")) {
            JsonElement pe = action.get("publish_event");
            if (pe != null && pe.isJsonObject()) {
                JsonObject payload = pe.getAsJsonObject();
                String topic = getString(payload, "topic", null);
                JsonObject dataObj = (payload.has("data") && payload.get("data").isJsonObject())
                        ? payload.getAsJsonObject("data")
                        : new JsonObject();
                if (topic != null && !topic.isEmpty()) {
                    ConcurrentHashMap<String, Long> map = lastPublishByRule.get(r.name);
                    if (map == null) {
                        ConcurrentHashMap<String, Long> fresh =
                                new ConcurrentHashMap<String, Long>();
                        ConcurrentHashMap<String, Long> existing =
                                lastPublishByRule.putIfAbsent(r.name, fresh);
                        map = existing != null ? existing : fresh;
                    }
                    map.put(topic, Long.valueOf(clock.getAsLong()));
                    bus.publish(topic, dataObj);
                }
            }
            return;
        }
        if (action.has("gui_action")) {
            Object gui = guiDispatcher;
            if (gui == null) {
                logInfo("Rule '" + r.name + "' gui_action skipped — "
                        + "GuiActionDispatcher not available (Phase 7 not merged)");
                return;
            }
            JsonElement ge = action.get("gui_action");
            if (ge != null && ge.isJsonObject()) {
                final Object target = gui;
                final JsonObject request = ge.getAsJsonObject().deepCopy();
                runGovernedMutation(r, "reactive-gui", "gui_action", false,
                        new MutationCoordinator.Operation<Object>() {
                            @Override public Object run() throws Exception {
                                return invokeReflectiveOrThrow(target, "dispatch", request);
                            }
                        });
            }
            return;
        }
        if (action.has("run_intent")) {
            Object intent = intentRouter;
            if (intent == null) {
                logInfo("Rule '" + r.name + "' run_intent skipped — "
                        + "IntentRouter not available (Phase 5 not merged)");
                return;
            }
            JsonElement re = action.get("run_intent");
            if (re != null && re.isJsonPrimitive()) {
                String phrase = re.getAsString();
                if (phrase != null && !phrase.isEmpty()) {
                    Object resolved = invokeReflective(intent, "resolve", phrase);
                    // Phase 5 IntentRouter.resolve returns Optional<Resolved>.
                    if (resolved instanceof java.util.Optional) {
                        java.util.Optional<?> opt = (java.util.Optional<?>) resolved;
                        resolved = opt.isPresent() ? opt.get() : null;
                    }
                    if (resolved == null) return;
                    final String macro = extractPublicField(resolved, "macro");
                    if (macro != null && !macro.isEmpty() && cmdEngine != null) {
                        ExecutionResult result = runGovernedMutation(r,
                                "reactive-intent", macro, true,
                                new MutationCoordinator.Operation<ExecutionResult>() {
                                    @Override public ExecutionResult run() {
                                        return cmdEngine.executeMacroOnCurrentThread(macro, null);
                                    }
                                });
                        if (result == null || !result.isSuccess()) {
                            throw new IllegalStateException(result == null
                                    ? "intent returned no result" : result.getError());
                        }
                    }
                }
            }
            return;
        }
        if (action.has("close_dialog")) {
            JsonElement ce = action.get("close_dialog");
            String regex = ".*";
            if (ce != null && ce.isJsonObject()) {
                String titleMatches = getString(ce.getAsJsonObject(), "title_matches", null);
                if (titleMatches != null && !titleMatches.isEmpty()) regex = titleMatches;
            }
            final String titlePattern = regex;
            runGovernedMutation(r, "reactive-dialog", titlePattern, false,
                    new MutationCoordinator.Operation<Object>() {
                        @Override public Object run() throws Exception {
                            closeDialogsMatching(titlePattern);
                            return null;
                        }
                    });
            return;
        }
        if (action.has("capture")) {
            JsonElement cv = action.get("capture");
            String name = null;
            if (cv != null && cv.isJsonPrimitive()) name = cv.getAsString();
            final String captureName = name;
            runGovernedMutation(r, "reactive-capture", captureName, false,
                    new MutationCoordinator.Operation<Path>() {
                        @Override public Path run() throws Exception {
                            return doCapture(captureName, r.name);
                        }
                    });
            return;
        }
        if (action.has("wait")) {
            long ms = parseWaitMs(action.get("wait"));
            if (ms > 0) {
                try { Thread.sleep(ms); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
            return;
        }

        // Unknown action — list keys for debugging.
        StringBuilder keys = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : action.entrySet()) {
            if (keys.length() > 0) keys.append(", ");
            keys.append(e.getKey());
        }
        logWarn("Rule '" + r.name + "' has unknown action keys: " + keys);
    }

    // ------------------------------------------------------------------
    // Action helpers
    // ------------------------------------------------------------------

    private void closeDialogsMatching(final String regex) throws Exception {
        final Pattern pat;
        try {
            pat = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Invalid title_matches regex '" + regex + "': " + e.getMessage(), e);
        }
        Runnable closer = new Runnable() {
            @Override
            public void run() {
                // Prefer WindowManager.getNonImageTitles() per the spec, but
                // fall back to iterating all top-level windows so titleless
                // AWT dialogs aren't silently skipped.
                String[] titles = null;
                try {
                    titles = WindowManager.getNonImageTitles();
                } catch (Throwable ignore) {}

                if (titles != null) {
                    for (String title : titles) {
                        if (title == null) continue;
                        if (!pat.matcher(title).matches()) continue;
                        if (isProtectedTitle(title)) continue;
                        Window w = resolveWindowByTitle(title);
                        if (w == null) continue;
                        if (w == IJ.getInstance()) continue;
                        try {
                            w.setVisible(false);
                            w.dispose();
                        } catch (Throwable ignore) {}
                    }
                }

                // Also sweep AWT Dialogs by title (DialogWatcher emits
                // dialog.appeared for plain Dialogs which WindowManager does
                // not track).
                Window[] all = Window.getWindows();
                if (all == null) return;
                for (Window w : all) {
                    if (w == null || !w.isShowing()) continue;
                    if (w == IJ.getInstance()) continue;
                    String title = extractTitle(w);
                    if (title == null) continue;
                    if (!pat.matcher(title).matches()) continue;
                    if (isProtectedTitle(title)) continue;
                    try {
                        w.setVisible(false);
                        w.dispose();
                    } catch (Throwable ignore) {}
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            closer.run();
        } else {
            SwingUtilities.invokeAndWait(closer);
        }
    }

    private static boolean isProtectedTitle(String title) {
        if (title == null) return false;
        if (title.equals("ImageJ") || title.equals("Fiji")) return true;
        if (title.contains("AI Assistant")) return true;
        return false;
    }

    private static String extractTitle(Window w) {
        if (w instanceof java.awt.Dialog) return ((java.awt.Dialog) w).getTitle();
        if (w instanceof java.awt.Frame) return ((java.awt.Frame) w).getTitle();
        return null;
    }

    private static Window resolveWindowByTitle(String title) {
        // WindowManager.getWindow(String) exists in newer ImageJ but returns
        // Window; older versions only expose getFrame(String). Probe both.
        try {
            Method m = WindowManager.class.getMethod("getWindow", String.class);
            Object w = m.invoke(null, title);
            if (w instanceof Window) return (Window) w;
        } catch (Throwable ignore) {}
        try {
            Method m = WindowManager.class.getMethod("getFrame", String.class);
            Object f = m.invoke(null, title);
            if (f instanceof Window) return (Window) f;
        } catch (Throwable ignore) {}
        // Last-ditch: iterate all windows and match the title.
        Window[] all = Window.getWindows();
        if (all != null) {
            for (Window w : all) {
                if (title.equals(extractTitle(w))) return w;
            }
        }
        return null;
    }

    private Path doCapture(String baseName, String ruleName) throws Exception {
        CaptureData capture = captureBackend.capture();
        if (capture == null || capture.png == null || capture.png.length == 0) {
            throw new IllegalStateException("No active image was available for capture");
        }
        if (capture.png.length > MAX_CAPTURE_BYTES) {
            throw new IllegalArgumentException("Capture exceeded " + MAX_CAPTURE_BYTES
                    + " byte limit");
        }
        if (capture.exportDir == null) {
            throw new IOException("Could not resolve AI_Exports capture directory");
        }
        Path captureDir = capture.exportDir.toAbsolutePath().normalize();
        Path leaf = captureDir.getFileName();
        if (leaf == null || !"AI_Exports".equalsIgnoreCase(leaf.toString())) {
            throw new IOException("Reactive captures must target AI_Exports/");
        }
        Files.createDirectories(captureDir);
        String safeBase = (baseName != null && !baseName.isEmpty())
                ? baseName.replaceAll("[^A-Za-z0-9_.-]", "_")
                : ("reactive_" + ruleName.replaceAll("[^A-Za-z0-9_.-]", "_"));
        if (safeBase.length() > MAX_CAPTURE_BASE_CHARS) {
            safeBase = safeBase.substring(0, MAX_CAPTURE_BASE_CHARS);
        }
        String fname = safeBase + "_" + clock.getAsLong() + "_"
                + Long.toUnsignedString(captureSequence.incrementAndGet(), 36) + ".png";
        Path out = captureDir.resolve(fname).normalize();
        if (!out.getParent().equals(captureDir)) {
            throw new IOException("Capture path escaped AI_Exports/");
        }
        Files.write(out, capture.png, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        publishDiagnostic("reactive.capture_written", null,
                "capture=" + fname + " bytes=" + capture.png.length);
        return out;
    }

    private static String extractPublicField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            return v != null ? v.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeReflective(Object target, String methodName, Object... args) {
        if (target == null) return null;
        try {
            Method best = null;
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != args.length) continue;
                best = m;
                break;
            }
            if (best == null) {
                logWarn("No method " + methodName + "(" + args.length + " args) on "
                        + target.getClass().getName());
                return null;
            }
            best.setAccessible(true);
            return best.invoke(target, args);
        } catch (Throwable t) {
            logWarn("Reflective invoke " + methodName + " failed: " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    static boolean topicMatches(String pattern, String topic) {
        if (pattern == null || topic == null) return false;
        if (pattern.equals(topic)) return true;
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return topic.equals(prefix) || topic.startsWith(prefix + ".");
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return topic.startsWith(prefix);
        }
        return false;
    }

    static boolean whereMatches(JsonObject where, JsonObject data) {
        if (where == null || where.entrySet().isEmpty()) return true;
        if (data == null) return false;
        for (Map.Entry<String, JsonElement> e : where.entrySet()) {
            JsonElement actual = resolvePath(data, e.getKey());
            if (actual == null) return false;
            if (!elementEquals(e.getValue(), actual)) return false;
        }
        return true;
    }

    private static JsonElement resolvePath(JsonObject obj, String path) {
        if (obj == null || path == null || path.isEmpty()) return null;
        String[] parts = path.split("\\.");
        JsonElement cur = obj;
        for (String p : parts) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(p);
        }
        return cur;
    }

    private static boolean elementEquals(JsonElement expected, JsonElement actual) {
        if (expected == null || actual == null) return expected == actual;
        return expected.equals(actual);
    }

    static long parseWaitMs(JsonElement el) {
        if (el == null) return 0L;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            try { return el.getAsLong(); } catch (Exception ignore) { return 0L; }
        }
        if (!el.isJsonPrimitive()) return 0L;
        String s = el.getAsString();
        if (s == null) return 0L;
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return 0L;
        try {
            if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2).trim());
            if (s.endsWith("s")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 1000.0);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // File watcher
    // ------------------------------------------------------------------

    private void startWatcher() {
        try {
            Files.createDirectories(rulesDir);
            watchService = FileSystems.getDefault().newWatchService();
            rulesDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            logWarn("WatchService not started: " + e.getMessage());
            return;
        }

        watchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long pendingChangeMs = 0L;
                while (!stopping) {
                    WatchKey key = null;
                    try {
                        key = watchService.poll(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ie) {
                        return;
                    } catch (ClosedWatchServiceException cwe) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (key != null) {
                        boolean relevant = false;
                        for (WatchEvent<?> ev : key.pollEvents()) {
                            Object ctx = ev.context();
                            if (ctx instanceof Path) {
                                String fn = ctx.toString().toLowerCase();
                                if (fn.endsWith(".json") || fn.endsWith(".lock")) {
                                    relevant = true;
                                }
                            }
                        }
                        key.reset();
                        if (relevant) pendingChangeMs = now;
                    }
                    if (pendingChangeMs > 0 && (now - pendingChangeMs) >= RELOAD_DEBOUNCE_MS) {
                        try {
                            reload();
                            logInfo("Reactive rules hot-reloaded ("
                                    + rules.size() + " active, "
                                    + quarantined.size() + " quarantined)");
                        } catch (Throwable t) {
                            logWarn("Hot-reload failed: " + t.getMessage());
                        }
                        pendingChangeMs = 0L;
                    }
                }
            }
        }, "imagej-ai-reactive-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private static String getString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try { return e.getAsString(); } catch (Exception ex) { return def; }
    }

    private static boolean getBool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try { return e.getAsBoolean(); } catch (Exception ex) { return def; }
    }

    private static int getInt(JsonObject o, String key, int def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try { return e.getAsInt(); } catch (Exception ex) { return def; }
    }

    private static void logWarn(String msg) {
        System.err.println("[ImageJAI-Reactive] " + msg);
    }

    private static void logInfo(String msg) {
        System.err.println("[ImageJAI-Reactive] " + msg);
    }

    private static Object invokeReflectiveOrThrow(Object target, String methodName,
                                                   Object... args) throws Exception {
        if (target == null) throw new IllegalArgumentException("target is required");
        Method best = null;
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterCount() == args.length) {
                best = method;
                break;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(methodName + "(" + args.length + " args) on "
                    + target.getClass().getName());
        }
        try {
            best.setAccessible(true);
            return best.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    private void publishDiagnostic(String topic, Rule rule, String reason) {
        if (bus == null) return;
        JsonObject data = new JsonObject();
        data.addProperty("_reactive_internal", true);
        if (rule != null) data.addProperty("rule", rule.name);
        String bounded = reason == null ? "" : reason;
        if (bounded.length() > 512) bounded = bounded.substring(0, 512);
        data.addProperty("reason", bounded);
        try { bus.publish(topic, data); } catch (Throwable ignore) {}
    }

    private final class QueuedRule implements Runnable {
        private final Rule rule;
        private final JsonObject frame;
        private final long enqueuedAt;
        private final long deadline;
        private final Semaphore permit;
        private final AtomicBoolean released = new AtomicBoolean(false);

        QueuedRule(Rule rule, JsonObject frame, long enqueuedAt,
                   long deadline, Semaphore permit) {
            this.rule = rule;
            this.frame = frame;
            this.enqueuedAt = enqueuedAt;
            this.deadline = deadline;
            this.permit = permit;
        }

        @Override public void run() {
            try {
                fireRule(rule, frame, deadline);
            } catch (Throwable t) {
                recordRuleFailure(rule, t);
            } finally {
                releasePermit();
            }
        }

        void releasePermit() {
            if (permit != null && released.compareAndSet(false, true)) permit.release();
        }
    }

    private static final class DefaultMutationPolicy implements MutationPolicy {
        private final SessionUndo undo = new SessionUndo();
        private final AtomicLong callSequence = new AtomicLong();
        private final StateInspector inspector = new StateInspector();

        @Override public void checkSafety(String ruleName, String sourceKind, String code)
                throws Exception {
            if (!isImageMutation(sourceKind) || code == null || code.isEmpty()) return;
            List<DestructiveScanner.DestructiveOp> findings =
                    DestructiveScanner.scan(code, scannerContext());
            for (DestructiveScanner.DestructiveOp finding : findings) {
                if (finding.severity == DestructiveScanner.Severity.REJECT) {
                    throw new MutationCoordinator.SafetyException(
                            finding.ruleId + ": " + finding.message);
                }
            }
        }

        @Override public void beforeMutation(String ruleName, String sourceKind, String code)
                throws Exception {
            if (!isImageMutation(sourceKind)) return;
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp == null) return;
            StateInspector.BoundedCsv csv = inspector.getResultsTableCSVBounded(
                    StateInspector.DEFAULT_RESULTS_CSV_LIMIT_BYTES);
            if (csv.truncated()) {
                throw new MutationCoordinator.SafetyException(
                        "Reactive mutation blocked: exact ResultsTable undo snapshot is "
                                + csv.originalBytes() + " bytes (limit "
                                + StateInspector.DEFAULT_RESULTS_CSV_LIMIT_BYTES + ").");
            }
            UndoFrame frame = UndoFrame.capture(
                    "reactive-" + callSequence.incrementAndGet(), imp,
                    RoiManager.getInstance(), csv.text(), UndoFrame.macroHasDiskWrites(code));
            if (frame == null) {
                throw new MutationCoordinator.SafetyException(
                        "Reactive mutation blocked: undo snapshot could not be captured.");
            }
            undo.pushFrame(frame);
        }

        @Override public void afterMutation(String ruleName, String sourceKind, String code,
                                            MutationCoordinator.Outcome<?> outcome) {}
        @Override public void onCompletion(String ruleName, String sourceKind, String code,
                                           MutationCoordinator.Completion<?> completion) {}

        private static boolean isImageMutation(String sourceKind) {
            return "reactive-macro".equals(sourceKind)
                    || "reactive-intent".equals(sourceKind);
        }

        private static DestructiveScanner.Context scannerContext() {
            final ImagePlus imp = WindowManager.getCurrentImage();
            String activePath = null;
            String exportsRoot = Paths.get(System.getProperty("user.dir", "."))
                    .resolve("AI_Exports").toAbsolutePath().normalize().toString();
            int bitDepth = 0;
            boolean calibrationActive = false;
            if (imp != null) {
                bitDepth = imp.getBitDepth();
                Calibration calibration = imp.getCalibration();
                if (calibration != null) {
                    String unit = calibration.getUnit();
                    calibrationActive = calibration.pixelWidth != 1.0
                            || (unit != null && !unit.isEmpty()
                            && !"pixel".equalsIgnoreCase(unit)
                            && !"pixels".equalsIgnoreCase(unit));
                }
                try {
                    FileInfo info = imp.getOriginalFileInfo();
                    if (info != null && info.directory != null
                            && info.fileName != null) {
                        Path parent = Paths.get(info.directory).toAbsolutePath().normalize();
                        activePath = parent.resolve(info.fileName).normalize().toString();
                        exportsRoot = parent.resolve("AI_Exports").toString();
                    }
                } catch (Throwable ignore) {}
            }
            RoiManager manager = RoiManager.getInstance();
            int roiCount = manager == null ? 0 : manager.getCount();
            int resultRows = 0;
            try {
                ij.measure.ResultsTable table = ij.measure.ResultsTable.getResultsTable();
                resultRows = table == null ? 0 : table.getCounter();
            } catch (Throwable ignore) {}
            return new DestructiveScanner.Context(activePath, exportsRoot, bitDepth,
                    calibrationActive, roiCount, resultRows, true, true,
                    new DestructiveScanner.FileExistsCheck() {
                        @Override public boolean exists(String path) {
                            if (path == null || path.trim().isEmpty()) return false;
                            try { return Files.exists(Paths.get(path)); }
                            catch (RuntimeException invalid) { return false; }
                        }
                    });
        }
    }

    private static final class DefaultCaptureBackend implements CaptureBackend {
        @Override public CaptureData capture() {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp == null) return new CaptureData(null, null);
            byte[] png = ImageCapture.captureActiveImage();
            Path root = Paths.get(System.getProperty("user.dir", "."));
            try {
                FileInfo info = imp.getOriginalFileInfo();
                if (info != null && info.directory != null && !info.directory.trim().isEmpty()) {
                    root = Paths.get(info.directory);
                }
            } catch (Throwable ignore) {}
            return new CaptureData(png, root.resolve("AI_Exports"));
        }
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public static class Rule {
        public String name;
        public String description;
        public volatile boolean enabled;
        public int priority;
        public String event;
        public JsonObject where;
        public List<JsonObject> actions;
        public String rateLimitSpec;
        public RateLimiter rateLimiter;
        public long waitBefore;
        public String sourceFile;
        public long sourceModified;
        public volatile long hits;
        public volatile long lastFired;
        public volatile boolean runtimeQuarantined;
        public volatile String quarantineReason = "";
        public final AtomicInteger consecutiveFailures = new AtomicInteger();
    }

    public static class Quarantined {
        public final String path;
        public final String error;
        public Quarantined(String path, String error) {
            this.path = path;
            this.error = error;
        }
    }

    /**
     * Simple sliding-window rate limiter. {@code N/sec} / {@code N/min} /
     * {@code N/hour} syntax — up to N permits within the named window.
     */
    public static class RateLimiter {
        public final int permits;
        public final long windowMs;
        private final Deque<Long> stamps = new ArrayDeque<Long>();

        RateLimiter(int permits, long windowMs) {
            this.permits = permits;
            this.windowMs = windowMs;
        }

        static RateLimiter parse(String spec) {
            if (spec == null) return null;
            String s = spec.trim().toLowerCase();
            int slash = s.indexOf('/');
            if (slash <= 0) return null;
            int permits;
            try {
                permits = Integer.parseInt(s.substring(0, slash).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (permits <= 0) return null;
            String unit = s.substring(slash + 1).trim();
            long window;
            if ("sec".equals(unit) || "s".equals(unit) || "second".equals(unit) || "seconds".equals(unit)) {
                window = 1000L;
            } else if ("min".equals(unit) || "m".equals(unit) || "minute".equals(unit) || "minutes".equals(unit)) {
                window = 60_000L;
            } else if ("hour".equals(unit) || "h".equals(unit) || "hours".equals(unit)) {
                window = 3_600_000L;
            } else {
                return null;
            }
            return new RateLimiter(permits, window);
        }

        public synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long cutoff = now - windowMs;
            while (!stamps.isEmpty() && stamps.peekFirst().longValue() < cutoff) {
                stamps.pollFirst();
            }
            if (stamps.size() >= permits) return false;
            stamps.addLast(Long.valueOf(now));
            return true;
        }
    }

}
