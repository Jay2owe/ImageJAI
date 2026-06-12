# Provider Launch Path Handoff and User Setup

This note captures the Ollama/Gemma launch-path fix and the pattern every
other provider should follow. The important rule is simple: provider choice
must change only the model-call adapter, not the terminal UI or Fiji tool loop.

## What broke

Gemma 4 31B originally launched as the bundled Python wrapper:

```text
AgentLauncher
  -> gemma4_31b_agent
  -> python -m gemma4_31b
  -> agent/gemma4_31b/loop.py
  -> ollama.chat(...)
```

That wrapper is the tuned user experience. It owns:

- animated thinking/tool/status lines
- prompt-toolkit input and queued prompts
- slash commands such as `/help`, `/queue`, `/interrupt`, `/mode`, `/think`
- the full Gemma/Fiji tool registry from `agent/gemma4_31b/registry.py`
- the event-bus subscriber from `agent/gemma4_31b/events.py`
- friction-log and stuck-loop guards
- post-tool safety injectors

The multi-provider picker had started routing `gemma4:31b-cloud` through the
generic provider path:

```text
AgentLaunchOrchestrator
  -> ProxyAgentLauncher
  -> python -m agent.providers.agent_cli --provider ollama-cloud --model gemma4:31b-cloud
  -> thin provider REPL / LiteLLM client
```

That path technically reached a model client, but it did not use the tuned
wrapper. For the user it looked and behaved like a different agent: less polish,
fewer controls, different status rendering, and inconsistent tool-loop behavior.
It also exposed alias mistakes such as passing cloud tags to the wrong LiteLLM
model name.

## The fix

The fix was to make the rich wrapper the common runtime and make provider/model
selection an input to that runtime.

### 1. Java routes Ollama Cloud back to the bundled wrapper

`src/main/java/imagejai/engine/picker/AgentLaunchOrchestrator.java` now
special-cases `providerId == "ollama-cloud"` in the proxy branch.

Instead of going to `ProxyAgentLauncher`, it builds this `AgentInfo`:

```text
command      = gemma4_31b_agent
contextFlags = --provider ollama-cloud --model <model_id>
local        = true
defaultModel = <model_id>
```

Then it calls the ordinary `AgentLauncher.launch(...)` path. That matters
because the session still goes through the same embedded-PTY/external-terminal
machinery, safe-mode/audit environment, context sync, and posture checks.

All Ollama Cloud models, not just `gemma4:31b-cloud`, should follow this rule.
The regression test is:

```text
src/test/java/imagejai/engine/picker/AgentLaunchOrchestratorTest.java
  - ollamaCloudProviderUsesBundledOllamaWrapperWithoutCliDetection
  - allOllamaCloudModelsUseOllamaWrapper
```

### 2. The bundled wrapper accepts provider and model

`agent/gemma4_31b/__main__.py` accepts:

```text
--provider <provider_key>
--model <model_id>
```

It starts the event subscriber and calls:

```python
loop.run(
    model=args.model,
    provider=args.provider,
    ...
)
```

`agent/gemma4_31b/loop.py` normalises the provider with:

```text
*-cloud or :cloud tag -> ollama-cloud
otherwise             -> ollama
```

When no `provider_client` is supplied, the loop uses the native Ollama Python
client:

```python
ollama.chat(model=model, messages=messages, tools=tools, ...)
```

That keeps local Ollama and Ollama Cloud on the original working path.

### 3. `agent.providers.agent_cli` delegates instead of owning the UX

`agent/providers/agent_cli.py` is now the provider entrypoint, but not the UI.

For Ollama providers:

```python
_OLLAMA_PROVIDERS = {"ollama", "ollama-cloud"}
_run_ollama_wrapper(provider, model)
```

That imports `agent.gemma4_31b.__main__.main()` and calls it in-process with
`--provider` and `--model`.

For non-Ollama providers:

```python
client = get_client(provider, model, **opts)
rich_loop.run(
    model=model,
    provider=provider,
    provider_client=client,
    provider_opts=opts,
    prompt_filename="",
    budget_guard=guard,
)
```

So OpenAI, Groq, Mistral, Anthropic, Gemini, etc. use the same terminal, same
slash commands, same event integration, and same Fiji tool registry. Only
`ProviderClient.chat(...)` differs.

The regression tests are:

```text
agent/providers/test_agent_cli.py
  - test_main_routes_ollama_to_gemma_wrapper
  - test_main_ollama_wrapper_importerror_falls_back
  - test_main_routes_non_ollama_to_rich_wrapper
```

### 4. Provider clients implement one narrow contract

`agent/providers/base.py` defines `ProviderClient`:

```python
chat(messages, tools, model, **opts)
extract_text(response)
extract_tool_calls(response)
append_assistant(messages, response)
append_tool_result(messages, call, result)
```

The rich loop calls those methods when `provider_client` is present. That is the
portability boundary: every provider adapter must translate its own SDK/API
shape into this interface.

Current router behavior in `agent/providers/router.py`:

- `anthropic` -> `AnthropicNativeClient`
- `gemini` -> `GeminiNativeClient`
- everything else in `_PROXY_PROVIDERS` -> `LiteLLMProxyClient`

