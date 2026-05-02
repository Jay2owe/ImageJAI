"""Gemini native provider client.

This bypasses LiteLLM to keep Gemini's function-call parts explicit.
Phase B is non-streaming by design.

Phase C adds opt-in native features beyond the OpenAI translation surface:

- ``enable_google_search`` (default ``False``): attach a
  ``Tool(google_search=GoogleSearch())`` so the model can ground answers in
  Google Search. ``extract_grounding_metadata`` surfaces the citations.
- ``enable_code_execution`` (default ``False``): attach a
  ``Tool(code_execution=ToolCodeExecution())`` so the model can run Python
  inside Google's sandbox. ``executable_code`` and ``code_execution_result``
  parts are exposed by ``extract_code_execution``.
- ``thinking_budget`` (default ``0``): when > 0 forwards
  ``thinking_config=ThinkingConfig(include_thoughts=True, thinking_budget=N)``
  and ``extract_thinking`` returns the thought parts separately so they are
  not surfaced as user-visible assistant text.
"""
from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any

import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types as genai_types

from .base import ProviderClient, ToolCall, to_gemini_tool


# Phase H — Gemini's API does not return the LiteLLM cost header (we bypass
# the proxy on the native path). Convert response.usage_metadata into the same
# "x-litellm-response-cost" shape so the budget tracker observes a uniform
# stream regardless of transport.
GEMINI_PRICING_USD_PER_MTOK: dict[str, dict[str, float]] = {
    "gemini-2.5-pro": {"input": 1.25, "output": 5.0},
    "gemini-2.5-flash": {"input": 0.30, "output": 2.5},
    "gemini-2.5-flash-lite": {"input": 0.10, "output": 0.40},
    "gemini-2.0-flash": {"input": 0.10, "output": 0.40},
}

_COST_LISTENERS: list[Callable[[str], None]] = []


def add_cost_listener(listener: Callable[[str], None]) -> None:
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
        except Exception:
            pass


def _estimate_cost_usd(model: str, response: Any) -> float:
    pricing = GEMINI_PRICING_USD_PER_MTOK.get(model)
    if not pricing:
        return 0.0
    usage = getattr(response, "usage_metadata", None)
    if usage is None:
        return 0.0
    in_tok = int(getattr(usage, "prompt_token_count", 0) or 0)
    out_tok = int(getattr(usage, "candidates_token_count", 0) or 0)
    return (in_tok / 1_000_000.0) * pricing["input"] \
        + (out_tok / 1_000_000.0) * pricing["output"]


