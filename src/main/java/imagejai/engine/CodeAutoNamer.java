package imagejai.engine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cascading auto-namer for captured macros / scripts. Zero agent cost —
 * runs only on the Java side after the agent has already sent its code
 * via {@code execute_macro} / {@code run_script}.
 *
 * <p>Strategy (first non-empty slug wins):
 * <ol>
 *   <li>Leading comment (first ~5 non-blank lines, {@code //}, {@code #},
 *       {@code /* ... *}{@code /}); skip-list filters stock boilerplate.</li>
 *   <li>Composite {@code run("...")} signature — up to 3 distinct
 *       non-plumbing plugin names joined with {@code __}.</li>
 *   <li>First defined symbol ({@code def}/{@code function}/assignment).</li>
 *   <li>Fallback {@code macro_<HHMMSS>} / {@code script_<HHMMSS>}.</li>
 * </ol>
 */
public final class CodeAutoNamer {

    private static final int MAX_SLUG_LEN = 50;

    /** Plumbing / state-management ops — skipped when picking plugin names. */
    private static final Set<String> PLUMBING = new LinkedHashSet<>();
    static {
        PLUMBING.add("close");
        PLUMBING.add("close all");
        PLUMBING.add("duplicate...");
        PLUMBING.add("duplicate");
        PLUMBING.add("select none");
        PLUMBING.add("select all");
        PLUMBING.add("make inverse");
        PLUMBING.add("clear results");
        PLUMBING.add("set measurements");
        PLUMBING.add("set measurements...");
        PLUMBING.add("properties...");
        PLUMBING.add("set scale...");
        PLUMBING.add("rename");
        PLUMBING.add("rename...");
    }

    /** Stock boilerplate comments the agent likes to add — treat as no-name. */
    private static final Pattern BOILERPLATE = Pattern.compile(
            "^(auto[\\- ]generated|claude[\\- ]agent[\\- ]executed"
                    + "|run\\s+by.*agent|ai[\\- ]generated)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LEADING_COMMENT = Pattern.compile(
            "^\\s*(?://\\s*(.*)|#\\s*(.*)|/\\*\\s*(.*?)(?:\\*/)?\\s*$)");
    private static final Pattern RUN_CALL = Pattern.compile(
            "\\brun\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern DEF_GROOVY = Pattern.compile(
            "\\bdef\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FUNCTION = Pattern.compile(
            "\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "^\\s*(?!if\\b|for\\b|while\\b|return\\b)"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*=",
            Pattern.MULTILINE);

    private CodeAutoNamer() {}

    /**
     * Build a slug for the given code. Never returns null or blank; the
     * timestamp-fallback guarantees at least {@code macro_<HHMMSS>}.
     *
     * @param language   "ijm" / "groovy" / "jython" / "javascript" / null
     * @param code       the raw macro / script
     * @param timeSuffix a short time suffix for the fallback (e.g. "143215")
     */
    public static String nameFor(String language, String code, String timeSuffix) {
        String slug = fromLeadingComment(code);
        if (slug == null || slug.isEmpty()) slug = fromRunCalls(code);
        if (slug == null || slug.isEmpty()) slug = fromFirstSymbol(code);
        if (slug == null || slug.isEmpty()) {
            String prefix = (language == null || "ijm".equalsIgnoreCase(language))
                    ? "macro" : "script";
            slug = prefix + "_" + timeSuffix;
        }
        return slug;
    }

    private static String fromLeadingComment(String code) {
        int scanned = 0;
        for (String raw : code.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (scanned++ >= 5) break;
            Matcher m = LEADING_COMMENT.matcher(line);
            if (!m.find()) continue;
            String text = firstNonNull(m.group(1), m.group(2), m.group(3));
            if (text == null) continue;
            text = text.trim();
            if (text.isEmpty()) continue;
            if (BOILERPLATE.matcher(text).find()) continue;
            String slug = slugify(text);
            if (!slug.isEmpty()) return slug;
        }
        return null;
    }

    private static String fromRunCalls(String code) {
        List<String> distinct = new ArrayList<>();
        Matcher m = RUN_CALL.matcher(code);
        while (m.find()) {
            String name = m.group(1).trim();
            if (PLUMBING.contains(name.toLowerCase())) continue;
            String slug = slugify(name);
            if (slug.isEmpty()) continue;
            if (!distinct.contains(slug)) distinct.add(slug);
            if (distinct.size() >= 3) break;
        }
        if (distinct.isEmpty()) {
            // Fall back to plumbing-only signature so the entry is still nameable
            m = RUN_CALL.matcher(code);
            while (m.find()) {
                String slug = slugify(m.group(1));
                if (!slug.isEmpty() && !distinct.contains(slug)) distinct.add(slug);
                if (distinct.size() >= 2) break;
            }
        }
        if (distinct.isEmpty()) return null;
        return truncate(String.join("__", distinct));
    }

    private static String fromFirstSymbol(String code) {
        Matcher m = DEF_GROOVY.matcher(code);
        if (m.find()) return slugify(m.group(1));
        m = FUNCTION.matcher(code);
        if (m.find()) return slugify(m.group(1));
        m = ASSIGNMENT.matcher(code);
        if (m.find()) return slugify(m.group(1));
        return null;
    }

    /**
     * Slugify to {@code [a-z0-9_]+}, collapsed underscores, trimmed,
     * truncated at {@link #MAX_SLUG_LEN} at a word boundary.
     */
    static String slugify(String in) {
        if (in == null) return "";
        String lower = Normalizer.normalize(in.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '_') sb.append('_');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') sb.deleteCharAt(sb.length() - 1);
        while (sb.length() > 0 && sb.charAt(0) == '_') sb.deleteCharAt(0);
        return truncate(sb.toString());
    }

    private static String truncate(String slug) {
        if (slug.length() <= MAX_SLUG_LEN) return slug;
        int cut = slug.lastIndexOf("__", MAX_SLUG_LEN);
        if (cut > 0) return slug.substring(0, cut);
        cut = slug.lastIndexOf("_", MAX_SLUG_LEN);
        if (cut > 0) return slug.substring(0, cut);
        return slug.substring(0, MAX_SLUG_LEN);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }
}
