# Truthful image and state commands

## Why this stage exists

Several server commands can return success for the wrong image or leave Fiji state changed. Image provenance also relies on titles/unordered sets, and queued Swing work may execute after the caller has timed out.

## Prerequisites

- Stages 04 and 09 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Image and state commands".
- `src/main/java/imagejai/engine/TCPCommandServer.java` (TODO: locate `open_image`, `get_pixels`, batch, probe, dialog dispatch, and result envelope handlers).
- `src/main/java/imagejai/engine/ImageGraph.java` and `ImageMonitor.java`.
- `src/main/java/imagejai/engine/GuiActionDispatcher.java`.
- `src/main/java/imagejai/engine/PipelineBuilder.java`.

## Scope

- Prove the requested image opened; do not accept a pre-existing active image.
- Bound pixel allocation before reading and restore original C/Z/T position in `finally`.
- Detect new/in-place images by stable object identity and deterministic ordering.
- Preserve completed batch results and identify a throwing subcommand index.
- Invalidate queued Swing actions after timeout so they cannot execute late.
- Restrict plugin probes to cancellable dialogs and report side-effect risk accurately.
- Make temporary script names/paths safe and locale-independent.

## Out of scope

- Flood and global buffer caps belong to stage 23.
- Reactive rule behavior belongs to stage 13.
- Dataset hash persistence belongs to stage 14.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Correct command success/state behavior. |
| `src/main/java/imagejai/engine/ImageGraph.java` | MODIFY | Stable identity and in-place provenance. |
| `src/main/java/imagejai/engine/ImageMonitor.java` | MODIFY | Deterministic identity-based image events. |
| `src/main/java/imagejai/engine/GuiActionDispatcher.java` | MODIFY | Cancel stale queued UI actions. |
| `src/main/java/imagejai/engine/PipelineBuilder.java` | MODIFY | Preserve deterministic stage/batch results. |
| `src/main/java/imagejai/engine/ScriptGenerator.java` | MODIFY | Prevent traversal/CRLF and locale drift. |
| `src/test/java/imagejai/engine/TCPCommandServerStateDeltaTest.java` | MODIFY | Exercise image/pixel/batch state failures. |
| `src/test/java/imagejai/engine/ImageGraphTest.java` | MODIFY | Cover identities, in-place operations, and order. |

## Implementation sketch

Capture the pre-open image identity set and require a newly opened identity associated with the requested path. For pixels, validate dimensions/byte budget first, save position, and restore it even on encoding failure. Give every queued GUI action a cancellable token checked immediately before execution. Probe only dialogs with a verified cancel/close route and return an explicit `side_effect_risk` field.

## Exit gate

1. Failed open with another active image returns failure and leaves that image untouched.
2. Pixel success/failure restores C/Z/T and rejects oversized allocation before reading.
3. New and in-place operations produce stable, deterministically ordered provenance.
4. Timed-out GUI work cannot run later; batch failures retain prior indexed results.
5. Unsupported probe dialogs fail clearly without executing the plugin action.

## Known risks

- Bio-Formats may open asynchronously; wait on identity/event evidence with a bounded deadline, not a fixed sleep.
- Some custom dialogs have no reliable cancel path and must be rejected rather than guessed.
