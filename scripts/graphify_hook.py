#!/usr/bin/env python3
"""Schedule safe, non-blocking Graphify updates for this repository.

Every trigger writes a small request file and starts a detached worker. Workers
share an atomic directory lock, wait for a quiet debounce window, and serialize
``python -m graphify update``. The caller always returns promptly; failures are
recorded under ``.git/graphify-hook-logs``.
"""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Callable, Iterable, Sequence


RELEVANT_SUFFIXES = frozenset(
    {
        ".c",
        ".cc",
        ".cpp",
        ".cs",
        ".cxx",
        ".go",
        ".h",
        ".hpp",
        ".html",
        ".java",
        ".js",
        ".json",
        ".jsx",
        ".kt",
        ".kts",
        ".md",
        ".php",
        ".py",
        ".rb",
        ".rs",
        ".rst",
        ".scala",
        ".sh",
        ".sql",
        ".toml",
        ".ts",
        ".tsx",
        ".txt",
        ".xml",
        ".yaml",
        ".yml",
    }
)
IGNORED_PARTS = frozenset(
    {".git", ".pytest_cache", "__pycache__", "graphify-out", "target"}
)
DEFAULT_DEBOUNCE_SECONDS = 2.0
DEFAULT_STALE_SECONDS = 30.0 * 60.0
DEFAULT_LOCK_WAIT_SECONDS = 2.0 * 60.0 * 60.0
UpdateFunction = Callable[[Path, Sequence[str]], bool]


def _state_dir(root: Path) -> Path:
    return root / ".git" / "graphify-hook-state"


def _pending_dir(root: Path) -> Path:
    return _state_dir(root) / "pending"


def _lock_dir(root: Path) -> Path:
    return _state_dir(root) / "writer.lock"


def _log_dir(root: Path) -> Path:
    return root / ".git" / "graphify-hook-logs"


def is_relevant_path(path: str | Path) -> bool:
    """Return whether *path* can affect the code/document knowledge graph."""

    candidate = Path(str(path).replace("\\", "/"))
    lowered_parts = {part.lower() for part in candidate.parts}
    if lowered_parts.intersection(IGNORED_PARTS):
        return False
    return candidate.suffix.lower() in RELEVANT_SUFFIXES


def filter_relevant_paths(paths: Iterable[str | Path]) -> list[str]:
    """Normalize, filter, and de-duplicate changed paths deterministically."""

    relevant = {
        str(path).strip().replace("\\", "/")
        for path in paths
        if str(path).strip() and is_relevant_path(path)
    }
    return sorted(relevant)


def _atomic_write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    os.replace(temporary, path)


def _queue_request(root: Path, event: str, paths: Sequence[str]) -> Path:
    pending = _pending_dir(root)
    pending.mkdir(parents=True, exist_ok=True)
    request = pending / f"{time.time_ns()}-{os.getpid()}-{uuid.uuid4().hex}.json"
    payload = {
        "created_at": time.time(),
        "event": event,
        "paths": list(paths),
    }
    # Exclusive creation prevents two triggers from ever sharing a request.
    with request.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(payload, stream, sort_keys=True)
        stream.write("\n")
    return request


def _pending_requests(root: Path) -> list[Path]:
    pending = _pending_dir(root)
    if not pending.is_dir():
        return []
    return sorted(path for path in pending.glob("*.json") if path.is_file())


def _seconds_until_quiet(
    requests: Sequence[Path], debounce_seconds: float, now: float | None = None
) -> float:
    if not requests:
        return 0.0
    newest = max(path.stat().st_mtime for path in requests)
    current = time.time() if now is None else now
    return max(0.0, newest + debounce_seconds - current)


