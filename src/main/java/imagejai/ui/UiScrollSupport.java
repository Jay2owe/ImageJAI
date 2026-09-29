package imagejai.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;

/**
 * Shared small-screen scroll behaviour for ImageJAI Swing surfaces.
 */
public final class UiScrollSupport {
    private static final int SCROLL_INCREMENT = 16;
    private static final int SCREEN_MARGIN = 24;

    private UiScrollSupport() {
    }

    /**
     * Wrap a complete UI or section so every edge remains reachable when its
     * host window is smaller than the component's preferred size.
     */
    public static JScrollPane wrap(JComponent content, String accessibleName) {
        return wrap(content, accessibleName,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    public static JScrollPane wrap(JComponent content, String accessibleName,
                                   int verticalPolicy, int horizontalPolicy) {
        if (content == null) {
            throw new IllegalArgumentException("Scrollable content is required.");
        }
        JScrollPane scroll = new JScrollPane(content, verticalPolicy, horizontalPolicy);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setMinimumSize(new Dimension(0, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(SCROLL_INCREMENT);
        scroll.getHorizontalScrollBar().setUnitIncrement(SCROLL_INCREMENT);
        scroll.getVerticalScrollBar().setBlockIncrement(SCROLL_INCREMENT * 5);
        scroll.getHorizontalScrollBar().setBlockIncrement(SCROLL_INCREMENT * 5);
        if (accessibleName != null && !accessibleName.trim().isEmpty()) {
            scroll.getAccessibleContext().setAccessibleName(accessibleName);
            scroll.getAccessibleContext().setAccessibleDescription(
                    accessibleName + " is scrollable when it does not fit on screen.");
        }
        return scroll;
    }

    /**
     * Keep an expandable section from consuming the whole host while retaining
     * access to all of its children through a vertical scrollbar.
     */
    public static JScrollPane cappedSection(JComponent content, String accessibleName,
                                            int maximumPreferredHeight) {
        JScrollPane scroll = wrap(content, accessibleName);
        Dimension preferred = scroll.getPreferredSize();
        int height = Math.max(1, Math.min(preferred.height, maximumPreferredHeight));
        scroll.setPreferredSize(new Dimension(preferred.width, height));
        return scroll;
    }

    /**
     * Cap a packed dialog to the usable monitor bounds. Its internal scroll
     * panes then expose content which cannot fit at that size.
     */
    public static void fitToScreen(Window window) {
        if (window == null || GraphicsEnvironment.isHeadless()) {
            return;
        }
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        Rectangle bounds = configuration == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
                : configuration.getBounds();
        int maxWidth = Math.max(240, bounds.width - (2 * SCREEN_MARGIN));
        int maxHeight = Math.max(200, bounds.height - (2 * SCREEN_MARGIN));
        Dimension current = window.getSize();
        window.setSize(Math.min(current.width, maxWidth), Math.min(current.height, maxHeight));
    }
}
