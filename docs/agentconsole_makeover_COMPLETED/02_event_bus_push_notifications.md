# Phase 2 — Event Bus (Push Notifications)

## Motivation

The current TCP protocol is strictly **pull**: the client asks, the server answers. Any "did the image change?" / "did a dialog appear?" / "did the macro finish?" question requires a polling call. The context hook is effectively a polling loop disguised as a prompt hook.

This is wasteful and slow. When Fiji is idle for five minutes, the hook still fires on every user message, the server still walks the full state tree, and the LLM still receives an identical briefing. When a dialog pops up between hook calls, the agent doesn't know until the next prompt — often 30+ seconds of wasted wall time.

AgentConsole's solution is a **pub/sub bus**: the bus publishes events like `agent.<sid>.awaiting_input`, `agent.status_changed`, `worker.idle`. Subscribers receive pushes and react. Commander uses this to avoid polling `read output` in a loop.

Metaphor: polling is a nurse walking rounds every five minutes to check vitals; push notifications are bedside monitors that beep only when something changes.

## End Goal

A long-lived TCP subscription that pushes events to the agent as they happen inside Fiji:

- `image.opened {title, dims, calibration}` — a new image appears.
- `image.closed {title}`
- `image.updated {title, reason}` — slice change, LUT change, ROI change.
- `dialog.appeared {title, kind}` — modal, error, warning, generic dialog.
- `dialog.closed {title}`
- `macro.started {macro_id, preview}`
- `macro.completed {macro_id, success, error, new_images}`
- `results.changed {rows, cols}`
- `memory.pressure {used_pct}` — fires crossing 80%.
- `log.entry {line}` — throttled to significant lines.

The agent subscribes once at startup and reacts instead of polling.

## Scope

### Server-side: `engine/EventBus.java` (new)

- Single in-JVM publisher with topic strings.
- Thread-safe `publish(topic, JsonObject)`.
- `subscribe(topicPattern, listener)` — supports exact match and `*` wildcard suffix.
- Coalescing for high-frequency topics (`image.updated`, `log.entry`): debounce to ≤1 message per 200ms per topic.

### Server-side: `engine/TCPCommandServer.java`

- New command `subscribe {"topics": ["image.*", "dialog.*", "macro.completed"]}`.
- The subscribe command **does not close the socket**. Instead, the connection is upgraded into a streaming channel. Subsequent lines on the same socket are event frames: `{"event": "image.opened", "data": {...}, "ts": ...}\n`.
- A heartbeat frame (`{"event": "heartbeat", "ts": ...}`) every 30s so the client can detect dropped sockets.
- Unsubscribe = close the socket.

### Server-side: event sources

- `engine/ImageMonitor.java`: already polls for saturation/calibration/memory; extend to publish `image.*` and `memory.pressure`.
- `engine/CommandEngine.java`: publish `macro.started`/`macro.completed` around `executeMacro`.
- New `engine/DialogWatcher.java`: polls `WindowManager.getNonImageTitles()` + inspects `JDialog` roots, publishes `dialog.*`.
- `engine/StateInspector.java`: hooks on `ResultsTable.getResultsTable()` row count changes → `results.changed`.
- `ij.IJ.log` hook (via a `java.io.PrintStream` wrapper on the log window) → `log.entry` (throttled, only lines matching `(?i)error|warning|exception|failed`).

### Client-side: `agent/ij.py`

- New subcommand: `python ij.py subscribe image.* dialog.* macro.completed`
- Prints events to stdout in JSONL. Can be piped to a consumer (e.g. `jq`).
- Importable: `for event in imagej_events(["dialog.*"]): ...`

### Client-side: context hook

- On session start: open a background subscription thread to `image.*`, `dialog.*`, `macro.completed`, `memory.pressure`.
- Maintain an in-process snapshot of current Fiji state.
- When the hook fires, serve from the snapshot instead of polling TCP.
- Fall back to polling if the subscription socket drops.

## Implementation Sketch

```java
// EventBus.java
public class EventBus {
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPublish = new ConcurrentHashMap<>();
    public void publish(String topic, JsonObject data) {
        long now = System.currentTimeMillis();
        if (isCoalesced(topic) && now - lastPublish.getOrDefault(topic, 0L) < 200) return;
        lastPublish.put(topic, now);
        JsonObject frame = new JsonObject();
        frame.addProperty("event", topic);
        frame.add("data", data);
        frame.addProperty("ts", now);
        dispatch(topic, frame);
    }
}
```

```java
// TCPCommandServer.java subscribe handler
private void handleSubscribe(Socket sock, JsonObject req) {
    JsonArray topics = req.getAsJsonArray("topics");
    OutputStream out = sock.getOutputStream();
    Listener l = frame -> {
        synchronized (out) {
            out.write((frame.toString() + "\n").getBytes(UTF_8));
            out.flush();
        }
    };
    for (JsonElement t : topics) bus.subscribe(t.getAsString(), l);
    // Block this thread on heartbeats until the socket dies.
    sendHeartbeats(sock, out);
    for (JsonElement t : topics) bus.unsubscribe(t.getAsString(), l);
}
```

## Impact

- Idle Fiji + chatty user = **zero** extra TCP traffic for state refresh between meaningful events.
- Dialog appearance latency (from appearance → agent awareness) drops from "next prompt" (tens of seconds) to ~200ms.
- Macro completion notifications let async workflows (Phase 3) close the loop without polling.
- Context hook becomes event-driven; the cached snapshot is always current without any network cost at prompt time.

## Validation

1. `python ij.py subscribe image.*` in one terminal; open an image in Fiji → event prints immediately.
2. Heartbeat every 30s when idle.
3. Kill Fiji mid-subscription → client detects dropped socket within 60s and auto-reconnects.
4. Context hook with subscription active: zero `get_state` calls during a 10-prompt conversation that doesn't touch Fiji.

## Risks

- **Thread leaks.** Each subscriber holds a socket thread. Mitigation: cap at 8 concurrent subscriptions, enforce timeout on dead sockets, reuse subscription threads via a small thread pool.
- **Event storms.** A stack with 100 slices fires 100 `image.updated` events on "Play" animation. Mitigation: hard-coalesce `image.updated` to 5Hz max.
- **Event ordering.** Events from different publishers may arrive out of order. Mitigation: include monotonic `seq` field; clients sort by seq within short windows.
- **Backpressure.** Slow client blocks the publisher thread. Mitigation: per-socket bounded queue (e.g. 256 frames); overflow = drop oldest + emit `event_dropped` sentinel.
