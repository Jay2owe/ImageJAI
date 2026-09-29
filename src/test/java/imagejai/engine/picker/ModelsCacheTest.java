package imagejai.engine.picker;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    public void unusableModelIdIsSkippedInsteadOfLosingTheWholeProvider()
            throws IOException {
        // OpenRouter publishes "~vendor/model-latest" alias ids. They cannot be
        // launched, but they must not abort the cache write for the other 400+
        // usable models, and they must not leave a temp file behind.
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        Set<String> ids = new LinkedHashSet<String>(Arrays.asList(
                "qwen/qwen3-coder", "~openai/gpt-mini-latest", "z-ai/glm-4.6"));

        cache.write("openrouter", Instant.parse("2026-05-02T09:14:33Z"),
                "https://openrouter.ai/api/v1/models", ids);

        ModelsCache.Snapshot snap = cache.read("openrouter");
        assertNotNull(snap);
        assertEquals(Arrays.asList("qwen/qwen3-coder", "z-ai/glm-4.6"), snap.modelIds());
        assertEquals(1, cache.lastRejectedModelCount());
        assertEquals("no temp file may survive a write", 0, tmpFileCount(dir));
    }

    @Test
    public void failedWriteLeavesNoTempFileBehind() throws IOException {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        Set<String> tooMany = new LinkedHashSet<String>();
        for (int i = 0; i <= ModelsCache.MAX_MODELS; i++) {
            tooMany.add("model-" + i);
        }

        try {
            cache.write("openrouter", Instant.now(),
                    "https://openrouter.ai/api/v1/models", tooMany);
            org.junit.Assert.fail("expected the safety cap to reject this write");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("safety cap"));
        }

        assertEquals("a rejected write must not litter the cache", 0, tmpFileCount(dir));
    }

    @Test
    public void writeSweepsTempFilesAbandonedByAnEarlierCrash() throws Exception {
        Path dir = Files.createTempDirectory("mc-test");
        Path orphan = dir.resolve("openrouter-123456789.tmp");
        Files.write(orphan, new byte[0]);
        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - (2L * 60L * 60L * 1000L)));
        Path recent = dir.resolve("ollama-987654321.tmp");
        Files.write(recent, new byte[0]);

        ModelsCache cache = new ModelsCache(dir);
        cache.write("anthropic", Instant.now(), "https://api.anthropic.com/v1/models",
                new LinkedHashSet<String>(Arrays.asList("claude-opus-4-7")));

        assertFalse("stale temp file must be swept", Files.exists(orphan));
        assertTrue("a temp file from a live write must be kept", Files.exists(recent));
    }

    private static long tmpFileCount(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    @Test
    public void writeStripsCredentialsAndQueryFromEndpointMetadata() throws IOException {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache cache = new ModelsCache(dir);
        cache.write("gemini", Instant.parse("2026-05-02T00:00:00Z"),
                "https://user:secret@example.invalid/models?key=top-secret",
                new LinkedHashSet<String>(Arrays.asList("gemini-2.5-pro")));

        String json = Files.readString(cache.pathFor("gemini"));
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("?key="));
        assertTrue(json.contains("https://example.invalid/models"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void providerIdCannotEscapeCacheDirectory() {
        new ModelsCache(java.nio.file.Paths.get("cache")).pathFor("../secrets");
    }

    @Test
    public void unreadableCacheIsNotMissingAndSucceedsAfterAccessIsRestored() throws Exception {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache writer = new ModelsCache(dir);
        writer.write("openai", Instant.parse("2026-05-02T00:00:00Z"), "endpoint",
                new LinkedHashSet<String>(Arrays.asList("gpt-5")));
        AtomicInteger opens = new AtomicInteger();
        ModelsCache cache = new ModelsCache(dir, path -> {
            if (opens.getAndIncrement() == 0) {
                throw new AccessDeniedException(path.toString());
            }
            return Files.newInputStream(path);
        });

        try {
            cache.read("openai");
            throw new AssertionError("Expected unreadable cache error");
        } catch (ModelsCache.CacheReadException expected) {
            assertEquals("unreadable", expected.code());
        }
        assertEquals("gpt-5", cache.read("openai").modelIds().get(0));
    }

    @Test
    public void deniedPathProbeMakesHasAndReadExplicitThenRecovers() throws Exception {
        Path dir = Files.createTempDirectory("mc-test");
        ModelsCache writer = new ModelsCache(dir);
        writer.write("openai", Instant.parse("2026-05-02T00:00:00Z"), "endpoint",
                new LinkedHashSet<String>(Arrays.asList("gpt-5")));
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        ModelsCache cache = new ModelsCache(dir, path -> {
            opens.incrementAndGet();
            return Files.newInputStream(path);
        }, path -> {
            if (probes.getAndIncrement() == 0) {
                throw new AccessDeniedException(path.toString());
            }
            return Files.readAttributes(path,
                    java.nio.file.attribute.BasicFileAttributes.class);
        });

        try {
            cache.has("openai");
            throw new AssertionError("Expected unreadable cache error");
        } catch (ModelsCache.CacheReadException expected) {
            assertEquals("unreadable", expected.code());
        }
        assertEquals(0, opens.get());
        assertTrue(cache.has("openai"));
        assertEquals("gpt-5", cache.read("openai").modelIds().get(0));
        assertEquals(1, opens.get());
    }

    @Test
    public void cacheReadStopsAtByteCapBeforeParsing() throws Exception {
        Path dir = Files.createTempDirectory("mc-test");
        Path slot = dir.resolve("openai.json");
        Files.write(slot, "{}".getBytes(StandardCharsets.UTF_8));
        byte[] oversized = new byte[ModelsCache.MAX_CACHE_BYTES + 1];
        ModelsCache cache = new ModelsCache(dir,
                path -> new ByteArrayInputStream(oversized));

        try {
            cache.read("openai");
            throw new AssertionError("Expected too_large cache error");
        } catch (ModelsCache.CacheReadException expected) {
            assertEquals("too_large", expected.code());
        }
    }

    @Test
    public void malformedCacheIsDistinctFromMissing() throws Exception {
        Path dir = Files.createTempDirectory("mc-test");
        Files.write(dir.resolve("openai.json"), "not-json".getBytes(StandardCharsets.UTF_8));
        try {
            new ModelsCache(dir).read("openai");
            throw new AssertionError("Expected malformed cache error");
        } catch (ModelsCache.CacheReadException expected) {
            assertEquals("malformed", expected.code());
        }
    }
}
