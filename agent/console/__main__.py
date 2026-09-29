"""Command-line entry point for the ImageJAI Console."""
from __future__ import annotations

import argparse
import os
import sys

LOCAL_PROVIDERS = frozenset({
    "ollama", "lmstudio", "jan", "llamacpp", "vllm",
    "codex-subscription", "claude-subscription",
})
LOGIN_ALIASES = {
    "codex": "codex-subscription",
    "openai": "codex-subscription",
    "chatgpt": "codex-subscription",
    "claude": "claude-subscription",
    "anthropic": "claude-subscription",
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="imagejai",
        description="ImageJAI Console: one terminal for AI-assisted Fiji work.",
    )
    parser.add_argument("--workspace", help="path to the ImageJAI agent workspace")
    parser.add_argument("--host", default=None, help="ImageJAI TCP host (default localhost)")
    parser.add_argument("--port", type=int, default=None, help="ImageJAI TCP port (default 7746)")
    parser.add_argument("--fiji", default=None,
                        help="Fiji installation folder or launcher (saved for later runs)")
    parser.add_argument("--session", default=None, help="open a saved ImageJAI session by ID")
    parser.add_argument(
        "-m", "--model", default=None,
        help="model to use, e.g. --model claude-opus-4-7 (or the short form "
             "--claude-opus-4-7). Unknown flags go to the vendor client.",
    )
    parser.add_argument("--effort", default=None,
                        help="reasoning effort for the selected model")
    sub = parser.add_subparsers(dest="cmd")
    login = sub.add_parser("login", help="log in to a provider, then launch")
    login.add_argument(
        "provider", nargs="?", choices=sorted(LOGIN_ALIASES),
        help="official subscription login: codex/openai/chatgpt or claude/anthropic",
    )
    sub.add_parser("status", help="print Fiji and provider status, then exit")
    imagej_use = sub.add_parser(
        "use", add_help=False,
        help="run the built-in ImageJ Use automation entry point",
    )
    imagej_use.add_argument("integration_args", nargs=argparse.REMAINDER)
    harness = sub.add_parser(
        "harness", add_help=False,
        help="run the external sandboxed ImageJ Plugin Test Harness",
    )
    harness.add_argument("integration_args", nargs=argparse.REMAINDER)
    return parser


def resolve_model_flag(token: str) -> "tuple[str, str] | None":
    """Map a bare ``--<model>`` flag onto a (provider, model) pair.

    Lets the launcher read the way the user thinks about it:

        imagejai --claude-opus-5 --dangerously-skip-permissions

    The first flag names a model from the shared catalog; anything the catalog
    does not know is left alone and forwarded to the vendor client.
    """
    name = token[2:].strip().lower()
    if not name:
        return None
    try:
        from agent.console.catalog import CatalogEngine
        from agent.console.model_choice import subscription_entries
        entries = [*subscription_entries(), *CatalogEngine().offline().models]
    except Exception:
        return None
    for entry in entries:
        if entry.model_id.lower() == name:
            provider = {"anthropic": "claude-subscription",
                        "openai": "codex-subscription"}.get(entry.provider, entry.provider)
            return provider, entry.model_id
    # tolerate the punctuation people actually type: claude-opus-4.7 == claude-opus-4-7
    flattened = name.replace(".", "-").replace("_", "-")
    for entry in entries:
        if entry.model_id.lower().replace(".", "-").replace("_", "-") == flattened:
            provider = {"anthropic": "claude-subscription",
                        "openai": "codex-subscription"}.get(entry.provider, entry.provider)
            return provider, entry.model_id
    return None


_CONSOLE_FLAGS = {
    "--workspace", "--host", "--port", "--fiji", "--session", "--model", "--effort", "-m", "-h", "--help",
}
_SUBCOMMANDS = {"login", "status", "use", "harness"}


