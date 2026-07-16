from __future__ import annotations

import base64
import io
import json
import socket
import sys
import threading
import time
from pathlib import Path

import pytest


AGENT_DIR = Path(__file__).resolve().parent
if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))

import ij  # noqa: E402
from imagej_use import helpers  # noqa: E402
from imagej_use import run  # noqa: E402


PNG = b"\x89PNG\r\n\x1a\n" + b"stage24-test"
PNG_B64 = base64.b64encode(PNG).decode("ascii")


class FakeSession:
    def __init__(self, hello=None, screenshot=None):
        self.session_id = "session-one"
        self.hello_response = hello or {
            "ok": True,
            "result": {
                "session_id": self.session_id,
                "server_version": "1.8.0",
                "compatibility": False,
            },
        }
        self.screenshot_response = screenshot or {
            "ok": True, "result": {"base64": PNG_B64}}
        self.calls = []

    def hello(self):
        self.calls.append(("hello",))
        return self.hello_response

    def _call(self, name, *args, **kwargs):
        self.calls.append((name, args, kwargs))
        return {"ok": True, "result": name}

    def ping(self):
        return self._call("ping")

    def get_state(self):
        return self._call("get_state")

    def get_image_info(self):
        return self._call("get_image_info")

    def get_results_table(self):
        return self._call("get_results_table")

    def run_macro(self, code):
        return self._call("run_macro", code)

    def run_script(self, code, language="groovy", timeout=180):
        return self._call("run_script", code, language, timeout)

    def capture_image(self, max_size=1024):
        self.calls.append(("capture_image", (max_size,), {}))
        return self.screenshot_response

    def wait_for_event(self, topics=None, predicate=None, timeout=60):
        return self._call("wait_for_event", topics, predicate, timeout)

    def get_dialogs(self):
        return self._call("get_dialogs")

    def interact_dialog(self, action, **kwargs):
        return self._call("interact_dialog", action, **kwargs)


class EventLoopbackServer:
    def __init__(self):
        self.requests = []
        self.errors = []
        self._done = threading.Event()
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(2)
        self._listener.settimeout(3)
        self.port = self._listener.getsockname()[1]
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    @staticmethod
    def _request(conn):
        data = b""
        while not data.endswith(b"\n"):
            chunk = conn.recv(65536)
            if not chunk:
                break
            data += chunk
        return json.loads(data.decode("utf-8"))

    def _run(self):
        try:
            conn, _ = self._listener.accept()
            with conn:
                hello = self._request(conn)
                self.requests.append(hello)
                response = {
                    "ok": True,
                    "result": {
                        "session_id": "event-session",
                        "expires_at": int(time.time() * 1000) + 60_000,
                        "server_version": "1.8.0",
                    },
                }
                conn.sendall((json.dumps(response) + "\n").encode("utf-8"))
            conn, _ = self._listener.accept()
            with conn:
                subscribe = self._request(conn)
                self.requests.append(subscribe)
                frames = [
                    {"event": "subscribed", "data": {"topics": ["macro.*"]}},
                    {"event": "macro.started", "data": {"id": "wrong"}},
                    {"event": "macro.completed", "data": {"id": "wanted"}},
                ]
                conn.sendall(b"".join(
                    (json.dumps(frame) + "\n").encode("utf-8")
                    for frame in frames))
        except Exception as exc:
            self.errors.append(exc)
        finally:
            self._listener.close()
            self._done.set()

    def finish(self):
        assert self._done.wait(5), "loopback server did not finish"
        self._thread.join(timeout=1)
        assert not self.errors


def test_stdin_runner_preloads_helpers_on_one_session(tmp_path):
    session = FakeSession()
    output = io.StringIO()
    source = """
print(get_state()['result'])
print(run_macro('run(\"Invert\");')['result'])
print(screenshot_to_path('.tmp/result.png'))
print(wait_for_event('macro.*', {'event': 'macro.completed'}, 2)['result'])
"""

    run._execute(
        source, session,
        {helpers.WORKSPACE_ENV: str(tmp_path)}, output)

    assert output.getvalue().splitlines()[0:2] == ["get_state", "run_macro"]
    assert (tmp_path / ".tmp" / "result.png").read_bytes() == PNG
    assert [call[0] for call in session.calls] == [
        "hello", "get_state", "run_macro", "capture_image", "wait_for_event"]


def test_governed_event_wait_uses_same_authenticated_loopback_session():
    server = EventLoopbackServer()
    session = ij.ImageJSession(
        host="127.0.0.1",
        port=server.port,
        token_loader=lambda: "install-secret",
        capabilities={"accept_events": ["*"]},
        client_session_id="",
        model_endpoint="",
    )

    frame = session.wait_for_event(
        "macro.*",
        predicate={"event": "macro.completed", "data": {"id": "wanted"}},
        timeout=2,
    )
    server.finish()

    assert frame["event"] == "macro.completed"
    assert server.requests[1] == {
        "command": "subscribe",
        "topics": ["macro.*"],
        "session_id": "event-session",
        "token": "install-secret",
    }


