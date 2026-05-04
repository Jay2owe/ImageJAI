package imagejai.engine.safeMode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): a non-modal
 * floating frame that lists the last three safe-mode events from
 * {@link SafeModeIndicatorState#recentEvents()}. Pops from the click on
 * either the toolbar dot or the status-bar overlay.
 *
 * <p>The panel is intentionally lightweight — no menu, no buttons, no
 * scroll panes — because the click-to-open / close-and-forget interaction
 * matches a tooltip more than it does a real dashboard. The full safety
 * dashboard is a Tier-2 idea (see Stage 07 §Out of scope).
 *
 * <p>The frame attaches itself to the indicator state as a listener so it
 * refreshes in place when a new event arrives while it is open. The user
 * can also dismiss it by clicking elsewhere or hitting Escape; it gets
 * recreated on the next click.
 */
public final class SafeModeEventPanel {

    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("HH:mm:ss");
    private static final int WIDTH = 440;
    private static final int HEIGHT = 200;

    private final SafeModeIndicatorState state;
    private final JFrame frame;
    private final JPanel rows;

    private final SafeModeIndicatorState.Listener listener =
            new SafeModeIndicatorState.Listener() {
                @Override
                public void onChange(SafeModeIndicatorState s) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() { rebuild(); }
                    });
                }
            };

    public SafeModeEventPanel(SafeModeIndicatorState state) {
        this.state = state;
        this.frame = new JFrame("Safe Mode — recent events");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(WIDTH, HEIGHT);
        frame.setAlwaysOnTop(true);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        root.setBackground(new Color(0xF5F5F5));

        JLabel header = new JLabel("Last 3 safe-mode events");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 13f));
        root.add(header, BorderLayout.NORTH);

        rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        root.add(rows, BorderLayout.CENTER);

        frame.setContentPane(root);
        rebuild();

        state.addListener(listener);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                state.removeListener(listener);
            }
        });
    }

    /** Position next to the click that triggered the open and bring the
     *  frame to the front. Idempotent — clicking the toolbar dot again
     *  re-shows an already-visible panel rather than spawning a new one. */
    public void showAtMouse(MouseEvent e) {
        Point screen = mouseScreenLocation(e);
        if (screen != null) {
            frame.setLocation(screen.x + 12, screen.y + 12);
        }
        if (!frame.isVisible()) {
            frame.setVisible(true);
        } else {
            frame.toFront();
            frame.requestFocus();
        }
    }

    /** Convenience for the test path / wrappers — true while the JFrame
     *  exists and has not been disposed. */
    public boolean isAlive() {
        return frame.isDisplayable();
    }

    /** State-change hook from {@link SafeModeIndicator}. */
    public void refresh() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() { rebuild(); }
        });
    }

    public void dispose() {
        try { state.removeListener(listener); } catch (Throwable ignore) {}
        try { frame.dispose(); } catch (Throwable ignore) {}
    }

    private void rebuild() {
        rows.removeAll();
        List<SafeModeIndicatorState.Event> recent = state.recentEvents();
        if (recent.isEmpty()) {
            JLabel empty = new JLabel("No safe-mode events yet.");
            empty.setForeground(new Color(0x666666));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            rows.add(empty);
        } else {
            for (SafeModeIndicatorState.Event ev : recent) {
                rows.add(buildRow(ev));
                rows.add(Box.createVerticalStrut(6));
            }
        }
        rows.revalidate();
        rows.repaint();
    }

    private JComponent buildRow(SafeModeIndicatorState.Event ev) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(true);
        row.setBackground(Color.WHITE);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xDDDDDD)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                row.getPreferredSize().height + 24));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel ts = new JLabel(formatTs(ev.ts));
        ts.setFont(ts.getFont().deriveFont(Font.PLAIN, 11f));
        ts.setForeground(new Color(0x888888));
        ts.setPreferredSize(new Dimension(70, 16));
        row.add(ts, BorderLayout.WEST);

        String body = ev.description != null && !ev.description.isEmpty()
                ? ev.description
                : ev.topic;
        JLabel desc = new JLabel("<html><body style='width:300px'>" + escapeHtml(body)
                + "</body></html>");
        desc.setFont(desc.getFont().deriveFont(Font.PLAIN, 12f));
        SafeModeIndicatorState.Colour c = SafeModeIndicatorState.colourFor(ev.topic);
        desc.setForeground(textFor(c));
        row.add(desc, BorderLayout.CENTER);

        return row;
    }

    private static Color textFor(SafeModeIndicatorState.Colour c) {
        if (c == null) return Color.BLACK;
        switch (c) {
            case RED:   return new Color(0xB00000);
            case AMBER: return new Color(0xA86A00);
            case GREEN: return new Color(0x1F7A1F);
            case GREY:
            default:    return new Color(0x444444);
        }
    }

    private static String formatTs(long ts) {
        if (ts <= 0L) return "--:--:--";
        synchronized (TS_FMT) {
            return TS_FMT.format(new Date(ts));
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static Point mouseScreenLocation(MouseEvent e) {
        if (e == null) return null;
        try {
            Component src = e.getComponent();
            if (src == null) return e.getLocationOnScreen();
            Point p = new Point(e.getX(), e.getY());
            SwingUtilities.convertPointToScreen(p, src);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }
}
