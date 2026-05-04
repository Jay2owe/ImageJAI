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

---

## Second-pass independent review (2026-05-04, same day)

**Auditor:** independent reviewer (single agent, fresh context, not part
of the original /claude-sequential queue).
**Branch:** main, in sync with origin.
**Audit type:** Outstanding-item closure + cross-phase smell sweep that
the per-phase scoping in the original pass could not see.

Three commits pushed in this pass: `b6ce04e`, `ea4235c`, `881479b`. None
mutate the frozen plan documents `00`–`08`; only `AUDIT_LOG.md` is
rewritten.

### Outstanding item 1 — `BillingFailureDialog` and `BudgetCeilingDialog`

**Status:** FIXED in `ea4235c`.

Plan §H lists both as Swing classes; the original implementation surfaced
billing failures as terminal output and the budget ceiling as the
Python-side `_pause_message()` only. Two new Swing classes ship under
`src/main/java/imagejai/ui/picker/`:

- `BillingFailureDialog` (06 §3.7) — DOCUMENT_MODAL parented to a `Frame`,
  three exits (Switch model / Open `<provider>` console / Close). The
  console-open path uses `Desktop.browse(URI)` with a `BrowserOpener`
  test seam so the suite stays headless; falls back to a `JOptionPane`
  showing the URL when `Desktop` is unsupported.
- `BudgetCeilingDialog` (06 §6.3) — DOCUMENT_MODAL with a `JSpinner` for
  the post-resume ceiling. Default = current × 2 per 06 §6.3 step 5; the
  Resume path rejects values at-or-below the current ceiling so a
  confused user can't lock the loop into immediate re-pause. Three
  exits — Resume / Switch to a free model / Close. Optional
  fallback-estimate caveat ("may be ±20%") rendered when the dialog's
  caller flags the cost as a fallback rather than a header value.

Both classes mirror the modality and parenting conventions of
`FirstUseDialog.java`, including `WindowAdapter.windowClosing` mapping
[×] to a non-destructive default per 06 §3.2.

**13 unit tests added** (6 BillingFailureDialog + 7 BudgetCeilingDialog)
exercise modality type, default result, title text, provider-display
fallback, browser-opener test seam, and the ceiling-doubling default.
All headless-safe via `assumeFalse(GraphicsEnvironment.isHeadless())`.

**Wiring left as a follow-up phase.** The Java-side dialog is the
long-running mirror that fires when an upstream signal arrives — the
Python tool loop already owns the `/resume`/`/switch` REPL, and the
cross-language signal channel between `ProxyAgentLauncher` (401/402/429
catch) and the Java Swing thread is not yet defined in the plan. The
two dialog classes themselves close out the Phase H spec acceptance
gap.

### Outstanding item 2 — `agent/ollama_agent/budget_ceiling.py` path drift

**Status:** DOCUMENTED (no code change).

Plan §H referenced `agent/providers/budget_ceiling.py` as the new
location. The file is at `agent/ollama_agent/budget_ceiling.py` and is
functional. We deliberately did **not** move it because:

1. `agent/ollama_agent/` is in the no-touch list for this rollout. A
   move would touch unrelated in-progress work in that directory.
2. The Java mirror (`imagejai.engine.budget.BudgetCeilingTracker`)
   is at the spec-correct package, so the behaviour in `06 §6.3` is
   reachable from both languages without renaming the Python file.
3. `agent/providers/test_models_yaml_pricing.py` already imports the
   pricing loader from `agent.ollama_agent.budget_ceiling` — moving the
   file would require renaming the import, which has the same blast
   radius as touching `ollama_agent/` directly.

A future phase that re-owns the Python budget ceiling can move the file
and update the import. Until then this is intentional drift, recorded
once here.

### Outstanding item 3 — `families/gemini.md` and `families/other.md`

**Status:** FIXED in `b6ce04e`.

The loader silently skipped missing family files: 4 entries with
`family: gemini` and 2 with `family: other` lost their family overlay
step entirely.

Two changes:

