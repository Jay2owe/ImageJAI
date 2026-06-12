# Local Assistant v2 — Brainstorm and Decisions

Synthesis of design options for the v2 work — pending-turn state
machine, parameter prompting, disambiguation chips, conversational
memory, friction-log persistence, "improve from history" flow,
multi-step chaining decision. Locked v1 decisions are inherited; the
sections below cover only what v2 adds. The decisions in §6 are the
source of truth that drives `PLAN.md` and the numbered stages.

## 1. Inherited from v1 (not reopened)

These are settled. v2 does not revisit them.

- **No runtime LLM.** Phrasebook generated at dev time, shipped
  baked into the JAR. Zero outbound calls from the running plugin.
- **Two-tier matcher only.** Tier 1 hash + Tier 2 Jaro-Winkler
  (threshold 0.90). MiniLM is opt-in download, not a default.
- **Layer onto `IntentRouter`** for user-taught intents. Don't
  rebuild it.
- **`FrictionLog` is the place to record misses.** v1 wires misses
  in-memory; v2 makes the persistence layer real.
- **Slash > phrasebook precedence** in `LocalAssistant.handle()`.
- **House rules** (CLAUDE.md, agent/CLAUDE.md) are non-negotiable.

## 2. Pending-turn state machine

### Problem

v1 is stateless across user turns. Every `LocalAssistant.handle()`
treats the input in isolation. That breaks two things v2 needs:

- *Missing-parameter prompting* — "subtract background" with no
  radius needs to ask "what radius? default is 50" and apply the
  user's next message ("100") to the held intent.
- *Disambiguation* — when "how many frames" matches both
  `image.stack_counts` and `image.position` within margin, the
  assistant needs to ask "which one?" and resolve on the next
  click/message.

Both flows want the same primitive: hold one pending question, take
the next user input as either an answer or an abandonment.

### Options weighed

| Approach | Implementation | Verdict |
|---|---|---|
| **Single-slot `PendingTurn` field on `LocalAssistant`** | One nullable field, cleared on resolve or new free-form input | **Picked.** Simple, matches the "single-action v1" constraint we inherited. |
| Stack of pending turns (multi-question dialogs) | List with push/pop semantics | Skip. Encourages conversation trees we don't want; users reset by typing anything. |
| Per-intent state object passed back in subsequent calls | Each intent declares its own state class | Skip. More plumbing; doesn't simplify the two real callers (param prompt + disambiguation). |
| External actor model with FSM library | Akka-style or hand-rolled | Skip. Vastly out of scope. |

### Decision

A single `PendingTurn` slot on `LocalAssistant`. One question at a
time. The next user message either resolves it (numeric answer for
parameters; chip click or matching candidate for disambiguation) or
clears it (anything else starts fresh). The slot is also cleared on
`/clear` and on agent switch.

`AssistantReply` gains a `clarifying(question, candidates)` factory
so reply objects carry the chip data without growing a fourth field
for general use.

### Edge cases

- **User types a brand-new request while a pending turn is open.**
  The pending turn is dropped; the new request is matched fresh.
  This is the "anything resets" rule. Document it in the user-
  visible help so users aren't surprised.
- **User types nonsense.** Same — the pending turn is dropped, the
  matcher returns its usual "I don't recognise" miss.
- **Two pending turns from the same intent.** Don't allow it.
  Stage 02's parameter prompts ask one slot at a time even when
  multiple are missing, and the second prompt only fires after the
  first resolves.

## 3. Parameter prompting (chat-reply path)

### Already shipped in v1 (file dialogs)

`save-as-tiff`/`save-as-png`/`save-as-jpeg` use the OS file dialog
for the filename slot. That path stays.

### v2 adds chat-reply prompting for numerics and text

The intents that already accept slots (`preprocess.subtract_background`
radius, `preprocess.gaussian_blur` sigma, `segmentation.find_maxima`
prominence, etc., per `IntentMatcher.extractSlots()`) currently fall
back to a hard-coded default when the slot is empty. v2 changes this
so missing slots produce a clarification reply instead of silently
running with the default.

