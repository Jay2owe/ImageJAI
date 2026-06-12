package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Step 10 (docs/tcp_upgrade/10_phantom_dialog_detector.md): post-command AWT
 * focus check that catches modal dialogs which appeared DURING a command but
 * nobody noticed — the "ghost-dialog-queued-macros" pathology where every
 * subsequent macro queues silently behind an invisible "Overwrite?" prompt.
 *
 * <p>Usage: snapshot {@link #currentModalWindows()} at the start of a
 * mutating handler, run the command, then call
 * {@link #detect(Set, boolean)}. A non-empty result means at least one new
 * modal window is on screen that was not there before.
 *
 * <p>Auto-dismiss is opt-in ({@code caps.autoDismissPhantoms}) AND restricted
 * to a fixed safe-label allow-list so the server can unblock the event queue
 * without making semantic decisions on the agent's behalf.
 */
public final class PhantomDialogDetector {

    /** Exact button labels the server is allowed to auto-click. */
    private static final List<String> SAFE_DISMISS_BUTTONS =
            Collections.unmodifiableList(java.util.Arrays.asList(
                    "No", "Cancel", "Don't Save", "Skip"));

    /** Body snippet truncated at this size to keep replies bounded. */
    private static final int MAX_BODY_CHARS = 400;

    private PhantomDialogDetector() { }

    /**
     * Snapshot the set of currently-visible modal windows. Call this BEFORE
     * the command runs; the returned set is compared against the post-command
     * snapshot to identify dialogs that appeared during the call.
     *
     * <p>Safe to call from any thread — {@link Window#getWindows()} returns a
     * defensive copy. Never returns null.
     */
    public static Set<Window> currentModalWindows() {
        Set<Window> out = new LinkedHashSet<Window>();
        Window[] windows;
        try {
            windows = Window.getWindows();
        } catch (Throwable ignore) {
            return out;
        }
        if (windows == null) return out;
        for (Window w : windows) {
            if (isModalDialog(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * Compare {@code preCommandModals} with the current modal window set and
     * report any new modals as a phantom-dialog record. Returns
     * {@link Optional#empty()} when no new modal is on screen.
     *
     * <p>When {@code autoDismiss} is true AND the front-most new modal has a
     * button whose label is in the safe allow-list ({@link #pickSafeButton}),
     * that button is clicked on the EDT and {@code autoDismissed=true} is
     * added to the reply. Buttons outside the allow-list (e.g. {@code "Yes"},
     * {@code "Overwrite"}, {@code "Save"}, {@code "Delete"}) are NEVER
     * clicked — dismissal is for breaking deadlocks, not for deciding what
     * the user wanted.
     *
     * @param preCommandModals snapshot returned by {@link #currentModalWindows()}
     *                         at the start of the handler. May be {@code null}
     *                         or empty.
     * @param autoDismiss      whether the caller has opted into auto-clicking
     *                         safe buttons. Usually resolved from
     *                         {@code caps.autoDismissPhantoms} with an
     *                         optional per-call override.
     */
    public static Optional<JsonObject> detect(Set<Window> preCommandModals,
                                              boolean autoDismiss) {
        Set<Window> now = currentModalWindows();
        Set<Window> pre = preCommandModals != null
                ? preCommandModals : Collections.<Window>emptySet();
        now.removeAll(pre);
        if (now.isEmpty()) return Optional.empty();

        Window front = frontMostOf(now);
        if (front == null) return Optional.empty();

        String title = titleOf(front);
        String body = extractBody(front);
        List<String> buttons = extractButtons(front);

        JsonObject out = new JsonObject();
        out.addProperty("title", title != null ? title : "");
        if (body != null && !body.isEmpty()) {
            out.addProperty("body", body);
        }
        JsonArray buttonsJson = new JsonArray();
        for (String label : buttons) {
            buttonsJson.add(label);
        }
        out.add("buttons", buttonsJson);
        String cause = guessCause(title, body, buttons);
        if (cause != null) {
            out.addProperty("likelyCause", cause);
        } else {
            // Serialise an explicit null so clients can feature-detect the key
            // without needing to distinguish "no guess" from "field missing".
            out.add("likelyCause", com.google.gson.JsonNull.INSTANCE);
        }

        boolean dismissed = false;
        if (autoDismiss) {
            String safe = pickSafeButton(buttons);
            if (safe != null) {
                dismissed = clickButtonOnEdt(front, safe);
            }
        }
        out.addProperty("autoDismissed", dismissed);
        return Optional.of(out);
    }

    // -------------------------------------------------------------------
    // Pure helpers (exposed package-private so tests can exercise them
    // without a live Swing peer).
    // -------------------------------------------------------------------

    /**
     * Return the first allow-listed button label in {@code buttons}, or null
     * when none match. Label comparison is exact (trimmed) — NOT
     * case-insensitive — so {@code "cancel"} does NOT match {@code "Cancel"};
     * IJ/Swing always emits the canonical capitalisation. Locale variants
     * ({@code "Annuler"}) are explicitly out of scope.
     */
    static String pickSafeButton(List<String> buttons) {
        if (buttons == null) return null;
        for (String label : buttons) {
            if (label == null) continue;
            String trimmed = label.trim();
            for (String safe : SAFE_DISMISS_BUTTONS) {
                if (safe.equals(trimmed)) return trimmed;
            }
        }
        return null;
    }

    /**
     * First-match heuristic lookup of {@code likelyCause} strings. Ordered
     * from most specific to least; returns null when nothing matches.
     *
     * <p>Reasoning is deliberately narrow: we only emit a cause string when
     * we're confident enough that it won't send the agent down a wrong path.
     * A missing {@code likelyCause} is correct behaviour, not a bug.
     */
    static String guessCause(String title, String body, List<String> buttons) {
        String t = title != null ? title.toLowerCase() : "";
        String b = body != null ? body.toLowerCase() : "";

        if (t.contains("overwrite") || b.contains("already exists")) {
            return "saveAs without unique filename; setBatchMode not active";
        }
        if (buttons != null && buttons.contains("Don't Save")) {
            return "unsaved changes; close() without flattening";
        }
        if (t.contains("save") && !t.contains("saveas") && t.length() > 0) {
            // Narrower than the Don't-Save rule above — catches titles like
            // "Save changes?" without that specific button set.
            if (b.contains("save") || b.contains("changes")) {
                return "unsaved changes; close() without flattening";
            }
        }
        if ("macro error".equals(t)) {
            return "macro error not captured in response; "
                    + "detectIjMacroError should have caught this";
        }
        return null;
    }

    /** True when the window is a visible modal {@link Dialog}. */
    static boolean isModalDialog(Window w) {
        if (w == null) return false;
        if (!(w instanceof Dialog)) return false;
        Dialog d = (Dialog) w;
        if (!d.isShowing()) return false;
        if (!d.isModal()) return false;
        String title = d.getTitle();
        if (title == null) title = "";
        // Protect the assistant's own panel — it's NEVER a phantom dialog.
        if (title.contains("AI Assistant")) return false;
        return true;
    }

    /**
     * Pick the front-most entry from a new-modals set. {@code Window#getWindows()}
     * tends to return creation order; the last modal in the set is the
     * most-recently-shown one and therefore the one blocking the event queue.
     */
    static Window frontMostOf(Set<Window> windows) {
        Window chosen = null;
        for (Window w : windows) {
            chosen = w;
        }
        return chosen;
    }

    static String titleOf(Window w) {
        if (w instanceof Dialog) {
            String t = ((Dialog) w).getTitle();
            return t != null ? t : "";
        }
        return "";
    }

    /**
     * Best-effort plain-text body extraction — walks labels / text areas /
     * {@code MultiLineLabel} and returns a single-line condensed string
     * capped at {@link #MAX_BODY_CHARS}. Returns an empty string when
     * nothing readable was found; button labels are NOT included.
     */
    static String extractBody(Window w) {
        if (!(w instanceof Container)) return "";
        StringBuilder sb = new StringBuilder();
        try {
            walkText((Container) w, sb);
        } catch (Throwable ignore) {
            return "";
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_BODY_CHARS) {
            s = s.substring(0, MAX_BODY_CHARS) + "…";
        }
        return s;
    }

    static List<String> extractButtons(Window w) {
        List<String> out = new ArrayList<String>();
        if (!(w instanceof Container)) return out;
        try {
            walkButtons((Container) w, out);
        } catch (Throwable ignore) {
        }
        return out;
    }

    private static void walkText(Container c, StringBuilder out) {
        Component[] kids = c.getComponents();
        if (kids == null) return;
        for (Component comp : kids) {
            if (comp instanceof javax.swing.JLabel) {
                String s = ((javax.swing.JLabel) comp).getText();
                if (s != null && !s.isEmpty()) out.append(s).append(' ');
            } else if (comp instanceof java.awt.Label) {
                String s = ((java.awt.Label) comp).getText();
                if (s != null && !s.isEmpty()) out.append(s).append(' ');
            } else if (comp instanceof javax.swing.JTextArea) {
                String s = ((javax.swing.JTextArea) comp).getText();
                if (s != null && !s.isEmpty()) out.append(s).append(' ');
            } else if (comp instanceof java.awt.TextArea) {
                String s = ((java.awt.TextArea) comp).getText();
                if (s != null && !s.isEmpty()) out.append(s).append(' ');
            } else if (!(comp instanceof javax.swing.JButton
                    || comp instanceof java.awt.Button)) {
                // Non-button / non-label — try reflective getters for things
                // like IJ's MultiLineLabel whose text lives in private fields.
                String fieldText = readReflectiveText(comp);
                if (fieldText != null && !fieldText.isEmpty()) {
                    out.append(fieldText).append(' ');
                }
            }
            if (comp instanceof Container) {
                walkText((Container) comp, out);
            }
        }
    }

    private static void walkButtons(Container c, List<String> out) {
        Component[] kids = c.getComponents();
        if (kids == null) return;
        for (Component comp : kids) {
            if (comp instanceof javax.swing.JButton) {
                String t = ((javax.swing.JButton) comp).getText();
                if (t != null && !t.trim().isEmpty()) out.add(t.trim());
            } else if (comp instanceof java.awt.Button) {
                String t = ((java.awt.Button) comp).getLabel();
                if (t != null && !t.trim().isEmpty()) out.add(t.trim());
            }
            if (comp instanceof Container) {
                walkButtons((Container) comp, out);
            }
        }
    }

    /**
     * Last-resort read of text-like fields on custom components (e.g.
     * {@code ij.gui.MultiLineLabel}) that expose their content in private
     * fields rather than a public getter.
     */
    private static String readReflectiveText(Component comp) {
        if (comp == null) return null;
        // Public getters first.
        try {
            java.lang.reflect.Method m = comp.getClass().getMethod("getText");
            Object v = m.invoke(comp);
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignore) {}
        try {
            java.lang.reflect.Method m = comp.getClass().getMethod("getMessage");
            Object v = m.invoke(comp);
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignore) {}

        // Private-field fallback: walk the declared-field hierarchy.
        Class<?> cls = comp.getClass();
        final String[] fieldNames = { "text2", "text", "label", "msg", "message" };
        while (cls != null && cls != Object.class) {
            for (String name : fieldNames) {
                try {
                    java.lang.reflect.Field f = cls.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(comp);
                    if (v instanceof String) {
                        String s = ((String) v).trim();
                        if (!s.isEmpty()) return s;
                    }
                } catch (Throwable ignore) {}
            }
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("lines");
                f.setAccessible(true);
                Object v = f.get(comp);
                if (v instanceof String[]) {
                    StringBuilder sb = new StringBuilder();
                    for (String ln : (String[]) v) {
                        if (ln != null && !ln.trim().isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(ln.trim());
                        }
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            } catch (Throwable ignore) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Click the first button whose label matches {@code label} on the EDT.
     * Returns true when the click was dispatched, false otherwise. Runs on
     * the EDT via {@link SwingUtilities#invokeAndWait} (fallback to
     * {@link SwingUtilities#invokeLater} when the current thread IS the EDT).
     */
    private static boolean clickButtonOnEdt(final Window w, final String label) {
        if (w == null || label == null) return false;
        final boolean[] ok = new boolean[] { false };
        Runnable click = new Runnable() {
            @Override
            public void run() {
                try {
                    ok[0] = dispatchClick(w, label);
                } catch (Throwable ignore) {
                    ok[0] = false;
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            click.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(click);
            } catch (Throwable ignore) {
                return false;
            }
        }
        return ok[0];
    }

    private static boolean dispatchClick(Container c, String label) {
        Component[] kids = c.getComponents();
        if (kids == null) return false;
        for (Component comp : kids) {
            if (comp instanceof javax.swing.JButton) {
                javax.swing.JButton b = (javax.swing.JButton) comp;
                String t = b.getText();
                if (t != null && label.equals(t.trim())) {
                    b.doClick();
                    return true;
                }
            } else if (comp instanceof java.awt.Button) {
                java.awt.Button b = (java.awt.Button) comp;
                String t = b.getLabel();
                if (t != null && label.equals(t.trim())) {
                    b.dispatchEvent(new java.awt.event.ActionEvent(
                            b, java.awt.event.ActionEvent.ACTION_PERFORMED, t));
                    return true;
                }
            }
            if (comp instanceof Container) {
                if (dispatchClick((Container) comp, label)) return true;
            }
        }
        return false;
    }
}
