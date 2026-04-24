package imagejai.engine;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for Step 12 pattern detection
 * (docs/tcp_upgrade/12_per_agent_telemetry.md). Exercises every rule with an
 * injected clock so throttling, triggering, and non-triggering paths are
 * deterministic — no reliance on {@code System.currentTimeMillis}.
 */
public class PatternDetectorTest {

    private static void record(SessionStats stats, String cmd, long ts) {
        stats.record(cmd, "", ts, null);
    }

    private static void recordFail(SessionStats stats, String cmd, String args, long ts, String code) {
        stats.record(cmd, args, ts, code);
    }

    // -----------------------------------------------------------------------
    // close_dialogs_thrashing
    // -----------------------------------------------------------------------

    @Test
    public void closeDialogsFiresOnFourthCallInWindow() {
        SessionStats stats = new SessionStats();
        record(stats, "close_dialogs", 1_000L);
        record(stats, "close_dialogs", 2_000L);
        record(stats, "close_dialogs", 3_000L);

        // After 3 calls: no fire.
        List<PatternDetector.Hint> before = PatternDetector.check(stats, 3_500L);
        assertTrue("3 calls must not fire", before.isEmpty());

        record(stats, "close_dialogs", 4_000L);
        List<PatternDetector.Hint> after = PatternDetector.check(stats, 4_500L);
        assertEquals("4th call fires", 1, after.size());
        assertEquals(PatternDetector.KIND_CLOSE_DIALOGS_THRASHING, after.get(0).kind);
        assertEquals("PATTERN_DETECTED", after.get(0).code);
    }

    @Test
    public void closeDialogsDoesNotRefireInsideThrottle() {
        SessionStats stats = new SessionStats();
        for (int i = 0; i < 4; i++) record(stats, "close_dialogs", 1_000L + i);
        assertEquals(1, PatternDetector.check(stats, 5_000L).size());

        // 5th call 6 minutes later — throttle window is 5 min, but the 30s
        // history window has expired, so even without throttle we would not
        // fire. Explicitly inject a 5th call still inside the 30s window but
        // 10s after the first fire: throttle must silence it.
        record(stats, "close_dialogs", 15_000L);
        List<PatternDetector.Hint> repeat = PatternDetector.check(stats, 16_000L);
        assertTrue("throttle suppresses re-fire", repeat.isEmpty());
    }

    @Test
    public void closeDialogsRefiresAfterThrottleExpires() {
        SessionStats stats = new SessionStats();
        for (int i = 0; i < 4; i++) record(stats, "close_dialogs", 1_000L + i);
        assertEquals(1, PatternDetector.check(stats, 5_000L).size());

        // Jump 35 min forward. Populate a fresh 4-call burst inside a new 30s
        // window so the rule has something to fire on.
        long base = 35L * 60_000L;
        for (int i = 0; i < 4; i++) record(stats, "close_dialogs", base + i * 1000L);
        List<PatternDetector.Hint> fresh = PatternDetector.check(stats, base + 5_000L);
        assertEquals("throttle window expired → rule can fire again", 1, fresh.size());
    }

    @Test
    public void closeDialogsIntermediateCommandDoesNotBreakWindow() {
        // The rule uses a time-window count, not a consecutive-run check, so
        // an unrelated command between close_dialogs calls still trips it if
        // four close_dialogs land inside 30s.
        SessionStats stats = new SessionStats();
        record(stats, "close_dialogs", 1_000L);
        record(stats, "close_dialogs", 2_000L);
        record(stats, "get_log",       2_500L);
        record(stats, "close_dialogs", 3_000L);
        record(stats, "close_dialogs", 4_000L);
        List<PatternDetector.Hint> hints = PatternDetector.check(stats, 4_500L);
        assertEquals(1, hints.size());
    }

    @Test
    public void closeDialogsDoesNotFireOutsideWindow() {
        SessionStats stats = new SessionStats();
        // 4 calls across 40 seconds — only three land inside the 30s window.
        record(stats, "close_dialogs",  0L);
        record(stats, "close_dialogs", 15_000L);
        record(stats, "close_dialogs", 25_000L);
        record(stats, "close_dialogs", 35_000L);
        assertTrue("first call aged out of 30s window → only 3 inside",
                PatternDetector.check(stats, 35_500L).isEmpty());
    }

    // -----------------------------------------------------------------------
    // get_state_polling
    // -----------------------------------------------------------------------

    @Test
    public void getStatePollingFiresAfterFiveInWindow() {
        SessionStats stats = new SessionStats();
        for (int i = 0; i < 5; i++) record(stats, "get_state", i * 1000L);
        List<PatternDetector.Hint> hints = PatternDetector.check(stats, 5_500L);
        assertEquals(1, hints.size());
        assertEquals(PatternDetector.KIND_GET_STATE_POLLING, hints.get(0).kind);
    }

    @Test
    public void getStatePollingResetsAfterMutation() {
        SessionStats stats = new SessionStats();
        for (int i = 0; i < 3; i++) record(stats, "get_state", i * 1000L);
        // A mutating command clears the polling streak.
        record(stats, "execute_macro", 3_500L);
        for (int i = 0; i < 3; i++) record(stats, "get_state", 4_000L + i * 1000L);
        List<PatternDetector.Hint> hints = PatternDetector.check(stats, 7_000L);
        assertTrue("mutating command between polls → no fire", hints.isEmpty());
    }

