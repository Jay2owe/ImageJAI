package imagejai.engine.picker;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase G acceptance — docs/multi_provider/02_curation_strategy.md §5.
 *
 * <p>Covers fresh fetch / 24h hit / stale-but-served-on-failure.
 */
public class ModelsCacheTest {

    @Test
    public void writeThenRead_roundTrips() throws IOException {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);

        Instant fetchedAt = Instant.parse("2026-05-02T09:14:33Z");
        Set<String> ids = new LinkedHashSet<String>(Arrays.asList(
                "claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5"));
        cache.write("anthropic", fetchedAt, "https://api.anthropic.com/v1/models", ids);

        ModelsCache.Snapshot snap = cache.read("anthropic");
        assertNotNull(snap);
        assertEquals("anthropic", snap.providerId());
        assertEquals(fetchedAt, snap.fetchedAt());
        assertEquals(3, snap.modelIds().size());
        assertEquals("claude-opus-4-7", snap.modelIds().get(0));
    }

    @Test
    public void isFresh_withinTTL_true() throws IOException {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        Instant fetchedAt = Instant.parse("2026-05-02T09:00:00Z");
        cache.write("groq", fetchedAt, "https://api.groq.com/openai/v1/models",
                new LinkedHashSet<String>(Arrays.asList("llama-3.3-70b-versatile")));
        Instant fiveHoursLater = fetchedAt.plus(java.time.Duration.ofHours(5));
        assertTrue(cache.isFresh("groq", fiveHoursLater));
    }

    @Test
    public void isFresh_pastTTL_false() throws IOException {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        Instant fetchedAt = Instant.parse("2026-05-02T09:00:00Z");
        cache.write("groq", fetchedAt, "https://api.groq.com/openai/v1/models",
                new LinkedHashSet<String>(Arrays.asList("llama-3.3-70b-versatile")));
        Instant twoDaysLater = fetchedAt.plus(java.time.Duration.ofDays(2));
        assertFalse(cache.isFresh("groq", twoDaysLater));
    }

    @Test
    public void readMissing_returnsNull() throws IOException {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        assertNull(cache.read("nope"));
        assertFalse(cache.has("nope"));
    }

    @Test
    public void staleSnapshot_stillReadable_servesAsFallback() throws IOException {
        // Stale-but-present beats empty (02 §5): verify a stale cache can still
        // be read by callers when a refresh fails.
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        Instant fetchedAt = Instant.parse("2026-04-01T09:00:00Z");
        cache.write("openai", fetchedAt, "https://api.openai.com/v1/models",
                new LinkedHashSet<String>(Arrays.asList("gpt-5", "gpt-5-mini")));

        Instant later = Instant.parse("2026-05-02T09:00:00Z");
        assertFalse("snapshot is past TTL", cache.isFresh("openai", later));
        ModelsCache.Snapshot snap = cache.read("openai");
        assertNotNull("stale snapshot must still be readable so the merge "
                + "layer can fall back when a refresh fails", snap);
        assertEquals(2, snap.modelIds().size());
    }

    @Test
    public void atomicWrite_overwritesPriorSnapshot() throws IOException {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        cache.write("openai", Instant.parse("2026-04-01T00:00:00Z"),
                "endpoint", new LinkedHashSet<String>(Arrays.asList("gpt-5")));
        cache.write("openai", Instant.parse("2026-05-02T00:00:00Z"),
                "endpoint", new LinkedHashSet<String>(Arrays.asList("gpt-5", "gpt-5-mini")));
        ModelsCache.Snapshot snap = cache.read("openai");
        assertEquals(2, snap.modelIds().size());
        // Tmp file must be cleaned up.
        long tmpCount = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        assertEquals("tmp file must be moved into place atomically", 0, tmpCount);
    }
}
