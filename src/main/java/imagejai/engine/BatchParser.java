package imagejai.engine;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse ||| shorthand chains into dispatchable TCP command objects.
 *
 * <p>Each segment maps to a JSON command. Supported keyword prefixes mirror
 * the {@code ij.py} subcommand vocabulary so the chain feels consistent:
 *
 * <ul>
 *   <li>{@code ping}, {@code state}, {@code info}, {@code results}, {@code log},
 *       {@code dialogs}, {@code windows}, {@code histogram}, {@code metadata} —
 *       readonly queries
 *   <li>{@code capture [name]} — capture_image
 *   <li>{@code measure} — execute_macro {@code run("Measure");}
 *   <li>{@code close_dialogs [pattern]} — close_dialogs
 *   <li>{@code macro <code>} — execute_macro with the remaining text as code
 *   <li>anything else — execute_macro with the whole segment as code
 * </ul>
 *
 * <p>Separator is three pipes {@code |||}. Escape a literal triple-pipe with
 * a preceding backslash: {@code \|||} becomes a literal {@code |||} inside the
 * segment rather than splitting.
 */
public class BatchParser {

    public static final String SEPARATOR = "|||";
    public static final int DEFAULT_MAX_SEGMENTS = 64;

    public static List<JsonObject> parse(String chain) {
        return parse(chain, DEFAULT_MAX_SEGMENTS);
    }

    /**
     * Parse at most {@code maxSegments}, aborting as soon as the next
     * non-empty segment is seen. This deliberately does not split the whole
     * input first: a separator-heavy request must not materialise thousands
     * of discarded strings before the caller can enforce its cap.
     */
    public static List<JsonObject> parse(String chain, int maxSegments) {
        if (chain == null) return new ArrayList<JsonObject>();
        if (maxSegments < 0) {
            throw new IllegalArgumentException("maxSegments must not be negative");
        }
        List<JsonObject> out = new ArrayList<JsonObject>(
                Math.min(maxSegments, 16));
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < chain.length()) {
            if (isEscapedSeparator(chain, i)) {
                current.append(SEPARATOR);
                i += 4;
                continue;
            }
            if (isSeparator(chain, i)) {
                addSegment(current, out, maxSegments, i);
                current.setLength(0);
                i += 3;
                continue;
            }
            current.append(chain.charAt(i++));
        }
        addSegment(current, out, maxSegments, chain.length());
        return out;
    }

    private static void addSegment(StringBuilder raw, List<JsonObject> out,
                                   int maxSegments, int offset) {
        String trimmed = raw.toString().trim();
        if (trimmed.isEmpty()) return;
        if (out.size() >= maxSegments) {
            throw new SegmentLimitException(maxSegments, offset);
        }
        out.add(parseSegment(trimmed));
    }

    private static boolean isEscapedSeparator(String value, int offset) {
        return offset + 3 < value.length()
                && value.charAt(offset) == '\\'
                && isSeparator(value, offset + 1);
    }

    private static boolean isSeparator(String value, int offset) {
        return offset + 2 < value.length()
                && value.charAt(offset) == '|'
                && value.charAt(offset + 1) == '|'
                && value.charAt(offset + 2) == '|';
    }

    public static final class SegmentLimitException extends IllegalArgumentException {
        private final int offset;

        SegmentLimitException(int maxSegments, int offset) {
            super("Chain segment count exceeds max " + maxSegments);
            this.offset = offset;
        }

        public int offset() { return offset; }
    }

    /** Package-private for tests. */
    static List<String> splitUnescaped(String s) {
        return splitUnescaped(s, DEFAULT_MAX_SEGMENTS);
    }

    /** Bounded legacy split seam; production parsing uses the streaming path. */
    static List<String> splitUnescaped(String s, int maxSegments) {
        if (maxSegments < 0) {
            throw new IllegalArgumentException("maxSegments must not be negative");
        }
        List<String> result = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            // Escaped separator: \|||
            if (isEscapedSeparator(s, i)) {
                cur.append("|||");
                i += 4;
                continue;
            }
            // Real separator
            if (isSeparator(s, i)) {
                if (result.size() >= maxSegments) {
                    throw new SegmentLimitException(maxSegments, i);
                }
                result.add(cur.toString());
                cur.setLength(0);
                i += 3;
                continue;
            }
            cur.append(s.charAt(i));
            i++;
        }
        if (result.size() >= maxSegments) {
            throw new SegmentLimitException(maxSegments, n);
        }
        result.add(cur.toString());
        return result;
    }

    /** Package-private for tests. */
    static JsonObject parseSegment(String seg) {
        // Split first token from the rest
        int spaceIdx = seg.indexOf(' ');
        String head = spaceIdx < 0 ? seg : seg.substring(0, spaceIdx);
        String rest = spaceIdx < 0 ? "" : seg.substring(spaceIdx + 1).trim();
        String lowerHead = head.toLowerCase();

        if (spaceIdx < 0) {
            // Single-word keywords
            if ("ping".equals(lowerHead)) return cmd("ping");
            if ("state".equals(lowerHead)) return cmd("get_state");
            if ("info".equals(lowerHead)) return cmd("get_image_info");
            if ("results".equals(lowerHead)) return cmd("get_results_table");
            if ("log".equals(lowerHead)) return cmd("get_log");
            if ("dialogs".equals(lowerHead)) return cmd("get_dialogs");
            if ("windows".equals(lowerHead)) return cmd("get_open_windows");
            if ("histogram".equals(lowerHead)) return cmd("get_histogram");
            if ("metadata".equals(lowerHead)) return cmd("get_metadata");
            if ("measure".equals(lowerHead)) {
                JsonObject c = cmd("execute_macro");
                c.addProperty("code", "run(\"Measure\");");
                return c;
            }
            if ("capture".equals(lowerHead)) return cmd("capture_image");
            if ("close_dialogs".equals(lowerHead)) return cmd("close_dialogs");
        } else {
            // Keyword + argument
            if ("capture".equals(lowerHead)) {
                JsonObject c = cmd("capture_image");
                if (!rest.isEmpty()) c.addProperty("name", rest);
                return c;
            }
            if ("close_dialogs".equals(lowerHead)) {
                JsonObject c = cmd("close_dialogs");
                if (!rest.isEmpty()) c.addProperty("pattern", rest);
                return c;
            }
            if ("macro".equals(lowerHead)) {
                JsonObject c = cmd("execute_macro");
                c.addProperty("code", rest);
                return c;
            }
        }

        // Default: whole segment is macro code
        JsonObject c = cmd("execute_macro");
        c.addProperty("code", seg);
        return c;
    }

    private static JsonObject cmd(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("command", name);
        return o;
    }
}
