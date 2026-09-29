"""Discover and run local prompt files from the console command picker.

The embedded widget injects a vendor slash command into an interactive
terminal. The standalone console has no interactive vendor terminal, so it
reads the selected file and sends its text through the normal chat turn.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .cliagents import CommandScanError, user_command_dir, user_commands

MAX_COMMAND_FILE_BYTES = 256 * 1024


class CommandFileError(ValueError):
    """A selected command file cannot safely become a console prompt."""


@dataclass(frozen=True)
class PromptCommand:
    command: str
    path: Path
    root: Path
    source: str


@dataclass(frozen=True)
class CommandDiscovery:
    commands: tuple[PromptCommand, ...] = ()
    errors: tuple[str, ...] = ()


def discover_commands(
    provider: str | None, model: str | None,
    workspace: Path | None, config_dir: Path,
    *, reserved_commands: frozenset[str] = frozenset(),
    home: Path | None = None,
) -> CommandDiscovery:
    """List console files and relevant embedded-widget command folders."""
    sources: list[tuple[str, str, Path | None, Path | None]] = [
        ("Console", "console", config_dir / "console", None),
    ]
    if provider in {"claude-subscription", "anthropic"} and workspace is not None:
        sources.append(("Claude", "claude", workspace, None))
    gemma_model = (model or "").lower()
    if "gemma4" in gemma_model and "31b" in gemma_model:
        sources.append(("Gemma", "gemma4_31b", None, home or Path.home()))

    reserved = {name.casefold() for name in reserved_commands}
    commands: list[PromptCommand] = []
    errors: list[str] = []
    for source, agent_id, folder, home in sources:
        root, _, _ = user_command_dir(agent_id, folder, home=home)
        if root is None:
            continue
        try:
            found = user_commands(agent_id, folder, home=home, use_cache=False)
        except CommandScanError as error:
            errors.append(f"{source}: {error}")
            continue
        for entry in found.commands:
            if entry.command.partition(" ")[0].casefold() in reserved:
                errors.append(f"{source}: {entry.command} conflicts with a built-in command")
                continue
            path = Path(entry.description)
            if not path.is_relative_to(root.resolve()):
                errors.append(f"{source}: ignored a command outside {root}")
                continue
            commands.append(PromptCommand(
                entry.command, path, root, source))
    return CommandDiscovery(tuple(commands), tuple(errors))


def match_command(text: str, commands: tuple[PromptCommand, ...]
                  ) -> tuple[PromptCommand, str] | None:
    """Match a typed slash command, including Gemma's two-word prefix."""
    query = text.strip()
    for entry in sorted(commands, key=lambda item: len(item.command), reverse=True):
        prefix = entry.command
        if query.casefold() == prefix.casefold():
            return entry, ""
        if query.casefold().startswith((prefix + " ").casefold()):
            return entry, query[len(prefix):].strip()
    return None


def load_prompt(command: PromptCommand, arguments: str = "",
                *, max_chars: int = 65_536) -> str:
    """Read a still-valid local file and expand its plain-text arguments."""
    try:
        root = command.root.resolve(strict=True)
        path = command.path.resolve(strict=True)
        if not path.is_relative_to(root) or not path.is_file():
            raise CommandFileError("command file is outside its command folder")
        suffixes = {".md"} if command.source == "Claude" else {".md", ".txt"}
        if path.suffix.lower() not in suffixes:
            raise CommandFileError("command file must be Markdown or text")
        with path.open("rb") as stream:
            raw = stream.read(MAX_COMMAND_FILE_BYTES + 1)
        if len(raw) > MAX_COMMAND_FILE_BYTES:
            raise CommandFileError("command file is too large")
        body = raw.decode("utf-8-sig")
    except CommandFileError:
        raise
    except (OSError, RuntimeError, UnicodeError) as error:
        raise CommandFileError(f"cannot read command file: {error}") from error

    # Claude Markdown command metadata is not an instruction to the model.
    body = re.sub(r"\A---\r?\n.*?\r?\n---[ \t]*\r?\n", "", body, count=1, flags=re.S)
    body = body.strip()
    if not body:
        raise CommandFileError("command file is empty")
    arguments = arguments.strip()
    if "$ARGUMENTS" in body:
        body = body.replace("$ARGUMENTS", arguments)
    elif arguments:
        body += "\n\nUser arguments: " + arguments
    body = body.strip()
    if len(body) > max_chars:
        raise CommandFileError(f"expanded command exceeds {max_chars:,} characters")
    return body
