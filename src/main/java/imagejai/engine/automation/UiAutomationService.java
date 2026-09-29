package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import imagejai.engine.EventBus;
import imagejai.engine.GuiActionDispatcher;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Test-only observation and semantic control of this JVM's AWT/Swing UI.
 *
 * <p>Everything here reads or writes Swing state on the event-dispatch thread,
 * resolves targets by exact opaque identity plus generation, and refuses rather
 * than guessing. There is no fuzzy label matching, no coordinate-based
 * targeting, and no synthesis of native mouse or keyboard input: physical input
 * belongs to the external harness, and the distinction is deliberate because a
 * semantic {@code activate} proves the listener path while a real click also
 * proves hit-testing, focus, and z-order.</p>
 *
 * <p>Scope of "owned": every window this JVM created. Nothing outside the
 * process is visible or reachable, and an id that no longer resolves to a live
 * component in a live window fails closed as {@code stale_ui_target}.</p>
 */
public final class UiAutomationService {

    /** Result codes shared with the command layer. */
    public static final String ERR_STALE = "stale_ui_target";
    public static final String ERR_UNKNOWN = "unknown_ui_target";
    public static final String ERR_NOT_ACTIONABLE = "ui_target_not_actionable";
    public static final String ERR_UNSUPPORTED = "ui_action_unsupported";
    public static final String ERR_EDT_TIMEOUT = "ui_edt_timeout";
    public static final String ERR_INVALID = "invalid_request";

    /** Owner classification for a window root. */
    public static final String OWNER_IMAGEJAI = "imagejai";
    public static final String OWNER_IMAGEJ = "imagej";
    public static final String OWNER_OTHER = "other";

    /** Thrown when a bounded EDT read did not complete inside its deadline. */
    public static final class EdtTimeoutException extends Exception {
        private static final long serialVersionUID = 1L;
        EdtTimeoutException(String message) { super(message); }
    }

    /** A resolved target, or the exact reason it could not be resolved. */
    public static final class Resolution {
        private final Component component;
        private final java.awt.MenuComponent menuComponent;
        private final Window window;
        private final String errorCode;
        private final String errorMessage;

        private Resolution(Component component, java.awt.MenuComponent menuComponent,
                           Window window, String errorCode, String errorMessage) {
            this.component = component;
            this.menuComponent = menuComponent;
            this.window = window;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static Resolution ok(Component component, Window window) {
            return new Resolution(component, null, window, null, null);
        }

        static Resolution menu(java.awt.MenuComponent menuComponent, Window window) {
            return new Resolution(null, menuComponent, window, null, null);
        }

        static Resolution failure(String code, String message) {
            return new Resolution(null, null, null, code, message);
        }

        public boolean isOk() { return errorCode == null; }
        public Component component() { return component; }
        /** The AWT menu component this id denotes, or {@code null}. */
        public java.awt.MenuComponent menuComponent() { return menuComponent; }
        /** True when the id denotes an AWT menu rather than a component. */
        public boolean isMenuTarget() { return menuComponent != null; }
        public Window window() { return window; }
        public String errorCode() { return errorCode; }
        public String errorMessage() { return errorMessage; }
    }

    private final UiIdentityRegistry identities;
    private final EventBus eventBus;
    private final AtomicInteger activeActions = new AtomicInteger();
    private final AtomicLong actionSequence = new AtomicLong();
    private volatile Supplier<Window[]> windowSupplier = new Supplier<Window[]>() {
        @Override public Window[] get() { return Window.getWindows(); }
    };

    public UiAutomationService(UiIdentityRegistry identities, EventBus eventBus) {
        this.identities = identities == null ? new UiIdentityRegistry() : identities;
        this.eventBus = eventBus;
    }

    public UiIdentityRegistry identities() { return identities; }

    /** Automation EDT actions currently in flight. Consumed by the idle monitor. */
    public int activeActionCount() { return activeActions.get(); }

    /** Test seam: supply a deterministic window set instead of the live desktop. */
    void setWindowSupplierForTest(Supplier<Window[]> supplier) {
        this.windowSupplier = supplier == null
                ? new Supplier<Window[]>() {
                    @Override public Window[] get() { return Window.getWindows(); }
                }
                : supplier;
    }

    // -----------------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------------

    /** Options controlling one {@code get_ui_tree} call. */
    public static final class SnapshotOptions {
        String windowId;
        boolean includeHidden;
        int maxNodes = AutomationPolicy.MAX_TREE_NODES;
        int maxDepth = AutomationPolicy.MAX_TREE_DEPTH;

        public SnapshotOptions windowId(String value) { this.windowId = value; return this; }
        public SnapshotOptions includeHidden(boolean value) {
            this.includeHidden = value; return this;
        }
        public SnapshotOptions maxNodes(int value) {
            this.maxNodes = Math.max(1, Math.min(AutomationPolicy.MAX_TREE_NODES, value));
            return this;
        }
        public SnapshotOptions maxDepth(int value) {
            this.maxDepth = Math.max(1, Math.min(AutomationPolicy.MAX_TREE_DEPTH, value));
            return this;
        }
    }

    /** Build a snapshot on the event thread within {@code timeoutMs}. */
    public UiTreeSnapshot snapshot(final SnapshotOptions options, long timeoutMs)
            throws EdtTimeoutException {
        return callOnEdt(new Supplier<UiTreeSnapshot>() {
            @Override public UiTreeSnapshot get() { return buildSnapshot(options); }
        }, timeoutMs);
    }

    UiTreeSnapshot buildSnapshot(SnapshotOptions options) {
        SnapshotOptions effective = options == null ? new SnapshotOptions() : options;
        List<UiTreeSnapshot.WindowEntry> entries =
                new ArrayList<UiTreeSnapshot.WindowEntry>();
        int[] budget = new int[] {effective.maxNodes};
        boolean[] truncated = new boolean[] {false};
        int nodeCount = 0;

        // Enumerate first: discovering a disposed window is itself an
        // invalidation, so the generation every node reports must be read
        // afterwards and must equal the one the snapshot publishes.
        List<Window> windows = ownedWindows(effective.includeHidden);
        long generation = identities.generation();

        for (Window window : windows) {
            String windowId = identities.windowId(window);
            if (effective.windowId != null && !effective.windowId.equals(windowId)) {
                continue;
            }
            Point origin = originOf(window);
            UiNode root = new UiNode(window, windowId, windowId, null, "", 0,
                    generation, origin);
            appendChildren(window, root, windowId, "", 1, effective, budget,
                    truncated, origin, generation);
            appendAwtMenuBar(window, root, windowId, effective, budget, truncated,
                    generation);
            entries.add(new UiTreeSnapshot.WindowEntry(root, windowExtras(window)));
            nodeCount += root.size();
        }

        return new UiTreeSnapshot(generation, System.currentTimeMillis(),
                UiTreeSnapshot.describeEnvironment(), entries, nodeCount, truncated[0]);
    }

