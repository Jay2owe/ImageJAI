package imagejai.engine.automation;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.InvocationEvent;
import java.awt.event.PaintEvent;
import java.awt.event.WindowEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link EventQueue} that times how long AWT events wait before dispatch and
 * how long their handlers run.
 *
 * <p>Installed <strong>only</strong> when the startup test-automation policy is
 * armed. Normal ImageJ-AI never touches the system event queue, so production
 * dispatch is byte-for-byte the stock JDK path.</p>
 *
 * <p>Retention is bounded and payload-free: the queue keeps counters, a post
 * timestamp per in-flight event, and the event's class category. It never
 * retains an event, a component, typed text, or a key code.</p>
 */
public final class InstrumentedEventQueue extends EventQueue {

    /** Event categories reported to sinks. */
    public static final String KIND_PAINT = "paint";
    public static final String KIND_INPUT = "input";
    public static final String KIND_WINDOW = "window";
    public static final String KIND_COMPONENT = "component";
    public static final String KIND_INVOCATION = "invocation";
    public static final String KIND_OTHER = "other";

    /** Largest number of in-flight post timestamps retained. */
    static final int MAX_PENDING = 4_096;

    /** Receives one call per dispatched event, on the event thread. */
    public interface Sink {
        void onDispatch(String kind, double queueMs, double handlerMs);
    }

    private static final Object INSTALL_LOCK = new Object();
    private static InstrumentedEventQueue installed;

    private final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<Sink>();
    private final Map<AWTEvent, Long> postedAtNanos = new IdentityHashMap<AWTEvent, Long>();
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong droppedTimestamps = new AtomicLong();
    private volatile long lastInteractiveNanos;
    private volatile long lastPaintNanos;

    private InstrumentedEventQueue() {
    }

    /**
     * Push an instrumented queue onto the system event queue, or return the one
     * already installed. Safe to call more than once.
     */
    public static InstrumentedEventQueue install() {
        synchronized (INSTALL_LOCK) {
            if (installed != null) return installed;
            InstrumentedEventQueue queue = new InstrumentedEventQueue();
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
            installed = queue;
            return queue;
        }
    }

    /** The installed queue, or {@code null} when the bridge is not armed. */
    public static InstrumentedEventQueue installed() {
        synchronized (INSTALL_LOCK) {
            return installed;
        }
    }

    /**
     * Remove the instrumented queue. {@link EventQueue#pop()} must run on the
     * event thread, so this schedules it and returns immediately.
     */
    public static void uninstall() {
        final InstrumentedEventQueue queue;
        synchronized (INSTALL_LOCK) {
            queue = installed;
            installed = null;
        }
        if (queue == null) return;
        queue.sinks.clear();
        Runnable pop = new Runnable() {
            @Override public void run() {
                try {
                    queue.pop();
                } catch (Throwable alreadyGone) {
                    // Another queue may have been pushed on top; leaving ours in
                    // place is harmless because every sink is detached.
                }
                synchronized (queue.postedAtNanos) {
                    queue.postedAtNanos.clear();
                }
            }
        };
        if (EventQueue.isDispatchThread()) pop.run();
        else EventQueue.invokeLater(pop);
    }

    /** Test seam: build an uninstalled queue for direct dispatch-path tests. */
    static InstrumentedEventQueue forTest() {
        return new InstrumentedEventQueue();
    }

    public void addSink(Sink sink) {
        if (sink != null) sinks.addIfAbsent(sink);
    }

    public void removeSink(Sink sink) {
        if (sink != null) sinks.remove(sink);
    }

    public long dispatchedCount() { return dispatched.get(); }

    /** Nanotime of the last input/window/component event dispatched. */
    public long lastInteractiveNanos() { return lastInteractiveNanos; }

    /** Nanotime of the last paint event dispatched. */
    public long lastPaintNanos() { return lastPaintNanos; }

    /** Post timestamps discarded because the bounded map was full. */
    public long droppedTimestampCount() { return droppedTimestamps.get(); }

    @Override
    public void postEvent(AWTEvent event) {
        trackPost(event);
        super.postEvent(event);
    }

    /** Record when an event entered the queue, so its wait can be measured. */
    void trackPost(AWTEvent event) {
        if (event == null || sinks.isEmpty()) return;
        synchronized (postedAtNanos) {
            if (postedAtNanos.size() >= MAX_PENDING) {
                // A storm of posts must not grow the map without bound. The
                // affected events simply report an unknown queue delay.
                postedAtNanos.clear();
                droppedTimestamps.incrementAndGet();
            }
            postedAtNanos.put(event, Long.valueOf(System.nanoTime()));
        }
    }

    @Override
    protected void dispatchEvent(AWTEvent event) {
        long startNanos = System.nanoTime();
        Long posted;
        synchronized (postedAtNanos) {
            posted = postedAtNanos.remove(event);
        }
        try {
            super.dispatchEvent(event);
        } finally {
            long endNanos = System.nanoTime();
            record(event, posted, startNanos, endNanos);
        }
    }

    private void record(AWTEvent event, Long postedAt, long startNanos, long endNanos) {
        dispatched.incrementAndGet();
        String kind = kindOf(event);
        if (KIND_PAINT.equals(kind)) {
            lastPaintNanos = endNanos;
        } else if (KIND_INPUT.equals(kind) || KIND_WINDOW.equals(kind)
                || KIND_COMPONENT.equals(kind)) {
            lastInteractiveNanos = endNanos;
        }
        if (sinks.isEmpty()) return;
        double queueMs = postedAt == null ? -1.0d
                : UiAutomationService.millisBetween(postedAt.longValue(), startNanos);
        double handlerMs = UiAutomationService.millisBetween(startNanos, endNanos);
        for (Sink sink : sinks) {
            try {
                sink.onDispatch(kind, queueMs, handlerMs);
            } catch (Throwable ignored) {
                // A broken sink must never break AWT dispatch.
            }
        }
    }

    static String kindOf(AWTEvent event) {
        if (event instanceof PaintEvent) return KIND_PAINT;
        if (event instanceof InputEvent) return KIND_INPUT;
        if (event instanceof WindowEvent) return KIND_WINDOW;
        if (event instanceof InvocationEvent) return KIND_INVOCATION;
        if (event instanceof ComponentEvent) return KIND_COMPONENT;
        return KIND_OTHER;
    }
}