### Two design choices

| Question | Option A | Option B | Choice |
|---|---|---|---|
| **Where is "this slot is required" declared?** | Annotation on the `Intent` class | Method on `Intent` returning a slot spec | **B**: explicit `Intent.requiredSlots()` returning a list. Annotations need a processor; this fits the existing tiny interface. |
| **Default-or-prompt?** | Always prompt when slot missing | Prompt only when intent says "required" | **B**: gentle. `subtract_background` with no radius has worked for 30 years with the default; we don't want to suddenly force users to type "50". Make `requiredSlots()` return the truly required ones (e.g. `image.switch_channel` channel number — there's no sensible default). For the rest, the prompt is a *suggestion*: "what radius? default is 50, press enter to accept". |

### Decision

`Intent` interface gains:

```java
default List<SlotSpec> requiredSlots() { return List.of(); }
default List<SlotSpec> suggestedSlots() { return List.of(); }
```

Where `SlotSpec` is `(name, prompt, defaultValue)`. `LocalAssistant`
checks `requiredSlots()` after matching; if any are missing, it
parks the intent in the `PendingTurn` slot and returns a
clarification reply asking for the first missing slot. `defaultValue`
is shown in the prompt; if the user replies blank, the default
applies. Suggested slots are not prompted — they remain
fall-back-to-default — but show up in `/help` so users know they
exist.

## 4. Disambiguation chips on submit

### v1 behaviour to keep

Autocomplete chip row under the input — Tier 2 already drives this
on every keystroke. Don't change it. Stage 03 is about the
*post-submit* path.

### Margin computation

`IntentMatcher` already computes ranked phrases; the runner-up is
just the second element of `topK(input, 2)`. The decision is when to
clarify vs when to silently pick the top.

| Threshold | Effect |
|---|---|
| Top-1 ≥ 0.99 | Always pick. Effectively a Tier-1 hit; no chips. |
| Top-1 ≥ 0.90 AND top-1 - top-2 ≥ 0.05 | Pick top-1. (Inherited v1 behaviour.) |
| Top-1 ≥ 0.90 AND top-1 - top-2 < 0.05 | Show clarification chips. **(NEW in stage 03.)** |
| Top-1 < 0.90 | Miss. Show "did you mean" chips (existing v1 path). |

