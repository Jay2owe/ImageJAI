# Coordinator-backed macro, script, job, and pipeline execution

## Why this stage exists

The coordinator from stage 05 is only useful when all primary engine entry points use it. Async jobs, scripts, and pipelines currently bypass combinations of safety scan, timeout, mutex, undo, provenance, and in-flight accounting.

## Prerequisites

- Stage 05 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/full-codebase-hardening-2026-07/05_mutation-coordinator.md`.
- `src/main/java/imagejai/engine/TCPCommandServer.java` (TODO: locate sync/async macro, script, batch, and pipeline handlers).
- `src/main/java/imagejai/engine/CommandEngine.java`.
- `src/main/java/imagejai/engine/JobRegistry.java`.
- `src/main/java/imagejai/engine/PipelineBuilder.java`.
- `src/main/java/imagejai/engine/CrossToolRunner.java`.
- `src/main/java/imagejai/engine/safeMode/SourceImageTagger.java`.

## Scope

- Route sync/async macros, scripts, pipelines, and cross-tool mutations through `MutationCoordinator`.
- Integrate macro lint and `SourceImageTagger` with the shared preflight policy.
- Preserve job ownership, timeout, cancellation, undo, provenance, and structured failure consistently.
- Resume pipelines after the last completed stage without rerunning completed stages.
- Report the failing batch index while retaining prior results.
- Bound cross-tool output and terminate process trees reliably using explicit charset.

## Out of scope

- ConversationLoop/Local Assistant migration belongs to stage 10.
- Exploration and reactive migration belong to stages 11 and 13.
- Full resource/flood limits belong to stage 23.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Use coordinator in core handlers. |
| `src/main/java/imagejai/engine/CommandEngine.java` | MODIFY | Share preflight/lifecycle behavior. |
| `src/main/java/imagejai/engine/JobRegistry.java` | MODIFY | Remove bypass execution. |
| `src/main/java/imagejai/engine/PipelineBuilder.java` | MODIFY | Correct resume and coordinator routing. |
| `src/main/java/imagejai/engine/CrossToolRunner.java` | MODIFY | Bound subprocess lifecycle/output. |
| `src/main/java/imagejai/engine/safeMode/SourceImageTagger.java` | MODIFY | Make source tagging part of real production paths. |
| `src/test/java/imagejai/engine/TCPCommandServerBatchCapsTest.java` | MODIFY | Cover partial batch failure and caps. |
| `src/test/java/imagejai/engine/CommandEngineLifecycleTest.java` | NEW | Exercise success/failure/timeout/cancel across surfaces. |

## Implementation sketch

Each handler constructs the same coordinator request with source kind and owner session; none creates its own executor or acquires an independent mutation mutex. Pipeline checkpoints are committed only after a stage completes. Batch wraps each result with its index and stops/continues according to the documented contract without discarding completed entries. Cross-tool capture uses a byte cap and kills descendants on timeout.

## Exit gate

1. Tests spy on the coordinator and prove every listed surface uses it exactly once.
2. Async execution cannot bypass safe mode, macro lint, undo, timeout, or provenance.
3. Pipeline resume skips completed stages; batch failure identifies the index and retains earlier results.
4. Cross-tool timeout terminates descendants and bounded output reports truncation.
5. Maven unit tests pass with no duplicate mutation lock/executor path left in these handlers.

## Known risks

- Preserve read-only commands outside the mutation lock.
- A pipeline stage must not be marked complete until all coordinator completion hooks succeed.