def split_launcher_tokens(raw: list[str]) -> "tuple[list[str], list[str], str | None]":
    """Split argv into console options, vendor pass-through, and a model flag.

    argparse cannot do this alone: a vendor flag with a value
    (``--permission-mode auto``) makes argparse read the value as a
    subcommand. So the split happens first, by hand, and argparse only ever
    sees options it declares.
    """
    console: list[str] = []
    vendor: list[str] = []
    model_flag: str | None = None
    index = 0
    while index < len(raw):
        token = raw[index]
        if token in _SUBCOMMANDS:
            console.extend(raw[index:])
            break
        if not token.startswith("-"):
            console.append(token)
            index += 1
            continue
        base = token.split("=", 1)[0]
        takes_value = "=" not in token and base not in ("-h", "--help")
        if base in _CONSOLE_FLAGS:
            console.append(token)
            if takes_value and index + 1 < len(raw):
                console.append(raw[index + 1])
                index += 1
        elif model_flag is None and resolve_model_flag(base) is not None:
            model_flag = base
        else:
            vendor.append(token)
            following = raw[index + 1] if index + 1 < len(raw) else None
            if (takes_value and following is not None
                    and not following.startswith("-")
                    and following not in _SUBCOMMANDS):
                vendor.append(following)
                index += 1
        index += 1
    return console, vendor, model_flag


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse console options while forwarding integration options verbatim.

    Unknown ``--flags`` are not an error: a flag that names a catalog model
    selects that model, and everything else is passed straight to the vendor
    command-line client.
    """

    raw = list(sys.argv[1:] if argv is None else argv)
    for index, token in enumerate(raw):
        if token not in {"use", "harness"}:
            continue
        args = build_parser().parse_args(raw[: index + 1])
        args.integration_args = raw[index + 1:]
        args.model_choice = None
        args.vendor_args = []
        return args
    console_tokens, vendor_args, model_flag = split_launcher_tokens(raw)
    args = build_parser().parse_args(console_tokens)
    args.integration_args = getattr(args, "integration_args", [])
    model_choice = resolve_model_flag(model_flag) if model_flag else None
    if getattr(args, "model", None):
        explicit = resolve_model_flag("--" + str(args.model))
        model_choice = explicit or (None, str(args.model))
    args.model_choice = model_choice
    args.vendor_args = vendor_args
    return args


def _apply_env(args: argparse.Namespace) -> None:
    if args.host:
        os.environ["IMAGEJAI_TCP_HOST"] = args.host
    if args.port:
        os.environ["IMAGEJAI_TCP_PORT"] = str(args.port)


def _boot_workspace(explicit: str | None):
    from agent.console.workspace import ensure_importable
    try:
        return ensure_importable(explicit)
    except RuntimeError as exc:
        print(f"[imagejai] {exc}", file=sys.stderr)
        raise SystemExit(2)


def cmd_status(workspace: str | None = None) -> int:
    from agent.console.config import ConsoleConfig, load_secret
    from agent.console.fiji import FijiConnection, summarize_state
    from agent.console.subscriptions import SUBSCRIPTION_PROVIDERS, subscription_status
    from agent.console.integrations import harness_status, imagej_use_status

    cfg = ConsoleConfig.load()
    found = _boot_workspace(workspace)
    print(f"workspace     : {found}")
    if cfg.fiji_path:
        print(f"fiji path     : {cfg.fiji_path}")
    print(f"fiji server   : {cfg.host}:{cfg.port}")
    try:
        state = summarize_state(FijiConnection(cfg.host, cfg.port).get_state())
        print(
            f"fiji          : online - {state['n_images']} image(s), "
            f"active: {state['active_title']}"
        )
    except Exception as exc:
        print(f"fiji          : offline ({exc})")
    if cfg.provider and cfg.model:
        if cfg.provider in SUBSCRIPTION_PROVIDERS:
            ready, message = subscription_status(cfg.provider)
            auth = "yes" if ready else f"NO - {message}"
        else:
            auth = "yes" if (load_secret(cfg.provider) or cfg.provider in LOCAL_PROVIDERS) else "MISSING"
        print(f"provider      : {cfg.provider} / {cfg.model} (login: {auth})")
    else:
        print("provider      : not configured - run `imagejai login`")
    imagej_use = imagej_use_status()
    harness = harness_status()
    print(f"imagej-use    : {'ready' if imagej_use.available else 'MISSING'} - {imagej_use.detail}")
    print(f"test harness  : {'ready' if harness.available else 'not installed'} - {harness.detail}")
    return 0


def _subscription_login(alias: str) -> None:
    from agent.console.config import ConsoleConfig
    from agent.console.subscriptions import run_subscription_login, subscription_status

    provider = LOGIN_ALIASES[alias]
    ready, message = subscription_status(provider)
    if not ready:
        try:
            result = run_subscription_login(provider)
        except RuntimeError as exc:
            print(f"[imagejai] {exc}", file=sys.stderr)
            raise SystemExit(2)
        if result != 0:
            raise SystemExit(result)
        ready, message = subscription_status(provider)
    if not ready:
        print(f"[imagejai] login did not complete: {message}", file=sys.stderr)
        raise SystemExit(2)
    config = ConsoleConfig.load()
    config.provider = provider
    config.model = "default"
    config.save()


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    _apply_env(args)
    if args.fiji:
        from agent.console.config import ConsoleConfig
        from agent.console.fiji_startup import fiji_root
        root = fiji_root(args.fiji)
        if root is None:
            print(f"[imagejai] no Fiji launcher found at {args.fiji}", file=sys.stderr)
            return 2
        config = ConsoleConfig.load()
        config.fiji_path = str(root)
        config.save()
    if args.cmd == "status":
        return cmd_status(args.workspace)
    if args.cmd == "use":
        _boot_workspace(args.workspace)
        from agent.console.integrations import run_imagej_use
        return run_imagej_use(args.integration_args)
    if args.cmd == "harness":
        from agent.console.integrations import run_harness
        return run_harness(args.integration_args)
    if args.cmd == "login" and args.provider:
        _subscription_login(args.provider)

    try:
        import textual  # noqa: F401
    except ImportError:
        print(
            "[imagejai] console dependencies are missing. Reinstall with:\n"
            "    python -m pip install -e <ImageJAI repository>",
            file=sys.stderr,
        )
        return 2

    _boot_workspace(args.workspace)
    from agent.console.config import ConsoleConfig
    from agent.console.subscriptions import set_vendor_extra_args
    from agent.console.tui import ConsoleApp

    set_vendor_extra_args(getattr(args, "vendor_args", []))
    choice = getattr(args, "model_choice", None)
    if choice or args.effort:
        from agent.console.model_choice import normalise_effort
        provider, model = choice if choice else (None, None)
        config = ConsoleConfig.load()
        if choice and provider:
            config.provider = provider
        if choice:
            config.model = model
        if args.effort:
            if not config.provider or not config.model:
                print("[imagejai] select a model before setting effort", file=sys.stderr)
                return 2
            try:
                config.effort = normalise_effort(
                    config.provider, config.model, args.effort)
            except ValueError as exc:
                print(f"[imagejai] {exc}", file=sys.stderr)
                return 2
        elif choice:
            config.effort = "default"
        config.save()

    force_login = args.cmd == "login" and not args.provider
    config = ConsoleConfig.load()
    ConsoleApp(
        config, force_login=force_login,
        initial_session_id=args.session,
        auto_start_fiji=config.auto_start_fiji,
    ).run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
