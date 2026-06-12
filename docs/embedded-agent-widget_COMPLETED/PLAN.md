# Embedded Agent Widget — Implementation Plan

Embed every AI CLI agent (Claude Code, Aider, Gemini, Codex, Gemma-via-Ollama)
inside the existing Fiji plugin frame as a first-class TUI pane with a
left rail of one-click commands, macro sets, recipes, and session controls.
Replaces today's external `cmd.exe start` terminal from `AgentLauncher.java`.

> Revised after a codex review that caught (a) the wrong Maven repo and
> license for JediTerm, (b) stale file paths, (c) a missing launcher/session
> refactor, (d) font work sequenced after the phase that needs it, and
> (e) two v1 buttons whose premises don't actually connect to the
> existing helpers.

## 1. Goal

One window. Biologist opens Fiji → clicks "AI Assistant" → picks an agent
from the existing dropdown → the agent's full terminal UX (banners, colours,
spinners, ANSI animations, prompt_toolkit history, slash commands) renders
inside the plugin frame, with a left rail of buttons that inject text into
the PTY or call TCP directly. A Settings toggle keeps the current
external-terminal path alive for users who prefer it.

**Non-goals for v1.** Voice input, multi-tab concurrent agents, paired
planner/implementer agents, git-of-Fiji-state, reference embedding search,
"save as recipe" button, "explain this dialog" button (last two deferred
because their existing Python helpers don't fit — see §8).

## 2. Architecture

```
AiRootFrame (existing JFrame in ImageJAIPlugin)
├── Header (existing, in ChatPanel today)   profile / agent-dropdown ▶ / clear / settings
├── Body (NEW — AiRootPanel, swaps):
│     • ChatView — today's HTML chat pane (extracted from ChatPanel)
│     • TerminalView — NEW, holds:
│           ├── WEST: LeftRail
│           └── CENTER: TerminalHost (JediTerm | ExternalProxy)
└── Status bar (existing) active image / TCP port / agent name
```

**Key split from today's layout:** the current `ChatPanel` already owns
chat rendering, input, `ChatPanelController` for TCP GUI actions, and
hard-disables its input when no API key is set
(`src/main/java/imagejai/ui/ChatPanel.java:178-193`). Stuffing a
terminal inside it compounds that god-panel problem — embedded Gemma needs
local input with **no** API key, which fights the hard-disable. Instead,
extract a sibling `TerminalView` under a common `AiRootPanel` swap. Chat
input logic stays with chat; terminal input stays with terminal.

### 2.1 Terminal backend

**Primary:** JediTerm 3.57 (`org.jetbrains.jediterm:jediterm-core` and
`-ui`) + pty4j 0.13.12 (`org.jetbrains.pty4j:pty4j`). Terminal font:
**JetBrains Mono** (Apache-2.0) as primary, **Noto Emoji** (SIL OFL 1.1,
monochrome variant) as fallback for glyphs the primary lacks. JetBrains
Mono is chosen because JediTerm is tested against it inside IntelliJ
daily — minimises rendering surprises. Noto Emoji monochrome is the
fallback because Linux distros ship no default emoji font in a reliable
way; Windows and macOS can still use their OS colour-emoji fonts via
JediTerm's font fallback chain.

**Licences and repos — important, I got this wrong in draft 1:**

- **JediTerm is LGPL-3.0**, not dual Apache/LGPL. Published to
  JetBrains' `intellij-dependencies` Maven repo at
  `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies`.
  **Not on Maven Central** (verified: `repo1.maven.org` returns 404 for
  `org/jetbrains/jediterm/jediterm-core/3.47/`). 3.47 is not current;
  JetBrains has published newer 3.5x/3.6x builds, so v1 should pin a
  tested pair rather than treat 3.47 as the repo head. The `pom.xml`
  needs a `<repositories>` entry.
- **pty4j 0.13.12 is EPL-1.0** and is on Maven Central. 0.13.11 is also
  stale.
- **Important with `pom-scijava:37.0.0`:** the SciJava parent manages an
  older Kotlin line, while current pty4j/JediTerm builds pull Kotlin
  2.1.21. Pin `kotlin.version` explicitly in `pom.xml` or Maven will try
  to reconcile the wrong Kotlin stdlib.

LGPL-3.0 on a dynamically-linked dependency is acceptable for a Fiji
plugin (Fiji ships many LGPL plugins). Confirm licensing before
public release if that is a concern; not a build blocker.

Wire JediTerm with a ~40-line `ImageJAITtyConnector implements TtyConnector`:

```java
int read(char[] buf, int off, int len)   → ptyProcess.getInputStream().read(...)
void write(byte[] bytes)                  → ptyProcess.getOutputStream().write(...)
void write(String s)                      → write(s.getBytes(UTF_8))
boolean isConnected()                     → ptyProcess.isAlive()
void resize(TermSize sz)                  → ptyProcess.setWinSize(new WinSize(sz.cols, sz.rows))
int waitFor()                             → ptyProcess.waitFor()
```

**Fallback:** JCEF + xterm.js. **Not** a "flip a setting" swap — JCEF is
tangled with JetBrains Runtime packaging; adopting it is a separate
~1-week packaging project. Documented here as "if JediTerm's Windows
ConPTY alt-screen rendering of Gemma's animations proves unfixable, we
have a known escape hatch" — not as a v1 deliverable.

### 2.2 Launcher / session abstraction (Phase 0)

Today's `AgentLauncher.launchAgent(AgentInfo)` returns `boolean`
(`src/main/java/imagejai/engine/AgentLauncher.java:112-167`), and
`ChatPanel.showAgentLaunchMenu` only reads success/fail
(`src/main/java/imagejai/ui/ChatPanel.java:946-949`). That interface
cannot represent an embedded session. Introduce:

Before introducing `AgentSession`, extract a shared
`AgentLaunchSpec { shellCommand, workingDir, env }`. Line 112 is only the
main body; launch-critical logic also leaks into `syncContextFiles()`,
`findLinuxTerminal()`, and the command-string assumptions in
`KNOWN_AGENTS` (`gh copilot` as a compound command, Open Interpreter's
POSIX-only `$(cat CLAUDE.md)`, etc.). External and embedded launch paths
need one command-building source of truth.

```java
interface AgentSession {
    AgentInfo info();
    void writeInput(String s);        // append \r handled inside
    InputStream output();              // read stdout+stderr bytes
    boolean isAlive();
    int exitValue();                   // -1 while alive
    void interrupt();                  // send \x03 into PTY (Ctrl+C)
    void destroy();                    // EOF → grace → destroyForcibly
}
```

For detached external launches, the handle is acknowledgement-only. On
Windows the current `cmd.exe /c start ...` path detaches immediately, so
`isAlive()/exitValue()/interrupt()/destroy()` are not meaningful until an
embedded PTY owns the child process directly.

Two implementations:
- `ExternalAgentSession` — wraps today's `cmd.exe /c start` invocation; its
  `output()` returns an always-empty stream, `writeInput` is a no-op, and
  lifecycle methods are explicitly best-effort / unsupported on detached
  terminals.
- `EmbeddedAgentSession` — wraps a pty4j `PtyProcess` (filled in Phase 1).

`AgentLauncher.launchAgent(AgentInfo, Mode)` returns `AgentSession`. The
boolean return disappears; all existing call sites migrate to checking
`session != null`.

### 2.3 Agent features that must survive embedding

The Gemma wrapper at `agent/gemma4_31b/` is the renderer-stress
reference — the other CLIs use simpler ANSI and are trivially easier:

| Feature | Source | Requirement |
|---|---|---|
| Startup banner | `agent/gemma4_31b/loop.py:1257-1262` | Plain ANSI print — free |
| Animated status line (RGB-pulse frames, wave-highlighted label) | `agent/gemma4_31b/loop.py:349-413` | 24-bit SGR + cursor-up redraws |
| Per-tool emoji icons (🔎 🪄 📸 ⚡) | `agent/gemma4_31b/loop.py:124-157, 685-700` | Platform emoji fallback or explicit glyph substitution, smoke-tested in Phase 1 |
| Braille spinner (ollama_agent shares) | — | Font with Braille range — same bundled font |
| Footer context bar `ctx [████░░░░]` | `agent/gemma4_31b/loop.py:482-492` | 24-bit SGR + cursor-up |
| Green `you> ` prompt | `agent/gemma4_31b/loop.py:921` (`\033[32m`) | Basic 16-colour SGR — always works |
| prompt_toolkit multi-line + history + tab-completion | `agent/gemma4_31b/loop.py:34-49`; history at `~/.config/imagej-ai/gemma4_31b/history.txt` | Real PTY with bracketed-paste; pipes break it |
| Slash commands `/help /clear /queue /interrupt /ccommands /save-recipe` | `agent/gemma4_31b/loop.py:187-194` | PTY stdin write |
| Ctrl+C → `_TurnAborted` (abort turn, not kill process) | `agent/gemma4_31b/loop.py:206` | Send `\x03` byte, **not** `PtyProcess.destroy()` |
| Background TCP event subscriber daemon | `agent/gemma4_31b/events.py:69-107` | Runs inside the Python process — zero widget work |
| `--style claude` variant | `agent/gemma4_31b/__main__.py:43-77` (swaps `GEMMA.md` ↔ `GEMMA_CLAUDE.md`) | Already in `AgentLauncher.KNOWN_AGENTS[8]` — unchanged |

### 2.4 Left-rail buttons — what calls what

Three dispatch modes:

1. **PTY inject** — write text + `\r` to the PTY. User sees the typed text
   and can edit before the agent reads it.
2. **TCP hotline** — direct JSON over `localhost:{settings.tcpPort}`, zero
   LLM round-trip. Idempotent Fiji ops only.
3. **Python helper** — spawn `python -m …`, pipe result into the PTY as
   context.

**v1 rail (9 buttons):**

| Section | Button | Mode | Target |
|---|---|---|---|
| **Session** | New chat | PTY inject | `/clear` (or restart PTY if the CLI lacks the command — see §2.6) |
| | New WIP | File-create + PTY inject | Prompts for a slug, creates `agent/work_in_progress/<slug>.md` from a template (Goal / Steps / Decisions / Open questions), then PTY-injects "Start a new work-in-progress: read `<path>` and help me scope it" |
| **Agent** | Commands | Popup + PTY inject | Per-agent slash-command menu — content differs per agent (see §2.6). Click a command → PTY-inject `<slash-command>\r`. Agent-independent fallback: show the agent's built-in `/help` output captured at first launch |
| **Fiji hotlines** | Close all images | TCP hotline | `execute_macro` with `run('Close All');` (`TCPCommandServer.java:661-662` dispatch → handler at `:1115`) |
| | Reset ROI Manager | TCP hotline | `execute_macro` with `roiManager('Reset');` |
| | Z-project (max) | TCP hotline | `execute_macro` with `run('Z Project...', 'projection=[Max Intensity]');` |
| | Macro sets… | Popup + TCP hotline | Popup lists `.ijm` files in `agent/macro_sets/` (new folder, user-managed) → TCP `execute_macro` with the file body. Bypasses the agent entirely; fast repeat of known-good macros |
| **Guidance** | Run recipe… | Python helper | Popup lists the 26 YAMLs in `agent/recipes/` → invoke new `agent/run_recipe.py` |
| | Audit my results | Python helper + PTY inject | `agent/auditor.py:561` `audit_results()` → paste summary into PTY |

### 2.5 TCP server interaction

No breaking changes in v1. Uses existing commands only:
- `execute_macro` — dispatch at `TCPCommandServer.java:661-662`, handler at
  `:1115`
- `subscribe` — stream handler at `:403` (for live status-bar updates;
  v1.5 optional)

Nothing in `TCPCommandServer.java` is modified in v1. Future additions
(`preview_macro`/`confirm_macro` for shadow-macro, `notify_agent` reverse
channel) are deferred to a v2 milestone.

### 2.6 Per-agent behaviour matrix

The five agents in `KNOWN_AGENTS` (`AgentLauncher.java:46-56`) differ in
slash-command support, `/clear` semantics, and Ctrl+C behaviour. The
widget must route button actions accordingly.

| Agent | `/clear` | Slash commands for **Commands** button | Ctrl+C | Exit | Verification |
|---|---|---|---|---|---|
| **Gemma 4 31B** (local Ollama wrapper, `agent/gemma4_31b/`) | Yes — `loop.py:187-194` | Built-in: `/help /clear /queue /interrupt /ccommands /save-recipe` + user's `.ccommands/*.{md,txt}` scanned at `loop.py:861-871` | `_TurnAborted` — aborts current turn, keeps session (`loop.py:206`) | Ctrl+D or close PTY | **Verified** — code paths read |
| **Gemma 4 31B (Claude-style)** | Same as above | Same as above | Same as above | Same as above | Same — only system prompt differs (`__main__.py:43-77`) |
| **Claude Code** | Yes (built-in) | Built-in (`/init /compact /help /model /memory /cost`, etc.) + filesystem-discoverable `.claude/commands/*.md` in the working dir | Cancels in-flight response, keeps session | `/exit` or Ctrl+D | **To verify** day 1 of Phase 4 — command list may drift across versions |
| **Aider** | Yes (built-in) | Fixed built-in set: `/add /ask /chat-mode /clear /code /commit /diff /drop /exit /git /help /lint /ls /model /models /quit /read-only /reset /run /save /settings /test /tokens /undo /voice /web` | Cancels current turn, keeps session | `/exit` | **To verify** — pin Aider version; list regenerated on Aider upgrade |
| **Gemini CLI** | Uncertain — likely yes | Limited slash commands; most control is via argv flags at launch (`--prompt`, `--prompt-interactive`) | Cancels | Ctrl+D or `/quit` | **To verify** day 1 of Phase 4 — may need fallback to PTY-restart for "New chat" |
| **Codex CLI** | Uncertain | Uncertain — some subset around session control | Cancels | Ctrl+D | **To verify** day 1 of Phase 4 — treat as Aider-like until confirmed |
| **Open Interpreter / Cline / Copilot CLI** | n/a (not installed by default; detected only if on PATH) | n/a | n/a | n/a | Not supported in v1; Commands button shows "no registry" |

**Button behaviour implications:**

- **New chat button** — tries `/clear` first. If PTY scrollback shows
  "unknown command" (or equivalent per-agent string) within 1 s, fall
  back to destroying and relaunching the session in the same tab.
  Per-agent pattern file in `src/main/resources/agents/<id>/clear.json`
  keeps the matching text isolated from widget code.
- **Commands button** — reads a bundled registry at
  `src/main/resources/agents/<id>/commands.json` (one file per agent)
  giving the fixed built-in set. For Claude Code and Gemma, the widget
  also scans the workspace (`.claude/commands/` and `.ccommands/`) and
  appends user-defined commands to the popup. For agents without a
  registry entry, the button is disabled with tooltip "no command list".
- **Ctrl+C key binding** — JediTerm's default sends `\x03` into the PTY,
  which is what every agent above wants. **Do not** remap Ctrl+C to
  anything else; the session-destroy action lives on a toolbar button,
  not a terminal keystroke.
- **Macro sets, Run recipe, Audit, Close All, Reset ROI, Z-project,
  New WIP** — all agent-agnostic by design (bypass the PTY or PTY-inject
  plain text). No per-agent logic needed.

**Scope note.** The registries in `src/main/resources/agents/` are
shipped data, not code — adding a new agent is a one-file drop plus the
existing one-line `KNOWN_AGENTS` entry, preserving the "one line to add
an agent" contract from `CLAUDE.md`.

### 2.7 Prompt interception — copy, paste, approval, cancel, interrupt

Approach lifted from the AgentConsole app
(`…/Macros and Scripts/Claude/AgentConsole/agent_console/`), which already
solves the confirm/cancel pattern for Claude, Gemini, and Codex CLIs.
Principles translated from Python/pyte to Java/JediTerm:

1. **Ctrl+C has dual behaviour** (`AgentConsole/gui/vt100_widget.py:1464-1474`).
   If text is selected in the terminal, Ctrl+C **copies** to the OS
   clipboard and clears selection. If no selection, send `\x03` into the
   PTY as a real SIGINT-equivalent. JediTerm's default key handler is
   replaced with one that checks `getSelectedText()` first. A separate
   toolbar button ("Kill session") is reserved for `PtyProcess.destroy()`.
2. **Ctrl+V pastes raw UTF-8** into the PTY
   (`AgentConsole/gui/vt100_widget.py:1476-1482`). AgentConsole does not
   use bracketed-paste (`\x1b[200~…\x1b[201~`) — v1 of ours does the same
   for parity. If we see pasted-line corruption with prompt_toolkit in
   Gemma, revisit in Phase 5 and enable bracketed paste via
   `JediTermWidget`'s `setBracketedPasteMode(true)`.
3. **Prompt-confirm auto-handling.** A background watcher polls the
   terminal scrollback at 250 ms (JediTerm's `TerminalTextBuffer`,
   last 20 lines) for known prompt patterns and responds by writing
   `\r` (confirm) or `\x1b` (cancel / "No") into the PTY. This is the
   same approach as AgentConsole's `ai_commander.py:1105-1124`
   `_poll_paste_confirm()`. Patterns:

   | Situation | Regex source | Response |
   |---|---|---|
   | Claude Code approval prompt (`Allow/Approve/Permit/Do you want…?`) | `AgentConsole/adapters/claude_adapter.py:20-23` `_APPROVAL_RE` | Policy: see below |
   | Trust-folder prompt (`Do you trust / Yes, I trust this folder / Quick safety check`) | `AgentConsole/core/session_manager.py:108-114` `_TRUST_PROMPT_RE` | Auto-confirm with `\r` |
   | Paste-confirm prompt (`Press Enter to confirm paste / Pasted N lines`) | `AgentConsole/core/ai_commander.py:44-46` `_PASTE_CONFIRM_RE` | Auto-confirm with `\r` |
   | Gemini / Codex prompts | Not detected in AgentConsole (adapters return `None` from `detect_status()`) | Manual user interaction — widget shows a "Confirm" / "Cancel" toolbar pair when the watcher detects any question-mark-terminated prompt |

4. **Approval policy file.** A JSON at
   `src/main/resources/agents/<id>/approval.json` per-agent, modelled on
   AgentConsole's `~/.agent-console/config/approval_policy.json`:
   ```json
   {
     "auto_confirm": [
       "(?i)^allow\\s+read\\s+access\\s+to\\s+.*CLAUDE\\.md",
       "(?i)^permit\\s+access\\s+to\\s+.*/AI_Exports/"
     ],
     "always_escalate": [
       "(?i)rm\\s+-rf",
       "(?i)git\\s+reset\\s+--hard",
       "(?i)drop\\s+table"
     ]
   }
   ```
   `auto_confirm` patterns fire `\r`; `always_escalate` patterns are
   **never** auto-confirmed — widget shows a modal "The agent is
   asking permission for X. Allow / Deny" with the raw prompt text.
   Unmatched prompts escalate too (safe default).
5. **Cancel button** (toolbar, always visible when any prompt is
   pending): writes `\x1b` (ESC) into the PTY. Some agents interpret
   ESC as "cancel prompt," others as "backspace" — tested per-agent at
   Phase 4.
6. **Interrupt button** (toolbar): writes `\x03` (SIGINT). Distinct
   from Kill.
7. **Kill session button** (toolbar, confirm-destructive): calls
   `PtyProcess.destroy()` → `destroyForcibly()` after 2 s grace.

All four toolbar buttons (Confirm / Cancel / Interrupt / Kill) live at
the top of the `TerminalView`, above the JediTerm pane. Confirm and
Cancel are **hidden** until the watcher detects a pending prompt;
Interrupt and Kill are always visible.

### 2.8 URL and OAuth-link handling

Claude Code, Gemini CLI, and Codex CLI all print OAuth URLs during
first-run authentication. In AgentConsole this was a known gap — URLs
are plain text, copy-paste only
(`AgentConsole/gui/vt100_widget.py` — no hyperlink detection). We do
better:

- Register a `HyperlinkStyle` provider on `JediTermWidget` that
  recognises `https?://\S+` and `file://\S+` and renders them as
  clickable links opening via `Desktop.browse(URI)`.
- Additionally, a watcher (same polling loop as §2.7) scans the last
  20 lines for `https?://` and, on first detection, shows a small
  "Copy OAuth URL" button in the terminal toolbar. Clicking copies the
  most recent URL to the OS clipboard. Button disappears after 30 s or
  once another URL arrives.
- Works for any agent — no per-agent logic needed.

### 2.9 Python helper gaps in v1

Only one helper is written new:
- **`agent/run_recipe.py`** — loads a recipe YAML, presents a SciJava
  `GenericDialog` for any `parameters:` block, substitutes `${slots}`,
  and executes `steps[]` **one step at a time**, printing per-step
  status to stdout (and therefore to the PTY). Each `execute_macro`
  call is separate so the helper can abort on first failure and surface
  the specific failing step to the user, not a single 7-step blob on
  completion (gap #5 in the §11 audit). Reuse `recipe_search.py`'s
  YAML loader / fallback parser so the helper does **not** introduce a
  hard PyYAML dependency. `recipe_search.py` already has the search
  side but no runner — this fills the gap.

Everything else called by buttons is an existing function.

### 2.10 Session code journal (silent capture + one-click rerun)

**Goal.** Every macro / script the agent runs is silently captured on
the Java side, auto-named from the code itself, and surfaced in a rail
section the user can click to re-run or save. The agent never sees
this exists — zero tokens, zero prompt bloat, zero new TCP commands.

**Why it's free to the agent.** Every line of code the agent runs
passes through exactly two server handlers:
`TCPCommandServer.handleExecuteMacro` (`:1153`) for IJM macros and
`TCPCommandServer.handleRunScript` (`:1777`) for Groovy / Jython / JS.
The journal hooks each one after `code` is read but before execution.
No new TCP command is exposed; nothing is added to the `macro.*` event
stream; the agent system prompts are untouched. The journal is a
private field inside the server — there is no wire path that would let
a subscribed agent discover it.

**Capture contract.** `SessionCodeJournal` — singleton, thread-safe.

```java
void record(String language, String code, String source,
            long macroId, long startedAtMs, long durationMs,
            boolean success, String failureMessage);

List<Entry> snapshot();              // for UI
Entry get(String id);                // for re-run
void addListener(Listener l);        // UI refresh
```

Each `Entry` stores `{id, name, language, code, timestamp,
duration_ms, success, run_count, source}`. `source` is the existing
`TCPCommandServer` concept already used in `macro.started` events
(`"tcp"`, `"rail:macro-set"`, etc.) — lets the rail filter out its
own re-run clicks so they don't double-log.

**Storage.**
- In-memory ring of the last 200 entries (covers multi-hour sessions;
  cheap).
- Append-only `.ijm` / `.groovy` file per entry at
  `AI_Exports/.session/code/<HHMMSS>_<name>.<ext>`. Uses the existing
  `AI_Exports` convention from `CLAUDE.md` house rules. Subsumes the
  Phase 5 "scrollback persistence" bullet — same audit-trail purpose,
  more focused content.
- Single `AI_Exports/.session/code/INDEX.json` manifest listing
  `{id, name, file, timestamp, language, run_count, success}` — lets
  the rail repopulate itself across Fiji restarts if the user wants
  session history to persist (opt-in via `Settings`; default off so
  new sessions start clean).

**Filters applied before recording.**
- **Length filter:** skip code ≤20 chars (kills `getTitle()`,
  `getValue("X")`, etc. — pure state probes the agent issues by the
  dozen).
- **Adjacent-duplicate suppression:** if the same canonical code
  (whitespace-normalised) matches the most recent entry, increment its
  `run_count` instead of creating a new entry. Keeps the list short
  when the agent iterates.
- **Non-adjacent duplicate:** if the same canonical code matches a
  non-most-recent entry, promote that entry to the top and increment
  `run_count` — don't create a second entry with the same name.

**Auto-naming — cascading strategy.** All strategies produce a
slug (≤50 chars, `[a-z0-9_]+`, collapsed underscores, trimmed). First
non-empty slug wins.

1. **Leading comment.** Scan the first ~5 non-blank lines for the
   first `//`, `#`, or `/* … */` comment. Strip the marker, take up
   to 50 chars of the text, slugify. Skip-list of stock boilerplate
   so the agent's framing comments don't become names:
   `^(auto.generated|claude.agent.executed|run by.*agent|ai.generated)`.
   If the leading comment matches the skip-list, fall through to
   strategy 2.
2. **Composite operation signature** (was "first `run(...)` call" —
   widened per your note). Collect **all** `run("([^"]+)"…)` calls
   in order, in parsing order, preserving duplicates for frequency
   analysis but emitting each distinct plugin name only once.
   Selection rules:
   - Apply a **plumbing block-list** that excludes pure state
     management: `Close`, `Close All`, `Duplicate...`, `Select None`,
     `Select All`, `Make Inverse`, `Clear Results`, `Set Measurements`,
     `Enhance Contrast` when `normalize=false` (display-only — not a
     measurable op), `Properties...`, `Set Scale...`, `Rename`.
   - From the remaining list, take up to **3** distinct plugin names
     in order of first appearance. Slugify each. Join with `__`
     (double underscore) so it reads like a pipeline:
     `gaussian_blur__auto_threshold__analyze_particles`.
   - If everything in the code is plumbing, fall back to the first
     plumbing op so the entry is at least identifiable:
     `duplicate__close_all`.
   - Truncate total slug to 50 chars at an `__` boundary, then at a
     `_` boundary, then hard.
3. **First defined symbol.** For Groovy / Jython code with no
   `run(...)` calls. Regex order: `def\s+([A-Za-z_][A-Za-z0-9_]*)`,
   `function\s+([A-Za-z_][A-Za-z0-9_]*)`, `([A-Za-z_][A-Za-z0-9_]*)\s*=`
   (anchored at a line start, excluding keywords `if`, `for`, `while`,
   `return`). Slugify the first capture group.
4. **Fallback.** `macro_<HHMMSS>` (or `script_<HHMMSS>` if the
   language isn't IJM).

**Collision handling.**
- Same slug + identical canonical code → covered by the duplicate
  suppression above; increment `run_count`, no new entry.
- Same slug + different code → append `_2`, `_3`, … to the new
  entry's slug. Numeric suffix chosen over content hash for
  readability.

**Entry ID.** Internal stable ID is a monotonic counter
(`MACRO_ID_SEQ` already exists at `TCPCommandServer.java:116` —
reuse it so journal IDs line up with `macro.started` event IDs for
debugging). Display name is the slug.

**UI — new LeftRail section "Session history".**

Placement: above the **Macro sets…** button in the Fiji-hotlines
section of §2.4. Collapsible, remembers collapsed state via `ij.Prefs`.

Row format (newest first): `HH:MM:SS  <name>  ×<run_count>  <lines>L`.
Failed entries render in red with a ⚠ prefix; tooltip shows the
failure message.

Interactions:
- **Single click** — copy code to OS clipboard, show a 1 s toast
  "Copied <name>".
- **Double click** — re-run via existing `TcpHotline` helper
  (`execute_macro` or `run_script` depending on stored language).
  `source` field set to `"rail:history"` so the re-run doesn't
  spawn a second journal entry — the filter rule in the capture
  path treats `rail:history` / `rail:macro-set` as "bump run_count
  on the original entry instead of creating a new one".
- **Right click → menu:**
  - *Open in editor* — writes to a temp `.ijm`, opens via
    `Desktop.open()`.
  - *Save to Macro sets…* — prompts for a name, copies to
    `agent/macro_sets/<name>.ijm`. Entry becomes permanent and
    appears in the existing Macro-sets popup (§2.4).
  - *Show file* — reveals the `AI_Exports/.session/code/<file>` in
    Explorer / Finder.
  - *Remove from history* — deletes from the ring (leaves the
    file on disk for audit).
- **Section header menu:**
  - *Clear history* — empties ring, optionally also deletes files
    (confirm-destructive).
  - *Filter: exclude plumbing* — toggle that hides entries whose
    name started with a plumbing-only fallback (see strategy 2
    last sub-bullet).

**Agent silence — explicit checklist.**
1. No new TCP command added.
2. `macro.*` / `dialog.*` event streams unchanged.
3. No new fields in `execute_macro` / `run_script` responses.
4. No new files in `agent/` that the agent might stumble across
   during filesystem scans (journal files live under `AI_Exports/`,
   which the agent already writes to but is not prompted to read
   without user ask).
5. Default system prompts / `CLAUDE.md` / `GEMMA.md` are untouched.

**Scope guards.** The `run_count`, `source` filtering, and
canonical-code deduplication exist specifically so user-driven
re-runs via the rail don't inflate the journal or feed back into
themselves — closing the obvious reflexive loop.

## 3. Build changes

`pom.xml` currently targets Java 1.8
(`pom.xml:27-28, 80-81`). Current JediTerm / pty4j builds require JVM 11
bytecode. Fiji ships JRE 21, so the runtime is fine; the friction is in
the build and in a couple of Java-8-era assumptions in this repo.

Two concrete Phase 0 fixes are required before the dependency bump:
- `ConversationLoop.java:705` still uses
  `javax.xml.bind.DatatypeConverter`, which disappears on Java 11. Replace
  it with `java.util.Base64`.
- `pom-scijava:37.0.0` can be overridden to Java 11, but it also manages
  an older Kotlin line. Set `kotlin.version` explicitly so Maven does not
  pull a mismatched stdlib for JediTerm / pty4j.

```xml
<maven.compiler.source>11</maven.compiler.source>
<maven.compiler.target>11</maven.compiler.target>
<scijava.jvm.build.version>[11,)</scijava.jvm.build.version>
<kotlin.version>2.1.21</kotlin.version>
```

Add the JetBrains repo (required — JediTerm is not on Maven Central):

```xml
<repositories>
  <repository>
    <id>jetbrains-intellij-dependencies</id>
    <url>https://packages.jetbrains.team/maven/p/ij/intellij-dependencies</url>
  </repository>
  <!-- existing scijava.public repo preserved -->
</repositories>
```

Add dependencies (all `compile` scope — must ship in the plugin JAR;
Fiji does not provide them):

```xml
<dependency>
  <groupId>org.jetbrains.pty4j</groupId>
  <artifactId>pty4j</artifactId>
  <version>0.13.12</version>
</dependency>
<dependency>
  <groupId>org.jetbrains.jediterm</groupId>
  <artifactId>jediterm-core</artifactId>
  <version>3.57</version>
</dependency>
<dependency>
  <groupId>org.jetbrains.jediterm</groupId>
  <artifactId>jediterm-ui</artifactId>
  <version>3.57</version>
</dependency>
```

pty4j ships native `.dll` / `.so` / `.dylib` under
`resources/com/pty4j/native/<os>/<arch>/` in its JAR. No current build
handles native resources — add `maven-shade-plugin` with
`minimizeJar=false`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <minimizeJar>false</minimizeJar>
        <createDependencyReducedPom>false</createDependencyReducedPom>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"/>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

pty4j extracts the matching native to a temp dir at runtime and
`System.load`s it. Fiji's classloader handles this without extra work.

## 4. Phases

### Phase 0 — Launcher/session refactor + toolchain bump (2–3 days)

**Goal:** the codebase supports an embedded session without adding
JediTerm yet.

Deliverables:
- `pom.xml` bumped to Java 11; a smoke build of the existing code
  against JDK 11 target proves Fiji still loads the plugin.
- `ConversationLoop.java:705` switched from
  `javax.xml.bind.DatatypeConverter` to `java.util.Base64`.
- New `imagejai.engine.AgentLaunchSpec` extracted from
  `AgentLauncher` so command construction, cwd, and environment are
  shared by external and embedded launch modes.
- New interface `imagejai.engine.AgentSession`.
- `ExternalAgentSession` — refactored from today's
  `AgentLauncher.launchAgent()` body
  (`src/main/java/imagejai/engine/AgentLauncher.java:112-167`),
  but explicitly treated as fire-and-forget for detached external
  terminals.
- `AgentLauncher.launch(AgentInfo, Mode)` returns `AgentSession`; boolean
  return removed.
- `KNOWN_AGENTS` / launch-spec building normalised so shell-sensitive
  entries stop relying on POSIX fragments where Windows currently uses
  `cmd.exe` (most notably Open Interpreter's `$(cat CLAUDE.md)`).
- `ChatPanel.showAgentLaunchMenu`
  (`src/main/java/imagejai/ui/ChatPanel.java:905-952`) updated
  to accept `AgentSession`; continues to discard the handle for now.
- New `imagejai.ui.ChatSurface` (or equivalent tiny facade)
  introduced so `ConversationLoop` and `ImageJAIPlugin.startTcpServer`
  stop depending on the concrete `ChatPanel` before `ChatView`
  extraction.
- `Settings.agentEmbeddedTerminal` flag (in
  `src/main/java/imagejai/config/Settings.java`), surfaced in
  `src/main/java/imagejai/ui/SettingsDialog.java`.
- **Settings migration:** new installs default to `embedded`; existing
  installs that already have a saved `Settings` JSON default to
  `external` and are prompted once on next launch. Prevents a silent UX
  change for current users (audit gap #≈ settings migration).
- **TCP port collision handling:** if `:7746` binds fail on plugin
  start, scan up to `:7750`, use the first free port, and write the
  chosen port to the status bar. Hotline buttons read
  `Settings.tcpPort` (not a hardcoded constant). Fixes audit gap #1.
- **First-run no-key path:** `SettingsDialog.firstRunFlow()` gains a
  "Use local agent only (no API key)" option. When selected, the
  dialog does not force an API key, and `ChatPanel.updateInputState`
  (`ChatPanel.java:178-193`) treats the local-only case as valid.
  Fixes audit gap #2.
- **LGPL-3.0 NOTICE.** New file
  `src/main/resources/META-INF/NOTICE.md` listing JediTerm, pty4j, and
  their upstream source URLs per LGPL / EPL requirements. Fixes audit
  gap #11.
- **Session code journal — server side only** (§2.10). New class
  `imagejai.engine.SessionCodeJournal` with the
  `record()` / `snapshot()` / `get()` / `addListener()` contract.
  Two hooks added: one in `TCPCommandServer.handleExecuteMacro` after
  the `code` field is read (`:1158`), one in `handleRunScript` after
  its `code` is read (`:1787`). Length filter, canonical-code
  deduplication, and the cascading auto-namer (leading-comment →
  composite `run(...)` signature → first defined symbol → timestamp
  fallback) all land here so the UI work in Phase 4 is pure
  plumbing. No UI surfaces it yet. No TCP command exposes it.
  Journal files write to `AI_Exports/.session/code/` on each
  `record()`; in-memory ring holds 200 entries. Verifiable by
  running any existing agent externally for a few turns and
  inspecting the on-disk files.

**Exit gate:** every existing agent in `KNOWN_AGENTS`
(`AgentLauncher.java:46-56`) still launches externally on a fresh Fiji
build, including the entries that currently depend on shell quirks. The
Mode enum exists; `EMBEDDED` is rejected with "not yet implemented". A
fresh install with no API key can complete the first-run flow and reach
the agent dropdown.

### Phase 1 — Terminal primitive with bundled font (3–4 days)

**Goal:** JediTerm runs Gemma in a standalone debug frame; all
renderer-stress features from §2.3 visibly work.

Deliverables:
- **15-minute version recheck** (first task of this phase): `curl` the
  JetBrains `intellij-dependencies` repo for any `3.5x`/`3.6x` JediTerm
  newer than 3.57 and any pty4j newer than 0.13.12. If present, skim its
  commits for "conpty", "alt", "screen", "resize", "winsize" — if a
  relevant fix or regression is found, bump the pin in `pom.xml`
  before continuing; otherwise keep 3.57 / 0.13.12.
- Bundled fonts under `src/main/resources/fonts/`: `JetBrainsMono-Regular.ttf`
  (primary, Apache-2.0, ~200 KB) and `NotoEmoji-Regular.ttf` (fallback,
  monochrome, SIL OFL 1.1, ~400 KB). Both loaded via `Font.createFont`
  at plugin startup, registered as JediTerm's font chain (primary →
  emoji fallback → OS fallback). Total bundle impact ~600 KB.
- **Emoji rendering is still a smoke-check item, not a solved property
  of the bundled fonts alone** — JediTerm's font-fallback chain must
  actually pick up the secondary font on the target platform. Verify
  specifically on Linux where OS emoji coverage is unreliable.
- `imagejai.terminal.EmbeddedPty` wrapping
  `PtyProcessBuilder` + `JediTermWidget` + `ImageJAITtyConnector`.
- `EmbeddedAgentSession` implements `AgentSession` over `EmbeddedPty`.
- Throwaway `Plugins > AI Assistant > Terminal Smoke Test` command that
  opens a bare JFrame with the widget and launches Gemma.
- **Dropbox-sync native-lib test:** smoke-test on the actual Dropbox-
  synced Fiji install, not a local copy. pty4j extracts `conpty.dll` to
  a temp dir on first use — verify Dropbox's file-lock behaviour does
  not block `System.load`. If it does, set
  `System.setProperty("pty4j.preferred.native.folder", <local-temp>)`
  before the first `PtyProcessBuilder` call. Fixes audit gap #3.

**Exit gate:** on Windows 11, `gemma4_31b_agent --style claude`
renders with
- banner ✓
- animated status line (RGB pulse, wave label) ✓
- tool icon emojis ✓
- Braille spinner ✓
- green `you> ` prompt ✓
- prompt_toolkit history (↑ recalls previous turn) ✓
- Ctrl+C aborts the turn, does not kill the process ✓
- resize reflows the TUI ✓

If any of these fail visibly on Windows, decide on the day whether to
keep JediTerm (fix the specific issue) or trigger the JCEF escape hatch
(§7 risk 1).

### Phase 2 — Plugin-frame integration (2–3 days)

**Goal:** the embedded terminal takes the place of the external
`cmd.exe start` window for the existing play button, and `ChatPanel` is
decomposed so terminal and chat don't fight.

Deliverables:
- First move `ConversationLoop` and `ImageJAIPlugin.startTcpServer`
  behind the small `ChatSurface` / `ChatPanelController` facades so the
  extraction is not blocked on the concrete `ChatPanel` type.
- Extract `ChatView` from today's `ChatPanel` — the body region only
  (messages pane + input bar + `ChatPanelController` methods). Header
  and launcher stay up one level.
- New `AiRootPanel` holds the header plus a `CardLayout` body that
  swaps between `ChatView` and `TerminalView`.
- `TerminalView` (NEW) = `WEST: LeftRail stub` + `CENTER: TerminalHost`,
  where `TerminalHost` holds the `JediTermWidget` for an
  `EmbeddedAgentSession`.
- `ImageJAIPlugin.run()`
  (`src/main/java/imagejai/ImageJAIPlugin.java:40-135`) swaps
  to `AiRootPanel`; the 420×600 default frame size is preserved for chat
  mode and grows to ~900×700 when the terminal card is shown.
- Launching an agent with `Mode.EMBEDDED` shows the terminal card; when
  the PTY exits, card flips back to chat.
- Zombie-process safety: `Runtime.addShutdownHook` calls `destroy` on
  all live sessions; the existing `windowClosed` cleanup path in
  `ImageJAIPlugin.run()` calls the same shared `shutdownSessions()`
  helper.

**Exit gate:** every agent in `KNOWN_AGENTS` launches embedded on a
clean Fiji install, including Gemma with both `--style` variants. The
external-mode toggle in Settings still works. Closing the plugin window
does not leave orphaned processes (verified via Task Manager).

### Phase 3 — TCP hotlines (1–2 days)

**Goal:** three TCP-hotline buttons fire Fiji ops with zero LLM
involvement.

Deliverables:
- `imagejai.ui.LeftRail` (`JPanel`, `BoxLayout.Y_AXIS`),
  collapsible via a chevron.
- `imagejai.ui.TcpHotline` helper: opens a short-lived
  socket to `localhost:{settings.tcpPort}`, sends one JSON command,
  reads response, closes.
- Three wired buttons: Close all images, Reset ROI Manager, Z-project
  (max). Each tooltip shows the macro payload. Buttons must check state
  before acting; specifically, Z-project is disabled (or shows a clear
  precondition error) unless a stack is open.
- Rail-collapsed state and font size persisted via `ij.Prefs`.

**Exit gate:** with Fiji open and an image loaded, clicking each button
performs the documented Fiji action in <500 ms, with no agent running.

### Phase 4 — Button wiring + prompt interception (3–4 days)

**Widened again (was 2–3 days). Adds §2.7 prompt-interception layer
(Confirm / Cancel / Interrupt / Kill toolbar + auto-approval watcher)
and §2.8 URL handling to the original button work.**

Deliverables:
- **Prompt-watcher** (`imagejai.terminal.PromptWatcher`):
  250 ms poll over `JediTermWidget`'s last 20 buffer lines. Reads
  per-agent regex patterns from
  `src/main/resources/agents/<id>/approval.json` (§2.7 step 4).
  Matches fire one of: auto-confirm (`\r`), escalate (show modal),
  "pending prompt" (reveal Confirm/Cancel toolbar buttons).
- **Terminal toolbar** (`imagejai.ui.TerminalToolbar`,
  above the JediTerm pane):
  - **Confirm** — hidden by default; shown when watcher detects a
    pending prompt. Sends `\r`.
  - **Cancel** — hidden by default; shown with Confirm. Sends `\x1b`.
  - **Interrupt** — always visible. Sends `\x03`.
  - **Kill session** — always visible. Calls
    `EmbeddedAgentSession.destroy()` with a modal "Are you sure?".
- **Ctrl+C dual-mode binding:** JediTerm key handler overridden — if
  selection exists, copy to OS clipboard and clear selection; else
  send `\x03`. Mirrors
  `AgentConsole/gui/vt100_widget.py:1464-1474`.
- **Ctrl+V paste:** raw UTF-8 to PTY. Documented but not bracketed in
  v1 (matches AgentConsole's choice). Revisit in Phase 5 if
  prompt_toolkit corruption appears.
- **URL hyperlink support:** register `JediTermWidget` HyperlinkStyle
  provider for `https?://\S+` → opens via
  `java.awt.Desktop.getDesktop().browse(URI)`. A "Copy URL" button
  appears in the terminal toolbar when the watcher sees a fresh URL
  in the last 20 lines; disappears after 30 s. Fixes audit gap #7.
- `agent/run_recipe.py` — loads `agent/recipes/<name>.yaml`, presents
  a `GenericDialog` for any `parameters:` block, substitutes `${slots}`
  in each `step.macro`, and **executes steps one at a time**, streaming
  per-step status (`[step 3/7] gaussian_blur … ok (312 ms)`) to stdout
  so the PTY shows progress. On failure, the failing step is
  highlighted and subsequent steps are skipped. Fixes audit gap #5.
- `agent/macro_sets/` — new folder, empty; widget scans for `.ijm`
  files at popup open.
- Per-agent registry triplet under `src/main/resources/agents/<id>/`:
  `commands.json`, `clear.json`, `approval.json`. One triplet per agent
  in `KNOWN_AGENTS`; agents without files get the Commands button
  disabled and fall back to "escalate every prompt" policy.
- **New agent chat** (renamed from "New chat" to disambiguate —
  audit gap #10): PTY-inject `/clear`; if the agent's `clear.json`
  matcher fires within 1 s, stay; otherwise destroy and relaunch the
  session in place. Fiji state is not touched.
- `New WIP` button: Swing input dialog for a slug → creates
  `agent/work_in_progress/<slug>.md` from
  `src/main/resources/wip_template.md` → PTY-inject
  "Start new WIP: read `<path>` and help me scope it."
- `Commands` button: reads `commands.json`, plus live-scans
  `.claude/commands/*.md` (Claude Code only) and
  `.ccommands/*.{md,txt}` (Gemma only). Popup → PTY-inject.
- `Macro sets…` button: popup of `agent/macro_sets/*.ijm` → TCP
  `execute_macro`.
- `Close All` / `Reset ROI` / `Z-project (max)`: three hardcoded
  hotlines (ported from Phase 3 rail).
- `Run recipe…` button: popup of 26 YAMLs; spawns
  `python agent/run_recipe.py <name>` and tees per-step output.
- `Audit my results` button: calls
  `agent/auditor.py:561 audit_results(…)` with `get_results_table` +
  `get_image_info`, pastes `summary` into PTY.
- **Focus return** — after any button dispatch, call
  `terminalWidget.requestFocusInWindow()` so the next keystroke reaches
  the PTY without a manual click. Fixes audit gap #9.
- **Session history section** on the `LeftRail` (§2.10). New
  `imagejai.ui.SessionHistoryPanel` subscribed to
  `SessionCodeJournal` via `addListener`. Row renderer, single/double
  click, right-click popup (*Open in editor*, *Save to Macro sets…*,
  *Show file*, *Remove*), and section-header menu (*Clear history*,
  *Filter: exclude plumbing*) all wired. Re-run path uses the
  existing `TcpHotline` helper from Phase 3, tagging the request with
  `source=rail:history` so the journal's capture hook bumps
  `run_count` instead of recording a new entry. Persisted settings
  (collapsed state, plumbing-filter toggle, optional cross-restart
  history) via `ij.Prefs`.

**Exit gate:** all 9 rail buttons plus 4 terminal-toolbar buttons
(Confirm / Cancel / Interrupt / Kill) work with every embedded launcher
entry in `KNOWN_AGENTS`. Approval policy auto-confirms Claude's
whitelisted prompts and escalates everything else. Clicking a button
keeps keyboard focus in the terminal. Claude Code's OAuth URL on
first launch is clickable or one-click copyable.

### Phase 5 — Polish (1–2 days)

(Font work already done in Phase 1.)

- Ctrl+=/Ctrl+- font-size shortcuts inside the terminal pane, persisted
  via `ij.Prefs`.
- Window size persisted per-mode so chat-only remembers 420×600 and
  terminal remembers 900×700.
- JediTerm colour scheme derived from existing `ChatPanel` constants
  (`ChatPanel.java:42-50`: `BG_MAIN #1e1e23`, `ACCENT #00c8ff`, etc.).
- Log prefix: all new code uses `IJ.log("[ImageJAI-Term] ...")` to match
  the `[ImageJAI]` / `[ImageJAI-TCP]` pattern in the existing code.
- macOS + Linux smoke test.
- **Registry-refresh dev script**
  (`scripts/refresh_agent_registries.sh`, Bash / PowerShell pair): runs
  `<cli> /help` for each installed agent, parses the output, and
  writes the updated `commands.json`. Intended as a monthly manual
  run, not CI — but checked into the repo so anyone can refresh.
  Addresses audit gap #6.
- **Scrollback persistence (optional, off-by-default).** Narrower
  scope than originally planned — the §2.10 Session code journal
  already captures the executed-code audit trail under
  `AI_Exports/.session/code/`. This Phase 5 bullet only adds the
  *terminal output* side: on embedded session exit, serialise the
  last N scrollback lines to `AI_Exports/.session/log/<agent>_<ts>.log`
  so the user can reconstruct what the agent *said* alongside what it
  *ran*. Off by default because scrollback can contain pasted
  credentials; opt-in via Settings.

**Exit gate:** a new user on a clean Fiji install can open the plugin,
launch Gemma with no API key, run a recipe, audit the results, and
never leave the Fiji window.

**Total: 12–19 working days** (Phase 4 grew again to 3–4 days to cover
the §2.7 prompt-interception layer + §2.8 URL handling + focus-return +
rename of "New chat" → "New agent chat").

## 5. Files touched

| Path | Action |
|---|---|
| `pom.xml` | MODIFY — Java 11, explicit Kotlin pin, JetBrains repo, pty4j+jediterm deps, shade plugin |
| `src/main/java/imagejai/engine/AgentLaunchSpec.java` | NEW — shared shell command / cwd / env contract |
| `src/main/java/imagejai/engine/AgentSession.java` | NEW — interface |
| `src/main/java/imagejai/engine/ExternalAgentSession.java` | NEW — wraps today's launcher behaviour as a detached-session stub |
| `src/main/java/imagejai/engine/EmbeddedAgentSession.java` | NEW — wraps pty4j |
| `src/main/java/imagejai/terminal/EmbeddedPty.java` | NEW — pty4j + JediTerm + connector |
| `src/main/java/imagejai/terminal/ImageJAITtyConnector.java` | NEW |
| `src/main/java/imagejai/terminal/PromptWatcher.java` | NEW — 250 ms poll over scrollback, matches approval.json regexes, emits events |
| `src/main/java/imagejai/terminal/ApprovalPolicy.java` | NEW — loads approval.json, decides auto_confirm / escalate / pending |
| `src/main/java/imagejai/ui/TerminalToolbar.java` | NEW — Confirm / Cancel / Interrupt / Kill / Copy URL buttons above JediTerm pane |
| `src/main/java/imagejai/ui/AiRootPanel.java` | NEW — header + CardLayout body |
| `src/main/java/imagejai/ui/ChatSurface.java` | NEW — tiny UI facade for ConversationLoop / TCP wiring |
| `src/main/java/imagejai/ui/ChatView.java` | NEW — extracted body of today's ChatPanel |
| `src/main/java/imagejai/ui/TerminalView.java` | NEW — rail + terminal host |
| `src/main/java/imagejai/ui/TerminalHost.java` | NEW — holds JediTermWidget |
| `src/main/java/imagejai/ui/LeftRail.java` | NEW |
| `src/main/java/imagejai/ui/TcpHotline.java` | NEW |
| `src/main/java/imagejai/engine/SessionCodeJournal.java` | NEW — §2.10 silent capture, auto-namer, ring + on-disk persistence |
| `src/main/java/imagejai/engine/CodeAutoNamer.java` | NEW — §2.10 cascading name strategies (comment → composite run-signature → first symbol → timestamp) |
| `src/main/java/imagejai/ui/SessionHistoryPanel.java` | NEW — rail section, single/double-click, right-click menu |
| `AI_Exports/.session/code/` | NEW — runtime-created, per-entry `.ijm`/`.groovy` files + `INDEX.json` |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY — return `AgentSession`, accept `Mode`, extract `AgentLaunchSpec`, fix shell-portable command building |
| `src/main/java/imagejai/ui/ChatPanel.java` | MODIFY — Phase 0 launcher/session plumbing, then compatibility wrapper while `ChatView` extraction lands |
| `src/main/java/imagejai/ImageJAIPlugin.java` | MODIFY — swap to `AiRootPanel`, shutdown hook |
| `src/main/java/imagejai/ConversationLoop.java` | MODIFY — `java.util.Base64`, depend on `ChatSurface` instead of concrete `ChatPanel` |
| `src/main/java/imagejai/config/Settings.java` | MODIFY — `agentEmbeddedTerminal` flag |
| `src/main/java/imagejai/ui/SettingsDialog.java` | MODIFY — surface the flag |
| `src/main/resources/fonts/JetBrainsMono-Regular.ttf` | NEW — primary terminal font (Apache-2.0, ~200 KB) |
| `src/main/resources/fonts/NotoEmoji-Regular.ttf` | NEW — monochrome emoji fallback (SIL OFL 1.1, ~400 KB) |
| `src/main/resources/wip_template.md` | NEW — template for `New WIP` button (Goal / Steps / Decisions / Open questions) |
| `src/main/resources/agents/gemma4_31b/commands.json` | NEW — slash-command registry (`/help /clear /queue /interrupt /ccommands /save-recipe`) |
| `src/main/resources/agents/gemma4_31b/clear.json` | NEW — `/clear` matcher + pty_restart fallback |
| `src/main/resources/agents/gemma4_31b/approval.json` | NEW — auto_confirm / escalate regex lists (§2.7) |
| `src/main/resources/agents/claude/commands.json` | NEW — Claude Code built-in slash commands + filesystem-scan hint for `.claude/commands/` |
| `src/main/resources/agents/claude/clear.json` | NEW |
| `src/main/resources/agents/claude/approval.json` | NEW — approvals tuned for Claude Code's `Allow/Approve/Permit` prompts + trust-folder matcher |
| `src/main/resources/agents/aider/commands.json` | NEW — Aider built-in slash commands (captured from `aider --help`) |
| `src/main/resources/agents/aider/clear.json` | NEW |
| `src/main/resources/agents/aider/approval.json` | NEW — escalate-all baseline (no known auto-confirm prompts) |
| `src/main/resources/agents/gemini/commands.json` | NEW — Gemini CLI slash commands (captured day 1 of Phase 4) |
| `src/main/resources/agents/gemini/clear.json` | NEW |
| `src/main/resources/agents/gemini/approval.json` | NEW — escalate-all baseline |
| `src/main/resources/agents/codex/commands.json` | NEW — Codex CLI slash commands (captured day 1 of Phase 4) |
| `src/main/resources/agents/codex/clear.json` | NEW |
| `src/main/resources/agents/codex/approval.json` | NEW — escalate-all baseline |
| `src/main/resources/META-INF/NOTICE.md` | NEW — LGPL-3.0 (JediTerm) + EPL-1.0 (pty4j) attributions and source-code pointers |
| `scripts/refresh_agent_registries.sh` | NEW — dev script: runs `<cli> /help` for each installed agent and rewrites commands.json (monthly manual run) |
| `scripts/refresh_agent_registries.ps1` | NEW — Windows counterpart |
| `agent/run_recipe.py` | NEW — recipe compile-and-run orchestrator |
| `agent/macro_sets/` | NEW — empty folder for user-dropped `.ijm` files, scanned by Macro sets button |
| `agent/work_in_progress/` | EXISTING — new `.md` files created here by the New WIP button |

**Not touched in v1:** `agent/gemma4_31b/**` (Python agent stays as-is),
`agent/ollama_agent/**` (out of scope per user), `agent/references/**`,
`agent/ij.py`.

**`TCPCommandServer.java` — two tiny additions only** (§2.10):
one line in `handleExecuteMacro` (`:1158`) and one line in
`handleRunScript` (`:1787`), each calling
`SessionCodeJournal.INSTANCE.record(...)` after success / failure is
known. No handler added, no event published, no response field
changed — wire contract unchanged.

## 6. Testing

- **Phase 0:** existing CI smoke build on JDK 11; manual: launch each
  `KNOWN_AGENTS` agent externally.
- **Phase 1:** manual renderer-stress checklist on Windows 11 (§4 exit
  gate).
- **Phase 2:** launch every agent embedded + external. Force-close Fiji
  mid-session and verify no orphan processes.
- **Phase 3:** unit test `TcpHotline` against a stub JSON server. Manual:
  click each hotline, verify Fiji state via
  `WindowManager.getImageTitles()`.
- **Phase 4:** unit test `run_recipe.py` with a fixture recipe. Manual:
  run `cell_counting.yaml` end-to-end.
- **Phase 5:** cross-platform smoke — macOS (ARM + Intel), Ubuntu/X11.

No CI exists today; these are manual checkpoints.

## 7. Risks — ranked

1. **`AgentLauncher` refactor blast radius** (was underweighted in draft 1).
   `AgentLauncher.launchAgent()` currently returns `boolean` and
   `ChatPanel` reads only success/fail
   (`src/main/java/imagejai/ui/ChatPanel.java:946-949`).
   Changing the contract touches at least three classes and anything
   that calls through `ImageJAIPlugin.run()`. Also, the current line-112
   boundary is not self-contained: shell construction and context sync
   leak into helper methods, and external Windows launches detach so
   lifecycle reporting is fake unless the plan treats them as
   acknowledgement-only. Phase 0 exists specifically to do this before
   rendering work starts. Budget slip if surprises: +1 day.
2. **JediTerm on JetBrains repo, not Maven Central.** Offline builds will
   fail unless the `<repositories>` entry is added; some corporate mirrors
   won't proxy it. Mitigation: commit the repo entry; document a fallback
   of vendoring the JARs if IT blocks it.
3. **Windows ConPTY + prompt_toolkit / alt-screen on Gemma's animated
   status line.** Previously top risk. Mitigation: tested on day 1 of
   Phase 1; the `TerminalHost` abstraction keeps the JCEF escape open
   but JCEF is a ~1-week packaging project, not a flag flip. Budget slip
   if triggered: +5 days.
4. **LGPL-3.0 on JediTerm.** Dynamic linking is fine for plugin
   distribution, but if ImageJAI is ever bundled into a single JAR with
   proprietary code it becomes a question. Not a v1 blocker.
5. **Fiji classloader + pty4j native extraction.** pty4j has worked inside
   IntelliJ for years and should be transparent under
   `net.imagej.launcher`. Verify early by launching a no-op `cmd.exe`
   from the Phase 1 build.
6. **Java 1.8 → 11 bump is not just a compiler flag flip.** `ConversationLoop`
   still uses `javax.xml.bind.DatatypeConverter`, so Phase 0 must fix the
   code as well as the POM.
7. **SciJava BOM vs JetBrains Kotlin line.** `pom-scijava:37.0.0` manages
   older Kotlin artifacts, while current pty4j / JediTerm builds want
   Kotlin 2.1.21. Pin `kotlin.version` explicitly in `pom.xml`; test on a
   clean Fiji install.
8. **User habit disruption.** External-mode toggle stays; default can
   flip later.
9. **Terminal font / emoji fidelity.** A bundled mono font fixes width and
   Braille/box-drawing issues, but emoji may still depend on OS fallback.
   Treat emoji rendering as a smoke-test item, not a solved property of
   the bundled font alone.

## 8. Non-goals — explicit, with reasons

**Deferred from draft 1 because the existing helpers don't fit:**

- **"Save as recipe" button.** `agent/gemma4_31b/harvest_recipe.py:265`
  `save_recipe_file()` exists but the module has no CLI entry point —
  `python -m harvest_recipe` will not work, and the function expects a
  fully-formed recipe `dict`, not a session. A v1.5 task is "add CLI
  entry to `harvest_recipe.py`" + wire the button.
- **"Explain this dialog" button.** `agent/probe_plugin.py:49`
  `probe(plugin_name, force)` takes a *plugin command name*, not an
  open-dialog title; `TCPCommandServer.handleProbeCommand`
  (`src/main/java/imagejai/engine/TCPCommandServer.java:3566`)
  invokes `IJ.doCommand(pluginName)` and reads the resulting dialog.
  There is no existing path from "dialog is open right now → probe its
  parameters." Needs a new TCP command `get_active_dialog_command` +
  wiring. v2 item.

**Held for a later milestone (no existing helper, full new code):**

- Shadow macro preview / confirm-before-run (additive change to
  `TCPCommandServer.handleExecuteMacro` at `:1115`; ~2 days).
- Probe-driven dynamic button panel (auto-populate from open dialog).
- Reference doc search / `@mention` autocomplete.
- Multi-tab concurrent agents.
- Paired-agent mode (Claude plans, Aider implements).
- Whisper voice input.
- Rewind-to-checkpoint.
- Drag-image-to-describe / explain-this-pixel.
- Night-watcher.
- Per-agent personality YAMLs.
- Out-of-band `notify_agent` TCP command.

All are in the brainstorm record.

## Review notes appended by Codex

- Corrected stale dependency guidance: pty4j `0.13.12`, JediTerm `3.57`,
  JetBrains repo still required, LGPL-3.0 still correct.
- Corrected wrong line/count references: `execute_macro` dispatch is
  `TCPCommandServer.java:661-662`, recipe count is 26, `ImageJAIPlugin.run()`
  range extends through line 135.
- Added missing load-bearing work: Java 11 `DatatypeConverter` fix,
  explicit `kotlin.version` pin, shared `AgentLaunchSpec`, shell-portable
  launcher cleanup, `ChatSurface` facade before `ChatView` extraction, and
  `ImageJAIPlugin` shutdown via the existing window-close path rather than
  a nonexistent `dispose()`.
- Still slightly uncertain: the exact bundled mono font choice, and the
  exact JediTerm point release to pin on implementation day if JetBrains
  publishes a newer matched pair before work starts.

## Follow-up decisions (resolved 2026-04-20)

- **Terminal font:** JetBrains Mono (primary) + Noto Emoji monochrome
  (fallback). JetBrains Mono is the font JediTerm is tested against
  daily inside IntelliJ; Noto Emoji mono gives predictable Linux
  coverage since most distros ship no default emoji font. Windows and
  macOS still use OS colour-emoji fonts via JediTerm's fallback chain.
  Total bundle ~600 KB.
- **Version pin strategy:** pin JediTerm 3.57 + pty4j 0.13.12 in
  `pom.xml` now (Phase 0) for reproducible builds. Phase 1 starts with a
  15-minute recheck of the JetBrains repo — bump only if a newer release
  contains a Windows ConPTY / alt-screen / resize fix relevant to
  Gemma's animations.
