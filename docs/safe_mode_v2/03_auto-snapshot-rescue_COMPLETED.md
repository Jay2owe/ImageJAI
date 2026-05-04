# Stage 03 — Auto-snapshot rescue frame

Before every safe-mode `execute_macro` (and `run_script`,
`run_pipeline`), capture a one-frame snapshot of the active image,
ROI Manager, and Results table. Discard on success. Commit to the
undo stack on failure, so the agent or user can `rewind` immediately
even without negotiating undo caps.

## Why this stage exists

Macros that pass the destructive scanner and rehearsal can still
corrupt pixels at full scale — the size-dependent failure modes
rehearsal can't predict (Gaussian sigma=50 instead of 5, accidental
threshold-then-Convert-to-Mask on the wrong image). The existing
`SessionUndo` / `UndoFrame` infrastructure already handles
zlib-compressed pixel snapshots; we just need to flip it on by
default for safe-mode calls. Free undo for the biologist with no new
machinery.

## Prerequisites

- Stage 01 (`_COMPLETED`)
- Stage 02 (`_COMPLETED`) — needs `caps.safeModeOptions.autoSnapshotRescue`

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `docs/safe_mode/01_rehearsal.md` (existing rehearsal spec —
  this stage hooks the same call site, AFTER rehearsal passes)
- `src/main/java/imagejai/engine/SessionUndo.java` — the
  ring buffer (`GLOBAL_CAP_BYTES`, eviction policy)
- `src/main/java/imagejai/engine/UndoFrame.java` —
  capture format, zlib compression
- `src/main/java/imagejai/engine/TCPCommandServer.java`:
  - `handleExecuteMacro` ≈ line 2678
  - `captureUndoFrameIfEnabled` call ≈ line 2738
  - `handleRunScript` ≈ 3550, `handleRunPipeline` ≈ 3992
  - `handleRewind` ≈ 2145 (already wired; we're just feeding it
    snapshots)

## Scope

- Add `SessionUndo.captureRescueFrame(callId)` returning a
  `Closeable` (or a `RescueHandle` with `commit()` / `release()`).
- In `handleExecuteMacro`, when `caps.safeMode &&
  caps.safeModeOptions.autoSnapshotRescue && !caps.undo`, capture
  a rescue frame BEFORE entering the `MACRO_MUTEX` block; release on
  success, commit on any thrown exception or `success: false` reply.
- Same for `handleRunScript` and `handleRunPipeline` (each step in a
  pipeline gets its own rescue frame).
- Memory budget: rescue frames count against the existing
  `SessionUndo.GLOBAL_CAP_BYTES`. Existing eviction policy handles
  it. Surface a warning in the reply if we evicted to make room.

## Out of scope

- A new TCP `rewind` command — already exists at `handleRewind`.
- A user-facing "Undo last AI action" toolbar button — that's UI
  work and lives in Stage 07 if at all.
- Snapshotting non-image state beyond ROIs and Results
  (Recorder log, console buffer) — defer.

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/SessionUndo.java` | MODIFY | Add `captureRescueFrame(callId)` returning a handle |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Wire rescue capture into `handleExecuteMacro`, `handleRunScript`, `handleRunPipeline` |
| `src/test/java/imagejai/engine/SessionUndoRescueTest.java` | NEW | Verify rescue frame committed on failure, released on success |

## Implementation sketch

```java
// SessionUndo.java
public RescueHandle captureRescueFrame(String callId) {
    UndoFrame frame = UndoFrame.capture(activeImp, roiManager, results, callId);
    return new RescueHandle(this, frame);
}

public static final class RescueHandle implements AutoCloseable {
    private final SessionUndo undo;
    private final UndoFrame frame;
    private boolean committed = false;
    public void commit() { undo.pushFrame(frame); committed = true; }
    public void release() { /* discard frame, free zlib buffer */ }
    @Override public void close() { if (!committed) release(); }
}
```

```java
// handleExecuteMacro (around 2738):
boolean useRescue = caps.safeMode
    && caps.safeModeOptions.autoSnapshotRescue
    && !caps.undo;  // undo path already snapshots
SessionUndo.RescueHandle rescue = useRescue
    ? sessionUndo.captureRescueFrame(undoCallId)
    : null;
try {
    // ... existing rehearsal + MACRO_MUTEX block ...
    if (replyIsSuccess(reply)) {
        if (rescue != null) rescue.release();
    } else {
        if (rescue != null) rescue.commit();
    }
    return reply;
} catch (Throwable t) {
    if (rescue != null) rescue.commit();
    throw t;
}
```

## Exit gate

1. `mvn compile` clean.
2. New `SessionUndoRescueTest` passes:
   - Successful macro → rescue frame released, `SessionUndo.size()`
     unchanged.
   - Macro that throws → rescue frame committed; subsequent `rewind`
     restores pixels.
   - `caps.undo == true` → rescue path skipped (undo path already
     captures).
3. Manual: in Fiji, run a `Gaussian Blur sigma=50` via the agent on
   a small test image; call `rewind` and confirm pixels restored.
4. Memory: 50 consecutive macros on a 4 MB image stay within
   `GLOBAL_CAP_BYTES` (existing eviction proven by current
   `SessionUndo` tests).

## Known risks

- Disk-backed virtual stacks: `ImagePlus.duplicate()` may trigger a
  full read from disk (slow, GBs). Detect via `imp.getStack()
  instanceof VirtualStack` and skip rescue with a logged note.
- Plugins with native-side state (3D Viewer, Cellpose) won't be
  fully restored. Acknowledge in the reply when a rescue commit
  happens on an image with such plugins active.
- Memory pressure on already-tight Fiji JVMs — rely on
  `GLOBAL_CAP_BYTES` eviction; never let rescue frames OOM.
