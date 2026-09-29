"""Wrapped messages execute Fiji tools across providers, without shell calls."""
import json
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from agent.console.agent_loop import AbortFlag, ConsoleAgent, TurnCallbacks
from agent.console.wrapped_tools import (
    ACTION_OPEN, ACTION_CLOSE, RESULT_OPEN, RESULT_CLOSE, ActionDisplay,
    ActionError, ActionRequest, execute_action, execute_function, parse_action,
    result_message, validate_arguments, capture_attachment,
)


def action(tool="run_macro", arguments=None, ident="a1"):
    return ACTION_OPEN + json.dumps({"id": ident, "tool": tool,
                                    "arguments": {} if arguments is None else arguments}) + ACTION_CLOSE


def wrapped_agent(provider="claude-subscription"):
    calls = []

    def run_macro(code: str) -> dict:
        """Run a Fiji macro."""
        calls.append(code)
        return {"ok": True, "result": {"success": True, "newImages": ["blobs.gif"]}}

    def run_script(code: str, language: str) -> dict:
        """Run a Fiji script."""
        calls.append((code, language))
        return {"ok": True}

    agent = ConsoleAgent.__new__(ConsoleAgent)
    agent.provider, agent.model = provider, "test-model"
    agent.tools = [run_macro, run_script]
    agent.host_code_tools = {"run_script"}
    agent.messages, agent.action_receipts = [], {}
    agent.abort = AbortFlag()
    agent.fiji_connection = None
    agent.tool_result_filter = None
    agent._effort_kwargs = {}
    return agent, calls


def test_complete_macro_message_preserves_quotes_and_newlines():
    code = 'run("Blobs");\nprint("a \\\"quoted\\\" value");'
    request = parse_action(action(arguments={"code": code}))
    assert request.arguments["code"] == code
    for text in ["Here is an example:\n" + action(), "```json\n" + action() + "\n```", "normal chat"]:
        assert parse_action(text) is None


@pytest.mark.parametrize("text", [
    ACTION_OPEN + '{}', ACTION_OPEN + '{}' + ACTION_CLOSE,
    ACTION_OPEN + '{"id":"a","id":"b","tool":"run_macro","arguments":{}}' + ACTION_CLOSE,
    ACTION_OPEN + '{"id":"a","tool":"run_macro","arguments":{"code":NaN}}' + ACTION_CLOSE,
    action(arguments=["bad"]), action(ident="a id"), action() + action(),
    ACTION_OPEN + 'x' * 65536 + ACTION_CLOSE,
], ids=["incomplete", "missing-fields", "duplicate-fields", "nonfinite-number",
        "wrong-arguments", "invalid-id", "multiple-actions", "oversized"])
def test_malformed_frames_never_execute(text):
    with pytest.raises(ActionError):
        parse_action(text)


def test_streamed_action_is_hidden_and_thinking_remains_visible():
    text, thinking, answers, preparing = [], [], [], []
    cb = TurnCallbacks(on_text_delta=text.append, on_thinking_delta=thinking.append,
                       on_assistant=answers.append, on_tool_preparing=preparing.append)
    display = ActionDisplay(cb)
    filtered = display.callbacks()
    wire = action(arguments={"code": 'run("Blobs");'})
    for char in wire:
        filtered.on_text_delta(char)
    filtered.on_thinking_delta("Opening Blobs")
    filtered.on_assistant(wire)
    filtered.on_text_delta("Opened ")
    filtered.on_text_delta("Blobs")
    filtered.on_assistant("Opened Blobs")
    assert text == ["Opened ", "Blobs"]
    assert thinking == ["Opening Blobs"]
    assert answers == ["Opened Blobs"]
    assert preparing == ["run_macro"]


