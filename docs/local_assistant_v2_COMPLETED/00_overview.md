# Local Assistant v2 — Staged Implementation

The canonical long-form record is `PLAN.md` in this folder, with the
research synthesis in `BRAINSTORM.md`. Each numbered stage below is
sized for one focused agent session (~1–2 days). Stages are executed
in numeric order via `/do-step docs/local_assistant_v2/`.

v1 shipped on `main` at commit `ff8206b` (2026-05-02): 10 stages
covering the agent selector, two-tier matcher, ~50 hand-curated
intents, slash commands, installer panel, and menu mining. v2 picks
up the items v1 explicitly deferred (PLAN §13 "Phase D" and §14
"Open Questions") plus a new clarification state machine that the
deferred items all depend on.

## End goal

Local Assistant moves from "matches one phrase, runs one intent" to
"holds a one-turn conversation, asks for missing parameters, asks
which intent the user meant when ambiguous, remembers what the user
just did, learns from misses". Still no runtime LLM. Still
deterministic. Still <5 ms p99 for the matcher itself.

By the end of v2, the three known benchmark misses ("how many
frames", "what can you do", "commands") resolve via clarification
chips rather than silent miss. Cross-session miss collection makes
the next phrasebook regeneration data-driven instead of guessed.

## What v2 changes (one paragraph)

A `PendingTurn` slot on `LocalAssistant` (stage 01) lets the
assistant ask one question at a time and resolve it on the user's
next message — used by both *missing parameter* prompts (stage 02:
"what radius? default is 50") and *disambiguation* prompts (stage
03: top-1 vs top-2 within margin). A `ConversationContext` (stage
04) gives pronouns ("do that again", "measure it") something to
resolve against. A JSONL writer over `FrictionLog` (stage 05) makes
misses survive across sessions, and the new `/improve` command
(stage 06) turns those misses into a YAML diff the developer
accepts or rejects before the phrasebook recompiles. Stage 07
documents the multi-step chaining decision: punted to v3.

## Architecture additions

```
imagejai.local
├── LocalAssistant.java          (existing — gains PendingTurn slot, ConversationContext)
├── PendingTurn.java             (NEW, stage 01)
├── ConversationContext.java     (NEW, stage 04)
├── AssistantReply.java          (existing — gains clarifying() factory, stage 01)
├── IntentMatcher.java           (existing — gains margin-aware match2(), stage 03)
└── slash/ImproveSlashCommand.java (NEW, stage 06)

imagejai.engine
├── FrictionLog.java             (existing — gains JSONL persistence, stage 05)
└── FrictionLogJournal.java      (NEW, stage 05)
```

No new runtime dependencies. No new package directories. v2 lands
on the v1 code without restructuring it.

## Stage map

| NN | Slug | Goal | Size | Depends on |
|---|---|---|---|---|
| 01 | pending-turn-state-machine | `PendingTurn` slot on `LocalAssistant` + `AssistantReply.clarifying(question, candidates)` factory; one-turn conversation resolution | ~1 day | — |
| 02 | parameter-prompting | When required slot missing, reply asking for it via the stage-01 pending slot; user's next message fills the slot and runs the intent | ~1 day | 01 |
| 03 | disambiguation-chips | `IntentMatcher` returns runner-up when top-1 / top-2 within margin (default 0.05); reply via stage-01 clarification path; ChatView renders chips | ~1 day | 01 |
| 04 | conversational-memory | `ConversationContext` tracks last image / last intent / last ROI; pronoun resolution; sunset on `/clear` or 10-minute idle | ~1.5 days | — |
| 05 | friction-log-jsonl | Persist `FrictionLog.record()` rows to `~/.imagej-ai/friction.jsonl` with size-based rotation; ring buffer remains as fast-access overlay | ~1 day | — |
| 06 | improve-from-history | `/improve` slash command summarises persisted friction log, presents YAML diff against `tools/intents.yaml` for accept/reject, no automatic LLM calls | ~1.5 days | 05 |
| 07 | multi-step-chaining-decision | Single decision doc: chained intents punt to v3 backlog with the rationale | ~0.5 day | — |

Total estimated: ~7.5 days. Stages 01 → 02 and 01 → 03 are the only
hard dependency chains. Stages 04, 05, 07 can execute in any order.
Stage 06 needs 05.

## House rules (applies to every stage)

Pulled from `CLAUDE.md` and `agent/CLAUDE.md`. Non-negotiable;
failing them is a regression. Carried forward verbatim from v1.

- **State check.** Every mutating intent must verify an image is
  open before acting; reply "no image is open" otherwise.
- **`AI_Exports/`.** All file outputs go to `AI_Exports/` next to
  the opened image (resolve via
  `ImagePlus.getOriginalFileInfo().directory`). If the active image
  has no file-backed directory, ask the user to save it first.
- **Display-only contrast.** Never use `Enhance Contrast
  normalize=true` for data that may be measured. Use `setMinAndMax()`.
- **Never close the Log window.** "Close all but active" intents
  must whitelist the ImageJ Log window.
- **Masks, not overlays.** Detection results display as object masks.
  `Analyze Particles` defaults to `show=Masks`.
- **Probe before guessing.** Dialog-opening intents open the dialog
  and stop. Do not guess parameters for unfamiliar plugin dialogs.
- **No runtime LLM.** Ever. The phrasebook is generated *at dev
  time*. The shipped plugin makes zero outbound LLM calls. v2 stage
  06 surfaces a YAML *diff* for developer review; it does not
  itself call any LLM. Re-running `tools/phrasebook_build.py` on the
  accepted diff is still a developer action.
- **Fix bugs, don't work around them.** If a stage hits an
  underlying bug in v1 code, fix it in place rather than papering
  over it.

## v1 invariants v2 must not regress

- **Benchmark accuracy floor: 97.4% top-1** on
  `tests/benchmark/biologist_phrasings.jsonl` (the
  `IntentMatcherBenchmarkTest` currently asserts ≥80% but the live
  number is 97.4%; v2 stages must keep that number ≥97.4%).
- **No new runtime dependencies.** Gson, JUnit, in-repo
  `engine.FuzzyMatcher` only.
- **No new IO on the matcher hot path.** All disk activity stays
  off the `match()` and `topK()` call paths. Stage 05's JSONL
  writer is async-write-safe; stage 04's context is in-memory.
- **`IntentRouter` API.** Use `teach`/`forget`/`list`/`resolve`,
  not `add`/`remove`. (Existing v1 mistake — don't re-introduce it.)
- **Slash > phrasebook precedence** stays in
  `LocalAssistant.handle()`.

## Known design constraints (carried from v1 §14)

1. **Naming** stays "Local Assistant".
2. **Single-action v1.** v2 inherits this — multi-step chaining is
   formally punted to v3 in stage 07.
3. **Parameter prompting** uses chat replies for missing
   numeric/text parameters; file dialogs already handle filenames.
   Stage 02 implements the chat-reply path.
4. **`FrictionLog` persistence** lands in stage 05.
5. **Intent ID namespacing.** Built-ins keep `builtin.<category>.
   <name>`; menu-mined keep `menu.<menu-path>`; user-taught remain
   regex-keyed via `IntentRouter`. v2 introduces no new ID
   namespaces.

## How to run a stage

```
/do-step docs/local_assistant_v2/
```

This finds the lowest-numbered `NN_*.md` file without `_COMPLETED`,
reads it, executes it, commits, and renames the file to
`NN_*_COMPLETED.md`.

## Future work (not split into stages)

Items below stay in the v3 backlog rather than as v2 stages.

- **Multi-step chained intents.** "Threshold and measure",
  "subtract background then count cells". Per stage 07 decision:
  needs an action language with explicit step boundaries, undo
  semantics, and inter-step error handling. Out of scope for v2.
- **Phrasebook auto-regeneration on miss volume.** The v2 `/improve`
  flow is developer-driven (manual diff review). A scheduled,
  privacy-respecting opt-in flow ("regenerate phrasebook nightly
  from accumulated misses") is v3 material.
- **Semantic boost (MiniLM ONNX) integration.** v1 stage 09 ships
  the download path; v2 does not wire it as a Tier 2.5 stage in the
  matcher cascade. Defer until benchmark accuracy or miss volume
  justifies it.
- **Voice input.** Out of scope. Same call as v1.
- **Cross-session conversational memory.** v2 stage 04 is
  in-memory only. Persisting "last image touched" across
  sessions wasn't requested and risks confusing users when the
  image isn't actually open at startup.