def _pid_is_running(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        # Never use os.kill(pid, 0) on Windows. CPython implements general
        # signals there with TerminateProcess semantics, so a liveness probe
        # can terminate the very process it is checking.
        import ctypes
        from ctypes import wintypes

        process_query_limited_information = 0x1000
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel32.OpenProcess.restype = wintypes.HANDLE
        kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel32.CloseHandle.restype = wintypes.BOOL
        handle = kernel32.OpenProcess(
            process_query_limited_information, False, int(pid)
        )
        if handle:
            kernel32.CloseHandle(handle)
            return True
        # Access denied still proves that a process owns the PID.
        return ctypes.get_last_error() == 5
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def _read_lock_owner(lock: Path) -> dict[str, object] | None:
    try:
        value = json.loads((lock / "owner.json").read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None
    return value if isinstance(value, dict) else None


def _recover_demonstrably_stale_lock(
    lock: Path, stale_seconds: float, now: float
) -> bool:
    """Remove only an old, same-host lock whose recorded process is dead."""

    owner = _read_lock_owner(lock)
    if owner is None:
        return False
    try:
        created_at = float(owner["created_at"])
        pid = int(owner["pid"])
        host = str(owner["host"])
    except (KeyError, TypeError, ValueError):
        return False
    if host != socket.gethostname() or now - created_at < stale_seconds:
        return False
    if _pid_is_running(pid):
        return False

    # Re-read immediately before removal so a replaced lock is never deleted.
    if _read_lock_owner(lock) != owner:
        return False
    try:
        (lock / "owner.json").unlink()
        lock.rmdir()
    except OSError:
        return False
    return True


def _try_acquire_lock(
    root: Path,
    stale_seconds: float = DEFAULT_STALE_SECONDS,
    now: float | None = None,
) -> bool:
    lock = _lock_dir(root)
    lock.parent.mkdir(parents=True, exist_ok=True)
    current = time.time() if now is None else now
    try:
        lock.mkdir()
    except FileExistsError:
        if not _recover_demonstrably_stale_lock(lock, stale_seconds, current):
            return False
        try:
            lock.mkdir()
        except FileExistsError:
            return False

    try:
        _atomic_write_json(
            lock / "owner.json",
            {
                "created_at": current,
                "host": socket.gethostname(),
                "pid": os.getpid(),
            },
        )
    except OSError:
        shutil.rmtree(lock, ignore_errors=True)
        return False
    return True


def _release_lock(root: Path) -> None:
    lock = _lock_dir(root)
    owner = _read_lock_owner(lock)
    if owner is None or owner.get("pid") != os.getpid():
        return
    try:
        (lock / "owner.json").unlink()
        lock.rmdir()
    except OSError:
        # A future worker can conservatively recover it after the stale period.
        pass


def _graphify_version() -> str:
    for distribution in ("graphifyy", "graphify"):
        try:
            return importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            continue
    return "unavailable"


def _graph_metadata_version(root: Path) -> str | None:
    graph_path = root / "graphify-out" / "graph.json"
    try:
        graph = json.loads(graph_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None
    if not isinstance(graph, dict):
        return None
    for container in (graph, graph.get("metadata"), graph.get("_meta")):
        if not isinstance(container, dict):
            continue
        for key in ("graphify_version", "generator_version"):
            value = container.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return None


def _write_version_status(root: Path) -> dict[str, object]:
    installed = _graphify_version()
    graph_version = _graph_metadata_version(root)
    status = {
        "checked_at": time.time(),
        "graph_metadata_version": graph_version,
        "installed_version": installed,
        "version_drift": graph_version is not None and graph_version != installed,
    }
    _atomic_write_json(_state_dir(root) / "version.json", status)
    return status


def _request_events(requests: Sequence[Path]) -> list[str]:
    events: set[str] = set()
    for request in requests:
        try:
            payload = json.loads(request.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            events.add("unknown")
            continue
        event = payload.get("event") if isinstance(payload, dict) else None
        events.add(str(event) if event else "unknown")
    return sorted(events)


def _run_graphify_update(
    root: Path,
    events: Sequence[str],
    command: Sequence[str] | None = None,
) -> bool:
    logs = _log_dir(root)
    logs.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
    log_path = logs / f"update-{timestamp}-{os.getpid()}.log"
    graphify_command = list(command or (sys.executable, "-m", "graphify", "update", str(root)))
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    environment["PYTHONUTF8"] = "1"
    version = _write_version_status(root)

    try:
        with log_path.open("w", encoding="utf-8", newline="\n") as log:
            log.write(f"[graphify hook] events: {', '.join(events)}\n")
            log.write(f"[graphify hook] command: {graphify_command!r}\n")
            log.write(
                "[graphify hook] installed version: "
                f"{version['installed_version']}; graph metadata version: "
                f"{version['graph_metadata_version'] or 'unrecorded'}\n"
            )
            if version["version_drift"]:
                log.write("[graphify hook] WARNING: graphify version drift detected\n")
            log.flush()
            completed = subprocess.run(
                graphify_command,
                cwd=root,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
            log.write(f"\n[graphify hook] exit code: {completed.returncode}\n")
    except OSError as error:
        try:
            log_path.write_text(
                f"[graphify hook] could not start update: {error}\n", encoding="utf-8"
            )
        except OSError:
            pass
        return False

    _write_version_status(root)
    return completed.returncode == 0


def _acquire_lock_until(
    root: Path, stale_seconds: float, lock_wait_seconds: float
) -> bool:
    deadline = time.monotonic() + lock_wait_seconds
    while time.monotonic() < deadline:
        if _try_acquire_lock(root, stale_seconds=stale_seconds):
            return True
        time.sleep(0.25)
    return False


def _worker(
    root: Path,
    debounce_seconds: float = DEFAULT_DEBOUNCE_SECONDS,
    stale_seconds: float = DEFAULT_STALE_SECONDS,
    lock_wait_seconds: float = DEFAULT_LOCK_WAIT_SECONDS,
    run_update: UpdateFunction = _run_graphify_update,
) -> bool:
    """Drain pending requests under one writer lock."""

    while True:
        requests = _pending_requests(root)
        if not requests:
            return True
        quiet_wait = _seconds_until_quiet(requests, debounce_seconds)
        if quiet_wait > 0:
            time.sleep(quiet_wait)

        if not _acquire_lock_until(root, stale_seconds, lock_wait_seconds):
            return False
        try:
            while True:
                requests = _pending_requests(root)
                if not requests:
                    return True
                quiet_wait = _seconds_until_quiet(requests, debounce_seconds)
                if quiet_wait > 0:
                    time.sleep(quiet_wait)
                    continue
                snapshot = list(requests)
                success = run_update(root, _request_events(snapshot))
                # The failure is durable in the UTF-8 log. Clear this snapshot
                # so multiple waiting workers do not retry the same failure.
                for request in snapshot:
                    try:
                        request.unlink()
                    except FileNotFoundError:
                        pass
                if not success:
                    return False
                # Requests created during the update remain and are debounced.
        finally:
            _release_lock(root)


def _spawn_worker(root: Path) -> None:
    command = [
        sys.executable,
        str(Path(__file__).resolve()),
        "--worker",
        "--root",
        str(root),
    ]
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    environment["PYTHONUTF8"] = "1"
    options: dict[str, object] = {
        "cwd": str(root),
        "env": environment,
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.DEVNULL,
        "stderr": subprocess.DEVNULL,
        "close_fds": True,
    }
    if os.name == "nt":
        options["creationflags"] = (
            subprocess.CREATE_NEW_PROCESS_GROUP
            | subprocess.DETACHED_PROCESS
            | subprocess.CREATE_NO_WINDOW
        )
    else:
        options["start_new_session"] = True
    subprocess.Popen(command, **options)


def schedule_update(
    root: Path,
    event: str,
    paths: Sequence[str],
    force: bool = False,
    foreground: bool = False,
) -> bool:
    relevant = filter_relevant_paths(paths)
    if not force and not relevant:
        return False
    _queue_request(root, event, relevant)
    if foreground:
        _worker(root)
    else:
        _spawn_worker(root)
    return True


def _read_stdin_paths() -> list[str]:
    return [line.strip() for line in sys.stdin.read().splitlines() if line.strip()]


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", help="changed repository paths")
    parser.add_argument("--event", default="manual", help="trigger name for logs")
    parser.add_argument("--all", action="store_true", help="schedule without paths")
    parser.add_argument(
        "--paths-from-stdin", action="store_true", help="read one changed path per line"
    )
    parser.add_argument("--foreground", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    root = args.root.resolve()
    if args.worker:
        return 0 if _worker(root) else 1
    paths = list(args.paths)
    if args.paths_from_stdin:
        paths.extend(_read_stdin_paths())
    try:
        schedule_update(
            root,
            event=args.event,
            paths=paths,
            force=args.all,
            foreground=args.foreground,
        )
    except OSError as error:
        # Hooks must never block Git/editor operations. Best-effort logging
        # keeps setup errors actionable while preserving that contract.
        try:
            logs = _log_dir(root)
            logs.mkdir(parents=True, exist_ok=True)
            (logs / "scheduler-error.log").write_text(
                f"{time.time()}: {error}\n", encoding="utf-8"
            )
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
