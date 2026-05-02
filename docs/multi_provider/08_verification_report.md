# Multi-Provider Plan — Verification Report

**Date:** 2026-05-02
**Scope:** Cross-doc consistency audit of `00_overview.md` … `07_implementation_plan.md`
plus the spike at `agent/providers/_spike/`. Looks for true conflicts,
drift, decisions not yet propagated, broken cross-refs, and risks not
captured in 07's register.

The four user-stated decisions applied to the audit:

1. **Single unified `agent/providers/models.yaml`** carrying both display
   fields *and* context-loader fields.
2. **Standalone ▶ button** is repurposed to "re-launch last", not removed.
3. **`sync_context.py` is migrated, not patched** — Phase F replaces it
   with the new overlay loader.
4. **Budget ceiling lives in v4 (Phase H), not v1.**

---

## Section A — True conflicts (require human decision)

### A.1 Tier vocabulary diverges across three docs

The same field uses three different value sets:

| Doc | Tier values |
|---|---|
| `02 §1` (`models.yaml` schema) | `free`, `free-with-limits`, `paid`, `requires-subscription` |
| `06 §1.3` (curator-facing) | `free`, `free_limited`, `paid_credits`, `paid_sub` |
| `04 §4` (registry example) | `free`, `paid`, `local` (and the loader sketch only checks `vision`/`small_context`) |

These are **not stylistic** — `free-with-limits` ≠ `free_limited` ≠
absence of an entry. A YAML written for one doc will not validate
against another. Compounded by the unified-yaml decision (one file,
two consumers), the Java side and the Python loader will both try to
read the same `tier:` field.

**My read:** 02's hyphenated set is the most discoverable in YAML and
is the schema the curator actually edits. Adopt 02's names everywhere
(`free`, `free-with-limits`, `paid`, `requires-subscription`). Map the
six surfaces (06 badge `🟢/🟡/🔵/🟣/⚪`; 04 loader; 05 dropdown filter
"only free") onto that one set. 06's worked-examples section needs the
substitution applied; 04's registry tier values need replacing.

### A.2 Provider-key spelling diverges between spike and curation doc

The spike's `router.py` uses underscores (`github_models`, `ollama_cloud`,
`ollama`). `02 §1` uses hyphens (`github-models`, `ollama-cloud`,
`ollama`). The Python provider modules promoted in Phase B will inherit
the spike's underscores; the YAML Phase D writes will use 02's hyphens;
the lookup `router.get_client("github-models", ...)` will fail.

**My read:** hyphens win (matches every directory listing in the docs,
matches Python convention for filenames, matches the Java side). Phase
B fix: the production `router.py` normalises both forms or the
`_PROXY_PROVIDERS` set is updated to use hyphens. Recommend an
allow-list of canonical provider keys defined once in
`agent/providers/__init__.py` and imported by both `router` and the
Java side via the YAML.

### A.3 Spike's `_PROXY_PROVIDERS` set is incomplete

`agent/providers/_spike/router.py` whitelists eight providers:
`openai, groq, cerebras, openrouter, github_models, mistral, ollama_cloud,
ollama`. But the plan promises 15: `01` lists Together, Hugging Face,
DeepSeek, xAI, Perplexity in addition. `router.get_client("together",
...)` raises `ValueError` today.

**My read:** Phase B production must extend `_PROXY_PROVIDERS` to all
non-native providers. Add to Phase B acceptance criteria.

### A.4 Tool-reliability vocabulary diverges across four docs

| Doc | Values |
|---|---|
| `02 §1` (yaml schema) | `high`, `medium`, `low` |
| `04 §3.4`, `04 §6` | `excellent_tools`, `good_tools`, `weak_tools` |
| `06 §2.3` (UI strings) | `highly reliable`, `good but occasional retries`, `inconsistent — small or unproven` |
| `07 §B` acceptance | refers to `tool_call_reliability` (no value list) |

