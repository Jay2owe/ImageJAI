# Provider and Model Survey — Multi-Provider AI Backend

**Status:** Live document · **Date verified:** May 2026
**Scope:** Pricing tier, free-tier limits, tool-calling models, ImageJAI suitability for every candidate provider.

ImageJAI's agent runs a long tool-use loop (read state, call JSON tool, write macro, inspect, retry). Each model is judged on:

1. Reliable tool calling at 30+ calls/session without breaking JSON.
2. Reasonable interactive latency.
3. Cost containability — paid providers must be opt-in, never auto-charge.
4. Accessible without card-required signup for the free path.

## Contents

1. [Ollama (local)](#ollama-local) · 2. [Ollama Cloud](#ollama-cloud) · 3. [OpenAI](#openai) · 4. [Anthropic](#anthropic-claude) · 5. [Google Gemini](#google-gemini-ai-studio) · 6. [Groq](#groq) · 7. [Cerebras](#cerebras) · 8. [OpenRouter](#openrouter) · 9. [GitHub Models](#github-models) · 10. [Mistral](#mistral-la-plateforme) · 11. [Together AI](#together-ai) · 12. [HF Inference Providers](#hugging-face-inference-providers) · 13. [DeepSeek](#deepseek) · 14. [xAI Grok](#xai-grok) · 15. [Perplexity](#perplexity) · 16. [Master comparison](#master-comparison-table)

---

## Ollama (local)

Local Ollama runs models on the user's hardware — no signup, no rate limits, no spend, but bound by local RAM/VRAM and download size. Only "tools"-tagged models are usable for ImageJAI.

| Model | Params | Context | Tools | tok/s | Notes |
|-------|--------|---------|-------|------:|-------|
| `llama3.2:3b` | 3B | 128K | yes | 60-100 | Tiny + reliable. ~2 GB. |
| `llama3.1:8b` | 8B | 128K | yes | 40-80 | Sweet spot for laptop GPUs (~5 GB). |
| `llama3.3:70b` | 70B | 128K | yes | 8-15 | Needs 40 GB RAM. ~3.1 405B parity. |
| `qwen3:4b` | 4B | 32K | yes | 50-90 | Strong instruction follower. |
| `qwen3:8b/14b/32b` | 8/14/32B | 32K | yes | 10-50 | "Thinking" mode helps multi-step tools. |
| `qwen2.5-coder:7b/14b/32b` | 7/14/32B | 32K | yes | 20-60 | Best small open for ImageJ macros. |
| `mistral-nemo:12b` | 12B | 128K | yes | 25-50 | Long context, decent tools. |
| `mistral-small3:24b` | 24B | 128K | yes | 15-30 | Stronger than nemo. |
| `granite4:3b` / `granite4.1:8b` | 3/8B | 128K | yes | 40-80 | Reliable JSON. |
| `gemma3:4b/12b/27b` | 4/12/27B | 128K | yes (vision) | 15-60 | Inspects screenshots. |
| `deepseek-r1:7b/14b/32b` | 7/14/32B | 64K | yes | 10-50 | Reasoning, slow. |

- Tier: free-forever-rate-limited (hardware-bound only).
- Signup: zero.
- Tool-call quirks: Qwen2.5/3 and Llama 3.1+ recommended for high reliability. Avoid Phi (not tools-tagged).
- Endpoint: `http://localhost:11434/v1`. OpenAI-compatible. LiteLLM: `ollama/<model>`.
- **Recommendation: must-have.** No card, no quota, no telemetry baseline.

Sources: https://ollama.com/library · https://ollama.com/search?c=tools

---

## Ollama Cloud

Same `ollama` CLI and local API, but `:cloud`-suffixed models run on Ollama infrastructure. Subscription-priced (GPU-time). Free tier is qualitatively rate-limited ("light usage").

| Model | Params | Context | Tools | tok/s | Notes |
|-------|--------|---------|-------|------:|-------|
| `gpt-oss:20b-cloud` | 20B | 128K | yes | 80-120 | Fast OpenAI open weights. |
| `gpt-oss:120b-cloud` | 120B | 128K | yes | 40-70 | Most capable gpt-oss. |
| `qwen3-coder:480b-cloud` | 480B MoE | 256K | yes | 60-100 | Best open coder. ImageJAI's current default. |
| `deepseek-v3.1:671b-cloud` | 671B MoE | 128K | yes | 30-50 | Reasoning + tools. |
| `kimi-k2:1t-cloud` | 1T MoE | 256K | yes | 40-80 | Frontier-class open. |
| `glm-4.6:355b-cloud` | 355B MoE | 200K | yes | 50-80 | Strong agentic. |
| `qwen3:235b-cloud` | 235B MoE | 256K | yes | 50-90 | Smart-yet-fast generalist. |
| `gemma4:31b-cloud` | 31B | 128K | yes (vision) | 60-100 | ImageJAI's existing Gemma path. |

(Other `:cloud` SKUs: Kimi K2.5/K2.6, Devstral-2, MiniMax-M2.5/M2.7, Nemotron-3-Super/Nano, Ministral 3, Qwen3-Next, Gemini 3 Flash Preview.)

- Tier: free-with-paid-tier. Free is "light usage", 1 cloud-model concurrent, 5-h session + 7-day weekly windows. **No published hard numbers** (the main caveat). Pro $20/mo (~50× Free, 3 concurrent). Max $100/mo (~5× Pro, 10 concurrent).
- Signup: browser auth (`ollama signin`). No card.
- Tool-call quirks: same `/api/chat` endpoint as local — each model's tool reliability inherits from base.
- Endpoint: `https://ollama.com/api/chat` (or `localhost:11434` after signin). LiteLLM: `ollama/...`.
- **Recommendation: must-have.** Current default. Vague rate limits the only downside.

Sources: https://ollama.com/pricing · https://docs.ollama.com/cloud · https://ollama.com/search?c=cloud

---

## OpenAI

Frontier closed-weight (GPT-5, o-series). Tool calling is gold-standard reliability. No free tier — positive credit balance required.

| Model | Context | Tools | tok/s | Notes |
|-------|---------|-------|------:|-------|
| `gpt-5` | 400K | yes | 60-90 | $1.25/$10 in/out. |
| `gpt-5-mini` | 400K | yes | 100-150 | $0.25/$2. Sweet spot for tool loops. |
| `gpt-5-nano` | 200K | yes | 150-250 | Cheapest GPT-5. |
| `gpt-4.1` | 1M | yes | 60-100 | $2/$8. 1M-token context champion. |
| `gpt-4.1-mini` | 1M | yes | 100-160 | $0.40/$1.60. |
| `gpt-4.1-nano` | 1M | yes | 200+ | $0.10/$0.40. |
| `gpt-4o` / `gpt-4o-mini` | 128K | yes | 80-150 | Vision-capable. |
| `o3` | 200K | yes (limited) | 30-60 | Reasoning. |
| `o4-mini` | 200K | yes | 80-120 | Cost-effective reasoning. |

- Tier: paid-only-pay-as-you-go. New accounts get no guaranteed credits in 2026 (the $5 welcome credit was dropped mid-2025).
- Signup: card required.
- Tool-call quirks: most reliable. JSON schemas validated server-side. `strict: true` eliminates most edge cases.
- Endpoint: `https://api.openai.com/v1`. Reference for OpenAI-compat. LiteLLM: `openai/<model>`.
- **Recommendation: include for power users.** ★★★ — best tool reliability, no free path for biologists.

Sources: https://openai.com/api/pricing/ · https://platform.openai.com/docs/models

---

## Anthropic (Claude)

Strong for code, multi-step reasoning, long agentic loops — exactly ImageJAI's pattern. Native Messages API is richer than OpenAI-compat (proper schemas, parallel tool calls, server-side `code_execution`/`web_search`). Motivates the bypass-LiteLLM decision.

| Model | Context | Tools | tok/s | Notes |
|-------|---------|-------|------:|-------|
| `claude-opus-4-7` | 1M | yes | 30-60 | $5/$25. Strongest agentic + tool-use. |
| `claude-opus-4-6` | 1M | yes | 30-60 | Same price. "Fast mode" beta at 6× price. |
| `claude-sonnet-4-6` | 1M | yes | 60-100 | $3/$15. Best value for sustained loops. |
| `claude-haiku-4-5` | 200K | yes | 120-180 | $1/$5. Cheap + fast. |
| `claude-haiku-3-5` | 200K | yes | 100-150 | $0.80/$4. Older, cheaper. |

- Tier: paid-only-pay-as-you-go (Claude Pro $20/mo is the consumer chat product, separate from API).
- Free: small opaque test credit only.
- Signup: card required for production rate limits. Tier 1 (~$5 deposit) enables usable RPM.
- Tool-call quirks: most reliable alongside OpenAI. Prompt caching pays off after one read on 5-min cache (1.25× write, 0.1× read). **Opus 4.7 uses a new tokenizer that may consume up to 35% more tokens** for the same English text — factor into cost.
- Endpoint: `https://api.anthropic.com/v1` (Messages API). OpenAI-compat shim exists but loses parallel tool / structured output. **Use native SDK.** LiteLLM: `anthropic/<model>` but prefer direct.
- **Recommendation: must-have.** Best paid-tier for biologists; prompt caching makes 1M loops affordable.

Sources: https://platform.claude.com/docs/en/docs/about-claude/pricing · https://docs.anthropic.com/en/docs/about-claude/models

---

## Google Gemini (AI Studio)

Only frontier-class provider with a usable free tier — no card, generous RPM/RPD, 1M context. Native tool calling solid; vision best-in-class.

| Model | Context | Tools | tok/s | Notes |
|-------|---------|-------|------:|-------|
| `gemini-3.1-pro-preview` | 1M | yes | 50-90 | $2/$12 (≤200K), $4/$18 above. |
| `gemini-3-flash-preview` | 1M | yes | 100-150 | $0.50/$3. Sweet spot for tool loops. |
| `gemini-3.1-flash-lite-preview` | 1M | yes | 150-250 | $0.25/$1.50. Cheapest 3-series. |
| `gemini-2.5-pro` | 2M | yes | 50-90 | $1.25/$10 (≤200K), $2.50/$15 above. |
| `gemini-2.5-flash` | 1M | yes | 100-200 | $0.30/$2.50. |
| `gemini-2.5-flash-lite` | 1M | yes | 200-300 | $0.10/$0.40. Cheapest. |

- Tier: free-with-paid-tier.
- Free-tier limits (May 2026, AI Studio key):
  - `gemini-2.5-pro`: 5 RPM, 100 RPD, 250K TPM
  - `gemini-2.5-flash`: 10 RPM, 250 RPD, 250K TPM
  - `gemini-2.5-flash-lite`: 15 RPM, 1,000 RPD, 250K TPM
  - `gemini-3-flash-preview`, `3.1-flash-lite-preview`: similar but more restrictive.
- Signup: browser auth (Google account). No card. Vertex AI requires GCP project + billing.
- Tool-call quirks: similar to OpenAI but subtly different schemas — LiteLLM normalises. **Free-tier requests train future models unless you opt out**; paid-tier does not. Some preview models silently fall back if quota exceeded.
- Endpoint: `https://generativelanguage.googleapis.com/v1beta`. OpenAI-compat at `/v1beta/openai/`. Native SDK richer (`google_search`, `code_execution`). **Bypass LiteLLM** for native. LiteLLM: `gemini/<model>`.
- **Recommendation: must-have.** Best paid-tier-but-free-to-start. 1M context lets ImageJAI dump full image metadata + macro history.

Sources: https://ai.google.dev/pricing · https://ai.google.dev/gemini-api/docs/rate-limits

---

## Groq

Open-weight models on LPU silicon — output 4-10× faster than GPU providers. Curated catalogue: only LPU-stable models go to production; others stay in "preview" with stricter limits.

| Model | Params | Context | Tools | tok/s | Notes |
|-------|--------|---------|-------|------:|-------|
| `llama-3.1-8b-instant` | 8B | 128K | yes | 840 | Cheapest. |
| `llama-3.3-70b-versatile` | 70B | 128K | yes | 394 | Strong all-rounder. $0.59/$0.79. |
| `openai/gpt-oss-20b` | 20B | 128K | yes | 1,000 | Fastest. $0.075/$0.30. |
| `openai/gpt-oss-120b` | 120B | 128K | yes | 500 | Best capability/speed. $0.15/$0.60. |
| `qwen3-32b` (preview) | 32B | 128K | yes | 662 | Multi-step tools. $0.29/$0.59. |
| `meta-llama/llama-4-scout-17b-16e-instruct` | 17B MoE | 10M | yes | 594 | Massive context, vision. $0.11/$0.34. |
| Compound / Compound Mini | aggregate | 128K | yes (built-in web+code) | varies | Groq's native agent. |

- Tier: free-with-paid-tier.
- Free-tier limits (May 2026):
  - `llama-3.1-8b-instant`: 30 RPM, 14,400 RPD, 6K TPM, 500K TPD
  - `llama-3.3-70b-versatile`: 30 RPM, 1,000 RPD, 12K TPM, 100K TPD
  - `openai/gpt-oss-120b`: 30 RPM, 1,000 RPD, 8K TPM, 200K TPD
- Signup: browser auth (Google/GitHub) → API key. No card.
- Tool-call quirks: small (8B) Llama occasionally malformed JSON under load. GPT-OSS variants most reliable.
- Endpoint: `https://api.groq.com/openai/v1`. OpenAI-compat drop-in. LiteLLM: `groq/<model>`.
- **Recommendation: must-have.** Speed transforms interactive tool-loop UX (5 min → 30 s for a 30-call session). Pair with free tier as the "unauthenticated fast path".

Sources: https://groq.com/pricing/ · https://console.groq.com/docs/rate-limits · https://console.groq.com/docs/models

---

## Cerebras

Wafer-scale CS-3 chips — highest reported tok/s anywhere (3,000 for GPT-OSS-120B). Smaller catalogue than Groq. Tool calling per-model.

| Model | Params | Context | Tools | tok/s | Notes |
|-------|--------|---------|-------|------:|-------|
| `llama3.1-8b` | 8B | 128K | yes | 2,200 | Cheapest. |
| `llama-3.3-70b` | 70B | 128K | yes | ~600 | $0.60/MTok in (deprecation scheduled Feb 2026 — verify). |
| `qwen-3-32b` | 32B | 128K | yes | ~1,000 | |
| `gpt-oss-120b` | 120B | 128K | yes | 3,000 | Fastest large open model anywhere. |
| `glm-4.7` | ~100B | 128K | yes | 1,000 | Cerebras: "10× faster than Sonnet 4.5". |
| `llama-4-scout` | 17B MoE | 10M | yes | 2,000+ | Vision, huge context. |

- Tier: free-with-paid-tier. 1M tokens/day across all models, 30 RPM. Pricing $0.60-$3.90/MTok; Llama 3.1 405B is $6/$12.
- Signup: browser auth. No card for free; $10 deposit unlocks Developer plan (10× rate).
- Tool-call quirks: tool support recent, less battle-tested than Groq. Smaller Llamas may need retries on multi-tool prompts.
- Endpoint: `https://api.cerebras.ai/v1`. OpenAI-compat. LiteLLM: `cerebras/<model>`.
- **Recommendation: must-have for speed.** 1M-tokens/day free is big for biologists running batches. Pair with Groq as redundant fast paths.

Sources: https://www.cerebras.ai/pricing · https://inference-docs.cerebras.ai/

---

## OpenRouter

Passthrough router — 300+ models from 60+ underlying providers behind one OpenAI-compatible API. No markup; revenue from a small fee on credit purchases. Useful as a single-key fallback for any model not natively integrated.

| Pattern | Notes |
|---------|-------|
| Hundreds of routed models | Pricing matches each underlying provider exactly. |
| `:free` suffix variants | A handful (e.g. `meta-llama/llama-3.1-8b-instruct:free`, `deepseek/deepseek-r1:free`) free with strict caps. |
| Auto-routing | Picks cheapest/fastest for a model. |

- Tier: free-with-paid-tier (free-path = free-rate-limited routed models).
- Free-tier limits: <$10 credits → ~50 RPD on free models; ≥$10 → 1,000 RPD on free + all paid routes.
- Signup: browser auth. No card for free models.
- Tool-call quirks: depends on underlying model. OpenRouter normalises but each model retains quirks (DeepSeek occasionally returns tool calls in `content`; Gemma sometimes ignores `tool_choice`).
- Endpoint: `https://openrouter.ai/api/v1`. OpenAI-compat. LiteLLM: `openrouter/<model>` (redundant — point ImageJAI direct).
- **Recommendation: highly valuable as escape hatch.** Pricing transparent.

Sources: https://openrouter.ai/docs/quickstart · https://openrouter.ai/docs/faq · https://openrouter.ai/models

---

## GitHub Models

GitHub/MS prototyping playground exposing a curated set (OpenAI, Meta, MS, Mistral, Cohere, DeepSeek, xAI) behind Azure AI Inference, free for any GitHub user. **Not for production** — daily caps tight, no paid path on this surface (production = move to Azure AI Foundry, separate account).

| Model (representative) | Context | Tools | Notes |
|------------------------|---------|-------|-------|
| `openai/gpt-5` | 200K | yes | Free with cap. |
| `openai/gpt-4o` / `gpt-4o-mini` | 128K | yes | |
| `openai/o3` / `o4-mini` | 200K | yes | Stricter (1-2 RPM). |
| `meta/llama-4-scout-17b-16e-instruct` | 10M | yes | |
| `microsoft/phi-4` | 16K | yes | |
| `mistral-ai/mistral-large-2411` | 128K | yes | |
| `deepseek/deepseek-v3-0324` | 128K | yes | |
| `xai/grok-3` | 1M | yes | Stricter (~15 RPD). |

- Tier: free-forever-rate-limited (no paid here).
- Free-tier limits (May 2026):
  - Low-tier: 15 RPM, 150 RPD, 8K input/4K output per request
  - High-tier (GPT-5, Claude): 10 RPM, 50 RPD
  - DeepSeek-R1, Grok: 1-2 RPM, 8-15 RPD
- Signup: any GitHub user with `models:read` PAT. No card.
- Tool-call quirks: Azure AI Inference normalises across all underlying models. **4K output cap** is the main practical issue — complex macro generation can hit it.
- Endpoint: `https://models.github.ai/inference` (legacy: `https://models.inference.ai.azure.com`). LiteLLM: `github/<publisher>/<model>`.
- **Recommendation: include as "free taste of paid models".** 50 RPD cap means not a daily driver.

Sources: https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models · https://github.com/marketplace/models

---

## Mistral La Plateforme

Mistral's API — EU-hosted, GDPR-friendly. Strong open-weight line (Mistral Large 3, Medium 3.5, Small 4, Ministral) plus specialists (Codestral, Devstral 2, Pixtral). Free "Experimentation" tier — numbers unpublished.

| Model | Context | Tools | tok/s | Notes |
|-------|---------|-------|------:|-------|
| `mistral-large-3` | 256K | yes | 50-90 | $0.50/$1.50. Frontier-tier. |
| `mistral-medium-3.5` | 128K | yes | 80-130 | Strong agentic. |
| `mistral-medium-3` | 128K | yes | 80-130 | $0.40/$2.00. |
| `mistral-small-4` | 128K | yes | 100-160 | Best small open-weight. |
| `ministral-3-14b/8b/3b` | 128K | yes | 100-200 | Edge-deployable. |
| `codestral-latest` | 256K | yes (limited) | 100-150 | Code-focused; FIM. |
| `devstral-2` | 128K | yes | 80-120 | Open-weight code agent. |
| `magistral-medium-1.2` | 128K | yes | 60-100 | Reasoning. |
| `pixtral-large` | 128K | yes (vision) | 50-90 | |

- Tier: free-with-paid-tier. "Experimentation" rate-limited (~1 req/sec, ~500K tokens/min historically); no published exact RPM/RPD. `devstral-small` permanently free for individual use.
- Signup: browser auth + phone verification for free; card for paid.
- Tool-call quirks: native works on Large/Medium; Small/Ministral less reliable on multi-call. OpenAI-compat format.
- Endpoint: `https://api.mistral.ai/v1`. OpenAI-compat. LiteLLM: `mistral/<model>`.
- **Recommendation: include.** EU hosting + open-weights for users with EU data-residency concerns.

Sources: https://mistral.ai/pricing · https://docs.mistral.ai/getting-started/models/models_overview/

---

## Together AI

Serverless host for open-weights — DeepSeek, GLM, Qwen, Llama. Transparent per-token pricing. Signup credits, then pay-as-you-go.

| Model | Context | Tools | tok/s | Notes |
|-------|---------|-------|------:|-------|
| `meta-llama/Llama-3.3-70B-Instruct-Turbo` | 128K | yes | 100-200 | $0.88. |
| `Qwen/Qwen2.5-7B-Instruct-Turbo` | 128K | yes | 200+ | $0.30. |
| `Qwen/Qwen3.6-Plus` | 256K | yes | 80-150 | $0.50/$3. |
| `deepseek-ai/DeepSeek-R1-0528` | 128K | yes | 30-60 | $3/$7. Reasoning. |
| `deepseek-ai/DeepSeek-V4-Pro` | 1M | yes | 50-90 | $2.10/$4.40. |
| `zai-org/GLM-5.1` | 200K | yes | 80-150 | $1.40/$4.40. |
| `meta-llama/Llama-4-Scout-17B-16E-Instruct` | 10M | yes | 200+ | Vision. Long context. |

- Tier: free-credits-then-paid (~$1 signup credit).
- Free-tier limits: ~6K RPM, generous TPM during signup; pay-as-you-go after with standard fair-use.
- Signup: browser auth. Card to continue past credit.
- Tool-call quirks: pass-through with OpenAI-format normalisation. Reliability follows underlying model.
- Endpoint: `https://api.together.xyz/v1`. OpenAI-compat. LiteLLM: `together_ai/<model>`.
- **Recommendation: include.** Often cheapest host for niche open models not on Groq/Cerebras.

Sources: https://www.together.ai/pricing · https://docs.together.ai/

---

## Hugging Face Inference Providers

Unified gateway in front of 17 partners (Cerebras, Groq, Together, SambaNova, Fireworks, Replicate, Hyperbolic, Novita, Nscale, OVHcloud, etc.). One token, OpenAI-compat endpoint, automatic failover. No markup. Tiny monthly free credits ($0.10 free / $2 PRO).

| Routing | Notes |
|---------|-------|
| `<org>/<model>` | Picks fastest available. |
| `:fastest` / `:cheapest` | Explicit routing. |
| `:<provider>` | Force underlying provider. |

Tool-calling-capable routes (representative): `openai/gpt-oss-120b` (Cerebras, Groq, Together, Fireworks); `meta-llama/Llama-3.3-70B-Instruct` (Cerebras, Groq, Together, SambaNova); `deepseek-ai/DeepSeek-V3-0324` and `R1` (Together, Fireworks, SambaNova); `Qwen/Qwen3-Coder-480B` (Together, Fireworks, Hyperbolic); `zai-org/GLM-4.5` (Together, Fireworks).

- Tier: free-with-paid-tier (Free $0.10/mo, PRO $9/mo for $2 credits).
- Signup: browser auth. No card for included credits.
- Tool-call quirks: HF normalises to OpenAI format; underlying quirks bleed through. **Auto-routing can switch providers between calls** in a session — minor consistency risk; mitigate by pinning a provider.
- Endpoint: `https://router.huggingface.co/v1`. OpenAI-compat. LiteLLM: `huggingface/<model>`.
- **Recommendation: include alongside OpenRouter** as redundancy. PRO $2/mo ≈ a Claude Haiku 4.5 light-use day.

Sources: https://huggingface.co/docs/inference-providers/index · https://huggingface.co/docs/inference-providers/pricing

---

## DeepSeek

Open-weight V4/R1. Direct API is cheapest first-party access; same models also via Together, Fireworks, SambaNova, OpenRouter. Off-peak (UTC 16:30-00:30) discounts of 50-75% historically.

| Model | Context | Tools | tok/s | Notes |
|-------|---------|-------|------:|-------|
| `deepseek-v4-flash` | 1M | yes | 50-90 | $0.14/$0.28 (cache miss). |
| `deepseek-v4-pro` | 1M | yes | 30-60 | $0.435/$0.87 (75% discount until **2026-05-31**). |

- Tier: paid-only-pay-as-you-go.
- Free: none (signup credits historically; 2026 requires positive balance).
- Signup: card + phone verification (China-friendly methods preferred). Friction-heavy for non-Chinese users.
- Tool-call quirks: OpenAI-compat. **Model occasionally emits tool calls in `content` field as plain JSON** — most clients handle. R1's reasoning tokens are not exposed by default; very long.
- Endpoint: `https://api.deepseek.com/v1`. OpenAI-compat. LiteLLM: `deepseek/<model>`.
- **Recommendation: route via OpenRouter or HF** — same pricing, easier signup. Direct API only for users on DeepSeek's billing.

Sources: https://api-docs.deepseek.com/quick_start/pricing

---

## xAI Grok

Long-context (up to 2M on Grok 4.1 Fast). Pricing dropped 40% with Grok 4.3.

| Model | Context | Tools | tok/s | Notes |
|-------|---------|-------|------:|-------|
| `grok-4.3` | 1M | yes | 60-100 | $1.25/$2.50. Current flagship. |
| `grok-4` | 256K | yes | 50-80 | $3/$15. Older. |
| `grok-4.1-fast` | 2M | yes | 100-150 | $0.20/$0.50. Cheap + fast. |
| `grok-4.20` | 2M | yes | 60-100 | $2/$6. |
| `grok-3-mini` | 128K | yes | 100-150 | Older. |

- Tier: free-credits-then-paid ($5-25 signup credit).
- Signup: card for production rate. Phone verification.
- Tool-call quirks: OpenAI-compat, generally reliable. Long-context quality degrades past ~500K.
- Endpoint: `https://api.x.ai/v1`. OpenAI-compat. LiteLLM: `xai/<model>`.
- **Recommendation: include for completeness.** Grok 4.1 Fast at $0.20/$0.50 competes with Gemini 2.5 Flash-Lite if user already has xAI account.

Sources: https://docs.x.ai/developers/models · https://x.ai/api

---

## Perplexity

Search-augmented LLMs — every response grounded in live web results with citations. **Wrong shape for ImageJAI's tool loop** (controlling Fiji, not searching the web).

| Model | Context | Tools | Notes |
|-------|---------|-------|-------|
| `sonar` | 128K | partial | $1/$1 + $5-12 per 1K reqs. Search baked-in. |
| `sonar-pro` | 200K | yes | $3/$15 + $6-14 per 1K reqs. |
| `sonar-reasoning-pro` | 128K | yes | $2/$8 + $6-14 per 1K reqs. |

- Tier: paid-only-pay-as-you-go.
- Signup: card + verification.
- Tool-call quirks: tool-calling supported but always tied to web search by default — fights ImageJAI's pattern.
- Endpoint: `https://api.perplexity.ai/chat/completions`. OpenAI-compat. LiteLLM: `perplexity/<model>`.
- **Recommendation: skip for primary use.** Could be a sub-tool ("look up which threshold method this paper used") but not the agent's main brain.

Sources: https://docs.perplexity.ai/guides/pricing · https://docs.perplexity.ai/guides/getting-started

---

## Master comparison table

Tier legend: **FFRL** free-forever-rate-limited · **FWPT** free-with-paid-tier · **FCTP** free-credits-then-paid · **POPAYG** paid-only-pay-as-you-go · **POSUB** paid-only-subscription

Stars: ★★★★★ must-have · ★★★★ strong include · ★★★ include · ★★ optional · ★ skip

| # | Provider | Tier | Free-tier headline | ★ | Justification |
|---|----------|------|--------------------|:-:|---------------|
| 1 | Ollama (local) | FFRL | Hardware-bound only | ★★★★★ | Zero spend, zero signup, zero telemetry. |
| 2 | Ollama Cloud | FWPT | "Light usage" + 1 concurrent | ★★★★★ | Frontier-scale open behind browser auth — current default. |
| 3 | OpenAI | POPAYG | None for new accounts | ★★★ | Best tool reliability, no free path. Power users. |
| 4 | Anthropic | POPAYG | Tiny opaque test credit | ★★★★★ | Strongest agent + tools for long sessions. |
| 5 | Google Gemini | FWPT | 250-1,000 RPD on Flash, no card | ★★★★★ | Only frontier-class free tier sans card. 1M ctx. |
| 6 | Groq | FWPT | 30 RPM / 1-14K RPD, no card | ★★★★★ | 4-10× speed makes interactive loops feel instant. |
| 7 | Cerebras | FWPT | 1M tokens/day, no card | ★★★★ | Highest tok/s. Smaller catalogue than Groq. |
| 8 | OpenRouter | FWPT | 50-1,000 RPD on `:free` | ★★★★★ | Any-model escape hatch. Same prices as direct. |
| 9 | GitHub Models | FFRL | 50-1,000 RPD across all | ★★★★ | Free taste of GPT-5/Claude/Llama-4. |
| 10 | Mistral La Plateforme | FWPT | Rate-limited Experimentation + free Devstral Small | ★★★ | EU-hosted. Less compelling than alternatives. |
| 11 | Together AI | FCTP | ~$1 signup credit | ★★★ | Cheapest host for some niche open models. |
| 12 | HF Inference Providers | FWPT | $0.10/mo free, $2/mo PRO | ★★★ | Useful alongside OpenRouter for redundancy. |
| 13 | DeepSeek | POPAYG | None | ★★ | Same models cheaper via OpenRouter; signup friction-heavy. |
| 14 | xAI Grok | FCTP | Promotional credits | ★★ | Grok 4.1 Fast competes; Gemini Flash-Lite covers same niche. |
| 15 | Perplexity | POPAYG | None | ★ | Wrong shape for Fiji control. |

## Key takeaways for the next stages

1. **Five must-haves** for the dropdown's default surface: Ollama (local), Ollama Cloud, Google Gemini, Groq, Cerebras. All five usable with browser auth and no card. Anthropic and OpenRouter are tier-2 must-haves once a user provides a key.
2. **Bypass-LiteLLM list** is short and predictable: Anthropic (tool-call fidelity, prompt caching, 1M context, native tokens) and Google Gemini (server-side `code_execution`, `google_search`, vision specifics). All others route through LiteLLM Proxy without losing capability.
3. **The "free path that actually works" stack** for a no-card biologist: Ollama Cloud + Gemini + Groq + Cerebras + GitHub Models + OpenRouter `:free`. Five distinct browser-auth provider accounts giving frontier-class capability at zero cost. The demo story.
4. **Pricing volatility** is real. Anthropic Opus pricing dropped 3× when 4.5 launched; Grok cut 40% on 4.3; DeepSeek's discount window expires 2026-05-31. The curation layer (`models.yaml` from stage 2) needs a "last-verified" timestamp; stage 6 (tier-safety) should treat all paid prices as advisory, not absolute.
5. **Tool-call reliability scales roughly with model size and provider maturity:** OpenAI ≈ Anthropic > Google ≈ Cerebras (GPT-OSS-120B) ≈ Groq (GPT-OSS-120B) > Mistral Large ≈ DeepSeek V4-Pro > Llama 70B > anything ≤7B. ImageJAI's tool loop should warn when a user picks a small model — stage 5 (UI) work.
