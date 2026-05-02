"""Anthropic native provider client.

This bypasses LiteLLM so Claude content blocks and tool-use threading are
preserved. Phase B is non-streaming by design.

Phase C adds opt-in native features beyond the OpenAI translation surface:

- ``enable_prompt_caching`` (default ``True``): mark the last system message
  and the last tool definition with ``cache_control: {"type": "ephemeral"}``
  so the prefix is reused for ~90% input savings on subsequent calls.
- ``thinking_budget`` (default ``0``): when > 0 forwards
  ``thinking={"type": "enabled", "budget_tokens": N}`` and the assistant
  ``thinking`` blocks are threaded back automatically by ``append_assistant``.
- ``enable_server_tools`` (default ``None``): list of native server tool
  names to enable alongside user tools. Supported: ``"web_search"``,
  ``"code_execution"``.
- Citation blocks returned by server tools are surfaced via
  ``extract_citations`` and the assistant content blocks are preserved
  verbatim (so server-tool ``tool_use`` IDs round-trip on the next turn).
"""
from __future__ import annotations

from collections.abc import Callable
from typing import Any

from anthropic import Anthropic, APIConnectionError, APIStatusError, APITimeoutError

from .base import ProviderClient, ToolCall, to_anthropic_tool


DEFAULT_MAX_TOKENS = 4096
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_MAX_RETRIES = 2

_SERVER_TOOL_SPECS: dict[str, dict[str, Any]] = {
    "web_search": {"type": "web_search_20250305", "name": "web_search"},
    "code_execution": {"type": "code_execution_20250522", "name": "code_execution"},
}

_SERVER_TOOL_BETAS: dict[str, str] = {
    "code_execution": "code-execution-2025-05-22",
}


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
        enable_prompt_caching = bool(opts.pop("enable_prompt_caching", True))
        thinking_budget = int(opts.pop("thinking_budget", 0) or 0)
        enable_server_tools = opts.pop("enable_server_tools", None) or []
        if isinstance(enable_server_tools, str):
            enable_server_tools = [enable_server_tools]
        enable_server_tools = [str(name).strip() for name in enable_server_tools if name]

        system_prompt, non_system_messages = self._split_system(messages)
        kwargs: dict[str, Any] = {
            "model": model,
            "messages": non_system_messages,
            "max_tokens": opts.pop("max_tokens", DEFAULT_MAX_TOKENS),
        }

        tool_specs: list[dict[str, Any]] = (
            [to_anthropic_tool(tool) for tool in tools] if tools else []
        )
        for name in enable_server_tools:
            spec = _SERVER_TOOL_SPECS.get(name)
            if spec is None:
                raise ValueError(
                    f"unknown Anthropic server tool {name!r}; "
                    f"supported: {sorted(_SERVER_TOOL_SPECS)}"
                )
            tool_specs.append(dict(spec))

        if enable_prompt_caching and tool_specs:
            self._mark_cache_control(tool_specs[-1])
        if tool_specs:
            kwargs["tools"] = tool_specs

        if system_prompt:
            kwargs["system"] = self._build_system(system_prompt, enable_prompt_caching)

        if thinking_budget > 0:
            kwargs["thinking"] = {"type": "enabled", "budget_tokens": thinking_budget}

        beta_headers = sorted(
            {_SERVER_TOOL_BETAS[name] for name in enable_server_tools if name in _SERVER_TOOL_BETAS}
        )
        if beta_headers:
            extra_headers = dict(opts.pop("extra_headers", None) or {})
            existing = extra_headers.get("anthropic-beta", "")
            merged = ",".join(filter(None, [existing] + beta_headers))
            extra_headers["anthropic-beta"] = merged
            kwargs["extra_headers"] = extra_headers

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

    @staticmethod
    def _build_system(system_prompt: str, enable_prompt_caching: bool) -> Any:
        if not enable_prompt_caching:
            return system_prompt
        return [
            {
                "type": "text",
                "text": system_prompt,
                "cache_control": {"type": "ephemeral"},
            }
        ]

    @staticmethod
    def _mark_cache_control(tool_spec: dict[str, Any]) -> None:
        tool_spec["cache_control"] = {"type": "ephemeral"}

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

    @staticmethod
    def extract_thinking(response: Any) -> list[dict[str, Any]]:
        """Return any extended-thinking blocks as plain dicts."""
        out: list[dict[str, Any]] = []
        for block in getattr(response, "content", None) or []:
            if _block_get(block, "type") == "thinking":
                out.append(
                    {
                        "type": "thinking",
                        "thinking": _block_get(block, "thinking") or "",
                        "signature": _block_get(block, "signature") or "",
                    }
                )
        return out

    @staticmethod
    def extract_citations(response: Any) -> list[dict[str, Any]]:
        """Return citation blocks attached to text content (server-tool grounded answers)."""
        out: list[dict[str, Any]] = []
        for block in getattr(response, "content", None) or []:
            if _block_get(block, "type") != "text":
                continue
            citations = _block_get(block, "citations")
            if not citations:
                continue
            for citation in citations:
                if isinstance(citation, dict):
                    out.append(dict(citation))
                elif hasattr(citation, "model_dump"):
                    out.append(citation.model_dump(mode="json", exclude_none=True))
                else:
                    out.append({"value": repr(citation)})
        return out

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
    if block_type == "thinking":
        return {
            "type": "thinking",
            "thinking": getattr(block, "thinking", ""),
            "signature": getattr(block, "signature", ""),
        }
    return {"type": str(block_type or "unknown"), "value": repr(block)}
