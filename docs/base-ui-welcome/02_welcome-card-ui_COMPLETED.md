# 02 — Welcome card UI

## Goal

A standalone `WelcomePanel` Swing component matching Design B: title, tagline,
one big primary CTA, a recommended-agent line, a "Choose a different
assistant ▾" link, and a posture footer. Built and visually correct in
isolation; wired into `AiRootPanel` in stage 03.

## Target layout (Design B, ~420×600)

```
┌─ AI Assistant ─────────────────────────── ─ ☐ ✕ ┐
│                                              ⚙    │
│                  ✦  ImageJAI                      │
│       Do image analysis by describing it          │
│       in plain English. I pick the tools,         │
│       write the macro, show you the result.       │
│         ┌─────────────────────────────┐           │
│         │    ▶   Start analysing      │           │
│         └─────────────────────────────┘           │
│           Recommended: Claude Code                │
│           Choose a different assistant ▾          │
│  🔒 On-premises — your data stays on this machine │
└───────────────────────────────────────────────────┘
```

## Context / anchors

- Match the existing dark theme constants in `AiRootPanel`: `BG_MAIN(30,30,35)`,
  `ACCENT(0,200,255)`, `TEXT_MUTED(120,120,130)` (`AiRootPanel.java:88-90`).
- Use centred `BoxLayout(Y_AXIS)` with `Box.createVerticalGlue()` above/below
  the CTA cluster so it stays centred and never overflows a narrow width.
- The posture footer should render the live posture label (reuse
  `PostureController.getInstance().current()` and the wording already used by
  `PostureBadge`).

## Steps

1. Add `imagejai.ui.WelcomePanel extends JPanel`. Constructor takes callbacks,
   not logic: `Runnable onStart`, `Runnable onChooseDifferent` (stage 03 wires
   them).
2. Expose `setRecommendation(String displayName, String reason)` so the parent
   can set the CTA caption ("Start analysing" with sublabel "Recommended:
   <name>") and tooltip (the reason). Default caption before a recommendation
   arrives: "Start analysing" with no sublabel.
3. Expose `setPostureText(String label)` and refresh it on posture change
   (parent calls this from the existing posture listener).
4. Style the CTA as a real primary button (filled accent, larger font, hand
   cursor); the "Choose a different assistant ▾" as a muted text-style button.
5. No agent detection, no launch, no `CardLayout` here — pure view + callbacks.

## Files

- New: `src/main/java/imagejai/ui/WelcomePanel.java`

## Exit gate

- A throwaway `main`/manual harness (or an existing dev launcher) shows the
  panel at 420×600 with no overlap; CTA + link fire their callbacks
  (verify with `IJ.log` stubs).
- `mvn -q compile` clean.

## Out of scope

The CardLayout switch, recommendation computation (stage 01 supplies it), and
the "choose different" popup contents (stage 03/04).
