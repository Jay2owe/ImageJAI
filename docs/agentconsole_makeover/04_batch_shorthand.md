# Phase 4 — Batch `|||` Shorthand

## Motivation

`batch` already exists as a TCP command (array of commands executed in one connection). Two frictions remain:

1. **JSON ceremony**: `{"command": "batch", "commands": [{"command": "execute_macro", "code": "open(...)"}, {"command": "execute_macro", "code": "run('Gaussian Blur...', 'sigma=2')"}, ...]}` is painful to hand-type or paste into a shell.
2. **Round-trip cost**: on Windows each TCP handshake is ~30–50ms. A 5-step workflow sent as 5 separate `ij.py macro` calls is ~200ms of pure handshake overhead.

AgentConsole's `|||` separator (`agent_contexts/TCP/batch.md`) is a string-level shorthand: `cmd1 ||| cmd2 ||| cmd3` parsed server-side into individual commands, executed in order, single response.

Metaphor: three letters in one envelope, one trip to the post box.

## End Goal

- `python ij.py run 'open("x.tif") ||| run("Gaussian Blur...", "sigma=2") ||| run("Measure")'` executes the three macros in order in a single TCP call and returns an array of results.
- Any result with `ok: false` halts the chain by default; `--continue-on-error` flag allows best-effort.
- Works for any macro-runnable string; also `||| capture` etc. — the shorthand dispatches by keyword to existing TCP commands.
- Preserves existing `batch` JSON API unchanged.

## Scope

### Server-side: `engine/BatchParser.java` (new)

- Splits on `|||` with escape support (`\|||` is literal).
- Each segment is dispatched by keyword: `macro <code>`, `capture [name]`, `measure`, etc. — mirrors the `ij.py` subcommand vocabulary so the shorthand feels consistent.
- Pure macro code with no keyword prefix is treated as `macro <code>` (the common case).

### Server-side: `engine/TCPCommandServer.java`

- New command `run {"chain": "cmd1 ||| cmd2"}` or plain string form `{"command": "run", "chain": "..."}`.
- Each segment logged separately for observability.
- `halt_on_error` field (default `true`).

### Client-side: `agent/ij.py`

- New subcommand `python ij.py run 'chain'`.
- Keeps existing `python ij.py batch` for the JSON array case.

## Implementation Sketch

```java
// BatchParser.java
public List<JsonObject> parse(String chain) {
    List<String> segments = splitUnescaped(chain, "|||");
    List<JsonObject> out = new ArrayList<>();
    for (String seg : segments) {
        seg = seg.trim();
        out.add(parseSegment(seg));
    }
    return out;
}

private JsonObject parseSegment(String s) {
    // `capture foo` → {command: capture_image, name: foo}
    // `measure`     → {command: execute_macro, code: "run('Measure');"}
    // anything else → {command: execute_macro, code: s}
    for (Keyword k : KEYWORDS) if (s.startsWith(k.prefix)) return k.build(s);
    JsonObject o = new JsonObject();
    o.addProperty("command", "execute_macro");
    o.addProperty("code", s);
    return o;
}
```

## Impact

- Quick 3–5 step workflows in the shell become a single-line invocation.
- Wall-clock speedup for typical pipelines: ~150–200ms saved per chained operation on Windows.
- Dialog-timing bugs between steps evaporate because all segments run in one dispatch on the server, eliminating the gap where a new dialog could steal focus between your client's calls.

## Validation

1. `python ij.py run 'run("Blobs (25K)") ||| run("Invert") ||| capture inverted'` — one TCP round-trip, three results back.
2. Middle segment fails → subsequent segments skipped, response lists partial results with error at failing index.
3. `--continue-on-error` still executes later segments after a failure.
4. Escaped separator `\|||` treated as literal `|||` inside a macro string.

## Risks

- **Parser ambiguity.** What if a macro legitimately contains the string `|||` (e.g. a filename)? Mitigation: require escaping; document the escape rule in `ij.py --help run`.
- **Feature creep.** Each new keyword (`capture`, `measure`, `results`, …) adds maintenance. Keep the keyword set small and stable; bare-string fallback handles the long tail.
- **Transaction semantics.** Users may expect "all-or-nothing" execution. It's not — earlier successful segments stay committed (images opened stay open). Document clearly: this is chained, not transactional.
