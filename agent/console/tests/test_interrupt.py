"""Escape stops a streaming turn before the next model reply arrives."""
from __future__ import annotations

import threading
import sys
import time
from types import SimpleNamespace

from agent.console.agent_loop import AbortFlag, ConsoleAgent, TurnCallbacks


def test_abort_does_not_wait_for_a_slow_cancel_callback():
    flag = AbortFlag()
    entered = threading.Event()
    release = threading.Event()

    def slow_cancel():
        entered.set()
        release.wait(timeout=2)

    flag.register(slow_cancel)
    started = time.monotonic()
    flag.set()
    try:
        assert time.monotonic() - started < 0.5
        assert entered.wait(timeout=1)
        assert flag.set_flag
    finally:
        release.set()


def test_abort_closes_active_stream_and_drops_late_answer():
    entered = threading.Event()
    released = threading.Event()
    done = []
    answers = []

    class BlockingClient:
        def chat_stream(self, messages, tools, model, *, abort, on_delta=None, **opts):
            abort.register(released.set)
            entered.set()
            released.wait(timeout=5)
            if abort.set_flag:
                raise InterruptedError("stream closed")
            return {"text": "late answer"}

        def extract_tool_calls(self, reply):
            return []

        def extract_text(self, reply):
            return reply["text"]

        def append_assistant(self, messages, reply):
            messages.append({"role": "assistant", "content": reply["text"]})

    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="test")
    agent.client = BlockingClient()
    agent.abort = AbortFlag()
    worker = threading.Thread(target=lambda: agent.turn(
        "question", TurnCallbacks(on_assistant=answers.append, on_done=done.append)),
        daemon=True)
    worker.start()
    assert entered.wait(timeout=2)
    agent.abort.set()
    worker.join(timeout=1)
    assert not worker.is_alive()
    assert done == [False]
    assert answers == []
    assert not any(message.get("role") == "assistant" for message in agent.messages)


def test_escape_can_stop_turn_before_worker_starts():
    from agent.console.config import ConsoleConfig
    from agent.console.tui import ConsoleApp

    app = ConsoleApp(ConsoleConfig())
    app.agent = SimpleNamespace(abort=None)
    scheduled = []
    app._run_turn = lambda text, abort: scheduled.append((text, abort))
    app._submit_text("question")
    assert app.turn_running
    assert scheduled == [("question", app.abort)]
    app.action_interrupt()
    assert app.abort.set_flag
    assert app._turn_cancel_requested


def test_subscription_abort_terminates_owned_process(tmp_path, monkeypatch):
    from agent.console import subscriptions

    agent = object.__new__(subscriptions.SubscriptionAgent)
    agent.abort = AbortFlag()
    created = threading.Event()
    owned = []
    result = []
    original_popen = subscriptions.subprocess.Popen

    def launch(*args, **kwargs):
        process = original_popen(*args, **kwargs)
        owned.append(process)
        created.set()
        return process

    monkeypatch.setattr(subscriptions.subprocess, "Popen", launch)

    def run():
        try:
            agent._run([sys.executable, "-c", "import time; time.sleep(60)"], tmp_path)
        except RuntimeError as exc:
            result.append(str(exc))

    worker = threading.Thread(target=run, daemon=True)
    worker.start()
    try:
        assert created.wait(timeout=3)
        started = time.monotonic()
        agent.abort.set()
        worker.join(timeout=2)
        assert not worker.is_alive()
        assert time.monotonic() - started < 2
        assert result == ["interrupted by user"]
    finally:
        for process in owned:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