    private void appendChildren(Container container, UiNode parent, String windowId,
                                String parentPath, int depth, SnapshotOptions options,
                                int[] budget, boolean[] truncated, Point origin,
                                long generation) {
        if (depth > options.maxDepth) {
            truncated[0] = true;
            return;
        }
        Component[] children;
        try {
            children = container.getComponents();
        } catch (Throwable brokenContainer) {
            return;
        }
        if (children == null) return;
        for (int i = 0; i < children.length; i++) {
            Component child = children[i];
            if (child == null) continue;
            if (!options.includeHidden && !child.isVisible()) continue;
            if (budget[0] <= 0) {
                truncated[0] = true;
                return;
            }
            budget[0]--;
            String path = parentPath.isEmpty() ? String.valueOf(i)
                    : parentPath + "/" + i;
            UiNode node = new UiNode(child, identities.nodeId(child), windowId,
                    parent.id(), path, depth, generation, origin);
            parent.addChild(node);
            if (child instanceof Container) {
                appendChildren((Container) child, node, windowId, path, depth + 1,
                        options, budget, truncated, origin, generation);
            }
            appendMenuBar(child, node, windowId, path, depth + 1, options, budget,
                    truncated, origin, generation);
        }
    }

    /**
     * A {@code JMenuBar}'s menus hold their items in a popup that is not a child
     * of the menu in the container tree until it is shown. Walk them explicitly
     * so a scenario can target "File &gt; Open" without first opening the menu.
     */
    private void appendMenuBar(Component component, UiNode node, String windowId,
                               String parentPath, int depth, SnapshotOptions options,
                               int[] budget, boolean[] truncated, Point origin,
                               long generation) {
        if (!(component instanceof javax.swing.JMenu)) return;
        javax.swing.JMenu menu = (javax.swing.JMenu) component;
        if (depth > options.maxDepth) {
            truncated[0] = true;
            return;
        }
        int count;
        try {
            count = menu.getItemCount();
        } catch (Throwable brokenMenu) {
            return;
        }
        for (int i = 0; i < count; i++) {
            javax.swing.JMenuItem item;
            try {
                item = menu.getItem(i);
            } catch (Throwable brokenMenu) {
                continue;
            }
            if (item == null) continue;
            if (budget[0] <= 0) {
                truncated[0] = true;
                return;
            }
            budget[0]--;
            String path = parentPath + "/menu:" + i;
            UiNode child = new UiNode(item, identities.nodeId(item), windowId,
                    node.id(), path, depth, generation, origin);
            node.addChild(child);
            appendMenuBar(item, child, windowId, path, depth + 1, options, budget,
                    truncated, origin, generation);
        }
    }

    /** Path segment that introduces a frame's AWT menu bar in {@code index_path}. */
    static final String MENU_PATH_ROOT = "menu_bar";

    /**
     * Publish a frame's AWT menu bar.
     *
     * <p>ImageJ 1.x builds its whole command surface from {@link java.awt.MenuBar},
     * {@link java.awt.Menu} and {@link java.awt.MenuItem}. Those extend
     * {@code MenuComponent}, not {@code Component}, so {@link #appendChildren}'s
     * {@code getComponents()} walk cannot reach them at any depth — which is why
     * a tree of the ImageJ main window used to contain no menu at all and no
     * scenario could open a menu-launched dialog. Swing's {@code JMenuBar} is a
     * real component and is already covered by the container walk.</p>
     *
     * <p>The index path is {@code menu_bar/<menu>/<item>[/<item>…]}, where every
     * numeric segment is the item's position inside its parent menu — including
     * separators, which AWT stores as ordinary items and which therefore occupy
     * a position in the native menu too. A caller that has to drive the native
     * menu (the JVM is never told where it is drawn) can walk exactly those
     * positions.</p>
     */
    private void appendAwtMenuBar(Window window, UiNode root, String windowId,
                                  SnapshotOptions options, int[] budget,
                                  boolean[] truncated, long generation) {
        if (!(window instanceof java.awt.Frame)) return;
        java.awt.MenuBar menuBar;
        try {
            menuBar = ((java.awt.Frame) window).getMenuBar();
        } catch (Throwable brokenFrame) {
            return;
        }
        if (menuBar == null) return;
        if (options.maxDepth < 1) {
            truncated[0] = true;
            return;
        }
        if (budget[0] <= 0) {
            truncated[0] = true;
            return;
        }
        budget[0]--;
        boolean showing = window.isShowing();
        // menuBarNode, not menuNode: the bar publishes its own AWT item count
        // so a driver walking the native menu can tell the two bars apart
        // instead of assuming they line up. See UiNode.menuBarNode.
        UiNode barNode = UiNode.menuBarNode(menuBar, identities.menuNodeId(menuBar),
                windowId, root.id(), MENU_PATH_ROOT, 1, generation, showing);
        root.addChild(barNode);

        int menuCount;
        try {
            menuCount = menuBar.getMenuCount();
        } catch (Throwable brokenMenuBar) {
            return;
        }
        for (int i = 0; i < menuCount; i++) {
            java.awt.Menu menu;
            try {
                menu = menuBar.getMenu(i);
            } catch (Throwable brokenMenuBar) {
                continue;
            }
            if (menu == null) continue;
            if (!appendAwtMenu(menu, barNode, windowId, MENU_PATH_ROOT + "/" + i, 2,
                    options, budget, truncated, generation, showing)) {
                return;
            }
        }
    }

    /**
     * Append one AWT menu and, recursively, its items.
     *
     * @return false when the node budget ran out and the caller must stop
     */
    private boolean appendAwtMenu(java.awt.MenuItem item, UiNode parent, String windowId,
                                  String path, int depth, SnapshotOptions options,
                                  int[] budget, boolean[] truncated, long generation,
                                  boolean showing) {
        if (depth > options.maxDepth) {
            truncated[0] = true;
            return true;
        }
        if (budget[0] <= 0) {
            truncated[0] = true;
            return false;
        }
        budget[0]--;
        UiNode node = UiNode.menuNode(item, identities.menuNodeId(item), windowId,
                parent.id(), path, depth, generation, showing);
        parent.addChild(node);

        if (!(item instanceof java.awt.Menu)) return true;
        java.awt.Menu menu = (java.awt.Menu) item;
        int itemCount;
        try {
            itemCount = menu.getItemCount();
        } catch (Throwable brokenMenu) {
            return true;
        }
        for (int i = 0; i < itemCount; i++) {
            java.awt.MenuItem child;
            try {
                child = menu.getItem(i);
            } catch (Throwable brokenMenu) {
                continue;
            }
            if (child == null) continue;
            if (!appendAwtMenu(child, node, windowId, path + "/" + i, depth + 1,
                    options, budget, truncated, generation, showing)) {
                return false;
            }
        }
        return true;
    }