The proxy client prefixes model aliases using `_PROXY_MODEL_PREFIXES`, for
example:

```text
openai          -> openai/<model>
groq            -> groq/<model>
ollama-cloud    -> ollama-cloud/<model>
together        -> together_ai/<model>
github-models   -> github/<model>
```

### 5. Java provider launch contract for all non-CLI providers

`ProviderAgentLaunch.plan(...)` is the common spawn shape for native/proxy
provider rows:

```text
python -m agent.providers.agent_cli --provider <provider> --model <model>
```

It also injects:

```text
PYTHONPATH                 = parent of the agent/ workspace
IMAGEJAI_PROVIDER          = <provider>
IMAGEJAI_MODEL             = <model>
IMAGEJAI_LITELLM_PORT      = selected sidecar port, when proxy is active
IMAGEJAI_BUDGET_CEILING_USD = current launch budget ceiling, when configured
IMAGEJAI_NATIVE_*          = native feature flags for Anthropic/Gemini
```

Then it calls `AgentLauncher.launch(plan.info, mode, plan.env)`. Do not spawn
provider terminals directly from new provider code. Always go through
`AgentLauncher` so embedded terminal styling, fallback-to-external behavior,
TCP port env, safe mode, and audit session metadata remain consistent.

The regression test is:

```text
src/test/java/imagejai/engine/picker/ProviderAgentLaunchTest.java
```

### 6. The wrapper is model-agnostic — no hardcoded model tag

The bundled wrapper drives *any* Ollama model (and, via a `ProviderClient`,
any provider model). There is **no hardcoded model default** baked into the
loop. `loop.DEFAULT_MODEL` was removed; the model is always supplied by
`--model` / `loop.run(model=...)`. For a bare manual launch with no `--model`,
`loop._resolve_default_model()` resolves in this order:

```text
IMAGEJAI_MODEL env -> OLLAMA_MODEL env -> first local Ollama model -> clear error
```

So multiple Ollama models share one wrapper script. `agent/gemma4_31b/__main__.py`
passes `--model` through (or resolves it), and `_probe_vision.py` uses
`IMAGEJAI_MODEL` / the resolver rather than a fixed tag. Regression test:
`agent/gemma4_31b/tests/test_resolve_default_model.py`.

### 7. Ollama is keyless — local AND cloud — via the local daemon

No Ollama model needs an API key. The local daemon (`localhost:11434`) serves
local models directly and **forwards `:cloud` / `-cloud` tags to ollama.com**
once the user has run `ollama signin`. The launch proves it:
`AgentLaunchOrchestrator` routes both `ollama` and `ollama-cloud` to the
bundled wrapper, which calls `ollama.chat()` against the local daemon and never
checks `OLLAMA_API_KEY`.

Treat the keyless local-daemon set as a first-class concept, not a one-off
string check on `"ollama"`:

- Java: `ProviderCredentials.LOCAL_DAEMON_PROVIDERS = {ollama, ollama-cloud}`
  with `isLocalDaemonProvider(key)`. `Settings.hasCredentialsFor` and
  `MultiProviderPanel.deriveStatus` return ready for that set — no key needed.
- Python: `agent/providers/litellm.config.yaml`'s `ollama-cloud` entry is
  `imagejai_env_optional: true` and routed through the local daemon
  (`localhost:11434`), so `proxy._enabled()` always exposes it. An API key, when
  present, is an optional override (direct ollama.com API), never a precondition.

Regression tests: `ProviderCredentialsTest.bothOllamaProvidersAreKeylessLocalDaemon`,
`MultiProviderPanelTest.ollamaCloudReadsAsReadyWithoutKey`. Any future provider
with a keyless local-daemon path should join `LOCAL_DAEMON_PROVIDERS` rather
than getting its own string special-case.

## How to apply the pattern to every provider

For each provider, implement only these pieces:

1. Add or update a `ProviderClient` adapter if the provider needs a native SDK.
   Keep it behind the five-method interface in `agent/providers/base.py`.

2. If the provider is OpenAI-compatible or already supported by LiteLLM, prefer
   the proxy path. Add the provider key to `_PROXY_MODEL_PREFIXES` and
   `PROVIDER_KEYS` in `agent/providers/router.py`.

3. Add one or more aliases in `agent/providers/litellm.config.yaml` for proxy
   providers. The `model_name` must match what `LiteLLMProxyClient.normalise_model`
   will send.

4. Add curated model rows in `agent/providers/models.yaml`. Keep provider ids
   hyphenated and equal to `router.py`.

5. Do not build a provider-specific terminal UI. The model row should launch
   `agent.providers.agent_cli`, and `agent_cli` should call `rich_loop.run(...)`.

6. Add tests:
   - router maps provider to the intended client
   - proxy alias normalises to the expected LiteLLM model name
   - `agent_cli.main(...)` reaches `_run_rich_provider_wrapper`
   - Java launch plan sets provider/model/env

7. Run the focused test set:

```powershell
python -m pytest agent/providers/test_agent_cli.py agent/providers/test_router.py -q
mvn -q "-Dtest=AgentLaunchOrchestratorTest,ProviderAgentLaunchTest,AgentRegistryTest" test "-Denforcer.skip=true"
```