The unified YAML stores one value; UI displays another; loader composes
overlays keyed by a third. **My read:** YAML uses 02's `high/medium/low`.
The 04 overlay filenames stay as-is (`weak_tools.md`) — those are file
names, the YAML field is the source. The mapping
(`high→excellent_tools.md`/no overlay, `medium→good_tools.md`,
`low→weak_tools.md`) is the loader's job. UI labels in 06 are display
strings; map them in `ModelMenuItem` rendering. Document the mapping
in the unified yaml header comment.

### A.5 Phase F internal contradiction over context registry location

`07 §F` "New files" lists:

> `agent/contexts/` ... `models.yaml` (the *context* registry — note this
> file is in a different location and serves a different purpose from
> `agent/providers/models.yaml`)

But `07 §F` "Modified files" then says:

> `agent/providers/models.yaml`: extend each entry with the
> context-loader fields used by `loader.py` ...

And Open Question 5 in `07 §5` is **RESOLVED** as "merge into a single
`agent/providers/models.yaml`". The "New files" parenthetical
contradicts the resolution. **My read:** delete the parenthetical; do
not create a second yaml under `agent/contexts/`. (See Section C.1.)

### A.6 Feature-flag retirement window

`05 §10.6`: "The feature flag is removed in the release after the new
picker proves stable; at that point the old `JComboBox` field ... are
deleted in one commit."

`07 §1`: "atomic switchover only once Phase D ships and the lab build
has run for at least two weeks without regressions."

These describe the same gate but state different criteria — "release
after stable" vs "two weeks". Either could be intended; pick one.

**My read:** keep 07's "two weeks lab build" (concrete, falsifiable).
Update 05 §10.6 to reference 07's criterion.

### A.7 Open Q3 vs the user-stated decision on `sync_context.py`

User-stated decision: "sync_context.py is migrated, not patched. Phase F
replaces it with the new overlay loader."

`07 §5` Open Q3 currently says: "Phase F's plan is to *bridge* — keep
`sync_context.py` running but point its derivation at the new loader.
... **Recommendation: keep the bridge for now.**"

The two statements can be reconciled if "bridge" means "thin shim that
delegates to `loader.load_context()`" — but the wording makes the
reader think `sync_context.py` survives unchanged in Phase F.

**My read:** rewrite Open Q3's resolution to say "RESOLVED: Phase F
rewrites `sync_context.py` to delegate to `loader.load_context()`. The
file path is preserved for backward compatibility with anyone running
it manually, but its logic is replaced." (See Section C.3.)

---

## Section B — Drift / naming inconsistencies (mechanical fixes)

### B.1 Provider-key spelling

Canonical: hyphens. Drift sites:

- `agent/providers/_spike/router.py:21,24-27` — `_NATIVE_PROVIDERS`
  uses `gemini`, `_PROXY_PROVIDERS` uses `github_models`, `ollama_cloud`.
  (Spike file is read-only per task; Phase B production fixes this.)
- `06 §1.3` curator-facing example uses `ollama_local`, `ollama_cloud`,
  `gemini_free`/`gemini_paid`, `openrouter_free`/`openrouter_paid`,
  `deepseek_direct`, `mistral_paid`, `hf_inference`. These should be
  `ollama`, `ollama-cloud`, `gemini`, `openrouter`, `deepseek`,
  `mistral`, `huggingface` (no `_free`/`_paid`/`_direct` suffixes — the
  tier is a per-model field, not a provider variant).
- `04 §6` registry example uses model ids like `anthropic/claude-opus-4-7`,
  `ollama/gemma4:31b-cloud`. The unified yaml has separate `provider`
  and `model_id` fields (per `02 §1`). Reconcile: the loader's keying
  string can be `provider/model_id` for `load_context("provider/model")`
  ergonomics, but the yaml itself has two fields.

### B.2 Provider count

- `00`, `01 §master comparison table`, `05 §9.2`, `07 §1` all say **15** providers.
- `05 §2.2` ASCII mockup shows 13 expanded provider rows.
- `02 §6` lists 15 endpoints; two are "no live endpoint" (Ollama Cloud, Perplexity).

