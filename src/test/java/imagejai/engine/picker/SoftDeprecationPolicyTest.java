package imagejai.engine.picker;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase G acceptance — docs/multi_provider/02_curation_strategy.md §3.
 *
 * <p>Verifies the four lifecycle states (active, soft-deprecated,
 * pinned-deprecated, hidden) and the 30-day window transition.
 */
public class SoftDeprecationPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 2);

    private static ModelEntry seed(boolean pinned, LocalDate deprecatedSince) {
        return new ModelEntry("anthropic", "claude-haiku-3-5",
                "Claude Haiku 3.5", "older Haiku",
                ModelEntry.Tier.PAID, 200_000, false,
                ModelEntry.Reliability.MEDIUM, pinned, true, "",
                LocalDate.of(2026, 4, 1), deprecatedSince, "claude-haiku-4-5");
    }

    @Test
    public void active_whenDeprecatedSinceNull() {
        ModelEntry e = seed(false, null);
        assertEquals(SoftDeprecationPolicy.State.ACTIVE,
                SoftDeprecationPolicy.stateOf(e, TODAY));
        assertFalse(SoftDeprecationPolicy.isHidden(e, TODAY));
    }

    @Test
    public void softDeprecated_withinWindow() {
        ModelEntry e = seed(false, TODAY.minusDays(10));
        assertEquals(SoftDeprecationPolicy.State.SOFT_DEPRECATED,
                SoftDeprecationPolicy.stateOf(e, TODAY));
        assertFalse(SoftDeprecationPolicy.isHidden(e, TODAY));
    }

    @Test
    public void pinnedDeprecated_pastWindow() {
        ModelEntry e = seed(true, TODAY.minusDays(60));
        assertEquals(SoftDeprecationPolicy.State.PINNED_DEPRECATED,
                SoftDeprecationPolicy.stateOf(e, TODAY));
        assertFalse("pinned-deprecated stays visible past 30 days",
                SoftDeprecationPolicy.isHidden(e, TODAY));
    }

    @Test
    public void hidden_pastWindow_unpinned() {
        ModelEntry e = seed(false, TODAY.minusDays(60));
        assertEquals(SoftDeprecationPolicy.State.HIDDEN,
                SoftDeprecationPolicy.stateOf(e, TODAY));
        assertTrue(SoftDeprecationPolicy.isHidden(e, TODAY));
    }

    @Test
    public void exactly30Days_stillSoftDeprecated() {
        ModelEntry e = seed(false, TODAY.minusDays(SoftDeprecationPolicy.WINDOW_DAYS));
        assertEquals(SoftDeprecationPolicy.State.SOFT_DEPRECATED,
                SoftDeprecationPolicy.stateOf(e, TODAY));
    }

    @Test
    public void markIfMissing_isIdempotent() {
        ModelEntry fresh = seed(false, null);
        ModelEntry firstMark = SoftDeprecationPolicy.markIfMissing(fresh, TODAY);
        assertEquals(TODAY, firstMark.deprecatedSince());

        ModelEntry secondMark = SoftDeprecationPolicy.markIfMissing(firstMark, TODAY.plusDays(5));
        assertEquals("must not bump deprecatedSince once set",
                TODAY, secondMark.deprecatedSince());
    }

    @Test
    public void clearAndRefresh_dropsDeprecation() {
        ModelEntry e = seed(false, TODAY.minusDays(10));
        ModelEntry cleared = SoftDeprecationPolicy.clearAndRefresh(e, TODAY);
        assertNull(cleared.deprecatedSince());
        assertEquals(TODAY, cleared.lastVerified());
    }
}