## User Setup Guide

Users need two layers:

- a model backend daemon or API account
- the ImageJAI Python provider dependencies

### Common ImageJAI Python setup

From the ImageJAI project root:

```powershell
python -m pip install -r agent/providers/requirements.txt
python -m pip install -e agent/gemma4_31b
```

Verify the rich wrapper is importable:

```powershell
python -m agent.providers.agent_cli --help
python -m agent.gemma4_31b --help
```

### Ollama local daemon

Install Ollama from the official platform installer:

- Windows: https://docs.ollama.com/windows
- macOS: https://docs.ollama.com/macos
- Linux: https://docs.ollama.com/linux

After install, verify the daemon:

```powershell
ollama --version
ollama list
```

On Windows/macOS the desktop app usually starts the daemon. On Linux the
service path is:

```bash
sudo systemctl start ollama
sudo systemctl status ollama
```

Pull at least one local model that appears in `models.yaml` or add a matching
row:

```powershell
ollama pull llama3.2:3b
ollama pull llama3.1:8b
ollama pull qwen2.5-coder:7b
```

Verify the local API:

```powershell
curl http://localhost:11434/api/tags
```

### Ollama Cloud

Ollama Cloud can be reached two ways:

1. **Keyless (default in ImageJAI):** through the signed-in local daemon by
   running a `*-cloud` or `:cloud` model. No `OLLAMA_API_KEY` needed — just
   `ollama signin` once. ImageJAI launches cloud models this way (through the
   wrapper / local daemon) and shows the Ollama Cloud card as ready without any
   key configured.

   ```powershell
   ollama signin
   ollama run gemma3:27b-cloud   # daemon forwards to ollama.com
   ```

2. **Direct API (optional override):** through the direct Ollama Cloud API with
   `OLLAMA_API_KEY`. Only needed if you want to bypass the local daemon.

For the direct API path, create an Ollama API key and set:

```powershell
setx OLLAMA_API_KEY "<your-key>"
setx OLLAMA_CLOUD_API_BASE "https://ollama.com"
```

Open a new terminal after `setx`, then verify:

```powershell
curl https://ollama.com/api/tags
```

For CLI-backed cloud models, verify a cloud tag before using it in ImageJAI:

```powershell
ollama run qwen3-coder:480b-cloud
```

### LiteLLM sidecar for proxy providers

ImageJAI starts the LiteLLM sidecar itself through the Java plugin when needed,
but the Python environment must have the proxy package installed:

```powershell
python -m pip install -r agent/providers/requirements.txt
```

For manual testing from the repo root:

```powershell
python -m agent.providers.proxy --help
```

The Java launcher exports `IMAGEJAI_LITELLM_PORT` when the sidecar chooses a
port in `4000-4010`. The Python client also scans those ports if the env var is
missing, so a busy port 4000 does not silently break provider launches.

### API-key providers

Store keys in ImageJAI's per-provider secrets UI when available. For manual
terminal testing, set the expected environment variable before launching Fiji:

```powershell
setx OPENAI_API_KEY "<key>"
setx ANTHROPIC_API_KEY "<key>"
setx GOOGLE_API_KEY "<key>"
setx GEMINI_API_KEY "<key>"
setx GROQ_API_KEY "<key>"
setx CEREBRAS_API_KEY "<key>"
setx OPENROUTER_API_KEY "<key>"
setx GITHUB_TOKEN "<token>"
setx MISTRAL_API_KEY "<key>"
setx TOGETHER_API_KEY "<key>"
setx HUGGINGFACE_API_KEY "<key>"
setx DEEPSEEK_API_KEY "<key>"
setx XAI_API_KEY "<key>"
setx PERPLEXITY_API_KEY "<key>"
```

Restart Fiji after setting new environment variables. On Windows, `setx` only
affects new processes.

Useful official setup references:

- OpenAI quickstart/API key: https://developers.openai.com/api/docs/quickstart
- Anthropic quickstart/API key: https://docs.anthropic.com/en/docs/get-started
- Gemini API key: https://ai.google.dev/gemini-api/docs/api-key
- Groq quickstart: https://console.groq.com/docs/quickstart
- OpenRouter quickstart/authentication: https://openrouter.ai/docs/quickstart
- Mistral getting started: https://docs.mistral.ai/studio-api/overview
- LiteLLM proxy quickstart: https://docs.litellm.ai/docs/proxy/quick_start

### Smoke tests for users

With Fiji open and ImageJAI loaded:

1. Select a local Ollama model and launch. The terminal should show
   `imagejai_agent - ollama/<model>`.
2. Select an Ollama Cloud model. The terminal should show
   `imagejai_agent - ollama-cloud/<model>`.
3. Select a non-Ollama provider model. The terminal should still show the same
   `imagejai_agent` rich UI, not a provider-specific REPL.
4. Type `/help`. If slash commands appear, the launch is using the correct
   shared wrapper.
5. Ask "what images are open?" The first model action should call `get_state`
   or another Fiji inspection tool, proving the shared tool registry is active.
