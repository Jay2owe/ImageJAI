# Stage 01 — Settings field and persistent agent selector

## Why this stage exists

The whole Local Assistant feature lives behind a dropdown choice the
user has not yet been able to make. Today the agent dropdown is
opened transiently from the play button in `AiRootPanel` and not
persisted; chat routing in stage 02 needs a stable, persistent value
to switch on. This stage builds that foundation.

## Prerequisites

None.

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §3 ("Verified Current Code") and
  §4.1 ("Agent selector")
- `src/main/java/imagejai/config/Settings.java` — model
  profile persistence pattern; existing fields `configs`,
  `activeConfigId`, `agentEmbeddedTerminal`, `tcpServerEnabled`
- `src/main/java/imagejai/ui/AiRootPanel.java`
  `createHeader()` and `showAgentLaunchMenu()`
- `src/main/java/imagejai/engine/AgentLauncher.java`
  `KNOWN_AGENTS`, `detectAgents()`, `launch(...)`
- `CLAUDE.md` (project root) — house rules

## Scope

- Add a `selectedAgentName` field to `Settings` with a default
  sentinel value of `"Local Assistant"`. Add getter, setter, and
  JSON persistence wiring matching existing field patterns.
- Define a single `AgentLauncher.LOCAL_ASSISTANT_NAME` constant
  ("Local Assistant" or whatever §14.1 names it). Do **not** add
  Local Assistant to `KNOWN_AGENTS`; it is synthetic, not a
  detected CLI.
- Convert the play-button popup `AiRootPanel.showAgentLaunchMenu()`
  into a persistent `JComboBox<String>` in `createHeader()` next to
  the existing model-profile combo. Items: `LOCAL_ASSISTANT_NAME`
  first, then `AgentLauncher.detectAgents()` results. Selection
  writes to `Settings.selectedAgentName` and saves immediately.
- The play button's behaviour stays the same when an external agent
  is selected (launches it via `AgentLauncher.launch(agent, mode)`
  with `mode = EMBEDDED` if `Settings.agentEmbeddedTerminal` else
  `EXTERNAL`). When `LOCAL_ASSISTANT_NAME` is selected the play
  button does nothing (or is hidden / disabled — implementer's
  choice).

## Out of scope

- Routing chat input to `LocalAssistant.handle()` — stage 02.
- Bypassing the API-key gate and first-run dialog — stage 02.
- Any actual `LocalAssistant` class — stage 02.
- Models & Agents installer panel — stage 09.
- GSD-conditional `--dangerously-skip-permissions` for Claude —
  stage 09 (the unconditional flag in `KNOWN_AGENTS` stays for now).

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Add `selectedAgentName` field + getter/setter + JSON load/save |
| `src/main/java/imagejai/ui/AiRootPanel.java` | MODIFY | Add persistent agent selector to `createHeader()`, wire selection to `Settings` |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Add `LOCAL_ASSISTANT_NAME` constant |

## Implementation sketch

```java
// Settings.java — add alongside existing fields
public String selectedAgentName = AgentLauncher.LOCAL_ASSISTANT_NAME;

public String getSelectedAgentName() {
  return selectedAgentName == null ? AgentLauncher.LOCAL_ASSISTANT_NAME
                                   : selectedAgentName;
}

public void setSelectedAgentName(String name) {
  this.selectedAgentName = name;
  save();   // match existing persistence pattern in this class
}
```

```java
// AgentLauncher.java
public static final String LOCAL_ASSISTANT_NAME = "Local Assistant";
```

```java
// AiRootPanel.createHeader() — pseudocode
JComboBox<String> agentCombo = new JComboBox<>();
agentCombo.addItem(AgentLauncher.LOCAL_ASSISTANT_NAME);
for (AgentLauncher.AgentInfo a : agentLauncher.detectAgents()) {
  agentCombo.addItem(a.name);
}
agentCombo.setSelectedItem(settings.getSelectedAgentName());
agentCombo.addActionListener(e -> {
  settings.setSelectedAgentName((String) agentCombo.getSelectedItem());
  refreshInputState();   // stage 02 will use this to flip mode
});
header.add(agentCombo);
```

## Exit gate

1. `mvn -q -pl . compile` succeeds with no new warnings.
2. Fresh launch (delete `~/.imagej-ai/config.json`): the agent
   selector appears in the chat header and shows "Local Assistant"
   selected by default.
3. Selecting a different agent (e.g. "Claude Code" if installed)
   then closing and reopening the plugin restores that selection
   from `Settings`.
4. Selecting "Local Assistant" and clicking the play button does
   nothing (or the button is correctly hidden/disabled). Selecting
   an external agent and clicking play still launches that agent
   in a terminal as before.

## Known risks

- **Two combos in the header could look cluttered.** The model-
  profile combo is for LLM provider/model; the new combo is for
  agent choice. Label them clearly.
- **Selector widening the header beyond its intended size.** Prefer
  a compact combo with truncating renderer over expanding the
  whole panel.
- **`Settings` JSON migration.** Existing user configs lack
  `selectedAgentName`. Defaulting to `LOCAL_ASSISTANT_NAME` on
  null/missing covers this; do not break existing fields.
