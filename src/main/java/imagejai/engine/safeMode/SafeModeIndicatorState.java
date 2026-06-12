package imagejai.engine.safeMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): pure-data state
 * machine for the safe-mode status indicator. Holds the current colour, the
 * three most recent events, the master-switch flag, and the timestamp of
 * the last event used by the decay rule.
 *
 * <p>Headless-safe: this class touches no Swing and no ImageJ APIs, so unit
 * tests can drive it directly. The toolbar tool, status-bar overlay, and
 * event panel each subscribe to this state via {@link Listener#onChange} and
 * re-paint themselves on the EDT — keeping every UI side-effect out of the
 * pub/sub thread that ferries {@code safe_mode.*} frames in from the
 * {@link imagejai.engine.EventBus}.
 *
 * <p>Colour rules — see plan §Event source:
 * <ul>
 *   <li>{@link Colour#GREEN}: {@code safe_mode.passed},
 *       {@code safe_mode.snapshot.released}, or no events yet.</li>
 *   <li>{@link Colour#AMBER}: {@code safe_mode.warned},
 *       {@code safe_mode.roi_auto_backup}, {@code safe_mode.calibration_warning}.</li>
 *   <li>{@link Colour#RED}: {@code safe_mode.blocked},
 *       {@code safe_mode.queue_storm_blocked}, {@code safe_mode.snapshot.committed}.</li>
 *   <li>{@link Colour#GREY}: master safe-mode is OFF.</li>
 * </ul>
 *
 * <p>Decay: 60 seconds with no new event resets {@link #colour()} from
 * AMBER/RED back to GREEN. The recent-events deque is preserved so the
 * click-out panel still shows what tripped the gate. Driven by
 * {@link #applyDecay(long)} so tests can advance time deterministically
 * without sleeping.
 */
public final class SafeModeIndicatorState {

    /** 60-second decay window per plan §Decay. */
    public static final long DECAY_MILLIS = 60_000L;

    /** Capacity of the recent-events ring per plan §Toolbar dot click. */
    public static final int RECENT_CAPACITY = 3;

    /** Coarse colour state — the single value the toolbar dot and the
     *  status-bar square paint from. */
    public enum Colour { GREEN, AMBER, RED, GREY }

    /** Plain-data event row — populated from the
     *  {@link imagejai.engine.EventBus} frame and surfaced in
     *  the click-out panel and the status-bar tooltip. */
    public static final class Event {
        public final String topic;
        public final long ts;
        public final String description;

        public Event(String topic, long ts, String description) {
            this.topic = topic == null ? "" : topic;
            this.ts = ts;
            this.description = description == null ? "" : description;
        }
    }

    /** Notified after every state mutation. UI layers re-paint on the EDT. */
    public interface Listener { void onChange(SafeModeIndicatorState s); }

    private final Deque<Event> recent = new ArrayDeque<Event>();
    private Colour colour = Colour.GREEN;
    private long lastEventTs = 0L;
    private boolean masterEnabled = true;
    private final List<Listener> listeners = new ArrayList<Listener>();

    public SafeModeIndicatorState() {}

    public synchronized Colour colour() { return colour; }

    public synchronized boolean masterEnabled() { return masterEnabled; }

    /** Most recent event first; capped at {@link #RECENT_CAPACITY}. */
    public synchronized List<Event> recentEvents() {
        return new ArrayList<Event>(recent);
    }

    public synchronized long lastEventTs() { return lastEventTs; }

    public void addListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) { listeners.add(l); }
    }

    public void removeListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) { listeners.remove(l); }
    }

    /** Toggle the master safe-mode flag — paints grey when off, restores
     *  the colour driven by the most recent event when flipped back on. */
    public void setMasterEnabled(boolean enabled) {
        boolean changed;
        synchronized (this) {
            changed = (this.masterEnabled != enabled);
            this.masterEnabled = enabled;
            if (!enabled) {
                colour = Colour.GREY;
            } else {
                // Re-derive from the most recent event (or default green).
                Event head = recent.peekFirst();
                colour = head != null ? colourFor(head.topic) : Colour.GREEN;
            }
        }
        if (changed) fireChange();
    }

    /**
     * Record a new safe-mode event. Pushes onto the recent-events ring,
     * updates the colour state, and stamps {@link #lastEventTs}. No-op when
     * the topic is null/empty so an upstream filter mistake cannot blank
     * the indicator.
     */
    public void recordEvent(String topic, long nowMs, String description) {
        if (topic == null || topic.isEmpty()) return;
        synchronized (this) {
            recent.offerFirst(new Event(topic, nowMs, description));
            while (recent.size() > RECENT_CAPACITY) recent.pollLast();
            lastEventTs = nowMs;
            // Master-OFF still records events into the ring (so a user can
            // see what an unguarded run did) but keeps the dot grey.
            if (masterEnabled) {
                colour = colourFor(topic);
            }
        }
        fireChange();
    }

    /**
     * Apply the 60-second decay rule against the supplied current time.
     * AMBER / RED with no new event for {@link #DECAY_MILLIS} ms drop back
     * to GREEN. Idempotent: callers can poll on a 1-second timer without
     * blowing up the listener fanout when nothing changed.
     *
     * @return true when the colour actually changed (caller can use this
     *         to skip a redundant repaint).
     */
    public boolean applyDecay(long nowMs) {
        boolean changed = false;
        synchronized (this) {
            if (!masterEnabled) return false;
            if (colour == Colour.GREEN || colour == Colour.GREY) return false;
            if (lastEventTs == 0L) return false;
            if ((nowMs - lastEventTs) >= DECAY_MILLIS) {
                colour = Colour.GREEN;
                changed = true;
            }
        }
        if (changed) fireChange();
        return changed;
    }

    /** Map a {@code safe_mode.*} topic to its colour. Visible for tests. */
    public static Colour colourFor(String topic) {
        if (topic == null) return Colour.GREEN;
        // RED takes precedence over AMBER / GREEN on overlap.
        if (topic.contains("blocked")) return Colour.RED;
        if (topic.endsWith(".committed")) return Colour.RED;
        if (topic.contains("warned")) return Colour.AMBER;
        if (topic.contains("backup")) return Colour.AMBER;
        if (topic.contains("warning")) return Colour.AMBER;
        return Colour.GREEN;
    }

    /**
     * Map a {@code safe_mode.*} topic to a plain-English description used
     * in tooltips and the click-out panel. Falls back to the topic when
     * the topic is unknown so a future event still reaches the user.
     */
    public static String describe(String topic) {
        if (topic == null) return "";
        if (topic.equals("safe_mode.queue_storm_blocked")) {
            return "Blocked: agent tried to queue a second macro on the same image while one was paused on a dialog.";
        }
        if (topic.equals("safe_mode.blocked")) {
            return "Blocked: scanner caught a destructive operation in the macro.";
        }
        if (topic.equals("safe_mode.snapshot.committed")) {
            return "Macro failed; pre-run snapshot kept so you can rewind.";
        }
        if (topic.equals("safe_mode.snapshot.released")) {
            return "Macro succeeded; pre-run snapshot dropped.";
        }
        if (topic.equals("safe_mode.calibration_warning")) {
            return "Warned: macro would reset image calibration to 1 px/unit.";
        }
        if (topic.equals("safe_mode.roi_auto_backup")) {
            return "Backed up live ROIs to AI_Exports/ before the agent reset them.";
        }
        if (topic.equals("safe_mode.warned")) {
            return "Warned: scanner flagged a non-blocking concern.";
        }
        if (topic.equals("safe_mode.passed")) {
            return "Macro passed safe-mode checks.";
        }
        return topic;
    }

    private void fireChange() {
        // Snapshot to avoid concurrent-modification on dispatch and so the
        // notify path holds neither this nor the listeners lock — UI
        // listeners re-enter the EDT.
        Listener[] snapshot;
        synchronized (listeners) {
            snapshot = listeners.toArray(new Listener[0]);
        }
        for (Listener l : snapshot) {
            try { l.onChange(this); } catch (Throwable ignore) {}
        }
    }

    /** Test helper: clear all state. Not used in production. */
    public synchronized void resetForTest() {
        recent.clear();
        colour = Colour.GREEN;
        lastEventTs = 0L;
        masterEnabled = true;
    }

    /** Most recent event topic — convenience for the status-bar tooltip. */
    public synchronized String lastTopic() {
        Iterator<Event> it = recent.iterator();
        return it.hasNext() ? it.next().topic : "";
    }
}
