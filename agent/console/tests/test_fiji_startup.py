"""The console may launch a selected Fiji, but must never duplicate one."""
from __future__ import annotations

import os

import pytest

from agent.console import fiji_startup as startup
from agent.console.fiji import FijiConnection, FijiError


@pytest.fixture()
def fiji(tmp_path, monkeypatch):
    root = tmp_path / "Fiji.app"
    root.mkdir()
    (root / "ImageJ-win64.exe").write_bytes(b"test launcher")
    monkeypatch.setattr(startup.console_config, "CONFIG_DIR", tmp_path / "home")
    return root


def test_request_targets_one_installation_and_port(fiji):
    request = startup.write_start_request(fiji, 7746, now=100.0)
    body = request.read_text(encoding="utf-8")
    assert f"target_uri={fiji.resolve().as_uri()}\n" in body
    assert "port=7746\n" in body
    assert "created_ms=100000\n" in body
    with pytest.raises(ValueError):
        startup.write_start_request(fiji, 0)


def test_auto_detection_prefers_running_fiji(fiji, tmp_path, monkeypatch):
    monkeypatch.setattr(startup, "running_fiji_roots", lambda: [fiji])
    monkeypatch.setattr(startup, "find_workspace", lambda: None)
    monkeypatch.chdir(tmp_path)
    assert startup.candidates()[0] == fiji.resolve()


def test_copied_batch_launcher_cannot_start_another_fiji(fiji, tmp_path):
    if os.name != "nt":
        pytest.skip("Windows batch launchers only")
    script = fiji / "Launch Fiji.bat"
    script.write_text(f'start "" "{tmp_path / "Other.app" / "ImageJ-win64.exe"}"',
                      encoding="utf-8")
    assert startup.launcher_for(fiji) == fiji / "ImageJ-win64.exe"
    script.write_text('start "" "%~dp0ImageJ-win64.exe"', encoding="utf-8")
    assert startup.launcher_for(fiji) == script


def test_packaged_launcher_uses_bundled_supported_java(fiji, monkeypatch):
    if os.name != "nt":
        pytest.skip("Windows Fiji launcher only")
    java = fiji / "java" / "win64" / "zulu11-jdk11.0.31"
    (java / "bin").mkdir(parents=True)
    (java / "bin" / "java.exe").write_bytes(b"test")
    seen = []
    monkeypatch.setattr(startup.subprocess, "Popen",
                        lambda command, **kwargs: seen.append(command))
    startup.launch_fiji(fiji)
    assert seen == [[str(fiji / "ImageJ-win64.exe"), "--java-home", str(java),
                     "--default-gc"]]


def test_starts_selected_fiji_then_connects(fiji, monkeypatch):
    monkeypatch.setattr(startup, "_matching_process_running", lambda root: False)
    launches = []
    monkeypatch.setattr(startup, "launch_fiji", lambda root: launches.append(root))
    monkeypatch.setattr(startup.time, "sleep", lambda _: None)
    attempts = 0

    def probe():
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise ConnectionError("offline")
        return fiji.resolve()

    assert startup.ensure_fiji(fiji, 7746, probe) == "launched and connected"
    assert launches == [fiji.resolve()]
    assert not startup.request_path().exists()


def test_cold_start_closes_matching_dialog_by_default(fiji, monkeypatch):
    from agent.console import windows_startup_dialog
    monkeypatch.setattr(startup, "_matching_process_running", lambda root: False)
    monkeypatch.setattr(startup, "launch_fiji", lambda root: None)
    monkeypatch.setattr(startup.time, "sleep", lambda _: None)
    closed = []
    monkeypatch.setattr(windows_startup_dialog, "dismiss_startup_error",
                        lambda root: closed.append(root) or True)
    attempts = 0

    def probe():
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise ConnectionError("startup dialog blocks Fiji")
        return fiji.resolve()

    assert startup.ensure_fiji(fiji, 7746, probe) == "launched and connected"
    assert closed == [fiji.resolve(), fiji.resolve()]
    assert not startup.request_path().exists()


