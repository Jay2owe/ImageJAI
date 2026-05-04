package imagejai.engine.safeMode;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.gui.Toolbar;
import ij.plugin.tool.PlugInTool;
import imagejai.engine.EventBus;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): the toolbar tool
 * that paints the safe-mode status dot on Fiji's main toolbar. Subscribes
 * to {@code safe_mode.*} on {@link EventBus}, drives the underlying
 * {@link SafeModeIndicatorState} state machine, and forwards every state
 * change to the toolbar (repaint) and the status-bar overlay.
 *
 * <p>Lifecycle: {@link #installOnStartup()} is idempotent and headless-safe.
 * Production calls it once from
 * {@code ImageJAIPlugin.run()} after the chat frame is built. Tests can
 * skip it and drive {@link #state()} directly via the EventBus subscription
 * — installation and event handling are independent so unit tests can run
 * without Swing.
 *
 * <p>Click on the toolbar circle opens a {@link SafeModeEventPanel} listing
 * the last three events. The panel is a non-modal {@code JFrame} so the
 * user can leave it open while working; it re-attaches itself to fresh
 * state on every click.
 *
 * <p>Decay: a daemon scheduler invokes {@link SafeModeIndicatorState#applyDecay}
 * once per second. The state class clamps to a 60-second window so AMBER /
 * RED only flips back to GREEN after a quiet stretch — the recent-events
 * deque is preserved so the click-out panel still shows what tripped the
 * gate even after the dot reverts.
 */
public final class SafeModeIndicator extends PlugInTool implements EventBus.Listener {

    private static final SafeModeIndicator INSTANCE = new SafeModeIndicator();

    /** Singleton accessor — the plugin only ever installs one indicator. */
    public static SafeModeIndicator getInstance() {
        return INSTANCE;
    }

    private final SafeModeIndicatorState state = new SafeModeIndicatorState();
    private final AtomicBoolean installed = new AtomicBoolean(false);

    private SafeModeStatusBarOverlay overlay;
    private SafeModeEventPanel eventPanel;
    private ScheduledExecutorService decayScheduler;
    private ScheduledFuture<?> decayFuture;

    private final SafeModeIndicatorState.Listener stateListener =
            new SafeModeIndicatorState.Listener() {
                @Override
                public void onChange(SafeModeIndicatorState s) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() { repaintAll(); }
                    });
                }
            };

    public SafeModeIndicator() {
        // PlugInTool requires a public no-arg constructor; ImageJ never
        // instantiates this class directly (we hand-deliver the singleton
        // via Toolbar.addPlugInTool) but keeping the contract intact means
        // a future macro that addresses the tool by name still works.
    }

    /** Read-only access to the underlying state machine — exposed so unit
     *  tests can verify colour transitions without touching Swing. */
    public SafeModeIndicatorState state() {
        return state;
    }

    /**
     * Subscribe to the EventBus, attach the toolbar tool, mount the
     * status-bar overlay, and start the 1-second decay timer. Idempotent
     * across re-entry. Early-returns the UI install when running headless
     * (no GraphicsEnvironment) so a CLI driver that exercises macros never
     * tries to repaint a non-existent toolbar.
     */
    public void installOnStartup() {
        if (!installed.compareAndSet(false, true)) return;
        if (GraphicsEnvironment.isHeadless()) {
            // Subscribe even in headless so tests calling installOnStartup
            // get the bus wiring. Skip any UI install.
            EventBus.getInstance().subscribe("safe_mode.*", this);
            state.addListener(stateListener);
            return;
        }
        EventBus.getInstance().subscribe("safe_mode.*", this);
        state.addListener(stateListener);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    Toolbar.addPlugInTool(SafeModeIndicator.this);
                } catch (Throwable t) {
                    IJ.log("[ImageJAI-SafeMode] Toolbar.addPlugInTool failed: "
                            + t.getMessage());
                }
                try {
                    overlay = SafeModeStatusBarOverlay.install(state);
                } catch (Throwable t) {
                    IJ.log("[ImageJAI-SafeMode] status-bar overlay install failed: "
                            + t.getMessage());
                }
            }
        });
        decayScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ImageJAI-SafeMode-Decay");
                t.setDaemon(true);
                return t;
            }
        });
        decayFuture = decayScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try { state.applyDecay(System.currentTimeMillis()); }
                catch (Throwable ignore) {}
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Tear down: drop the EventBus subscription, cancel the decay timer,
     * and detach the status-bar overlay. The toolbar tool itself stays
     * registered (Fiji has no remove API) but with no live state listener
     * its icon stops updating.
     */
    public void uninstall() {
        if (!installed.compareAndSet(true, false)) return;
        try { EventBus.getInstance().unsubscribe(this); } catch (Throwable ignore) {}
        try { state.removeListener(stateListener); } catch (Throwable ignore) {}
        if (decayFuture != null) {
            try { decayFuture.cancel(true); } catch (Throwable ignore) {}
            decayFuture = null;
        }
        if (decayScheduler != null) {
            try { decayScheduler.shutdownNow(); } catch (Throwable ignore) {}
            decayScheduler = null;
        }
        if (overlay != null) {
            try { overlay.uninstall(); } catch (Throwable ignore) {}
            overlay = null;
        }
        if (eventPanel != null) {
            try { eventPanel.dispose(); } catch (Throwable ignore) {}
            eventPanel = null;
        }
    }

    /**
     * Wire the Stage-02 master safe-mode toggle to the indicator. AiRootPanel
     * calls this from the Safe-Mode checkbox listener so flipping the box
     * paints the dot grey (off) or restores the live colour (on).
     */
    public void setMasterEnabled(boolean enabled) {
        state.setMasterEnabled(enabled);
    }

    @Override
    public void onEvent(JsonObject frame) {
        if (frame == null) return;
        try {
            JsonElement evEl = frame.get("event");
            if (evEl == null || !evEl.isJsonPrimitive()) return;
            String topic = evEl.getAsString();
            if (topic == null || !topic.startsWith("safe_mode.")) return;
            long ts;
            try {
                JsonElement tsEl = frame.get("ts");
                ts = tsEl != null && tsEl.isJsonPrimitive()
                        ? tsEl.getAsLong()
                        : System.currentTimeMillis();
            } catch (Throwable ignore) {
                ts = System.currentTimeMillis();
            }
            String desc = SafeModeIndicatorState.describe(topic);
            // Append a one-line target hint when the publisher carried one,
            // so the click-out panel reads "Blocked: scanner caught …
            // (target=Image-1.tif)".
            try {
                JsonElement dataEl = frame.get("data");
                if (dataEl != null && dataEl.isJsonObject()) {
                    JsonObject data = dataEl.getAsJsonObject();
                    JsonElement t = data.get("target");
                    if (t != null && t.isJsonPrimitive()) {
                        desc = desc + " (target=" + t.getAsString() + ")";
                    } else {
                        JsonElement ti = data.get("target_image");
                        if (ti != null && ti.isJsonPrimitive()) {
                            desc = desc + " (image=" + ti.getAsString() + ")";
                        }
                    }
                }
            } catch (Throwable ignore) {}
            state.recordEvent(topic, ts, desc);
        } catch (Throwable ignore) {
            // Subscriber faults must never fault the publisher path.
        }
    }

    private void repaintAll() {
        try {
            Toolbar tb = Toolbar.getInstance();
            if (tb != null) tb.repaint();
        } catch (Throwable ignore) {}
        if (overlay != null) {
            try { overlay.refresh(); } catch (Throwable ignore) {}
        }
        if (eventPanel != null) {
            try { eventPanel.refresh(); } catch (Throwable ignore) {}
        }
    }

    @Override
    public String getToolName() {
        // Tooltip the toolbar shows on hover. Carries the live colour and
        // last topic so the biologist can read the state without clicking.
        SafeModeIndicatorState.Colour c = state.colour();
        if (!state.masterEnabled()) {
            return "ImageJAI Safe Mode: OFF — no checks running.";
        }
        String last = state.lastTopic();
        if (last == null || last.isEmpty()) {
            return "ImageJAI Safe Mode: " + c.name() + " — no events yet.";
        }
        return "ImageJAI Safe Mode: " + c.name() + " — " + last;
    }

    /**
     * Toolbar icon DSL (see {@code ij.gui.Toolbar.drawIcon}): {@code C<rgb>}
     * sets pen colour with each digit a hex nibble (0..f); {@code V<x><y><w><h>}
     * paints a filled oval at offset (x,y) with diameter (w,h) in single-hex
     * units. {@code V33aa} is a 10×10 dot starting at (3,3) inside the
     * 21×21 toolbar cell — leaves the cell border for the outline-on-hover
     * effect Fiji's toolbar already paints.
     */
    @Override
    public String getToolIcon() {
        SafeModeIndicatorState.Colour c = state.colour();
        String hex;
        switch (c) {
            case RED:   hex = "f00"; break;
            case AMBER: hex = "fa0"; break;
            case GREY:  hex = "888"; break;
            case GREEN:
            default:    hex = "0a0"; break;
        }
        return "C" + hex + "V33aa";
    }

    @Override
    public void mousePressed(ij.ImagePlus imp, MouseEvent e) {
        // Open / refocus the click-out panel listing the last three events.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    if (eventPanel == null || !eventPanel.isAlive()) {
                        eventPanel = new SafeModeEventPanel(state);
                    }
                    eventPanel.showAtMouse(e);
                } catch (Throwable t) {
                    IJ.log("[ImageJAI-SafeMode] event-panel open failed: "
                            + t.getMessage());
                }
            }
        });
    }

    /** Test-only: rebuild internal state. Not used in production. */
    public void resetForTest() {
        state.resetForTest();
    }
}
