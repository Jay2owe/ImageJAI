# Authenticated, privacy-safe event subscriptions

## Why this stage exists

Subscriptions are currently detected by raw substring before normal dispatch. They can bypass authentication, detokenisation, pseudonymisation, and audit while streaming sensitive titles, paths, dialog text, macro previews, and job previews.

## Prerequisites

- Stage 03 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, release blocker 2.
- `src/main/java/imagejai/engine/TCPCommandServer.java` (TODO: locate pre-dispatch subscribe detection and stream lifecycle).
- `src/main/java/imagejai/engine/EventBus.java`.
- `src/main/java/imagejai/engine/ImageMonitor.java`.
- `src/main/java/imagejai/engine/DialogWatcher.java`.
- `src/main/java/imagejai/engine/JobRegistry.java`.
- `agent/ij.py` (TODO: locate event subscription definitions and remove the duplicate).

## Scope

- Parse JSON before dispatch and match exact `command == "subscribe"`.
- Authenticate and authorize the subscription through stage 03 sessions.
- Apply the normal detokenisation/pseudonymisation envelope to every frame.
- Audit open/close metadata without sensitive payloads.
- Preserve 8-subscriber and 256-frame caps.
- Coalesce only declared coalescible topics and retain distinct image/job/dialog identities.
- Remove the duplicate Python `imagej_events` implementation.

## Out of scope

- General EventBus/reactive queue hardening belongs to stage 13.
- Provider wrappers consuming events belong to stage 07.
- Event-wait convenience helpers belong to stage 24.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Route exact subscribe requests through governance. |
| `src/main/java/imagejai/engine/EventBus.java` | MODIFY | Add explicit coalescing policy and identity preservation. |
| `src/main/java/imagejai/engine/OutboundEvent.java` | MODIFY | Carry safe topic/identity metadata. |
| `agent/ij.py` | MODIFY | Use one authenticated subscription client. |
| `src/test/java/imagejai/engine/TCPCommandServerDataGovernanceTest.java` | MODIFY | Assert auth/privacy/audit for streams. |
| `src/test/java/imagejai/engine/EventBusTest.java` | NEW | Cover coalescing and bounded identities. |

## Implementation sketch

Deserialize the first frame into the same request type used by normal commands, validate its session, then upgrade only an exact subscribe command. Serialize every outbound event through the same privacy transformation used for command responses. Audit fields should be limited to session pseudonym, topics, start/end reason, and counts. Topic policy must explicitly mark state-heartbeat-like events coalescible; job/image/dialog lifecycle events key on stable IDs.

## Exit gate

1. Strings merely containing "subscribe" dispatch normally and cannot open a stream.
2. Missing/invalid/expired sessions cannot subscribe.
3. Tests prove raw paths, titles, dialog content, macro source, and job previews do not escape the configured privacy envelope.
4. Distinct job/image identities survive queue pressure; explicitly coalescible frames collapse.
5. Limits remain 8 subscribers and 256 frames per subscriber, and open/close audits contain no payload data.

## Known risks

- Privacy transformations must not corrupt structural event fields required by clients.
- Slow-subscriber handling must close only that subscriber, never block command execution.
