package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import imagejai.engine.EventBus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * Idle detection has to distinguish "nothing is happening" from "I gave up",
 * and say which condition failed. A bare boolean would let a scenario report a
 * pass on a UI that never settled.
 */
public class UiIdleMonitorTest {

    private UiAutomationService service;
    private UiIdleMonitor monitor;
    private javax.swing.JFrame anchor;

    @Before
    public void setUp() throws Exception {
        assumeFalse("the event thread is unavailable", GraphicsEnvironment.isHeadless());
        service = new UiAutomationService(new UiIdentityRegistry(), null);
        monitor = new UiIdleMonitor(service, EventBus.getInstance(), null);
        monitor.start();
        // Keep one displayable window for the whole test. With none, AWT stops
        // the event thread when it goes quiet and starts a fresh one on the next
        // post — so a runnable "blocking the EDT" can end up blocking a thread
        // that is no longer the one dispatching, and the barrier would sail
        // through. A real Fiji always has the main window; the fixture matches.
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                anchor = new javax.swing.JFrame("Idle anchor");
                anchor.setSize(80, 60);
                anchor.setLocation(-3000, -3000);
                anchor.setVisible(true);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        if (monitor != null) monitor.close();
        if (anchor != null) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { anchor.dispose(); }
            });
        }
    }

    @Test
    public void aQuietUiReachesIdleAndSaysWhichChecksPassed() throws Exception {
        settle();
        JsonObject result = monitor.awaitIdle(3_000L, 30L, false);

        assertTrue(result.toString(), result.get("idle").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("blockers").size());
        JsonObject checks = result.getAsJsonObject("checks");
        assertTrue(checks.get("edt_barrier").getAsBoolean());
        assertTrue(checks.get("no_active_ui_action").getAsBoolean());
        assertTrue(checks.get("event_quiet").getAsBoolean());
        assertFalse("no instrumented queue in this fixture",
                checks.get("awt_instrumented").getAsBoolean());
        assertTrue(result.get("waited_ms").getAsDouble() >= 0.0d);
        assertTrue(result.get("polls").getAsInt() >= 1);
    }

    @Test
    public void aRelevantEventBlocksIdleUntilTheQuietWindowElapses() {
        EventBus.getInstance().publish("dialog.appeared", new JsonObject());

        JsonObject blocked = monitor.awaitIdle(120L, 2_000L, false);
        assertFalse(blocked.get("idle").getAsBoolean());
        assertTrue(contains(blocked.getAsJsonArray("blockers"),
                UiIdleMonitor.BLOCKER_RECENT_EVENT));
        assertFalse(blocked.getAsJsonObject("checks").get("event_quiet").getAsBoolean());
        assertEquals("dialog.appeared", blocked.get("last_event_topic").getAsString());

        // Same event, a quiet window it has already outlived: idle is reachable.
        JsonObject settled = monitor.awaitIdle(3_000L, 30L, false);
        assertTrue(settled.toString(), settled.get("idle").getAsBoolean());
    }

    @Test
    public void anIrrelevantTopicNeverBlocksIdle() {
        // memory.pressure fires on a timer; treating it as activity would make
        // idle unreachable on a busy machine.
        EventBus.getInstance().publish("memory.pressure", new JsonObject());
        JsonObject result = monitor.awaitIdle(2_000L, 40L, false);
        assertTrue(result.toString(), result.get("idle").getAsBoolean());
    }

    @Test
    public void anEventThreadThatDoesNotAnswerIsReportedAsABarrierFailure() {
        // Whether AWT lets a probe through while another runnable is blocking
        // is a JVM scheduling detail; what the monitor reports when the probe
        // does not answer is the contract, so that is what is pinned here.
        UiIdleMonitor blocked = new UiIdleMonitor(service, EventBus.getInstance(),
                null, timeoutMs -> null);
        blocked.start();
        try {
            JsonObject result = blocked.awaitIdle(120L, 20L, false);
            assertFalse(result.toString(), result.get("idle").getAsBoolean());
            assertTrue(result.toString(), contains(result.getAsJsonArray("blockers"),
                    UiIdleMonitor.BLOCKER_EDT));
            assertFalse(result.toString(), result.getAsJsonObject("checks")
                    .get("edt_barrier").getAsBoolean());
        } finally {
            blocked.close();
        }
    }

    @Test
    public void aSlowButAnsweringEventThreadStillReachesIdle() {
        UiIdleMonitor slow = new UiIdleMonitor(service, EventBus.getInstance(),
                null, timeoutMs -> Double.valueOf(45.0d));
        slow.start();
        try {
            JsonObject result = slow.awaitIdle(2_000L, 30L, false);
            assertTrue(result.toString(), result.get("idle").getAsBoolean());
            assertTrue(result.getAsJsonObject("checks").get("edt_barrier").getAsBoolean());
            assertEquals(45.0d, result.getAsJsonObject("checks")
                    .get("edt_barrier_ms").getAsDouble(), 0.001d);
        } finally {
            slow.close();
        }
    }

    @Test
    public void aRealEventThreadBarrierIsMeasuredAndReported() throws Exception {
        settle();
        JsonObject result = monitor.awaitIdle(3_000L, 30L, false);
        assertTrue(result.toString(), result.get("idle").getAsBoolean());
        // The live probe went through the real event thread and timed itself.
        assertTrue(result.getAsJsonObject("checks")
                .get("edt_barrier_ms").getAsDouble() >= 0.0d);
    }

    @Test
    public void anInFlightUiActionBlocksIdleAndIsNamedWithItsCount() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch inAction = new CountDownLatch(1);
        final javax.swing.JFrame frame = new javax.swing.JFrame("Idle fixture");
        final javax.swing.JButton button = new javax.swing.JButton("Long action");
        button.addActionListener(event -> {
            inAction.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                frame.getContentPane().add(button);
                frame.pack();
                frame.setLocation(-3000, -3000);
                frame.setVisible(true);
            }
        });

        Thread actor = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    service.callOnEdt(() -> service.performAction(button, frame,
                            new UiAutomationService.ActionRequest(
                                    UiNode.ACTION_ACTIVATE, null, null),
                            -1L, System.nanoTime()), 10_000L);
                } catch (Exception ignored) {
                    // The assertions below cover the outcome we care about.
                }
            }
        }, "idle-test-actor");
        actor.setDaemon(true);
        actor.start();

        try {
            assertTrue(inAction.await(5, TimeUnit.SECONDS));
            assertEquals(1, service.activeActionCount());
            // The event thread is inside the action, so the barrier and the
            // active-action check both fail — and both are reported.
            JsonObject blocked = monitor.awaitIdle(150L, 20L, false);
            assertFalse(blocked.get("idle").getAsBoolean());
            assertFalse(blocked.getAsJsonObject("checks")
                    .get("no_active_ui_action").getAsBoolean());
            assertTrue(contains(blocked.getAsJsonArray("blockers"),
                    UiIdleMonitor.BLOCKER_ACTIVE_ACTION));
        } finally {
            release.countDown();
            actor.join(5_000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { frame.dispose(); }
            });
        }

        settle();
        assertEquals(0, service.activeActionCount());
        assertTrue(monitor.awaitIdle(3_000L, 30L, false).get("idle").getAsBoolean());
    }

    @Test
    public void closingTheMonitorDetachesItFromTheBus() {
        monitor.close();
        long before = monitor.lastRelevantEventNanos();
        EventBus.getInstance().publish("dialog.appeared", new JsonObject());
        assertEquals(before, monitor.lastRelevantEventNanos());
        // Closing twice is not an error.
        monitor.close();
    }

    private void settle() throws Exception {
        // Drain anything this test class itself queued before measuring.
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { }
        });
        Thread.sleep(80L);
    }

    private static boolean contains(JsonArray blockers, String prefix) {
        for (JsonElement blocker : blockers) {
            if (blocker.getAsString().startsWith(prefix)) return true;
        }
        return false;
    }
}
