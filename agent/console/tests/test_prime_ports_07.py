from types import SimpleNamespace
from agent.console.side_questions import build_side_agent, SIDE_PROMPT

def test_side_history_and_capabilities_are_independent():
    main = SimpleNamespace(provider="test", model="test", effort="high", messages=[{"content":"main"}])
    fresh = SimpleNamespace(tools=[lambda:None], _base_system_prompt="original", messages=[], external_session_id="unsafe")
    calls = []
    def factory(*args, **kwargs):
        calls.append((args, kwargs))
        return fresh
    side = build_side_agent(main, "secret", factory)
    assert side.tools == [] and side.host_code_tools == frozenset()
    assert side.fiji_connection is None and side.external_session_id is None
    assert side.messages[0]["content"] == SIDE_PROMPT
    side.messages.append({"content":"side answer"})
    assert main.messages == [{"content":"main"}]
    assert calls[0][1]["effort"] == "high"

def test_subscription_side_does_not_resume_parent():
    main = SimpleNamespace(provider="codex-subscription", model="default", effort="medium")
    fresh = SimpleNamespace(tools=[], messages=[], external_session_id="parent", resume_vendor_latest=True)
    side = build_side_agent(main, factory=lambda *a,**k:fresh)
    assert side._tool_instructions == SIDE_PROMPT
    assert side.external_session_id is None and not side.resume_vendor_latest

def test_thinking_deltas_are_batched_without_losing_text():
    from agent.console.side_questions import SideThoughtBuffer
    emitted=[]
    buffer=SideThoughtBuffer(emitted.append)
    for _ in range(1000): buffer.push("reasoning ")
    buffer.flush()
    assert "".join(emitted) == "reasoning "*1000
    assert len(emitted) < 10
