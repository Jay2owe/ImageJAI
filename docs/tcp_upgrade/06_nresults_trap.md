# Step 06 — `nResults` Trap Warning

## Motivation

One of the most frequently re-encountered bugs in the transcripts:

```
run("Analyze Particles...", "size=50-Infinity");
// agent reads nResults
// nResults == 0
// agent thinks threshold is wrong, retries seven times
```

The macro ran correctly. `Analyze Particles` just doesn't write
to the Results table unless the options string contains
`summarize`, `display`, or the agent reads `roiManager("count")`
after `add` is set. Gemma loses three to seven turns learning
this every single time.

A small macro-analyser catches the pattern post-execution and
inlines a warning before the agent sees `nResults == 0`.

## End goal

When `execute_macro` returns, if the analyser detects the
`Analyze Particles` trap (called without a result-writing flag),
the reply carries:

```json
{"warnings": [{
  "code": "NRESULTS_TRAP",
  "message": "Analyze Particles was called without a flag that writes results.",
  "hint": "Add 'summarize' or 'display' to see rows, or use 'add' and read roiManager('count') instead.",
  "affectedLines": [3]
}]}
```

Agent (especially Gemma) then knows `nResults == 0` is expected,
not a failure. Single line in the reply; two lines of text at
most; ends a class of loops.

## Scope

In:
- New `MacroAnalyser` utility, pure function
  `analyse(String code, PostExecState state) -> List<Warning>`.
- Initial rule set: one rule — `analyzeParticlesMissingResultFlag`.
- Integration in `handleExecuteMacro` post-execution.
- Architecture designed for adding more rules later (step 11,
  step 13 will want to stack on this).

Out:
- Anything fancier than regex-based analysis. No AST. No runtime
  inspection beyond what post-execution state already tells us.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — `handleExecuteMacro`.
- `docs/tcp_upgrade/02_structured_errors.md` — this adds a
  `warnings[]` array to the success-reply shape; check that
  structured-errors step doesn't collide.
- Friction logs: `filter-loop-nresults-trap.md`,
  `filter-shootout-hallucinated-commands.md`,
  `blobs-10-filters.md`.

## Rule: `analyzeParticlesMissingResultFlag`

Trigger conditions (all must hold):
1. Macro contains a `run("Analyze Particles...", "OPTS")` call.
2. `OPTS` does not contain any of: `summarize`, `display`,
   `show=[`. (Note: `add` alone writes to ROI Manager but not
   the Results table — treated as a less serious case; fires a
   warning only if the agent's macro later reads `nResults`.)
3. Post-execution `nResults == 0`.

When all three hold, emit the warning. Extracted as:

```java
public final class MacroAnalyser {
    public static List<Warning> analyse(String code, PostExec state) {
        List<Warning> out = new ArrayList<>();
        nresultsTrap(code, state).ifPresent(out::add);
        return out;
    }

    static Optional<Warning> nresultsTrap(String code, PostExec state) {
        Matcher m = Pattern.compile(
            "run\\s*\\(\\s*[\"']Analyze Particles\\.\\.\\.[\"']\\s*,\\s*[\"']([^\"']*)[\"']"
        ).matcher(code);
        if (!m.find()) return Optional.empty();
        String opts = m.group(1);
        boolean writesResults = opts.contains("summarize")
            || opts.contains("display") || opts.contains("show=[");
        if (writesResults) return Optional.empty();
        if (state.nResultsAfter > 0) return Optional.empty();

        int line = lineOf(code, m.start());
        boolean roiOnly = opts.contains("add");
        String hint = roiOnly
            ? "You used 'add' — rows go to ROI Manager, not the Results table. Read roiManager('count') for the count."
            : "Add 'summarize' or 'display' to the options to populate Results, or use 'add' and read roiManager('count').";
        return Optional.of(new Warning(
            "NRESULTS_TRAP",
            "Analyze Particles was called without a flag that writes to Results; nResults is 0 by design.",
            hint,
            List.of(line)));
    }
}
```

## Reply shape

New top-level `warnings[]` on success replies. Empty array is
omitted; the field is present only when at least one warning
fired.

```json
{"ok": true, "result": {
  "success": true,
  "warnings": [{"code": "NRESULTS_TRAP", ...}],
  "stateDelta": {...}
}}
```

On failure replies, warnings still appear (an agent might emit a
warning-worthy pattern alongside a compile error), alongside the
`error` object.

## Capability gate

`caps.warnings` — default `true` for all agents. Gemma benefits
most; Claude and Codex may already catch the pattern themselves
but the warning is cheap. Turning off is opt-out only.

## Extensibility

`MacroAnalyser.analyse` is the single entry point. Steps 11, 13,
and later experimentation should add new rules as additional
optional-returning methods and append them to the `out` list.
Each rule follows the same pattern: pure function of `(code,
state)`, returns `Optional<Warning>`. No shared state, no
ordering assumptions.

Future rules to register in later steps (not shipped here):

- `enhanceContrastBeforeMeasure` — `run("Enhance Contrast",
  "normalize=true")` followed by `Measure` (per house rules).
- `measureOnBinaryMask` — `run("Measure")` on an image whose
  bit depth is 1 or whose histogram is 2-valued.
- `thresholdAfterMeasure` — `setAutoThreshold` after `Measure`
  on the same image (usually a logic error).

Those are step 11's territory. This step ships just the one rule
and the framework.

## Tests

- `run("Analyze Particles...", "size=50-Infinity")` with zero
  particles found → warning fires.
- Same macro but with `summarize` in options → no warning.
- Same macro but with one particle found and no flag → no
  warning (state shows rows present, so the trap didn't bite).
- `add` alone, agent never reads nResults → no warning
  technically necessary, but fires anyway (erring on the side of
  the agent knowing). Decision: fire, because the agent may be
  about to read `nResults` on the next turn.
- Macro with no Analyze Particles call → no warning; analyser
  returns empty quickly.

## Failure modes

- False positive when the agent deliberately wanted rows in ROI
  Manager only and doesn't care about `nResults`. The hint is
  text; it's not actionable; no harm done beyond a few extra
  tokens.
- Regex misses non-standard whitespace. Accept — rare, visible
  in telemetry.
- Ordering with `stateDelta`: both reference post-execution state
  via `StateInspector`. Capture state once, pass to both; don't
  query twice in case anything's raced.