def test_screenshot_path_is_workspace_scoped_and_atomic(tmp_path):
    session = FakeSession()
    saved = helpers.screenshot_to_path(
        session, "nested/view.png", workspace=tmp_path)

    assert Path(saved) == tmp_path / "nested" / "view.png"
    assert Path(saved).read_bytes() == PNG
    assert list((tmp_path / "nested").glob("*.tmp")) == []


def test_screenshot_rejects_traversal_before_creating_outside_directory(tmp_path):
    session = FakeSession()
    outside = tmp_path.parent / "stage24-should-not-exist" / "view.png"

    with pytest.raises(helpers.WorkspaceError, match="escapes"):
        helpers.screenshot_to_path(
            session, "../stage24-should-not-exist/view.png", workspace=tmp_path)

    assert not outside.parent.exists()
    assert all(call[0] != "capture_image" for call in session.calls)


def test_screenshot_rejects_missing_or_non_png_payload(tmp_path):
    missing = FakeSession(screenshot={"ok": True, "result": {}})
    wrong = FakeSession(screenshot={
        "ok": True,
        "result": {"base64": base64.b64encode(b"not-png").decode("ascii")},
    })

    with pytest.raises(helpers.ScreenshotError, match="base64 PNG"):
        helpers.screenshot_to_path(missing, "missing.png", workspace=tmp_path)
    with pytest.raises(helpers.ScreenshotError, match="not a PNG"):
        helpers.screenshot_to_path(wrong, "wrong.png", workspace=tmp_path)


def test_workspace_helpers_require_explicit_path_and_cannot_shadow_core(tmp_path):
    core = {"session": object(), "get_state": lambda: None}
    helper_file = tmp_path / "imagej_helpers.py"
    helper_file.write_text("value = 42\n__all__ = ['value']\n", encoding="utf-8")

    assert helpers.load_workspace_helpers(None, core) == {}
    assert helpers.load_workspace_helpers(tmp_path, core) == {"value": 42}

    helper_file.write_text("session = 'bad'\n__all__ = ['session']\n", encoding="utf-8")
    with pytest.raises(helpers.WorkspaceError, match="cannot shadow"):
        helpers.load_workspace_helpers(tmp_path, core)


@pytest.mark.parametrize(
    "hello,expected_check,expected_code",
    [
        (
            {"ok": False, "error": {
                "code": "transport_unreachable", "message": "refused"}},
            "fiji_reachability", "fiji_unreachable",
        ),
        (
            {"ok": False, "error": {
                "code": "invalid_token", "message": "bad token"}},
            "authentication", "authentication_failed",
        ),
        (
            {"ok": True, "result": {
                "session_id": "s", "server_version": "2.0.0"}},
            "protocol", "protocol_mismatch",
        ),
    ],
)
def test_doctor_distinguishes_transport_auth_and_protocol(
        tmp_path, hello, expected_check, expected_code):
    report = run.doctor(
        FakeSession(hello=hello),
        {helpers.WORKSPACE_ENV: str(tmp_path)})
    checks = {item["name"]: item for item in report["checks"]}

    assert report["ok"] is False
    assert checks[expected_check]["code"] == expected_code
    assert checks[expected_check]["status"] == "fail"


def test_doctor_distinguishes_workspace_and_screenshot_failures(tmp_path):
    no_workspace = run.doctor(FakeSession(), {})
    no_workspace_checks = {item["name"]: item for item in no_workspace["checks"]}
    assert no_workspace_checks["workspace"]["code"] == "workspace_invalid"
    assert no_workspace_checks["screenshot"]["status"] == "skipped"

    screenshot_failure = run.doctor(
        FakeSession(screenshot={"ok": False, "error": "privacy refusal"}),
        {helpers.WORKSPACE_ENV: str(tmp_path)})
    screenshot_checks = {
        item["name"]: item for item in screenshot_failure["checks"]}
    assert screenshot_checks["screenshot"]["code"] == "screenshot_failed"
    assert screenshot_checks["screenshot"]["status"] == "fail"


def test_doctor_success_cleans_its_probe_screenshot(tmp_path):
    report = run.doctor(
        FakeSession(), {helpers.WORKSPACE_ENV: str(tmp_path)})

    assert report["ok"] is True
    assert not (tmp_path / ".imagej-use-auto" / "doctor.png").exists()


def test_runner_has_no_process_daemon_or_coordinate_control():
    source = (AGENT_DIR / "imagej_use" / "run.py").read_text(encoding="utf-8")
    helper_source = (AGENT_DIR / "imagej_use" / "helpers.py").read_text(
        encoding="utf-8")
    combined = source + helper_source

    assert "subprocess" not in combined
    assert "Popen" not in combined
    assert "Robot" not in combined
    assert ".listen(" not in combined
    assert "screen_x" not in combined
    assert "screen_y" not in combined


def test_package_declares_imagej_use_auto_entry_point():
    pyproject = (AGENT_DIR / "pyproject.toml").read_text(encoding="utf-8")
    assert 'imagej-use-auto = "imagej_use.run:main"' in pyproject
