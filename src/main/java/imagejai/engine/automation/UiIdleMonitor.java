package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import imagejai.engine.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides when the interface has actually finished, and says exactly what is
 * still in the way when it has not.
 *
 * <p>"The command returned" is not the same as "the plugin finished and the
 * window is responsive again". Idle here means all of:</p>
 *
 * <ol>
 *   <li>an event-thread barrier completed inside the remaining deadline;</li>
 *   <li>no automation EDT action is in flight;</li>
 *   <li>no relevant lifecycle event (image, dialog, results, macro, job,
 *       window, action) arrived during the quiet window;</li>
 *   <li>no interactive AWT work (input, window, component events) was
 *       dispatched during the quiet window, when dispatch instrumentation is
 *       installed.</li>
 * </ol>
 *
 * <p>Paint activity is measured and reported but does not block idle by
 * default: plugins with animated previews repaint forever, and requiring total
 * AWT silence would make idle unreachable. A caller that needs paint quiet asks
 * for it explicitly.</p>
 *
 * <p>On timeout the reply lists the unsatisfied conditions rather than a bare
 * {@code false}, so a scenario can report <em>why</em> it never settled.</p>
 */
public final class UiIdleMonitor implements AutoCloseable {

    public static final long DEFAULT_QUIET_MS = 150L;
    public static final long MIN_QUIET_MS = 10L;
    public static final long MAX_QUIET_MS = 10_000L;
    private static final long POLL_INTERVAL_MS = 20L;