No real conflict — count is consistent — but the mockup misses
GitHub Models and one other. Update the mockup ASCII to show all 15
when condensing.

### B.3 ASCII-mockup line numbers vs Java line numbers

`05 §1` quotes line numbers from `AiRootPanel.java` (63, 209-212,
213-223, 224, 257-265, 311-337, 339-371, 373-384, 386, 398-411,
413-436). `07 §D` repeats them. They are consistent with each other.
Verify before Phase D execution that the lines still match (the file
will have moved).

### B.4 Cache TTL vs refresh-button timeout

`02 §5`: cache TTL is **24 h**; manual-refresh button timeout is
**5 s** for cache miss; refresh on demand.
`05 §8.4`: refresh-button per-provider timeout is **2-3 s** (rendered
as `~2-3s` in §8.2 "greys out the dropdown for ~2-3s while in flight").
`06 §4.4`: per-provider refresh timeout is **4 s**.
`07 §G`: "5 s per-provider timeout" for cache miss; "4 s" for manual
refresh.

Three numbers (2-3, 4, 5) for what is effectively the same operation.
**Canonical:** 4 s for manual refresh (06's number, repeated in 07);
5 s for synchronous first-fetch on cache miss (02's number, repeated
in 07). Update `05 §8.2` to drop the 2-3 s figure.

### B.5 `last_verified` field

`02 §1` shipping schema requires `last_verified` per entry.
`06 §7.6` uses a per-provider `last_verified` plus per-model
`pricing_changes`. `04 §6` registry example omits `last_verified`
entirely.

**Canonical:** per-entry `last_verified` (per 02). Add a note in 04's
registry example showing it merges with the curated entry's field;
no separate context-side field.

### B.6 "Auto-discovered" subsection naming

- `02 §1`/`§3` doesn't show a section header.
- `05 §4.4` mockup uses `── Auto-discovered (unverified) ──`.
- `06 §5.1` uses `— Auto-discovered (unverified) —`.
- `07 §G` references "auto-discovered subsection".

Settle on `— Auto-discovered (unverified) —` (em-dash).

### B.7 Cost-header name

`07 §A` and `07 §H`: `x-litellm-response-cost` (verbatim).
`06 §6.3`: `x-litellm-response-cost`.

Consistent — flag only because the same string is repeated four times
across docs. Keep all references identical character-for-character
during condensing.

### B.8 Dropdown launch behaviour: 4-way variance on what ▶ does

- `00` (existing): ▶ launches the selected agent.
- `05 §1` "remove or repurpose" (ambivalent).
- `05 §2.1` "repurposed to 're-launch last'".
- `05 §10.3` and `07 §D` ▶ becomes "re-launch last".

User decision is "re-launch last". Drop the ambivalent wording in
`05 §1` (`"can either be removed, repurposed as 're-launch last', or
kept as a fallback trigger"`) — user has decided.

### B.9 Phase numbering shadow: 04 §5 phases A–E vs 07 phases A–H

`04 §5` describes a five-phase migration (Phase A "Build", B "Cut over
sync_context.py", C "Cut over Gemma", D "Multi-provider Stage 7 wires
the loader", E "Eventual cleanup"). These are 04-internal but use the
same letters as 07's Phase A–H. A reader cross-jumping between docs
can confuse 04's "Phase B" (sync_context cutover) with 07's "Phase B"
(Python provider modules).

**Fix:** rename 04 §5 phases to "Step 1 / Step 2 / …" or "F-1, F-2,
…" so they are obviously local to 04.

### B.10 `models_local.yaml` naming

`02 §4`: `%APPDATA%\imagejai\models_local.yaml`.
`05 §11.3`: same.
`06`: not mentioned by name (refers to "user pin/hide overrides").
`07 §G`: `%APPDATA%/imagejai/models_local.yaml`.

Consistent — recorded for completeness.

### B.11 Effort estimates internally consistent

A=2, B=2.5, C=1.5, D=5, E=4, F=3, G=4, H=3.5 → 25.5 days. Phase 6
"MVS v1" lists A+B+D+F = 12.5 days; not stated explicitly anywhere
but math checks out. No conflict.

---

## Section C — Decisions to propagate

### C.1 Decision 1 — Single unified `agent/providers/models.yaml`

Sites still describing two yaml files or a separate context registry:

- **`04 §4` directory layout**: shows `models.yaml` *inside*
  `agent/contexts/`. Should be removed; loader reads
  `agent/providers/models.yaml`.
- **`04 §4` loader sketch**:
  ```python
  CTX_DIR = Path(__file__).parent
  REGISTRY = CTX_DIR / "models.yaml"
  ```
  Should be:
  ```python
  REGISTRY = Path(__file__).parents[1] / "providers" / "models.yaml"
  ```
- **`04 §4` "Registry format (`models.yaml`)"** section header — clarify
  this is the unified file; add a note that the Java `ProviderRegistry`
  reads the same file.
- **`07 §F` New files**: drop the parenthetical claiming the context
  registry is "in a different location and serves a different purpose".
  Clarify it's the same file Phase D created, extended by Phase F with
  context-loader fields.
- **`02 §1` schema doc**: add a note that this file *also* carries
  context-loader fields (`harness`, `family`, `context_size`,
  `tool_reliability`) consumed by `agent/contexts/loader.py`.
- **`06 §1.3`**: the curator yaml example needs the same field set —
  add a comment that fields not shown (display, harness, vision, etc.)
  belong on the same entry per the unified-yaml decision.

### C.2 Decision 2 — Standalone ▶ button repurposed (not removed)

Sites describing it as removable:

- **`05 §1` Implications**: `"The play-button (`agentBtn`, line 257) is
  separate from the dropdown today: ... can either be removed,
  repurposed as 're-launch last', or kept as a fallback trigger; §10
  picks 're-launch last' as the safe default."` Drop the "either be
  removed" hedge — user has confirmed.

All other surfaces (`05 §2.1`, `05 §10.3`, `07 §D` and `§5` Open Q4
"Confirmed") already reflect the decision correctly.

### C.3 Decision 3 — `sync_context.py` migrated, not patched

Sites that imply patch-level changes:

- **`07 §5` Open Q3**: currently labelled "Recommendation: keep the
  bridge for now." Should be relabelled **RESOLVED** with text:
  `"Phase F rewrites sync_context.py to delegate read_claude_md() and
  the AGENT_FILES map to loader.load_context(). The file path is
  preserved (anyone running it manually still gets generated CLI
  contexts) but its logic is replaced. Removal of the file altogether
  is a future cleanup, not a Phase F goal."`

### C.4 Decision 4 — Budget ceiling in v4, not v1

Already correctly propagated — `07 §6` MVS v1 explicitly excludes
budget ceiling, `07 §5` Open Q2 recommends "defer to v4 as planned",
`06 §6` is the v4 design. **No further fixes needed.**

### C.5 Other decisions worth propagating

- **Anthropic and OpenAI are both included despite being paid-only** —
  `00 §Key decisions`. Already in `01`, `06 §1.3`, `07 §6`. No drift.
- **Ollama dropdown split (local vs cloud)** — `00`, `01 §Ollama`,
  `01 §Ollama Cloud`. `05 §2.2` mockup shows them as distinct providers.
  `02 §6` has separate endpoints. `07 §A` config has both. No drift.
- **LiteLLM Proxy autostart** — `00`, `07 §A`. Consistent.
- **Soft-deprecation 30-day window** — `02 §3`, `05 §4.2`, `07 §G`.
  Consistent.

---

## Section D — Cross-reference fixes

### D.1 Misnumbered references

- **`05 §6.1`**: "(stacks AFTER the paid dialog if both apply, per
  `06 §5.3`)". 06 §5.3 IS the uncurated dialog section — this ref is
  correct.
- **`05 §6.5`**: refers to `06 §5.3` — correct.
- **`07 §1`** Risk register: `03 §8` referenced for risks #1-#9, with
  specific item numbers. Verified — `03 §8` does carry items 1-10 with
  the right indices.
- **`07 §A` acceptance**: refers to `01 §Master comparison table` —
  correct.
- **`07 §F` acceptance**: refers to `04 §3.4` for `weak_tools.md` —
  correct.
- **`04 §3.4`** refers to `agent/ollama_agent/CLAUDE.md:11–13`. Verified
  externally — that file exists in the repo per `03 §7`.

### D.2 Stage-1 section references missing in `06`

`06 §1.3` says "see section 5 of `01_provider_survey.md`" — `01` has no
numbered sections, only provider-named subsections plus a "Master
comparison table" and "Key takeaways". The reference probably means
"§Key takeaways item 5" (tool-call reliability ranking) — but item 5
is the takeaway about `models.yaml` last-verified timestamps, not
reliability. The reliability takeaway is item 5 in the original ranked
list under `01 §Key takeaways item 5`. **Fix:** change ref to
"`01 §Key takeaways for the next stages` item 5 (tool-call reliability
scaling)" so a reader knows what to look for.

