"""Gemini native client — uses google-genai SDK directly.

Why bypass the LiteLLM Proxy: Gemini's function-calling protocol uses
`Part.function_call` / `Part.function_response` parts and does not have
stable tool-call ids the way OpenAI does — calls are matched by name +
position. The SDK enforces invariants the OpenAI translation can lose.

Protocol shape:
  - Outbound: tools=[Tool(function_declarations=[{...}])]
  - Inbound:  response.candidates[0].content.parts — each Part may carry
              a `.function_call` (FunctionCall) with .name and .args (dict)
              OR `.text`.
  - Reply:    Content(role='user', parts=[Part(function_response=
                FunctionResponse(name=..., response={'result': ...}))])
  - Threading: append the assistant Content (or its dict equivalent) to
              the running history.

Note on ids: Gemini does not return a tool-call id. We invent one
locally (name + index) so the ProviderClient interface stays uniform —
when threading the result back, we ignore call.id and key by name +
position, which is what the SDK expects.

History format: google-genai accepts dict form for `contents=`. We use
that throughout to stay consistent with the OpenAI/Anthropic clients.
"""
from __future__ import annotations

from typing import Any, Callable

from google import genai
from google.genai import types as genai_types

from base import ProviderClient, ToolCall, to_gemini_tool


class GeminiNativeClient(ProviderClient):
    """Native Gemini client. `model` strings: gemini-2.5-pro, gemini-2.5-flash, etc."""

    def __init__(self, api_key: str | None = None):
        # SDK reads GOOGLE_API_KEY / GEMINI_API_KEY from env if None.
        self._client = genai.Client(api_key=api_key) if api_key else genai.Client()

    def chat(self, messages: list, tools: list[Callable],
             model: str, **opts) -> Any:
        contents = self._messages_to_contents(messages)
        cfg_kwargs: dict[str, Any] = {}
        if tools:
            cfg_kwargs["tools"] = [
                genai_types.Tool(function_declarations=[to_gemini_tool(t) for t in tools])
            ]
            # The SDK has an "automatic function calling" path that runs
            # callables for you. We disable it because we want the loop
            # in ollama_chat.py to stay in charge of dispatch.
            cfg_kwargs["automatic_function_calling"] = (
                genai_types.AutomaticFunctionCallingConfig(disable=True)
            )

        system_text = self._extract_system(messages)
        if system_text:
            cfg_kwargs["system_instruction"] = system_text

        # Allow callers to pass through generation knobs (temperature,
        # max_output_tokens, etc) under the same dict.
        for k in ("temperature", "max_output_tokens", "top_p", "top_k"):
            if k in opts:
                cfg_kwargs[k] = opts.pop(k)

        config = genai_types.GenerateContentConfig(**cfg_kwargs) if cfg_kwargs else None
        return self._client.models.generate_content(
            model=model, contents=contents, config=config,
        )

    # ── extraction ──
    def extract_text(self, response: Any) -> str:
        # The SDK exposes a `.text` convenience that concatenates all
        # text parts and returns "" if the response was function-calls
        # only.
        return (getattr(response, "text", "") or "").strip()

    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        out: list[ToolCall] = []
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return out
        parts = getattr(candidates[0].content, "parts", None) or []
        for idx, part in enumerate(parts):
            fc = getattr(part, "function_call", None)
            if fc is None:
                continue
            out.append(ToolCall(
                id=f"{fc.name}:{idx}",   # synthetic; Gemini has no native id
                name=fc.name,
                args=dict(fc.args or {}),
            ))
        return out

    # ── threading ──
    def append_assistant(self, messages: list, response: Any) -> None:
        # We store the assistant turn in our normalised dict shape: role
        # 'assistant' with either text content or a list of part dicts
        # describing each function_call. _messages_to_contents on the
        # next call rehydrates these into Gemini types.
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            messages.append({"role": "assistant", "content": ""})
            return
        parts = getattr(candidates[0].content, "parts", None) or []

        text_chunks: list[str] = []
        fcalls: list[dict] = []
        for part in parts:
            if getattr(part, "function_call", None) is not None:
                fc = part.function_call
                fcalls.append({
                    "type": "function_call",
                    "name": fc.name,
                    "args": dict(fc.args or {}),
                })
            elif getattr(part, "text", None):
                text_chunks.append(part.text)

        if fcalls:
            entry: dict[str, Any] = {"role": "assistant", "content": "".join(text_chunks)}
            entry["function_calls"] = fcalls
            messages.append(entry)
        else:
            messages.append({"role": "assistant", "content": "".join(text_chunks)})

    def append_tool_result(self, messages: list, call: ToolCall,
                           result: str) -> None:
        # Tool results in Gemini are a USER turn carrying a
        # function_response part. Match by name; the SDK ignores any id.
        messages.append({
            "role": "user",
            "function_response": {
                "name": call.name,
                "response": {"result": str(result)},
            },
        })

    # ── helpers ──
    @staticmethod
    def _extract_system(messages: list) -> str:
        parts: list[str] = []
        for m in messages:
            if isinstance(m, dict) and m.get("role") == "system":
                content = m.get("content", "")
                if content:
                    parts.append(str(content))
        return "\n\n".join(parts)

    def _messages_to_contents(self, messages: list) -> list:
        """Convert our normalised dict messages into Gemini Content list.

        Gemini uses 'user' and 'model' roles (not 'assistant'). System
        prompts are pulled out separately and passed via
        system_instruction; we drop them here.
        """
        out: list[dict] = []
        for m in messages:
            if not isinstance(m, dict):
                continue
            role = m.get("role")
            if role == "system":
                continue

            # Tool-result turn.
            if role == "user" and "function_response" in m:
                fr = m["function_response"]
                out.append({
                    "role": "user",
                    "parts": [{
                        "function_response": {
                            "name": fr["name"],
                            "response": fr["response"],
                        },
                    }],
                })
                continue

            # Assistant turn carrying function calls.
            if role == "assistant" and m.get("function_calls"):
                parts: list[dict] = []
                if m.get("content"):
                    parts.append({"text": m["content"]})
                for fc in m["function_calls"]:
                    parts.append({
                        "function_call": {"name": fc["name"], "args": fc["args"]},
                    })
                out.append({"role": "model", "parts": parts})
                continue

            # Plain text turn.
            content = m.get("content", "")
            mapped_role = "model" if role == "assistant" else "user"
            out.append({"role": mapped_role, "parts": [{"text": str(content) if content else ""}]})
        return out
