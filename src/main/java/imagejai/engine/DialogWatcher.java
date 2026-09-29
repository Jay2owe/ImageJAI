package imagejai.engine;

import com.google.gson.JsonObject;
import imagejai.engine.automation.AutomationBridge;
import imagejai.engine.automation.UiAutomationService;
import imagejai.engine.automation.UiIdentityRegistry;
import imagejai.engine.automation.UiNode;

import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2: polls {@link Window#getWindows()} every 500ms on the EDT and emits
 * {@code dialog.appeared} / {@code dialog.closed} events through {@link EventBus}.
 * <p>
 * Dialog identity is an opaque id attached to the concrete Window instance.
 * Display titles are metadata only: simultaneous or successive dialogs with
 * the same title retain distinct lifecycles.
 */
public class DialogWatcher {

    private static final int POLL_INTERVAL_MS = 500;

    private final EventBus bus;
    private Timer timer;
    private volatile boolean running;

    private final Map<String, DialogState> openDialogs =
            new LinkedHashMap<String, DialogState>();
    private final IdentityHashMap<Window, String> dialogIds =
            new IdentityHashMap<Window, String>();
    private final AtomicLong dialogSequence = new AtomicLong();
    private final String identityNamespace = UUID.randomUUID().toString().substring(0, 8);
    /**
     * Stable window identity shared with the test automation bridge, or
     * {@code null} on a normal Fiji. When present, dialog events also carry the
     * {@code window_id} the UI tree reports, so a harness can correlate a
     * lifecycle event with the node it was targeting without matching on title.
     */
    private final UiIdentityRegistry identities;
    private final Map<String, WindowState> openWindows =
            new LinkedHashMap<String, WindowState>();

    public DialogWatcher(EventBus bus) {
        this(bus, AutomationBridge.sharedIdentitiesIfEnabled());
    }

    /** Test seam / explicit wiring for the automation bridge's identity registry. */
    public DialogWatcher(EventBus bus, UiIdentityRegistry identities) {
        this.bus = bus;
        this.identities = identities;
    }

    /** Start polling. Safe to call once at plugin start — idempotent. */
    public void start() {
        if (running) return;
        running = true;
        timer = new Timer(POLL_INTERVAL_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    poll();
                } catch (Throwable t) {
                    // Never let watcher faults bring down the EDT timer.
                }
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    public void stop() {
        running = false;
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        openDialogs.clear();
        openWindows.clear();
        dialogIds.clear();
    }

    private void poll() {
        if (!running) return;

        Map<String, DialogState> current = new LinkedHashMap<String, DialogState>();
        Map<String, WindowState> currentWindows = new LinkedHashMap<String, WindowState>();

        Window[] windows = Window.getWindows();
        if (windows != null) {
            for (Window win : windows) {
                if (win == null || !win.isShowing()) continue;
                if (identities != null) {
                    String windowId = identities.windowId(win);
                    currentWindows.put(windowId, new WindowState(windowId,
                            UiNode.roleOf(win), UiAutomationService.ownerOf(win),
                            win instanceof Dialog && ((Dialog) win).isModal()));
                }
                if (!(win instanceof Dialog)) continue;
                Dialog dlg = (Dialog) win;
                String title = dlg.getTitle();
                if (title == null) title = "";
                // Skip our own chat window — never emit events for ourselves.
                if (title.contains("AI Assistant")) continue;

                String dialogId = dialogIds.get(dlg);
                if (dialogId == null) {
                    dialogId = "dialog-" + identityNamespace + "-"
                            + dialogSequence.incrementAndGet();
                    dialogIds.put(dlg, dialogId);
                }
                current.put(dialogId,
                        new DialogState(dialogId, title, classifyDialog(dlg), dlg));
            }
        }

        for (DialogState state : current.values()) {
            if (!openDialogs.containsKey(state.id)) {
                JsonObject data = buildDialogData(state.dialog, state.kind);
                data.addProperty("dialog_id", state.id);
                data.addProperty("title", state.title);
                data.addProperty("kind", state.kind);
                data.addProperty("type", state.kind);
                addWindowIdentity(data, state.dialog);
                bus.publish("dialog.appeared", data);
            }
        }

        for (DialogState state : openDialogs.values()) {
            if (!current.containsKey(state.id)) {
                JsonObject data = new JsonObject();
                data.addProperty("dialog_id", state.id);
                data.addProperty("title", state.title);
                data.addProperty("kind", state.kind);
                addWindowIdentity(data, state.dialog);
                bus.publish("dialog.closed", data);
                dialogIds.remove(state.dialog);
            }
        }

        openDialogs.clear();
        openDialogs.putAll(current);
        publishWindowLifecycle(currentWindows);
    }

    /**
     * Payload-minimal window lifecycle for the test bridge: ids, role, owner,
     * and modality. No titles, no labels, no contents — a subscriber learns
     * that a window appeared, not what it says.
     */
    private void publishWindowLifecycle(Map<String, WindowState> current) {
        if (identities == null) return;
        for (WindowState state : current.values()) {
            if (!openWindows.containsKey(state.id)) {
                bus.publish("ui.window.appeared", state.toJson());
            }
        }
        for (WindowState state : openWindows.values()) {
            if (!current.containsKey(state.id)) {
                bus.publish("ui.window.closed", state.toJson());
            }
        }
        openWindows.clear();
        openWindows.putAll(current);
    }

    private void addWindowIdentity(JsonObject data, Window window) {
        if (identities == null || window == null) return;
        data.addProperty("window_id", identities.windowId(window));
        data.addProperty("ui_generation", identities.generation());
    }

    private static final class WindowState {
        final String id;
        final String role;
        final String owner;
        final boolean modal;

        WindowState(String id, String role, String owner, boolean modal) {
            this.id = id;
            this.role = role;
            this.owner = owner;
            this.modal = modal;
        }

        JsonObject toJson() {
            JsonObject data = new JsonObject();
            data.addProperty("window_id", id);
            data.addProperty("role", role);
            data.addProperty("owner", owner);
            data.addProperty("modal", modal);
            return data;
        }
    }

    /**
     * Classify a dialog by its text content: {@code error}, {@code warning},
     * {@code prompt}, or {@code info}. Mirrors the classification used by
     * {@code TCPCommandServer.detectOpenDialogs()}.
     */
    private String classifyDialog(Dialog dlg) {
        StringBuilder text = new StringBuilder();
        boolean hasOk = false;
        boolean hasCancel = false;
        try {
            collectText(dlg, text);
            hasOk = hasButton(dlg, "OK");
            hasCancel = hasButton(dlg, "Cancel");
        } catch (Throwable ignore) {
        }
        String lower = text.toString().toLowerCase();
        if (lower.contains("error") || lower.contains("exception") || lower.contains("failed")) {
            return "error";
        }
        if (lower.contains("warning") || lower.contains("caution")) {
            return "warning";
        }
        if (hasOk && hasCancel) return "prompt";
        return "info";
    }

    private JsonObject buildDialogData(Dialog dlg, String fallbackKind) {
        JsonObject data = new JsonObject();
        if (dlg == null) {
            data.addProperty("text", "");
            data.addProperty("modal", false);
            data.add("buttons", new com.google.gson.JsonArray());
            if (fallbackKind != null) {
                data.addProperty("kind", fallbackKind);
                data.addProperty("type", fallbackKind);
            }
            return data;
        }
        StringBuilder text = new StringBuilder();
        List<String> buttons = new ArrayList<String>();
        try {
            collectText(dlg, text);
            collectButtons(dlg, buttons);
        } catch (Throwable ignore) {
        }
        data.addProperty("title", dlg.getTitle() == null ? "" : dlg.getTitle());
        data.addProperty("text", text.toString().trim());
        data.addProperty("modal", dlg.isModal());
        com.google.gson.JsonArray buttonsJson = new com.google.gson.JsonArray();
        for (String label : buttons) {
            buttonsJson.add(label);
        }
        data.add("buttons", buttonsJson);
        String kind = fallbackKind != null ? fallbackKind : classifyDialog(dlg);
        data.addProperty("kind", kind);
        data.addProperty("type", kind);
        return data;
    }

    private static final class DialogState {
        final String id;
        final String title;
        final String kind;
        final Dialog dialog;

        DialogState(String id, String title, String kind, Dialog dialog) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.dialog = dialog;
        }
    }

    private void collectText(Container c, StringBuilder out) {
        Component[] kids = c.getComponents();
        if (kids == null) return;
        for (Component comp : kids) {
            if (comp instanceof javax.swing.JLabel) {
                String s = ((javax.swing.JLabel) comp).getText();
                if (s != null) out.append(s).append(' ');
            } else if (comp instanceof java.awt.Label) {
                String s = ((java.awt.Label) comp).getText();
                if (s != null) out.append(s).append(' ');
            } else if (comp instanceof javax.swing.JTextArea) {
                String s = ((javax.swing.JTextArea) comp).getText();
                if (s != null) out.append(s).append(' ');
            } else if (comp instanceof java.awt.TextArea) {
                String s = ((java.awt.TextArea) comp).getText();
                if (s != null) out.append(s).append(' ');
            }
            if (comp instanceof Container) {
                collectText((Container) comp, out);
            }
        }
    }

    private boolean hasButton(Container c, String label) {
        Component[] kids = c.getComponents();
        if (kids == null) return false;
        for (Component comp : kids) {
            if (comp instanceof javax.swing.JButton) {
                String t = ((javax.swing.JButton) comp).getText();
                if (t != null && label.equalsIgnoreCase(t.trim())) return true;
            } else if (comp instanceof java.awt.Button) {
                String t = ((java.awt.Button) comp).getLabel();
                if (t != null && label.equalsIgnoreCase(t.trim())) return true;
            }
            if (comp instanceof Container) {
                if (hasButton((Container) comp, label)) return true;
            }
        }
        return false;
    }

    private void collectButtons(Container c, List<String> out) {
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
                collectButtons((Container) comp, out);
            }
        }
    }
}
