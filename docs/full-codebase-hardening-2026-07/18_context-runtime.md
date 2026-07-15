# Complete, fresh model context runtime

## Why this stage exists

Two offline tests fail because models classified as `other` have no family overlay. Context-hook reconnects can also mark stale snapshots fresh, startup leases race, and some harness classifications do not match the real launch path.

## Prerequisites

- Stage 01 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Provider and context runtime".
- `agent/contexts/loader.py`, `agent/contexts/test_contexts.py`, and `agent/providers/models.yaml`.
- `context_hook.py` (TODO: locate heartbeat, lease, reconnect, and snapshot freshness logic).
- `agent/sync_context.py` and generated `agent/AGENTS.md`.

## Scope

- Add a generic `families/other.md` overlay.
- Require every declared model family and harness entry to resolve.
- Correct harness classifications proven inconsistent with actual provider launch behavior.
- Treat expired/missing heartbeats as stale even after reconnect.
- Make daemon lease/startup atomic and prevent duplicate owners.
- Keep generated snapshots deterministic and update-only through the explicit stage-01 command.

## Out of scope

- Provider vision/tools/events are owned by stage 07.
- Canonical API/context documentation wording belongs to stage 25.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/contexts/families/other.md` | NEW | Generic fallback family overlay. |
| `agent/contexts/loader.py` | MODIFY | Enforce complete deterministic resolution. |
| `agent/contexts/test_contexts.py` | MODIFY | Cover all model/harness combinations. |
| `agent/providers/models.yaml` | MODIFY | Correct only verified harness metadata. |
| `context_hook.py` | MODIFY | Fix heartbeat freshness and atomic lease. |
| `agent/sync_context.py` | MODIFY | Preserve explicit deterministic regeneration. |

## Implementation sketch

Resolve family/harness through an exhaustive validation pass at load time. The generic overlay contains only family-neutral guidance. Store lease owner, process identity, and expiry atomically; a reconnect must read the last real heartbeat rather than writing a fresh one. Use injected time in tests and stable sort/order for generated context.

## Exit gate

1. The two reviewed `other` family cases pass and every model entry resolves exactly one family/harness.
2. Expired heartbeat remains stale after reconnect.
3. Concurrent daemon starts elect one owner without truncating valid state.
4. Context generation is byte-stable and missing snapshots fail unless explicitly updated.

## Known risks

- Do not use the generic overlay to hide a misspelled known family; validate known aliases separately.
- Lease recovery must not take over from a demonstrably live owner.
