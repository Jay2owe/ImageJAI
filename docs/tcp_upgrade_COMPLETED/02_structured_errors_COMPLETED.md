# Step 02 — Structured Error Objects

## Motivation

Today every failure path returns `{"error": "some string"}`. The
string is often excellent, but it gives the agent no signal on
whether a retry is safe, what category of failure this is, or
what specific action might recover. Transcripts show Gemma
retrying the same macro seven times on errors that will never
succeed — the string said "Macro Error line 36" and she kept
thinking a different threshold would fix it.

Replacing the string with an object unlocks three things: retry
control via `retry_safe: false`, category-based branching in the
agent, and a `suggested[]` list the server can populate when it
already knows the fix (e.g. fuzzy match results from step 04).

## End goal

When a command fails, the reply is:

```json
{"ok": true, "result": {
  "success": false,
  "error": {
    "code": "MACRO_BLOCKED_ON_DIALOG",
    "category": "blocked",
    "retry_safe": false,
    "message": "Macro paused on modal dialog 'Gaussian Blur...'",
    "recovery_hint": "Probe the plugin and pass explicit args.",
    "suggested": [
      {"command": "probe_command",
       "args": {"plugin": "Gaussian Blur..."}}
    ],
    "sideEffects": {
      "newImages": ["blobs-1"],
      "resultsChanged": true
    }
  }
}}
```

For backwards compatibility with `ij.py` clients that inspect
`result.error` as a string (documented in `agent/CLAUDE.md` Error
Handling), clients that didn't call `hello` continue to receive
a plain string. Only clients that negotiated structured errors
get the object.

## Scope

In:
- Five error categories: `compile`, `runtime`, `blocked`,
  `not_found`, `state`.
- An `ErrorCode` enum (string constants) used throughout the
  server.
- An `ErrorBuilder` helper to produce the JSON consistently.
- Rewriting every `addProperty("error", ...)` call site in
  `TCPCommandServer.java` and its helpers to use the builder.
- `sideEffects` carries through the info today's reply already
  captures (`newImages`, `resultsChanged`, `logDelta`, etc.) so
  the agent doesn't lose them on failure.
- Capability gate: if `caps.structured_errors` is off (the legacy
  default), emit the old string shape. The builder produces both.

Out:
- The `suggested[]` list is defined but only populated where the
  server already knows the answer — initially just fuzzy-match
  results from step 04 and nResults warnings from step 06. Other
  `suggested[]` sources come later.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — every call to `addProperty("error", ...)` and
  `detectIjMacroError`. Count them; plan the sweep.
- `agent/ij.py` — how error strings are currently parsed and
  displayed.
- `docs/tcp_upgrade/01_hello_handshake.md` — caps shape.

## Error categories

| Category | Meaning | Typical `retry_safe` |
|---|---|---|
| `compile` | Macro parse error, bad syntax | false (unless agent fixes code) |
| `runtime` | Exception during execution | depends — runtime out-of-memory is true once memory is freed; array-index-out-of-bounds is false until code changes |
| `blocked` | Modal dialog or user-input gate | false until dialog handled |
| `not_found` | Plugin/file/image does not exist | false (fuzzy match may supply `suggested[]`) |
| `state` | Preconditions not met (no image open, wrong bit depth) | true after state is fixed |

The agent uses `category` + `retry_safe` to decide what to do.
The `recovery_hint` is for the LLM to read and reason about.

## Error codes (initial set)

A handful of codes, each with a stable string identifier:

- `MACRO_COMPILE_ERROR`
- `MACRO_RUNTIME_ERROR`
- `MACRO_BLOCKED_ON_DIALOG`
- `PLUGIN_NOT_FOUND`
- `IMAGE_NOT_OPEN`
- `WRONG_IMAGE_TYPE`
- `NO_SELECTION`
- `NRESULTS_TRAP` (step 06 populates this)
- `REHEARSAL_FAILED` (safe-mode docs)
- `DESTRUCTIVE_OP_BLOCKED` (safe-mode docs)

Add new codes as steps land; this step ships only the first seven.

## Code sketch

```java
final class ErrorReply {
    final String code;
    final String category;
    final boolean retrySafe;
    final String message;
    final String recoveryHint;
    final List<JsonObject> suggested = new ArrayList<>();
    JsonObject sideEffects = null;

    // build() returns either a JsonObject (structured) or a
    // JsonPrimitive String (legacy), depending on caps.
    Object build(AgentCaps caps) {
        if (caps != null && caps.structuredErrors) {
            JsonObject o = new JsonObject();
            o.addProperty("code", code);
            o.addProperty("category", category);
            o.addProperty("retry_safe", retrySafe);
            o.addProperty("message", message);
            if (recoveryHint != null)
                o.addProperty("recovery_hint", recoveryHint);
            if (!suggested.isEmpty()) {
                JsonArray arr = new JsonArray();
                suggested.forEach(arr::add);
                o.add("suggested", arr);
            }
            if (sideEffects != null) o.add("sideEffects", sideEffects);
            return o;
        }
        return message;  // legacy string
    }
}
```

Call-site migration pattern:

```java
// before
result.addProperty("error", "No image open");

// after
result.add("error",
    new ErrorReply()
        .code("IMAGE_NOT_OPEN").category("state").retrySafe(true)
        .message("No image open")
        .recoveryHint("Open an image first, then retry.")
        .buildJsonElement(caps));
```

`buildJsonElement` returns a `JsonElement` (either `JsonObject`
or `JsonPrimitive`), so the `.add()` call works either way.

## Python side

`ij.py` — update error parsing to accept either a string or an
object:

```python
err = resp.get("result", {}).get("error")
if isinstance(err, dict):
    # structured
    return {"ok": False, "error": err}
elif isinstance(err, str):
    # legacy
    return {"ok": False, "error": {"code": "LEGACY",
                                   "category": "runtime",
                                   "retry_safe": True,
                                   "message": err}}
```

The wrapper always exposes an object downstream so agent code
doesn't have to branch. This also means Gemma's loop (in
`agent/gemma4_31b/loop.py`) can read `err["retry_safe"]`
uniformly.

## Agent-side handling

In `gemma4_31b/loop.py`, any error with `retry_safe: false` skips
the "maybe try again" branch and surfaces the `recovery_hint`
directly. This is the single largest loop-breaker — see the
transcripts that motivated the change.

## Tests

- `IMAGE_NOT_OPEN` produced on empty state: both legacy string
  and structured object emitted correctly for respective caps.
- `MACRO_COMPILE_ERROR`: `retry_safe: false`.
- `MACRO_RUNTIME_ERROR` with OOM: `retry_safe: true`.
- `MACRO_BLOCKED_ON_DIALOG`: `sideEffects.newImages` preserved.
- A `suggested[]` entry round-trips through `ij.py` unchanged.

## Failure modes

- A call site missed during the sweep still emits the old shape.
  Mitigation: add a unit test that grep's the built JAR for
  `addProperty("error"` and fails if any remain outside
  `ErrorReply`.
- Agents pre-dating this change crash on unexpected object shape.
  Mitigation: caps gate. No-hello = legacy string.
- Over-specific `retry_safe: false` prevents a legitimate retry
  after the agent fixed the code. Mitigation: `retry_safe` is
  defined per-error-code, documented in this file, and reviewed
  when a new code is added.
