# Stage 02 — Local Assistant package skeleton and chat routing

## Why this stage exists

Stage 01 made the user's agent choice persistent and visible. This
stage connects that choice to a real handler so that everything from
stage 03 onwards has somewhere to plug into. We also unblock the
first-launch user experience by bypassing the API-key gate and the
forced settings dialog when Local Assistant is the active mode.

## Prerequisites

- Stage 01 (`Settings.selectedAgentName` exists and is wired to the
  header dropdown).

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §3, §4.2 ("Chat submission"), §6.1
  ("Classes")
- `src/main/java/imagejai/ui/ChatView.java`
  (`sendMessage()`, `refreshInputState()`, `setEnabled()`, the
  `ChatListener` interface, `appendMessage(...)`)
- `src/main/java/imagejai/ui/ChatPanel.java` —
  understand that this is a compatibility wrapper around `ChatView`
- `src/main/java/imagejai/ConversationLoop.java`
  `onUserMessage()` and `processUserMessage()`
- `src/main/java/imagejai/ImageJAIPlugin.java` `run()`
  — the first-run `SettingsDialog` open

## Scope

- Create package `imagejai.local` with these stub
  classes:
  - `LocalAssistant.java` — owns `IntentLibrary` + `IntentMatcher` +
    `FijiBridge`. `handle(String input) -> AssistantReply`.
  - `Intent.java` — interface (`id()`, `description()`,
    `execute(Map<String,String>, FijiBridge) -> AssistantReply`).
  - `IntentLibrary.java` — empty registry; methods `byId(String)`,
    `all()`, `register(Intent)`.
  - `IntentMatcher.java` — `match(String) -> Optional<MatchedIntent>`.
    Stub returns empty.
  - `AssistantReply.java` — record/POJO with `text`, `macroEcho`,
    `attachments`. Static factories `text(String)`,
    `withMacro(String, String)`.
  - `FijiBridge.java` — empty for now; will be filled by stages
    06/07. Holds a reference to `CommandEngine` and exposes nothing
    yet.
- Wire `ChatView.sendMessage()` so that when
  `settings.getSelectedAgentName().equals(AgentLauncher.LOCAL_ASSISTANT_NAME)`,
  the input string is routed to `localAssistant.handle(input)` and
  the reply is appended via `appendMessage("assistant", reply.text)`
  + an optional macro-echo line. When any other agent is selected,
  the existing `ChatListener` notification path is preserved
  unchanged.
- In `ChatView.refreshInputState()` and `ChatView.setEnabled()`,
  treat Local Assistant as "always enabled" — bypass the
  `Settings.hasApiKey()` gate.
- In `ImageJAIPlugin.run()`, do **not** open `SettingsDialog` on
  first run when `selectedAgentName == LOCAL_ASSISTANT_NAME`.
- Ship one trivial built-in intent so the wiring is end-to-end
  testable: a `HelpIntent` whose `execute` returns canned text
  ("I am the Local Assistant. I match plain English to ImageJ
  actions. Try: 'pixel size', 'close all', 'list ROIs'."). Register
  it manually in `IntentLibrary` and have `IntentMatcher.match()`
  return a hit when the normalised input equals "help".

## Out of scope

- Phrasebook JSON loader — stage 03.
- Real intents beyond `HelpIntent` — stages 06–07.
- Tier 2 fuzzy match and autocomplete chips — stage 05.
- `IntentRouter` fallback — stage 08.
- Slash command parsing — stage 08 (treat `/help` as plain text for
  now; only "help" matches in this stage).

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/LocalAssistant.java` | NEW | Top-level handler |
| `src/main/java/imagejai/local/Intent.java` | NEW | Interface |
| `src/main/java/imagejai/local/IntentLibrary.java` | NEW | Registry |
| `src/main/java/imagejai/local/IntentMatcher.java` | NEW | Stub matcher |
| `src/main/java/imagejai/local/AssistantReply.java` | NEW | Reply container |
| `src/main/java/imagejai/local/FijiBridge.java` | NEW | Empty facade |
| `src/main/java/imagejai/local/intents/HelpIntent.java` | NEW | Trivial proof-of-wiring intent |
| `src/main/java/imagejai/ui/ChatView.java` | MODIFY | Route to `LocalAssistant` when active; bypass `hasApiKey()` gate |
| `src/main/java/imagejai/ImageJAIPlugin.java` | MODIFY | Skip first-run `SettingsDialog` when Local Assistant is default |

## Implementation sketch

```java
// LocalAssistant.java
public class LocalAssistant {
  private final IntentLibrary library;
  private final IntentMatcher matcher;
  private final FijiBridge fiji;

  public LocalAssistant(IntentLibrary lib, IntentMatcher m, FijiBridge f) {
    this.library = lib; this.matcher = m; this.fiji = f;
  }

  public AssistantReply handle(String input) {
    return matcher.match(input)
      .map(m -> library.byId(m.intentId())
                       .execute(m.slots(), fiji))
      .orElse(AssistantReply.text(
        "I don't recognise \"" + input + "\". Type 'help' to see " +
        "what I can do."));
  }
}
```

```java
// ChatView.sendMessage() — pseudocode
String input = inputField.getText();
if (settings.getSelectedAgentName()
            .equals(AgentLauncher.LOCAL_ASSISTANT_NAME)) {
  appendMessage("user", input);
  AssistantReply r = localAssistant.handle(input);
  appendMessage("assistant", r.text());
  if (r.macroEcho() != null) {
    appendMessage("assistant", "```\n" + r.macroEcho() + "\n```");
  }
  return;
}
// existing path: notify ChatListener
notifyListeners(input);
```

## Exit gate

1. `mvn -q compile` succeeds.
2. With Local Assistant selected and **no API key configured**:
   the chat input is enabled, no `SettingsDialog` pops up at
   plugin startup.
3. Typing `help` and hitting enter shows the canned help reply.
4. Typing `anything else` shows the "I don't recognise that"
   message — proving that `IntentMatcher.match()` is being called
   and returns empty.
5. Switching the dropdown back to an LLM-based agent (if
   configured) restores the existing chat behaviour.

## Known risks

- **`ChatPanel`/`ChatView` duality.** `ChatPanel.java` is a
  compatibility wrapper. Edit `ChatView` (the actual implementation)
  and verify `ChatPanel` still compiles.
- **`AssistantReply` pattern choice.** Either a Java `record` (JDK
  16+) or a small POJO. JDK 25 is available; record is preferred
  unless the codebase consistently uses POJOs.
- **First-run dialog bypass.** Be careful that `ImageJAIPlugin.run()`
  still opens `SettingsDialog` when the user explicitly invokes
  Settings — the bypass is only for the *automatic* first-run open.
