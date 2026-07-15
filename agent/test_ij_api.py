from __future__ import annotations

import ast
import importlib.util
import json
import socket
import threading
import time
from pathlib import Path


IJ_PATH = Path(__file__).with_name("ij.py")
SPEC = importlib.util.spec_from_file_location("ij_under_test", IJ_PATH)
ij = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(ij)


class ScriptedLoopbackServer:
    def __init__(self, expected_requests, handler):
        self.requests = []
        self.errors = []
        self._expected_requests = expected_requests
        self._handler = handler
        self._done = threading.Event()
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(4)
        self._listener.settimeout(3)
        self.port = self._listener.getsockname()[1]
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        try:
            for index in range(self._expected_requests):
                conn, _ = self._listener.accept()
                with conn:
                    data = b""
                    while not data.endswith(b"\n"):
                        chunk = conn.recv(65536)
                        if not chunk:
                            break
                        data += chunk
                    request = json.loads(data.decode("utf-8"))
                    self.requests.append(request)
                    response = self._handler(index, request)
                    conn.sendall((json.dumps(response) + "\n").encode("utf-8"))
        except Exception as exc:  # surfaced deterministically by finish()
            self.errors.append(exc)
        finally:
            self._listener.close()
            self._done.set()

    def finish(self):
        assert self._done.wait(5), "loopback server did not finish"
        self._thread.join(timeout=1)
        assert self.errors == []


def capture_imagej_command(monkeypatch):
    calls = []

    def fake_imagej_command(cmd, *args, **kwargs):
        calls.append((cmd, args, kwargs))
        return {"ok": True, "result": cmd}

    monkeypatch.setattr(ij, "imagej_command", fake_imagej_command)
    return calls


def test_new_helpers_are_public():
    for name in [
        "run_script",
        "run_groovy",
        "run_jython",
        "probe_command",
        "interact_dialog",
        "get_progress",
        "get_roi_state",
        "get_display_state",
        "get_console",
        "list_reactive_rules",
        "reactive_enable",
        "reactive_disable",
        "reactive_reload",
        "reactive_stats",
        "ImageJSession",
    ]:
        assert name in ij.__all__
        assert hasattr(ij, name)


def test_imagej_session_negotiates_once_and_carries_credentials_across_sockets():
    expires_at = int(time.time() * 1000) + 60_000

    def reply(index, request):
        if index == 0:
            assert request["command"] == "hello"
            assert request["token"] == "install-secret"
            assert request["client_session_id"] == "launch-audit-id"
            assert request["capabilities"]["agent_id"] == "launch-audit-id"
            return {
                "ok": True,
                "result": {
                    "session_id": "server-session-123",
                    "expires_at": expires_at,
                    "enabled": ["structured_errors", "safe_mode", "undo"],
                },
            }
        assert request["command"] == "get_state"
        assert request["session_id"] == "server-session-123"
        assert request["token"] == "install-secret"
        return {"ok": True, "result": {"title": "Blobs"}}

    server = ScriptedLoopbackServer(2, reply)
    session = ij.ImageJSession(
        host="127.0.0.1",
        port=server.port,
        token_loader=lambda: "install-secret",
        client_session_id="launch-audit-id",
        capabilities={"structured_errors": True, "safe_mode": True, "undo": True},
    )
    command = {"command": "get_state"}

    response = session.request(command)
    server.finish()

    assert response == {"ok": True, "result": {"title": "Blobs"}}
    assert command == {"command": "get_state"}
    assert session.session_id == "server-session-123"
    assert len(server.requests) == 2


def test_imagej_session_does_not_replay_authentication_failure():
    def reply(index, request):
        if index == 0:
            return {
                "ok": True,
                "result": {
                    "session_id": "server-session-456",
                    "expires_at": int(time.time() * 1000) + 60_000,
                    "enabled": [],
                },
            }
        return {
            "ok": False,
            "error": {
                "code": "session_token_mismatch",
                "message": "invalid credentials",
                "retry_safe": False,
            },
        }

    server = ScriptedLoopbackServer(2, reply)
    session = ij.ImageJSession(
        host="127.0.0.1",
        port=server.port,
        token_loader=lambda: "wrong-after-hello",
    )

    response = session.request({"command": "get_state"})
    server.finish()

    assert response["error"]["code"] == "session_token_mismatch"
    assert len(server.requests) == 2
    assert session.session_id is None


