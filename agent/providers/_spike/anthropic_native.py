"""Anthropic native client — uses the anthropic SDK directly.

Why bypass the LiteLLM Proxy: Anthropic's tool-use protocol is built
around content blocks (`text`, `tool_use`, `tool_result`) rather than a
flat string + side-channel `tool_calls` field. Translating through
OpenAI shape loses fidelity (block ids, parallel tool use, structured
content). Going native preserves it.

Protocol shape:
  - Outbound tools=[{"name", "description", "input_schema"}]
  - Inbound:  response.content is a list of blocks. tool_use blocks have
              .id, .name, .input (already a dict, no JSON parsing).
  - Reply:    {"role":"user", "content":[{"type":"tool_result",
                "tool_use_id": id, "content": str}]}
  - Threading: assistant turn appended as
              {"role":"assistant", "content": <raw block list>}.
              The full block list MUST be preserved — Anthropic rejects
              tool_result messages whose tool_use_id is not present in
              the immediately-prior assistant message.

System prompt: Anthropic does NOT accept role=system inside messages.
We split it out and pass via the top-level `system=` param.
"""
from __future__ import annotations

from typing import Any, Callable

from anthropic import Anthropic

from base import ProviderClient, ToolCall, to_anthropic_tool


DEFAULT_MAX_TOKENS = 4096


class AnthropicNativeClient(ProviderClient):
    """Native Claude client. `model` strings: claude-opus-4-7, claude-sonnet-4-6, etc."""

    def __init__(self, api_key: str | None = None):
        # SDK reads ANTHROPIC_API_KEY from env if api_key is None.
        self._client = Anthropic(api_key=api_key) if api_key else Anthropic()

    def chat(self, messages: list, tools: list[Callable],
             model: str, **opts) -> Any:
        tool_specs = [to_anthropic_tool(t) for t in tools] if tools else None
        system_prompt, msgs = self._split_system(messages)

        kwargs: dict[str, Any] = {
            "model": model,
            "messages": msgs,
            "max_tokens": opts.pop("max_tokens", DEFAULT_MAX_TOKENS),
        }
        if system_prompt:
            kwargs["system"] = system_prompt
        if tool_specs:
            kwargs["tools"] = tool_specs
        kwargs.update(opts)
        return self._client.messages.create(**kwargs)

    @staticmethod
    def _split_system(messages: list) -> tuple[str, list]:
        """Pop role=system entries (joining if multiple) and return (system, rest)."""
        sys_parts: list[str] = []
        rest: list = []
        for m in messages:
            role = m.get("role") if isinstance(m, dict) else getattr(m, "role", None)
            if role == "system":
                content = m.get("content") if isinstance(m, dict) else getattr(m, "content", "")
                if content:
                    sys_parts.append(str(content))
            else:
                rest.append(m)
        return ("\n\n".join(sys_parts), rest)

    # ── extraction ──
    def extract_text(self, response: Any) -> str:
        # Concatenate every text block in order. Tool_use blocks are skipped.
        out_parts: list[str] = []
        for block in (response.content or []):
            block_type = getattr(block, "type", None)
            if block_type == "text":
                out_parts.append(getattr(block, "text", "") or "")
        return "".join(out_parts).strip()

    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        out: list[ToolCall] = []
        for block in (response.content or []):
            if getattr(block, "type", None) == "tool_use":
                out.append(ToolCall(
                    id=block.id,
                    name=block.name,
                    args=dict(block.input or {}),
                ))
        return out

    # ── threading ──
    def append_assistant(self, messages: list, response: Any) -> None:
        # Re-serialise content blocks as plain dicts. The Anthropic SDK
        # accepts BaseModel objects on the next call but mixing them with
        # dicts in our message list leads to serialisation errors elsewhere
        # (e.g. when ollama_chat.py tries to estimate tokens by reading
        # m["content"] as a string). Plain dicts are the lowest common
        # denominator.
        content_blocks: list[dict] = []
        for block in (response.content or []):
            btype = getattr(block, "type", None)
            if btype == "text":
                content_blocks.append({"type": "text", "text": block.text})
            elif btype == "tool_use":
                content_blocks.append({
                    "type": "tool_use",
                    "id": block.id,
                    "name": block.name,
                    "input": dict(block.input or {}),
                })
            # Other block types (e.g. thinking) are dropped by the spike;
            # extend here if/when we enable them.
        messages.append({"role": "assistant", "content": content_blocks})

    def append_tool_result(self, messages: list, call: ToolCall,
                           result: str) -> None:
        # Anthropic threads tool results through a role=user message
        # carrying a tool_result content block. Note: id field is
        # `tool_use_id`, NOT `tool_call_id` (that's OpenAI's field).
        messages.append({
            "role": "user",
            "content": [{
                "type": "tool_result",
                "tool_use_id": call.id,
                "content": str(result),
            }],
        })
