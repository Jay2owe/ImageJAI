"""Provider router for ImageJAI's Python multi-provider clients."""
from __future__ import annotations

import re
from collections.abc import Iterable
from typing import Any

from .anthropic_native import AnthropicNativeClient
from .base import HOST_CODE_CAPABILITY, ProviderClient, ProviderToolPolicy
from .gemini_native import GeminiNativeClient
from .litellm_proxy import DEFAULT_API_KEY, LiteLLMProxyClient, default_base_url


_NATIVE_PROVIDERS = frozenset({"anthropic", "gemini"})

for _adapter in (AnthropicNativeClient, GeminiNativeClient, LiteLLMProxyClient):
    if "._spike." in str(getattr(_adapter, "__module__", "")):
        raise RuntimeError("experimental provider adapters cannot be production-routed")

_PROXY_MODEL_PREFIXES = {
    "openai": "openai/",
    "groq": "groq/",
    "cerebras": "cerebras/",
    "openrouter": "openrouter/",
    "github-models": "github/",
    "mistral": "mistral/",
    "ollama-cloud": "ollama-cloud/",
    "ollama": "ollama/",
    "together": "together_ai/",
    "huggingface": "huggingface/",
    "deepseek": "deepseek/",
    "xai": "xai/",
    "perplexity": "perplexity/",
    # Keyless local OpenAI-compatible servers — run through a local daemon, no
    # API key. The alias prefix routes the proxy entry; litellm_params.model in
    # litellm.config.yaml uses the actual LiteLLM provider + local api_base.
    "lmstudio": "lmstudio/",
    "jan": "jan/",
    "llamacpp": "llamacpp/",
    "vllm": "vllm/",
}

_PROXY_PROVIDERS = frozenset(_PROXY_MODEL_PREFIXES)

# Keyless local OpenAI-compatible servers route ANY loaded model through a
# "<prefix>/*" wildcard entry in litellm.config.yaml. Their specific model is
# discovered at runtime (not curated), so the launch preflight must not require
# the alias to be enumerated by the proxy's /v1/models.
_WILDCARD_PROVIDERS = frozenset({"lmstudio", "jan", "llamacpp", "vllm"})

# This is an explicit trust classification, not an inference from base_url.
# In particular, loopback LiteLLM endpoints for cloud providers remain cloud.
_LOCAL_PROVIDERS = frozenset({"ollama", "lmstudio", "jan", "llamacpp", "vllm"})
_KNOWN_CAPABILITIES = frozenset({HOST_CODE_CAPABILITY})
_IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,255}$")

PROVIDER_KEYS = tuple(
    sorted(
        {
            "ollama",
            "ollama-cloud",
            "openai",
            "anthropic",
            "gemini",
            "groq",
            "cerebras",
            "openrouter",
            "github-models",
            "mistral",
            "together",
            "huggingface",
            "deepseek",
            "xai",
            "perplexity",
            "lmstudio",
            "jan",
            "llamacpp",
            "vllm",
        }
    )
)

_KNOWN = _NATIVE_PROVIDERS | _PROXY_PROVIDERS
if _KNOWN != set(PROVIDER_KEYS):
    raise RuntimeError("provider registry mismatch between router paths and allow-list")


def validate_process_identifier(value: str, label: str) -> str:
    """Validate a provider/model identifier before it reaches a boundary.

    The accepted alphabet covers the registry's model IDs while rejecting
    whitespace, control characters, quoting and shell metacharacters.
    """

    if not isinstance(value, str):
        raise ValueError(f"{label} must be a string")
    normalised = value.strip()
    if not _IDENTIFIER_RE.fullmatch(normalised):
        raise ValueError(f"invalid {label} {value!r}")
    return normalised


def _normalise_capabilities(raw: Any) -> frozenset[str]:
    if raw is None:
        return frozenset()
    if isinstance(raw, str) or not isinstance(raw, Iterable):
        raise ValueError("capabilities must be an iterable of capability names")
    capabilities = frozenset(str(item).strip() for item in raw)
    unknown = capabilities - _KNOWN_CAPABILITIES
    if unknown:
        raise ValueError("unknown provider capabilities: {}".format(", ".join(sorted(unknown))))
    return capabilities


def provider_tool_policy(provider: str, capabilities: Any = None) -> ProviderToolPolicy:
    """Return the trusted locality/capability policy for a provider key."""

    provider_key = validate_process_identifier(provider, "provider")
    if provider_key not in _KNOWN:
        raise ValueError(f"unknown provider {provider!r}. Known providers: {', '.join(PROVIDER_KEYS)}")
    return ProviderToolPolicy(
        provider=provider_key,
        is_local=provider_key in _LOCAL_PROVIDERS,
        capabilities=_normalise_capabilities(capabilities),
    )


def get_client(provider: str, model: str | None = None, **opts: Any) -> ProviderClient:
    """Return a client for one canonical hyphenated provider key.

    Provider-specific opts:

    - ``api_key`` (all): override the credential picked from env.
    - ``timeout`` / ``max_retries`` (all): wire timeouts.
    - ``server_tools`` (gemini only): list opt-in for Google's server-side
      tools. Accepts ``["google_search"]`` and/or ``["code_execution"]``.
      Both default off (parity with the OpenAI translation surface).

      **Cost note (Phase H risk E.8 — server-tool cost surprise):**
      ``code_execution`` runs in Google's billed sandbox. Each enabled
      call therefore incurs a sandbox charge in addition to token cost,
      and the charge is *not* surfaced in LiteLLM's
      ``x-litellm-response-cost`` header (we bypass the proxy on the
      native path). Phase H's budget ceiling must add a fixed surcharge
      per ``code_execution``-enabled call when accounting for spend.
      ``google_search`` is metered against a free daily quota and incurs
      no per-call dollar charge under current pricing.
    """

    if not isinstance(provider, str):
        raise ValueError("provider must be a string")
    provider_key = validate_process_identifier(provider.strip().lower(), "provider")
    policy = provider_tool_policy(provider_key, opts.get("capabilities"))
    if model is not None:
        validate_process_identifier(model, "model")

    if provider_key == "anthropic":
        client = AnthropicNativeClient(
            api_key=opts.get("api_key"),
            timeout=opts.get("timeout", 120.0),
            max_retries=opts.get("max_retries", 2),
        )
    elif provider_key == "gemini":
        client = GeminiNativeClient(
            api_key=opts.get("api_key"),
            timeout=opts.get("timeout", 120.0),
            max_retries=opts.get("max_retries", 2),
            server_tools=opts.get("server_tools"),
        )
    else:
        client = LiteLLMProxyClient(
            provider=provider_key,
            model_prefix=_PROXY_MODEL_PREFIXES[provider_key],
            base_url=opts.get("base_url") or default_base_url(),
            api_key=opts.get("api_key", DEFAULT_API_KEY),
            timeout=opts.get("timeout", 120.0),
            max_retries=opts.get("max_retries", 2),
            wildcard=provider_key in _WILDCARD_PROVIDERS,
        )
    return client.configure_tool_policy(policy)


__all__ = [
    "PROVIDER_KEYS",
    "_PROXY_PROVIDERS",
    "get_client",
    "provider_tool_policy",
    "validate_process_identifier",
]
