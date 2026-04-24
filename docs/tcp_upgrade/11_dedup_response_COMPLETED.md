# Step 11 — Dedup Repeated State Responses

## Motivation

`blobs-10-filters.md` shows Gemma calling `get_state` five times
within 15 seconds on the same image, each call returning an
identical ~700-token payload. She's trying to verify the macro
ran, then re-verifying, then checking again after a filter. Every
call burns tokens for no new information.

If the server notices that the outgoing `get_state` payload is
byte-identical to the last one sent to this agent within a short
window, it can short-circuit with:

```json
{"unchanged": true, "since": 1714000000000, "ageMs": 2400}
```

The agent saves ~650 tokens on the repeat.

## End goal

Dedup applies to *read-only* state queries only:
- `get_state`
- `get_image_info`
- `get_results_table`
- `get_log`
- `get_histogram`
- `get_open_windows`
- `get_metadata`
- `get_dialogs`
- `get_roi_state` (from step 07)
- `get_display_state` (from step 07)

Each agent connection has a small LRU of recent (command, args)
→ (response hash, timestamp). If a new query hits the cache with
the same args and the hash matches the current computed response,
the server returns the short form.

## Scope

In:
- A `ResponseDedupCache` per `AgentCaps` entry (so per-socket).
- Integration in every read-only handler: compute the real
  response, hash it, compare with cache, short-circuit if
  unchanged within window.
- Capability gate `caps.dedup` — default `true`.
- Per-command opt-out via `force: true` in the request.

Out:
- Cross-agent deduplication (one Gemma session asking what
  another already asked). Too complex; not a real scenario.
- Dedup on mutating commands (`execute_macro` etc.). Those always
  return fresh data; a repeat call is semantically different.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — every read-only handler and its dispatch.
- `docs/tcp_upgrade/01_hello_handshake.md` — where `caps.dedup`
  lives.
- `docs/tcp_upgrade/05_state_delta_and_pulse.md` — `stateDelta`
  already does something related but for the opposite
  direction (post-mutation diffs).

## Mechanics

Key: `(commandName, canonicalArgs)` where `canonicalArgs` is a
stable string form of the JSON args (sorted keys).

Value: `{bodyHash, timestampMs}`. `bodyHash` is SHA-256 of the
JSON response body, truncated to 128 bits for speed.

Window: 10 seconds. Outside the window, dedup never fires.

```java
public final class ResponseDedupCache {
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private final long windowMs;

    public Optional<JsonObject> checkOrStore(
            String cmd, String canonicalArgs, JsonObject freshBody) {
        String key = cmd + "|" + canonicalArgs;
        CachedEntry prev = cache.get(key);
        long now = System.currentTimeMillis();
        String freshHash = hash(freshBody);
        if (prev != null
            && prev.bodyHash.equals(freshHash)
            && (now - prev.timestampMs) < windowMs) {
            JsonObject shortReply = new JsonObject();
            shortReply.addProperty("unchanged", true);
            shortReply.addProperty("since", prev.timestampMs);
            shortReply.addProperty("ageMs", now - prev.timestampMs);
            // refresh timestamp so a continuous chain can keep deduping
            cache.put(key, new CachedEntry(freshHash, now));
            return Optional.of(shortReply);
        }
        cache.put(key, new CachedEntry(freshHash, now));
        return Optional.empty();
    }
}
```

Handler integration (one example):

```java
private JsonObject handleGetState(JsonObject req, AgentCaps caps) {
    JsonObject fresh = computeGetStateBody();
    if (caps.dedup && !optBool(req, "force", false)) {
        Optional<JsonObject> dedup = caps.dedupCache
            .checkOrStore("get_state", canonicalArgs(req), fresh);
        if (dedup.isPresent()) return okReply(dedup.get());
    }
    return okReply(fresh);
}
```

## Computing the full response anyway

Note we compute the fresh body first, then decide whether to
short-circuit. That's deliberate — it's the only way to know
whether the hash changed. The cost we save is the *wire transfer*
and the *agent's context*, not the server's compute. For local
TCP on one machine the savings are small; for the agent reading
the reply into its context window, they're large.

## Opt-out

Per-call override:

```json
{"command": "get_state", "force": true}
```

Agents that explicitly want a full payload (e.g. after an
external change they suspect happened) bypass the cache. Does
not update the cache timestamp.

## Interaction with silent-change detection

A future step (not in this plan) could emit a `state.external_change`
event when the server detects user-driven changes that happened
outside any TCP macro. When that lands, the dedup cache should
be invalidated for any key affected by the change. Today: not
applicable; dedup relies on pure output-hash comparison, so any
real change will miss the cache naturally.

## Tests

- `get_state` twice in a row, no state change → second returns
  `{"unchanged": true, "ageMs": ...}`.
- `get_state` twice with a `run("Invert")` between → second
  returns full body (hash changed).
- `get_state` with `force: true` → always returns full body.
- 12-second gap between calls → second returns full body
  (outside window).
- Two agents on different sockets asking the same thing → no
  cross-contamination; each has its own cache.
- `get_state` dedup hit while `execute_macro` is in flight → the
  dedup doesn't wait for the macro; it compares against cached
  pre-macro body. Document this: dedup answers the question
  "what was state last time I asked", not "what is state right
  now given possibly-racing mutations".

## Failure modes

- Cache grows without bound if agents query many distinct arg
  combinations. Cap size at 200 entries per socket, LRU-evict.
- Hash collision leading to a false "unchanged". 128 bits makes
  this vanishingly unlikely (~2^-64 for a birthday match across
  4 billion cache entries); accept.
- Agent sees `{"unchanged": true}` but its local state model has
  drifted. Mitigation: include `ageMs` so the agent can judge
  whether to refetch. Document in wrapper docs that `ageMs > 5000`
  warrants a `force: true` follow-up.
