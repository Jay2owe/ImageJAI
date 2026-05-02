"""LiteLLM Proxy provider client.

This path uses the OpenAI SDK against the local LiteLLM Proxy. Phase B is
non-streaming by design.
"""
from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI

from .base import ProviderClient, ToolCall, to_openai_tool


DEFAULT_BASE_URL = "http://localhost:4000/v1"
DEFAULT_API_KEY = "sk-litellm-proxy-local"
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_MAX_RETRIES = 2


class LiteLLMProxyClient(ProviderClient):
    """OpenAI-shaped client pointed at a local LiteLLM Proxy."""

    def __init__(
        self,
        *,
        provider: str | None = None,
        model_prefix: str | None = None,
        base_url: str = DEFAULT_BASE_URL,
        api_key: str = DEFAULT_API_KEY,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        max_retries: int = DEFAULT_MAX_RETRIES,
    ) -> None:
        self.provider = provider
        self.model_prefix = model_prefix
        self.base_url = _normalise_base_url(base_url)
        self._client = OpenAI(
            base_url=self.base_url,
            api_key=api_key,
            timeout=timeout,
            max_retries=max_retries,
        )

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        **opts: Any,
    ) -> Any:
        # Phase C native-only kwargs — silently drop on the proxy path so
        # callers can pass features uniformly. Routing intentionally chooses
        # the native client when these matter.
        for native_only in (
            "enable_prompt_caching",
            "thinking_budget",
            "enable_server_tools",
            "enable_google_search",
            "enable_code_execution",
        ):
            opts.pop(native_only, None)
        tool_specs = [to_openai_tool(tool) for tool in tools] if tools else None
        kwargs: dict[str, Any] = {
            "model": self.normalise_model(model),
            "messages": messages,
        }
        if tool_specs:
            kwargs["tools"] = tool_specs
            kwargs["tool_choice"] = opts.pop("tool_choice", "auto")
        kwargs.update(opts)
        try:
            return self._client.chat.completions.create(**kwargs)
        except (APIConnectionError, APIStatusError, APITimeoutError) as exc:
            raise RuntimeError(f"LiteLLM proxy chat failed for model {model!r}: {exc}") from exc

    def normalise_model(self, model: str) -> str:
        if not self.model_prefix:
            return model
        if model.startswith(self.model_prefix):
            return model
        return f"{self.model_prefix}{model}"

    def extract_text(self, response: Any) -> str:
        message = response.choices[0].message
        return (message.content or "").strip() if message else ""

    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        message = response.choices[0].message
        raw_calls = getattr(message, "tool_calls", None) or []
        calls: list[ToolCall] = []
        for raw_call in raw_calls:
            raw_args = getattr(raw_call.function, "arguments", None) or "{}"
            error: str | None = None
            try:
                parsed = json.loads(raw_args)
                if not isinstance(parsed, dict):
                    error = "tool arguments JSON did not decode to an object"
                    parsed = {}
            except (json.JSONDecodeError, TypeError) as exc:
                error = f"malformed tool arguments JSON: {exc}"
                parsed = {}
            calls.append(
                ToolCall(
                    id=raw_call.id,
                    name=raw_call.function.name,
                    args=parsed,
                    error=error,
                )
            )
        return calls

    def append_assistant(self, messages: list[dict[str, Any]], response: Any) -> None:
        message = response.choices[0].message
        entry: dict[str, Any] = {
            "role": "assistant",
            "content": message.content or "",
        }
        if getattr(message, "tool_calls", None):
            entry["tool_calls"] = [
                {
                    "id": tool_call.id,
                    "type": "function",
                    "function": {
                        "name": tool_call.function.name,
                        "arguments": tool_call.function.arguments,
                    },
                }
                for tool_call in message.tool_calls
            ]
        messages.append(entry)

    def append_tool_result(
        self,
        messages: list[dict[str, Any]],
        call: ToolCall,
        result: str,
    ) -> None:
        messages.append(
            {
                "role": "tool",
                "tool_call_id": call.id,
                "content": str(result),
            }
        )


def _normalise_base_url(base_url: str) -> str:
    cleaned = base_url.rstrip("/")
    if cleaned.endswith("/v1"):
        return cleaned
    return f"{cleaned}/v1"
