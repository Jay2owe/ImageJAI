# Stage 05 — Terminal primitive (JediTerm + pty4j + fonts)

## Why this stage exists

This is where an embedded terminal actually starts rendering an
agent's TUI. The stage delivers a standalone smoke-test frame —
not wired into the plugin window yet — running Gemma through
JediTerm, with bundled fonts, so the renderer-stress checklist
(animated status line, braille spinner, tool-icon emojis,
prompt_toolkit history, Ctrl+C-aborts-turn) can be visibly
verified before touching `ImageJAIPlugin.run()`. If JediTerm on
Windows ConPTY can't render Gemma's alt-screen animations, that
failure must surface **here** so the JCEF escape hatch (PLAN.md
§7 risk 3) can be triggered before stage 06 does integration
work on top of a broken foundation.

## Prerequisites

- Stage 02 `_COMPLETED`. (Stage 04 may be in flight in parallel.)

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.1 ("Terminal backend"),
  §2.3 ("Agent features that must survive embedding"), §3
  ("Build changes"), §4 Phase 1 bullets
- `agent/gemma4_31b/loop.py` lines 124-157, 187-194, 206, 349-413,
  482-492, 685-700, 921, 1257-1262 — the renderer-stress surface
- `src/main/java/imagejai/engine/AgentSession.java`
  (from stage 02) — contract the new embedded session implements
- `src/main/resources/META-INF/NOTICE.md` (from stage 03) — fill
  in the final JediTerm / pty4j attributions and source URLs here

## Scope

- **15-minute version recheck** on the JetBrains
  `intellij-dependencies` repo — bump JediTerm / pty4j pins only
  if a newer release fixes a Windows ConPTY / alt-screen / resize
  issue. Otherwise keep 3.57 / 0.13.12.
- `pom.xml`:
  - Add JetBrains `intellij-dependencies` repo.
  - Add `pty4j` 0.13.12 and `jediterm-core` / `jediterm-ui` 3.57
    as `compile` scope.
  - Add `maven-shade-plugin` with `minimizeJar=false`,
    `ServicesResourceTransformer`, `ManifestResourceTransformer`.
- Bundled fonts under `src/main/resources/fonts/`:
  - `JetBrainsMono-Regular.ttf` (~200 KB, Apache-2.0).
  - `NotoEmoji-Regular.ttf` monochrome (~400 KB, SIL OFL 1.1).
  - Loaded via `Font.createFont` at plugin startup, registered as
    JediTerm's font chain (primary → emoji fallback → OS fallback).
- `ImageJAITtyConnector implements TtyConnector` — ~40 lines
  wrapping pty4j `PtyProcess` streams into JediTerm's interface.
- `EmbeddedPty` — composition of `PtyProcessBuilder` +
  `JediTermWidget` + `ImageJAITtyConnector`.
- `EmbeddedAgentSession implements AgentSession` wrapping
  `EmbeddedPty`; `writeInput(s)` appends `\r`, `interrupt()`
  writes `\x03`, `destroy()` sends EOF → 2 s grace →
  `destroyForcibly`.
- New menu entry `Plugins > AI Assistant > Terminal Smoke Test`
  that opens a bare `JFrame` with the widget and launches Gemma.
  Disposable — removed in stage 06 once the real integration
  lands, or kept behind a debug flag.
- Dropbox-sync native-lib test: first `PtyProcessBuilder` call
  inside the Dropbox-synced Fiji install. pty4j extracts
  `conpty.dll` to a temp dir. If Dropbox's file-lock behaviour
  blocks `System.load`, set
  `System.setProperty("pty4j.preferred.native.folder",
  <local-temp>)` before the first call.
- `AgentLauncher.launch(info, Mode.EMBEDDED)` now returns a real
  `EmbeddedAgentSession` instead of throwing.
- Fill in `src/main/resources/META-INF/NOTICE.md` content:
  JediTerm LGPL-3.0 + pty4j EPL-1.0, with upstream source URLs.