    private JsonObject windowExtras(Window window) {
        JsonObject extras = new JsonObject();
        extras.addProperty("owner", ownerOf(window));
        extras.addProperty("active", window.isActive());
        extras.addProperty("focused", window.isFocused());
        extras.addProperty("always_on_top", safeAlwaysOnTop(window));
        if (window instanceof java.awt.Dialog) {
            java.awt.Dialog dialog = (java.awt.Dialog) window;
            extras.addProperty("modal", dialog.isModal());
            extras.addProperty("title", UiNode.bounded(nullToEmpty(dialog.getTitle())));
        } else if (window instanceof java.awt.Frame) {
            java.awt.Frame frame = (java.awt.Frame) window;
            extras.addProperty("modal", false);
            extras.addProperty("title", UiNode.bounded(nullToEmpty(frame.getTitle())));
            extras.addProperty("state", frame.getExtendedState());
        } else {
            extras.addProperty("modal", false);
            extras.addProperty("title", "");
        }
        return extras;
    }

    private static boolean safeAlwaysOnTop(Window window) {
        try {
            return window.isAlwaysOnTop();
        } catch (Throwable notPermitted) {
            return false;
        }
    }

    /**
     * Classify who built the window. Only informative: everything returned here
     * lives in this JVM, so there is no cross-process window to reject.
     */
    public static String ownerOf(Window window) {
        if (window == null) return OWNER_OTHER;
        String className = window.getClass().getName();
        if (className.startsWith("imagejai.")) return OWNER_IMAGEJAI;
        String title = titleOf(window);
        if (title != null && title.contains("AI Assistant")) return OWNER_IMAGEJAI;
        if (className.startsWith("ij.")) return OWNER_IMAGEJ;
        return OWNER_OTHER;
    }

    private static String titleOf(Window window) {
        if (window instanceof java.awt.Dialog) return ((java.awt.Dialog) window).getTitle();
        if (window instanceof java.awt.Frame) return ((java.awt.Frame) window).getTitle();
        return null;
    }

    private List<Window> ownedWindows(boolean includeHidden) {
        List<Window> windows = new ArrayList<Window>();
        Window[] all = windowSupplier.get();
        if (all == null) return windows;
        for (Window window : all) {
            if (window == null) continue;
            if (!includeHidden && !window.isShowing()) {
                // A window we already identified and that has gone away can no
                // longer be targeted; make that visible through the generation.
                if (identities.isKnown(window) && !window.isDisplayable()) {
                    identities.forget(window);
                }
                continue;
            }
            windows.add(window);
        }
        return windows;
    }

    private static Point originOf(Window window) {
        try {
            return window.isShowing() ? window.getLocationOnScreen() : new Point(0, 0);
        } catch (Throwable notRealised) {
            return new Point(0, 0);
        }
    }

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    /**
     * Resolve an opaque id against the live UI.
     *
     * @param requiredGeneration the generation the caller observed, or a
     *                           negative value to skip the staleness check
     *                           (reads may skip it; mutations never do)
     */
    public Resolution resolve(String id, long requiredGeneration) {
        if (!UiIdentityRegistry.looksLikeId(id)) {
            return Resolution.failure(ERR_INVALID,
                    "node_id/window_id must be an opaque id issued by get_ui_tree.");
        }
        Object target = identities.resolveTarget(id);
        if (target == null) {
            return Resolution.failure(ERR_UNKNOWN,
                    "That id is not known to this session; take a fresh get_ui_tree snapshot.");
        }
        if (target instanceof java.awt.MenuComponent) {
            return resolveMenu((java.awt.MenuComponent) target, requiredGeneration);
        }
        Component component = (Component) target;
        Window window = component instanceof Window
                ? (Window) component
                : javax.swing.SwingUtilities.getWindowAncestor(component);
        if (window == null || !window.isDisplayable()) {
            identities.forget(component);
            return Resolution.failure(ERR_STALE,
                    "The window that owned this component has been disposed.");
        }
        if (requiredGeneration >= 0 && requiredGeneration != identities.generation()) {
            return Resolution.failure(ERR_STALE,
                    "The UI snapshot generation advanced; take a fresh get_ui_tree snapshot.");
        }
        return Resolution.ok(component, window);
    }

    /**
     * Resolve an AWT menu target. A menu component has no window ancestor
     * chain of its own; walk its {@code MenuContainer} parents up to the frame
     * that owns the menu bar, which is what makes it addressable at all.
     */
    private Resolution resolveMenu(java.awt.MenuComponent menu, long requiredGeneration) {
        java.awt.Frame owner = menuOwnerFrame(menu);
        if (owner == null || !owner.isDisplayable()) {
            identities.forget(menu);
            return Resolution.failure(ERR_STALE,
                    "The frame that owned this menu has been disposed.");
        }
        if (requiredGeneration >= 0 && requiredGeneration != identities.generation()) {
            return Resolution.failure(ERR_STALE,
                    "The UI snapshot generation advanced; take a fresh get_ui_tree snapshot.");
        }
        return Resolution.menu(menu, owner);
    }

    /** The frame a menu component ultimately hangs from, or {@code null}. */
    static java.awt.Frame menuOwnerFrame(java.awt.MenuComponent menu) {
        Object current = menu;
        // Bounded: a corrupted menu graph must not spin here.
        for (int guard = 0; guard < 64 && current != null; guard++) {
            if (current instanceof java.awt.Frame) return (java.awt.Frame) current;
            if (!(current instanceof java.awt.MenuComponent)) return null;
            try {
                current = ((java.awt.MenuComponent) current).getParent();
            } catch (Throwable brokenMenu) {
                return null;
            }
        }
        return null;
    }

    /** Describe one resolved menu node. */
    public JsonObject describeMenu(final java.awt.MenuComponent menu, long timeoutMs)
            throws EdtTimeoutException {
        return callOnEdt(new Supplier<JsonObject>() {
            @Override public JsonObject get() { return describeMenuNow(menu); }
        }, timeoutMs);
    }