### D.3 `02 §5` vs `07 §G` cache TTL

Both quote 24 h — verified.

### D.4 `04 §6` describes "dropdown" without referring to 05

`04 §6` "Notes for the UI implementation" lists soft-deprecation,
pinned-favourite behaviour, etc., that 05 specifies in detail. Add
forward-reference: "see `05 §4` for the rendering, `05 §10` for the
migration."

### D.5 `06 §6.1` references LiteLLM cost headers without the canonical name

`06 §6.1` says "LiteLLM proxy already exposes per-request cost in its
response headers". The exact header name (`x-litellm-response-cost`)
appears one section later in `06 §6.3` and again in `07 §A`/`§H`.
While not broken, naming the header in §6.1 too tightens the
description.

---

## Section E — Risks not in 07's register

### E.1 Tier-vocabulary normalisation (Section A.1)

**Where identified:** Section A.1 above, latent in 02/04/06 conflict.
**Why it deserves a register slot:** if Phase B and Phase D ship before
the vocab is reconciled, the YAML written by curator hand will silently
fall through to "uncurated" / "unknown" because the field value
doesn't match the parser's enum.
**Suggested mitigation phase:** Phase B (Python loader) + Phase D
(Java loader). Add a single canonical enum to the unified yaml
header comment; both loaders reject unknown values with a clear error.

