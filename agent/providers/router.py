"""Provider router for ImageJAI's Python multi-provider clients."""
from __future__ import annotations

from typing import Any

from .anthropic_native import AnthropicNativeClient
from .base import ProviderClient
from .gemini_native import GeminiNativeClient
from .litellm_proxy import DEFAULT_API_KEY, DEFAULT_BASE_URL, LiteLLMProxyClient


_NATIVE_PROVIDERS = frozenset({"anthropic", "gemini"})

_PROXY_MODEL_PREFIXES = {
    "openai": "openai/",
    "groq": "groq/",
    "cerebras": "cerebras/",
    "openrouter": "openrouter/",
    "github-models": "github/",
    "mistral": "mistral/",
    "ollama-cloud": "ollama/",
    "ollama": "ollama/",
    "together": "together_ai/",
    "huggingface": "huggingface/",
    "deepseek": "deepseek/",
    "xai": "xai/",
    "perplexity": "perplexity/",
}

_PROXY_PROVIDERS = frozenset(_PROXY_MODEL_PREFIXES)

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
        }
    )
)

_KNOWN = _NATIVE_PROVIDERS | _PROXY_PROVIDERS
if _KNOWN != set(PROVIDER_KEYS):
    raise RuntimeError("provider registry mismatch between router paths and allow-list")


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

    provider_key = provider.strip().lower()
    if provider_key not in _KNOWN:
        raise ValueError(f"unknown provider {provider!r}. Known providers: {', '.join(PROVIDER_KEYS)}")

    if provider_key == "anthropic":
        return AnthropicNativeClient(
            api_key=opts.get("api_key"),
            timeout=opts.get("timeout", 120.0),
            max_retries=opts.get("max_retries", 2),
        )
    if provider_key == "gemini":
        return GeminiNativeClient(
            api_key=opts.get("api_key"),
            max_retries=opts.get("max_retries", 2),
            server_tools=opts.get("server_tools"),
        )

    return LiteLLMProxyClient(
        provider=provider_key,
        model_prefix=_PROXY_MODEL_PREFIXES[provider_key],
        base_url=opts.get("base_url", DEFAULT_BASE_URL),
        api_key=opts.get("api_key", DEFAULT_API_KEY),
        timeout=opts.get("timeout", 120.0),
        max_retries=opts.get("max_retries", 2),
    )


__all__ = ["PROVIDER_KEYS", "_PROXY_PROVIDERS", "get_client"]
