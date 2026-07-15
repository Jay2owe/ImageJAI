# Non-blocking Graphify hook parity

## Why this stage exists

The existing graph is stale and local Git hooks call private APIs, rebuild too broadly, and block normal Git work. Later stages depend on an architecture graph that updates safely even when edits, commits, and builds happen close together.

## Prerequisites

- Stage 01 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Graphify hook parity with FLASH/PULSE".
- `build.sh` (TODO: locate the current graph rebuild block).
- `.git/hooks/post-commit` and `.git/hooks/post-checkout` (local installation state; do not commit them).
- Sibling FLASH/PULSE hook runner/config files identified in the review; read them, but adapt paths and artifact patterns to ImageJAI.

## Scope

- Add one tracked hook runner with suffix filtering, UTF-8 logs, debounce, a lock, and stale-lock recovery.
- Use public `python -m graphify update`/build commands and record installed/graph metadata version drift.
- Add tracked install/config support for Git post-commit/post-checkout and `.codex`/`.claude` post-edit triggers.
- Invoke the runner once after a successful build/deploy, never once per copied artifact.
- Install local `.git/hooks` copies without committing them.

## Out of scope

- Reworking graph contents or graphify itself is not part of this plan.
- Deployment behavior beyond the single post-success trigger belongs to stage 26.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `scripts/graphify_hook.py` | NEW | Shared detached, locked update runner. |
| `scripts/install_graphify_hooks.ps1` | NEW | Reinstall local Git hook shims. |
| `.codex/hooks.json` | NEW | Tracked Codex post-edit trigger. |
| `.claude/hooks/post_change_graphify.py` | NEW | Claude post-edit shim. |
| `.claude/settings.json` | NEW | Register the Claude hook if repository ignore policy permits tracking. |
| `build.sh` | MODIFY | Call the common runner once after success. |
| `.git/hooks/post-commit` | LOCAL MODIFY | Non-blocking installed shim. |
| `.git/hooks/post-checkout` | LOCAL MODIFY | Non-blocking installed shim. |

## Implementation sketch

The shared runner should accept an event plus changed paths, filter code/document suffixes, acquire an atomic lock containing PID/time, recover only demonstrably stale locks, debounce bursts, and spawn the graph update detached. Write UTF-8 output to a stable ignored log. Git shims should determine changed paths and return immediately; editor hooks should call the same runner. A version mismatch is logged visibly but never blocks Git.

## Exit gate

1. Hook installation is idempotent and local hook files invoke only the tracked runner.
2. Documentation-only and code edits schedule one update; unrelated binary changes schedule none.
3. Simulated concurrent triggers produce one graph writer and stale-lock recovery is tested.
4. A failing graph update leaves an actionable UTF-8 log while Git commands still succeed promptly.
5. `graphify-out/graph.json` can be updated using the installed public graphify CLI.

## Known risks

- `.claude` is currently ignored; adjust the narrow ignore rule only if needed, without exposing local settings/secrets.
- Never let stale-lock recovery kill an unrelated process; time and ownership checks must be conservative.
