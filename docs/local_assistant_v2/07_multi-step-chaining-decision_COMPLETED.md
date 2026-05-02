# Stage 07 — Multi-step chained intents (decision)

## Why this stage exists

v1 explicitly punted on multi-step chained intents (PLAN §14.2:
"Single-action v1"). The deferred-items list for v2 reopens the
question: should v2 implement chaining ("threshold and measure",
"subtract background then count cells"), or formally defer to v3?

This stage's deliverable is the *decision*, not implementation.
The user asked for an explicit choice rather than waffling, and
this is it.

## Decision

**Defer to v3.** v2 will not implement multi-step chained intents.

## Why

Four reasons, in order of weight:

1. **Action language design isn't simple.** "Threshold and
   measure" expands to multiple intents whose data flow is
   non-trivial. The threshold value picked in step 1 needs to
   reach step 2's measurement context. A flat list of
   `[(intent_id, slots), (intent_id, slots)]` is not enough — you
   need named outputs from earlier steps and expressions for
   downstream references. That's a small DSL, and v2's budget
   doesn't have room for it.

2. **Undo semantics.** Users expect chain-level undo: "oops,
   undo that" should roll back the *entire* chain, not just the
   last step. ImageJ's edit history isn't reliable as a chain-
   level undo log — many macros don't push undo records at all,
   and some are inherently irreversible (channel splits, file
   exports). A custom chain-undo log layered over `runMacro`
   calls is its own sub-project.

3. **Inter-step error handling.** If step 2 fails, what should
   happen? Abort the chain? Prompt the user mid-chain? Continue
   with a default? The right answer differs by chain. Without an
   explicit policy, every chain becomes a small surprise.

4. **Budget.** v2's other six stages already total ~7 days of
   focused work, and they directly resolve known benchmark
   misses, deferred items from PLAN §13/§14, and a real
   user-facing pain point (parameter prompts). Adding chaining
   doubles v2's scope and risks eclipsing the small, useful
   wins.

The brainstorm did not find a way to do chaining cheaply. The
honest call is to defer.

## What v3 needs to design before implementing

Anyone writing the v3 chaining stage starts here. None of these
are negotiable; they're what made v2 say no.

### A1. Action language

Pick the language shape:
- **Sequential `[(intent_id, slots), ...]` with named outputs**:
  each step declares an `output_name` that downstream steps can
  reference via `${output_name.field}`. Easiest to implement,
  enough for "threshold-then-measure" style chains.
- **Block-structured DSL** with conditionals, loops, error
  handlers. Strong but expensive — equivalent to embedding a
  macro language alongside ImageJ Macro.
- **Recipe YAML extension** of `agent/recipes/`. Already partway
  there in YAML form on the Python side. Could be the right
  reuse path; a v3 stage should investigate.

Prefer the simplest option that actually solves the
"threshold-then-measure" use case. Start with sequential + named
outputs; only add control flow if a real user need surfaces.

### A2. Undo semantics

- **Chain-level undo**: a single user undo rolls back the whole
  chain. Requires an inverse-macro registry per intent. Not all
  intents have safe inverses (file exports, channel splits).
- **Step-level undo**: user undoes one step at a time. Easier;
  matches ImageJ's existing edit history; weaker UX.

Pick step-level undo for v3 unless inverse-macro coverage proves
viable. Document which intents support undo and which don't.

### A3. Inter-step error handling

Default policy: **abort on error, restore prior state where
possible, surface a clear message**. Per-chain override only if
strongly motivated. The pending-turn machinery from v2 stage 01
can hold "step 2 failed, retry / skip / abort?" prompts, so v3
can layer interactive recovery on top.

### A4. Test fixtures (write before implementing)

- Success chain: "subtract background then count cells" runs
  both steps against an image with cells, ends with a results
  table.
- Mid-chain failure with abort: step 2 fails (e.g. no image
  selected mid-chain), the chain aborts with a message, no
  partial results are persisted.
- Mid-chain failure with continue: same but the chain is
  configured to continue past failures, ends with partial
  results.
- Step-level undo of a 3-step chain: user undoes once, only the
  last step is rolled back.
- Chain with named output: step 1 thresholds, step 2 measures
  using the threshold value from step 1.

## What v2 *does* preserve

While v2 doesn't implement chaining, it leaves the door open:

- The `PendingTurn` state machine (stage 01) can be extended
  with a new `Kind.CHAIN_STEP` for v3 mid-chain interactions.
- `Intent.requiredSlots()` / `suggestedSlots()` (stage 02) are
  v3-friendly: a chain step that's missing slots can use the
  same prompt machinery.
- `ConversationContext` (stage 04) already tracks "last intent"
  and "last slots" — a v3 chain runner can layer "current chain
  step" on top.

So v2 is *not* a dead end for chaining. v3 picks up an extended
state machine, not a brand-new one.

## Out of scope for stage 07

- Implementation. This stage produces only this decision
  document.
- Updating `agent/recipes/` YAML. That's a v3 concern.
- Modifying `LocalAssistant` to handle chains in a degraded way
  (e.g. running the steps sequentially without inter-step state).
  That would ship a half-feature, which is exactly the trap the
  CLAUDE.md house rule "no half-finished implementations" warns
  about.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `docs/local_assistant_v2/07_multi-step-chaining-decision.md` | NEW | This document |

No code changes. No test changes.

## Exit gate

1. The document exists at the path above.
2. `00_overview.md` lists this stage in the stage map.
3. The "Future work" section in `00_overview.md` references the
   v3 chaining backlog item.
4. `mvn -q test` passes (it does — no code changed).

## Known risks

- **Pressure to revisit.** A future stakeholder may insist v2
  needs chaining. Push back with this document. The four reasons
  for deferring don't change because someone wants the feature
  more.
- **Half-implementing later.** If v3 starts and runs out of
  time, the temptation will be to ship "chains without undo" or
  "chains without inter-step state". Don't. The v2 single-action
  experience is good enough on its own; a half-finished v3
  chaining is worse than no v3 chaining.
- **Confusion with `agent/recipes/`.** The Python-side recipe
  loader already runs multi-step workflows for external CLI
  agents. That path is separate — those are pre-canned scripts
  the external agent invokes, not user-typed natural-language
  chains parsed by Local Assistant. v3 chaining is *Local
  Assistant* chaining, distinct from recipes. Document this in
  the v3 stage when it's written.
