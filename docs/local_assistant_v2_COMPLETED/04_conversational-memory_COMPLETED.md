# Stage 04 — Conversational memory

## Why this stage exists

Pronouns and back-references are how humans actually talk. "Do
that again", "measure it", "the next image", "save those results"
are dead-obvious to a person, and right now they're all "I don't
recognise" misses. Stage 04 gives them somewhere to bind: a tiny
in-memory `ConversationContext` that remembers the last image
touched, the last intent run, and the last ROI selection. A
`PronounResolver` runs *before* the matcher to short-circuit
pronoun phrases.

Sunset rule: the context goes stale after 10 minutes of idle.
"Do that again" 30 minutes later probably doesn't mean what it
did, and we don't want surprising re-runs.

## Prerequisites

None. Independent of stages 01-03 — a pronoun rewrite returns a
non-clarifying reply, so it doesn't interact with `PendingTurn`.

## Read first

- `docs/local_assistant_v2/00_overview.md`
- `docs/local_assistant_v2/PLAN.md` §8 ("Stage 04 — Conversational
  memory")
- `docs/local_assistant_v2/BRAINSTORM.md` §5
- `src/main/java/imagejai/local/LocalAssistant.java`
  — find the existing `handle()` flow at line 82
- `src/main/java/imagejai/local/intents/control/RoiResultsIntents.java`
  — these intents touch ROI state. Stage 04 hooks them to record
  ROI index in `ConversationContext`.
- `src/main/java/imagejai/local/intents/analysis/MeasurementIntents.java`
  — find the measure-results-saving intents. "save those results"
  rewrites to one of these.
- `src/main/java/imagejai/local/slash/ClearSlashCommand.java`
  — `/clear` already calls `ChatHistoryController.clear()`; stage
  04 hooks it to also clear the context.

## Scope

- New `ConversationContext.java` with the state shape from PLAN
  §8. In-memory only. Sunset on 10-minute idle.
- New `PronounResolver.java` with four pronoun patterns:
  - `^(do that again|repeat that|run that again)\b` →
    `Rewrite(lastIntentId, lastSlots)`
  - `^measure (it|that)\b` → `Rewrite("measurement.measure_rois",
    {})` if `lastRoiIndex` is set, else fall through
  - `^the next image\b` → `Rewrite("image.next_open_image", {})`
    (a thin wrapper that switches to the next image in
    `WindowManager.getIDList()`; if that intent doesn't already
    exist, add it as a tiny new built-in)
  - `^save (those|the) results\b` → `Rewrite("results.save_csv",
    {})` if a results table is pending
- Hold a `ConversationContext ctx` field on `LocalAssistant`.
  Update it after every successful `Intent.execute()` call:
  `ctx.recordIntentRun(intent.id(), slots)`.
- Run `pronouns.resolve(input, ctx)` *before* the slash dispatch
  in `handle()`, but *after* the pending-turn check from stage
  01.
- Hook `/clear` so it also clears the context.

## Out of scope

- Cross-session persistence. v2 stage 04 is in-memory only.
- Pronoun resolution that *needs* an LLM ("the bright spots",
  "the cells in the corner"). Out of scope by design.
- Surfacing context state in `/info` or `/help`. Optional polish;
  defer.
- Pronoun resolution running *during* a pending parameter prompt.
  Pending turns take priority — if the user says "do that again"
  while a parameter prompt is open, the parameter prompt
  resolution drops it (because "do that again" isn't a numeric
  value), then on the next turn pronoun resolution runs against
  fresh state.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/ConversationContext.java` | NEW | The state object |
| `src/main/java/imagejai/local/PronounResolver.java` | NEW | Pronoun → intent rewrite |
| `src/main/java/imagejai/local/LocalAssistant.java` | MODIFY | Hold `ctx`, run resolver, record on execute |
| `src/main/java/imagejai/local/intents/control/ImageControlIntents.java` | MODIFY (or NEW intent) | Add `image.next_open_image` if missing |
| `src/main/java/imagejai/local/intents/control/RoiResultsIntents.java` | MODIFY | Record ROI selection into ctx |
| `src/main/java/imagejai/local/slash/ClearSlashCommand.java` | MODIFY | Clear context |
| `src/test/java/imagejai/local/ConversationMemoryTest.java` | NEW | Pronoun coverage + sunset rule |

## Implementation sketch

```java
// ConversationContext.java
public class ConversationContext {
    private static final long IDLE_SUNSET_MS = 10L * 60 * 1000;

    private String lastIntentId;
    private Map<String, String> lastSlots = Collections.emptyMap();
    private Integer lastRoiIndex;
    private long lastUpdated;

    public synchronized void recordIntentRun(String intentId,
                                             Map<String, String> slots) {
        this.lastIntentId = intentId;
        this.lastSlots = Collections.unmodifiableMap(new HashMap<>(slots));
        this.lastUpdated = System.currentTimeMillis();
    }

    public synchronized void recordRoiSelection(int index) {
        this.lastRoiIndex = index;
        this.lastUpdated = System.currentTimeMillis();
    }

    public synchronized boolean isStale() {
        return lastUpdated == 0
            || System.currentTimeMillis() - lastUpdated > IDLE_SUNSET_MS;
    }

    public synchronized Optional<String> lastIntentId() {
        return isStale() ? Optional.empty() : Optional.ofNullable(lastIntentId);
    }

    public synchronized Optional<Map<String,String>> lastSlots() {
        return isStale() ? Optional.empty() : Optional.of(lastSlots);
    }

    public synchronized Optional<Integer> lastRoiIndex() {
        return isStale() ? Optional.empty() : Optional.ofNullable(lastRoiIndex);
    }

    public synchronized void clear() {
        lastIntentId = null;
        lastSlots = Collections.emptyMap();
        lastRoiIndex = null;
        lastUpdated = 0;
    }
}
```

```java
// PronounResolver.java
public class PronounResolver {
    public record Rewrite(String intentId, Map<String, String> slots) { }

    public Optional<Rewrite> resolve(String input, ConversationContext ctx) {
        if (ctx.isStale()) return Optional.empty();
        String s = input.toLowerCase(Locale.ROOT).trim();

        if (s.matches("^(do that again|repeat that|run that again)\\b.*")) {
            Optional<String> id = ctx.lastIntentId();
            Optional<Map<String,String>> slots = ctx.lastSlots();
            if (id.isPresent() && slots.isPresent()) {
                return Optional.of(new Rewrite(id.get(), slots.get()));
            }
        }

        if (s.matches("^measure (it|that)\\b.*") && ctx.lastRoiIndex().isPresent()) {
            return Optional.of(new Rewrite("measurement.measure_rois", Map.of()));
        }

        if (s.matches("^the next image\\b.*")) {
            return Optional.of(new Rewrite("image.next_open_image", Map.of()));
        }

        if (s.matches("^save (those|the) results\\b.*")) {
            return Optional.of(new Rewrite("results.save_csv", Map.of()));
        }

        return Optional.empty();
    }
}
```

```java
// LocalAssistant.handle() — insertion point
public AssistantReply handle(String input) {
    if (pending != null) { ... }                                  // stage 01

    Optional<PronounResolver.Rewrite> rewrite = pronouns.resolve(input, ctx);
    if (rewrite.isPresent()) {
        Intent target = library.byId(rewrite.get().intentId());
        if (target != null) {
            AssistantReply reply = target.execute(rewrite.get().slots(), fiji);
            ctx.recordIntentRun(target.id(), rewrite.get().slots());
            return reply;
        }
    }

    // ...existing slash → matcher → router → miss
    // After every successful Intent.execute(...), call:
    //   ctx.recordIntentRun(intent.id(), slots);
}
```

## Exit gate

1. `mvn -q test` passes.
2. `IntentMatcherBenchmarkTest` accuracy ≥97.4% (no regression —
   pronoun resolution runs only when `lastIntentId` is set, so the
   benchmark, which starts cold, sees the matcher unchanged).
3. `ConversationMemoryTest` covers:
   - Run `pixel size` → context records `image.pixel_size`.
   - Then "do that again" → re-runs `image.pixel_size`.
   - Then `/clear` → context cleared; "do that again" returns
     standard miss.
   - Manually advance the clock (test seam) past 10 minutes →
     "do that again" returns miss.
   - "the next image" with no images open → degrades gracefully
     (the `image.next_open_image` intent itself returns "no
     images open").
   - "measure it" with no ROI selected → falls through to fresh
     matching (the resolver returns `Optional.empty()` because
     `lastRoiIndex` is empty).
4. Manual smoke: open Fiji with two images, switch to Local
   Assistant, run "list open images", run "pixel size", then run
   "do that again" — sees pixel-size again. Then run "the next
   image" — switches to the other image.
5. Pronoun resolution does NOT fire when input is a slash command
   ("`/help`" → not a pronoun; goes through slash path).
6. The sunset rule is testable: inject a clock seam into
   `ConversationContext` (constructor-arg `LongSupplier nowMs`,
   default `System::currentTimeMillis`) so tests can advance time
   without `Thread.sleep`.

## Known risks

- **`image.next_open_image` may not exist as an intent today.**
  Verify in `ImageControlIntents.java` — if absent, add it as a
  small new built-in: cycle `WindowManager.getIDList()` to find
  the next ID after the current image and call
  `WindowManager.setCurrentWindow(...)`. Add a phrasebook entry in
  `tools/intents.yaml`.
- **`results.save_csv` may not exist as an intent today.** Same
  verification — find the closest existing intent (likely under
  `RoiResultsIntents` or `MeasurementIntents`) and either reuse
  its ID or add a new wrapper intent. Don't fail silently; if
  neither pronoun pattern resolves to an existing intent, the
  pronoun fires and the intent lookup fails. Add a guard
  (`target == null` falls through to fresh matching) so a missing
  intent is forgivable.
- **Pronoun false positives.** "Save those results from the
  CTCF measurement" matches "^save (those|the) results\\b" —
  good. "The next image processing step is..." matches "^the
  next image\\b" — bad. Anchor the regex with a word boundary or
  end-of-string check (`^the next image\\s*[.!?]?\\s*$`) to keep
  the false-positive rate low. Tune off the benchmark.
- **Context update placement.** Update the context only on
  *successful* `Intent.execute()`. If the intent throws or
  returns "no image open", don't record it — "do that again"
  shouldn't replay an error.
- **Thread safety.** `ConversationContext` is `synchronized` per
  method. Cheap enough for the small number of fields and the
  per-message access pattern.
- **Don't persist the context.** v2 is in-memory. If a future
  agent thinks it'd be a good idea to write it to disk, push back
  — the BRAINSTORM §5 "scope discipline" note exists exactly to
  prevent that.
