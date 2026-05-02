package imagejai.ui.picker;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.usage.UsageTracker;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase H acceptance — docs/multi_provider/06_tier_safety.md §7.
 */
public class MainNotificationCheckTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static ModelEntry deepseekChat(double inUsd, double outUsd) {
        Map<String, Object> features = new HashMap<>();
        Map<String, Object> pricing = new HashMap<>();
        pricing.put("input_usd_per_mtok", inUsd);
        pricing.put("output_usd_per_mtok", outUsd);
        features.put("pricing", pricing);
        return new ModelEntry("deepseek", "deepseek-chat", "DeepSeek V3.1 Chat",
                "", ModelEntry.Tier.PAID, 128_000, false,
                ModelEntry.Reliability.MEDIUM, false, true, "",
                LocalDate.of(2026, 5, 1), null, null, features);
    }

    private UsageTracker favouriteFor(Path path, String providerId, String modelId,
                                      String lastSeenTier,
                                      Double inUsd, Double outUsd) {
        UsageTracker tracker = UsageTracker.load(path);
        LocalDate today = LocalDate.of(2026, 5, 2);
        tracker.recordLaunch(providerId, modelId, today.minusDays(2));
        tracker.recordLaunch(providerId, modelId, today.minusDays(1));
        tracker.recordLaunch(providerId, modelId, today);
        tracker.recordSeenTier(providerId, modelId, lastSeenTier, inUsd, outUsd);
        return tracker;
    }

    @Test
    public void priceIncreaseAboveThreshold_emitsNotification() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = favouriteFor(path, "deepseek", "deepseek-chat",
                "paid", 0.435, 0.87);
        ModelEntry current = deepseekChat(1.74, 3.48);  // ~4× more
        MainNotificationCheck.RegistryView view =
                (p, m) -> "deepseek".equals(p) && "deepseek-chat".equals(m) ? current : null;
        List<MainNotificationCheck.Notification> out =
                MainNotificationCheck.runAgainst(tracker, view, Collections.emptyList(),
                        LocalDate.of(2026, 6, 1));
        assertEquals(1, out.size());
        assertEquals(MainNotificationCheck.Severity.HIGH, out.get(0).severity);
        assertTrue(out.get(0).title.contains("price changed"));
    }

    @Test
    public void priceUnchanged_emitsNothing() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = favouriteFor(path, "deepseek", "deepseek-chat",
                "paid", 0.435, 0.87);
        ModelEntry current = deepseekChat(0.435, 0.87);
        MainNotificationCheck.RegistryView view =
                (p, m) -> "deepseek".equals(p) && "deepseek-chat".equals(m) ? current : null;
        List<MainNotificationCheck.Notification> out =
                MainNotificationCheck.runAgainst(tracker, view, Collections.emptyList(),
                        LocalDate.of(2026, 5, 2));
        assertTrue(out.isEmpty());
    }

    @Test
    public void tierChange_emitsHighSeverityNotification() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = favouriteFor(path, "groq", "compound-mini",
                "free", null, null);
        ModelEntry current = new ModelEntry("groq", "compound-mini", "Compound Mini",
                "", ModelEntry.Tier.FREE_WITH_LIMITS, 128_000, false,
                ModelEntry.Reliability.MEDIUM, false, true, "");
        MainNotificationCheck.RegistryView view =
                (p, m) -> "groq".equals(p) && "compound-mini".equals(m) ? current : null;
        List<MainNotificationCheck.Notification> out =
                MainNotificationCheck.runAgainst(tracker, view, Collections.emptyList(),
                        LocalDate.of(2026, 5, 2));
        assertEquals(1, out.size());
        assertEquals(MainNotificationCheck.Severity.HIGH, out.get(0).severity);
        assertTrue(out.get(0).title.contains("tier changed"));
    }

    @Test
    public void preWarning_firesInsideT7Window() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = favouriteFor(path, "deepseek", "deepseek-chat",
                "paid", 0.435, 0.87);
        ModelEntry current = deepseekChat(0.435, 0.87);
        MainNotificationCheck.RegistryView view =
                (p, m) -> "deepseek".equals(p) && "deepseek-chat".equals(m) ? current : null;
        MainNotificationCheck.ScheduledChange change = new MainNotificationCheck.ScheduledChange(
                "deepseek", "deepseek-chat", LocalDate.of(2026, 6, 1),
                1.74, 3.48, "Promotional discount expires");
        // 5 days out — inside T-7 window.
        List<MainNotificationCheck.Notification> out =
                MainNotificationCheck.runAgainst(tracker, view, Arrays.asList(change),
                        LocalDate.of(2026, 5, 27));
        assertEquals(1, out.size());
        assertTrue(out.get(0).body.contains("2026-06-01"));
    }

    @Test
    public void preWarning_doesNotFireBeforeWindow() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = favouriteFor(path, "deepseek", "deepseek-chat",
                "paid", 0.435, 0.87);
        ModelEntry current = deepseekChat(0.435, 0.87);
        MainNotificationCheck.RegistryView view =
                (p, m) -> "deepseek".equals(p) && "deepseek-chat".equals(m) ? current : null;
        MainNotificationCheck.ScheduledChange change = new MainNotificationCheck.ScheduledChange(
                "deepseek", "deepseek-chat", LocalDate.of(2026, 6, 1),
                1.74, 3.48, "");
        // 14 days out — outside T-7.
        List<MainNotificationCheck.Notification> out =
                MainNotificationCheck.runAgainst(tracker, view, Arrays.asList(change),
                        LocalDate.of(2026, 5, 18));
        assertTrue(out.isEmpty());
    }

    @Test
    public void preWarning_skipsNonFavourites() {
        Path path = tmp.getRoot().toPath().resolve("usage.json");
        UsageTracker tracker = UsageTracker.load(path);  // empty
        MainNotificationCheck.RegistryView view = (p, m) -> null;
        MainNotificationCheck.ScheduledChange change = new MainNotificationCheck.ScheduledChange(
                "deepseek", "deepseek-chat", LocalDate.of(2026, 6, 1),
                1.74, 3.48, "");
        List<MainNotificationCheck.Notification> out =
                MainNotificationCheck.runAgainst(tracker, view, Arrays.asList(change),
                        LocalDate.of(2026, 5, 27));
        assertTrue(out.isEmpty());
    }

    @Test
    public void filterDismissed_removesByKey() {
        MainNotificationCheck.Notification a = new MainNotificationCheck.Notification(
                "deepseek/deepseek-chat", MainNotificationCheck.Severity.HIGH,
                "title", "body");
        MainNotificationCheck.Notification b = new MainNotificationCheck.Notification(
                "openai/gpt-5", MainNotificationCheck.Severity.LOW, "title", "body");
        List<MainNotificationCheck.Notification> filtered =
                MainNotificationCheck.filterDismissed(Arrays.asList(a, b),
                        Collections.singleton("deepseek/deepseek-chat"));
        assertEquals(1, filtered.size());
        assertEquals("openai/gpt-5", filtered.get(0).key);
    }
}
