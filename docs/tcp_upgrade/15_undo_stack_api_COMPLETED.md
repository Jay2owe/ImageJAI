# Step 15 — Undo Stack as API

## Motivation

Agents treat every `execute_macro` as a commitment. If a
threshold comes out wrong, the only recovery is to reload the
image from disk — a full round-trip that burns a turn, plus
whatever downstream state (ROIs, results) was also lost.

When rewind is cheap, experimentation goes up and brittleness
goes down. An agent can try Otsu, compare to Triangle, decide
Otsu was right, revert the Triangle run, move on. Today that
same flow costs three file loads.

## End goal

The server maintains a per-image rolling stack of the last N
pixel states (compressed) plus side-state (ROI Manager snapshot,
Results table snapshot). Two new commands:

### `rewind`
```json
{"command": "rewind", "n": 2}
// or
{"command": "rewind", "to_call_id": "c-42"}
// Response
{"ok": true, "result": {
  "rewound": 2,
  "activeImage": "blobs",
  "restoredROIs": 12,
  "restoredResultsRows": 0
}}
```

### `branch`
```json
{"command": "branch", "from_call_id": "c-42"}
// Response
{"ok": true, "result": {
  "branchId": "b-3",
  "baseCallId": "c-42"
}}
```

A branch lets the agent go back to a known-good state and try
a different path, without losing the original path's history.

## Scope

In:
- Per-image undo buffer — compressed pixel state plus metadata.
- Side-state snapshots — ROI Manager count/names/bounds, Results
  table CSV, active-image title.
- Integration in `handleExecuteMacro`: snapshot *before* every
  mutating command.
- Two new handlers: `rewind`, `branch`.
- Memory budget: ~5 undo states per image, compressed. Total cap
  ~500 MB before LRU eviction per session.
- Capability gate `caps.undo` — default `false` (off until
  explicitly opted in, because memory cost is real).

Out:
- Disk side-effect reversal. A macro that wrote a file cannot
  be undone by this system. Document that clearly.
- Script-run (Groovy/Jython) undo. Those can do anything
  including touching files; mark them as "branch boundary" —
  rewind past a script run is disallowed.
- Undoing the ROI Manager beyond snapshot-and-restore. Good
  enough for the common case.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — `handleExecuteMacro` mutation points.
- `src/main/java/imagejai/engine/ImageMonitor.java`
  — image state tracking.
- `docs/tcp_upgrade/13_provenance_graph.md` — the graph gives
  us call IDs to reference.
- Java `ImagePlus.duplicate()` and `ImageProcessor` snapshot
  APIs.

## Data model

```java
public final class UndoFrame {
    final String callId;           // from provenance graph
    final String imageTitle;
    final byte[] compressedPixels; // zstd or similar
    final int bitDepth;
    final int width, height, slices;
    final List<RoiSnapshot> rois;
    final String resultsCsv;
    final long timestampMs;
    final long sizeBytes;
}

public final class UndoStack {
    private final Deque<UndoFrame> frames = new ArrayDeque<>();
    private final int maxFrames = 5;
    private final long maxBytes = 100 * 1024 * 1024; // per image
    public void push(UndoFrame f) { ... }
    public UndoFrame pop() { ... }
    public UndoFrame peek(int n) { ... }
    public UndoFrame findByCallId(String id) { ... }
}

public final class SessionUndo {
    final Map<String, UndoStack> byImageTitle = new HashMap<>();
    final long globalCapBytes = 500 * 1024 * 1024;
    long currentBytes = 0;
}
```

Before every mutating command:

```java
if (caps.undo) {
    UndoFrame f = captureFrame(currentCallId, activeImp);
    caps.sessionUndo.push(activeImp.getTitle(), f);
}
```

Frames are compressed on a background thread to avoid blocking
the macro path. If compression can't keep up with macro rate,
drop oldest frames rather than block.

## Rewind semantics

`rewind n=2`:
- Pop top frame (the pre-current state).
- Pop one more.
- Restore: replace active image pixels, re-apply ROI snapshot,
  re-apply Results table.
- Return diff to agent.

`rewind to_call_id="c-42"`:
- Walk back to the frame captured before `c-42`, dropping
  intermediate frames.
- Same restore logic.

Rewind is not reversible. Once you rewind past a frame, that
frame is gone. If the agent wants to preserve a path before
exploring, use `branch` first.

## Branch semantics

`branch from_call_id="c-42"`:
- Take a deep copy of the state-at-call-id.
- Register as a new branch with its own `UndoStack`.
- Agent now operates on the branch; the original path's history
  is preserved and retrievable by `branch_list` + `branch_switch`.

Additional commands (ship with the branching piece):

- `branch_list` — show all branches, their base call IDs,
  how many frames each holds.
- `branch_switch` — activate a different branch.
- `branch_delete` — discard a branch (reclaim memory).

## Memory management

- Per-image cap: 5 frames or 100 MB, whichever hits first.
- Per-session global cap: 500 MB.
- On overflow: LRU-evict oldest frame globally. Log to
  FrictionLog so we see if caps are too tight.
- Script runs and long-duration macros are also tracked; no
  special rules beyond the caps.

Compression: zstd level 3 on pixel bytes. ROI and Results
snapshots are small enough uncompressed.

Cost budget: a 4K × 4K × 16-bit image is 32 MB uncompressed, ~8
MB compressed. Five frames = 40 MB per image. A session with
five active images hits the global cap; that's the reasonable
worst case.

## Capability gate

`caps.undo` — default `false` for all agents initially. Opt-in
via `hello`. The memory cost is the reason — agents that don't
need rewind shouldn't pay for it.

When enabled, Claude and Codex benefit most (they'll actually
use branching). Gemma benefits less (usually pushes forward).

## Tests

- Push 5 frames, rewind 1 → state matches pre-5th state.
- Push 6 frames → 1st is dropped, rewind 5 → state matches
  pre-2nd state, not pre-1st.
- Rewind then push new frame → old post-rewind frames are
  discarded (common undo semantics).
- Branch from call id → new stack independent of original.
- Branch switch preserves both stacks.
- Memory cap triggers eviction under load; telemetry entry
  recorded.
- Disk-side-effects macro (e.g. `saveAs`) warns that rewind
  won't undo the file.
- `caps.undo == false` → commands fail gracefully with
  `UNDO_DISABLED` error.

## Failure modes

- Macro with disk side effects can't be fully undone. Document
  clearly; emit a warning when a rewind-tracked frame contains
  disk writes.
- Memory blowup on large stacks. Hard caps are the defence.
- Non-pixel state we forget to snapshot (window positions, LUTs,
  user selections). List what's captured in the `caps.undo`
  contract; be explicit about what isn't.
- Concurrent rewind during an in-flight macro. Reject with
  `UNDO_BUSY` error — serialise via the existing macro mutex.
- Branch proliferation (agent creates 50 branches). Cap at 10
  branches per session; oldest-LRU evict, require explicit
  `branch_delete` to go over.

## Why this is Phase 3

This is the largest code change in the plan and the first one
that fundamentally alters the concurrency model (branches as
first-class objects). Ship Phase 1 and Phase 2 first; the real
quality-of-life wins from those are enough on their own. Only
revisit undo-as-API after we've seen how agents use the simpler
feedback features.
