"""Abstract ProviderClient + cross-provider tool-schema converter.

The existing ollama_chat.py tool loop is built around four primitives:

  1. Send (messages, tools, model) and get a response back.
  2. Decide whether the response is a final answer or a tool-call.
  3. Append the assistant response to the running message list.
  4. Append a tool-result message keyed to the right call id.

Everything else (which provider, which SDK shape, how schemas are built)
varies between Anthropic / Gemini / OpenAI-compatible. This module gives
those four primitives one shape so the loop can stay provider-agnostic.
"""
from __future__ import annotations

import inspect
import re
import typing
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Literal


@dataclass
class ToolCall:
    """One model-issued tool invocation, normalised across providers."""

    id: str            # provider-supplied call id; used for result threading
    name: str          # tool function name
    args: dict         # parsed kwargs ready for TOOL_MAP[name](**args)


class ProviderClient(ABC):
    """Thin interface every provider path implements.

    Implementations must be stateless across calls — the message list
    passed in IS the conversation state. Everything provider-specific
    (auth, base_url, model coercions) lives inside .chat().
    """

    @abstractmethod
    def chat(self, messages: list, tools: list[Callable],
             model: str, **opts) -> Any:
        """Issue one chat completion. Returns the raw provider response."""

    @abstractmethod
    def extract_text(self, response: Any) -> str:
        """Final answer text, or '' if the response was tool-calls only."""

    @abstractmethod
    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        """Parsed tool calls, or [] if the response was a final answer."""

    @abstractmethod
    def append_assistant(self, messages: list, response: Any) -> None:
        """Append the model's reply to messages so the next turn sees it."""

    @abstractmethod
    def append_tool_result(self, messages: list, call: ToolCall,
                           result: str) -> None:
        """Append a tool execution result keyed to `call.id`."""


# ── Schema conversion ────────────────────────────────────────────────────
#
# Each provider wants tool definitions in a different shape:
#   - OpenAI / LiteLLM:  {"type":"function", "function":{"name", "description", "parameters": <JSON Schema>}}
#   - Anthropic:         {"name", "description", "input_schema": <JSON Schema>}
#   - Gemini:            {"name", "description", "parameters": <JSON Schema (subset)>}
#
# All three accept a JSON Schema for the parameter object, so we build
# *one* schema per Python callable and wrap it three different ways.

_PRIMITIVE_TO_JSON: dict[type, str] = {
    str: "string",
    int: "integer",
    float: "number",
    bool: "boolean",
}


def _annotation_to_jsonschema(ann: Any) -> dict[str, Any]:
    """Map a Python annotation to a JSON Schema fragment.

    Supports str/int/float/bool/list/dict and `X | None` / Optional[X].
    Anything we can't classify falls back to {"type":"string"} — matching
    the ollama client's behaviour for un-annotated params.
    """
    if ann is inspect.Parameter.empty:
        return {"type": "string"}

    origin = typing.get_origin(ann)
    args = typing.get_args(ann)

    # Optional[X] / X | None — strip the None and recurse.
    if origin in (typing.Union, type(None).__class__) or (origin is None and args):
        non_none = [a for a in args if a is not type(None)]
        if len(non_none) == 1:
            return _annotation_to_jsonschema(non_none[0])

    if origin in (list, typing.List):
        item = args[0] if args else str
        return {"type": "array", "items": _annotation_to_jsonschema(item)}

    if origin in (dict, typing.Dict):
        return {"type": "object"}

    if isinstance(ann, type) and ann in _PRIMITIVE_TO_JSON:
        return {"type": _PRIMITIVE_TO_JSON[ann]}

    return {"type": "string"}


_ARG_DOC_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+?)\s*$")


def _parse_arg_docs(docstring: str) -> dict[str, str]:
    """Extract per-arg descriptions from a Google/RST-ish 'Args:' block.

    Cheap and forgiving — used purely to attach `description` fields to
    JSON-schema properties. Anything we miss falls through to no
    description, which is fine.
    """
    out: dict[str, str] = {}
    if not docstring:
        return out
    in_args = False
    for line in docstring.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        low = stripped.lower()
        if low.startswith(("args:", "arguments:", "parameters:")):
            in_args = True
            continue
        if low.startswith(("returns:", "return:", "raises:", "example", "examples:")):
            in_args = False
            continue
        if in_args:
            m = _ARG_DOC_RE.match(stripped)
            if m:
                out[m.group(1)] = m.group(2)
    return out


def _summary_line(docstring: str) -> str:
    """First non-empty line of a docstring — what we use as `description`."""
    if not docstring:
        return ""
    for line in docstring.splitlines():
        s = line.strip()
        if s:
            return s
    return ""


def fn_to_json_schema(fn: Callable) -> dict[str, Any]:
    """Build the parameters JSON Schema for one Python callable.

    Output shape:
        {
          "name": "fn_name",
          "description": "first docstring line",
          "schema": {"type":"object", "properties": {...}, "required": [...]}
        }

    Wrap with `to_openai_tool` / `to_anthropic_tool` / `to_gemini_tool`
    for the three target shapes.
    """
    sig = inspect.signature(fn)
    arg_docs = _parse_arg_docs(fn.__doc__ or "")
    # Resolve annotations including PEP-563 stringified forms (e.g. when
    # the defining module uses `from __future__ import annotations`).
    try:
        resolved = typing.get_type_hints(fn)
    except Exception:
        resolved = {}

    properties: dict[str, dict] = {}
    required: list[str] = []
    for pname, param in sig.parameters.items():
        if pname in ("self", "cls"):
            continue
        ann = resolved.get(pname, param.annotation)
        prop = _annotation_to_jsonschema(ann)
        if pname in arg_docs:
            prop["description"] = arg_docs[pname]
        properties[pname] = prop
        if param.default is inspect.Parameter.empty:
            required.append(pname)

    return {
        "name": fn.__name__,
        "description": _summary_line(fn.__doc__ or "") or fn.__name__,
        "schema": {
            "type": "object",
            "properties": properties,
            "required": required,
        },
    }


def to_openai_tool(fn: Callable) -> dict[str, Any]:
    """OpenAI / LiteLLM-Proxy `tools=[...]` element."""
    spec = fn_to_json_schema(fn)
    return {
        "type": "function",
        "function": {
            "name": spec["name"],
            "description": spec["description"],
            "parameters": spec["schema"],
        },
    }


def to_anthropic_tool(fn: Callable) -> dict[str, Any]:
    """Anthropic Messages API `tools=[...]` element."""
    spec = fn_to_json_schema(fn)
    return {
        "name": spec["name"],
        "description": spec["description"],
        "input_schema": spec["schema"],
    }


def to_gemini_tool(fn: Callable) -> dict[str, Any]:
    """Gemini function declaration. Wrap as Tool(function_declarations=[...]) at call time.

    Gemini's schema validator is stricter than OpenAI's:
      - It rejects empty `properties: {}` for object schemas, so we omit
        `parameters` entirely when the function takes no arguments.
      - It does not accept `additionalProperties`.
    """
    spec = fn_to_json_schema(fn)
    decl: dict[str, Any] = {
        "name": spec["name"],
        "description": spec["description"],
    }
    if spec["schema"]["properties"]:
        decl["parameters"] = spec["schema"]
    return decl


__all__ = [
    "ProviderClient",
    "ToolCall",
    "fn_to_json_schema",
    "to_openai_tool",
    "to_anthropic_tool",
    "to_gemini_tool",
]
