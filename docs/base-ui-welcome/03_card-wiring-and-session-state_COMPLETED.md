# 03 — Card wiring + session-state switching

## Goal

Make the Welcome card the home screen: shown whenever no agent session is live,
replaced by the working state (embedded `TerminalView` for CLI agents, or the
chat card for the Local Assistant) when one launches, and restored when the
agent exits. Wire the CTA and the "Choose a different assistant" link.

## Context / anchors

- `cards` / `cardLayout` with `CARD_CHAT` and `CARD_TERMINAL`
  (`AiRootPanel.java:139-143`); `showChatCard()` / `showTerminalCard()`
  (`:1136-1158`).
- Live sessions tracked in `liveSessions` (`:98`); added in
  `handleLaunchedSession` (`:1011-1041`), removed in `watchSessionExit`
  (`:1072-1097`). External-terminal sessions are also added but have no embedded
  card — treat "live" as "any entry in `liveSessions`".
- Launch paths: `launchModelAsync` (`:925`) and `launchAgentAsync` (`:968`),
  mode from `settings.agentEmbeddedTerminal` (`:940-942`). Embedded primary;
  external is the Java-fallback.
- Local Assistant has no process; it lives on the chat card and is selected via
  `LOCAL_ASSISTANT_NAME`.

## Steps

1. Add `CARD_WELCOME = "welcome"`; construct `WelcomePanel` and
   `cards.add(welcomePanel, CARD_WELCOME)`. Add `showWelcomeCard()` mirroring
   the other `show*Card()` methods (set `currentCard`, `cardLayout.show`,
   `applyFrameSize`).
2. On construction, show Welcome instead of the chat card when there is no live
   session (replace/guard the `showChatCard()` call at `:187`).
3. Compute the recommendation (stage 01) off the EDT after `detectAgents()`
   resolves (reuse the existing `SwingWorker` in `refreshAgentSelectorAsync`,
   `:789`), then `welcomePanel.setRecommendation(...)` on the EDT. Recompute on
   posture change (`installPostureRefreshListener`, `:305`) and after CLI agents
   are injected (`injectCliAgentsAsync` done-block, `:251`).
4. CTA (`onStart`): launch the recommended agent.
   - Local Assistant → just `showChatCard()` and focus input (no process).
   - CLI / API agent → existing `launchAgentAsync` / `launchModelAsync`; the
     embedded path already swaps to `CARD_TERMINAL` and resizes in
     `handleLaunchedSession`.
5. "Choose a different assistant" (`onChooseDifferent`): open the agent picker.
   Reuse `ModelPickerButton`'s popup (`modelPicker.showPopup()`) so selection +
   `onLaunchRequested` flow is unchanged; the picker already lists CLI agents +
   Local. (A richer card popup — Design C — can replace this later behind the
   same callback.)
6. Return-to-home: in `watchSessionExit`, when `liveSessions` becomes empty
   after removal, call `showWelcomeCard()` instead of `showChatCard()`
   (`:1093`). Recompute the recommendation so "last-used" reflects the agent
   just closed.
7. Guard for TCP-only/headless: all of the above is inside `AiRootPanel`, which
   only exists when the panel is live, so no extra guard needed — but do not
   assume an image or session exists.

## Files

- `src/main/java/imagejai/ui/AiRootPanel.java` (constructor, new card,
  show/recompute methods, CTA/link callbacks, `watchSessionExit`).

## Exit gate

- Fresh launch → Welcome card shows with a named recommendation.
- "Start analysing" launches the recommended agent: a CLI agent fills the panel
  with the embedded `TerminalView` and grows to 900×700; Local Assistant lands
  on the chat card.
- Quitting the embedded agent returns to the Welcome card at 420×600.
- "Choose a different assistant" opens the existing picker and launching from it
  still works.
- On-premises posture: recommendation and picker offer only local agents.
- Build + deploy to Fiji; click the full loop once.

## Out of scope

The thin working-state header and `⋯` overflow (stage 04); window-size
hardening and empty states (stage 05).
