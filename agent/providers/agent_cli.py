"""Interactive Fiji agent driven by any multi-provider model.

This is the entry point the Java side launches when the user picks a non-CLI
provider model in the cascading dropdown (see
``imagejai.engine.picker.ProxyAgentLauncher`` /
``NativeAgentLauncher``). It is the missing wire between the picker and the
Python provider clients: it reads the chosen provider + model from argv/env,
builds a :class:`~agent.providers.base.ProviderClient` via
:func:`agent.providers.router.get_client`, loads the per-model context overlay
as the system prompt, and runs a tool-calling REPL that controls Fiji through
the TCP command server (``agent.ij``).

Usage::

    python -m agent.providers.agent_cli --provider groq --model llama-3.3-70b-versatile

Provider and model also fall back to the ``IMAGEJAI_PROVIDER`` /
``IMAGEJAI_MODEL`` environment variables, which is how the Java launcher passes
them. Native-feature opt-ins arrive via ``IMAGEJAI_NATIVE_*`` env vars set by
``NativeAgentLauncher``.

The production path delegates to ``agent.gemma4_31b.loop`` so every provider
uses the same animated terminal, Fiji tool registry, slash commands, and
embedded-widget controls as the original Ollama/Gemma wrapper. The older thin
REPL helpers remain below as testable fallback utilities.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Callable
from urllib.parse import urlparse

from agent.providers.base import HOST_CODE_CAPABILITY, ProviderToolPolicy
from agent.providers.router import PROVIDER_KEYS, get_client, provider_tool_policy

try:  # pragma: no cover - exercised when the contexts package is importable
    from agent.contexts.loader import load_context
except Exception:  # pragma: no cover - defensive: contexts are optional
    load_context = None  # type: ignore[assignment]

from agent import ij


# --------------------------------------------------------------------------
# Fiji tools — thin wrappers over agent.ij with model-facing docstrings so the
# schema converter (agent.providers.base.fn_to_json_schema) produces clean
# parameter descriptions for every provider.
# --------------------------------------------------------------------------

def run_macro(code: str) -> str:
    """Run ImageJ macro-language code in Fiji and return the result.

    Args:
        code: ImageJ macro source to execute.
    """
    return _as_text(ij.execute_macro(code))


def run_script(code: str, language: str = "groovy") -> str:
    """Run a script inside Fiji's JVM (full Java API access).

    Args:
        code: Script source.
        language: One of "groovy", "jython", or "javascript".
    """
    return _as_text(ij.run_script(code, language=language))


def get_state() -> str:
    """Return full ImageJ state: open images, results, memory."""
    return _as_text(ij.get_state())


def get_image_info() -> str:
    """Return details about the currently active image."""
    return _as_text(ij.get_image_info())


def get_results() -> str:
    """Return the Results table as CSV."""
    return _as_text(ij.get_results_table())


def get_histogram() -> str:
    """Return intensity statistics and histogram bins for the active image."""
    return _as_text(ij.get_histogram())


def list_windows() -> str:
    """List all open Fiji windows grouped by type."""
    return _as_text(ij.get_open_windows())


def get_metadata() -> str:
    """Return Bio-Formats metadata and calibration for the active image."""
    return _as_text(ij.get_metadata())


def get_log() -> str:
    """Return the ImageJ Log window contents."""
    return _as_text(ij.get_log())


def get_console(tail: int = 2000) -> str:
    """Return recent Fiji stdout/stderr (Groovy/Jython tracebacks live here).

    Args:
        tail: Maximum number of characters of console tail to return.
    """
    return _as_text(ij.get_console(tail=tail))


def get_dialogs() -> str:
    """Check for open modal dialogs or error popups in Fiji."""
    return _as_text(ij.get_dialogs())


def close_dialogs() -> str:
    """Dismiss any open Fiji dialogs."""
    return _as_text(ij.close_dialogs())


def probe_plugin(plugin: str) -> str:
    """Discover a plugin's dialog parameters without running it.

    Args:
        plugin: Exact command name, e.g. "Gaussian Blur...".
    """
    return _as_text(ij.probe_command(plugin))


FIJI_TOOLS: list[Callable[..., Any]] = [
    run_macro,
    get_state,
    get_image_info,
    get_results,
    get_histogram,
    list_windows,
    get_metadata,
    get_log,
    get_console,
    get_dialogs,
    close_dialogs,
    probe_plugin,
]

# Host-code primitives are deliberately absent from the legacy provider
# surface unless the router has classified the provider as local *and* the
# caller explicitly enabled the host_code capability.  This fallback path is
# still reachable when the rich wrapper cannot be imported, so schema omission
# is backed by a dispatch check rather than treated as a sufficient boundary.
_LOCAL_HOST_CODE_TOOLS: tuple[Callable[..., Any], ...] = (run_script,)
TOOL_MAP: dict[str, Callable[..., Any]] = {
    fn.__name__: fn for fn in FIJI_TOOLS
}

_MAX_TOOL_ROUNDS = 16

# Ollama-backed models do not use the thin provider REPL below. They run
# through the bundled rich terminal loop (animated terminal, 37-tool Fiji
# surface, friction log, slash commands) — the path the user tuned via the
# gemma improvement loop. The same rich loop is now used for non-Ollama
# providers too; only the model-call client changes.
_OLLAMA_PROVIDERS = frozenset({"ollama", "ollama-cloud"})


def _run_ollama_wrapper(
    provider: str,
    model: str,
    opts: dict[str, Any] | None = None,
) -> int:
    """Delegate an Ollama model to the gemma4_31b wrapper loop.

    Returns the wrapper's exit code. Raises ImportError if the wrapper package
    (or its ``ollama`` / ``PIL`` dependencies) is unavailable, so the caller can
    fall back to the generic provider path with a clear message.
    """
    from agent.gemma4_31b.__main__ import main as gemma_main

    argv: list[str] = []
    if provider and provider.strip():
        argv += ["--provider", provider.strip()]
    if model and model.strip():
        argv += ["--model", model.strip()]
    capabilities = set((opts or {}).get("capabilities") or ())
    if HOST_CODE_CAPABILITY in capabilities:
        argv.append("--allow-local-host-code")
    return gemma_main(argv)


def _run_rich_provider_wrapper(provider: str, model: str,
                               opts: dict[str, Any] | None = None) -> int:
    """Run a provider-backed model through the shared rich terminal loop."""

    from agent.gemma4_31b import loop as rich_loop

    client = get_client(provider, model, **(opts or {}))
    if not _preflight_model(client, model, print):
        return 1

    guard = _BudgetGuard(_ceiling_from_env())
    if guard.enabled():
        guard.register()
    try:
        return rich_loop.run(
            model=model,
            provider=provider,
            provider_client=client,
            provider_opts=opts or {},
            prompt_filename="",
            budget_guard=guard,
        )
    finally:
        if guard.enabled():
            guard.unregister()


def _as_text(value: Any) -> str:
    """Coerce a TCP reply into a bounded string for the model history."""

    if isinstance(value, str):
        return value[:8000]
    try:
        return json.dumps(value, default=str)[:8000]
    except (TypeError, ValueError):
        return str(value)[:8000]


def _native_opts() -> dict[str, Any]:
    """Translate IMAGEJAI_NATIVE_* env vars into router/client opts."""

    opts: dict[str, Any] = {}
    server_tools: list[str] = []
    if _env_true("IMAGEJAI_NATIVE_GOOGLE_SEARCH"):
        server_tools.append("google_search")
    if _env_true("IMAGEJAI_NATIVE_CODE_EXECUTION"):
        server_tools.append("code_execution")
    # NativeAgentLauncher comma-joins server_tools too; honour either form.
    for tool in _split_env("IMAGEJAI_NATIVE_SERVER_TOOLS"):
        if tool not in server_tools:
            server_tools.append(tool)
    if server_tools:
        opts["server_tools"] = server_tools
    return opts


_LOCAL_HOST_CODE_ENV = "IMAGEJAI_ALLOW_LOCAL_HOST_CODE"
_TRUE_ENV_VALUES = frozenset({"1", "true", "yes", "on"})
_FALSE_ENV_VALUES = frozenset({"", "0", "false", "no", "off"})


def _strict_env_bool(name: str) -> bool:
    """Parse one security-sensitive boolean without typo-to-false behavior."""

    raw = os.environ.get(name)
    if raw is None:
        return False
    value = raw.strip().lower()
    if value in _TRUE_ENV_VALUES:
        return True
    if value in _FALSE_ENV_VALUES:
        return False
    raise ValueError(
        "{} must be one of 1/true/yes/on or 0/false/no/off".format(name)
    )


def _local_host_code_opts(
    provider: str,
    *,
    explicit_grant: bool = False,
) -> dict[str, Any]:
    """Return a host-code capability only for an explicitly trusted local provider.

    The CLI flag and environment variable are launcher/user inputs, never model
    inputs.  Even an explicit request fails closed for cloud providers.
    """

    env_grant = _strict_env_bool(_LOCAL_HOST_CODE_ENV)
    requested = bool(explicit_grant) or env_grant
    if not requested:
        return {}
    policy = provider_tool_policy(provider)
    if not policy.is_local:
        raise ValueError(
            "local host-code permission cannot be granted to cloud provider {!r}".format(
                provider
            )
        )
    if provider == "ollama":
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
    return {"capabilities": [HOST_CODE_CAPABILITY]}


def _provider_opts(provider: str, *, explicit_host_code: bool = False) -> dict[str, Any]:
    """Compose native-provider options with the trusted-local permission."""

    opts = _native_opts()
    opts.update(
        _local_host_code_opts(provider, explicit_grant=explicit_host_code)
    )
    return opts


def _env_true(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def _split_env(name: str) -> list[str]:
    raw = os.environ.get(name, "")
    return [part.strip() for part in raw.split(",") if part.strip()]


def _fallback_system_prompt() -> str:
    return (
        "You are an AI agent controlling Fiji/ImageJ for a biologist doing image "
        "analysis. You drive Fiji through tools that talk to a TCP command server.\n"
        "Rules: check state before acting (never assume an image is open); probe "
        "unfamiliar plugins before using them; write outputs to an AI_Exports/ "
        "folder next to the source image; never use Enhance Contrast normalize on "
        "data you will measure (use setMinAndMax for display only); if something "
        "fails, read the Log and console, then fix the macro rather than working "
        "around it."
    )


def _build_system_prompt(provider: str, model: str) -> str:
    if load_context is not None:
        try:
            text = load_context(f"{provider}/{model}")
            if text and text.strip():
                return text
        except Exception:
            pass
    return _fallback_system_prompt()


def _preflight_model(client: Any, model: str, emit: Callable[[str], None]) -> bool:
    """Validate launch-time model availability when the client can check it."""

    checker = getattr(client, "require_model_available", None)
    if not callable(checker):
        return True
    try:
        checker(model)
    except Exception as exc:
        emit(f"[error] model unavailable before chat: {exc}")
        return False
    return True


def _short(args: Any, limit: int = 200) -> str:
    try:
        text = json.dumps(args, default=str)
    except (TypeError, ValueError):
        text = str(args)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _tools_for_client(client: Any) -> list[Callable[..., Any]]:
    """Return the fail-closed legacy tool surface for one routed client."""

    policy = getattr(client, "tool_policy", None)
    if not isinstance(policy, ProviderToolPolicy):
        policy = ProviderToolPolicy()
    tools = list(FIJI_TOOLS)
    if policy.is_local and policy.has_capability(HOST_CODE_CAPABILITY):
        tools.extend(_LOCAL_HOST_CODE_TOOLS)
    return tools


def _dispatch(
    call: Any,
    allowed_tools: dict[str, Callable[..., Any]] | None = None,
) -> str:
    """Run one tool call, returning a string result (never raising)."""

    # The default is the least-privileged surface.  Callers must pass a map
    # derived from a trusted local policy to dispatch host code.
    permitted = TOOL_MAP if allowed_tools is None else allowed_tools
    fn = permitted.get(call.name)
    if fn is None:
        return f"ERROR: unknown tool '{call.name}'"
    if call.error:
        return f"ERROR: malformed tool arguments: {call.error}"
    try:
        return _as_text(fn(**(call.args or {})))
    except TypeError as exc:
        return f"ERROR: bad arguments for {call.name}: {exc}"
    except Exception as exc:  # pragma: no cover - defensive runtime guard
        return f"ERROR: {type(exc).__name__}: {exc}"


class _BudgetGuard:
    """Per-session spend ceiling for provider-agent loops.

    Java's ``LiteLlmProxyService`` cost sentinel mirrors proxy spend into Swing,
    but it cannot block a running terminal process by itself. The agent loop
    subscribes to every Python provider client's cost listener, accumulates
    spend, and pauses the tool loop before the next model call when the ceiling
    (``IMAGEJAI_BUDGET_CEILING_USD``, exported by the Java launcher) is reached.
    A ceiling of 0 / unset means "no enforcement" — today's behaviour.
    """

    def __init__(self, ceiling_usd: float) -> None:
        self.ceiling_usd = ceiling_usd
        self.total_usd = 0.0

    def _on_cost(self, payload: str) -> None:
        try:
            self.total_usd += float(payload)
        except (TypeError, ValueError):
            pass

    def enabled(self) -> bool:
        return self.ceiling_usd > 0.0

    def exceeded(self) -> bool:
        return self.enabled() and self.total_usd >= self.ceiling_usd

    def raise_ceiling(self) -> None:
        # Mirror the Java BudgetCeilingDialog default: at least double, and never
        # below the already-spent total so /resume can't immediately re-pause.
        self.ceiling_usd = max(self.ceiling_usd * 2.0, self.total_usd * 2.0)

    def register(self) -> None:
        for mod in self._native_modules():
            try:
                mod.add_cost_listener(self._on_cost)
            except Exception:
                pass

    def unregister(self) -> None:
        for mod in self._native_modules():
            try:
                mod.remove_cost_listener(self._on_cost)
            except Exception:
                pass

    @staticmethod
    def _native_modules() -> list[Any]:
        mods: list[Any] = []
        try:
            from agent.providers import litellm_proxy
            mods.append(litellm_proxy)
        except Exception:
            pass
        try:
            from agent.providers import anthropic_native
            mods.append(anthropic_native)
        except Exception:
            pass
        try:
            from agent.providers import gemini_native
            mods.append(gemini_native)
        except Exception:
            pass
        return mods


def _ceiling_from_env() -> float:
    """Read the budget ceiling the Java launcher exports for paid sessions."""

    raw = os.environ.get("IMAGEJAI_BUDGET_CEILING_USD", "").strip()
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return 0.0
    return value if value > 0.0 else 0.0


def run_turn(client: Any, model: str, messages: list[dict[str, Any]],
             emit: Callable[[str], None] = print,
             max_rounds: int = _MAX_TOOL_ROUNDS,
             guard: "_BudgetGuard | None" = None) -> str:
    """Drive one user turn to completion: model → tools → … → final text."""

    tools = _tools_for_client(client)
    allowed_tools = {fn.__name__: fn for fn in tools}
    for _ in range(max_rounds):
        if guard is not None and guard.exceeded():
            emit(f"[budget] ceiling ${guard.ceiling_usd:.2f} reached "
                 f"(spent ~${guard.total_usd:.4f}). Paused before the next model "
                 f"call — type /resume to raise the ceiling, or /exit to stop.")
            return ""
        try:
            response = client.chat(messages, tools, model)
        except Exception as exc:
            emit(f"[error] model call failed: {type(exc).__name__}: {exc}")
            return ""
        client.append_assistant(messages, response)
        calls = client.extract_tool_calls(response)
        if not calls:
            text = client.extract_text(response)
            if text:
                emit(f"\n{text}\n")
            return text
        for call in calls:
            emit(f"[tool] {call.name}({_short(call.args)})")
            result = _dispatch(call, allowed_tools)
            client.append_tool_result(messages, call, result)
    emit("[note] reached the tool-round limit for this turn; ask me to continue.")
    return ""


def run(provider: str, model: str, opts: dict[str, Any] | None = None,
        emit: Callable[[str], None] = print,
        read_line: Callable[[str], str] = input) -> int:
    """Interactive REPL: read user lines, run turns until EOF or /exit."""

    client = get_client(provider, model, **(opts or {}))
    if not _preflight_model(client, model, emit):
        return 1
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": _build_system_prompt(provider, model)}
    ]
    guard = _BudgetGuard(_ceiling_from_env())
    if guard.enabled():
        guard.register()
        emit(f"ImageJAI agent ready — {provider} / {model}. "
             f"Budget ceiling ${guard.ceiling_usd:.2f}. Type your request, "
             f"/resume to raise the ceiling, or /exit to quit.")
    else:
        emit(f"ImageJAI agent ready — {provider} / {model}. "
             f"Type your request, or /exit to quit.")
    try:
        while True:
            try:
                line = read_line("you> ")
            except (EOFError, KeyboardInterrupt):
                emit("")
                return 0
            if line is None:
                return 0
            stripped = line.strip()
            if not stripped:
                continue
            if stripped in {"/exit", "/quit"}:
                return 0
            if stripped == "/resume":
                if guard.enabled():
                    guard.raise_ceiling()
                    emit(f"[budget] ceiling raised to ${guard.ceiling_usd:.2f}.")
                else:
                    emit("[budget] no budget ceiling is set for this session.")
                continue
            messages.append({"role": "user", "content": line})
            run_turn(client, model, messages, emit=emit, guard=guard)
    finally:
        guard.unregister()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Interactive Fiji agent backed by a multi-provider model.")
    parser.add_argument("--provider", default=os.environ.get("IMAGEJAI_PROVIDER"),
                        help="canonical provider key, e.g. groq, anthropic, gemini")
    parser.add_argument("--model", default=os.environ.get("IMAGEJAI_MODEL"),
                        help="model id, e.g. llama-3.3-70b-versatile")
    parser.add_argument(
        "--allow-local-host-code",
        action="store_true",
        help=(
            "Expose run_shell, run_script, and saved-recipe execution to a "
            "trusted local provider. Rejected for every cloud provider."
        ),
    )
    args = parser.parse_args(argv)

    provider = (args.provider or "").strip()
    model = (args.model or "").strip()
    if not provider or not model:
        print("error: --provider and --model (or IMAGEJAI_PROVIDER / "
              "IMAGEJAI_MODEL) are required", file=sys.stderr)
        return 2

    provider_key = provider.lower()
    if provider_key not in PROVIDER_KEYS:
        print(f"error: unknown provider {provider!r}; known: "
              f"{', '.join(PROVIDER_KEYS)}", file=sys.stderr)
        return 2

    try:
        opts = _provider_opts(
            provider_key,
            explicit_host_code=args.allow_local_host_code,
        )
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    if provider_key in _OLLAMA_PROVIDERS:
        try:
            return _run_ollama_wrapper(provider_key, model, opts)
        except ImportError as exc:
            print(f"warning: gemma4_31b wrapper unavailable ({exc}); falling "
                  f"back to the provider-client loop for {provider_key}/{model}",
                  file=sys.stderr)

    return _run_rich_provider_wrapper(provider_key, model, opts)


if __name__ == "__main__":
    raise SystemExit(main())
