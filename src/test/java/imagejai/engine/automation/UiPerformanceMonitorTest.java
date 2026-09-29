package imagejai.engine.automation;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.awt.AWTEvent;
import java.awt.event.InvocationEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Per-action timing. The point of these fields is to separate a slow handler
 * from a busy queue from a frozen event thread, so each has to accumulate
 * independently — and retention has to stay bounded, because a long run would
 * otherwise turn instrumentation into a leak.
 */
public class UiPerformanceMonitorTest {

    @Test
    public void tracesAccumulateQueueHandlerAndDelayEvidenceSeparately() {
        UiPerformanceMonitor monitor = new UiPerformanceMonitor(null, null);
        UiPerformanceMonitor.Trace trace = monitor.start("action-1");
        assertNotNull(trace);

        trace.onDispatch(InstrumentedEventQueue.KIND_INPUT, 4.0d, 12.0d);
        trace.onDispatch(InstrumentedEventQueue.KIND_PAINT, 1.0d, 3.0d);
        trace.onDispatch(InstrumentedEventQueue.KIND_PAINT, 2.0d, 1.0d);
        trace.onDispatch(InstrumentedEventQueue.KIND_INVOCATION, -1.0d, 250.0d);
        trace.onEdtDelay(15.0d);
        trace.onEdtDelay(1_500.0d);
        trace.mark("action_dispatched");

        JsonObject json = monitor.stop(trace.traceId()).toJson();
        assertEquals("action-1", json.get("action_id").getAsString());
        assertFalse(json.get("open").getAsBoolean());
        assertEquals(4, json.get("awt_events").getAsInt());
        assertEquals(2, json.get("paint_events").getAsInt());
        assertEquals(1, json.get("input_events").getAsInt());
        assertEquals(1, json.get("invocation_events").getAsInt());
        // The unknown-queue-delay sample is excluded from the queue statistics
        // rather than counted as zero, which would understate the delay.
        assertEquals(4.0d, json.get("edt_queue_ms_max").getAsDouble(), 0.0001d);
        assertEquals(3, json.get("edt_queue_samples").getAsInt());
        assertEquals(250.0d, json.get("handler_ms_max").getAsDouble(), 0.0001d);
        assertEquals(266.0d, json.get("handler_ms_total").getAsDouble(), 0.0001d);
        assertEquals(1_500.0d, json.get("max_edt_delay_ms").getAsDouble(), 0.0001d);
        assertEquals(1, json.get("freeze_count").getAsInt());
        assertEquals(1, json.getAsJsonArray("phases").size());
        assertEquals("action_dispatched", json.getAsJsonArray("phases").get(0)
                .getAsJsonObject().get("phase").getAsString());
    }

    @Test
    public void aStoppedTraceIgnoresLaterActivity() {
        UiPerformanceMonitor monitor = new UiPerformanceMonitor(null, null);
        UiPerformanceMonitor.Trace trace = monitor.start("action-1");
        trace.onDispatch(InstrumentedEventQueue.KIND_INPUT, 1.0d, 1.0d);
        monitor.stop(trace.traceId());

        trace.onDispatch(InstrumentedEventQueue.KIND_INPUT, 99.0d, 99.0d);
        trace.onEdtDelay(9_999.0d);

        JsonObject json = monitor.trace(trace.traceId()).toJson();
        assertEquals(1, json.get("awt_events").getAsInt());
        assertEquals(0.0d, json.get("max_edt_delay_ms").getAsDouble(), 0.0001d);
    }

