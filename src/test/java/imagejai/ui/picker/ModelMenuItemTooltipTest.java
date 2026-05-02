package imagejai.ui.picker;

import org.junit.Test;
import imagejai.engine.picker.ModelEntry;

import java.time.LocalDate;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase H acceptance — docs/multi_provider/06_tier_safety.md §1.2.
 *
 * <p>Each tier badge must carry the canonical lay-language tooltip.
 */
public class ModelMenuItemTooltipTest {

    @Test
    public void freeBadge_tooltipMentionsNoCard() {
        String text = ModelMenuItem.tierBadgeTooltip(ModelEntry.Tier.FREE);
        assertTrue(text, text.contains("Free to use"));
        assertTrue(text, text.contains("No card"));
    }

    @Test
    public void freeWithLimitsBadge_tooltipMentionsRateLimits() {
        String text = ModelMenuItem.tierBadgeTooltip(ModelEntry.Tier.FREE_WITH_LIMITS);
        assertTrue(text, text.contains("Free, but rate-limited"));
        assertTrue(text, text.contains("per-day cap"));
    }

    @Test
    public void paidBadge_tooltipMentionsCharges() {
        String text = ModelMenuItem.tierBadgeTooltip(ModelEntry.Tier.PAID);
        assertTrue(text, text.contains("You pay per use"));
        assertTrue(text, text.contains("credit or a card"));
    }

    @Test
    public void subscriptionBadge_tooltipMentionsActiveSubscription() {
        String text = ModelMenuItem.tierBadgeTooltip(ModelEntry.Tier.REQUIRES_SUBSCRIPTION);
        assertTrue(text, text.contains("active monthly subscription"));
        assertTrue(text, text.contains("no surprise charges"));
    }

    @Test
    public void uncuratedBadge_tooltipMentionsAutoDetected() {
        String text = ModelMenuItem.tierBadgeTooltip(ModelEntry.Tier.UNCURATED);
        assertTrue(text, text.contains("Auto-detected"));
        assertTrue(text, text.contains("not yet verified"));
    }

    @Test
    public void composeTooltip_combinesDeprecationAndTier() {
        ModelEntry deprecated = new ModelEntry("anthropic", "claude-haiku-3-5",
                "Claude Haiku 3.5", "older Haiku",
                ModelEntry.Tier.PAID, 200_000, false,
                ModelEntry.Reliability.MEDIUM, false, true, "",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 22),
                "claude-haiku-4-5");
        String composed = ModelMenuItem.composeTooltip(deprecated, LocalDate.of(2026, 5, 2));
        assertNotNull(composed);
        assertTrue(composed, composed.contains("No longer available"));
        assertTrue(composed, composed.contains("You pay per use"));
    }

    @Test
    public void composeTooltip_activeRowReturnsOnlyBadgeText() {
        ModelEntry active = new ModelEntry("ollama", "llama3.2:3b",
                "Llama 3.2 3B", "local", ModelEntry.Tier.FREE, 128_000, false,
                ModelEntry.Reliability.MEDIUM, false, true, "");
        String composed = ModelMenuItem.composeTooltip(active, LocalDate.now());
        assertNotNull(composed);
        assertTrue(composed.contains("Free to use"));
    }
}
