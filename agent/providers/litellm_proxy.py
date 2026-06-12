"""LiteLLM Proxy provider client.

This path uses the OpenAI SDK against the local LiteLLM Proxy. Phase B is
non-streaming by design.
"""
from __future__ import annotations

import json
import os
from collections.abc import Callable
from typing import Any

from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI

from .base import ProviderClient, ToolCall, to_openai_tool


DEFAULT_BASE_URL = "http://localhost:4000/v1"
DEFAULT_API_KEY = "sk-litellm-proxy-local"
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_MAX_RETRIES = 2
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
                f"http://localhost:{candidate}/health/readiness", timeout=0.3
            ) as response:
                if response.status == 200:
                    return candidate
        except (OSError, urllib.error.URLError):
            continue
    return None


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
        self._client = OpenAI(
            base_url=self.base_url,
            api_key=api_key,
            timeout=timeout,
            max_retries=max_retries,
        )

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[Callable[..., Any]],
        model: str,
        **opts: Any,
    ) -> Any:
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
        try:
            raw = self._client.chat.completions.with_raw_response.create(**kwargs)
            _notify_cost(raw.headers.get(COST_HEADER))
            return raw.parse()
        except (APIConnectionError, APIStatusError, APITimeoutError) as exc:
            raise RuntimeError(
                "LiteLLM proxy chat failed for model {!r} "
                "(alias {!r} at {}): {}{}".format(
                    model, alias, self.base_url, exc, self._invalid_model_hint(alias, exc)
                )
            ) from exc

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


def _normalise_base_url(base_url: str) -> str:
    cleaned = base_url.rstrip("/")
    if cleaned.endswith("/v1"):
        return cleaned
    return f"{cleaned}/v1"
