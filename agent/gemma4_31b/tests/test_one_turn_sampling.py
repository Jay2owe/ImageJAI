from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b import loop  # noqa: E402


class _DummyTicker:
    def start(self, label: str) -> None:
        del label

    def current_label(self) -> str:
        return ""

    def set_phase(self, initial_label: str, transitions=()) -> None:
        del initial_label, transitions

    def stop(self) -> None:
        return None


def _tool_call(name: str, arguments: dict | None = None):
    return SimpleNamespace(function=SimpleNamespace(name=name, arguments=arguments or {}))


def _response(*, content: str = "", tool_calls: list | None = None, eval_count: int = 0):
    return SimpleNamespace(
        message=SimpleNamespace(content=content, tool_calls=tool_calls or []),
        eval_count=eval_count,
    )


def _patch_one_turn_dependencies(monkeypatch, chat_side_effect):
    monkeypatch.setattr(loop, "_ActivityTicker", _DummyTicker)
    monkeypatch.setattr(loop, "_chat_interruptible", chat_side_effect)
    monkeypatch.setattr(loop, "_console_emit", lambda *args, **kwargs: None)
    monkeypatch.setattr(loop, "_invalidate_prompt", lambda: None)
    monkeypatch.setattr(
        loop,
        "_run_interruptible",
        lambda fn, *args, abort_event=None, **kwargs: fn(*args, **kwargs),
    )


def test_tool_mode_uses_tool_profile_without_think(monkeypatch):
    captured_options: list[dict] = []

    def fake_chat_interruptible(**kwargs):
        captured_options.append(dict(kwargs["options"]))
        return _response(content="done")

    _patch_one_turn_dependencies(monkeypatch, fake_chat_interruptible)

    reply, had_failure = loop._one_turn(
        "model",
        [],
        [],
        {},
        loop._resolve_turn_config("plain request", None, None, False),
    )

    assert "done" in reply
    assert had_failure is False
    assert captured_options == [
        {"temperature": 0.25, "top_p": 0.90, "top_k": 30, "num_ctx": loop.NUM_CTX}
    ]
    assert "think" not in captured_options[0]


def test_provider_client_uses_rich_loop_tool_dispatch(monkeypatch):
    threaded: list[tuple[str, str]] = []
    captured: list[dict] = []

    class FakeCall:
        id = "call-1"
        name = "inspect"
        args = {"x": 4}
        error = None

    class FakeClient:
        def __init__(self):
            self.step = 0

        def chat(self, messages, tools, model, **opts):
            self.step += 1
            captured.append(dict(opts))
            return ("tool", FakeCall()) if self.step == 1 else ("text", "done")

        def append_assistant(self, messages, response):
            messages.append({"role": "assistant", "content": ""})

        def extract_tool_calls(self, response):
            return [response[1]] if response[0] == "tool" else []

        def extract_text(self, response):
            return response[1] if response[0] == "text" else ""

        def append_tool_result(self, messages, call, result):
            threaded.append((call.name, result))
            messages.append({"role": "tool", "content": result})

    _patch_one_turn_dependencies(monkeypatch, lambda **kwargs: _response(content="unused"))

    reply, had_failure = loop._one_turn(
        "provider-model",
        [],
        [object()],
        {"inspect": lambda x: "value={}".format(x)},
        loop._resolve_turn_config("plain request", None, None, False),
        provider="groq",
        provider_client=FakeClient(),
    )

    assert "done" in reply
    assert had_failure is False
    assert threaded == [("inspect", "value=4")]
    assert captured == [
        {"temperature": 0.25, "top_p": 0.90},
        {"temperature": 0.25, "top_p": 0.90},
    ]


def test_plan_mode_sends_think_true(monkeypatch):
    captured_options: list[dict] = []

    def fake_chat_interruptible(**kwargs):
        captured_options.append(dict(kwargs["options"]))
        return _response(content="done")

    _patch_one_turn_dependencies(monkeypatch, fake_chat_interruptible)

    reply, had_failure = loop._one_turn(
        "model",
        [],
        [],
        {},
        loop._resolve_turn_config("which method should I use?", None, None, False),
    )

    assert "done" in reply
    assert had_failure is False
    assert captured_options == [
        {"temperature": 0.60, "top_p": 0.92, "top_k": 50, "num_ctx": loop.NUM_CTX, "think": True}
    ]


