from __future__ import annotations

import importlib.util
from pathlib import Path


IJ_PATH = Path(__file__).with_name("ij.py")
SPEC = importlib.util.spec_from_file_location("ij_under_test", IJ_PATH)
ij = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(ij)


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
    ]:
        assert name in ij.__all__
        assert hasattr(ij, name)


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
