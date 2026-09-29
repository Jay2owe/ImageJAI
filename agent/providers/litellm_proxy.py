"""LiteLLM Proxy provider client.

This path uses the OpenAI SDK against the local LiteLLM Proxy. Phase B is
non-streaming by design.
"""
from __future__ import annotations

import json
import math
import os
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI

from agent.ollama_agent.budget_ceiling import estimate_runtime_cost_usd

from .base import (
    ProviderClient,
    ToolCall,
    encode_capture_image,
    prune_capture_images,
    to_openai_tool,
)


DEFAULT_BASE_URL = "http://localhost:4000/v1"
DEFAULT_API_KEY = ""
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_MAX_RETRIES = 2
DEFAULT_MAX_OUTPUT_TOKENS = 8192
MAX_OUTPUT_TOKENS = 32768
COST_HEADER = "x-litellm-response-cost"
_COST_LISTENERS: list[Callable[[str], None]] = []


def add_cost_listener(listener: Callable[[str], None]) -> None:
    if listener not in _COST_LISTENERS:
        _COST_LISTENERS.append(listener)


def remove_cost_listener(listener: Callable[[str], None]) -> None:
    try:
        _COST_LISTENERS.remove(listener)
    except ValueError:
        pass


def _notify_cost(value: str | None) -> None:
    if value is None or value == "":
        return
    for listener in list(_COST_LISTENERS):
        try:
            listener(str(value))
        except Exception:
            pass


def _runtime_api_key() -> str:
    """Read the per-sidecar key without falling back to a shared constant."""

    for name in ("IMAGEJAI_LITELLM_MASTER_KEY", "LITELLM_MASTER_KEY"):
        value = os.environ.get(name, "").strip()
        if value:
            return value
    auth_file = os.environ.get("IMAGEJAI_LITELLM_AUTH_FILE", "").strip()
    path = Path(auth_file) if auth_file else Path(__file__).with_name("proxy.auth")
    try:
        value = path.read_text(encoding="utf-8").strip()
        return value
    except OSError:
        return ""


def _auth_headers() -> dict[str, str]:
    key = _runtime_api_key()
    return {"Authorization": f"Bearer {key}"} if key else {}


def default_base_url() -> str:
    """Resolve the proxy base URL, honouring the dynamic port the Java
    ``LiteLlmProxyService`` actually selected.

    The sidecar scans ports 4000-4010 and the Java launcher exports the live
    port to the agent process as ``IMAGEJAI_LITELLM_PORT`` (or a full
    ``IMAGEJAI_LITELLM_BASE_URL``). Without this, the client hard-coded
    ``localhost:4000`` and silently talked to the wrong port whenever 4000 was
    already taken. Falls back to the 4000 default so a bare
    ``python -m agent.providers.agent_cli`` still works in development.
    """

    explicit = os.environ.get("IMAGEJAI_LITELLM_BASE_URL", "").strip()
    if explicit:
        return explicit
    port = os.environ.get("IMAGEJAI_LITELLM_PORT", "").strip()
    if port.isdigit() and int(port) > 0:
        return f"http://localhost:{port}/v1"
    # No confirmed port from Java (sidecar may still have been starting when the
    # agent launched). Scan 4000-4010 for the live proxy the same way the Java
    # side does, so a proxy that landed on 4001 because 4000 was busy is still
    # reachable. Falls back to 4000 only when nothing is up yet.
    scanned = _scan_live_proxy_port()
    if scanned:
        return f"http://localhost:{scanned}/v1"
    return DEFAULT_BASE_URL


def _scan_live_proxy_port() -> int | None:
    """Return the first port in 4000-4010 whose LiteLLM readiness endpoint
    answers 200, or None when no proxy is reachable."""

    import urllib.error
    import urllib.request

    for candidate in range(4000, 4011):
        try:
            with urllib.request.urlopen(
                urllib.request.Request(
                    f"http://localhost:{candidate}/health/readiness",
                    headers=_auth_headers(),
                ),
                timeout=0.3,
            ) as response:
                if response.status == 200:
                    return candidate
        except (OSError, urllib.error.URLError):
            continue
    return None


@dataclass
class _StreamFunction:
    """OpenAI function-call shape for accumulated streaming tool calls."""
    name: str = ""
    arguments: str = ""


@dataclass
class _StreamToolCall:
    id: str = ""
    function: _StreamFunction = None  # type: ignore[assignment]


