package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-session response deduplication for read-only state queries.
 *
 * <p>Only fixed-size digests are retained. Caller-controlled request JSON and
 * response bodies are never stored in the cache. Canonical hashing is
 * iterative and bounded so an unexpectedly deep response cannot overflow the
 * Java stack or grow an unbounded temporary canonical string.</p>
 */
public final class ResponseDedupCache {

    public static final long DEFAULT_WINDOW_MS = 10_000L;
    public static final int DEFAULT_MAX_ENTRIES = 200;
    public static final int DIGEST_HEX_CHARS = 64;
    public static final int DEFAULT_MAX_RETAINED_KEY_BYTES =
            DEFAULT_MAX_ENTRIES * DIGEST_HEX_CHARS;

    private static final int MAX_CANONICAL_DEPTH = 128;
    private static final int MAX_CANONICAL_NODES = 100_000;
    private static final int MAX_CANONICAL_CONTAINER_ENTRIES = 20_000;
    private static final int MAX_CANONICAL_PRIMITIVE_CHARS = 4 * 1024 * 1024;
    private static final long MAX_CANONICAL_UTF8_BYTES = 32L * 1024L * 1024L;

    private final long windowMs;
    private final int maxEntries;
    private final int maxRetainedKeyBytes;
    private final LinkedHashMap<String, CachedEntry> cache;
    private int retainedKeyBytes;

    public ResponseDedupCache() {
        this(DEFAULT_WINDOW_MS, DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_RETAINED_KEY_BYTES);
    }

    public ResponseDedupCache(long windowMs, int maxEntries) {
        this(windowMs, maxEntries, Math.max(DIGEST_HEX_CHARS,
                Math.min(DEFAULT_MAX_RETAINED_KEY_BYTES,
                        saturatedMultiply(maxEntries, DIGEST_HEX_CHARS))));
    }

    ResponseDedupCache(long windowMs, int maxEntries, int maxRetainedKeyBytes) {
        if (windowMs <= 0L) throw new IllegalArgumentException("windowMs must be positive");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
        if (maxRetainedKeyBytes < DIGEST_HEX_CHARS) {
            throw new IllegalArgumentException("maxRetainedKeyBytes is too small");
        }
        this.windowMs = windowMs;
        this.maxEntries = maxEntries;
        this.maxRetainedKeyBytes = maxRetainedKeyBytes;
        this.cache = new LinkedHashMap<String, CachedEntry>(
                Math.min(16, maxEntries), 0.75f, true);
    }

    public Optional<JsonObject> checkOrStore(
            String cmd, String canonicalArgs, JsonObject freshBody) {
        return checkOrStore(cmd, canonicalArgs, freshBody, System.currentTimeMillis());
    }

    Optional<JsonObject> checkOrStore(
            String cmd, String canonicalArgs, JsonObject freshBody, long now) {
        return checkOrStoreHash(cmd, canonicalArgs, hash(freshBody), now);
    }

    Optional<JsonObject> checkOrStoreHash(
            String cmd, String canonicalArgs, String freshHash, long now) {
        // A failed/over-budget canonicalisation must be a cache miss, never a
        // shared sentinel that could produce a false "unchanged" response.
        if (freshHash == null || freshHash.isEmpty()) return Optional.empty();
        String key = digestText((cmd == null ? "" : cmd) + "|"
                + (canonicalArgs == null ? "" : canonicalArgs));
        synchronized (cache) {
            CachedEntry prev = cache.get(key);
            if (prev != null
                    && prev.bodyHash.equals(freshHash)
                    && (now - prev.timestampMs) < windowMs) {
                long ageMs = now - prev.timestampMs;
                long since = prev.timestampMs;
                cache.put(key, new CachedEntry(freshHash, now));
                JsonObject shortReply = new JsonObject();
                shortReply.addProperty("unchanged", true);
                shortReply.addProperty("since", since);
                shortReply.addProperty("ageMs", ageMs);
                return Optional.of(shortReply);
            }
            putBounded(key, new CachedEntry(freshHash, now));
            return Optional.empty();
        }
    }

    private void putBounded(String key, CachedEntry entry) {
        if (!cache.containsKey(key)) retainedKeyBytes += key.length();
        cache.put(key, entry);
        Iterator<Map.Entry<String, CachedEntry>> iterator =
                cache.entrySet().iterator();
        while ((cache.size() > maxEntries
                || retainedKeyBytes > maxRetainedKeyBytes) && iterator.hasNext()) {
            Map.Entry<String, CachedEntry> eldest = iterator.next();
            retainedKeyBytes -= eldest.getKey().length();
            iterator.remove();
        }
    }

    void clear() {
        synchronized (cache) {
            cache.clear();
            retainedKeyBytes = 0;
        }
    }

    int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    int retainedKeyBytes() {
        synchronized (cache) {
            return retainedKeyBytes;
        }
    }

    /** Stable 128-bit response identity, rendered as 32 lowercase hex chars. */
    static String hash(JsonElement body) {
        return hash(body, Collections.<String>emptySet());
    }

