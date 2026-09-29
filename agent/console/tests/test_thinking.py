"""Thinking remains visible during work, without repeating streamed snapshots."""
import json
import sys
import threading

import pytest

from agent.console.agent_loop import AbortFlag, TurnCallbacks
from agent.console.subscriptions import SubscriptionAgent
from agent.console.vendor_events import VendorEvents


def callbacks():
    seen = {key: [] for key in ("thinking", "text", "answer", "start", "result")}
    cb = TurnCallbacks(
        on_thinking_delta=seen["thinking"].append,
        on_text_delta=seen["text"].append,
        on_assistant=seen["answer"].append,
        on_tool_start=lambda *args: seen["start"].append(args),
        on_tool_result=lambda *args: seen["result"].append(args),
    )
    return cb, seen


def test_codex_reasoning_snapshots_and_tools_are_visible_once():
    cb, seen = callbacks()
    stream = VendorEvents("codex-subscription", cb)
    for kind, text in [("started", "Check"), ("updated", "Check the image"),
                       ("completed", "Check the image")]:
        stream.feed(json.dumps({"type": "item." + kind, "item": {
            "id": "reason-1", "type": "reasoning", "text": text}}))
    for kind, status in [("started", "in_progress"), ("completed", "completed")]:
        stream.feed(json.dumps({"type": "item." + kind, "item": {
            "id": "tool-1", "type": "command_execution", "command": "python check.py",
            "status": status, "aggregated_output": "image open", "exit_code": 0}}))
    stream.feed(json.dumps({"type": "item.completed", "item": {
        "id": "answer-1", "type": "agent_message", "text": "Done"}}))
    stream.feed('{"type":"unknown","encrypted_content":"never display"}')
    stream.feed("not JSON")
    assert "".join(seen["thinking"]) == "Check the image"
    assert seen["text"] == ["Done"]
    assert seen["answer"] == ["Done"]
    assert seen["start"] == [("Shell", {"command": "python check.py"})]
    assert seen["result"] == [("Shell", True, "image open")]


def test_claude_partial_and_complete_messages_do_not_repeat_thinking():
    cb, seen = callbacks()
    stream = VendorEvents("claude-subscription", cb)

    def partial(payload):
        stream.feed(json.dumps({"type": "stream_event", "event": payload}))

    partial({"type": "message_start", "message": {"id": "message-1"}})
    partial({"type": "content_block_delta", "index": 0,
             "delta": {"type": "thinking_delta", "thinking": "Inspect the image"}})
    partial({"type": "content_block_delta", "index": 0,
             "delta": {"type": "signature_delta", "signature": "never display"}})
    partial({"type": "content_block_delta", "index": 1,
             "delta": {"type": "text_delta", "text": "Opened"}})
    stream.feed(json.dumps({"type": "assistant", "message": {
        "id": "message-1", "content": [
            {"type": "thinking", "thinking": "Inspect the image", "signature": "never display"},
            {"type": "text", "text": "Opened blobs"}]}}))
    stream.feed('{"type":"result","session_id":"session-1","result":"Opened blobs"}')
    assert "".join(seen["thinking"]) == "Inspect the image"
    assert "".join(seen["text"]) == "Opened blobs"
    assert seen["answer"] == ["Opened blobs"]
    assert stream.session_id == "session-1"