@dataclass
class _StreamMessage:
    """OpenAI assistant-message shape assembled from stream deltas."""
    content: str | None = None
    tool_calls: list = None  # type: ignore[assignment]


@dataclass
class _StreamChoice:
    message: _StreamMessage = None  # type: ignore[assignment]
    finish_reason: str | None = None


@dataclass
class _StreamCompletion:
    """Minimal ChatCompletion stand-in returned by ``chat_stream``."""
    choices: list = None  # type: ignore[assignment]
    usage: Any = None


class LiteLLMProxyClient(ProviderClient):
    """OpenAI-shaped client pointed at a local LiteLLM Proxy."""

    def __init__(
        self,
        *,
        provider: str | None = None,
        model_prefix: str | None = None,
        base_url: str = DEFAULT_BASE_URL,
        api_key: str = DEFAULT_API_KEY,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        max_retries: int = DEFAULT_MAX_RETRIES,
        wildcard: bool = False,
    ) -> None:
        self.provider = provider
        self.model_prefix = model_prefix
        # Wildcard providers (keyless local OpenAI-compatible servers) route any
        # loaded model through a "<prefix>/*" entry in the proxy config, so the
        # specific model is not enumerated in /v1/models — skip strict preflight.
        self.wildcard = bool(wildcard)
        self.base_url = _normalise_base_url(base_url)
        self.api_key = str(api_key or "").strip() or _runtime_api_key()
        self._client = OpenAI(
            base_url=self.base_url,
            # The SDK insists on a non-empty value.  Calls still fail closed
            # below when the sidecar credential was not published.
            api_key=self.api_key or "imagejai-missing-proxy-key",
            timeout=timeout,
            max_retries=max_retries,
        )

    def _chat_kwargs(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        opts: dict[str, Any],
        stream: bool = False,
    ) -> dict[str, Any]:
        """Build chat.completions kwargs shared by chat() and chat_stream()."""
        if not self.api_key:
            raise RuntimeError(
                "LiteLLM proxy authentication is unavailable; restart the "
                "ImageJAI sidecar so it can publish a private runtime key"
            )
        # Phase C native-only kwargs — silently drop on the proxy path so
        # callers can pass features uniformly. Routing intentionally chooses
        # the native client when these matter.
        for native_only in (
            "enable_prompt_caching",
            "thinking_budget",
            "enable_server_tools",
            "enable_google_search",
            "enable_code_execution",
        ):
            opts.pop(native_only, None)
        if "max_completion_tokens" in opts:
            opts["max_completion_tokens"] = min(
                MAX_OUTPUT_TOKENS,
                max(1, int(opts["max_completion_tokens"])),
            )
            opts.pop("max_tokens", None)
        else:
            opts["max_tokens"] = min(
                MAX_OUTPUT_TOKENS,
                max(1, int(opts.get("max_tokens", DEFAULT_MAX_OUTPUT_TOKENS))),
            )
        tool_specs = [to_openai_tool(tool) for tool in tools] if tools else None
        alias = self.normalise_model(model)
        kwargs: dict[str, Any] = {
            "model": alias,
            "messages": messages,
        }
        if tool_specs:
            kwargs["tools"] = tool_specs
            kwargs["tool_choice"] = opts.pop("tool_choice", "auto")
        kwargs.update(opts)
        if stream:
            kwargs["stream"] = True
            # usage rides on the final chunk so cost listeners still work
            kwargs["stream_options"] = {"include_usage": True}
        return kwargs

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        **opts: Any,
    ) -> Any:
        """Issue one non-streaming chat completion."""
        kwargs = self._chat_kwargs(messages, tools, model, opts)
        alias = kwargs["model"]
        try:
            raw = self._client.chat.completions.with_raw_response.create(**kwargs)
            response = raw.parse()
            cost_header = raw.headers.get(COST_HEADER)
            # Keep a reported header distinct from registry fallback estimates.
            try:
                reported = float(cost_header) if cost_header is not None else None
            except (TypeError, ValueError):
                reported = None
            if reported is not None and math.isfinite(reported) and reported >= 0:
                response.__dict__["response_cost_usd"] = reported
            try:
                parsed_cost = float(cost_header) if cost_header is not None else 0.0
            except (TypeError, ValueError):
                parsed_cost = 0.0
            if math.isfinite(parsed_cost) and parsed_cost > 0:
                _notify_cost(str(parsed_cost))
            else:
                usage = getattr(response, "usage", None)
                input_tokens = int(getattr(usage, "prompt_tokens", 0) or 0)
                output_tokens = int(getattr(usage, "completion_tokens", 0) or 0)
                fallback = estimate_runtime_cost_usd(
                    self.provider or "",
                    model,
                    input_tokens,
                    output_tokens,
                )
                if fallback > 0:
                    _notify_cost(str(fallback))
            return response
        except (APIConnectionError, APIStatusError, APITimeoutError) as exc:
            raise RuntimeError(
                "LiteLLM proxy chat failed for model {!r} "
                "(alias {!r} at {}): {}{}".format(
                    model, alias, self.base_url, exc, self._invalid_model_hint(alias, exc)
                )
            ) from exc

    def chat_stream(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        on_delta: Callable[[str], None] | None = None,
        abort: Any | None = None,
        on_thinking: Callable[[str], None] | None = None,
        on_tool_preparing: Callable[[str], None] | None = None,
        **opts: Any,
    ) -> Any:
        """Streaming chat over the OpenAI-compatible proxy surface.

        Emits text deltas via ``on_delta`` and returns a synthetic response
        exposing the same attribute surface as the non-streaming
        ``ChatCompletion`` (choices[0].message.{content, tool_calls}, usage),
        so ``extract_*`` / ``append_*`` helpers work unchanged.
        """
        kwargs = self._chat_kwargs(messages, tools, model, opts, stream=True)
        alias = kwargs["model"]
        content_parts: list[str] = []
        tool_acc: dict[int, dict[str, str]] = {}
        finish_reason: str | None = None
        usage = None
        try:
            stream = self._client.chat.completions.create(**kwargs)
            closer = getattr(stream, "close", None)
            if abort is not None and callable(closer):
                abort.register(closer)
            try:
                for chunk in stream:
                    if abort is not None and abort.set_flag:
                        raise InterruptedError("interrupted by user")
                    chunk_usage = getattr(chunk, "usage", None)
                    if chunk_usage is not None:
                        usage = chunk_usage
                    choices = getattr(chunk, "choices", None) or []
                    if not choices:
                        continue
                    choice = choices[0]
                    finish_reason = getattr(choice, "finish_reason", None) or finish_reason
                    delta = getattr(choice, "delta", None)
                    if delta is None:
                        continue
                    thinking = (getattr(delta, "reasoning_content", None)
                                or getattr(delta, "reasoning", None))
                    if isinstance(thinking, str) and thinking and on_thinking is not None:
                        on_thinking(thinking)
                    delta_text = getattr(delta, "content", None)
                    if delta_text:
                        content_parts.append(delta_text)
                        if on_delta is not None:
                            on_delta(delta_text)
                    for tc in getattr(delta, "tool_calls", None) or []:
                        idx = int(getattr(tc, "index", 0) or 0)
                        acc = tool_acc.setdefault(idx, {"id": "", "name": "", "arguments": ""})
                        if getattr(tc, "id", None):
                            acc["id"] = tc.id
                        fn = getattr(tc, "function", None)
                        if fn is not None:
                            if getattr(fn, "name", None):
                                if not acc["name"] and on_tool_preparing is not None:
                                    on_tool_preparing(fn.name)
                                acc["name"] = fn.name
                            if getattr(fn, "arguments", None):
                                acc["arguments"] += fn.arguments
            finally:
                if abort is not None and callable(closer):
                    abort.unregister(closer)
                    closer()
        except (APIConnectionError, APIStatusError, APITimeoutError) as exc:
            raise RuntimeError(
                "LiteLLM proxy stream failed for model {!r} "
                "(alias {!r} at {}): {}{}".format(
                    model, alias, self.base_url, exc, self._invalid_model_hint(alias, exc)
                )
            ) from exc

        tool_calls = [
            _StreamToolCall(
                id=acc["id"] or f"stream_{idx}",
                function=_StreamFunction(name=acc["name"], arguments=acc["arguments"]),
            )
            for idx, acc in sorted(tool_acc.items())
        ]
        message = _StreamMessage(content="".join(content_parts), tool_calls=tool_calls)
        response = _StreamCompletion(
            choices=[_StreamChoice(message=message, finish_reason=finish_reason)],
            usage=usage,
        )
        if usage is not None:
            fallback = estimate_runtime_cost_usd(
                self.provider or "",
                model,
                int(getattr(usage, "prompt_tokens", 0) or 0),
                int(getattr(usage, "completion_tokens", 0) or 0),
            )
            if fallback > 0:
                _notify_cost(str(fallback))
        return response


    def require_model_available(self, model: str) -> str:
        """Raise with a local recovery hint if the proxy does not expose model."""

        alias = self.normalise_model(model)
        if self.wildcard:
            # Keyless local server: a "<prefix>/*" route accepts any loaded
            # model, which is exactly what discovery surfaces. The proxy does
            # not list the specific model, so the membership check below would
            # spuriously fail — accept the alias and let the chat call surface
            # any real routing error.
            return alias
        available = self.available_model_ids()
        if alias not in available:
            sample = ", ".join(available[:12]) if available else "(no models returned)"
            raise RuntimeError(
                "LiteLLM proxy at {} does not expose model alias {!r}. "
                "Check agent/providers/litellm.config.yaml and restart the "
                "LiteLLM sidecar. Available aliases: {}".format(
                    self.base_url, alias, sample
                )
            )
        return alias

    def available_model_ids(self) -> list[str]:
        """Return model ids advertised by the local LiteLLM proxy."""

        if not self.api_key:
            raise RuntimeError(
                "LiteLLM proxy authentication is unavailable; restart the sidecar"
            )
        try:
            response = self._client.models.list()
        except (APIConnectionError, APIStatusError, APITimeoutError) as exc:
            raise RuntimeError(
                f"LiteLLM proxy model list failed at {self.base_url}: {exc}"
            ) from exc
        ids: list[str] = []
        for item in getattr(response, "data", []) or []:
            model_id = getattr(item, "id", None)
            if model_id is None and isinstance(item, dict):
                model_id = item.get("id")
            if model_id:
                ids.append(str(model_id))
        return ids

    def normalise_model(self, model: str) -> str:
        if not self.model_prefix:
            return model
        if model.startswith(self.model_prefix):
            return model
        return f"{self.model_prefix}{model}"

    @staticmethod
    def _invalid_model_hint(alias: str, exc: Exception) -> str:
        text = str(exc).lower()
        if "invalid model" not in text and "model name" not in text:
            return ""
        return (
            " Hint: the ImageJAI launcher asked for alias {!r}; compare it "
            "with GET /v1/models on the LiteLLM sidecar and the model_name "
            "entries in agent/providers/litellm.config.yaml."
        ).format(alias)

    def extract_text(self, response: Any) -> str:
        message = response.choices[0].message
        return (message.content or "").strip() if message else ""

    def extract_tool_calls(self, response: Any) -> list[ToolCall]:
        message = response.choices[0].message
        raw_calls = getattr(message, "tool_calls", None) or []
        calls: list[ToolCall] = []
        for raw_call in raw_calls:
            raw_args = getattr(raw_call.function, "arguments", None) or "{}"
            error: str | None = None
            try:
                parsed = json.loads(raw_args)
                if not isinstance(parsed, dict):
                    error = "tool arguments JSON did not decode to an object"
                    parsed = {}
            except (json.JSONDecodeError, TypeError) as exc:
                error = f"malformed tool arguments JSON: {exc}"
                parsed = {}
            calls.append(
                ToolCall(
                    id=raw_call.id,
                    name=raw_call.function.name,
                    args=parsed,
                    error=error,
                )
            )
        return calls

    def append_assistant(self, messages: list[dict[str, Any]], response: Any) -> None:
        message = response.choices[0].message
        entry: dict[str, Any] = {
            "role": "assistant",
            "content": message.content or "",
        }
        if getattr(message, "tool_calls", None):
            entry["tool_calls"] = [
                {
                    "id": tool_call.id,
                    "type": "function",
                    "function": {
                        "name": tool_call.function.name,
                        "arguments": tool_call.function.arguments,
                    },
                }
                for tool_call in message.tool_calls
            ]
        messages.append(entry)

    def append_tool_result(
        self,
        messages: list[dict[str, Any]],
        call: ToolCall,
        result: str,
    ) -> None:
        messages.append(
            {
                "role": "tool",
                "tool_call_id": call.id,
                "content": str(result),
            }
        )
        if call.name == "capture_image" and self.may_attach_image():
            encoded = encode_capture_image(result)
            if encoded is not None:
                prune_capture_images(messages[:-1])
                mime_type, data = encoded
                messages.append(
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Captured Fiji image:"},
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:{mime_type};base64,{data}",
                                    "detail": "low",
                                },
                            },
                        ],
                    }
                )


def _normalise_base_url(base_url: str) -> str:
    cleaned = base_url.rstrip("/")
    if cleaned.endswith("/v1"):
        return cleaned
    return f"{cleaned}/v1"
