# Recoverable persistence and deterministic provenance

## Why this stage exists

Corrupt ledger/history/session files can be treated as empty and overwritten. Records may attach to whichever image is current after async work, IDs/backup names collide, audit CSV can execute spreadsheet formulas, and dataset hashes cover only an active plane.

## Prerequisites

- Stage 12 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Persistence and provenance".
- `src/main/java/imagejai/engine/LedgerStore.java` and `SessionCodeJournal.java`.
- `src/main/java/imagejai/engine/ImageGraph.java` and `StateInspector.java`.
- `src/main/java/imagejai/engine/security/AuditLog.java` and `RoiAutoBackup.java`.
- `agent/session_log.py`.

## Scope

- Quarantine corrupt ledger/history/session files and refuse destructive overwrite.
- Bound entry/history sizes, return immutable snapshots, and use atomic writes.
- Capture initiating dataset identity before async work and attach methods/journal output to it.
- Use collision-resistant IDs and ROI backup filenames.
- Escape spreadsheet formula prefixes in audit CSV and report malformed summaries accurately.
- Hash every dataset plane in stable C/Z/T order with fixed locale/encoding.
- Make journal/state ordering deterministic and avoid mutable entry races.

## Out of scope

- Python failed-macro replay/search behavior is completed in stage 17.
- General buffer throughput caps belong to stage 23.
- Methods/API document generation belongs to stage 25.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/LedgerStore.java` | MODIFY | Quarantine, bound, atomically persist immutable entries. |
| `src/main/java/imagejai/engine/SessionCodeJournal.java` | MODIFY | Bind entries to initiating datasets. |
| `src/main/java/imagejai/engine/StateInspector.java` | MODIFY | Stable all-plane dataset hash. |
| `src/main/java/imagejai/engine/security/AuditLog.java` | MODIFY | Safe CSV and honest corrupt-summary diagnostics. |
| `src/main/java/imagejai/engine/safeMode/RoiAutoBackup.java` | MODIFY | Collision-resistant atomic backups. |
| `src/test/java/imagejai/engine/LedgerStoreTest.java` | MODIFY | Cover corruption/bounds/immutability. |
| `src/test/java/imagejai/engine/security/AuditLogTest.java` | MODIFY | Cover formula/corrupt/atomic cases. |
| `src/test/java/imagejai/engine/StateInspectorTest.java` | NEW | Cover full-dataset hashing and lifecycle. |

## Implementation sketch

Read into a temporary validated model; on corruption move the original to a timestamp-plus-random quarantine name and return an explicit error without saving an empty replacement. Capture dataset ID/hash at request admission, not completion. Hash plane bytes with dimensions/type/calibration metadata in a documented order. Prefix CSV cells beginning `=`, `+`, `-`, or `@` safely. All replacements write temp, fsync where available, then atomically move.

## Exit gate

1. Corrupt stores are preserved in quarantine and cannot be overwritten by the next save.
2. Oversized entries/histories fail with structured errors; returned snapshots cannot mutate storage.
3. An async image switch cannot redirect journal/methods provenance.
4. Same-second backups/IDs do not collide; locale changes do not change hashes/order.
5. Formula-like audit fields open as text and malformed audit rows remain visible as diagnostics.

## Known risks

- Full-stack hashes are expensive; stage 23 may add bounded caching, but correctness comes first.
- Atomic move availability varies by filesystem; implement a tested safe fallback that never destroys the original first.
