package imagejai.ui.picker;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Configuration-status glyph next to the tier badge per
 * docs/multi_provider/05_ui_design.md §11.1: ✓ ready, ⚠ needs setup, ✗ unavailable.
 *
 * <p>Reused by {@link ModelMenuItem} and {@link ProviderMenu} so menu rows and
 * provider headers stay visually coherent.
 */
public final class StatusIcon implements Icon {

    public enum Status { READY, NEEDS_SETUP, UNAVAILABLE }

    private static final int SIZE = 14;

    private final Status status;

    private StatusIcon(Status status) {
        this.status = status;
    }

    public static StatusIcon forStatus(Status status) {
        return new StatusIcon(status == null ? Status.NEEDS_SETUP : status);
    }

    /** Bridge for {@link ModelMenuItem.ProviderStatusIcon}. */
    public static StatusIcon forStatus(ModelMenuItem.ProviderStatusIcon icon) {
        if (icon == null) return new StatusIcon(Status.NEEDS_SETUP);
        switch (icon) {
            case READY:       return new StatusIcon(Status.READY);
            case UNAVAILABLE: return new StatusIcon(Status.UNAVAILABLE);
            case NEEDS_SETUP:
            default:          return new StatusIcon(Status.NEEDS_SETUP);
        }
    }

    public Status status() { return status; }

    @Override
    public int getIconWidth() { return SIZE; }

    @Override
    public int getIconHeight() { return SIZE; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String glyph;
            switch (status) {
                case READY:
                    g2.setColor(new Color(60, 160, 90));
                    glyph = "✓";
                    break;
                case UNAVAILABLE:
                    g2.setColor(new Color(200, 60, 60));
                    glyph = "✗";
                    break;
                case NEEDS_SETUP:
                default:
                    g2.setColor(new Color(200, 150, 30));
                    glyph = "⚠";
                    break;
            }
            Font font = c == null ? new Font(Font.SANS_SERIF, Font.BOLD, 12) : c.getFont();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int baseline = y + (SIZE + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(glyph, x, baseline);
        } finally {
            g2.dispose();
        }
    }
}
