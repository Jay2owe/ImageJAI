package imagejai.engine;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 12 (docs/tcp_upgrade/12_per_agent_telemetry.md): per-socket rolling
 * log of commands plus tiny bookkeeping for the pattern-detection rule set.
 *
 * <p>Lives alongside {@link ResponseDedupCache} on
 * {@link TCPCommandServer.AgentCaps}. One instance per connection.
 *
 * <p>Thread-safety: each instance is bound to a single connection whose TCP
 * handler is single-threaded per request, but every public method guards its
 * shared state with {@code synchronized} to stay safe against future batch /
 * run paths that reuse the same caps from nested threads.
 */
public final class SessionStats {

    /** Hard cap on the rolling command history. */
    public static final int MAX_HISTORY = 50;

    /** Per-rule silence window after the rule fires once. */
    public static final long DEFAULT_THROTTLE_MS = 5L * 60L * 1000L;

    /** One row of the history ring buffer. */
    public static final class CmdLog {
        public final String cmd;
        public final String canonicalArgs;
        public final long timestampMs;
        /** Structured error code (or normalised error string) when the call failed; {@code null} on success. */
        public final String responseErrorCode;

        public CmdLog(String cmd, String canonicalArgs, long timestampMs, String responseErrorCode) {
            this.cmd = cmd == null ? "" : cmd;
            this.canonicalArgs = canonicalArgs == null ? "" : canonicalArgs;
            this.timestampMs = timestampMs;
            this.responseErrorCode = responseErrorCode;
        }

        public boolean isFailure() {
            return responseErrorCode != null && !responseErrorCode.isEmpty();
        }
    }

    private final Deque<CmdLog> commandHistory = new ArrayDeque<CmdLog>();
    private final Map<String, Long> lastFiredByRule = new HashMap<String, Long>();
    private final Set<String> probedCommands = new HashSet<String>();

    /** Append one command to the history, evicting the oldest when capped. */
    public synchronized void record(String cmd, String canonicalArgs, long ts, String responseErrorCode) {
        commandHistory.addLast(new CmdLog(cmd, canonicalArgs, ts, responseErrorCode));
        while (commandHistory.size() > MAX_HISTORY) {
            commandHistory.removeFirst();
        }
    }

    /** Snapshot of the history in insertion order (oldest first). */
    public synchronized List<CmdLog> history() {
        return Collections.unmodifiableList(new java.util.ArrayList<CmdLog>(commandHistory));
    }

    /** Current history size — primarily for tests. */
    public synchronized int size() {
        return commandHistory.size();
    }

    /**
     * True when the rule keyed on {@code ruleKind} has not fired inside the
     * last {@link #DEFAULT_THROTTLE_MS}. Pattern rules gate their own
     * detection on this — see {@link PatternDetector}.
     */
    public synchronized boolean canFire(String ruleKind, long now) {
        return canFire(ruleKind, now, DEFAULT_THROTTLE_MS);
    }

    public synchronized boolean canFire(String ruleKind, long now, long throttleMs) {
        Long last = lastFiredByRule.get(ruleKind);
        return last == null || (now - last) >= throttleMs;
    }

    /** Mark a rule fired so subsequent checks stay silent for the throttle window. */
    public synchronized void markFired(String ruleKind, long ts) {
        lastFiredByRule.put(ruleKind, ts);
    }

    /**
     * Note that the client has run {@code probe_command} against this plugin
     * name this session. Consulted by {@code probe_before_run_missed}.
     */
    public synchronized void noteProbed(String pluginName) {
        if (pluginName != null && !pluginName.isEmpty()) {
            probedCommands.add(pluginName);
        }
    }

    public synchronized boolean hasProbed(String pluginName) {
        return pluginName != null && probedCommands.contains(pluginName);
    }

    /** Wipe all history + rule throttles. Intended for tests. */
    public synchronized void clear() {
        commandHistory.clear();
        lastFiredByRule.clear();
        probedCommands.clear();
    }
}
