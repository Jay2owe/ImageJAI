package imagejai.engine.automation;

import java.awt.EventQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how long the Swing event thread takes to answer a trivial task.
 *
 * <p>A slow handler and a busy queue look identical from outside the process:
 * both make the interface unresponsive. This heartbeat separates them by
 * posting one no-op every {@link #DEFAULT_INTERVAL_MS} and reporting how much
 * later than scheduled it actually ran. A delay above
 * {@link #DEFAULT_FREEZE_MS} counts as a freeze.</p>
 *
 * <p>One daemon thread, one queued no-op in flight at a time, and no retained
 * history: the heartbeat cannot itself become the load it is measuring.</p>
 */
public final class EdtHeartbeat implements AutoCloseable {

    public static final long DEFAULT_INTERVAL_MS = 200L;
    public static final long DEFAULT_FREEZE_MS = 1_000L;

    /** Receives each observed scheduling delay, on the event thread. */
    public interface DelaySink {
        void onEdtDelay(double delayMs);
    }

    private final long intervalMs;
    private final long freezeMs;
    private final CopyOnWriteArrayList<DelaySink> sinks =
            new CopyOnWriteArrayList<DelaySink>();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong freezes = new AtomicLong();
    private volatile double maxDelayMs;
    private volatile double lastDelayMs;
    private ScheduledExecutorService scheduler;

    public EdtHeartbeat() {
        this(DEFAULT_INTERVAL_MS, DEFAULT_FREEZE_MS);
    }

    public EdtHeartbeat(long intervalMs, long freezeMs) {
        this.intervalMs = Math.max(20L, intervalMs);
        this.freezeMs = Math.max(50L, freezeMs);
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ImageJAI-EDT-Heartbeat");
                thread.setDaemon(true);
                return thread;
            }
        });
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { beat(); }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        sinks.clear();
    }

    public void addSink(DelaySink sink) {
        if (sink != null) sinks.addIfAbsent(sink);
    }

    public void removeSink(DelaySink sink) {
        if (sink != null) sinks.remove(sink);
    }

    public long sampleCount() { return samples.get(); }
    public long freezeCount() { return freezes.get(); }
    public double maxDelayMs() { return maxDelayMs; }
    public double lastDelayMs() { return lastDelayMs; }

    /** Reset the running aggregates. Used when a session or trace ends. */
    public void reset() {
        samples.set(0L);
        freezes.set(0L);
        maxDelayMs = 0.0d;
        lastDelayMs = 0.0d;
    }

    void beat() {
        if (!inFlight.compareAndSet(false, true)) {
            // The previous no-op has not run yet: the event thread is already
            // late, and queueing more would only deepen the backlog.
            return;
        }
        final long postedAtNanos = System.nanoTime();
        try {
            EventQueue.invokeLater(new Runnable() {
                @Override public void run() {
                    try {
                        observe(UiAutomationService.millisBetween(
                                postedAtNanos, System.nanoTime()));
                    } finally {
                        inFlight.set(false);
                    }
                }
            });
        } catch (Throwable notDispatchable) {
            inFlight.set(false);
        }
    }

    void observe(double delayMs) {
        samples.incrementAndGet();
        lastDelayMs = delayMs;
        if (delayMs > maxDelayMs) maxDelayMs = delayMs;
        if (delayMs >= freezeMs) freezes.incrementAndGet();
        for (DelaySink sink : sinks) {
            try {
                sink.onEdtDelay(delayMs);
            } catch (Throwable ignored) {
                // A broken sink must never stop the heartbeat.
            }
        }
    }
}