## Out of scope

- Replacing the external-terminal path inside the plugin window —
  stage 06.
- Any `LeftRail` / button wiring — stages 07, 10, 11.
- Prompt interception / URL hyperlinks — stage 08.

## Files touched

| Path                                                                       | Action | Reason                                           |
|----------------------------------------------------------------------------|--------|--------------------------------------------------|
| `pom.xml`                                                                  | MODIFY | JetBrains repo + deps + shade plugin             |
| `src/main/resources/fonts/JetBrainsMono-Regular.ttf`                       | NEW    | Primary terminal font                            |
| `src/main/resources/fonts/NotoEmoji-Regular.ttf`                           | NEW    | Monochrome emoji fallback                        |
| `src/main/java/imagejai/terminal/ImageJAITtyConnector.java`     | NEW    | pty4j ↔ JediTerm bridge                          |
| `src/main/java/imagejai/terminal/EmbeddedPty.java`              | NEW    | Widget + process composition                     |
| `src/main/java/imagejai/engine/EmbeddedAgentSession.java`       | NEW    | `AgentSession` over `EmbeddedPty`                |
| `src/main/java/imagejai/engine/AgentLauncher.java`              | MODIFY | Real implementation for `Mode.EMBEDDED`          |
| `src/main/java/imagejai/ImageJAIPlugin.java`                    | MODIFY | Register smoke-test menu + font loading          |
| `src/main/resources/META-INF/NOTICE.md`                                    | MODIFY | Fill in attributions                             |

## Implementation sketch

```java
public final class ImageJAITtyConnector implements TtyConnector {
    private final PtyProcess proc;
    private final InputStreamReader reader;
    public int read(char[] buf, int off, int len) throws IOException { ... }
    public void write(byte[] bytes) throws IOException { ... }
    public void write(String s) throws IOException { write(s.getBytes(UTF_8)); }
    public boolean isConnected() { return proc.isAlive(); }
    public void resize(TermSize sz) { proc.setWinSize(new WinSize(sz.cols, sz.rows)); }
    public int waitFor() throws InterruptedException { return proc.waitFor(); }
}
```

Font registration skeleton (run once at plugin start, not per
terminal):
```java
GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
ge.registerFont(Font.createFont(Font.TRUETYPE_FONT,
    getClass().getResourceAsStream("/fonts/JetBrainsMono-Regular.ttf")));
ge.registerFont(Font.createFont(Font.TRUETYPE_FONT,
    getClass().getResourceAsStream("/fonts/NotoEmoji-Regular.ttf")));
```

## Exit gate

On Windows 11, launching Gemma via the smoke-test frame renders
all of:
1. Startup banner (`loop.py:1257-1262`)
2. Animated status line — RGB pulse, wave-highlighted label
3. Tool icon emojis (🔎 🪄 📸 ⚡)
4. Braille spinner
5. Green `you>` prompt
6. prompt_toolkit history: `↑` recalls previous turn
7. Ctrl+C aborts turn (shows `_TurnAborted`); process stays alive
8. Resize reflows the TUI
9. Dropbox `conpty.dll` extraction succeeds without manual folder
   override — or, if it didn't, the override is in place and
   documented in the commit message.

If any of (1)–(8) fail visibly, STOP and escalate — the JCEF
escape hatch is the fallback; do not start stage 06 on top of a
broken terminal.

## Known risks

- **Windows ConPTY + alt-screen** (PLAN.md risk #3). Mitigation
  is this stage's exit gate.
- **Offline / firewalled CI build.** The JetBrains repo isn't
  proxied by default. If `mvn` fails with 404 on
  `jediterm-core`, vendor the JARs into `lib/` and point the
  dependency entries at them. Document either way in the commit.
- **SciJava BOM vs JetBrains Kotlin.** Stage 01 pinned
  `kotlin.version=2.1.21`. If `mvn dependency:tree` shows a
  different Kotlin stdlib being resolved, dig in here.
