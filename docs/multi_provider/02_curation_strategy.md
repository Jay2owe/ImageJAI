# Curation and Auto-Discovery Strategy

**Status:** Draft · **Date:** 2026-05-02
**Depends on:** [`01_provider_survey.md`](01_provider_survey.md) · **Consumed by:** stage 5 (UI), stage 7 (synthesis)

## Purpose

Stage 1 catalogued ~80 tool-calling models across 15 providers. The dropdown cannot be hand-maintained at that surface (providers add/rename/retire monthly), but a pure auto-discovered list is unusable (a biologist scrolling 300 OpenRouter routes with no description has no way to choose).

The runtime list is the merge of:

- **(a)** Live `/models` API results from each configured provider.
- **(b)** Manually curated descriptions in `agent/providers/models.yaml` (shipped with ImageJAI).
- **(c)** User-local override file at `%APPDATA%\imagejai\models_local.yaml` (Windows; OS equivalents below).

This file is the **single unified yaml** carrying both display fields (`display_name`, `description`, `tier`, `pinned`, `vision`) and context-loader fields (`harness`, `family`, `context_size`, `tool_reliability`); both Java `ProviderRegistry` and Python `agent/contexts/loader.py` read it and ignore unknown fields.

## Sections

1. [`models.yaml` schema](#1-modelsyaml-schema) · 2. [Merge rules](#2-merge-rules) · 3. [Soft-deprecation](#3-soft-deprecation) · 4. [User pin mechanism](#4-user-pin-mechanism) · 5. [Update cadence and cache](#5-update-cadence-and-cache) · 6. [Auto-discovery endpoints per provider](#6-auto-discovery-endpoints-per-provider) · 7. [Curator workflow and audit tool](#7-curator-workflow-and-audit-tool)

---

## 1. `models.yaml` schema

Curated entries live in `agent/providers/models.yaml`, bundled in the JAR, read at startup. Flat YAML list under top-level `models:`. Flat (no nested provider grouping) because the merge layer keys off `(provider, model_id)` pairs and a flat list diffs cleanly in the audit tool.

### Field reference

| Field | Req | Type | Description |
|---|:-:|---|---|
| `provider` | yes | string | Provider key matching `01`. Lowercase, hyphenated: `ollama`, `ollama-cloud`, `openai`, `anthropic`, `gemini`, `groq`, `cerebras`, `openrouter`, `github-models`, `mistral`, `together`, `huggingface`, `deepseek`, `xai`, `perplexity`. |
| `model_id` | yes | string | Verbatim from provider's `/models`. Examples: `claude-opus-4-7`, `gemma4:31b-cloud`, `openai/gpt-oss-120b`. |
| `display_name` | yes | string | Short row name. Tier badge + icons take rest of row. |
| `description` | yes | string | 1-2 sentences in lay language. ≤200 chars. Aim at a biologist who has never heard of attention heads. |
| `tier` | yes | enum | `free` / `free-with-limits` / `paid` / `requires-subscription`. Drives badge colour. |
| `context_window` | yes | int | Max input+output tokens. Trusted from upstream when upstream returns one. |
| `vision_capable` | yes | bool | Accepts image inputs (screenshots, microscopy panels). |
| `tool_call_reliability` | yes | enum | `high` / `medium` / `low`. Drives weak-model warning. |
| `last_verified` | yes | ISO date | Curator's last confirmation. Audit tool flags >90 days. |
| `pinned` | yes | bool | Curator pin: stays in dropdown even if upstream `/models` drops it. |
| `curated` | yes | bool | Always `true` for entries from this file (auto-discovered stubs synthesised in memory). Real field so user-overrides can refer to it. |
| `harness` | yes | enum | `cli_shell` / `tool_loop`. Consumed by `agent/contexts/loader.py`. |
| `family` | yes | string | Family overlay key: `anthropic`, `gemma`, `llama`, `qwen`, etc. Loader reads `families/{family}.md`. |
| `context_size` | no | enum | `small` / `medium` / `large`. Loader applies `small_context.md` overlay when `small`. |
| `deprecated_since` | no | ISO date | Set on soft-deprecation. |
| `replacement` | no | string | `model_id` user should switch to. Surfaced in "no longer available" dialog. |
| `notes` | no | string | Curator commentary; visible in audit tool + details tooltip. |

### Conventions

- `tier` follows 01's pricing taxonomy: `free` = no card/no quota; `free-with-limits` = free tier with caps; `paid` = pay-as-you-go credit; `requires-subscription` = Ollama Pro / Claude Pro / etc.
- For Ollama local models, `tier: free`; `context_window` is the model's native context, not user hardware.
- `model_id` is verbatim — do not normalise. `provider` is the only synthetic field.

### Example

```yaml
# agent/providers/models.yaml — unified registry (display + context fields).
# Edited by hand. Audit with `python -m agent.providers.audit_models`.

models:
  - provider: anthropic
    model_id: claude-opus-4-7
    display_name: Claude Opus 4.7
    description: >
      Anthropic's strongest model for long Fiji sessions. Best at multi-step
      tool calls and writing reliable ImageJ macros. Costs roughly 5p per analysis.
    tier: paid
    context_window: 1000000
    vision_capable: true
    tool_call_reliability: high
    last_verified: 2026-05-01
    pinned: true
    curated: true
    harness: tool_loop
    family: anthropic
    notes: >
      New tokenizer consumes ~35% more tokens than 4.5 — factor into stage-6 cost.

  - provider: ollama-cloud
    model_id: gemma4:31b-cloud
    display_name: Gemma 4 31B (cloud)
    description: >
      Free vision-capable model — looks at screenshots and microscopy panels.
      Light rate limits on the free Ollama account.
    tier: free-with-limits
    context_window: 128000
    vision_capable: true
    tool_call_reliability: medium
    last_verified: 2026-05-01
    pinned: true
    curated: true
    harness: tool_loop
    family: gemma
    notes: ImageJAI's existing default Gemma path.

  # Soft-deprecated example
  - provider: anthropic
    model_id: claude-haiku-3-5
    display_name: Claude Haiku 3.5
    description: Cheap, fast Claude. Older — Haiku 4.5 is usually the better choice.
    tier: paid
    context_window: 200000
    vision_capable: false
    tool_call_reliability: medium
    last_verified: 2026-04-15
    pinned: false
    curated: true
    harness: tool_loop
    family: anthropic
    deprecated_since: 2026-04-20
    replacement: claude-haiku-4-5
    notes: Anthropic dropped from /models on 2026-04-20.

  # Auto-discovered stub (synthesised at merge time, never written to file)
  - provider: openrouter
    model_id: moonshotai/kimi-vl-a3b-thinking-2507
    display_name: moonshotai/kimi-vl-a3b-thinking-2507
    description: Uncurated — pricing and capabilities unknown.
    tier: free-with-limits      # neutral default; rendered greyed-out
    context_window: 65536       # taken from upstream when present
    vision_capable: false       # conservative default
    tool_call_reliability: low  # conservative default
    last_verified: 2026-05-02   # set to today by merge layer
    pinned: false
    curated: false
```

The fourth entry is illustrative only — the in-memory shape the merge function emits for unknown models. Never written to file.

---

## 2. Merge rules

The dropdown is built once per refresh tick (§5) by joining curated and live `/models` on `(provider, model_id)`.

| Curated? | Upstream? | Result |
|:-:|:-:|---|
| yes | yes | Use curated as-is. Refresh `last_verified` to today. Trust upstream `context_window` if present. Clear any soft-deprecation flag (model is back). |
| yes | no | Enter or stay in soft-deprecated state (§3). Pinned entries stay visible indefinitely with stronger warning. |
| no | yes | Synthesise uncurated stub: neutral icon, "uncurated — pricing unknown" subtitle, greyed-out tier badge. `tool_call_reliability: low` so the stage-5 warning fires. |
| no | no | Cannot happen (nothing to merge). |

### Conflict resolution

| Field | Trust |
|---|---|
| `context_window` | upstream (provider knows their own limits) |
| `vision_capable` | upstream when flagged; curator otherwise (most `/models` don't expose this) |
| `display_name` | curator (upstream names inconsistent — `gpt-5-mini-2025-08-07` vs `GPT-5 Mini`) |
| `description`, `tier`, `tool_call_reliability`, `notes`, `harness`, `family` | curator (upstream doesn't carry these) |
| `deprecated_since`, `replacement` | curator |
| `pinned` | curator (or user — see §4) |

### Pseudocode

```python
def merge_models(curated: list[dict], live: dict[str, list[str]],
                 today: date) -> list[dict]:
    by_key = {(e["provider"], e["model_id"]): e for e in curated}
    upstream_keys = {(p, m) for p, ms in live.items() for m in ms}

    out = []
    for key, entry in by_key.items():
        if key in upstream_keys:
            entry = {**entry, "last_verified": today.isoformat()}
            entry.pop("deprecated_since", None)
        else:
            entry = mark_soft_deprecated(entry, today)
        out.append(entry)
    for key in upstream_keys - by_key.keys():
        out.append(synthesise_uncurated(*key, live_meta(live, key), today))
    return out
```

`live_meta()` reaches into provider-extra fields (`context_length`, `pricing`, `multimodal`) to pre-fill the synthesised stub. `mark_soft_deprecated()` and `synthesise_uncurated()` per §3 and §1.

---

## 3. Soft-deprecation

A curated model can disappear from `/models` because the provider retired it, renamed it, or the endpoint glitched. Lifecycle protects users from all three without flooding them with churn.

### Lifecycle

```
   present upstream
  ┌────────────────┐
  │   Active       │   normal dropdown row
  └───────┬────────┘
          │ upstream missing
          ▼
  ┌────────────────┐
  │ Soft-deprecated│   visible 30 days
  │  (≤30 days)    │   "no longer available since YYYY-MM-DD"
  └───────┬────────┘
          │ 30 days elapsed
          ▼
  ┌──────────┐  OR  ┌────────────────────┐
  │ Hidden   │      │ Pinned-deprecated  │
  │ (pinned: │      │ (pinned: true)     │
  │  false)  │      │ stays visible,     │
  │ removed  │      │ stronger warning   │
  └──────────┘      └────────────────────┘
```

### `mark_soft_deprecated()`

```python
def mark_soft_deprecated(entry: dict, today: date) -> dict:
    if "deprecated_since" not in entry:
        entry = {**entry, "deprecated_since": today.isoformat()}
    age_days = (today - date.fromisoformat(entry["deprecated_since"])).days
    if age_days > 30 and not entry.get("pinned"):
        entry = {**entry, "_hidden": True}      # filtered out before render
    return entry
```

**Implementation guard (Phase G):** only mark deprecated on a *successful* endpoint call that returns *without* the model — never on a failed call. Otherwise transient endpoint outages cascade through every model.

### Dropdown appearance per phase

**Active.**
```
[●] Claude Opus 4.7              [PAID]  vision  high
    Anthropic's strongest model for long Fiji sessions...
```

**Soft-deprecated, day 1-30.** Amber dot, struck-through name.
```
[!] Claude Haiku 3.5             [PAID]  high
    No longer available since 2026-04-20 — try Claude Haiku 4.5
```

**Pinned-deprecated, day 31+.** Red dot, badge swapped to RETIRED.
```
[X] gemma4:31b-cloud             [RETIRED]  vision
    Provider has retired this model. Calls will fail. Switch to gemma5:34b-cloud.
```

**Hidden.** Not in the dropdown. Still in `models.yaml` so audit tool reports it.

### Click on a soft-deprecated row

1. Attempt the call.
2. On the first "model not found" failure (HTTP 404 or `model_not_found` body), surface a Swing dialog:

   > **Claude Haiku 3.5 is no longer available.**
   > Anthropic retired this model on 2026-04-20. We recommend switching to **Claude Haiku 4.5** — same speed, slightly cheaper, better tool calls.
   > [ Use Claude Haiku 4.5 ] [ Pick another model ] [ Try again anyway ]

3. "Try again anyway" handles the rare case of a wrong upstream `/models` response. No auto-retry.

Dialog text generated from the `replacement` field; if empty, falls back to a generic "open the model picker" button.

---

## 4. User pin mechanism

Curator pins describe what every user should keep. User pins describe what *this* user wants. Both coexist; user wins.

### File location

| OS | Path |
|---|---|
| Windows | `%APPDATA%\imagejai\models_local.yaml` |
| macOS | `~/Library/Application Support/imagejai/models_local.yaml` |
| Linux | `${XDG_CONFIG_HOME:-~/.config}/imagejai/models_local.yaml` |

Created on first pin. Survives ImageJAI updates (lives outside JAR + Fiji install).

### Schema

```yaml
# Managed by the ImageJAI dropdown. Hand-edits preserved but the dropdown
# may rewrite the file when you click the pin/unpin icon.
version: 1
overrides:
  - provider: anthropic
    model_id: claude-opus-4-7
    pinned: true                 # always shown even if curator unpins it
    hidden: false
  - provider: ollama-cloud
    model_id: gemma4:31b-cloud
    pinned: false
    hidden: true                 # user does not want to see this
  - provider: openrouter
    model_id: meta-llama/llama-3.3-70b-instruct:free
    pinned: true                 # user pinned an uncurated upstream model
    hidden: false
```

Three fields per entry: `provider`, `model_id`, any subset of `pinned`/`hidden`. Anything unmentioned falls through to curator defaults.

### Pin UI affordances

- Star icon on every dropdown row. Empty = unpinned, filled = pinned. Click toggles.
- Right-click menu: "Hide this model" / "Show hidden models" (header toggle).
- Pinned entries float to top, sorted by provider then `display_name`.

### Merge order

```python
def apply_user_overrides(merged: list[dict], local: dict) -> list[dict]:
    overrides = {(o["provider"], o["model_id"]): o
                 for o in local.get("overrides", [])}
    out = []
    for entry in merged:
        key = (entry["provider"], entry["model_id"])
        ovr = overrides.get(key, {})
        if "pinned" in ovr:
            entry = {**entry, "pinned": ovr["pinned"]}
        if ovr.get("hidden") and not entry.get("pinned"):
            continue                            # user-hidden, drop
        if entry.get("_hidden") and not entry.get("pinned"):
            continue                            # soft-deprecation hide
        out.append(entry)
    return out
```

Last-writer-wins reading order: (1) `models.yaml` → (2) live `/models` merge → (3) soft-deprecation → (4) `models_local.yaml` → (5) render.

A user pin overrides everything, including curator-marked deprecation. Pinned-deprecated rows stay with the strongest warning; calls still fail gracefully.

### User pins survive updates

`models_local.yaml` is outside the JAR + Fiji install. The plugin only reads/writes overrides for keys already in it; never deletes. A user who pinned `claude-opus-4-7` last year still has it after upgrading to v3.0.

---

## 5. Update cadence and cache

### Refresh triggers

| Trigger | Behaviour |
|---|---|
| Fiji startup | If any cache > 24 h, refresh in background before first dropdown show. Stale renders, updates in place. |
| Manual refresh button | Top of dropdown. Refreshes all configured providers in parallel. |
| API key change for provider X | Refresh X immediately. Others untouched. |
| Provider unconfigured | Skipped — no request, no log. Header reads "Not configured — add a key in settings". |

### Cache file layout

```
<config-root>/imagejai/cache/models/
  ollama.json  ollama-cloud.json  openai.json  anthropic.json  gemini.json
  groq.json  cerebras.json  openrouter.json  github-models.json  mistral.json
  together.json  huggingface.json  deepseek.json  xai.json  perplexity.json
```

`<config-root>` = same OS dir as `models_local.yaml` (§4). Each file:

```json
{
  "provider": "anthropic",
  "fetched_at": "2026-05-02T09:14:33Z",
  "endpoint": "https://api.anthropic.com/v1/models",
  "models": [
    {"id": "claude-opus-4-7", "context_length": 1000000, "type": "model"}
  ]
}
```

`models` is the verbatim provider payload normalised to `[{id, ...}]`. Extra fields (pricing, modalities, deprecation) preserved — `live_meta()` reads from this blob.

### TTL and failure modes

- **TTL: 24 h.** After, next dropdown render fires a background refresh.
- **Refresh failure** (network/401/5xx): keep cache, log, surface red dot on refresh button. Stale-but-present beats empty.
- **Cache miss** (no file): synchronous fetch when dropdown first opened, **5 s timeout** per provider. On timeout that provider shows "Couldn't reach — click refresh"; others render.
- **Manual refresh** uses **4 s** per-provider timeout (06 §4.4).

---

## 6. Auto-discovery endpoints per provider

All return JSON; most OpenAI-compatible (`{"data": [{"id": "..."}]}`); exceptions noted.

| # | Provider | Endpoint | Auth | Notes |
|---|---|---|---|---|
| 1 | Ollama (local) | `GET http://localhost:11434/api/tags` | none | `{"models": [{"name": "..."}]}` — wrapper translates. |
| 2 | Ollama Cloud | **No live endpoint.** | — | Catalogue is a static page; curated entries authoritative. Audit tool scrapes the page on manual run. |
| 3 | OpenAI | `GET https://api.openai.com/v1/models` | `Authorization: Bearer <key>` | Standard OpenAI list. |
| 4 | Anthropic | `GET https://api.anthropic.com/v1/models` | `x-api-key: <key>`, `anthropic-version: 2023-06-01` | `{"data": [{"id": "claude-...", "display_name": "...", "type": "model"}]}`. |
| 5 | Gemini | `GET https://generativelanguage.googleapis.com/v1beta/models?key=<key>` | API key in query | `{"models": [{"name": "models/gemini-...", "supportedGenerationMethods": [...]}]}`. Filter to entries with `generateContent`. |
| 6 | Groq | `GET https://api.groq.com/openai/v1/models` | `Authorization: Bearer <key>` | OpenAI-shaped. Includes preview-tier — combine with curated `tool_call_reliability`. |
| 7 | Cerebras | `GET https://api.cerebras.ai/v1/models` | `Authorization: Bearer <key>` | OpenAI-shaped. |
| 8 | OpenRouter | `GET https://openrouter.ai/api/v1/models` | none for listing | 300+ entries with `pricing.prompt`/`completion`, `context_length`, `architecture.modality`, `top_provider`. Merge harvests directly. |
| 9 | GitHub Models | `GET https://models.github.ai/catalog/models` | `Authorization: Bearer <PAT>` (`models:read`) | `[{name, publisher, summary, model_family, ...}]`. Wrapper translates `name` → `model_id` as `<publisher>/<name>`. |
| 10 | Mistral | `GET https://api.mistral.ai/v1/models` | `Authorization: Bearer <key>` | OpenAI-shaped. |
| 11 | Together AI | `GET https://api.together.xyz/v1/models` | `Authorization: Bearer <key>` | OpenAI-shaped; `id` is full HF-style path. |
| 12 | HF Inference Providers | `GET https://router.huggingface.co/v1/models` | `Authorization: Bearer <hf_token>` | Routable models; HF picks fastest/cheapest at call time. |
| 13 | DeepSeek | `GET https://api.deepseek.com/v1/models` | `Authorization: Bearer <key>` | OpenAI-shaped. |
| 14 | xAI Grok | `GET https://api.x.ai/v1/models` | `Authorization: Bearer <key>` | OpenAI-shaped. |
| 15 | Perplexity | **No public list endpoint.** | — | Curated entries authoritative. |

The two "no live endpoint" providers (Ollama Cloud, Perplexity) have the merge layer treat curated as also being upstream — curated entries always pass the "present upstream" check, no uncurated stubs synthesised. Audit tool warns curator when these need manual refresh.

---

## 7. Curator workflow and audit tool

### Tool location

`agent/providers/audit_models.py` — invoked as `python -m agent.providers.audit_models`. Deps: `requests`, `pyyaml` (already in `agent/`).

### Output sketch

```
$ python -m agent.providers.audit_models
ImageJAI model audit — 2026-05-02 10:14 UTC

[anthropic]
  + new since last audit: claude-sonnet-4-7  (1M ctx)
  - missing since last audit: claude-haiku-3-5  (last_verified 2026-04-15)
  ! stale curation (>90 days): claude-haiku-4-5  (last_verified 2026-01-30)

[gemini]
  + new since last audit: gemini-3.2-flash-preview  (1M ctx)
  ok: gemini-3-flash-preview, gemini-3.1-flash-lite-preview, gemini-2.5-pro,
      gemini-2.5-flash, gemini-2.5-flash-lite

[ollama-cloud]
  ! no live endpoint — curated entries assumed authoritative
  ! stale curation (>90 days): kimi-k2:1t-cloud  (last_verified 2026-01-12)

[openrouter]
  + 14 new models since last audit (use --verbose to list)
  - 3 missing since last audit (use --verbose to list)

Summary: 17 new, 4 missing, 3 stale.
Run `python -m agent.providers.audit_models --apply` to write a stub block
for each new model into models.yaml (curator must then fill description and tier).
```

### Sketch implementation

```python
# agent/providers/audit_models.py
"""Diff curated models.yaml against each provider's live /models."""
from __future__ import annotations
import argparse, datetime as dt, sys
from pathlib import Path
import yaml, requests

CURATED = Path(__file__).parent / "models.yaml"
ENDPOINTS = {              # see §6 for full table
    "anthropic": ("https://api.anthropic.com/v1/models",
                  {"x-api-key": "$ANTHROPIC_API_KEY",
                   "anthropic-version": "2023-06-01"}),
    "openai":    ("https://api.openai.com/v1/models",
                  {"Authorization": "Bearer $OPENAI_API_KEY"}),
    "gemini":    ("https://generativelanguage.googleapis.com/v1beta/models"
                  "?key=$GEMINI_API_KEY", {}),
    # ...one row per provider with a live endpoint
}
STALE_DAYS = 90

def load_curated() -> dict[tuple[str, str], dict]:
    raw = yaml.safe_load(CURATED.read_text())
    return {(e["provider"], e["model_id"]): e for e in raw["models"]}

def fetch_live(provider: str) -> set[str]:
    url, hdrs = ENDPOINTS[provider]
    r = requests.get(expand_env(url), headers=expand_env_dict(hdrs), timeout=10)
    r.raise_for_status()
    body = r.json()
    items = body.get("data") or body.get("models") or []
    return {it.get("id") or it.get("name", "").rsplit("/", 1)[-1] for it in items}

def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true",
                    help="Append stubs for new models to models.yaml")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    today = dt.date.today()
    curated = load_curated()
    new, missing, stale = [], [], []

    for provider in ENDPOINTS:
        try:
            live = fetch_live(provider)
        except Exception as e:
            print(f"[{provider}] fetch failed: {e}", file=sys.stderr); continue
        cur = {m for (p, m) in curated if p == provider}
        new += [(provider, m) for m in live - cur]
        missing += [(provider, m) for m in cur - live]
        for (p, m), entry in curated.items():
            if p != provider: continue
            age = (today - dt.date.fromisoformat(entry["last_verified"])).days
            if age > STALE_DAYS: stale.append((p, m, age))

    print_report(new, missing, stale, verbose=args.verbose)
    if args.apply: append_stubs(new, today)
    return 0 if not (missing or stale) else 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

`expand_env`, `expand_env_dict`, `print_report`, `append_stubs` are small helpers (env-var substitution, formatted printing, YAML round-trip with `ruamel.yaml` to preserve comments). ~120 lines including helpers.

### Curator routine

1. **Monthly:** `python -m agent.providers.audit_models`. Read the report.
2. For each `+ new` of interest: write description, tier, reliability in `models.yaml`. `--apply` pre-creates stubs (`curated: false`, `tier: free-with-limits` placeholder) so curator only edits.
3. For each `- missing`: confirm against provider release notes. If retired, set `deprecated_since` + `replacement`. If renamed, update `model_id`.
4. For each `! stale`: re-read provider section in `01`, confirm pricing/limits, bump `last_verified`.
5. Commit: `models: refresh after 2026-05 audit`.

### Cadence

Monthly matches 01's pricing-volatility observation; 30-day soft-deprecation window catches retirements before they confuse users. Quarterly is too slow (Anthropic dropped Opus pricing 3× in one cycle).

Scheduled background-agent candidate (the `/schedule` skill): "every first Monday, run audit and open a PR if there are new entries". Out of scope here — flagged for stage 7.
