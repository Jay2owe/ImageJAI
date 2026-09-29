package imagejai.engine.automation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * Enumeration, exact resolution, and semantic actions against a real Swing
 * window.
 *
 * <p>The fixture is shown off-screen so the components realise real peers,
 * bounds, and focus behaviour — a hierarchy that was never displayed would not
 * prove the geometry a physical harness has to trust.</p>
 */
public class UiAutomationServiceTest {

    private UiIdentityRegistry identities;
    private UiAutomationService service;
    private JFrame frame;

    // Fixture controls, all created and read on the event thread.
    private JButton okButton;
    private JButton disabledButton;
    private JCheckBox preview;
    private JRadioButton otsu;
    private JTextField sigma;
    private JPasswordField secret;
    private JComboBox<String> method;
    private JSlider threshold;
    private JTabbedPane tabs;
    private JTable table;
    private JPanel hidden;

    @Before
    public void setUp() throws Exception {
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());
        identities = new UiIdentityRegistry();
        service = new UiAutomationService(identities, null);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { buildFixture(); }
        });
        // Only this fixture's windows take part; a stray window from another
        // test in the same JVM must not make assertions ambiguous.
        service.setWindowSupplierForTest(new Supplier<Window[]>() {
            @Override public Window[] get() { return new Window[] {frame}; }
        });
    }

    @After
    public void tearDown() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { frame.dispose(); }
            });
        }
    }

    private void buildFixture() {
        frame = new JFrame("Automation Fixture");
        JPanel content = new JPanel();

        okButton = new JButton("OK");
        okButton.setName("okButton");
        disabledButton = new JButton("Apply");
        disabledButton.setEnabled(false);
        preview = new JCheckBox("Preview");
        otsu = new JRadioButton("Otsu");
        JRadioButton li = new JRadioButton("Li");
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        group.add(otsu);
        group.add(li);

        JLabel sigmaLabel = new JLabel("Sigma");
        sigma = new JTextField("2.0", 8);
        sigmaLabel.setLabelFor(sigma);

        secret = new JPasswordField("top-secret-value", 8);
        secret.setName("apiKeyField");

        method = new JComboBox<String>(new String[] {"Otsu", "Li", "Triangle"});
        threshold = new JSlider(0, 255, 100);
        tabs = new JTabbedPane();
        tabs.addTab("Basic", new JPanel());
        tabs.addTab("Advanced", new JPanel());
        table = new JTable(new String[][] {{"a"}, {"b"}, {"c"}}, new String[] {"col"});

        hidden = new JPanel();
        hidden.setVisible(false);
        hidden.add(new JButton("Never shown"));

        content.add(okButton);
        content.add(disabledButton);
        content.add(preview);
        content.add(otsu);
        content.add(li);
        content.add(sigmaLabel);
        content.add(sigma);
        content.add(secret);
        content.add(method);
        content.add(threshold);
        content.add(tabs);
        content.add(table);
        content.add(hidden);
        frame.getContentPane().add(content);
        frame.pack();
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
    }

    // -----------------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------------

    @Test
    public void snapshotCoversEveryOwnedWindowWithStableIdentity() throws Exception {
        UiTreeSnapshot first = service.snapshot(new UiAutomationService.SnapshotOptions(),
                5_000L);
        assertEquals(1, first.windows().size());

        JsonObject window = first.windows().get(0).toJson();
        assertEquals(UiNode.ROLE_FRAME, window.get("role").getAsString());
        assertEquals("Automation Fixture", window.get("title").getAsString());
        assertEquals(UiAutomationService.OWNER_OTHER, window.get("owner").getAsString());
        assertFalse(window.get("modal").getAsBoolean());
        assertTrue(window.get("id").getAsString().startsWith("w-"));

        // Identity is stable across snapshots, and nothing invalidated, so the
        // generation a caller echoes stays usable.
        UiTreeSnapshot second = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        assertEquals(first.generation(), second.generation());
        assertEquals(window.get("id").getAsString(),
                second.windows().get(0).toJson().get("id").getAsString());
        assertEquals(nodeId(first, "OK"), nodeId(second, "OK"));
    }

    @Test
    public void hierarchyRolesAndGeometryAreReported() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        JsonObject ok = findByLabel(snapshot, "OK");
        assertNotNull(ok);
        assertEquals(UiNode.ROLE_BUTTON, ok.get("role").getAsString());
        assertEquals("okButton", ok.get("name").getAsString());
        assertTrue(ok.get("enabled").getAsBoolean());
        assertTrue(ok.get("showing").getAsBoolean());
        assertTrue(ok.get("depth").getAsInt() >= 1);
        assertTrue(ok.get("index_path").getAsString().length() > 0);

        JsonObject bounds = ok.getAsJsonObject("bounds");
        assertTrue(bounds.get("width").getAsInt() > 0);
        assertTrue(bounds.get("height").getAsInt() > 0);
        // Window-relative bounds are inside the window; screen bounds are not
        // required to be, because the fixture deliberately sits off-screen.
        assertTrue(bounds.get("x").getAsInt() >= 0);
        JsonObject screen = ok.getAsJsonObject("screen_bounds");
        assertEquals(bounds.get("width").getAsInt(), screen.get("width").getAsInt());
        JsonObject centre = ok.getAsJsonObject("screen_center");
        assertEquals(screen.get("x").getAsInt() + screen.get("width").getAsInt() / 2,
                centre.get("x").getAsInt());

        assertEquals(UiNode.ROLE_RADIO, findByLabel(snapshot, "Otsu").get("role").getAsString());
        assertEquals(UiNode.ROLE_CHECKBOX,
                findByLabel(snapshot, "Preview").get("role").getAsString());
        // The text field carries the caption through labelFor, not proximity,
        // and reports which of the two it used.
        JsonObject sigmaField = findByRole(snapshot, UiNode.ROLE_TEXT);
        assertEquals("Sigma", sigmaField.get("label").getAsString());
        assertEquals("label_for", sigmaField.get("label_source").getAsString());
        assertEquals("2.0", sigmaField.get("value").getAsString());
        assertFalse(findByLabel(snapshot, "Apply").get("enabled").getAsBoolean());
        assertEquals(0, findByLabel(snapshot, "Apply").getAsJsonArray("actions").size());
    }

    @Test
    public void hiddenSubtreesAreExcludedUnlessAskedFor() throws Exception {
        UiTreeSnapshot visible = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        assertNull(findByLabel(visible, "Never shown"));

        UiTreeSnapshot everything = service.snapshot(
                new UiAutomationService.SnapshotOptions().includeHidden(true), 5_000L);
        JsonObject neverShown = findByLabel(everything, "Never shown");
        assertNotNull(neverShown);
        assertFalse(neverShown.get("showing").getAsBoolean());
    }

    @Test
    public void secretsNeverAppearAnywhereInASnapshot() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions().includeHidden(true), 5_000L);
        String encoded = snapshot.toJson().toString();
        assertFalse(encoded.contains("top-secret-value"));

        JsonObject passwordNode = findByRole(snapshot, UiNode.ROLE_PASSWORD);
        assertNotNull(passwordNode);
        assertTrue(passwordNode.get("masked").getAsBoolean());
        assertEquals(0, passwordNode.getAsJsonArray("actions").size());
    }

    @Test
    public void nodeBudgetIsEnforcedAndReported() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions().maxNodes(3), 5_000L);
        assertTrue(snapshot.truncated());
        assertTrue(snapshot.nodeCount() <= 4);
        assertEquals(AutomationPolicy.MAX_TREE_NODES,
                snapshot.toJson().get("max_nodes").getAsInt());
    }

    @Test
    public void snapshotCarriesTheEnvironmentBaselinesAreKeyedOn() throws Exception {
        JsonObject environment = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L)
                .toJson().getAsJsonObject("environment");
        assertTrue(environment.get("os_name").getAsString().length() > 0);
        assertTrue(environment.get("java_version").getAsString().length() > 0);
        assertTrue(environment.has("look_and_feel"));
        assertTrue(environment.get("screen_scale").getAsDouble() > 0.0d);
    }

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    @Test
    public void malformedAndUnknownIdentitiesAreRejectedSeparately() {
        assertEquals(UiAutomationService.ERR_INVALID,
                service.resolve("not-an-id", -1L).errorCode());
        assertEquals(UiAutomationService.ERR_INVALID,
                service.resolve(null, -1L).errorCode());
        assertEquals(UiAutomationService.ERR_UNKNOWN,
                service.resolve("n-abcdefgh-999999", -1L).errorCode());
    }

    @Test
    public void aStaleGenerationIsRefusedBeforeAnythingIsTouched() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        String id = nodeId(snapshot, "OK");

        assertTrue(service.resolve(id, snapshot.generation()).isOk());
        assertEquals(UiAutomationService.ERR_STALE,
                service.resolve(id, snapshot.generation() - 1).errorCode());
        assertEquals(UiAutomationService.ERR_STALE,
                service.resolve(id, snapshot.generation() + 1).errorCode());
        // A read may opt out of the check; a mutation never does.
        assertTrue(service.resolve(id, -1L).isOk());
    }

    @Test
    public void disposingTheWindowInvalidatesEveryIdentityUnderIt() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        String id = nodeId(snapshot, "OK");
        long before = identities.generation();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { frame.dispose(); }
        });

        UiAutomationService.Resolution resolution = service.resolve(id, -1L);
        assertFalse(resolution.isOk());
        assertEquals(UiAutomationService.ERR_STALE, resolution.errorCode());
        assertTrue("disposal must advance the generation",
                identities.generation() > before);
    }

    // -----------------------------------------------------------------------
    // Semantic actions
    // -----------------------------------------------------------------------

    @Test
    public void activatingAButtonFiresItsListenerExactlyOnceOnTheEventThread()
            throws Exception {
        final AtomicInteger clicks = new AtomicInteger();
        final List<String> threads = new ArrayList<String>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                okButton.addActionListener(event -> {
                    clicks.incrementAndGet();
                    threads.add(Thread.currentThread().getName());
                });
            }
        });

        JsonObject response = act(okButton, UiNode.ACTION_ACTIVATE, null, null);
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        JsonObject result = response.getAsJsonObject("result");

        assertEquals(1, clicks.get());
        assertEquals(1, result.getAsJsonObject("dispatch").get("count").getAsInt());
        assertTrue(result.getAsJsonObject("dispatch")
                .get("on_event_thread").getAsBoolean());
        assertTrue(threads.get(0).contains("AWT-EventQueue"));
        assertTrue(result.getAsJsonObject("dispatch").has("queue_ms"));
        assertTrue(result.getAsJsonObject("dispatch").has("handler_ms"));
        assertNotNull(result.getAsJsonObject("pre_state"));
        assertNotNull(result.getAsJsonObject("post_state"));
        assertTrue(result.get("window_still_showing").getAsBoolean());
    }

    @Test
    public void focusIsNotCountedWhenTheRequestCannotSucceed() throws Exception {
        // `Component.requestFocusInWindow()` returns false when the request is
        // *guaranteed* to fail — most commonly because the component's
        // top-level window is not, and here can never be, the focused window.
        // The service used to discard that boolean and count the dispatch
        // anyway, so it reported `count: 1` for a guaranteed no-op. An external
        // driver read that as success and typed at a field that had never taken
        // focus.
        //
        // A window with `setFocusableWindowState(false)` makes the false
        // deterministic on any host, and it is not a contrivance: Fiji's own
        // `Quick Search` results panel is exactly such a dialog. Note the
        // assertion is not "focus was taken" — a `true` return only means the
        // request was not refused outright, and the transfer is asynchronous,
        // so `isFocusOwner()` immediately afterwards proves nothing either way.
        final JDialog unfocusable = new JDialog(frame, "Never focusable", false);
        final JTextField field = new JTextField("x", 8);
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    unfocusable.setFocusableWindowState(false);
                    unfocusable.getContentPane().add(field);
                    unfocusable.pack();
                    unfocusable.setLocation(-3000, -3000);
                    unfocusable.setVisible(true);
                }
            });

            JsonObject response = act(field, UiNode.ACTION_FOCUS, null, null);
            assertTrue(response.toString(), response.get("ok").getAsBoolean());
            JsonObject result = response.getAsJsonObject("result");

            assertEquals("a focus that cannot succeed is not a dispatch",
                    0, result.getAsJsonObject("dispatch").get("count").getAsInt());
            assertFalse(result.getAsJsonObject("post_state")
                    .get("focused").getAsBoolean());
        } finally {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { unfocusable.dispose(); }
            });
        }
    }

    @Test
    public void focusIsCountedOnceWhenTheRequestIsAccepted() throws Exception {
        // Acceptance is asynchronous and depends on the host window manager.
        // Keep the real hierarchy, but make acceptance deterministic so this
        // tests dispatch accounting on desktops and virtual Linux displays.
        final AtomicInteger requests = new AtomicInteger();
        final JTextField acceptingField = new JTextField("accept focus", 8) {
            @Override public boolean requestFocusInWindow() {
                requests.incrementAndGet();
                return true;
            }
        };
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ((JPanel) frame.getContentPane().getComponent(0)).add(acceptingField);
                frame.pack();
            }
        });
        JsonObject result = act(acceptingField, UiNode.ACTION_FOCUS, null, null)
                .getAsJsonObject("result");
        assertEquals(1, result.getAsJsonObject("dispatch").get("count").getAsInt());
        assertEquals("one focus request was delivered", 1, requests.get());
    }

    @Test
    public void checkboxesReportTheirBeforeAndAfterState() throws Exception {
        JsonObject result = act(preview, UiNode.ACTION_SET_SELECTED,
                new JsonPrimitive(true), null).getAsJsonObject("result");

        assertFalse(result.getAsJsonObject("pre_state").get("selected").getAsBoolean());
        assertTrue(result.getAsJsonObject("post_state").get("selected").getAsBoolean());
        JsonObject delta = result.getAsJsonObject("delta").getAsJsonObject("selected");
        assertFalse(delta.get("from").getAsBoolean());
        assertTrue(delta.get("to").getAsBoolean());

        // Setting the state it already holds is a no-op, not a second click.
        JsonObject again = act(preview, UiNode.ACTION_SET_SELECTED,
                new JsonPrimitive(true), null).getAsJsonObject("result");
        assertEquals(0, again.getAsJsonObject("dispatch").get("count").getAsInt());
        assertEquals(0, again.getAsJsonObject("delta").size());
    }

    @Test
    public void radioSelectionGoesThroughTheButtonGroup() throws Exception {
        JsonObject result = act(otsu, UiNode.ACTION_SET_SELECTED,
                new JsonPrimitive(true), null).getAsJsonObject("result");
        assertTrue(result.getAsJsonObject("post_state").get("selected").getAsBoolean());
    }

    @Test
    public void settingTextUpdatesTheDocumentWithoutSubmittingTheDialog()
            throws Exception {
        final AtomicInteger actionEvents = new AtomicInteger();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                sigma.addActionListener(event -> actionEvents.incrementAndGet());
            }
        });

        JsonObject result = act(sigma, UiNode.ACTION_SET_TEXT,
                new JsonPrimitive("3.5"), null).getAsJsonObject("result");

        assertEquals("2.0", result.getAsJsonObject("pre_state").get("value").getAsString());
        assertEquals("3.5", result.getAsJsonObject("post_state").get("value").getAsString());
        // Posting an ActionEvent here would be indistinguishable from Enter and
        // could submit a GenericDialog the scenario had not finished filling in.
        assertEquals(0, actionEvents.get());
    }

    @Test
    public void comboSelectionIsExactWithNoSubstringFallback() throws Exception {
        JsonObject exact = act(method, UiNode.ACTION_SELECT_ITEM,
                new JsonPrimitive("Triangle"), null);
        assertTrue(exact.get("ok").getAsBoolean());
        assertEquals("Triangle", exact.getAsJsonObject("result")
                .getAsJsonObject("post_state").get("value").getAsString());

        JsonObject substring = act(method, UiNode.ACTION_SELECT_ITEM,
                new JsonPrimitive("Tri"), null);
        assertFalse(substring.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_NOT_ACTIONABLE,
                substring.getAsJsonObject("error").get("code").getAsString());
        assertTrue(substring.getAsJsonObject("error").get("message").getAsString()
                .contains("no substring fallback"));

        JsonObject byIndex = act(method, UiNode.ACTION_SELECT_ITEM, null,
                Integer.valueOf(1));
        assertTrue(byIndex.get("ok").getAsBoolean());
        assertEquals("Li", byIndex.getAsJsonObject("result")
                .getAsJsonObject("post_state").get("value").getAsString());
    }

    @Test
    public void numericControlsClampRatherThanThrow() throws Exception {
        JsonObject high = act(threshold, UiNode.ACTION_SET_NUMBER,
                new JsonPrimitive(9_000), null).getAsJsonObject("result");
        assertEquals(255.0d, high.getAsJsonObject("post_state")
                .get("numeric_value").getAsDouble(), 0.0d);

        JsonObject low = act(threshold, UiNode.ACTION_SET_NUMBER,
                new JsonPrimitive(-42), null).getAsJsonObject("result");
        assertEquals(0.0d, low.getAsJsonObject("post_state")
                .get("numeric_value").getAsDouble(), 0.0d);
    }

    @Test
    public void tabsAndTableRowsAreSelectableByExactIdentity() throws Exception {
        JsonObject tab = act(tabs, UiNode.ACTION_SELECT_TAB,
                new JsonPrimitive("Advanced"), null);
        assertTrue(tab.toString(), tab.get("ok").getAsBoolean());
        assertEquals("Advanced", tab.getAsJsonObject("result")
                .getAsJsonObject("post_state").get("value").getAsString());

        JsonObject missing = act(tabs, UiNode.ACTION_SELECT_TAB,
                new JsonPrimitive("Nonexistent"), null);
        assertFalse(missing.get("ok").getAsBoolean());

        JsonObject row = act(table, UiNode.ACTION_SELECT_ROW, null, Integer.valueOf(2));
        assertTrue(row.toString(), row.get("ok").getAsBoolean());

        JsonObject outOfRange = act(table, UiNode.ACTION_SELECT_ROW, null,
                Integer.valueOf(99));
        assertFalse(outOfRange.get("ok").getAsBoolean());
    }

    @Test
    public void disabledHiddenAndPasswordTargetsAreRefusedBeforeMutation()
            throws Exception {
        JsonObject disabled = act(disabledButton, UiNode.ACTION_ACTIVATE, null, null);
        assertFalse(disabled.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_NOT_ACTIONABLE,
                disabled.getAsJsonObject("error").get("code").getAsString());

        JsonObject password = act(secret, UiNode.ACTION_SET_TEXT,
                new JsonPrimitive("new-secret"), null);
        assertFalse(password.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_UNSUPPORTED,
                password.getAsJsonObject("error").get("code").getAsString());
        assertFalse(password.toString().contains("new-secret"));

        final JPanel hiddenPanel = hidden;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { hiddenPanel.setVisible(true); }
        });
        Component neverShown = hiddenPanel.getComponent(0);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { hiddenPanel.setVisible(false); }
        });
        JsonObject offScreen = act(neverShown, UiNode.ACTION_ACTIVATE, null, null);
        assertFalse(offScreen.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_NOT_ACTIONABLE,
                offScreen.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void anActionTheRoleDoesNotSupportIsRefused() throws Exception {
        JsonObject response = act(okButton, UiNode.ACTION_SET_TEXT,
                new JsonPrimitive("nope"), null);
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_UNSUPPORTED,
                response.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void aGenerationThatMovedBetweenResolveAndDispatchStopsTheAction()
            throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        long stale = snapshot.generation();
        identities.invalidate();

        JsonObject response = onEdt(new Supplier<JsonObject>() {
            @Override public JsonObject get() {
                return service.performAction(okButton, frame,
                        new UiAutomationService.ActionRequest(
                                UiNode.ACTION_ACTIVATE, null, null),
                        stale, System.nanoTime());
            }
        });
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_STALE,
                response.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void closingAWindowGoesThroughItsOwnCloseHandler() throws Exception {
        final JDialog[] holder = new JDialog[1];
        final AtomicInteger closings = new AtomicInteger();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                JDialog dialog = new JDialog(frame, "Child", false);
                dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent event) {
                        closings.incrementAndGet();
                    }
                });
                dialog.setSize(120, 80);
                dialog.setLocation(-3000, -3000);
                dialog.setVisible(true);
                holder[0] = dialog;
            }
        });

        JsonObject response = act(holder[0], UiNode.ACTION_CLOSE_WINDOW, null, null);
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        assertEquals(1, closings.get());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { holder[0].dispose(); }
        });
    }

    @Test
    public void actionsAreCountedWhileTheyRunSoIdleCanSeeThem() throws Exception {
        assertEquals(0, service.activeActionCount());
        final int[] observed = new int[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                okButton.addActionListener(
                        event -> observed[0] = service.activeActionCount());
            }
        });
        act(okButton, UiNode.ACTION_ACTIVATE, null, null);
        assertEquals(1, observed[0]);
        assertEquals(0, service.activeActionCount());
    }

    @Test
    public void anActionOffTheEventThreadIsRefusedOutright() {
        JsonObject response = service.performAction(okButton, frame,
                new UiAutomationService.ActionRequest(UiNode.ACTION_ACTIVATE, null, null),
                -1L, System.nanoTime());
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_NOT_ACTIONABLE,
                response.getAsJsonObject("error").get("code").getAsString());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private JsonObject act(final Component target, final String action,
                           final JsonElement value, final Integer index)
            throws Exception {
        return onEdt(new Supplier<JsonObject>() {
            @Override public JsonObject get() {
                Window window = target instanceof Window ? (Window) target
                        : SwingUtilities.getWindowAncestor(target);
                return service.performAction(target, window,
                        new UiAutomationService.ActionRequest(action, value, index),
                        -1L, System.nanoTime());
            }
        });
    }

    private JsonObject onEdt(Supplier<JsonObject> work) throws Exception {
        return service.callOnEdt(work, 5_000L);
    }

    private static String nodeId(UiTreeSnapshot snapshot, String label) {
        JsonObject node = findByLabel(snapshot, label);
        return node == null ? null : node.get("id").getAsString();
    }

    private static JsonObject findByLabel(UiTreeSnapshot snapshot, String label) {
        for (UiTreeSnapshot.WindowEntry entry : snapshot.windows()) {
            JsonObject found = search(entry.toJson(), "label", label);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonObject findByRole(UiTreeSnapshot snapshot, String role) {
        for (UiTreeSnapshot.WindowEntry entry : snapshot.windows()) {
            JsonObject found = search(entry.toJson(), "role", role);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonObject search(JsonObject node, String key, String value) {
        JsonElement candidate = node.get(key);
        if (candidate != null && candidate.isJsonPrimitive()
                && value.equals(candidate.getAsString())) {
            return node;
        }
        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) {
                JsonObject found = search(child.getAsJsonObject(), key, value);
                if (found != null) return found;
            }
        }
        return null;
    }
}
