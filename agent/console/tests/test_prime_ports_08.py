import pytest
from agent.console.agent_loop import _clip
from agent.console.wrapped_tools import result_message, ActionRequest

def test_preview_is_bounded_preserves_head_and_tail_and_discloses():
    source = "START\n" + "x"*10000 + "\nFINAL ERROR: missing calibration"
    preview = _clip(source, 4000)
    assert len(preview) == 4000
    assert preview.startswith("START") and preview.endswith("missing calibration")
    assert "incomplete preview" in preview and "10,039" in preview
    receipt = {"id":"a", "tool":"results", "ok":True, "result":source}
    assert "missing calibration" in result_message(receipt)["content"]
    assert receipt["result"] == source

@pytest.mark.parametrize("limit", [0, 1, 10, 100])
def test_small_budgets_are_honest_and_short(limit):
    assert len(_clip("x"*1000, limit)) <= limit

def test_short_returns_unchanged():
    assert _clip("three rows") == "three rows"
