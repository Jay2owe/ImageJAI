# 04 — Thin working-state header + ⋯ overflow menu

## Goal

Replace the crowded single-row header with a state-aware header: minimal on the
Welcome card (title + ⚙), and a thin bar in the working state holding the
agent-switch picker + posture badge + a `⋯` overflow menu. This is the change
that actually kills the overlap bug.

## Target (working state)

```
┌─ AI Assistant ─────────────────────────── ─ ☐ ✕ ┐
│ ● Claude Code ▾   [New agent chat]    🔒 On-prem  ⋯│
├───────────────────────────────────────────────────┤
│  ...embedded TerminalView or Local-Assistant chat │
```

`⋯` menu items: Browse Files…, View Audit Log, Safe Mode (checkbox item),
Profile ▸ (submenu), Clear conversation, Settings….

## Context / anchors

- Current header built in `createHeader()` (`AiRootPanel.java:426-639`); the two
  colliding `FlowLayout` clusters at `:430` (WEST) and `:569` (EAST).
- Controls to relocate into `⋯`: Browse Files (`:591`), View Audit Log (`:600`),
  Clear (`:610`), Settings (`:627`), Safe Mode checkbox (`:545`), Profile
  switcher (`:515`). Keep their existing action bodies — only their housing
  changes.
- Agent-switch picker reuses `ModelPickerButton` (already in WEST) / the
  recommendation logic; selecting a different agent should exit the current
  embedded session and launch the new one in the same widget.
- PostureBadge + EgressIndicator stay visible (read-only status).

## Steps

1. Split header construction by state: a minimal header for `CARD_WELCOME`
   (title + ⚙ only) and a thin working header for `CARD_CHAT` / `CARD_TERMINAL`.
   Swap header content when the card changes (the header lives in the persistent
   `BorderLayout.NORTH`, so swap its inner component or use a `CardLayout` on the
   header too).
2. Build the `⋯` button → `JPopupMenu` and move the six secondary controls into
   `JMenuItem`s (Safe Mode as `JCheckBoxMenuItem`, Profile as a submenu of the
   `ModelConfig`s). Preserve every existing `ActionListener` body.
3. Working header left side: the agent-switch picker showing the running agent's
   name. Switching → confirm, then exit current embedded session (reuse the
   relaunch/destroy path in `relaunchEmbeddedSession`, `:1099`) and launch the
   chosen agent.
4. Remove the standalone ▶ `agentBtn` and the WEST title/labels/Profile/SafeMode
   from the always-on row (they now live in the picker + `⋯`). Keep
   `updateLaunchButtonState` semantics where still relevant, or retire it.
5. Verify the primary working row holds at most: picker + (optional New-agent-
   chat) + posture badge + egress + `⋯` — so it cannot overflow ~404px. Apply a
   `WrapLayout` (vendored, see below) as a backstop so it degrades by wrapping,
   never clipping, at large fonts.
6. (Optional, recommended) Vendor `WrapLayout` (a ~60-line `FlowLayout`
   subclass, pure AWT) into `imagejai.ui` and use it for the working header row.

## Files

- `src/main/java/imagejai/ui/AiRootPanel.java` (header rework, `⋯` menu,
  switch picker, header swap on card change).
- Optional new: `src/main/java/imagejai/ui/WrapLayout.java`.

## Exit gate

- First open (Welcome): header is just title + ⚙; nothing overlaps.
- Working state: thin header; all six secondary controls reachable from `⋯`
  with their original behaviour; PostureBadge/EgressIndicator visible.
- Switching agent from the header picker exits the current embedded session and
  launches the new one in the same widget.
- Drag the window narrow and bump the L&F font — the working header wraps, never
  clips. Build + deploy + click through.

## Out of scope

Window pack/min-size (stage 05). The richer agent-cards popup (Design C).
