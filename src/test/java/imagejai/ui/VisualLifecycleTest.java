package imagejai.ui;

import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.SoftDeprecationPolicy;
import imagejai.ui.installer.ProviderCard;
import imagejai.ui.picker.BillingFailureDialog;
import imagejai.ui.picker.BudgetCeilingDialog;
import imagejai.ui.picker.FirstUseDialog;
import imagejai.ui.picker.MainNotificationCheck;
import imagejai.ui.picker.ModelMenuItem;
import imagejai.ui.picker.TierChangeBanner;
import org.junit.Test;

import javax.accessibility.AccessibleRole;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/** Contrast, custom-focus, accessible-card, and one-shot dialog lifecycle tests. */
public class VisualLifecycleTest {

    @Test
    public void ownedSurfacesRemainReadableAcrossLightAndDarkPalettes() throws Exception {
        assertPalette(new Color(245, 245, 245), new Color(25, 25, 28),
                new Color(220, 232, 247), new Color(20, 30, 45));
        assertPalette(new Color(35, 35, 40), new Color(235, 235, 240),
                new Color(70, 82, 100), Color.WHITE);
    }

    @Test
    public void customChromeRetainsVisibleFocusAndProviderActionsAreAccessible()
            throws Exception {
        ModelMenuItem item = new ModelMenuItem(model(false), (ignored, pinned) -> { });
        item.setSize(380, 24);
        item.getModel().setArmed(true);
        BufferedImage image = new BufferedImage(380, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        item.paint(graphics);
        graphics.dispose();
        Color selectedBackground = ThemeColors.uiColor(
                "MenuItem.selectionBackground", item.getBackground());
        int focusRgb = ThemeColors.focusColor(selectedBackground).getRGB() & 0x00ffffff;
        int focusPixels = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, 1) & 0x00ffffff) == focusRgb) focusPixels++;
        }
        assertTrue("custom model row must paint a focus ring", focusPixels > 100);

        AtomicInteger statusClicks = new AtomicInteger();
        AtomicInteger actionClicks = new AtomicInteger();
        ProviderCard card = new ProviderCard("demo", "Demo Provider", "Description",
                ProviderCard.Status.NEEDS_SETUP, ProviderCard.CostTier.FREE);
        card.setStatusClickListener((source, status) -> statusClicks.incrementAndGet());
        card.setActionListener(e -> actionClicks.incrementAndGet());
        card.statusButton().doClick();
        card.actionButton().doClick();
        assertEquals(1, statusClicks.get());
        assertEquals(1, actionClicks.get());
        assertFocusButton(card.statusButton());
        assertFocusButton(card.actionButton());
        assertTrue(card.statusButton().getAccessibleContext().getAccessibleName()
                .contains("Demo Provider status"));

        TierChangeBanner banner = banner();
        JButton dismiss = find(banner, JButton.class, "Dismiss");
        assertNotNull(dismiss);
        assertFocusButton(dismiss);
        assertTrue(dismiss.getAccessibleContext().getAccessibleName()
                .startsWith("Dismiss notification:"));
    }

    @Test
    public void everyDialogOutcomeDisposesAndReleasesApplicationListeners() throws Exception {
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            ModelEntry entry = model(true);

            BillingFailureDialog billingSwitch = billing();
            assertDisposedAfterClick(billingSwitch, "Switch model");
            assertEquals(BillingFailureDialog.Result.SWITCH_MODEL,
                    result(billingSwitch, BillingFailureDialog.class));

            BillingFailureDialog billingConsole = billing();
            assertDisposedAfterClick(billingConsole, "Open Demo console");
            assertEquals(BillingFailureDialog.Result.OPEN_CONSOLE,
                    result(billingConsole, BillingFailureDialog.class));

            BillingFailureDialog billingClose = billing();
            assertDisposedAfterClick(billingClose, "Close");
            assertEquals(BillingFailureDialog.Result.CLOSE,
                    result(billingClose, BillingFailureDialog.class));

            BillingFailureDialog billingWindowClose = billing();
            assertDisposedAfterWindowClose(billingWindowClose);
            assertEquals(BillingFailureDialog.Result.CLOSE,
                    result(billingWindowClose, BillingFailureDialog.class));

            BudgetCeilingDialog resume = new BudgetCeilingDialog(null, 1.20, 1.00);
            JSpinner spinner = find(resume, JSpinner.class, null);
            spinner.setValue(3.0);
            assertDisposedAfterClick(resume, "Resume");
            assertEquals(BudgetCeilingDialog.Result.RESUME,
                    result(resume, BudgetCeilingDialog.class));
            assertEquals(3.0, resume.newCeilingUsd(), 0.0);

            BudgetCeilingDialog switchFree = new BudgetCeilingDialog(null, 1.20, 1.00);
            assertDisposedAfterClick(switchFree, "Switch to a free model");
            assertEquals(BudgetCeilingDialog.Result.SWITCH_FREE,
                    result(switchFree, BudgetCeilingDialog.class));

            BudgetCeilingDialog budgetClose = new BudgetCeilingDialog(null, 1.20, 1.00);
            assertDisposedAfterClick(budgetClose, "Close");
            assertEquals(BudgetCeilingDialog.Result.CLOSE,
                    result(budgetClose, BudgetCeilingDialog.class));

            BudgetCeilingDialog budgetWindowClose = new BudgetCeilingDialog(null, 1.20, 1.00);
            assertDisposedAfterWindowClose(budgetWindowClose);

            FirstUseDialog firstContinue = new FirstUseDialog(
                    null, entry, FirstUseDialog.Variant.PAID, "Demo");
            JCheckBox remember = find(firstContinue, JCheckBox.class, null);
            remember.setSelected(true);
            assertDisposedAfterClick(firstContinue, "Continue");
            assertEquals(FirstUseDialog.Result.CONTINUE,
                    result(firstContinue, FirstUseDialog.class));
            assertTrue(firstContinue.dontAskAgainChecked());

            FirstUseDialog firstReject = new FirstUseDialog(
                    null, entry, FirstUseDialog.Variant.PAID, "Demo");
            assertDisposedAfterClick(firstReject, "Use a free model instead");
            assertEquals(FirstUseDialog.Result.PICK_FREE,
                    result(firstReject, FirstUseDialog.class));

            FirstUseDialog firstWindowClose = new FirstUseDialog(
                    null, entry, FirstUseDialog.Variant.PAID, "Demo");
            assertDisposedAfterWindowClose(firstWindowClose);
            assertEquals(FirstUseDialog.Result.PICK_FREE,
                    result(firstWindowClose, FirstUseDialog.class));
        });
    }

    @Test
    public void repeatedDecisionDialogsDoNotGrowDisplayableWindowsOrListeners()
            throws Exception {
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            int baseline = displayableWindowCount();
            for (int i = 0; i < 12; i++) {
                FirstUseDialog dialog = new FirstUseDialog(
                        null, model(true), FirstUseDialog.Variant.PAID, "Demo");
                assertDisposedAfterClick(dialog,
                        i % 2 == 0 ? "Continue" : "Use a free model instead");
            }
            assertEquals(baseline, displayableWindowCount());
        });
    }

    private static void assertPalette(Color panelBackground, Color labelForeground,
                                      Color selectionBackground, Color selectionForeground)
            throws Exception {
        String[] keys = {"Panel.background", "Label.foreground", "MenuItem.background",
                "MenuItem.foreground", "MenuItem.selectionBackground",
                "MenuItem.selectionForeground", "Label.disabledForeground", "Focus.color"};
        Map<String, Object> before = new LinkedHashMap<String, Object>();
        for (String key : keys) before.put(key, UIManager.get(key));
        try {
            UIManager.put("Panel.background", panelBackground);
            UIManager.put("Label.foreground", labelForeground);
            UIManager.put("MenuItem.background", panelBackground);
            UIManager.put("MenuItem.foreground", labelForeground);
            UIManager.put("MenuItem.selectionBackground", selectionBackground);
            UIManager.put("MenuItem.selectionForeground", selectionForeground);
            UIManager.put("Label.disabledForeground", labelForeground);
            UIManager.put("Focus.color", new Color(0, 120, 215));

            ProviderCard card = new ProviderCard("demo", "Demo", "Description",
                    ProviderCard.Status.UNAVAILABLE, ProviderCard.CostTier.PAID);
            assertContrast(card.getBackground(), card.getForeground());
            assertContrast(card.getBackground(), card.statusButton().getForeground());
            for (JLabel label : findAll(card, JLabel.class)) {
                assertContrast(card.getBackground(), label.getForeground());
            }

            TierChangeBanner banner = banner();
            JPanel row = firstOpaquePanel(banner);
            JLabel bannerText = find(row, JLabel.class, null);
            assertContrast(row.getBackground(), bannerText.getForeground());

            PostureBanner posture = new PostureBanner(() -> null);
            JPanel root = posture.rootPanelForTest();
            JLabel title = posture.titleForTest("Privacy Posture");
            assertContrast(root.getBackground(), root.getForeground());
            assertContrast(root.getBackground(), title.getForeground());

            ModelEntry uncurated = model(false);
            ModelMenuItem menuItem = new ModelMenuItem(
                    uncurated, (ignored, pinned) -> { });
            Method colors = ModelMenuItem.class.getDeclaredMethod("textColorFor",
                    ModelEntry.class, SoftDeprecationPolicy.State.class,
                    Color.class, boolean.class);
            colors.setAccessible(true);
            Color normal = (Color) colors.invoke(null, uncurated,
                    SoftDeprecationPolicy.State.ACTIVE, menuItem.getBackground(), false);
            Color selected = (Color) colors.invoke(null, uncurated,
                    SoftDeprecationPolicy.State.ACTIVE, selectionBackground, true);
            assertContrast(menuItem.getBackground(), normal);
            assertContrast(selectionBackground, selected);
        } finally {
            for (Map.Entry<String, Object> entry : before.entrySet()) {
                if (entry.getValue() == null) {
                    UIManager.getDefaults().remove(entry.getKey());
                } else {
                    UIManager.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static TierChangeBanner banner() {
        TierChangeBanner banner = new TierChangeBanner();
        banner.setNotifications(Arrays.asList(new MainNotificationCheck.Notification(
                "demo/model", MainNotificationCheck.Severity.HIGH,
                "Pricing changed", "Review the new provider price before launch.")));
        return banner;
    }

    private static BillingFailureDialog billing() {
        return new BillingFailureDialog(null, "Demo", "payment required",
                URI.create("https://example.invalid/billing"), uri -> true);
    }

    private static ModelEntry model(boolean curated) {
        return new ModelEntry("demo", "vision-1", "Vision 1", "",
                curated ? ModelEntry.Tier.PAID : ModelEntry.Tier.UNCURATED,
                8192, false, ModelEntry.Reliability.HIGH,
                false, curated, "");
    }

    private static void assertDisposedAfterClick(JDialog dialog, String text) {
        assertTrue("packed dialog should own a peer before its decision", dialog.isDisplayable());
        JButton button = find(dialog, JButton.class, text);
        assertNotNull("missing button " + text, button);
        assertTrue(button.getActionListeners().length > 0);
        button.doClick();
        assertReleased(dialog, button);
    }

    private static void assertDisposedAfterWindowClose(JDialog dialog) {
        assertTrue(dialog.isDisplayable());
        dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
        assertFalse(dialog.isDisplayable());
        assertEquals(0, dialog.getWindowListeners().length);
        assertEquals(0, dialog.getContentPane().getComponentCount());
    }

    private static void assertReleased(JDialog dialog, JButton retainedButton) {
        assertFalse(dialog.isDisplayable());
        assertEquals(0, dialog.getWindowListeners().length);
        assertEquals(0, retainedButton.getActionListeners().length);
        assertEquals(0, dialog.getContentPane().getComponentCount());
    }

    private static Object result(Object dialog, Class<?> type) {
        try {
            Field result = type.getDeclaredField("result");
            result.setAccessible(true);
            return result.get(dialog);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int displayableWindowCount() {
        int count = 0;
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isDisplayable()) count++;
        }
        return count;
    }

    private static void assertFocusButton(JButton button) {
        assertTrue(button.isFocusable());
        assertTrue(button.isFocusPainted());
        assertEquals(AccessibleRole.PUSH_BUTTON,
                button.getAccessibleContext().getAccessibleRole());
        assertNotNull(button.getAccessibleContext().getAccessibleName());
    }

    private static void assertContrast(Color background, Color foreground) {
        assertTrue("contrast=" + ThemeColors.contrastRatio(background, foreground)
                        + " bg=" + background + " fg=" + foreground,
                ThemeColors.contrastRatio(background, foreground) >= 4.5);
    }

    private static JPanel firstOpaquePanel(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JPanel && ((JPanel) component).isOpaque()) {
                return (JPanel) component;
            }
        }
        throw new AssertionError("No opaque banner row");
    }

    private static <T extends Component> T find(Container root, Class<T> type, String text) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && matchesText(component, text)) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T nested = find((Container) component, type, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        java.util.ArrayList<T> out = new java.util.ArrayList<T>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) out.add(type.cast(component));
            if (component instanceof Container) out.addAll(findAll((Container) component, type));
        }
        return out;
    }

    private static boolean matchesText(Component component, String text) {
        if (text == null) return true;
        return component instanceof AbstractButton
                && text.equals(((AbstractButton) component).getText());
    }
}