def test_duplicate_id_replays_after_resume_and_conflicts_are_refused():
    agent, calls = wrapped_agent()
    request = parse_action(action(arguments={"code": 'run("Blobs");'}))
    first = execute_action(agent, request, TurnCallbacks())
    assert first["ok"]
    agent.messages.append(result_message(first))
    # Saved history alone is sufficient to recover the receipt.
    agent.action_receipts = {}
    assert execute_action(agent, request, TurnCallbacks())["replayed"]
    conflict = execute_action(agent, ActionRequest("a1", "run_macro", {"code": "different"}), TurnCallbacks())
    assert not conflict["ok"]
    assert calls == ['run("Blobs");']
    # Compaction can remove the text history without losing the receipt.
    agent.messages.clear()
    assert execute_action(agent, request, TurnCallbacks())["replayed"]
    assert calls == ['run("Blobs");']


def test_invalid_arguments_and_unapproved_scripts_do_not_run():
    agent, calls = wrapped_agent()
    for ident, args in [("a1", {"code": 42}), ("a2", {"code": "test", "extra": True}), ("a3", {})]:
        assert not execute_action(agent, ActionRequest(ident, "run_macro", args), TurnCallbacks())["ok"]
    req = ActionRequest("script", "run_script", {"code": "print(1)", "language": "groovy"})
    assert not execute_action(agent, req, TurnCallbacks())["ok"]
    assert calls == []
    approved = ActionRequest("approved", "run_script", req.arguments)
    assert execute_action(agent, approved, TurnCallbacks(on_approval=lambda *_: True))["ok"]


def test_unknown_tools_and_shell_requests_are_refused():
    agent, calls = wrapped_agent()
    def run_shell(command: str):
        """A shell tool present in the registry, forbidden on this route."""
        calls.append(command)
    agent.tools.append(run_shell)
    for tool in ["run_shell", "missing_tool"]:
        assert not execute_action(agent, ActionRequest(tool, tool, {}), TurnCallbacks())["ok"]
    assert not calls


def test_capture_contract_default_and_boolean_integer_validation():
    def capture_image(max_size: int):
        """Capture the active image."""
    assert validate_arguments(capture_image, {}) == {"max_size": 1024}
    with pytest.raises(ActionError):
        validate_arguments(capture_image, {"max_size": True})


def test_nested_macro_failure_and_string_errors_are_failures():
    agent, _ = wrapped_agent()
    def failed():
        """A failing macro."""
        return {"ok": True, "result": {"success": False, "error": "Bad command"}}
    def capture_image(max_size: int):
        """A failing capture."""
        return "ERROR: No image"
    agent.tools = [failed, capture_image]
    assert execute_function(agent, "failed", {})[0] is False
    assert execute_function(agent, "capture_image", {"max_size": 1024})[0] is False


def test_interrupt_returns_without_waiting_for_blocked_tool():
    agent, _ = wrapped_agent()
    entered, release = threading.Event(), threading.Event()
    def blocked():
        """A blocking desktop command."""
        entered.set()
        release.wait(timeout=3)
        return "late result"
    agent.tools = [blocked]
    outcome = []
    worker = threading.Thread(target=lambda: outcome.append(execute_function(agent, "blocked", {})))
    worker.start()
    assert entered.wait(timeout=1)
    start = time.monotonic()
    agent.abort.set()
    worker.join(timeout=0.5)
    release.set()
    assert not worker.is_alive()
    assert time.monotonic() - start < 0.5
    assert outcome == [(False, "Interrupted by user")]


class TextClient:
    """A model that uses the shared message route rather than native calls."""
    def __init__(self, replies):
        self.replies = iter(replies)
        self.inputs = []
    def chat_stream(self, messages, tools, model, **kwargs):
        assert tools == [], "Wrapped tools must also work on text-only models"
        assert all(message.get("name") != "imagejai_tool_result" for message in messages)
        self.inputs.append(list(messages))
        reply = next(self.replies)
        kwargs["on_delta"](reply)
        return reply
    def extract_tool_calls(self, reply):
        return []
    def extract_text(self, reply):
        return reply
    def append_assistant(self, messages, reply):
        messages.append({"role": "assistant", "content": reply})


