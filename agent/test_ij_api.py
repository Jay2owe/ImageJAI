from __future__ import annotations

import ast
import importlib.util
import json
import socket
import threading
import time
from pathlib import Path

import pytest


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


class RawLoopbackServer:
    """One-request peer used to exercise hostile reply framing."""

    def __init__(self, payload):
        self.errors = []
        self._payload = payload
        self._done = threading.Event()
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(1)
        self._listener.settimeout(3)
        self.port = self._listener.getsockname()[1]
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        try:
            conn, _ = self._listener.accept()
            with conn:
                request = b""
                while not request.endswith(b"\n"):
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    request += chunk
                conn.sendall(self._payload)
        except Exception as exc:
            self.errors.append(exc)
        finally:
            self._listener.close()
            self._done.set()

    def finish(self):
        assert self._done.wait(5), "raw loopback server did not finish"
        self._thread.join(timeout=1)
        assert self.errors == []


def capture_imagej_command(monkeypatch):
    calls = []

    def fake_imagej_command(cmd, *args, **kwargs):
        calls.append((cmd, args, kwargs))
        return {"ok": True, "result": cmd}

    monkeypatch.setattr(ij, "imagej_command", fake_imagej_command)
    return calls


class ScriptedSession:
    def __init__(self, responses=(), frames=()):
        self.responses = list(responses)
        self.frames = list(frames)
        self.requests = []
        self.event_calls = []

    def request(self, command, timeout=None):
        self.requests.append((dict(command), timeout))
        assert self.responses, "unexpected request"
        response = self.responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        return response

    def events(self, topics, reconnect, read_timeout):
        self.event_calls.append((topics, reconnect, read_timeout))
        for frame in self.frames:
            if isinstance(frame, BaseException):
                raise frame
            yield frame


