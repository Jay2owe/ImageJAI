"""Opt-in phase-2 zero-polling validation.

Importing this module only defines helpers. Run it explicitly after compiling
``ZeroPollingServer.java`` and creating ``test-scripts/cp.txt``::

    python test-scripts/zero_polling_test.py

The test starts a disposable server on port 7747, bootstraps the context-hook
subscriber, and proves that ten idle prompts are served from its event snapshot
without repeated ``get_state`` calls.
"""

from __future__ import annotations

import argparse
import atexit
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional


PROJECT_DIR = Path(__file__).resolve().parents[1]
CACHE_DIR = PROJECT_DIR / "agent" / ".tmp"
CONTEXT_HOOK = PROJECT_DIR / "context_hook.py"
CP_FILE = PROJECT_DIR / "test-scripts" / "cp.txt"
OUT_DIR = PROJECT_DIR / "test-scripts" / "out"
TARGET_CLASSES = PROJECT_DIR / "target" / "classes"
DEFAULT_PORT = 7747
ZPS_LIFETIME_MS = 15_000


def endpoint_slug(host: str, port: int) -> str:
    safe_host = "".join(ch if ch.isalnum() else "_" for ch in str(host))
    return "{}_{}".format(safe_host, port)


def _remove_if_present(*paths: Path) -> None:
    for path in paths:
        try:
            path.unlink()
        except FileNotFoundError:
            pass


def _pid_from(path: Path) -> Optional[int]:
    try:
        value = int((path.read_text(encoding="utf-8").strip() or "0"))
    except (OSError, ValueError):
        return None
    return value if value > 0 else None


def _stop_process_tree(pid_path: Path) -> None:
    """Stop only the sidecar recorded for this test endpoint."""
    pid = _pid_from(pid_path)
    if pid is None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/F", "/T"],
            capture_output=True,
            timeout=5,
            check=False,
        )
    else:
        try:
            os.kill(pid, 15)
        except (OSError, ProcessLookupError):
            pass


def _java_executable(java_home: Optional[str]) -> str:
    home = java_home or os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java")
    if found:
        return found
    raise RuntimeError("Java was not found; set JAVA_HOME or add java to PATH")


def _classpath() -> str:
    if not CP_FILE.is_file():
        raise RuntimeError(
            "test-scripts/cp.txt is missing; generate the Maven test classpath first"
        )
    dependencies = CP_FILE.read_text(encoding="utf-8").strip()
    return os.pathsep.join((str(OUT_DIR), str(TARGET_CLASSES), dependencies))


def _terminate(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def run_zero_polling(port: int = DEFAULT_PORT, java_home: Optional[str] = None) -> bool:
    slug = endpoint_slug("127.0.0.1", port)
    snapshot = CACHE_DIR / "event_snapshot_{}.json".format(slug)
    pid_path = CACHE_DIR / "event_subscriber_{}.pid".format(slug)
    test_env = dict(os.environ)
    test_env["IMAGEJAI_TCP_PORT"] = str(port)

    # A stale PID file is scoped to this disposable endpoint. Do not scan for
    # or terminate unrelated ImageJAI/Python processes on the workstation.
    _stop_process_tree(pid_path)
    _remove_if_present(snapshot, pid_path)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    atexit.register(_stop_process_tree, pid_path)

    zps = subprocess.Popen(
        [
            _java_executable(java_home),
            "-cp",
            _classpath(),
            "ZeroPollingServer",
            str(port),
            str(ZPS_LIFETIME_MS),
        ],
        cwd=str(PROJECT_DIR),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        time.sleep(1.5)
        result = subprocess.run(
            [sys.executable, str(CONTEXT_HOOK), "--timing", "session-start"],
            cwd=str(PROJECT_DIR),
            env=test_env,
            input="",
            text=True,
            capture_output=True,
            timeout=15,
            check=False,
        )
        if result.returncode != 0:
            print("session-start failed: {}".format(result.stderr[:400]), file=sys.stderr)
            return False

        time.sleep(3.0)
        if not snapshot.is_file():
            print("snapshot file was not created at {}".format(snapshot), file=sys.stderr)
            return False

        served_from_snapshot = []
        for _ in range(10):
            response = subprocess.run(
                [sys.executable, str(CONTEXT_HOOK), "--timing", "every-message"],
                cwd=str(PROJECT_DIR),
                env=test_env,
                input="",
                text=True,
                capture_output=True,
                timeout=8,
                check=False,
            )
            try:
                payload = json.loads(response.stdout.strip() or "{}")
                context = payload.get("hookSpecificOutput", {}).get(
                    "additionalContext", ""
                )
            except (TypeError, ValueError):
                context = response.stdout[:200]
            served_from_snapshot.append("(from event snapshot)" in context)
            time.sleep(0.15)

        try:
            output, _ = zps.communicate(timeout=ZPS_LIFETIME_MS / 1000 + 10)
        except subprocess.TimeoutExpired:
            _terminate(zps)
            output, _ = zps.communicate()

        def count(name: str) -> int:
            match = re.search(r"{}=(\d+)".format(re.escape(name)), output)
            return int(match.group(1)) if match else -1

        get_state = count("get_state")
        subscribe = count("subscribe")
        passed = get_state in (0, 1) and subscribe >= 1 and all(served_from_snapshot)
        print(
            "zero-polling: get_state={} subscribe={} snapshots={}/10".format(
                get_state, subscribe, sum(served_from_snapshot)
            )
        )
        return passed
    finally:
        _terminate(zps)
        _stop_process_tree(pid_path)
        _remove_if_present(snapshot, pid_path)


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--java-home", help="JDK/JRE containing bin/java")
    args = parser.parse_args(argv)
    try:
        passed = run_zero_polling(port=args.port, java_home=args.java_home)
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print("zero-polling setup failed: {}".format(exc), file=sys.stderr)
        return 1
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())

