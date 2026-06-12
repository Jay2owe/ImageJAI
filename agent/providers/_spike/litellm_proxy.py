"""LiteLLM Proxy client — uses the openai SDK pointed at localhost:4000.

This is the catch-all path for OpenAI-compatible providers. The proxy
holds API keys, picks a backend based on the `model` string, and exposes
the standard /v1/chat/completions surface. From our side it is just an
OpenAI client with a different base_url.

Tool calls in this dialect:
  - Outbound: tools=[{"type":"function", "function": {...}}]
  - Inbound:  message.tool_calls[i].id, .function.name, .function.arguments (JSON STRING)
  - Reply:    {"role":"tool", "tool_call_id": id, "content": result}
  - Threading: append the assistant message verbatim before the tool replies.
"""
from __future__ import annotations

import json
from typing import Any, Callable

from openai import OpenAI

from base import ProviderClient, ToolCall, to_openai_tool


DEFAULT_BASE_URL = "http://localhost:4000"
DEFAULT_API_KEY = "sk-litellm-proxy-local"  # proxy ignores it; SDK requires non-empty


class LiteLLMProxyClient(ProviderClient):
    """OpenAI-shaped client pointed at a local LiteLLM Proxy."""

    def __init__(self, base_url: str = DEFAULT_BASE_URL,
                 api_key: str = DEFAULT_API_KEY):
        self._client = OpenAI(base_url=base_url, api_key=api_key)

    def chat(self, messages: list, tools: list[Callable],
             model: str, **opts) -> Any:
        tool_specs = [to_openai_tool(t) for t in tools] if tools else None
        kwargs: dict[str, Any] = {"model": model, "messages": messages}
        if tool_specs:
            kwargs["tools"] = tool_specs
            kwargs["tool_choice"] = opts.pop("tool_choice", "auto")
        kwargs.update(opts)
        return self._client.chat.completions.create(**kwargs)

    # ── extraction ──
    def extract_text(self, response: Any) -> str:
        msg = response.choices[0].message
        return (msg.content or "").strip() if msg else ""

    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        msg = response.choices[0].message
        raw_calls = getattr(msg, "tool_calls", None) or []
        out: list[ToolCall] = []
        for tc in raw_calls:
            # OpenAI delivers arguments as a JSON-encoded string.
            try:
                args = json.loads(tc.function.arguments or "{}")
            except (json.JSONDecodeError, TypeError):
                args = {}
            out.append(ToolCall(id=tc.id, name=tc.function.name, args=args))
        return out

    # ── threading ──
    def append_assistant(self, messages: list, response: Any) -> None:
        # Re-serialise the assistant message into the dict shape the next
        # call expects. We can't append the SDK object — the next request
        # serialises through pydantic and rejects unknown fields.
        msg = response.choices[0].message
        entry: dict[str, Any] = {"role": "assistant", "content": msg.content or ""}
        if getattr(msg, "tool_calls", None):
            entry["tool_calls"] = [
                {
                    "id": tc.id,
                    "type": "function",
                    "function": {
                        "name": tc.function.name,
                        "arguments": tc.function.arguments,
                    },
                }
                for tc in msg.tool_calls
            ]
        messages.append(entry)

    def append_tool_result(self, messages: list, call: ToolCall,
                           result: str) -> None:
        messages.append({
            "role": "tool",
            "tool_call_id": call.id,
            "content": str(result),
        })
