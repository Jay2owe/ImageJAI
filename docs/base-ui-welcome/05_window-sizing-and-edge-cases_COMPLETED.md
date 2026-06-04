# 05 — Window sizing + edge cases

## Goal

Stop the window being born clipped, give the Welcome card a sensible size, and
make the empty/degenerate states (no agents, on-premises with no local agent,
first-ever run) graceful.

## Context / anchors

- `CHAT_SIZE = 420×600`, `TERMINAL_SIZE = 900×700` (`AiRootPanel.java:85-86`).
- `applyFrameSize()` does `frame.setSize(...)` per card and **never** `pack()`
  (`:1160-1168`); no `setMinimumSize` on the panel side. `ImageJAIPlugin`
  sets the frame to 420×600 with a 350×400 minimum (`ImageJAIPlugin.java:185`),
  but `applyFrameSize` overrides on the first card show.
- Per-card size persists as "WxH" via `ij.Prefs`, floor 240 (`:1170-1207`).

## Steps

1. Give the Welcome card its own size key (reuse `CHAT_SIZE` as the default) so
   `savedSizeFor` (`:1182`) handles `CARD_WELCOME`.
2. Reconcile `applyFrameSize`: when applying a card size, take
   `max(savedSize, frame.getMinimumSize())` so a too-small saved value can't
   re-clip. Do not `setSize` over a min that was set elsewhere — set the floor
   once.
3. Edge cases:
   - **No agents installed:** recommendation = Local Assistant; Welcome CTA
     reads "Start with the built-in assistant"; "Choose a different assistant"
     still opens the picker (which shows install/setup affordances).
   - **On-premises, no local agent:** CTA explains "This folder is on-premises —
     install a local assistant" rather than offering a filtered-out cloud agent;
     route the link to the installer/settings.
   - **First-ever run:** the legacy first-run `SettingsDialog`
     (`ImageJAIPlugin.java:107-119`) should not fire over the Welcome card for
     the Local-Assistant default; confirm ordering so the user sees Welcome, not
     a key-entry dialog, unless they pick an API agent that needs one.
4. Confirm the existing `componentResized` listener (`:196`) still persists
   sizes and that the Welcome→embedded→Welcome transitions restore the right
   per-card size each time.

## Files

- `src/main/java/imagejai/ui/AiRootPanel.java`
- Possibly `src/main/java/imagejai/engine/ImageJAIPlugin.java` (first-run
  ordering only).

## Exit gate

- Welcome opens at a size where nothing clips; embedded grows to its saved/own
  size; returning home restores the welcome size.
- With zero agents installed, the Welcome CTA launches the Local Assistant and
  never dead-ends.
- On-premises with no local agent shows the install guidance, not a cloud agent.
- First-ever run lands on Welcome (Local-Assistant default), not the API-key
  dialog.

## Out of scope

New installer UI; the richer agent-cards popup.