    /** Topics whose arrival means Fiji is still doing something a test cares about. */
    static final Set<String> RELEVANT_TOPICS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "image.opened", "image.updated", "image.closed",
                    "dialog.appeared", "dialog.closed",
                    "results.changed",
                    "macro.started", "macro.completed",
                    "job.started", "job.progress", "job.completed", "job.failed",
                    "ui.window.appeared", "ui.window.closed",
                    "ui.action.started", "ui.action.completed")));

    public static final String BLOCKER_EDT = "edt_barrier";
    public static final String BLOCKER_ACTIVE_ACTION = "active_ui_action";
    public static final String BLOCKER_RECENT_EVENT = "recent_event";
    public static final String BLOCKER_AWT_ACTIVITY = "awt_interactive_activity";
    public static final String BLOCKER_PAINT = "paint_activity";

    /**
     * Round-trips a no-op through the event thread and returns how long that
     * took, or {@code null} when it did not answer inside the budget.
     *
     * <p>Extracted so the blocker-reporting logic can be tested without racing
     * AWT: whether a blocked event thread is <em>observed</em> depends on the
     * JVM's dispatch scheduling, but what the monitor reports when it observes
     * one must not.</p>
     */
    interface BarrierProbe {
        Double measureMs(long timeoutMs);
    }

    private final UiAutomationService service;
    private final EventBus eventBus;
    private final InstrumentedEventQueue queue;
    private final EventBus.Listener listener;
    private final BarrierProbe barrierProbe;
    private volatile long lastRelevantNanos;
    private volatile String lastRelevantTopic = "";
    private volatile boolean started;

    public UiIdleMonitor(UiAutomationService service, EventBus eventBus,
                         InstrumentedEventQueue queue) {
        this(service, eventBus, queue, null);
    }

    UiIdleMonitor(UiAutomationService service, EventBus eventBus,
                  InstrumentedEventQueue queue, BarrierProbe barrierProbe) {
        this.service = service;
        this.eventBus = eventBus;
        this.queue = queue;
        this.barrierProbe = barrierProbe == null
                ? new BarrierProbe() {
                    @Override public Double measureMs(long timeoutMs) {
                        return edtBarrierMs(timeoutMs);
                    }
                }
                : barrierProbe;
        this.lastRelevantNanos = System.nanoTime();
        this.listener = new EventBus.Listener() {
            @Override public void onEvent(JsonObject frame) {
                try {
                    if (frame == null || !frame.has("event")) return;
                    String topic = frame.get("event").getAsString();
                    if (!RELEVANT_TOPICS.contains(topic)) return;
                    lastRelevantTopic = topic;
                    lastRelevantNanos = System.nanoTime();
                } catch (RuntimeException ignored) {
                    // A malformed frame must never break idle detection.
                }
            }
        };
    }

    public synchronized void start() {
        if (started || eventBus == null) return;
        eventBus.subscribe("*", listener);
        started = true;
    }

    @Override
    public synchronized void close() {
        if (!started || eventBus == null) return;
        eventBus.unsubscribe(listener);
        started = false;
    }

    /** Nanotime of the most recent relevant lifecycle event. */
    public long lastRelevantEventNanos() { return lastRelevantNanos; }

    /** Topic of the most recent relevant lifecycle event, or {@code ""}. */
    public String lastRelevantTopic() { return lastRelevantTopic; }

    /**
     * Wait until the UI is idle or the deadline passes.
     *
     * @param timeoutMs        total budget, clamped to the policy maximum
     * @param quietMs          how long nothing relevant must have happened
     * @param requirePaintQuiet also require no paint events in the quiet window
     * @return the {@code result} object for {@code wait_for_ui_idle}
     */
    public JsonObject awaitIdle(long timeoutMs, long quietMs, boolean requirePaintQuiet) {
        long budgetMs = Math.max(1L, Math.min(AutomationPolicy.MAX_WAIT_TIMEOUT_MS, timeoutMs));
        long quiet = Math.max(MIN_QUIET_MS, Math.min(MAX_QUIET_MS, quietMs));
        long startedAtNanos = System.nanoTime();
        long deadlineNanos = startedAtNanos + budgetMs * 1_000_000L;
        int polls = 0;
        List<String> blockers = new ArrayList<String>();
        double lastBarrierMs = -1.0d;

        while (true) {
            polls++;
            blockers = new ArrayList<String>();
            long remainingMs = Math.max(1L,
                    (deadlineNanos - System.nanoTime()) / 1_000_000L);

            Double barrierMs = barrierProbe.measureMs(Math.min(remainingMs, budgetMs));
            if (barrierMs == null) {
                blockers.add(BLOCKER_EDT);
            } else {
                lastBarrierMs = barrierMs.doubleValue();
            }

            int active = service == null ? 0 : service.activeActionCount();
            if (active > 0) blockers.add(BLOCKER_ACTIVE_ACTION + ":" + active);

            long eventAgeMs = ageMs(lastRelevantNanos);
            if (eventAgeMs < quiet) {
                blockers.add(BLOCKER_RECENT_EVENT + ":" + safeTopic(lastRelevantTopic));
            }

            if (queue != null) {
                long interactiveAgeMs = ageMs(queue.lastInteractiveNanos());
                if (interactiveAgeMs < quiet) blockers.add(BLOCKER_AWT_ACTIVITY);
                if (requirePaintQuiet && ageMs(queue.lastPaintNanos()) < quiet) {
                    blockers.add(BLOCKER_PAINT);
                }
            }

            if (blockers.isEmpty()) {
                return result(true, startedAtNanos, polls, quiet, blockers,
                        lastBarrierMs, requirePaintQuiet);
            }
            if (System.nanoTime() >= deadlineNanos) {
                return result(false, startedAtNanos, polls, quiet, blockers,
                        lastBarrierMs, requirePaintQuiet);
            }
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MS,
                        Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                blockers.add("interrupted");
                return result(false, startedAtNanos, polls, quiet, blockers,
                        lastBarrierMs, requirePaintQuiet);
            }
        }
    }

    /** Round-trip a no-op through the event thread. Null means it did not answer. */
    private Double edtBarrierMs(long timeoutMs) {
        if (service == null) return Double.valueOf(0.0d);
        final long submittedAtNanos = System.nanoTime();
        try {
            return service.callOnEdt(new Supplier<Double>() {
                @Override public Double get() {
                    return Double.valueOf(UiAutomationService.millisBetween(
                            submittedAtNanos, System.nanoTime()));
                }
            }, timeoutMs);
        } catch (UiAutomationService.EdtTimeoutException notAnswering) {
            return null;
        } catch (RuntimeException failed) {
            return null;
        }
    }

    private JsonObject result(boolean idle, long startedAtNanos, int polls, long quietMs,
                              List<String> blockers, double barrierMs,
                              boolean requirePaintQuiet) {
        JsonObject json = new JsonObject();
        json.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        json.addProperty("idle", idle);
        json.addProperty("waited_ms",
                UiAutomationService.millisBetween(startedAtNanos, System.nanoTime()));
        json.addProperty("polls", polls);
        json.addProperty("quiet_ms", quietMs);
        json.addProperty("require_paint_quiet", requirePaintQuiet);

        JsonObject checks = new JsonObject();
        checks.addProperty("edt_barrier", !blockers.contains(BLOCKER_EDT));
        checks.addProperty("edt_barrier_ms", barrierMs);
        boolean actionBlocked = false;
        boolean eventBlocked = false;
        for (String blocker : blockers) {
            if (blocker.startsWith(BLOCKER_ACTIVE_ACTION)) actionBlocked = true;
            if (blocker.startsWith(BLOCKER_RECENT_EVENT)) eventBlocked = true;
        }
        checks.addProperty("no_active_ui_action", !actionBlocked);
        checks.addProperty("event_quiet", !eventBlocked);
        checks.addProperty("awt_quiet", !blockers.contains(BLOCKER_AWT_ACTIVITY));
        checks.addProperty("awt_instrumented", queue != null);
        json.add("checks", checks);

        JsonArray blockerArray = new JsonArray();
        for (String blocker : blockers) blockerArray.add(new JsonPrimitive(blocker));
        json.add("blockers", blockerArray);

        json.addProperty("last_event_topic", safeTopic(lastRelevantTopic));
        json.addProperty("last_event_age_ms", ageMs(lastRelevantNanos));
        json.addProperty("active_ui_actions",
                service == null ? 0 : service.activeActionCount());
        if (queue != null) {
            json.addProperty("last_interactive_awt_age_ms",
                    ageMs(queue.lastInteractiveNanos()));
            json.addProperty("last_paint_age_ms", ageMs(queue.lastPaintNanos()));
        }
        return json;
    }

    private static long ageMs(long sinceNanos) {
        if (sinceNanos <= 0L) return Long.MAX_VALUE / 4;
        return Math.max(0L, (System.nanoTime() - sinceNanos) / 1_000_000L);
    }

    private static String safeTopic(String topic) {
        if (topic == null || topic.isEmpty()) return "";
        return RELEVANT_TOPICS.contains(topic) ? topic : "custom";
    }
}
