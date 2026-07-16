#!/usr/bin/env python3
"""Schedule safe, non-blocking Graphify updates for this repository.

Every trigger writes a small request file. One race-safe claim admits a single
detached worker for the burst; that worker waits for a quiet debounce window
and serializes full public updates or changed-path rebuilds behind the writer
lock. The caller always returns promptly; failures are recorded under
``.git/graphify-hook-logs`` and their requests remain queued.
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
from pathlib import Path, PurePosixPath
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
DEFAULT_CLAIM_STALE_SECONDS = 30.0 * 60.0
MAX_PATH_FILE_BYTES = 4_000_000
UpdateFunction = Callable[[Path, Sequence[str], Sequence[str], bool], bool]


def _state_dir(root: Path) -> Path:
    return root / ".git" / "graphify-hook-state"


def _pending_dir(root: Path) -> Path:
    return _state_dir(root) / "pending"


def _lock_dir(root: Path) -> Path:
    return _state_dir(root) / "writer.lock"


def _worker_claim_dir(root: Path) -> Path:
    return _state_dir(root) / "worker.claim"


def _log_dir(root: Path) -> Path:
    return root / ".git" / "graphify-hook-logs"


def _normalise_repository_path(path: str | Path) -> str | None:
    """Return one safe repository-relative POSIX path, or ``None``."""

    text = str(path).strip().replace("\\", "/")
    candidate = PurePosixPath(text)
    if (
        not text
        or candidate.is_absolute()
        or any(part in ("", ".", "..") for part in candidate.parts)
        or (candidate.parts and candidate.parts[0].endswith(":"))
    ):
        return None
    return candidate.as_posix()


def is_relevant_path(path: str | Path) -> bool:
    """Return whether *path* can affect the code/document knowledge graph."""

    normalized = _normalise_repository_path(path)
    if normalized is None:
        return False
    candidate = PurePosixPath(normalized)
    lowered_parts = {part.lower() for part in candidate.parts}
    if lowered_parts.intersection(IGNORED_PARTS):
        return False
    return candidate.suffix.lower() in RELEVANT_SUFFIXES


def filter_relevant_paths(paths: Iterable[str | Path]) -> list[str]:
    """Normalize, filter, and de-duplicate changed paths deterministically."""

    relevant = set()
    for path in paths:
        normalized = _normalise_repository_path(path)
        if normalized is not None and is_relevant_path(normalized):
            relevant.add(normalized)
    return sorted(relevant)


def _atomic_write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    os.replace(temporary, path)


def _queue_request(
    root: Path,
    event: str,
    paths: Sequence[str],
    full: bool | None = None,
) -> Path:
    pending = _pending_dir(root)
    pending.mkdir(parents=True, exist_ok=True)
    request = pending / f"{time.time_ns()}-{os.getpid()}-{uuid.uuid4().hex}.json"
    normalized = filter_relevant_paths(paths)
    full_request = not normalized if full is None else bool(full)
    payload = {
        "created_at": time.time(),
        "event": event,
        "full": full_request,
        "paths": [] if full_request else normalized,
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


def _read_worker_claim(root: Path) -> dict[str, object] | None:
    claim = _worker_claim_dir(root)
    try:
        value = json.loads((claim / "owner.json").read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None
    return value if isinstance(value, dict) else None


def _recover_stale_or_dead_worker_claim(
    root: Path, stale_seconds: float, now: float
) -> bool:
    """Remove only a dead same-host claim or an expired bounded lease."""

    claim = _worker_claim_dir(root)
    owner = _read_worker_claim(root)
    if owner is None:
        try:
            expired = now - claim.stat().st_mtime >= stale_seconds
        except OSError:
            return False
        if not expired:
            return False
    else:
        try:
            heartbeat_at = float(owner.get("heartbeat_at", owner["created_at"]))
            pid = int(owner["pid"])
            host = str(owner["host"])
        except (KeyError, TypeError, ValueError):
            return False
        dead_same_host = host == socket.gethostname() and not _pid_is_running(pid)
        expired = now - heartbeat_at >= stale_seconds
        if not dead_same_host and not expired:
            return False
        # Do not delete a claim that was renewed or replaced during recovery.
        if _read_worker_claim(root) != owner:
            return False
    try:
        owner_path = claim / "owner.json"
        if owner_path.exists():
            owner_path.unlink()
        claim.rmdir()
    except OSError:
        return False
    return True


def _try_acquire_worker_claim(
    root: Path,
    stale_seconds: float = DEFAULT_CLAIM_STALE_SECONDS,
    now: float | None = None,
    pid: int | None = None,
) -> str | None:
    """Atomically claim responsibility for draining the pending queue."""

    claim = _worker_claim_dir(root)
    claim.parent.mkdir(parents=True, exist_ok=True)
    current = time.time() if now is None else now
    try:
        claim.mkdir()
    except FileExistsError:
        if not _recover_stale_or_dead_worker_claim(root, stale_seconds, current):
            return None
        try:
            claim.mkdir()
        except FileExistsError:
            return None
    token = uuid.uuid4().hex
    try:
        _atomic_write_json(
            claim / "owner.json",
            {
                "created_at": current,
                "heartbeat_at": current,
                "host": socket.gethostname(),
                "pid": os.getpid() if pid is None else pid,
                "token": token,
            },
        )
    except OSError:
        shutil.rmtree(claim, ignore_errors=True)
        return None
    return token


def _adopt_worker_claim(
    root: Path, token: str, pid: int, now: float | None = None
) -> bool:
    """Transfer/renew a claim only when its unguessable token still matches."""

    owner = _read_worker_claim(root)
    if owner is None or owner.get("token") != token:
        return False
    current = time.time() if now is None else now
    updated = dict(owner)
    updated["heartbeat_at"] = current
    updated["host"] = socket.gethostname()
    updated["pid"] = pid
    # Re-read immediately before replacement to avoid adopting a new claim.
    if _read_worker_claim(root) != owner:
        return False
    try:
        _atomic_write_json(_worker_claim_dir(root) / "owner.json", updated)
    except OSError:
        return False
    return True


def _release_worker_claim(root: Path, token: str) -> bool:
    claim = _worker_claim_dir(root)
    owner = _read_worker_claim(root)
    if owner is None or owner.get("token") != token:
        return False
    retired = claim.with_name(f".{claim.name}.released-{token}")
    try:
        # Rename the complete claim atomically. Unlinking owner.json before
        # rmdir leaves a window where a recovering scheduler can create a new
        # claim that the old owner then accidentally removes.
        os.replace(claim, retired)
    except OSError:
        return False
    shutil.rmtree(retired, ignore_errors=True)
    return True


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
        # Missing generator metadata is itself drift: the graph cannot prove
        # that it was produced by the installed Graphify version.
        "version_drift": graph_version != installed,
    }
    _atomic_write_json(_state_dir(root) / "version.json", status)
    return status


def _request_batch(requests: Sequence[Path]) -> tuple[list[str], list[str], bool]:
    """Aggregate one durable queue snapshot; any full request dominates."""

    events: set[str] = set()
    paths: set[str] = set()
    full = False
    for request in requests:
        try:
            payload = json.loads(request.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            events.add("unknown")
            full = True
            continue
        if not isinstance(payload, dict):
            events.add("unknown")
            full = True
            continue
        event = payload.get("event")
        events.add(str(event) if event else "unknown")
        raw_paths = payload.get("paths")
        if payload.get("full") is True or not isinstance(raw_paths, list):
            full = True
            continue
        normalized = filter_relevant_paths(raw_paths)
        if not normalized:
            full = True
        else:
            paths.update(normalized)
    return sorted(events), ([] if full else sorted(paths)), full


def _request_events(requests: Sequence[Path]) -> list[str]:
    """Compatibility helper for diagnostics and older focused tests."""

    return _request_batch(requests)[0]


def _write_changed_paths_file(root: Path, paths: Sequence[str]) -> Path:
    """Write a bounded JSON handoff under Git state, never on the command line."""

    normalized = filter_relevant_paths(paths)
    if not normalized:
        raise ValueError("incremental Graphify update requires changed paths")
    payload = {"version": 1, "paths": normalized}
    encoded = (
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
    if len(encoded) > MAX_PATH_FILE_BYTES:
        raise ValueError(
            "changed-path payload is {} bytes; maximum is {}".format(
                len(encoded), MAX_PATH_FILE_BYTES
            )
        )
    handoffs = _state_dir(root) / "handoffs"
    handoffs.mkdir(parents=True, exist_ok=True)
    target = handoffs / "changed-paths-{}-{}.json".format(os.getpid(), uuid.uuid4().hex)
    with target.open("xb") as stream:
        stream.write(encoded)
    return target


def _remove_changed_paths_file(path: Path | None) -> None:
    if path is None:
        return
    try:
        path.unlink()
    except FileNotFoundError:
        pass
    try:
        path.parent.rmdir()
    except OSError:
        pass


def _prepare_graphify_command(
    root: Path,
    paths: Sequence[str],
    full: bool,
    command: Sequence[str] | None = None,
) -> tuple[list[str], Path | None, str]:
    if command is not None:
        return list(command), None, "custom"
    if full or not paths:
        return (
            [sys.executable, "-m", "graphify", "update", str(root), "--force"],
            None,
            "full-public",
        )
    helper = Path(__file__).resolve().with_name("graphify_incremental.py")
    if not helper.is_file():
        raise RuntimeError(
            "incremental Graphify helper is missing: {}; queue retained".format(helper)
        )
    path_file = _write_changed_paths_file(root, paths)
    return (
        [
            sys.executable,
            str(helper),
            "--root",
            str(root),
            "--paths-file",
            str(path_file),
        ],
        path_file,
        "incremental-private-api",
    )


def _run_graphify_update(
    root: Path,
    events: Sequence[str],
    paths: Sequence[str] = (),
    full: bool = False,
    command: Sequence[str] | None = None,
) -> bool:
    logs = _log_dir(root)
    logs.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
    log_path = logs / f"update-{timestamp}-{os.getpid()}.log"
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    environment["PYTHONUTF8"] = "1"
    version = _write_version_status(root)
    path_file: Path | None = None

    try:
        with log_path.open("w", encoding="utf-8", newline="\n") as log:
            log.write(f"[graphify hook] events: {', '.join(events)}\n")
            log.write(f"[graphify hook] changed paths: {len(paths)}\n")
            log.write(
                "[graphify hook] installed version: "
                f"{version['installed_version']}; graph metadata version: "
                f"{version['graph_metadata_version'] or 'unrecorded'}\n"
            )
            if version["version_drift"]:
                log.write("[graphify hook] WARNING: graphify version drift detected\n")
            try:
                graphify_command, path_file, mode = _prepare_graphify_command(
                    root, paths, full, command=command
                )
            except (OSError, RuntimeError, ValueError) as error:
                log.write("[graphify hook] ERROR preparing update: {}\n".format(error))
                return False
            log.write(f"[graphify hook] mode: {mode}\n")
            log.write(f"[graphify hook] command: {graphify_command!r}\n")
            log.flush()
            try:
                completed = subprocess.run(
                    graphify_command,
                    cwd=root,
                    env=environment,
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    check=False,
                )
            finally:
                _remove_changed_paths_file(path_file)
                path_file = None
            log.write(f"\n[graphify hook] exit code: {completed.returncode}\n")
    except OSError as error:
        try:
            log_path.write_text(
                f"[graphify hook] could not start update: {error}\n", encoding="utf-8"
            )
        except OSError:
            pass
        return False
    finally:
        _remove_changed_paths_file(path_file)

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
                events, paths, full = _request_batch(snapshot)
                success = run_update(root, events, paths, full)
                if not success:
                    # Failed work remains durable. A later trigger can recover
                    # it after the failed worker releases its bounded claim.
                    return False
                for request in snapshot:
                    try:
                        request.unlink()
                    except FileNotFoundError:
                        pass
                # Requests created during the update remain and are debounced.
        finally:
            _release_lock(root)


def _run_claimed_worker(
    root: Path,
    claim_token: str,
    worker: Callable[..., bool] = _worker,
) -> bool:
    """Drain, release, then recheck so admission races cannot strand work."""

    if not _adopt_worker_claim(root, claim_token, os.getpid()):
        return False
    token = claim_token
    while True:
        success = worker(root)
        _release_worker_claim(root, token)
        if not success:
            return False
        if not _pending_requests(root):
            return True
        # A request may have arrived after the worker's last empty check but
        # before claim release. Reclaim it here unless a scheduler won first.
        next_token = _try_acquire_worker_claim(root)
        if next_token is None:
            return True
        token = next_token
        if not _adopt_worker_claim(root, token, os.getpid()):
            return False


def _spawn_worker(root: Path, claim_token: str) -> None:
    command = [
        sys.executable,
        str(Path(__file__).resolve()),
        "--worker",
        "--claim-token",
        claim_token,
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
    try:
        process = subprocess.Popen(command, **options)
    except OSError:
        _release_worker_claim(root, claim_token)
        raise
    # The scheduler owns the claim while spawning; transfer it to the child
    # before this short-lived hook process returns.
    # If transfer loses a race with child startup, leave the token in place.
    # The child can still adopt it; otherwise dead/stale recovery will clear
    # the bounded lease. Releasing here could admit a second live worker.
    _adopt_worker_claim(root, claim_token, process.pid)


def schedule_update(
    root: Path,
    event: str,
    paths: Sequence[str],
    force: bool = False,
    foreground: bool = False,
) -> bool:
    supplied = [str(path) for path in paths if str(path).strip()]
    relevant = filter_relevant_paths(supplied)
    full = bool(force or not supplied)
    if not full and not relevant:
        return False
    _queue_request(root, event, relevant, full=full)
    if foreground:
        return _worker(root)
    else:
        claim_token = _try_acquire_worker_claim(root)
        if claim_token is not None:
            _spawn_worker(root, claim_token)
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
    parser.add_argument("--claim-token", default="", help=argparse.SUPPRESS)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    root = args.root.resolve()
    if args.worker:
        if not args.claim_token:
            return 1
        return 0 if _run_claimed_worker(root, args.claim_token) else 1
    paths = list(args.paths)
    if args.paths_from_stdin:
        paths.extend(_read_stdin_paths())
    try:
        scheduled = schedule_update(
            root,
            event=args.event,
            paths=paths,
            force=args.all,
            foreground=args.foreground,
        )
        if args.foreground and not scheduled:
            return 1
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
