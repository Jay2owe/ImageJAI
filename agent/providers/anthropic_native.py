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

# Phase H — Anthropic's Messages API does not return the LiteLLM cost header
# because we bypass the proxy. Convert the response usage block into the same
# "x-litellm-response-cost" shape (USD as a string) so the budget tracker
# observes a uniform stream regardless of transport.
ANTHROPIC_PRICING_USD_PER_MTOK: dict[str, dict[str, float]] = {
    "claude-opus-4-7": {"input": 15.0, "output": 75.0},
    "claude-sonnet-4-6": {"input": 3.0, "output": 15.0},
    "claude-haiku-4-5": {"input": 0.80, "output": 4.0},
}

_COST_LISTENERS: list[Callable[[str], None]] = []


def add_cost_listener(listener: Callable[[str], None]) -> None:
    """Register a function to receive cost values for every Anthropic call."""

    if listener and listener not in _COST_LISTENERS:
        _COST_LISTENERS.append(listener)


def remove_cost_listener(listener: Callable[[str], None]) -> None:
    if listener in _COST_LISTENERS:
        _COST_LISTENERS.remove(listener)


def _emit_cost(value: float) -> None:
    if value <= 0:
        return
    payload = f"{value:.6f}"
    for listener in list(_COST_LISTENERS):
        try:
            listener(payload)
        except Exception:  # listeners must never break the call path
            pass


def _estimate_cost_usd(model: str, response: Any) -> float:
    pricing = ANTHROPIC_PRICING_USD_PER_MTOK.get(model)
    if not pricing:
        return 0.0
    usage = getattr(response, "usage", None)
    if usage is None:
        return 0.0
    in_tok = int(getattr(usage, "input_tokens", 0) or 0)
    out_tok = int(getattr(usage, "output_tokens", 0) or 0)
    cache_read = int(getattr(usage, "cache_read_input_tokens", 0) or 0)
    cache_write = int(getattr(usage, "cache_creation_input_tokens", 0) or 0)
    # Treat cache reads at 10% of input rate (Anthropic ephemeral caching);
    # cache writes at the standard input rate per Anthropic's billing docs.
    billable_in = in_tok + cache_write + (cache_read * 0.1)
    return (billable_in / 1_000_000.0) * pricing["input"] \
        + (out_tok / 1_000_000.0) * pricing["output"]

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
            response = self._client.messages.create(**kwargs)
        except (APIConnectionError, APIStatusError, APITimeoutError) as exc:
            raise RuntimeError(f"Anthropic chat failed for model {model!r}: {exc}") from exc
        try:
            _emit_cost(_estimate_cost_usd(model, response))
        except Exception:
            pass  # cost listeners must never block a successful call.
        return response

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
