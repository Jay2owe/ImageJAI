"""Common provider interface and tool-schema conversion.

The production provider paths are intentionally non-streaming for Phase B.
Streaming tool calls require event assembly and are deferred by the
multi-provider plan.
"""
from __future__ import annotations

import inspect
import re
import types
import typing
from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal


@dataclass(frozen=True)
class ToolCall:
    """One model-issued tool invocation, normalised across providers."""

    id: str
    name: str
    args: dict[str, Any]
    error: str | None = None


class ProviderClient(ABC):
    """Five-method interface used by the ImageJAI tool loop."""

    @abstractmethod
    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        **opts: Any,
    ) -> Any:
        """Issue one non-streaming chat completion."""

    @abstractmethod
    def extract_text(self, response: Any) -> str:
        """Return final answer text, or an empty string for tool-call turns."""

    @abstractmethod
    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        """Return parsed tool calls, or an empty list for final-answer turns."""

    @abstractmethod
    def append_assistant(self, messages: list[dict[str, Any]], response: Any) -> None:
        """Append the model turn in the provider's required history shape."""

    @abstractmethod
    def append_tool_result(
        self,
        messages: list[dict[str, Any]],
        call: ToolCall,
        result: str,
    ) -> None:
        """Append a tool result keyed to the given normalised tool call."""


_PRIMITIVE_TO_JSON: dict[type, str] = {
    str: "string",
    int: "integer",
    float: "number",
    bool: "boolean",
}

_ARG_DOC_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+?)\s*$")


def _annotation_to_jsonschema(annotation: Any) -> dict[str, Any]:
    if annotation is inspect.Parameter.empty:
        return {"type": "string"}

    origin = typing.get_origin(annotation)
    args = typing.get_args(annotation)

    if origin in (typing.Union, types.UnionType):
        non_none = [arg for arg in args if arg is not type(None)]
        if len(non_none) == 1:
            return _annotation_to_jsonschema(non_none[0])
        return {"anyOf": [_annotation_to_jsonschema(arg) for arg in non_none]}

    if origin in (list, typing.List):
        item_type = args[0] if args else str
        return {"type": "array", "items": _annotation_to_jsonschema(item_type)}

    if origin in (dict, typing.Dict):
        return {"type": "object"}

    if origin is Literal:
        values = list(args)
        schema_type = _literal_schema_type(values)
        schema: dict[str, Any] = {"enum": values}
        if schema_type:
            schema["type"] = schema_type
        return schema

    if isinstance(annotation, type) and annotation in _PRIMITIVE_TO_JSON:
        return {"type": _PRIMITIVE_TO_JSON[annotation]}

    return {"type": "string"}


def _literal_schema_type(values: list[Any]) -> str | None:
    if not values:
        return None
    value_types = {type(value) for value in values}
    if len(value_types) != 1:
        return None
    return _PRIMITIVE_TO_JSON.get(next(iter(value_types)))


def _parse_arg_docs(docstring: str) -> dict[str, str]:
    out: dict[str, str] = {}
    in_args = False
    for line in docstring.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        lowered = stripped.lower()
        if lowered.startswith(("args:", "arguments:", "parameters:")):
            in_args = True
            continue
        if lowered.startswith(("returns:", "return:", "raises:", "example", "examples:")):
            in_args = False
            continue
        if in_args:
            match = _ARG_DOC_RE.match(stripped)
            if match:
                out[match.group(1)] = match.group(2)
    return out


def _summary_line(docstring: str) -> str:
    for line in docstring.splitlines():
        stripped = line.strip()
        if stripped:
            return stripped
    return ""


def fn_to_json_schema(fn: Callable[..., Any]) -> dict[str, Any]:
    """Build a provider-neutral JSON-Schema fragment for a Python callable."""

    signature = inspect.signature(fn)
    arg_docs = _parse_arg_docs(fn.__doc__ or "")
    try:
        resolved_hints = typing.get_type_hints(fn)
    except Exception:
        resolved_hints = {}

    properties: dict[str, dict[str, Any]] = {}
    required: list[str] = []
    for name, parameter in signature.parameters.items():
        if name in {"self", "cls"}:
            continue
        annotation = resolved_hints.get(name, parameter.annotation)
        prop = _annotation_to_jsonschema(annotation)
        if name in arg_docs:
            prop["description"] = arg_docs[name]
        properties[name] = prop
        if parameter.default is inspect.Parameter.empty:
            required.append(name)

    return {
        "name": fn.__name__,
        "description": _summary_line(fn.__doc__ or "") or fn.__name__,
        "schema": {
            "type": "object",
            "properties": properties,
            "required": required,
        },
    }


def to_openai_tool(fn: Callable[..., Any]) -> dict[str, Any]:
    spec = fn_to_json_schema(fn)
    return {
        "type": "function",
        "function": {
            "name": spec["name"],
            "description": spec["description"],
            "parameters": spec["schema"],
        },
    }


def to_anthropic_tool(fn: Callable[..., Any]) -> dict[str, Any]:
    spec = fn_to_json_schema(fn)
    return {
        "name": spec["name"],
        "description": spec["description"],
        "input_schema": spec["schema"],
    }


def to_gemini_tool(fn: Callable[..., Any]) -> dict[str, Any]:
    spec = fn_to_json_schema(fn)
    declaration: dict[str, Any] = {
        "name": spec["name"],
        "description": spec["description"],
    }
    if spec["schema"]["properties"]:
        declaration["parameters"] = spec["schema"]
    return declaration
