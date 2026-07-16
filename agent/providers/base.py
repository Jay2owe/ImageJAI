"""Common provider interface and tool-schema conversion.

The production provider paths are intentionally non-streaming for Phase B.
Streaming tool calls require event assembly and are deferred by the
multi-provider plan.
"""
from __future__ import annotations

import base64
import io
import inspect
import re
import types
import typing
from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

from PIL import Image


HOST_CODE_CAPABILITY = "host_code"
MAX_VISION_EDGE = 896
MAX_VISION_BYTES = 500 * 1024

# The runtime registry predates provider-native schemas.  These are contract
# normalisations, not alternate tool implementations: the names remain the
# exact decorated Python names and the loop supplies the same defaults when a
# model omits an optional argument.
TOOL_ARGUMENT_DEFAULTS: dict[str, dict[str, Any]] = {
    "close_dialogs": {"pattern": ""},
    "capture_image": {"max_size": 1024},
}

_BARE_LIST_ITEM_SCHEMAS: dict[tuple[str, str], dict[str, Any]] = {
    ("get_pixels_array", "region"): {"type": "integer"},
    ("open_lif_series", "indices"): {"type": "integer"},
    ("save_recipe", "promote"): {"type": "string"},
}


@dataclass(frozen=True)
class ProviderToolPolicy:
    """Trusted provider classification and explicitly granted capabilities.

    ``is_local`` is assigned by the provider router, never inferred from an
    endpoint URL supplied by a caller.  An absent policy is deliberately the
    least-privileged cloud policy.
    """

    provider: str = "unknown"
    is_local: bool = False
    capabilities: frozenset[str] = frozenset()

    def has_capability(self, capability: str) -> bool:
        return capability in self.capabilities


@dataclass(frozen=True)
class HostCodeApprovalRequest:
    """Exact, single-call preview passed to a cloud approval callback."""

    provider: str
    model: str
    tool: str
    preview: str
    working_directory: str


@dataclass(frozen=True)
class ToolCall:
    """One model-issued tool invocation, normalised across providers."""

    id: str
    name: str
    args: dict[str, Any]
    error: str | None = None


class ProviderClient(ABC):
    """Five-method interface used by the ImageJAI tool loop."""

    @property
    def tool_policy(self) -> ProviderToolPolicy:
        """Return the router-assigned policy, failing closed when absent."""

        return getattr(self, "_imagejai_tool_policy", ProviderToolPolicy())

    def configure_tool_policy(self, policy: ProviderToolPolicy) -> "ProviderClient":
        """Attach the router's trusted policy and return this client."""

        if not isinstance(policy, ProviderToolPolicy):
            raise TypeError("policy must be a ProviderToolPolicy")
        self._imagejai_tool_policy = policy
        return self

    @abstractmethod
    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        **opts: Any,
    ) -> Any:
        """Issue one non-streaming chat completion.

        Standard opts (recognised by every client where they make sense):
        ``temperature``, ``max_tokens``/``max_output_tokens``, ``top_p``,
        ``top_k``, ``tool_choice``.

        Phase C opt-in native features — each client ignores the kwargs it
        does not support, so callers can pass them uniformly:

        - Anthropic (``anthropic_native.py``):
          ``enable_prompt_caching: bool = True``,
          ``thinking_budget: int = 0``,
          ``enable_server_tools: list[str] | None = None``  (``"web_search"``,
          ``"code_execution"``).
        - Gemini (``gemini_native.py``):
          ``enable_google_search: bool = False``,
          ``enable_code_execution: bool = False``,
          ``thinking_budget: int = 0``.

        The proxy path (``litellm_proxy.py``) drops these — they are exactly
        the features the bypass exists to expose.
        """

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

    if annotation is list:
        return {"type": "array", "items": {}}

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
        bare_items = _BARE_LIST_ITEM_SCHEMAS.get((fn.__name__, name))
        if prop.get("type") == "array" and bare_items is not None:
            prop["items"] = dict(bare_items)
        if name in arg_docs:
            prop["description"] = arg_docs[name]
        contract_default = TOOL_ARGUMENT_DEFAULTS.get(fn.__name__, {}).get(name, inspect.Parameter.empty)
        if contract_default is not inspect.Parameter.empty:
            prop["default"] = contract_default
        properties[name] = prop
        if (
            parameter.default is inspect.Parameter.empty
            and contract_default is inspect.Parameter.empty
        ):
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


def apply_tool_argument_defaults(name: str, args: dict[str, Any]) -> dict[str, Any]:
    """Return model arguments completed with the public registry defaults."""

    completed = dict(args)
    for key, value in TOOL_ARGUMENT_DEFAULTS.get(str(name), {}).items():
        completed.setdefault(key, value)
    return completed


def encode_capture_image(path_value: str) -> tuple[str, str] | None:
    """Return a bounded ``(mime_type, base64)`` capture for provider vision.

    Captures are always re-encoded as RGB JPEG and constrained by both pixel
    edge and encoded byte count.  Invalid/non-file tool output is ignored.
    """

    path = Path(str(path_value or "").strip())
    if not path.is_file():
        return None
    try:
        with Image.open(path) as source:
            image = source.convert("RGB")
        if max(image.size) > MAX_VISION_EDGE:
            scale = MAX_VISION_EDGE / float(max(image.size))
            image = image.resize(
                (
                    max(1, round(image.width * scale)),
                    max(1, round(image.height * scale)),
                ),
                Image.Resampling.LANCZOS,
            )
        quality = 85
        while True:
            buffer = io.BytesIO()
            image.save(buffer, format="JPEG", quality=quality, optimize=True)
            payload = buffer.getvalue()
            if len(payload) <= MAX_VISION_BYTES:
                return "image/jpeg", base64.b64encode(payload).decode("ascii")
            if quality > 45:
                quality -= 10
                continue
            if max(image.size) <= 256:
                return None
            image = image.resize(
                (
                    max(1, round(image.width * 0.75)),
                    max(1, round(image.height * 0.75)),
                ),
                Image.Resampling.LANCZOS,
            )
    except (OSError, ValueError):
        return None


def prune_capture_images(messages: list[dict[str, Any]]) -> None:
    """Remove earlier provider image blocks so raw pixels cannot accumulate."""

    for message in messages:
        message.pop("capture_image", None)
        message.pop("images", None)
        content = message.get("content")
        if not isinstance(content, list):
            continue
        retained: list[Any] = []
        for part in content:
            if not isinstance(part, dict):
                retained.append(part)
                continue
            if part.get("type") in {"image", "image_url"}:
                continue
            if "inline_data" in part:
                continue
            retained.append(part)
        message["content"] = retained
