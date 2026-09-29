"""Locate the ImageJAI agent workspace and make it importable.

The console can be launched from anywhere, but the Fiji tools, provider
clients, and context overlays all live in the workspace ``agent/``
directory (shipped with the plugin). This module applies the same
discovery rules as the Java launcher (see USER_GUIDE.md):

1. ``IMAGEJAI_AGENT_WORKSPACE`` environment variable.
2. The saved path in ``~/.imagej-ai/console.json`` (``workspace`` key).
3. The current working directory (or ``agent/`` inside it).
4. ``~/ImageJAI/agent``.

and, once found, prepends the workspace's parent directory to
``sys.path`` so ``agent.ij`` / ``agent.providers`` import cleanly.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from .config import CONFIG_DIR, CONFIG_PATH

import json


def _looks_like_workspace(path: Path) -> bool:
    return (path / "ij.py").is_file() or (path / "providers").is_dir()


def _candidate_paths() -> list[Path]:
    seen: list[Path] = []

    def add(p: Path | None) -> None:
        if p is None:
            return
        try:
            p = p.resolve()
        except OSError:
            return
        if p not in seen:
            seen.append(p)

    env = os.environ.get("IMAGEJAI_AGENT_WORKSPACE")
    if env:
        add(Path(env))

    try:
        data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        saved = data.get("workspace")
        if saved:
            add(Path(saved))
    except (OSError, ValueError):
        pass

    cwd = Path.cwd()
    add(cwd)
    add(cwd / "agent")
    add(cwd.parent / "agent")

    add(Path.home() / "ImageJAI" / "agent")

    # console lives inside <repo>/agent/console — walk up from this file.
    here = Path(__file__).resolve()
    for parent in here.parents:
        if parent.name == "agent":
            add(parent)
            break

    return seen


def find_workspace() -> Path | None:
    for candidate in _candidate_paths():
        if _looks_like_workspace(candidate):
            return candidate
    return None


def ensure_importable(explicit: str | None = None) -> Path:
    """Find the workspace and put its parent on ``sys.path``.

    Raises ``RuntimeError`` with a fix-it message when no workspace is
    found — the console cannot drive Fiji without ``agent/ij.py``.
    """
    if explicit:
        path = Path(explicit).resolve()
        if not _looks_like_workspace(path):
            raise RuntimeError(f"{path} does not look like an ImageJAI agent workspace (no ij.py)")
    else:
        path = find_workspace()
        if path is None:
            raise RuntimeError(
                "Could not locate the ImageJAI agent workspace.\n"
                "Fix it with one of:\n"
                "  - set IMAGEJAI_AGENT_WORKSPACE=<path to agent/>\n"
                "  - run the console from the ImageJAI repository\n"
                "  - put the workspace at ~/ImageJAI/agent"
            )

    # Two import roots:
    #   workspace parent -> "agent.ij", "agent.providers" (repo layout)
    #   workspace itself -> "ij", "gemma4_31b", "providers" (workspace layout)
    for entry in (path.parent, path):
        if str(entry) not in sys.path:
            sys.path.insert(0, str(entry))
    os.environ.setdefault("IMAGEJAI_AGENT_WORKSPACE", str(path))
    return path
