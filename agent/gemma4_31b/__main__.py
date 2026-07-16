"""Console entry point for the gemma4_31b_agent command.

Registered via pyproject.toml as
    gemma4_31b_agent = gemma4_31b.__main__:main

so the ImageJAI play button can launch the agent by name once the
package is pip-installed.
"""

from __future__ import annotations

import argparse
import os
import sys
from urllib.parse import urlparse

from . import active_image, events, loop, safety


_LOCAL_HOST_CODE_ENV = "IMAGEJAI_ALLOW_LOCAL_HOST_CODE"
_TRUE_ENV_VALUES = frozenset({"1", "true", "yes", "on"})
_FALSE_ENV_VALUES = frozenset({"", "0", "false", "no", "off"})


def _local_host_code_opts(provider: str, explicit_grant: bool) -> dict:
    """Parse the direct Ollama entry point's trusted-local permission."""

    raw = os.environ.get(_LOCAL_HOST_CODE_ENV)
    value = "" if raw is None else raw.strip().lower()
    if value in _TRUE_ENV_VALUES:
        env_grant = True
    elif value in _FALSE_ENV_VALUES:
        env_grant = False
    else:
        raise ValueError(
            "{} must be one of 1/true/yes/on or 0/false/no/off".format(
                _LOCAL_HOST_CODE_ENV
            )
        )
    if not (bool(explicit_grant) or env_grant):
        return {}
    if provider != "ollama":
        raise ValueError(
            "local host-code permission cannot be granted to cloud provider {!r}".format(
                provider
            )
        )
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
    return {"capabilities": ["host_code"]}


def main(argv: list[str] | None = None) -> int:
    """Parse CLI flags, wire up Phase 1d plumbing, and run the chat loop.

    ``argv`` defaults to ``sys.argv[1:]`` (the console-script / ``python -m``
    entry). It is passed explicitly when another entry point — e.g.
    ``agent.providers.agent_cli`` routing an Ollama model — wants to launch
    this wrapper loop with a chosen ``--model`` instead of re-spawning a
    process.
    """
    parser = argparse.ArgumentParser(
        prog="gemma4_31b_agent",
        description="ImageJAI rich terminal agent for Ollama-backed Fiji sessions.",
    )
    parser.add_argument(
        "--provider",
        default=None,
        help=(
            "Provider key for context loading. Defaults to ollama-cloud for "
            "*-cloud models and ollama otherwise."
        ),
    )
    parser.add_argument(
        "--model",
        default=None,
        help=(
            "Model tag to run — any Ollama model works through this same "
            "wrapper. When omitted, resolved from IMAGEJAI_MODEL/OLLAMA_MODEL "
            "or the first local Ollama model."
        ),
    )
    parser.add_argument(
        "--no-friction-log",
        action="store_true",
        help="Disable the friction.log that records macro failures and stuck loops.",
    )
    parser.add_argument(
        "--export-dir",
        default=None,
        help=(
            "Fallback folder for AI_Exports/ when the active image has no "
            "file on disk (e.g. Blobs sample or File > New images)."
        ),
    )
    parser.add_argument(
        "--style",
        default="gemma",
        choices=["gemma", "claude"],
        help=(
            "Which system prompt to load. 'gemma' (default) uses GEMMA.md, "
            "the prescriptive rule-driven prompt. 'claude' uses "
            "GEMMA_CLAUDE.md, a concise Claude-style narrative prompt for "
            "A/B comparison."
        ),
    )
    parser.add_argument(
        "--mode",
        default="auto",
        choices=["auto", *loop.SAMPLING_PROFILES.keys()],
        help="Initial mode lock. 'auto' = per-turn heuristic.",
    )
    parser.add_argument(
        "--think",
        default="auto",
        choices=["auto", "on", "off"],
        help="Initial thinking lock.",
    )
    parser.add_argument(
        "--allow-local-host-code",
        action="store_true",
        help=(
            "Expose run_shell, run_script, and saved-recipe execution to a "
            "trusted local provider. Rejected for Ollama Cloud."
        ),
    )
    args = parser.parse_args(argv)

    model = (args.model or "").strip()
    if not model:
        try:
            model = loop._resolve_default_model()
        except RuntimeError as exc:
            print("error: {}".format(exc), file=sys.stderr)
            return 2

    if args.export_dir:
        active_image.set_export_dir_override(args.export_dir)
    if args.no_friction_log:
        safety.friction_log.ENABLED = False

    prompt_filename = "GEMMA_CLAUDE.md" if args.style == "claude" else "GEMMA.md"
    initial_mode_lock = None if args.mode == "auto" else args.mode
    initial_think_lock = None if args.think == "auto" else (args.think == "on")
    provider_key = loop._normalise_provider(args.provider, model)
    try:
        provider_opts = _local_host_code_opts(
            provider_key,
            args.allow_local_host_code,
        )
    except ValueError as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2

    events.start_subscriber(
        [
            "image.activated",
            "image.opened",
            "image.updated",
            "image.closed",
            "macro.completed",
            "dialog.appeared",
            "dialog.closed",
        ]
    )

    return loop.run(
        model=model,
        no_friction_log=args.no_friction_log,
        prompt_filename=prompt_filename,
        initial_mode_lock=initial_mode_lock,
        initial_think_lock=initial_think_lock,
        provider=provider_key,
        provider_opts=provider_opts,
    )


if __name__ == "__main__":
    sys.exit(main())
