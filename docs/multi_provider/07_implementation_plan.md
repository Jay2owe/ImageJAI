# Stage 7 — Multi-Provider Implementation Plan

**Status:** Final shipping order · **Date:** 2026-05-02
**Synthesises:** `00`–`06` · **Audience:** the engineer (or agent) who ships the multi-provider backend.

Each phase below is a discrete piece of work with its own acceptance criteria and effort estimate. Phases are ordered so each commit leaves the codebase working.

---

## 1. Executive summary

Today ImageJAI's AI backend is Ollama-only. The play button at `AiRootPanel.java:257` launches one of nine CLI agents detected on `PATH`, plus the in-process Ollama loop. There is no UI surface for picking between Claude, Gemini, Groq, Cerebras, OpenRouter, GitHub Models, or Mistral. Users who want any of those install separate CLI tools.

We replace that with a cascading **provider → model** dropdown backed by:

- **LiteLLM Proxy** sidecar started on Fiji boot, routing most providers through one OpenAI-shaped local endpoint at `localhost:4000`, storing all credentials in one config.
- **Native bypass paths** for Anthropic and Gemini (richer tool-calling, prompt caching, server-side tools).
- **Python provider abstraction** (promoted from `agent/providers/_spike/`) plugging into `ollama_chat.py` via a five-method `ProviderClient` interface (`03 §3`).
- **Curated `agent/providers/models.yaml` + live `/models` discovery** (the unified yaml from `02 §1`, also consumed by `agent/contexts/loader.py` per `04 §4`).
- **Per-model-family context overlays** composing `base.md` + harness + capabilities + reliability + family.
- **Tier-safety affordances** per `06`: badges, hover card, first-use-per-paid-provider dialog, status icons that one-click into installer, opt-in budget ceiling.

```
                          ┌──────────────────────────────────────┐
                          │  AiRootPanel (Swing header)          │
                          │  [Claude Sonnet 4.6 ▾]   [▶ re-run]  │
                          └──────────────┬───────────────────────┘
                                         │ click
                                         ▼
                  ┌──────────────────────────────────┐
                  │ ModelPickerButton.JPopupMenu     │
                  │  ★ pinned · ▾ Anthropic ✓ …      │
                  │  …(15 providers, ~80 models)…    │
                  └────────────────┬─────────────────┘
                                   │ pick (provider, model_id)
                                   ▼
                  ┌──────────────────────────────────┐
                  │ ProviderTierGate                 │
                  │  paid? → FirstUseDialog (1×/sess)│
                  │  uncurated? → UncuratedDialog    │
                  └────────────────┬─────────────────┘
                                   │ Continue
                                   ▼
        ┌──────────────────────────┴──────────────────────────┐
        │ AgentLaunchOrchestrator picks transport             │
        │  cli      → AgentLauncher.launch()  (existing)      │
        │  proxy    → ProxyAgentLauncher → openai SDK →       │
        │             localhost:4000 (LiteLLM)                │
        │  native   → NativeAgentLauncher → anthropic SDK     │
        │             or google-genai SDK                     │
        └──────────────────────────────────────────────────────┘
                                   │
                  ┌────────────────┼────────────────┐
                  ▼                ▼                ▼
         LiteLLM Proxy     api.anthropic.com  generativelanguage…
        (Python sidecar)   (native bypass)    (native bypass)
                  │
        Groq · Cerebras · OpenRouter · GitHub Models · Mistral ·
        Together · HF · DeepSeek · xAI · Perplexity · OpenAI ·
        Ollama Local · Ollama Cloud
```

**Why better than today.**
1. One UI surface for fifteen providers instead of one CLI per provider.
2. Free-tier-only stack (Ollama + Gemini + Groq + Cerebras + GitHub Models + OpenRouter `:free`) is one dropdown click instead of five separate installs.
3. Cost is visible *before* the click (hover card), confirmed once per provider per session, optionally hard-capped (budget ceiling). Today: zero cost surfacing.
4. Provider catalogue stays current — `/models` polled with 24 h cache; soft-deprecation keeps user pins working.
5. Per-model context overlays — Gemma-tuned advice doesn't bleed into Claude sessions.

**What changes for an existing user.** Nothing visible by default. Plugin still boots, play button still launches Ollama-Cloud Gemma. Dropdown grows when user pastes a non-Ollama key.

**Rollout.** Feature-flag-gated incremental phases (each behind `Settings.useMultiProviderPicker = false` until ready); parallel coexistence with current launcher; **atomic switchover only after Phase D ships and the lab build has run for at least two weeks without regressions** (see `05 §10.6`).

---

## 2. Phase breakdown

Eight phases, A–H. Each leaves the codebase compiling and the existing Ollama path working.

Each phase carries: Goal · Acceptance criteria · Files touched (New / Modified / NOT touched) · Dependencies · Effort estimate · Risks.

### Phase A — LiteLLM Proxy bundling + autostart

**Goal.** Bundle `litellm[proxy]`, ship a default `litellm.config.yaml`, autostart on Fiji boot inside a Python sidecar, surface health check + cost-header capture in the existing `AgentLauncher` plumbing. After A, an OpenAI-SDK call to `localhost:4000/v1/chat/completions` works for any provider whose key is in the config — even with no UI surface.

**Acceptance.**
- `pip install -r agent/providers/requirements.txt` resolves `litellm[proxy]>=1.50` (or current latest) into the existing ImageJAI Python venv.
- `agent/providers/proxy.py` exposes `start(config_path) -> Popen`, `stop(handle)`, `wait_healthy(timeout=8.0)`.
- Default `agent/providers/litellm.config.yaml` ships with placeholders for every provider in `01 §Master comparison table` — Ollama, Ollama Cloud, OpenAI, Anthropic, Gemini, Groq, Cerebras, OpenRouter, GitHub Models, Mistral, Together, HF, DeepSeek, xAI, Perplexity — with `os.environ/<NAME>` references. Proxy starts cleanly with no keys set, returning empty `/models` only for Ollama Local.
- New Java service `LiteLlmProxyService` (under `imagejai.engine`) constructed by `IJAiPlugin` at startup, spawns the sidecar, polls `GET http://localhost:4000/health/readiness` until 200 or 8 s, logs status to ImageJ Log.
- The cost header `x-litellm-response-cost` is captured by a Python `ResponseCostMiddleware` and surfaced through a callback the future budget ceiling (Phase H) subscribes to.
- Manual: starting Fiji with no keys leaves the proxy running; `curl localhost:4000/v1/models` returns at least Ollama-local entries.
- Manual: setting `OPENAI_API_KEY` in shell env and restarting Fiji makes `gpt-5-mini` reachable through the proxy.
- Manual: stopping Fiji terminates the sidecar (verify with `tasklist`/`ps`).

**Files.** New: `agent/providers/proxy.py` · `agent/providers/litellm.config.yaml` · `agent/providers/requirements.txt` · `src/main/java/imagejai/engine/LiteLlmProxyService.java` · `CostHeaderListener.java`. Modified: `IJAiPlugin.java` (add `LiteLlmProxyService` construction + `Runtime.getRuntime().addShutdownHook`); `pom.xml` (no new Java deps; bundle `agent/providers/` as a plugin resource so the JAR carries `litellm.config.yaml`). NOT touched: existing `AgentLauncher.java`, `AiRootPanel.java`, the Ollama loop, `agent/ollama_agent/`. The spike at `agent/providers/_spike/` — Phase B promotes it; A only ships infrastructure.

