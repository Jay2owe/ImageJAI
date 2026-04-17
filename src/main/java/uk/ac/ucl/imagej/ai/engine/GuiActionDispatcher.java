package uk.ac.ucl.imagej.ai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import uk.ac.ucl.imagej.ai.ui.ChatPanelController;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Phase 7: dispatches {@code gui_action} TCP requests to the {@link ChatPanelController}.
 * <p>
 * Action types: {@code inline_preview}, {@code toast}, {@code show_markdown},
 * {@code highlight_roi}, {@code focus_image}, {@code confirm}.
 * <p>
 * Toast traffic is rate-limited to one accepted toast per
 * {@value #TOAST_MIN_INTERVAL_MS} ms; suppressed toasts are counted and the
 * next accepted toast appends an aggregated "(N suppressed)" tail. This keeps
 * a chatty agent from DoS'ing the user with notifications.
 * <p>
 * The controller may be {@code null} when the plugin's chat panel isn't open.
 * In that case every dispatch returns {@code ok: false} with a clear message
 * — TCP-only usage gets a graceful no-op rather than an NPE.
 */
public class GuiActionDispatcher {

    /** Minimum wall-clock gap between two displayed toasts, in ms. */
    public static final long TOAST_MIN_INTERVAL_MS = 500L;

    private final ChatPanelController controller;
    private final EventBus eventBus;
    private final AtomicLong confirmCounter = new AtomicLong(0);

    // Toast rate-limiter — single producer expected (TCP server), but guard
    // anyway against concurrent writers.
    private final Object toastLock = new Object();
    private long lastToastAtMs = 0L;
    private int suppressedToastCount = 0;

    public GuiActionDispatcher(ChatPanelController controller) {
        this(controller, EventBus.getInstance());
    }

    /** Test seam — inject a dedicated bus. */
    GuiActionDispatcher(ChatPanelController controller, EventBus eventBus) {
        this.controller = controller;
        this.eventBus = eventBus;
    }

    public boolean hasController() {
        return controller != null;
    }

    /**
     * Route a parsed {@code gui_action} payload. The payload's outer JSON must
     * carry a {@code type} string; any other top-level fields depend on the
     * action.
     *
     * @return {@code {"ok": true, "type": ...}} on success, otherwise
     *         {@code {"ok": false, "error": "..."}}
     */
    public JsonObject dispatch(JsonObject req) {
        if (req == null) return err("null request");
        JsonElement typeEl = req.get("type");
        if (typeEl == null || !typeEl.isJsonPrimitive()) {
            return err("gui_action: missing 'type'");
        }
        String type = typeEl.getAsString();
        if (controller == null) {
            return err("gui_action: chat panel not open (TCP-only mode)");
        }

        try {
            if ("inline_preview".equals(type)) {
                return doInlinePreview(req);
            } else if ("toast".equals(type)) {
                return doToast(req);
            } else if ("show_markdown".equals(type)) {
                return doShowMarkdown(req);
            } else if ("highlight_roi".equals(type)) {
                return doHighlightRoi(req);
            } else if ("focus_image".equals(type)) {
                return doFocusImage(req);
            } else if ("confirm".equals(type)) {
                return doConfirm(req);
            } else {
                return err("unknown gui_action: " + type);
            }
        } catch (RuntimeException ex) {
            return err("gui_action " + type + ": " + ex.getMessage());
        }
    }

    private JsonObject doInlinePreview(JsonObject req) {
        String path = strField(req, "path");
        if (path == null || path.isEmpty()) return err("inline_preview: missing 'path'");
        controller.inlineImage(Paths.get(path));
        return ok("inline_preview");
    }

    private JsonObject doToast(JsonObject req) {
        String message = strField(req, "message");
        if (message == null) message = "";
        String level = strField(req, "level");
        if (level == null) level = "info";

        boolean accepted;
        int suppressed;
        synchronized (toastLock) {
            long now = System.currentTimeMillis();
            if (now - lastToastAtMs >= TOAST_MIN_INTERVAL_MS) {
                lastToastAtMs = now;
                suppressed = suppressedToastCount;
                suppressedToastCount = 0;
                accepted = true;
            } else {
                suppressedToastCount++;
                suppressed = 0;
                accepted = false;
            }
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("type", "toast");
        resp.addProperty("displayed", accepted);
        if (!accepted) {
            resp.addProperty("rate_limited", true);
            return resp;
        }
        String displayMessage = message;
        if (suppressed > 0) {
            displayMessage = displayMessage + "  (" + suppressed + " suppressed)";
            resp.addProperty("aggregated_suppressed", suppressed);
        }
        controller.toast(displayMessage, level);
        return resp;
    }

    private JsonObject doShowMarkdown(JsonObject req) {
        String content = strField(req, "content");
        if (content == null) return err("show_markdown: missing 'content'");
        controller.showMarkdown(content);
        return ok("show_markdown");
    }

    private JsonObject doHighlightRoi(JsonObject req) {
        String title = strField(req, "title");
        if (title == null || title.isEmpty()) {
            title = strField(req, "image");
        }
        if (title == null || title.isEmpty()) return err("highlight_roi: missing 'title'");

        JsonElement roiEl = req.get("roi");
        if (roiEl == null) roiEl = req.get("bounds");
        int[] bounds = parseBounds(roiEl);
        if (bounds == null) {
            return err("highlight_roi: 'roi' must be a 4-element [x,y,w,h] array");
        }
        controller.highlightRoi(title, bounds);
        return ok("highlight_roi");
    }

    private JsonObject doFocusImage(JsonObject req) {
        String title = strField(req, "title");
        if (title == null || title.isEmpty()) {
            title = strField(req, "image");
        }
        if (title == null || title.isEmpty()) return err("focus_image: missing 'title'");
        controller.focusImage(title);
        return ok("focus_image");
    }

    /**
     * {@code confirm} is fire-and-forget on the TCP response — the dispatcher
     * returns immediately with a generated request id. The actual choice is
     * published as {@code gui_action.confirm.resolved} on the {@link EventBus}
     * once the user clicks. Subscribers correlate via the {@code id} field.
     */
    private JsonObject doConfirm(JsonObject req) {
        final String prompt = strField(req, "prompt");
        if (prompt == null || prompt.isEmpty()) return err("confirm: missing 'prompt'");

        final List<String> options = parseOptions(req.get("options"));
        if (options == null || options.isEmpty()) {
            // Tolerant fallback: also accept "actions" (matches the spec's
            // alt-name) and a comma-separated string.
            JsonElement alt = req.get("actions");
            List<String> fallback = parseOptions(alt);
            if (fallback != null && !fallback.isEmpty()) {
                return doConfirm(replaceOptions(req, fallback));
            }
            return err("confirm: 'options' must be a non-empty array");
        }

        // Caller-supplied id wins; otherwise generate one so the bus payload
        // is always correlatable. Plain integer is enough — the bus is
        // single-process and we just need uniqueness within a session.
        String reqId = strField(req, "id");
        final String confirmId = (reqId == null || reqId.isEmpty())
                ? "confirm-" + confirmCounter.incrementAndGet()
                : reqId;

        controller.confirm(prompt, options, new Consumer<String>() {
            @Override
            public void accept(String chosen) {
                JsonObject data = new JsonObject();
                data.addProperty("id", confirmId);
                data.addProperty("prompt", prompt);
                data.addProperty("choice", chosen);
                JsonArray optsArr = new JsonArray();
                for (String o : options) optsArr.add(o);
                data.add("options", optsArr);
                eventBus.publish("gui_action.confirm.resolved", data);
            }
        });

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("type", "confirm");
        resp.addProperty("id", confirmId);
        resp.addProperty("pending", true);
        return resp;
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static String strField(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        return el.getAsString();
    }

    private static int[] parseBounds(JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        JsonArray arr = el.getAsJsonArray();
        if (arr.size() != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            JsonElement n = arr.get(i);
            if (n == null || !n.isJsonPrimitive()) return null;
            try {
                out[i] = n.getAsInt();
            } catch (Exception e) {
                return null;
            }
        }
        return out;
    }

    private static List<String> parseOptions(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonArray()) {
            List<String> out = new ArrayList<String>();
            JsonArray arr = el.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement n = arr.get(i);
                if (n == null || !n.isJsonPrimitive()) continue;
                String s = n.getAsString();
                if (s != null && !s.isEmpty()) out.add(s);
            }
            return out;
        }
        if (el.isJsonPrimitive()) {
            String csv = el.getAsString();
            if (csv == null) return null;
            List<String> out = new ArrayList<String>();
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        return null;
    }

    private static JsonObject replaceOptions(JsonObject req, List<String> options) {
        JsonObject copy = new JsonObject();
        for (java.util.Map.Entry<String, JsonElement> e : req.entrySet()) {
            if (!"options".equals(e.getKey()) && !"actions".equals(e.getKey())) {
                copy.add(e.getKey(), e.getValue());
            }
        }
        JsonArray arr = new JsonArray();
        for (String o : options) arr.add(o);
        copy.add("options", arr);
        return copy;
    }

    private static JsonObject ok(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("type", type);
        return o;
    }

    private static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }
}
