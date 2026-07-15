# Safe, truthful test discovery

## Why this stage exists

The repair needs a trustworthy baseline before behavior changes. Today root pytest collection can execute destructive setup code, a live-Fiji script is collected as a unit test, Java integration suites are excluded, and missing generated context snapshots can be hidden.

## Prerequisites

- None.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Safe test discovery and coverage".
- `agent/test_count.py` (whole file; TODO: locate the connection attempt and top-level execution).
- `test-scripts/zero_polling_test.py` (whole file; TODO: locate every import-time side effect).
- `pom.xml` (TODO: locate Surefire/Failsafe and integration-test exclusions).
- `agent/contexts/test_contexts.py` (TODO: locate snapshot assertions).

## Scope

- Restrict default pytest collection to offline test roots and explicit test filename patterns.
- Move or rename `agent/test_count.py` into an opt-in live-Fiji smoke test with assertions and cleanup.
- Put every action in `zero_polling_test.py` behind an explicit entry point.
- Standardize Java integration tests on one JUnit/tag/profile mechanism and expose an integration command.
- Make missing tracked context snapshots fail unless an explicit update command is used.
- Separate flaky sleep/performance ceilings from correctness tests.

## Out of scope

- Direct engine lifecycle coverage is owned by the relevant implementation stages and verified in stage 26.
- Fixing the missing `other` context overlay belongs to stage 18.
- Running Fiji or closing a live Fiji instance is forbidden.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `pytest.ini` | NEW | Define offline default discovery and markers. |
| `agent/test_count.py` | DELETE/RENAME | Stop collecting a live service check as a unit test. |
| `test-scripts/zero_polling_test.py` | MODIFY | Remove import-time process/file side effects. |
| `pom.xml` | MODIFY | Standardize the integration profile. |
| `agent/contexts/test_contexts.py` | MODIFY | Make snapshot absence explicit. |
| `src/test/java/imagejai/engine/IntentRouterTest.java` | MODIFY if needed | Move timing ceilings out of correctness assertions; confirm before editing. |

## Implementation sketch

Use markers such as `live_fiji` and `benchmark`, excluded by the default command. Rename the live script to a non-default smoke-test filename under `test-scripts/` and require `if __name__ == "__main__":`. The Java integration profile should discover one consistent suffix/tag and never run silently as part of an offline unit test. Snapshot regeneration must require a named flag or command rather than being an implicit side effect.

## Exit gate

1. `python -m pytest --collect-only -q` completes without launching Java, terminating processes, deleting files, sleeping, or contacting Fiji.
2. `python -m pytest -q` runs only offline tests and has no connection-refused failure.
3. The documented Maven unit command passes; the integration profile is discoverable and reports a clear prerequisite if Fiji is absent.
4. A test proves importing `zero_polling_test.py` causes no side effects.

## Known risks

- Moving tests can reduce coverage accidentally; compare collected test counts before and after by category.
- Do not mask real failures with broad ignore patterns; exclude only explicitly marked live/benchmark tests.
