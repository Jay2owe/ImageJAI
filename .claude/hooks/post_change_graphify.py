"""Translate Claude/Codex post-edit payloads into Graphify hook requests."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PATCH_PATH = re.compile(
    r"^\*\*\* (?:Add|Update|Delete) File:\s*(.+?)\s*$", re.MULTILINE
)


def _changed_paths(payload: object) -> list[str]:
    if not isinstance(payload, dict):
        return []
    tool_input = payload.get("tool_input", payload.get("input", {}))
    paths: set[str] = set()
    if isinstance(tool_input, dict):
        for key in ("file_path", "path", "filename"):
            value = tool_input.get(key)
            if isinstance(value, str) and value.strip():
                paths.add(value.strip())
        patch = tool_input.get("patch", tool_input.get("input", ""))
    else:
        patch = tool_input
    if isinstance(patch, str):
        paths.update(match.group(1).strip() for match in PATCH_PATH.finditer(patch))
    return sorted(paths)


def main() -> int:
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except (json.JSONDecodeError, TypeError):
        return 0
    paths = _changed_paths(payload)
    if not paths:
        return 0
    try:
        subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "graphify_hook.py"),
                "--event",
                "post-edit",
                *paths,
            ],
            cwd=ROOT,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
