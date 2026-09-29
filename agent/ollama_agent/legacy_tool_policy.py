"""Central provider-policy enforcement for executable legacy Ollama wrappers."""

from __future__ import annotations

import os
from urllib.parse import urlparse

try:
    from agent.providers.base import HOST_CODE_CAPABILITY
    from agent.providers.router import provider_tool_policy
except ImportError:  # pragma: no cover - bundled workspace import layout
    from providers.base import HOST_CODE_CAPABILITY  # type: ignore
    from providers.router import provider_tool_policy  # type: ignore


_LOCAL_HOST_CODE_ENV = "IMAGEJAI_ALLOW_LOCAL_HOST_CODE"
_TRUE_VALUES = frozenset({"1", "true", "yes", "on"})
_FALSE_VALUES = frozenset({"", "0", "false", "no", "off"})

# These fixed-purpose helpers neither expose arbitrary URLs/local files nor
# control another process. Every filesystem, generic network, shell,
# AgentConsole, delegation, learned, and IoT tool stays behind an explicit
# trusted-local host-code grant.
_SAFE_TOOL_NAMES = frozenset({"get_weather", "get_datetime"})


def _is_cloud_model(model: str) -> bool:
    value = str(model or "").strip().lower()
    return value.endswith("-cloud") or value.endswith(":cloud")


def _strict_host_code_grant() -> bool:
    raw = os.environ.get(_LOCAL_HOST_CODE_ENV)
    value = "" if raw is None else raw.strip().lower()
    if value in _TRUE_VALUES:
        return True
    if value in _FALSE_VALUES:
        return False
    raise ValueError(
        "{} must be one of 1/true/yes/on or 0/false/no/off".format(
            _LOCAL_HOST_CODE_ENV
        )
    )


def policy_for_model(model: str):
    """Return the router-owned policy for a legacy wrapper model."""
    provider = "ollama-cloud" if _is_cloud_model(model) else "ollama"
    grant = _strict_host_code_grant()
    if grant and provider != "ollama":
        raise ValueError("local host-code permission cannot be granted to a cloud model")
    if grant:
        endpoint = os.environ.get("OLLAMA_HOST", "").strip()
        if endpoint:
            try:
                hostname = urlparse(endpoint).hostname
            except ValueError:
                hostname = None
            if hostname not in {"localhost", "127.0.0.1", "::1"}:
                raise ValueError(
                    "local host-code permission requires a loopback OLLAMA_HOST"
                )
    capabilities = [HOST_CODE_CAPABILITY] if grant else None
    return provider_tool_policy(provider, capabilities)


def host_tools_allowed(model: str) -> bool:
    policy = policy_for_model(model)
    return policy.is_local and policy.has_capability(HOST_CODE_CAPABILITY)


def allowed_tools_for_model(model: str, tools) -> list:
    """Filter schemas through the central policy before a model sees them."""
    candidates = list(tools or [])
    if host_tools_allowed(model):
        return candidates
    return [fn for fn in candidates if getattr(fn, "__name__", "") in _SAFE_TOOL_NAMES]


def dispatch_tool_for_model(model: str, name: str, args: dict, tool_map: dict):
    """Independently enforce the schema policy against forged tool calls."""
    fn = tool_map.get(name)
    if fn is None:
        return "ERROR: unknown tool {!r}".format(name)
    allowed_names = {
        item.__name__ for item in allowed_tools_for_model(model, tool_map.values())
    }
    if name not in allowed_names:
        provider = policy_for_model(model).provider
        return "ERROR: tool {!r} is not permitted for provider {!r}".format(
            name, provider
        )
    return fn(**args)
