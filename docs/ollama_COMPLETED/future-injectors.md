# Deferred system-note injectors

Candidates evaluated in the 2026-04-19 brainstorm but NOT implemented in the
first batch. Each one has its trigger + payload worked out so the next
implementor can ship without re-deriving.

Active injectors live in `agent/gemma4_31b/loop.py` around lines 1498–1800
(regex constants near line 211; user-text injectors, post-tool dispatcher, and
pre-dispatch hook together). Wiring in `run()` at the `pre_turn_notes` block.

---

## #4 — `_count_after_shootout_note` (dropped 2026-04-20)

**Trigger** (user text): `\b(count|how\s+many|number\s+of)\b`

**Payload**:

> The user asked for a count. If `threshold_shootout` already ran this turn or
> last turn, its `count` IS the answer — report that number. Do NOT run a fresh
> threshold + `Analyze Particles` to re-count. Re-segment only if the user asked
> for per-object measures, ROIs, or a labelled mask.

**Why deferred**: the existing GEMMA_CLAUDE.md section
("threshold_shootout is the answer, not reconnaissance") already covers this,
and firing on every "count" question over-triggers. Revisit if the
`redundant-segment-after-shootout.md` pattern recurs.

---

## #6 — `_negative_intent_suppressor` (dropped 2026-04-20)

**Trigger** (user text): `\b(not|no|without|don'?t)\s+(use\s+)?threshold`

**Dual effect**: returns its own note AND must SUPPRESS `_phase6_system_note`
for the same turn. Current wiring in `run()` runs each injector
independently, so this needs either (a) a gating check before
`_phase6_system_note` is called, or (b) the `_router` architecture (#23).

**Payload**:

> User explicitly EXCLUDED thresholding ("not", "without", "no"). Do NOT call
> `threshold_shootout` this turn even if the task sounds count- or
> segment-shaped. They want a non-threshold approach — filters, intensity
> stats, manual ROI, pixel-side counts. Ask them which if ambiguous.

**Why deferred**: low-frequency friction in the transcripts, and cleaner to
ship alongside `_router` so the suppression semantics are explicit.

---

## #13 — `_nresults_zero_injector` (stub exists)

**Injection point**: post-tool (same dispatcher as the three live post-tool
injectors). Stub function already exists in `loop.py` so wiring is a
one-line change to `_POST_TOOL_INJECTORS`.

**Trigger** (post-tool inspection):
- `args.get("code", "")` contains `"Analyze Particles"` AND does NOT contain
  `"add_to_manager"` or `'roiManager("count"'`
- `result_text` contains three or more lines matching `^\s*\w+:\s*0\s*$`
  (agent printed per-filter/iteration counts and they're all zero)

**Payload**:

> Counts came back as zero. `Analyze Particles...` with `summarize` writes to
> the Summary window, NOT to Results — so `nResults` stays 0. Fix: add
> `add_to_manager` to the args, then read `roiManager("count")` for the
> number. Call `roiManager("reset")` between iterations to avoid carry-over.

**Why deferred**: the three shipped post-tool injectors cover the highest-
frequency frictions; this one is worth enabling once we have telemetry on
whether lint-layer warnings are catching the summarize trap pre-send.

**To enable**: implement the body, then uncomment the entry in
`_POST_TOOL_INJECTORS`.

---

## #16 — `_bitdepth_guardian` (pre-dispatch abort)

**Injection point**: pre-dispatch. Current `_pre_dispatch_abort_note` is an
empty stub; wire the logic into it.

**Trigger**:
- `tool_name` in `{"run_macro", "run_macro_async", "run_script"}`
- `args.get("code", "")` matches `r'run\("AND\b|imageCalculator\([^)]*"AND"'`
- Current active-image bit depth is 16 or 32. Source options:
  - `agent/gemma4_31b/active_image.py` — add a cached `current_bit_depth()`
    fed by the `image.opened` / `image.changed` event stream so the check is
    zero-cost.
  - Fallback: cheap synchronous `get_image_info` TCP call — only paid on
    macros that match the AND regex, so overhead is bounded.

**Payload**:

> Your macro uses `AND` on a 16-bit image. `AND` corrupts 16-bit data by
> dropping the high byte silently. Use
> `imageCalculator("Multiply create", ...)` with the mask rescaled to 0/1,
> OR convert the mask to 8-bit first. This tool call was aborted — rewrite
> the macro.

**Why deferred**: this is the only injector that CANCELS a tool call, so it
needs extra care around `turn_config` flip-to-recover and failure accounting.
Ship after at least one release of the empty `_pre_dispatch_abort_note` hook
so its semantics are settled.

---

## #23 — `_router` (architectural refactor)

**What it is**: replace the flat `pre_turn_notes = [...]` list in `run()`
with a registry keyed by injector name. Each entry:

```python
(should_fire_fn, generate_note_fn, suppresses: list[str])
```

A router runs `should_fire_fn(prompt_text, messages)` for every entry,
builds the fire set, drops any injector named in another firing injector's
`suppresses`, then calls `generate_note_fn` for the survivors.

**Why deferred**: only worth doing once the active injector count crosses
~10, OR once `_negative_intent_suppressor` (#6) needs to mute `_phase6`
cleanly. Current count is 9 pre-turn + 3 post-tool = 12 total but the
pre-turn set has no suppression relationships yet.

**First use case**: `_negative_intent_suppressor` declares
`suppresses=["_phase6_system_note"]`.

---

## Notes on adding new injectors

- Keep pre-turn note bodies 25–60 words, imperative, function-call syntax
  for tools (e.g. `threshold_shootout()`, not \`threshold\_shootout\`).
- Mirror the existing regex style: `\b...\b`, `re.IGNORECASE`, morphology
  captured explicitly (`filter(?:s|ing)?`).
- Post-tool injectors receive a turn-scoped `turn_state` dict they may
  freely read/write; state does NOT persist across user turns.
- Pre-dispatch injectors abort the tool call when non-None. Use sparingly.
- Regression-test every new injector with at least one positive and one
  negative case in `agent/gemma4_31b/tests/test_injectors.py`.
