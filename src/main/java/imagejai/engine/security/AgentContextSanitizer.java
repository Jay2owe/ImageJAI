package imagejai.engine.security;

import java.nio.charset.StandardCharsets;

/**
 * Wraps externally-sourced text in tagged envelopes before it crosses the
 * boundary into agent context. Strips C0/C1 control chars (preserving
 * {@code \n} and {@code \t}), length-caps to 8 KB UTF-8, and emits
 * {@code [sourceTag: <safe>]} so role-confusion injections cannot present
 * themselves as system or user voice. See
 * {@code docs/imagejai-publication/security/agent_context_sanitization.md}.
 */
public final class AgentContextSanitizer {

    public static final int DEFAULT_MAX_BYTES = 8192;
    private static final String TRUNCATED_SUFFIX = "...[truncated]";

    private AgentContextSanitizer() {}

    public static String wrap(String raw, String sourceTag) {
        return wrap(raw, sourceTag, DEFAULT_MAX_BYTES);
    }

    public static String wrap(String raw, String sourceTag, int maxBytes) {
        String tag = (sourceTag == null || sourceTag.isEmpty()) ? "UNTAGGED" : sourceTag;
        if (raw == null || raw.isEmpty()) {
            return "[" + tag + ": <empty>]";
        }
        String stripped = stripControlChars(raw);
        if (stripped.isEmpty()) {
            return "[" + tag + ": <empty>]";
        }
        int budget = maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
        byte[] utf8 = stripped.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= budget) {
            return "[" + tag + ": " + stripped + "]";
        }
        String truncated = truncateUtf8Safely(stripped, budget / 2);
        return "[" + tag + ": " + truncated + TRUNCATED_SUFFIX + "]";
    }

    private static String stripControlChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (cp == '\n' || cp == '\t'
                    || (cp > 0x1F && cp != 0x7F && !(cp >= 0x80 && cp <= 0x9F))) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static String truncateUtf8Safely(String s, int maxBytes) {
        if (maxBytes <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int byteCount = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpBytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (byteCount + cpBytes > maxBytes) break;
            sb.appendCodePoint(cp);
            byteCount += cpBytes;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