    @Test
    public void concurrentTracesAreCappedAndDoNotCrossOver() {
        UiPerformanceMonitor monitor = new UiPerformanceMonitor(null, null);
        UiPerformanceMonitor.Trace[] traces =
                new UiPerformanceMonitor.Trace[AutomationPolicy.MAX_OPEN_TRACES];
        for (int i = 0; i < traces.length; i++) {
            traces[i] = monitor.start("action-" + i);
            assertNotNull(traces[i]);
        }
        assertNull("the open-trace cap must refuse rather than grow",
                monitor.start("one-too-many"));

        traces[0].onDispatch(InstrumentedEventQueue.KIND_INPUT, 1.0d, 1.0d);
        assertEquals(1, monitor.trace(traces[0].traceId()).toJson()
                .get("awt_events").getAsInt());
        assertEquals(0, monitor.trace(traces[1].traceId()).toJson()
                .get("awt_events").getAsInt());

        monitor.stop(traces[0].traceId());
        assertNotNull("a slot frees when a trace is closed",
                monitor.start("after-close"));
    }

    @Test
    public void finishedTracesAreRetainedButBounded() {
        UiPerformanceMonitor monitor = new UiPerformanceMonitor(null, null);
        String first = null;
        for (int i = 0; i < AutomationPolicy.MAX_RETAINED_TRACES + 5; i++) {
            UiPerformanceMonitor.Trace trace = monitor.start("action-" + i);
            if (i == 0) first = trace.traceId();
            monitor.stop(trace.traceId());
        }
        assertEquals(AutomationPolicy.MAX_RETAINED_TRACES, monitor.retainedCount());
        assertNull("the oldest aggregate is evicted first", monitor.trace(first));
    }

    @Test
    public void unknownTraceIdsAreNotInvented() {
        UiPerformanceMonitor monitor = new UiPerformanceMonitor(null, null);
        assertNull(monitor.stop("trace-does-not-exist"));
        assertNull(monitor.trace("trace-does-not-exist"));
        assertFalse(monitor.mark("trace-does-not-exist", "phase"));
    }

    @Test
    public void closeDropsEveryTraceSoASessionCannotLeakMetrics() {
        UiPerformanceMonitor monitor = new UiPerformanceMonitor(null, null);
        UiPerformanceMonitor.Trace open = monitor.start("open");
        UiPerformanceMonitor.Trace closed = monitor.start("closed");
        monitor.stop(closed.traceId());

        monitor.close();

        assertEquals(0, monitor.openCount());
        assertEquals(0, monitor.retainedCount());
        assertNull(monitor.trace(open.traceId()));
        assertNull(monitor.trace(closed.traceId()));
    }

    @Test
    public void theSummaryReportsWhetherDispatchIsActuallyInstrumented() {
        UiPerformanceMonitor uninstrumented = new UiPerformanceMonitor(null, null);
        JsonObject summary = uninstrumented.summaryJson();
        assertFalse(summary.get("instrumented").getAsBoolean());
        assertEquals(0L, summary.get("awt_events_total").getAsLong());
        assertEquals(AutomationPolicy.MAX_OPEN_TRACES,
                summary.get("max_open_traces").getAsInt());

        InstrumentedEventQueue queue = InstrumentedEventQueue.forTest();
        EdtHeartbeat heartbeat = new EdtHeartbeat();
        try {
            UiPerformanceMonitor instrumented =
                    new UiPerformanceMonitor(queue, heartbeat);
            assertTrue(instrumented.summaryJson().get("instrumented").getAsBoolean());
        } finally {
            heartbeat.close();
        }
    }

    @Test
    public void phaseMarksAreBounded() {
        UiPerformanceMonitor monitor = new UiPerformanceMonitor(null, null);
        UiPerformanceMonitor.Trace trace = monitor.start("action");
        for (int i = 0; i < 200; i++) trace.mark("phase-" + i);
        assertEquals(32, monitor.stop(trace.traceId()).toJson()
                .getAsJsonArray("phases").size());
    }

