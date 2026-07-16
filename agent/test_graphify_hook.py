from __future__ import annotations

import importlib.util
import json
import os
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "imagejai_graphify_hook", ROOT / "scripts" / "graphify_hook.py"
)
assert SPEC is not None and SPEC.loader is not None
graphify_hook = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(graphify_hook)

HELPER_SPEC = importlib.util.spec_from_file_location(
    "imagejai_graphify_incremental", ROOT / "scripts" / "graphify_incremental.py"
)
assert HELPER_SPEC is not None and HELPER_SPEC.loader is not None
graphify_incremental = importlib.util.module_from_spec(HELPER_SPEC)
HELPER_SPEC.loader.exec_module(graphify_incremental)


def _make_root(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / ".git").mkdir(parents=True)
    return root


def test_relevant_suffix_filter_is_deterministic() -> None:
    paths = [
        "src/Z.java",
        "docs/notes.md",
        "target/imagej-ai.jar",
        "graphify-out/graph.json",
        "image.tif",
        "src/Z.java",
        r"agent\helper.py",
    ]
    assert graphify_hook.filter_relevant_paths(paths) == [
        "agent/helper.py",
        "docs/notes.md",
        "src/Z.java",
    ]


def test_debounce_uses_newest_request_timestamp(tmp_path: Path) -> None:
    older = tmp_path / "older.json"
    newer = tmp_path / "newer.json"
    older.write_text("{}", encoding="utf-8")
    newer.write_text("{}", encoding="utf-8")
    os.utime(older, (100.0, 100.0))
    os.utime(newer, (101.0, 101.0))
    assert graphify_hook._seconds_until_quiet(
        [older, newer], debounce_seconds=2.0, now=101.5
    ) == 1.5


def test_request_batch_aggregates_paths_and_full_request_dominates(tmp_path: Path) -> None:
    root = _make_root(tmp_path)
    first = graphify_hook._queue_request(
        root, "post-commit", [r"agent\b.py", "agent/a.py", "agent/a.py"]
    )
    second = graphify_hook._queue_request(
        root, "post-checkout", ["src/Z.java", "agent/b.py"]
    )

    events, paths, full = graphify_hook._request_batch([first, second])

    assert events == ["post-checkout", "post-commit"]
    assert paths == ["agent/a.py", "agent/b.py", "src/Z.java"]
    assert full is False

    all_request = graphify_hook._queue_request(root, "manual-all", [], full=True)
    events, paths, full = graphify_hook._request_batch(
        [first, second, all_request]
    )
    assert events == ["manual-all", "post-checkout", "post-commit"]
    assert paths == []
    assert full is True


def test_empty_schedule_is_a_full_request_and_uses_public_update(
    tmp_path: Path, monkeypatch
) -> None:
    root = _make_root(tmp_path)
    monkeypatch.setattr(graphify_hook, "_try_acquire_worker_claim", lambda _root: None)

    assert graphify_hook.schedule_update(root, "post-checkout", [])
    events, paths, full = graphify_hook._request_batch(
        graphify_hook._pending_requests(root)
    )
    assert events == ["post-checkout"]
    assert paths == []
    assert full is True

    command, path_file, mode = graphify_hook._prepare_graphify_command(
        root, paths, full
    )
    assert command == [
        sys.executable,
        "-m",
        "graphify",
        "update",
        str(root),
        "--force",
    ]
    assert path_file is None
    assert mode == "full-public"


def test_incremental_command_uses_bounded_path_file_and_cleans_it(
    tmp_path: Path, monkeypatch
) -> None:
    root = _make_root(tmp_path)
    captured = {}

    def fake_run(command, **kwargs):
        path_file = Path(command[-1])
        captured["command"] = list(command)
        captured["path_file"] = path_file
        captured["payload"] = json.loads(path_file.read_text(encoding="utf-8"))
        assert kwargs["cwd"] == root
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(graphify_hook.subprocess, "run", fake_run)

    assert graphify_hook._run_graphify_update(
        root,
        ["post-commit"],
        [r"agent\b.py", "agent/a.py", "agent/a.py"],
        False,
    )

    assert captured["payload"] == {
        "version": 1,
        "paths": ["agent/a.py", "agent/b.py"],
    }
    assert captured["command"][1].endswith("graphify_incremental.py")
    assert captured["command"][-2] == "--paths-file"
    assert not captured["path_file"].exists()
    assert list((root / ".git" / "graphify-hook-state").rglob("changed-paths-*.json")) == []
    content = next(graphify_hook._log_dir(root).glob("update-*.log")).read_text(
        encoding="utf-8"
    )
    assert "mode: incremental-private-api" in content
    assert "changed paths: 3" in content


