# Tier Safety and Cost Clarity Rules

**Status:** Live document · **Date:** May 2026
**Depends on:** [`01`](01_provider_survey.md) · **Audience:** Stage 5 (UI), Stage 7 (synthesis).

## Premise

Paid providers cannot auto-charge a card unless the user has provisioned credit, set up a subscription, or added a card on file. There is no "implicit" billing risk — if no card, the API request fails with 401/402 and ImageJAI surfaces the error. So tier safety is **about clarity, not blocking**:

- (a) Users must not *accidentally* trigger a charge — first paid call in a session is preceded by a one-time confirmation.
- (b) Users see what each model will cost or rate-limit them at, **before** clicking.
- (c) Useful warnings appear the first time a paid model is picked in a session.
- (d) Auto-discovered uncurated models default to "unknown — confirm before running."

ImageJAI's agent loop is long (30+ tool calls per analysis). Cost surprises compound. The UI must front-load that information, then get out of the way.

## Sections

1. [Tier badge system](#1-tier-badge-system) · 2. [Hover-detail card](#2-hover-detail-card) · 3. [First-use-of-paid-model dialog](#3-first-use-of-paid-model-dialog) · 4. [Configuration-status](#4-configuration-status-handling) · 5. [Auto-discovered uncurated models](#5-auto-discovered-uncurated-models) · 6. [Budget ceiling](#6-budget-ceiling-optional-feature) (v4) · 7. [Tier-change notifications](#7-tier-change-notifications) · 8. [Internationalisation and currency](#8-internationalisation-and-currency)

---

## 1. Tier badge system

Every model row in the cascading dropdown carries one badge in a fixed leading column. Badges are **never combined** — each model maps to exactly one. Five badges map cleanly onto the five tier classifications in `01` (FFRL, FWPT, FCTP, POPAYG, POSUB), plus one for uncurated.

### 1.1 Badge definitions

| Badge | Name | Shape (no-emoji fallback) | Maps from `01` tier |
|-------|------|------|---------------------|
| 🟢 | **Free** | `[F]` | FFRL with no caps that bite ImageJAI |
| 🟡 | **Free with limits** | `[L]` | FFRL/FWPT where free RPM/RPD/TPM is low enough to bite a 30-call session |
| 🔵 | **Pay-as-you-go** | `[$]` | POPAYG, FCTP, or paid path of any FWPT |
| 🟣 | **Subscription** | `[S]` | POSUB |
| ⚪ | **Uncurated** | `[?]` | Discovered live but not in `models.yaml` |

The split between 🟢 and 🟡 is the only judgement call. Mechanical rule:

> A free model is 🟢 if a 30-call ImageJAI session at 4 KB average per request can complete inside the free tier *with quota left for a second session the same day*. Otherwise 🟡.

Worked examples (numbers from `01 §Groq`, `§Gemini`, etc.):

- Groq `llama-3.1-8b-instant`: 30 RPM, 14,400 RPD, 6,000 TPM → 🟢.
- Groq `llama-3.3-70b-versatile`: 30 RPM, **1,000 RPD**, 12K TPM → 🟡 (1K RPD is a busy-day cap).
- Gemini `gemini-2.5-pro` free: **5 RPM, 100 RPD** → 🟡 (a single retry storm kills the day).
- Gemini `gemini-2.5-flash-lite`: 15 RPM, 1,000 RPD → 🟢.
- GitHub Models high-tier (GPT-5, Claude on free): **50 RPD** → 🟡 always (cap is 1.5 sessions/day).
- GitHub Models DeepSeek-R1 / Grok: 1-2 RPM, 8-15 RPD → 🟡 with extra friction (warrant "tight free quota" warning even though no money).
- Local Ollama: no quotas → always 🟢.
- Ollama Cloud `:cloud` on free: "light usage", no published numbers → 🟡 (opaque caps are functionally limits).
- Anthropic, OpenAI, DeepSeek API, xAI: 🔵.
- Hypothetical Claude Pro chat product (if added): 🟣.

### 1.2 Tooltip text (lay language)

```
🟢 Free  — "Free to use. No card needed, no usage caps that matter for normal sessions."
🟡 Free with limits  — "Free, but rate-limited. You may hit a per-minute or per-day cap on long sessions — keep an eye on the status bar."
🔵 Pay-as-you-go  — "You pay per use. Charges only happen if you've already added credit or a card to this provider — see hover for current rates."
🟣 Subscription  — "Requires an active monthly subscription with this provider. Without it the request will be refused — no surprise charges."
⚪ Uncurated  — "Auto-detected from the provider — pricing and reliability not yet verified. Check the provider's pricing page directly before running a long session."
```

### 1.3 Mapping rule (curator-facing)

The unified `agent/providers/models.yaml` (`02 §1`) carries `tier:` per entry. Allowed values match `02`'s schema exactly:

```yaml
tier: free                    # → 🟢
tier: free-with-limits        # → 🟡
tier: paid                    # → 🔵
tier: requires-subscription   # → 🟣
# entry without curated metadata (curated: false) → ⚪ at runtime
```

Provider-level defaults (so common cases don't need per-model annotation) live in the same yaml:

```yaml
# in providers section of models.yaml
ollama:           { default_tier: free }
ollama-cloud:     { default_tier: free-with-limits }
gemini:           { default_tier: free-with-limits }   # paid models override
groq:             { default_tier: free-with-limits }
cerebras:         { default_tier: free-with-limits }
github-models:    { default_tier: free-with-limits }
openrouter:       { default_tier: free-with-limits }   # `:free` model variants
anthropic:        { default_tier: paid }
openai:           { default_tier: paid }
deepseek:         { default_tier: paid }
mistral:          { default_tier: paid }               # devstral-small overrides to free
together:         { default_tier: paid }
huggingface:      { default_tier: paid }
xai:              { default_tier: paid }
perplexity:       { default_tier: paid }
```

Per-model overrides only when a model's tier diverges from the provider default — e.g. Mistral's `devstral-small` is permanently free, so it sets `tier: free` overriding `mistral`'s `paid` default; Anthropic's `claude-…-pro-route` (if added) sets `tier: requires-subscription`.

---

## 2. Hover-detail card

When a user hovers a model row for ≥400 ms, a card appears anchored to the right edge of the dropdown. Read-only — no buttons, no toggles. Clicking the row dismisses; sibling row swaps; cursor off the dropdown closes.

### 2.1 Information hierarchy

Top to bottom, fixed order so users learn the layout:

1. **Display name** + **provider**.
2. **Tier badge + one-line tier summary** (the cost/limit info that decides whether to click).
3. **Capability strip** — three pills: tool reliability, vision, context length.
4. **Pricing or limits** — concrete numbers, never qualitative when concrete is available.
5. **Model id** — small and last, for users debugging via `models.yaml`.

Width fixed at 440 px; never scrollable — if content can't fit, the curator's description is too long.

### 2.2 ASCII layouts (canonical)

#### 🟢 Free model — local Ollama

```
┌────────────────────────────────────────────────────────────────┐
│  Llama 3.2 3B           ·  Ollama (local)                      │
│  🟢 Free  ·  Runs on your computer, no quota.                  │
│                                                                │
│  Tools: highly reliable │ Vision: no │ Context: 128K tokens    │
│                                                                │
│  No usage caps — speed depends on your GPU/CPU.                │
│  Disk: ~2.0 GB to download.                                    │
│                                                                │
│  id: ollama/llama3.2:3b                                        │
└────────────────────────────────────────────────────────────────┘
```

#### 🟡 Free with limits — Groq Llama 70B

```
┌────────────────────────────────────────────────────────────────┐
│  Llama 3.3 70B Versatile  ·  Groq                              │
│  🟡 Free with limits  ·  No card needed.                       │
│                                                                │
│  Tools: highly reliable │ Vision: no │ Context: 128K tokens    │
│                                                                │
│  Free tier: 30 requests/minute · 1,000 requests/day            │
│             12K tokens/minute · 100K tokens/day                │
│  Paid tier: $0.59 / 1M input · $0.79 / 1M output               │
│                                                                │
│  id: groq/llama-3.3-70b-versatile                              │
└────────────────────────────────────────────────────────────────┘
```

#### 🔵 Pay-as-you-go — Claude Sonnet 4.6

```
┌────────────────────────────────────────────────────────────────┐
│  Claude Sonnet 4.6      ·  Anthropic                           │
│  🔵 Pay-as-you-go  ·  Charges only with credit on file.        │
│                                                                │
│  Tools: highly reliable │ Vision: yes │ Context: 1M tokens     │
│                                                                │
│  Pricing: $3 / 1M input · $15 / 1M output                      │
│  Prompt caching: 5-min cache, 0.1× cost on repeat reads        │
│  Typical ImageJAI session: ~$0.05 – $0.40 depending on length  │
│                                                                │
│  id: anthropic/claude-sonnet-4-6                               │
└────────────────────────────────────────────────────────────────┘
```

#### 🟣 Subscription — placeholder shape (no current model uses this badge)

```
┌────────────────────────────────────────────────────────────────┐
│  Claude (Pro chat route) ·  Anthropic                          │
│  🟣 Subscription  ·  Requires Claude Pro ($20/month).          │
│                                                                │
│  Tools: highly reliable │ Vision: yes │ Context: 200K tokens   │
│                                                                │
│  Active Pro subscription required. Without it, requests will   │
│  fail — no charges. Sign in: claude.ai/account                 │
│                                                                │
│  id: anthropic-pro/claude-…                                    │
└────────────────────────────────────────────────────────────────┘
```

#### ⚪ Uncurated — auto-discovered

```
┌────────────────────────────────────────────────────────────────┐
│  llama-3.2-90b-text-preview   ·  Groq                          │
│  ⚪ Uncurated  ·  Auto-detected from provider.                 │
│                                                                │
│  Tools: unknown │ Vision: unknown │ Context: unknown           │
│                                                                │
│  Pricing not yet verified. Check Groq's pricing page directly  │
│  before running a long session: groq.com/pricing               │
│                                                                │
│  id: groq/llama-3.2-90b-text-preview                           │
└────────────────────────────────────────────────────────────────┘
```

### 2.3 Field rules

**Tier summary line** (under the badge):

| Badge | Line |
|-------|------|
| 🟢 | "Runs on your computer, no quota." (local) / "No card needed, generous quota." (hosted) |
| 🟡 | "No card needed." (limits below; no need to repeat) |
| 🔵 | "Charges only with credit on file." |
| 🟣 | "Requires {subscription name} ({price}/month)." |
| ⚪ | "Auto-detected from provider." |

**Tool reliability pill** (UI strings — map from yaml `tool_call_reliability` field):

- `high` → "highly reliable" — OpenAI, Anthropic, Gemini 2.5+, Cerebras GPT-OSS-120B, Groq GPT-OSS-120B.
- `medium` → "good but occasional retries" — Llama 70B, Mistral Large/Medium, GLM-4.7, DeepSeek V4-Pro.
- `low` → "inconsistent — small or unproven" — anything ≤ 7B; preview-grade tool support; uncurated routes.
- ⚪ uncurated: always "unknown".

**Vision pill.** `yes`/`no`/`unknown`. From `vision_capable` flag or, for uncurated, regex inferred from `model_id`: `(vision|vl|gemini-[2-9]|gpt-4o|gpt-5|claude-(opus|sonnet|haiku)-[4-9]|gemma3|gemma4|llama-4-scout|pixtral)`.

**Context pill.** Always shown. Read straight from `models.yaml` (`128K`, `1M`, `2M`). For ⚪: `unknown`.

**Pricing block (🔵 only).** Always show input/output as `$X / 1M input · $Y / 1M output`. Optional second line for prompt caching (Anthropic, Gemini). Optional third line for "Typical ImageJAI session: $X – $Y" — see §6 for derivation; if curator didn't supply, omitted, never made up.

**Free-tier limits block (🟡 only).** Two lines max — RPM/RPD on line one, TPM/TPD on line two. Single-type-only providers omit line two. For "opaque" tiers (Ollama Cloud, Mistral Experimentation, OpenAI promotional): one line `Free tier: light usage, no published numbers — see provider site.`

**Model id (last line).** `provider/model_id` form, monospace, dim grey.

### 2.4 Card width and accessibility

- 440 px fixed.
- Pills use both colour (badge tier) and shape/text — never colour alone.
- `aria-label`: e.g. `"Claude Sonnet 4.6, pay-as-you-go, 3 dollars per million input tokens, 15 dollars per million output tokens, 1 million token context, vision capable, tool calls highly reliable"`. Screen-reader users get the headline before any visual hover triggers.

---

## 3. First-use-of-paid-model dialog

### 3.1 When does it fire?

All four must be true:

1. The user clicked the play button to start an agent session.
2. The model's badge is 🔵 or 🟣.
3. The provider is configured (otherwise §4 takes priority).
4. The provider is not in the user's "don't ask again" set (§3.4).

It does **not** fire on hover, dropdown selection, or save — only on actual launch click. Avoids nagging users exploring the dropdown.

Fires **once per (provider × ImageJAI process)**. Picking `claude-haiku-4-5` then `claude-sonnet-4-6` in the same session triggers no second dialog. Switching from Anthropic to OpenAI does fire again — billing is per-provider.

🟡 free-with-limits do **not** trigger this dialog — they get a separate, lighter status-bar warning on first use of the day (§3.6).

### 3.2 Dialog text — 🔵 pay-as-you-go

Provider name and pricing come from `models.yaml`; everything else is a static template.

```
┌───────────────────────────────────────────────────────────────┐
│  Heads up: this model charges per use                  [×]    │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  Claude Sonnet 4.6 (Anthropic) is a pay-as-you-go model.      │
│                                                               │
│  Pricing                                                      │
│    $3 per 1 million input tokens                              │
│    $15 per 1 million output tokens                            │
│                                                               │
│  A typical ImageJAI session runs $0.05 – $0.40, but a long    │
│  batch with vision can go higher.                             │
│                                                               │
│  You will only be charged if you have already added credit    │
│  or a card to your Anthropic account. If not, the request     │
│  will fail and ImageJAI will tell you what to do.             │
│                                                               │
│  ┌──────────────────────────────────────────────────────┐     │
│  │ ☐  Don't ask again for Anthropic this session        │     │
│  └──────────────────────────────────────────────────────┘     │
│                                                               │
│             [ Use a free model instead ]    [ Continue ]      │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

Notes:
- "Typical session" line is curator-supplied (see §6); if not supplied, omitted, never guessed.
- "Use a free model instead" closes the dialog, re-opens the dropdown filtered to 🟢/🟡 only. Friendly off-ramp.
- "Continue" launches.
- `[×]` is equivalent to "Use a free model instead" — closing without explicit Continue cancels. Important: does **not** silently launch.

### 3.3 Dialog text — 🟣 subscription

```
┌───────────────────────────────────────────────────────────────┐
│  Heads up: this model needs a subscription             [×]    │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  Claude (Pro route) requires an active Claude Pro             │
│  subscription ($20/month, charged by Anthropic).              │
│                                                               │
│  ImageJAI does not handle the subscription — you sign up      │
│  on Anthropic's site. Without an active subscription this     │
│  request will simply fail; you will not be charged anything   │
│  through ImageJAI.                                            │
│                                                               │
│  ┌──────────────────────────────────────────────────────┐     │
│  │ ☐  Don't ask again for Anthropic Pro this session    │     │
│  └──────────────────────────────────────────────────────┘     │
│                                                               │
│             [ Use a free model instead ]    [ Continue ]      │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

### 3.4 "Don't ask again" semantics

- Scope: **per-provider, per-session.** Storage in-memory only — `Set<ProviderId>` on the launcher singleton. Empty on Fiji restart.
- The checkbox label always names the provider, never just "this model". Users don't think they're whitelisting a single SKU and get surprised when a sibling model also runs without prompting.
- We deliberately do **not** persist this to disk. Within-session lowers friction; across days requires re-consent.
- Power-user lab: edit `settings.json` and set `tier_safety.confirm_paid_models = false` globally — documented but not exposed as UI toggle, deliberately.

### 3.5 What we deliberately do NOT show

- **No session cost estimator** (would be wrong; agent retries unpredictably; precision implied by a number creates false sense). The "$0.05 – $0.40 typical" is curator-supplied, advisory, bounded.
- **No live token counter.** Cost-anxiety in the corner makes users second-guess legitimate retries. Hard cap is opt-in via §6.
- **No "estimated cost of next call".** Branching factor of the agent loop makes this misleading.
- **No promotional prompts.** ImageJAI is not in affiliate-marketing.

### 3.6 Status-bar strip for 🟡 first-use-of-day

When a 🟡 is launched and it is the first time today (rolling 24 h, persisted to `~/.imagejai/state.json`), a non-modal strip appears at the bottom of the agent terminal panel:

```
🟡  Free with limits — this provider caps you at 30 req/min, 1,000/day.   [ dismiss ]
```

Auto-dismisses after 8 seconds or on first agent message — whichever first. Never blocks input.

### 3.7 Billing failure mid-session

If the agent is mid-loop and the provider returns 401/402/429-billing, the loop pauses:

```
┌───────────────────────────────────────────────────────────────┐
│  Anthropic refused the request                         [×]    │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  Anthropic returned: "credit balance too low" (HTTP 402).     │
│                                                               │
│  This usually means there is no payment method or credit on   │
│  your Anthropic account. ImageJAI did not charge you and      │
│  cannot top up your balance for you.                          │
│                                                               │
│  Next steps                                                   │
│   · Add credit at console.anthropic.com (recommended)         │
│   · Or switch to a free model (Gemini Flash, Groq, Ollama)    │
│                                                               │
│      [ Switch model ]   [ Open Anthropic console ]   [ Close ]│
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

Without this, a 402 is a confusing red traceback. The dialog converts the API error into actionable plain-English next steps.

---

## 4. Configuration-status handling

The tier badge tells the user about money. A **status icon** (separate column) tells the user whether they can run the model *right now* on this machine. Orthogonal — Anthropic Sonnet 4.6 is 🔵 paid regardless, but ✓ Ready or ⚠ Needs setup depending on credentials.

### 4.1 Three states

| Icon | State | Meaning |
|------|-------|---------|
| ✓ | **Ready** | Configured. Last successful auth check < 24 h ago, or this session has used the provider. |
| ⚠ | **Needs setup** | No credential on this machine. Clicking opens the installer panel pre-targeted. |
| ✗ | **Unavailable** | Configured but errored on the most recent `/models` discovery (DNS, 5xx, expired key). |

### 4.2 Where the icon lives

In the dropdown row, between badge and model name:

```
🟢  ✓  Llama 3.2 3B                  Ollama (local)
🟡  ✓  Llama 3.3 70B Versatile       Groq
🔵  ⚠  Claude Sonnet 4.6             Anthropic     ← needs setup
🔵  ✗  Grok 4.1 Fast                 xAI           ← errored
🔵  ✓  GPT-5 Mini                    OpenAI
```

Icon is greyscale by design — it shouldn't compete with the tier badge. Colour can come back if user testing demands.

### 4.3 What happens on click — by state

#### ✓ Ready

1. If 🔵/🟣 and provider not in "don't ask again" → §3 dialog.
2. Otherwise (or after Continue) → save selection, launch agent terminal.

#### ⚠ Needs setup — one-click feel

Three things in sequence, no extra dialogs:

1. Selection *remembered as pending* — launcher records "user wanted this model after setup."
2. Dropdown closes.
3. Installer panel opens, scrolled and pre-targeted at this provider:

```
┌──────────────────── Set up Anthropic ─────────────────────────┐
│                                                               │
│  You picked Claude Sonnet 4.6 — Anthropic needs an API key    │
│  before ImageJAI can talk to it.                              │
│                                                               │
│  1. Open console.anthropic.com  [ Open ]                      │
│  2. Create an API key (Settings → API Keys → Create Key)      │
│  3. Paste it here:  [____________________________________]    │
│                                                                │
│  ImageJAI stores keys locally in your LiteLLM config — never  │
│  uploaded.                                                    │
│                                                               │
│           [ Skip — pick a different model ]   [ Save & run ]  │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

After Save & run:
- Key written to LiteLLM proxy config.
- A test `/models` call is fired.
- On success: §3 dialog (this *is* the first paid use) → on Continue, the originally-selected model launches.
- On failure: stay in installer with inline red error ("Anthropic rejected this key — check it's complete and not revoked").
- "Skip — pick a different model": dropdown re-opens, pending cleared.

**Strictly one click** from dropdown to installer panel. Any extra confirm/are-you-sure are removed.

For browser-auth providers (Ollama Cloud, Gemini, Groq, Cerebras, GitHub, OpenRouter) the installer substitutes a `[ Sign in with browser ]` for the paste field; rest identical.

#### ✗ Unavailable

```
┌───────────────────────────────────────────────────────────────┐
│  xAI is not responding                                  [×]   │
├───────────────────────────────────────────────────────────────┤
│  Last error (2026-05-02 14:22): HTTP 503 from api.x.ai        │
│                                                               │
│  The provider may be down or your key may have been           │
│  revoked. ImageJAI will retry the next time you open this     │
│  dropdown.                                                    │
│                                                               │
│      [ Retry now ]   [ Reconfigure key ]   [ Pick another ]   │
└───────────────────────────────────────────────────────────────┘
```

- "Retry now" re-runs `/models`. On success icon flips ✓; user clicks again.
- "Reconfigure key" → installer.
- "Pick another" closes, returns to dropdown.

State ✗ persists for the lifetime of the Fiji process or until next manual retry. We deliberately do not auto-retry — provider outages are rare enough that surfacing once is correct, and quiet-retrying eats startup quota.

### 4.4 How status is determined

- On Fiji startup the LiteLLM proxy launches and a `/models` call is made to each *configured* provider. Cached for 24 h.
- For *unconfigured* providers no network call is made — marked ⚠ on "no key in config" alone.
- Status calls happen **in parallel** with **4-second per-provider timeout**. A slow provider does not block the dropdown — shows ✗ until next refresh.
- Dropdown header has `[ refresh ]` link to re-run all `/models` checks on demand.

---

## 5. Auto-discovered uncurated models

### 5.1 Definition

An *uncurated* model:
- Came back from a provider's live `/models` at startup or refresh,
- Is not listed (by `model_id`) in `models.yaml`,
- Is not on the `auto_hide` blocklist (deprecated SKUs, embeddings, image-gen, etc.).

These appear *underneath* curated models in the same provider sub-menu, in a sub-section titled `— Auto-discovered (unverified) —`.

### 5.2 Display rules

| Field | Value |
|-------|-------|
| Display name | Raw `model_id` — no prettification. |
| Tier badge | ⚪ |
| Status icon | Inherited from parent provider (✓/⚠/✗ — same as §4). |
| Hover-card | §2.2 ⚪ template. |
| Tool-call reliability | `unknown` |
| Vision | `unknown`, but inferred to `yes` if `model_id` matches `(?i)(vision\|vl\|gemini-[2-9]\|gpt-4o\|gpt-5\|claude-(opus\|sonnet\|haiku)-[4-9]\|gemma3\|gemma4\|llama-4-scout\|pixtral)` — shown as `yes (inferred from name)`. |
| Context length | `unknown` unless `/models` includes it (OpenAI does, OpenRouter does, most don't). |
| Pricing | `unknown — see provider site` with link to provider's pricing page. |

Tooltip:

> "Pricing unknown — check {provider} pricing page directly. Tool-call reliability not yet validated. The next time you launch this model, ImageJAI will warn you once before continuing."

### 5.3 First-use-of-uncurated-model dialog

On first launch of an uncurated model in a session, regardless of provider tier:

```
┌───────────────────────────────────────────────────────────────┐
│  This model has not been verified                       [×]   │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  llama-3.2-90b-text-preview was auto-detected from Groq.      │
│  ImageJAI has not verified its pricing, tool-call             │
│  reliability, or vision support.                              │
│                                                                │
│  What this means                                               │
│   · It may charge you (check Groq's pricing page below).      │
│   · It may produce malformed tool calls and break your        │
│     analysis loop. ImageJAI will retry, but you may need      │
│     to switch to a curated model.                             │
│                                                                │
│  Open Groq pricing: groq.com/pricing                  [ Open ]│
│                                                                │
│  ┌──────────────────────────────────────────────────────┐     │
│  │ ☐  Don't ask again for unverified Groq models        │     │
│  └──────────────────────────────────────────────────────┘     │
│                                                                │
│         [ Use a curated model instead ]    [ Try anyway ]     │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

- **Stacks** with the §3 paid-model dialog. If provider's default tier is paid (e.g. unknown OpenAI SKU), the user sees the paid warning *first*, then this one. We do not merge them — different risks (cost vs. reliability); conflating lets one be dismissed by reading the other.
- "Don't ask again" scope is **per-(provider × session)**, same as §3.4.

### 5.4 Promoting uncurated to curated

If a user successfully runs an uncurated model and it works well, the curator can move it to `models.yaml`. Flow in `02`. Users have a `[ Suggest curation ]` link in the hover-card for ⚪ models that opens a pre-filled GitHub issue with model id and provider — telemetry-free, opt-in, no analytics.

### 5.5 Hard-stop list

Short blocklist in `models.yaml` prevents certain auto-discovered ids from appearing:

```yaml
auto_hide_patterns:
  - "*-embed-*"      # embeddings
  - "*-tts-*"        # text-to-speech
  - "whisper-*"      # speech-to-text
  - "dall-e-*"       # image generation
  - "stable-diffusion-*"
  - "*-deprecated-*"
  - "moderation-*"
```

Lives in `models.yaml` so curators can add without code changes. Hidden models still appear in LiteLLM proxy config (advanced user could call by id from a custom recipe) — just suppressed from dropdown.

---

## 6. Budget ceiling (optional feature) — v4 (Phase H)

### 6.1 Recommendation: ship it, off by default, simple shape

Per-session cost ceiling — opt-in, plainly scoped, one number. **Lives in v4 (Phase H), not v1 — paid models excluded from `models.yaml` until v4 ships, so the ceiling has nothing to protect earlier.**

**For:** Hard backstop for users with funded balances who run unattended overnight batches. The §3 dialog protects against accidental *first* spend; a ceiling protects against runaway loops. LiteLLM proxy already exposes per-request cost in its `x-litellm-response-cost` response header (see §6.3), so token-cost accounting is free — we don't maintain a per-provider pricing table ourselves. ~50 LOC in the agent wrapper plus a settings field.

**Against:** Most ImageJAI sessions cost cents. A ceiling that fires once a year mostly sits unused. LiteLLM's cost numbers can lag when providers change pricing — a ceiling on stale prices could be 2× wrong. Mitigated by opt-in.

### 6.2 Settings location

```
─── Tier safety ─────────────────────────────────────────────────
  ☐ Pause the agent if a single session exceeds   [ $1.00 ▾ ]
       (Estimated from LiteLLM token accounting — accurate to
        within ~10% if the model's pricing in models.yaml is
        current. Free models are not counted.)

  ☑ Confirm before first use of a paid model in each session
  ☑ Warn before first use of an unverified model in each session
```

Default ceiling values: `$0.25`, `$0.50`, `$1.00`, `$2.00`, `$5.00`, `$10.00`, `Custom…`.

**Per-session.** A "session" is one play-button-launch-to-terminal-close. Matches user mental model; avoids cross-session state.

### 6.3 Enforcement

The agent wrapper (Python side, runs in the terminal) tracks running cost:

1. After each model call, read `x-litellm-response-cost` from LiteLLM's response headers. If absent, fall back to local token-count × `models.yaml` pricing.
2. Maintain `session_cost_usd` in process memory.
3. Free-tier (🟢/🟡) calls contribute `0.00` regardless of token count — ceiling is about money, not quota.
4. Before each tool-loop iteration, check `session_cost_usd >= ceiling`. If breached, stop and emit:

```
─────────────────────────────────────────────────────────────────
⏸  Budget ceiling reached: $1.04 of $1.00.

   The agent has paused. Your last action completed; nothing
   was charged that you didn't already authorise.

   Options:
     · Type /resume to continue with a higher ceiling
     · Type /switch <model> to drop to a free model
     · Close this terminal to end the session
─────────────────────────────────────────────────────────────────
```

5. `/resume` prompts for a new ceiling (default: current × 2). `session_cost` continues accumulating — ceiling is raised, not reset.
6. Fires *between* tool-loop iterations, never *during* an in-flight call. We do not abort an HTTP request mid-flight; that would still incur cost on the open call.

### 6.4 What we don't do

- **No mid-call estimation.** Cannot predict cost of in-flight call.
- **No ceiling on free models.** Free-tier rate limits are the cap; an additional cost ceiling would be misleading (zero is zero).
- **No persistent rolling-day or rolling-week budget.** Different feature; out of scope.
- **No cross-provider arithmetic problem.** Cost summed in USD across providers in one session — see §8.

### 6.5 Telemetry

None. Session cost stays in the agent process; never logged or transmitted. If the session ends without breaching, the number is discarded.

---

## 7. Tier-change notifications

### 7.1 The problem

`models.yaml` updates occasionally — pricing drops (Anthropic Opus 4.5 → 3× cheaper), discount expiries (DeepSeek's 75% off ends 2026-05-31), tier reclassifications, hard deprecations (Cerebras Llama 3.3 70B scheduled Feb 2026).

Users have *favourites* — used multiple times. When a favourite changes tier, they should know on next launch without reading release notes.

### 7.2 What counts as a "favourite"

Tracked in `~/.imagejai/state.json` after the user has launched it ≥ 3 times across at least 2 distinct days. Avoids one-off experiments and keeps the list tight.

```json
{
  "favourites": {
    "anthropic/claude-sonnet-4-6": {
      "first_used": "2026-04-12T09:14:00Z",
      "last_used": "2026-05-01T16:22:00Z",
      "use_count": 47,
      "last_seen_tier": "paid",
      "last_seen_input_price_usd_per_mtok": 3.0,
      "last_seen_output_price_usd_per_mtok": 15.0
    },
    "deepseek/deepseek-v4-pro": {
      "use_count": 8,
      "last_seen_input_price_usd_per_mtok": 0.435,
      "last_seen_output_price_usd_per_mtok": 0.87
    }
  }
}
```

When the user launches Fiji and `models.yaml` differs from `last_seen_*` for any favourite, a banner is queued.

### 7.3 What changes trigger a notification

| Change | Severity | Action |
|--------|---------|--------|
| Price *increase* ≥ 10% on input or output | High | Banner pinned until dismissed. |
| Price *decrease* ≥ 10% | Low | One-line strip in dropdown header for one launch. |
| Tier change (🟡 → 🔵 or 🟢 → 🟡 etc.) | High | Banner pinned. |
| Tier change in lighter direction (🔵 → 🟢) | Low | One-line strip. |
| Free-tier limits tightened (RPD halved or worse) | Medium | Banner, dismissable, not pinned. |
| Free-tier limits loosened | None | No notification. |
| Model deprecated (removed from `models.yaml`) | High | Banner with replacement, pinned. |
| New model added to a provider you've used | None | Discoverable in the dropdown only. We don't push novelty. |
| Discount-expiry warning (T-7 days) | Medium | Banner: "this model gets X% more expensive on YYYY-MM-DD". |

Thresholds (10%) and durations (T-7) live in `models.yaml` so the curator can tune. Defaults given.

### 7.4 Banner placement

**Two surfaces, neither modal:**

1. **Fiji startup banner** — slim strip across the top of the agent panel. One at a time; multiples stack vertically with per-banner dismiss.
2. **In-dropdown indicator** — small `(price changed)` link next to the affected model for 30 days after the change. Click opens the same banner content as a tooltip.

Startup banner is dismissed permanently per-change-per-machine — `state.json` records dismissal so the user doesn't see the same banner every Fiji launch.

### 7.5 Banner content — concrete examples

**Discount expiry (DeepSeek 2026-05-31):**

```
ℹ  DeepSeek V4-Pro: pricing change on 2026-06-01

   You've used this model 8 times. Its current 75% discount ends in
   29 days. After 2026-06-01 it will cost:

      $1.74 / 1M input  (was $0.435 — about 4× more)
      $3.48 / 1M output (was $0.87  — about 4× more)

   A typical session of yours would go from ~$0.04 to ~$0.16.

   [ See alternatives ]   [ Open DeepSeek pricing ]   [ Dismiss ]
```

"See alternatives" filters the dropdown to models in the same capability bracket (reasoning + 1M context + tool calls) sorted by current price.

**Price increase already happened:**

```
⚠  Anthropic Claude Opus 4.6: price increased

   Last time you used Opus 4.6 the price was $5/$25 per 1M tokens.
   It is now $7/$30 — about 25% more expensive.

   ImageJAI is showing the new price in the dropdown. Sessions
   will charge at the new rate.

   [ See alternatives ]   [ Open Anthropic pricing ]   [ Dismiss ]
```

**Tier reclassification (free → free-with-limits):**

```
🟡  Groq compound-mini: now rate-limited

   Groq has moved compound-mini from generous-free to limited-free.
   New cap: 100 req/day. You've used it 14 times.

   The badge in the dropdown is now 🟡.

   [ See alternatives ]   [ Dismiss ]
```

### 7.6 How the system actually knows

`models.yaml` ships with a `last_verified` ISO date per entry (already mandated by `01 §Key takeaways item 4`, schema in `02 §1`). On Fiji startup the launcher compares each favourite's `last_seen_*` against the current `models.yaml` values; differences trigger banners.

For DeepSeek-style discount expiries: the entry includes a `pricing_changes` list with future-dated changes. The launcher runs the T-7-day check against this list at startup, regardless of whether `models.yaml` updated — so a user who installs ImageJAI 25 days before the change still gets the warning at startup on day-22.

```yaml
deepseek/deepseek-v4-pro:
  display_name: "DeepSeek V4-Pro"
  tier: paid
  pricing:
    input_usd_per_mtok: 0.435
    output_usd_per_mtok: 0.87
    note: "75% promotional discount; expires 2026-05-31"
  pricing_changes:
    - effective_date: "2026-06-01"
      reason: "Promotional discount expires"
      input_usd_per_mtok: 1.74
      output_usd_per_mtok: 3.48
```

---

## 8. Internationalisation and currency

### 8.1 Recommendation: USD only, with a footnote

Show prices in USD. Add a one-line footnote in the hover-card and pricing dialogs when the user's locale is not en-US.

### 8.2 Reasoning

Three options considered:

**Option A — USD only.** Matches every provider's billing reality (every provider on the survey lists public pricing in USD; user invoice is in USD or local-currency-equivalent at provider's chosen FX rate).

**Option B — USD + local-currency conversion.** Friendly, but requires live FX feed. The conversion ImageJAI shows will *not* match what the user is actually billed — every provider does its own FX at its own rate, often with card-network markup. A "£0.20" estimate that turns into a £0.23 invoice is more confusing than useful.

**Option C — qualitative bands ("cheap"/"mid"/"premium").** Hides information some users need. A grant-funded lab budgets in dollars, not adjectives.

Option A wins. Provider invoice is source of truth.

### 8.3 The footnote

In the hover-card pricing block (🔵 only), append a third line when locale ≠ `en-US`:

```
Pricing: $3 / 1M input · $15 / 1M output
Prompt caching: 5-min cache, 0.1× cost on repeat reads
Typical ImageJAI session: $0.05 – $0.40
Charged in USD. Your bank's FX rate applies — see Anthropic billing.
```

Locale via `Locale.getDefault()`. No external services, no IP geolocation. Suppress with `tier_safety.show_currency_footnote = false`.

### 8.4 Where currency-aware text appears

- §2 hover-card pricing block — footnote as above.
- §3 first-use-of-paid-model dialog — same footnote appended.
- §6 budget ceiling — preset values stay USD. Ceiling summed in USD by the agent wrapper, breached in USD.
- §7 tier-change banners — USD throughout.

We do not add a "show me in EUR/GBP/JPY" preference — multiplies strings to translate without adding accuracy.

### 8.5 Number formatting

USD figures formatted with the user's locale's grouping (`Locale.getDefault()` → `NumberFormat.getCurrencyInstance(Locale.US)` for symbol, separator-aware string assembly):

| Locale | Renders as |
|--------|------------|
| en-US, en-GB, en-AU | `$3 / 1M input · $15 / 1M output` |
| de-DE, fr-FR | `$3 / 1 Mio. input · $15 / 1 Mio. output` |
| ja-JP | `$3 / 100万 input · $15 / 100万 output` |

Localising the "1M" abbreviation is delegated to Stage 5 (UI). Stage 6 (this doc) just establishes that the *currency* is USD, the *number* is locale-formatted.

### 8.6 Out of scope

- No live FX feed.
- No "estimated my-currency invoice" feature.
- No multi-currency settings.
