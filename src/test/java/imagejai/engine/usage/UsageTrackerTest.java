package imagejai.engine.usage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase H acceptance — docs/multi_provider/06_tier_safety.md §7.2.
 */
public class UsageTrackerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void freshLoad_returnsEmpty() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = UsageTracker.load(path);
        assertNotNull(tracker);
        assertTrue(tracker.favourites().isEmpty());
    }

    @Test
    public void favouriteThreshold_requires3UsesAndAtLeast2DistinctDays() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = UsageTracker.load(path);
        LocalDate today = LocalDate.of(2026, 5, 2);
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today);
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today);
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today);
        // 3 uses but only 1 day — not yet a favourite.
        assertFalse(tracker.isFavourite("anthropic", "claude-sonnet-4-6"));
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today.plusDays(1));
        assertTrue(tracker.isFavourite("anthropic", "claude-sonnet-4-6"));
    }

    @Test
    public void recordSeenTier_updatesPricing() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = UsageTracker.load(path);
        tracker.recordSeenTier("anthropic", "claude-sonnet-4-6", "paid", 3.0, 15.0);
        UsageTracker.Record record = tracker.get("anthropic", "claude-sonnet-4-6");
        assertNotNull(record);
        assertEquals("paid", record.lastSeenTier);
        assertEquals(3.0, record.lastSeenInputUsdPerMtok, 1e-9);
        assertEquals(15.0, record.lastSeenOutputUsdPerMtok, 1e-9);
    }

    @Test
    public void persistAndReload_preservesData() throws IOException {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = UsageTracker.load(path);
        LocalDate today = LocalDate.of(2026, 5, 2);
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today);
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today.plusDays(1));
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today.plusDays(2));
        tracker.recordSeenTier("anthropic", "claude-sonnet-4-6", "paid", 3.0, 15.0);
        tracker.save();

        UsageTracker reloaded = UsageTracker.load(path);
        UsageTracker.Record record = reloaded.get("anthropic", "claude-sonnet-4-6");
        assertNotNull(record);
        assertEquals(3, record.useCount);
        assertEquals(3, record.distinctDays.size());
        assertEquals("paid", record.lastSeenTier);
        assertTrue(reloaded.isFavourite("anthropic", "claude-sonnet-4-6"));
    }

    @Test
    public void favouritesList_onlyIncludesQualifying() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = UsageTracker.load(path);
        LocalDate today = LocalDate.of(2026, 5, 2);
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today);
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today.plusDays(1));
        tracker.recordLaunch("anthropic", "claude-sonnet-4-6", today.plusDays(2));
        tracker.recordLaunch("openai", "gpt-5-mini", today);

        List<String> favourites = tracker.favourites();
        assertEquals(1, favourites.size());
        assertEquals("anthropic/claude-sonnet-4-6", favourites.get(0));
    }

    @Test
    public void splitKey_handlesProviderSlashModel() {
        String[] parts = UsageTracker.split("anthropic/claude-sonnet-4-6");
        assertEquals("anthropic", parts[0]);
        assertEquals("claude-sonnet-4-6", parts[1]);
    }

    @Test
    public void nullInputs_areIgnored() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = UsageTracker.load(path);
        tracker.recordLaunch(null, "x", LocalDate.now());
        tracker.recordLaunch("x", null, LocalDate.now());
        tracker.recordLaunch("x", "y", null);
        assertNull(tracker.get(null, "x"));
        assertTrue(tracker.snapshot().isEmpty());
    }
}
