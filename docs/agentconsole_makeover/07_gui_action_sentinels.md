# Phase 7 — GUI_ACTION Sentinels (Backchannel to the Chat Panel)

## Motivation

TCP today is one-directional from the agent's perspective: the agent sends commands, the server changes Fiji state. The external agent cannot ask the **plugin's own chat panel** (`ui/ChatPanel.java`) to do anything — can't inline an image preview, can't display a toast, can't highlight a ROI for the user's eye, can't surface a confirmation prompt.

This is a missed opportunity. When the external Claude captures a screenshot, the file sits in `agent/.tmp/` — the user has to `Read` it themselves or open it in another window. When Claude detects something worth the user's attention ("you probably want to re-threshold; here's why"), it has no way to make that visible *in the chat panel the user is actually looking at*.

AgentConsole solved this with `GUI_ACTION:` sentinels (`ai_commander.py:68` — `gui_action_requested` signal). The orchestrator can return `GUI_ACTION:discover`, `GUI_ACTION:show_dialog:...` etc., and the main window intercepts and reacts. The LLM stays ignorant of UI internals; the sentinel is a structured side-channel.

Metaphor: an intercom between the kitchen and the dining room. The chef (external agent) can't walk into the dining room, but they can ring the bell and send a note with the waiter.

## End Goal

- TCP responses may include an optional `gui_action` field: `{"ok": true, "result": {...}, "gui_action": {"type": "inline_preview", "path": ".tmp/x.png"}}`.
- The plugin's `ChatPanel` subscribes to a dispatcher that intercepts `gui_action` frames and acts on them.
- Supported actions (v1):
  - `inline_preview {path}` — show image inline in chat panel as a thumbnail.
  - `toast {message, level}` — transient notification (info/warn/error).
  - `highlight_roi {title, roi}` — select and flash a ROI on a named image.
  - `focus_image {title}` — bring a named image to front.
  - `show_markdown {content}` — render a short MD snippet inline (analysis summary).
  - `confirm {prompt, actions[]}` — blocking choice that emits a response event back on the bus (Phase 2 integration).
- Agents use these via explicit TCP command `gui_action {...}` or as a piggyback field on any response.

## Scope

### Server-side: `engine/GuiActionDispatcher.java` (new)

- Registry of action types.
- Each action type has a handler that must run on the EDT and touches only `ChatPanel`-level state.
- Exposed to `TCPCommandServer` so any command can attach a `gui_action` in the response path.
- Exposed as a standalone `gui_action` TCP command for external agents that just want to drive the UI without sending an imaging command.

### Server-side: `ui/ChatPanel.java`

- New `ChatPanelController` interface: `inlineImage(Path)`, `toast(String, Level)`, `showMarkdown(String)`, `confirm(String, List<String>, Consumer<String>)`, etc.
- Implementation uses Swing on the EDT. Toast = a transient `JLabel` at the top of the panel with a fade timer. Inline preview = existing `ImagePreview` component.

### Server-side: `engine/TCPCommandServer.java`

- Accept `gui_action` as a top-level command and route to dispatcher.
- On response serialisation, if the handler returned a `GuiAction`, attach to response JSON.

### Client-side: `agent/ij.py`

- `python ij.py toast "Analysis complete" --level info`
- `python ij.py inline /path/to/capture.png`
- `python ij.py focus "DAPI.tif"`
- Most commands accept an implicit `--toast-on-complete` flag for fire-and-announce workflows.

## Implementation Sketch

```java
// GuiActionDispatcher.java
public class GuiActionDispatcher {
    private final ChatPanelController ui;
    private final Map<String, Handler> handlers = new HashMap<>();

    public GuiActionDispatcher(ChatPanelController ui) {
        this.ui = ui;
        register("inline_preview", (data) -> SwingUtilities.invokeLater(() ->
            ui.inlineImage(Paths.get(data.get("path").getAsString()))));
        register("toast", (data) -> SwingUtilities.invokeLater(() ->
            ui.toast(data.get("message").getAsString(), level(data))));
        // ...
    }

    public JsonObject dispatch(JsonObject req) {
        String type = req.get("type").getAsString();
        Handler h = handlers.get(type);
        if (h == null) return err("unknown gui_action: " + type);
        h.handle(req);
        return ok();
    }
}
```

## Impact

- Captures don't need a separate `Read` step — they appear in the chat panel the user is already looking at.
- Analysis summaries from the external agent can surface as formatted markdown inline, turning the chat panel into a shared dashboard instead of a terminal log.
- Toasts give the external agent a lightweight way to nudge the user ("memory at 85%") without interrupting.
- `confirm` lets the external agent ask the user a question through the plugin without the user having to switch windows.

## Validation

1. From the agent terminal, `python ij.py toast "Hello" --level info` → toast appears in the plugin's chat panel within 500ms.
2. `python ij.py inline /tmp/x.png` → thumbnail appears inline.
3. `python ij.py confirm "Proceed with Otsu?" --options "Yes,No"` → blocks until user clicks in the chat panel; response comes back on the socket.
4. All actions dispatch on the EDT; no Swing thread-violation warnings in the ImageJ log.

## Risks

- **EDT safety is critical.** Every handler must dispatch via `SwingUtilities.invokeLater`. Mitigation: the dispatcher enforces this centrally; handlers can't touch Swing directly without going through the controller.
- **Denial of service.** An agent could spam toasts. Mitigation: rate-limit toasts to 1 per 500ms; queue overflow = drop oldest + show aggregated "N suppressed" notice.
- **User confusion.** The chat panel starts doing things the user didn't type. Mitigation: every agent-driven action appears in the chat panel with a clear "[agent]" prefix and a dismiss affordance; user can mute via a chat-panel toggle.
- **State coupling.** Now the TCP server needs a reference to the chat panel. Keep coupling loose via the `ChatPanelController` interface so TCP-only usage (no plugin open) still works — handlers become no-ops when the UI isn't available.
