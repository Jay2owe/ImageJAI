package imagejai.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded per-session telemetry used by {@link PatternDetector}. */
public final class SessionStats {

    public static final int MAX_HISTORY = 50;
    public static final long DEFAULT_THROTTLE_MS = 5L * 60L * 1000L;
    public static final int MAX_COMMAND_BYTES = 128;
    public static final int MAX_ARGS_SUMMARY_BYTES = 512;
    public static final int MAX_ERROR_CODE_BYTES = 128;
    public static final int MAX_PROBED_COMMANDS = 128;
    public static final int MAX_PROBED_COMMAND_BYTES = 256;
    public static final int MAX_RULE_KINDS = 64;
    public static final int MAX_RULE_KIND_BYTES = 128;
    public static final int ARGS_DIGEST_BYTES = 64; // lowercase SHA-256 hex
    public static final int MAX_RETAINED_BYTES =
            MAX_HISTORY * (MAX_COMMAND_BYTES + ARGS_DIGEST_BYTES
                    + MAX_ARGS_SUMMARY_BYTES + MAX_ERROR_CODE_BYTES)
            + MAX_PROBED_COMMANDS * MAX_PROBED_COMMAND_BYTES
            + MAX_RULE_KINDS * MAX_RULE_KIND_BYTES;

    /** One row of the bounded history ring. */
    public static final class CmdLog {
        public final String cmd;
        /** Fixed SHA-256 identity used for exact equality checks. */
        public final String argsDigest;
        /** Bounded diagnostic prefix used only to identify plugin names. */
        public final String argsSummary;
        /** Compatibility alias: no longer contains raw caller arguments. */
        @Deprecated public final String canonicalArgs;
        public final long timestampMs;
        public final String responseErrorCode;
        private final int retainedBytes;

        public CmdLog(String cmd, String rawArgs, long timestampMs,
                      String responseErrorCode) {
            this(cmd, ResponseDedupCache.digestText(rawArgs), rawArgs,
                    timestampMs, responseErrorCode, true);
        }

        private CmdLog(String cmd, String argsDigest, String argsSummary,
                       long timestampMs, String responseErrorCode,
                       boolean alreadyDigested) {
            this.cmd = truncateUtf8(cmd, MAX_COMMAND_BYTES);
            this.argsDigest = normaliseDigest(argsDigest, alreadyDigested);
            this.argsSummary = truncateUtf8(argsSummary, MAX_ARGS_SUMMARY_BYTES);
            this.canonicalArgs = this.argsDigest;
            this.timestampMs = timestampMs;
            this.responseErrorCode = responseErrorCode == null ? null
                    : truncateUtf8(responseErrorCode, MAX_ERROR_CODE_BYTES);
            this.retainedBytes = utf8Bytes(this.cmd) + utf8Bytes(this.argsDigest)
                    + utf8Bytes(this.argsSummary) + utf8Bytes(this.responseErrorCode);
        }

        static CmdLog fromDigest(String cmd, String argsDigest, String argsSummary,
                                 long timestampMs, String responseErrorCode) {
            return new CmdLog(cmd, argsDigest, argsSummary, timestampMs,
                    responseErrorCode, true);
        }

        public boolean isFailure() {
            return responseErrorCode != null && !responseErrorCode.isEmpty();
        }

        int retainedBytes() {
            return retainedBytes;
        }
    }

    private final Deque<CmdLog> commandHistory = new ArrayDeque<CmdLog>();
    private final Map<String, Long> lastFiredByRule = new HashMap<String, Long>();
    private final Set<String> probedCommands = new HashSet<String>();
    private int historyBytes;
    private int probedBytes;
    private int ruleBytes;

    /** Record raw args from tests/legacy callers without retaining them. */
    public synchronized void record(String cmd, String rawArgs, long ts,
                                    String responseErrorCode) {
        add(new CmdLog(cmd, rawArgs, ts, responseErrorCode));
    }