# Covers every current provider without credentials, network calls or a model
# dependency: execution semantics belong to the wrapper, not the provider.
from agent.console.providers import PROVIDER_KEYS


@pytest.mark.parametrize("provider", PROVIDER_KEYS)
def test_shared_message_loop_for_every_provider(provider):
    agent, calls = wrapped_agent(provider)
    agent.client = TextClient([action(arguments={"code": 'run("Blobs");'}), "Blobs is open"])
    answers, visible, results, done = [], [], [], []
    cb = TurnCallbacks(on_assistant=answers.append, on_text_delta=visible.append,
                       on_tool_result=lambda *args: results.append(args), on_done=done.append)
    assert agent.turn("Open Blobs", cb)
    assert calls == ['run("Blobs");']
    assert answers == visible == ["Blobs is open"]
    assert results[0][0:2] == ("run_macro", True)
    assert done == [True]
    assert RESULT_OPEN in agent.client.inputs[1][-1]["content"]


@pytest.mark.parametrize("provider", ["codex-subscription", "claude-subscription"])
def test_subscription_wrapper_resumes_same_conversation_and_uses_stdin(provider, monkeypatch, tmp_path):
    from agent.console import subscriptions
    template, calls = wrapped_agent(provider)
    monkeypatch.setattr(subscriptions, "_import_registry", lambda: (template.tools, template.host_code_tools))
    monkeypatch.setattr(subscriptions, "_executable", lambda _: "vendor")
    monkeypatch.setattr(subscriptions, "subscription_status", lambda _: (True, "ready"))
    monkeypatch.setattr(subscriptions, "ensure_importable", lambda: tmp_path)
    agent = subscriptions.SubscriptionAgent(provider)
    commands, prompts, thinking, answers, tools = [], [], [], [], []
    replies = iter([action(arguments={"code": 'run("Blobs");'}), "Blobs is open"])

    def run(command, workspace, stdin=None, on_stdout=None):
        commands.append(command)
        prompts.append(stdin)
        reply = next(replies)
        if provider.startswith("codex"):
            events = [{"type": "thread.started", "thread_id": "same-session"},
                      {"type": "item.completed", "item": {"id": "r1", "type": "reasoning", "text": "Checking Fiji"}},
                      {"type": "item.completed", "item": {"id": "m1", "type": "agent_message", "text": reply}}]
        else:
            events = [{"type": "assistant", "session_id": "same-session", "message": {"id": "m1", "content": [
                {"type": "thinking", "thinking": "Checking Fiji"}, {"type": "text", "text": reply}]}},
                      {"type": "result", "session_id": "same-session", "result": reply}]
        lines = [json.dumps(event) for event in events]
        for line in lines:
            on_stdout(line)
        return 0, "\n".join(lines), ""

    monkeypatch.setattr(agent, "_run", run)
    cb = TurnCallbacks(on_assistant=answers.append, on_thinking_delta=thinking.append,
                       on_tool_start=lambda *args: tools.append(args))
    assert agent.turn("Open Blobs", cb)
    assert calls == ['run("Blobs");']
    assert answers == ["Blobs is open"]
    assert thinking == ["Checking Fiji", "Checking Fiji"]
    assert tools == [("run_macro", {"code": 'run("Blobs");'})]
    assert "same-session" in commands[1]
    assert ("resume" if provider.startswith("codex") else "--resume") in commands[1]
    assert all(ACTION_OPEN in prompt for prompt in prompts)
    assert RESULT_OPEN in prompts[1]
    assert all('run("Blobs");' not in " ".join(command) for command in commands)
    assert agent.external_session_id == "same-session"


