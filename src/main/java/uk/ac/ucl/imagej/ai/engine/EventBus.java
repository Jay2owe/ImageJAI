package uk.ac.ucl.imagej.ai.engine;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2: in-JVM publish/subscribe event bus for ImageJAI TCP push notifications.
 * <p>
 * Publishers (ImageMonitor, CommandEngine, StateInspector, DialogWatcher) emit
 * events by topic string. Subscribers (TCP streaming sockets) register patterns
 * and receive framed JSON objects: {@code {event, data, ts, seq}}.
 * <p>
 * Per-topic coalescing: identical-topic publishes within 200ms are dropped.
 * Monotonic {@code seq} lets clients reorder interleaved events.
 * <p>
 * Singleton — shared across the whole plugin JVM.
 */
public class EventBus {

    public interface Listener {
        void onEvent(JsonObject frame);
    }

    private static final long COALESCE_MS = 200L;

    private static final EventBus INSTANCE = new EventBus();

    public static EventBus getInstance() {
        return INSTANCE;
    }

    private static final class Subscription {
        final String pattern;
        final Listener listener;
        Subscription(String pattern, Listener listener) {
            this.pattern = pattern;
            this.listener = listener;
        }
    }

    private final CopyOnWriteArrayList<Subscription> subs = new CopyOnWriteArrayList<Subscription>();
    private final ConcurrentHashMap<String, Long> lastPublish = new ConcurrentHashMap<String, Long>();
    private final AtomicLong seqCounter = new AtomicLong(0);
    // Nestable per-pattern suppression. Publishers check isSuppressed(topic)
    // before dispatching. Used by handleExecuteMacro to drop image.* events
    // while a macro is in flight — those events trigger the agent to send
    // concurrent get_image_info / get_histogram calls whose invokeLater
    // EDT tasks race against Duplicate's imp.show(), leaving the wrong
    // window as WindowManager.currentImage when the macro's next line
    // (setAutoThreshold / Convert to Mask) runs.
    private final ConcurrentHashMap<String, AtomicInteger> suppressCounts = new ConcurrentHashMap<String, AtomicInteger>();

    private EventBus() {}

    /** Monotonic global sequence used by both published and synthetic frames. */
    public long nextSeq() {
        return seqCounter.incrementAndGet();
    }

    /** Register a listener for a topic pattern. Supports exact match, {@code *}, and suffix wildcards like {@code image.*}. */
    public void subscribe(String pattern, Listener listener) {
        if (pattern == null || listener == null) return;
        subs.add(new Subscription(pattern, listener));
    }

    /** Remove every subscription belonging to {@code listener}. */
    public void unsubscribe(Listener listener) {
        if (listener == null) return;
        List<Subscription> toRemove = new ArrayList<Subscription>();
        for (Subscription s : subs) {
            if (s.listener == listener) toRemove.add(s);
        }
        if (!toRemove.isEmpty()) subs.removeAll(toRemove);
    }

    /**
     * Publish an event. Coalesces per-topic at 200ms (identical-topic events
     * within the window are dropped). Dispatches synchronously to matching
     * listeners; listeners are expected to queue asynchronously.
     */
    public void publish(String topic, JsonObject data) {
        if (topic == null) return;
        if (isSuppressed(topic)) return;
        long now = System.currentTimeMillis();
        Long prev = lastPublish.get(topic);
        if (prev != null && (now - prev) < COALESCE_MS) {
            return;
        }
        lastPublish.put(topic, now);

        JsonObject frame = new JsonObject();
        frame.addProperty("event", topic);
        frame.add("data", data != null ? data : new JsonObject());
        frame.addProperty("ts", now);
        frame.addProperty("seq", nextSeq());

        for (Subscription s : subs) {
            if (!matches(s.pattern, topic)) continue;
            try {
                s.listener.onEvent(frame);
            } catch (Throwable ignore) {
                // Listener faults must never break the publisher thread.
            }
        }
    }

    /** Convenience: publish a no-data event. */
    public void publish(String topic) {
        publish(topic, null);
    }

    /** Current subscriber count — used by TCPCommandServer to enforce the 8-subscriber cap. */
    public int subscriberCount() {
        return subs.size();
    }

    /**
     * Push a suppression scope for all topics matching {@code pattern} (same
     * wildcard semantics as {@link #subscribe}). Every {@code pushSuppress}
     * must be balanced by exactly one {@code popSuppress} — use try/finally.
     * Counts nest safely across threads.
     */
    public void pushSuppress(String pattern) {
        if (pattern == null) return;
        AtomicInteger count = suppressCounts.get(pattern);
        if (count == null) {
            AtomicInteger created = new AtomicInteger(0);
            count = suppressCounts.putIfAbsent(pattern, created);
            if (count == null) count = created;
        }
        count.incrementAndGet();
    }

    /** Balance a prior {@link #pushSuppress} for the same pattern. */
    public void popSuppress(String pattern) {
        if (pattern == null) return;
        AtomicInteger count = suppressCounts.get(pattern);
        if (count == null) return;
        int n = count.decrementAndGet();
        if (n < 0) count.set(0);
    }

    private boolean isSuppressed(String topic) {
        if (suppressCounts.isEmpty()) return false;
        for (Map.Entry<String, AtomicInteger> e : suppressCounts.entrySet()) {
            if (e.getValue().get() <= 0) continue;
            if (matches(e.getKey(), topic)) return true;
        }
        return false;
    }

    /**
     * Match semantics:
     * <ul>
     *   <li>{@code "*"} — matches any topic.</li>
     *   <li>{@code "image.*"} — matches {@code image}, {@code image.opened}, {@code image.updated.slice}, etc.</li>
     *   <li>{@code "image*"} — prefix match (less strict; rarely needed).</li>
     *   <li>Anything else — exact string equality.</li>
     * </ul>
     */
    static boolean matches(String pattern, String topic) {
        if (pattern == null || topic == null) return false;
        if (pattern.equals(topic)) return true;
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return topic.equals(prefix) || topic.startsWith(prefix + ".");
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return topic.startsWith(prefix);
        }
        return false;
    }
}
