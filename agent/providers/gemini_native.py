"""Gemini native provider client.

This bypasses LiteLLM to keep Gemini's function-call parts explicit.
Phase B is non-streaming by design.
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
        config_kwargs: dict[str, Any] = {}
        if tools:
            config_kwargs["tools"] = [
                genai_types.Tool(function_declarations=[to_gemini_tool(tool) for tool in tools])
            ]
            config_kwargs["automatic_function_calling"] = (
                genai_types.AutomaticFunctionCallingConfig(disable=True)
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
                return self._client.models.generate_content(
                    model=model,
                    contents=self._messages_to_contents(messages),
                    config=config,
                )
            except (genai_errors.APIError, httpx.TransportError) as exc:
                if attempt >= self.max_retries:
                    raise RuntimeError(f"Gemini chat failed for model {model!r}: {exc}") from exc
                time.sleep(self.retry_backoff * (2**attempt))
        raise RuntimeError(f"Gemini chat failed for model {model!r}: retry loop exhausted")

    def extract_text(self, response: Any) -> str:
        return (getattr(response, "text", "") or "").strip()

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

    def append_assistant(self, messages: list[dict[str, Any]], response: Any) -> None:
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            messages.append({"role": "assistant", "content": ""})
            return

        parts = getattr(candidates[0].content, "parts", None) or []
        text_chunks: list[str] = []
        function_calls: list[dict[str, Any]] = []
        for part in parts:
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
