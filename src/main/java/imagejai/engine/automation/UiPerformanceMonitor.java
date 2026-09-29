package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded per-action timing evidence, correlated to a harness-issued action id.
 *
 * <p>An external stopwatch cannot tell a slow handler from a busy event queue
 * from a frozen event thread. A trace runs for the span the harness declares —
 * normally opened immediately before it sends physical input and closed when it
 * observes the terminal state — and accumulates, for that span only:</p>
 *
 * <ul>
 *   <li>dispatched AWT events by category, with queue and handler times;</li>
 *   <li>the worst event-thread scheduling delay seen by {@link EdtHeartbeat};</li>
 *   <li>freeze count above the heartbeat threshold;</li>
 *   <li>caller-declared phase marks, so idle/settle can be attributed.</li>
 * </ul>
 *
 * <p>Nothing about the events themselves is retained: no components, no key
 * codes, no text, no screenshots. Open traces are capped at
 * {@link AutomationPolicy#MAX_OPEN_TRACES} and finished traces at
 * {@link AutomationPolicy#MAX_RETAINED_TRACES}, oldest evicted first.</p>
 */
public final class UiPerformanceMonitor implements AutoCloseable {

    /** One open or finished measurement span. */
    public static final class Trace implements InstrumentedEventQueue.Sink,
            EdtHeartbeat.DelaySink {
        private final String traceId;
        private final String actionId;
        private final long startedAtNanos;
        private final long startedAtEpochMs;
        private volatile long endedAtNanos;

        private final AtomicLong events = new AtomicLong();
        private final AtomicLong paintEvents = new AtomicLong();
        private final AtomicLong inputEvents = new AtomicLong();
        private final AtomicLong invocationEvents = new AtomicLong();
        private final AtomicLong windowEvents = new AtomicLong();
        private volatile double queueMsMax;
        private volatile double queueMsSum;
        private volatile long queueSamples;
        private volatile double handlerMsMax;
        private volatile double handlerMsSum;
        private volatile double edtDelayMsMax;
        private volatile long edtDelaySamples;
        private volatile long freezes;
        private final List<String[]> phases = new ArrayList<String[]>();

        Trace(String traceId, String actionId) {
            this.traceId = traceId;
            this.actionId = actionId;
            this.startedAtNanos = System.nanoTime();
            this.startedAtEpochMs = System.currentTimeMillis();
        }

        public String traceId() { return traceId; }
        public String actionId() { return actionId; }
        public boolean isOpen() { return endedAtNanos == 0L; }

        @Override
        public void onDispatch(String kind, double queueMs, double handlerMs) {
            if (!isOpen()) return;
            events.incrementAndGet();
            if (InstrumentedEventQueue.KIND_PAINT.equals(kind)) paintEvents.incrementAndGet();
            else if (InstrumentedEventQueue.KIND_INPUT.equals(kind)) inputEvents.incrementAndGet();
            else if (InstrumentedEventQueue.KIND_INVOCATION.equals(kind)) {
                invocationEvents.incrementAndGet();
            } else if (InstrumentedEventQueue.KIND_WINDOW.equals(kind)) {
                windowEvents.incrementAndGet();
            }
            if (queueMs >= 0.0d) {
                queueSamples++;
                queueMsSum += queueMs;
                if (queueMs > queueMsMax) queueMsMax = queueMs;
            }
            handlerMsSum += handlerMs;
            if (handlerMs > handlerMsMax) handlerMsMax = handlerMs;
        }

        @Override
        public void onEdtDelay(double delayMs) {
            if (!isOpen()) return;
            edtDelaySamples++;
            if (delayMs > edtDelayMsMax) edtDelayMsMax = delayMs;
            if (delayMs >= EdtHeartbeat.DEFAULT_FREEZE_MS) freezes++;
        }

        /** Record a caller-declared phase boundary, bounded to 32 marks. */
        synchronized void mark(String phase) {
            if (phase == null || phases.size() >= 32) return;
            phases.add(new String[] {phase,
                    String.valueOf(UiAutomationService.millisBetween(
                            startedAtNanos, System.nanoTime()))});
        }

        void end() {
            if (endedAtNanos == 0L) endedAtNanos = System.nanoTime();
        }

        public synchronized JsonObject toJson() {
            long end = endedAtNanos == 0L ? System.nanoTime() : endedAtNanos;
            JsonObject json = new JsonObject();
            json.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
            json.addProperty("trace_id", traceId);
            json.addProperty("action_id", actionId);
            json.addProperty("open", isOpen());
            json.addProperty("started_at_epoch_ms", startedAtEpochMs);
            json.addProperty("duration_ms",
                    UiAutomationService.millisBetween(startedAtNanos, end));
            json.addProperty("awt_events", events.get());
            json.addProperty("paint_events", paintEvents.get());
            json.addProperty("input_events", inputEvents.get());
            json.addProperty("window_events", windowEvents.get());
            json.addProperty("invocation_events", invocationEvents.get());
            json.addProperty("edt_queue_ms_max", queueMsMax);
            json.addProperty("edt_queue_ms_mean", queueSamples == 0L ? 0.0d
                    : Math.round(queueMsSum / queueSamples * 1_000.0d) / 1_000.0d);
            json.addProperty("edt_queue_samples", queueSamples);
            json.addProperty("handler_ms_max", handlerMsMax);
            json.addProperty("handler_ms_total",
                    Math.round(handlerMsSum * 1_000.0d) / 1_000.0d);
            json.addProperty("max_edt_delay_ms", edtDelayMsMax);
            json.addProperty("edt_delay_samples", edtDelaySamples);
            json.addProperty("freeze_count", freezes);
            JsonArray phaseArray = new JsonArray();
            for (String[] phase : phases) {
                JsonObject entry = new JsonObject();
                entry.addProperty("phase", phase[0]);
                entry.addProperty("at_ms", Double.parseDouble(phase[1]));
                phaseArray.add(entry);
            }
            json.add("phases", phaseArray);
            return json;
        }
    }

    private final InstrumentedEventQueue queue;
    private final EdtHeartbeat heartbeat;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Trace> open = new LinkedHashMap<String, Trace>();
    private final LinkedHashMap<String, Trace> finished =
            new LinkedHashMap<String, Trace>() {
                private static final long serialVersionUID = 1L;
                @Override protected boolean removeEldestEntry(Map.Entry<String, Trace> eldest) {
                    return size() > AutomationPolicy.MAX_RETAINED_TRACES;
                }
            };

    public UiPerformanceMonitor(InstrumentedEventQueue queue, EdtHeartbeat heartbeat) {
        this.queue = queue;
        this.heartbeat = heartbeat;
    }

    /** True when AWT dispatch instrumentation is actually installed. */
    public boolean isInstrumented() { return queue != null; }

    /**
     * Open a trace for a harness action id.
     *
     * @return the new trace, or {@code null} when the open-trace cap is reached
     */
    public synchronized Trace start(String actionId) {
        if (open.size() >= AutomationPolicy.MAX_OPEN_TRACES) return null;
        String traceId = "trace-" + sequence.incrementAndGet();
        Trace trace = new Trace(traceId, actionId == null ? "" : actionId);
        open.put(traceId, trace);
        if (queue != null) queue.addSink(trace);
        if (heartbeat != null) heartbeat.addSink(trace);
        return trace;
    }

    /** Close a trace and retain its aggregate. Returns null when unknown. */
    public synchronized Trace stop(String traceId) {
        Trace trace = open.remove(traceId);
        if (trace == null) return null;
        trace.end();
        if (queue != null) queue.removeSink(trace);
        if (heartbeat != null) heartbeat.removeSink(trace);
        finished.put(traceId, trace);
        return trace;
    }

    /** Mark a phase boundary on an open trace. */
    public synchronized boolean mark(String traceId, String phase) {
        Trace trace = open.get(traceId);
        if (trace == null) return false;
        trace.mark(phase);
        return true;
    }

    /** Open or finished trace by id, or {@code null}. */
    public synchronized Trace trace(String traceId) {
        Trace trace = open.get(traceId);
        return trace != null ? trace : finished.get(traceId);
    }

    public synchronized int openCount() { return open.size(); }
    public synchronized int retainedCount() { return finished.size(); }

    /** Process-wide counters, independent of any trace. */
    public JsonObject summaryJson() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        json.addProperty("instrumented", queue != null);
        json.addProperty("awt_events_total", queue == null ? 0L : queue.dispatchedCount());
        json.addProperty("dropped_queue_timestamps",
                queue == null ? 0L : queue.droppedTimestampCount());
        json.addProperty("heartbeat_samples", heartbeat == null ? 0L : heartbeat.sampleCount());
        json.addProperty("max_edt_delay_ms", heartbeat == null ? 0.0d : heartbeat.maxDelayMs());
        json.addProperty("last_edt_delay_ms",
                heartbeat == null ? 0.0d : heartbeat.lastDelayMs());
        json.addProperty("freeze_count", heartbeat == null ? 0L : heartbeat.freezeCount());
        synchronized (this) {
            json.addProperty("open_traces", open.size());
            json.addProperty("retained_traces", finished.size());
        }
        json.addProperty("max_open_traces", AutomationPolicy.MAX_OPEN_TRACES);
        json.addProperty("max_retained_traces", AutomationPolicy.MAX_RETAINED_TRACES);
        return json;
    }

    /** Detach every trace and drop retained aggregates. */
    @Override
    public synchronized void close() {
        for (Trace trace : open.values()) {
            trace.end();
            if (queue != null) queue.removeSink(trace);
            if (heartbeat != null) heartbeat.removeSink(trace);
        }
        open.clear();
        for (Trace trace : finished.values()) {
            if (queue != null) queue.removeSink(trace);
            if (heartbeat != null) heartbeat.removeSink(trace);
        }
        finished.clear();
    }
}
