# Step 01 — `hello` Handshake and `AgentCaps`

## Motivation

Every later step asks the same question: "how should this reply
be shaped for this specific agent?" Without a per-connection
capability record, every shaping decision has to re-derive itself
from scratch or hard-code assumptions. This step establishes the
foundation: a one-command handshake, a per-socket `AgentCaps`
record, and threading that record into every response builder.

## End goal

A client that sends `hello` on connect declares its agent name,
vision support, output format, token budget, and which events it
wants pushed. The server stores these per-socket and uses them
throughout the session. A client that never sends `hello` sees
exactly today's behaviour — full backwards compatibility.

## Scope

In:
- New `hello` command handler in `TCPCommandServer`.
- `AgentCaps` class with sensible defaults.
- `Map<Socket, AgentCaps>` keyed by the connection, cleared on
  disconnect.
- Threading the socket (or the caps directly) into
  `handleExecuteMacro` and every other response-building handler
  that later steps will modify.
- `ij.py` and `agent/gemma4_31b/registry.py` learn to call
  `hello` once per connection.
- Doc updates in `agent/CLAUDE.md` and `agent/gemma4_31b/GEMMA.md`.

Out:
- No new response fields yet. This step is wiring only. Later
  steps consume the caps.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — handler dispatch table, `handleExecuteMacro` signature, how
  sockets are currently passed around (they usually aren't).
- `agent/ij.py` — connection lifecycle, where a new call would
  naturally slot in.
- `agent/gemma4_31b/registry.py` — how the Gemma wrapper creates
  its persistent socket.

## Protocol

Request:

```json
{"command": "hello",
 "agent": "gemma-31b",
 "version": "0.4.1",
 "capabilities": {
   "vision": false,
   "output_format": "json",
   "token_budget": 4000,
   "verbose": false,
   "accept_events": ["macro.*", "image.*", "dialog.*"],
   "agent_id": "gemma-jamie-laptop",
   "pulse": true,
   "safe_mode": false
 }}
```

Response:

```json
{"ok": true, "result": {
  "server_version": "1.7.3",
  "session_id": "s-47f2",
  "enabled": [
    "structured_errors", "state_delta", "canonical_macro",
    "fuzzy_match", "pulse"
  ],
  "server_time_ms": 1714000000000
}}
```

The `enabled` list is what the server will actually emit for this
connection — the intersection of the agent's declared interest
and the server's current capabilities. Lets the agent know what
to parse.

## Default caps per agent wrapper

| Field | Gemma 31B | Claude Code | Codex | Gemini |
|---|---|---|---|---|
| `vision` | false | true | true | true |
| `output_format` | json | markdown | json | json |
| `token_budget` | 4000 | 20000 | 10000 | 10000 |
| `verbose` | false | true | true | false |
| `pulse` | true | **false** (hooks provide it) | true | true |

Claude's `pulse: false` is load-bearing — Claude Code hooks
already inject session state, so server-side pulse would
duplicate.

## Code sketch

New fields on `TCPCommandServer`:

```java
static final class AgentCaps {
    String agent = "unknown";
    String agentId = null;
    boolean vision = false;
    String outputFormat = "json";
    int tokenBudget = Integer.MAX_VALUE;
    boolean verbose = false;
    boolean pulse = false;
    Set<String> acceptEvents = Set.of();
}
static final AgentCaps DEFAULT_CAPS = new AgentCaps();

private final Map<Socket, AgentCaps> capsBySocket = new ConcurrentHashMap<>();
```

Handler:

```java
private JsonObject handleHello(JsonObject req, Socket sock) {
    AgentCaps c = new AgentCaps();
    c.agent = optString(req, "agent", "unknown");
    JsonObject caps = req.has("capabilities")
        ? req.getAsJsonObject("capabilities") : new JsonObject();
    c.vision       = optBool(caps, "vision", false);
    c.outputFormat = optString(caps, "output_format", "json");
    c.tokenBudget  = optInt(caps, "token_budget", Integer.MAX_VALUE);
    c.verbose      = optBool(caps, "verbose", false);
    c.pulse        = optBool(caps, "pulse", false);
    c.agentId      = optString(caps, "agent_id",
                               c.agent + "-" + sock.getPort());
    c.acceptEvents = parseStringArray(caps, "accept_events");
    capsBySocket.put(sock, c);

    JsonObject result = new JsonObject();
    result.addProperty("server_version", SERVER_VERSION);
    result.addProperty("session_id", sessionIdFor(sock));
    result.add("enabled", enabledCapsFor(c));
    result.addProperty("server_time_ms", System.currentTimeMillis());
    return okReply(result);
}
```

Socket cleanup: on disconnect, remove the entry. Ideally hook
the existing connection-close path; otherwise a periodic sweep
keyed on closed-socket detection works.

### Threading caps into handlers

Today many handlers have signatures like:

```java
private JsonObject handleExecuteMacro(JsonObject req)
```

This step changes them to:

```java
private JsonObject handleExecuteMacro(JsonObject req, AgentCaps caps)
```

In the dispatch loop, look up caps once per request:

```java
AgentCaps caps = capsBySocket.getOrDefault(sock, DEFAULT_CAPS);
```

and pass it through. Every handler this step touches signature-
wise should be listed in `03–07` — coordinate so only one
sweeping rename happens.

## Python side

`ij.py` — add an optional `hello()` call in `_connect()`:

```python
_HELLO_CAPS = {
    "vision": True, "output_format": "markdown",
    "token_budget": 20000, "pulse": False,   # Claude has hooks
    "accept_events": ["macro.*", "image.*", "dialog.*"],
}

def _hello(sock):
    _send(sock, {"command": "hello", "agent": "claude-code",
                 "capabilities": _HELLO_CAPS})
    return _recv(sock)
```

Call on first connect; stash the response for optional inspection
(`ij.py capabilities`). On hello failure, fall back to the
no-handshake path.

`agent/gemma4_31b/registry.py` — mirror, with Gemma's caps dict:

```python
GEMMA_CAPS = {
    "vision": False, "output_format": "json",
    "token_budget": 4000, "pulse": True,
    "accept_events": ["macro.*", "image.*", "dialog.*"],
}
```

## Docs

`agent/CLAUDE.md` gains a short "Handshake" section explaining
that `ij.py` now calls `hello` automatically on connect and
listing Claude's default caps.

`agent/gemma4_31b/GEMMA.md` gains the same for Gemma's caps.

## Tests

- Hello with no capabilities field — defaults applied.
- Hello with partial caps — missing fields fall back to defaults.
- No hello — `capsBySocket.get` returns null, `DEFAULT_CAPS` is
  used, today's reply shape emerges.
- Two concurrent connections with different caps — no crosstalk.
- Socket close — entry is removed.

## Failure modes

- `capsBySocket` leaking if disconnect hook misses edge cases.
  Add a 30-minute sweep of closed sockets as a belt-and-braces
  cleanup.
- Wrappers forgetting to send `hello` — silently get legacy
  behaviour. No warning is emitted because we want backwards
  compatibility, but consider a DEBUG log line on first command
  from a no-hello socket so we can tell at a glance during
  rollout.
- `agent_id` collisions when two sessions of the same wrapper run
  side by side. Default includes `sock.getPort()` to
  disambiguate.
