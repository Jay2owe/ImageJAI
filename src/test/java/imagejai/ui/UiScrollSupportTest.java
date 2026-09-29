package imagejai.ui;

import imagejai.config.Settings;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class UiScrollSupportTest {

    @Test
    public void wrapMakesOversizedSectionScrollableInBothDirections() {
        JPanel content = new JPanel();
        content.setPreferredSize(new Dimension(900, 700));

        JScrollPane scroll = UiScrollSupport.wrap(content, "Oversized test section");

        assertSame(content, scroll.getViewport().getView());
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                scroll.getVerticalScrollBarPolicy());
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                scroll.getHorizontalScrollBarPolicy());
        assertEquals(16, scroll.getVerticalScrollBar().getUnitIncrement());
        assertEquals(16, scroll.getHorizontalScrollBar().getUnitIncrement());
        assertEquals("Oversized test section",
                scroll.getAccessibleContext().getAccessibleName());
        assertEquals(new Dimension(0, 0), scroll.getMinimumSize());
    }

    @Test
    public void cappedSectionLimitsPreferredHeightWithoutDroppingContent() {
        JPanel content = new JPanel();
        content.setPreferredSize(new Dimension(500, 600));

        JScrollPane scroll = UiScrollSupport.cappedSection(
                content, "Expandable test section", 120);

        assertSame(content, scroll.getViewport().getView());
        assertTrue(scroll.getPreferredSize().height <= 120);
    }

    @Test
    public void terminalActionRailScrollsAndStillPropagatesFontChanges()
            throws Exception {
        LeftRail rail = new LeftRail(new Settings(), new File("."), null, null);
        Field scrollField = LeftRail.class.getDeclaredField("bodyScroll");
        scrollField.setAccessible(true);
        JScrollPane scroll = (JScrollPane) scrollField.get(rail);
        Field buttonField = LeftRail.class.getDeclaredField("newWipButton");
        buttonField.setAccessible(true);
        JButton button = (JButton) buttonField.get(rail);

        rail.setRailFontSize(17);

        assertEquals("Terminal action rail",
                scroll.getAccessibleContext().getAccessibleName());
        assertEquals(17f, button.getFont().getSize2D(), 0.01f);
    }

    @Test
    public void everySettingsTabUsesAScrollViewportAndDialogRemainsResizable()
            throws Exception {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        final SettingsDialog[] dialog = new SettingsDialog[1];
        Settings settings = new Settings();
        settings.configs.clear();
        Settings.ModelConfig config = new Settings.ModelConfig(
                "local", "ollama", "gemma3");
        settings.configs.add(config);
        settings.activeConfigId = config.id;
        SwingUtilities.invokeAndWait(() ->
                dialog[0] = new SettingsDialog(null, settings));
        try {
            Field tabsField = SettingsDialog.class.getDeclaredField("tabs");
            tabsField.setAccessible(true);
            JTabbedPane tabs = (JTabbedPane) tabsField.get(dialog[0]);

            assertEquals(3, tabs.getTabCount());
            for (int i = 0; i < tabs.getTabCount(); i++) {
                assertTrue(tabs.getTitleAt(i) + " must remain reachable on small screens",
                        tabs.getComponentAt(i) instanceof JScrollPane);
            }
            assertTrue(dialog[0].isResizable());
        } finally {
            SwingUtilities.invokeAndWait(dialog[0]::dispose);
        }
    }
}
