package imagejai.engine.security;

/**
 * Wraps untrusted text in a bounded, control-free, source-tagged envelope.
 * Scanning and allocation stop at explicit limits; the implementation never
 * copies or UTF-8-encodes the complete untrusted input before enforcing them.
 */
public final class AgentContextSanitizer {

    public static final int DEFAULT_MAX_BYTES = 8192;
    public static final int MAX_SCAN_CHARS = 65_536;
    static final int MAX_SOURCE_TAG_SCAN_CHARS = 1_024;
    static final int MAX_SOURCE_TAG_BYTES = 96;
    private static final int MIN_ENVELOPE_BYTES = 5; // [X: ]
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    private static final String EMPTY = "<empty>";

    private AgentContextSanitizer() {}

    public static String wrap(String raw, String sourceTag) {
        return wrap(raw, sourceTag, DEFAULT_MAX_BYTES);
    }

    /**
     * Return a valid UTF-8 envelope whose encoded size is at most maxBytes.
     * A positive budget smaller than the minimal tagged envelope is rejected
     * instead of silently returning an over-budget value.
     */
    public static String wrap(String raw, String sourceTag, int maxBytes) {
        int budget = maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
        if (budget < MIN_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("maxBytes must be at least "
                    + MIN_ENVELOPE_BYTES);
        }

        String tag = sanitiseTag(sourceTag);
        int tagBudget = budget - 4;
        if (tag.length() > tagBudget) tag = tag.substring(0, tagBudget);
        if (tag.isEmpty()) tag = "X";

        String prefix = "[" + tag + ": ";
        int contentBudget = budget - prefix.length() - 1;
        Content content = sanitiseContent(raw, contentBudget);
        StringBuilder result = new StringBuilder(
                prefix.length() + content.text.length() + 1);
        result.append(prefix).append(content.text).append(']');
        return result.toString();
    }

    private static String sanitiseTag(String raw) {
        String source = raw == null || raw.isEmpty() ? "UNTAGGED" : raw;
        StringBuilder out = new StringBuilder(Math.min(
                source.length(), MAX_SOURCE_TAG_BYTES));
        int limit = Math.min(source.length(), MAX_SOURCE_TAG_SCAN_CHARS);
        for (int i = 0; i < limit && out.length() < MAX_SOURCE_TAG_BYTES; i++) {
            char c = source.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_'
                    || c == ':' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.length() == 0 ? "UNTAGGED" : out.toString();
    }

    private static Content sanitiseContent(String raw, int maxBytes) {
        String source = raw == null ? "" : raw;
        int scanLimit = Math.min(source.length(), MAX_SCAN_CHARS);
        StringBuilder out = new StringBuilder(Math.min(maxBytes, 4096));
        int bytes = 0;
        int i = 0;
        boolean truncated = false;

        while (i < scanLimit) {
            char first = source.charAt(i);
            int cp;
            int chars;
            if (Character.isHighSurrogate(first)
                    && i + 1 < source.length()
                    && Character.isLowSurrogate(source.charAt(i + 1))) {
                cp = Character.toCodePoint(first, source.charAt(i + 1));
                chars = 2;
            } else if (Character.isSurrogate(first)) {
                cp = 0xfffd;
                chars = 1;
            } else {
                cp = first;
                chars = 1;
            }
            if (i + chars > scanLimit) {
                truncated = true;
                break;
            }
            i += chars;
            if (!allowed(cp)) continue;
            int cpBytes = utf8Bytes(cp);
            if (bytes + cpBytes > maxBytes) {
                truncated = true;
                break;
            }
            out.appendCodePoint(cp);
            bytes += cpBytes;
        }
        if (i < source.length()) truncated = true;

        if (truncated) {
            int markerBytes = Math.min(TRUNCATED_SUFFIX.length(), maxBytes);
            while (bytes + markerBytes > maxBytes && out.length() > 0) {
                int cp = out.codePointBefore(out.length());
                out.setLength(out.length() - Character.charCount(cp));
                bytes -= utf8Bytes(cp);
            }
            if (markerBytes > 0) {
                out.append(TRUNCATED_SUFFIX, 0, markerBytes);
            }
        } else if (out.length() == 0 && EMPTY.length() <= maxBytes) {
            out.append(EMPTY);
        }
        return new Content(out.toString());
    }

    private static boolean allowed(int cp) {
        return cp == '\n' || cp == '\t'
                || (cp > 0x1f && cp != 0x7f && !(cp >= 0x80 && cp <= 0x9f));
    }

    private static int utf8Bytes(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }

    private static final class Content {
        final String text;
        Content(String text) { this.text = text; }
    }
}
