"""Conversation replay retains full evidence and hides control frames."""
import json

from agent.console.replay import replay_entries


def event(event_type, **payload):
    return {"type": event_type, "payload": payload}


def test_full_replay_keeps_thinking_calls_and_full_messages_in_order():
    long = "analysis " * 600 + "THE END"
    entries = replay_entries([
        event("user", text="Open Blobs"),
        event("thinking", text="Check the sample"),
        event("tool_call", tool="run_macro", arguments={"code": 'run("Blobs");'}, correlation_id="one"),
        event("tool_result", tool="run_macro", correlation_id="one", ok=True,
              result='{"ok":true,"result":{"newImages":["blobs.gif"]}}'),
        event("assistant", text=long),
    ], [{"role": "assistant", "content": long}])
    assert [e.kind for e in entries] == ["user", "thinking", "tool_call", "tool_result", "assistant"]
    assert entries[-1].text == long and len(entries[-1].text) > 800
    assert entries[3].arguments == {"code": 'run("Blobs");'}


def test_replay_clear_boundary_and_no_duplicate_receipts():
    events = [event("user", text="old"), event("assistant", text="old answer"),
              event("decision", kind="conversation_cleared"), event("user", text="new"),
              event("assistant", text='<imagejai-action>{"tool":"run_macro"}</imagejai-action>')]
    assert [e.text for e in replay_entries(events)] == ["new"]
    assert replay_entries(events[:3], [{"role": "user", "content": "old"}]) == []


def test_old_messages_replay_complete_content_blocks_and_receipt_once():
    receipt = {"id": "one", "tool": "run_macro", "arguments": {"code": 'run("Blobs");'},
               "ok": True, "result": "complete raw result"}
    frame = "<imagejai-result>" + json.dumps(receipt) + "</imagejai-result>"
    messages = [{"role": "user", "content": "Open Blobs"},
                {"role": "assistant", "content": [{"type": "text", "text": "<imagejai-action>{}</imagejai-action>"}]},
                {"role": "user", "name": "imagejai_tool_result", "content": frame},
                {"role": "assistant", "content": [{"type": "text", "text": "x" * 1600 + "END"}]}]
    entries = replay_entries([], messages, {"one": receipt})
    assert [e.kind for e in entries] == ["user", "tool_call", "tool_result", "assistant"]
    assert entries[2].text == "complete raw result"
    assert entries[-1].text.endswith("END")


def test_tool_artifacts_remain_lazy_and_keep_saved_summary():
    entry, = replay_entries([event("tool_result", tool="get_results", ok=True,
        result={"artifact": "artifacts/" + "a" * 32 + ".txt", "head": "preview",
                "summary": "Found 12 measurements"})])
    assert entry.text == "preview" and entry.summary == "Found 12 measurements"
    assert entry.artifact["path"].endswith(".txt")


def test_console_replays_artifact_text_and_tool_details_without_reading_return(tmp_path, monkeypatch):
    from agent.console import config, tui
    from agent.console.config import ConsoleConfig
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config, "CONFIG_PATH", tmp_path / "console.json")
    app = tui.ConsoleApp(ConsoleConfig())
    session = app.store.create()
    app._select_session(session)
    text = "Full message " * 4000 + "COMPLETE END"
    app._record_text_evidence("assistant", text)
    result = json.dumps({"ok": True, "result": {"rows": 12, "data": "x" * 40000}})
    app._record_tool_evidence("table", "get_results", {}, True, result)
    logs = []
    app._log = logs.append
    app._render_session_messages()
    assert any("COMPLETE END" in str(line) for line in logs)
    detail, = app._tool_result_details.values()
    assert detail.artifact is not None and detail.raw == ""
    assert detail.read() == result
    assert len(app.evidence.read_events()) == 3  # rendering does not append evidence


def test_replay_rejects_artifact_path_outside_session(tmp_path, monkeypatch):
    from agent.console import config, tui
    from agent.console.config import ConsoleConfig
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config, "CONFIG_PATH", tmp_path / "console.json")
    app = tui.ConsoleApp(ConsoleConfig())
    app._select_session(app.store.create())
    app.evidence.append("assistant", {"text_artifact": "../../outside.txt"})
    logs = []
    app._log = logs.append
    app._render_session_messages()
    assert any("unavailable" in str(line) for line in logs)
