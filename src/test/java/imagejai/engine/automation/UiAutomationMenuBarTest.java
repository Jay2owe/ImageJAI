package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.CheckboxMenuItem;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
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
 * The AWT menu bar published by {@code get_ui_tree}.
 *
 * <p>ImageJ 1.x builds its entire command surface from {@link MenuBar},
 * {@link Menu} and {@link MenuItem}. Those extend {@code MenuComponent}, not
 * {@code Component}, so a {@code Container.getComponents()} walk cannot reach
 * them at any depth — a tree of the ImageJ main window contained no menu at all
 * and no external harness could open a menu-launched dialog. The fixture here
 * mirrors the real path a scenario needs: Process &gt; Filters &gt; Gaussian
 * Blur...</p>
 */
public class UiAutomationMenuBarTest {

    private UiIdentityRegistry identities;
    private UiAutomationService service;
    private Frame frame;
    private MenuItem gaussian;
    private MenuItem disabledItem;
    private CheckboxMenuItem toggleItem;
    private final AtomicInteger gaussianInvocations = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());
        identities = new UiIdentityRegistry();
        service = new UiAutomationService(identities, null);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { buildFixture(); }
        });
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
        frame = new Frame("Menu Fixture");

        MenuBar bar = new MenuBar();
        Menu file = new Menu("File");
        file.add(new MenuItem("Open..."));
        file.addSeparator();
        file.add(new MenuItem("Quit"));

        Menu process = new Menu("Process");
        process.add(new MenuItem("Smooth"));
        Menu filters = new Menu("Filters");
        filters.add(new MenuItem("Convolve..."));
        gaussian = new MenuItem("Gaussian Blur...");
        gaussian.addActionListener(e -> gaussianInvocations.incrementAndGet());
        filters.add(gaussian);
        disabledItem = new MenuItem("Unsharp Mask...");
        disabledItem.setEnabled(false);
        filters.add(disabledItem);
        process.add(filters);
        toggleItem = new CheckboxMenuItem("Preview");
        process.add(toggleItem);

        bar.add(file);
        bar.add(process);
        // ImageJ 1.x calls setHelpMenu, and the help menu is the one bar entry
        // a platform peer may place differently from its indexed position, so
        // the fixture has to have one.
        Menu help = new Menu("Help");
        help.add(new MenuItem("About..."));
        bar.setHelpMenu(help);
        frame.setMenuBar(bar);

        frame.setSize(320, 200);
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
    }

    // -----------------------------------------------------------------------
    // Publication
    // -----------------------------------------------------------------------

    @Test
    public void menuBarIsPublishedWithLabelsAndIndexPaths() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        JsonObject window = snapshot.windows().get(0).toJson();

        JsonObject bar = findByRole(window, UiNode.ROLE_MENU_BAR);
        assertNotNull("the frame's AWT menu bar must be published", bar);
        assertEquals("menu_bar", bar.get("index_path").getAsString());
        assertEquals(window.get("id").getAsString(), bar.get("parent_id").getAsString());

        JsonObject process = findByLabel(window, "Process");
        assertNotNull(process);
        assertEquals(UiNode.ROLE_MENU, process.get("role").getAsString());
        assertEquals("menu_bar/1", process.get("index_path").getAsString());
        assertEquals(bar.get("id").getAsString(), process.get("parent_id").getAsString());

        JsonObject filters = findByLabel(window, "Filters");
        assertNotNull(filters);
        assertEquals(UiNode.ROLE_MENU, filters.get("role").getAsString());
        assertEquals("menu_bar/1/1", filters.get("index_path").getAsString());

        JsonObject blur = findByLabel(window, "Gaussian Blur...");
        assertNotNull("the leaf command must be reachable", blur);
        assertEquals(UiNode.ROLE_MENU_ITEM, blur.get("role").getAsString());
        assertEquals("Gaussian Blur...", blur.get("text").getAsString());
        assertEquals("Gaussian Blur...", blur.get("accessible_name").getAsString());
        // The numeric tail is the position of each entry inside its parent
        // menu, which is what a native menu driver walks.
        assertEquals("menu_bar/1/1/1", blur.get("index_path").getAsString());
        assertEquals(filters.get("id").getAsString(), blur.get("parent_id").getAsString());
        assertTrue(blur.get("enabled").getAsBoolean());
        assertTrue(actionsOf(blur).contains(UiNode.ACTION_ACTIVATE));

        // Labels are not unique in a real Fiji — Process appears on the bar and
        // again under Plugins — so the fully-qualified path is what identifies
        // a command without guessing.
        JsonArray path = blur.getAsJsonArray("menu_path");
        assertEquals(3, path.size());
        assertEquals("Process", path.get(0).getAsString());
        assertEquals("Filters", path.get(1).getAsString());
        assertEquals("Gaussian Blur...", path.get(2).getAsString());
        assertFalse("the bar itself is not a step on any path",
                bar.has("menu_path"));
    }

    @Test
    public void theBarPublishesItsOwnAwtCountAndHelpPosition() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        JsonObject window = snapshot.windows().get(0).toJson();
        JsonObject bar = findByRole(window, UiNode.ROLE_MENU_BAR);
        assertNotNull(bar);

        // A driver walking the native menu has no way to check that the bar it
        // measures is the bar published here. This is the number it reconciles
        // GetMenuItemCount against; without it a constant index offset is
        // invisible and every position below the bar is silently wrong.
        assertEquals(3, bar.get("menu_count").getAsInt());
        assertEquals(2, bar.get("help_menu_index").getAsInt());

        // Only the bar carries them — they describe the bar, not menu nodes.
        JsonObject process = findByLabel(window, "Process");
        assertFalse(process.has("menu_count"));
        assertFalse(process.has("help_menu_index"));
        JsonObject blur = findByLabel(window, "Gaussian Blur...");
        assertFalse(blur.has("menu_count"));
    }

    @Test
    public void publishedChildrenAreNoSubstituteForTheAwtCount() throws Exception {
        // The reason menu_count exists rather than leaving a caller to count
        // children: children stop early when the depth limit or node budget
        // runs out, so child_count conflates a short bar with a truncated one.
        UiAutomationService.SnapshotOptions options =
                new UiAutomationService.SnapshotOptions().maxDepth(1);
        UiTreeSnapshot snapshot = service.snapshot(options, 5_000L);
        JsonObject bar = findByRole(snapshot.windows().get(0).toJson(),
                UiNode.ROLE_MENU_BAR);
        assertNotNull("the bar survives even when its menus do not", bar);

        assertEquals(0, bar.get("child_count").getAsInt());
        assertEquals("the AWT count is reported whatever was published",
                3, bar.get("menu_count").getAsInt());
        assertTrue(snapshot.truncated());
    }

    @Test
    public void menuNodesCarryNoGeometryAndNoFocus() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        JsonObject blur = findByLabel(snapshot.windows().get(0).toJson(),
                "Gaussian Blur...");
        // The window manager draws an AWT menu; the JVM is never told where.
        // Publishing a rectangle here would be a guess a physical driver would
        // then click.
        assertFalse(blur.has("screen_bounds"));
        assertFalse(blur.has("screen_center"));
        assertFalse(blur.has("bounds"));
        assertFalse(blur.get("focusable").getAsBoolean());
        assertFalse(blur.get("focused").getAsBoolean());
        assertFalse(blur.get("masked").getAsBoolean());
    }

    @Test
    public void separatorsHoldTheirPositionButOfferNoAction() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        JsonObject window = snapshot.windows().get(0).toJson();
        JsonObject quit = findByLabel(window, "Quit");
        assertNotNull(quit);
        // AWT stores a separator as an ordinary item, so it occupies a native
        // menu position too; skipping it would slide every later index up one.
        assertEquals("menu_bar/0/2", quit.get("index_path").getAsString());

        JsonObject separator = findByIndexPath(window, "menu_bar/0/1");
        assertNotNull(separator);
        assertTrue(actionsOf(separator).isEmpty());
    }

    @Test
    public void disabledEntriesOfferNoAction() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        JsonObject unsharp = findByLabel(snapshot.windows().get(0).toJson(),
                "Unsharp Mask...");
        assertNotNull(unsharp);
        assertFalse(unsharp.get("enabled").getAsBoolean());
        assertTrue(actionsOf(unsharp).isEmpty());
    }

    @Test
    public void depthLimitTruncatesRatherThanOmittingSilently() throws Exception {
        UiAutomationService.SnapshotOptions options =
                new UiAutomationService.SnapshotOptions().maxDepth(2);
        UiTreeSnapshot snapshot = service.snapshot(options, 5_000L);
        JsonObject window = snapshot.windows().get(0).toJson();
        assertNotNull(findByLabel(window, "Process"));
        assertNull(findByLabel(window, "Gaussian Blur..."));
        assertTrue(snapshot.truncated());
    }

    // -----------------------------------------------------------------------
    // Resolution and action
    // -----------------------------------------------------------------------

    @Test
    public void menuIdsResolveAndActivateExactlyOnce() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        final String blurId = findByLabel(snapshot.windows().get(0).toJson(),
                "Gaussian Blur...").get("id").getAsString();
        final long generation = snapshot.generation();

        UiAutomationService.Resolution resolution = service.resolve(blurId, generation);
        assertTrue(resolution.isOk());
        assertTrue(resolution.isMenuTarget());
        assertEquals(gaussian, resolution.menuComponent());
        assertNull(resolution.component());
        assertEquals(frame, resolution.window());

        final UiAutomationService.Resolution resolved = resolution;
        JsonObject[] holder = new JsonObject[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0] = service.performMenuAction(resolved.menuComponent(),
                        resolved.window(),
                        new UiAutomationService.ActionRequest(
                                UiNode.ACTION_ACTIVATE, null, null),
                        generation, System.nanoTime());
            }
        });
        JsonObject response = holder[0];
        assertTrue(String.valueOf(response), response.get("ok").getAsBoolean());
        assertEquals(1, gaussianInvocations.get());
        JsonObject result = response.getAsJsonObject("result");
        assertEquals(1, result.getAsJsonObject("dispatch").get("count").getAsInt());
        assertEquals("Gaussian Blur...",
                result.getAsJsonObject("target").get("label").getAsString());
    }

    @Test
    public void disabledMenuEntryIsRefusedRatherThanInvoked() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        String id = findByLabel(snapshot.windows().get(0).toJson(),
                "Unsharp Mask...").get("id").getAsString();
        final UiAutomationService.Resolution resolution =
                service.resolve(id, snapshot.generation());
        assertTrue(resolution.isOk());

        final long generation = snapshot.generation();
        JsonObject[] holder = new JsonObject[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0] = service.performMenuAction(resolution.menuComponent(),
                        resolution.window(),
                        new UiAutomationService.ActionRequest(
                                UiNode.ACTION_ACTIVATE, null, null),
                        generation, System.nanoTime());
            }
        });
        assertFalse(holder[0].get("ok").getAsBoolean());
        assertEquals(UiAutomationService.ERR_NOT_ACTIONABLE,
                holder[0].getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void checkboxMenuItemReportsAndAcceptsSelection() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        JsonObject preview = findByLabel(snapshot.windows().get(0).toJson(), "Preview");
        assertNotNull(preview);
        assertFalse(preview.get("selected").getAsBoolean());
        assertTrue(actionsOf(preview).contains(UiNode.ACTION_SET_SELECTED));

        final UiAutomationService.Resolution resolution =
                service.resolve(preview.get("id").getAsString(), snapshot.generation());
        final long generation = snapshot.generation();
        JsonObject[] holder = new JsonObject[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0] = service.performMenuAction(resolution.menuComponent(),
                        resolution.window(),
                        new UiAutomationService.ActionRequest(
                                UiNode.ACTION_SET_SELECTED,
                                new com.google.gson.JsonPrimitive(true), null),
                        generation, System.nanoTime());
            }
        });
        assertTrue(String.valueOf(holder[0]), holder[0].get("ok").getAsBoolean());
        assertTrue(toggleItem.getState());
        assertTrue(holder[0].getAsJsonObject("result").getAsJsonObject("delta")
                .has("selected"));
    }

    @Test
    public void menuIdGoesStaleWhenItsFrameIsDisposed() throws Exception {
        UiTreeSnapshot snapshot = service.snapshot(
                new UiAutomationService.SnapshotOptions(), 5_000L);
        String id = findByLabel(snapshot.windows().get(0).toJson(),
                "Gaussian Blur...").get("id").getAsString();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { frame.dispose(); }
        });
        UiAutomationService.Resolution resolution = service.resolve(id, -1L);
        assertFalse(resolution.isOk());
        assertEquals(UiAutomationService.ERR_STALE, resolution.errorCode());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<String> actionsOf(JsonObject node) {
        List<String> actions = new ArrayList<String>();
        JsonArray array = node.getAsJsonArray("actions");
        if (array != null) {
            for (JsonElement element : array) actions.add(element.getAsString());
        }
        return actions;
    }

    private static JsonObject findByRole(JsonObject node, String role) {
        return find(node, "role", role);
    }

    private static JsonObject findByLabel(JsonObject node, String label) {
        return find(node, "label", label);
    }

    private static JsonObject findByIndexPath(JsonObject node, String path) {
        return find(node, "index_path", path);
    }

    private static JsonObject find(JsonObject node, String key, String value) {
        JsonElement candidate = node.get(key);
        if (candidate != null && candidate.isJsonPrimitive()
                && value.equals(candidate.getAsString())) {
            return node;
        }
        JsonArray children = node.getAsJsonArray("children");
        if (children == null) return null;
        for (JsonElement child : children) {
            JsonObject match = find(child.getAsJsonObject(), key, value);
            if (match != null) return match;
        }
        return null;
    }
}
