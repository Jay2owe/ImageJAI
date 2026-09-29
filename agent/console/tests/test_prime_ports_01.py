from types import SimpleNamespace
import pytest
from agent.console.turn_queue import PromptQueue, inject_steering
from agent.console.agent_loop import TurnCallbacks

def test_queue_modes_round_trip_and_no_duplicate_delivery():
    q = PromptQueue()
    q.put("change channel", "steer")
    q.put("measure next", "follow_up")
    q = PromptQueue(q.snapshot())
    assert [r["text"] for r in q.take("steer", all_matches=True)] == ["change channel"]
    assert q.take("steer") == []
    assert q.take("follow_up")[0]["text"] == "measure next"

def test_queue_bounds_and_cancel():
    q = PromptQueue()
    for _ in range(32): q.put("x")
    with pytest.raises(ValueError): q.put("y")
    q.clear()
    assert not q.snapshot()
    with pytest.raises(ValueError): q.put("x" * 65537)

def test_safe_boundary_preserves_complete_pairs():
    q = PromptQueue()
    q.put("use channel 2")
    agent = SimpleNamespace(messages=[{"role":"assistant", "tool_calls":[{"id":"a"}]},
                                      {"role":"tool", "tool_call_id":"a", "content":"done"}])
    seen = []
    cb = TurnCallbacks(take_steering=lambda:[r["text"] for r in q.take("steer",all_matches=True)], on_user=seen.append)
    assert inject_steering(agent, cb) == ["use channel 2"]
    assert agent.messages[1]["role"] == "tool"
    assert agent.messages[2]["content"] == "use channel 2"
    assert inject_steering(agent, cb) == []
