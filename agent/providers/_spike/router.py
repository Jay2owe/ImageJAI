"""Router — provider name + model -> ProviderClient instance.

Thin enough that the call site stays readable:

    client = router.get_client("anthropic", "claude-opus-4-7")
    resp   = client.chat(messages, ALL_TOOLS, "claude-opus-4-7")

The router does NOT cache clients — each ProviderClient owns an SDK
client which is itself cheap to construct. If profiling later shows
construction is hot, memoise here.
"""
from __future__ import annotations

from base import ProviderClient
from anthropic_native import AnthropicNativeClient
from gemini_native import GeminiNativeClient
from litellm_proxy import LiteLLMProxyClient


# Provider names that bypass LiteLLM and use a native SDK.
_NATIVE_PROVIDERS = {"anthropic", "gemini"}

# Provider names that route through the LiteLLM Proxy.
_PROXY_PROVIDERS = {
    "openai", "groq", "cerebras", "openrouter",
    "github_models", "mistral", "ollama_cloud", "ollama",
}


def get_client(provider: str, model: str | None = None,
               **opts) -> ProviderClient:
    """Return a ProviderClient for `provider`.

    `model` is accepted for symmetry but the client doesn't bind to it —
    the model string is passed at call time so one client instance can
    handle multiple models from the same provider.
    """
    p = provider.lower().strip()

    if p == "anthropic":
        return AnthropicNativeClient(api_key=opts.get("api_key"))
    if p == "gemini":
        return GeminiNativeClient(api_key=opts.get("api_key"))
    if p in _PROXY_PROVIDERS:
        return LiteLLMProxyClient(
            base_url=opts.get("base_url", "http://localhost:4000"),
            api_key=opts.get("api_key", "sk-litellm-proxy-local"),
        )

    raise ValueError(
        f"unknown provider {provider!r}. Known: "
        f"{sorted(_NATIVE_PROVIDERS | _PROXY_PROVIDERS)}"
    )


__all__ = ["get_client"]
