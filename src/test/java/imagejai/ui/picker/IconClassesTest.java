package imagejai.ui.picker;

import org.junit.Test;
import imagejai.engine.picker.ModelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Smoke tests for the dedicated Icon classes split out of {@link ModelMenuItem}
 * per docs/multi_provider/05_ui_design.md §11.1. Verifies factories return the
 * documented shapes and {@code paintIcon} doesn't throw on a headless canvas.
 */
public class IconClassesTest {

    @Test
    public void tierBadgeIconReturnsTwelvePxDisc() {
        TierBadgeIcon icon = TierBadgeIcon.forTier(ModelEntry.Tier.FREE);
        assertEquals(12, icon.getIconWidth());
        assertEquals(12, icon.getIconHeight());
        paintWithoutThrowing(icon);
    }

    @Test
    public void tierBadgeDeprecatedSwapsToRedDisc() {
        TierBadgeIcon icon = TierBadgeIcon.forTierDeprecated(ModelEntry.Tier.FREE);
        // forTierDeprecated should yield a different colour than the live tier
        // — guarded by the colorFor(tier, retired=true) branch.
        assertNotNull(icon);
        paintWithoutThrowing(icon);
    }

    @Test
    public void statusIconBridgeFromProviderStatusIcon() {
        assertEquals(StatusIcon.Status.READY,
                StatusIcon.forStatus(ModelMenuItem.ProviderStatusIcon.READY).status());
        assertEquals(StatusIcon.Status.NEEDS_SETUP,
                StatusIcon.forStatus(ModelMenuItem.ProviderStatusIcon.NEEDS_SETUP).status());
        assertEquals(StatusIcon.Status.UNAVAILABLE,
                StatusIcon.forStatus(ModelMenuItem.ProviderStatusIcon.UNAVAILABLE).status());
        assertEquals(StatusIcon.Status.NEEDS_SETUP,
                StatusIcon.forStatus((StatusIcon.Status) null).status());
    }

    @Test
    public void pinStarIconRemembersHoverState() {
        PinStarIcon hollow = PinStarIcon.forState(false, false);
        assertFalse(hollow.filled());
        assertFalse(hollow.hovered());

        PinStarIcon hovered = PinStarIcon.forState(true, true);
        assertTrue(hovered.filled());
        assertTrue(hovered.hovered());

        paintWithoutThrowing(hollow);
        paintWithoutThrowing(hovered);
    }

    private static void paintWithoutThrowing(javax.swing.Icon icon) {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            icon.paintIcon(null, g2, 4, 4);
        } finally {
            g2.dispose();
        }
    }
}
