package imagejai.ui;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;

/** A macro item whose secondary click opens actions instead of running it. */
final class MacroMenuItem extends JMenuItem {
    private final JPopupMenu contextMenu;
    private final Component contextAnchor;

    MacroMenuItem(String text, JPopupMenu contextMenu, Component contextAnchor) {
        super(text);
        this.contextMenu = contextMenu;
        this.contextAnchor = contextAnchor;
    }

    @Override
    protected void processMouseEvent(MouseEvent event) {
        if (!showContextMenu(event)) {
            super.processMouseEvent(event);
        }
    }

    @Override
    public void processMouseEvent(MouseEvent event, MenuElement[] path,
                                  MenuSelectionManager manager) {
        if (!showContextMenu(event)) {
            super.processMouseEvent(event, path, manager);
        }
    }

    private boolean showContextMenu(MouseEvent event) {
        if (!SwingUtilities.isRightMouseButton(event) && !event.isPopupTrigger()) {
            return false;
        }
        boolean showNow = event.isPopupTrigger()
                || (SwingUtilities.isRightMouseButton(event)
                && event.getID() == MouseEvent.MOUSE_RELEASED);
        if (showNow && !contextMenu.isVisible()) {
            Point location = event.getLocationOnScreen();
            MenuSelectionManager.defaultManager().clearSelectedPath();
            Point anchorLocation = contextAnchor.getLocationOnScreen();
            location.translate(-anchorLocation.x, -anchorLocation.y);
            contextMenu.show(contextAnchor, location.x, location.y);
        }
        event.consume();
        return true;
    }
}