def test_console_tools_use_selected_authenticated_connection_and_resolve_tokens(monkeypatch, tmp_path):
    from agent.console.workspace import ensure_importable
    ensure_importable()
    from gemma4_31b import registry, tools_fiji
    from agent.console.fiji import FijiConnection
    monkeypatch.setenv("IMAGEJAI_TCP_HOST", "127.0.0.1")
    monkeypatch.setenv("IMAGEJAI_TCP_PORT", "7746")
    connection = FijiConnection("127.0.0.1", 17746)
    connection.select_installation(tmp_path)
    commands = []
    addresses = []
    def send(payload, **options):
        commands.append(payload)
        addresses.append(options)
        return {"ok": True, "result": {"images": []}}
    monkeypatch.setattr(connection, "installation_root", lambda: tmp_path.resolve())
    connection._ij = SimpleNamespace(
        hello=lambda **_: {"ok": True, "result": {"compatibility": False}},
        imagej_command=send,
    )
    agent, _ = wrapped_agent()
    agent.tools = [tools_fiji.get_state]
    agent.fiji_connection = connection
    monkeypatch.setattr(registry, "_new_imagej_session", lambda: pytest.fail("Must use the console connection"))
    assert execute_action(agent, ActionRequest("state", "get_state", {}), TurnCallbacks())["ok"]
    assert commands == [{"command": "get_state"}]
    assert addresses == [{"host": "127.0.0.1", "port": 17746, "timeout": 60.0}]
    connection.select_installation(tmp_path / "other-fiji")
    refusal = execute_action(agent, ActionRequest("other", "get_state", {}), TurnCallbacks())
    assert not refusal["ok"]
    assert len(commands) == 1


def test_open_image_resolves_tokens_and_polls_pending_operation_without_reopening(monkeypatch, tmp_path):
    from agent.console.workspace import ensure_importable
    ensure_importable()
    from gemma4_31b import tools_fiji
    from agent.console.fiji import FijiConnection
    monkeypatch.setenv("IMAGEJAI_TCP_HOST", "127.0.0.1")
    monkeypatch.setenv("IMAGEJAI_TCP_PORT", "7746")
    connection = FijiConnection("127.0.0.1", 17746)
    image = tmp_path / "real-image.tif"
    token = "image-example.tif"
    connection.token_map = SimpleNamespace(
        resolve=lambda value: SimpleNamespace(path=image) if value == token else None,
        mapping=lambda: {token: image},
    )
    commands = []
    def send(payload, **options):
        assert options["host"] == connection.host and options["port"] == connection.port
        commands.append(payload)
        if "operation_id" in payload:
            assert payload == {"command": "open_image", "operation_id": "pending-open"}
            return {"ok": True, "result": {"success": True, "title": "opened"}}
        assert payload == {"command": "open_image", "path": str(image)}
        return {"ok": False, "error": {"code": "operation_in_progress"},
                "operation": {"command": "open_image", "operation_id": "pending-open"}}
    connection._ij = SimpleNamespace(
        hello=lambda **_: {"ok": True, "result": {"compatibility": False}}, imagej_command=send)
    agent, _ = wrapped_agent()
    agent.tools = [tools_fiji.open_image, tools_fiji.poll_operation]
    agent.fiji_connection = connection
    opening = execute_action(agent, ActionRequest("open", "open_image", {"path": token}), TurnCallbacks())
    assert not opening["ok"]
    pending = json.loads(opening["result"])["operation"]
    assert pending["operation_id"] == "pending-open"
    assert execute_action(agent, ActionRequest("poll", "poll_operation", pending), TurnCallbacks())["ok"]
    assert len(commands) == 2
    assert not execute_action(agent, ActionRequest("bad", "poll_operation", {
        "command": "execute_macro", "operation_id": "pending-open"}), TurnCallbacks())["ok"]
    assert len(commands) == 2


def test_tool_privacy_filter_runs_before_result_returns_to_model():
    agent, _ = wrapped_agent()
    agent.tool_result_filter = lambda value: value.replace("blobs.gif", "<IMAGE_1>")
    result = execute_action(agent, ActionRequest("a1", "run_macro", {"code": "test"}), TurnCallbacks())
    assert "blobs.gif" not in result["result"]
    assert "<IMAGE_1>" in result["result"]


