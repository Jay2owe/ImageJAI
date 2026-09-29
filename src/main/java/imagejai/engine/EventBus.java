package imagejai.engine;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Phase 2: in-JVM publish/subscribe event bus for ImageJAI TCP push notifications.
 * <p>
 * Publishers (ImageMonitor, CommandEngine, StateInspector, DialogWatcher) emit
 * events by topic string. Subscribers (TCP streaming sockets) register patterns
 * and receive framed JSON objects: {@code {event, data, ts, seq}}.
 * <p>
 * Only explicitly declared state-like topics are coalesced. Their identity is
 * part of the key, so an update for one job or image can never suppress an
 * update for another one.
 * Monotonic {@code seq} lets clients reorder interleaved events.
 * <p>
 * Singleton — shared across the whole plugin JVM.
 */
public class EventBus {

    public interface Listener {
        void onEvent(JsonObject frame);
    }

    /** Bounded, payload-free diagnostic for a listener that threw. */
    public static final class ListenerFailure {
        private final long timestampMillis;
        private final String topic;
        private final String listenerType;
        private final String errorType;

        ListenerFailure(long timestampMillis, String topic, Listener listener,
                        Throwable failure) {
            this.timestampMillis = timestampMillis;
            this.topic = safeDiagnosticTopic(topic);
            this.listenerType = listener == null ? "unknown"
                    : listener.getClass().getName();
            this.errorType = failure == null ? "unknown"
                    : failure.getClass().getName();
        }

        public long timestampMillis() { return timestampMillis; }
        public String topic() { return topic; }
        public String listenerType() { return listenerType; }
        public String errorType() { return errorType; }
    }

