from __future__ import annotations

import json
import sys
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b import harvest_recipe, tools_fiji, tools_jobs  # noqa: E402
import recipe_search  # noqa: E402


def test_run_macro_does_not_hidden_probe(monkeypatch):
    commands: list[str] = []

    monkeypatch.setattr(tools_fiji.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.safety, "audit_log", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.lint, "lint_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.visual_diff, "is_destructive", lambda code: False)

    def fake_send(command, **payload):
        commands.append(command)
        return {"ok": True, "result": {"success": True, "output": "macro ok"}}

    monkeypatch.setattr(tools_fiji, "send", fake_send)

    resp = tools_fiji.run_macro('run("Gaussian Blur...", "sigma=2");')

    assert resp["ok"] is True
    assert commands == ["execute_macro"]


def test_run_macro_visual_diff_runs_without_export_folder(monkeypatch):
    captures: list[str] = []

    monkeypatch.setattr(tools_fiji.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.safety, "audit_log", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.lint, "lint_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.visual_diff, "is_destructive", lambda code: True)
    monkeypatch.setattr(
        tools_fiji,
        "send",
        lambda command, **payload: {"ok": True, "result": {"output": "macro ok"}},
    )

    def fake_capture_thumbnail():
        captures.append("capture")
        return {"tag": len(captures)}

    monkeypatch.setattr(tools_fiji.visual_diff, "capture_thumbnail", fake_capture_thumbnail)
    monkeypatch.setattr(
        tools_fiji.visual_diff,
        "diff_report",
        lambda before, after, code, new_images=None: {"consistent": True, "reason": "ok", "numbers": {}},
    )

    resp = tools_fiji.run_macro('run("Gaussian Blur...", "sigma=2");')

    assert captures == ["capture", "capture"]
    assert resp["visual_diff"]["consistent"] is True


def test_run_macro_async_job_status_logs_success_and_visual_diff(monkeypatch):
    tools_jobs._ASYNC_TRACK.clear()
    audit_calls: list[tuple] = []

    monkeypatch.setattr(tools_jobs.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(
        tools_jobs.safety,
        "audit_log",
        lambda *args, **kwargs: audit_calls.append((args, kwargs)),
    )
    monkeypatch.setattr(tools_jobs.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_jobs.lint, "lint_macro", lambda code: None)
    monkeypatch.setattr(tools_jobs.visual_diff, "is_destructive", lambda code: True)
    monkeypatch.setattr(
        tools_jobs.visual_diff,
        "capture_thumbnail",
        lambda: {"_pixels": [], "bins": [1.0], "mean": 0.0, "max": 1.0, "bit_depth": 8},
    )
    monkeypatch.setattr(
        tools_jobs.visual_diff,
        "diff_report",
        lambda before, after, code, new_images=None: {"consistent": False, "reason": "bad diff", "numbers": {}},
    )

    def fake_send(command, **payload):
        if command == "execute_macro_async":
            return {"ok": True, "result": {"job_id": "j1"}}
        if command == "job_status":
            return {"ok": True, "result": {"job_id": "j1", "state": "completed", "output": "done"}}
        raise AssertionError("unexpected command {}".format(command))

    monkeypatch.setattr(tools_jobs, "send", fake_send)

    job_id = tools_jobs.run_macro_async('run("Median...", "radius=2");')
    assert job_id == "j1"
    assert audit_calls == []

    resp = tools_jobs.job_status("j1")

    assert len(audit_calls) == 1
    args, kwargs = audit_calls[0]
    assert args[0] == "macro"
    assert args[1] == 'run("Median...", "radius=2");'
    assert kwargs["success"] is True
    assert kwargs["metadata"]["job_id"] == "j1"
    assert resp["visual_diff"]["consistent"] is False
    assert resp["result"]["output"].startswith("VISUAL DIFF WARNING:")
    assert "j1" not in tools_jobs._ASYNC_TRACK


def test_run_macro_async_does_not_hidden_probe(monkeypatch):
    commands: list[str] = []

    tools_jobs._ASYNC_TRACK.clear()
    monkeypatch.setattr(tools_jobs.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(tools_jobs.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_jobs.lint, "lint_macro", lambda code: None)
    monkeypatch.setattr(tools_jobs.visual_diff, "is_destructive", lambda code: False)

    def fake_send(command, **payload):
        commands.append(command)
        if command == "execute_macro_async":
            return {"ok": True, "result": {"job_id": "job-1"}}
        raise AssertionError("unexpected command {}".format(command))

    monkeypatch.setattr(tools_jobs, "send", fake_send)

    job_id = tools_jobs.run_macro_async('run("Median...", "radius=2");')

    assert job_id == "job-1"
    assert commands == ["execute_macro_async"]


def test_harvest_recipe_filters_to_current_session_and_success(monkeypatch, tmp_path):
    export_dir = tmp_path / "AI_Exports"
    export_dir.mkdir()
    current_session = "gemma-current"

    records = [
        {
            "session_id": "gemma-old",
            "source": "macro",
            "code": 'run("Gaussian Blur...", "sigma=1");',
            "success": True,
        },
        {
            "session_id": current_session,
            "source": "macro",
            "code": 'run("Gaussian Blur...", "sigma=2");',
            "success": True,
        },
        {
            "session_id": current_session,
            "source": "macro",
            "code": 'run("Median...", "radius=3");',
            "success": False,
        },
        {
            "session_id": current_session,
            "source": "script",
            "code": 'print("hello")',
            "success": True,
        },
    ]
    (export_dir / "audit.log").write_text(
        "".join(json.dumps(item) + "\n" for item in records),
        encoding="utf-8",
    )

    monkeypatch.setattr(harvest_recipe.safety, "session_id", lambda: current_session)
    monkeypatch.setattr(harvest_recipe.active_image, "current_export_folder", lambda: str(export_dir))
    monkeypatch.setattr(
        harvest_recipe,
        "_describe_image_text",
        lambda: (
            'Active image "sample.tif" is 16-bit, 2048×2048 pixels, '
            'single channel, single z, single frame. '
            'Pixel size is calibrated at 0.325 um per pixel.'
        ),
    )

    entries = harvest_recipe.read_audit_log(str(export_dir))
    filtered = harvest_recipe.filter_workflow(entries)
    recipe = harvest_recipe.draft_recipe("Blur workflow", "desc", [])
    issues = recipe_search.validate_recipe(recipe)

    assert len(entries) == 3
    assert len(filtered) == 1
    assert filtered[0]["code"] == 'run("Gaussian Blur...", "sigma=2");'
    assert issues == []
    assert recipe["created"]["session_id"] == current_session
    assert recipe["domain"] == "preprocessing"
    assert recipe["preconditions"]["image_type"] == ["16-bit"]
    assert recipe["preconditions"]["min_channels"] == 1
    assert recipe["preconditions"]["needs_stack"] is False
    assert recipe["outputs"] == [
        {
            "type": "image",
            "description": "Processed image, mask, or derived ImageJ window created by the workflow.",
        }
    ]
    assert recipe["tags"] == ["preprocessing", "16-bit", "single_channel"]
    assert len(recipe["steps"]) == 1
    assert recipe["steps"][0]["macro"] == 'run("Gaussian Blur...", "sigma=2");'
    assert recipe["steps"][0]["description"] == "Run Gaussian Blur"
    assert recipe["parameters"][0]["name"] == "sigma"
    assert recipe["parameters"][0]["type"] == "numeric"
    assert recipe["parameters"][0]["default"] == 2
    assert recipe["parameters"][0]["range"] == [0, 6]
    assert recipe["parameters"][0]["image_specific"] is True
    assert recipe["known_issues"]