The 0.05 margin is configurable via `Settings`. Tune off the
benchmark — the 3 known misses ("how many frames", "what can you
do", "commands") should resolve via clarification rather than a
silent wrong pick.

### Chip UX

Reuse the existing `AutocompleteChipRow` for visual consistency, but
wire it as a *post-submit* render rather than the live keystroke
path. The chips render under the most recent assistant message
(rather than under the input), so they're attached to the question.
Click a chip → that phrase becomes the next user input → resolves
the pending turn.

## 5. Conversational memory

### Scope discipline

Easy to design for too much. The lab use cases that motivate this
are narrow:

- "do that again" → re-run the last intent with the same slots
- "measure it" → run a measurement intent against the last ROI /
  last threshold result
- "the next image" → switch to the next open image
- "save those results" → save the last measurement table

That's four pronoun classes, and they all bind to state we already
have or can capture cheaply.

### Storage shape

```java
class ConversationContext {
  Optional<String> lastImageTitle;   // WindowManager id resolved on read
  Optional<String> lastIntentId;
  Optional<Map<String,String>> lastSlots;
  Optional<Integer> lastRoiIndex;    // RoiManager index, validated on read
  Instant lastUpdated;
}
```

In-memory only. Cleared on `/clear`. Sunset after 10 minutes of
idle (anti-stale-data — if you walked away and came back, "do that
again" probably doesn't mean what it did 30 minutes ago).

### Where pronouns dispatch

A new `PronounResolver` runs *before* `IntentMatcher.match()` in
`LocalAssistant.handle()`. If the input is a pronoun pattern ("do
that again", "measure it", "the next image", "save those results")
AND the context has the relevant binding, it rewrites the input to
the resolved intent. Falls through if context is empty.

Why before the matcher: pronoun resolution is short-circuit. If we
let the matcher try first, "do that again" might fuzzy-match
something silly.

## 6. FrictionLog JSONL persistence

### Constraints

- The hot path (`record()`) must stay O(1) and non-blocking. Don't
  block the matcher on a disk write.
- Must survive crashes without corrupting the file (append-only,
  flush-per-line is fine).
- Must rotate at a sensible size — users will hit `/improve` after
  weeks of use, but not after years; cap the live file at ~10 MB.
- v1 keeps the in-memory ring buffer for the existing
  `get_friction_log` TCP command. Don't break that.

### Layered design

```
record(...)
  ├─ in-memory ring buffer (existing, fast-access overlay)
  └─ FrictionLogJournal.append(entry) (NEW)
        └─ AsyncJournalWriter (single-thread executor, queue-bounded)
              └─ ~/.imagej-ai/friction.jsonl (one JSON object per line)
```

`FrictionLogJournal` is the new class. The ring buffer remains the
authoritative read source for `get_friction_log` (recent entries
only, fast). The journal is the read source for `/improve` (full
history, slow).

### Rotation

- File grows past 10 MB → rename to `friction.jsonl.1`.
- `friction.jsonl.1` exists when rotating → shift to `.2`, etc.
- Cap at 5 generations (`.1` through `.5`); drop the oldest.

### Privacy

The journal lives under `~/.imagej-ai/`. Document its location in
`/improve`'s output so users can inspect or delete it. No telemetry
leaves the machine.

## 7. "Improve from chat history" flow

### What `/improve` does

1. Read all `friction.jsonl*` files from `~/.imagej-ai/`.
2. Filter to entries where `command == "local_assistant"` and
   `error == "miss"` (the path stage 05 writes).
3. Bucket by normalised input. Show top buckets with count, last-
   seen, and the closest existing intent (top-1 from `IntentMatcher`).
4. For each bucket, ask the user (interactively, in chat): keep,
   add as alias to existing intent X, add as a new intent, or
   ignore.
5. Build a YAML diff against `tools/intents.yaml`:
   - "alias" entries become new `seeds:` lines under existing
     intents.
   - "new intent" entries become a new YAML block (with the user
     supplying a description and at least one seed).
6. Show the diff. User accepts → write the updated YAML. User
   rejects → no-op.

### Where the LLM is (and isn't)

**The LLM is not invoked by `/improve`.** Re-running
`tools/phrasebook_build.py` after the YAML lands is a separate
manual step. Otherwise we'd have a runtime LLM call on a "feels
local" command — exactly the trap v1 ruled out.

The user-facing message after accepting the diff is:

> Updated `tools/intents.yaml`. To regenerate the phrasebook, run
> `python tools/phrasebook_build.py` and rebuild the plugin.

### UX risk

Most users won't run `/improve`. The dev who maintains ImageJAI
runs it. That's fine — the friction log is mostly diagnostic
plumbing, and the few biologists who *do* run it will give better
feedback than the empty-text-box silence we have today.

## 8. Multi-step chained intents — the v3 question

### Why it keeps coming up

Real workflows are multi-step. "Threshold and measure". "Subtract
background then count cells". v1 explicitly punted (PLAN §14.2).
v2 has to either commit to it or formally defer.

### What it would actually take

- A small action language: `[intent_id, slots]` pairs with explicit
  ordering.
- Inter-step error handling: what happens if step 2 fails — abort
  the chain, prompt the user, or proceed?
- Undo semantics: the user expects "oops, undo that" to roll back
  the chain, not just the last step. ImageJ's edit history isn't
  reliable for this.
- A way to express data flow between steps (the threshold value
  from step 1 feeding step 2). Slots aren't enough; you need
  expressions.

That's a substantial sub-project — comparable in scope to the v1
matcher itself.

### v2 budget reality

v2 already runs ~7.5 days for the items the user asked for. Adding
chaining doubles it and risks eclipsing the small, useful changes
that resolve real benchmark misses. The brainstorm doesn't find a
way to do chaining cheaply; therefore it should be deferred
explicitly.

### Decision

**Defer to v3 backlog.** Stage 07 is a single decision document
that records the rationale and lists what a v3 stage would have to
solve before implementation begins. That makes the call legible
for the next person; it's not waffling.

## 9. Decisions taken (drives `PLAN.md`)

1. **Single-slot `PendingTurn`** on `LocalAssistant`. One pending
   question at a time. Cleared on resolve, on free-form input,
   on `/clear`, or on agent switch. Same machinery serves both
   parameter prompting and disambiguation.
2. **`AssistantReply.clarifying(question, candidates)`** factory.
   Reply object carries chip data without a fourth field for general
   use.
3. **`Intent.requiredSlots()`** declares the truly-required slots.
   Suggested slots fall back to defaults silently. `requiredSlots()`
   is empty for nearly all v1 intents — only switch to channel /
   jump-to-slice / jump-to-frame are required without a sensible
   default.
4. **Disambiguation margin: 0.05** Jaro-Winkler. Configurable in
   `Settings` (`localAssistantDisambiguationMargin`). Tuned off the
   benchmark; the 3 known misses must clarify rather than
   silently mis-pick.
5. **`PronounResolver` runs before `IntentMatcher`**. Four pronoun
   patterns: "do that again", "measure it", "the next image",
   "save those results". Empty context → fall through.
6. **`ConversationContext` is in-memory only.** Cleared on `/clear`,
   sunset after 10 minutes of idle. No cross-session persistence.
7. **`FrictionLogJournal` writes async** to
   `~/.imagej-ai/friction.jsonl`. Single-thread executor, bounded
   queue. The in-memory ring buffer remains the read source for
   `get_friction_log` TCP command; the journal is the read source
   for `/improve`.
8. **JSONL rotation at ~10 MB**, max 5 generations.
9. **`/improve` is dev-driven, no automatic LLM.** Produces a YAML
   diff against `tools/intents.yaml` for accept/reject. The user
   re-runs `tools/phrasebook_build.py` and rebuilds the plugin to
   apply.
10. **Multi-step chaining defers to v3.** Stage 07 records the
    rationale and the prerequisites a v3 implementation would need.

## 10. Risks still open after v2

- **`PendingTurn` UX confusion.** Users may not realise typing
  anything resets the pending turn. Mitigate with explicit echo:
  the clarification reply ends with "(or type a new request to
  cancel)". Verify in the benchmark.
- **`/improve` developer load.** Reviewing the YAML diff for a
  long-running install with thousands of misses is real work.
  Sort buckets by count and cap the interactive review to the top
  20 by default; deeper review is a flag.
- **Pronoun false positives.** "Save those results" matches
  literally, but a user typing "save those results from yesterday"
  might trigger the wrong path. Mitigate with anchored regexes
  ("^do that again\\b") and unit tests.
- **Disambiguation chips fatigue.** If the margin is too generous,
  every borderline match shows chips. Track chip-show frequency in
  `FrictionLog`; if more than ~10% of submits show chips, tighten
  the margin.

## 11. In-repo sources

- `src/main/java/imagejai/local/LocalAssistant.java`
- `src/main/java/imagejai/local/IntentMatcher.java`
- `src/main/java/imagejai/local/AssistantReply.java`
- `src/main/java/imagejai/local/IntentLibrary.java`
- `src/main/java/imagejai/local/Intent.java`
- `src/main/java/imagejai/local/intents/analysis/PreprocessingIntents.java`
- `src/main/java/imagejai/local/intents/control/ImageControlIntents.java`
- `src/main/java/imagejai/engine/FrictionLog.java`
- `src/main/java/imagejai/engine/IntentRouter.java`
- `src/main/java/imagejai/ui/ChatView.java`
- `src/test/java/imagejai/local/IntentMatcherBenchmarkTest.java`
- `tests/benchmark/biologist_phrasings.jsonl`
- `tools/intents.yaml`
- `tools/phrasebook_build.py`
- `docs/local_assistant_COMPLETED/PLAN.md` §13, §14
- `docs/local_assistant_COMPLETED/BRAINSTORM.md`