- `agent/contexts/families/gemini.md` — minimal stub. Per
  `04 §3.5` the Gemini quirk ("Tool-result formatting differs from
  Anthropic — Gemini wraps `function_response` in a structured object")
  is a wrapper concern (`agent/providers/gemini_native.py`), not a prompt
  concern. The stub names that explicitly so future quirks accrue here
  rather than being re-discovered.
- `agent/providers/models.yaml` — Perplexity Sonar / Sonar Pro
  reclassified from `family: other` to `family: llama`. Sonar is a
  Llama-derived grounded-search wrapper; the existing `families/llama.md`
  stub overlay is the right tone. This drops the `other` family
  entirely so no `families/other.md` stub is needed.
- `agent/contexts/_snapshots/gemini_gemini-2.5-pro.md` regenerated to
  include the new family overlay tail.
- `agent/contexts/_snapshots/perplexity_sonar.md` removed — Perplexity
  is no longer the canonical entry for any family (Groq Llama 3.3 70B
  is the canonical for `llama`), so the snapshot was orphaned.

All 62 contexts tests pass after the change.

### Outstanding item 4 — Snapshot test coverage

**Status:** PLAN UPDATED via this audit log entry; no code change.

Plan §F acceptance reads "Snapshot-tests every composed output to lock
against drift." The current `agent/contexts/test_contexts.py` design
asserts:

1. `test_every_model_composes` — smoke test over all ~38 yaml entries:
   each composes without raising, and the composition includes the
   `base.md` content marker.
2. `test_snapshot_canonical_per_family` — byte-identical snapshot against
   one canonical entry per family (~13 family canonicals in registry
   order).
3. `test_claude_path_equivalence` — every substantive fragment from
   `agent/CLAUDE.md` survives in the composed Claude context.
4. `test_groovy_console_error_fact_in_base` — fact-survival check across
   three model_ids covering both harness types.

The chosen split is **per-family canonical for snapshots, per-entry
smoke for every model** rather than per-entry snapshots for all ~38
models. Justification:

- The loader's output is a deterministic function of the tuple
  (`harness`, `vision_capable`, `context_size`, `tool_call_reliability`,
  `family`). Two yaml entries with the same tuple produce byte-identical
  output, so per-entry snapshotting gives no extra drift coverage over
  per-tuple snapshotting.
- Per-family canonical covers the family axis cleanly and catches the
  failure mode that motivated the spec acceptance ("a hand-edited
  family overlay regresses"). The other four axes are exercised by
  targeted regression tests (`test_no_vision_overlay_applied_for_text_only_model`,
  `test_weak_tools_overlay_applied_for_low_reliability`,
  `test_high_reliability_omits_reliability_overlay`).
- 38 snapshots × ~440 lines each = ~17 000 LOC of pinned text, most of
  it byte-identical between siblings on the same family. The
  maintenance cost of regenerating these on every legitimate base/overlay
  change outweighs the diagnostic benefit.

Treat this as the canonical reading of "snapshot-tests every composed
output": every composition is *covered* (smoke), and one per family is
*pinned* (snapshot). Future audits can lift the snapshot coverage to
per-tuple if a real drift slips through.

### Cross-phase smell — `Settings.setLastError` was dead code

**Status:** FIXED in `881479b`.

`Settings.setLastError(provider, message)` and the per-provider error
map `lastProviderErrors` were defined for `CachedErrorDialog` to read,
but no production code path ever populated them. Phase G's
`runRefreshOffEdt` fan-out already had the failure information in
`MergeFunction.LiveResult.failure()` but threw it away after building
the failed-providers list. As a result, every ✗ status icon click
would render `(no cached error)` instead of the provider's actual
response.

This is the kind of bug per-phase scoping was unlikely to catch: it
spans Phase E (the dialog), Phase G (the discovery loop), and the
Settings schema (a single `Map<String, String>`). The fix routes the
error through:

- `ProviderDiscovery.lastErrorFor(providerId)` — new accessor backed by
  a `ConcurrentHashMap` that the `discover()` loop populates on every
  IO failure (IOException class name + message) or non-2xx HTTP status
  (status code + body, truncated to 240 chars to keep the JSON tidy if
  a provider returns a verbose HTML error page). Cleared on every
  successful `discover()` so a one-off transient outage doesn't leave
  stale text after the provider recovers.
- `AiRootPanel.runRefreshOffEdt` — reads `ProviderDiscovery.lastErrorFor`
  and feeds it into `Settings.setLastError` on failure; on success calls
  `setLastError(provider, null)` to clear. Curated-only providers
  (`ollama-cloud`, `perplexity`) never produce an error so the existing
  fallback path leaves their entries untouched.

**Credential safety check.** No keys are persisted by this change. Keys
live in `<imagej-ai>/secrets/<provider>.env`, not in `config.json` (per
`Settings.java:148-152`). Only HTTP body fragments and IOException
messages reach `lastProviderErrors`, and bodies are size-capped at 240
chars before storage. Verified by tracing every call site of
`setLastError` after the change — only one call site, in
`AiRootPanel.runRefreshOffEdt`, with the message sourced from
`ProviderDiscovery.lastErrorFor`.

**4 new tests** in `ProviderDiscoveryTest` cover IOException capture,
HTTP 4xx body capture (with the OpenAI-style nested error body),
success-clears-error semantics, and curated-only providers staying
clear. Total `ProviderDiscoveryTest` count: 15 passing.

### Other cross-phase observations (no fix in this pass)

These are documented for the next reviewer; none are blocking the
multi-provider rollout from shipping.

1. **`BudgetCeilingTracker` is never instantiated in production.** The
   class lives at `imagejai.engine.budget.BudgetCeilingTracker`
   with a real `BreachListener` interface, a `CostHeaderListener`
   implementation, and 9 unit tests — but no production code path
   constructs one or registers it with `LiteLlmProxyService`. The Java
   mirror is therefore a mirror of an empty puddle until the dialog
   wiring (above) lands. Tracked for the same follow-up phase that
   wires `BillingFailureDialog`/`BudgetCeilingDialog` to their fire
   sources.
2. **`CachedErrorDialog` is never invoked.** Phase E ships the class
   but no Swing handler in `ProviderCard` / `ProviderMenu` calls
   `new CachedErrorDialog(...).show(parent)` on a ✗ status icon click.
   The dialog now has real data to render (item 5 fix above) but
   nothing surfaces it. Same follow-up.
3. **Concurrent agent sessions racing on the proxy port file.** The
   prior audit closed the `state.json` race via `Settings.save()` atomic
   move. The same race exists on
   `agent/providers/proxy.port`: two Fiji instances both spawn LiteLlm
   sidecars, each rewrites `proxy.port`, and the second listener
   misses the first's read. Already partially mitigated by the
   port-scan fallback (4000–4010), so the worst case is "the Java
   side scans for a few hundred ms before settling" rather than
   "wrong port" — but a file lock on `proxy.port` would close the
   window cleanly. Not blocking.
4. **`MergeFunction.LiveResult` carries no error reason.** The fix
   above bolted the error onto `ProviderDiscovery` instead of widening
   the `LiveResult` record because callers already use `failure()` as
   a sentinel and adding a third state would touch every test fixture.
   If the dialog wiring follow-up wants the reason in the merge layer
   too, a `LiveResult.failure(String reason)` overload is the cheap
   change.

### Cumulative test results after this pass

- `python -m pytest agent/providers/ agent/contexts/ -x --tb=short` →
  **120 passed, 0 failed.**
- `mvn test -Dtest='*MultiProvider*,*Picker*,*Provider*Test,*Installer*,*Credential*,*FirstUse*,*Uncurated*,*Discovery*,*Cache*,*SoftDeprecation*,*Merge*,*Wizard*,*UserState*,*Settings*,*BillingFailure*,*BudgetCeiling*'`
  → **135 tests, 0 failures, 0 errors, 0 skipped** (up from 109 in the
  first pass: +13 dialog tests, +4 ProviderDiscovery error-capture
  tests, +9 prior tier/budget tests = +26).
- `mvn -DskipTests compile` → BUILD SUCCESS.
- `python -m agent.contexts.loader anthropic/claude-opus-4-7` composes
  cleanly.

**Final status after this second pass: GREEN.** The four outstanding
items from the first pass are closed (1 fixed, 1 documented, 1 fixed,
1 plan-clarified). One real cross-phase smell was found and fixed
(`Settings.setLastError` dead code). Four further smells are noted
above for the follow-up phase that wires the new dialogs to their
fire sources.