@pytest.fixture
def isolated_read_cache(monkeypatch, tmp_path):
    monkeypatch.setattr(ij, "_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(ij, "_CACHE_FILE", str(tmp_path / "cache.json"))
    monkeypatch.setattr(ij, "_READONLY_CACHE", {})
    monkeypatch.setattr(ij, "_READONLY_CACHE_DIRTY", False)


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


@pytest.mark.parametrize("terminator", [b"", b"\n"])
def test_one_shot_reply_rejects_oversize_frames_before_decode(
        monkeypatch, terminator):
    monkeypatch.setattr(ij, "MAX_REPLY_FRAME_BYTES", 64)
    server = RawLoopbackServer(b"x" * 65 + terminator)
    session = ij.ImageJSession(
        host="127.0.0.1", port=server.port, token_loader=lambda: None)

    with pytest.raises(ValueError, match="reply frame exceeds 64 bytes"):
        session._exchange({"command": "ping"}, timeout=2)
    server.finish()


@pytest.mark.parametrize("terminator", [b"", b"\n"])
def test_event_stream_rejects_oversize_frames_before_decode(
        monkeypatch, terminator):
    monkeypatch.setattr(ij, "MAX_EVENT_FRAME_BYTES", 64)
    server = RawLoopbackServer(b"x" * 65 + terminator)
    session = ij.ImageJSession(
        host="127.0.0.1", port=server.port, token_loader=lambda: None)
    session._session_id = "bounded-event-session"

    with pytest.raises(ValueError, match="event frame exceeds 64 bytes"):
        list(session.events(["*"], reconnect=False, read_timeout=2))
    server.finish()


def test_imagej_events_has_one_public_implementation():
    tree = ast.parse(IJ_PATH.read_text(encoding="utf-8"))
    definitions = [
        node for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name == "imagej_events"
    ]

    assert len(definitions) == 1


def test_read_cache_key_canonicalizes_semantic_args_and_excludes_framing():
    first = ij._readonly_cache_key(
        " LOCALHOST ",
        "7746",
        {
            "command": "get_console",
            "tail": 25,
            "filter": {"stderr": True, "stdout": False},
            "session_id": "session-a",
            "token": "secret-a",
            "if_none_match": "hash-a",
        },
    )
    same = ij._readonly_cache_key(
        "localhost",
        7746,
        {
            "filter": {"stdout": False, "stderr": True},
            "tail": 25,
            "command": "get_console",
            "session_id": "session-b",
            "token": "secret-b",
        },
    )

    assert first == same
    assert first != ij._readonly_cache_key(
        "localhost", 7746, {"command": "get_console", "tail": 26})
    assert first != ij._readonly_cache_key(
        "other-host", 7746, {"command": "get_console", "tail": 25,
                             "filter": {"stderr": True, "stdout": False}})
    assert first != ij._readonly_cache_key(
        "localhost", 7747, {"command": "get_console", "tail": 25,
                             "filter": {"stderr": True, "stdout": False}})


def test_read_cache_isolated_by_endpoint_and_semantic_args(
        monkeypatch, isolated_read_cache):
    server_a = ScriptedSession(responses=[
        {"ok": True, "hash": "hash-a", "result": {"text": "a"}},
        {"ok": True, "unchanged": True},
        {"ok": True, "hash": "hash-a-50", "result": {"text": "a50"}},
    ])
    server_b = ScriptedSession(responses=[
        {"ok": True, "hash": "hash-b", "result": {"text": "b"}},
    ])
    sessions = {("server", 8001): server_a, ("server", 8002): server_b}
    monkeypatch.setattr(ij, "_session_for", lambda host, port: sessions[(host, port)])

    first = ij.imagej_command(
        {"command": "get_console", "tail": 25}, host="server", port=8001)
    replay = ij.imagej_command(
        {"tail": 25, "command": "get_console"}, host="server", port=8001)
    other_args = ij.imagej_command(
        {"command": "get_console", "tail": 50}, host="server", port=8001)
    other_server = ij.imagej_command(
        {"command": "get_console", "tail": 25}, host="server", port=8002)

    assert first["result"] == {"text": "a"}
    assert replay == {
        "ok": True,
        "result": {"text": "a"},
        "hash": "hash-a",
        "cached": True,
    }
    assert other_args["result"] == {"text": "a50"}
    assert other_server["result"] == {"text": "b"}
    assert server_a.requests[0][0] == {"command": "get_console", "tail": 25}
    assert server_a.requests[1][0]["if_none_match"] == "hash-a"
    assert "if_none_match" not in server_a.requests[2][0]
    assert "if_none_match" not in server_b.requests[0][0]


def test_read_cache_never_resolves_an_unrelated_explicit_hash(
        monkeypatch, isolated_read_cache):
    session = ScriptedSession(responses=[
        {"ok": True, "hash": "local-hash", "result": {"value": "old"}},
        {"ok": True, "unchanged": True},
        {"ok": True, "hash": "fresh-hash", "result": {"value": "fresh"}},
    ])
    monkeypatch.setattr(ij, "_session_for", lambda host, port: session)
    command = {"command": "get_state"}

    ij.imagej_command(command, host="server", port=8001)
    response = ij.imagej_command(
        {"command": "get_state", "if_none_match": "foreign-hash"},
        host="server",
        port=8001,
    )

    assert response["result"] == {"value": "fresh"}
    assert session.requests[1][0]["if_none_match"] == "foreign-hash"
    assert session.requests[2][0] == command


def test_read_cache_persistence_is_versioned_and_round_trips(
        monkeypatch, isolated_read_cache):
    monkeypatch.setattr(ij, "_cache_now", lambda: 1000.0)
    key = ij._readonly_cache_key(
        "server", 8001, {"command": "job_status", "job_id": "job-a"})
    assert ij._cache_store(key, "job-hash", {"state": "running"}) is True

    ij._flush_cache()

    payload = json.loads(Path(ij._CACHE_FILE).read_text(encoding="utf-8"))
    assert not Path(ij._CACHE_FILE + ".tmp").exists()
    assert payload["version"] == ij._CACHE_SCHEMA_VERSION
    assert payload["entries"] == {
        key: {
            "hash": "job-hash",
            "result": {"state": "running"},
            "stored_at": 1000.0,
            "accessed_at": 1000.0,
        }
    }
    assert ij._load_cache_from_disk() == {
        key: {
            "hash": "job-hash",
            "result": {"state": "running"},
            "stored_at": 1000.0,
            "accessed_at": 1000.0,
        }
    }


@pytest.mark.parametrize("payload", [
    {"job_status": ["old-hash", {"state": "completed"}]},
    {"version": 1, "entries": {}},
    {"version": 2, "entries": {}},
    {"version": 999, "entries": {}},
    {"version": 3, "entries": {"not-a-canonical-key": {}}},
    {"version": 3, "entries": {}, "unexpected": True},
    {"version": 2, "entries": {"not-a-canonical-key": ["hash", {}]}},
    {"version": 2, "entries": {}, "unexpected": True},
])
def test_read_cache_rejects_legacy_unknown_or_malformed_schemas(
        isolated_read_cache, payload):
    Path(ij._CACHE_FILE).write_text(json.dumps(payload), encoding="utf-8")

    assert ij._load_cache_from_disk() == {}


def test_read_cache_rejects_oversized_file_before_and_during_parse(
        monkeypatch, isolated_read_cache):
    monkeypatch.setattr(ij, "_CACHE_MAX_FILE_BYTES", 128)
    Path(ij._CACHE_FILE).write_bytes(b"{" + b" " * 128)
    # Simulate the file growing after the pre-read size check. The bounded
    # binary read must still reject it before JSON decoding/parsing.
    monkeypatch.setattr(ij.os.path, "getsize", lambda path: 128)
    monkeypatch.setattr(
        ij.json, "loads",
        lambda raw, **kwargs: pytest.fail("oversized cache reached JSON parse"),
    )

    assert ij._load_cache_from_disk() == {}


def test_read_cache_bounds_two_thousand_distinct_endpoint_argument_calls(
        monkeypatch, isolated_read_cache):
    class GeneratedSession:
        def __init__(self):
            self.count = 0

        def request(self, command, timeout=None):
            self.count += 1
            job_id = command["job_id"]
            return {
                "ok": True,
                "hash": "hash-" + job_id,
                "result": {"job_id": job_id, "state": "running"},
            }

    session = GeneratedSession()
    clock = [1000.0]
    monkeypatch.setattr(ij, "_cache_now", lambda: clock[0])
    monkeypatch.setattr(ij, "_session_for", lambda host, port: session)

    for index in range(2000):
        clock[0] += 1.0
        ij.imagej_command(
            {"command": "job_status", "job_id": "job-%04d" % index},
            host="server-%02d" % (index % 20),
            port=8000 + (index % 10),
        )

    assert session.count == 2000
    assert len(ij._READONLY_CACHE) <= ij._CACHE_MAX_ENTRIES
    assert ij._cache_retained_bytes(
        ij._READONLY_CACHE) <= ij._CACHE_MAX_RETAINED_BYTES

    ij._flush_cache()
    cache_path = Path(ij._CACHE_FILE)
    payload = json.loads(cache_path.read_text(encoding="utf-8"))
    assert len(payload["entries"]) <= ij._CACHE_MAX_ENTRIES
    assert cache_path.stat().st_size <= ij._CACHE_MAX_FILE_BYTES


def test_read_cache_lru_and_ttl_eviction_are_deterministic(
        monkeypatch, isolated_read_cache):
    monkeypatch.setattr(ij, "_CACHE_MAX_ENTRIES", 2)
    monkeypatch.setattr(ij, "_CACHE_TTL_SECONDS", 5)
    clock = [1000.0]
    monkeypatch.setattr(ij, "_cache_now", lambda: clock[0])
    session = ScriptedSession(responses=[
        {"ok": True, "hash": "hash-a", "result": {"value": "a"}},
        {"ok": True, "hash": "hash-b", "result": {"value": "b"}},
        {"ok": True, "unchanged": True},
        {"ok": True, "hash": "hash-c", "result": {"value": "c"}},
    ])
    monkeypatch.setattr(ij, "_session_for", lambda host, port: session)

    def call(name):
        return ij.imagej_command(
            {"command": "job_status", "job_id": name},
            host="server", port=8001)

    call("a")
    clock[0] += 1
    call("b")
    clock[0] += 1
    assert call("a")["cached"] is True  # refresh A's LRU age
    clock[0] += 1
    call("c")

    key_a = ij._readonly_cache_key(
        "server", 8001, {"command": "job_status", "job_id": "a"})
    key_b = ij._readonly_cache_key(
        "server", 8001, {"command": "job_status", "job_id": "b"})
    key_c = ij._readonly_cache_key(
        "server", 8001, {"command": "job_status", "job_id": "c"})
    assert set(ij._READONLY_CACHE) == {key_a, key_c}
    assert key_b not in ij._READONLY_CACHE

    clock[0] = 1007.9
    assert ij._prune_cache(ij._READONLY_CACHE, now=clock[0]) is True
    assert set(ij._READONLY_CACHE) == {key_c}
    clock[0] = 1008.0
    assert ij._prune_cache(ij._READONLY_CACHE, now=clock[0]) is True
    assert ij._READONLY_CACHE == {}


def test_read_cache_load_removes_expired_entries_from_disk(
        monkeypatch, isolated_read_cache):
    monkeypatch.setattr(ij, "_CACHE_TTL_SECONDS", 5)
    monkeypatch.setattr(ij, "_cache_now", lambda: 1008.0)
    key = ij._readonly_cache_key(
        "server", 8001, {"command": "job_status", "job_id": "expired"})
    payload = {
        "version": ij._CACHE_SCHEMA_VERSION,
        "entries": {
            key: {
                "hash": "expired-hash",
                "result": {"state": "running"},
                "stored_at": 1000.0,
                "accessed_at": 1001.0,
            }
        },
    }
    Path(ij._CACHE_FILE).write_text(json.dumps(payload), encoding="utf-8")

    assert ij._load_cache_from_disk() == {}
    rewritten = json.loads(Path(ij._CACHE_FILE).read_text(encoding="utf-8"))
    assert rewritten == {"version": ij._CACHE_SCHEMA_VERSION, "entries": {}}


def test_read_cache_rejects_single_entry_over_retained_byte_limit(
        monkeypatch, isolated_read_cache):
    monkeypatch.setattr(ij, "_CACHE_MAX_RETAINED_BYTES", 256)
    key = ij._readonly_cache_key(
        "server", 8001, {"command": "get_console", "tail": 100})

    assert ij._cache_store(key, "large-hash", {"text": "x" * 1024}) is False
    assert ij._READONLY_CACHE == {}
    assert ij._cache_retained_bytes(ij._READONLY_CACHE) == 0


def test_read_cache_does_not_retain_caller_mutations(
        monkeypatch, isolated_read_cache):
    session = ScriptedSession(responses=[
        {"ok": True, "hash": "state-hash", "result": {"items": ["safe"]}},
        {"ok": True, "unchanged": True},
    ])
    monkeypatch.setattr(ij, "_session_for", lambda host, port: session)

    first = ij.imagej_command(
        {"command": "get_state"}, host="server", port=8001)
    first["result"]["items"].append("caller-owned")
    replay = ij.imagej_command(
        {"command": "get_state"}, host="server", port=8001)

    assert replay["result"] == {"items": ["safe"]}


def test_read_cache_rejects_duplicate_json_keys(
        isolated_read_cache):
    Path(ij._CACHE_FILE).write_text(
        '{"version":3,"version":3,"entries":{}}', encoding="utf-8")

    assert ij._load_cache_from_disk() == {}


def test_job_status_routes_to_the_supplied_endpoint(monkeypatch):
    calls = capture_imagej_command(monkeypatch)

    ij.job_status("job-a", host="remote-host", port=8801)

    assert calls == [
        (
            {"command": "job_status", "job_id": "job-a"},
            (),
            {"host": "remote-host", "port": 8801},
        )
    ]


def test_wait_for_job_treats_timed_out_as_terminal_on_non_default_endpoint(
        monkeypatch):
    status_calls = []

    def fake_status(job_id, host, port):
        status_calls.append((job_id, host, port))
        return {"ok": True, "result": {"state": "timed_out"}}

    monkeypatch.setattr(ij, "job_status", fake_status)
    monkeypatch.setattr(
        ij, "_session_for",
        lambda host, port: pytest.fail("terminal status must not subscribe"))

    result = ij.wait_for_job(
        "job-timeout", host="remote-host", port=8801, timeout=10)

    assert result["result"]["state"] == "timed_out"
    assert status_calls == [("job-timeout", "remote-host", 8801)]


def test_wait_for_job_handles_terminal_event_before_subscription_ack(
        monkeypatch):
    statuses = iter([
        {"ok": True, "result": {"state": "running"}},
        {"ok": True, "result": {"state": "completed"}},
    ])
    status_calls = []
    session = ScriptedSession(frames=[
        {"event": "job.completed", "data": {"job_id": "job-a"}},
        {"event": "subscribed", "data": {"topics": ["job.*"]}},
    ])

    def fake_status(job_id, host, port):
        status_calls.append((job_id, host, port))
        return next(statuses)

    monkeypatch.setattr(ij, "job_status", fake_status)
    monkeypatch.setattr(ij, "_session_for", lambda host, port: session)

    result = ij.wait_for_job(
        "job-a", host="remote-host", port=8801, timeout=10)

    assert result["result"]["state"] == "completed"
    assert status_calls == [
        ("job-a", "remote-host", 8801),
        ("job-a", "remote-host", 8801),
    ]


def test_wait_for_job_polling_keeps_endpoint_and_uses_monotonic_deadline(
        monkeypatch):
    statuses = iter([
        {"ok": True, "result": {"state": "running"}},
        {"ok": True, "result": {"state": "running"}},
        {"ok": True, "result": {"state": "timed_out"}},
    ])
    status_calls = []
    session = ScriptedSession(frames=[])

    def fake_status(job_id, host, port):
        status_calls.append((job_id, host, port))
        return next(statuses)

    monkeypatch.setattr(ij, "job_status", fake_status)
    monkeypatch.setattr(ij, "_session_for", lambda host, port: session)
    monkeypatch.setattr(
        time, "time",
        lambda: pytest.fail("wait_for_job must use time.monotonic"))

    result = ij.wait_for_job(
        "job-a",
        host="remote-host",
        port=8801,
        timeout=10,
        poll_interval=0,
    )

    assert result["result"]["state"] == "timed_out"
    assert status_calls == [
        ("job-a", "remote-host", 8801),
        ("job-a", "remote-host", 8801),
        ("job-a", "remote-host", 8801),
    ]


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
