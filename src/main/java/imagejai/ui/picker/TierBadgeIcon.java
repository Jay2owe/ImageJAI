package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 12px filled disc representing a model's tier per docs/multi_provider/05_ui_design.md §11.1
 * and the colour mapping in docs/multi_provider/06_tier_safety.md §1.1.
 *
 * <p>Reused by {@link ModelMenuItem} (cell column), {@link HoverCard} (header
 * row) and {@link FirstUseDialog} (paid-confirm headline).
 */
public final class TierBadgeIcon implements Icon {

    private static final int SIZE = 12;

    private final Color fill;

    private TierBadgeIcon(Color fill) {
        this.fill = fill;
    }

    public static TierBadgeIcon forTier(ModelEntry.Tier tier) {
        return new TierBadgeIcon(colorFor(tier, false));
    }

    /** Pinned-deprecated rows swap to the red {@code RETIRED} dot per 02 §3. */
    public static TierBadgeIcon forTierDeprecated(ModelEntry.Tier tier) {
        return new TierBadgeIcon(colorFor(tier, true));
    }

    static Color colorFor(ModelEntry.Tier tier, boolean retired) {
        if (retired) {
            return new Color(200, 60, 60);
        }
        if (tier == null) {
            return new Color(200, 200, 205);
        }
        switch (tier) {
            case FREE:                  return new Color(60, 180, 75);
            case FREE_WITH_LIMITS:      return new Color(240, 200, 50);
            case PAID:                  return new Color(70, 130, 220);
            case REQUIRES_SUBSCRIPTION: return new Color(160, 95, 215);
            case UNCURATED:
            default:                    return new Color(200, 200, 205);
        }
    }

    @Override
    public int getIconWidth() { return SIZE; }

    @Override
    public int getIconHeight() { return SIZE; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillOval(x, y, SIZE, SIZE);
            g2.setColor(new Color(60, 60, 70));
            g2.drawOval(x, y, SIZE, SIZE);
        } finally {
            g2.dispose();
        }
    }
}
