package imagejai.engine.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Process-local file-selection brief delivered to an agent by pseudonym only.
 */
public final class Brief {
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)image-[0-9a-f]{4,12}(?:\\.[A-Za-z0-9.]+)?(?::\\d+)?");

    private final String sessionId;
    private final List<String> tokens;
    private final String tag;
    private final Map<String, Object> metadata;

    public Brief(String sessionId, List<String> tokens, String tag,
                 Map<String, Object> metadata) {
        this.sessionId = normaliseSession(sessionId);
        this.tokens = immutableTokens(tokens);
        this.tag = tag == null ? "" : tag.trim();
        this.metadata = immutableMetadata(metadata);
    }

    public String sessionId() {
        return sessionId;
    }

    public List<String> tokens() {
        return tokens;
    }

    public String tag() {
        return tag;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public static String normaliseSession(String sessionId) {
        String s = sessionId == null ? "" : sessionId.trim();
        return s.isEmpty() ? "default" : s;
    }

    private static List<String> immutableTokens(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                String cleaned = value == null ? "" : value.trim();
                if (!cleaned.isEmpty() && TOKEN_PATTERN.matcher(cleaned).matches()) {
                    out.add(cleaned);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