def test_tool_error_flips_next_round_to_recover(monkeypatch):
    captured_options: list[dict] = []
    friction_events: list[dict] = []
    responses = [
        _response(tool_calls=[_tool_call("boom")]),
        _response(content="recovered"),
    ]

    def fake_chat_interruptible(**kwargs):
        captured_options.append(dict(kwargs["options"]))
        return responses.pop(0)

    _patch_one_turn_dependencies(monkeypatch, fake_chat_interruptible)
    monkeypatch.setattr(loop.safety, "friction_log", lambda event: friction_events.append(event))

    reply, had_failure = loop._one_turn(
        "model",
        [],
        [object()],
        {"boom": lambda: "ERROR: bad tool call"},
        loop._resolve_turn_config("plain request", None, None, False),
    )

    assert "recovered" in reply
    assert had_failure is True
    assert captured_options[0] == {
        "temperature": 0.25,
        "top_p": 0.90,
        "top_k": 30,
        "num_ctx": loop.NUM_CTX,
    }
    assert captured_options[1] == {
        "temperature": 0.30,
        "top_p": 0.90,
        "top_k": 35,
        "num_ctx": loop.NUM_CTX,
        "think": True,
    }
    assert friction_events == [
        {"event": "turn_config", "mode": "recover", "thinking": True, "source": "recover"}
    ]


def test_only_one_mid_turn_flip_happens_for_multiple_errors(monkeypatch):
    captured_options: list[dict] = []
    friction_events: list[dict] = []
    responses = [
        _response(tool_calls=[_tool_call("boom")]),
        _response(tool_calls=[_tool_call("boom")]),
        _response(content="done"),
    ]

    def fake_chat_interruptible(**kwargs):
        captured_options.append(dict(kwargs["options"]))
        return responses.pop(0)

    _patch_one_turn_dependencies(monkeypatch, fake_chat_interruptible)
    monkeypatch.setattr(loop.safety, "friction_log", lambda event: friction_events.append(event))

    reply, had_failure = loop._one_turn(
        "model",
        [],
        [object()],
        {"boom": lambda: "ERROR: bad tool call"},
        loop._resolve_turn_config("plain request", None, None, False),
    )

    assert "done" in reply
    assert had_failure is True
    assert captured_options == [
        {"temperature": 0.25, "top_p": 0.90, "top_k": 30, "num_ctx": loop.NUM_CTX},
        {"temperature": 0.30, "top_p": 0.90, "top_k": 35, "num_ctx": loop.NUM_CTX, "think": True},
        {"temperature": 0.30, "top_p": 0.90, "top_k": 35, "num_ctx": loop.NUM_CTX, "think": True},
    ]
    assert friction_events == [
        {"event": "turn_config", "mode": "recover", "thinking": True, "source": "recover"}
    ]


def test_post_tool_guidance_is_visible_to_user_and_model(monkeypatch):
    emitted: list[str] = []
    model_rounds: list[list[object]] = []
    responses = [
        _response(tool_calls=[_tool_call("run_macro", {"macro": "bad command"})]),
        _response(content="recovered"),
    ]

    def fake_chat_interruptible(**kwargs):
        model_rounds.append(list(kwargs["messages"]))
        return responses.pop(0)

    _patch_one_turn_dependencies(monkeypatch, fake_chat_interruptible)
    monkeypatch.setattr(
        loop,
        "_console_emit",
        lambda text, **kwargs: emitted.append(text),
    )

    reply, had_failure = loop._one_turn(
        "model",
        [],
        [object()],
        {"run_macro": lambda macro: 'ERROR: Unrecognized command: "Laplacian"'},
        loop._resolve_turn_config("plain request", None, None, False),
    )

    assert "recovered" in reply
    assert had_failure is True
    assert any(
        isinstance(message, dict) and message.get("role") == "system"
        and "Unrecognized command" in message.get("content", "")
        for message in model_rounds[-1]
    )
    assert any(
        "post-tool note" in line and "Unrecognized command" in line
        for line in emitted
    )


def test_identical_tool_repeat_guard_works_without_post_tool_guidance(monkeypatch):
    friction_events: list[dict] = []
    responses = [
        _response(tool_calls=[_tool_call("boom")])
        for _ in range(loop.MAX_IDENTICAL_TOOL_REPEATS)
    ]

    def fake_chat_interruptible(**kwargs):
        return responses.pop(0)

    _patch_one_turn_dependencies(monkeypatch, fake_chat_interruptible)
    monkeypatch.setattr(
        loop.safety,
        "friction_log",
        lambda event: friction_events.append(event),
    )

    reply, had_failure = loop._one_turn(
        "model",
        [],
        [object()],
        {"boom": lambda: "ERROR: bad tool call"},
        loop._resolve_turn_config("plain request", None, None, False),
    )

    assert "stopped after the same tool repeated" in reply
    assert had_failure is True
    stuck_events = [
        event for event in friction_events
        if event.get("event") == "stuck_tool_loop"
    ]
    assert stuck_events == [
        {
            "event": "stuck_tool_loop",
            "tool": "boom",
            "args": "{}",
            "repeat_count": loop.MAX_IDENTICAL_TOOL_REPEATS,
            "result_preview": "ERROR: bad tool call",
        }
    ]
