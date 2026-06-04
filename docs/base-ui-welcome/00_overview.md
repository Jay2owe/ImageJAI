# Base UI: Welcome launcher + working-state header

## Why

The first window that opens (`AiRootPanel`, chat card at `CHAT_SIZE = 420×600`)
crams ~13 controls into a single non-wrapping `BorderLayout` header row. The
WEST cluster (~631px) and EAST cluster (~357px) both demand their preferred
width inside ~404px of usable space, so they physically overlap on first open
(`AiRootPanel.java:426-639`, `:85`). There is also no clear way to spawn a
specific agent: a cascading model-picker dropdown (CLI agents folded in as a
synthetic `cli` provider) plus a bare ▶ glyph that actually *relaunches the
last* model.

## The design

The window has three body states, driven by the existing `CardLayout`
(`AiRootPanel.java:139-143`). One rule governs which is shown:

> **Is an agent session live?** → working state. **Otherwise** → Welcome.

```
  no session ──Start / pick──▶ session live ──agent exits──▶ no session
  ═══════════                 ═════════════                ═══════════
  WELCOME (home)              embedded widget OR            back to
  minimal header              Local-Assistant chat          WELCOME
  one big CTA                 thin header bar
```

- **Welcome card (new):** title + tagline + one primary CTA ("Start
  analysing") that launches the *recommended* agent, a "Recommended: X" line,
  a "Choose a different assistant ▾" link, and a posture footer. Header
  collapses to title + ⚙. A single centred CTA cannot overlap at any width —
  it retires the crowded header for the moment that matters most.
- **Working state — embedded agent (primary):** launching a CLI agent
  (Claude Code, Aider, Codex, Gemma…) fills the panel with the existing
  embedded widget — `TerminalView` = `LeftRail` (button rail) + `TerminalToolbar`
  (prompt approvals / copy-url) + `TerminalHost` (JediTerm PTY). Window grows
  to `TERMINAL_SIZE = 900×700`. This is the whole reason the plugin needs
  Java 11 (pty4j/JediTerm), and it is the intended experience.
- **Working state — Local Assistant:** the built-in, no-process assistant uses
  the chat card with the same thin header.
- **Thin header bar (working state):** agent name + a switch picker
  (`● Claude Code ▾`) + posture badge + a `⋯` overflow menu holding the
  secondary controls (Browse Files, View Audit Log, Profile, Safe Mode, Clear,
  Settings) that crowd today's header.

### Recommended-agent logic (shared)

One ranking, reused by the Welcome CTA *and* the working-state switch picker:

1. posture-eligible only (on-premises hides cloud — `AgentLauncher.filterAgentsForPosture`, `:404`)
2. prefer installed / authenticated
3. prefer last-used (persisted via `ij.Prefs`)
4. fall back to the always-present Local Assistant

Always name the pick and show the reason on hover — the supervising scientist
must be able to audit the choice (governance story).

## Hard constraints (any stage must respect)

- **Swing/AWT only.** Build is Java 11 bytecode (`pom.xml:76-78`), not Java 8 —
  the "Java 8 / Zulu 8" copy in `AiRootPanel.showJavaCompatibilityDialog`
  (`:1056`) and `CLAUDE.md` is stale (see stage 06). Only the embedded terminal
  is version-gated. No JavaFX, no new third-party UI dependency.
- **Posture filtering is live.** On-premises hides every non-local agent and
  refuses `-cloud` Ollama tags; re-filter on posture change
  (`installPostureRefreshListener`, `:305`). The recommendation must never
  offer a filtered-out agent (no dead-ends).
- **Governance controls stay reachable** (PostureBadge, EgressIndicator,
  Browse Files, View Audit Log, ConfigurationPane, ReceiptsPane). They may move
  into `⋯` / a status strip but no deeper than one click.
- **Local Assistant** is always available with no API key
  (`AgentLauncher.LOCAL_ASSISTANT_NAME`, `:27`).
- **Tier-safety gates fire at launch** (`FirstUseDialog` via `ProviderTierGate`,
  `BudgetCeilingDialog`, `BillingFailureDialog`) — keep them in the launch path.
- **Agent-agnostic** — no hardcoded model identity beyond display.
- **TCP-only / headless** — chat controller methods already no-op when the
  panel is absent; new logic must do the same.

## Reused, not rebuilt

- `TerminalView` / `LeftRail` / `TerminalToolbar` / `TerminalHost` — the embedded
  widget. The Welcome card is just a new front door to it.
- `CardLayout` body + per-card window-size persistence (`:1170-1207`).
- `ModelPickerButton` cascading popup + `onLaunchRequested` callback
  (`ModelPickerButton.java:48`) — basis for the "choose a different assistant"
  picker and the working-state switch picker.
- `AgentLaunchOrchestrator` model→transport routing (CLI / NATIVE / PROXY).
- Governance widgets (`PostureBadge`, `EgressIndicator`, etc.) — self-contained
  `JComponent`s, just relocated.

## Stages

- `01_recommended-agent-logic.md` — ranking + reason string (no UI).
- `02_welcome-card-ui.md` — the Welcome panel component (static, not wired).
- `03_card-wiring-and-session-state.md` — add the card, drive show/hide off
  live-session state, route the CTA + "choose different" link.
- `04_thin-header-and-overflow-menu.md` — state-aware header, `⋯` overflow,
  working-state switch picker; retire the crowded row.
- `05_window-sizing-and-edge-cases.md` — pack/min-size, welcome sizing,
  no-agents / on-premises / first-run empty states.
- `06_reconcile-java-version-copy.md` — fix the stale "Java 8" copy.

## How to execute

Run `/do-step base-ui-welcome` to do the lowest-numbered un-`_COMPLETED`
stage, or hand a single `NN_*.md` to an implementer. Each stage has its own
exit gate; build with `mvn` (JDK 25 toolchain) and deploy to Fiji via the
`deploy` skill to click through.

## Out of scope (deferred)

Agent cards launcher (Design C — reachable later from the "choose different"
link), capability chips (Design D), command-palette launch, surfacing the rail
on the chat card. These ride on the same recommendation logic and can follow.
