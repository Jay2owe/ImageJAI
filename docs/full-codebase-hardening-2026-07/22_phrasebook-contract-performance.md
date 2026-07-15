# Exact, bounded phrasebook-to-intent contract

## Why this stage exists

Thirty phrasebook IDs representing 1,410 phrases have no Java handler, while the documented generator removes 314 IDs unless `--keep` is passed. Matching and `/improve` can also perform enormous fuzzy cross-products and full sorts on the Swing event thread.

## Prerequisites

- Stage 10 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Local Assistant and phrasebook".
- `src/main/resources/phrasebook.json` and `tools/phrasebook_build.py`.
- `src/main/java/imagejai/local/IntentLibrary.java`, `IntentMatcher.java`, and intent factories.
- `src/main/java/imagejai/local/ImproveSession.java` and `slash/ImproveSlashCommand.java`.
- Existing intent/matcher/generator tests.

## Scope

- Resolve every phrasebook ID to a real handler or remove it only with explicit reviewed evidence that it is obsolete.
- Make the documented generator preserve the canonical ID set by default.
- Add build-time equality checks between phrasebook IDs and Java handlers.
- Precompute normalized phrases and use bounded best-candidate/top-k selection.
- Move expensive improve/matching work off the Swing event thread.
- Make ordering deterministic under ties/locales.

## Out of scope

- Assistant mutation policy is completed in stage 10.
- General chat buffer caps belong to stage 23.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/resources/phrasebook.json` | MODIFY only if obsolete IDs are proven | Canonical phrases/IDs. |
| `tools/phrasebook_build.py` | MODIFY | Preserve IDs and validate equality by default. |
| `src/main/java/imagejai/local/IntentLibrary.java` | MODIFY | Register complete handler set. |
| `src/main/java/imagejai/local/IntentMatcher.java` | MODIFY | Precompute and bound matching. |
| `src/main/java/imagejai/local/ImproveSession.java` | MODIFY | Bound/offload improve candidate search. |
| `src/main/java/imagejai/local/intents/analysis/AnalysisIntentFactory.java` | MODIFY | Supply missing analysis handlers. |
| `src/main/java/imagejai/local/intents/control/ControlIntentFactory.java` | MODIFY | Supply missing control handlers. |
| `src/test/java/imagejai/local/IntentMatcherBenchmarkTest.java` | MODIFY | Equality, determinism, and bounded-work tests. |

## Implementation sketch

Generate normalized immutable entries once and scan for the best two/top-k without sorting the whole corpus. The equality gate computes `phrasebookIds == registeredHandlerIds` and prints both missing sets. Generator default is preservation; destructive pruning requires an explicit named flag. Background improve work returns through a generation token so stale results cannot update UI.

## Exit gate

1. Build fails with a precise diff for any phrasebook/handler mismatch; current sets are equal.
2. Default phrasebook generation removes zero IDs and is byte-stable.
3. Matching has a tested upper bound proportional to corpus size, not phrase cross-product sorting.
4. Tie ordering is stable across runs/locales.
5. Improve/matching cannot block the Swing event thread.

## Known risks

- Do not invent semantics for missing handlers; map them to existing reviewed intent behavior or explicitly document removal evidence.
- Preserve expected fuzzy matches with golden regression cases while changing the algorithm.