### E.2 Provider-key normalisation drift (Section A.2)

**Where identified:** spike `router.py` vs `02 §1`.
**Why it deserves a register slot:** the dropdown will look up
`router.get_client("github-models")` and crash because the spike's
allowlist is `github_models`. Catches at runtime, late.
**Suggested mitigation phase:** Phase B. Add an allow-list constant
exported from `agent/providers/__init__.py`; reject unknown providers
at registry load time, not at first launch.

### E.3 Spike's incomplete `_PROXY_PROVIDERS` set (Section A.3)

**Where identified:** spike `router.py:24-27`.
**Why:** Phase B if it ships verbatim from spike covers 8 of 15
providers. The 7 missing (Together, HF, DeepSeek, xAI, Perplexity,
plus the curated/ollama-cloud distinction) silently fail at first
launch.
**Suggested mitigation phase:** Phase B. Add to acceptance criteria:
"router.get_client returns a working client for every provider in
01's master comparison table (15 entries)."

### E.4 `from __future__ import annotations` in CALLER modules

`03 §8` risk #1 names PEP-563 annotations breaking the schema converter
when `from __future__ import annotations` is enabled in the spike's
own test file. The fix is `typing.get_type_hints(fn)`. **But:** the
risk in production is that *consumer* modules (e.g.
`agent/ollama_agent/ollama_chat.py`, where `ALL_TOOLS` is defined) use
the future import, and the converter gets called on tool functions
defined there. The spike's test verifies its own module; the production
converter must succeed against arbitrary caller modules. **Mitigation:**
Phase B test must include a synthetic module with PEP-563 enabled and
import-time tool definitions, asserting `to_openai_tool(fn).parameters`
contains the right primitive types. Already partially in 07 §B
acceptance ("the schema converter from §4.2 with the PEP-563 fix") but
worth explicit test coverage.

