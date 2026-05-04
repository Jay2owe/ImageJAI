# Stage 01 — Fix the batch / run / intent bypass

Thread the caller's `AgentCaps` through nested `dispatch(JsonObject)`
calls so commands routed via `batch`, `run` (chain), and `intent`
inherit the same caps as the originating socket. Without this, every
later safe-mode guard can be defeated by wrapping a destructive macro
inside `{"command": "batch", "commands": [...]}`.

## Why this stage exists

This is a current bug, not a feature — the original safe-mode plan
assumed gates on `handleExecuteMacro` would catch all macro paths,
but nested dispatch silently substitutes `DEFAULT_CAPS` (where
`safeMode = false`). Until this is fixed, every other stage in
`safe_mode_v2/` has a hole. Stage 02 builds the master switch on top
of this.

## Prerequisites

None. Foundation stage.

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `docs/safe_mode/03_integration.md` (existing handshake spec)
- `src/main/java/imagejai/engine/TCPCommandServer.java`,
  specifically:
  - dispatch ladder around lines 1108–1226
  - caps lookup at lines 1112–1114
  - `handleBatch` ≈ 4410, `handleRunChain` ≈ 4453, `handleIntent` ≈ 1175 (verify line numbers — file is ~7100 lines and shifts)
  - `dispatch(JsonObject)` overload that nested calls use
- `CLAUDE.md` (project root) house rules

## Scope

- Add a `dispatch(JsonObject request, AgentCaps caps)` overload.
- Make the existing single-arg `dispatch(JsonObject)` resolve caps
  from the socket as today, then call the new overload.
- In `handleBatch`, `handleRunChain`, `handleIntent` (and any other
  handler that calls `dispatch(subReq)`), pass the caller's caps
  through.
- Add a unit test for each handler proving a destructive macro
  inside a batch is blocked when the outer caller has `safe_mode =
  true`, and not blocked when the outer caller has `safe_mode =
  false`.

## Out of scope

- The destructive scanner itself (Stage 05).
- The master-switch UI (Stage 02).
- Async-mode behaviour for queue storm (Stage 04).
- Any new caps fields beyond what already exists in `AgentCaps`.

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Add caps-threaded `dispatch` overload; update `handleBatch`/`handleRunChain`/`handleIntent` |
| `src/test/java/imagejai/engine/TCPCommandServerBatchCapsTest.java` | NEW | Regression test proving caps inheritance |

## Implementation sketch

```java
// New overload — single source of truth for command routing.
private JsonObject dispatch(JsonObject request, AgentCaps caps) {
    String command = request.get("command").getAsString();
    // ... existing if-ladder, but using `caps` instead of looking up
    // by socket. Pass `caps` to handlers that already accept it.
}

// Existing single-arg overload now thin:
private JsonObject dispatch(JsonObject request) {
    AgentCaps caps = capsBySocket.getOrDefault(currentSock(), DEFAULT_CAPS);
    return dispatch(request, caps);
}

// In handleBatch (~line 4410):
for (JsonElement step : request.getAsJsonArray("commands")) {
    JsonObject subReq = step.getAsJsonObject();
    JsonObject result = dispatch(subReq, caps); // <-- thread caps
    // accumulate as today
}
```

Same one-line change in `handleRunChain` (`dispatch(parsed, caps)`)
and `handleIntent` (anywhere it re-enters `dispatch`).

## Exit gate

1. `mvn compile` clean, no new warnings.
2. New `TCPCommandServerBatchCapsTest` passes:
   - Caller with `safe_mode=true` sending `batch: [{execute_macro,
     File.delete(...)}]` returns `DESTRUCTIVE_OP_BLOCKED`.
   - Caller with `safe_mode=false` sending the same payload runs
     (assuming a test that doesn't actually delete — use a mock or a
     `print()` macro and assert the gate didn't fire).
3. Existing `TCPCommandServerHelloTest` and any current batch tests
   still pass.
4. Manual: build the JAR, deploy to Fiji, send a `batch` containing
   a `print("hello")` from a test agent — same behaviour as before
   (no regression).

## Known risks

- The dispatch ladder is 100+ lines of if/else; refactoring it into
  a registry is tempting but **out of scope** for this stage. Keep
  the change minimal: add the overload, route through it, leave the
  ladder shape alone.
- `handleIntent` may delegate to a separate helper method — search
  for any `dispatch(` call site and thread caps through every one.
- The currently-running socket may be `null` for events injected from
  scheduled tasks (e.g. reactive rules). Existing fallback to
  `DEFAULT_CAPS` is correct in that path; don't break it.
