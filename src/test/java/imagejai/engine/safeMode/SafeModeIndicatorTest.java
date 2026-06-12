package imagejai.engine.safeMode;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import imagejai.engine.EventBus;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): exercise the
 * indicator's pure logic — colour state machine, EventBus subscription,
 * decay timer, and master-switch behaviour — without touching Swing.
 *
 * <p>Two layers under test:
 * <ul>
 *   <li>{@link SafeModeIndicatorState} — pure data, no AWT. Covers colour
 *       transitions, recent-event ring, decay timing, master toggle.</li>
 *   <li>{@link SafeModeIndicator} — EventBus → state wiring. We invoke the
 *       singleton's {@link SafeModeIndicator#onEvent} directly with frames
 *       shaped like the ones {@link EventBus#publish} would dispatch and
 *       assert the underlying state advanced. We do NOT call
 *       {@link SafeModeIndicator#installOnStartup} — that path mounts a
 *       toolbar tool, which a headless test cannot do safely.</li>
 * </ul>
 */
public class SafeModeIndicatorTest {

    private SafeModeIndicatorState state;

    @Before
    public void setUp() {
        state = new SafeModeIndicatorState();
        // The singleton indicator is shared across tests in the JVM; reset
        // its state up-front so prior tests can't leak colour into ours.
        SafeModeIndicator.getInstance().resetForTest();
    }

    @After
    public void tearDown() {
        SafeModeIndicator.getInstance().resetForTest();
    }

    @Test
    public void initialColourIsGreen() {
        assertEquals(SafeModeIndicatorState.Colour.GREEN, state.colour());
        assertTrue(state.recentEvents().isEmpty());
    }

    @Test
    public void blockedTopicFlipsToRed() {
        state.recordEvent("safe_mode.blocked", 1_000L, "blocked");
        assertEquals(SafeModeIndicatorState.Colour.RED, state.colour());
        assertEquals(1, state.recentEvents().size());
    }

    @Test
    public void queueStormBlockedTopicFlipsToRed() {
        state.recordEvent("safe_mode.queue_storm_blocked", 1_000L, "qs");
        assertEquals(SafeModeIndicatorState.Colour.RED, state.colour());
    }

    @Test
    public void snapshotCommittedTopicFlipsToRed() {
        state.recordEvent("safe_mode.snapshot.committed", 1_000L, "kept");
        assertEquals(SafeModeIndicatorState.Colour.RED, state.colour());
    }

    @Test
    public void roiAutoBackupFlipsToAmber() {
        state.recordEvent("safe_mode.roi_auto_backup", 1_000L, "backed up");
        assertEquals(SafeModeIndicatorState.Colour.AMBER, state.colour());
    }

    @Test
    public void calibrationWarningFlipsToAmber() {
        state.recordEvent("safe_mode.calibration_warning", 1_000L, "calibration");
        assertEquals(SafeModeIndicatorState.Colour.AMBER, state.colour());
    }

    @Test
    public void snapshotReleasedKeepsGreen() {
        state.recordEvent("safe_mode.snapshot.released", 1_000L, "released");
        assertEquals(SafeModeIndicatorState.Colour.GREEN, state.colour());
    }

    @Test
    public void recentEventRingCapsAtThree() {
        state.recordEvent("safe_mode.passed", 1L, "p1");
        state.recordEvent("safe_mode.passed", 2L, "p2");
        state.recordEvent("safe_mode.warned", 3L, "w3");
        state.recordEvent("safe_mode.blocked", 4L, "b4");
        List<SafeModeIndicatorState.Event> rec = state.recentEvents();
        assertEquals(SafeModeIndicatorState.RECENT_CAPACITY, rec.size());
        assertEquals(3, rec.size());
        // Most-recent first.
        assertEquals("safe_mode.blocked", rec.get(0).topic);
        assertEquals("safe_mode.warned",  rec.get(1).topic);
        assertEquals("safe_mode.passed",  rec.get(2).topic);
    }

    @Test
    public void masterSwitchOffPaintsGrey() {
        state.recordEvent("safe_mode.blocked", 1L, "b");
        assertEquals(SafeModeIndicatorState.Colour.RED, state.colour());
        state.setMasterEnabled(false);
        assertEquals(SafeModeIndicatorState.Colour.GREY, state.colour());
        // Recorded events still in the ring so the click-out panel keeps
        // showing them after the master flip.
        assertFalse(state.recentEvents().isEmpty());
    }

    @Test
    public void masterSwitchOnRestoresLastColour() {
        state.recordEvent("safe_mode.blocked", 1L, "b");
        state.setMasterEnabled(false);
        state.setMasterEnabled(true);
        assertEquals(SafeModeIndicatorState.Colour.RED, state.colour());
    }

    @Test
    public void masterSwitchOnWithNoEventsIsGreen() {
        state.setMasterEnabled(false);
        state.setMasterEnabled(true);
        assertEquals(SafeModeIndicatorState.Colour.GREEN, state.colour());
    }

    @Test
    public void decayDropsAmberRedToGreenAfter60Seconds() {
        // ts=0 is treated as "no events yet" by applyDecay — start at 1 ms
        // so the decay timer is armed.
        state.recordEvent("safe_mode.warned", 1L, "w");
        assertEquals(SafeModeIndicatorState.Colour.AMBER, state.colour());
        // 30 s is inside the window — no change.
        assertFalse(state.applyDecay(30_001L));
        assertEquals(SafeModeIndicatorState.Colour.AMBER, state.colour());
        // 60 s after the event — drops to GREEN.
        assertTrue(state.applyDecay(60_001L));
        assertEquals(SafeModeIndicatorState.Colour.GREEN, state.colour());
        // Recent events are preserved.
        assertEquals(1, state.recentEvents().size());
    }

    @Test
    public void decayIsIdempotent() {
        state.recordEvent("safe_mode.blocked", 1L, "b");
        assertTrue(state.applyDecay(60_001L));
        assertFalse(state.applyDecay(60_002L));
        assertFalse(state.applyDecay(60_003L));
    }

    @Test
    public void decaySkippedWhenMasterOff() {
        state.recordEvent("safe_mode.blocked", 1L, "b");
        state.setMasterEnabled(false);
        assertFalse(state.applyDecay(60_001L));
        assertEquals(SafeModeIndicatorState.Colour.GREY, state.colour());
    }

    @Test
    public void emptyTopicIsIgnored() {
        state.recordEvent("", 1L, "x");
        assertTrue(state.recentEvents().isEmpty());
        assertEquals(SafeModeIndicatorState.Colour.GREEN, state.colour());
    }

    @Test
    public void colourForKnownTopics() {
        assertEquals(SafeModeIndicatorState.Colour.RED,
                SafeModeIndicatorState.colourFor("safe_mode.blocked"));
        assertEquals(SafeModeIndicatorState.Colour.RED,
                SafeModeIndicatorState.colourFor("safe_mode.queue_storm_blocked"));
        assertEquals(SafeModeIndicatorState.Colour.RED,
                SafeModeIndicatorState.colourFor("safe_mode.snapshot.committed"));
        assertEquals(SafeModeIndicatorState.Colour.AMBER,
                SafeModeIndicatorState.colourFor("safe_mode.warned"));
        assertEquals(SafeModeIndicatorState.Colour.AMBER,
                SafeModeIndicatorState.colourFor("safe_mode.calibration_warning"));
        assertEquals(SafeModeIndicatorState.Colour.AMBER,
                SafeModeIndicatorState.colourFor("safe_mode.roi_auto_backup"));
        assertEquals(SafeModeIndicatorState.Colour.GREEN,
                SafeModeIndicatorState.colourFor("safe_mode.passed"));
        assertEquals(SafeModeIndicatorState.Colour.GREEN,
                SafeModeIndicatorState.colourFor("safe_mode.snapshot.released"));
    }

    @Test
    public void describeKnownTopicsIsPlainEnglish() {
        String d = SafeModeIndicatorState.describe("safe_mode.queue_storm_blocked");
        assertNotNull(d);
        assertTrue(d.toLowerCase().contains("blocked"));
        assertTrue(SafeModeIndicatorState
                .describe("safe_mode.calibration_warning")
                .toLowerCase().contains("calibration"));
    }

    @Test
    public void listenerFiresOnEveryRecordedEvent() {
        AtomicInteger fired = new AtomicInteger(0);
        state.addListener(new SafeModeIndicatorState.Listener() {
            @Override
            public void onChange(SafeModeIndicatorState s) { fired.incrementAndGet(); }
        });
        state.recordEvent("safe_mode.passed", 1L, "p");
        state.recordEvent("safe_mode.warned", 2L, "w");
        assertEquals(2, fired.get());
    }

    /**
     * Indicator → EventBus wiring. The indicator's onEvent path is what
     * runs in production; install the listener manually (without going
     * through installOnStartup, which would try to add a toolbar tool)
     * and assert a publish lands in the underlying state.
     */
    @Test
    public void indicatorOnEventDrivesState() {
        SafeModeIndicator indicator = SafeModeIndicator.getInstance();
        indicator.resetForTest();
        EventBus bus = EventBus.getInstance();
        // Wire by hand so we don't instantiate the toolbar / overlay.
        bus.subscribe("safe_mode.*", indicator);
        try {
            JsonObject data = new JsonObject();
            data.addProperty("rule_id", "calibration_loss");
            data.addProperty("target", "Test-Image.tif");
            bus.publish("safe_mode.blocked", data);
            assertEquals(SafeModeIndicatorState.Colour.RED,
                    indicator.state().colour());
            assertFalse(indicator.state().recentEvents().isEmpty());
            String desc = indicator.state().recentEvents().get(0).description;
            assertNotNull(desc);
            assertTrue("description carries target hint: " + desc,
                    desc.contains("Test-Image.tif"));
        } finally {
            bus.unsubscribe(indicator);
            indicator.resetForTest();
        }
    }

    @Test
    public void indicatorIgnoresNonSafeModeTopics() {
        SafeModeIndicator indicator = SafeModeIndicator.getInstance();
        indicator.resetForTest();
        EventBus bus = EventBus.getInstance();
        bus.subscribe("*", indicator);
        try {
            // EventBus coalesces to 200ms per topic — give each publish a
            // unique non-safe-mode topic and confirm the indicator sees
            // nothing through onEvent's prefix filter.
            bus.publish("image.opened", new JsonObject());
            bus.publish("dialog.shown", new JsonObject());
            assertTrue(indicator.state().recentEvents().isEmpty());
        } finally {
            bus.unsubscribe(indicator);
            indicator.resetForTest();
        }
    }
}