    // -----------------------------------------------------------------------
    // repeat_error_identical_macro
    // -----------------------------------------------------------------------

    @Test
    public void repeatMacroFiresOnThreeIdenticalFailures() {
        SessionStats stats = new SessionStats();
        String args = "{\"code\":\"run(\\\"Foo...\\\", \\\"bar\\\");\"}";
        for (int i = 0; i < 3; i++) {
            recordFail(stats, "execute_macro", args, i * 100L, "PLUGIN_NOT_FOUND");
        }
        List<PatternDetector.Hint> hints = PatternDetector.check(stats, 500L);
        assertEquals(1, hints.size());
        assertEquals(PatternDetector.KIND_REPEAT_ERROR_IDENTICAL_MACRO, hints.get(0).kind);
        assertEquals("warning", hints.get(0).severity);
    }

    @Test
    public void repeatMacroDoesNotFireOnDifferentErrors() {
        SessionStats stats = new SessionStats();
        String args = "{\"code\":\"run(\\\"Foo...\\\", \\\"bar\\\");\"}";
        recordFail(stats, "execute_macro", args, 0L, "PLUGIN_NOT_FOUND");
        recordFail(stats, "execute_macro", args, 100L, "MACRO_RUNTIME_ERROR");
        recordFail(stats, "execute_macro", args, 200L, "PLUGIN_NOT_FOUND");
        List<PatternDetector.Hint> hints = PatternDetector.check(stats, 300L);
        assertTrue("error codes diverge → no fire", hints.isEmpty());
    }

    @Test
    public void repeatMacroDoesNotFireOnSuccess() {
        SessionStats stats = new SessionStats();
        String args = "{\"code\":\"print(\\\"hi\\\");\"}";
        for (int i = 0; i < 3; i++) {
            recordFail(stats, "execute_macro", args, i * 100L, null);
        }
        assertTrue(PatternDetector.check(stats, 500L).isEmpty());
    }

    // -----------------------------------------------------------------------
    // probe_before_run_missed
    // -----------------------------------------------------------------------

    @Test
    public void probeMissedFiresOnTwoUnprobedFailures() {
        SessionStats stats = new SessionStats();
        // Pad the session past the grace period (4 commands minimum).
        record(stats, "get_state", 0L);
        record(stats, "get_state", 100L);
        recordFail(stats, "execute_macro",
                "{\"code\":\"run(\\\"MyPlugin...\\\", \\\"a=1\\\");\"}",
                200L, "PLUGIN_NOT_FOUND");
        recordFail(stats, "execute_macro",
                "{\"code\":\"run(\\\"MyPlugin...\\\", \\\"a=2\\\");\"}",
                300L, "PLUGIN_NOT_FOUND");
        List<PatternDetector.Hint> hints = PatternDetector.check(stats, 400L);
        assertEquals(1, hints.size());
        assertEquals(PatternDetector.KIND_PROBE_BEFORE_RUN_MISSED, hints.get(0).kind);
    }

    @Test
    public void probeMissedSuppressedAfterProbe() {
        SessionStats stats = new SessionStats();
        stats.noteProbed("MyPlugin");
        record(stats, "get_state", 0L);
        record(stats, "get_state", 100L);
        recordFail(stats, "execute_macro",
                "{\"code\":\"run(\\\"MyPlugin...\\\", \\\"a=1\\\");\"}",
                200L, "PLUGIN_NOT_FOUND");
        recordFail(stats, "execute_macro",
                "{\"code\":\"run(\\\"MyPlugin...\\\", \\\"a=2\\\");\"}",
                300L, "PLUGIN_NOT_FOUND");
        assertTrue("probe_command recorded → hint suppressed",
                PatternDetector.check(stats, 400L).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Pure SessionStats bookkeeping
    // -----------------------------------------------------------------------

    @Test
    public void sessionStatsCapsHistoryAtFifty() {
        SessionStats stats = new SessionStats();
        for (int i = 0; i < 75; i++) {
            record(stats, "ping", i);
        }
        assertEquals(SessionStats.MAX_HISTORY, stats.size());
    }

    @Test
    public void sessionStatsThrottleHonoursWindow() {
        SessionStats stats = new SessionStats();
        assertTrue(stats.canFire("rule_a", 0L, 1_000L));
        stats.markFired("rule_a", 0L);
        assertFalse("inside throttle",   stats.canFire("rule_a",   500L, 1_000L));
        assertTrue ("at the throttle edge", stats.canFire("rule_a", 1_000L, 1_000L));
    }

    @Test
    public void extractPluginNameStripsEllipsis() {
        assertEquals("MyPlugin", PatternDetector.extractPluginName(
                "{\"code\":\"run(\\\"MyPlugin...\\\", \\\"\\\");\"}"));
    }

    @Test
    public void extractPluginNameReturnsNullWhenNoRun() {
        assertEquals(null, PatternDetector.extractPluginName("{\"code\":\"print(\\\"hi\\\");\"}"));
    }

    @Test
    public void isMutatingRecognisesReadOnlyCommands() {
        assertFalse(PatternDetector.isMutating("get_state"));
        assertFalse(PatternDetector.isMutating("get_log"));
        assertFalse(PatternDetector.isMutating("probe_command"));
        assertTrue(PatternDetector.isMutating("execute_macro"));
        assertTrue(PatternDetector.isMutating("run_script"));
        assertTrue(PatternDetector.isMutating("run_pipeline"));
    }
}
