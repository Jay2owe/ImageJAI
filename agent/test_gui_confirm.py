from __future__ import annotations

import importlib.util
import socket
import time
from pathlib import Path
from types import SimpleNamespace


IJ_PATH = Path(__file__).with_name("ij.py")
SPEC = importlib.util.spec_from_file_location("ij_gui_confirm_under_test", IJ_PATH)
ij = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(ij)


def _fixed_confirmation_id(monkeypatch):
    monkeypatch.setattr(
        ij.uuid, "uuid4", lambda: SimpleNamespace(hex="a" * 32))
    return "confirm-" + ("a" * 32)


def test_gui_confirm_subscribes_before_dispatch_and_keeps_fast_response(
        monkeypatch):
    confirmation_id = _fixed_confirmation_id(monkeypatch)
    order = []

    class FastSession:
        def events(self, topics, reconnect, read_timeout, deadline):
            assert topics == ["gui_action.confirm.resolved"]
            assert reconnect is False
            assert read_timeout > 0
            assert deadline > time.monotonic()
            order.append("subscribe")
            try:
                yield {"event": "subscribed", "data": {"topics": topics}}
                assert order == ["subscribe", "dispatch"]
                order.append("event")
                yield {
                    "event": "gui_action.confirm.resolved",
                    "data": {"id": confirmation_id, "choice": "Yes"},
                }
                while True:
                    yield {"event": "heartbeat", "data": {}}
            finally:
                order.append("close")

        def request(self, command, timeout, _check_dialogs):
            raise AssertionError("completed confirmation must not be cancelled")

    def dispatch(command, timeout):
        assert order == ["subscribe"]
        assert command["id"] == confirmation_id
        assert command["type"] == "confirm"
        assert timeout > 0
        order.append("dispatch")
        return {"ok": True, "id": confirmation_id, "pending": True}

    monkeypatch.setattr(ij, "_session_for", lambda host, port: FastSession())
    monkeypatch.setattr(ij, "imagej_command", dispatch)

    result = ij.gui_confirm("Proceed?", ["Yes", "No"], timeout=1)

    assert result == {"id": confirmation_id, "choice": "Yes"}
    assert order == ["subscribe", "dispatch", "event", "close"]


def test_repeated_confirmation_timeouts_close_every_listener_without_extra_wait(
        monkeypatch):
    confirmation_id = _fixed_confirmation_id(monkeypatch)
    active = 0
    peak = 0
    closed = 0

    class TimingOutSession:
        def __init__(self):
            self.cancellations = 0

        def events(self, topics, reconnect, read_timeout, deadline):
            nonlocal active, peak, closed
            active += 1
            peak = max(peak, active)
            try:
                yield {"event": "subscribed", "data": {"topics": topics}}
                time.sleep(0.015)
                raise socket.timeout("synthetic deadline")
            finally:
                active -= 1
                closed += 1

        def request(self, command, timeout, _check_dialogs):
            assert active == 0, "listener must close before server cancellation"
            assert command == {
                "command": "gui_action",
                "type": "confirm_cancel",
                "id": confirmation_id,
            }
            assert timeout == 0.25
            assert _check_dialogs is False
            self.cancellations += 1
            return {"ok": True, "id": confirmation_id, "cancelled": True}

    def dispatch(command, timeout):
        assert command["id"] == confirmation_id
        return {"ok": True, "id": confirmation_id, "pending": True}

    session = TimingOutSession()
    monkeypatch.setattr(ij, "_session_for", lambda host, port: session)
    monkeypatch.setattr(ij, "imagej_command", dispatch)

    started = time.monotonic()
    results = [
        ij.gui_confirm("Proceed?", ["Yes", "No"], timeout=0.01)
        for _ in range(12)
    ]
    elapsed = time.monotonic() - started

    assert all(result == {
        "id": confirmation_id, "choice": None, "timed_out": True,
    } for result in results)
    assert active == 0
    assert peak == 1
    assert closed == 12
    assert session.cancellations == 12
    assert elapsed < 1.0


def test_subscription_rejection_does_not_dispatch_prompt(monkeypatch):
    confirmation_id = _fixed_confirmation_id(monkeypatch)

    class RejectedSession:
        def events(self, topics, reconnect, read_timeout, deadline):
            yield {
                "ok": False,
                "error": {"code": "subscriber_capacity"},
            }

        def request(self, command, timeout, _check_dialogs):
            raise AssertionError("undispatched prompt must not be cancelled")

    def unexpected_dispatch(command, timeout):
        raise AssertionError("prompt dispatched without an acknowledged listener")

    monkeypatch.setattr(ij, "_session_for", lambda host, port: RejectedSession())
    monkeypatch.setattr(ij, "imagej_command", unexpected_dispatch)

    result = ij.gui_confirm("Proceed?", ["Yes", "No"], timeout=1)

    assert result["id"] == confirmation_id
    assert result["choice"] is None
    assert result["error"]["error"]["code"] == "subscriber_capacity"


def test_transport_failure_after_dispatch_closes_and_cancels(monkeypatch):
    confirmation_id = _fixed_confirmation_id(monkeypatch)
    actions = []

    class BrokenSession:
        def events(self, topics, reconnect, read_timeout, deadline):
            try:
                yield {"event": "subscribed", "data": {"topics": topics}}
                raise ConnectionError("stream reset")
            finally:
                actions.append("closed")

        def request(self, command, timeout, _check_dialogs):
            actions.append(command["type"])
            return {"ok": True}

    def dispatch(command, timeout):
        actions.append("confirm")
        return {"ok": True, "id": confirmation_id, "pending": True}

    monkeypatch.setattr(ij, "_session_for", lambda host, port: BrokenSession())
    monkeypatch.setattr(ij, "imagej_command", dispatch)

    result = ij.gui_confirm("Proceed?", ["Yes", "No"], timeout=1)

    assert result["id"] == confirmation_id
    assert "stream reset" in result["error"]
    assert actions == ["confirm", "closed", "confirm_cancel"]
