from __future__ import annotations

import json
import socket
import threading
import time

import pytest

from agent.gemma4_31b import events, loop, registry


class _ScriptedLoopbackServer:
    def __init__(self, expected_requests, handler):
        self.requests = []
        self.errors = []
        self._expected_requests = expected_requests
        self._handler = handler
        self._done = threading.Event()
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(8)
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
                    frames = response if isinstance(response, list) else [response]
                    conn.sendall(b"".join(
                        (json.dumps(frame) + "\n").encode("utf-8")
                        for frame in frames
                    ))
        except Exception as exc:  # surfaced deterministically by finish()
            self.errors.append(exc)
        finally:
            self._listener.close()
            self._done.set()

    def finish(self):
        assert self._done.wait(5), "loopback server did not finish"
        self._thread.join(timeout=1)
        assert self.errors == []


@pytest.fixture(autouse=True)
def _restore_registry_session():
    with registry._SESSION_LOCK:
        previous = registry._SESSION
    yield
    registry._set_session_for_test(previous)


def _strict_session(port: int) -> registry.StrictGemmaSession:
    return registry.StrictGemmaSession(
        host="127.0.0.1",
        port=port,
        timeout=2,
        agent="gemma-31b",
        capabilities=registry.GEMMA_CAPS,
        token_loader=lambda: "strict-token",
        client_session_id="launch-test",
    )


def _hello_result(**overrides):
    result = {
        "session_id": "strict-session",
        "expires_at": int(time.time() * 1000) + 60_000,
        "compatibility": False,
    }
    result.update(overrides)
    return {"ok": True, "result": result}


def test_tools_and_events_share_one_strict_authenticated_session(monkeypatch):
    def reply(index, request):
        if index == 0:
            assert request["command"] == "hello"
            assert request["agent"] == "gemma-31b"
            assert request["token"] == "strict-token"
            assert request["client_session_id"] == "launch-test"
            for key, value in registry.GEMMA_CAPS.items():
                assert request["capabilities"][key] == value
            return _hello_result()

        assert request["session_id"] == "strict-session"
        assert request["token"] == "strict-token"
        assert request["client_session_id"] == "launch-test"
        if index == 1:
            assert request["command"] == "get_state"
            return {"ok": True, "result": {"title": "Blobs"}}
        if index == 2:
            assert request["command"] == "subscribe"
            assert request["topics"] == ["image.*"]
            return [
                {"event": "subscribed", "data": {"topics": ["image.*"]}},
                {"event": "image.updated", "data": {"title": "Blobs"}},
            ]
        assert request["command"] == "get_image_info"
        return {"ok": True, "result": {"width": 256}}

    server = _ScriptedLoopbackServer(4, reply)
    session = _strict_session(server.port)
    registry._set_session_for_test(session)
    seen_frames = []
    monkeypatch.setattr(events, "_handle_frame", seen_frames.append)

    state = registry.send("get_state")
    events._stream_once(["image.*"])
    info = registry.send("get_image_info")
    server.finish()

    assert state["ok"] is True
    assert info["ok"] is True
    assert [frame["event"] for frame in seen_frames] == ["subscribed", "image.updated"]
    assert loop._new_governed_event_session() is session
    assert registry.imagej_session() is session
    assert [request["command"] for request in server.requests].count("hello") == 1


def test_auth_failure_never_falls_back_to_bare_tool_request():
    def reply(index, request):
        del index
        assert request["command"] == "hello"
        assert request["token"] == "strict-token"
        return {
            "ok": False,
            "error": {
                "code": "auth_required",
                "message": "strict token required",
                "retry_safe": False,
            },
        }

    server = _ScriptedLoopbackServer(1, reply)
    registry._set_session_for_test(_strict_session(server.port))

    response = registry.send("get_state")
    server.finish()

    assert response["error"]["code"] == "auth_required"
    assert [request["command"] for request in server.requests] == ["hello"]


def test_compatibility_hello_is_rejected_before_tool_or_event_requests():
    def reply(index, request):
        del index
        assert request["command"] == "hello"
        return _hello_result(compatibility=True, session_id="legacy-session")

    server = _ScriptedLoopbackServer(1, reply)
    session = _strict_session(server.port)
    registry._set_session_for_test(session)

    response = registry.send("get_state")
    server.finish()

    assert response["error"]["code"] == "compatibility_forbidden"
    assert session.session_id is None
    assert [request["command"] for request in server.requests] == ["hello"]


def test_event_subscription_rejects_compatibility_without_bare_subscribe():
    def reply(index, request):
        del index
        assert request["command"] == "hello"
        return _hello_result(compatibility=True, session_id="legacy-event-session")

    server = _ScriptedLoopbackServer(1, reply)
    session = _strict_session(server.port)
    registry._set_session_for_test(session)

    frames = list(session.events(["image.*"], reconnect=False))
    server.finish()

    assert frames[0]["error"]["code"] == "compatibility_forbidden"
    assert [request["command"] for request in server.requests] == ["hello"]


def test_singleton_creation_is_thread_safe(monkeypatch):
    created = []

    def build():
        created.append(object())
        return created[-1]

    registry._set_session_for_test(None)
    monkeypatch.setattr(registry, "_new_imagej_session", build)
    seen = []
    threads = [threading.Thread(target=lambda: seen.append(registry.imagej_session())) for _ in range(12)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert len(created) == 1
    assert seen == [created[0]] * 12


def test_compatibility_validation_is_atomic_with_concurrent_tool_request():
    validation_entered = threading.Event()
    release_validation = threading.Event()

    class _PausingStrictSession(registry.StrictGemmaSession):
        def _validate_hello_response_locked(self, response):
            if response.get("ok"):
                validation_entered.set()
                assert release_validation.wait(3)
            return super()._validate_hello_response_locked(response)

    def reply(index, request):
        assert request["command"] == "hello"
        if index == 0:
            return _hello_result(compatibility=True, session_id="racy-legacy-session")
        return {
            "ok": False,
            "error": {
                "code": "auth_required",
                "message": "second negotiation intentionally denied",
                "retry_safe": False,
            },
        }

    server = _ScriptedLoopbackServer(2, reply)
    session = _PausingStrictSession(
        host="127.0.0.1",
        port=server.port,
        timeout=2,
        agent="gemma-31b",
        capabilities=registry.GEMMA_CAPS,
        token_loader=lambda: "strict-token",
        client_session_id="launch-test",
    )
    hello_results = []
    tool_results = []
    hello_thread = threading.Thread(target=lambda: hello_results.append(session.hello()))
    hello_thread.start()
    assert validation_entered.wait(3)

    tool_thread = threading.Thread(
        target=lambda: tool_results.append(session.request({"command": "get_state"}))
    )
    tool_thread.start()
    release_validation.set()
    hello_thread.join(timeout=3)
    tool_thread.join(timeout=3)
    server.finish()

    assert not hello_thread.is_alive()
    assert not tool_thread.is_alive()
    assert hello_results[0]["error"]["code"] == "compatibility_forbidden"
    assert tool_results[0]["error"]["code"] == "auth_required"
    assert [request["command"] for request in server.requests] == ["hello", "hello"]