def test_closed_startup_dialog_keeps_request_alive_until_fiji_finishes(fiji, monkeypatch):
    from agent.console import windows_startup_dialog
    clock = [0.0]
    monkeypatch.setattr(startup, "_matching_process_running", lambda root: False)
    monkeypatch.setattr(startup, "launch_fiji", lambda root: None)
    monkeypatch.setattr(startup.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(startup.time, "sleep", lambda duration: clock.__setitem__(
        0, 24.0 if clock[0] == 0.0 else clock[0] + duration))
    monkeypatch.setattr(windows_startup_dialog, "dismiss_startup_error",
                        lambda root: clock[0] >= 24.0)

    def probe():
        if clock[0] < 50.0:
            raise ConnectionError("Fiji is finishing startup")
        return fiji.resolve()

    assert startup.ensure_fiji(fiji, 7746, probe,
                               close_startup_error=True) == "launched and connected"
    assert clock[0] == 50.0


def test_startup_dialog_requires_selected_process_and_exact_shape(fiji):
    from agent.console.windows_startup_dialog import _is_selected_fiji_dialog
    executable = str(fiji / "ImageJ-win64.exe")
    children = [("SunAwtCanvas", ""), ("Button", "  OK  ")]
    assert _is_selected_fiji_dialog(fiji, executable, "(Fiji Is Just) ImageJ",
                                    "SunAwtDialog", children)
    assert not _is_selected_fiji_dialog(fiji, executable.replace("Fiji.app", "Other.app"),
                                        "(Fiji Is Just) ImageJ", "SunAwtDialog", children)
    assert not _is_selected_fiji_dialog(fiji, executable, "(Fiji Is Just) ImageJ",
                                        "SunAwtFrame", children)
    assert not _is_selected_fiji_dialog(fiji, executable, "(Fiji Is Just) ImageJ",
                                        "SunAwtDialog", [("Button", "Cancel")])


def test_running_fiji_gets_request_without_duplicate_launch(fiji, monkeypatch):
    from agent.console import windows_startup_dialog
    monkeypatch.setattr(startup, "_matching_process_running", lambda root: True)
    monkeypatch.setattr(startup, "launch_fiji", lambda root: pytest.fail("launched duplicate Fiji"))
    monkeypatch.setattr(windows_startup_dialog, "dismiss_startup_error",
                        lambda root: pytest.fail("closed a running Fiji dialog"))
    monkeypatch.setattr(startup.time, "sleep", lambda _: None)
    attempts = 0

    def probe():
        nonlocal attempts
        attempts += 1
        if attempts < 2:
            raise ConnectionError("offline")
        return fiji.resolve()

    assert startup.ensure_fiji(
        fiji, 7746, probe, close_startup_error=True) == "connected to running Fiji"


def test_startup_dialog_appearing_after_connection_timeout_is_still_closed(fiji, monkeypatch):
    from agent.console import windows_startup_dialog
    clock = [0.0]
    closed = [False]
    monkeypatch.setattr(startup, "_matching_process_running", lambda root: False)
    monkeypatch.setattr(startup, "launch_fiji", lambda root: None)
    monkeypatch.setattr(startup.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(startup.time, "sleep", lambda duration: clock.__setitem__(0, clock[0] + duration))

    def dismiss(root):
        closed[0] = clock[0] >= 30.0
        return closed[0]

    def probe():
        if not closed[0]:
            raise ConnectionError("startup error has not appeared yet")
        return fiji.resolve()

    monkeypatch.setattr(windows_startup_dialog, "dismiss_startup_error", dismiss)
    assert startup.ensure_fiji(fiji, 7746, probe, timeout_s=25.0) == "launched and connected"
    assert closed[0]
    assert clock[0] == 30.0  # returns immediately after recovery, without a fixed wait


def test_refuses_port_owned_by_different_fiji(fiji, tmp_path, monkeypatch):
    other = tmp_path / "Other-Fiji.app"
    other.mkdir()
    (other / "ImageJ-win64.exe").write_bytes(b"test launcher")
    monkeypatch.setattr(startup, "launch_fiji", lambda root: pytest.fail("launched Fiji"))
    with pytest.raises(RuntimeError, match="different Fiji installation"):
        startup.ensure_fiji(fiji, 7746, lambda: other.resolve())
    assert not startup.request_path().exists()


def test_fiji_commands_are_blocked_after_selecting_another_installation(fiji, tmp_path):
    other = tmp_path / "Other-Fiji.app"
    other.mkdir()
    calls = []

    class FakeIJ:
        @staticmethod
        def hello(**kwargs):
            return {"ok": True, "result": {"installation_uri": other.as_uri()}}

        @staticmethod
        def get_state():
            calls.append("get_state")
            return {"ok": True}

    connection = FijiConnection("127.0.0.1", 7746)
    connection._ij = FakeIJ()
    connection.select_installation(fiji)
    with pytest.raises(FijiError, match="different Fiji installation"):
        connection.get_state()
    assert calls == []