    @Test
    public void theEventQueueClassifiesAndTimesWhatItDispatches() {
        InstrumentedEventQueue queue = InstrumentedEventQueue.forTest();
        final AtomicInteger calls = new AtomicInteger();
        final String[] kinds = new String[1];
        final double[] times = new double[2];
        queue.addSink((kind, queueMs, handlerMs) -> {
            calls.incrementAndGet();
            kinds[0] = kind;
            times[0] = queueMs;
            times[1] = handlerMs;
        });

        AtomicInteger ran = new AtomicInteger();
        InvocationEvent event = new InvocationEvent(this,
                (Runnable) ran::incrementAndGet);
        // trackPost is the half of postEvent that measures; calling postEvent
        // itself on an uninstalled queue would spin up a private dispatch
        // thread and race this explicit dispatch.
        queue.trackPost(event);
        queue.dispatchEvent(event);

        assertEquals(1, ran.get());
        assertEquals(1, calls.get());
        assertEquals(InstrumentedEventQueue.KIND_INVOCATION, kinds[0]);
        assertTrue("a posted event has a measurable queue delay", times[0] >= 0.0d);
        assertTrue(times[1] >= 0.0d);
        assertEquals(1L, queue.dispatchedCount());
    }

    @Test
    public void anEventDispatchedWithoutAPostReportsAnUnknownQueueDelay() {
        InstrumentedEventQueue queue = InstrumentedEventQueue.forTest();
        final double[] observed = new double[] {0.0d};
        queue.addSink((kind, queueMs, handlerMs) -> observed[0] = queueMs);

        InvocationEvent event = new InvocationEvent(this, (Runnable) () -> { });
        queue.dispatchEvent(event);

        assertEquals("unknown is -1, never a fabricated zero",
                -1.0d, observed[0], 0.0001d);
    }

    @Test
    public void aBrokenSinkCannotBreakDispatch() {
        InstrumentedEventQueue queue = InstrumentedEventQueue.forTest();
        queue.addSink((kind, queueMs, handlerMs) -> {
            throw new IllegalStateException("sink is broken");
        });
        AtomicInteger ran = new AtomicInteger();
        InvocationEvent event = new InvocationEvent(this,
                (Runnable) ran::incrementAndGet);
        queue.dispatchEvent(event);
        assertEquals(1, ran.get());
    }

    @Test
    public void pendingPostTimestampsStayBounded() {
        InstrumentedEventQueue queue = InstrumentedEventQueue.forTest();
        queue.addSink((kind, queueMs, handlerMs) -> { });
        for (int i = 0; i < InstrumentedEventQueue.MAX_PENDING + 50; i++) {
            queue.trackPost(new InvocationEvent(this, (Runnable) () -> { }));
        }
        assertTrue("a post storm must drop timestamps, not grow the map",
                queue.droppedTimestampCount() >= 1L);
    }

    @Test
    public void eventKindsAreClassifiedForTheIdleAndTraceSignals() {
        assertEquals(InstrumentedEventQueue.KIND_INVOCATION,
                InstrumentedEventQueue.kindOf(
                        new InvocationEvent(this, (Runnable) () -> { })));
        assertEquals(InstrumentedEventQueue.KIND_OTHER,
                InstrumentedEventQueue.kindOf(new AWTEvent(this, 0) { }));
    }

    @Test
    public void theHeartbeatSeparatesDelayFromFreeze() {
        EdtHeartbeat heartbeat = new EdtHeartbeat(50L, 500L);
        try {
            final AtomicInteger samples = new AtomicInteger();
            heartbeat.addSink(delay -> samples.incrementAndGet());

            heartbeat.observe(12.0d);
            heartbeat.observe(900.0d);
            heartbeat.observe(3.0d);

            assertEquals(3, samples.get());
            assertEquals(3L, heartbeat.sampleCount());
            assertEquals(1L, heartbeat.freezeCount());
            assertEquals(900.0d, heartbeat.maxDelayMs(), 0.0001d);
            assertEquals(3.0d, heartbeat.lastDelayMs(), 0.0001d);

            heartbeat.reset();
            assertEquals(0L, heartbeat.sampleCount());
            assertEquals(0.0d, heartbeat.maxDelayMs(), 0.0001d);
        } finally {
            heartbeat.close();
        }
    }
}