    JsonObject describeMenuNow(java.awt.MenuComponent menu) {
        java.awt.Frame owner = menuOwnerFrame(menu);
        String windowId = owner == null ? "" : identities.windowId(owner);
        Object parent = null;
        try {
            parent = menu.getParent();
        } catch (Throwable brokenMenu) {
            // A menu that cannot name its parent still describes itself.
        }
        String parentId = parent instanceof java.awt.MenuComponent
                ? identities.menuNodeId((java.awt.MenuComponent) parent)
                : (owner == null ? null : identities.windowId(owner));
        UiNode node = UiNode.menuNode(menu, identities.menuNodeId(menu), windowId,
                parentId, "", 0, identities.generation(),
                owner != null && owner.isShowing());
        JsonObject json = node.toJson(false);
        if (owner != null) json.addProperty("window_owner", ownerOf(owner));
        return json;
    }

    /** Semantic-only view of a menu node, for pre/post evidence. */
    JsonObject semanticMenuNow(java.awt.MenuComponent menu) {
        java.awt.Frame owner = menuOwnerFrame(menu);
        UiNode node = UiNode.menuNode(menu, identities.menuNodeId(menu),
                owner == null ? "" : identities.windowId(owner), null, "", 0,
                identities.generation(), owner != null && owner.isShowing());
        return node.semanticJson();
    }

    /** Describe one resolved node, optionally with its subtree. */
    public JsonObject describe(final Component component, final boolean includeChildren,
                               long timeoutMs) throws EdtTimeoutException {
        return callOnEdt(new Supplier<JsonObject>() {
            @Override public JsonObject get() {
                return describeNow(component, includeChildren);
            }
        }, timeoutMs);
    }

    JsonObject describeNow(Component component, boolean includeChildren) {
        Window window = component instanceof Window
                ? (Window) component
                : javax.swing.SwingUtilities.getWindowAncestor(component);
        Point origin = window == null ? new Point(0, 0) : originOf(window);
        String windowId = window == null ? "" : identities.windowId(window);
        Component parent = component.getParent();
        String parentId = parent == null ? null : identities.nodeId(parent);
        UiNode node = new UiNode(component, identities.nodeId(component), windowId,
                parentId, "", 0, identities.generation(), origin);
        if (includeChildren && component instanceof Container) {
            SnapshotOptions options = new SnapshotOptions();
            appendChildren((Container) component, node, windowId, "", 1, options,
                    new int[] {AutomationPolicy.MAX_TREE_NODES},
                    new boolean[] {false}, origin, identities.generation());
        }
        JsonObject json = node.toJson(includeChildren);
        if (window != null) {
            for (java.util.Map.Entry<String, JsonElement> extra
                    : windowExtras(window).entrySet()) {
                if (component == window) json.add(extra.getKey(), extra.getValue());
            }
            json.addProperty("window_owner", ownerOf(window));
        }
        return json;
    }

    /** Semantic-only view used for pre/post evidence. Must run on the EDT. */
    JsonObject semanticNow(Component component) {
        Window window = component instanceof Window
                ? (Window) component
                : javax.swing.SwingUtilities.getWindowAncestor(component);
        Point origin = window == null ? new Point(0, 0) : originOf(window);
        String windowId = window == null ? "" : identities.windowId(window);
        UiNode node = new UiNode(component, identities.nodeId(component), windowId,
                null, "", 0, identities.generation(), origin);
        return node.semanticJson();
    }

    // -----------------------------------------------------------------------
    // Semantic actions
    // -----------------------------------------------------------------------

    /** One semantic action request, already validated for shape. */
    public static final class ActionRequest {
        final String action;
        final JsonElement value;
        final Integer index;

        public ActionRequest(String action, JsonElement value, Integer index) {
            this.action = action;
            this.value = value;
            this.index = index;
        }
    }

    /**
     * Execute one semantic action. <strong>Must be called on the EDT</strong>
     * (the command layer routes it through the shared EDT operation registry so
     * a timed-out caller can poll rather than replay).
     *
     * @param submittedAtNanos monotonic timestamp taken before queueing, used
     *                         to report the queue delay the action experienced
     */
    public JsonObject performAction(Component component, Window window,
                                    ActionRequest request, long requiredGeneration,
                                    long submittedAtNanos) {
        long startedAtNanos = System.nanoTime();
        activeActions.incrementAndGet();
        String actionId = "act-" + actionSequence.incrementAndGet();
        publish("ui.action.started", actionEvent(actionId, request.action, null));
        try {
            if (!isEventThread()) {
                return error(ERR_NOT_ACTIONABLE,
                        "UI actions must execute on the Swing event thread.");
            }
            if (requiredGeneration >= 0
                    && requiredGeneration != identities.generation()) {
                return error(ERR_STALE, "The UI snapshot generation advanced "
                        + "before the action reached the event thread.");
            }
            if (!component.isShowing() && !UiNode.ACTION_CLOSE_WINDOW.equals(request.action)) {
                return error(ERR_NOT_ACTIONABLE,
                        "The target is not showing; a user could not interact with it.");
            }
            if (!component.isEnabled()) {
                return error(ERR_NOT_ACTIONABLE,
                        "The target is disabled; a user could not interact with it.");
            }
            String role = UiNode.roleOf(component);
            if (UiNode.ROLE_PASSWORD.equals(role)) {
                return error(ERR_UNSUPPORTED,
                        "The bridge never drives password components. Use physical input.");
            }
            List<String> allowed = UiNode.actionsFor(role, component);
            if (!allowed.contains(request.action)) {
                return error(ERR_UNSUPPORTED, "Action '" + request.action
                        + "' is not available for role '" + role + "'. Available: "
                        + allowed);
            }

            JsonObject preState = semanticNow(component);
            int[] dispatchCount = new int[] {0};
            JsonObject failure = applyAction(component, window, request, dispatchCount);
            long endedAtNanos = System.nanoTime();
            if (failure != null) return failure;
            JsonObject postState = semanticNow(component);

            JsonObject result = new JsonObject();
            result.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
            result.addProperty("action", request.action);
            result.addProperty("action_id", actionId);
            result.addProperty("generation", identities.generation());
            result.add("target", describeNow(component, false));
            result.add("pre_state", preState);
            result.add("post_state", postState);
            result.add("delta", UiNode.diff(preState, postState));
            JsonObject dispatch = new JsonObject();
            dispatch.addProperty("thread", Thread.currentThread().getName());
            dispatch.addProperty("on_event_thread", true);
            dispatch.addProperty("count", dispatchCount[0]);
            dispatch.addProperty("queue_ms", millisBetween(submittedAtNanos, startedAtNanos));
            dispatch.addProperty("handler_ms", millisBetween(startedAtNanos, endedAtNanos));
            result.add("dispatch", dispatch);
            result.addProperty("window_still_showing",
                    window != null && window.isShowing());
            publish("ui.action.completed",
                    actionEvent(actionId, request.action, "ok"));
            return success(result);
        } catch (Throwable failure) {
            publish("ui.action.completed",
                    actionEvent(actionId, request.action, "error"));
            return error(ERR_NOT_ACTIONABLE, "The UI action failed: "
                    + failure.getClass().getSimpleName());
        } finally {
            activeActions.decrementAndGet();
        }
    }