    private static final long COALESCE_MS = 200L;
    private static final int MAX_COALESCE_KEYS = 1024;
    private static final int MAX_LISTENER_FAILURES = 64;
    public static final int MAX_SUBSCRIPTIONS = 1024;
    public static final int MAX_PATTERN_CHARS = 128;
    private static final Set<String> COALESCIBLE_TOPICS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "image.updated",
                    "job.progress",
                    "results.changed"
            )));

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
    private final AtomicLong listenerFailureCounter = new AtomicLong(0);
    private final AtomicLong rejectedSubscriptionCounter = new AtomicLong(0);
    private final ArrayDeque<ListenerFailure> listenerFailures =
            new ArrayDeque<ListenerFailure>();
    private final LongSupplier clock;
    // Nestable per-pattern suppression. Publishers check isSuppressed(topic)
    // before dispatching. Used by handleExecuteMacro to drop image.* events
    // while a macro is in flight — those events trigger the agent to send
    // concurrent get_image_info / get_histogram calls whose invokeLater
    // EDT tasks race against Duplicate's imp.show(), leaving the wrong
    // window as WindowManager.currentImage when the macro's next line
    // (setAutoThreshold / Convert to Mask) runs.
    private final ConcurrentHashMap<String, AtomicInteger> suppressCounts = new ConcurrentHashMap<String, AtomicInteger>();

    private EventBus() {
        this(new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    /** Package-private deterministic clock seam for focused coalescing tests. */
    EventBus(LongSupplier clock) {
        this.clock = clock == null ? new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        } : clock;
    }

    /** Monotonic global sequence used by both published and synthetic frames. */
    public long nextSeq() {
        return seqCounter.incrementAndGet();
    }

    /** Register a listener for a topic pattern. Supports exact match, {@code *}, and suffix wildcards like {@code image.*}. */
    public synchronized void subscribe(String pattern, Listener listener) {
        if (pattern == null || listener == null) return;
        if (pattern.length() == 0 || pattern.length() > MAX_PATTERN_CHARS
                || subs.size() >= MAX_SUBSCRIPTIONS) {
            rejectedSubscriptionCounter.incrementAndGet();
            return;
        }
        subs.add(new Subscription(pattern, listener));
    }

    /** Atomically register all patterns or none; used by TCP subscribe ack. */
    public synchronized boolean subscribeAll(List<String> patterns,
                                             Listener listener) {
        if (patterns == null || patterns.isEmpty() || listener == null) {
            rejectedSubscriptionCounter.incrementAndGet();
            return false;
        }
        if (patterns.size() > MAX_SUBSCRIPTIONS - subs.size()) {
            rejectedSubscriptionCounter.incrementAndGet();
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.length() == 0
                    || pattern.length() > MAX_PATTERN_CHARS) {
                rejectedSubscriptionCounter.incrementAndGet();
                return false;
            }
        }
        for (String pattern : patterns) {
            subs.add(new Subscription(pattern, listener));
        }
        return true;
    }

    /** Remove every subscription belonging to {@code listener}. */
    public synchronized void unsubscribe(Listener listener) {
        if (listener == null) return;
        List<Subscription> toRemove = new ArrayList<Subscription>();
        for (Subscription s : subs) {
            if (s.listener == listener) toRemove.add(s);
        }
        if (!toRemove.isEmpty()) subs.removeAll(toRemove);
    }

    /**
     * Publish an event. Only topics in {@link #COALESCIBLE_TOPICS} coalesce,
     * and only when both their topic and stable identity match. Lifecycle
     * events are never coalesced. Dispatches synchronously to matching
     * listeners; listeners are expected to queue asynchronously.
     */
    public void publish(String topic, JsonObject data) {
        if (topic == null) return;
        if (isSuppressed(topic)) return;
        long now = clock.getAsLong();
        String key = coalescingKey(topic, data);
        if (key != null) {
            Long prev = lastPublish.get(key);
            if (prev != null && (now - prev) < COALESCE_MS) {
                return;
            }
            lastPublish.put(key, now);
            pruneCoalescingKeys(now);
        }

        JsonObject frame = new JsonObject();
        frame.addProperty("event", topic);
        frame.add("data", data != null ? data : new JsonObject());
        frame.addProperty("ts", now);
        frame.addProperty("seq", nextSeq());

        for (Subscription s : subs) {
            if (!matches(s.pattern, topic)) continue;
            try {
                s.listener.onEvent(frame);
            } catch (Throwable failure) {
                recordListenerFailure(topic, s.listener, failure, now);
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

    public long listenerFailureCount() {
        return listenerFailureCounter.get();
    }

    /** Number of subscriptions refused by the global count/pattern bounds. */
    public long rejectedSubscriptionCount() {
        return rejectedSubscriptionCounter.get();
    }

    public List<ListenerFailure> recentListenerFailures() {
        synchronized (listenerFailures) {
            return Collections.unmodifiableList(
                    new ArrayList<ListenerFailure>(listenerFailures));
        }
    }

    /** True only for state-like frames whose replacement is lossless. */
    static boolean isCoalescible(JsonObject frame) {
        return coalescingKey(frame) != null;
    }

    /**
     * Return the internal replacement key for a frame, or {@code null} when
     * the frame must retain its own queue slot. The key is never serialized.
     */
    static String coalescingKey(JsonObject frame) {
        if (frame == null || !frame.has("event")
                || !frame.get("event").isJsonPrimitive()) {
            return null;
        }
        JsonObject data = frame.has("data") && frame.get("data").isJsonObject()
                ? frame.getAsJsonObject("data") : null;
        return coalescingKey(frame.get("event").getAsString(), data);
    }

    static String coalescingKey(String topic, JsonObject data) {
        if (topic == null || !COALESCIBLE_TOPICS.contains(topic)) {
            return null;
        }
        if ("results.changed".equals(topic)) {
            return topic + "|singleton";
        }
        String identity = firstString(data,
                "job_id", "image_id", "dialog_id", "macro_id", "id");
        if (identity == null || identity.isEmpty()) {
            // An identity-bearing topic without an identity is unsafe to
            // collapse: retain every frame until its publisher is repaired.
            return null;
        }
        return topic + "|" + identity;
    }

    private static String firstString(JsonObject data, String... keys) {
        if (data == null) return null;
        for (String key : keys) {
            try {
                if (data.has(key) && data.get(key).isJsonPrimitive()) {
                    String value = data.get(key).getAsString();
                    if (value != null && !value.isEmpty()) return value;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private void pruneCoalescingKeys(long now) {
        if (lastPublish.size() <= MAX_COALESCE_KEYS) return;
        long cutoff = now - COALESCE_MS;
        for (Map.Entry<String, Long> entry : lastPublish.entrySet()) {
            Long value = entry.getValue();
            if (value == null || value <= cutoff) {
                lastPublish.remove(entry.getKey(), value);
            }
        }
        // An extreme same-window identity burst remains bounded. Removing an
        // arbitrary key can only reduce coalescing; it cannot drop an event.
        while (lastPublish.size() > MAX_COALESCE_KEYS) {
            java.util.Iterator<String> it = lastPublish.keySet().iterator();
            if (!it.hasNext()) break;
            lastPublish.remove(it.next());
        }
    }

    private void recordListenerFailure(String topic, Listener listener,
                                       Throwable failure, long now) {
        long failureCount = listenerFailureCounter.incrementAndGet();
        ListenerFailure diagnostic = new ListenerFailure(now, topic, listener, failure);
        synchronized (listenerFailures) {
            while (listenerFailures.size() >= MAX_LISTENER_FAILURES) {
                listenerFailures.removeFirst();
            }
            listenerFailures.addLast(diagnostic);
        }
        if (failureCount <= 3L || failureCount % 100L == 0L) {
            System.err.println("[ImageJAI-EventBus] listener failure #"
                    + failureCount + " on " + diagnostic.topic() + ": "
                    + diagnostic.errorType());
        }
    }

    private static String safeDiagnosticTopic(String topic) {
        String value = topic == null ? "" : topic;
        return value.matches("[a-z0-9_.-]{1,128}") ? value : "custom";
    }

    int coalescingStateSizeForTest() {
        return lastPublish.size();
    }

    int suppressionStateSizeForTest() {
        return suppressCounts.size();
    }

    /**
     * Push a suppression scope for all topics matching {@code pattern} (same
     * wildcard semantics as {@link #subscribe}). Every {@code pushSuppress}
     * must be balanced by exactly one {@code popSuppress} — use try/finally.
     * Counts nest safely across threads.
     */
    public void pushSuppress(String pattern) {
        if (pattern == null) return;
        suppressCounts.compute(pattern, (key, count) -> {
            AtomicInteger next = count == null ? new AtomicInteger() : count;
            next.incrementAndGet();
            return next;
        });
    }

    /** Balance a prior {@link #pushSuppress} for the same pattern. */
    public void popSuppress(String pattern) {
        if (pattern == null) return;
        suppressCounts.computeIfPresent(pattern, (key, count) ->
                count.decrementAndGet() <= 0 ? null : count);
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
