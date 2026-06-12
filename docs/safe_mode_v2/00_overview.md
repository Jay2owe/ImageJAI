# Safe Mode v2

Expansion of the original safe-mode plan in `docs/safe_mode/` from
"thumbnail rehearsal + destructive-op interceptor on `execute_macro`"
to a layered set of guards covering the full TCP command surface,
plus a master on/off switch and a single-glance UI indicator.

## End goal

The biologist runs an AI agent against Fiji and trusts that the agent
cannot silently invalidate measurements, lose hand-drawn ROIs, or
overwrite raw data — without paying for that safety in latency or
modal popups during normal work. Power users can flip the master
switch off for a fast, unguarded session. Default-on guards catch
catastrophic damage; opt-in toggles cover situational risks.

## Why

The original plan (`docs/safe_mode/`) only gates `handleExecuteMacro`.
The TCP server has 50+ handlers; agents can trivially route around
the guard via `batch`, `run` (chain), `intent`, `run_pipeline`,
`run_script`, `interact_dialog`, `branch_delete`, `rewind`. The
"destructive" set is also defined at the filesystem level (deletes,
overwrites, close-all) — but the worst real damage in microscopy is
scientific-integrity loss: bit-depth narrowing, calibration reset,
ROI Manager wipes, Z-projection over the original. Those silently
turn measurements into nonsense and look identical on disk.

This v2 plan closes the bypass, adds the missing scientific-integrity
rules, and gives the biologist a permanent at-a-glance signal of how
the session is going.

## Architecture overview

- **Master switch.** A single `caps.safe_mode` flag (on by default
  per agent in `hello`) gates every guard below. Off = legacy fast
  path, no checks.
- **Per-guard flags** under `caps.safe_mode_options.*` let the user
  enable opt-in guards (bit-depth narrowing, normalize block) without
  flipping the master switch.
- **Single dispatch path.** Caps thread through nested
  `dispatch(JsonObject)` calls so `batch`/`run`/`intent`/etc. inherit
  the same caps as the originating call.
- **Existing infrastructure reused:** `SessionUndo` for snapshots,
  `FrictionLog` + `FrictionLogJournal` for events, `EventBus` for UI
  push, `MacroAnalyser.PostExec` for runtime checks, `ErrorReply` for
  structured error envelopes, `ij.CommandListener` (already used by
  `RecorderHook`) for runtime command interception.

## Stage map

| NN | Name | Goal | Size | Depends on |
|----|------|------|------|------------|
| 01 | fix-batch-run-bypass | Thread caps through nested dispatch so `batch`/`run`/`intent` inherit safe mode | ~3 h | — |
| 02 | master-switch-and-caps | Master `caps.safe_mode` toggle + per-guard option fields + AgentLauncher checkbox | ~5 h | 01 |
| 03 | auto-snapshot-rescue | Auto-snapshot the active image (and ROI/Results) before every safe-mode macro; commit on failure, discard on success | ~5 h | 02 |
| 04 | queue-storm-per-image | Block second macro on same image while first is paused on a Fiji dialog; allow concurrent macros on different images | ~6 h | 02 |
| 05 | destructive-scanner-expansion | Scientific-integrity rules: calibration loss, ROI wipe + auto-backup, Z-project overwrite, microscopy→PNG overwrite, plus opt-in bit-depth + normalize | ~14 h | 02 |
| 06 | results-source-image-column | Auto-add `Source_Image` column to Results table whenever active image changes; eliminates cross-contamination ambiguity | ~4 h | 02 |
| 07 | status-indicator-ui | Toolbar dot + Fiji status-bar coloured indicator; tooltip / click shows last 3 events | ~6 h | 02, 03, 04, 05, 06 |
| 08 | audit-log-integrity | Remove `clear_friction_log` from agent-callable surface; add `outcome`/`severity`/`rule_id` fields to FrictionLog rows | ~2 h | — |

Total ≈ 45 hours. Stages 03–06 are independent of each other once 02
is in; can run in parallel waves.

## Build order

1. **Stage 01** alone (foundation; mandatory).
2. **Stage 02** alone (capability plumbing every guard reads).
3. **Stages 03, 04, 05, 06 in any order or parallel.**
4. **Stage 07** last in the main path (consumes events from 03–06).
5. **Stage 08** independently at any time.

## House rules (from CLAUDE.md, every stage must respect)

- Outputs go to `AI_Exports/` next to the opened image. Never write
  elsewhere. Auto-backups (e.g. ROI snapshot before reset) write to
  `AI_Exports/.safemode_<kind>_<ts>.zip`.
- Never `Enhance Contrast normalize=true` on data being measured —
  Stage 05 codifies this as an opt-in hard block.
- Never close the ImageJ Log window during a session.
- Build with JDK 25, deploy JAR to the user's Fiji plugins folder.
- Fix the underlying bug, don't work around it silently. If a stage
  exposes a deeper issue (e.g. the bypass in Stage 01), name it.

## Known open questions

- Trust-score per agent (Tier-2 idea from the brainstorm) is deferred
  to v3; needs a calibration corpus.
- Auditor-agent peer review (Tier-2) is deferred; viable once `caps`
  plumbing from Stage 02 is in.
- Provenance graph + methods-section export deferred to v3.

## Source

Plan was synthesised in conversation 2026-05-04 from a four-agent
brainstorm; no monolith PLAN.md exists. The original safe-mode design
this expands lives in `docs/safe_mode/00_overview.md` through
`03_integration.md` — read those first, they remain the spec for
rehearsal and the destructive-op base classes.

## How to run a stage

```
/do-step docs/safe_mode_v2/
```

`/do-step` reads the lowest-numbered `NN_*.md` without `_COMPLETED`,
executes it, commits, and renames to `NN_*_COMPLETED.md`.
