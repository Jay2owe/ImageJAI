"""Hermetic tests for the pure-Python reviewed harness store."""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import harness as hz  # noqa: E402


class Clock:
    def __init__(self):
        self.value = datetime(2026, 1, 1, tzinfo=timezone.utc)

    def __call__(self):
        return self.value

    def tick(self, seconds=1):
        self.value += timedelta(seconds=seconds)


def store(tmp_path, **kwargs):
    clock = kwargs.pop("clock", Clock())
    ids = iter(f"entry-{i}" for i in range(100))
    return hz.HarnessStore(
        tmp_path / "explicit" / "state.json",
        tmp_path / "explicit" / "refinements.jsonl",
        clock=clock,
        id_factory=lambda: next(ids),
        **kwargs,
    )


def proposal(s, **kwargs):
    fields = dict(kind="fact", scope="session", title="Image rule", content="Keep raw pixels")
    fields.update(kwargs)
    return s.propose(**fields)


def test_paths_are_injected_and_schema_and_jsonl_are_written(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path / "must-not-be-used"))
    s = store(tmp_path)
    entry = proposal(s)
    state = json.loads(s.state_path.read_text(encoding="utf-8"))
    assert state["schema_version"] == 1
    assert state["entries"][entry.id]["status"] == "candidate"
    lines = s.refinements_path.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1 and json.loads(lines[0])["action"] == "propose"
    assert not Path(os.environ["HOME"]).exists()


def test_atomic_replace_failure_preserves_previous_state(tmp_path, monkeypatch):
    s = store(tmp_path)
    item = proposal(s)
    original = s.state_path.read_bytes()

    def fail_replace(_source, _target):
        raise OSError("simulated crash before replace")

    monkeypatch.setattr(hz.os, "replace", fail_replace)
    with pytest.raises(OSError):
        s.update(item.id, expected_version=1, title="not committed")
    assert s.state_path.read_bytes() == original
    assert not list(s.state_path.parent.glob("*.tmp"))


def test_corrupt_and_oversized_state_fail_closed(tmp_path):
    s = store(tmp_path)
    s.state_path.parent.mkdir(parents=True)
    s.state_path.write_text("not json", encoding="utf-8")
    with pytest.raises(hz.HarnessCorruptionError):
        s.list()
    assert s.state_path.read_text(encoding="utf-8") == "not json"
    s.state_path.write_bytes(b"x" * (s.MAX_STATE_BYTES + 1))
    with pytest.raises(hz.HarnessCorruptionError):
        proposal(s)


def test_crud_versions_deprecates_instead_of_removing_and_rolls_back(tmp_path):
    s = store(tmp_path)
    one = proposal(s)
    two = s.update(one.id, expected_version=1, title="Better title")
    assert two.version == 2 and s.get(one.id).title == "Better title"
    with pytest.raises(hz.HarnessConflictError):
        s.update(one.id, expected_version=1, title="stale")
    dead = s.delete(one.id, expected_version=2, reviewer="Jamie", reason="superseded")
    assert dead.status == "deprecated" and len(s.list()) == 1
    restored = s.rollback(one.id, 1, expected_version=3, reviewer="Jamie", reason="needed")
    assert restored.version == 4
    assert restored.status == "candidate" and restored.title == "Image rule"


def test_promotion_requires_review_and_never_jumps(tmp_path):
    s = store(tmp_path)
    item = proposal(s)
    with pytest.raises(hz.HarnessValidationError):
        s.promote(item.id, expected_version=1, reviewer="", evidence="proof")
    with pytest.raises(hz.HarnessValidationError):
        s.promote(item.id, expected_version=1, reviewer="J", evidence="proof",
                  target_status="project_approved")
    item = s.promote(item.id, expected_version=1, reviewer="J", evidence="observed")
    assert item.status == "session_confirmed"
    item = s.promote(item.id, expected_version=2, reviewer="J", evidence=["reproduced"])
    item = s.promote(item.id, expected_version=3, reviewer="PI", evidence="agreed protocol")
    assert item.status == "validated" and item.version == 4
    with pytest.raises(hz.HarnessValidationError):
        s.promote(item.id, expected_version=4, reviewer="PI", evidence="again")


def test_scope_transition_is_explicit_and_reviewed(tmp_path):
    s = store(tmp_path)
    item = proposal(s)
    with pytest.raises(hz.HarnessValidationError):
        s.update(item.id, expected_version=1, scope="project")
    changed = s.transition_scope(item.id, "project", expected_version=1,
                                 reviewer="J", evidence="project config")
    assert changed.scope == "project" and changed.metadata["last_scope_reviewer"] == "J"


def test_expiry_status_and_scope_filters(tmp_path):
    clock = Clock()
    s = store(tmp_path, clock=clock)
    expired = proposal(s, expires_at=clock.value - timedelta(seconds=1))
    live = proposal(s, scope="project", title="live", content="live")
    promoted = s.promote(live.id, expected_version=1, reviewer="J", evidence="x")
    got = s.retrieve("", allowed_statuses=["session_confirmed"], scopes=["project"])
    assert [e.id for e in got] == [promoted.id]
    assert expired.id not in [e.id for e in s.retrieve(include_expired=False)]
    assert expired.id in [e.id for e in s.retrieve(include_expired=True)]


