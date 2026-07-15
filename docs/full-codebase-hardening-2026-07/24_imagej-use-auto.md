# Browser-use-style `imagej-use-auto` runner

## Why this stage exists

Browser-use succeeds by pairing a persistent target with a one-command stdin runner whose helpers are preloaded. Fiji plus ImageJAI already provide the persistent target; the repaired session transport can expose the same ergonomic pattern without coordinate clicking or another daemon.

## Prerequisites

- Stages 04 and 12 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Browser-use-style ImageJ control".
- `agent/ij.py` and `agent/CLAUDE.md`.
- `src/main/java/imagejai/engine/AgentLauncher.java`.
- The local browser-use runner only as a UX reference; do not copy browser concepts listed out of scope.

## Scope

- Finish a public `ImageJSession` over stage-03 transport.
- Add a stdin Python runner with helpers pre-imported.
- Add `--doctor` for Fiji reachability, protocol/auth, workspace, and screenshot capability.
- Add screenshot-to-path and governed `wait_for_event` helpers.
- Load optional workspace `imagej_helpers.py` through an explicit workspace environment path.
- Package/document the launcher and expose it to AgentLauncher/context generation.

## Out of scope

- No browser coordinates, Java Robot, second daemon, automatic Fiji start/close, remote listening, profiles, or tabs.
- Dialog control remains semantic `get_dialogs`/`interact_dialog` commands.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/ij.py` | MODIFY | Public session-oriented client API. |
| `agent/imagej_use/__init__.py` | NEW | Runner package exports. |
| `agent/imagej_use/helpers.py` | NEW | Preloaded semantic helpers. |
| `agent/imagej_use/run.py` | NEW | Stdin execution and doctor CLI. |
| `agent/imagej_helpers.py` | NEW | Optional workspace convenience template. |
| `agent/pyproject.toml` | NEW | Install the imagej-use-auto entry point. |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Make runner available to supported agents. |
| `agent/test_imagej_use.py` | NEW | Loopback/doctor/helper tests. |

## Implementation sketch

Read Python from stdin, construct one authenticated `ImageJSession`, and execute with a namespace containing `session`, semantic helpers, and optional workspace helpers. `--doctor` returns structured checks and non-zero on required failure. Screenshot helpers accept a safe path and verify the file exists; event waits subscribe through stage 04 with timeout/topic/predicate support.

## Exit gate

1. Piped Python can call `get_state`, `run_macro`, screenshot, and event wait through one session.
2. `--doctor` distinguishes Fiji unavailable, auth failure, protocol mismatch, workspace issue, and screenshot failure.
3. Workspace helpers load only from the explicit workspace and cannot shadow core security/session objects.
4. Tests prove no automatic Fiji/process/remote-control behavior.
5. AgentLauncher/context instructions show the exact command and semantic dialog workflow.

## Known risks

- Executing stdin Python is a local explicit capability; stage 06 policy must keep it out of cloud tool schemas by default.
- Screenshot paths must obey the workspace/export path rules.
