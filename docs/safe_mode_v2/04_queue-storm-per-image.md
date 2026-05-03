# Stage 04 — Queue-storm guard, scoped per-image

Block a second `execute_macro` targeting an image that already has
a macro paused on a Fiji modal dialog. Allow concurrent macros on
*different* images (async-mode-friendly). Catches the
ghost-dialog-queued-macros disaster without preventing legitimate
parallel pipelines.

## Why this stage exists

A real transcript shows Gemma firing 16 macros while macro #1 was
paused on a "Convert Stack?" dialog — each macro spawned a duplicate
`ch2_final-0001…0009`, polluting state and causing macro #16 to fire
against the wrong image. The fix is mechanically simple: detect the
"paused on dialog" state and block further macros targeting the same
image. Smart async agents running parallel pipelines on *different*
images are unaffected.

## Prerequisites

- Stage 01 (`_COMPLETED`)
- Stage 02 (`_COMPLETED`) — needs `caps.safeModeOptions.queueStormGuard`

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `src/main/java/imagejai/engine/TCPCommandServer.java`:
  - `MACRO_MUTEX` and macro-running tracking
  - `handleExecuteMacro` ≈ 2678
  - `handleExecuteMacroAsync` ≈ 4598 (async path; this guard must
    apply equally)
  - `handleInteractDialog` ≈ 6290 (dialog-dismissal path — must
    bypass the guard so the agent can recover)
- Phantom-dialog detection: search for `PhantomDialogDetector` —
  existing code that knows when a macro is stuck on a dialog
- `docs/safe_mode/02_destructive_block.md` for response shape pattern

## Scope

- Maintain a `Map<String /* imageTitle */, ActiveMacro>`
  `inFlightByImage` keyed on the macro's target image title (taken
  from the most recent `selectImage` arg, or active image when the
  macro begins).
- When `execute_macro` arrives and `caps.safeModeOptions.queueStormGuard`:
  - Resolve the target image title.
  - If `inFlightByImage[title]` exists AND its state is
    `PAUSED_ON_DIALOG` → return `QUEUE_STORM_BLOCKED` with the
    blocking macro's ID and the dialog title.
  - If `inFlightByImage[title]` exists AND its state is `RUNNING` →
    standard behaviour (serialised by `MACRO_MUTEX` as today).
  - If the title differs (different image) → allow concurrent.
- Update state transitions: macro start → `RUNNING`; dialog detected
  → `PAUSED_ON_DIALOG`; macro return → remove entry.
- `handleInteractDialog` always bypasses the guard (that's the
  recovery path).

## Out of scope

- Multi-agent / multi-socket coordination beyond per-image scope.
- Dialog auto-dismissal (already handled elsewhere).
- A `cancel_queued` TCP command — defer; `handleInteractDialog`
  is enough recovery for v2.

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | `inFlightByImage` map, queue-storm check at top of `handleExecuteMacro`/`handleExecuteMacroAsync` |
| `src/main/java/imagejai/engine/ErrorReply.java` | MODIFY | Add `CODE_QUEUE_STORM_BLOCKED` constant |
| `src/test/java/imagejai/engine/QueueStormGuardTest.java` | NEW | Cover same-image blocked, different-image allowed, dialog-bypass |

## Implementation sketch

Response shape on block:

```json
{"ok": true, "result": {
  "success": false,
  "error": {
    "code": "QUEUE_STORM_BLOCKED",
    "category": "blocked",
    "retry_safe": false,
    "message": "Macro #abc123 is paused on a 'Convert Stack?' dialog targeting 'ch2_final'. Refusing to queue another macro on the same image.",
    "blocking_macro_id": "abc123",
    "blocking_dialog_title": "Convert Stack?",
    "target_image": "ch2_final",
    "recovery_hint": "Either dismiss the dialog (interact_dialog), wait for macro #abc123 to finish, or run on a different image."
  }
}}
```

```java
// Sketch — in handleExecuteMacro at the top, after caps lookup:
if (caps.safeMode && caps.safeModeOptions.queueStormGuard) {
    String target = resolveTargetImageTitle(code); // parse last selectImage(...) arg, fall back to active image
    ActiveMacro inFlight = inFlightByImage.get(target);
    if (inFlight != null && inFlight.state == State.PAUSED_ON_DIALOG) {
        return queueStormBlockedReply(inFlight, target);
    }
}
// ... existing path ...
inFlightByImage.put(target, new ActiveMacro(callId, State.RUNNING));
try {
    return executeMacroInner(...);
} finally {
    inFlightByImage.remove(target);
}
```

State transition: hook `PhantomDialogDetector` (or whatever existing
code detects the paused state) to flip the entry to
`PAUSED_ON_DIALOG`.

## Exit gate

1. `mvn compile` clean.
2. New `QueueStormGuardTest` passes:
   - Macro on imageA paused on dialog → second macro on imageA
     returns `QUEUE_STORM_BLOCKED` with the right metadata.
   - Macro on imageA paused on dialog → macro on imageB succeeds
     (concurrent allowed).
   - Macro on imageA paused on dialog → `interact_dialog` clicks
     through (bypass works).
   - Guard off (`safe_mode=false` OR `queueStormGuard=false`) →
     no block, legacy serialisation behaviour.
3. Manual: reproduce the ghost-dialog scenario — open Fiji, send a
   macro that triggers a "Convert Stack?" dialog, send a second
   macro, confirm block + recover via `interact_dialog`.

## Known risks

- Resolving target image title is heuristic — macros that don't call
  `selectImage` use the current active image, which can drift if the
  user clicks elsewhere. Acceptable: the guard fires on the agent's
  *intended* target as best the server can know.
- Dialog detection lag: the existing detector may take a few hundred
  ms to flip state. A second macro arriving in that window slips
  through. Acceptable for v2.
- Async-mode wrappers must NOT serialise their own macros into one
  socket — confirm Python wrappers can fire concurrent
  `execute_macro_async` calls without local locking.