def test_applicability_is_exact_and_rank_is_deterministic(tmp_path):
    clock = Clock()
    s = store(tmp_path, clock=clock)
    a = proposal(s, title="segment nuclei", content="watershed",
                 applicability={"task": "segment", "state": {"image_open": True}})
    clock.tick()
    b = proposal(s, title="segment cells", content="threshold",
                 applicability={"task": "segment", "state": {"image_open": True}})
    b = s.promote(b.id, expected_version=1, reviewer="J", evidence="validated")
    assert len(s.retrieve("segment", task="segment", state={"image_open": False})) == 0
    found = s.retrieve("nuclei watershed", task="segment", state={"image_open": True})
    # Status strength is the first deterministic rank key.
    assert [x.id for x in found] == [b.id, a.id]


def test_conflicts_are_disclosed_and_never_merged(tmp_path):
    s = store(tmp_path)
    first = proposal(s, title="Use Otsu", conflicts=["entry-1"])
    second = proposal(s, title="Never use Otsu")
    result = s.search("Otsu")
    assert len(result) == 2
    assert result[0].id != result[1].id
    assert result.conflicts[0]["entry_id"] == first.id
    assert result.conflicts[0]["conflicting_entry"]["id"] == second.id


def test_digest_is_model_aware_bounded_private_and_excludes_evidence(tmp_path):
    s = store(tmp_path)
    for index in range(8):
        entry = proposal(s, title=f"SECRET title {index}", content="SECRET content " + "x" * 300,
                         evidence=["RAW_EVIDENCE_SECRET"], source="SECRET source")
        s.promote(entry.id, expected_version=entry.version, reviewer="tester",
                  evidence="verified in this session")
    calls = []

    def sanitize(text):
        calls.append(text)
        return text.replace("SECRET", "[private]")

    small = s.digest("title", {}, {"reliability": "low", "context_window": 4000,
                                   "max_digest_chars": 900}, sanitize)
    large = s.digest("title", {}, {"reliability": "high", "context_window": 100000}, sanitize)
    assert len(small) <= 900 and small.count("\n-") <= 3
    assert large.count("\n-") > small.count("\n-")
    assert "SECRET" not in small + large
    assert "RAW_EVIDENCE" not in small + large
    assert "session_confirmed/session" in small and "id=entry-" in small and "v=2" in small
    assert any("SECRET title" in value for value in calls)


def test_field_entry_and_mapping_bounds(tmp_path):
    s = store(tmp_path, max_entries=1)
    with pytest.raises(hz.HarnessValidationError):
        proposal(s, title="x" * (s.MAX_TITLE + 1))
    proposal(s)
    with pytest.raises(hz.HarnessValidationError):
        proposal(s, title="second")
    s2 = store(tmp_path / "other")
    with pytest.raises(hz.HarnessValidationError):
        proposal(s2, metadata={"blob": "x" * s2.MAX_MAPPING_BYTES})


def test_two_store_instances_reject_a_stale_write(tmp_path):
    clock = Clock()
    state = tmp_path / "shared" / "state.json"
    log = tmp_path / "shared" / "events.jsonl"
    s1 = hz.HarnessStore(state, log, clock=clock, id_factory=lambda: "shared")
    s2 = hz.HarnessStore(state, log, clock=clock, id_factory=lambda: "unused")
    item = proposal(s1)
    s1.update(item.id, expected_version=1, content="writer one")
    with pytest.raises(hz.HarnessConflictError):
        s2.update(item.id, expected_version=1, content="writer two")
    assert s2.get(item.id).content == "writer one"


def test_mutating_return_values_cannot_mutate_store(tmp_path):
    s = store(tmp_path)
    item = proposal(s, metadata={"nested": {"safe": True}})
    item.metadata["nested"]["safe"] = False
    fetched = s.get(item.id)
    assert fetched.metadata["nested"]["safe"] is True


def test_bad_log_fails_closed_when_rollback_needs_history(tmp_path):
    s = store(tmp_path)
    item = proposal(s)
    s.refinements_path.write_text("{bad json}\n", encoding="utf-8")
    with pytest.raises(hz.HarnessCorruptionError):
        s.rollback(item.id, 1, expected_version=1, reviewer="J", reason="test")
    with pytest.raises(hz.HarnessCorruptionError):
        s.update(item.id, expected_version=1, title="must not write")
    assert s.get(item.id).version == 1


def test_digest_never_injects_unreviewed_candidates(tmp_path):
    s = store(tmp_path)
    proposal(s, title="Unreviewed", content="do a dangerous guess")
    digest = s.digest("dangerous", {}, {"reliability": "low"})
    assert "Unreviewed" not in digest
    assert "dangerous guess" not in digest