def test_imagej_session_compatibility_mode_still_uses_server_session_id():
    def reply(index, request):
        if index == 0:
            assert "token" not in request
            return {
                "ok": True,
                "result": {
                    "session_id": "compat-session-789",
                    "expires_at": int(time.time() * 1000) + 60_000,
                    "compatibility": True,
                    "enabled": ["safe_mode"],
                },
            }
        assert request["session_id"] == "compat-session-789"
        assert "token" not in request
        return {"ok": True, "result": "pong"}

    server = ScriptedLoopbackServer(2, reply)
    session = ij.ImageJSession(
        host="127.0.0.1", port=server.port, token_loader=lambda: None)

    response = session.request({"command": "ping"})
    server.finish()

    assert response == {"ok": True, "result": "pong"}


def test_event_stream_carries_durable_session_credentials():
    def reply(index, request):
        if index == 0:
            assert request["command"] == "hello"
            assert request["token"] == "install-secret"
            assert request["capabilities"]["accept_events"] == ["*"]
            return {
                "ok": True,
                "result": {
                    "session_id": "stream-session-123",
                    "expires_at": int(time.time() * 1000) + 60_000,
                    "enabled": [],
                },
            }
        assert request == {
            "command": "subscribe",
            "topics": ["job.*"],
            "session_id": "stream-session-123",
            "token": "install-secret",
        }
        return {"event": "subscribed", "data": {"topics": ["job.*"]}}

    server = ScriptedLoopbackServer(2, reply)
    session = ij.ImageJSession(
        host="127.0.0.1",
        port=server.port,
        token_loader=lambda: "install-secret",
        client_session_id="",
        model_endpoint="",
    )

    frames = list(session.events(["job.*"], reconnect=False))
    server.finish()

    assert frames == [
        {"event": "subscribed", "data": {"topics": ["job.*"]}}
    ]


def test_imagej_events_has_one_public_implementation():
    tree = ast.parse(IJ_PATH.read_text(encoding="utf-8"))
    definitions = [
        node for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name == "imagej_events"
    ]

    assert len(definitions) == 1


def test_run_script_defaults_to_groovy_with_cli_timeout(monkeypatch):
    calls = capture_imagej_command(monkeypatch)

    resp = ij.run_script('println("hello")')

    assert resp["ok"] is True
    assert calls == [
        (
            {
                "command": "run_script",
                "language": "groovy",
                "code": 'println("hello")',
            },
            (),
            {"timeout": 180},
        )
    ]


def test_run_groovy_and_run_jython_are_language_specific(monkeypatch):
    calls = capture_imagej_command(monkeypatch)

    ij.run_groovy("return 1", timeout=12)
    ij.run_jython("print 1", timeout=34)

    assert calls == [
        (
            {"command": "run_script", "language": "groovy", "code": "return 1"},
            (),
            {"timeout": 12},
        ),
        (
            {"command": "run_script", "language": "jython", "code": "print 1"},
            (),
            {"timeout": 34},
        ),
    ]


def test_inspection_wrappers_send_expected_commands(monkeypatch):
    calls = capture_imagej_command(monkeypatch)

    ij.get_progress()
    ij.get_roi_state()
    ij.get_display_state()
    ij.get_console(tail=5000)

    assert [call[0] for call in calls] == [
        {"command": "get_progress"},
        {"command": "get_roi_state"},
        {"command": "get_display_state"},
        {"command": "get_console", "tail": 5000},
    ]


def test_probe_and_dialog_wrappers_send_expected_commands(monkeypatch):
    calls = capture_imagej_command(monkeypatch)

    ij.probe_command("Gaussian Blur...")
    ij.interact_dialog("click_button", target="OK", index=1)

    assert [call[0] for call in calls] == [
        {"command": "probe_command", "plugin": "Gaussian Blur..."},
        {
            "command": "interact_dialog",
            "action": "click_button",
            "target": "OK",
            "index": 1,
        },
    ]


def test_reactive_wrappers_send_expected_commands(monkeypatch):
    calls = capture_imagej_command(monkeypatch)

    ij.list_reactive_rules()
    ij.reactive_enable("auto-close")
    ij.reactive_disable("auto-close")
    ij.reactive_reload()
    ij.reactive_stats()

    assert [call[0] for call in calls] == [
        {"command": "list_reactive_rules"},
        {"command": "reactive_enable", "name": "auto-close"},
        {"command": "reactive_disable", "name": "auto-close"},
        {"command": "reactive_reload"},
        {"command": "reactive_stats"},
    ]
