# Stage 12 — Polish, cross-platform, persistence

## Why this stage exists

Everything needed for a biologist to open Fiji, pick an agent, run
a recipe, audit results, and re-run any macro from session history
is already shipped by stage 11. This stage is the "a new user can
sit down and it just feels finished" layer: keyboard shortcuts
inside the terminal, per-mode window size memory, colour-scheme
parity with the chat pane, optional terminal-output persistence,
a macOS / Linux smoke test, and the monthly dev script for
registry refresh. After this stage ships, the feature is done.

## Prerequisites

- Stages 01–11 all `_COMPLETED`.

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §4 Phase 5 bullets
- `src/main/java/imagejai/ui/ChatPanel.java:42-50`
  (colour constants: `BG_MAIN #1e1e23`, `ACCENT #00c8ff`, etc.
  — the JediTerm colour scheme must match)
- `src/main/java/imagejai/ui/AiRootPanel.java` (from
  stage 06 — window-size memory hooks here)

## Scope

- **Font-size shortcuts.** Ctrl+= / Ctrl+- change JediTerm font
  size by ±1 pt. Persisted via `ij.Prefs`
  (`ai.assistant.terminal.fontsize`). Bounds 8–28.
- **Per-mode window size memory.** Chat card remembers 420×600
  (or last user resize); terminal card remembers 900×700 (or
  last). Keys `ai.assistant.window.size.chat` /
  `.window.size.terminal`.
- **JediTerm colour scheme derived from `ChatPanel` constants.**
  Build a `ColorPalette` that uses `BG_MAIN #1e1e23` as
  background, `ACCENT #00c8ff` as the cursor colour, etc. Match
  foreground / selection / highlight to the chat pane's existing
  palette so users flipping cards don't feel visual whiplash.
- **Log prefix consistency.** All new code from stages 05–11
  uses `IJ.log("[ImageJAI-Term] ...")`. Audit each touched file
  with `grep "IJ.log" src/main/java/imagejai/{terminal,ui}`
  and fix any `[ImageJAI]` / `[ImageJAI-TCP]` leakage.
- **macOS + Linux smoke test.** Run the full v1 exit gate (PLAN
  §4 Phase 5 exit gate) on:
  - macOS ARM (Apple Silicon) — 1 machine.
  - Ubuntu 22.04 / X11 — 1 machine.
  - Focus: font fallback (does Noto Emoji load?), pty4j native
    extraction (does `libpty.so` / `libpty.dylib` load from the
    Fiji-plugins temp dir?), Ctrl+C / Ctrl+V keybindings.
- **Scrollback-output persistence (optional, off by default).**
  Stage 04 already owns executed-code persistence. This adds the
  *terminal-output* side: on embedded-session exit, serialise
  the last N scrollback lines to
  `AI_Exports/.session/log/<agent>_<timestamp>.log`. Off by
  default (scrollback may contain pasted credentials). Opt-in
  via a Settings checkbox.
- **Bracketed paste (contingent).** Only flip
  `setBracketedPasteMode(true)` on `JediTermWidget` if stage 08
  field reports show prompt_toolkit paste corruption in Gemma.
  If no reports: leave off, match AgentConsole parity.
- **Registry-refresh dev script.** Polish pass on stage 09's
  `scripts/refresh_agent_registries.{sh,ps1}` — confirm it runs
  clean on a fresh machine with only one agent installed; skip
  missing agents with a warning, not an error.

## Out of scope

- Voice input, multi-tab concurrent agents, paired planner /
  implementer — all v2+ (PLAN.md §8 non-goals).
- Shadow macro preview / confirm-before-run — v2 additive change
  to `TCPCommandServer.handleExecuteMacro`.
- Save-as-recipe from session — deferred in PLAN.md §8 pending
  `harvest_recipe.py` CLI entry point.

## Files touched

| Path                                                                  | Action | Reason                                             |
|-----------------------------------------------------------------------|--------|----------------------------------------------------|
| `src/main/java/imagejai/ui/TerminalHost.java`              | MODIFY | Font-size shortcuts + colour palette               |
| `src/main/java/imagejai/ui/AiRootPanel.java`               | MODIFY | Per-mode window-size memory                        |
| `src/main/java/imagejai/config/Settings.java`              | MODIFY | `persistScrollback` opt-in flag                    |
| `src/main/java/imagejai/ui/SettingsDialog.java`            | MODIFY | Expose `persistScrollback` checkbox                |
| `src/main/java/imagejai/engine/EmbeddedAgentSession.java`  | MODIFY | On destroy: serialise scrollback if enabled        |
| `scripts/refresh_agent_registries.sh`                                 | MODIFY | Graceful skip on missing agents                    |
| `scripts/refresh_agent_registries.ps1`                                | MODIFY | Ditto                                              |

## Implementation sketch

Font-size binding:
```java
termHost.registerShortcut(KeyStroke.getKeyStroke(VK_EQUALS, CTRL_DOWN_MASK),
    () -> setFontSize(Math.min(fontSize + 1, 28)));
termHost.registerShortcut(KeyStroke.getKeyStroke(VK_MINUS, CTRL_DOWN_MASK),
    () -> setFontSize(Math.max(fontSize - 1, 8)));
```

Window-size memory (per-mode):
```java
void onCardShown(String card) {
    Dimension saved = ij.Prefs.getString("ai.assistant.window.size." + card);
    frame.setSize(saved != null ? saved : defaultFor(card));
}
frame.addComponentListener(resizeEvt -> {
    ij.Prefs.set("ai.assistant.window.size." + currentCard, frame.getSize());
});
```

Colour palette sketch (pulled from `ChatPanel.java:42-50`):
```java
ColorPalette palette = new ColorPalette()
    .background(new Color(0x1e1e23))
    .foreground(new Color(0xe6e6e6))
    .accent(new Color(0x00c8ff))
    // ...fill from ChatPanel constants
    ;
jediTermWidget.setColorPalette(palette);
```

## Exit gate

A new user on a clean Fiji install (Windows 11, macOS, and
Ubuntu) can:
1. Open the plugin.
2. Launch Gemma with no API key (stage 03's first-run path).
3. Run `cell_counting.yaml` via Run recipe (stage 10).
4. Use Audit my results (stage 10).
5. Re-run any macro from session history by double-clicking
   (stage 11).
6. Never leave the Fiji window.
7. Close the plugin; verify no orphan processes.
8. Reopen the plugin; terminal window opens at the remembered
   size; if persist-across-restarts is on, session history has
   the prior entries.

Ctrl+= / Ctrl+- resizes font; setting persists. Dark-mode colours
match the chat pane. Terminal logs use `[ImageJAI-Term]` prefix.
macOS and Linux smoke test pass the Windows Phase-1 renderer
checklist (stage 05's exit gate).

## Known risks

- `ij.Prefs` stores strings; serialising a `Dimension` needs a
  simple `"WxH"` convention. Pick one, document, stick to it.
- Colour palette API has shifted between JediTerm point
  releases. If the call in the sketch doesn't exist on the
  pinned version, fall back to overriding individual SGR colour
  codes via `TerminalSettingsProvider`.
- Linux terminal font metrics can differ by distro. If
  JetBrains Mono isn't fixed-width on GTK, double-check
  `GraphicsEnvironment.registerFont` actually registered it
  (check the returned boolean).