    /**
     * Execute one semantic action against an AWT menu item.
     *
     * <p>Menu items are the whole of ImageJ 1.x's command surface, so this is
     * the only way an in-process caller can invoke a command through the same
     * path a click takes. It delivers the item's own {@code ActionEvent} to the
     * item's own listeners, exactly once, on the event thread — which is what
     * the AWT peer does when the user releases the mouse. It does not open the
     * native menu: no window manager is involved, so this proves the listener
     * path but not hit-testing or z-order. That distinction is the same one the
     * component path draws, and physical input still belongs to the harness.</p>
     *
     * <strong>Must be called on the EDT.</strong>
     */
    public JsonObject performMenuAction(java.awt.MenuComponent menu, Window window,
                                        ActionRequest request, long requiredGeneration,
                                        long submittedAtNanos) {
        long startedAtNanos = System.nanoTime();
        activeActions.incrementAndGet();
        String actionId = "act-" + actionSequence.incrementAndGet();
        publish("ui.action.started", actionEvent(actionId, request.action, null));
        try {
            if (!isEventThread()) {
                return error(ERR_NOT_ACTIONABLE,
                        "UI actions must execute on the Swing event thread.");
            }
            if (requiredGeneration >= 0
                    && requiredGeneration != identities.generation()) {
                return error(ERR_STALE, "The UI snapshot generation advanced "
                        + "before the action reached the event thread.");
            }
            if (window == null || !window.isShowing()) {
                return error(ERR_NOT_ACTIONABLE,
                        "The frame that owns this menu is not showing.");
            }
            if (!(menu instanceof java.awt.MenuItem)
                    || !((java.awt.MenuItem) menu).isEnabled()) {
                return error(ERR_NOT_ACTIONABLE,
                        "The menu entry is disabled; a user could not invoke it.");
            }
            if (UiNode.isMenuSeparator(menu)) {
                return error(ERR_NOT_ACTIONABLE,
                        "That entry is a separator, not a command.");
            }
            String role = UiNode.menuRoleOf(menu);
            java.awt.MenuItem item = (java.awt.MenuItem) menu;

            JsonObject preState = semanticMenuNow(menu);
            int[] dispatchCount = new int[] {0};
            JsonObject failure;
            if (UiNode.ACTION_ACTIVATE.equals(request.action)) {
                failure = activateMenuItem(item, dispatchCount);
            } else if (UiNode.ACTION_SET_SELECTED.equals(request.action)
                    && item instanceof java.awt.CheckboxMenuItem) {
                Boolean desired = asBoolean(request.value);
                failure = desired == null
                        ? error(ERR_INVALID, "set_selected requires a boolean 'value'.")
                        : setCheckboxMenuItem((java.awt.CheckboxMenuItem) item,
                                desired.booleanValue(), dispatchCount);
            } else {
                failure = error(ERR_UNSUPPORTED, "Action '" + request.action
                        + "' is not available for role '" + role + "'. Available: "
                        + UiNode.menuNode(menu, "", "", null, "", 0, 0L, true).actions());
            }
            long endedAtNanos = System.nanoTime();
            if (failure != null) return failure;
            JsonObject postState = semanticMenuNow(menu);

            JsonObject result = new JsonObject();
            result.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
            result.addProperty("action", request.action);
            result.addProperty("action_id", actionId);
            result.addProperty("generation", identities.generation());
            result.add("target", describeMenuNow(menu));
            result.add("pre_state", preState);
            result.add("post_state", postState);
            result.add("delta", UiNode.diff(preState, postState));
            JsonObject dispatch = new JsonObject();
            dispatch.addProperty("thread", Thread.currentThread().getName());
            dispatch.addProperty("on_event_thread", true);
            dispatch.addProperty("count", dispatchCount[0]);
            dispatch.addProperty("queue_ms", millisBetween(submittedAtNanos, startedAtNanos));
            dispatch.addProperty("handler_ms", millisBetween(startedAtNanos, endedAtNanos));
            result.add("dispatch", dispatch);
            result.addProperty("window_still_showing", window.isShowing());
            publish("ui.action.completed",
                    actionEvent(actionId, request.action, "ok"));
            return success(result);
        } catch (Throwable failure) {
            publish("ui.action.completed",
                    actionEvent(actionId, request.action, "error"));
            return error(ERR_NOT_ACTIONABLE, "The UI action failed: "
                    + failure.getClass().getSimpleName());
        } finally {
            activeActions.decrementAndGet();
        }
    }

    private JsonObject activateMenuItem(java.awt.MenuItem item, int[] dispatchCount) {
        java.awt.event.ActionListener[] listeners = item.getActionListeners();
        if (listeners.length == 0) {
            return error(ERR_NOT_ACTIONABLE, item instanceof java.awt.Menu
                    ? "A submenu has no command of its own; target one of its items."
                    : "That menu entry has no action listener to invoke.");
        }
        java.awt.event.ActionEvent event = new java.awt.event.ActionEvent(
                item, java.awt.event.ActionEvent.ACTION_PERFORMED,
                item.getActionCommand());
        for (java.awt.event.ActionListener listener : listeners) {
            listener.actionPerformed(event);
        }
        dispatchCount[0]++;
        return null;
    }

    private JsonObject setCheckboxMenuItem(java.awt.CheckboxMenuItem item, boolean desired,
                                           int[] dispatchCount) {
        if (item.getState() == desired) return null;
        item.setState(desired);
        java.awt.event.ItemEvent event = new java.awt.event.ItemEvent(
                item, java.awt.event.ItemEvent.ITEM_STATE_CHANGED, item.getLabel(),
                desired ? java.awt.event.ItemEvent.SELECTED
                        : java.awt.event.ItemEvent.DESELECTED);
        for (java.awt.event.ItemListener listener : item.getItemListeners()) {
            listener.itemStateChanged(event);
        }
        dispatchCount[0]++;
        return null;
    }

