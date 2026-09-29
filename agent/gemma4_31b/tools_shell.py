"""Structured host-process tool for explicitly privileged agents."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from .registry import tool

_TIMEOUT_S = 30
_OUTPUT_CAP = 2000
_NO_WINDOW = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
_REPO_ROOT = str(Path(__file__).resolve().parents[2])


def _validated_invocation(argv: list[str], cwd: str = "") -> tuple[list[str], str]:
    if not isinstance(argv, list) or not argv:
        raise ValueError("argv must be a non-empty list of strings")
    if len(argv) > 128:
        raise ValueError("argv contains too many arguments")
    cleaned: list[str] = []
    total_chars = 0
    for value in argv:
        if not isinstance(value, str) or not value or "\x00" in value:
            raise ValueError("every argv item must be a non-empty string without NUL bytes")
        total_chars += len(value)
        cleaned.append(value)
    if total_chars > 32768:
        raise ValueError("argv is too large")

    if cwd is None or cwd == "":
        resolved_cwd = Path(_REPO_ROOT)
    elif not isinstance(cwd, str) or "\x00" in cwd:
        raise ValueError("cwd must be a path string without NUL bytes")
    else:
        candidate = Path(cwd).expanduser()
        resolved_cwd = candidate if candidate.is_absolute() else Path(_REPO_ROOT) / candidate
        resolved_cwd = resolved_cwd.resolve()
    if not resolved_cwd.is_dir():
        raise ValueError("cwd is not an existing directory: {}".format(resolved_cwd))
    return cleaned, str(resolved_cwd)


def preview_shell_call(argv: list[str], cwd: str = "") -> str:
    """Return the exact structured process invocation used for approval."""

    clean_argv, resolved_cwd = _validated_invocation(argv, cwd)
    return json.dumps(
        {"argv": clean_argv, "cwd": resolved_cwd},
        ensure_ascii=False,
        separators=(",", ":"),
    )


@tool
def run_shell(argv: list[str], cwd: str = "") -> str:
    """Run one structured host process and return its combined output.

    No command shell is involved. The working directory defaults to the
    ImageJAI project root.

    Args:
        argv: Executable and arguments as separate strings, for example ["git", "status"].
        cwd: Existing working directory, or an empty string for the project root.
    """

    try:
        clean_argv, resolved_cwd = _validated_invocation(argv, cwd)
        result = subprocess.run(
            clean_argv,
            shell=False,
            cwd=resolved_cwd,
            capture_output=True,
            creationflags=_NO_WINDOW,
            timeout=_TIMEOUT_S,
        )
    except ValueError as exc:
        return "ERROR: {}".format(exc)
    except subprocess.TimeoutExpired:
        return "ERROR: command timed out after {}s".format(_TIMEOUT_S)
    except OSError as exc:
        return "ERROR: {}: {}".format(type(exc).__name__, exc)

    stdout = (result.stdout or b"").decode("utf-8", errors="replace").strip()
    stderr = (result.stderr or b"").decode("utf-8", errors="replace").strip()
    output = stdout or stderr
    if not output:
        return "EMPTY_OUTPUT (exit code {}) - command produced no output.".format(
            result.returncode
        )
    return output[:_OUTPUT_CAP]
