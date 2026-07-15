from __future__ import annotations

import importlib.util
import json
import os
import socket
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
    calls: list[tuple[str, ...]] = []
    calls_lock = threading.Lock()

    def fake_update(_root: Path, events: list[str]) -> bool:
        with calls_lock:
            calls.append(tuple(events))
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

    assert calls == [("post-commit", "post-edit")]
    assert graphify_hook._pending_requests(root) == []


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
