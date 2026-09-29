package imagejai.ui;

import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.EmbeddedAgentSession;
import imagejai.engine.SessionCodeJournal;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import imagejai.local.AutocompleteChipRow;
import imagejai.local.RankedPhrase;
import imagejai.ui.picker.ModelMenuItem;
import imagejai.ui.picker.ModelPickerButton;
import imagejai.ui.picker.ProviderMenu;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Headless component contracts for the primary keyboard-only workflow. */
public class AccessibilityTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void chatEditorUsesNormalTabTraversalAndNamesInteractiveControls() throws Exception {
        AtomicReference<ChatView> ref = new AtomicReference<ChatView>();
        SwingUtilities.invokeAndWait(() -> ref.set(new ChatView(new Settings())));
        ChatView view = ref.get();
        JTextArea editor = view.inputAreaForTest();
        JButton send = view.sendButtonForTest();

        assertTrue(editor.getFocusTraversalKeysEnabled());
        assertTrue(editor.getFocusTraversalKeys(
                KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS).contains(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)));
        assertTrue(editor.getFocusTraversalKeys(
                KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS).contains(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)));
        assertTrue(editor.getAccessibleContext().getAccessibleDescription().contains(
                "Shift+Enter adds a new line"));
        assertNamedRole(editor, "Chat message", AccessibleRole.TEXT);
        assertNamedRole(view.messageAreaForTest(), "Chat transcript", AccessibleRole.TEXT);
        assertNamedRole(send, "Send chat message", AccessibleRole.PUSH_BUTTON);
        assertTrue(send.isFocusable());
        assertTrue(send.getMnemonic() != 0);
        assertNotNull(editor.getActionMap().get("acceptFirstSuggestion"));

        AtomicReference<String> suggestion = new AtomicReference<String>();
        AutocompleteChipRow chips = new AutocompleteChipRow(suggestion::set);
        SwingUtilities.invokeAndWait(() -> chips.setCandidates(Collections.singletonList(
                new RankedPhrase("Count the open images", "image.count", 0.99))));
        JButton chip = (JButton) chips.getComponent(0);
        assertTrue(chip.isFocusable());
        assertTrue(chip.getAccessibleContext().getAccessibleName()
                .startsWith("Suggested message:"));
        SwingUtilities.invokeAndWait(chip::doClick);
        assertEquals("Count the open images", suggestion.get());

        AtomicReference<String> confirmed = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> view.populateConfirmControlsForTest(
                Arrays.asList("Allow", "Deny"), confirmed::set));
        List<Component> confirmControls = descendants(view.confirmHostForTest());
        assertFocusableNamed(confirmControls, "Choose Allow", JButton.class);
        JButton allow = namedButton(confirmControls, "Choose Allow");
        SwingUtilities.invokeAndWait(allow::doClick);
        assertEquals("Allow", confirmed.get());
    }

    @Test
    public void modelAndProviderSecondaryActionsHaveKeyboardBindingsAndNames() throws Exception {
        ModelEntry model = model("demo", "vision-1");
        ProviderEntry provider = new ProviderEntry("demo", "Demo Provider",
                ProviderEntry.Status.NEEDS_SETUP, "configuration required",
                Collections.singletonList(model));
        AtomicInteger pins = new AtomicInteger();
        AtomicInteger modelStatus = new AtomicInteger();
        AtomicInteger providerStatus = new AtomicInteger();
        ProviderMenu menu = new ProviderMenu(provider, entry -> { },
                (entry, pinned) -> pins.incrementAndGet(), providerId -> { },
                new ProviderMenu.StatusListener() {
                    @Override
                    public boolean onStatusIconClicked(ModelEntry entry,
                                                       ModelMenuItem.ProviderStatusIcon status) {
                        modelStatus.incrementAndGet();
                        return true;
                    }

                    @Override
                    public boolean onProviderStatusIconClicked(ProviderEntry entry,
                                                               ModelMenuItem.ProviderStatusIcon status) {
                        providerStatus.incrementAndGet();
                        return true;
                    }
                });

        ModelMenuItem item = null;
        JMenuItem credentials = null;
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof ModelMenuItem) item = (ModelMenuItem) component;
            if (component instanceof JMenuItem && !(component instanceof ModelMenuItem)) {
                credentials = (JMenuItem) component;
            }
        }
        assertNotNull(item);
        assertNotNull(credentials);
        assertTrue(item.isFocusable());
        assertTrue(item.getAccessibleContext().getAccessibleName().contains("Model vision-1"));
        invoke(item.getActionMap().get("toggleModelPin"), item);
        invoke(item.getActionMap().get("activateModelStatus"), item);
        invoke(menu.getActionMap().get("activateProviderStatus"), menu);
        assertEquals(1, pins.get());
        assertEquals(1, modelStatus.get());
        assertEquals(1, providerStatus.get());
        assertTrue(credentials.getAccessibleContext().getAccessibleName()
                .contains("Demo Provider"));
        assertNamedRole(menu, "Provider Demo Provider", AccessibleRole.MENU);

        ProviderRegistry registry = ProviderRegistry.fromMerged(
                Collections.singletonList(model), LocalDate.of(2026, 7, 16));
        ModelPickerButton picker = new ModelPickerButton(registry, new Settings());
        assertTrue(picker.isFocusable());
        assertTrue(picker.getAccessibleContext().getAccessibleName().startsWith("AI model:"));
        assertNotNull(picker.getActionMap().get("refreshModels"));
        assertNotNull(picker.getActionMap().get("openModelPicker"));

        JPopupMenu popup = (JPopupMenu) field(ModelPickerButton.class, "popup").get(picker);
        assertNotNull(popup.getActionMap().get("toggleSelectedModelPin"));
        assertNotNull(popup.getActionMap().get("activateSelectedProviderStatus"));
        List<Component> descendants = descendants(popup);
        assertFocusableNamed(descendants, "Refresh model list", JButton.class);
        assertFocusableNamed(descendants, "Show free models only", JButton.class);
        assertFocusableNamed(descendants, "Filter models", JTextField.class);
    }

    @Test
    public void receiptsCanBeExpandedAndOpenedWithoutMouseEvents() throws Exception {
        Path csv = tmp.newFolder("audit-accessibility").toPath().resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);
        AtomicInteger presented = new AtomicInteger();
        AtomicReference<ReceiptsPane> ref = new AtomicReference<ReceiptsPane>();
        SwingUtilities.invokeAndWait(() -> ref.set(new ReceiptsPane(log,
                detail -> presented.incrementAndGet())));
        ReceiptsPane pane = ref.get();
        log.append(receiptRow());
        log.flushForTest();
        flushEdt();

        JTable table = pane.tableForTest();
        SwingUtilities.invokeAndWait(() -> {
            pane.toggleForTest().doClick();
            table.setRowSelectionInterval(0, 0);
            invoke(table.getActionMap().get("showSelectedReceipt"), table);
        });
        assertEquals(1, presented.get());
        assertTrue(pane.toggleForTest().isFocusable());
        assertNamedRole(pane.toggleForTest(), "Collapse receipts", AccessibleRole.PUSH_BUTTON);
        assertNamedRole(table, "Outbound receipt list", AccessibleRole.TABLE);
        assertEquals("showSelectedReceipt", table.getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
        assertEquals("showSelectedReceipt", table.getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)));

        pane.dispose();
        log.shutdownAndAwait(100);
    }

    @Test
    public void historyRowsCopyRerunAndExposeMoreActionsFromKeyboard() throws Exception {
        SessionCodeJournal journal = SessionCodeJournal.INSTANCE;
        journal.clearRing();
        journal.record("ijm", "run(\"Blobs (25K)\"); print(\"accessible history\");",
                "test", 7L, System.currentTimeMillis(), 2L, true, "");
        AtomicReference<String> copied = new AtomicReference<String>();
        CountDownLatch reran = new CountDownLatch(1);
        AtomicReference<SessionHistoryPanel> ref = new AtomicReference<SessionHistoryPanel>();
        SwingUtilities.invokeAndWait(() -> ref.set(new SessionHistoryPanel(
                new TcpHotline(new Settings()), tmp.getRoot(), null,
                copied::set, entry -> reran.countDown())));
        SessionHistoryPanel panel = ref.get();
        JList<SessionCodeJournal.Entry> list = panel.listForTest();
        assertTrue(list.getModel().getSize() > 0);
        String collapseNameBefore = panel.collapseButtonForTest()
                .getAccessibleContext().getAccessibleName();

        SwingUtilities.invokeAndWait(() -> {
            list.setSelectedIndex(0);
            invoke(list.getActionMap().get("copySelectedHistory"), list);
            invoke(list.getActionMap().get("rerunSelectedHistory"), list);
            panel.collapseButtonForTest().doClick();
        });
        assertTrue(copied.get().contains("accessible history"));
        assertTrue(reran.await(2, TimeUnit.SECONDS));
        assertNamedRole(list, "Session code history", AccessibleRole.LIST);
        assertEquals(AccessibleRole.PUSH_BUTTON, panel.collapseButtonForTest()
                .getAccessibleContext().getAccessibleRole());
        assertFalse(collapseNameBefore.equals(panel.collapseButtonForTest()
                .getAccessibleContext().getAccessibleName()));
        assertNamedRole(panel.menuButtonForTest(), "Session history options",
                AccessibleRole.PUSH_BUTTON);
        assertNotNull(list.getActionMap().get("showSelectedHistoryMenu"));
        assertEquals("showSelectedHistoryMenu", list.getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK)));
        JPopupMenu rowMenu = panel.rowMenuForTest(0);
        assertEquals(4, rowMenu.getComponentCount());
        for (Component component : rowMenu.getComponents()) {
            assertTrue(component instanceof JMenuItem);
            assertFalse(((JMenuItem) component).getText().trim().isEmpty());
        }

        panel.removeNotify();
        journal.clearRing();
    }

    @Test
    public void terminalControlsAreDisabledUntilALiveKeyboardSessionIsAttached()
            throws Exception {
        AtomicReference<TerminalToolbar> ref = new AtomicReference<TerminalToolbar>();
        SwingUtilities.invokeAndWait(() -> ref.set(new TerminalToolbar(null)));
        TerminalToolbar toolbar = ref.get();
        SwingUtilities.invokeAndWait(() -> {
            toolbar.showPendingPrompt("Continue?");
            toolbar.showCopyUrl("https://example.invalid/auth");
        });
        assertTerminalEnabled(toolbar, false);

        AtomicReference<String> lastWrite = new AtomicReference<String>();
        AtomicInteger interrupts = new AtomicInteger();
        TerminalToolbar.SessionControl live = new TerminalToolbar.SessionControl() {
            @Override public boolean isAlive() { return true; }
            @Override public EmbeddedAgentSession.WriteResult writeRaw(String text) {
                lastWrite.set(text);
                return EmbeddedAgentSession.WriteResult.success();
            }
            @Override public void interrupt() { interrupts.incrementAndGet(); }
            @Override public void destroy() { }
            @Override public String displayName() { return "test agent"; }
        };
        SwingUtilities.invokeAndWait(() -> {
            toolbar.attachSessionForTest(live);
            toolbar.showPendingPrompt("Continue?");
            toolbar.showCopyUrl("https://example.invalid/auth");
        });
        assertTerminalEnabled(toolbar, true);
        for (JButton button : terminalButtons(toolbar)) {
            assertTrue(button.isFocusable());
            assertTrue(button.getMnemonic() != 0);
            assertEquals(AccessibleRole.PUSH_BUTTON,
                    button.getAccessibleContext().getAccessibleRole());
            assertFalse(button.getAccessibleContext().getAccessibleName().trim().isEmpty());
        }

        SwingUtilities.invokeAndWait(() -> toolbar.confirmButtonForTest().doClick());
        assertEquals("\r", lastWrite.get());
        SwingUtilities.invokeAndWait(() -> {
            toolbar.showPendingPrompt("Cancel?");
            toolbar.cancelButtonForTest().doClick();
            toolbar.interruptButtonForTest().doClick();
        });
        assertEquals("\u001b", lastWrite.get());
        assertEquals(1, interrupts.get());
        SwingUtilities.invokeAndWait(() -> toolbar.clearSession(null));
        assertTerminalEnabled(toolbar, false);
    }

    private static ModelEntry model(String provider, String modelId) {
        return new ModelEntry(provider, modelId, modelId, "",
                ModelEntry.Tier.FREE, 8192, false, ModelEntry.Reliability.HIGH,
                false, true, "");
    }

    private static AuditRow receiptRow() {
        return new AuditRow(Instant.parse("2026-07-16T12:00:00Z"), "session",
                "get_state", PrivacyPosture.PSEUDONYMISED, "demo", "", 20, 10,
                "sha256:test", true, Collections.singletonList("path"), "",
                "{\"success\":true}");
    }

    private static void assertTerminalEnabled(TerminalToolbar toolbar, boolean enabled) {
        for (JButton button : terminalButtons(toolbar)) {
            assertEquals(button.getAccessibleContext().getAccessibleName(),
                    enabled, button.isEnabled());
        }
    }

    private static List<JButton> terminalButtons(TerminalToolbar toolbar) {
        List<JButton> out = new ArrayList<JButton>();
        out.add(toolbar.confirmButtonForTest());
        out.add(toolbar.cancelButtonForTest());
        out.add(toolbar.interruptButtonForTest());
        out.add(toolbar.killButtonForTest());
        out.add(toolbar.copyUrlButtonForTest());
        return out;
    }

    private static void assertNamedRole(Component component, String nameFragment,
                                        AccessibleRole role) {
        AccessibleContext context = component.getAccessibleContext();
        assertNotNull(context);
        assertNotNull(context.getAccessibleName());
        assertTrue(context.getAccessibleName(),
                context.getAccessibleName().contains(nameFragment));
        assertEquals(role, context.getAccessibleRole());
    }

    private static void assertFocusableNamed(List<Component> components, String name,
                                             Class<?> type) {
        for (Component component : components) {
            if (type.isInstance(component)
                    && component.getAccessibleContext() != null
                    && name.equals(component.getAccessibleContext().getAccessibleName())) {
                assertTrue(name, component.isFocusable());
                return;
            }
        }
        throw new AssertionError("Missing accessible component: " + name);
    }

    private static JButton namedButton(List<Component> components, String name) {
        for (Component component : components) {
            if (component instanceof JButton
                    && name.equals(component.getAccessibleContext().getAccessibleName())) {
                return (JButton) component;
            }
        }
        throw new AssertionError("Missing button: " + name);
    }

    private static List<Component> descendants(Container root) {
        List<Component> out = new ArrayList<Component>();
        for (Component child : root.getComponents()) {
            out.add(child);
            if (child instanceof Container) out.addAll(descendants((Container) child));
            if (child instanceof javax.swing.JMenu) {
                out.addAll(descendants(((javax.swing.JMenu) child).getPopupMenu()));
            }
        }
        return out;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void invoke(Action action, Object source) {
        assertNotNull(action);
        action.actionPerformed(new ActionEvent(source, ActionEvent.ACTION_PERFORMED, "test"));
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
