# Stage 06 — Plugin-frame integration

## Why this stage exists

Stage 05 proved JediTerm can host the agent. This stage wires that
proof into the actual plugin window: the embedded terminal replaces
the external `cmd.exe start` window for users who have opted in via
the Settings flag from stage 03, and `ChatPanel` is decomposed so
terminal input and chat input don't fight each other. After this
stage ships, the end-user experience is "one Fiji window, agent
runs inside it" — every later stage adds rail buttons, prompt
interception, and session-history UI on top of this frame.

## Prerequisites

- Stages 03, 05 `_COMPLETED`.

## Read first

- `docs/embedded-agent-widget/00_overview.md` (architecture diagram)
- `docs/embedded-agent-widget/PLAN.md` §2 ("Architecture"), §4
  Phase 2 bullets
- `src/main/java/imagejai/ui/ChatPanel.java` (whole file
  — especially `:42-50` colour constants, `:178-193` input state,
  `:905-952` launcher menu)
- `src/main/java/imagejai/ui/ChatSurface.java` (from
  stage 02)
- `src/main/java/imagejai/ImageJAIPlugin.java:40-135`
- `src/main/java/imagejai/terminal/EmbeddedPty.java`
  (from stage 05)

## Scope

- Extract `ChatView` from `ChatPanel` — **body region only** (the
  messages pane + input bar + `ChatPanelController` methods).
  Header and launcher dropdown stay one level up.
- New `AiRootPanel` holds the existing header plus a `CardLayout`
  body that swaps between `ChatView` and `TerminalView`.
- `TerminalView` (NEW) = `WEST: LeftRail` stub (empty container —
  populated in stage 07) + `CENTER: TerminalHost`.
- `TerminalHost` holds a live `JediTermWidget` for an
  `EmbeddedAgentSession`.
- `ImageJAIPlugin.run()` swaps to `AiRootPanel`. Frame size:
  420×600 for chat mode (preserved), grows to ~900×700 when
  terminal card is shown.
- Mode routing: `AgentLauncher.launch(info, mode)` where `mode` is
  derived from `Settings.agentEmbeddedTerminal`. `EMBEDDED` →
  terminal card; `EXTERNAL` → stays on chat card (today's
  behaviour). When the PTY exits, flip back to chat card.
- Zombie-process safety:
  - `Runtime.addShutdownHook` calls `destroy()` on every live
    `AgentSession`.
  - The existing `windowClosed` cleanup path in
    `ImageJAIPlugin.run()` calls the same shared
    `shutdownSessions()` helper (do not duplicate logic).
- Remove the stage-05 "Terminal Smoke Test" menu entry (or hide it
  behind a debug flag) now that the real path is live.

## Out of scope

- Any rail button implementation — stages 07, 10, 11.
- Prompt interception / toolbar / URL hyperlinks — stage 08.
- Font-size shortcuts, colour-scheme theming, window-size
  persistence — stage 12.
- Session history panel — stage 11.

## Files touched

| Path                                                                  | Action | Reason                                          |
|-----------------------------------------------------------------------|--------|-------------------------------------------------|
| `src/main/java/imagejai/ui/AiRootPanel.java`               | NEW    | Header + CardLayout body                        |
| `src/main/java/imagejai/ui/ChatView.java`                  | NEW    | Extracted body of ChatPanel                     |
| `src/main/java/imagejai/ui/TerminalView.java`              | NEW    | WEST rail (stub) + CENTER terminal host         |
| `src/main/java/imagejai/ui/TerminalHost.java`              | NEW    | Holds JediTermWidget + resize handling          |
| `src/main/java/imagejai/ui/ChatPanel.java`                 | MODIFY | Reduced to header + compatibility wrapper       |
| `src/main/java/imagejai/ImageJAIPlugin.java`               | MODIFY | Use AiRootPanel, shutdown hook, size policy     |

## Implementation sketch

```
AiRootPanel (BorderLayout)
 ├─ NORTH: HeaderBar  (profile / agent dropdown / clear / settings)
 ├─ CENTER: JPanel with CardLayout
 │    ├─ card "chat":     ChatView
 │    └─ card "terminal": TerminalView
 │                          ├─ WEST: LeftRail (empty stub)
 │                          └─ CENTER: TerminalHost(JediTermWidget)
 └─ SOUTH: StatusBar  (active image / TCP port / agent name)
```

Card-flip triggers:
- `launch(info, EMBEDDED)` succeeds → `cardLayout.show(body, "terminal")`.
- `session.isAlive()` transitions to false (detected via a
  listener or a short-poll Swing Timer) → `cardLayout.show(body, "chat")`.

Frame size:
```java
if ("terminal".equals(currentCard)) frame.setSize(900, 700);
else                                frame.setSize(420, 600);
```

`shutdownSessions()` helper lives on `AiRootPanel` (or a new
`SessionRegistry` — either is fine; pick one and be consistent):

```java
void shutdownSessions() {
    for (AgentSession s : liveSessions) {
        try { s.destroy(); } catch (Exception ignore) {}
    }
    liveSessions.clear();
}
```

## Exit gate

1. `mvn package` succeeds; plugin loads.
2. With `agentEmbeddedTerminal=true`, launching every agent in
   `KNOWN_AGENTS` shows the terminal card with the agent running
   inside it — including Gemma under both `--style` variants.
3. With `agentEmbeddedTerminal=false`, launching opens the
   existing external terminal; chat card stays visible.
4. Closing the plugin window (`windowClosed`) leaves no orphan
   processes — verified via Task Manager (Windows) /
   `ps | grep gemma` (macOS / Linux).
5. Exiting an agent via `/exit` or Ctrl+D flips the card back to
   `chat` automatically; the user can re-launch a different agent
   without closing the plugin.

## Known risks

- **God-panel fight** (PLAN.md §2). If you leave chat-input logic
  coupled to terminal-input logic, stage 08 will be painful. Do
  the extraction cleanly — `ChatView` owns chat; `TerminalHost`
  owns terminal; `AiRootPanel` owns neither, only swaps them.
- **CardLayout + JediTerm focus.** JediTerm may grab focus on
  every card-show. That's fine when flipping to terminal, wrong
  when flipping to chat. On `show("chat", …)` call
  `chatView.requestFocusInWindow()` explicitly.
- **Settings drift.** If the user edits the embedded flag while
  an agent is running, don't change card mid-session — apply the
  new value only on the next `launch()`.
