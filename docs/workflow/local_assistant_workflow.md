# Local Assistant Workflow

Deterministic in-app path. No external model and no TCP bridge required.

```text
1. User prompt in ImageJAI chat
   "measure this ROI", "next slice", "threshold with Otsu"
        |
        v
2. ChatView
   Checks that "Local Assistant" is selected.
   Handles the message directly instead of notifying ConversationLoop.
        |
        v
3. LocalAssistant
   Keeps local conversation state.
   Handles pending questions and disambiguation.
        |
        v
4. Phrase and command routing
        |
        +--> IntentLibrary
        |       built-in commands and aliases
        |
        +--> IntentMatcher
        |       fuzzy phrase matching and autocomplete chips
        |
        +--> SlashCommandRegistry
        |       /help, /clear, /teach, /macros, /info, etc.
        |
        +--> IntentRouter
                user-taught phrase -> macro mappings
        |
        v
5. FijiBridge
   Local Java facade for Fiji actions.
   Checks image state where needed.
   Resolves AI_Exports/ for file-backed images when local intents save output.
        |
        v
6. CommandEngine
   Runs ImageJ macro code for local assistant actions.
   Captures success/failure, new images, and Results table changes.
        |
        v
7. Fiji/ImageJ runtime
   Active image, stacks, ROIs, Results table, dialogs, installed plugins.
        |
        v
8. Local safety and output checks
   Safe Mode records risky macro actions and recoverable ROI backups.
   Saved files are resolved under AI_Exports/ where local intents write output.
   Audit and posture UI can surface relevant safety events.
        |
        v
9. Return to ChatView
   LocalAssistant returns AssistantReply.
   ChatView shows plain text and, when present, the macro echo.
        |
        v
10. Output
   Updated Fiji windows, Results table changes, local saved files, chat reply.
```

## Short Version

```text
User prompt
  -> ChatView
  -> LocalAssistant
  -> IntentLibrary / IntentMatcher / SlashCommandRegistry / IntentRouter
  -> FijiBridge
  -> CommandEngine
  -> Fiji/ImageJ
  -> local safety/output checks
  -> AssistantReply back to ChatView
```
