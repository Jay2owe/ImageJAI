"""Tests for the pure image-analysis-aware compaction planner."""
from __future__ import annotations

import copy
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import compaction as compact  # noqa: E402


def _side(plan, role):
    if any(message.get("role") == role for message in plan.head_messages):
        return "head"
    if any(message.get("role") == role for message in plan.recent_messages):
        return "recent"
    return None


def test_atomic_openai_tool_pair_is_never_split():
    messages = [
        {"role": "user", "content": "measure"},
        {"role": "assistant", "tool_calls": [
            {"id": "call-7", "type": "function", "function": {"name": "results", "arguments": "{}"}}
        ]},
        {"role": "tool", "tool_call_id": "call-7", "content": "Mean=12.50 AU"},
        {"role": "assistant", "content": "The measurement is ready."},
    ]
    # Try every useful budget around the cut.  The call and result must always
    # be in the same output partition.
    for budget in range(0, compact.estimate_tokens(messages) + 5):
        plan = compact.plan_compaction(messages, budget)
        call_side = "head" if any(m.get("tool_calls") for m in plan.head_messages) else "recent"
        result_side = _side(plan, "tool")
        assert call_side == result_side


def test_anthropic_content_blocks_are_an_atomic_pair():
    messages = [
        {"role": "assistant", "content": [{"type": "tool_use", "id": "u1", "name": "state", "input": {}}]},
        {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "u1", "content": "ok"}]},
        {"role": "assistant", "content": "done"},
    ]
    budget = compact.estimate_tokens(messages[-1:])
    plan = compact.plan_compaction(messages, budget)
    assert [m["role"] for m in plan.head_messages] == ["assistant", "user"]
    assert not plan.malformed


def test_inline_images_are_evicted_before_ordinary_prose_and_keep_metadata():
    pixels = "data:image/png;base64," + ("QUJD" * 3000)
    prose = "Use the approved Otsu workflow, then inspect the segmentation."
    messages = [{
        "role": "user",
        "content": [
            {"type": "text", "text": prose},
            {"type": "image_url", "image_url": {"url": pixels}},
        ],
        "dimensions": "2048x1024",
        "source_image": "image-a1b2.tif",
        "revision": 3,
        "c": 2,
        "z": 7,
        "t": 4,
    }]
    plan = compact.plan_compaction(messages, 100)
    rendered = str(plan.recent_messages + plan.head_messages)
    assert pixels not in rendered
    assert prose in rendered
    assert len(plan.evicted_artifacts) == 1
    artifact = plan.evicted_artifacts[0]
    assert artifact.kind == "inline_image"
    for field in ("dimensions=2048x1024", "source_image=image-a1b2.tif", "revision=3",
                  "C=2", "Z=7", "T=4", "sha256="):
        assert field in artifact.marker
    assert plan.estimated_tokens_after < plan.estimated_tokens_before


def test_duplicate_screenshot_has_an_explicit_duplicate_marker():
    data = "data:image/png;base64," + ("YWJj" * 100)
    messages = [
        {"role": "user", "content": [{"type": "image_url", "image_url": {"url": data}}]},
        {"role": "user", "content": [{"type": "image_url", "image_url": {"url": data}}]},
    ]
    plan = compact.plan_compaction(messages, 9999)
    assert [item.kind for item in plan.evicted_artifacts] == ["inline_image", "duplicate_screenshot"]
    assert all("COMPACTION_EVICTED" in item.marker for item in plan.evicted_artifacts)


def test_measurements_macros_parameters_and_identifiers_are_verbatim():
    macro = 'setThreshold(100, 255);\nrun("Measure");'
    measurement = "ROI roi-7: Mean intensity = 1200.375 AU; Area = 44.20 µm^2"
    messages = [
        {"role": "user", "content": "Analyse image-deadbeef.tif at C=2 Z=7 T=4."},
        {"role": "assistant", "tool_calls": [{
            "id": "m1", "function": {"name": "run_macro", "arguments": {"macro": macro, "sigma": "1.5 µm"}}
        }]},
        {"role": "tool", "tool_call_id": "m1", "content": measurement},
        {"role": "assistant", "content": {"results": [{"roi_id": "roi-7", "mean": 1200.375, "area": 44.20}],
                                             "csv_ref": "AI_Exports/results.csv", "sha256": "abc123"}},
    ]
    plan = compact.plan_compaction(messages, 1)
    facts = "\n".join(plan.critical_facts)
    assert measurement in plan.critical_facts
    assert macro in facts
    assert "1.5 µm" in facts
    assert "image-deadbeef.tif" in facts
    assert "1200.375" in facts and "44.2" in facts
    assert "AI_Exports/results.csv" in facts and "abc123" in facts