def test_incremental_temp_file_is_cleaned_when_process_start_fails(
    tmp_path: Path, monkeypatch
) -> None:
    root = _make_root(tmp_path)
    monkeypatch.setattr(
        graphify_hook.subprocess,
        "run",
        lambda *args, **kwargs: (_ for _ in ()).throw(OSError("cannot start")),
    )

    assert not graphify_hook._run_graphify_update(
        root, ["post-commit"], ["agent/a.py"], False
    )
    assert list((root / ".git" / "graphify-hook-state").rglob("changed-paths-*.json")) == []
    content = next(graphify_hook._log_dir(root).glob("update-*.log")).read_text(
        encoding="utf-8"
    )
    assert "could not start update: cannot start" in content


def test_incremental_helper_rejects_incompatible_private_api(tmp_path: Path) -> None:
    def incompatible(root: Path, *, changed_paths, force):
        return True

    try:
        graphify_incremental._invoke_rebuild(
            tmp_path, [Path("agent/a.py")], rebuild=incompatible
        )
    except RuntimeError as error:
        assert "missing block_on_lock" in str(error)
    else:  # pragma: no cover - assertion guard
        raise AssertionError("incompatible Graphify API must fail closed")


def test_incremental_helper_evicts_all_legacy_nodes_for_changed_source(
    tmp_path: Path,
) -> None:
    root = _make_root(tmp_path)
    graph_path = root / "graphify-out" / "graph.json"
    graph_path.parent.mkdir()
    graph_path.write_text(
        json.dumps(
            {
                "nodes": [
                    {"id": "legacy", "source_file": "agent/tool.py"},
                    {"id": "keep", "source_file": "agent/other.py"},
                ],
                "links": [],
            }
        ),
        encoding="utf-8",
    )

    def mocked_rebuild(root_arg, *, changed_paths, force, block_on_lock):
        assert root_arg == root
        assert force is True
        assert block_on_lock is True
        changed = {path.as_posix() for path in changed_paths}
        graph = json.loads(graph_path.read_text(encoding="utf-8"))
        graph["nodes"] = [
            node
            for node in graph["nodes"]
            if node.get("source_file") not in changed
        ]
        graph["nodes"].append(
            {"id": "current", "source_file": "agent/tool.py"}
        )
        graph_path.write_text(json.dumps(graph), encoding="utf-8")
        return True

    assert graphify_incremental._invoke_rebuild(
        root, [Path("agent/tool.py")], rebuild=mocked_rebuild
    )
    nodes = json.loads(graph_path.read_text(encoding="utf-8"))["nodes"]
    assert [node["id"] for node in nodes] == ["keep", "current"]


def test_stale_lock_recovery_requires_dead_same_host_owner(tmp_path: Path) -> None:
    root = _make_root(tmp_path)
    lock = graphify_hook._lock_dir(root)
    lock.mkdir(parents=True)
    (lock / "owner.json").write_text(
        json.dumps({"created_at": 0.0, "host": socket.gethostname(), "pid": 2**30}),
        encoding="utf-8",
    )
    assert graphify_hook._try_acquire_lock(root, stale_seconds=10.0, now=100.0)
    graphify_hook._release_lock(root)

    lock.mkdir()
    (lock / "owner.json").write_text(
        json.dumps(
            {"created_at": 0.0, "host": socket.gethostname(), "pid": os.getpid()}
        ),
        encoding="utf-8",
    )
    assert not graphify_hook._try_acquire_lock(root, stale_seconds=10.0, now=100.0)


def test_concurrent_workers_run_one_writer_for_burst(tmp_path: Path) -> None:
    root = _make_root(tmp_path)
    graphify_hook._queue_request(root, "post-edit", ["agent/a.py"])
    graphify_hook._queue_request(root, "post-commit", ["src/A.java"])
    calls: list[tuple[tuple[str, ...], tuple[str, ...], bool]] = []
    calls_lock = threading.Lock()

    def fake_update(
        _root: Path, events: list[str], paths: list[str], full: bool
    ) -> bool:
        with calls_lock:
            calls.append((tuple(events), tuple(paths), full))
        time.sleep(0.05)
        return True

    workers = [
        threading.Thread(
            target=graphify_hook._worker,
            kwargs={
                "root": root,
                "debounce_seconds": 0.0,
                "stale_seconds": 60.0,
                "lock_wait_seconds": 2.0,
                "run_update": fake_update,
            },
        )
        for _ in range(2)
    ]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join(timeout=3.0)
        assert not worker.is_alive()

    assert calls == [
        (("post-commit", "post-edit"), ("agent/a.py", "src/A.java"), False)
    ]
    assert graphify_hook._pending_requests(root) == []


