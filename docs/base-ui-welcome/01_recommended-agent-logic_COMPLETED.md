# 01 — Recommended-agent logic

## Goal

A pure, testable ranking that picks the agent the Welcome CTA and the
working-state switch picker should default to, plus a short human reason string
("installed, runs locally for this folder"). No UI in this stage.

## Context / anchors

- Detected agents come from `AgentLauncher.detectAgents()` →
  `List<AgentLauncher.AgentInfo>` (`AgentLauncher.java:139`), already
  posture-filtered by `filterAgentsForPosture` (`:404`).
- `AgentInfo` carries `name`, `command`, `description`, `isLocal()`,
  `isOllama()` (`AgentLauncher.java:40-85`).
- The always-present fallback is `AgentLauncher.LOCAL_ASSISTANT_NAME` (`:27`).
- Last-used selection persists today via `settings.getSelectedAgentName()` /
  `setSelectedAgentName(...)` and `settings.selectedProvider` /
  `selectedModelId`; reuse these rather than inventing a new pref.

## Steps

1. Add `imagejai.engine.AgentRecommender` (new class). Single entry point, e.g.
   `Recommendation recommend(List<AgentInfo> eligibleAgents, String lastUsedName)`
   returning a small immutable `Recommendation { AgentInfo agent /* null = Local
   Assistant */, String displayName, String reason }`.
2. Ranking (first match wins):
   1. the last-used agent, **if** still present in `eligibleAgents`
      (reason: "your last assistant");
   2. else the first installed/authenticated agent, preferring local
      (`isLocal()`) when posture is on-premises (reason: "installed, runs
      locally" / "installed on this machine");
   3. else the Local Assistant (reason: "built-in, works offline, no setup").
3. Never return an agent absent from `eligibleAgents` (the list is already
   posture-filtered, so on-premises can only yield local agents → no dead-end).
4. Keep the reason strings short and plain-language (biologist-facing). Brand
   names are fine in the `displayName`; the reason explains *why*.
5. Do not call Swing or `AgentLauncher.launch` here — this is decision logic
   only, so it stays unit-testable off the EDT.

## Files

- New: `src/main/java/imagejai/engine/AgentRecommender.java`
- New: `src/test/java/imagejai/engine/AgentRecommenderTest.java`

## Exit gate

- Unit tests cover: empty list → Local Assistant; last-used present → returns
  it; last-used absent → installed pick; on-premises list (locals only) → never
  returns a cloud agent. `mvn -q -Dtest=AgentRecommenderTest test` passes.
- No Swing/EDT dependency in `AgentRecommender` (compile-checked by the test
  running headless).

## Out of scope

Wiring into the UI (stages 02–04). Any change to how agents are *detected* or
*launched*.
