from __future__ import annotations

import json

import httpx
import pytest
import respx

from agent.providers import PROVIDER_KEYS, ProviderClient, router
from agent.providers.anthropic_native import AnthropicNativeClient
from agent.providers.gemini_native import GeminiNativeClient
from agent.providers.litellm_proxy import LiteLLMProxyClient
from agent.providers.router import _PROXY_PROVIDERS


def test_router_native_and_proxy_mapping(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    assert isinstance(router.get_client("anthropic", "claude-sonnet-4-6"), AnthropicNativeClient)
    assert isinstance(router.get_client("gemini", "gemini-2.5-flash"), GeminiNativeClient)
    assert isinstance(router.get_client("groq", "llama-3.3-70b-versatile"), LiteLLMProxyClient)
    assert isinstance(router.get_client("github-models", "openai/gpt-4o-mini"), LiteLLMProxyClient)
    assert isinstance(router.get_client("ollama-cloud", "gemma4:31b-cloud"), LiteLLMProxyClient)


def test_provider_key_allow_list_and_proxy_count() -> None:
    assert set(PROVIDER_KEYS) == {
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
    assert _PROXY_PROVIDERS == {
        "openai",
        "groq",
        "cerebras",
        "openrouter",
        "github-models",
        "mistral",
        "ollama-cloud",
        "ollama",
        "together",
        "huggingface",
        "deepseek",
        "xai",
        "perplexity",
    }
    assert len(_PROXY_PROVIDERS) == 13


def test_router_rejects_unknown_and_underscore_provider_keys() -> None:
    with pytest.raises(ValueError, match="unknown provider"):
        router.get_client("does-not-exist", "model")
    with pytest.raises(ValueError, match="unknown provider"):
        router.get_client("github_models", "openai/gpt-4o-mini")
    with pytest.raises(ValueError, match="unknown provider"):
        router.get_client("ollama_cloud", "gemma4:31b-cloud")


def test_router_exports_provider_client_type() -> None:
    assert issubclass(LiteLLMProxyClient, ProviderClient)


def test_together_proxy_smoke_routes_to_litellm_prefix() -> None:
    _assert_proxy_smoke(
        "together",
        "mistralai/Mixtral-8x7B-Instruct-v0.1",
        "together_ai/mistralai/Mixtral-8x7B-Instruct-v0.1",
    )


def test_huggingface_proxy_smoke_routes_to_litellm_prefix() -> None:
    _assert_proxy_smoke("huggingface", "openai/gpt-oss-120b", "huggingface/openai/gpt-oss-120b")


def test_deepseek_proxy_smoke_routes_to_litellm_prefix() -> None:
    _assert_proxy_smoke("deepseek", "deepseek-v4-flash", "deepseek/deepseek-v4-flash")


def test_xai_proxy_smoke_routes_to_litellm_prefix() -> None:
    _assert_proxy_smoke("xai", "grok-4.1-fast", "xai/grok-4.1-fast")


def test_perplexity_proxy_smoke_routes_to_litellm_prefix() -> None:
    _assert_proxy_smoke("perplexity", "sonar", "perplexity/sonar")


def _assert_proxy_smoke(provider: str, model: str, expected_model: str) -> None:
    client = router.get_client(provider, model, base_url="http://localhost:4000", api_key="dummy")
    assert isinstance(client, LiteLLMProxyClient)
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "localhost"
        assert request.url.port == 4000
        seen.append(json.loads(request.content))
        return httpx.Response(200, json=_openai_text_json("ok"))

    with respx.mock(assert_all_called=True) as mock:
        mock.post("http://localhost:4000/v1/chat/completions").mock(side_effect=handler)
        response = client.chat([{"role": "user", "content": "hi"}], [], model)

    assert client.extract_text(response) == "ok"
    assert seen[0]["model"] == expected_model


def _openai_text_json(text: str) -> dict:
    return {
        "id": "chatcmpl-text",
        "object": "chat.completion",
        "created": 1,
        "model": "test",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": text},
                "finish_reason": "stop",
            }
        ],
    }
