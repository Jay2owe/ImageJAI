# Stage 08 — IntentRouter integration and slash commands

## Why this stage exists

The codebase already has a regex-based user-taught intent system
(`IntentRouter` + the `intent`/`intent_teach`/`intent_list`/
`intent_forget` TCP commands). Local Assistant should use it as the
extension layer rather than building a parallel one. This stage
also makes the slash-command surface a first-class part of the
chat: `/help`, `/clear`, `/macros`, `/info`, `/close`, `/teach`,
`/intents`, `/forget`.

## Prerequisites

- Stage 03 (`LocalAssistant`, `IntentMatcher` exist with Tier 1).
- Useful but not strictly required: stages 06–07 (so `/macros` etc.
  have content to coexist with).

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §6.2 (matcher cascade), §11
  (extensibility), §13 (slash commands)
- `src/main/java/imagejai/engine/IntentRouter.java` —
  verify the public API: `resolve(String)`, `teach(...)`,
  `forget(...)`, `list()`. **Do not assume `add`/`remove`** — the
  methods are `teach`/`forget`.
- `src/main/java/imagejai/engine/TCPCommandServer.java`
  handlers for `intent`, `intent_teach`, `intent_list`,
  `intent_forget` — they show how the router is invoked today.
- `agent/gemma4_31b/loop.py` lines around 2090–2170 — the existing
  `/help` and `/clear` slash command handling

## Scope

### IntentRouter integration

In `LocalAssistant.handle()`, after Tier 1 hash and Tier 2 fuzzy
both miss but **before** the "I don't recognise" reply:

```java
Optional<IntentRouter.Match> userMatch = intentRouter.resolve(input);
if (userMatch.isPresent()) {
  String macro = userMatch.get().expandedMacro();   // $1..$9 substituted
  fiji.runMacro(macro);
  return AssistantReply.withMacro(
    "Ran user-taught intent: " + userMatch.get().pattern(), macro);
}
```

If `resolve()` is not the actual method name, use whatever the
router exposes for "match input → matched mapping". Mirror the
behaviour of the existing `intent` TCP handler.

### Slash commands

Recognise input that starts with `/` in `IntentMatcher.normalise()`:
**do not** strip the leading `/` from the normalised key; instead
treat `/name args` as a special case that splits into command + args
and dispatches via a `SlashCommandRegistry` before reaching the
phrasebook lookup. Slash commands take precedence over any
phrasebook entry that happens to share the name.

Implement these slash commands. Each has a one-line plain-English
phrasebook alias (so users can either type `/info` or "info"):

| Command | Behaviour | Notes |
|---|---|---|
| `/help` | Print one-line description of every registered intent, grouped by §5 category. | Categories from intent ID prefix. Replaces the stage 02 stub. |
| `/clear` | Clear the chat history. | Mirror gemma4_31b `_handle_clear`. |
| `/macros` | List `<Fiji>/macros/*.ijm` plus `~/.imagej-ai/learned_macros/*.ijm`. Columns: filename, first-line `// description`, last modified. | "run my <name> macro" also matches via fuzzy lookup against filenames. |
| `/info` | Table of all open images: title, dimensions (W × H × C × Z × T), bit depth, pixel size + unit, file path, saturated?. | Use `WindowManager.getIDList()` + `WindowManager.getImage(id)` per the codebase pattern. |
| `/close` | `/close` (active), `/close all`, `/close all but active`, `/close <substring>`. Always whitelist the Log window. | Overlaps with control intents in stage 06; the slash command is the canonical front door. |
| `/teach <phrase> => <macro>` | Add or update a user-taught intent via `IntentRouter.teach(...)`. | The `=>` separator is required; show the parsed regex back to the user before saving. |
| `/intents` | List all `IntentRouter` mappings with hit count and last-used timestamp. | Pulls from `IntentRouter.list()` or equivalent. |
| `/forget <phrase-or-pattern>` | Remove a user-taught intent by its regex pattern via `IntentRouter.forget(...)`. | Stage 14.5 carries the namespacing decision. |

Slash commands that take arguments use chat-reply prompting if the
required argument is missing (PLAN §14.3): `/teach` with no args
replies "usage: /teach <phrase> => <macro>".

## Out of scope

- Installer panel slash command (e.g. `/install`) — stage 09.
- Menu-mined intent stubs — stage 10.
- `/save-recipe`, `/interrupt`, `/think`, `/mode`, `/ccommand[s]`,
  `/queue` — explicitly **not ported** from gemma4_31b
  (PLAN §13).
