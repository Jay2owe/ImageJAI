package imagejai.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Step 11 (docs/tcp_upgrade/11_dedup_response.md): per-socket response dedup
 * for read-only state queries. When an agent repeatedly polls the same
 * command within a short window and the response body is byte-identical to
 * the last one, short-circuit with
 * {@code {"unchanged": true, "since": ts, "ageMs": N}} instead of resending
 * the full ~700-token payload.
 *
 * <p>Distinct from the caller-driven {@code if_none_match} hash dedup already
 * implemented in {@link TCPCommandServer#applyReadonlyDedup}: that layer is
 * opt-in by the client, this layer is automatic for any caps.dedup=true
 * session. Both coexist — the automatic layer runs first and short-circuits
 * on a match; if it stores a fresh body, the legacy hash layer still attaches
 * its {@code hash} field for clients that want to drive opt-in dedup later.
 *
 * <p>Keyed on {@code (commandName, canonicalArgs)} where {@code canonicalArgs}
 * is a stable string form of the JSON request (sorted keys, framing fields
 * stripped). Value is {@code (bodyHash, timestampMs)}. Body hash is the
 * truncated 128 bits of SHA-256 over the response body, rendered as hex.
 *
 * <p>Window is 10 seconds by default; outside the window the cache never
 * short-circuits and the fresh body always wins. Cache size is capped at 200
 * entries per socket, LRU-evicted, so a session that churns distinct arg
 * combinations doesn't balloon memory.
 *
 * <p>Thread-safety: each instance is bound to a single connection and the
 * connection's TCP handler is single-threaded per request, so the internal
 * LRU map does not need external synchronisation. Guard anyway with
 * {@code synchronized} for belt-and-braces — a future batch/run path that
 * dispatches nested requests reusing the same caps would otherwise race.
 */
public final class ResponseDedupCache {

    /** Default sliding window during which a repeat hash short-circuits. */
    public static final long DEFAULT_WINDOW_MS = 10_000L;

    /** Default maximum distinct (command, args) keys held per socket. */
    public static final int DEFAULT_MAX_ENTRIES = 200;

    private final long windowMs;
    private final int maxEntries;
    private final LinkedHashMap<String, CachedEntry> cache;

    public ResponseDedupCache() {
        this(DEFAULT_WINDOW_MS, DEFAULT_MAX_ENTRIES);
    }

    public ResponseDedupCache(long windowMs, int maxEntries) {
        if (windowMs <= 0L) throw new IllegalArgumentException("windowMs must be positive");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
        this.windowMs = windowMs;
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<String, CachedEntry>(
                Math.min(16, maxEntries), 0.75f, true /* access-order LRU */) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
                return size() > ResponseDedupCache.this.maxEntries;
            }
        };
    }

    /**
     * Consult the cache for this (cmd, args) key. If a prior entry exists,
     * hashes match, and the prior entry is within the window, return the
     * short-form reply body and refresh the entry timestamp. Otherwise store
     * the fresh hash under the key and return {@link Optional#empty()}.
     *
     * <p>The fresh body is not mutated and not stored verbatim — only its
     * hash, which is what the next call compares against.
     *
     * @param cmd            command name (e.g. {@code "get_state"})
     * @param canonicalArgs  stable string form of the request args
     * @param freshBody      the computed response body (not mutated)
     * @return short-form body when the cache hits, else empty
     */
    public Optional<JsonObject> checkOrStore(
            String cmd, String canonicalArgs, JsonObject freshBody) {
        return checkOrStore(cmd, canonicalArgs, freshBody, System.currentTimeMillis());
    }

    /** Test-visible overload allowing a deterministic clock. */
    Optional<JsonObject> checkOrStore(
            String cmd, String canonicalArgs, JsonObject freshBody, long now) {
        if (cmd == null) cmd = "";
        if (canonicalArgs == null) canonicalArgs = "";
        String key = cmd + "|" + canonicalArgs;
        String freshHash = hash(freshBody);
        synchronized (cache) {
            CachedEntry prev = cache.get(key);
            if (prev != null
                    && prev.bodyHash.equals(freshHash)
                    && (now - prev.timestampMs) < windowMs) {
                long ageMs = now - prev.timestampMs;
                long since = prev.timestampMs;
                // Refresh timestamp so a continuous chain of identical
                // queries keeps deduping; the window is "time since last
                // distinct body", not "time since first identical body".
                cache.put(key, new CachedEntry(freshHash, now));
                JsonObject shortReply = new JsonObject();
                shortReply.addProperty("unchanged", true);
                shortReply.addProperty("since", since);
                shortReply.addProperty("ageMs", ageMs);
                return Optional.of(shortReply);
            }
            cache.put(key, new CachedEntry(freshHash, now));
            return Optional.empty();
        }
    }

    /** Drop every cached entry. Intended for tests only. */
    void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /** Current entry count. Intended for tests only. */
    int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /**
     * SHA-256 of the response body's canonical JSON form, truncated to the
     * leading 128 bits and rendered as lowercase hex. 128 bits is the plan's
     * spec and gives a birthday collision probability of ~2^-64 for a cache
     * size of millions of entries — orders of magnitude below any real risk.
     */
    static String hash(JsonElement body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] raw = md.digest(
                    canonicalJson(body).getBytes(StandardCharsets.UTF_8));
            // Truncate to 16 bytes (128 bits) — enough for dedup collision
            // resistance, cheaper than rendering the full 256-bit digest.
            StringBuilder sb = new StringBuilder(32);
            int limit = Math.min(16, raw.length);
            for (int i = 0; i < limit; i++) {
                int v = raw[i] & 0xff;
                if (v < 0x10) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            // MessageDigest.getInstance("SHA-256") is guaranteed by every
            // JDK; if we somehow land here, return a sentinel so the next
            // caller just stores-and-misses (safe, never false-unchanged).
            return "";
        }
    }

    /**
     * Canonical JSON for hashing: sort object keys alphabetically so two
     * logically identical bodies with different key insertion orders always
     * hash the same. Arrays keep their element order.
     */
    private static String canonicalJson(JsonElement el) {
        if (el == null) return "null";
        StringBuilder sb = new StringBuilder();
        writeCanonical(el, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonElement el, StringBuilder sb) {
        if (el == null || el.isJsonNull()) {
            sb.append("null");
            return;
        }
        if (el.isJsonPrimitive()) {
            sb.append(el.toString());
            return;
        }
        if (el.isJsonArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonElement child : el.getAsJsonArray()) {
                if (!first) sb.append(',');
                writeCanonical(child, sb);
                first = false;
            }
            sb.append(']');
            return;
        }
        // Object — sorted keys.
        JsonObject obj = el.getAsJsonObject();
        java.util.TreeMap<String, JsonElement> sorted = new java.util.TreeMap<String, JsonElement>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonElement> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            writeCanonical(e.getValue(), sb);
            first = false;
        }
        sb.append('}');
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else out.append(c);
        }
        return out.toString();
    }

    /**
     * Build a stable string form of the request object for use as part of
     * the cache key. Strips framing fields that don't describe the actual
     * query parameters ({@code command}, {@code if_none_match}, {@code force})
     * so an agent toggling {@code force} doesn't shift its cache key across
     * otherwise-identical queries.
     */
    public static String canonicalArgs(JsonObject request) {
        if (request == null) return "";
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> e : request.entrySet()) {
            String k = e.getKey();
            if ("command".equals(k) || "if_none_match".equals(k) || "force".equals(k)) {
                continue;
            }
            copy.add(k, e.getValue());
        }
        return canonicalJson(copy);
    }

    static final class CachedEntry {
        final String bodyHash;
        final long timestampMs;

        CachedEntry(String bodyHash, long timestampMs) {
            this.bodyHash = bodyHash;
            this.timestampMs = timestampMs;
        }
    }
}
