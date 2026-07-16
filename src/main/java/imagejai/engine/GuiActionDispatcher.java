package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import imagejai.ui.ChatPanelController;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

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
    public static final int MAX_CONFIRM_ID_CHARS = 128;
    public static final int MAX_PENDING_CONFIRMATIONS = 64;
    private static final int MAX_TERMINAL_CONFIRMATIONS = 256;
    private static final Pattern CONFIRM_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_CONFIRM_ID_CHARS + "}");

    /**
     * Cancellation token for work queued on Swing's event thread. Invalidate
     * it when the caller times out: a runnable that has not started will then
     * be skipped instead of mutating Fiji after an error response was sent.
     */
    public static final class ActionToken {
        private boolean valid = true;
        private boolean started;
        private boolean finished;
        private final CountDownLatch finishedSignal = new CountDownLatch(1);

        private synchronized boolean tryStart() {
            if (!valid) return false;
            started = true;
            return true;
        }

        private synchronized void finish() {
            finished = true;
            finishedSignal.countDown();
        }

        /** Returns true only when queued work was invalidated before start. */
        public synchronized boolean invalidate() {
            if (finished) return false;
            valid = false;
            return !started;
        }

        public synchronized boolean hasStarted() { return started; }
        public synchronized boolean isFinished() { return finished; }
        public synchronized boolean isValid() { return valid; }
        public void awaitFinished() throws InterruptedException {
            finishedSignal.await();
        }
    }

    /** Queue one action with a token checked immediately before execution. */
    public static ActionToken queueSwingAction(final Runnable action) {
        if (action == null) throw new IllegalArgumentException("action is required");
        final ActionToken token = new ActionToken();
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                if (!token.tryStart()) {
                    token.finish();
                    return;
                }
                try {
                    action.run();
                } finally {
                    token.finish();
                }
            }
        });
        return token;
    }

    private final ChatPanelController controller;
    private final EventBus eventBus;
    private final AtomicLong confirmCounter = new AtomicLong(0);
    private final Object confirmLock = new Object();
    private final Map<String, PendingConfirmation> pendingConfirmations =
            new LinkedHashMap<String, PendingConfirmation>();
    private final LinkedHashMap<String, Boolean> terminalConfirmations =
            new LinkedHashMap<String, Boolean>();

    private static final class PendingConfirmation {
        final String id;
        final String prompt;
        final List<String> options;
        boolean terminal;

        PendingConfirmation(String id, String prompt, List<String> options) {
            this.id = id;
            this.prompt = prompt;
            this.options = options;
        }
    }

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
            } else if ("confirm_cancel".equals(type)) {
                return doCancelConfirm(req);
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
        if (reqId != null && !reqId.isEmpty()
                && !CONFIRM_ID_PATTERN.matcher(reqId).matches()) {
            return err("confirm: 'id' must contain 1-"
                    + MAX_CONFIRM_ID_CHARS
                    + " ASCII letters, digits, '.', '_', ':', or '-'");
        }
        final String confirmId = (reqId == null || reqId.isEmpty())
                ? "confirm-" + confirmCounter.incrementAndGet()
                : reqId;

        final PendingConfirmation pending =
                new PendingConfirmation(confirmId, prompt, options);
        synchronized (confirmLock) {
            if (pendingConfirmations.containsKey(confirmId)) {
                return err("confirm: id is already pending");
            }
            if (terminalConfirmations.containsKey(confirmId)) {
                return err("confirm: id was recently completed; use a new id");
            }
            if (pendingConfirmations.size() >= MAX_PENDING_CONFIRMATIONS) {
                return err("confirm: too many pending confirmations");
            }
            pendingConfirmations.put(confirmId, pending);
        }

        final boolean admitted;
        try {
            admitted = controller.confirm(confirmId, prompt, options, new Consumer<String>() {
            @Override
            public void accept(String chosen) {
                resolveConfirmation(pending, chosen);
            }
            });
        } catch (RuntimeException failure) {
            abandonConfirmation(pending);
            throw failure;
        }
        if (!admitted) {
            abandonConfirmation(pending);
            return err("confirm: chat panel could not admit confirmation");
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("type", "confirm");
        resp.addProperty("id", confirmId);
        resp.addProperty("pending", true);
        return resp;
    }

    /**
     * Wake a confirmation subscriber after its client-side deadline. The
     * client closes its stream before sending this action, so publishing the
     * correlated cancellation makes the TCP pump observe the closed socket
     * immediately instead of retaining one of the eight subscriber slots
     * until the next 30-second heartbeat.
     */
    private JsonObject doCancelConfirm(JsonObject req) {
        String confirmId = strField(req, "id");
        if (!validConfirmId(confirmId)) {
            return err("confirm_cancel: invalid or missing 'id'");
        }

        boolean publishCancellation;
        synchronized (confirmLock) {
            PendingConfirmation pending = pendingConfirmations.get(confirmId);
            if (pending != null && !pending.terminal) {
                pending.terminal = true;
                pendingConfirmations.remove(confirmId);
                rememberTerminalLocked(confirmId);
                publishCancellation = true;
            } else if (terminalConfirmations.containsKey(confirmId)) {
                publishCancellation = false;
            } else {
                // Preserve cleanup for a subscriber whose confirm response was
                // lost, while making repeated cancellation idempotent.
                rememberTerminalLocked(confirmId);
                publishCancellation = true;
            }
        }

        // The controller invalidates the callback first and performs the exact
        // ID-keyed Swing cleanup before the correlated event is visible.
        controller.cancelConfirmation(confirmId);
        if (publishCancellation) {
            JsonObject data = new JsonObject();
            data.addProperty("id", confirmId);
            data.addProperty("cancelled", true);
            eventBus.publish("gui_action.confirm.resolved", data);
        }

        JsonObject resp = ok("confirm_cancel");
        resp.addProperty("id", confirmId);
        resp.addProperty("cancelled", publishCancellation);
        if (!publishCancellation) resp.addProperty("already_resolved", true);
        return resp;
    }

    private void resolveConfirmation(PendingConfirmation pending, String chosen) {
        synchronized (confirmLock) {
            if (pending.terminal
                    || pendingConfirmations.get(pending.id) != pending) {
                return;
            }
            pending.terminal = true;
            pendingConfirmations.remove(pending.id);
            rememberTerminalLocked(pending.id);
        }

        JsonObject data = new JsonObject();
        data.addProperty("id", pending.id);
        data.addProperty("prompt", pending.prompt);
        data.addProperty("choice", chosen);
        JsonArray optsArr = new JsonArray();
        for (String option : pending.options) optsArr.add(option);
        data.add("options", optsArr);
        eventBus.publish("gui_action.confirm.resolved", data);
    }

    private void abandonConfirmation(PendingConfirmation pending) {
        synchronized (confirmLock) {
            if (pendingConfirmations.get(pending.id) == pending) {
                pending.terminal = true;
                pendingConfirmations.remove(pending.id);
                rememberTerminalLocked(pending.id);
            }
        }
    }

    private void rememberTerminalLocked(String confirmationId) {
        terminalConfirmations.put(confirmationId, Boolean.TRUE);
        while (terminalConfirmations.size() > MAX_TERMINAL_CONFIRMATIONS) {
            String oldest = terminalConfirmations.keySet().iterator().next();
            terminalConfirmations.remove(oldest);
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static String strField(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        return el.getAsString();
    }

    private static boolean validConfirmId(String value) {
        return value != null && CONFIRM_ID_PATTERN.matcher(value).matches();
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
