from __future__ import annotations

import base64
import struct

from agent.gemma4_31b import loop, tools_jobs, tools_python


FLOAT_DOMAIN = {
    "representation": "raw", "pixel_type": "float32", "signed": True,
    "density_calibrated": False, "acquisition_min_raw": None,
    "acquisition_max_raw": None, "acquisition_min_calibrated": None,
    "acquisition_max_calibrated": None,
}


def _pixel_response(*, x, y, width, height, values=None):
    values = values or [1.0] * (width * height)
    raw = struct.pack("<{}f".format(len(values)), *values)
    return {
        "ok": True,
        "result": {
            "image_id": "image-123",
            "image_revision": 7,
            "display_revision": 11,
            "x": x,
            "y": y,
            "width": width,
            "height": height,
            "sliceStart": 1,
            "sliceEnd": 1,
            "sliceCount": 1,
            "sliceAxis": "Z",
            "channel": 2,
            "frame": 3,
            "channels": 4,
            "slices": 5,
            "frames": 6,
            "nPixels": len(values),
            "type": "32-bit",
            "encoding": "base64_float32_le",
            "value_domain": FLOAT_DOMAIN,
            "acquisition_min_count": None,
            "acquisition_max_count": None,
            "acquisition_limit_counts_exact": False,
            "data": base64.b64encode(raw).decode("ascii"),
        },
    }


def test_timed_out_job_is_terminal_and_releases_tracking(monkeypatch):
    audit = []
    friction = []
    notes = []
    tools_jobs._ASYNC_TRACK.clear()
    tools_jobs._ASYNC_TRACK["job-timeout"] = {
        "code": 'run("Slow");',
        "before_thumb": None,
        "lint_warnings": None,
    }
    monkeypatch.setattr(
        tools_jobs,
        "send",
        lambda command, **payload: {
            "ok": True,
            "result": {
                "job_id": "job-timeout",
                "state": "timed_out",
                "error": "timed out",
            },
        },
    )
    monkeypatch.setattr(tools_jobs.safety, "audit_log", lambda *a, **k: audit.append((a, k)))
    monkeypatch.setattr(tools_jobs.safety, "friction_log", lambda row: friction.append(row))
    monkeypatch.setattr(tools_jobs.safety, "note_execution", lambda value: notes.append(value))

    response = tools_jobs.job_status("job-timeout")

    assert response["result"]["state"] == "timed_out"
    assert "job-timeout" not in tools_jobs._ASYNC_TRACK
    assert audit[0][1]["success"] is False
    assert friction[0]["state"] == "timed_out"
    assert notes == [False]
    assert loop._update_async_job_state("job_status", response, True) is False


def test_completed_job_without_nested_execution_result_fails_closed(monkeypatch):
    audit = []
    tools_jobs._ASYNC_TRACK.clear()
    tools_jobs._ASYNC_TRACK["job-malformed"] = {
        "code": 'run("Test");',
        "before_thumb": None,
        "lint_warnings": None,
    }
    monkeypatch.setattr(
        tools_jobs,
        "send",
        lambda command, **payload: {
            "ok": True,
            "result": {"job_id": "job-malformed", "state": "completed"},
        },
    )
    monkeypatch.setattr(tools_jobs.safety, "audit_log", lambda *a, **k: audit.append((a, k)))
    monkeypatch.setattr(tools_jobs.safety, "friction_log", lambda row: None)
    monkeypatch.setattr(tools_jobs.safety, "note_execution", lambda value: None)

    response = tools_jobs.job_status("job-malformed")

    assert "missing nested execution result" in response["postprocess_error"]
    assert audit[0][1]["success"] is False
    assert "job-malformed" not in tools_jobs._ASYNC_TRACK


def test_region_tools_reject_out_of_bounds_without_pixel_fetch(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: {
        "image_id": "image-123", "image_revision": 7, "display_revision": 11,
        "value_domain": FLOAT_DOMAIN,
        "width": 10, "height": 8, "channel": 2, "sliceStart": 1,
        "sliceEnd": 1, "sliceAxis": "Z", "frame": 3,
        "channels": 4, "slices": 5, "frames": 6,
    })
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("out-of-bounds requests must not reach Fiji")
        ),
    )

    assert "outside active image bounds" in tools_python.region_stats(9, 0, 2, 1)["error"]
    assert "outside active image bounds" in tools_python.get_pixels_array(0, [-1, 0, 1, 1])["error"]
    assert "inside active image bounds" in tools_python.line_profile(0, 0, 10, 0)["error"]


def test_region_stats_rejects_server_clamping_after_image_race(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: {
        "image_id": "image-123", "image_revision": 7, "display_revision": 11,
        "value_domain": FLOAT_DOMAIN,
        "width": 10, "height": 8, "channel": 2, "sliceStart": 1,
        "sliceEnd": 1, "sliceAxis": "Z", "frame": 3,
        "channels": 4, "slices": 5, "frames": 6,
    })
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: _pixel_response(x=9, y=0, width=1, height=1),
    )

    result = tools_python.region_stats(8, 0, 2, 1)

    assert "clamped pixel geometry" in result["error"]


def test_line_profile_rejects_server_clamping_after_image_race(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: {
        "image_id": "image-123", "image_revision": 7, "display_revision": 11,
        "value_domain": FLOAT_DOMAIN,
        "width": 10, "height": 8, "channel": 2, "sliceStart": 1,
        "sliceEnd": 1, "sliceAxis": "Z", "frame": 3,
        "channels": 4, "slices": 5, "frames": 6,
    })
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: _pixel_response(x=1, y=0, width=1, height=1),
    )

    result = tools_python.line_profile(0, 0, 1, 0)

    assert "clamped pixel geometry" in result["error"]


def test_valid_geometry_is_reported_exactly(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: {
        "image_id": "image-123", "image_revision": 7, "display_revision": 11,
        "value_domain": FLOAT_DOMAIN,
        "width": 10, "height": 8, "channel": 2, "sliceStart": 1,
        "sliceEnd": 1, "sliceAxis": "Z", "frame": 3,
        "channels": 4, "slices": 5, "frames": 6,
    })
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: _pixel_response(
            x=2, y=3, width=2, height=1, values=[2.0, 4.0]
        ),
    )

    result = tools_python.region_stats(2, 3, 2, 1)

    assert result["x"] == 2
    assert result["y"] == 3
    assert result["width"] == 2
    assert result["height"] == 1
    assert result["count"] == 2
    assert result["mean"] == 3.0
    assert result["channel"] == 2
    assert result["frame"] == 3
    assert result["sliceAxis"] == "Z"