def test_tool_error_is_filtered_and_state_delta_is_retained():
    agent, _ = wrapped_agent()
    def fail():
        """A tool error containing a sensitive path."""
        raise OSError("private-sample.tif cannot be read")
    def state():
        """State plus the notification of a new image."""
        return {"ok": True, "result": {"images": []}, "stateDelta": {"newImages": ["Blobs"]}}
    agent.tools = [fail, state]
    agent.tool_result_filter = lambda text: text.replace("private-sample.tif", "<IMAGE_1>")
    ok, error = execute_function(agent, "fail", {})
    assert not ok and "private-sample.tif" not in error and "<IMAGE_1>" in error
    ok, result = execute_function(agent, "state", {})
    assert ok and json.loads(result)["stateDelta"]["newImages"] == ["Blobs"]


@pytest.mark.parametrize("provider", ["anthropic", "gemini", "ollama"])
def test_wrapped_capture_attaches_bounded_pixels_and_keeps_text_receipt(provider, tmp_path):
    from PIL import Image
    from agent.providers.base import message_image_bytes
    image = tmp_path / "capture.png"
    Image.new("RGB", (16, 12), "white").save(image)
    def capture_image(max_size: int) -> str:
        """Capture the active image."""
        return str(image)
    agent, _ = wrapped_agent(provider)
    agent.tools = [capture_image]
    agent.client = TextClient([action("capture_image", {}), "Image received"])
    agent.set_image_policy(True)
    attachments = []
    assert agent.turn("Look at the image", TurnCallbacks(on_image_attached=lambda *a: attachments.append(a)))
    assert len(attachments) == 1 and attachments[0][0] == "capture_image"
    next_messages = agent.client.inputs[1]
    assert RESULT_OPEN in next_messages[-2]["content"]
    assert message_image_bytes(next_messages[-1]) == attachments[0][1]
    # Switching the policy removes existing pixels before the next API request.
    agent.set_image_policy(False)
    assert not any(message_image_bytes(m) for m in agent.messages)


def test_blocked_capture_does_not_return_a_local_path_or_replay_pixels(tmp_path):
    image = tmp_path / "private-sample.png"
    agent, _ = wrapped_agent()
    agent._image_policy = False
    agent._last_capture_path = str(image)
    request = ActionRequest("capture", "capture_image", {})
    receipt = {**request.as_dict(), "ok": True, "result": str(image)}
    assert capture_attachment(agent, request, receipt) is None
    assert str(image) not in receipt["result"]
    agent._image_policy = True
    assert capture_attachment(agent, request, {**receipt, "replayed": True}) is None


@pytest.mark.parametrize("provider", ["codex-subscription", "claude-subscription"])
def test_subscription_capture_uses_vendor_image_input(provider, monkeypatch, tmp_path):
    import base64
    from agent.console import subscriptions
    template, _ = wrapped_agent(provider)
    monkeypatch.setattr(subscriptions, "_import_registry", lambda: (template.tools, template.host_code_tools))
    monkeypatch.setattr(subscriptions, "_executable", lambda _: "vendor")
    monkeypatch.setattr(subscriptions, "subscription_status", lambda _: (True, "ready"))
    monkeypatch.setattr(subscriptions, "ensure_importable", lambda: tmp_path)
    agent = subscriptions.SubscriptionAgent(provider, external_session_id="same-session")
    pixels = base64.b64encode(b"fixture-jpeg-bytes").decode()
    agent._pending_image = ("image/jpeg", pixels)
    attachments = []
    def run(command, workspace, stdin=None, **kwargs):
        assert "same-session" in command
        if provider.startswith("codex"):
            capture = Path(command[command.index("--image") + 1])
            assert capture.read_bytes() == b"fixture-jpeg-bytes"
            event = {"type": "item.completed", "item": {
                "id": "a1", "type": "agent_message", "text": "Saw image"}}
        else:
            assert command[command.index("--input-format") + 1] == "stream-json"
            parts = json.loads(stdin)["message"]["content"]
            assert ACTION_OPEN in parts[0]["text"]
            assert parts[1]["source"]["data"] == pixels
            assert parts[1]["source"]["media_type"] == "image/jpeg"
            event = {"type": "result", "session_id": "same-session", "result": "Saw image"}
        line = json.dumps(event)
        kwargs["on_stdout"](line)
        return 0, line, ""
    monkeypatch.setattr(agent, "_run", run)
    cb = TurnCallbacks(on_image_attached=lambda *a: attachments.append(a))
    if provider.startswith("codex"):
        assert agent._codex_turn("Look", cb) == "Saw image"
    else:
        assert agent._claude_turn("Look", cb) == "Saw image"
    assert attachments == [("capture_image", len(pixels))]
    assert agent._pending_image is None


