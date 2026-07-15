# Safe Python workflow caches, logs, training, and practice

## Why this stage exists

Probe caches collide and go stale, session logs can replay failed macros and corrupt concurrent writes, and trainer/practice code can close or contaminate user images, Results, ROIs, and settings. These tools need deterministic, isolated behavior over the repaired session client.

## Prerequisites

- Stages 03 and 16 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Python scientific helpers".
- `agent/probe_plugin.py`, `session_log.py`, `train_agent.py`, and `practice.py`.
- `agent/macro_lint.py`, `adviser.py`, and relevant existing API tests.

## Scope

- Make probe caches collision-resistant, versioned, atomic, and invalidated by plugin/Fiji changes.
- Record strong session IDs atomically and exclude failed macros from replay.
- Integrate macro lint with documented safety rules and robust parsing.
- Fix adviser reference paths/dependencies.
- Run trainer/practice against isolated duplicates with transactional Results/ROI/settings restoration.
- Never close all user images; handle multi-series inputs safely.
- Seed generated examples and verify actual outcomes, including BlackBackground and correct density data.

## Out of scope

- Recipe search/validation is owned by stage 15.
- Server-side mutation serialization is owned by stages 05/09.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/probe_plugin.py` | MODIFY | Versioned atomic cache keys/invalidation. |
| `agent/session_log.py` | MODIFY | Atomic strong IDs and successful replay only. |
| `agent/macro_lint.py` | MODIFY | Enforce documented macro hazards robustly. |
| `agent/adviser.py` | MODIFY | Correct reference/dependency resolution. |
| `agent/train_agent.py` | MODIFY | Isolate state, seed, and verify outcomes. |
| `agent/practice.py` | MODIFY | Isolate practice from user data/state. |
| `agent/test_probe_plugin_api.py` | MODIFY | Cache collision/staleness/atomic tests. |
| `agent/test_workflow_safety.py` | NEW | Session/training/practice regressions. |

## Implementation sketch

Cache keys include normalized command plus plugin/Fiji fingerprint and schema version; write temp then replace. Session entries carry cryptographic IDs and explicit success, and replay filters success only. Training snapshots open images/ROIs/Results/settings, works on duplicates, and restores in `finally`; it closes only objects it created. Use seeded RNG and assert the intended output rather than only absence of an error.

## Exit gate

1. Same-named plugin variants cannot collide and a plugin fingerprint change invalidates cache.
2. Concurrent/corrupt session writes preserve the original; failed macros never appear in replay.
3. Macro lint catches every documented high-risk rule with parser edge tests.
4. Trainer/practice leave pre-existing images, active image, Results, ROIs, and settings unchanged on success/failure.
5. Repeated seeded training produces the same examples/results and verifies the correct density/output data.

## Known risks

- Plugin fingerprints must be cheap enough for normal lookup; cache the fingerprint for a process lifetime.
- Multi-series files must be enumerated, not blindly fully opened.
