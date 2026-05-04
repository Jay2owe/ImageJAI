# Multi-Provider Rollout — Audit Log

**Date:** 2026-05-04
**Auditor:** /claude-sequential queue (8 phase-audit agents + final integration agent)
**Branch:** main, in sync with origin
**Audit type:** AUDIT + GAP-FILL pass against the eight committed phases A–H of
the multi-provider plan in `docs/multi_provider/07_implementation_plan.md`.

This file is the single audit-trail entry for the cycle. Plan documents in
`docs/multi_provider/` remain frozen — only `AUDIT_LOG.md` is touched here.

---

## Per-phase results

| Phase | Original commit                                     | Audit fix commits                              | Criteria met after audit                                                                                            |
|-------|-----------------------------------------------------|------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| A     | (curation/yaml & router foundation)                  | `0ebe27f`                                      | Stale `proxy.port` no longer pins to 4000 — scanner sweeps 4000–4010 to find the live LiteLLM sidecar.              |
| B     | (registry loader / safe yaml)                        | (no gaps found)                                | SafeConstructor on every yaml load; no audit fixes required.                                                         |
| C     | `cb7d2cb` Phase C native bypass                      | `9c785b4`, `8525c25`, `45b9858`                | Gemini server_tools (Search + code execution) opt-in via router; prompt-cache hit / parallel tools / vision tests added; Opus 4.7 tokenizer_multiplier promoted to a structured field. |
| D     | `1cabe23` Phase D picker shell                       | `51ce049`, `1ad0844`, `413644f`                | Icon classes split (TierBadgeIcon / StatusIcon / PinStarIcon); ProviderRegistry.refreshProvider + RefreshWorker landed; legacy selectedAgentName migrates to (provider, model_id). |
| E     | `74e0f55` Phase E credentials wizards                | `b9cd0e2`, `31f5398`                           | Ollama daemon `/api/tags` 2s sanity check + cloud-flow keeps only the token; CredentialVerifier hook surface added on each wizard. |
| F     | `f3eb40a` Phase F context overlays                   | `e7be3ad`, `6ca58d3`                           | UTF-8 stdout for the loader CLI on Windows; baseline snapshots refreshed after Safe Mode v2 stage 02 lines were added. |
| G     | `7303c78` Phase G discovery + soft-deprecation       | `722933e` + (final) `2d48296`                  | Production CredentialVerifier wired through ProviderDiscovery in MultiProviderPanel; **AiRootPanel.modelPicker.setRefreshTask** now feeds a real RefreshTask (was the outstanding gap — closed below). |
| H     | `99c1ded` Phase H ceiling + tier gates               | `cf6127f`, `462e270` + (final) `d2dc5f3`       | Atomic Settings.save() closes E.7 race; 1.35× Opus 4.7 multiplier reaches the fallback math; **pricing block now present in models.yaml** so the multiplier actually applies in production (was the outstanding gap — closed below). |

Total audit-fix commits in this pass: **15** (13 phase-audit + 2 final integration).

---

## Final integration commits (2026-05-04)

These are the cross-phase fixes the per-phase audit agents could not address
because they spanned phase boundaries.

### `2d48296` — wire AiRootPanel refresh-task to ProviderDiscovery + ModelsCache

`ModelPickerButton.headerRefreshButton.setEnabled(refreshTask != null)` meant
the dropdown's ↻ button was permanently disabled in production: nothing was
ever calling `setRefreshTask()` outside the unit tests.

`AiRootPanel.buildRefreshTask()` now wires:
- `ProviderDiscovery` over the 13 fan-out endpoints (4 s timeout per 06 §4.4),
  with `ollama-cloud` and `perplexity` skipped per 02 §6.
- `ModelsCache.write` for every successful provider; cache fallback for failed.
- `MergeFunction.merge` + `applyVisibility` against the curated yaml + user
  overrides + live results.
- `ProviderRegistry.fromMerged` for the new dropdown contents.
- New / removed counts diffed against the previous registry for the header
  strip's "X new, Y removed since last check" affordance.

### `d2dc5f3` — Opus 4.7 pricing block

`load_pricing_from_models_yaml` silently skips entries without a top-level
`pricing` block. Phase C's `tokenizer_multiplier: 1.35` field landed but Phase
H's fallback math never saw it — the entry was dropped before the multiplier
was read. Added the May 2026 rates ($15/M input, $75/M output) so the entry
round-trips, plus a regression test under `agent/providers/` (kept out of
`agent/ollama_agent/` because that directory is the no-touch zone for this
rollout).

---

## Outstanding issues for future work

1. **`agent/ollama_agent/budget_ceiling.py` path drift.** The plan listed the
   file at `agent/providers/budget_ceiling.py`. It is actually under
   `agent/ollama_agent/`. We deliberately did **not** move it because
   `agent/ollama_agent/` is in the no-touch list for this rollout (unrelated
   work landed there in parallel). A future phase that re-owns the budget
   ceiling can move the file, update import paths in
   `test_models_yaml_pricing.py`, and slim the import surface.

2. **`BillingFailureDialog` is currently console-only.** The spec sketches a
   Swing dialog for visibility when the ceiling fires; the current
   implementation logs to the console + Settings. If the user wants a
   modal-on-pause, that is a small follow-up phase under Phase H.

3. **Pricing blocks for non-Opus 4.7 entries.** Only Opus 4.7 has a structured
   pricing block today. Other paid models (Sonnet 4.6, Haiku 4.5, GPT-5,
   Gemini 2.5 Pro, …) still rely on the LiteLLM cost header at runtime. If the
   header is unreliable, a follow-up phase should bulk-fill the yaml.

4. **Java jacoco instrumentation warnings on `java/sql/Time`.** `mvn test`
   prints `Unsupported class file major version 69` warnings during jacoco
   instrumentation but the tests themselves pass and report `BUILD SUCCESS`.
   This is a jacoco-vs-JDK-25 mismatch, not a multi-provider issue. Tracked
   separately.

---

## Verification

- **Java build:** `mvn -DskipTests compile` — `BUILD SUCCESS`.
- **Java multi-provider tests:** `mvn test -Dtest='*MultiProvider*,*Picker*,...'`
  — **109 tests, 0 failures, 0 errors, 0 skipped**.
- **Python tests (Phase F + multi-provider):** `python -m pytest agent/providers/
  agent/contexts/` — **121 tests, 0 failures**.
- **Python tests (budget ceiling on real yaml):** `python -m pytest
  agent/ollama_agent/test_budget_ceiling.py` — **17 tests, 0 failures**.
- **Smoke tests:**
  - `python -m agent.contexts.loader anthropic/claude-opus-4-7` composes the
    full base + family overlay cleanly.
  - `python -m agent.providers.audit_models --help` prints argparse usage.
  - `python -c "from agent.providers.router import get_client, PROVIDER_KEYS;
    print(PROVIDER_KEYS); print(get_client('groq', 'llama-3.3-70b-versatile')
    is not None)"` returns the 15 canonical provider tuple and `True` (router
    does not require a key for client construction).

**Final status: GREEN.** Multi-provider rollout is complete with all eight
phases audited, and the two outstanding cross-phase gaps closed.
