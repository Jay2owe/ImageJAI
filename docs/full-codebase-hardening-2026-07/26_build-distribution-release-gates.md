# Reproducible build, scrubbed lab bundle, and final release gates

## Why this stage exists

Build scripts can skip tests/suppress diagnostics and replace installed JARs before verifying the new artifact. JARs are non-reproducible, notices are incomplete, the lab bundle uses the wrong Python support/install model, and broad allowlists can include secrets or experimental runtime debris.

## Prerequisites

- Stages 02 through 25 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Build and lab distribution" and "Safe test discovery and coverage".
- `build.sh`, `pom.xml`, `CITATION.cff`, `src/main/java/imagejai/config/Constants.java`, and `src/main/resources/META-INF/NOTICE.md`.
- `scripts/make_lab_bundle.ps1` and distribution instructions in root `AGENTS.md`/README.
- Engine classes listed for missing lifecycle tests: `CommandEngine`, `JobRegistry`, `PipelineBuilder`, `StateInspector`, `ReactiveEngine`, and `PromptWatcher`.

## Scope

- Run tests by default with visible diagnostics; require explicit `--skip-tests`.
- Make the JAR reproducible and exclude dependency Maven metadata contrary to policy.
- Complete notices for shaded dependencies.
- Verify one fresh main artifact/hash before removing or replacing an installed JAR.
- Require Python 3.10-3.13 and create a dedicated bundle environment.
- Assemble the agent workspace from an allowlist and fail on secret/private/runtime/experimental patterns.
- Verify the direct lifecycle and real `ij.py` loopback tests added by the owning implementation stages.
- Synchronize Maven, Constants, and CITATION metadata with stage-25 documentation.
- Run offline Python, Java unit/integration-profile, recipe, context, manifest, hook, and two-build hash gates.

## Out of scope

- Do not deploy, close Fiji, publish, push, or upload an update site during this stage.
- Live-Fiji smoke tests remain opt-in and are reported separately if Fiji is unavailable.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `build.sh` | MODIFY | Tested diagnostic reproducible build. |
| `pom.xml` | MODIFY | Reproducibility, metadata exclusion, integration gates. |
| `src/main/resources/META-INF/NOTICE.md` | MODIFY | Complete shaded dependency notices. |
| `scripts/make_lab_bundle.ps1` | MODIFY | Verify-first allowlisted private lab bundle. |
| `src/main/java/imagejai/config/Constants.java` | MODIFY | Canonical plugin version. |
| `CITATION.cff` | MODIFY | Canonical release version. |
| `docs/full-codebase-hardening-2026-07/RELEASE_VERIFICATION.md` | NEW | Record commands, hashes, counts, and opt-in omissions. |

## Implementation sketch

Normalize archive timestamps/order and remove unwanted `META-INF/maven` entries. Select only `target/imagej-ai-<version>.jar`; hash it before any replacement, copy to a temporary destination, verify, then atomically replace only matching ImageJAI JARs. Bundle from an explicit file list into a versioned virtual environment setup and scan paths/content for secrets/private/runtime artifacts. Use injected clocks/executors for lifecycle tests instead of sleeps.

## Exit gate

1. Two clean builds from identical source produce identical main-JAR hashes and contain no prohibited metadata.
2. Unit/offline suites, integration profile, recipes, contexts, manifest, hooks, and lifecycle tests pass with counts recorded.
3. A simulated copy/hash failure preserves the old installed JAR.
4. Bundle inspection proves Python 3.10-3.13 environment setup, allowlist-only contents, complete notices, and zero secret/private/`_spike`/runtime files.
5. `RELEASE_VERIFICATION.md` records exact commands/results and any unrun live-Fiji test without claiming it passed.

## Known risks

- Windows may lock the local JAR while Fiji runs; report the lock and require restart, never close Fiji automatically.
- Reproducible builds require all generated inputs to be deterministic; trace any hash drift before weakening the gate.