**Dependencies.** None — foundation.

**Effort.** **~2 days (16 h).** Half-day Python launcher cross-OS; one day Java lifecycle + shutdown hook + resource bundling; half-day cost-header pipeline + manual-test pass.

**Risks.**
- **Proxy startup time.** "Few seconds" may exceed Fiji startup-feel target on cold launches. Mitigate: background-thread the boot, "proxy ready" banner async.
- **Python venv coupling.** Proxy must run in the same venv the agent loop uses, otherwise pip-resolves diverge. Document the venv path in `proxy.py`; warn loudly if `litellm` is not importable.
- **Port collision** on `localhost:4000`. Fallback range 4000–4010; write chosen port to a tiny `proxy.port` file the Java side reads. The file is rewritten on every start, deleted on clean shutdown; if Java finds it stale (no listener), it falls back to scanning the range itself.
- **Windows process tree** — `Popen` parents on Windows don't always receive shutdown signals; use `taskkill /T` in the JVM shutdown hook.

---

### Phase B — Python provider modules

**Goal.** Promote `agent/providers/_spike/` to `agent/providers/` as production-quality modules — real error handling, HTTP retry, type hints, real test fixtures (`respx`/`pytest-httpx` cassettes replacing the spike's mocked SDKs), and a `router.get_client(provider, model)` that the existing `agent/ollama_agent/ollama_chat.py` tool loop can call.

**Acceptance.**
- `agent/providers/__init__.py` exports `router`, `ProviderClient`, `ToolCall`, `fn_to_json_schema`, `to_openai_tool`, `to_anthropic_tool`, `to_gemini_tool`. Also exports a canonical provider-key allow-list so `router` and the Java side share one set; reject unknown providers at registry load time.
- `agent/providers/base.py` carries the five-method interface from `03 §3` plus the schema converter from `03 §4.2` with the PEP-563 fix.
- `agent/providers/litellm_proxy.py` calls `localhost:4000` via the `openai` SDK; parses tool-call arguments from JSON strings (risk #3 in `03 §8`).
- `agent/providers/anthropic_native.py` defaults `max_tokens=4096` (risk #7), pulls system messages out of the messages list, re-threads full content blocks (risk #2), uses `tool_use_id` not `tool_call_id` (risk #4).
- `agent/providers/gemini_native.py` disables automatic function calling (risk #9), translates `assistant`↔`model` roles, synthesises `f"{name}:{idx}"` tool-call ids (risk #5), drops empty `parameters` for zero-arg tools (risk #6).
- `agent/providers/router.py` exposes `get_client(provider: str, model: str) -> ProviderClient`. **Provider keys are hyphenated** (canonical: `ollama-cloud`, `github-models`, `huggingface`) — the spike's underscore set is corrected here. Mapping (`03 §7`):
  - **Native path (2):** `anthropic` → `AnthropicNativeClient`; `gemini` → `GeminiNativeClient`.
  - **Proxy path, already in spike (8):** `openai`, `groq`, `cerebras`, `openrouter`, `github-models`, `mistral`, `ollama-cloud`, `ollama` → `LiteLLMProxyClient`.
  - **Proxy path, NEW in Phase B (5):** `together`, `huggingface`, `deepseek`, `xai`, `perplexity` → `LiteLLMProxyClient`. Each must be wired into `litellm.config.yaml` (Phase A) under its LiteLLM-recognised model-prefix (`together_ai/`, `huggingface/`, `deepseek/`, `xai/`, `perplexity/`) per `01 §Master table`. `_PROXY_PROVIDERS` in `router.py` enumerates all 13 hyphenated keys.
  - **Per-provider smoke test:** for each of the 5 new keys, one `respx`-mocked test issues a chat call and asserts the request URL routes through `localhost:4000` with the correct upstream model prefix; `router.get_client("together", "mistralai/Mixtral-8x7B-Instruct-v0.1")` (and similar for the other four) must not raise. Confirms acceptance against `01`'s 15-provider promise.
- The spike's eight tests pass against the new modules (`pytest agent/providers/test_*.py`).
- Three new tests: malformed-JSON tool-call argument (risk #10), multi-tool parallel call within one turn, unknown-tool-name dispatch.
- A PEP-563 caller-module test: synthetic module with `from __future__ import annotations` defining a tool function — assert `to_openai_tool(fn).parameters` carries the right primitive types (catches the production-side flavour of risk #1).
- Manual: `examples/run_provider_demo.py` lets the developer invoke any of the three paths against a tiny multiply tool using real keys.

**Files.** New: `agent/providers/__init__.py`, `base.py` (promoted with hardening), `litellm_proxy.py`, `anthropic_native.py`, `gemini_native.py`, `router.py`, six split test files, `examples/run_provider_demo.py`. Modified: none on production side. NOT touched: `agent/providers/_spike/` — kept as reference and passing baseline; delete only after Phase D ships and the lab build is stable. `agent/ollama_agent/ollama_chat.py` — Phase B leaves untouched. The migration diff in `03 §6` is held in reserve for Phase D.

**Dependencies.** Phase A must be merged so tests can hit a running proxy. (B's unit tests mock the SDK, so don't strictly need A — but the demo script does.)

**Effort.** **~2.5 days (20 h).** Spike already proves design; B is hardening, error paths, splitting into shippable modules. Half-day tests, half `router.py` registration, one day three production clients (most work is fixture recording for `respx`), half demo + docs.

**Risks.**
- **Streaming gap.** Spike is `stream=False` (risk #10). Phase B should explicitly *not* implement streaming — defer. Document in module docstring.
- **Pinning SDK versions.** `openai`, `anthropic`, `google-genai` all release breaking changes. Pin to May 2026 versions verified in survey; CI smoke test catches regressions.
- **LiteLLM cost-header drift.** LiteLLM has changed header names between releases. If a future LiteLLM upgrade renames the header, Phase H's budget ceiling silently stops triggering. Pin LiteLLM in `requirements.txt`; add a startup self-test that fires one tiny chat and asserts the cost header is present, fails loudly if not.
- **Risk of regressing the existing Gemma loop.** Don't change `ollama_chat.py` in Phase B — keep the new modules dormant.

---

### Phase C — Native paths for Anthropic and Gemini

**Goal.** Wire the bypass-proxy path for Claude and Gemini through to live API, exposing native features the OpenAI translation loses. After C, the router can pick `provider == anthropic` for prompt caching, parallel tool calls, rich content blocks; `provider == gemini` for server-side `code_execution`/`google_search`, vision input.

**Acceptance.**
- `AnthropicNativeClient` exposes:
  - **Prompt caching:** when system message + tool definitions are re-sent within five minutes, the second call attaches `cache_control: {"type": "ephemeral"}` to those blocks. `last_seen_response.usage.cache_read_input_tokens` asserted > 0 on the second test call.
  - **Parallel tool calls in a single response:** `extract_tool_calls` returns the full list; the loop dispatches sequentially today (a parallel-execution upgrade is out of scope) but the wrapper surfaces them.
  - **Vision input:** `messages` carrying `{"type": "image", "source": {…}}` blocks pass through unmodified.
- `GeminiNativeClient` exposes:
  - **Server-side `code_execution`** via router opt-in: `router.get_client("gemini", model, server_tools=["code_execution"])`. Disabled by default (parity with OpenAI surface) — biologist who needs Python-side computation flips it per call.
  - **Server-side `google_search`** likewise opt-in.
  - **Vision input:** PNG screenshots from `python ij.py capture` round-trip cleanly.
- New `test_native_features.py`: Anthropic prompt-cache hit (using `respx` to assert `cache_control` field on the second call); Gemini `code_execution` enables when opt-in is set, off by default.
- Router signature accepts `**opts` so the loop can pass provider-specific knobs without breaking the abstraction.

**Files.** Modified: `anthropic_native.py` (caching, vision); `gemini_native.py` (server-tool opt-ins, vision); `router.py` (`**opts`); `test_*.py`. NOT touched: LiteLLM proxy path. Native features are deliberately *not* retro-fitted on the proxy path — that's the entire reason the bypass exists.

**Dependencies.** Phase B (native client classes must exist).

**Effort.** **~1.5 days (12 h).** Most work is reading SDK docs for caching/server-tools and writing fixtures. Each feature is one or two extra fields on the request body.

**Risks.**
- **Opus 4.7 tokenizer.** Anthropic's Opus 4.7 uses a new tokenizer that consumes ~35% more tokens for the same English text (`01 §Anthropic`). Cost predictions in `models.yaml` must reflect this, the cache-hit tests must use 4.7, *and* the same multiplier must apply to Phase H's typical-session estimate (`06 §2.4`/`§3.2`) and the budget-ceiling fallback (`06 §6.3`).
- **Server-tool cost surprise.** Gemini's `code_execution` runs in Google's sandbox and bills extra. The opt-in keeps it off by default; document the cost implication in the router docstring so Phase H's ceiling logic accounts for it.

---

### Phase D — Java ProviderRegistry + cascading dropdown

**Goal.** Build the Swing UI from `05`: a `ModelPickerButton` replacing the flat `JComboBox<String> agentSelector` at `AiRootPanel.java:63/209`, owning a `JPopupMenu` with cascading provider submenus, custom `ModelMenuItem` rows, hover-card singleton `JWindow`, right-click + click-to-pin affordances. All gated behind `Settings.useMultiProviderPicker = false`.

**Acceptance.**
- New package `imagejai.ui.picker`: `ModelPickerButton`, `ModelMenuItem`, `ProviderMenu`, `HoverCard`, `TierBadgeIcon`, `StatusIcon`, `PinStarIcon` (full list `05 §11.1`).
- New package `imagejai.engine.picker`: `ModelEntry`, `ProviderEntry`, `ProviderRegistry`, `ProviderRegistry.RefreshWorker`, `ProxyAgentLauncher`, `NativeAgentLauncher`.
- `ProviderRegistry` loads the unified `agent/providers/models.yaml` (hardcoded ~12 entries this phase; auto-discovery merge in Phase G); exposes `Iterable<ProviderEntry> providers()`, `ModelEntry lookup(provider, modelId)`. Both Java and Python (`agent/contexts/loader.py`) read this same file; each ignores fields it doesn't recognise.
- `ModelPickerButton` constructs the popup once at startup; rebuilds a single `ProviderMenu`'s children when `ProviderRegistry.refreshProvider` fires, not the whole popup (avoids flicker risk in `05 §3.9`).
- Click-to-pin works: `MouseEvent.consume()` on the star column suppresses launch + popup-close (`05 §3.4`); row repaints; popup stays open.
- Click-to-launch on row body fires `onLaunchRequested` → new `AgentLaunchOrchestrator.launchAsync(entry)` which picks transport: `cli` → existing `AgentLauncher.launch()`; `anthropic`/`gemini` → `NativeAgentLauncher`; all others → `ProxyAgentLauncher`.
- Hover-card `JWindow` honours 400 ms show / 120 ms grace (`05 §5.2`).
- Feature flag: `Settings.useMultiProviderPicker = false` keeps existing `JComboBox`; `true` swaps to new picker. Both code paths live until Phase D ships AND v3.x cycle closes.
- Manual: with `useMultiProviderPicker = true` and only Ollama configured, dropdown shows Ollama-local provider expanded with the user's pulled models; every other provider shows ⚠ and a "Add credentials…" leaf.
- Manual: clicking a 🟢 Ollama row launches the existing `gemma4_31b_agent` flow without any tier-safety dialog.
- Manual: clicking the (hardcoded for now) `claude-sonnet-4-6` row with no key configured opens the installer panel pre-targeted at Anthropic (delegating to Phase E).

**Files.** New: all classes in `imagejai.ui.picker.*` and `engine.picker.*`; first cut of `agent/providers/models.yaml` with a dozen curated entries (Anthropic, Gemini, Groq, Ollama, Ollama Cloud) — full curation in Phase G.

Modified `AiRootPanel.java`:
- 63: replace `JComboBox<String> agentSelector` with `ModelPickerButton modelPicker` (gated; keep both fields during transition).
- 209–212: add `modelPicker` construction alongside existing `agentSelector`. Layout picks one based on flag.
- 213–223: add `modelPicker.addSelectionListener(...)` alongside existing listener.
- 224: `leftPanel.add(modelPicker)` *or* `leftPanel.add(agentSelector)`, by flag.
- 257–265: `agentBtn` text changes when flag on ("▶ re-run last") — `05 §10.3`.
- 311–337 (`refreshAgentSelectorAsync`): kept; flag-off uses it.
- 339–371 (`refreshAgentSelector`): kept.
- 398–411 / 413–436 (launch methods): when flag on, route through `AgentLaunchOrchestrator.launchAsync(modelEntry)`.

Modified `Settings.java`: add fields from `05 §9.4` (`selectedProvider`, `selectedModelId`, `defaultProvider`, `defaultModelId`, `refreshIntervalHours`, `refreshOnStartup`, `confirmPaidModels`, `warnUncuratedModels`, `budgetCeilingEnabled`, `budgetCeilingUsd`, `showCurrencyFootnote`, `useMultiProviderPicker`). Bump `Settings.SCHEMA_VERSION` if it exists, else add `migrate()` invocation in `Settings.load()`.

Modified `pom.xml`: add `org.yaml:snakeyaml` (or `org.snakeyaml:snakeyaml-engine` per JDK 25 compat).

NOT touched: `AgentLauncher.java` (Phase D leaves CLI-spawn path verbatim — `KNOWN_AGENTS`, `detectAgents`, `launch(AgentInfo, Mode)`. Renaming `KNOWN_AGENTS` → `CLI_AGENTS` is a Phase D+ cleanup once the new code paths are stable). The existing Profiles tab in `SettingsDialog.java` — Phase E adds the new Multi-Provider tab; D doesn't touch existing tabs.

**Dependencies.** Phases A and B (B because new launchers call the Python provider modules; A because Phase D's `lookup`-and-launch path can hit the proxy).

**Effort.** **~5 days (40 h, with +25% Swing tax baked in).** Custom `paintComponent`, click-to-pin trick, hover-card lifecycle, right-click menu — fiddly-but-well-specified. Two days rendering + popup; one day registry + flag plumbing; one day click-to-pin + right-click; one day manual-test cycles + Swing-on-Windows quirks.

**Risks.**
- **Swing pitfalls.** Custom `JMenuItem`, `MouseEvent.consume()` ordering, hover-card flicker. All pre-flagged in `05 §3` — follow the spec exactly. Do not theme the popup.
- **Feature-flag drift.** Carrying both code paths means duplicate work for two releases. Mitigation: write the new path first, test it; do not refactor the old; old fields and methods stay verbatim, marked `// TODO(multiprovider-cleanup): remove after flag flip`.
- **State mismatch on flag flip.** A user who has used the old `selectedAgentName` then turns the flag on must seamlessly get the right `(provider, model)` resolution. Seeding logic in `05 §10.5` covers this; write a unit test for each legacy-name → new-key entry.
- **SnakeYAML CVE history.** Use `SafeConstructor` only — never default `Constructor`. Apply to `models.yaml` (bundled) AND `models_local.yaml` (user-editable, untrusted by definition).

---

### Phase E — Installer panel extension (per-provider setup)

**Goal.** Extend `SettingsDialog`'s Models & Agents tab (`SettingsDialog.java:166`) for per-provider credential setup for the fifteen providers in `01`. Each provider uses one of five install shapes. Add deep-link entry point `SettingsDialog.openWithProvider(parent, providerId)` that the ⚠ status icon click in Phase D calls.

**Acceptance.**
- Five `InstallerRow` subclasses:
  - **`PureApiKeyRow`** — Anthropic, OpenAI, Mistral, Together, DeepSeek, xAI, Perplexity. Single text field, Save, status line.
  - **`BrowserAuthRow`** — Gemini, Groq, Cerebras, GitHub Models, OpenRouter, HuggingFace, Ollama Cloud. "Sign in with browser" → opens localhost callback URL via `java.awt.Desktop.browse(...)` (or, where supported, paste-token fallback).
  - **`LocalRuntimePlusCloudRow`** — Ollama (local + browser sign-in for Cloud). Daemon URL (default `http://localhost:11434`), "Sign in to Ollama Cloud" button.
  - **`LocalModelDownloadRow`** — pulled Ollama model picker. Lists `ollama list`, "Pull a model…" with download-size warnings (`01 §Ollama` table sources sizes).
  - **`PaidWithCardRow`** — every paid provider that can take card on file (Anthropic, OpenAI, etc.). Same as `PureApiKeyRow` plus contextual "manage billing at …" link.
- `SettingsDialog.openWithProvider(Frame, String providerId)` opens the Models & Agents tab pre-scrolled to the relevant row (`JViewport.scrollRectToVisible`).
- Each row's Save button:
  1. Writes the key to LiteLLM proxy config (`agent/providers/litellm.config.yaml` env reference filled in `state.json` keystore).
  2. Calls `ProviderRegistry.refreshProvider(providerId)` synchronously with **4 s** timeout (`06 §4.4`).
  3. On success: status flips ✓; if Phase D `pendingLaunch` is set, fires Phase H's `FirstUseDialog` and launches the originally selected model.
  4. On failure: inline red error with provider's actual response.
- Manual: Anthropic — paste a real key, hit Save, confirm `localhost:4000/v1/models` returns Claude entries within 4 s.
- Manual: Gemini — click Sign in with browser, complete OAuth, confirm registry refreshes. (If browser OAuth not available May 2026 for Gemini's API path, fallback to API-key paste with link to `aistudio.google.com/app/apikey`.)
- Manual: Ollama Local — non-default daemon URL is honoured by subsequent `/models` calls.

**Files.** New: `InstallerRow.java` (abstract base) + five concrete rows under `imagejai.ui.installer/` · `CredentialStore.java` (reads/writes `state.json` keystore, mediates between Java and the YAML config). Modified: `SettingsDialog.java` line 32/135 (widen `providerCombo` per `05 §9.3`); 163–167 (third tab `"Multi-Provider"` from `05 §9.1`); 166 (rebuild Models & Agents tab as vertical stack of `InstallerRow` instances); end (add `openWithProvider`). `agent/providers/litellm.config.yaml`: ensure each provider entry references env vars by the same key the new `CredentialStore` writes. NOT touched: existing Profiles tab (lines 81–161). `Settings.java` schema — Phase D's work.

**Dependencies.** Phase D (the dropdown's ⚠ click flow needs `openWithProvider`).

**Effort.** **~4 days (32 h).** Five row types × ~3 h = 15 h. Browser OAuth callback for Gemini (and other browser-auth providers) is the unknown — budget 4 h. Credential store + manual tests across providers eat the remainder.

**Risks.**
- **Key storage at rest.** `state.json` is plaintext. Document explicitly; the current ImageJAI assumption is that the user owns their machine. Linux/macOS keychain integration is explicit non-goal (out of scope; biologists running headless boxes need the flat-file path).
- **OAuth callback flake.** Browser-auth flows that need a localhost callback (Gemini, OpenRouter) collide with anything on `localhost:8765`. Pick a free port with `ServerSocket(0)` and pass into the OAuth URL.
- **Ollama daemon URL trust.** Some users run Ollama on a remote box. Sanity-check by hitting `GET /api/tags` with 2 s timeout before saving; surface the response.

---

### Phase F — Per-model-family context files

**Goal.** Implement the additive-overlay loader from `04 §4`, land `agent/contexts/` with `base.md` + harness + capabilities + reliability + family overlays, **rewrite `agent/sync_context.py` to delegate to the loader** (per Open Q3 RESOLVED — file path preserved for backward compat with anyone running it manually, but logic replaced), migrate the seven existing CLI context files to be re-derived from the loader.

**Acceptance.**
- `agent/contexts/` directory layout from `04 §4`: `base.md`, `harness/cli_shell.md`, `harness/tool_loop.md`, `capabilities/no_vision.md`, `capabilities/small_context.md`, `reliability/good_tools.md`, `reliability/weak_tools.md`, `families/{anthropic,openai,gemini,llama,qwen,gemma,phi,mistral,deepseek,glm,kimi,gpt_oss}.md`.
- **No separate context registry yaml** — loader reads the unified `agent/providers/models.yaml` (created in Phase D, extended here with context-loader fields per `02 §1`/`04 §4`).
- `agent/contexts/loader.py` matches the ~32-line sketch in `04 §4`. `python -m agent.contexts.loader anthropic/claude-opus-4-7` prints composed context to stdout.
- `agent/contexts/test_contexts.py`:
  - Asserts every model in the registry composes without raising.
  - Snapshot-tests every composed output to lock against drift.
  - Asserts the composed Claude context contains every paragraph today's `agent/CLAUDE.md` carries (the equivalence check from `04 §5` Step 1).
- `sync_context.py` rewritten: `read_*()` delegates to `loader.load_context(model_id)` per `04 §5` Step 2 (CLAUDE.md → `anthropic/claude-opus-4-7`, AGENTS.md → `openai/gpt-5-codex`, GEMINI.md → `google/gemini-2-5-pro`, etc.). Claude-only handshake content no longer in AGENTS.md / GEMINI.md / .aider.conventions.md (resolves bug 1 from `04 §1`).
- Groovy/Jython console-error fact (CLAUDE.md:209–213) is now in `base.md` and propagates to every regenerated CLI context (resolves bug 2).
- `agent/gemma4_31b/loop.py` reads `loader.load_context("ollama/gemma4:31b-cloud")` instead of `agent/gemma4_31b/GEMMA_CLAUDE.md` directly (GEMMA file becomes a regenerated artefact).
- The MEMORY.md house rules (never close Log window; display object mask not outlines; check windows before selectImage) are folded into `base.md`.
- Manual: run `python sync_context.py`, then `git diff` should show every legacy CLI file still ends with the same trailing paragraph but no longer carries Claude handshake noise in middle.
- Manual: launch Claude Code with new CLAUDE.md — confirms it still understands TCP commands and Groovy/console rule.
- Manual: launch the Gemma loop with new context — confirms agent still recognises `threshold_shootout`, `run_macro`, etc.

**Files.** New: `agent/contexts/base.md` (215-line draft from `04 §2`); `harness/cli_shell.md` (lifted from CLAUDE.md:28–71); `harness/tool_loop.md` (from GEMMA_CLAUDE.md:14–31); `capabilities/no_vision.md`, `small_context.md` (per `04 §3.2-3.3`); `reliability/good_tools.md`, `weak_tools.md` (per `04 §3.4`); `families/*.md` (twelve files; empty stubs except Gemma — quirks from GEMMA_CLAUDE.md, Anthropic — handshake from CLAUDE.md:6–25, Qwen — macro-syntax-vs-Python one-liner from `04 §3.5`); `loader.py` (the ~32-line composition function; reads the unified `agent/providers/models.yaml`); `test_contexts.py`. Modified: `agent/providers/models.yaml` extends each entry with context-loader fields used by `loader.py` — `harness` (`cli_shell` or `tool_loop`), `family`, `context_size`, `tool_call_reliability`. Existing display fields (`display_name`, `description`, `tier`, `pinned`, `vision_capable`) added in Phase D untouched. Both consumers (Java `ProviderRegistry`, Python `loader.py`) ignore fields they don't recognise. `agent/sync_context.py`: rewritten — `read_claude_md()` / `strip_claude_specific()` delegate to the loader; `AGENT_FILES` map now names a model id per output path. `agent/gemma4_31b/loop.py`: switch system-prompt source to `loader.load_context("ollama/gemma4:31b-cloud")`. NOT touched: existing legacy CLI context files. The migration rule from `04 §5` ("must remain valid agent contexts at every commit") means these are *regenerated* by the new loader pathway, not deleted.

**Dependencies.** None functionally — F is independent of A–E. Recommended to ship in parallel with A/B/C/D. (F can extend `models.yaml` with context-loader fields; if Phase D hasn't landed yet, F creates the file with both display *and* context fields.)

**Effort.** **~3 days (24 h).** One day `base.md` + harness overlays (mostly lift-and-tag). Half-day loader + registry-extension. Half-day family overlays. One day `sync_context.py` migration + snapshot tests + manual sanity-check across CLAs.

**Risks.**
- **Loss of fidelity** — a hand-edited line in CLAUDE.md gets dropped. Mitigation: equivalence test in `04 §5` Step 1 as a CI check.
- **Live agents read a file we missed.** Mitigation: project-wide grep for likely filenames (`CONVENTIONS.md`, `INSTRUCTIONS.md`, hidden dotfiles) before flipping the bridge. Stage 4 audit found six output paths.
- **Family overlay regressions.** Gemma quirks in `families/gemma.md` are tuned against actual friction logs. Snapshot test locks composed output per model — quirk changes show as snapshot diff and must be reviewed.

---

### Phase G — Auto-discovery + curation + soft-deprecation

**Goal.** Implement the merge layer from `02`: a `models.yaml` loader joining curated entries with live `/models` results from each configured provider, applying soft-deprecation (30-day window for disappearing models, pinned-deprecated for user pins), keeping a 24 h on-disk cache, shipping the audit tool from `02 §7`. After G, Phase D's hardcoded `models.yaml` becomes the real curated source-of-truth and the dropdown grows automatically.

**Acceptance.**
- `agent/providers/models.yaml` populated with the full `01` catalogue — at least every starred-3+ entry. Each entry has `provider`, `model_id`, `display_name`, `description`, `tier`, `context_window`, `vision_capable`, `tool_call_reliability`, `last_verified`, `pinned`, `curated`, plus context fields from Phase F. Schema matches `02 §1`.
- User-overrides path resolves correctly per OS: Windows `%APPDATA%/imagejai/models_local.yaml`; macOS `~/Library/Application Support/imagejai/models_local.yaml`; Linux `${XDG_CONFIG_HOME:-~/.config}/imagejai/models_local.yaml`.
- `ProviderRegistry` (Java) implements the merge from `02 §2` pseudocode:
  - Curated + upstream → use curated, refresh `last_verified`.
  - Curated, not upstream → call `mark_soft_deprecated`, hide after 30 days unless pinned. **Only mark deprecated on a *successful* endpoint call that returns *without* the model — never on a failed call** (refines `02 §3` sketch; mitigates risk #10 below).
  - Not curated, upstream → synthesise stub with `tool_call_reliability=low`.
- 24 h on-disk cache populated under `<config-root>/imagejai/cache/models/<provider>.json` per `02 §5`. Cache misses fetch synchronously with 5 s per-provider timeout.
- Manual refresh button at top of popup (`05 §8`) calls `ProviderRegistry.refreshAll()` in parallel with **4 s** per-provider timeout; failures keep stale entries and surface a red dot per `02 §5` "stale-but-present beats empty".
- Soft-deprecated rows render with strikethrough subtitle from `02 §3`. Pinned-deprecated rows render with red `RETIRED` badge.
- Auto-discovered uncurated rows appear in `— Auto-discovered (unverified) —` (em-dash form) per `05 §4.4` and `06 §5`.
- `agent/providers/audit_models.py` matches `02 §7` sketch including `--apply` for stub generation and `--verbose` for full diffs.
- Manual: starting Fiji with Anthropic configured triggers a refresh; the dropdown's Anthropic submenu shows curated rows + any newly-introduced models in the auto-discovered subsection.
- Manual: removing a model from `models.yaml`, restarting Fiji — missing model appears soft-deprecated. Setting clock forward 31 days and re-launching makes it disappear (or stay if pinned).
- Manual: `python -m agent.providers.audit_models` prints the diff against live providers.

**Files.** New: `audit_models.py` (the tool from `02 §7`); `engine/picker/ModelsYamlLoader.java` (SafeYAML-based); `ModelsLocalLoader.java` (reads/writes `models_local.yaml` for pins/hides — also SafeConstructor since user-editable); `MergeFunction.java` (the merge logic); `SoftDeprecationPolicy.java` (handles `mark_soft_deprecated` and the 30-day lifecycle); `ModelsCache.java` (per-provider JSON cache with 24 h TTL); `ProviderDiscovery.java` (fan-out HTTP calls to fifteen `/models` endpoints from `02 §6`). Modified: `agent/providers/models.yaml` (full curated catalogue replaces Phase D's seed list); `engine/picker/ProviderRegistry.java` (Phase D) wired into the new loaders; `ui/picker/ModelMenuItem.java` (strikethrough + RETIRED badge state from `05 §4.2`); `ui/picker/ProviderMenu.java` (auto-discovered subsection separator). NOT touched: the two providers without a live `/models` (Ollama Cloud, Perplexity per `02 §6`) — remain curated-only. The merge layer treats their curated entries as their own upstream list.

**Dependencies.** Phase D (the merge layer feeds into the dropdown's `ProviderRegistry`).

**Effort.** **~4 days (32 h).** Half-day populate `models.yaml` from survey table. One day merge + soft-deprecation in Java. One day `ProviderDiscovery` (each provider's response shape needs its own normaliser per `02 §6`). Half-day audit tool. One day manual cross-provider test.

**Risks.**
- **`/models` endpoint flake.** Each provider can rate-limit, 5xx, hang. Mitigation: 4 s per-provider timeout, `stale-but-present`. Test by deliberately killing connectivity to one provider mid-refresh.
- **Soft-deprecation false positive on transient outages.** A provider that rate-limits the discovery call could mark all its models deprecated. Already addressed in acceptance criteria above (only mark on successful call without model).
- **SnakeYAML CVE history on `models_local.yaml`.** `models_local.yaml` is **user-editable** (lives in `%APPDATA%`) — a malicious local actor could plant a YAML constructor exploit. Apply `SafeConstructor` to `ModelsLocalLoader`, not just to the bundled file.

---

### Phase H — Tier-safety dialogs + status icons + budget ceiling

**Goal.** Land the user-facing tier-safety affordances from `06`: tier badges (already rendered by Phase D's `TierBadgeIcon`, but H wires them to the curator's `tier:` field on `models.yaml`), first-use-of-paid-model dialog, status-icon click flows (⚠ → installer panel, ✗ → cached-error dialog), and the **opt-in budget ceiling** (v4 — see Open Q2; deliberately deferred to Phase H).

**Acceptance.**
- `FirstUseDialog` (`imagejai.ui.picker`) implements modality + cancel from `06 §3.2-3.4`:
  - DOCUMENT_MODAL parented to `AiRootPanel`'s top-level `JFrame`.
  - Three exits: Continue / Use a free model instead / [×]. Cancel re-opens the popup filtered to 🟢/🟡 only.
  - "Don't ask again for {provider} this session" — in-memory `Set<ProviderId>` on `ProviderTierGate`, never persisted.
- `UncuratedDialog` mirrors `FirstUseDialog`'s scaffolding for the uncurated case from `06 §5.3`. Stacks *after* the paid dialog when both apply.
- `BillingFailureDialog` fires when the agent loop catches 401/402/429-billing from the proxy; presents the dialog from `06 §3.7`.
- `TierChangeBanner` consumes `models.yaml` `pricing_changes` field and `state.json`'s favourites map per `06 §7`. Renders one strip per change above the chat card.
- `BudgetCeilingDialog` fires per `06 §6.3` when `session_cost_usd >= ceiling`. Reads per-call cost from the Python wrapper's `x-litellm-response-cost` callback (Phase A).
- `Settings.confirmPaidModels`, `warnUncuratedModels`, `budgetCeilingEnabled`/`budgetCeilingUsd` are exposed in the new Multi-Provider settings tab from Phase D's `Settings.java`.
- Status icon click flow from `05 §7` works end-to-end:
  - ⚠ click on a model row → opens `SettingsDialog.openWithProvider(parent, providerId)`, sets `pendingLaunch`. After Save & run in Phase E's installer, the pending launch resumes (firing FirstUseDialog if paid).
  - ✗ click on a provider header → opens cached-error dialog from `06 §4.3`.
  - ✓ click → falls through to launch (no-op on icon).
- `state.json` schema matches `06 §7.2` for favourites + dismissed banners. **Atomic writes** (tmp file + rename) plus a file lock if multi-instance is supported (or document if not) — prevents racing on concurrent agent sessions.
- Manual: pick `claude-sonnet-4-6` (paid, configured) → dialog fires → Continue → loop runs. Pick again same session → no dialog. Pick again across Fiji restart → dialog fires again.
- Manual: enable budget ceiling at $0.10, pick Claude Sonnet, run a long session — at the first call crossing $0.10 the loop pauses with `BudgetCeilingDialog` message in the agent terminal. `/resume` raises ceiling and continues; `/switch` drops to a free model.
- Manual: pick uncurated `⚪` row → uncurated dialog fires. Pick again same session → no dialog. Switch to a paid uncurated row → both dialogs fire (paid first, then uncurated).

**Files.** New: `FirstUseDialog.java` · `UncuratedDialog.java` · `BillingFailureDialog.java` · `BudgetCeilingDialog.java` · `TierChangeBanner.java` · `ProviderTierGate.java` (under `ui/picker/`); `config/UserState.java` (reads `state.json` for favourites, dismissed banners, "first use of day" tracking from `06 §3.6` and `06 §7.2`); `agent/providers/budget_ceiling.py` (Python-side hook watching `x-litellm-response-cost`, accumulates `session_cost_usd`, emits structured pause message). Modified: `engine/picker/ProviderRegistry.java` (Phase D) emits `tier-change` events to `TierChangeBanner` on refresh; `ui/picker/ModelPickerButton.java` (Phase D) wires click handler to `ProviderTierGate` per `05 §6.1`; `SettingsDialog.java` (Phase E) populates the Multi-Provider tab with tier-safety toggles from `05 §9.1`; `agent/providers/litellm_proxy.py` (Phase B) surfaces cost header to budget-ceiling subscriber. NOT touched: the CLI-spawn path. Tier safety only applies to proxy/native; CLI agents handle their own auth/billing out-of-band.

**Dependencies.** Phase D (dropdown), Phase E (installer panel), Phase G (curated `tier:` field; `pricing_changes` for tier-change banners). Budget ceiling depends on Phase A (cost headers) and B (Python wrapper).

**Effort.** **~3.5 days (28 h).** Each dialog ~3 h with layout already spec'd. State.json + favourites + pricing-change diff ~6 h. Budget ceiling ~6 h (Python middleware + `/resume`/`/switch` terminal commands). Manual-test pass takes a day.

**Risks.**
- **Tier-change false positives.** A `models.yaml` typo (price `3.0` → `30.0`) triggers a 10× banner cascade for every favourite. Mitigation: audit-tool's `--verbose` diff in Phase G surfaces large changes loudly. Add runtime sanity check: if more than 3 favourites would banner at once, surface one consolidated banner instead.
- **Budget-ceiling cost-header gaps.** Some providers don't return the cost header through the OpenAI-compat shim. The fallback (local token-count × `models.yaml` pricing) needs the same pricing table; stale price means ceiling fires at the wrong amount. Mitigation: log "ceiling estimated, may be ±20%" in the pause message.
- **Browser callback for "Open … console"-style links.** Use `java.awt.Desktop.browse(URI)` and catch `UnsupportedOperationException` on headless boxes — fall back to printing the URL to the terminal.
- **In-flight call vs ceiling.** Ceiling fires *between* iterations (`06 §6.3`). Document: a single very-expensive call still completes (and is billed) before the next is gated.

---

## 3. Dependency graph + recommended sequence

```
   Phase A: LiteLLM proxy bundling + autostart
           │
           ▼
   Phase B: Python provider modules
           │
           ├──► Phase C: Native paths (Anthropic + Gemini features)
           │
           ▼
   Phase D: Java ProviderRegistry + cascading dropdown
           │
           ├──► Phase E: Installer panel extension
           │             │
           │             ▼
           │      Phase H: Tier-safety dialogs + budget ceiling
           │             ▲
           ▼             │
   Phase G: Auto-discovery + curation + soft-deprecation
                         │
                         └── (G feeds the tier field that H consumes)

   Phase F: Per-model-family context files       (independent —
                                                  ships in parallel)
```

### Recommended ship sequence

```
A → B → D → E → G → C → H
            (F runs in parallel from week 1 — no blocker)
```

- **A then B** — without proxy and Python clients, nothing else ships. Two weeks before any UI work.
- **D before E** — the dropdown delivers the new shape end-to-end (even with hardcoded yaml); E adds polish on the install path.
- **G before C and H** — curated tier field needs to exist before tier-safety dialogs (H) can read it. Auto-discovery also benefits the soft-deprecation testing path H reuses.
- **C late** — native features (caching, server tools) are power-user. Shipping the dropdown to free-tier-only users first lets us validate the architecture before adding richness.
- **H last** — tier safety is the final consumer of every prior phase's output (proxy headers, registry state, curated tiers, installer).

### Parallelisable

- **Phase F** independent of A–E and G–H. Day-one ship in parallel with A.
- **Phase C** independent of D–G. Once B lands, can run alongside D.
- **Phase G** starts only after D, but parallelises with E, C, and the early parts of H.

### Minimum viable shipping (MVS)

The first user-facing release ships when **A + B + D + F** are green and stable. Specifically:
- LiteLLM proxy autostarts (A).
- Python provider modules work (B).
- Cascading dropdown is feature-flag-on for the lab build (D).
- Context overlays don't regress the existing CLAUDE.md / GEMMA_CLAUDE.md agents (F).

E, G, H, C ship in subsequent releases:

- **MVS v1** (A+B+D+F): three providers wired (Ollama, Groq, Gemini), hardcoded yaml, no soft-deprecation, no budget ceiling, no banners. Dropdown functional but install flow is "open Settings → paste key" rather than the polished installer rows.
- **MVS v2** (+ E): polished install flow.
- **MVS v3** (+ G): full provider catalogue, auto-discovery, curation.
- **MVS v4** (+ H): tier-safety dialogs and budget ceiling.
- **MVS v5** (+ C): native features (prompt caching, server tools).

**Why this set:** A+B is the architecture foundation; nothing ships without them. D is where the user sees value. F prevents context regressions while wrapper paths shift. E/G/H/C add value but are independent slices that can ship iteratively.

---

## 4. Risk register (aggregated)

| # | Risk | Phase | Mitigation |
|:-:|------|:-----:|------------|
| 1 | PEP-563 stringified annotations break the schema converter | B | `typing.get_type_hints(fn)` per `03 §4.2`; locked by spike test + new caller-module test. |
| 2 | Anthropic rejects `tool_result` without preceding assistant `tool_use` block list | B/C | Always re-thread full content block list; never strip `thinking`; per `03 §5.2`/§8 #2. |
| 3 | OpenAI tool-call arguments are JSON strings, not dicts | B | Parse inside `extract_tool_calls` in `litellm_proxy.py`. |
| 4 | Gemini has no native tool-call ids (matches by name + position) | B | Synthesise `f"{name}:{idx}"`; `03 §5.3`. |
| 5 | `sync_context.py` regex leaks Claude-only handshake into AGENTS.md/GEMINI.md/Aider | F | Replace with loader-based derivation per `04 §5` Step 2; equivalence test. |
| 6 | Live agents read a context file we missed | F | Project-wide grep before flipping; explicit allow-list of files to regenerate per `04 §5` Step 2. |
| 7 | Custom `JMenuItem` rendering + `MouseEvent.consume()` ordering for click-to-pin | D | Follow `05 §3.4` exactly. |
| 8 | Hover-card `JWindow` flicker on rapid sibling traversal | D | Single `JWindow` per popup show, 400 ms show + 120 ms grace per `05 §5.2`. |
| 9 | Tier-change false positives from `models.yaml` typos cascade banners | H | Audit-tool `--verbose` highlights large diffs (G); runtime caps consolidated banner at 3 simultaneous favourites (H). |
| 10 | Soft-deprecation false positive on transient `/models` outage | G | Only mark deprecated on a successful endpoint call that returns *without* the model — never on failed call. |
| 11 | LiteLLM proxy startup time on cold launch | A | Background-thread the boot, surface "proxy ready" banner async; fallback port range 4000–4010. |
| 12 | Windows process tree doesn't get shutdown signal cleanly | A | `taskkill /T` in JVM shutdown hook. |
| 13 | Browser OAuth callback ports collide on user's machine | E | `ServerSocket(0)` to pick a free port; pass into OAuth URL. |
| 14 | Plaintext key storage (`state.json`) is a known limitation | E | Document explicitly; OS-keychain integration out-of-scope. |
| 15 | LiteLLM cost headers absent for some providers, breaking budget ceiling | H | Fallback to local token-count × `models.yaml` price; warn user the ceiling is ±20% in pause message. |
| 16 | SnakeYAML CVE history (RCE on untrusted input) | D, G | Always use `SafeConstructor`; never default `Constructor`. Apply to BOTH bundled `models.yaml` AND user-editable `models_local.yaml`. |
| 17 | Streaming gap (spike is `stream=False` everywhere) | B (acknowledged) | Document explicitly; defer to a future phase outside this plan. |
| 18 | Pricing volatility — Anthropic, DeepSeek discount, Cerebras deprecation | G | Monthly audit-tool run (`02 §7`); `pricing_changes` field with future-dated entries fires T-7 banners (H). |
| 19 | Provider-key spelling drift (spike underscores vs canonical hyphens) | B | Canonical hyphenated allow-list exported from `agent/providers/__init__.py`; `router.get_client` rejects unknown keys at registry load, not at first launch. |
| 20 | Spike's `_PROXY_PROVIDERS` set covers only 8 of 15 providers | B | Acceptance criterion: `router.get_client` returns a working client for every provider in `01`'s master comparison table. |
| 21 | Tier-vocabulary mismatch between yaml schema (02), curator examples (06), context registry (04) | B/D | Single canonical enum (`02 §1`'s `free`/`free-with-limits`/`paid`/`requires-subscription`) declared in `models.yaml` header comment; both Java and Python loaders reject unknown values with a clear error. |
| 22 | Tool-reliability vocabulary mismatch (`high/medium/low` yaml vs `excellent/good/weak` overlay filenames vs UI strings) | B/F | Yaml stores `high/medium/low`; loader maps to overlay files; UI maps to lay-language strings. Document mapping in yaml header comment. |
| 23 | LiteLLM cost-header version drift (header renamed in upgrade) | B/H | Pin LiteLLM in `requirements.txt`; startup self-test fires one tiny chat and asserts header is present, fails loudly if not. |
| 24 | Opus 4.7 ~35% tokenizer expansion affects cost estimates AND budget-ceiling fallback | C/H | Curator note for Opus 4.7 includes the multiplier; budget-ceiling fallback applies it; hover-card ranges derived against it. |
| 25 | Concurrent agent sessions racing on `state.json` | H | Atomic write (tmp file + rename); file lock if multi-instance supported, document if not. |

---

## 5. Open questions

These need a decision before the relevant phase can start.

1. **Monthly scheduled audit-tool run after Phase G lands?** `02 §7` flags this. Recommendation: yes — add a `/schedule` routine `every first Monday of the month` that runs the audit and opens a PR with `--apply` stub block. Light effort, high signal. **User decision needed.**
2. **Budget ceiling in MVS v1 or defer to MVS v4?** `06 §6.1` recommends "ship it, off by default". **Decision: defer to v4 (Phase H) as planned** — paid models are excluded from `models.yaml` until v4 ships, so the ceiling has nothing to protect earlier. The §3 first-use dialog gates against accidental *first* spend; that's the immediate concern.
3. **Keep `sync_context.py` or replace entirely? — RESOLVED.** Phase F **rewrites** `sync_context.py` to delegate `read_claude_md()` and the `AGENT_FILES` map to `loader.load_context()`. The file path is preserved (anyone running it manually still gets generated CLI contexts) but its logic is replaced. Removal of the file altogether is a future cleanup, not a Phase F goal.
4. **Standalone ▶ button: re-launch last, removed, or kept as fallback? — CONFIRMED re-launch last.** `05 §10.3`. Implementation: enabled iff `selectedProvider` + `selectedModelId` resolve to a `ModelEntry` with status ✓; disabled tooltip "Pick a model from the dropdown first."
5. **Context registry conflict — RESOLVED (user decision 2026-05-02).** Stage 2 and Stage 4 each proposed a `models.yaml`. Merge into a single `agent/providers/models.yaml` carrying both display fields (`display_name`, `description`, `tier`, `pinned`, `vision_capable`) and context-loader fields (`harness`, `family`, `context_size`, `tool_call_reliability`). Each consumer ignores fields it doesn't recognise — no extra plumbing. Phase D creates the file with display fields; Phase F extends with context fields; Phase G fills the full curated catalogue. Single source of truth, no risk of drift.
6. **`KNOWN_AGENTS` rename to `CLI_AGENTS`.** `05 §10.4`. Recommendation: defer to a post-Phase-D cleanup commit (after the flag flips on by default). Keep the variable name verbatim during parallel-path coexistence so diffs stay readable.
7. **Promotion strategy for soft-deprecated user-pinned models.** `02 §3` defines pinned-deprecated as "stays visible with stronger warning". When a user explicitly tries to launch a pinned-deprecated model and it fails (no longer reachable), should the failure auto-unpin? Recommendation: **no.** The user pinned for a reason; converting failure to silent unpin is surprising. Document; revisit after first user complaint.

---

## 6. First-milestone definition (MVS v1)

| | |
|---|---|
| **Phases** | A + B + D + F |
| **Providers** | Ollama (existing), Groq (new free), Gemini (new free) |
| **Features** | LiteLLM proxy autostart; Python provider modules; cascading dropdown behind `useMultiProviderPicker = true`; context overlays composing per-model |
| **Not yet** | Native Anthropic/Gemini features (Phase C); polished installer panel (Phase E); auto-discovery + soft-deprecation (Phase G); tier-safety dialogs / budget ceiling (Phase H) |
| **`models.yaml`** | Hardcoded ~12 entries — Ollama-local pulled models, `gemma4:31b-cloud`, three Groq Llama models, three Gemini Flash models |
| **Settings** | New tab exists (Phase D) but only carries `useMultiProviderPicker` and `selectedProvider`/`selectedModelId` |
| **First-use dialog** | Not present in v1 — paid models still excluded from `models.yaml` until Phase H ships the dialog |
| **Risk envelope** | Lowest — every paid provider gated behind "no curated row in v1" until tier safety is in |

**Subsequent milestones:**
- **v2** = v1 + E: install via the new panel rather than hand-editing the proxy config.
- **v3** = v2 + G + curated paid models for the four must-have providers (Anthropic, OpenAI, Cerebras, OpenRouter): full curation + auto-discovery.
- **v4** = v3 + H: tier safety. Paid models become safely usable.
- **v5** = v4 + C: native features, prompt caching, server tools.

---

## 7. Quick-start for the engineer who picks this up

### Orientation
- Read in this order: `00`, this file, then drill into the stage doc that maps to your current phase.
- The spike is at `agent/providers/_spike/`. **Don't modify it.** Proven baseline. Run its tests once (`pytest agent/providers/_spike/test_spike.py -v`) to confirm the local Python env can drive the SDKs.
- `agent/CLAUDE.md` is the richest source for how the existing Claude-flavoured agent uses the TCP surface — read before touching context files.
- Current Swing surface: `src/main/java/imagejai/ui/AiRootPanel.java`. The flat `JComboBox` to be replaced is at lines 63 and 209.
- Current launcher: `src/main/java/imagejai/engine/AgentLauncher.java`. CLI-agent table at lines 62–72; launch entry point `launch(AgentInfo, Mode)` at line 135.

### Recommended branch
`feature/multi-provider-mvs` — long-lived feature branch, rebased onto `main` between phases; merged after each phase via PR with `gsd-execute-phase`-style atomic commits.

### Recommended commit cadence
**One commit per phase task, not per phase.** Each phase has 6–10 discrete tasks (Phase A: "add proxy.py", "add LiteLlmProxyService spawn", "add shutdown hook", "wire cost-header callback", etc.). Atomic commits keep `git bisect` useful and let any phase be partially reverted.

Tag head of each phase with `multiprovider-phase-<letter>-done`.

### Test strategy
- **A:** manual cross-OS spawn test. No automated tests — proxy is third-party.
- **B:** `pytest agent/providers/test_*.py` covers eight spike + three new tests. Run `pytest agent/providers/_spike/test_spike.py` unchanged as regression baseline.
- **C:** extend `agent/providers/test_native_features.py` with cassette tests for caching and server-tool opt-ins.
- **D:** new Swing-level test class `AiRootPanelMultiProviderTest` using `AssertJ-Swing` (or manual smoke). Plus unit test for `Settings.migrate()` covering each legacy `selectedAgentName` mapping from `05 §10.5`.
- **E:** manual cross-provider install passes.
- **F:** `agent/contexts/test_contexts.py` — snapshot + CLAUDE.md content-equivalence.
- **G:** `agent/providers/test_audit_models.py` covers diff logic + `--apply` stub generation. Manual full-provider refresh.
- **H:** end-to-end manual test for first-use dialog, budget ceiling, status-icon click flow.

### Where to log questions while building
- **Open questions during a phase** — append to this file, §5.
- **New risks discovered** — append to §4. Tag with phase letter that owns mitigation.
- **Curator decisions** (e.g. ambiguous tier classification) — `agent/providers/CURATION_NOTES.md` (create if needed; not part of this plan but a sensible parking lot).
- **Codebase observations during implementation** — `gsd-add-todo` if GSD workflow is in use, or a `// TODO(multiprovider): …` comment with phase letter.