    /** Record a precomputed identity plus a bounded diagnostic summary. */
    public synchronized void recordDigest(String cmd, String argsDigest,
                                          String argsSummary, long ts,
                                          String responseErrorCode) {
        add(CmdLog.fromDigest(cmd, argsDigest, argsSummary, ts, responseErrorCode));
    }

    private void add(CmdLog row) {
        commandHistory.addLast(row);
        historyBytes += row.retainedBytes();
        while (commandHistory.size() > MAX_HISTORY) {
            historyBytes -= commandHistory.removeFirst().retainedBytes();
        }
    }

    public synchronized List<CmdLog> history() {
        return Collections.unmodifiableList(
                new java.util.ArrayList<CmdLog>(commandHistory));
    }

    public synchronized int size() {
        return commandHistory.size();
    }

    public synchronized boolean canFire(String ruleKind, long now) {
        return canFire(ruleKind, now, DEFAULT_THROTTLE_MS);
    }

    public synchronized boolean canFire(String ruleKind, long now, long throttleMs) {
        String key = boundedRuleKind(ruleKind);
        Long last = lastFiredByRule.get(key);
        return last == null || (now - last) >= throttleMs;
    }

    public synchronized void markFired(String ruleKind, long ts) {
        String key = boundedRuleKind(ruleKind);
        if (key.isEmpty()) return;
        if (!lastFiredByRule.containsKey(key)) {
            if (lastFiredByRule.size() >= MAX_RULE_KINDS) return;
            ruleBytes += utf8Bytes(key);
        }
        lastFiredByRule.put(key, ts);
    }

    /**
     * Record a successfully probed plugin name. Oversized names and entries
     * beyond the declared cap are ignored rather than retained in truncated
     * form, which could otherwise falsely match a different plugin.
     */
    public synchronized void noteProbed(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()
                || utf8BytesAtMost(pluginName, MAX_PROBED_COMMAND_BYTES)
                > MAX_PROBED_COMMAND_BYTES) return;
        if (probedCommands.contains(pluginName)) return;
        if (probedCommands.size() >= MAX_PROBED_COMMANDS) return;
        probedCommands.add(pluginName);
        probedBytes += utf8Bytes(pluginName);
    }

    public synchronized boolean hasProbed(String pluginName) {
        return pluginName != null && probedCommands.contains(pluginName);
    }

    public synchronized int probedCount() {
        return probedCommands.size();
    }

    public synchronized int retainedBytes() {
        return historyBytes + probedBytes + ruleBytes;
    }

    public synchronized void clear() {
        commandHistory.clear();
        lastFiredByRule.clear();
        probedCommands.clear();
        historyBytes = 0;
        probedBytes = 0;
        ruleBytes = 0;
    }

    private static String boundedRuleKind(String value) {
        return truncateUtf8(value, MAX_RULE_KIND_BYTES);
    }

    private static String normaliseDigest(String value, boolean alreadyDigested) {
        String candidate = value == null ? "" : value;
        if (alreadyDigested && candidate.length() == ARGS_DIGEST_BYTES) {
            boolean hex = true;
            for (int i = 0; i < candidate.length(); i++) {
                char c = candidate.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                    hex = false;
                    break;
                }
            }
            if (hex) return candidate;
        }
        return ResponseDedupCache.digestText(candidate);
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        if (utf8BytesAtMost(value, maxBytes) <= maxBytes) return value;
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = codePoint <= 0x7f ? 1
                    : codePoint <= 0x7ff ? 2
                    : codePoint <= 0xffff ? 3 : 4;
            if (bytes + width > maxBytes) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static int utf8Bytes(String value) {
        return value == null ? 0
                : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Returns maxBytes+1 as soon as the limit is exceeded. */
    private static int utf8BytesAtMost(String value, int maxBytes) {
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            bytes += codePoint <= 0x7f ? 1
                    : codePoint <= 0x7ff ? 2
                    : codePoint <= 0xffff ? 3 : 4;
            if (bytes > maxBytes) return maxBytes + 1;
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }
}