    /** Returns null on success, or the structured failure to report. */
    private JsonObject applyAction(Component component, Window window,
                                   ActionRequest request, int[] dispatchCount) {
        String action = request.action;
        if (UiNode.ACTION_FOCUS.equals(action)) {
            // requestFocusInWindow() is documented to do nothing at all unless
            // this component's top-level window is *already* the focused
            // window. Discarding the boolean therefore reported a guaranteed
            // no-op as a delivered dispatch: an external driver saw
            // count == 1 while `isFocusOwner()` — which is what UiNode
            // publishes as `focused` — stayed false, so the write and the read
            // disagreed by construction. Measured against a Fiji sitting
            // behind ImageJAI's own console window.
            //
            // The action is not made to succeed here. Raising the window would
            // turn a semantic action into one that seizes the desktop, which
            // is the one thing the semantic surface exists not to do. It
            // reports what happened and lets the caller decide.
            boolean granted = component.requestFocusInWindow();
            if (granted) dispatchCount[0]++;
            return null;
        }
        if (UiNode.ACTION_ACTIVATE.equals(action)) {
            return activate(component, dispatchCount);
        }
        if (UiNode.ACTION_SET_SELECTED.equals(action)) {
            Boolean desired = asBoolean(request.value);
            if (desired == null) {
                return error(ERR_INVALID, "set_selected requires a boolean 'value'.");
            }
            return setSelected(component, desired.booleanValue(), dispatchCount);
        }
        if (UiNode.ACTION_SET_TEXT.equals(action)) {
            String text = asString(request.value);
            if (text == null) {
                return error(ERR_INVALID, "set_text requires a string 'value'.");
            }
            return setText(component, text, dispatchCount);
        }
        if (UiNode.ACTION_SET_NUMBER.equals(action)) {
            Double number = asDouble(request.value);
            if (number == null) {
                return error(ERR_INVALID, "set_number requires a numeric 'value'.");
            }
            return setNumber(component, number.doubleValue(), dispatchCount);
        }
        if (UiNode.ACTION_SELECT_ITEM.equals(action)) {
            return selectItem(component, request, dispatchCount);
        }
        if (UiNode.ACTION_SELECT_TAB.equals(action)) {
            return selectTab(component, request, dispatchCount);
        }
        if (UiNode.ACTION_SELECT_ROW.equals(action)) {
            return selectRow(component, request, dispatchCount);
        }
        if (UiNode.ACTION_EXPAND.equals(action) || UiNode.ACTION_COLLAPSE.equals(action)) {
            return expandOrCollapse(component, request,
                    UiNode.ACTION_EXPAND.equals(action), dispatchCount);
        }
        if (UiNode.ACTION_CLOSE_WINDOW.equals(action)) {
            Window target = component instanceof Window ? (Window) component : window;
            if (target == null) {
                return error(ERR_UNSUPPORTED, "close_window requires a window target.");
            }
            target.dispatchEvent(new java.awt.event.WindowEvent(
                    target, java.awt.event.WindowEvent.WINDOW_CLOSING));
            dispatchCount[0]++;
            return null;
        }
        return error(ERR_UNSUPPORTED, "Unknown action: " + action);
    }

    private JsonObject activate(Component component, int[] dispatchCount) {
        if (component instanceof javax.swing.AbstractButton) {
            ((javax.swing.AbstractButton) component).doClick();
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof java.awt.Button) {
            java.awt.Button button = (java.awt.Button) component;
            java.awt.event.ActionEvent event = new java.awt.event.ActionEvent(
                    button, java.awt.event.ActionEvent.ACTION_PERFORMED,
                    button.getActionCommand());
            for (java.awt.event.ActionListener listener : button.getActionListeners()) {
                listener.actionPerformed(event);
            }
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof java.awt.Checkbox) {
            java.awt.Checkbox checkbox = (java.awt.Checkbox) component;
            return setAwtCheckbox(checkbox, !checkbox.getState(), dispatchCount);
        }
        return error(ERR_UNSUPPORTED,
                "activate is not implemented for " + component.getClass().getName());
    }

    private JsonObject setSelected(Component component, boolean desired,
                                   int[] dispatchCount) {
        if (component instanceof javax.swing.AbstractButton) {
            javax.swing.AbstractButton button = (javax.swing.AbstractButton) component;
            if (button.isSelected() != desired) {
                // doClick keeps ButtonGroup bookkeeping and listeners honest;
                // setSelected alone would skip the action listeners a plugin
                // relies on.
                button.doClick();
                dispatchCount[0]++;
            }
            if (button.isSelected() != desired) {
                return error(ERR_NOT_ACTIONABLE,
                        "The control refused the requested selection state.");
            }
            return null;
        }
        if (component instanceof java.awt.Checkbox) {
            return setAwtCheckbox((java.awt.Checkbox) component, desired, dispatchCount);
        }
        return error(ERR_UNSUPPORTED,
                "set_selected is not implemented for " + component.getClass().getName());
    }

    private JsonObject setAwtCheckbox(java.awt.Checkbox checkbox, boolean desired,
                                      int[] dispatchCount) {
        if (checkbox.getState() == desired) return null;
        checkbox.setState(desired);
        java.awt.event.ItemEvent event = new java.awt.event.ItemEvent(
                checkbox, java.awt.event.ItemEvent.ITEM_STATE_CHANGED,
                checkbox.getLabel(),
                desired ? java.awt.event.ItemEvent.SELECTED
                        : java.awt.event.ItemEvent.DESELECTED);
        for (java.awt.event.ItemListener listener : checkbox.getItemListeners()) {
            listener.itemStateChanged(event);
        }
        dispatchCount[0]++;
        return null;
    }

    /**
     * Replace a text control's contents and notify its listeners.
     *
     * <p>No {@code ActionEvent} is posted: on a {@code GenericDialog} that would
     * be indistinguishable from pressing Enter and could submit the dialog. A
     * scenario that genuinely needs Enter must use the harness's physical
     * keyboard channel.</p>
     */
    private JsonObject setText(Component component, String text, int[] dispatchCount) {
        if (component instanceof javax.swing.text.JTextComponent) {
            javax.swing.text.JTextComponent field =
                    (javax.swing.text.JTextComponent) component;
            if (!field.isEditable()) {
                return error(ERR_NOT_ACTIONABLE, "The text component is read-only.");
            }
            field.setText(text);
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof java.awt.TextComponent) {
            java.awt.TextComponent field = (java.awt.TextComponent) component;
            if (!field.isEditable()) {
                return error(ERR_NOT_ACTIONABLE, "The text component is read-only.");
            }
            field.setText(text);
            // AWT only fires TEXT_VALUE_CHANGED for peer-driven edits, so a
            // GenericDialog preview listener would never see a programmatic
            // setText. Deliver it explicitly and exactly once.
            java.awt.event.TextEvent event = new java.awt.event.TextEvent(
                    field, java.awt.event.TextEvent.TEXT_VALUE_CHANGED);
            for (java.awt.event.TextListener listener : field.getTextListeners()) {
                listener.textValueChanged(event);
            }
            dispatchCount[0]++;
            return null;
        }
        return error(ERR_UNSUPPORTED,
                "set_text is not implemented for " + component.getClass().getName());
    }

