# Stage 03 — Settings, port collision, first-run no-key path

## Why this stage exists

Three pre-conditions have to hold before embedded-mode work is
user-facing: (a) a settings toggle lets users opt in or stay on the
external-terminal path; (b) TCP port 7746 conflicts must fail
gracefully instead of crashing the plugin; (c) a user launching
Fiji for the first time with **no API key** must be able to reach
the agent dropdown and use a local-only agent (Gemma via Ollama).
Without (c), the hard-disable in `ChatPanel.updateInputState`
blocks the local-only flow that this whole project is built around.

## Prerequisites

- Stage 02 `_COMPLETED`.

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §4 (Phase 0 bullets on
  settings migration, TCP port collision, first-run no-key path)
- `src/main/java/imagejai/config/Settings.java`
- `src/main/java/imagejai/ui/SettingsDialog.java`
  (especially `firstRunFlow()`)
- `src/main/java/imagejai/ui/ChatPanel.java:178-193`
  (the hard-disable logic)
- `src/main/java/imagejai/ImageJAIPlugin.java` (TCP
  server startup — locate `startTcpServer` or equivalent)

## Scope

- `Settings.agentEmbeddedTerminal` boolean. New installs default
  `true`; existing installs default `false` and are prompted once.
- `Settings.tcpPort` — read by hotline buttons (stage 07) and by
  the TCP server itself instead of a hardcoded `7746`.
- `SettingsDialog` — surface the embedded-terminal flag as a
  checkbox in the general tab.
- Settings migration: on load, detect a prior `Settings` JSON
  without the `agentEmbeddedTerminal` key, set it to `false`, flag
  a one-time prompt on next launch ("Try the new embedded terminal?
  Yes / Keep external").
- TCP port collision handling in `ImageJAIPlugin.startTcpServer`:
  if `:7746` bind fails, scan `:7747`–`:7750` for the first free
  port; write the chosen port to `Settings.tcpPort` and to the
  status bar. Log the fallback with `[ImageJAI-TCP]` prefix.
- First-run no-key path in `SettingsDialog.firstRunFlow()`: add a
  "Use local agent only (no API key)" option. When selected, the
  dialog does not require an API key.
- `ChatPanel.updateInputState` — treat local-only as a valid state
  (enable input if the active agent's `requiresApiKey == false`).
- New file `src/main/resources/META-INF/NOTICE.md` with the
  planned LGPL-3.0 (JediTerm) + EPL-1.0 (pty4j) attributions, even
  though those deps land in stage 05. Having the file on disk now
  means stage 05 only edits content, not build/packaging plumbing.

## Out of scope

- JediTerm / pty4j dependencies — stage 05.
- Embedded-terminal-mode UI rendering — stage 06.
- Any new TCP command — explicitly none across the whole project
  (agent-silence rule).

## Files touched

| Path                                                                  | Action | Reason                                               |
|-----------------------------------------------------------------------|--------|------------------------------------------------------|
| `src/main/java/imagejai/config/Settings.java`              | MODIFY | `agentEmbeddedTerminal`, `tcpPort`, migration hook   |
| `src/main/java/imagejai/ui/SettingsDialog.java`            | MODIFY | Surface flag + no-key first-run option               |
| `src/main/java/imagejai/ui/ChatPanel.java`                 | MODIFY | `updateInputState` honours local-only                |
| `src/main/java/imagejai/ImageJAIPlugin.java`               | MODIFY | Port-collision scan; write chosen port to Settings   |
| `src/main/resources/META-INF/NOTICE.md`                               | NEW    | LGPL-3.0 + EPL-1.0 attributions (content final in 05)|

## Implementation sketch

```java
// Settings.java
public boolean agentEmbeddedTerminal = false;   // migrated installs
public int tcpPort = 7746;                      // written at startup

// ImageJAIPlugin.startTcpServer (pseudocode)
for (int port : new int[]{7746, 7747, 7748, 7749, 7750}) {
    try {
        serverSocket = new ServerSocket(port);
        Settings.get().tcpPort = port;
        IJ.log("[ImageJAI-TCP] listening on :" + port);
        break;
    } catch (BindException be) {
        IJ.log("[ImageJAI-TCP] :" + port + " busy, trying next");
    }
}
```

First-run flow addition — a radio-group:
```
(•) Use an API-backed agent (Claude, Aider, Gemini, Codex)
( ) Use local agent only (no API key) — runs Gemma via Ollama
```

## Exit gate

1. `mvn package` succeeds; plugin loads in Fiji.
2. Fresh install → first-run dialog shows the new "local agent
   only" option; selecting it skips API-key validation.
3. Upgrade path: existing Settings JSON without the new key loads
   cleanly and shows the one-time "try embedded" prompt.
4. Two Fiji instances on the same machine: second boots and binds
   `:7747`, and the status bar shows the chosen port.
5. With no API key saved, opening the plugin does not hard-disable
   the input — confirmed by choosing a local-only agent and typing.

## Known risks

- Settings migration is the most likely regression surface. Back
  up a saved Settings JSON before testing.
- If `ChatPanel.updateInputState` is called before `Settings` is
  loaded (race on startup), the input may briefly flicker. Add a
  null-guard / default-to-disabled so the worst case is "user
  waits 100 ms".
