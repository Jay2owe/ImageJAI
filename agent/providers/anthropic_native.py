"""Anthropic native provider client.

This bypasses LiteLLM so Claude content blocks and tool-use threading are
preserved. Phase B is non-streaming by design.
"""
from __future__ import annotations

from collections.abc import Callable
from typing import Any

from anthropic import Anthropic, APIConnectionError, APIStatusError, APITimeoutError

from .base import ProviderClient, ToolCall, to_anthropic_tool


DEFAULT_MAX_TOKENS = 4096
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_MAX_RETRIES = 2


class AnthropicNativeClient(ProviderClient):
    """Native Claude Messages API client."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        max_retries: int = DEFAULT_MAX_RETRIES,
    ) -> None:
        kwargs: dict[str, Any] = {"timeout": timeout, "max_retries": max_retries}
        if api_key:
            kwargs["api_key"] = api_key
        self._client = Anthropic(**kwargs)

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        **opts: Any,
    ) -> Any:
        system_prompt, non_system_messages = self._split_system(messages)
        kwargs: dict[str, Any] = {
            "model": model,
            "messages": non_system_messages,
            "max_tokens": opts.pop("max_tokens", DEFAULT_MAX_TOKENS),
        }
        if tools:
            kwargs["tools"] = [to_anthropic_tool(tool) for tool in tools]
        if system_prompt:
            kwargs["system"] = system_prompt
        kwargs.update(opts)
        try:
            return self._client.messages.create(**kwargs)
        except (APIConnectionError, APIStatusError, APITimeoutError) as exc:
            raise RuntimeError(f"Anthropic chat failed for model {model!r}: {exc}") from exc

    @staticmethod
    def _split_system(messages: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
        system_parts: list[str] = []
        rest: list[dict[str, Any]] = []
        for message in messages:
            if message.get("role") == "system":
                content = message.get("content")
                if content:
                    system_parts.append(str(content))
            else:
                rest.append(message)
        return "\n\n".join(system_parts), rest

    def extract_text(self, response: Any) -> str:
        parts: list[str] = []
        for block in response.content or []:
            if _block_get(block, "type") == "text":
                parts.append(str(_block_get(block, "text") or ""))
        return "".join(parts).strip()

    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        calls: list[ToolCall] = []
        for block in response.content or []:
            if _block_get(block, "type") == "tool_use":
                raw_input = _block_get(block, "input") or {}
                calls.append(
                    ToolCall(
                        id=str(_block_get(block, "id")),
                        name=str(_block_get(block, "name")),
                        args=dict(raw_input) if isinstance(raw_input, dict) else {},
                    )
                )
        return calls

    def append_assistant(self, messages: list[dict[str, Any]], response: Any) -> None:
        messages.append(
            {
                "role": "assistant",
                "content": [_block_to_plain_dict(block) for block in (response.content or [])],
            }
        )

    def append_tool_result(
        self,
        messages: list[dict[str, Any]],
        call: ToolCall,
        result: str,
    ) -> None:
        messages.append(
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": call.id,
                        "content": str(result),
                    }
                ],
            }
        )


def _block_get(block: Any, key: str) -> Any:
    if isinstance(block, dict):
        return block.get(key)
    return getattr(block, key, None)


def _block_to_plain_dict(block: Any) -> dict[str, Any]:
    if isinstance(block, dict):
        return dict(block)
    if hasattr(block, "model_dump"):
        return block.model_dump(mode="json", exclude_none=True)

    block_type = getattr(block, "type", None)
    if block_type == "text":
        return {"type": "text", "text": getattr(block, "text", "")}
    if block_type == "tool_use":
        return {
            "type": "tool_use",
            "id": getattr(block, "id", ""),
            "name": getattr(block, "name", ""),
            "input": dict(getattr(block, "input", {}) or {}),
        }
    return {"type": str(block_type or "unknown"), "value": repr(block)}
