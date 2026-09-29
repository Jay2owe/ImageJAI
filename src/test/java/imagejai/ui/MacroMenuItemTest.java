package imagejai.ui;

import org.junit.Test;

import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MacroMenuItemTest {
    @Test
    public void secondaryClickOpensActionsWithoutRunningMacro() {
        RecordingPopup popup = new RecordingPopup();
        Component anchor = new JPanel() {
            @Override
            public Point getLocationOnScreen() {
                return new Point(100, 200);
            }
        };
        MacroMenuItem item = new MacroMenuItem("Count cells", popup, anchor);
        AtomicInteger runCount = new AtomicInteger();
        item.addActionListener(e -> runCount.incrementAndGet());

        MouseEvent press = rightMouseEvent(item, MouseEvent.MOUSE_PRESSED,
                InputEvent.BUTTON3_DOWN_MASK, false);
        item.processMouseEvent(press, new MenuElement[] { item },
                MenuSelectionManager.defaultManager());
        MouseEvent release = rightMouseEvent(item, MouseEvent.MOUSE_RELEASED, 0, true);
        item.processMouseEvent(release, new MenuElement[] { item },
                MenuSelectionManager.defaultManager());

        assertTrue(press.isConsumed());
        assertTrue(release.isConsumed());
        assertEquals(1, popup.showCount);
        assertEquals(3, popup.x);
        assertEquals(4, popup.y);
        assertEquals(0, runCount.get());

        item.doClick();
        assertEquals(1, runCount.get());
    }

    private static MouseEvent rightMouseEvent(Component source, int id, int modifiers,
                                               boolean popupTrigger) {
        return new MouseEvent(source, id, System.currentTimeMillis(), modifiers,
                3, 4, 103, 204, 1, popupTrigger, MouseEvent.BUTTON3);
    }

    private static final class RecordingPopup extends JPopupMenu {
        int showCount;
        int x;
        int y;

        @Override
        public void show(Component invoker, int x, int y) {
            showCount++;
            this.x = x;
            this.y = y;
        }
    }
}
