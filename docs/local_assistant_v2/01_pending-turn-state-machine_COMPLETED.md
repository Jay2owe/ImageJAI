# Stage 01 — Pending-turn state machine

## Why this stage exists

v1's `LocalAssistant.handle()` is stateless across user turns.
That's fine for one-shot intents but breaks two v2 flows:

- **Parameter prompting** (stage 02) needs to ask "what radius?"
  and apply the user's next message to the held intent.
- **Disambiguation** (stage 03) needs to present chips and resolve
  on the next click/message.

Both flows need the same primitive: hold one pending question;
take the next user input as either an answer or an abandonment.
This stage builds that primitive once so stages 02 and 03 don't
each invent their own state field.

## Prerequisites

None. Foundational stage.

## Read first

- `docs/local_assistant_v2/00_overview.md`
- `docs/local_assistant_v2/PLAN.md` §5 ("Stage 01 — Pending-turn
  state machine"), §3 ("Verified current code")
- `docs/local_assistant_v2/BRAINSTORM.md` §2
- `src/main/java/imagejai/local/LocalAssistant.java`
  — focus on `handle()` at line 82
- `src/main/java/imagejai/local/AssistantReply.java`
  — current factories at lines 23-29
- `src/main/java/imagejai/local/RankedPhrase.java`
  — for the candidate type used in disambiguation
- `src/main/java/imagejai/ui/ChatView.java` lines
  824-862 (`sendMessage()` rendering path) — where stage 03 will
  later read clarification candidates

## Scope

- New `PendingTurn.java` with two factories: `parameter(...)` and
  `disambiguation(...)` (the second isn't yet *used* until stage
  03, but ship the factory now so stages 02 and 03 don't fight over
  the type).
- Modify `AssistantReply` to add `clarifying(String question,
  List<RankedPhrase> candidates)` plus `isClarifying()` and
  `clarificationCandidates()` accessors. Backwards-compatible with
  existing `text(...)` and `withMacro(...)` callers.
- Modify `LocalAssistant` to hold `Optional<PendingTurn> pending`
  and check it at the very top of `handle(input)`. Resolution
  delegates to the *kind*: `PARAMETER` is a stub for stage 02 (the
  resolution method exists but nothing parks a parameter turn yet);
  `DISAMBIGUATION` is also a stub for stage 03.
- Wire `/clear` to also clear the pending turn (verify
  `ClearSlashCommand` flushes via `ChatHistoryController` — likely
  the simplest place is to call `localAssistant.clearPending()`
  from the same path).
- Unit tests for the resolution rules: anything that doesn't
  resolve drops the pending turn and falls through.

## Out of scope

- Actually parking parameter pending turns. Stage 02 does that.
- Actually parking disambiguation pending turns. Stage 03 does
  that.
- ChatView clarification chip rendering. Stage 03 does that.
- Conversational memory. Independent — stage 04.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/PendingTurn.java` | NEW | The state object |
| `src/main/java/imagejai/local/AssistantReply.java` | MODIFY | `clarifying(...)` factory + accessors |
| `src/main/java/imagejai/local/LocalAssistant.java` | MODIFY | `pending` field, top-of-handle check, `clearPending()` method |
| `src/main/java/imagejai/local/slash/ClearSlashCommand.java` | MODIFY | Clear pending turn in addition to history |
| `src/test/java/imagejai/local/PendingTurnTest.java` | NEW | State-machine unit tests |
| `src/test/java/imagejai/local/AssistantReplyTest.java` | NEW (or MODIFY existing) | `clarifying(...)` factory test |

## Implementation sketch

```java
// PendingTurn.java
public final class PendingTurn {
    public enum Kind { PARAMETER, DISAMBIGUATION }

    private final Kind kind;
    private final String question;
    private final String intentId;                  // PARAMETER only
    private final Map<String, String> filledSlots;  // PARAMETER only
    private final String missingSlot;               // PARAMETER only
    private final String defaultValue;              // PARAMETER only (nullable)
    private final List<RankedPhrase> candidates;    // DISAMBIGUATION only
    private final long createdAtMs;

    public static PendingTurn parameter(String intentId,
                                        Map<String, String> filledSlots,
                                        String missingSlot,
                                        String prompt,
                                        String defaultValue) { ... }

    public static PendingTurn disambiguation(String question,
                                             List<RankedPhrase> candidates) { ... }

    public Kind kind() { return kind; }
    public String question() { return question; }
    // ...accessors as needed
}
```

```java
// AssistantReply.java additions
public static AssistantReply clarifying(String question,
                                        List<RankedPhrase> candidates) {
    AssistantReply r = new AssistantReply(question, null, List.of());
    r.clarifying = true;
    r.candidates = candidates == null
            ? Collections.<RankedPhrase>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(candidates));
    return r;
}

