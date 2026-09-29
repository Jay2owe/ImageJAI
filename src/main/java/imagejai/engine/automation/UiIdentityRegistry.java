package imagejai.engine.automation;

import java.awt.Component;
import java.awt.Window;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-local opaque identity for AWT/Swing windows, components and menu
 * components, plus the generation counter that tells a caller when a previously
 * issued identity can no longer be trusted for targeting.
 *
 * <p>Identities are keyed on {@code Object} rather than {@code Component}
 * because ImageJ 1.x builds its main menu from {@link java.awt.MenuBar},
 * {@link java.awt.Menu} and {@link java.awt.MenuItem}, which extend
 * {@link java.awt.MenuComponent} and are therefore not {@code Component}s at
 * all. They still need stable ids, so the map holds either kind and
 * {@link #resolve} narrows back to a component while {@link #resolveMenu}
 * narrows to a menu component.</p>
 *
 * <p>Identities are handles, never selectors. They are meaningless outside the
 * JVM that issued them, are not derived from titles or paths, and are never
 * reused for a different component. A harness stores semantic selectors and
 * re-resolves them; it must not persist a node id across runs.</p>
 *
 * <h2>Generation semantics</h2>
 *
 * <p>The generation advances only when something <em>invalidates</em> existing
 * targeting: a registered window is disposed, or a registered component leaves
 * the hierarchy of a showing window. New windows and new components appearing
 * do not advance it, because they cannot make an already-issued id point at the
 * wrong thing. That keeps a snapshot usable for the action it was taken for
 * while still failing closed the moment a plugin rebuilds its UI.</p>
 *
 * <p>All mutating access happens on the event-dispatch thread, but the class is
 * synchronised anyway so a diagnostic read from a TCP worker is safe.</p>
 */
public final class UiIdentityRegistry {

    /** Ceiling on retained identities before the oldest cleared refs are purged. */
    static final int MAX_RETAINED = 20_000;

    private final String namespace = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong generation = new AtomicLong(1L);

    private final Map<Object, String> idsByTarget =
            new WeakHashMap<Object, String>();
    private final Map<String, WeakReference<Object>> targetsById =
            new HashMap<String, WeakReference<Object>>();

    /** Current generation. A snapshot carries this value; actions must echo it. */
    public long generation() {
        return generation.get();
    }

    /**
     * Advance the generation because a previously issued identity can no longer
     * be trusted. Idempotent per invalidating observation; callers should call
     * it once per detected invalidation.
     */
    public long invalidate() {
        return generation.incrementAndGet();
    }

    /** Stable opaque id for a window, minted on first sight. */
    public synchronized String windowId(Window window) {
        return idFor(window, "w");
    }

    /** Stable opaque id for a component, minted on first sight. */
    public synchronized String nodeId(Component component) {
        return idFor(component, "n");
    }

    /**
     * Stable opaque id for an AWT menu component, minted on first sight. Menu
     * ids share the {@code n-} prefix and the sequence with component ids so a
     * caller never has to know which kind of target an id denotes.
     */
    public synchronized String menuNodeId(java.awt.MenuComponent menu) {
        return idFor(menu, "n");
    }

    private String idFor(Object target, String prefix) {
        if (target == null) return null;
        String existing = idsByTarget.get(target);
        if (existing != null) return existing;
        purgeIfNeeded();
        String id = prefix + "-" + namespace + "-" + sequence.incrementAndGet();
        idsByTarget.put(target, id);
        targetsById.put(id, new WeakReference<Object>(target));
        return id;
    }

    /** True when this id was minted by this registry and is syntactically sound. */
    public static boolean looksLikeId(String candidate) {
        if (candidate == null) return false;
        int length = candidate.length();
        if (length < 5 || length > 64) return false;
        if (!candidate.startsWith("w-") && !candidate.startsWith("n-")) return false;
        for (int i = 2; i < length; i++) {
            char value = candidate.charAt(i);
            boolean allowed = (value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')
                    || value == '-';
            if (!allowed) return false;
        }
        return true;
    }

    /**
     * Resolve an id to its live target — a {@link Component} or a
     * {@link java.awt.MenuComponent} — or {@code null} when it has been
     * collected or was never issued by this registry. A cleared reference is an
     * invalidation: the generation advances so the caller sees a stale target
     * rather than a silent miss.
     */
    public synchronized Object resolveTarget(String id) {
        if (id == null) return null;
        WeakReference<Object> reference = targetsById.get(id);
        if (reference == null) return null;
        Object target = reference.get();
        if (target == null) {
            targetsById.remove(id);
            invalidate();
            return null;
        }
        return target;
    }

    /**
     * Resolve an id that must denote a {@link Component}. A live id that
     * denotes a menu component answers {@code null} here; use
     * {@link #resolveMenu} for those.
     */
    public synchronized Component resolve(String id) {
        Object target = resolveTarget(id);
        return target instanceof Component ? (Component) target : null;
    }

    /** Resolve an id that must denote an AWT {@link java.awt.MenuComponent}. */
    public synchronized java.awt.MenuComponent resolveMenu(String id) {
        Object target = resolveTarget(id);
        return target instanceof java.awt.MenuComponent
                ? (java.awt.MenuComponent) target : null;
    }

    /** Resolve an id that must denote a {@link Window}. */
    public synchronized Window resolveWindow(String id) {
        Component component = resolve(id);
        return component instanceof Window ? (Window) component : null;
    }

    /** True when this target already holds an issued identity. */
    public synchronized boolean isKnown(Object target) {
        return target != null && idsByTarget.containsKey(target);
    }

    /**
     * Forget a target that has left the UI and advance the generation. Used
     * by the snapshot builder when it observes a disposed window.
     */
    public synchronized void forget(Object target) {
        if (target == null) return;
        String id = idsByTarget.remove(target);
        if (id != null) {
            targetsById.remove(id);
            invalidate();
        }
    }

    /** Number of live identity mappings. Diagnostics and bounds tests. */
    public synchronized int size() {
        return targetsById.size();
    }

    /** Drop every identity and advance the generation. Used on shutdown. */
    public synchronized void clear() {
        boolean hadEntries = !targetsById.isEmpty();
        idsByTarget.clear();
        targetsById.clear();
        if (hadEntries) invalidate();
    }

    private void purgeIfNeeded() {
        if (targetsById.size() < MAX_RETAINED) return;
        Iterator<Map.Entry<String, WeakReference<Object>>> entries =
                targetsById.entrySet().iterator();
        boolean removed = false;
        while (entries.hasNext()) {
            Map.Entry<String, WeakReference<Object>> entry = entries.next();
            if (entry.getValue().get() == null) {
                entries.remove();
                removed = true;
            }
        }
        if (removed) invalidate();
        // A UI that really holds 20k live components is pathological; refuse to
        // grow without bound rather than mint identities forever.
        while (targetsById.size() >= MAX_RETAINED) {
            Iterator<String> keys = targetsById.keySet().iterator();
            if (!keys.hasNext()) break;
            keys.remove();
            removed = true;
        }
        if (removed) invalidate();
    }
}
