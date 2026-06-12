"""Console entry point for the gemma4_31b_agent command.

Registered via pyproject.toml as
    gemma4_31b_agent = gemma4_31b.__main__:main

so the ImageJAI play button can launch the agent by name once the
package is pip-installed.
"""

from __future__ import annotations

import argparse
import sys

from . import active_image, events, loop, safety


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
        provider=args.provider,
    )


if __name__ == "__main__":
    sys.exit(main())
