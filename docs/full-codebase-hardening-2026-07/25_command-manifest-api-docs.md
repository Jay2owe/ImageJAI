# Canonical command manifest and generated API documentation

## Why this stage exists

The server exposes about 60 commands, `ij.py` wraps fewer while claiming all, and README documents still fewer. Methods metadata, version/runtime statements, context/reference counts, and artifact names also disagree.

## Prerequisites

- Stages 18 and 24 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Canonical API and release metadata".
- `src/main/java/imagejai/engine/TCPCommandServer.java`, `agent/ij.py`, and `agent/methods_table.py`.
- `README.md`, `docs/USER_GUIDE.md`, and `docs/DEVELOPER.md`.
- `agent/references/INDEX.md`, `agent/contexts/base.md`, and documentation generators.

## Scope

- Create one machine-readable command manifest.
- Generate/validate server descriptors, Python wrapper coverage, and command documentation from it.
- Correct methods-table payload keys, version, log path, session, and initiating-dataset provenance.
- Synchronize version `0.3.0`, artifact name, and Java 11+ runtime claims across README, USER_GUIDE, and DEVELOPER documentation.
- Refresh reference counts/index deterministically and prevent generator hangs.
- Correct obsolete "around forty"/all-command claims through generation.

## Out of scope

- Command implementation behavior is owned by earlier stages.
- Maven/constants/citation metadata, artifact reproducibility, licenses, and bundle assembly belong to stage 26.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/command_manifest.json` | NEW | Canonical command contract. |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Consume/validate descriptors. |
| `agent/ij.py` | MODIFY | Manifest-backed wrapper coverage checks. |
| `agent/methods_table.py` | MODIFY | Correct live provenance metadata. |
| `README.md` | MODIFY | Generated accurate API/version/runtime. |
| `docs/USER_GUIDE.md` | MODIFY | Correct user-facing runtime/version claims. |
| `docs/DEVELOPER.md` | MODIFY | Correct developer runtime/version claims. |
| `agent/references/INDEX.md` | MODIFY | Deterministic current reference index. |

## Implementation sketch

Manifest entries include command name, mutation/read-only class, request/reply schema, capability/auth requirements, wrapper status, and short documentation. Add equality tests against server dispatch and wrapper registry. Generators sort inputs, use bounded reads/timeouts, and replace marked doc sections. Do not hand-maintain derived counts.

## Exit gate

1. Server dispatch names, manifest names, and claimed Python wrapper/raw coverage have no unexplained diff.
2. Generated command docs/counts are byte-stable.
3. Methods output reports real version/session/log/dataset fields.
4. README, USER_GUIDE, and DEVELOPER agree on `0.3.0`, artifact, and Java 11+; stage 26 checks them against build metadata.
5. Reference/context generation completes within a deterministic bounded test.

## Known risks

- Do not generate Java dispatch logic from an unvalidated runtime file; package/validate the manifest at build time.
- Raw-command availability can satisfy coverage only when documentation says it is raw, not a convenience wrapper.
