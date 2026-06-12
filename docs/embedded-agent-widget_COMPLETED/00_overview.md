# Embedded Agent Widget — Overview

## End goal

A biologist opens Fiji, clicks "AI Assistant", picks an agent (Claude
Code, Aider, Gemini, Codex, or Gemma via Ollama), and the agent's
full terminal UX renders **inside** the Fiji plugin frame — not in a
separate `cmd.exe` window. A left rail of one-click buttons lets the
user run Fiji ops directly, re-run macros the agent just executed,
and trigger saved recipes — all without leaving the Fiji window.

Every macro or script the agent runs is silently captured on the
Java side, auto-named from its own code, and listed in a "Session
history" rail section so the user can re-run or save any of it with
one click. The agent never sees this journal — zero token cost.

## Why we're doing this

- Today's `AgentLauncher.java` opens agents in an external terminal
  via `cmd.exe /c start`. The user juggles two windows and has no
  shared surface for Fiji-specific actions.
- `ChatPanel` hard-disables its input when no API key is set
  (`ChatPanel.java:178-193`), which fights the local-only Gemma
  agent that needs no API key.
- Biologists can't easily re-run code the agent produced. Copy-paste
  from a scroll-past terminal is fragile; there's no "save this as a
  recipe" path for non-coders.
- The agent loses context every time the external terminal closes.
  An embedded, session-aware surface lets the rail offer workflow
  shortcuts (New chat, New WIP, Commands, Macro sets, Run recipe,
  Audit) without any LLM round-trip.

## Architecture

```
AiRootFrame (existing JFrame)
├── Header                                 profile / agent / settings
├── Body (NEW AiRootPanel, CardLayout):
│     • ChatView — today's chat pane (extracted from ChatPanel)
│     • TerminalView — NEW:
│           ├── WEST: LeftRail
│           │         └── SessionHistoryPanel (auto-journal, §2.10)
│           └── CENTER: TerminalHost (JediTerm widget)
└── Status bar
```

**Terminal backend:** JediTerm 3.57 + pty4j 0.13.12, from the
JetBrains intellij-dependencies Maven repo (not Maven Central).
Bundled fonts: JetBrains Mono + Noto Emoji monochrome fallback.

**Silent code journal:** `SessionCodeJournal` singleton hooked into
`TCPCommandServer.handleExecuteMacro` and `handleRunScript`. Captures
every piece of code the agent runs, auto-names it from leading
comments / composite `run(...)` signatures / first defined symbol.
Zero new TCP commands, no new events, nothing the agent can see.

## Stage map

| NN | Stage                        | Goal                                                           | Size  | Depends on |
|----|------------------------------|----------------------------------------------------------------|-------|------------|
| 01 | toolchain-bump               | Java 11, DatatypeConverter fix, Kotlin pin                     | S     | —          |
| 02 | launcher-refactor            | AgentLaunchSpec + AgentSession + ExternalAgentSession          | M     | 01         |
| 03 | settings-and-firstrun        | embedded-terminal flag, port collision, first-run no-key path  | S–M   | 02         |
| 04 | session-journal-server       | SessionCodeJournal + CodeAutoNamer + TCP hooks                 | M     | 01         |
| 05 | terminal-primitive           | Fonts + JediTerm + pty4j + EmbeddedPty + smoke-test menu       | L     | 02         |
| 06 | plugin-frame-integration     | AiRootPanel + ChatView extraction + TerminalView wiring        | M–L   | 05         |
| 07 | tcp-hotlines                 | LeftRail + TcpHotline helper + 3 Fiji-hotline buttons          | S     | 06         |
| 08 | prompt-and-url-handling      | PromptWatcher + TerminalToolbar + URL hyperlinks               | M     | 06         |
| 09 | agent-registries             | commands/clear/approval JSON for all 5 agents + refresh script | M     | 08         |
| 10 | buttons-and-helpers          | New WIP + Macro sets + Run recipe (+ run_recipe.py) + Audit    | M     | 07, 09     |
| 11 | session-history-ui           | SessionHistoryPanel rail section + right-click menu            | S–M   | 04, 07     |
| 12 | polish                       | Font shortcuts, window sizes, colour scheme, cross-platform    | S     | all above  |

Size key: S ≈ ≤½ day, M ≈ 1 day, L ≈ 1–2 days.

**Parallelism.** Stages 04 and 05 can run in parallel after 02 lands
(server-side journal has no UI dependency). Stage 08 can start after
06 without waiting for 07.

## House rules (apply to every stage)

1. **Check state before acting.** Never assume an image is open.
2. **Probe unfamiliar plugins** before calling them.
3. **Write outputs to `AI_Exports/`** next to the opened image.
4. **Never** `Enhance Contrast normalize=true` on data you'll
   measure — rewrites pixel values. Use `setMinAndMax()` for
   display-only contrast.
5. **Fix root causes**, don't work around bugs silently.
6. **Build:** JDK 25, Maven, JAR deploys to the Dropbox Fiji
   plugins folder.
7. **Log prefix:** `IJ.log("[ImageJAI-Term] ...")` for new
   terminal/rail code; matches the `[ImageJAI]` / `[ImageJAI-TCP]`
   pattern already in the codebase.
8. **Agent silence.** Any feature touching TCP must not add a
   command the agent could discover, must not push a new `macro.*`
   event, and must not change existing response shapes. Server-side
   logging and UI-only surfaces are fine.

## Canonical source

The long-form design record is `PLAN.md` in this folder. It is not
deleted — when a stage file gives you enough to execute, use it;
when you need deeper background or rationale on a decision, fall
back to `PLAN.md`. Section numbers like §2.7 in the stage files
refer to sections of `PLAN.md`.

## Known open questions

- Exact JediTerm / pty4j point release to pin — Phase 1 begins with
  a 15-minute version recheck; bump only if a newer release
  contains a Windows ConPTY / resize fix relevant to Gemma.
- Emoji rendering on Linux — bundled Noto Emoji mono is the
  fallback but JediTerm's font-fallback chain behaviour must be
  smoke-tested on the target distro.
- LGPL-3.0 clearance for public release — dynamic linking is fine
  for Fiji plugins; confirm licensing if the plugin is
  ever bundled into proprietary code.

## How to run a stage

```
/do-step docs/embedded-agent-widget/
```

Runs the lowest-numbered non-`_COMPLETED` stage. Stage file is
self-contained — the agent reads it, gathers context from the "Read
first" list, executes, commits, and marks the file `_COMPLETED`.

## Total estimate

12–19 working days end-to-end if stages are run serially. Faster if
04/05 and 07/08 are parallelised.
