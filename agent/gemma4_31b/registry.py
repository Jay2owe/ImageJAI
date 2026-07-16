"""Tool registry for the Gemma 4 31B agent.

Each @tool function has its signature and docstring read by
ollama.chat() to build the tool schema, so the docstring IS the
contract. The decorator guards against empty docstrings because a
tool with no docstring registers with a useless schema and Gemma
will ignore it or call it with garbage arguments.

Also hosts the tiny JSON-over-TCP helper that every tools_*.py
module uses to talk to the Fiji server. Host and port follow the
same IMAGEJAI_TCP_HOST / IMAGEJAI_TCP_PORT environment variables as
agent/ij.py, so the standalone agent works when the plugin uses a
non-default TCP port.
"""

import os
import threading

try:
    from agent.providers.base import HOST_CODE_CAPABILITY, ProviderToolPolicy
except ImportError:  # pragma: no cover - direct package installs
    from providers.base import HOST_CODE_CAPABILITY, ProviderToolPolicy  # type: ignore

try:
    from agent.ij import ImageJSession as _BaseImageJSession
except ImportError:  # pragma: no cover - bundled workspace layout
    from ij import ImageJSession as _BaseImageJSession  # type: ignore

HOST = os.environ.get("IMAGEJAI_TCP_HOST", "localhost")
try:
    PORT = int(os.environ.get("IMAGEJAI_TCP_PORT", "7746"))
except ValueError:
    PORT = 7746
TIMEOUT_S = 60.0

# Step 01 (docs/tcp_upgrade/01_hello_handshake.md): Gemma's handshake caps.
# Gemma is text-only (vision=False), has a tight context budget, and has no
# external hook injecting session state, so pulse=True — later steps will rely
# on the server-side one-line pulse in lieu of hook feeds.
# Step 05 (docs/tcp_upgrade/05_state_delta_and_pulse.md): state_delta=True
# groups diff keys (newImages / resultsTable / logDelta / dismissedDialogs)
# under a single "stateDelta" sub-object — tighter replies for the 4k budget.
GEMMA_CAPS = {
    "vision": False,
    "output_format": "json",
    "token_budget": 4000,
    "verbose": False,
    "pulse": True,
    "state_delta": True,
    "accept_events": ["macro.*", "image.*", "dialog.*"],
}

REGISTRY: list = []

# These tools can execute arbitrary host/JVM code.  Keep this set explicit so
# a new tool cannot accidentally gain host-code privilege through name matching.
HOST_CODE_TOOL_NAMES = frozenset({
    "run_shell",
    "run_script",
    # Recipes can contain script steps that execute arbitrary JVM code. Keep
    # the whole runner behind host_code + one-call elevation; dry-run callers
    # use the canonical CLI instead of exposing this mixed-trust tool.
    "run_saved_recipe",
})

# A recipe may reference other files and resolves into a multi-step mutable
# plan.  The current tool call carries only a recipe name, so a cloud approval
# callback cannot approve an immutable, hash-bound execution snapshot.  Keep
# recipe execution local-only until the public contract can carry such a
# snapshot; cloud callers retain safe macro tools and may use the standalone
# dry-run CLI for inspection.
CLOUD_FORBIDDEN_TOOL_NAMES = frozenset({"run_saved_recipe"})


def _compatibility_forbidden() -> dict:
    return {
        "ok": False,
        "error": {
            "code": "compatibility_forbidden",
            "message": (
                "Gemma tools require an authenticated durable ImageJAI session; "
                "the server offered compatibility mode"
            ),
            "category": "authentication",
            "retry_safe": False,
        },
    }


