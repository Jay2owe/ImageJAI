# Stage 02 — Chat-reply parameter prompting

## Why this stage exists

v1's `IntentMatcher.extractSlots()` falls back to hard-coded
defaults whenever a slot is missing. That's fine for "subtract
background" (default radius 50 is a sensible starting point) but
wrong for "switch to channel" with no number — the assistant
shouldn't pick channel 1 silently. v2 introduces *required* slots:
when one's missing, the assistant asks via the stage-01 pending
turn slot and applies the user's next message.

## Prerequisites

- Stage 01 (`PendingTurn`, `clearPending()`, top-of-handle check).

## Read first

- `docs/local_assistant_v2/00_overview.md`
- `docs/local_assistant_v2/PLAN.md` §6 ("Stage 02 — Chat-reply
  parameter prompting"), §3 ("Verified current code")
- `docs/local_assistant_v2/BRAINSTORM.md` §3
- `src/main/java/imagejai/local/Intent.java` — current
  3-method interface
- `src/main/java/imagejai/local/IntentMatcher.java`
  lines 151-298 — slot extraction (do not modify; just observe
  what slots exist)
- `src/main/java/imagejai/local/intents/control/ImageControlIntents.java`
  — find `image.switch_channel`, `image.jump_slice`,
  `image.jump_frame`. These are the three intents that need
  required slots.
- `src/main/java/imagejai/local/intents/analysis/PreprocessingIntents.java`
  — these intents stay default-driven; do *not* add required slots
  here. Add suggested slots (advisory only).

## Scope

- New `SlotSpec.java` record: `(String name, String prompt,
  String defaultValue)`. `defaultValue` is nullable.
- Modify `Intent.java` to add two default methods:
  ```java
  default List<SlotSpec> requiredSlots()  { return List.of(); }
  default List<SlotSpec> suggestedSlots() { return List.of(); }
  ```
  Default returns mean existing intents need no change.
- Override `requiredSlots()` on:
  - `image.switch_channel` → `channel`
  - `image.jump_slice` → `slice`
  - `image.jump_frame` → `frame`
- Modify `LocalAssistant.handle()`: after `IntentMatcher.match()`
  succeeds, compute missing required slots; if any are missing,
  park a `PendingTurn.parameter(...)` and return a clarification
  reply.
- Implement `tryResolve` for `Kind.PARAMETER` in `LocalAssistant`:
  parse the user's input as the missing slot's value (numeric for
  these three intents); execute the held intent on success; drop
  the pending turn on parse failure (returns `Optional.empty()`,
  causing fall-through to fresh matching).
- Blank input on a parameter prompt accepts the default (when one
  exists). For the three required slots above, no default exists,
  so blank input drops the pending turn.

## Out of scope

- Suggested slots driving prompts. Suggested slots stay silent;
  they're declared so `/help` can mention them but never trigger a
  prompt. Stage 06 (`/improve`) might surface suggested slots in a
  later iteration; not here.
- Multi-slot prompts in one message. Ask one at a time.
- Disambiguation chips. Stage 03.
- Refactoring `IntentMatcher.extractSlots()`. The hard-coded slot
  table stays as is.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/SlotSpec.java` | NEW | `(name, prompt, defaultValue)` record |
| `src/main/java/imagejai/local/Intent.java` | MODIFY | `requiredSlots()` + `suggestedSlots()` default methods |
| `src/main/java/imagejai/local/intents/control/ImageControlIntents.java` | MODIFY | Override `requiredSlots()` on 3 intents |
| `src/main/java/imagejai/local/LocalAssistant.java` | MODIFY | Parameter-prompt insertion + parameter resolution |
| `src/test/java/imagejai/local/ParameterPromptingTest.java` | NEW | The two-turn flow |

## Implementation sketch

```java
// SlotSpec.java
public record SlotSpec(String name, String prompt, String defaultValue) {
    public boolean hasDefault() { return defaultValue != null; }
}
```

```java
// Intent.java additions
public interface Intent {
    String id();
    String description();
    AssistantReply execute(Map<String, String> slots, FijiBridge fiji);
    default List<SlotSpec> requiredSlots()  { return Collections.emptyList(); }
    default List<SlotSpec> suggestedSlots() { return Collections.emptyList(); }
}
```

```java
// ImageControlIntents.java — only switch_channel shown
class SwitchChannelIntent extends AbstractControlIntent {
    public String id() { return "image.switch_channel"; }
    public List<SlotSpec> requiredSlots() {
        return List.of(new SlotSpec("channel", "channel number", null));
    }
    // execute() unchanged
}
```

```java
// LocalAssistant.handle() — after Tier 1/2 hit
Intent intent = library.byId(matched.get().intentId());
List<SlotSpec> missing = computeMissing(intent.requiredSlots(),
                                        matched.get().slots());
if (!missing.isEmpty()) {
    SlotSpec first = missing.get(0);
    pending = PendingTurn.parameter(
            intent.id(),
            matched.get().slots(),
            first.name(),
            first.prompt(),
            first.defaultValue());
    String prompt = "What " + first.prompt() + "? "
            + (first.hasDefault()
               ? "Default: " + first.defaultValue() + ". "
               : "")
            + "(or type a new request to cancel)";
    return AssistantReply.text(prompt);
}
return intent.execute(matched.get().slots(), fiji);

private List<SlotSpec> computeMissing(List<SlotSpec> required,
                                      Map<String, String> filled) {
    List<SlotSpec> missing = new ArrayList<>();
    for (SlotSpec spec : required) {
        if (!filled.containsKey(spec.name())) missing.add(spec);
    }
    return missing;
}
```

```java
// LocalAssistant.tryResolve — PARAMETER branch
case PARAMETER:
    String value = input.trim();
    if (value.isEmpty()) {
        if (!p.hasDefault()) return Optional.empty();
        value = p.defaultValue();
    }
    if (!isValueShape(value)) return Optional.empty();
    Map<String, String> slots = new HashMap<>(p.filledSlots());
    slots.put(p.missingSlot(), value);
    Intent held = library.byId(p.intentId());
    return Optional.of(held.execute(slots, fiji));
```

`isValueShape` is a permissive check: numeric for the three v2
required slots (regex `^-?\d+(\.\d+)?$`). If the user types a
sentence ("close all"), it returns false and the pending turn
drops, falling through to fresh matching.

## Exit gate

1. `mvn -q test` passes.
2. `IntentMatcherBenchmarkTest` accuracy ≥97.4% (no regression).
3. `ParameterPromptingTest` covers:
   - "switch to channel" → reply contains "channel number"; pending
     turn parked.
   - Following with "2" → executes `image.switch_channel` with
     `channel=2`.
   - "switch to channel" → "close all" drops the pending turn;
     `close all` runs as a fresh request.
   - "switch to channel 2" (with the number inline) → executes
     immediately, no prompt.
   - "subtract background" → executes immediately with default
     radius (radius is *suggested*, not required).
4. Manual smoke: open Fiji with an image, switch to Local
   Assistant, type "switch to channel" — see prompt; type "2" —
   active channel changes to 2.
5. `/help` lists at least one suggested slot in the description for
   the affected intents (verify by inspecting `HelpSlashCommand`'s
   output — adding suggested-slot mention is optional bonus polish,
   not a gate).

## Known risks

- **Slot extraction overlap.** `IntentMatcher.extractSlots()`
  already extracts `channel`/`slice`/`frame` from inline numbers
  ("switch to channel 2"). When the number is present, the slot is
  filled and `requiredSlots()` finds nothing missing — no prompt
  fires. When it's absent, the slot is missing and we prompt. This
  is the desired behaviour; don't fight it.
- **Numeric parse vs intent rewrite.** A user replying "channel 3"
  to "what channel number?" passes `isValueShape("channel 3") ==
  false`, so the pending turn drops. That's a usability loss. Two
  options:
  - Strip leading words: `value.replaceAll("^[a-z ]+", "").trim()`.
  - Document "just type the number" in the prompt.
  Pick the strip-leading-words option; it's a one-liner that catches
  the common case.
- **Multiple required slots.** None of v2's three required-slot
  intents have more than one slot. The code should still handle
  the case (loop through `missing`, prompt one at a time, re-park
  after each resolves) — write a test for it even though no real
  intent triggers it today, to keep stage 06 future-safe.
- **Don't add required slots to preprocessing intents.** Defaults
  for radius/sigma/prominence work; forcing prompts annoys users.
  This is a design decision from BRAINSTORM §3 — uphold it.
