# Durable authenticated protocol sessions

## Why this stage exists

`agent/ij.py` creates a new socket per request while the server stores negotiated capabilities on the short-lived hello socket. As a result strict authentication fails and safe mode, undo, structured replies, deduplication, and pulse can silently fall back to defaults.

## Prerequisites

- Stage 01 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, release blocker 1.
- `src/main/java/imagejai/engine/TCPCommandServer.java` (TODO: locate accept loop, hello dispatch, socket capability map, authentication, and shutdown).
- `agent/ij.py` (TODO: locate connection creation, hello, `_HELLO_SENT`, request serialization, and response handling).
- `src/test/java/imagejai/engine/TCPCommandServerHelloTest.java`.
- `src/test/java/imagejai/engine/TCPCommandServerDataGovernanceTest.java`.
- `agent/test_ij_api.py`.

## Scope

- Add a bounded server-side registry of random, expiring sessions.
- Return a session identifier from authenticated `hello`; require session ID and token on later requests.
- Keep negotiated capabilities immutable for that session and revoke all sessions on stop.
- Give legacy clients an explicit restricted compatibility policy.
- Refactor `ij.py` around a session-oriented transport that automatically negotiates and carries credentials across reconnects.
- Add live loopback tests for strict tokens, invalid/missing sessions, capabilities, reconnects, expiry, and stop/restart.

## Out of scope

- Subscription framing and privacy belong to stage 04.
- Command behavior and resource limits belong to stages 12 and 23.
- The public preloaded runner belongs to stage 24.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Validate every request through a durable session. |
| `src/main/java/imagejai/engine/SessionCapsRegistry.java` | NEW | Focused bounded session/capability registry named by the source review design. |
| `agent/ij.py` | MODIFY | Add a session-oriented reconnecting client transport. |
| `src/test/java/imagejai/engine/TCPCommandServerHelloTest.java` | MODIFY | Cover protocol negotiation and compatibility. |
| `src/test/java/imagejai/engine/SessionCapsRegistryTest.java` | NEW | Cover entropy, immutability, expiry, bounds, and revocation. |
| `agent/test_ij_api.py` | MODIFY | Exercise real session propagation and structured replies. |

## Implementation sketch

`hello` returns `{session_id, expires_at, capabilities}` only after token validation. Every request envelope carries `session_id` and the configured auth token; the registry returns one immutable capability object or rejects the request. Use cryptographically secure random identifiers, monotonic expiry checks, a hard entry cap, and explicit compatibility capabilities with no privileged defaults. `ImageJSession` owns negotiation and retries only a transport reconnect, never an authentication failure.

## Exit gate

1. Strict-token hello followed by a command over a fresh socket succeeds with the same session.
2. Missing, unknown, expired, wrong-token, and post-stop sessions fail closed with structured errors.
3. Safe mode, undo, structured errors, pulse, and dedup capabilities remain exactly as negotiated across reconnects.
4. Registry capacity and expiry tests are deterministic through an injected clock.
5. Maven tests and the offline Python suite pass; a real loopback transport test is included without requiring Fiji.

## Known risks

- Preserve a deliberately narrow compatibility path for older helpers; do not silently grant current defaults.
- Do not log tokens or full session identifiers.