def test_long_tool_log_is_marked_but_exact_error_and_number_survive():
    log = ("ordinary trace line\n" * 300) + "ERROR threshold failed at 17.25 AU"
    messages = [{"role": "tool", "tool_call_id": "orphan", "content": log}]
    plan = compact.plan_compaction(messages, 10)
    joined = str(plan.head_messages + plan.recent_messages)
    assert log not in joined
    assert plan.evicted_artifacts[0].kind == "long_log"
    assert "ERROR threshold failed at 17.25 AU" in plan.critical_facts
    assert any("orphan" in warning for warning in plan.warnings)


def test_malformed_tool_pairs_are_flagged_and_kept_conservatively_atomic():
    missing = [
        {"role": "assistant", "tool_calls": [{"id": "a", "function": {"name": "state", "arguments": "{}"}}]},
        {"role": "user", "content": "still waiting"},
    ]
    plan = compact.plan_compaction(missing, 1)
    assert plan.malformed
    assert len(plan.recent_messages) == 2
    assert any("missing result" in warning for warning in plan.warnings)

    orphan = compact.plan_compaction([{"role": "tool", "tool_call_id": "no-call", "content": "x"}], 5)
    assert orphan.malformed
    assert any("orphan or mismatched" in warning for warning in orphan.warnings)


def test_one_oversized_turn_is_retained_whole():
    messages = [
        {"role": "assistant", "tool_calls": [{"id": "big", "function": {"name": "run", "arguments": "{}"}}]},
        {"role": "tool", "tool_call_id": "big", "content": "x" * 3500},
    ]
    plan = compact.plan_compaction(messages, 1)
    assert plan.head_messages == []
    assert len(plan.recent_messages) == 2
    assert any("oversized recent atomic unit" in warning for warning in plan.warnings)


def test_old_oversized_turn_is_compacted_before_short_recent_turns():
    messages = [
        {"role": "user", "content": "Calibration is 0.325 um/pixel.\n" + "context " * 20000},
        {"role": "assistant", "content": "The image is a synthetic stack."},
        {"role": "user", "content": "Continue with the projection."},
    ]
    plan = compact.plan_compaction(messages, 20_000)
    assert len(plan.head_messages) == 1
    assert len(plan.recent_messages) == 2
    assert "0.325" in "\n".join(plan.critical_facts)


def test_plan_is_deterministic_and_does_not_mutate_input():
    messages = [
        {"content": [{"image_url": {"url": "data:image/png;base64," + "QQ==" * 100}, "type": "image_url"}],
         "role": "user"},
        {"role": "assistant", "content": "decision: keep calibration 0.25 µm/px"},
    ]
    pristine = copy.deepcopy(messages)
    first = compact.plan_compaction(messages, 20)
    second = compact.plan_compaction(messages, 20)
    assert messages == pristine
    assert first == second
    assert compact.estimate_tokens(messages) == compact.estimate_tokens(pristine)


def test_artifact_writer_reference_is_put_in_marker():
    writes = []
    def writer(payload, metadata):
        writes.append((payload, metadata))
        return "AI_Exports/compaction/capture-1.png"

    data = "data:image/png;base64," + "QUJD" * 100
    plan = compact.plan_compaction([{"role": "user", "content": data}], 10, writer)
    assert len(writes) == 1
    assert "ref=AI_Exports/compaction/capture-1.png" in plan.evicted_artifacts[0].marker
    assert writes[0][1]["sha256"] == plan.evicted_artifacts[0].sha256


def test_token_estimate_and_threshold_boundaries():
    assert compact.estimate_tokens("1234") == 1
    assert compact.estimate_tokens("12345") == 2
    # Stable dict ordering.
    assert compact.estimate_tokens({"b": 2, "a": 1}) == compact.estimate_tokens({"a": 1, "b": 2})
    assert not compact.should_compact(80, context_window=100, reserve_tokens=20)
    assert compact.should_compact(81, context_window=100, reserve_tokens=20)
    with pytest.raises(ValueError):
        compact.should_compact(1, context_window=100, reserve_tokens=100)
    with pytest.raises(ValueError):
        compact.plan_compaction([], -1)