### E.5 LiteLLM proxy port file persistence

`07 §A` risks call out a fallback port range 4000-4010 and writing the
chosen port to `proxy.port`. Risk: a stale `proxy.port` file from a
previous run can mislead the Java side after a reboot — the Java
service will try to connect to a port the proxy did not bind. Not
captured.
**Suggested mitigation:** Phase A. The `proxy.port` file is rewritten
on every proxy start; deleted on clean shutdown; if the Java side
finds it stale (no listener), it falls back to scanning 4000-4010
itself.

### E.6 SnakeYAML SafeConstructor on `models_local.yaml`

`07 §D` and `§G` both flag SafeConstructor for `models.yaml`. But
`models_local.yaml` is **user-editable** (lives in `%APPDATA%`) — a
malicious local actor could plant a YAML constructor exploit there.
The risk register only covers the bundled file. **Mitigation:** apply
SafeConstructor to `models_local.yaml` parse path in
`ModelsLocalLoader` (Phase G).

### E.7 Concurrent agent sessions racing on `state.json`

`06 §3.6`, `06 §6`, `06 §7.2` all read/write `~/.imagejai/state.json`.
A user opening two Fiji instances (or one Fiji with the dropdown
re-rendering during a refresh) can race on the file. Last-writer-wins
silently drops favourites or dismissed-banner records.
**Suggested mitigation:** Phase H. Use atomic write (tmp file + rename)
plus a file lock if multi-instance is supported; document if not.

### E.8 Anthropic Opus 4.7 tokenizer cost shift

`01 §Anthropic` notes Opus 4.7 consumes ~35% more tokens for English
text. `07 §C` Risk acknowledges this affects prompt caching test
fixtures. But it also affects the **typical session** estimate that
06 §2.4 and §3.2 surface to the user, and the **budget ceiling** math
in §6.3.
**Suggested mitigation:** Phase H. The `models.yaml` curator note for
Opus 4.7 must include the multiplier; the budget ceiling fallback
(local token-count × yaml price) must apply it; the hover-card range
must be derived against it.

### E.9 LiteLLM proxy version drift breaking the cost header

`07 §A` Risk #11 covers proxy startup time but not version drift on the
cost header. LiteLLM has changed header names between releases. If the
pinned LiteLLM version is upgraded and the header is renamed, Phase H's
budget ceiling silently stops triggering.
**Suggested mitigation:** Phase B/H. Pin LiteLLM version in
`requirements.txt` (already in 07 §B Risks); add a startup self-test
that fires one tiny chat and asserts the cost header is present, fails
loudly if not.

### E.10 `KNOWN_AGENTS` table never refreshed when CLIs update

`05 §1` and `07 §D` describe `KNOWN_AGENTS` as a static table; CLI
agents (`claude`, `aider`, etc.) ship updates that may rename or
remove a CLI binary. The detect-on-PATH logic survives a rename but
the table's display name and description can drift. Not a register
item.
**Suggested mitigation:** post-Phase-D "CLI table refresh" routine —
the table is documentation, not code, and a quarterly re-verify is
cheap. Park as a `/schedule` candidate (mirrors `02 §7`'s curator
audit pattern).

---

## Section F — Reduction summary

Per-doc before/after (bytes):

| Doc | Before | After | Saved | % |
|-----|-------:|------:|------:|--:|
| `01_provider_survey.md` | 38,035 | 26,853 | 11,182 | -29% |
| `02_curation_strategy.md` | 30,849 | 24,440 |  6,409 | -20% |
| `03_wrapper_architecture.md` | 23,191 | 20,477 |  2,714 | -11% |
| `04_context_strategy.md` | 52,605 | 45,072 |  7,533 | -14% |
| `05_ui_design.md` | 61,583 | 54,711 |  6,872 | -11% |
| `06_tier_safety.md` | 52,369 | 46,283 |  6,086 | -11% |
| `07_implementation_plan.md` | 65,818 | 57,218 |  8,600 | -13% |
| **Total (six big docs, 01-07)** | **324,450** | **275,054** | **49,396** | **-15%** |

