package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import ij.ImagePlus;
import ij.gui.ImageCanvas;

import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One masked, structured view of an AWT/Swing component for the automation
 * bridge.
 *
 * <p>A node carries everything the external harness needs to decide what to do
 * and where to click: opaque identity, hierarchy position, role, the exact
 * semantic attributes it should match on, interaction state, and two
 * unambiguous coordinate systems.</p>
 *
 * <h2>Coordinate systems</h2>
 * <ul>
 *   <li>{@code bounds} — the component rectangle expressed in its window's
 *       coordinate space, with the window's outer top-left corner as origin.
 *       Includes window decorations, so {@code bounds} of a component inside a
 *       decorated dialog is offset by the title bar height.</li>
 *   <li>{@code screen_bounds} and {@code screen_center} — absolute coordinates
 *       on the virtual screen, taken from
 *       {@link Component#getLocationOnScreen()}. These are the coordinates a
 *       physical mouse driver must use. They are omitted when the component is
 *       not showing, because they are undefined then.</li>
 * </ul>
 *
 * <h2>Masking</h2>
 * <p>Password components and components whose name or label reads like a
 * credential never expose text or value. They report {@code masked: true} and a
 * character count only. Masking is applied at construction time, so a masked
 * value cannot reach a response, an event, or a diff.</p>
 *
 * <h2>AWT menu nodes</h2>
 * <p>ImageJ 1.x builds its main menu from {@link java.awt.MenuBar},
 * {@link java.awt.Menu} and {@link java.awt.MenuItem}, which extend
 * {@link java.awt.MenuComponent} and so are unreachable from a
 * {@code Container.getComponents()} walk. Those get their own construction path
 * ({@link #menuNode}). A menu node carries identity — id, role, label,
 * {@code parent_id}, {@code index_path}, {@code enabled} and any keyboard
 * shortcut — but <strong>no geometry</strong>: the AWT menu bar is drawn by the
 * native window manager and the JVM is never told where its items are. A
 * physical driver measures the rectangle itself from the numeric index path;
 * publishing a guessed rectangle would be worse than publishing none.</p>
 */
public final class UiNode {

    // Semantic roles. Stable strings — the harness matches on these.
    public static final String ROLE_FRAME = "frame";
    public static final String ROLE_DIALOG = "dialog";
    public static final String ROLE_WINDOW = "window";
    public static final String ROLE_BUTTON = "button";
    public static final String ROLE_CHECKBOX = "checkbox";
    public static final String ROLE_RADIO = "radio";
    public static final String ROLE_TOGGLE = "toggle";
    public static final String ROLE_TEXT = "text";
    public static final String ROLE_PASSWORD = "password";
    public static final String ROLE_TEXTAREA = "textarea";
    public static final String ROLE_COMBO = "combo";
    public static final String ROLE_LIST = "list";
    public static final String ROLE_SLIDER = "slider";
    public static final String ROLE_SCROLLBAR = "scrollbar";
    public static final String ROLE_SPINNER = "spinner";
    public static final String ROLE_TABS = "tabs";
    public static final String ROLE_TABLE = "table";
    public static final String ROLE_TREE = "tree";
    public static final String ROLE_LABEL = "label";
    public static final String ROLE_MENU_BAR = "menu_bar";
    public static final String ROLE_MENU = "menu";
    public static final String ROLE_MENU_ITEM = "menu_item";
    public static final String ROLE_CANVAS = "canvas";
    public static final String ROLE_IMAGE_CANVAS = "image_canvas";
    public static final String ROLE_PANEL = "panel";
    public static final String ROLE_SCROLL_PANE = "scroll_pane";
    public static final String ROLE_PROGRESS = "progress";
    public static final String ROLE_OTHER = "other";

    // Semantic actions. Exactly the set perform_ui_action understands.
    public static final String ACTION_FOCUS = "focus";
    public static final String ACTION_ACTIVATE = "activate";
    public static final String ACTION_SET_TEXT = "set_text";
    public static final String ACTION_SET_SELECTED = "set_selected";
    public static final String ACTION_SET_NUMBER = "set_number";
    public static final String ACTION_SELECT_ITEM = "select_item";
    public static final String ACTION_SELECT_TAB = "select_tab";
    public static final String ACTION_SELECT_ROW = "select_row";
    public static final String ACTION_EXPAND = "expand";
    public static final String ACTION_COLLAPSE = "collapse";
    public static final String ACTION_CLOSE_WINDOW = "close_window";

    private static final String[] SECRET_HINTS = {
            "password", "passphrase", "secret", "api key", "api_key", "apikey",
            "token", "credential", "private key"
    };

    private final Component component;
    private final java.awt.MenuComponent menuComponent;
    private final String shortcut;
    private final List<String> menuPath;
    private final String id;
    private final String windowId;
    private final String parentId;
    private final String indexPath;
    private final int depth;
    private final long generation;
    private final String role;
    private final String className;
    private final String name;
    private final String accessibleName;
    private final String text;
    private final String label;
    private final String labelSource;
    private final String tooltip;
    private final boolean enabled;
    private final boolean visible;
    private final boolean showing;
    private final boolean focusable;
    private final boolean focused;
    private final boolean masked;
    private final int maskedLength;
    private final Rectangle bounds;
    private final Rectangle screenBounds;
    private final JsonObject canvasView;
    private final Boolean selected;
    private final String value;
    private final Double numericValue;
    private final Double numericMinimum;
    private final Double numericMaximum;
    private final List<String> items;
    private final int itemCount;
    private final Integer selectedIndex;
    private final Integer rowCount;
    private final Integer columnCount;
    private final Integer barMenuCount;
    private final Integer barHelpMenuIndex;
    private final List<String> actions;
    private final List<UiNode> children = new ArrayList<UiNode>();

    UiNode(Component component, String id, String windowId, String parentId,
           String indexPath, int depth, long generation, Point windowOrigin) {
        this.component = component;
        this.menuComponent = null;
        this.shortcut = null;
        this.menuPath = null;
        this.id = id;
        this.windowId = windowId;
        this.parentId = parentId;
        this.indexPath = indexPath;
        this.depth = depth;
        this.generation = generation;
        this.role = roleOf(component);
        this.className = component.getClass().getName();
        this.name = bounded(component.getName());
        this.accessibleName = bounded(accessibleNameOf(component));
        this.masked = isMasked(component);
        String ownText = ownTextOf(component);
        this.maskedLength = masked ? maskedLengthOf(component) : 0;
        this.text = masked ? null : bounded(ownText);
        String[] labelAndSource = labelOf(component);
        this.label = masked ? null : bounded(labelAndSource[0]);
        this.labelSource = labelAndSource[1];
        this.tooltip = masked ? null : bounded(tooltipOf(component));
        this.enabled = component.isEnabled();
        this.visible = component.isVisible();
        this.showing = component.isShowing();
        this.focusable = component.isFocusable();
        this.focused = component.isFocusOwner();
        this.bounds = windowRelativeBounds(component, windowOrigin);
        this.screenBounds = screenBoundsOf(component);
        this.canvasView = canvasViewOf(component);
        this.selected = selectedOf(component);
        this.value = masked ? null : bounded(valueOf(component));
        double[] numeric = numericOf(component);
        this.numericValue = numeric == null ? null : Double.valueOf(numeric[0]);
        this.numericMinimum = numeric == null ? null : Double.valueOf(numeric[1]);
        this.numericMaximum = numeric == null ? null : Double.valueOf(numeric[2]);
        int[] itemCounts = new int[] {0, -1};
        this.items = itemsOf(component, itemCounts);
        this.itemCount = itemCounts[0];
        this.selectedIndex = itemCounts[1] >= 0
                ? Integer.valueOf(itemCounts[1]) : selectedRowOf(component);
        int[] table = tableShapeOf(component);
        this.rowCount = table == null ? null : Integer.valueOf(table[0]);
        this.columnCount = table == null ? null : Integer.valueOf(table[1]);
        this.barMenuCount = null;
        this.barHelpMenuIndex = null;
        this.actions = actionsFor(this.role, component);
    }

    /**
     * Construct a node for an AWT menu component.
     *
     * <p>{@link java.awt.MenuComponent} is not a {@link Component}, so none of
     * the component accessors apply: there is no accessible geometry, no focus
     * state, and no Swing label association. What a menu node publishes is
     * identity — enough for a harness to match "Process &gt; Filters &gt;
     * Gaussian Blur..." and to walk the same positions with a native menu API.
     * The numeric tail of {@code indexPath} is exactly that walk: the position
     * of each item within its parent menu.</p>
     *
     * @param showing whether the frame that owns the menu bar is on screen; the
     *                menu itself cannot be asked
     */
    static UiNode menuNode(java.awt.MenuComponent menu, String id, String windowId,
                           String parentId, String indexPath, int depth,
                           long generation, boolean showing) {
        return new UiNode(menu, id, windowId, parentId, indexPath, depth,
                generation, showing, null, null);
    }

    /**
     * Construct the node for a frame's AWT menu bar, carrying the bar's own
     * shape as well as its identity.
     *
     * <p>A physical driver cannot see this menu bar — it walks the native one
     * with {@code GetMenu}/{@code GetSubMenu}/{@code GetMenuItemRect} — and has
     * no way to check that the two are the same bar. {@code menu_count} is the
     * number to compare {@code GetMenuItemCount} against. If they differ, the
     * numeric segments of {@code index_path} are not the native positions and
     * every index below the bar is suspect; the harness can then say so instead
     * of walking into the wrong submenu.</p>
     *
     * <p>{@code child_count} is not a substitute. Published children are
     * skipped when a menu comes back null and stop early when the node budget
     * or the depth limit runs out, so a caller counting them cannot tell a
     * short bar from a truncated one.</p>
     *
     * <p>{@code help_menu_index} goes with it because the help menu is the one
     * bar entry a platform peer is entitled to place differently from its
     * position in {@link java.awt.MenuBar#getMenu(int)} — AWT tracks it
     * separately via {@code setHelpMenu}, which ImageJ 1.x calls. It is the
     * first thing to look at if the two counts disagree. Absent when the bar
     * has no help menu.</p>
     */
    static UiNode menuBarNode(java.awt.MenuBar bar, String id, String windowId,
                              String parentId, String indexPath, int depth,
                              long generation, boolean showing) {
        return new UiNode(bar, id, windowId, parentId, indexPath, depth,
                generation, showing, barMenuCountOf(bar), helpMenuIndexOf(bar));
    }

    private UiNode(java.awt.MenuComponent menu, String id, String windowId,
                   String parentId, String indexPath, int depth, long generation,
                   boolean showing, Integer barMenuCount, Integer barHelpMenuIndex) {
        this.component = null;
        this.menuComponent = menu;
        this.id = id;
        this.windowId = windowId;
        this.parentId = parentId;
        this.indexPath = indexPath;
        this.depth = depth;
        this.generation = generation;
        this.role = menuRoleOf(menu);
        this.className = menu.getClass().getName();
        this.name = bounded(safeMenuName(menu));
        String menuLabel = bounded(menuLabelOf(menu));
        this.accessibleName = menuLabel;
        this.text = menuLabel;
        this.label = menuLabel;
        this.labelSource = menuLabel == null ? "none" : "own_text";
        this.tooltip = null;
        this.enabled = menuEnabled(menu);
        this.visible = showing;
        this.showing = showing;
        this.focusable = false;
        this.focused = false;
        this.masked = false;
        this.maskedLength = 0;
        // Deliberately absent: the AWT menu bar is drawn by the window manager
        // and never reports its geometry to the JVM. A fabricated rectangle
        // would be worse than none, because a physical driver would click it.
        this.bounds = null;
        this.screenBounds = null;
        this.canvasView = null;
        this.selected = menu instanceof java.awt.CheckboxMenuItem
                ? Boolean.valueOf(((java.awt.CheckboxMenuItem) menu).getState())
                : null;
        this.value = null;
        this.numericValue = null;
        this.numericMinimum = null;
        this.numericMaximum = null;
        this.items = null;
        this.itemCount = 0;
        this.selectedIndex = null;
        this.rowCount = null;
        this.columnCount = null;
        this.barMenuCount = barMenuCount;
        this.barHelpMenuIndex = barHelpMenuIndex;
        this.shortcut = menuShortcutOf(menu);
        this.menuPath = menuPathOf(menu);
        this.actions = menuActionsFor(this.role, menu, this.enabled);
    }

    /**
     * The labels a user would read on their way to this entry, menu bar first:
     * {@code ["Process", "Filters", "Gaussian Blur..."]}.
     *
     * <p>Labels alone are not unique in a real Fiji — there is a {@code Process}
     * menu on the bar and another under {@code Plugins}, and two different
     * {@code Filters} submenus — so a caller matching on {@code label} has to
     * refuse as ambiguous. The full path is unique, is exactly how a scenario
     * names the command it wants, and only this JVM can produce it.</p>
     */
    private static List<String> menuPathOf(java.awt.MenuComponent menu) {
        if (menu instanceof java.awt.MenuBar) return null;
        List<String> reversed = new ArrayList<String>();
        Object current = menu;
        // Bounded: a corrupted menu graph must not spin here.
        for (int guard = 0; guard < 64 && current instanceof java.awt.MenuComponent;
                guard++) {
            if (current instanceof java.awt.MenuBar) break;
            String label = menuLabelOf((java.awt.MenuComponent) current);
            if (label == null) return null;
            reversed.add(label);
            try {
                current = ((java.awt.MenuComponent) current).getParent();
            } catch (Throwable brokenMenu) {
                return null;
            }
        }
        List<String> path = new ArrayList<String>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) path.add(reversed.get(i));
        return path;
    }

    void addChild(UiNode child) {
        children.add(child);
    }

    public String id() { return id; }
    public String windowId() { return windowId; }
    public String role() { return role; }
    public String label() { return label; }
    public String text() { return text; }
    public boolean isEnabled() { return enabled; }
    public boolean isShowing() { return showing; }
    public boolean isMasked() { return masked; }
    public List<UiNode> children() { return children; }
    public Component component() { return component; }
    /** The AWT menu component this node describes, or {@code null}. */
    public java.awt.MenuComponent menuComponent() { return menuComponent; }
    public List<String> actions() { return actions; }

    /** Total node count including this node. */
    public int size() {
        int total = 1;
        for (UiNode child : children) total += child.size();
        return total;
    }

    /** Full serialisation, including the child hierarchy. */
    public JsonObject toJson() {
        return toJson(true);
    }

    /** Serialisation with optional children — {@code get_ui_component} omits them. */
    public JsonObject toJson(boolean includeChildren) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("generation", generation);
        json.addProperty("window_id", windowId);
        if (parentId != null) json.addProperty("parent_id", parentId);
        json.addProperty("index_path", indexPath);
        json.addProperty("depth", depth);
        json.addProperty("role", role);
        json.addProperty("class_name", className);
        if (name != null) json.addProperty("name", name);
        if (accessibleName != null) json.addProperty("accessible_name", accessibleName);
        if (text != null) json.addProperty("text", text);
        if (label != null) json.addProperty("label", label);
        json.addProperty("label_source", labelSource);
        if (tooltip != null) json.addProperty("tooltip", tooltip);
        json.addProperty("enabled", enabled);
        json.addProperty("visible", visible);
        json.addProperty("showing", showing);
        json.addProperty("focusable", focusable);
        json.addProperty("focused", focused);
        json.addProperty("masked", masked);
        if (masked) json.addProperty("masked_length", maskedLength);
        if (shortcut != null) json.addProperty("shortcut", shortcut);
        if (menuPath != null) {
            JsonArray path = new JsonArray();
            for (String segment : menuPath) path.add(new JsonPrimitive(segment));
            json.add("menu_path", path);
        }
        // A menu node has no geometry at all rather than a zeroed rectangle,
        // so a consumer cannot mistake "unknown" for "at the window origin".
        if (bounds != null) json.add("bounds", rectangleJson(bounds));
        if (screenBounds != null) {
            json.add("screen_bounds", rectangleJson(screenBounds));
            JsonObject centre = new JsonObject();
            centre.addProperty("x", screenBounds.x + screenBounds.width / 2);
            centre.addProperty("y", screenBounds.y + screenBounds.height / 2);
            json.add("screen_center", centre);
        }
        if (canvasView != null) json.add("canvas_view", canvasView.deepCopy());
        if (selected != null) json.addProperty("selected", selected.booleanValue());
        if (value != null) json.addProperty("value", value);
        if (numericValue != null) {
            JsonObject numeric = new JsonObject();
            numeric.addProperty("value", numericValue);
            numeric.addProperty("minimum", numericMinimum);
            numeric.addProperty("maximum", numericMaximum);
            json.add("numeric", numeric);
        }
        if (items != null) {
            JsonArray array = new JsonArray();
            for (String item : items) array.add(new JsonPrimitive(item));
            json.add("items", array);
            json.addProperty("item_count", itemCount);
            json.addProperty("items_truncated", itemCount > items.size());
        }
        if (selectedIndex != null) json.addProperty("selected_index", selectedIndex);
        if (rowCount != null) json.addProperty("row_count", rowCount);
        if (columnCount != null) json.addProperty("column_count", columnCount);
        // Menu bar only. The count a native-menu driver reconciles against,
        // and the one bar position a platform peer may treat specially.
        if (barMenuCount != null) json.addProperty("menu_count", barMenuCount);
        if (barHelpMenuIndex != null) {
            json.addProperty("help_menu_index", barHelpMenuIndex);
        }
        JsonArray actionArray = new JsonArray();
        for (String action : actions) actionArray.add(new JsonPrimitive(action));
        json.add("actions", actionArray);
        if (includeChildren) {
            JsonArray childArray = new JsonArray();
            for (UiNode child : children) childArray.add(child.toJson(true));
            json.add("children", childArray);
        }
        json.addProperty("child_count", children.size());
        return json;
    }

    /**
     * The subset of state a semantic action can change. Used for the pre/post
     * evidence pair and the delta on {@code perform_ui_action}; deliberately
     * excludes hierarchy and geometry so a repaint does not read as a change.
     */
    public JsonObject semanticJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("role", role);
        json.addProperty("enabled", enabled);
        json.addProperty("visible", visible);
        json.addProperty("showing", showing);
        json.addProperty("focused", focused);
        json.addProperty("masked", masked);
        if (text != null) json.addProperty("text", text);
        if (selected != null) json.addProperty("selected", selected.booleanValue());
        if (value != null) json.addProperty("value", value);
        if (numericValue != null) json.addProperty("numeric_value", numericValue);
        if (selectedIndex != null) json.addProperty("selected_index", selectedIndex);
        return json;
    }

    /**
     * Field-wise difference between two {@link #semanticJson()} snapshots.
     * Each changed key maps to {@code {"from": ..., "to": ...}}.
     */
    public static JsonObject diff(JsonObject before, JsonObject after) {
        JsonObject delta = new JsonObject();
        if (before == null || after == null) return delta;
        Set<String> keys = new LinkedHashSet<String>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if ("id".equals(key) || "role".equals(key)) continue;
            JsonElement from = before.get(key);
            JsonElement to = after.get(key);
            if (from == null && to == null) continue;
            if (from != null && from.equals(to)) continue;
            JsonObject change = new JsonObject();
            change.add("from", from == null ? com.google.gson.JsonNull.INSTANCE : from);
            change.add("to", to == null ? com.google.gson.JsonNull.INSTANCE : to);
            delta.add(key, change);
        }
        return delta;
    }

    // -----------------------------------------------------------------------
    // Classification
    // -----------------------------------------------------------------------

    /**
     * Semantic role for a component. Ordering matters: {@code JRadioButton} and
     * {@code JCheckBox} both extend {@code JToggleButton}, and an AWT
     * {@code Checkbox} inside a {@code CheckboxGroup} is a radio button, not a
     * checkbox. Getting this wrong is what made the legacy flat inventory
     * classify GenericDialog radio groups as generic toggles.
     */
    public static String roleOf(Component component) {
        if (component == null) return ROLE_OTHER;
        if (component instanceof java.awt.Frame) return ROLE_FRAME;
        if (component instanceof java.awt.Dialog) return ROLE_DIALOG;
        if (component instanceof Window) return ROLE_WINDOW;
        if (component instanceof javax.swing.JRadioButtonMenuItem
                || component instanceof javax.swing.JCheckBoxMenuItem) return ROLE_MENU_ITEM;
        if (component instanceof javax.swing.JMenu) return ROLE_MENU;
        if (component instanceof javax.swing.JMenuItem) return ROLE_MENU_ITEM;
        // java.awt.MenuBar is a MenuComponent, not a Component, so it never
        // appears in a container walk; only the Swing menu bar can.
        if (component instanceof javax.swing.JMenuBar) return ROLE_MENU_BAR;
        if (component instanceof javax.swing.JPasswordField) return ROLE_PASSWORD;
        if (component instanceof javax.swing.JRadioButton) return ROLE_RADIO;
        if (component instanceof javax.swing.JCheckBox) return ROLE_CHECKBOX;
        if (component instanceof javax.swing.JToggleButton) return ROLE_TOGGLE;
        if (component instanceof javax.swing.JButton) return ROLE_BUTTON;
        if (component instanceof java.awt.Checkbox) {
            return ((java.awt.Checkbox) component).getCheckboxGroup() != null
                    ? ROLE_RADIO : ROLE_CHECKBOX;
        }
        if (component instanceof java.awt.Button) return ROLE_BUTTON;
        if (component instanceof javax.swing.JTextArea) return ROLE_TEXTAREA;
        if (component instanceof java.awt.TextArea) return ROLE_TEXTAREA;
        if (component instanceof javax.swing.JTextField) return ROLE_TEXT;
        if (component instanceof java.awt.TextField) return ROLE_TEXT;
        if (component instanceof javax.swing.text.JTextComponent) return ROLE_TEXTAREA;
        if (component instanceof javax.swing.JComboBox) return ROLE_COMBO;
        if (component instanceof java.awt.Choice) return ROLE_COMBO;
        if (component instanceof javax.swing.JList) return ROLE_LIST;
        if (component instanceof java.awt.List) return ROLE_LIST;
        if (component instanceof javax.swing.JSlider) return ROLE_SLIDER;
        if (component instanceof javax.swing.JScrollBar) return ROLE_SCROLLBAR;
        if (component instanceof java.awt.Scrollbar) return ROLE_SCROLLBAR;
        if (component instanceof javax.swing.JSpinner) return ROLE_SPINNER;
        if (component instanceof javax.swing.JTabbedPane) return ROLE_TABS;
        if (component instanceof javax.swing.JTable) return ROLE_TABLE;
        if (component instanceof javax.swing.JTree) return ROLE_TREE;
        if (component instanceof javax.swing.JProgressBar) return ROLE_PROGRESS;
        if (component instanceof javax.swing.JScrollPane) return ROLE_SCROLL_PANE;
        if (component instanceof JLabel) return ROLE_LABEL;
        if (component instanceof java.awt.Label) return ROLE_LABEL;
        if (isImageCanvas(component)) return ROLE_IMAGE_CANVAS;
        if (component instanceof java.awt.Canvas) return ROLE_CANVAS;
        if (component instanceof javax.swing.JPanel) return ROLE_PANEL;
        if (component instanceof java.awt.Panel) return ROLE_PANEL;
        return ROLE_OTHER;
    }

    /**
     * The current image-to-canvas transform for an ImageJ canvas.
     *
     * <p>Physical scenarios retain image coordinates, not desktop pixels or a
     * component-relative fraction captured before the user moved, zoomed or
     * resized the window. These facts let the external harness derive a fresh
     * point immediately before dispatch. They are copied while the UI tree is
     * already being built on the AWT event thread, so the rectangle, zoom and
     * component size describe one coherent view.</p>
     */
    private static JsonObject canvasViewOf(Component component) {
        if (!(component instanceof ImageCanvas)) return null;
        try {
            ImageCanvas canvas = (ImageCanvas) component;
            Rectangle source = canvas.getSrcRect();
            ImagePlus image = canvas.getImage();
            if (source == null || image == null || source.width <= 0 || source.height <= 0
                    || component.getWidth() <= 1 || component.getHeight() <= 1
                    || canvas.getMagnification() <= 0.0d) {
                return null;
            }
            JsonObject view = new JsonObject();
            view.addProperty("image_width", image.getWidth());
            view.addProperty("image_height", image.getHeight());
            view.addProperty("source_x", source.x);
            view.addProperty("source_y", source.y);
            view.addProperty("source_width", source.width);
            view.addProperty("source_height", source.height);
            view.addProperty("magnification", canvas.getMagnification());
            view.addProperty("canvas_width", component.getWidth());
            view.addProperty("canvas_height", component.getHeight());
            return view;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /**
     * Semantic role for an AWT menu component. {@code Menu} extends
     * {@code MenuItem} and {@code PopupMenu} extends {@code Menu}, so the most
     * specific test has to come first — otherwise every menu would surface as a
     * menu item and a scenario targeting {@code role: menu} would never match.
     */
    public static String menuRoleOf(java.awt.MenuComponent menu) {
        if (menu == null) return ROLE_OTHER;
        if (menu instanceof java.awt.MenuBar) return ROLE_MENU_BAR;
        if (menu instanceof java.awt.Menu) return ROLE_MENU;
        if (menu instanceof java.awt.MenuItem) return ROLE_MENU_ITEM;
        return ROLE_OTHER;
    }

    /**
     * True when this menu item is a separator. AWT has no separator type —
     * {@link java.awt.Menu#addSeparator()} inserts a {@code MenuItem} labelled
     * {@code "-"} — but it still occupies a position, so it is published rather
     * than skipped and the index path stays aligned with the real menu.
     */
    public static boolean isMenuSeparator(java.awt.MenuComponent menu) {
        if (!(menu instanceof java.awt.MenuItem) || menu instanceof java.awt.Menu) {
            return false;
        }
        String label = ((java.awt.MenuItem) menu).getLabel();
        return label == null || "-".equals(label.trim()) || label.trim().isEmpty();
    }

    /**
     * How many menus this bar holds, straight from AWT. The authoritative
     * count a native-menu driver checks its own against; see
     * {@link #menuBarNode}.
     */
    private static Integer barMenuCountOf(java.awt.MenuBar bar) {
        try {
            return bar == null ? null : Integer.valueOf(bar.getMenuCount());
        } catch (Throwable brokenMenuBar) {
            return null;
        }
    }

    /**
     * The position of this bar's help menu within
     * {@link java.awt.MenuBar#getMenu(int)}, or {@code null} when it has none
     * or AWT will not say. Reported by position rather than as a flag on the
     * menu itself because a caller comparing two bars is comparing sequences.
     */
    private static Integer helpMenuIndexOf(java.awt.MenuBar bar) {
        try {
            if (bar == null) return null;
            java.awt.Menu help = bar.getHelpMenu();
            if (help == null) return null;
            int count = bar.getMenuCount();
            for (int i = 0; i < count; i++) {
                if (bar.getMenu(i) == help) return Integer.valueOf(i);
            }
            // AWT holds a help menu that is not in the indexed sequence. That
            // is exactly the mismatch this field exists to expose, so it must
            // not be reported as a position that does not exist.
            return null;
        } catch (Throwable brokenMenuBar) {
            return null;
        }
    }

    private static String menuLabelOf(java.awt.MenuComponent menu) {
        try {
            if (menu instanceof java.awt.MenuItem) {
                String label = ((java.awt.MenuItem) menu).getLabel();
                return notBlank(label) ? label.trim() : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String safeMenuName(java.awt.MenuComponent menu) {
        try {
            return menu.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean menuEnabled(java.awt.MenuComponent menu) {
        try {
            return !(menu instanceof java.awt.MenuItem)
                    || ((java.awt.MenuItem) menu).isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The keyboard accelerator AWT holds for this item, rendered the way the
     * platform shows it. Publishing it matters more here than for a button: a
     * native menu cannot be measured from inside the JVM, so a caller that
     * knows the accelerator can invoke the command without clicking at all.
     */
    private static String menuShortcutOf(java.awt.MenuComponent menu) {
        try {
            if (!(menu instanceof java.awt.MenuItem)) return null;
            java.awt.MenuShortcut accelerator =
                    ((java.awt.MenuItem) menu).getShortcut();
            if (accelerator == null) return null;
            StringBuilder text = new StringBuilder();
            int mask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
            text.append(java.awt.event.KeyEvent.getKeyModifiersText(mask));
            if (accelerator.usesShiftModifier()) {
                text.append('+').append(java.awt.event.KeyEvent.getKeyModifiersText(
                        java.awt.event.InputEvent.SHIFT_MASK));
            }
            text.append('+').append(
                    java.awt.event.KeyEvent.getKeyText(accelerator.getKey()));
            return bounded(text.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Semantic actions for a menu node. A menu bar is structure, a separator is
     * decoration, and neither can be activated; an enabled menu or item can be,
     * which dispatches the item's own listeners exactly as AWT would.
     */
    private static List<String> menuActionsFor(String role, java.awt.MenuComponent menu,
                                               boolean enabled) {
        List<String> actions = new ArrayList<String>();
        if (!enabled || ROLE_MENU_BAR.equals(role) || isMenuSeparator(menu)) {
            return actions;
        }
        if (ROLE_MENU.equals(role) || ROLE_MENU_ITEM.equals(role)) {
            actions.add(ACTION_ACTIVATE);
        }
        if (menu instanceof java.awt.CheckboxMenuItem) {
            actions.add(ACTION_SET_SELECTED);
        }
        return actions;
    }

    private static boolean isImageCanvas(Component component) {
        for (Class<?> type = component.getClass(); type != null; type = type.getSuperclass()) {
            if ("ij.gui.ImageCanvas".equals(type.getName())) return true;
        }
        return false;
    }

    /**
     * True when this component's contents must never leave the JVM: a password
     * field, or a field whose own name/label reads like a credential.
     */
    public static boolean isMasked(Component component) {
        if (component == null) return false;
        if (component instanceof javax.swing.JPasswordField) return true;
        if (component instanceof java.awt.TextField
                && ((java.awt.TextField) component).getEchoChar() != 0) {
            return true;
        }
        if (!isValueBearing(component)) return false;
        StringBuilder hints = new StringBuilder();
        appendLower(hints, component.getName());
        appendLower(hints, accessibleNameOf(component));
        String[] label = labelOf(component);
        appendLower(hints, label[0]);
        if (component instanceof JComponent) {
            Object property = ((JComponent) component)
                    .getClientProperty("imagejai.automation.secret");
            if (Boolean.TRUE.equals(property)) return true;
        }
        String haystack = hints.toString();
        for (String hint : SECRET_HINTS) {
            if (haystack.contains(hint)) return true;
        }
        return false;
    }

    /**
     * How many characters a masked control holds. A scenario legitimately needs
     * to assert "the key field is populated" without the bridge ever copying
     * the characters, so the length is all that leaves the JVM.
     */
    private static int maskedLengthOf(Component component) {
        try {
            if (component instanceof javax.swing.text.JTextComponent) {
                return ((javax.swing.text.JTextComponent) component)
                        .getDocument().getLength();
            }
            if (component instanceof java.awt.TextComponent) {
                String value = ((java.awt.TextComponent) component).getText();
                return value == null ? 0 : value.length();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean isValueBearing(Component component) {
        return component instanceof javax.swing.text.JTextComponent
                || component instanceof java.awt.TextComponent;
    }

    private static void appendLower(StringBuilder target, String value) {
        if (value == null) return;
        target.append(value.toLowerCase(Locale.ROOT)).append(' ');
    }

    // -----------------------------------------------------------------------
    // Attribute extraction
    // -----------------------------------------------------------------------

    static String accessibleNameOf(Component component) {
        try {
            AccessibleContext context = component.getAccessibleContext();
            return context == null ? null : context.getAccessibleName();
        } catch (Throwable brokenPlugin) {
            // Third-party accessibility implementations do throw. Missing
            // metadata must never take down a snapshot.
            return null;
        }
    }

    static String ownTextOf(Component component) {
        try {
            if (component instanceof javax.swing.JPasswordField) return null;
            if (component instanceof javax.swing.AbstractButton) {
                return ((javax.swing.AbstractButton) component).getText();
            }
            if (component instanceof JLabel) return ((JLabel) component).getText();
            if (component instanceof java.awt.Label) {
                return ((java.awt.Label) component).getText();
            }
            if (component instanceof java.awt.Button) {
                return ((java.awt.Button) component).getLabel();
            }
            if (component instanceof java.awt.Checkbox) {
                return ((java.awt.Checkbox) component).getLabel();
            }
            if (component instanceof java.awt.Dialog) {
                return ((java.awt.Dialog) component).getTitle();
            }
            if (component instanceof java.awt.Frame) {
                return ((java.awt.Frame) component).getTitle();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Resolve the label a human would associate with a control. Priority is
     * Swing {@code labelFor}, then the accessible name, then the component's
     * own text, then the nearest preceding label in the same container. The
     * source is reported so the harness can tell an exact association from a
     * positional guess.
     */
    static String[] labelOf(Component component) {
        String labelFor = labelForText(component);
        if (notBlank(labelFor)) return new String[] {labelFor.trim(), "label_for"};
        String accessible = accessibleNameOf(component);
        if (notBlank(accessible)) return new String[] {accessible.trim(), "accessible_name"};
        String own = ownTextOf(component);
        if (notBlank(own)) return new String[] {own.trim(), "own_text"};
        String nearest = nearestPrecedingLabel(component);
        if (notBlank(nearest)) return new String[] {nearest.trim(), "nearest_label"};
        return new String[] {null, "none"};
    }

    private static String labelForText(Component component) {
        Container parent = component.getParent();
        if (parent == null) return null;
        for (Component sibling : parent.getComponents()) {
            if (sibling instanceof JLabel
                    && ((JLabel) sibling).getLabelFor() == component) {
                return ((JLabel) sibling).getText();
            }
        }
        return null;
    }

    private static String nearestPrecedingLabel(Component component) {
        Container parent = component.getParent();
        if (parent == null) return null;
        Component[] siblings = parent.getComponents();
        String candidate = null;
        for (Component sibling : siblings) {
            if (sibling == component) return candidate;
            String text = null;
            if (sibling instanceof JLabel) text = ((JLabel) sibling).getText();
            else if (sibling instanceof java.awt.Label) {
                text = ((java.awt.Label) sibling).getText();
            }
            if (notBlank(text)) candidate = text;
        }
        return null;
    }

    private static String tooltipOf(Component component) {
        try {
            return component instanceof JComponent
                    ? ((JComponent) component).getToolTipText() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean selectedOf(Component component) {
        try {
            if (component instanceof javax.swing.AbstractButton
                    && !(component instanceof javax.swing.JButton)) {
                return Boolean.valueOf(
                        ((javax.swing.AbstractButton) component).isSelected());
            }
            if (component instanceof java.awt.Checkbox) {
                return Boolean.valueOf(((java.awt.Checkbox) component).getState());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String valueOf(Component component) {
        try {
            if (component instanceof javax.swing.JPasswordField) return null;
            if (component instanceof javax.swing.text.JTextComponent) {
                return ((javax.swing.text.JTextComponent) component).getText();
            }
            if (component instanceof java.awt.TextComponent) {
                return ((java.awt.TextComponent) component).getText();
            }
            if (component instanceof javax.swing.JComboBox) {
                Object selected = ((javax.swing.JComboBox<?>) component).getSelectedItem();
                return selected == null ? null : String.valueOf(selected);
            }
            if (component instanceof java.awt.Choice) {
                return ((java.awt.Choice) component).getSelectedItem();
            }
            if (component instanceof javax.swing.JSpinner) {
                Object value = ((javax.swing.JSpinner) component).getValue();
                return value == null ? null : String.valueOf(value);
            }
            // Sliders and scrollbars carry no text of their own; their state is
            // the numeric block below, which remains the authoritative and
            // typed form. This mirrors the position into `value` as a
            // convenience so that a client reading one field for "what does
            // this control currently say" gets an answer for every control it
            // can drive — set_number accepts all three of these.
            if (component instanceof javax.swing.JSlider) {
                return String.valueOf(((javax.swing.JSlider) component).getValue());
            }
            if (component instanceof javax.swing.JScrollBar) {
                return String.valueOf(((javax.swing.JScrollBar) component).getValue());
            }
            if (component instanceof java.awt.Scrollbar) {
                return String.valueOf(((java.awt.Scrollbar) component).getValue());
            }
            if (component instanceof javax.swing.JTabbedPane) {
                javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) component;
                int index = tabs.getSelectedIndex();
                return index >= 0 && index < tabs.getTabCount()
                        ? tabs.getTitleAt(index) : null;
            }
            if (component instanceof javax.swing.JList) {
                Object selected = ((javax.swing.JList<?>) component).getSelectedValue();
                return selected == null ? null : String.valueOf(selected);
            }
            if (component instanceof java.awt.List) {
                return ((java.awt.List) component).getSelectedItem();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** {@code {value, minimum, maximum}} for range controls, else {@code null}. */
    private static double[] numericOf(Component component) {
        try {
            if (component instanceof javax.swing.JSlider) {
                javax.swing.JSlider slider = (javax.swing.JSlider) component;
                return new double[] {slider.getValue(), slider.getMinimum(),
                        slider.getMaximum()};
            }
            if (component instanceof javax.swing.JScrollBar) {
                javax.swing.JScrollBar bar = (javax.swing.JScrollBar) component;
                return new double[] {bar.getValue(), bar.getMinimum(), bar.getMaximum()};
            }
            if (component instanceof java.awt.Scrollbar) {
                java.awt.Scrollbar bar = (java.awt.Scrollbar) component;
                return new double[] {bar.getValue(), bar.getMinimum(), bar.getMaximum()};
            }
            if (component instanceof javax.swing.JProgressBar) {
                javax.swing.JProgressBar bar = (javax.swing.JProgressBar) component;
                return new double[] {bar.getValue(), bar.getMinimum(), bar.getMaximum()};
            }
            if (component instanceof javax.swing.JSpinner) {
                javax.swing.SpinnerModel model =
                        ((javax.swing.JSpinner) component).getModel();
                if (model instanceof javax.swing.SpinnerNumberModel) {
                    javax.swing.SpinnerNumberModel numbers =
                            (javax.swing.SpinnerNumberModel) model;
                    double value = ((Number) numbers.getValue()).doubleValue();
                    double minimum = numbers.getMinimum() instanceof Number
                            ? ((Number) numbers.getMinimum()).doubleValue()
                            : Double.NEGATIVE_INFINITY;
                    double maximum = numbers.getMaximum() instanceof Number
                            ? ((Number) numbers.getMaximum()).doubleValue()
                            : Double.POSITIVE_INFINITY;
                    if (Double.isInfinite(minimum) || Double.isInfinite(maximum)) {
                        // JSON has no infinity. Report the value only.
                        return new double[] {value, value, value};
                    }
                    return new double[] {value, minimum, maximum};
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Enumerated choices, bounded. {@code counts[0]}=total, {@code counts[1]}=selected. */
    private static List<String> itemsOf(Component component, int[] counts) {
        try {
            if (component instanceof javax.swing.JComboBox) {
                javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) component;
                counts[0] = combo.getItemCount();
                counts[1] = combo.getSelectedIndex();
                List<String> items = new ArrayList<String>();
                int limit = Math.min(counts[0], AutomationPolicy.MAX_ITEMS_PER_NODE);
                for (int i = 0; i < limit; i++) {
                    Object item = combo.getItemAt(i);
                    items.add(bounded(item == null ? "" : String.valueOf(item)));
                }
                return items;
            }
            if (component instanceof java.awt.Choice) {
                java.awt.Choice choice = (java.awt.Choice) component;
                counts[0] = choice.getItemCount();
                counts[1] = choice.getSelectedIndex();
                List<String> items = new ArrayList<String>();
                int limit = Math.min(counts[0], AutomationPolicy.MAX_ITEMS_PER_NODE);
                for (int i = 0; i < limit; i++) items.add(bounded(choice.getItem(i)));
                return items;
            }
            if (component instanceof javax.swing.JTabbedPane) {
                javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) component;
                counts[0] = tabs.getTabCount();
                counts[1] = tabs.getSelectedIndex();
                List<String> items = new ArrayList<String>();
                int limit = Math.min(counts[0], AutomationPolicy.MAX_ITEMS_PER_NODE);
                for (int i = 0; i < limit; i++) items.add(bounded(tabs.getTitleAt(i)));
                return items;
            }
            if (component instanceof javax.swing.JList) {
                javax.swing.JList<?> list = (javax.swing.JList<?>) component;
                counts[0] = list.getModel().getSize();
                counts[1] = list.getSelectedIndex();
                List<String> items = new ArrayList<String>();
                int limit = Math.min(counts[0], AutomationPolicy.MAX_ITEMS_PER_NODE);
                for (int i = 0; i < limit; i++) {
                    Object item = list.getModel().getElementAt(i);
                    items.add(bounded(item == null ? "" : String.valueOf(item)));
                }
                return items;
            }
            if (component instanceof java.awt.List) {
                java.awt.List list = (java.awt.List) component;
                counts[0] = list.getItemCount();
                counts[1] = list.getSelectedIndex();
                List<String> items = new ArrayList<String>();
                int limit = Math.min(counts[0], AutomationPolicy.MAX_ITEMS_PER_NODE);
                for (int i = 0; i < limit; i++) items.add(bounded(list.getItem(i)));
                return items;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int[] tableShapeOf(Component component) {
        try {
            if (component instanceof javax.swing.JTable) {
                javax.swing.JTable table = (javax.swing.JTable) component;
                return new int[] {table.getRowCount(), table.getColumnCount()};
            }
            if (component instanceof javax.swing.JTree) {
                return new int[] {((javax.swing.JTree) component).getRowCount(), 1};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Selected row for tabular controls whose rows are not enumerated as items. */
    private static Integer selectedRowOf(Component component) {
        try {
            int selected = -1;
            if (component instanceof javax.swing.JTable) {
                selected = ((javax.swing.JTable) component).getSelectedRow();
            } else if (component instanceof javax.swing.JTree) {
                selected = ((javax.swing.JTree) component).getMinSelectionRow();
            }
            return selected < 0 ? null : Integer.valueOf(selected);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Semantic actions this node accepts. Physical mouse and keyboard input is
     * deliberately absent: the bridge never synthesises native input, and the
     * external harness owns that channel.
     */
    static List<String> actionsFor(String role, Component component) {
        List<String> actions = new ArrayList<String>();
        if (component != null && component.isFocusable()) actions.add(ACTION_FOCUS);
        if (ROLE_BUTTON.equals(role) || ROLE_MENU_ITEM.equals(role)
                || ROLE_MENU.equals(role)) {
            actions.add(ACTION_ACTIVATE);
        } else if (ROLE_CHECKBOX.equals(role) || ROLE_RADIO.equals(role)
                || ROLE_TOGGLE.equals(role)) {
            actions.add(ACTION_ACTIVATE);
            actions.add(ACTION_SET_SELECTED);
        } else if (ROLE_TEXT.equals(role) || ROLE_TEXTAREA.equals(role)) {
            actions.add(ACTION_SET_TEXT);
        } else if (ROLE_PASSWORD.equals(role)) {
            // Deliberately empty: the bridge never types into a password field.
            // The external harness must use physical keyboard input if a
            // scenario genuinely requires it.
            actions.clear();
        } else if (ROLE_COMBO.equals(role) || ROLE_LIST.equals(role)) {
            actions.add(ACTION_SELECT_ITEM);
        } else if (ROLE_SLIDER.equals(role) || ROLE_SCROLLBAR.equals(role)
                || ROLE_SPINNER.equals(role)) {
            actions.add(ACTION_SET_NUMBER);
        } else if (ROLE_TABS.equals(role)) {
            actions.add(ACTION_SELECT_TAB);
        } else if (ROLE_TABLE.equals(role)) {
            actions.add(ACTION_SELECT_ROW);
        } else if (ROLE_TREE.equals(role)) {
            actions.add(ACTION_SELECT_ROW);
            actions.add(ACTION_EXPAND);
            actions.add(ACTION_COLLAPSE);
        } else if (ROLE_FRAME.equals(role) || ROLE_DIALOG.equals(role)
                || ROLE_WINDOW.equals(role)) {
            actions.add(ACTION_CLOSE_WINDOW);
        }
        if (component != null && !component.isEnabled()) {
            // A disabled control is reported with no actions at all so a
            // scenario cannot "successfully" drive something a user could not.
            actions.clear();
        }
        return actions;
    }

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    private static Rectangle windowRelativeBounds(Component component, Point windowOrigin) {
        Rectangle screen = screenBoundsOf(component);
        if (screen != null && windowOrigin != null) {
            return new Rectangle(screen.x - windowOrigin.x, screen.y - windowOrigin.y,
                    screen.width, screen.height);
        }
        Rectangle local = component.getBounds();
        return new Rectangle(local.x, local.y, local.width, local.height);
    }

    static Rectangle screenBoundsOf(Component component) {
        try {
            if (!component.isShowing()) return null;
            Point origin = component.getLocationOnScreen();
            return new Rectangle(origin.x, origin.y,
                    component.getWidth(), component.getHeight());
        } catch (Throwable notRealised) {
            return null;
        }
    }

    private static JsonObject rectangleJson(Rectangle rectangle) {
        JsonObject json = new JsonObject();
        json.addProperty("x", rectangle == null ? 0 : rectangle.x);
        json.addProperty("y", rectangle == null ? 0 : rectangle.y);
        json.addProperty("width", rectangle == null ? 0 : rectangle.width);
        json.addProperty("height", rectangle == null ? 0 : rectangle.height);
        return json;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static String bounded(String value) {
        if (value == null) return null;
        String trimmed = value;
        if (trimmed.length() > AutomationPolicy.MAX_TEXT_CHARS) {
            trimmed = trimmed.substring(0, AutomationPolicy.MAX_TEXT_CHARS);
        }
        return trimmed;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
