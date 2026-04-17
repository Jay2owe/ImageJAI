package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ij.IJ;
import ij.WindowManager;

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
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
    private static final Path RULES_DIR = HOME.resolve(".imagej-ai").resolve("reactive");
    private static final Path LOCK_FILE = RULES_DIR.resolve("reactive.lock");
    private static final Path CAPTURE_DIR = HOME.resolve(".imagej-ai").resolve("captures");

    private static final long RELOAD_DEBOUNCE_MS = 500L;
    private static final long CYCLE_WARN_WINDOW_MS = 1000L;
    private static final JsonParser PARSER = new JsonParser();

    private final EventBus bus;
    private final CommandEngine cmdEngine;
    // Phase 5/7 dependencies — volatile so the TCP server can swap the GUI
    // dispatcher when the chat panel controller (re)attaches. Typed as Object
    // so the engine compiles even before those classes are merged, and so the
    // caller can pass null for graceful degrade.
    private volatile Object intentRouter;
    private volatile Object guiDispatcher;

    private final CopyOnWriteArrayList<Rule> rules = new CopyOnWriteArrayList<Rule>();
    private final CopyOnWriteArrayList<Quarantined> quarantined = new CopyOnWriteArrayList<Quarantined>();

    // Cycle detector: rule.name -> (topic -> last publish ms by this rule).
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> lastPublishByRule =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>();

    private volatile boolean started;
    private volatile boolean stopping;
    private Thread watchThread;
    private WatchService watchService;
    private ExecutorService actionExecutor;
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
        this.bus = bus;
        this.cmdEngine = cmd;
        this.intentRouter = intent;
        this.guiDispatcher = gui;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized void start() {
        if (started) return;
        started = true;
        stopping = false;

        actionExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "imagej-ai-reactive-actions");
                t.setDaemon(true);
                return t;
            }
        });

        try {
            Files.createDirectories(RULES_DIR);
        } catch (IOException e) {
            logWarn("Failed to create rules dir " + RULES_DIR + ": " + e.getMessage());
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
            actionExecutor.shutdownNow();
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
        return Files.exists(LOCK_FILE);
    }

    public synchronized boolean setEnabled(String name, boolean enabled) {
        for (Rule r : rules) {
            if (r.name.equals(name)) {
                r.enabled = enabled;
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

        File dir = RULES_DIR.toFile();
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

        // Preserve enabled-state / hits / lastFired across reloads when name matches.
        Map<String, Rule> existing = new HashMap<String, Rule>();
        for (Rule r : rules) existing.put(r.name, r);
        for (Rule r : loaded) {
            Rule prev = existing.get(r.name);
            if (prev != null) {
                r.enabled = prev.enabled;
                r.hits = prev.hits;
                r.lastFired = prev.lastFired;
            }
        }
        rules.clear();
        rules.addAll(loaded);
        quarantined.clear();
        quarantined.addAll(qu);
    }

    private Rule parseRule(File f) throws IOException {
        JsonObject obj;
        InputStreamReader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
            JsonElement el = PARSER.parse(reader);
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
        List<JsonObject> actions = new ArrayList<JsonObject>();
        for (int i = 0; i < doArr.size(); i++) {
            JsonElement ae = doArr.get(i);
            if (ae == null || !ae.isJsonObject()) {
                throw new IllegalArgumentException("Action #" + i + " must be an object");
            }
            actions.add(ae.getAsJsonObject());
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
        return r;
    }

    // ------------------------------------------------------------------
    // Event dispatch
    // ------------------------------------------------------------------

    private void onBusEvent(JsonObject frame) {
        if (frame == null) return;
        if (Files.exists(LOCK_FILE)) return;

        JsonElement tEl = frame.get("event");
        if (tEl == null || !tEl.isJsonPrimitive()) return;
        final String topic = tEl.getAsString();
        if (topic == null) return;

        final JsonObject data = (frame.has("data") && frame.get("data").isJsonObject())
                ? frame.getAsJsonObject("data")
                : new JsonObject();

        for (final Rule r : rules) {
            if (!r.enabled) continue;
            if (!topicMatches(r.event, topic)) continue;
            if (!whereMatches(r.where, data)) continue;

            // Cycle detection — did this rule publish this topic within 1s?
            ConcurrentHashMap<String, Long> pubMap = lastPublishByRule.get(r.name);
            if (pubMap != null) {
                Long ts = pubMap.get(topic);
                if (ts != null && (System.currentTimeMillis() - ts.longValue()) < CYCLE_WARN_WINDOW_MS) {
                    logWarn("Rule '" + r.name + "' matched on '" + topic
                            + "' it published " + (System.currentTimeMillis() - ts.longValue())
                            + "ms ago — possible feedback loop (rate_limit still enforced)");
                }
            }

            if (r.rateLimiter != null && !r.rateLimiter.tryAcquire()) {
                continue;
            }
            r.hits++;
            r.lastFired = System.currentTimeMillis();

            final JsonObject capturedFrame = frame;
            final ExecutorService ex = actionExecutor;
            if (ex == null) continue;
            ex.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        fireRule(r, capturedFrame);
                    } catch (Throwable t) {
                        logWarn("Rule '" + r.name + "' firing failed: " + t.getMessage());
                    }
                }
            });
        }
    }

    private void fireRule(Rule r, JsonObject frame) {
        if (r.waitBefore > 0) {
            try { Thread.sleep(r.waitBefore); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        for (JsonObject action : r.actions) {
            try {
                executeAction(r, action, frame);
            } catch (Throwable t) {
                logWarn("Rule '" + r.name + "' action failed: " + t.getMessage());
                // Continue to next action rather than aborting the chain.
            }
        }
    }

    private void executeAction(Rule r, JsonObject action, JsonObject frame) throws Exception {
        if (action.has("execute_macro")) {
            JsonElement ce = action.get("execute_macro");
            if (ce != null && ce.isJsonPrimitive()) {
                String code = ce.getAsString();
                if (code != null && !code.isEmpty() && cmdEngine != null) {
                    cmdEngine.executeMacro(code);
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
                    map.put(topic, Long.valueOf(System.currentTimeMillis()));
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
                invokeReflective(gui, "dispatch", ge.getAsJsonObject());
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
                    // Prefer an explicit execute(resolved) method if present;
                    // otherwise extract the .macro field and run it ourselves.
                    Object executed = invokeReflective(intent, "execute", resolved);
                    if (executed != null) return;
                    String macro = extractPublicField(resolved, "macro");
                    if (macro != null && !macro.isEmpty() && cmdEngine != null) {
                        cmdEngine.executeMacro(macro);
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
            closeDialogsMatching(regex);
            return;
        }
        if (action.has("capture")) {
            JsonElement cv = action.get("capture");
            String name = null;
            if (cv != null && cv.isJsonPrimitive()) name = cv.getAsString();
            doCapture(name, r.name);
            return;
        }
        if (action.has("wait")) {
            long ms = parseWaitMs(action.get("wait"));
            if (ms > 0) {
                try { Thread.sleep(ms); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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

    private void closeDialogsMatching(final String regex) {
        final Pattern pat;
        try {
            pat = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            logWarn("Invalid title_matches regex '" + regex + "': " + e.getMessage());
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
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
        });
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

    private void doCapture(String baseName, String ruleName) {
        byte[] png = ImageCapture.captureActiveImage();
        if (png == null) return;
        try {
            Files.createDirectories(CAPTURE_DIR);
        } catch (IOException e) {
            logWarn("Failed to create capture dir " + CAPTURE_DIR + ": " + e.getMessage());
            return;
        }
        String safeBase = (baseName != null && !baseName.isEmpty())
                ? baseName.replaceAll("[^A-Za-z0-9_.-]", "_")
                : ("reactive_" + ruleName.replaceAll("[^A-Za-z0-9_.-]", "_"));
        String fname = safeBase + "_" + System.currentTimeMillis() + ".png";
        Path out = CAPTURE_DIR.resolve(fname);
        try {
            Files.write(out, png);
        } catch (IOException e) {
            logWarn("Failed to write capture " + out + ": " + e.getMessage());
        }
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
            Files.createDirectories(RULES_DIR);
            watchService = FileSystems.getDefault().newWatchService();
            RULES_DIR.register(watchService,
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
        public volatile long hits;
        public volatile long lastFired;
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