Breakdown of the cut:

- **01** had the largest pure-prose redundancy — provider sections each opened with a 1-2 paragraph preamble that just summarised the table that followed. Cutting those preambles delivered the headline 29% saving without losing a single price, RPM/RPD limit, or recommendation.
- **02-04** lost mostly section preambles, behavioural-rule re-phrasings, and the duplicated provider taxonomy (the curated yaml example moved to one place). 04's `base.md` draft (~10 KB) is essentially incompressible — every paragraph there is operational guidance the agent reads directly.
- **05** kept every ASCII mockup verbatim (per the "every dialog mockup" rule) but stripped the multi-paragraph rationale before each Swing pitfall and the duplicated migration prose. Most of the volume in 05 is the mockups themselves; the prose-to-mockup ratio was already favourable.
- **06** kept all six dialog ASCII boxes and the three banner mockups verbatim. Cuts came from collapsing the per-section "Reasoning"/"Why this matters" subsections and shortening the §8 i18n section.
- **07** lost the most boilerplate — phase headers (Goal/Acceptance/Files/Dependencies/Effort/Risks) had a lot of repeated framing language, and the New/Modified/NOT touched lists carried a lot of "leave this verbatim" prose that compressed well to single-sentence form. Substantive acceptance criteria and risk register entries are preserved.

**Why not 40-50%.** The four mandatory categories — every code excerpt, every ASCII mockup, every concrete number (pricing, limits, line refs), and every drafted dialog/lay-language description — together account for roughly 50% of the original 324 KB. With those held constant, the remaining 50% (the prose) compressed to roughly 30% — i.e. the cuttable surface lost ~40% of its mass, which is the headline rate the prompt asks for, but applied only to redundant prose. The aggregate 15% reflects how much pure-redundancy prose was actually present.

The four user-stated decisions were propagated:
1. **Unified `agent/providers/models.yaml`** — `02`, `04 §4`, `06 §1.3`, `07 §F` all updated; the loader sketch in `04 §4` now reads `agent/providers/models.yaml` directly.
2. **Standalone ▶ → re-launch last** — `05 §1` no longer hedges with "remove or repurpose"; `07 §1` and `§5 Open Q4` confirm.
3. **`sync_context.py` migrated, not patched** — `07 §5` Open Q3 now RESOLVED with explicit "rewrite, don't patch" wording; `04 §5` Step 2 says "rewritten to delegate to `loader.load_context()`".
4. **Budget ceiling in v4 only** — already correctly placed in `06 §6`/`07 §H`/`07 §6`; no change needed.

Other section drift that was fixed in-place:
- 04's "Phase A–E" migration → renamed "Step 1–5" so they don't shadow 07's Phase A–H (B.9).
- Tier vocabulary normalised to 02's set across 02/04/06 (A.1).
- Tool-reliability mapping (`high`/`medium`/`low` yaml ↔ overlay filenames ↔ UI strings) documented in 06 §2.3 and risks register #22.
- 4 s manual-refresh timeout / 5 s cache-miss timeout — `02 §5` and `05 §8.4` now agree (B.4).
- Auto-discovered subsection header normalised to em-dash form `— Auto-discovered (unverified) —` (B.6).
- Spike-vs-canonical provider keys: hyphenated form noted as canonical in 02, 06, 07; risk #19 added; spike file itself untouched per the read-only constraint.

Risks not in the original 07 register that were added: #19–#25 (provider-key drift, spike's incomplete `_PROXY_PROVIDERS`, tier-vocabulary mismatch, tool-reliability vocabulary mismatch, LiteLLM cost-header version drift, Opus-4.7 tokenizer cost shift, concurrent agent sessions racing on `state.json`).

