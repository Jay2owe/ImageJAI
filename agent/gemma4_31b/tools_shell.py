"""Shell-command tool for the Gemma 4 31B agent.

Modelled on agent_console/ollama/ollama_chat.py's run_shell: a
single `@tool`-registered function that shells out via
subprocess.run(shell=True), captures bytes (decoded with
errors="replace" because PowerShell and many Windows CLIs emit
UTF-16 / CP1252 rather than UTF-8), caps output at 2000 chars,
hides the subprocess console window on Windows, and times out
after 30 seconds.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from .registry import tool

_TIMEOUT_S = 30
_OUTPUT_CAP = 2000
_NO_WINDOW = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
# Pin every run_shell to the project root so relative paths like
# `agent/references/foo.md` and `agent/.tmp/commands.md` resolve the same
# way regardless of where the agent process was launched. Absolute paths
# are unaffected. parents[2] = …/ImageJAI (file lives at
# agent/gemma4_31b/tools_shell.py).
_REPO_ROOT = str(Path(__file__).resolve().parents[2])


@tool
def run_shell(command: str) -> str:
    """Run a shell command on the host machine and return its combined stdout/stderr output.

    Working directory is pinned to the ImageJAI project root, so relative
    paths starting with `agent/` or `.tmp/` always resolve the same way.

    Args:
        command: The shell command line to execute, for example "dir", "git status", or "python -c \\"print(1+1)\\"".
    """
    if not isinstance(command, str) or not command.strip():
        return "ERROR: command must be a non-empty string"
    try:
        r = subprocess.run(
            command,
            shell=True,
            cwd=_REPO_ROOT,
            capture_output=True,
            creationflags=_NO_WINDOW,
            timeout=_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        return "ERROR: command timed out after {}s".format(_TIMEOUT_S)
    except OSError as exc:
        return "ERROR: {}: {}".format(type(exc).__name__, exc)

    stdout = (r.stdout or b"").decode("utf-8", errors="replace").strip()
    stderr = (r.stderr or b"").decode("utf-8", errors="replace").strip()
    out = stdout or stderr
    if not out:
        return "EMPTY_OUTPUT (exit code {}) — command produced no stdout or stderr.".format(
            r.returncode
        )
    return out[:_OUTPUT_CAP]