- `IntentRouter` persistence layer changes — already exists; this
  stage only consumes the existing API.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/LocalAssistant.java` | MODIFY | Add IntentRouter fallback stage |
| `src/main/java/imagejai/local/IntentMatcher.java` | MODIFY | Detect `/`-prefixed input and route to slash registry first |
| `src/main/java/imagejai/local/SlashCommandRegistry.java` | NEW | Map `/name -> SlashCommand` |
| `src/main/java/imagejai/local/SlashCommand.java` | NEW | Interface |
| `src/main/java/imagejai/local/slash/*.java` | NEW (~8 files) | One class per command |
| `src/main/java/imagejai/ui/ChatView.java` | MODIFY | Hook for `/clear` to actually wipe rendered history |
| `src/main/java/imagejai/local/IntentLibrary.java` | MODIFY | Register slash commands' plain-English aliases (e.g. "show open images" → /info) |

## Implementation sketch

```java
// LocalAssistant.handle() — after stage 05's matcher misses
public AssistantReply handle(String input) {
  // Slash commands first (they live in IntentMatcher's normalise path)
  Optional<MatchedIntent> m = matcher.match(input);
  if (m.isPresent()) {
    return library.byId(m.get().intentId()).execute(m.get().slots(), fiji);
  }
  // User-taught fallback
  Optional<IntentRouter.Match> u = intentRouter.resolve(input);
  if (u.isPresent()) {
    fiji.runMacro(u.get().expandedMacro());
    return AssistantReply.withMacro(
      "Ran user-taught intent.", u.get().expandedMacro());
  }
  // Miss
  frictionLog.record("local_assistant", "miss", "",
                     IntentMatcher.normalise(input));
  List<RankedPhrase> chips = matcher.topK(input, 3);
  return AssistantReply.text(buildDidYouMean(input, chips));
}
```

```java
// SlashCommandRegistry.dispatch
public Optional<AssistantReply> dispatch(String input, FijiBridge fiji) {
  if (!input.startsWith("/")) return Optional.empty();
  String[] parts = input.split("\\s+", 2);
  String name = parts[0].substring(1);
  String args = parts.length > 1 ? parts[1] : "";
  SlashCommand cmd = registry.get(name);
  return cmd == null ? Optional.empty()
                     : Optional.of(cmd.execute(args, fiji));
}
```

```java
// TeachSlashCommand.java
public AssistantReply execute(String args, FijiBridge fiji) {
  String[] parts = args.split("=>", 2);
  if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
    return AssistantReply.text("usage: /teach <phrase> => <macro>");
  }
  String phrase = parts[0].trim();
  String macro = parts[1].trim();
  intentRouter.teach(phrase, macro);
  return AssistantReply.text(
    "Taught: \"" + phrase + "\" → " + macro);
}
```

## Exit gate

1. `/help` lists all built-in intents grouped by §5 category.
2. `/teach hello world => print("hello world");` followed by typing
   `hello world` in chat runs the macro.
3. `/intents` shows the just-taught intent with hit count 1.
4. `/forget hello world` removes it; `/intents` no longer lists it.
5. `/info` with three open images returns a 3-row table.
6. `/macros` lists at least the saved Fiji macros (verify against
   actual `<Fiji>/macros/` content on the dev machine).
7. `/close all` closes everything except the Log window.
8. `/clear` empties the chat panel.
9. Typing "info" (no slash) also returns the info table — the
   plain-English alias works.
10. `mvn -q test` passes.

## Known risks

- **`IntentRouter` API drift.** Verify the actual method names
  (`teach`/`forget`/`list`/`resolve` per the latest codebase, not
  `add`/`remove`). If the names are different, update the stage's
  pseudocode and the slash command implementations to match.
- **Slash command name collisions** with phrasebook entries. A
  built-in intent named "info" should not silently override
  `/info` or vice versa. Establish a precedence rule (slash >
  phrasebook) in `LocalAssistant.handle()` and document it.
- **Macro path resolution for `/macros`.** `<Fiji>/macros/` is
  `IJ.getDirectory("macros")`. `~/.imagej-ai/learned_macros/` is
  custom; create on first use.
- **`/teach` regex injection.** The user-supplied phrase becomes a
  regex in `IntentRouter`. Decide whether to escape user input
  (treat as literal) or pass through. The router already
  quarantines bad regexes; treating user input as literal is
  safer for non-technical users.
- **`/clear` and active turns.** If a long-running intent (e.g.
  compare thresholds) is in flight, `/clear` should not lose the
  user's place. Mirror gemma4_31b's check before clearing.
