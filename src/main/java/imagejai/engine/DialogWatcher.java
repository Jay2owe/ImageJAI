package imagejai.engine;

import com.google.gson.JsonObject;

import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2: polls {@link Window#getWindows()} every 500ms on the EDT and emits
 * {@code dialog.appeared} / {@code dialog.closed} events through {@link EventBus}.
 * <p>
 * Dialog identity is the window title (stable for the lifetime of a dialog).
 * Newly-seen titles produce {@code dialog.appeared}; titles that disappear
 * from the open set produce {@code dialog.closed}.
 */
public class DialogWatcher {

    private static final int POLL_INTERVAL_MS = 500;

    private final EventBus bus;
    private Timer timer;
    private volatile boolean running;

    // title -> classified kind; stable set of open dialogs from the last poll.
    private final Map<String, String> openDialogs = new HashMap<String, String>();

    public DialogWatcher(EventBus bus) {
        this.bus = bus;
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
    }

    private void poll() {
        if (!running) return;

        Set<String> seenTitles = new HashSet<String>();
        Map<String, String> currentKinds = new HashMap<String, String>();

        Window[] windows = Window.getWindows();
        if (windows != null) {
            for (Window win : windows) {
                if (win == null || !win.isShowing()) continue;
                if (!(win instanceof Dialog)) continue;
                Dialog dlg = (Dialog) win;
                String title = dlg.getTitle();
                if (title == null) title = "";
                // Skip our own chat window — never emit events for ourselves.
                if (title.contains("AI Assistant")) continue;

                seenTitles.add(title);
                if (!currentKinds.containsKey(title)) {
                    currentKinds.put(title, classifyDialog(dlg));
                }
            }
        }

        // dialog.appeared for new titles
        for (Map.Entry<String, String> e : currentKinds.entrySet()) {
            String title = e.getKey();
            String kind = e.getValue();
            if (!openDialogs.containsKey(title)) {
                JsonObject data = buildDialogData(findDialogByTitle(title), kind);
                data.addProperty("title", title);
                data.addProperty("kind", kind);
                data.addProperty("type", kind);
                bus.publish("dialog.appeared", data);
            }
        }

        // dialog.closed for titles that disappeared
        for (Map.Entry<String, String> e : openDialogs.entrySet()) {
            if (!seenTitles.contains(e.getKey())) {
                JsonObject data = new JsonObject();
                data.addProperty("title", e.getKey());
                bus.publish("dialog.closed", data);
            }
        }

        openDialogs.clear();
        openDialogs.putAll(currentKinds);
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

    private Dialog findDialogByTitle(String title) {
        Window[] windows = Window.getWindows();
        if (windows == null) return null;
        for (Window win : windows) {
            if (!(win instanceof Dialog) || !win.isShowing()) continue;
            Dialog dlg = (Dialog) win;
            String dlgTitle = dlg.getTitle();
            if (dlgTitle == null) dlgTitle = "";
            if (dlgTitle.equals(title)) return dlg;
        }
        return null;
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