    private JsonObject setNumber(Component component, double value, int[] dispatchCount) {
        if (component instanceof javax.swing.JSlider) {
            javax.swing.JSlider slider = (javax.swing.JSlider) component;
            slider.setValue(clampInt(value, slider.getMinimum(), slider.getMaximum()));
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof javax.swing.JScrollBar) {
            javax.swing.JScrollBar bar = (javax.swing.JScrollBar) component;
            bar.setValue(clampInt(value, bar.getMinimum(), bar.getMaximum()));
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof java.awt.Scrollbar) {
            java.awt.Scrollbar bar = (java.awt.Scrollbar) component;
            int clamped = clampInt(value, bar.getMinimum(), bar.getMaximum());
            bar.setValue(clamped);
            java.awt.event.AdjustmentEvent event = new java.awt.event.AdjustmentEvent(
                    bar, java.awt.event.AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED,
                    java.awt.event.AdjustmentEvent.TRACK, clamped);
            for (java.awt.event.AdjustmentListener listener : bar.getAdjustmentListeners()) {
                listener.adjustmentValueChanged(event);
            }
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof javax.swing.JSpinner) {
            javax.swing.JSpinner spinner = (javax.swing.JSpinner) component;
            try {
                Object current = spinner.getValue();
                Object next;
                if (current instanceof Integer) {
                    next = Integer.valueOf((int) Math.round(value));
                } else if (current instanceof Long) {
                    next = Long.valueOf(Math.round(value));
                } else if (current instanceof Float) {
                    next = Float.valueOf((float) value);
                } else {
                    next = Double.valueOf(value);
                }
                spinner.setValue(next);
                dispatchCount[0]++;
                return null;
            } catch (IllegalArgumentException outOfRange) {
                return error(ERR_NOT_ACTIONABLE,
                        "The spinner refused that value: " + outOfRange.getMessage());
            }
        }
        return error(ERR_UNSUPPORTED,
                "set_number is not implemented for " + component.getClass().getName());
    }

    private JsonObject selectItem(Component component, ActionRequest request,
                                  int[] dispatchCount) {
        String wanted = asString(request.value);
        Integer index = request.index;
        if (wanted == null && index == null) {
            return error(ERR_INVALID,
                    "select_item requires an exact 'value' or a numeric 'index'.");
        }
        if (component instanceof javax.swing.JComboBox) {
            javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) component;
            int target = index != null ? index.intValue()
                    : exactIndex(comboItems(combo), wanted);
            if (target < 0 || target >= combo.getItemCount()) {
                return notFound(wanted, index);
            }
            combo.setSelectedIndex(target);
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof java.awt.Choice) {
            java.awt.Choice choice = (java.awt.Choice) component;
            int target = index != null ? index.intValue()
                    : exactIndex(choiceItems(choice), wanted);
            if (target < 0 || target >= choice.getItemCount()) {
                return notFound(wanted, index);
            }
            choice.select(target);
            java.awt.event.ItemEvent event = new java.awt.event.ItemEvent(
                    choice, java.awt.event.ItemEvent.ITEM_STATE_CHANGED,
                    choice.getItem(target), java.awt.event.ItemEvent.SELECTED);
            for (java.awt.event.ItemListener listener : choice.getItemListeners()) {
                listener.itemStateChanged(event);
            }
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof javax.swing.JList) {
            javax.swing.JList<?> list = (javax.swing.JList<?>) component;
            int target = index != null ? index.intValue() : exactIndex(listItems(list), wanted);
            if (target < 0 || target >= list.getModel().getSize()) {
                return notFound(wanted, index);
            }
            list.setSelectedIndex(target);
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof java.awt.List) {
            java.awt.List list = (java.awt.List) component;
            int target = index != null ? index.intValue() : exactIndex(awtListItems(list), wanted);
            if (target < 0 || target >= list.getItemCount()) {
                return notFound(wanted, index);
            }
            list.select(target);
            java.awt.event.ItemEvent event = new java.awt.event.ItemEvent(
                    list, java.awt.event.ItemEvent.ITEM_STATE_CHANGED,
                    Integer.valueOf(target), java.awt.event.ItemEvent.SELECTED);
            for (java.awt.event.ItemListener listener : list.getItemListeners()) {
                listener.itemStateChanged(event);
            }
            dispatchCount[0]++;
            return null;
        }
        return error(ERR_UNSUPPORTED,
                "select_item is not implemented for " + component.getClass().getName());
    }