class GeminiNativeClient(ProviderClient):
    """Native Gemini client using google-genai."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        max_retries: int = 2,
        retry_backoff: float = 0.25,
    ) -> None:
        self._client = genai.Client(api_key=api_key) if api_key else genai.Client()
        self.max_retries = max_retries
        self.retry_backoff = retry_backoff

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        **opts: Any,
    ) -> Any:
        enable_google_search = bool(opts.pop("enable_google_search", False))
        enable_code_execution = bool(opts.pop("enable_code_execution", False))
        thinking_budget = int(opts.pop("thinking_budget", 0) or 0)

        config_kwargs: dict[str, Any] = {}
        tool_objects: list[Any] = []

        if tools:
            tool_objects.append(
                genai_types.Tool(function_declarations=[to_gemini_tool(tool) for tool in tools])
            )
            config_kwargs["automatic_function_calling"] = (
                genai_types.AutomaticFunctionCallingConfig(disable=True)
            )

        if enable_google_search:
            tool_objects.append(genai_types.Tool(google_search=genai_types.GoogleSearch()))
        if enable_code_execution:
            tool_objects.append(
                genai_types.Tool(code_execution=genai_types.ToolCodeExecution())
            )

        if tool_objects:
            config_kwargs["tools"] = tool_objects

        if thinking_budget > 0:
            config_kwargs["thinking_config"] = genai_types.ThinkingConfig(
                include_thoughts=True,
                thinking_budget=thinking_budget,
            )

        system_instruction = self._extract_system(messages)
        if system_instruction:
            config_kwargs["system_instruction"] = system_instruction

        for key in ("temperature", "max_output_tokens", "top_p", "top_k"):
            if key in opts:
                config_kwargs[key] = opts.pop(key)
        config_kwargs.update(opts)

        config = genai_types.GenerateContentConfig(**config_kwargs) if config_kwargs else None
        for attempt in range(self.max_retries + 1):
            try:
                response = self._client.models.generate_content(
                    model=model,
                    contents=self._messages_to_contents(messages),
                    config=config,
                )
            except (genai_errors.APIError, httpx.TransportError) as exc:
                if attempt >= self.max_retries:
                    raise RuntimeError(f"Gemini chat failed for model {model!r}: {exc}") from exc
                time.sleep(self.retry_backoff * (2**attempt))
                continue
            try:
                _emit_cost(_estimate_cost_usd(model, response))
            except Exception:
                pass
            return response
        raise RuntimeError(f"Gemini chat failed for model {model!r}: retry loop exhausted")

    def extract_text(self, response: Any) -> str:
        sdk_text = getattr(response, "text", None)
        if sdk_text:
            return sdk_text.strip()

        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return ""
        parts = getattr(getattr(candidates[0], "content", None), "parts", None) or []
        chunks: list[str] = []
        for part in parts:
            if getattr(part, "thought", False):
                continue
            text = getattr(part, "text", None)
            if text:
                chunks.append(text)
        return "".join(chunks).strip()

    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return []
        content = getattr(candidates[0], "content", None)
        parts = getattr(content, "parts", None) or []
        calls: list[ToolCall] = []
        for idx, part in enumerate(parts):
            function_call = getattr(part, "function_call", None)
            if function_call is None:
                continue
            calls.append(
                ToolCall(
                    id=f"{function_call.name}:{idx}",
                    name=function_call.name,
                    args=dict(function_call.args or {}),
                )
            )
        return calls

    @staticmethod
    def extract_thinking(response: Any) -> list[str]:
        """Return any ``thought=True`` text parts as plain strings."""
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return []
        parts = getattr(getattr(candidates[0], "content", None), "parts", None) or []
        out: list[str] = []
        for part in parts:
            if getattr(part, "thought", False):
                text = getattr(part, "text", None)
                if text:
                    out.append(text)
        return out

    @staticmethod
    def extract_grounding_metadata(response: Any) -> dict[str, Any] | None:
        """Return ``grounding_metadata`` (web search citations) for the first candidate."""
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return None
        metadata = getattr(candidates[0], "grounding_metadata", None)
        if metadata is None:
            return None
        if hasattr(metadata, "model_dump"):
            return metadata.model_dump(mode="json", exclude_none=True)
        if isinstance(metadata, dict):
            return dict(metadata)
        return {"value": repr(metadata)}

    @staticmethod
    def extract_code_execution(response: Any) -> list[dict[str, Any]]:
        """Return ``executable_code`` + ``code_execution_result`` parts in order."""
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return []
        parts = getattr(getattr(candidates[0], "content", None), "parts", None) or []
        out: list[dict[str, Any]] = []
        for part in parts:
            executable_code = getattr(part, "executable_code", None)
            if executable_code is not None:
                out.append(
                    {
                        "type": "executable_code",
                        "language": getattr(executable_code, "language", None),
                        "code": getattr(executable_code, "code", ""),
                    }
                )
            execution_result = getattr(part, "code_execution_result", None)
            if execution_result is not None:
                out.append(
                    {
                        "type": "code_execution_result",
                        "outcome": str(getattr(execution_result, "outcome", "")),
                        "output": getattr(execution_result, "output", ""),
                    }
                )
        return out

    def append_assistant(self, messages: list[dict[str, Any]], response: Any) -> None:
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            messages.append({"role": "assistant", "content": ""})
            return

        parts = getattr(candidates[0].content, "parts", None) or []
        text_chunks: list[str] = []
        function_calls: list[dict[str, Any]] = []
        thoughts: list[str] = []
        for part in parts:
            if getattr(part, "thought", False):
                text = getattr(part, "text", None)
                if text:
                    thoughts.append(text)
                continue
            function_call = getattr(part, "function_call", None)
            if function_call is not None:
                function_calls.append(
                    {
                        "type": "function_call",
                        "name": function_call.name,
                        "args": dict(function_call.args or {}),
                    }
                )
            elif getattr(part, "text", None):
                text_chunks.append(part.text)

        entry: dict[str, Any] = {"role": "assistant", "content": "".join(text_chunks)}
        if function_calls:
            entry["function_calls"] = function_calls
        if thoughts:
            entry["thinking"] = thoughts
        messages.append(entry)

    def append_tool_result(
        self,
        messages: list[dict[str, Any]],
        call: ToolCall,
        result: str,
    ) -> None:
        messages.append(
            {
                "role": "user",
                "function_response": {
                    "name": call.name,
                    "response": {"result": str(result)},
                },
            }
        )

    @staticmethod
    def _extract_system(messages: list[dict[str, Any]]) -> str:
        parts: list[str] = []
        for message in messages:
            if message.get("role") == "system" and message.get("content"):
                parts.append(str(message["content"]))
        return "\n\n".join(parts)

    def _messages_to_contents(self, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        contents: list[dict[str, Any]] = []
        for message in messages:
            role = message.get("role")
            if role == "system":
                continue

            if role == "user" and "function_response" in message:
                function_response = message["function_response"]
                contents.append(
                    {
                        "role": "user",
                        "parts": [
                            {
                                "function_response": {
                                    "name": function_response["name"],
                                    "response": function_response["response"],
                                }
                            }
                        ],
                    }
                )
                continue

            if role == "assistant" and message.get("function_calls"):
                parts: list[dict[str, Any]] = []
                if message.get("content"):
                    parts.append({"text": str(message["content"])})
                for function_call in message["function_calls"]:
                    parts.append(
                        {
                            "function_call": {
                                "name": function_call["name"],
                                "args": function_call["args"],
                            }
                        }
                    )
                contents.append({"role": "model", "parts": parts})
                continue

            mapped_role = "model" if role == "assistant" else "user"
            contents.append(
                {
                    "role": mapped_role,
                    "parts": [{"text": str(message.get("content", ""))}],
                }
            )
        return contents
