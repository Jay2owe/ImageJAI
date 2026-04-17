# Phase 1 — State Dedup + Readonly Fast-Path

## Motivation

The **context hook** (`context_hook.py`) fires on every single user prompt. Each fire calls `get_state`, `get_image_info`, `get_results_table`, `get_log`, `get_dialogs` over TCP — 5+ round trips — and pastes ~1–2 KB of formatted state into Claude's context.

Two problems:

1. **Most of that state is identical to the previous call.** If the user types three messages in a row without touching Fiji, we re-serialise and re-inject the same 1500 tokens three times.
2. **Readonly queries travel the full dispatch path** — session logging, autopsy wrapping, state snapshotting, JSON pretty-printing — when they should be a direct inspector read.

Metaphor: today, every time you walk into the room, the receptionist hands you the full 40-page briefing pack even though only the last page changed, and she routes every "what time is it?" through the chief of staff before answering.

## End Goal

- `get_state` (and peers) return `{"ok": true, "unchanged": true, "hash": "<md5>"}` when nothing has changed since the client's last recorded hash.
- The client (`ij.py` / context hook) caches the last payload by hash and reuses it on `unchanged: true`.
- Readonly commands (`ping`, `get_state`, `get_image_info`, `get_log`, `get_results_table`, `get_histogram`, `get_open_windows`, `get_metadata`, `get_dialogs`) skip session logging + autopsy wrapping and resolve directly against `StateInspector`.

## Scope

### Server-side (`engine/TCPCommandServer.java`)

- Add a `StateHasher` helper that computes an MD5 over the canonical JSON of each "stateful read" command's payload.
- Each request may carry an optional `"if_none_match": "<hash>"` field. When supplied and matching, reply `{"ok": true, "unchanged": true, "hash": "<same>"}` — no payload.
- Maintain a `READONLY_COMMANDS` set. When the dispatch target is in this set, bypass logging, EDT-wrapping for state-reads that don't touch ImageJ internals (e.g. `ping`), and autopsy.

### Server-side (`engine/StateInspector.java`)

- Expose a lightweight `statePayloadHash()` short-circuit that can skip the full serialisation when the upstream cache is already warm. (Optional optimisation.)

### Client-side (`agent/ij.py` + `context_hook.py`)

- Maintain an in-memory `{"command_name": (hash, payload)}` cache.
- Add `--if-none-match` auto-resolution so `python ij.py state` sends the last hash automatically.
- On `unchanged: true`, return the cached payload as though it had been re-fetched.
- Persist the cache for the duration of the current shell session (simple dict; no disk).

## Implementation Sketch

```java
// TCPCommandServer.java
private static final Set<String> READONLY = new HashSet<>(Arrays.asList(
    "ping", "get_state", "get_image_info", "get_log", "get_results_table",
    "get_histogram", "get_open_windows", "get_metadata", "get_dialogs"
));

private JsonObject dispatchReadonly(JsonObject req) {
    String cmd = req.get("command").getAsString();
    JsonObject payload = invokeInspector(cmd, req);
    String hash = md5(payload.toString());
    String ifNone = req.has("if_none_match") ? req.get("if_none_match").getAsString() : null;
    if (hash.equals(ifNone)) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("unchanged", true);
        r.addProperty("hash", hash);
        return r;
    }
    JsonObject r = new JsonObject();
    r.addProperty("ok", true);
    r.add("result", payload);
    r.addProperty("hash", hash);
    return r;
}
```

```python
# ij.py
_LAST = {}  # cmd_name -> (hash, parsed_result)

def imagej_command(cmd, ...):
    name = cmd.get("command")
    if name in READONLY and name in _LAST:
        cmd = dict(cmd, if_none_match=_LAST[name][0])
    resp = _send(cmd)
    if resp.get("unchanged"):
        return {"ok": True, "result": _LAST[name][1], "cached": True}
    if resp.get("ok") and "hash" in resp:
        _LAST[name] = (resp["hash"], resp.get("result"))
    return resp
```

## Impact

- Context hook round-trip time drops from ~50ms → ~5–10ms on unchanged states.
- Claude conversation token cost drops materially on long sessions (the STATE block stops recurring verbatim).
- Zero behavioural change for clients that don't opt in — `if_none_match` is optional.

## Validation

1. `python ij.py state` twice in a row — second call returns `{"cached": true}`.
2. Touch an image (e.g. change slice), call again — returns fresh payload.
3. Benchmark: 100 successive `ij.py info` calls with no image change, wall time < 1s total.
4. Verify context hook still produces correct state when Fiji changes mid-session.

## Risks

- **Stale cache.** If the server's hash computation misses a dimension (e.g. ignores ROI changes), the client sees stale state. Mitigation: hash the full canonical JSON including ROI bounds, slice index, LUT min/max.
- **Concurrent clients.** Two agents sharing the cache would see each other's unchanged flags incorrectly. Mitigation: cache is server-side-computed but client-side-stored, so each client has its own view.
