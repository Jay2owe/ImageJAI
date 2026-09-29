from __future__ import annotations

import hashlib
import json

import pytest

from agent.console.evidence import (
    ArtifactStore,
    ArtifactTooLargeError,
    EvidenceError,
    EvidenceJournal,
    build_scientific_checkpoint,
    record_tool_pair,
)


def test_lossless_unicode_numbers_and_monotonic_sequence(tmp_path):
    journal = EvidenceJournal(tmp_path, "safe-session")
    payload = {"text": "μm 🧠 café", "integer": 2**60 + 17, "float": 0.123456789012345}
    first = journal.append("user", payload)
    second = journal.append("assistant", {"answer": "✓"})
    assert [first["sequence"], second["sequence"]] == [1, 2]
    assert journal.read()[0]["payload"] == payload
    assert journal.read()[0]["timestamp"].endswith("Z")


def test_tool_pair_correlation(tmp_path):
    journal = EvidenceJournal(tmp_path, "s1")
    call, result = record_tool_pair(journal, "measure", {"channel": 2}, {"mean": 1.5})
    assert call["payload"]["correlation_id"] == result["payload"]["correlation_id"]
    assert [e["type"] for e in journal.read()] == ["tool_call", "tool_result"]
    assert journal.query("tool_result")[0]["payload"]["ok"] is True


def test_oversize_event_has_structured_truncation_disclosure(tmp_path):
    journal = EvidenceJournal(tmp_path, "s1", max_event_bytes=900)
    original = {"large": "abcd" * 1000}
    event = journal.append("assistant", original)
    disclosure = event["payload"]
    assert disclosure["_truncated"] is True
    assert disclosure["reason"] == "event_size_limit"
    assert disclosure["original_bytes"] == len(json.dumps(original, ensure_ascii=False, separators=(",", ":")).encode())
    assert len((journal.path.read_bytes().splitlines()[0])) < 900


def test_artifact_hash_and_binary_is_not_embedded_in_log(tmp_path):
    store = ArtifactStore(tmp_path, "s1")
    journal = EvidenceJournal(tmp_path, "s1")
    raw = b"\x89PNG\r\n\x1a\n\x00secret"
    ref = store.save_bytes(raw, extension="png", media_type="image/png")
    event = journal.append("image_capture", {"description": "capture"}, [ref])
    assert ref["sha256"] == hashlib.sha256(raw).hexdigest()
    assert store.verify(ref)["ok"] is True
    log = journal.path.read_bytes()
    assert raw not in log
    assert event["artifact_refs"][0]["artifact_id"] == ref["artifact_id"]
    with pytest.raises(EvidenceError, match="ArtifactStore"):
        journal.append("image_capture", {"bytes": raw})


def test_traversal_and_unsafe_names_are_rejected(tmp_path):
    with pytest.raises(EvidenceError):
        EvidenceJournal(tmp_path, "../escape")
    store = ArtifactStore(tmp_path, "safe")
    with pytest.raises(EvidenceError):
        store.save_bytes(b"x", extension="../png")
    fake = {"path": "artifacts/../../escape", "sha256": "x", "size": 1}
    assert store.verify(fake)["ok"] is False


def test_corrupt_tail_is_skipped_and_reported(tmp_path):
    journal = EvidenceJournal(tmp_path, "s1")
    journal.append("user", {"one": 1})
    with journal.path.open("ab") as fh:
        fh.write(b'{"sequence":2,"broken"')
    events = journal.read()
    assert len(events) == 1
    assert journal.last_read_report["skipped"] == 1
    assert journal.last_read_report["malformed_lines"][0]["line"] == 2
    # A later append remains monotonic and is not joined onto a partial tail.
    assert journal.append("assistant", {"two": 2})["sequence"] == 2
    assert [event["sequence"] for event in journal.read()] == [1, 2]
    assert journal.last_read_report["skipped"] == 1


def test_bounds_are_enforced(tmp_path):
    journal = EvidenceJournal(tmp_path, "s1", max_string_chars=5, max_items=2, max_depth=2)
    with pytest.raises(EvidenceError, match="string"):
        journal.append("user", {"x": "123456"})
    with pytest.raises(EvidenceError, match="list"):
        journal.append("user", [1, 2, 3])
    with pytest.raises(EvidenceError, match="nesting"):
        journal.append("user", {"x": {"y": {"z": 1}}})
    store = ArtifactStore(tmp_path, "s2", max_bytes=2)
    with pytest.raises(ArtifactTooLargeError):
        store.save_bytes(b"123")
    with pytest.raises(EvidenceError):
        journal.append("user", {"bad": float("nan")})


def test_checkpoint_preserves_scientific_state_exactly(tmp_path):
    store = ArtifactStore(tmp_path, "s1")
    result_ref = store.save_text("Area,Mean\n2.5,7.0\n", extension="csv", media_type="text/csv")
    macro = {
        "macro": 'run("Set Scale...", "distance=10 known=2.5 unit=µm");',
        "params": {"distance_px": 10, "known": 2.5, "threshold": 65535},
        "units": {"known": "µm", "area": "µm²"},
    }
    checkpoint = build_scientific_checkpoint(
        image_id="img-α", image_revision=17, dataset_tokens=["ds:a", "rev:17"],
        c=2, z=14, t=3,
        calibration={"pixel_width": 0.325, "unit": "µm", "frame_interval_s": 30},
        roi={"name": "cell 🧠", "bounds": [1, 2, 30, 40]}, macros=[macro],
        result_artifacts=[result_ref], approvals=[{"tool": "run_macro", "approved": True}],
        pending_jobs=[{"id": "job-1", "state": "running"}],
        decisions=[{"choice": "Otsu", "reason": "bimodal histogram"}],
    )
    assert checkpoint["image"] == {"id": "img-α", "revision": 17}
    assert checkpoint["position"] == {"c": 2, "z": 14, "t": 3}
    assert checkpoint["macros"][0] == macro
    assert checkpoint["result_artifacts"][0]["sha256"] == result_ref["sha256"]
    journal = EvidenceJournal(tmp_path, "s1")
    journal.append("checkpoint", checkpoint, [result_ref])
    assert journal.read()[0]["payload"] == checkpoint


def test_checkpoint_requires_macro_contract_and_artifact_hash():
    common = dict(image_id="i", image_revision=1, dataset_tokens=[], c=1, z=1, t=1,
                  calibration={}, roi=None, approvals=[], pending_jobs=[], decisions=[])
    with pytest.raises(EvidenceError, match="missing"):
        build_scientific_checkpoint(**common, macros=[{"macro": "x"}], result_artifacts=[])
    with pytest.raises(EvidenceError, match="sha256"):
        build_scientific_checkpoint(**common, macros=[], result_artifacts=[{"path": "x"}])
