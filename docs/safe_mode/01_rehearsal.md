# Silent Macro Rehearsal

## Idea

Before running an agent's macro on the real image, run it on a
256×256 thumbnail copy in a sandbox `ImagePlus`. If the thumbnail
run throws, times out, or produces nonsense, abort the real run
and return the rehearsal error as the agent's response. The agent
never knows rehearsal happened unless it failed.

## Why

- Most macro bugs (typos in plugin names, missing args, wrong
  image type, missing calibration) trigger on the thumbnail just
  as they would on the full image.
- A thumbnail run completes in well under 50 ms for most filter
  pipelines — imperceptible to the user.
- When the real run corrupts state (e.g. overwrites pixels with
  an accidental `Enhance Contrast normalize=true`), the corruption
  is irreversible without an undo step. Rehearsal catches this
  cheaply.

## Scope

Rehearsal runs in safe mode only — gated behind
`caps.safe_mode == true` set in the `hello` handshake. Existing
sessions are unaffected.

## What rehearsal catches

- Compile errors in the macro language
- `Unrecognized command:` errors from `run("Gausian Blur")`
- Wrong image type: `Not a binary image`, `Stack required`
- Missing selection: `Selection required`
- Missing argument normalisation: `sigma=` with no value
- Plugin dialogs that block (the thumbnail run uses `setBatchMode(true)`
  by default)

## What rehearsal does NOT catch

Some macros only fail at scale — these must bypass rehearsal:
- Memory-bound ops (`Watershed` on a 4K stack)
- Absolute-pixel thresholds (`size=500-Infinity` on a thumbnail
  gives zero particles)
- Plugins that read file paths the thumbnail image doesn't have
- Plugins with non-deterministic initialisation (random seeds)

An annotation lets the agent skip rehearsal per call:

```
// @safe_mode skip_rehearsal: size-dependent
run("Analyze Particles...", "size=500-Infinity summarize");
```

The server reads this annotation and skips rehearsal for that
macro, but still runs the destructive-op block.

## Implementation sketch

In `TCPCommandServer.handleExecuteMacro`, before executing:

```java
AgentCaps caps = capsBySocket.getOrDefault(sock, DEFAULT_CAPS);
if (caps.safeMode && !macroOptsOut(code, "skip_rehearsal")) {
    RehearsalResult r = rehearse(code, activeImp);
    if (!r.ok) {
        return errorReply("REHEARSAL_FAILED", r.error,
                          /*retrySafe=*/false,
                          "Fix the macro — rehearsal caught this before real run.");
    }
}
// proceed with real execution
```

`rehearse(code, imp)` duplicates `imp` to a 256×256 thumbnail,
runs the macro in a sandbox `Interpreter` with `setBatchMode(true)`,
captures any exception, and closes the thumbnail. Time-cap at
2 seconds; if it exceeds, return a `REHEARSAL_TIMEOUT` error and
recommend `@safe_mode skip_rehearsal: size-dependent`.

## Degradation

If the thumbnail duplicate itself fails (out of memory, unusual
image type), skip rehearsal silently and proceed with the real
run. Log the skip to `FrictionLog` for later review.

## Files

- Server: `src/main/java/imagejai/engine/TCPCommandServer.java`
  — new method `rehearse(String code, ImagePlus imp)`, call site
  inside `handleExecuteMacro`
- Tests: exercise rehearsal against each known failure category
  in the improvement-loop transcripts

## Failure modes

- Rehearsal false positives on edge-case macros — must be
  overridable per-call via the `@safe_mode` annotation and
  globally via `caps.safe_mode = false`.
- Rehearsal masking a real bug that only the thumbnail triggers
  (e.g., `size=1-Infinity` giving different results on 256×256
  vs 2048×2048). Agents should treat rehearsal-failed as
  suspicion, not certainty — error code makes this explicit.
- Thumbnail plugins with side effects on disk (writing caches).
  Sandbox the run to a temp `AI_Exports_rehearsal/` directory
  that's cleared after every rehearsal.
