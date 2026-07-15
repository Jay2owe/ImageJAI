# Single mutation coordinator

## Why this stage exists

Mutation admission, safe-mode scanning, serialization, timeouts, cancellation, undo, and provenance are fragmented. A cancelled `Future` can appear done before the ImageJ worker exits, allowing a zombie interpreter to mutate state after the global lock is released.

## Prerequisites

- Stage 03 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, release blocker 3.
- `src/main/java/imagejai/engine/CommandEngine.java`.
- `src/main/java/imagejai/engine/JobRegistry.java`.
- `src/main/java/imagejai/engine/TCPCommandServer.java` (TODO: locate macro mutex, sync/async macro, script, cancellation, and shutdown paths).
- `src/main/java/imagejai/engine/safeMode/DestructiveScanner.java`.
- `src/main/java/imagejai/engine/safeMode/SourceImageTagger.java`.
- `src/main/java/imagejai/engine/SessionUndo.java` and `SessionCodeJournal.java`.

## Scope

- Define one coordinator API for mutation admission and ownership.
- Acquire bounded capacity before worker creation.
- Centralize safety scan, serialization, owner session, timeout, cancellation, undo, provenance, and terminal completion.
- Mark cancellation/timeout terminal only after worker exit is observed.
- Prevent submission after shutdown and use cryptographically unpredictable session-scoped job IDs.
- Replace reliance on global `IJ.Macro.abort()` where ownership cannot be proven; otherwise serialize and verify the target worker.

## Out of scope

- Migrating every execution surface belongs to stages 09, 10, 11, and 13.
- Undo snapshot fidelity belongs to stage 11.
- Connection/request limits belong to stage 23.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/MutationCoordinator.java` | NEW | Own mutation lifecycle and policy. |
| `src/main/java/imagejai/engine/JobRegistry.java` | MODIFY | Delegate capacity, IDs, ownership, and terminal state. |
| `src/main/java/imagejai/engine/CommandEngine.java` | MODIFY | Use coordinator execution primitives. |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Construct coordinator and route core entry points. |
| `src/main/java/imagejai/engine/MutationCoordinatorTest.java` | NEW | Prove non-overlap and worker-exit semantics. |
| `src/test/java/imagejai/engine/QueueStormGuardTest.java` | MODIFY | Cover admission-before-thread creation and shutdown. |

## Implementation sketch

Expose one request object containing owner session, source kind, code/operation, timeout, undo/provenance flags, and cancellation handle. Return a job handle whose state machine distinguishes `CANCEL_REQUESTED`, `WORKER_EXITED`, and terminal result. Capacity permits and the mutation lock are released only in the worker's finalizer. Inject clocks/executors in tests; never infer exit from `Future.isDone()` alone.

## Exit gate

1. Concurrent mutation tests prove at most one ImageJ mutation body runs at once.
2. A timed-out/cancelled worker cannot mutate after another job begins.
3. Capacity is rejected before a worker/thread exists, and submit-after-stop fails.
4. Job IDs are unguessable, session-owned, and another session cannot query/cancel them.
5. Safety, undo, provenance, and completion hooks run exactly once on success/failure/cancel.

## Known risks

- ImageJ abort APIs are global; retain strict serialization until worker ownership is demonstrable.
- Avoid deadlocking the Swing event thread while waiting for worker exit.