class StrictGemmaSession(_BaseImageJSession):
    """ImageJSession that refuses unauthenticated compatibility negotiation."""

    def hello(self, timeout=10, force=False):
        # Base ImageJSession caches the server-issued session before returning
        # from hello(). Keep its re-entrant lock held through strict validation
        # so a concurrent tool/event request can never observe a compatibility
        # session in that small interval.
        with self._lock:
            response = super().hello(timeout=timeout, force=force)
            return self._validate_hello_response_locked(response)

    def _validate_hello_response_locked(self, response):
        """Validate while ``self._lock`` is held (overridable test seam)."""
        result = response.get("result") if isinstance(response, dict) else None
        if isinstance(response, dict) and response.get("ok"):
            if not isinstance(result, dict) or result.get("compatibility") is not False:
                self.invalidate()
                return _compatibility_forbidden()
        return response


_SESSION = None
_SESSION_LOCK = threading.RLock()


def _new_imagej_session():
    return StrictGemmaSession(
        host=HOST,
        port=PORT,
        timeout=TIMEOUT_S,
        agent="gemma-31b",
        capabilities=GEMMA_CAPS,
    )


def imagej_session():
    """Return the one thread-safe durable session shared by tools and events."""

    global _SESSION
    with _SESSION_LOCK:
        if _SESSION is None:
            _SESSION = _new_imagej_session()
        return _SESSION


def _set_session_for_test(session) -> None:
    """Replace the singleton without touching sockets (focused test seam)."""

    global _SESSION
    with _SESSION_LOCK:
        _SESSION = session


def tool(func):
    """Register a function as a tool exposed to Ollama.

    Raises ValueError at import time if the function has no
    docstring — ollama.chat() reads the docstring to build the tool
    schema, so an empty one produces a silent bug.
    """
    if not (func.__doc__ and func.__doc__.strip()):
        raise ValueError(
            "tool '{}' has no docstring — ollama.chat() uses the "
            "docstring as the tool's schema, so an empty one is a "
            "bug.".format(func.__name__)
        )
    REGISTRY.append(func)
    return func


def all_tools() -> list:
    """Return the list of registered tool callables."""
    return list(REGISTRY)


def tools_for_policy(
    policy: ProviderToolPolicy,
    *,
    cloud_elevation: bool = False,
) -> list:
    """Return only tools allowed by a trusted provider policy.

    Locality alone is insufficient: local providers also need the explicit
    ``host_code`` capability.  A cloud schema may contain host-code tools only
    for the current session when it has that capability *and* a live one-call
    approval callback (represented by ``cloud_elevation``).
    """

    if not isinstance(policy, ProviderToolPolicy):
        policy = ProviderToolPolicy()
    host_code_allowed = policy.has_capability(HOST_CODE_CAPABILITY) and (
        policy.is_local or cloud_elevation
    )
    if host_code_allowed:
        if policy.is_local:
            return list(REGISTRY)
        return [fn for fn in REGISTRY if fn.__name__ not in CLOUD_FORBIDDEN_TOOL_NAMES]
    return [fn for fn in REGISTRY if fn.__name__ not in HOST_CODE_TOOL_NAMES]


def is_host_code_tool(name: str) -> bool:
    """Return whether a tool is an arbitrary host/JVM code primitive."""

    return name in HOST_CODE_TOOL_NAMES


TOOL_MAP: dict = {}


def _rebuild_tool_map() -> dict:
    TOOL_MAP.clear()
    for fn in REGISTRY:
        TOOL_MAP[fn.__name__] = fn
    return TOOL_MAP


def send(command: str, **payload) -> dict:
    """Send one command through Gemma's authenticated durable session.

    The underlying protocol still uses one socket per request, but the same
    server-issued session ID, installation token and ``GEMMA_CAPS`` are carried
    on every socket. Hello/authentication failures are returned unchanged and
    are never followed by a compatibility or bare-command retry.
    """
    request = {"command": command}
    request.update(payload)
    session = imagej_session()
    if command == "hello":
        return session.hello(timeout=min(TIMEOUT_S, 10.0), force=True)
    return session.request(request, timeout=TIMEOUT_S)


def hello() -> dict:
    """Negotiate Gemma's strict durable session, with no legacy fallback."""

    return imagej_session().hello(timeout=min(TIMEOUT_S, 10.0))