    private JsonObject selectTab(Component component, ActionRequest request,
                                 int[] dispatchCount) {
        if (!(component instanceof javax.swing.JTabbedPane)) {
            return error(ERR_UNSUPPORTED, "select_tab requires a tabbed pane.");
        }
        javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) component;
        String wanted = asString(request.value);
        int target = request.index != null ? request.index.intValue() : -1;
        if (target < 0 && wanted != null) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (wanted.equals(tabs.getTitleAt(i))) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0 || target >= tabs.getTabCount()) {
            return notFound(wanted, request.index);
        }
        if (!tabs.isEnabledAt(target)) {
            return error(ERR_NOT_ACTIONABLE, "That tab is disabled.");
        }
        tabs.setSelectedIndex(target);
        dispatchCount[0]++;
        return null;
    }

    private JsonObject selectRow(Component component, ActionRequest request,
                                 int[] dispatchCount) {
        Integer index = request.index;
        if (index == null) {
            Double numeric = asDouble(request.value);
            if (numeric != null) index = Integer.valueOf((int) Math.round(numeric.doubleValue()));
        }
        if (index == null) {
            return error(ERR_INVALID, "select_row requires a numeric 'index'.");
        }
        if (component instanceof javax.swing.JTable) {
            javax.swing.JTable table = (javax.swing.JTable) component;
            if (index.intValue() < 0 || index.intValue() >= table.getRowCount()) {
                return notFound(null, index);
            }
            table.setRowSelectionInterval(index.intValue(), index.intValue());
            dispatchCount[0]++;
            return null;
        }
        if (component instanceof javax.swing.JTree) {
            javax.swing.JTree tree = (javax.swing.JTree) component;
            if (index.intValue() < 0 || index.intValue() >= tree.getRowCount()) {
                return notFound(null, index);
            }
            tree.setSelectionRow(index.intValue());
            dispatchCount[0]++;
            return null;
        }
        return error(ERR_UNSUPPORTED,
                "select_row is not implemented for " + component.getClass().getName());
    }

    private JsonObject expandOrCollapse(Component component, ActionRequest request,
                                        boolean expand, int[] dispatchCount) {
        if (!(component instanceof javax.swing.JTree)) {
            return error(ERR_UNSUPPORTED, "expand/collapse requires a tree.");
        }
        javax.swing.JTree tree = (javax.swing.JTree) component;
        Integer index = request.index;
        if (index == null) {
            Double numeric = asDouble(request.value);
            index = numeric == null ? Integer.valueOf(0)
                    : Integer.valueOf((int) Math.round(numeric.doubleValue()));
        }
        if (index.intValue() < 0 || index.intValue() >= tree.getRowCount()) {
            return notFound(null, index);
        }
        if (expand) tree.expandRow(index.intValue());
        else tree.collapseRow(index.intValue());
        dispatchCount[0]++;
        return null;
    }

    // -----------------------------------------------------------------------
    // EDT plumbing
    // -----------------------------------------------------------------------

    /**
     * Whether the calling thread is Swing's event-dispatch thread.
     *
     * <p>{@link EventQueue#isDispatchThread()} alone is not dependable here.
     * When the last displayable window goes away AWT stops the dispatch thread,
     * and the next posted event starts a fresh one; while those two overlap the
     * queue's recorded dispatch thread can be stale or null, and the check
     * answers {@code false} on the very thread that is dispatching. Fiji closes
     * and reopens windows constantly, so a bare {@code isDispatchThread} would
     * intermittently refuse a perfectly good action. Fall back to the dispatch
     * thread's own identity, which does not depend on that bookkeeping.</p>
     */
    public static boolean isEventThread() {
        if (EventQueue.isDispatchThread()) return true;
        return Thread.currentThread().getName().startsWith("AWT-EventQueue-");
    }

    /**
     * Run a read on the event thread and wait for it, bounded by
     * {@code timeoutMs}. Queued work that has not started is invalidated on
     * timeout so it cannot run after the caller has given up.
     */
    public <T> T callOnEdt(final Supplier<T> work, long timeoutMs)
            throws EdtTimeoutException {
        if (isEventThread()) return work.get();
        final Object[] holder = new Object[1];
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        GuiActionDispatcher.ActionToken token =
                GuiActionDispatcher.queueSwingAction(new Runnable() {
                    @Override public void run() {
                        try {
                            holder[0] = work.get();
                        } catch (Throwable thrown) {
                            failure[0] = thrown;
                        } finally {
                            latch.countDown();
                        }
                    }
                });
        boolean completed;
        try {
            completed = latch.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            token.invalidate();
            Thread.currentThread().interrupt();
            throw new EdtTimeoutException("Interrupted while waiting for the event thread.");
        }
        if (!completed) {
            token.invalidate();
            throw new EdtTimeoutException(
                    "The Swing event thread did not answer within " + timeoutMs + " ms.");
        }
        if (failure[0] != null) {
            throw new IllegalStateException("UI read failed on the event thread",
                    failure[0]);
        }
        @SuppressWarnings("unchecked")
        T result = (T) holder[0];
        return result;
    }

    // -----------------------------------------------------------------------
    // Small helpers
    // -----------------------------------------------------------------------

    private void publish(String topic, JsonObject data) {
        if (eventBus == null) return;
        try {
            eventBus.publish(topic, data);
        } catch (Throwable ignored) {
            // Telemetry must never break an action.
        }
    }

    private static JsonObject actionEvent(String actionId, String action, String outcome) {
        JsonObject data = new JsonObject();
        data.addProperty("action_id", actionId);
        data.addProperty("action", action);
        if (outcome != null) data.addProperty("outcome", outcome);
        return data;
    }

    static JsonObject success(JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("result", result);
        return response;
    }

    static JsonObject error(String code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("category", categoryFor(code));
        error.addProperty("retry_safe", ERR_INVALID.equals(code)
                || ERR_STALE.equals(code) || ERR_UNKNOWN.equals(code));
        response.add("error", error);
        return response;
    }

    private static String categoryFor(String code) {
        if (ERR_INVALID.equals(code)) return "validation";
        if (ERR_EDT_TIMEOUT.equals(code)) return "operation";
        return "state";
    }

    private static JsonObject notFound(String wanted, Integer index) {
        return error(ERR_NOT_ACTIONABLE, "No item matched "
                + (index != null ? "index " + index : "value '" + wanted + "'")
                + ". Matching is exact; there is no substring fallback.");
    }

    private static List<String> comboItems(javax.swing.JComboBox<?> combo) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            Object item = combo.getItemAt(i);
            items.add(item == null ? "" : String.valueOf(item));
        }
        return items;
    }

    private static List<String> choiceItems(java.awt.Choice choice) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < choice.getItemCount(); i++) items.add(choice.getItem(i));
        return items;
    }

    private static List<String> listItems(javax.swing.JList<?> list) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            Object item = list.getModel().getElementAt(i);
            items.add(item == null ? "" : String.valueOf(item));
        }
        return items;
    }

    private static List<String> awtListItems(java.awt.List list) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < list.getItemCount(); i++) items.add(list.getItem(i));
        return items;
    }

    /** Exact match only, then exact ignoring case. Never a substring. */
    private static int exactIndex(List<String> items, String wanted) {
        if (wanted == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (wanted.equals(items.get(i))) return i;
        }
        Set<Integer> caseMatches = new LinkedHashSet<Integer>();
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            if (item != null && item.equalsIgnoreCase(wanted)) caseMatches.add(i);
        }
        return caseMatches.size() == 1 ? caseMatches.iterator().next().intValue() : -1;
    }

    private static int clampInt(double value, int minimum, int maximum) {
        long rounded = Math.round(value);
        if (rounded < minimum) return minimum;
        if (rounded > maximum) return maximum;
        return (int) rounded;
    }

    static Boolean asBoolean(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return Boolean.valueOf(primitive.getAsBoolean());
        if (primitive.isString()) {
            String value = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(value)) return Boolean.TRUE;
            if ("false".equals(value)) return Boolean.FALSE;
        }
        return null;
    }

    static String asString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    static Double asDouble(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        try {
            return Double.valueOf(element.getAsDouble());
        } catch (RuntimeException notNumeric) {
            return null;
        }
    }

    static double millisBetween(long fromNanos, long toNanos) {
        if (fromNanos <= 0L || toNanos < fromNanos) return 0.0d;
        return Math.round((toNanos - fromNanos) / 1_000.0d) / 1_000.0d;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Every window currently owned by this JVM, showing or not. Diagnostics. */
    JsonArray windowIdsForTest() {
        JsonArray ids = new JsonArray();
        for (Window window : ownedWindows(false)) {
            ids.add(new JsonPrimitive(identities.windowId(window)));
        }
        return ids;
    }
}
