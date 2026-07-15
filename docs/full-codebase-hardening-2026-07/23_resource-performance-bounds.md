# Cross-cutting resource, path, and buffer bounds

## Why this stage exists

The server accepts several unbounded sizes and may create a thread per connection before global admission. Captures/results/batches, audit/chat buffers, histogram deltas, privacy scanning, visual grants, and path handling can exhaust resources or escape intended scope.

## Prerequisites

- Stages 12, 13, 14, and 22 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, performance/security findings.
- `src/main/java/imagejai/engine/TCPCommandServer.java` and `JobRegistry.java`.
- `src/main/java/imagejai/engine/HistogramDelta.java`.
- `src/main/java/imagejai/engine/security/AuditLog.java`, `PseudonymisationFilter.java`, and `VisualOverrideRegistry.java`.
- `src/main/java/imagejai/ui/ChatView.java`.
- `src/main/java/imagejai/engine/safeMode/DestructiveScanner.java`.

## Scope

- Enforce byte-based request caps, bounded connection workers, batch depth/count, result/capture sizes, and pre-allocation checks.
- Bound chat, audit queues/listeners/summaries, and raw provider/image histories.
- Avoid full-image histogram delta work when budget is exceeded and report truncation/sampling.
- Cache a deterministic token matcher rather than repeatedly sorting/scanning tokens.
- Use strong scoped visual grants and path tokens.
- Resolve/canonicalize paths and prove containment in `AI_Exports/`; block lexical traversal/symlink escape.
- Make provider/log/folder scans distinguish unreadable from empty.

## Out of scope

- Job ownership/cancellation is completed in stage 05.
- Provider round/history policy is completed in stage 07.
- Bundle secret scanning belongs to stage 26.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Bound protocol/connection allocations. |
| `src/main/java/imagejai/engine/JobRegistry.java` | MODIFY | Enforce final capacity/resource limits. |
| `src/main/java/imagejai/engine/HistogramDelta.java` | MODIFY | Bound image-wide comparison work. |
| `src/main/java/imagejai/engine/security/AuditLog.java` | MODIFY | Bound queues/summaries/listeners. |
| `src/main/java/imagejai/engine/security/PseudonymisationFilter.java` | MODIFY | Cached bounded token matcher. |
| `src/main/java/imagejai/engine/security/VisualOverrideRegistry.java` | MODIFY | Strong scoped grants. |
| `src/main/java/imagejai/ui/ChatView.java` | MODIFY | Bounded transcript model. |
| `src/main/java/imagejai/engine/safeMode/DestructiveScanner.java` | MODIFY | Canonical output containment. |

## Implementation sketch

Define named configuration constants and reject before allocation/thread creation. Measure UTF-8 bytes, not Java characters. Use fixed-cap deques/ring buffers with explicit dropped counts. Canonical path checks resolve the intended parent and reject symlink escapes. Expensive image/token work uses cached/vectorized/budget-aware algorithms with an explicit partial-result flag.

## Exit gate

1. Boundary tests reject oversized/multibyte/deep/flood requests before resource creation.
2. Soak tests keep thread, queue, topic, listener, and transcript counts within declared caps.
3. Traversal and symlink tests cannot escape `AI_Exports/`.
4. Expensive scans meet algorithmic work bounds and disclose sampling/truncation.
5. Unreadable resources return errors, never successful empty results.

## Known risks

- Choose limits from existing documented behavior and tests; avoid breaking normal microscopy stacks silently.
- Sampling is unsuitable for exact measurements unless the response explicitly marks it approximate.
