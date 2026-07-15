# Atomic undo, branches, and exploration transactions

## Why this stage exists

Undo can discard its frame before a failed restore and validates too little image structure. Exploration can select or close a user's image by a predictable title, overwrite Results/measurement preferences, compute invalid coverage, and lose rewind frames.

## Prerequisites

- Stage 09 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, release blockers 6 and 7.
- `src/main/java/imagejai/engine/SessionUndo.java`, `UndoFrame.java`, and `UndoStack.java`.
- `src/main/java/imagejai/engine/ExplorationEngine.java`.
- Existing `SessionUndo*Test.java`, `UndoFrameTest.java`, and `UndoStackTest.java`.

## Scope

- Validate image identity/type, width/height, channels/slices/frames, raw lengths, calibration, ROIs, Results, and all captured planes before restore/pop.
- Keep failed undo/rewind frames available.
- Make branches real atomic checkpoints and honor `from_call_id`.
- Track exploration temporary images by object identity plus random internal ID.
- Snapshot/restore current image, ROI Manager, Results, and measurement settings in `finally`.
- Validate binary masks before binary coverage; label other metrics truthfully.

## Out of scope

- General state command correctness belongs to stage 12.
- Coordinator admission/cancellation is already owned by stages 05/09.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/SessionUndo.java` | MODIFY | Validate then atomically restore/pop. |
| `src/main/java/imagejai/engine/UndoFrame.java` | MODIFY | Capture complete restorable state. |
| `src/main/java/imagejai/engine/UndoStack.java` | MODIFY | Preserve failed frames and real branch checkpoints. |
| `src/main/java/imagejai/engine/ExplorationEngine.java` | MODIFY | Isolate temporary images/global state and rewind. |
| `src/test/java/imagejai/engine/SessionUndoTest.java` | MODIFY | Add mismatch and partial-failure cases. |
| `src/test/java/imagejai/engine/SessionUndoRescueTest.java` | MODIFY | Prove failed frame retention. |
| `src/test/java/imagejai/engine/UndoFrameTest.java` | MODIFY | Cover hyperstack/type/results fidelity. |
| `src/test/java/imagejai/engine/ExplorationEngineTest.java` | NEW | Protect pre-existing images and global state. |

## Implementation sketch

Use a two-phase restore: resolve/validate every target and snapshot component, then apply; pop only after all application steps succeed. A branch owns a restorable checkpoint, not a history label. Exploration records actual `ImagePlus` identities, closes only those identities, and restores globals in a nested `try/finally`. Binary coverage rejects nonbinary/non-8-bit masks rather than applying `mean / 255` generically.

## Exit gate

1. Closed/replaced/same-size-different-type/hyperstack mismatch failures leave the undo frame intact.
2. Success restores all documented planes, dimensions, calibration, ROI state, and Results exactly.
3. Branch checkout restores the selected checkpoint and honors `from_call_id` atomically.
4. A user image sharing the temporary title is never selected, modified, or closed.
5. Exploration restores current image, ROI Manager, Results, and measurement preferences on success and failure.

## Known risks

- Snapshot memory limits must fail before mutation rather than creating partial snapshots.
- ImageJ global Results/ROI APIs require careful event-thread coordination; do not weaken atomicity to avoid it.
