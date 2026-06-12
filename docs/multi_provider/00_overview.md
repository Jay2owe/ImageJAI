# Multi-Provider AI Backend — Research and Implementation Plan

## Goal

Replace ImageJAI's current Ollama-Cloud-only AI backend with an extensible multi-provider system. Users will pick a provider (Ollama, Claude, Gemini, Groq, Cerebras, OpenRouter, GitHub Models, Mistral, etc.) from a cascading dropdown, and a model within that provider, with clear descriptions of cost, speed, and capability. A local LiteLLM proxy stores all credentials and routes most requests; native paths kept for Anthropic and Google.

## Architecture in one paragraph

LiteLLM Proxy runs as a localhost daemon (started on Fiji boot, ~few seconds). It holds API keys in one config file. Most providers route through it via an OpenAI-compatible client pointed at `localhost:4000`. Anthropic Claude and Google Gemini bypass the proxy and use their native SDKs because their tool-calling APIs are richer than the OpenAI translation. From the user's perspective: same Swing play button, same terminal pops up, same agent chat — the dropdown just got bigger.

## Plan-for-the-plan: seven research stages

| # | Stage | Depends on | Deliverable |
|---|---|---|---|
| 1 | Provider and model survey (deep pricing audit) | — | `01_provider_survey.md` |
| 2 | Curation and auto-discovery strategy | 1 | `02_curation_strategy.md` |
| 3 | Wrapper architecture spike (working POC code) | — | `03_wrapper_architecture.md` + `agent/providers/_spike/` |
| 4 | Per-model context strategy | — | `04_context_strategy.md` |
| 5 | Lay-language descriptions and UI design | 1, 2, 6 | `05_ui_design.md` |
| 6 | Cost and tier-safety rules | 1 | `06_tier_safety.md` |
| 7 | Synthesis: the actual implementation plan | 1–6 | `07_implementation_plan.md` |

Execution shape:

```
[1: survey]──┐
             ├──→ [2: curation]──┐
[3: spike]───┤                   ├──→ [5: UI] ──→ [7: synthesis]
             ├──→ [6: tier safety]┘
[4: context]─┘
```

## Conventions

- All research outputs live in `docs/multi_provider/` (this directory).
- Spike code lives in `agent/providers/_spike/`.
- Each agent writes its deliverable incrementally so quota interruptions preserve work-to-date.
- No commits. No modifications to existing `CLAUDE.md` / `AGENTS.md` / `GEMINI.md` files.
- Models, pricing, and rate limits change monthly — research must verify against live provider docs (May 2026), not training data.

## Key decisions already made (from planning conversation)

- **Anthropic and OpenAI are included** in the dropdown despite being paid-only. Clearly labelled.
- **Cost safety**: paid models always require an active subscription or API balance, so users cannot be auto-charged. Tier badge always visible. First-use-of-paid-model dialog confirms.
- **Tool-calling distinction**: presented in lay language ("highly reliable" vs "good but occasionally needs a retry"), not technical ("native vs wrapped").
- **Per-model context files**: extend the existing `CLAUDE.md` / `AGENTS.md` / `GEMINI.md` pattern. Stage 4 designs the structure.
- **Auto-discovery + curation**: fetch each provider's `/models` endpoint at runtime, overlay manually curated descriptions from `models.yaml`. Pinned favourites cannot be removed when models disappear upstream (soft-deprecation).
- **Ollama dropdown split**: local pulled models vs `:cloud` models shown distinctly. Local models get download-size warnings.
- **LiteLLM Proxy autostart**: a few seconds of startup is acceptable.