public boolean isClarifying() { return clarifying; }
public List<RankedPhrase> clarificationCandidates() { return candidates; }
```

```java
// LocalAssistant.java — top of handle()
public AssistantReply handle(String input) {
    if (pending != null) {
        Optional<AssistantReply> resolved = tryResolve(pending, input);
        pending = null;                       // always clear
        if (resolved.isPresent()) return resolved.get();
        // else fall through — user typed something new
    }
    // ...existing slash → matcher → router → miss path
}

private Optional<AssistantReply> tryResolve(PendingTurn p, String input) {
    switch (p.kind()) {
        case PARAMETER:       return Optional.empty(); // stub for stage 02
        case DISAMBIGUATION:  return Optional.empty(); // stub for stage 03
        default:              return Optional.empty();
    }
}

public void clearPending() { pending = null; }
```

```java
// ClearSlashCommand.java — add a setter / context hook
public AssistantReply execute(SlashCommandContext context) {
    // existing chat history clear
    if (context.localAssistant() != null) {
        context.localAssistant().clearPending();
    }
    // existing reply
}
```

(Note: `SlashCommandContext` does not currently expose
`LocalAssistant`. If wiring it in is invasive, an alternative is to
have `ClearSlashCommand` return a special reply that
`LocalAssistant` recognises and self-clears for. Pick whichever
keeps the diff smallest; document the choice.)

## Exit gate

1. `mvn -q test` passes.
2. `IntentMatcherBenchmarkTest` accuracy ≥97.4% (no regression
   from the v1 baseline).
3. `PendingTurnTest` covers: parameter factory builds correctly;
   disambiguation factory builds correctly; resolution stub returns
   `Optional.empty()` for both kinds; pending turn drops on any
   input that doesn't resolve.
4. `AssistantReplyTest` confirms `clarifying(...)` sets
   `isClarifying() == true` and round-trips the candidate list.
5. `/clear` clears the pending turn (write a test that parks a
   pending turn manually via a test helper, calls `/clear`, then
   verifies `pending` is empty).
6. Existing `LocalAssistant.handle()` behaviour unchanged when
   `pending` is null (verify `localAssistantMissRecordsFrictionLogEntry`
   in `IntentMatcherBenchmarkTest` still passes).

## Known risks

- **Where to store `pending`.** A field on `LocalAssistant` is the
  simplest place, but `LocalAssistant` is owned per `ChatView` —
  so the pending turn is tied to the chat session, which is what
  we want. Don't make it static.
- **Thread safety.** `ChatView.sendMessage()` runs on the EDT;
  `LocalAssistant.handle()` is currently called synchronously from
  there (per `ChatView.java:839`). Single-threaded access today —
  use a plain field. If the call ever moves off-EDT, revisit.
- **`SlashCommandContext` plumbing.** Adding a `localAssistant()`
  getter is the cleanest fix but ripples through every slash
  command's context construction. The alternative — a sentinel
  reply — keeps the diff small. Either is fine; pick the one
  that touches fewer files.
- **Don't introduce a `Settings` flag** for the pending turn yet.
  No knob is needed at this stage; defer until a later stage finds
  a real reason to make it configurable.
