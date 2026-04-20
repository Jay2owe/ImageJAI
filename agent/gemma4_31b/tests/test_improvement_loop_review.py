from __future__ import annotations

import sys
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b import events, lint, tools_fiji, tools_jobs, visual_diff  # noqa: E402


def _thumb(value: float) -> dict:
    arr = visual_diff.np.full((4, 4), value, dtype=visual_diff.np.float32)
    return {
        "_pixels": arr,
        "bins": [1.0, 0.0],
        "mean": float(value),
        "max": 255.0,
        "bit_depth": 8,
    }


def test_visual_diff_skips_duplicate_then_blur_with_single_new_image():
    report = visual_diff.diff_report(
        _thumb(0.0),
        _thumb(255.0),
        'run("Duplicate...", "title=gaussian_1"); run("Gaussian Blur...", "sigma=2");',
        new_images=["gaussian_1"],
    )

    assert report["consistent"] is True
    assert "comparison skipped" in report["reason"]


def test_run_macro_uses_macro_titles_for_event_mask_and_diff(monkeypatch):
    marked: list[str] = []
    seen_new_images: list[list[str]] = []

    monkeypatch.setattr(tools_fiji.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.safety, "audit_log", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.lint, "lint_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.visual_diff, "is_destructive", lambda code: True)
    monkeypatch.setattr(tools_fiji.visual_diff, "capture_thumbnail", lambda: {"tag": "thumb"})
    monkeypatch.setattr(
        tools_fiji.visual_diff,
        "diff_report",
        lambda before, after, code, new_images=None: (
            seen_new_images.append(list(new_images or []))
            or {"consistent": True, "reason": "ok", "numbers": {}}
        ),
    )
    monkeypatch.setattr(
        tools_fiji.events,
        "mark_image_created_by_agent",
        lambda title: marked.append(title),
    )
    monkeypatch.setattr(
        tools_fiji,
        "send",
        lambda command, **payload: {
            "ok": True,
            "result": {"success": True, "output": "", "newImages": ["laplacian"]},
        },
    )

    code = (
        'run("Duplicate...", "title=raw");'
        'run("Duplicate...", "title=gaussian_1");'
        'run("Gaussian Blur...", "sigma=2");'
    )
    tools_fiji.run_macro(code)

    assert marked == ["raw", "gaussian_1"]
    assert seen_new_images == [["raw", "gaussian_1"]]


def test_run_macro_does_not_mark_sync_active_image_without_create_ops(monkeypatch):
    marked: list[str] = []
    seen_new_images: list[list[str]] = []

    monkeypatch.setattr(tools_fiji.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.safety, "audit_log", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_fiji.lint, "lint_macro", lambda code: None)
    monkeypatch.setattr(tools_fiji.visual_diff, "is_destructive", lambda code: True)
    monkeypatch.setattr(tools_fiji.visual_diff, "capture_thumbnail", lambda: {"tag": "thumb"})
    monkeypatch.setattr(
        tools_fiji.visual_diff,
        "diff_report",
        lambda before, after, code, new_images=None: (
            seen_new_images.append(list(new_images or []))
            or {"consistent": True, "reason": "ok", "numbers": {}}
        ),
    )
    monkeypatch.setattr(
        tools_fiji.events,
        "mark_image_created_by_agent",
        lambda title: marked.append(title),
    )
    monkeypatch.setattr(
        tools_fiji,
        "send",
        lambda command, **payload: {
            "ok": True,
            "result": {"success": True, "output": "", "newImages": ["user_source.tif"]},
        },
    )

    tools_fiji.run_macro('run("Median...", "radius=2");')

    assert marked == []
    assert seen_new_images == [[]]


def test_job_status_marks_async_new_images(monkeypatch):
    marked: list[str] = []
    seen_new_images: list[list[str]] = []

    tools_jobs._ASYNC_TRACK.clear()
    monkeypatch.setattr(tools_jobs.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(tools_jobs.safety, "audit_log", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_jobs.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_jobs.lint, "lint_macro", lambda code: None)
    monkeypatch.setattr(tools_jobs.visual_diff, "is_destructive", lambda code: True)
    monkeypatch.setattr(tools_jobs.visual_diff, "capture_thumbnail", lambda: {"tag": "thumb"})
    monkeypatch.setattr(
        tools_jobs.visual_diff,
        "diff_report",
        lambda before, after, code, new_images=None: (
            seen_new_images.append(list(new_images or []))
            or {"consistent": True, "reason": "ok", "numbers": {}}
        ),
    )
    monkeypatch.setattr(
        tools_jobs.events,
        "mark_image_created_by_agent",
        lambda title: marked.append(title),
    )

    def fake_send(command, **payload):
        if command == "execute_macro_async":
            return {"ok": True, "result": {"job_id": "job-1"}}
        if command == "job_status":
            return {
                "ok": True,
                "result": {
                    "job_id": "job-1",
                    "state": "completed",
                    "output": "",
                    "newImages": ["mean_2", "laplacian"],
                },
            }
        raise AssertionError("unexpected command {}".format(command))

    monkeypatch.setattr(tools_jobs, "send", fake_send)

    assert tools_jobs.run_macro_async('run("Gaussian Blur...", "sigma=2");') == "job-1"
    tools_jobs.job_status("job-1")

    assert marked == ["mean_2", "laplacian"]
    assert seen_new_images == [["mean_2", "laplacian"]]


def test_events_mask_title_matching_avoids_broad_false_positives():
    events._AGENT_CREATED_IMAGES.clear()

    assert events.is_likely_agent_created_mask("mean_2")
    assert events.is_likely_agent_created_mask("unsharp_mask")
    assert not events.is_likely_agent_created_mask("mean_slice.tif")
    assert not events.is_likely_agent_created_mask("filter_paper_fig_1.png")
    assert not events.is_likely_agent_created_mask("fluorescent_cells_sharpened.tif")


def test_lint_macro_blocks_unicode_ijm_array_literal():
    code = "\N{GREEK SMALL LETTER MU} = [];"
    result = lint.lint_macro(code)

    assert "newArray" in result


def test_lint_macro_autofix_replace_patches_only_target_literal():
    code = (
        'msg = "Median... (radius=";\n'
        '// debug "Median... (radius="\n'
        'part = replace(part, "Median... (radius=", "");\n'
    )

    result = lint.lint_macro(code)

    assert isinstance(result, tuple)
    patched, warnings = result
    assert 'msg = "Median... (radius=";' in patched
    assert '// debug "Median... (radius="' in patched
    assert 'replace(part, "Median\\\\.\\\\.\\\\. \\\\(radius=", "")' in patched
    assert "Auto-escaped 1 literal(s)" in warnings


def test_lint_macro_autofix_replace_handles_multiline_call_and_escaped_quotes():
    code = (
        'part = replace(\n'
        '    part,\n'
        '    "Median... \\"quoted\\" (radius=",\n'
        '    ""\n'
        ');\n'
    )

    result = lint.lint_macro(code)

    assert isinstance(result, tuple)
    patched, _warnings = result
    assert '"Median\\\\.\\\\.\\\\. \\"quoted\\" \\\\(radius="' in patched


def test_lint_macro_autofix_replace_skips_variable_target():
    code = (
        'needle = "Median... (radius=";\n'
        'part = replace(part, needle, "");\n'
    )

    assert lint.lint_macro(code) is None


def test_run_macro_async_uses_patched_code_and_surfaces_lint_warning(monkeypatch):
    tools_jobs._ASYNC_TRACK.clear()

    monkeypatch.setattr(tools_jobs.safety, "check_macro", lambda code: None)
    monkeypatch.setattr(tools_jobs.safety, "audit_log", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_jobs.safety, "friction_log", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_jobs.safety, "note_execution", lambda *args, **kwargs: None)
    monkeypatch.setattr(tools_jobs.visual_diff, "is_destructive", lambda code: False)
    monkeypatch.setattr(
        tools_jobs.lint,
        "lint_macro",
        lambda code: (
            'part = replace(part, "Median\\.\\.\\. \\(radius=", "");',
            "WARNING: auto-fixed replace target",
        ),
    )

    seen = []

    def fake_send(command, **payload):
        seen.append((command, payload))
        if command == "execute_macro_async":
            return {"ok": True, "result": {"job_id": "job-1"}}
        if command == "job_status":
            return {
                "ok": True,
                "result": {
                    "job_id": "job-1",
                    "state": "completed",
                    "output": "",
                    "newImages": [],
                },
            }
        raise AssertionError("unexpected command {}".format(command))

    monkeypatch.setattr(tools_jobs, "send", fake_send)

    job_id = tools_jobs.run_macro_async('part = replace(part, "Median... (radius=", "");')
    resp = tools_jobs.job_status(job_id)

    assert job_id == "job-1"
    assert seen[0] == (
        "execute_macro_async",
        {"code": 'part = replace(part, "Median\\.\\.\\. \\(radius=", "");'},
    )
    assert resp["lint_warnings"] == "WARNING: auto-fixed replace target"
    assert resp["result"]["output"].startswith("WARNING: auto-fixed replace target")


def test_lint_script_blocks_groovy_hallucinated_ij_run_name():
    result = lint.lint_script(
        'import ij.IJ\nIJ.run("Band Pass Filter...", "filter=Band Pass auto");',
        "groovy",
    )

    assert "Bandpass Filter..." in result


def test_lint_script_blocks_groovy_hallucinated_ij_run_with_image_arg():
    result = lint.lint_script(
        'import ij.IJ\nIJ.run(imp, "Variance", "radius=2");',
        "groovy",
    )

    assert 'IJ.run("Variance"' in result