def test_saved_receipts_survive_session_reload_without_repeating_macro(monkeypatch, tmp_path):
    from agent.console import config
    from agent.console.sessions import SessionStore
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    cfg = config.ConsoleConfig()
    store = SessionStore(cfg)
    session = store.create()
    agent, calls = wrapped_agent()
    request = ActionRequest("open", "run_macro", {"code": 'run("Blobs");'})
    receipt = execute_action(agent, request, TurnCallbacks())
    session.action_receipts = {request.id: receipt}
    store.save_session(session)
    agent.action_receipts = SessionStore(cfg).get(session.id).action_receipts
    agent.messages.clear()
    assert execute_action(agent, request, TurnCallbacks())["replayed"]
    assert calls == ['run("Blobs");']


def test_console_binds_selected_connection_before_first_turn(monkeypatch, tmp_path):
    from agent.console import config, tui
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config, "CONFIG_PATH", tmp_path / "console.json")
    monkeypatch.setenv("IMAGEJAI_TCP_HOST", "127.0.0.1")
    monkeypatch.setenv("IMAGEJAI_TCP_PORT", "7746")
    agent, _ = wrapped_agent()
    monkeypatch.setattr(tui, "create_agent", lambda *a, **kw: agent)
    cfg = config.ConsoleConfig(provider="claude-subscription", model="test-model")
    app = tui.ConsoleApp(cfg)
    app._log = lambda *args: None
    session = app.store.create(provider=cfg.provider, model=cfg.model)
    session.action_receipts = {"saved": {"id": "saved"}}
    app._select_session(session)
    assert app.agent.fiji_connection is app.fiji
    assert app.agent.action_receipts == session.action_receipts


def test_interrupted_turn_never_requests_another_model_reply_and_finishes():
    agent, _ = wrapped_agent()
    def interrupting():
        """Simulate the user interrupting while this tool runs."""
        agent.abort.set()
        return "late result"
    agent.tools = [interrupting]
    agent.client = TextClient([action("interrupting", {})])
    done = []
    assert not agent.turn("Start", TurnCallbacks(on_done=done.append))
    assert done == [False]
    assert len(agent.client.inputs) == 1


def test_action_receipt_keeps_full_evidence_and_bounds_model_context():
    from agent.console.agent_loop import MAX_TOOL_RESULT_CHARS
    agent, _ = wrapped_agent()
    def large():
        """A table bigger than the model's short tool result budget."""
        return "x" * (MAX_TOOL_RESULT_CHARS + 2000)
    agent.tools = [large]
    records = []
    receipt = execute_action(agent, ActionRequest("table", "large", {}), TurnCallbacks(
        on_tool_record=lambda *args: records.append(args)))
    assert len(receipt["result"]) == len(records[0][-1]) == MAX_TOOL_RESULT_CHARS + 2000
    message = result_message(receipt)
    assert "incomplete preview" in message["content"]
    assert len(message["content"]) < MAX_TOOL_RESULT_CHARS + 1000


