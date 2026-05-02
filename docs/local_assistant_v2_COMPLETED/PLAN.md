# Local Assistant v2 Plan

## 1. Goal

Make Local Assistant *conversational* without making it *non-
deterministic*. Add a one-turn pending-question state machine, use
it to ask for missing parameters and disambiguate borderline
matches, give pronouns ("do that again") something to bind to,
persist friction-log misses to disk, and give the developer a
controlled flow ("`/improve`") to turn miss patterns into
phrasebook updates.

The shipped plugin still makes zero outbound LLM calls. The only
LLM in the loop is `tools/phrasebook_build.py`, run by the
developer at dev time, exactly as in v1.

The 97.4% top-1 benchmark accuracy v1 reached is a floor v2 must
not regress. The 3 known benchmark misses ("how many frames",
"what can you do", "commands") should resolve via clarification
chips rather than silent miss.

## 2. Why v2 exists

v1 shipped 10 stages and explicitly punted on six items in
`docs/local_assistant_COMPLETED/PLAN.md` §13 ("Phase D — ongoing
polish") and §14 ("Open Questions"):

- Disambiguation chips on submit
- Conversational memory
- `FrictionLog` JSONL persistence
- "Improve from chat history" flow
- Multi-step chained intents
- Chat-reply parameter prompting (called out in §14.3)

A separate design conversation added a *pending-turn state
machine* — a single foundational piece that the parameter prompt
and disambiguation paths both depend on. v2 picks up the deferred
items, adds the pending-turn state machine, and explicitly punts
chaining to v3.

## 3. Verified current code (do not contradict)

Confirmed by reading the code at commit `ff8206b`:

- `LocalAssistant.handle()` is currently stateless — see
  `src/main/java/imagejai/local/LocalAssistant.java:82`.
  It dispatches slash → matcher → router → miss in that order. The
  `PendingTurn` slot inserts at the *very top* of `handle()`,
  before the slash check (so that an in-flight clarification
  doesn't get hijacked by `/help`).
- `AssistantReply` has only `text(...)` and `withMacro(...)`
  factories (`AssistantReply.java:23-29`). Stage 01 adds
  `clarifying(...)`.
- `IntentMatcher.match()` returns `Optional<MatchedIntent>` with
  intent ID + slots (`IntentMatcher.java:41-56`). Slot extraction
  is hard-coded per intent in `extractSlots()` (lines 151-194).
  Stage 02 doesn't touch slot extraction; it adds a check on the
  *result* against `Intent.requiredSlots()` and parks the intent
  in `PendingTurn` if anything's missing.
- `IntentMatcher.topK(input, k)` already returns ranked phrases
  (`IntentMatcher.java:58-96`). Stage 03 adds a margin-aware
  helper (`match2(input, margin)` returning a `Match2Result` with
  best, runner-up, margin) — does not modify `match()` or
  `topK()`.
- `FrictionLog.record()` is in-memory only
  (`FrictionLog.java:82-101`). The bounded ring buffer of 100
  entries with 10-minute window stays. Stage 05 adds an async
  journal that writes to `~/.imagej-ai/friction.jsonl`.
- `Intent` interface is minimal (3 methods, `Intent.java`). Stage
  02 adds `default List<SlotSpec> requiredSlots()` and
  `default List<SlotSpec> suggestedSlots()`. Default returns empty
  list, so existing intents need no change.
- `IntentLibrary` registers built-ins, slash aliases, and menu
  imports (`IntentLibrary.java:50-73`). v2 doesn't change the
  registration flow.
- `ChatView.sendMessage()` already calls `localAssistant.handle()`
  on local-assistant-active (`ChatView.java:835-850`). The reply
  is rendered as one or two `appendMessage` calls. Stage 03
  extends this to render disambiguation chips below the
  assistant's clarification message.
- `tests/benchmark/biologist_phrasings.jsonl` has 115 rows. Live
  accuracy ≥97.4% per the benchmark test.
  `IntentMatcherBenchmarkTest` asserts ≥80%; v2 must not regress
  the live number.

## 4. Architecture additions

```
imagejai.local
├── LocalAssistant.java          MODIFY  — PendingTurn slot, ConversationContext, slot enforcement
├── PendingTurn.java             NEW      — stage 01
├── ConversationContext.java     NEW      — stage 04
├── PronounResolver.java         NEW      — stage 04
├── AssistantReply.java          MODIFY  — clarifying(question, candidates) factory
├── Intent.java                  MODIFY  — default requiredSlots(), suggestedSlots()
├── SlotSpec.java                NEW      — (name, prompt, defaultValue) record
├── IntentMatcher.java           MODIFY  — match2() helper for margin disambiguation
├── Match2Result.java            NEW      — (best, runnerUp, margin)
└── slash/ImproveSlashCommand.java NEW    — stage 06

imagejai.engine
├── FrictionLog.java             MODIFY  — wires journal append into record()
└── FrictionLogJournal.java      NEW      — async JSONL writer with rotation

imagejai.ui
└── ChatView.java                MODIFY  — clarification chip surface (post-submit)
```

No new external dependencies. No new packages. No restructuring.

## 5. Stage 01 — Pending-turn state machine

### Why

Both parameter prompting (stage 02) and disambiguation chips
(stage 03) need the assistant to *hold a question across one
turn*. Without this, both stages would invent their own state
field and diverge.

### Shape

```java
class PendingTurn {
  enum Kind { PARAMETER, DISAMBIGUATION }
  final Kind kind;
  final String question;             // shown to user
  final String intentId;             // for PARAMETER: held intent
  final Map<String,String> slots;    // for PARAMETER: filled so far
  final String missingSlot;          // for PARAMETER: what we're asking
  final String defaultValue;         // for PARAMETER: shown as "default 50"
  final List<RankedPhrase> candidates; // for DISAMBIGUATION: chip set
  final long createdAtMs;
}
```

`LocalAssistant` holds `Optional<PendingTurn> pending`. At the top
of `handle(input)`:

```java
public AssistantReply handle(String input) {
  if (pending.isPresent()) {
    PendingTurn p = pending.get();
    Optional<AssistantReply> resolution = tryResolve(p, input);
    if (resolution.isPresent()) {
      pending = Optional.empty();
      return resolution.get();
    }
    // user typed something else — drop the pending turn, fall through
    pending = Optional.empty();
  }
  // ...existing logic
}
```

`tryResolve` returns:
- For `PARAMETER`: parse the input as the missing slot value (or
  blank → default), build the full slots map, and execute the held
  intent. If the input clearly isn't a value (e.g. multi-word
  English), return `Optional.empty()` so the pending turn drops.
- For `DISAMBIGUATION`: if the input matches one of the
  `candidates` phrases (chip click puts the chip text into the
  input), execute the corresponding intent. Else
  `Optional.empty()`.

### `AssistantReply.clarifying`

```java
public static AssistantReply clarifying(
    String question, List<RankedPhrase> candidates) { ... }

public boolean isClarifying() { ... }
public List<RankedPhrase> clarificationCandidates() { ... }
```

`ChatView` checks `reply.isClarifying()` after handle returns and
renders the chip surface beneath the assistant message. Click on a
chip submits its phrase as the next user input.

### Sunset

`pending` is cleared by:
- successful resolution
- a new free-form input that doesn't resolve
- `/clear` slash command
- agent switch (handled by `ChatView` rebuilding `LocalAssistant`)

No timeout — the slot is in-memory and tied to the chat session
lifetime.

## 6. Stage 02 — Chat-reply parameter prompting

### Required vs suggested slots

```java
public interface Intent {
  String id();
  String description();
  AssistantReply execute(Map<String,String> slots, FijiBridge fiji);
  default List<SlotSpec> requiredSlots()  { return List.of(); }
  default List<SlotSpec> suggestedSlots() { return List.of(); }
}

public record SlotSpec(String name, String prompt, String defaultValue) { }
```

For v1's intent library, the *truly* required slots are limited:

| Intent | Required slot | Why |
|---|---|---|
| `image.switch_channel` | `channel` | No sensible default (which channel?) |
| `image.jump_slice` | `slice` | Same |
| `image.jump_frame` | `frame` | Same |

Everything else (radius, sigma, prominence, etc.) has a working
default. Those are *suggested*, not required, and stay silent.

### Flow

After `IntentMatcher.match(input)` returns a hit:

```java
Intent intent = library.byId(matchedIntent.intentId());
List<SlotSpec> missing = computeMissing(intent.requiredSlots(),
                                        matchedIntent.slots());
if (!missing.isEmpty()) {
  SlotSpec first = missing.get(0);
  pending = Optional.of(PendingTurn.parameter(
      intent.id(), matchedIntent.slots(), first));
  return AssistantReply.text(
      "What " + first.prompt() + "? "
      + (first.defaultValue() == null ? "" : "Default: " + first.defaultValue() + ". ")
      + "(or type a new request to cancel)");
}
return intent.execute(matchedIntent.slots(), fiji);
```

Resolution: when the next user message arrives, `tryResolve` parses
it. Numeric prompts run `Integer.parseInt` / `Double.parseDouble`;
parse failure means "not a value" and the pending turn drops.

### Multiple missing slots

Ask one at a time. After the first resolves, if more required slots
are still missing, re-park with the next one. The user perceives a
short conversation.

## 7. Stage 03 — Disambiguation chips

### `match2`

```java
public Match2Result match2(String input, double margin) {
  List<RankedPhrase> top = topK(input, 2);
  if (top.isEmpty()) return Match2Result.miss();
  RankedPhrase best = top.get(0);
  if (top.size() == 1) return Match2Result.confident(best);
  RankedPhrase runner = top.get(1);
  double gap = best.score() - runner.score();
  return gap < margin
      ? Match2Result.ambiguous(best, runner, gap)
      : Match2Result.confident(best);
}
```

The margin defaults to `0.05` and lives in `Settings`
(`localAssistantDisambiguationMargin`).

### `LocalAssistant.handle()` insertion point

Between Tier 1/Tier 2 success and `Intent.execute(...)`:

```java
Optional<MatchedIntent> matched = matcher.match(input);
if (matched.isPresent()) {
  Match2Result m2 = matcher.match2(input, settings.getMargin());
  if (m2.isAmbiguous()) {
    pending = Optional.of(PendingTurn.disambiguation(
        "Did you mean:", List.of(m2.best(), m2.runnerUp())));
    return AssistantReply.clarifying("Did you mean:",
                                     List.of(m2.best(), m2.runnerUp()));
  }
  // ...existing execute path
}
```

### Why this resolves the 3 benchmark misses

- "how many frames" matches `image.stack_counts` and
  `image.position` within margin → chips show both, user picks.
- "what can you do" matches `slash.help` and `meta.version` within
  margin → chips show both.
- "commands" matches `slash.help` and `diagnostic.list_commands`
  within margin → chips show both.

(Verify these intuitions against the live phrasebook during
implementation. The point is that ambiguous misses become
clarifications instead of silent picks.)

### ChatView wiring

After `handle()` returns:

```java
AssistantReply reply = localAssistant.handle(text);
if (reply.text().length() > 0) appendMessage("assistant", reply.text());
if (reply.isClarifying()) {
  // Render a post-submit chip row beneath the assistant message
  renderClarificationChips(reply.clarificationCandidates());
}
```

Click on a chip → its phrase becomes the next user input → resolves
the pending turn → executes the intent.

## 8. Stage 04 — Conversational memory

### `ConversationContext`

```java
public class ConversationContext {
  private static final long IDLE_SUNSET_MS = 10 * 60 * 1000;
  private Optional<String> lastImageTitle = Optional.empty();
  private Optional<String> lastIntentId = Optional.empty();
  private Optional<Map<String,String>> lastSlots = Optional.empty();
  private Optional<Integer> lastRoiIndex = Optional.empty();
  private long lastUpdated = 0;

  public void recordIntentRun(String intentId, Map<String,String> slots) { ... }
  public void recordRoiSelection(int index) { ... }
  public boolean isStale() { ... }
  public void clear() { ... }
}
```

`LocalAssistant` updates the context after every successful intent
execution. ROI selection is captured opportunistically — intents
that act on ROIs call `context.recordRoiSelection(index)`.

### `PronounResolver`

```java
public class PronounResolver {
  Optional<Rewrite> resolve(String input, ConversationContext ctx) {
    if (ctx.isStale()) return Optional.empty();
    String lower = input.toLowerCase().trim();
    if (lower.matches("^(do that again|repeat that)\\b.*")) {
      return ctx.lastIntent().map(...);   // re-execute with same slots
    }
    if (lower.matches("^measure (it|that)\\b.*")) { ... }
    if (lower.matches("^the next image\\b.*")) { ... }
    if (lower.matches("^save (those|the) results\\b.*")) { ... }
    return Optional.empty();
  }
  public record Rewrite(String intentId, Map<String,String> slots) { }
}
```

### Insertion point

```java
public AssistantReply handle(String input) {
  if (pending.isPresent()) { ... }                         // stage 01

  Optional<Rewrite> pronoun = pronouns.resolve(input, ctx);  // stage 04
  if (pronoun.isPresent()) {
    return library.byId(pronoun.get().intentId())
        .execute(pronoun.get().slots(), fiji);
  }

  if (SlashCommandRegistry.isSlashInput(input)) { ... }    // existing
  // matcher → router → miss as before
}
```

### Sunset rule

`isStale()` returns true when `lastUpdated + IDLE_SUNSET_MS <
System.currentTimeMillis()`. `clear()` is called by `/clear` slash
command (already wired to `ChatHistoryController`).

## 9. Stage 05 — FrictionLog JSONL persistence

### `FrictionLogJournal`

```java
public class FrictionLogJournal {
  static final long ROTATE_BYTES = 10L * 1024 * 1024;
  static final int MAX_GENERATIONS = 5;
  static final Path ROOT = Paths.get(System.getProperty("user.home"),
                                     ".imagej-ai");

  private final ExecutorService writer = Executors.newSingleThreadExecutor(...);
  private final Gson gson = new Gson();
  private final Path file = ROOT.resolve("friction.jsonl");

  public void append(FrictionLog.FailureEntry e) {
    writer.execute(() -> writeLine(e));
  }

  private void writeLine(FrictionLog.FailureEntry e) {
    try {
      Files.createDirectories(ROOT);
      if (Files.exists(file) && Files.size(file) >= ROTATE_BYTES) rotate();
      try (BufferedWriter w = Files.newBufferedWriter(file,
              StandardCharsets.UTF_8, StandardOpenOption.CREATE,
              StandardOpenOption.APPEND)) {
        w.write(gson.toJson(toMap(e)));
        w.write('\n');
      }
    } catch (IOException ignored) {
      // disk full / permission denied — fall back to in-memory only
    }
  }

  private void rotate() throws IOException {
    for (int i = MAX_GENERATIONS - 1; i >= 1; i--) { ... }
    Files.move(file, ROOT.resolve("friction.jsonl.1"),
               StandardCopyOption.REPLACE_EXISTING);
  }
}
```

### Hooking into `FrictionLog`

```java
public synchronized void record(String agentId, String command,
                                String argsSummary, String error) {
  // ...existing ring buffer write
  if (journal != null) journal.append(e);   // NEW
}
```

`journal` is set via a setter so the constructor stays back-
compatible with code that constructs `FrictionLog` without a
journal (the unit tests do this).

### Reads

The TCP `get_friction_log` command stays on the ring buffer
(fast). Stage 06's `/improve` reads the JSONL file directly via
`FrictionLogJournal.streamEntries()`.

## 10. Stage 06 — Improve from chat history

### `/improve` flow (interactive)

```
> /improve
Reading ~/.imagej-ai/friction.jsonl ... 1284 misses found.

Top 5 misses (by count):
  1. "how many frames" (47×, last seen 2 days ago)
     Closest existing intent: image.stack_counts (score 0.93)
     [a] alias to image.stack_counts  [n] new intent  [s] skip  [q] quit

> a
Aliased "how many frames" to image.stack_counts.

  2. "potato salad" (12×, last seen yesterday)
     Closest existing intent: (no match above 0.7)
     [n] new intent  [s] skip  [q] quit

> s
Skipped.

  ...

Diff against tools/intents.yaml:

@@ image.stack_counts @@
+  - "how many frames"

Apply? [y/n] y
Updated tools/intents.yaml.
To regenerate the phrasebook, run:
  python tools/phrasebook_build.py
  mvn package
```

### `ImproveSlashCommand`

```java
public class ImproveSlashCommand implements SlashCommand {
  public String name() { return "improve"; }
  public String intentId() { return "slash.improve"; }
  public AssistantReply execute(SlashCommandContext ctx) {
    List<MissBucket> buckets = ImproveAnalysis.fromJournal();
    // Render top 20 buckets; bucket-by-bucket interaction uses the
    // PendingTurn machinery from stage 01 (each prompt parks a
    // PARAMETER pending turn keyed by bucket index).
    // ...
  }
}
```

The interactive loop *uses* `PendingTurn` — each `[a]/[n]/[s]/[q]`
prompt is a parameter pending turn. This is why stage 06 depends on
stage 01 transitively (through stage 05 only for the journal read,
but the UX threads through 01).

### YAML mutation

`tools/intents.yaml` is a flat list of `id/description/seeds`
blocks. Aliasing means appending to `seeds:` for an existing block;
new intent means appending a new block (with a description the user
types in chat). Use SnakeYAML if it's already on the classpath, or
hand-write the mutation — the file is small enough that string-
based append is acceptable.

### No automatic LLM

`/improve` does not run `tools/phrasebook_build.py`. The user-
facing message after accept is explicit: *"To regenerate the
phrasebook, run `python tools/phrasebook_build.py` and rebuild the
plugin."* This keeps the "no runtime LLM" rule intact.

## 11. Stage 07 — Multi-step chained intents (decision)

### Decision: defer to v3

v2 will not implement multi-step chained intents. The reasons (in
order of weight):

1. **Action language design isn't simple.** "Threshold and
   measure" expands to multiple intents whose data flow is
   non-trivial — the threshold value from step 1 needs to feed
   step 2's "compare against threshold". A flat `[intent, intent]`
   list isn't enough.
2. **Undo semantics.** Users expect chain-level undo, not step-
   level. ImageJ's edit history isn't reliable for synthetic
   chains — a custom undo log over the macros emitted is its own
   sub-project.
3. **Inter-step error handling.** What happens if step 2 fails —
   abort, prompt, or proceed? The right answer differs by chain.
   Without a policy, we ship surprises.
4. **Budget.** v2's other six items already total ~7 days. Adding
   chaining doubles it and risks eclipsing the small wins
   (clarification, persistence, pronoun resolution) that resolve
   real benchmark misses today.

### What v3 needs to design before implementing

- An action language: explicit step boundaries, named outputs,
  expressions for inter-step references.
- A policy for inter-step failure handling (abort by default;
  per-chain override).
- A chain-level undo log layered over `runMacro` calls — possibly
  by wrapping each step in a `setBatchMode`/`run` macro that
  records reversibility tags.
- A test fixture covering at least: success chain, mid-chain
  failure (with abort), mid-chain failure (with continue), undo of
  a 3-step chain.

Stage 07's deliverable is the single decision document. No code.

## 12. Sequencing

```
01 ──┬─► 02
     └─► 03

04   independent
05 ──► 06   (06 reads the journal 05 writes)
07   independent (decision doc only)
```

Recommended execution order: 01, 02, 03, 04, 05, 06, 07. Stages 04
and 05 can swap or interleave; 06 must come after 05; 07 can run
any time but is cheap and good to land early as it formally closes
the chaining question.

## 13. Out of scope (explicit)

- Multi-step chained intents (per stage 07).
- Cross-session conversational memory.
- Auto-regeneration of phrasebook from miss volume.
- MiniLM ONNX semantic tier wiring (download path is in v1; tier
  insertion stays deferred).
- Voice input.
- Any new slash command beyond `/improve`. Specifically NOT adding
  `/think`, `/save-recipe`, `/interrupt`, `/queue`, `/mode` — same
  exclusion as v1.
- LLM-driven intent suggestion. `/improve` is dev-driven; it does
  not call any model.

## 14. Acceptance criteria for v2 as a whole

- `mvn -q test` passes.
- `IntentMatcherBenchmarkTest` top-1 accuracy ≥97.4% on
  `tests/benchmark/biologist_phrasings.jsonl`.
- The 3 known benchmark misses ("how many frames", "what can you
  do", "commands") return clarification chips, not silent miss.
- "subtract background" with no radius prompts "what radius?
  default is 50"; replying "100" runs the subtraction.
- "switch to channel" (no number) prompts "which channel?";
  replying "2" switches.
- After running an intent, "do that again" re-runs it.
- After 11 minutes idle, "do that again" returns the standard
  "I don't recognise" miss (sunset rule).
- After 100+ misses across multiple sessions, restarting Fiji and
  running `/improve` shows the misses (persistence works).
- `/improve` produces a YAML diff against `tools/intents.yaml`;
  accepting writes the file; the user-facing message tells them
  how to regenerate the phrasebook.
- No outbound network calls from the running plugin (verify by
  reading the new code, not by network monitoring — there should
  be zero new HTTP calls).
