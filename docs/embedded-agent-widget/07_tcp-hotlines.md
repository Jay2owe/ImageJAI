# Stage 07 — TCP hotlines (left rail first content)

## Why this stage exists

The left rail in `TerminalView` has been an empty stub since stage
06. This stage populates its first section with three zero-LLM
buttons — Close all images, Reset ROI Manager, Z-project (max) —
wired directly to the existing TCP `execute_macro` handler.
Biologists get instant, deterministic Fiji operations without
waiting for an agent round-trip; the pattern (`TcpHotline` helper
+ `LeftRail` + idempotent command) also establishes the scaffolding
that stages 10 and 11 reuse for their rail sections.

## Prerequisites

- Stage 06 `_COMPLETED`.

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.4 (rail dispatch modes),
  §2.5 (TCP server interaction), §4 Phase 3 bullets
- `src/main/java/imagejai/engine/TCPCommandServer.java`
  `:661-662` (`execute_macro` dispatch), `:1153` (handler)
- `src/main/java/imagejai/ui/TerminalView.java` (from
  stage 06 — the rail stub's WEST component)
- `src/main/java/imagejai/config/Settings.java`
  `.tcpPort` (from stage 03 — **must** be read here, not a
  hardcoded constant)

## Scope

- `LeftRail` JPanel with `BoxLayout.Y_AXIS`, collapsible via a
  chevron button in its header. Collapsed state persisted via
  `ij.Prefs`.
- `TcpHotline` helper: opens a short-lived socket to
  `localhost:{Settings.tcpPort}`, sends one JSON command, reads
  the response, closes. Returns a `JsonObject` (or throws with
  a user-readable message).
- Three buttons in a "Fiji hotlines" section of the rail:
  - **Close all images** → `execute_macro` with `run('Close All');`
  - **Reset ROI Manager** → `execute_macro` with `roiManager('Reset');`
  - **Z-project (max)** → `execute_macro` with
    `run('Z Project...', 'projection=[Max Intensity]');`
- Buttons check state before acting:
  - Z-project disabled (or shows a clear "open a stack first"
    error) unless a stack is currently open. Use
    `stateInspector.getActiveImageInfo()` pattern via a cheap
    `get_image_info` TCP call before firing.
- Each button's tooltip shows the macro payload verbatim.
- Font size + rail collapsed state persisted via `ij.Prefs` under
  keys `ai.assistant.rail.collapsed` and `ai.assistant.rail.font`.

## Out of scope

- Macro sets / Run recipe / New WIP / Audit buttons — stage 10.
- Session history rail section — stage 11.
- Commands / New-agent-chat buttons (PTY-inject mode) — stage 09.
- TerminalToolbar (Confirm / Cancel / Interrupt / Kill) — stage 08.

## Files touched

| Path                                                              | Action | Reason                                       |
|-------------------------------------------------------------------|--------|----------------------------------------------|
| `src/main/java/imagejai/ui/LeftRail.java`              | NEW    | Container + header chevron + sections API    |
| `src/main/java/imagejai/ui/TcpHotline.java`            | NEW    | Short-lived socket JSON client               |
| `src/main/java/imagejai/ui/TerminalView.java`          | MODIFY | Swap WEST stub for the real `LeftRail`       |

## Implementation sketch

```java
public final class TcpHotline {
    public static JsonObject send(JsonObject request) throws IOException {
        int port = Settings.get().tcpPort;
        try (Socket s = new Socket("localhost", port);
             Writer w = new OutputStreamWriter(s.getOutputStream(), UTF_8);
             Reader r = new InputStreamReader(s.getInputStream(), UTF_8)) {
            w.write(request.toString() + "\n"); w.flush();
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    public static JsonObject executeMacro(String code) {
        JsonObject req = new JsonObject();
        req.addProperty("command", "execute_macro");
        req.addProperty("code", code);
        req.addProperty("source", "rail:hotline");   // reused by journal
        return send(req);
    }
}
```

Rail section skeleton:
```java
section("Fiji hotlines")
  .button("Close all images",  () -> TcpHotline.executeMacro("run('Close All');"))
  .button("Reset ROI Manager", () -> TcpHotline.executeMacro("roiManager('Reset');"))
  .button("Z-project (max)",   () -> {
      if (!stackOpen()) { toast("Open a stack first"); return; }
      TcpHotline.executeMacro("run('Z Project...', 'projection=[Max Intensity]');");
  });
```

## Exit gate

1. With Fiji open and an image loaded, clicking each button
   performs the documented operation in <500 ms, with no agent
   running.
2. Z-project button is disabled (or toasts a clear message) when
   no stack is open.
3. Rail collapse chevron works and the state persists across
   plugin restarts via `ij.Prefs`.
4. Tooltip on each button shows the literal macro payload.
5. With two Fiji instances (so one is on `:7747`), buttons in the
   second instance still hit the right server because
   `TcpHotline` reads `Settings.tcpPort`, not a constant.
6. `SessionCodeJournal` (stage 04) entries for these clicks carry
   `source=rail:hotline` — no user surprise in stage 11's UI.

## Known risks

- Socket-leak if `TcpHotline.send` throws between `new Socket` and
  the try-with-resources open brace. Structure so **all** resources
  are acquired inside a single try-with-resources.
- EDT blocking: button click handlers must not call
  `TcpHotline.send` on the EDT — wrap in `SwingWorker` so Fiji
  doesn't freeze for the ~100 ms round-trip.
