package imagejai.engine.budget;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase H acceptance — docs/multi_provider/06_tier_safety.md §6.
 */
public class BudgetCeilingTrackerTest {

    @Test
    public void disabledTracker_neverFires() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(false, 0.10);
        AtomicInteger fires = new AtomicInteger();
        tracker.addListener((total, ceiling) -> fires.incrementAndGet());
        boolean fired = tracker.recordCost(0.50, false);
        assertFalse(fired);
        assertEquals(0, fires.get());
        assertFalse(tracker.isBreached());
        // Cost still accumulates so toggling enable mid-session is sensible.
        assertEquals(0.50, tracker.sessionCostUsd(), 1e-9);
    }

    @Test
    public void enabledTracker_firesOnceAtCeiling() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.10);
        AtomicInteger fires = new AtomicInteger();
        tracker.addListener((total, ceiling) -> fires.incrementAndGet());
        boolean firstShouldFire = tracker.recordCost(0.05, false);
        assertFalse(firstShouldFire);
        boolean secondShouldFire = tracker.recordCost(0.05, false);
        assertTrue(secondShouldFire);
        // Subsequent calls beyond the ceiling do not re-fire (one-shot).
        boolean thirdShouldFire = tracker.recordCost(0.10, false);
        assertFalse(thirdShouldFire);
        assertEquals(1, fires.get());
        assertTrue(tracker.isBreached());
    }

    @Test
    public void freeTierCalls_doNotCount() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.10);
        // Even a $5 free-tier call (an absurd token count on a free provider)
        // must not contribute. Callers signal the tier via the boolean.
        boolean fired = tracker.recordCost(5.00, true);
        assertFalse(fired);
        assertEquals(0.0, tracker.sessionCostUsd(), 1e-9);
        assertFalse(tracker.isBreached());
    }

    @Test
    public void freeTierOnlySession_neverBreaches() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.01);
        for (int i = 0; i < 100; i++) {
            tracker.recordCost(1.00, true);
        }
        assertEquals(0.0, tracker.sessionCostUsd(), 1e-9);
        assertFalse(tracker.isBreached());
    }

    @Test
    public void resumeRaisesCeiling_clearsBreachStateBelowNewCeiling() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.10);
        tracker.recordCost(0.20, false);
        assertTrue(tracker.isBreached());
        // /resume to $0.50 — should clear breach because $0.20 < $0.50.
        tracker.setCeilingUsd(0.50);
        assertFalse(tracker.isBreached());
        // Adding cost up to the new ceiling re-fires.
        boolean fired = tracker.recordCost(0.40, false);
        assertTrue(fired);
    }

    @Test
    public void costHeader_parsedFromLiteLLMString() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.10);
        tracker.onCostHeader("0.045");
        tracker.onCostHeader("0.06");
        assertTrue(tracker.isBreached());
    }

    @Test
    public void costHeader_malformedDropsSilently() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.10);
        tracker.onCostHeader("not-a-number");
        tracker.onCostHeader(null);
        tracker.onCostHeader("");
        assertEquals(0.0, tracker.sessionCostUsd(), 1e-9);
        assertFalse(tracker.isBreached());
    }

    @Test
    public void resetSession_clearsState() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.10);
        tracker.recordCost(0.20, false);
        tracker.resetSession();
        assertEquals(0.0, tracker.sessionCostUsd(), 1e-9);
        assertFalse(tracker.isBreached());
    }

    @Test
    public void breachListener_seesTotalAndCeiling() {
        BudgetCeilingTracker tracker = new BudgetCeilingTracker(true, 0.10);
        AtomicReference<double[]> capture = new AtomicReference<>();
        tracker.addListener((total, ceiling) -> capture.set(new double[]{total, ceiling}));
        tracker.recordCost(0.15, false);
        double[] observed = capture.get();
        assertEquals(0.15, observed[0], 1e-9);
        assertEquals(0.10, observed[1], 1e-9);
    }
}
