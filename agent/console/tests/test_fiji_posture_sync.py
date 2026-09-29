"""A console choice must reach Fiji's authoritative posture controller."""
from __future__ import annotations

import pytest

from agent.console.fiji import FijiConnection, FijiError, summarize_state
from agent.console.posture import FolderPostureStore, Posture, PostureController


def test_posture_choice_calls_fiji_controller_and_checks_reply():
    connection = object.__new__(FijiConnection)
    scripts = []

    def run_script(code, language, timeout):
        scripts.append((code, language, timeout))
        return {"ok": True, "result": {"success": True, "output": "STANDARD"}}

    connection.run_script = run_script
    assert connection.set_privacy_posture("STANDARD") == "STANDARD"
    assert "requestPosture(imagejai.config.PrivacyPosture.STANDARD" in scripts[0][0]
    assert scripts[0][1:] == ("groovy", 10)
    assert connection.privacy_posture() == "STANDARD"
    assert "requestPosture" not in scripts[1][0]
    with pytest.raises(ValueError):
        connection.set_privacy_posture("STANDARD; System.exit(0)")


def test_posture_choice_rejects_unconfirmed_script_result():
    connection = object.__new__(FijiConnection)
    connection.run_script = lambda *_: {
        "ok": True, "result": {"success": False, "error": "not allowed"}}
    with pytest.raises(FijiError, match="not allowed"):
        connection.set_privacy_posture("STANDARD")


def test_adopting_fiji_posture_does_not_write_a_second_folder_choice(tmp_path):
    controller = PostureController(FolderPostureStore())
    controller.on_folder_opened(tmp_path)
    controller.sync_from_fiji(Posture.STANDARD)
    assert controller.current is Posture.STANDARD
    assert not (tmp_path / ".imagejai-posture.json").exists()


def test_state_summary_reads_the_java_tcp_field_names():
    state = summarize_state({"ok": True, "result": {
        "allImages": [{"title": "cells.tif", "channels": 2}],
        "activeImage": {"title": "cells.tif", "channels": 2},
        "memory": {"usedMB": 80},
    }})
    assert state["n_images"] == 1
    assert state["active_title"] == "cells.tif"
    assert state["active"]["channels"] == 2
    assert state["memory"]["usedMB"] == 80