def test_compatibility_connection_is_refused_before_fiji_command():
    from agent.console.wrapped_tools import ConsoleToolSession
    commands = []
    connection = SimpleNamespace(
        host="127.0.0.1", port=7746, _require_selected=lambda: None,
        _module=lambda: SimpleNamespace(hello=lambda **_: {
            "ok": True, "result": {"compatibility": True}}),
        command=lambda *a, **kw: commands.append(a),
    )
    with pytest.raises(ActionError, match="authenticated"):
        ConsoleToolSession(connection, AbortFlag()).request({"command": "get_state"})
    assert commands == []


def test_interrupt_during_handshake_prevents_the_next_fiji_command():
    from agent.console.wrapped_tools import ConsoleToolSession
    abort = AbortFlag()
    commands = []
    def hello(**_):
        abort.set()
        return {"ok": True, "result": {"compatibility": False}}
    connection = SimpleNamespace(
        host="127.0.0.1", port=7746, _require_selected=lambda: None,
        _module=lambda: SimpleNamespace(hello=hello),
        command=lambda *a, **kw: commands.append(a),
    )
    with pytest.raises(ActionError, match="Interrupted"):
        ConsoleToolSession(connection, abort).request({"command": "execute_macro", "code": "test"})
    assert commands == []


@pytest.mark.parametrize("provider,model", [
    ("anthropic", "claude-opus-4-7"), ("gemini", "gemini-2.5-pro"),
    ("openai", "gpt-5"),
])
def test_real_provider_transports_accept_wrapped_results_without_native_tools(provider, model, monkeypatch):
    """Exercise actual SDK request/response conversion, with HTTP mocked."""
    import httpx
    import respx
    from agent.providers.router import get_client
    agent, calls = wrapped_agent(provider)
    agent.model = model
    agent.client = get_client(provider, model, api_key="fixture-not-a-real-key", base_url="http://localhost:4000")
    # The non-streaming route uses the same SDK message conversion, without
    # duplicating the independent streaming parser tests.
    monkeypatch.setattr(agent.client, "chat_stream", None)
    replies = iter([action(arguments={"code": 'run("Blobs");'}), "Blobs is open"])
    requests = []
    def respond(request):
        body = json.loads(request.content)
        requests.append(body)
        assert not body.get("tools")
        if provider == "gemini":
            assert not body.get("generationConfig", {}).get("tools")
        else:
            assert all(set(message) <= {"role", "content"} for message in body["messages"])
        text = next(replies)
        if provider == "anthropic":
            payload = {"id": "msg-fixture", "type": "message", "role": "assistant", "model": model,
                       "content": [{"type": "text", "text": text}], "stop_reason": "end_turn",
                       "usage": {"input_tokens": 10, "output_tokens": 10}}
        elif provider == "gemini":
            payload = {"candidates": [{"content": {"role": "model", "parts": [{"text": text}]},
                                       "finishReason": "STOP", "index": 0}], "modelVersion": model,
                       "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 10}}
        else:
            payload = {"id": "reply-fixture", "object": "chat.completion", "created": 0, "model": model,
                       "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                                    "finish_reason": "stop"}],
                       "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}
        return httpx.Response(200, json=payload)
    with respx.mock(assert_all_called=True) as mock:
        if provider == "anthropic":
            mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=respond)
        elif provider == "gemini":
            mock.post(url__regex=r".*/models/gemini-2\.5-pro:generateContent.*").mock(side_effect=respond)
        else:
            mock.post("http://localhost:4000/v1/chat/completions").mock(side_effect=respond)
        answers = []
        assert agent.turn("Open Blobs", TurnCallbacks(on_assistant=answers.append))
    assert calls == ['run("Blobs");']
    assert answers == ["Blobs is open"]
    assert len(requests) == 2
    assert RESULT_OPEN in json.dumps(requests[1])