@pytest.mark.parametrize("provider", ["codex-subscription", "claude-subscription"])
def test_subscription_turn_streams_before_process_exits(provider, tmp_path, monkeypatch):
    from agent.console import subscriptions

    monkeypatch.setattr(subscriptions, "_executable", lambda _: sys.executable)
    monkeypatch.setattr(subscriptions, "subscription_status", lambda _: (True, "ready"))
    monkeypatch.setattr(subscriptions, "ensure_importable", lambda: tmp_path)
    agent = SubscriptionAgent(provider)
    cb, seen = callbacks()
    observed = threading.Event()
    cb.on_thinking_delta = lambda text: (seen["thinking"].append(text), observed.set())
    if provider == "codex-subscription":
        events = [
            {"type": "thread.started", "thread_id": "vendor-1"},
            {"type": "item.completed", "item": {"id": "r1", "type": "reasoning", "text": "Inspecting"}},
            {"type": "item.completed", "item": {"id": "a1", "type": "agent_message", "text": "Done"}},
        ]
    else:
        events = [
            {"type": "system", "session_id": "vendor-1"},
            {"type": "assistant", "message": {"id": "m1", "content": [
                {"type": "thinking", "thinking": "Inspecting"}]}},
            {"type": "assistant", "message": {"id": "m2", "content": [{"type": "text", "text": "Done"}]}},
            {"type": "result", "session_id": "vendor-1", "result": "Done"},
        ]
    release = tmp_path / "release"
    script = tmp_path / "vendor.py"
    script.write_text(
        "import json, pathlib, time, sys\n"
        f"events = json.loads({json.dumps(events)!r})\n"
        "for event in events[:2]: print(json.dumps(event), flush=True)\n"
        # Fill stderr beyond a pipe buffer; both streams must be drained.
        "sys.stderr.write('diagnostic' * 10000); sys.stderr.flush()\n"
        f"while not pathlib.Path({str(release)!r}).exists(): time.sleep(0.01)\n"
        "for event in events[2:]: print(json.dumps(event), flush=True)\n", encoding="utf-8")
    real_run = agent._run

    def run(command, workspace, stdin=None, **kwargs):
        if provider == "claude-subscription":
            assert "stream-json" in command and "--include-partial-messages" in command
        return real_run([sys.executable, str(script)], workspace, stdin, **kwargs)

    monkeypatch.setattr(agent, "_run", run)
    result = []
    worker = threading.Thread(target=lambda: result.append(agent.turn("Open blobs", cb)), daemon=True)
    worker.start()
    try:
        assert observed.wait(timeout=4)
        assert worker.is_alive(), "Thinking must arrive before the command exits"
        assert seen["answer"] == []
    finally:
        release.touch()
        worker.join(timeout=4)
        if worker.is_alive():
            agent.abort.set()
            worker.join(timeout=3)
    assert result == [True]
    assert seen["thinking"] == ["Inspecting"]
    assert seen["answer"] == ["Done"]
    assert agent.external_session_id == "vendor-1"


def test_failed_claude_result_is_not_a_success(monkeypatch, tmp_path):
    from agent.console import subscriptions
    monkeypatch.setattr(subscriptions, "_executable", lambda _: sys.executable)
    monkeypatch.setattr(subscriptions, "subscription_status", lambda _: (True, "ready"))
    monkeypatch.setattr(subscriptions, "ensure_importable", lambda: tmp_path)
    agent = SubscriptionAgent("claude-subscription")
    monkeypatch.setattr(agent, "_run", lambda *a, **k: (
        0, '{"type":"result","is_error":true,"errors":["limit reached"]}', ""))
    with pytest.raises(RuntimeError, match="limit reached"):
        agent._claude_turn("hello")


def test_claude_finishes_commentary_before_showing_tool_and_full_arguments():
    order = []
    cb = TurnCallbacks(on_assistant=lambda text: order.append(("answer", text)),
                       on_tool_preparing=lambda name: order.append(("preparing", name)),
                       on_tool_start=lambda name, args: order.append((name, args)))
    stream = VendorEvents("claude-subscription", cb)
    stream.feed(json.dumps({"type": "stream_event", "event": {
        "type": "content_block_start", "index": 1, "content_block": {
            "type": "tool_use", "id": "t1", "name": "Bash", "input": {}}}}))
    stream.feed(json.dumps({"type": "assistant", "message": {"id": "m1", "content": [
        {"type": "text", "text": "Opening the image"},
        {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "python open.py"}}]}}))
    assert order == [("preparing", "Bash"), ("answer", "Opening the image"),
                     ("Bash", {"command": "python open.py"})]
