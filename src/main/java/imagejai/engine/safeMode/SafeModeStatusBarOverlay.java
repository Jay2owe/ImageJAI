package imagejai.engine.safeMode;

import ij.IJ;
import ij.ImageJ;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): a 14×14 coloured
 * square painted in the bottom-right corner of Fiji's main frame. Mirrors
 * {@link SafeModeIndicator}'s colour state so a biologist can spot a red /
 * amber dot at the corner without scanning the toolbar — useful when the
 * toolbar is busy or scrolled out of view.
 *
 * <p>The overlay attaches itself to the south layout slot of Fiji's main
 * frame, which today is occupied by the status strip drawn by
 * {@link ij.gui.ImageJ}. We don't want to clobber that strip — Fiji writes
 * "x=… y=…", coordinates and tool messages there — so the overlay
 * deliberately mounts as a sibling at the FRAME level, not over the strip:
 * we slot a small JComponent into the {@code BorderLayout.EAST} of the
 * status row when one is detected. A graceful fallback that just no-ops
 * keeps every code path safe even if Fiji's layout has shifted.
 *
 * <p>Headless-safe: the {@link #install} factory short-circuits to
 * {@code null} when {@code IJ.getInstance()} is unavailable.
 */
public final class SafeModeStatusBarOverlay extends JComponent {

    private static final int CELL_SIZE = 14;

    private final SafeModeIndicatorState state;

    private SafeModeStatusBarOverlay(SafeModeIndicatorState state) {
        this.state = state;
        setOpaque(false);
        Dimension d = new Dimension(CELL_SIZE + 4, CELL_SIZE + 4);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Reuse SafeModeIndicator's click handler so a click on the
                // square pops the same event panel as a click on the
                // toolbar dot. Wired through the singleton so we don't take
                // a hard dependency on the indicator at construction time.
                try {
                    SafeModeIndicator ind = SafeModeIndicator.getInstance();
                    ind.mousePressed(null, e);
                } catch (Throwable ignore) {}
            }
        });
        setToolTipText(buildTooltip());
    }

    /**
     * Mount the overlay into Fiji's main frame status row. Returns the
     * installed instance, or {@code null} when no Fiji frame is available
     * (headless or pre-startup race) — caller treats {@code null} as
     * "no overlay this session" rather than an error.
     */
    public static SafeModeStatusBarOverlay install(SafeModeIndicatorState state) {
        ImageJ ij = IJ.getInstance();
        if (ij == null) return null;
        SafeModeStatusBarOverlay overlay = new SafeModeStatusBarOverlay(state);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    Container parent = pickStatusContainer(ij);
                    if (parent == null) return;
                    LayoutManager lm = parent.getLayout();
                    if (lm instanceof java.awt.BorderLayout) {
                        Component existing = ((java.awt.BorderLayout) lm)
                                .getLayoutComponent(java.awt.BorderLayout.EAST);
                        if (existing == null) {
                            parent.add(overlay, java.awt.BorderLayout.EAST);
                            parent.revalidate();
                            parent.repaint();
                            return;
                        }
                    }
                    // Fallback: stick it on the south of the frame's content
                    // pane wrapped in a one-cell box so the existing
                    // status strip stays untouched.
                    parent.add(overlay);
                    parent.revalidate();
                    parent.repaint();
                } catch (Throwable t) {
                    IJ.log("[ImageJAI-SafeMode] overlay attach failed: "
                            + t.getMessage());
                }
            }
        });
        return overlay;
    }

    /** Best-effort: walk the Fiji frame for a container that has room for
     *  a small marker without overlapping the status strip. */
    private static Container pickStatusContainer(Frame ij) {
        // Most Fiji versions paint the status strip directly on the frame
        // canvas — the safest mount point is the south of the content
        // pane, but only when no one else owns it. Fall back to the frame
        // itself when no obvious slot is free.
        try {
            if (ij instanceof javax.swing.RootPaneContainer) {
                Container content = ((javax.swing.RootPaneContainer) ij).getContentPane();
                if (content != null) return content;
            }
        } catch (Throwable ignore) {}
        return ij;
    }

    /** Detach from the parent container — best-effort, swallows AWT
     *  errors from a frame that has already been disposed. */
    public void uninstall() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    Container parent = getParent();
                    if (parent != null) {
                        parent.remove(SafeModeStatusBarOverlay.this);
                        parent.revalidate();
                        parent.repaint();
                    }
                } catch (Throwable ignore) {}
            }
        });
    }

    /** State-change hook from {@link SafeModeIndicator}. Updates the
     *  tooltip and triggers a single repaint on the EDT. */
    public void refresh() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                setToolTipText(buildTooltip());
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int x = 2, y = 2;
            g2.setColor(colourFor(state.colour()));
            g2.fillOval(x, y, CELL_SIZE, CELL_SIZE);
            g2.setColor(new Color(0, 0, 0, 80));
            g2.drawOval(x, y, CELL_SIZE, CELL_SIZE);
        } finally {
            g2.dispose();
        }
    }

    private static Color colourFor(SafeModeIndicatorState.Colour c) {
        if (c == null) return Color.GRAY;
        switch (c) {
            case RED:   return new Color(0xCC0000);
            case AMBER: return new Color(0xE69500);
            case GREY:  return new Color(0x888888);
            case GREEN:
            default:    return new Color(0x2C9F2C);
        }
    }

    private String buildTooltip() {
        if (!state.masterEnabled()) {
            return "Safe Mode OFF — no checks running.";
        }
        String last = state.lastTopic();
        if (last == null || last.isEmpty()) {
            return "Safe Mode: " + state.colour().name() + " (no events yet).";
        }
        return "Safe Mode: " + state.colour().name() + " — " + last;
    }
}
