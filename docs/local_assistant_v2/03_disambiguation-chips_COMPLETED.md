# Stage 03 — Disambiguation chips on submit

## Why this stage exists

Three known benchmark misses ("how many frames", "what can you
do", "commands") fail because two intents score within margin and
v1 silently picks one — sometimes the wrong one. v2's fix: when
top-1 and top-2 are within a configurable margin (default 0.05),
the assistant asks "did you mean…?" and presents both options as
chips via the stage-01 pending turn slot. The user clicks; the
right intent runs.

This is the *post-submit* clarification path. It's distinct from
v1's *autocomplete* chip row, which renders below the input on
every keystroke and is unchanged.

## Prerequisites

- Stage 01 (`PendingTurn`, `AssistantReply.clarifying(...)`).
- Stage 02 is *not* required, but landing 02 first means the
  pending-turn machinery is exercised by both code paths and
  bugs surface earlier. Recommended order: 01 → 02 → 03.

## Read first

- `docs/local_assistant_v2/00_overview.md`
- `docs/local_assistant_v2/PLAN.md` §7 ("Stage 03 — Disambiguation
  chips")
- `docs/local_assistant_v2/BRAINSTORM.md` §4
- `src/main/java/imagejai/local/IntentMatcher.java`
  lines 41-96 — `match()` and `topK()` are the surface we extend
- `src/main/java/imagejai/local/RankedPhrase.java`
- `src/main/java/imagejai/local/AutocompleteChipRow.java`
  — reuse the visual styling but render in a *new place*
- `src/main/java/imagejai/ui/ChatView.java` lines
  824-862 — `sendMessage()` rendering. Stage 03 inserts a chip-
  rendering branch when the reply is clarifying.
- `src/main/java/imagejai/config/Settings.java` — find
  where `localAssistantFuzzyThreshold` is declared; add the new
  margin field next to it.
- `tests/benchmark/biologist_phrasings.jsonl` — find the rows for
  "how many frames", "what can you do", "commands" (or add them if
  absent). Stage 03 must turn these from misses into clarifying
  replies.

## Scope

- New `Match2Result.java` — `(RankedPhrase best, RankedPhrase
  runnerUp, double margin, Status status)` where `Status` is
  `CONFIDENT`, `AMBIGUOUS`, or `MISS`.
- New `IntentMatcher.match2(String input, double margin)` returning
  a `Match2Result`. Reuses `topK(input, 2)` internally. Does **not**
  modify `match()` or `topK()`.
- Add `localAssistantDisambiguationMargin` (default `0.05`) to
  `Settings` next to `localAssistantFuzzyThreshold`.
- Modify `LocalAssistant.handle()` to call `matcher.match2(...)`
  after Tier 1 / Tier 2 success. If `match2` returns `AMBIGUOUS`,
  park `PendingTurn.disambiguation(...)` and return
  `AssistantReply.clarifying("Did you mean:", [best, runnerUp])`.
  If `CONFIDENT`, fall through to the existing execute path
  (which itself runs the stage-02 required-slot check).
- Implement `tryResolve` for `Kind.DISAMBIGUATION`: the user's
  next message must match one of the candidate phrases (chip click
  fills the input with the chip's literal phrase). Resolve to
  `library.byId(candidate.intentId()).execute(...)`.
- Modify `ChatView.sendMessage()` to render a clarification chip
  row beneath the assistant's clarifying message when
  `reply.isClarifying()` is true. Chips, on click, submit the
  chip's phrase as the next user message — which falls through
  the `pending` resolution path.

## Out of scope

- Refactoring `match()` or `topK()`. New helper, no breaking
  changes.
- Removing or modifying the autocomplete chip row. v1's
  keystroke-driven chips stay.
- Persisting "user disambiguation choice → preferred intent"
  learning. v3 backlog. v2's chips are a per-turn affordance.
- Showing more than two candidates. v2 caps at top-2. Three+ would
  invite chip fatigue and the benchmark misses are all 2-way.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/Match2Result.java` | NEW | Margin-aware match result type |
| `src/main/java/imagejai/local/IntentMatcher.java` | MODIFY | Add `match2(...)` helper |
| `src/main/java/imagejai/local/LocalAssistant.java` | MODIFY | Insert disambiguation branch + DISAMBIGUATION resolution |
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Add `localAssistantDisambiguationMargin` |
| `src/main/java/imagejai/ui/ChatView.java` | MODIFY | Render clarification chips beneath the assistant message |
| `src/test/java/imagejai/local/DisambiguationTest.java` | NEW | Cover the 3 known benchmark misses + margin tuning |

## Implementation sketch

```java
// Match2Result.java
public final class Match2Result {
    public enum Status { CONFIDENT, AMBIGUOUS, MISS }

    private final Status status;
    private final RankedPhrase best;
    private final RankedPhrase runnerUp;
    private final double margin;

    public static Match2Result confident(RankedPhrase best) { ... }
    public static Match2Result ambiguous(RankedPhrase best,
                                         RankedPhrase runnerUp,
                                         double margin) { ... }
    public static Match2Result miss() { ... }

    public Status status() { return status; }
    public boolean isAmbiguous() { return status == Status.AMBIGUOUS; }
    public RankedPhrase best() { return best; }
    public RankedPhrase runnerUp() { return runnerUp; }
}
```

```java
// IntentMatcher.match2
public Match2Result match2(String input, double margin) {
    List<RankedPhrase> top = topK(input, 2);
    if (top.isEmpty()) return Match2Result.miss();
    RankedPhrase best = top.get(0);
    if (best.score() < settings.getLocalAssistantFuzzyThreshold()) {
        return Match2Result.miss();
    }
    if (top.size() == 1) return Match2Result.confident(best);
    RankedPhrase runner = top.get(1);
    // Don't disambiguate when both phrases route to the same intent.
    if (best.intentId().equals(runner.intentId())) {
        return Match2Result.confident(best);
    }
    double gap = best.score() - runner.score();
    return gap < margin
        ? Match2Result.ambiguous(best, runner, gap)
        : Match2Result.confident(best);
}
```

```java
// LocalAssistant.handle() — replace the Tier 1/2 execute branch
Optional<MatchedIntent> matched = matcher.match(input);
if (matched.isPresent()) {
    Match2Result m2 = matcher.match2(input, settings.getDisambiguationMargin());
    if (m2.isAmbiguous()) {
        List<RankedPhrase> candidates = List.of(m2.best(), m2.runnerUp());
        pending = PendingTurn.disambiguation("Did you mean:", candidates);
        return AssistantReply.clarifying("Did you mean:", candidates);
    }
    // ...existing required-slot check (stage 02), then execute
}
```

```java
// LocalAssistant.tryResolve — DISAMBIGUATION branch
case DISAMBIGUATION:
    String key = IntentMatcher.normalise(input);
    for (RankedPhrase candidate : p.candidates()) {
        if (key.equals(IntentMatcher.normalise(candidate.phrase()))) {
            Intent held = library.byId(candidate.intentId());
            return Optional.of(held.execute(Map.of(), fiji));
        }
    }
    return Optional.empty();   // user typed something else; drop
```

```java
// ChatView.sendMessage() — after appending the assistant reply
if (reply.isClarifying() && !reply.clarificationCandidates().isEmpty()) {
    appendClarificationChips(reply.clarificationCandidates());
}

private void appendClarificationChips(List<RankedPhrase> candidates) {
    AutocompleteChipRow row = new AutocompleteChipRow(phrase -> {
        inputArea.setText(phrase);
        sendMessage();
    });
    row.setCandidates(candidates);
    // Render row inline beneath the most recent assistant message
    // (likely a JPanel insertion in the message column).
}
```

## Exit gate

1. `mvn -q test` passes.
2. `IntentMatcherBenchmarkTest` accuracy ≥97.4%. Specifically:
   - "how many frames", "what can you do", "commands" rows must
     each end up in one of two states:
     - The expected intent is the top-1 result (acceptable — the
       phrasebook compiler may have aliased them after stage 06).
     - `match2` returns `AMBIGUOUS` and the expected intent is one
       of `best` / `runnerUp`. The benchmark test should treat
       this as a pass.
   - Add a benchmark assertion or test that explicitly checks the
     three rows resolve via clarification rather than silent miss.
3. `DisambiguationTest` covers:
   - "how many frames" → `match2` returns `AMBIGUOUS`; both
     `image.stack_counts` and `image.position` are in the
     candidates.
   - Clicking the `image.stack_counts` chip resolves to that
     intent.
   - Typing "close all" while a disambiguation is pending drops
     the pending turn and runs `close all` fresh.
   - Confident match (e.g. "pixel size") returns `CONFIDENT` and
     no chips render.
   - Margin tunable: setting `Settings.localAssistantDisambiguationMargin`
     to `0.0` makes `match2` always return `CONFIDENT`.
4. Manual smoke: type "how many frames" in the chat — see
   clarification message and two chips; click one — that intent
   runs.
5. Disambiguation does NOT fire when both top-1 and top-2 phrases
   point at the same intent ID (test with paraphrases of the same
   intent).

## Known risks

- **Margin tuning.** 0.05 is a guess. Run the benchmark with
  margins `0.02 / 0.05 / 0.08 / 0.10` and pick the value that
  resolves the 3 known misses without firing chips on confident
  matches. Document the choice in the test or a comment near the
  default value.
- **Same-intent runner-up.** The phrasebook has many phrases per
  intent; top-2 frequently point at the same intent. The
  `best.intentId().equals(runner.intentId())` short-circuit
  prevents bogus chips. Test it.
- **Chip placement in `ChatView`.** Inline chips beneath the
  assistant message means modifying the message-rendering layout.
  If that's invasive, an acceptable v2 fallback is to render the
  clarification chip row in the same slot as the autocomplete
  chip row (under the input) but with a distinct visual marker
  ("did you mean:"). Document the choice.
- **Resolution by chip vs typed phrase.** Both paths must work.
  The chip click sets `inputArea.setText(phrase); sendMessage();` —
  identical to the user typing the phrase. The
  `tryResolve` path matches normalised phrases; verify with a test
  where the user *types* the candidate phrase rather than clicking
  the chip.
- **Frequency.** If chips fire too often, biologists will
  silence-disable the assistant. Tracking chip-show count in
  `FrictionLog` (record as `event=disambiguation_shown`) is a
  cheap diagnostic; add it now so stage 06 can later use the data.