    /** Stable response identity with selected object keys omitted at any depth. */
    static String hash(JsonElement body, Set<String> excludedKeys) {
        byte[] digest = canonicalDigest(body,
                excludedKeys == null ? Collections.<String>emptySet() : excludedKeys);
        if (digest == null) return "";
        return toHex(digest, 16);
    }

    /** Full SHA-256 identity for caller-controlled text retained by telemetry. */
    static String digestText(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return toHex(digest.digest(), 32);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * Build a stable fixed-size identity of request arguments. Framing fields
     * are omitted without copying or retaining the nested request tree.
     */
    public static String canonicalArgs(JsonObject request) {
        if (request == null) return digestText("");
        JsonObject args = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : request.entrySet()) {
            String key = entry.getKey();
            if ("command".equals(key) || "if_none_match".equals(key)
                    || "force".equals(key) || "token".equals(key)
                    || "session_id".equals(key)) {
                continue;
            }
            args.add(key, entry.getValue());
        }
        byte[] digest = canonicalDigest(args, Collections.<String>emptySet());
        return digest == null
                ? digestText("canonicalisation-rejected")
                : toHex(digest, 32);
    }

    /** Iterative canonical JSON hashing; never recurses or materialises JSON. */
    private static byte[] canonicalDigest(JsonElement root, Set<String> excludedKeys) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestBudget budget = new DigestBudget(digest);
            Deque<Frame> stack = new ArrayDeque<Frame>();
            stack.push(new Frame(root, 1));
            int nodes = 0;
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (!frame.entered) {
                    frame.entered = true;
                    nodes++;
                    if (nodes > MAX_CANONICAL_NODES || frame.depth > MAX_CANONICAL_DEPTH) {
                        return null;
                    }
                    JsonElement element = frame.element;
                    if (element == null || element.isJsonNull()) {
                        budget.add("null");
                        stack.pop();
                    } else if (element.isJsonPrimitive()) {
                        JsonPrimitive primitive = element.getAsJsonPrimitive();
                        if (primitive.isString()
                                && primitive.getAsString().length()
                                > MAX_CANONICAL_PRIMITIVE_CHARS) return null;
                        budget.add(primitive.toString());
                        stack.pop();
                    } else if (element.isJsonArray()) {
                        frame.array = element.getAsJsonArray();
                        if (frame.array.size() > MAX_CANONICAL_CONTAINER_ENTRIES) return null;
                        budget.add("[");
                    } else {
                        JsonObject object = element.getAsJsonObject();
                        if (object.size() > MAX_CANONICAL_CONTAINER_ENTRIES) return null;
                        frame.object = object;
                        frame.keys = object.keySet().toArray(new String[0]);
                        Arrays.sort(frame.keys);
                        budget.add("{");
                    }
                    if (budget.exceeded()) return null;
                    continue;
                }

                if (frame.array != null) {
                    if (frame.index >= frame.array.size()) {
                        budget.add("]");
                        stack.pop();
                    } else {
                        if (frame.index > 0) budget.add(",");
                        JsonElement child = frame.array.get(frame.index++);
                        stack.push(new Frame(child, frame.depth + 1));
                    }
                } else if (frame.object != null) {
                    while (frame.index < frame.keys.length
                            && excludedKeys.contains(frame.keys[frame.index])) {
                        frame.index++;
                    }
                    if (frame.index >= frame.keys.length) {
                        budget.add("}");
                        stack.pop();
                    } else {
                        String key = frame.keys[frame.index++];
                        if (key.length() > MAX_CANONICAL_PRIMITIVE_CHARS) return null;
                        if (frame.emittedEntries++ > 0) budget.add(",");
                        budget.add(new JsonPrimitive(key).toString());
                        budget.add(":");
                        stack.push(new Frame(frame.object.get(key), frame.depth + 1));
                    }
                }
                if (budget.exceeded()) return null;
            }
            return digest.digest();
        } catch (Exception failure) {
            return null;
        }
    }

    private static String toHex(byte[] bytes, int requestedBytes) {
        StringBuilder out = new StringBuilder(requestedBytes * 2);
        int count = Math.min(requestedBytes, bytes.length);
        for (int i = 0; i < count; i++) {
            int value = bytes[i] & 0xff;
            if (value < 0x10) out.append('0');
            out.append(Integer.toHexString(value));
        }
        return out.toString();
    }

    private static int saturatedMultiply(int left, int right) {
        long value = (long) left * (long) right;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static final class DigestBudget {
        final MessageDigest digest;
        long bytes;

        DigestBudget(MessageDigest digest) {
            this.digest = digest;
        }

        void add(String value) {
            if (value == null || exceeded()) return;
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            bytes += encoded.length;
            if (!exceeded()) digest.update(encoded);
        }

        boolean exceeded() {
            return bytes > MAX_CANONICAL_UTF8_BYTES;
        }
    }

    private static final class Frame {
        final JsonElement element;
        final int depth;
        boolean entered;
        int index;
        int emittedEntries;
        JsonArray array;
        JsonObject object;
        String[] keys;

        Frame(JsonElement element, int depth) {
            this.element = element;
            this.depth = depth;
        }
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
