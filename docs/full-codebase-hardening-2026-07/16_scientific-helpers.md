# Correct scientific validation helpers

## Why this stage exists

Scientific helper defects can turn invalid measurements into passes: NaN/infinity/impossible geometry are accepted, even medians are wrong, large pixel arrays become huge Python objects, and colour changes can disappear in grayscale comparison.

## Prerequisites

- Stage 01 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Python scientific helpers".
- `agent/auditor.py`, `pixels.py`, and `image_diff.py`.
- `agent/test_auditor_api.py` and `test_pixels_api.py`.

## Scope

- Reject NaN, infinity, negative counts, impossible geometry, and unknown audit checks.
- Fix even-length median, empty arrays, NaN policy, and structured server errors.
- Avoid materializing millions of pixel values as Python objects.
- Compare colour channels and define constant-image correlation behavior.
- Add property/edge tests for numeric domains and array shapes.

## Out of scope

- Trainer/practice isolation belongs to stage 17.
- Server-side pixel allocation limits belong to stage 23.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/auditor.py` | MODIFY | Fail invalid/nonfinite/unknown checks. |
| `agent/pixels.py` | MODIFY | Correct robust vectorized statistics/errors. |
| `agent/image_diff.py` | MODIFY | Preserve channel differences and constants. |
| `agent/test_auditor_api.py` | MODIFY | Add invalid-domain regressions. |
| `agent/test_pixels_api.py` | MODIFY | Add median/empty/NaN/structured-error tests. |
| `agent/test_image_diff.py` | NEW | Cover colour and constant-image behavior. |

## Implementation sketch

Validate all numeric inputs with finite/domain constraints before calculations. Use NumPy arrays/statistics without `.tolist()` for large payloads; define a documented missing/NaN policy. Compare images per channel plus aggregate metrics. For constant images, return an explicit perfect match only when arrays are equal; otherwise define a non-perfect/undefined correlation result without divide-by-zero.

## Exit gate

1. Nonfinite, negative, impossible, and unknown audits fail with named reasons.
2. Median/empty/NaN tests match documented NumPy semantics.
3. A 4-million-pixel test stays within a bounded memory envelope.
4. Pure colour-channel changes are detected; equal/unequal constant images are distinguished.
5. Offline Python tests pass.

## Known risks

- Changing NaN policy can affect callers; return structured compatibility errors rather than silently dropping values.
- Memory tests should assert algorithmic shape/allocations where possible, not flaky machine-specific ceilings.
