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
 * Pin-star glyph per docs/multi_provider/05_ui_design.md §11.1: filled star
 * (pinned) or hollow outline (not pinned), with hover brightening.
 */
public final class PinStarIcon implements Icon {

    private static final int SIZE = 14;

    private final boolean filled;
    private final boolean hovered;

    private PinStarIcon(boolean filled, boolean hovered) {
        this.filled = filled;
        this.hovered = hovered;
    }

    public static PinStarIcon forState(boolean filled, boolean hovered) {
        return new PinStarIcon(filled, hovered);
    }

    public boolean filled() { return filled; }
    public boolean hovered() { return hovered; }

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
            Color base = filled ? new Color(220, 175, 30) : new Color(150, 150, 160);
            if (hovered) {
                base = base.brighter();
            }
            g2.setColor(base);
            Font font = c == null ? new Font(Font.SANS_SERIF, Font.BOLD, 12) : c.getFont();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int baseline = y + (SIZE + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(filled ? "★" : "☆", x, baseline);
        } finally {
            g2.dispose();
        }
    }
}