def test_concurrent_burst_schedules_only_one_detached_worker(
    tmp_path: Path, monkeypatch
) -> None:
    root = _make_root(tmp_path)
    spawned: list[str] = []
    spawned_lock = threading.Lock()
    barrier = threading.Barrier(24)

    def fake_spawn(_root: Path, claim_token: str) -> None:
        with spawned_lock:
            spawned.append(claim_token)

    monkeypatch.setattr(graphify_hook, "_spawn_worker", fake_spawn)

    def schedule(index: int) -> None:
        barrier.wait()
        assert graphify_hook.schedule_update(
            root, "post-edit", [f"agent/burst_{index}.py"]
        )

    schedulers = [threading.Thread(target=schedule, args=(index,)) for index in range(24)]
    for scheduler in schedulers:
        scheduler.start()
    for scheduler in schedulers:
        scheduler.join(timeout=3.0)
        assert not scheduler.is_alive()

    assert len(spawned) == 1
    assert len(graphify_hook._pending_requests(root)) == 24
    assert graphify_hook._release_worker_claim(root, spawned[0])


def test_worker_claim_recovers_dead_owner_and_expired_live_lease(
    tmp_path: Path,
) -> None:
    root = _make_root(tmp_path)
    claim = graphify_hook._worker_claim_dir(root)
    claim.mkdir(parents=True)
    (claim / "owner.json").write_text(
        json.dumps(
            {
                "created_at": 99.0,
                "heartbeat_at": 99.0,
                "host": socket.gethostname(),
                "pid": 2**30,
                "token": "dead",
            }
        ),
        encoding="utf-8",
    )
    dead_recovery = graphify_hook._try_acquire_worker_claim(
        root, stale_seconds=10.0, now=100.0
    )
    assert dead_recovery is not None
    assert graphify_hook._release_worker_claim(root, dead_recovery)

    claim.mkdir()
    (claim / "owner.json").write_text(
        json.dumps(
            {
                "created_at": 0.0,
                "heartbeat_at": 0.0,
                "host": socket.gethostname(),
                "pid": os.getpid(),
                "token": "expired",
            }
        ),
        encoding="utf-8",
    )
    lease_recovery = graphify_hook._try_acquire_worker_claim(
        root, stale_seconds=10.0, now=100.0
    )
    assert lease_recovery is not None
    assert graphify_hook._release_worker_claim(root, lease_recovery)


def test_claim_release_recheck_drains_request_queued_during_exit_race(
    tmp_path: Path, monkeypatch
) -> None:
    root = _make_root(tmp_path)
    graphify_hook._queue_request(root, "initial", ["agent/initial.py"])
    token = graphify_hook._try_acquire_worker_claim(root)
    assert token is not None
    spawned: list[str] = []
    calls = 0

    monkeypatch.setattr(
        graphify_hook,
        "_spawn_worker",
        lambda _root, claim_token: spawned.append(claim_token),
    )

    def fake_worker(_root: Path) -> bool:
        nonlocal calls
        calls += 1
        for request in graphify_hook._pending_requests(root):
            request.unlink()
        if calls == 1:
            # The scheduler sees the active claim and correctly declines to
            # spawn. The exiting worker must release, recheck, and reclaim.
            assert graphify_hook.schedule_update(
                root, "exit-race", ["src/ArrivedDuringExit.java"]
            )
        return True

    assert graphify_hook._run_claimed_worker(root, token, worker=fake_worker)
    assert calls == 2
    assert spawned == []
    assert graphify_hook._pending_requests(root) == []
    assert not graphify_hook._worker_claim_dir(root).exists()


def test_failed_update_keeps_request_for_later_recovery(tmp_path: Path) -> None:
    root = _make_root(tmp_path)
    graphify_hook._queue_request(root, "post-edit", ["agent/retry.py"])

    assert not graphify_hook._worker(
        root,
        debounce_seconds=0.0,
        stale_seconds=60.0,
        lock_wait_seconds=1.0,
        run_update=lambda _root, _events, _paths, _full: False,
    )
    assert len(graphify_hook._pending_requests(root)) == 1


def test_failed_update_writes_utf8_actionable_log(tmp_path: Path) -> None:
    root = _make_root(tmp_path)
    command = [
        sys.executable,
        "-c",
        "import sys; print('failure: μ'); raise SystemExit(7)",
    ]
    assert not graphify_hook._run_graphify_update(root, ["test"], command=command)
    logs = list(graphify_hook._log_dir(root).glob("update-*.log"))
    assert len(logs) == 1
    content = logs[0].read_text(encoding="utf-8")
    assert "failure: μ" in content
    assert "exit code: 7" in content


def test_missing_graph_generator_metadata_is_reported_as_version_drift(
    tmp_path: Path, monkeypatch
) -> None:
    root = _make_root(tmp_path)
    (root / "graphify-out").mkdir()
    (root / "graphify-out" / "graph.json").write_text("{}", encoding="utf-8")
    monkeypatch.setattr(graphify_hook, "_graphify_version", lambda: "9.9.9")

    status = graphify_hook._write_version_status(root)

    assert status["graph_metadata_version"] is None
    assert status["version_drift"] is True
