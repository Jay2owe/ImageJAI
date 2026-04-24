package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the Step 11 response-dedup cache
 * (docs/tcp_upgrade/11_dedup_response.md). The cache is pure server-side
 * state keyed on a single connection; these tests exercise it directly
 * without any live Fiji or TCP stack.
 */
public class ResponseDedupCacheTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Two identical bodies within the window → second returns {@code unchanged:true}. */
    @Test
    public void identicalBodiesWithinWindowShortCircuit() {
        ResponseDedupCache cache = new ResponseDedupCache(10_000L, 200);
        JsonObject body = parse("{\"ok\":true,\"result\":{\"title\":\"blobs.tif\",\"w\":256}}");

        Optional<JsonObject> first = cache.checkOrStore("get_state", "", body, 1_000_000L);
        Optional<JsonObject> second = cache.checkOrStore("get_state", "", body, 1_002_400L);

        assertFalse("first call stores and returns empty", first.isPresent());
        assertTrue("second call must short-circuit", second.isPresent());
        JsonObject reply = second.get();
        assertTrue(reply.get("unchanged").getAsBoolean());
        assertEquals(1_000_000L, reply.get("since").getAsLong());
        assertEquals(2_400L, reply.get("ageMs").getAsLong());
    }

    /** A body that changed between calls → second returns fresh (empty Optional). */
    @Test
    public void differentBodiesAlwaysReturnFresh() {
        ResponseDedupCache cache = new ResponseDedupCache();
        JsonObject before = parse("{\"result\":{\"nImages\":1}}");
        JsonObject after  = parse("{\"result\":{\"nImages\":2}}");

        Optional<JsonObject> first = cache.checkOrStore("get_state", "", before);
        Optional<JsonObject> second = cache.checkOrStore("get_state", "", after);

        assertFalse(first.isPresent());
        assertFalse("hash changed → fresh body wins", second.isPresent());
    }

    /** 12-second gap exceeds the 10-second window → cache miss. */
    @Test
    public void gapBeyondWindowDoesNotShortCircuit() {
        ResponseDedupCache cache = new ResponseDedupCache(10_000L, 200);
        JsonObject body = parse("{\"result\":{\"stable\":1}}");

        Optional<JsonObject> first = cache.checkOrStore("get_state", "", body, 5_000L);
        Optional<JsonObject> second = cache.checkOrStore("get_state", "", body, 17_000L);

        assertFalse(first.isPresent());
        assertFalse("12-second gap is outside window", second.isPresent());
    }

    /** A chain of identical calls keeps short-circuiting; each refreshes the timestamp. */
    @Test
    public void continuousChainKeepsDeduping() {
        ResponseDedupCache cache = new ResponseDedupCache(10_000L, 200);
        JsonObject body = parse("{\"result\":{\"x\":1}}");

        cache.checkOrStore("get_state", "", body, 0L);
        Optional<JsonObject> second = cache.checkOrStore("get_state", "", body, 8_000L);
        Optional<JsonObject> third  = cache.checkOrStore("get_state", "", body, 16_000L);
        Optional<JsonObject> fourth = cache.checkOrStore("get_state", "", body, 24_000L);

        assertTrue(second.isPresent());
        assertTrue("refreshed timestamp keeps third call inside window", third.isPresent());
        assertTrue("refreshed timestamp keeps fourth call inside window", fourth.isPresent());
        // ageMs tracks time since *last* refresh, not original insert.
        assertEquals(8_000L, fourth.get().get("ageMs").getAsLong());
    }

    /** Different commands with the same args each get their own cache line. */
    @Test
    public void differentCommandsHaveIndependentEntries() {
        ResponseDedupCache cache = new ResponseDedupCache();
        JsonObject body = parse("{\"result\":{\"same\":true}}");

        Optional<JsonObject> a1 = cache.checkOrStore("get_state", "", body);
        Optional<JsonObject> b1 = cache.checkOrStore("get_image_info", "", body);
        Optional<JsonObject> a2 = cache.checkOrStore("get_state", "", body);
        Optional<JsonObject> b2 = cache.checkOrStore("get_image_info", "", body);

        assertFalse(a1.isPresent());
        assertFalse(b1.isPresent());
        assertTrue("second get_state is a hit", a2.isPresent());
        assertTrue("second get_image_info is a hit", b2.isPresent());
    }

    /** Same command with different args → separate cache lines. */
    @Test
    public void differentArgsHaveIndependentEntries() {
        ResponseDedupCache cache = new ResponseDedupCache();
        JsonObject body = parse("{\"result\":{\"x\":1}}");

        cache.checkOrStore("get_log", "lines=10", body);
        Optional<JsonObject> hitA = cache.checkOrStore("get_log", "lines=10", body);
        // Different args: different cache line, first call is a miss.
        Optional<JsonObject> missB = cache.checkOrStore("get_log", "lines=20", body);

        assertTrue(hitA.isPresent());
        assertFalse(missB.isPresent());
    }

    /** LRU cap evicts oldest entries when exceeded. */
    @Test
    public void lruEvictsBeyondCap() {
        ResponseDedupCache cache = new ResponseDedupCache(10_000L, /* maxEntries= */ 2);
        JsonObject body = parse("{\"result\":{\"x\":1}}");

        cache.checkOrStore("get_state",      "a", body);
        cache.checkOrStore("get_image_info", "b", body);
        cache.checkOrStore("get_log",        "c", body);

        assertEquals("max 2 entries retained", 2, cache.size());
        // The first inserted key ("get_state|a") should have been evicted.
        Optional<JsonObject> firstAgain = cache.checkOrStore("get_state", "a", body);
        assertFalse("evicted key behaves like a fresh miss", firstAgain.isPresent());
    }

    /**
     * Key insertion order in the response body does not change the hash —
     * canonical JSON sorts keys alphabetically.
     */
    @Test
    public void keyInsertionOrderDoesNotChangeHash() {
        ResponseDedupCache cache = new ResponseDedupCache();
        JsonObject shapeA = new JsonObject();
        shapeA.addProperty("alpha", 1);
        shapeA.addProperty("beta",  2);

        JsonObject shapeB = new JsonObject();
        shapeB.addProperty("beta",  2);
        shapeB.addProperty("alpha", 1);

        cache.checkOrStore("get_state", "", shapeA);
        Optional<JsonObject> hit = cache.checkOrStore("get_state", "", shapeB);

        assertTrue("same content, different key order → cache hit", hit.isPresent());
    }

    /**
     * {@link ResponseDedupCache#canonicalArgs} strips framing fields so the
     * cache key is stable across toggles of {@code force} and
     * {@code if_none_match}.
     */
    @Test
    public void canonicalArgsStripsFramingFields() {
        JsonObject bare = parse("{\"command\":\"get_state\"}");
        JsonObject withForce = parse(
                "{\"command\":\"get_state\",\"force\":true,\"if_none_match\":\"abc\"}");
        assertEquals(
                ResponseDedupCache.canonicalArgs(bare),
                ResponseDedupCache.canonicalArgs(withForce));
    }

    /** Payload-bearing fields distinguish cache keys (tail windows etc.). */
    @Test
    public void canonicalArgsKeepsPayloadFields() {
        JsonObject ten = parse("{\"command\":\"get_log\",\"tail\":10}");
        JsonObject twenty = parse("{\"command\":\"get_log\",\"tail\":20}");
        assertNotEquals(
                ResponseDedupCache.canonicalArgs(ten),
                ResponseDedupCache.canonicalArgs(twenty));
    }

    /** Hash output is stable and hex-shaped. */
    @Test
    public void hashIsStableHex() {
        JsonObject body = parse("{\"a\":1,\"b\":[1,2,3]}");
        String h1 = ResponseDedupCache.hash(body);
        String h2 = ResponseDedupCache.hash(body);
        assertEquals(h1, h2);
        assertEquals("truncated SHA-256 → 32 hex chars", 32, h1.length());
        for (int i = 0; i < h1.length(); i++) {
            char c = h1.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            assertTrue("hash must be lowercase hex", hex);
        }
    }

    /** Arrays preserve element order when hashing (order-sensitive). */
    @Test
    public void arrayOrderMatters() {
        JsonObject a = new JsonObject();
        JsonArray a1 = new JsonArray();
        a1.add(1); a1.add(2); a1.add(3);
        a.add("xs", a1);

        JsonObject b = new JsonObject();
        JsonArray b1 = new JsonArray();
        b1.add(3); b1.add(2); b1.add(1);
        b.add("xs", b1);

        assertNotEquals(
                ResponseDedupCache.hash(a),
                ResponseDedupCache.hash(b));
    }
}
