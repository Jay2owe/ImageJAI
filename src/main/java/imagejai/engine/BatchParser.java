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

    public static List<JsonObject> parse(String chain) {
        if (chain == null) return new ArrayList<JsonObject>();
        List<String> segments = splitUnescaped(chain);
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            out.add(parseSegment(trimmed));
        }
        return out;
    }

    /** Package-private for tests. */
    static List<String> splitUnescaped(String s) {
        List<String> result = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            // Escaped separator: \|||
            if (i + 3 < n
                    && s.charAt(i) == '\\'
                    && s.charAt(i + 1) == '|'
                    && s.charAt(i + 2) == '|'
                    && s.charAt(i + 3) == '|') {
                cur.append("|||");
                i += 4;
                continue;
            }
            // Real separator
            if (i + 2 < n
                    && s.charAt(i) == '|'
                    && s.charAt(i + 1) == '|'
                    && s.charAt(i + 2) == '|') {
                result.add(cur.toString());
                cur.setLength(0);
                i += 3;
                continue;
            }
            cur.append(s.charAt(i));
            i++;
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
