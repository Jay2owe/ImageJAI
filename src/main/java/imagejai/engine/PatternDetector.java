package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 12 (docs/tcp_upgrade/12_per_agent_telemetry.md): per-session behaviour
 * detector. Reads the rolling {@link SessionStats#history()} and emits
 * {@link Hint}s when an agent's recent command stream matches a known-bad
 * pattern — e.g. thrashing {@code close_dialogs}, polling {@code get_state}
 * without mutating anything between calls, retrying the same failing macro.
 *
 * <p>Mirrors the shape of {@link MacroAnalyser}: each rule is a pure
 * {@code (stats, now) -> Optional<Hint>} function, stateless itself, with
 * per-rule throttling stored on the {@link SessionStats} instance.
 *
 * <p>Rule throttle: once a rule fires, the same rule stays silent for the
 * next {@link SessionStats#DEFAULT_THROTTLE_MS} so the hint doesn't nag.
 * Legitimate workflows that trip a threshold pay one hint, not one per turn.
 */
public final class PatternDetector {

    private PatternDetector() {}

    /** One pattern-detection result. Serialises to the {@code hints[]} JSON shape. */
    public static final class Hint {
        public final String code;
        public final String kind;
        public final String message;
        public final String severity;
        public final JsonArray suggested;

        public Hint(String code, String kind, String message, String severity, JsonArray suggested) {
            this.code = code;
            this.kind = kind;
            this.message = message;
            this.severity = severity;
            this.suggested = suggested;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("code", code);
            obj.addProperty("kind", kind);
            obj.addProperty("message", message);
            obj.addProperty("severity", severity == null ? "info" : severity);
            if (suggested != null && suggested.size() > 0) {
                obj.add("suggested", suggested);
            }
            return obj;
        }
    }

    // -----------------------------------------------------------------------
    // Top-level dispatch
    // -----------------------------------------------------------------------

    /**
     * Run every rule against {@code stats} and return the fired hints. Each
     * rule that fires also bumps its last-fired timestamp on {@code stats}
     * so consecutive {@code check} calls within the throttle window stay
     * silent.
     */
    public static List<Hint> check(SessionStats stats, long now) {
        List<Hint> out = new ArrayList<Hint>();
        if (stats == null) return out;
        closeDialogsThrashing(stats, now).ifPresent(out::add);
        getStatePolling(stats, now).ifPresent(out::add);
        repeatErrorIdenticalMacro(stats, now).ifPresent(out::add);
        probeBeforeRunMissed(stats, now).ifPresent(out::add);
        return out;
    }

    // -----------------------------------------------------------------------
    // Rule kind keys (used for per-rule throttling in SessionStats).
    // -----------------------------------------------------------------------

    public static final String KIND_CLOSE_DIALOGS_THRASHING     = "close_dialogs_thrashing";
    public static final String KIND_GET_STATE_POLLING           = "get_state_polling";
    public static final String KIND_REPEAT_ERROR_IDENTICAL_MACRO = "repeat_error_identical_macro";
    public static final String KIND_PROBE_BEFORE_RUN_MISSED     = "probe_before_run_missed";

    // -----------------------------------------------------------------------
    // Rules
    // -----------------------------------------------------------------------

    /** 30 s window for close_dialogs_thrashing. */
    static final long CLOSE_DIALOGS_WINDOW_MS = 30_000L;
    static final int  CLOSE_DIALOGS_THRESHOLD = 4;

    /**
     * Fires when {@code close_dialogs} appears ≥ {@link #CLOSE_DIALOGS_THRESHOLD}
     * times in the last {@link #CLOSE_DIALOGS_WINDOW_MS}. Dialogs usually want
     * to be engaged via {@code interact_dialog}, not dismissed.
     */
    static Optional<Hint> closeDialogsThrashing(SessionStats stats, long now) {
        if (!stats.canFire(KIND_CLOSE_DIALOGS_THRASHING, now)) return Optional.empty();
        long cutoff = now - CLOSE_DIALOGS_WINDOW_MS;
        int count = 0;
        for (SessionStats.CmdLog c : stats.history()) {
            if (c.timestampMs < cutoff) continue;
            if ("close_dialogs".equals(c.cmd) || "close_windows".equals(c.cmd)) count++;
        }
        if (count < CLOSE_DIALOGS_THRESHOLD) return Optional.empty();
        stats.markFired(KIND_CLOSE_DIALOGS_THRASHING, now);
        JsonArray suggested = new JsonArray();
        suggested.add(suggestion("get_dialogs"));
        return Optional.of(new Hint(
                "PATTERN_DETECTED",
                KIND_CLOSE_DIALOGS_THRASHING,
                "You've called close_dialogs " + count + " times in " +
                        (CLOSE_DIALOGS_WINDOW_MS / 1000) +
                        "s. Dialogs likely need to be interacted with " +
                        "(interact_dialog), not dismissed.",
                "info",
                suggested));
    }

    /** 60 s window for get_state polling. */
    static final long GET_STATE_POLL_WINDOW_MS = 60_000L;
    static final int  GET_STATE_POLL_THRESHOLD = 5;

    /**
     * Fires when {@code get_state} was called ≥ {@link #GET_STATE_POLL_THRESHOLD}
     * times in the last {@link #GET_STATE_POLL_WINDOW_MS} with zero intervening
     * mutating commands — i.e. the agent is polling a state that by
     * construction did not change.
     */
    static Optional<Hint> getStatePolling(SessionStats stats, long now) {
        if (!stats.canFire(KIND_GET_STATE_POLLING, now)) return Optional.empty();
        long cutoff = now - GET_STATE_POLL_WINDOW_MS;
        int count = 0;
        boolean seenMutating = false;
        for (SessionStats.CmdLog c : stats.history()) {
            if (c.timestampMs < cutoff) continue;
            if ("get_state".equals(c.cmd)) {
                count++;
            } else if (isMutating(c.cmd)) {
                // Reset — state probably changed since, polling is justified.
                count = 0;
                seenMutating = true;
            }
        }
        if (seenMutating) return Optional.empty();
        if (count < GET_STATE_POLL_THRESHOLD) return Optional.empty();
        stats.markFired(KIND_GET_STATE_POLLING, now);
        return Optional.of(new Hint(
                "PATTERN_DETECTED",
                KIND_GET_STATE_POLLING,
                "You've called get_state " + count + " times in " +
                        (GET_STATE_POLL_WINDOW_MS / 1000) +
                        "s with no mutating command between them. Nothing " +
                        "will have changed — subscribe to events or rely on " +
                        "the pulse field in macro responses.",
                "info",
                null));
    }

    static final int REPEAT_ERROR_THRESHOLD = 3;

    /**
     * Fires when the last {@link #REPEAT_ERROR_THRESHOLD} consecutive
     * {@code execute_macro} calls share the same canonical args AND all
     * failed with the same error code. "Consecutive" ignores readonly probe
     * commands so an agent that reads {@code get_log} between retries still
     * trips the rule, but any mutating command breaks the streak.
     */
    static Optional<Hint> repeatErrorIdenticalMacro(SessionStats stats, long now) {
        if (!stats.canFire(KIND_REPEAT_ERROR_IDENTICAL_MACRO, now)) return Optional.empty();
        List<SessionStats.CmdLog> hist = stats.history();
        // Walk from newest back, collecting the last N execute_macros.
        List<SessionStats.CmdLog> recentMacros = new ArrayList<SessionStats.CmdLog>();
        for (int i = hist.size() - 1; i >= 0; i--) {
            SessionStats.CmdLog c = hist.get(i);
            if ("execute_macro".equals(c.cmd)) {
                recentMacros.add(c);
                if (recentMacros.size() >= REPEAT_ERROR_THRESHOLD) break;
            } else if (isMutating(c.cmd)) {
                // A different mutating call interrupts the streak.
                return Optional.empty();
            }
        }
        if (recentMacros.size() < REPEAT_ERROR_THRESHOLD) return Optional.empty();
        String firstArgs = recentMacros.get(0).canonicalArgs;
        String firstErr  = recentMacros.get(0).responseErrorCode;
        if (firstErr == null || firstErr.isEmpty()) return Optional.empty();
        for (SessionStats.CmdLog c : recentMacros) {
            if (c.responseErrorCode == null || !c.responseErrorCode.equals(firstErr)) return Optional.empty();
            if (c.canonicalArgs == null || !c.canonicalArgs.equals(firstArgs)) return Optional.empty();
        }
        stats.markFired(KIND_REPEAT_ERROR_IDENTICAL_MACRO, now);
        return Optional.of(new Hint(
                "PATTERN_DETECTED",
                KIND_REPEAT_ERROR_IDENTICAL_MACRO,
                "Same execute_macro submitted " + REPEAT_ERROR_THRESHOLD +
                        " times with identical code and the same error (" +
                        firstErr + "). This macro will not succeed as " +
                        "written. Inspect recovery_hint and change the " +
                        "macro, or probe the referenced plugin first.",
                "warning",
                null));
    }

    /** Match a {@code run("Plugin...", "...")} call in a macro string. */
    private static final Pattern MACRO_RUN_PATTERN = Pattern.compile(
            "run\\s*\\(\\s*[\"']([^\"']+)[\"']");

    static final int PROBE_MISSED_THRESHOLD = 2;

    /**
     * Fires when ≥ {@link #PROBE_MISSED_THRESHOLD} recent {@code execute_macro}
     * calls ran a plugin that was never probed, with different args, all
     * failing. The regex is intentionally narrow — we capture the first
     * {@code run("...", ...)} from the most recent failures only.
     */
    static Optional<Hint> probeBeforeRunMissed(SessionStats stats, long now) {
        if (!stats.canFire(KIND_PROBE_BEFORE_RUN_MISSED, now)) return Optional.empty();
        List<SessionStats.CmdLog> hist = stats.history();
        // Grace period: fewer than 4 commands total means the session is too
        // young to complain about missing probes.
        if (hist.size() < 4) return Optional.empty();

        String pluginName = null;
        int failureCount = 0;
        java.util.Set<String> distinctArgs = new java.util.HashSet<String>();
        for (int i = hist.size() - 1; i >= 0 && failureCount < 5; i--) {
            SessionStats.CmdLog c = hist.get(i);
            if (!"execute_macro".equals(c.cmd)) continue;
            if (!c.isFailure()) continue;
            String candidate = extractPluginName(c.canonicalArgs);
            if (candidate == null) continue;
            if (pluginName == null) {
                pluginName = candidate;
            } else if (!pluginName.equals(candidate)) {
                // A different plugin broke the streak — bail out.
                break;
            }
            distinctArgs.add(c.canonicalArgs);
            failureCount++;
        }
        if (pluginName == null) return Optional.empty();
        if (failureCount < PROBE_MISSED_THRESHOLD) return Optional.empty();
        if (distinctArgs.size() < PROBE_MISSED_THRESHOLD) return Optional.empty();
        if (stats.hasProbed(pluginName)) return Optional.empty();

        stats.markFired(KIND_PROBE_BEFORE_RUN_MISSED, now);
        JsonArray suggested = new JsonArray();
        JsonObject probe = new JsonObject();
        probe.addProperty("command", "probe_command");
        probe.addProperty("name", pluginName);
        suggested.add(probe);
        return Optional.of(new Hint(
                "PATTERN_DETECTED",
                KIND_PROBE_BEFORE_RUN_MISSED,
                "You've called run(\"" + pluginName + "\", ...) " +
                        failureCount + " times with different args, all " +
                        "failing, without probing the plugin first. Run " +
                        "probe_command to discover its valid argument names.",
                "info",
                suggested));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Strip a plugin name out of a macro string — first match wins. Accepts
     * both raw macro source ({@code run("Foo...", ...)}) and JSON-encoded
     * canonical args ({@code {"code":"run(\"Foo...\", \"\")"}}) by
     * unescaping backslash-quoted delimiters before matching.
     */
    static String extractPluginName(String canonicalArgs) {
        if (canonicalArgs == null) return null;
        String unescaped = canonicalArgs
                .replace("\\\"", "\"")
                .replace("\\'", "'");
        Matcher m = MACRO_RUN_PATTERN.matcher(unescaped);
        if (!m.find()) return null;
        String raw = m.group(1);
        // Normalise the trailing ellipsis for comparison with probe_command
        // registrations, which store the canonical menu name form.
        if (raw.endsWith("...")) raw = raw.substring(0, raw.length() - 3);
        return raw.trim();
    }

    /**
     * Returns {@code true} when the command can plausibly mutate server
     * state. Pattern rules that need "no intervening mutation" use this as
     * the streak-breaker. Conservative false positives are fine — a mutating
     * command mis-classified as read-only would cause spurious hints; a
     * read-only command mis-classified as mutating just silences a hint.
     */
    static boolean isMutating(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        switch (cmd) {
            // Read-only / probe / telemetry commands — no state change.
            case "ping":
            case "hello":
            case "get_state":
            case "get_image_info":
            case "get_log":
            case "get_results_table":
            case "get_histogram":
            case "get_open_windows":
            case "get_metadata":
            case "get_dialogs":
            case "get_state_context":
            case "get_progress":
            case "get_friction_log":
            case "get_friction_patterns":
            case "get_roi_state":
            case "get_display_state":
            case "get_console":
            case "probe_command":
            case "list_commands":
            case "intent_list":
            case "job_status":
            case "job_list":
            case "list_reactive_rules":
            case "reactive_stats":
            case "capture_image":
            case "get_pixels":
                return false;
            default:
                return true;
        }
    }

    private static JsonObject suggestion(String cmd) {
        JsonObject o = new JsonObject();
        o.addProperty("command", cmd);
        return o;
    }

    /** Visible for tests: drain + re-check helper. */
    static Iterator<SessionStats.CmdLog> historyIterator(SessionStats stats) {
        return stats.history().iterator();
    }
}
