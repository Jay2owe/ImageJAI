package imagejai.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * In-memory ring buffer of recent TCP command failures.
 *
 * <p>A confused agent can query {@code get_friction_log} / {@code get_friction_patterns}
 * to ask the plugin "what have I been failing at?". Patterns are groupings of
 * (command, normalised-error) with count greater or equal to {@link #PATTERN_THRESHOLD}
 * within the last {@link #WINDOW_MS} ms.
 *
 * <p>Thread-safe. In-memory only — never persisted to disk.
 */
public class FrictionLog {

    /** Max entries retained. Older entries drop off. */
    public static final int CAPACITY = 100;

    /** Recent-window for pattern detection (ms). */
    public static final long WINDOW_MS = 600_000L; // 10 minutes

    /** Minimum repetitions to count as a pattern. */
    public static final int PATTERN_THRESHOLD = 3;

    public static class FailureEntry {
        public final long ts;
        public final String command;
        public final String argsSummary;
        public final String error;
        public final String normalisedError;

        FailureEntry(long ts, String command, String argsSummary, String error) {
            this.ts = ts;
            this.command = command;
            this.argsSummary = argsSummary;
            this.error = error;
            this.normalisedError = normaliseError(error);
        }
    }

    public static class Pattern {
        public final String command;
        public final String normalisedError;
        public final String sampleError;
        public final int count;
        public final long firstTs;
        public final long lastTs;

        Pattern(String command, String normalisedError, String sampleError,
                int count, long firstTs, long lastTs) {
            this.command = command;
            this.normalisedError = normalisedError;
            this.sampleError = sampleError;
            this.count = count;
            this.firstTs = firstTs;
            this.lastTs = lastTs;
        }
    }

    private final Deque<FailureEntry> entries = new ArrayDeque<FailureEntry>();

    public synchronized void record(String command, String argsSummary, String error) {
        if (command == null) command = "";
        if (error == null) error = "";
        if (argsSummary == null) argsSummary = "";
        FailureEntry e = new FailureEntry(System.currentTimeMillis(), command, argsSummary, error);
        if (entries.size() >= CAPACITY) entries.removeFirst();
        entries.addLast(e);
    }

    /** Most recent entries first (descending timestamp). */
    public synchronized List<FailureEntry> recent(int limit) {
        List<FailureEntry> out = new ArrayList<FailureEntry>();
        Iterator<FailureEntry> it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    /** Patterns (groupings of recurring failures) within the recent window. */
    public synchronized List<Pattern> patterns() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;

        // Use LinkedHashMap-style iteration over separate maps to stay Java 8 friendly.
        Map<String, int[]> countMap = new HashMap<String, int[]>();
        Map<String, long[]> tsMap = new HashMap<String, long[]>();
        Map<String, String> sampleMap = new HashMap<String, String>();
        Map<String, String[]> partsMap = new HashMap<String, String[]>();

        for (FailureEntry e : entries) {
            if (e.ts < cutoff) continue;
            String key = e.command + "::" + e.normalisedError;
            int[] c = countMap.get(key);
            if (c == null) {
                countMap.put(key, new int[]{1});
                tsMap.put(key, new long[]{e.ts, e.ts});
                sampleMap.put(key, e.error);
                partsMap.put(key, new String[]{e.command, e.normalisedError});
            } else {
                c[0]++;
                long[] ts = tsMap.get(key);
                if (e.ts < ts[0]) ts[0] = e.ts;
                if (e.ts > ts[1]) ts[1] = e.ts;
            }
        }

        List<Pattern> out = new ArrayList<Pattern>();
        for (Map.Entry<String, int[]> ent : countMap.entrySet()) {
            int count = ent.getValue()[0];
            if (count < PATTERN_THRESHOLD) continue;
            String key = ent.getKey();
            String[] p = partsMap.get(key);
            long[] ts = tsMap.get(key);
            out.add(new Pattern(p[0], p[1], sampleMap.get(key), count, ts[0], ts[1]));
        }
        return out;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /**
     * Normalise an error string for pattern grouping. Strips noise (paths,
     * numbers, hex) so "File /a/b.tif not found" and "File /c/d.tif not found"
     * collapse to the same key while the original error stays in the entry.
     */
    static String normaliseError(String error) {
        if (error == null) return "";
        String s = error;
        // Windows paths: C:\foo\bar or C:/foo/bar
        s = s.replaceAll("[A-Za-z]:[\\\\/][^\\s'\"]+", "<path>");
        // Absolute unix paths preceded by whitespace
        s = s.replaceAll("(^|\\s)/[^\\s'\"]+", "$1<path>");
        // Hex addresses
        s = s.replaceAll("0x[0-9a-fA-F]+", "<hex>");
        // Bare integers
        s = s.replaceAll("\\b\\d+\\b", "N");
        // Collapse whitespace
        s = s.replaceAll("\\s+", " ").trim().toLowerCase();
        if (s.length() > 200) s = s.substring(0, 200);
        return s;
    }
}
