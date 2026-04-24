# Integration with `handleExecuteMacro`

Order of operations when safe mode is active:

1. Receive `execute_macro` request with optional `destructive` field
2. `DestructiveScanner.scan(code)` — if any unconfirmed destructive
   ops, return `DESTRUCTIVE_OP_BLOCKED` and stop
3. `rehearse(code, thumbnail)` — if rehearsal fails, return
   `REHEARSAL_FAILED` and stop
4. Real execution (existing path)
5. Post-execution: existing `logDelta`, `newImages`, `stateDelta`,
   etc.

Fast-path: if the request comes over a socket whose `AgentCaps`
has `safe_mode == false`, skip steps 2 and 3 entirely. Existing
sessions pay zero cost.

## Handshake changes

The `hello` capability handshake gains two fields:

```json
{"command": "hello", "agent": "gemma-31b",
 "capabilities": {
    "safe_mode": true,
    "rehearsal": true,
    "destructive_policy": "reject"
 }}
```

- `safe_mode` — master switch. When false, both rehearsal and
  destructive scan are skipped regardless of the two fields below.
- `rehearsal` — defaults to true when `safe_mode` is on; can be
  set false if the agent is doing something size-dependent that
  thumbnails don't handle well.
- `destructive_policy` — `"reject"` (default), `"prompt"` (not yet
  implemented; reserved for a future interactive mode), `"allow"`
  (log-only, don't block — useful for debugging).

## Default policy per agent

Recommended defaults the agent wrappers should set in `hello`:

| Agent | safe_mode | rehearsal | destructive_policy |
|---|---|---|---|
| Gemma 4 31B | true | true | reject |
| Claude Code | true | true | reject |
| Codex | true | true | reject |
| Gemini | true | true | reject |

Safe mode is the default. Turning it off is opt-out, not opt-in.

## Backwards compatibility

A client that never sends `hello` (all current `ij.py` usage)
gets `AgentCaps.DEFAULT` which has `safe_mode = false`. Nothing
changes for existing sessions until their wrapper is updated to
call `hello`.

This gives a clean migration:

1. Ship the safe-mode server changes with `safe_mode = false` as
   the no-handshake default.
2. Teach Claude's `ij.py` to call `hello` with `safe_mode = true`.
3. Roll out transcript by transcript — revert per-agent if any
   wrapper breaks.
4. Once all four wrappers negotiate safe mode, flip the
   no-handshake default to `true` in a later release.

## FrictionLog entries

Every safe-mode event writes a `FrictionLog` row:

- `rehearsal.passed` / `rehearsal.failed` / `rehearsal.skipped`
- `destructive.blocked` / `destructive.confirmed` / `destructive.warned`

Rows include `agent_id`, `session_id`, and the operation signature.
Powers the per-agent safety dashboard and the improvement loop.
