from __future__ import annotations

import base64
import json
import struct

from agent.gemma4_31b import tools_python


def _pixel_response(
    *,
    x: int,
    y: int,
    width: int,
    height: int,
    slice_start: int,
    slice_end: int | None = None,
    slice_count: int = 1,
    values=None,
):
    if values is None:
        values = [1.0] * (width * height * slice_count)
    raw = struct.pack("<{}f".format(len(values)), *values)
    return {
        "ok": True,
        "result": {
            "x": x,
            "y": y,
            "width": width,
            "height": height,
            "sliceStart": slice_start,
            "sliceEnd": slice_start if slice_end is None else slice_end,
            "sliceCount": slice_count,
            "sliceAxis": "Z",
            "channel": 2,
            "frame": 3,
            "channels": 4,
            "slices": 5,
            "frames": 6,
            "nPixels": len(values),
            "type": "32-bit",
            "encoding": "base64_float32_le",
            "data": base64.b64encode(raw).decode("ascii"),
        },
    }


def _info(width: int, height: int, slices: int = 5) -> dict:
    return {
        "width": width,
        "height": height,
        "channels": 4,
        "slices": slices,
        "frames": 6,
    }


def test_explicit_slice_is_preflighted_before_pixel_fetch(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: _info(1, 1, 3))
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("out-of-range slices must not reach get_pixels")
        ),
    )

    result = tools_python.get_pixels_array(4, [])

    assert "outside active image range 1..3" in result["error"]


def test_explicit_slice_rejects_server_clamping_after_image_race(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: _info(1, 1, 5))
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: _pixel_response(
            x=0,
            y=0,
            width=1,
            height=1,
            slice_start=4,
        ),
    )

    result = tools_python.get_pixels_array(5, [])

    assert "different pixel slice" in result["error"]


def test_explicit_slice_requires_exact_start_end_and_count(monkeypatch):
    replies = iter(
        [
            _pixel_response(
                x=0,
                y=0,
                width=1,
                height=1,
                slice_start=3,
                slice_end=4,
                slice_count=2,
            ),
            _pixel_response(
                x=0,
                y=0,
                width=1,
                height=1,
                slice_start=3,
                slice_end=3,
                slice_count=1,
                values=[7.0],
            ),
        ]
    )
    calls = []
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: _info(1, 1, 5))
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: calls.append((command, kwargs)) or next(replies),
    )

    rejected = tools_python.get_pixels_array(3, [])
    accepted = tools_python.get_pixels_array(3, [])

    assert "exactly one Z plane" in rejected["error"]
    assert accepted["pixels"] == [[7.0]]
    assert accepted["channel"] == 2
    assert accepted["frame"] == 3
    assert accepted["sliceAxis"] == "Z"
    assert calls == [("get_pixels", {"slice": 3}), ("get_pixels", {"slice": 3})]


def test_current_slice_still_requires_one_self_consistent_plane(monkeypatch):
    replies = iter(
        [
            _pixel_response(
                x=0,
                y=0,
                width=1,
                height=1,
                slice_start=2,
                slice_end=3,
                slice_count=2,
            ),
            _pixel_response(
                x=0,
                y=0,
                width=1,
                height=1,
                slice_start=4,
                values=[9.0],
            ),
        ]
    )
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: _info(1, 1, 5))
    monkeypatch.setattr(
        tools_python, "_safe_send", lambda command, **kwargs: next(replies)
    )

    assert "exactly one Z plane" in tools_python.get_pixels_array(0, [])["error"]
    accepted = tools_python.get_pixels_array(0, [])
    assert accepted["pixels"] == [[9.0]]
    assert accepted["sliceStart"] == 4


def test_oversized_raw_request_is_rejected_without_fetch_or_large_objects(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: _info(2_000, 2_000))
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("oversized raw requests must not fetch or decode pixels")
        ),
    )

    result = tools_python.get_pixels_array(1, [])

    assert result["requested_values"] == 4_000_000
    assert result["max_values"] == tools_python.MAX_RAW_PIXEL_VALUES
    assert len(json.dumps(result)) < 1_000


def test_largest_allowed_raw_result_fits_pixel_history_budget(monkeypatch):
    side = 32
    longest_float32 = 3.4028234663852886e38
    monkeypatch.setattr(tools_python, "_get_image_info", lambda: _info(side, side))
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: _pixel_response(
            x=0,
            y=0,
            width=side,
            height=side,
            slice_start=1,
            values=[longest_float32] * tools_python.MAX_RAW_PIXEL_VALUES,
        ),
    )

    result = tools_python.get_pixels_array(1, [])

    assert isinstance(result, dict)
    assert sum(len(row) for row in result["pixels"]) == tools_python.MAX_RAW_PIXEL_VALUES
    assert result["channels"] == 4
    assert result["slices"] == 5
    assert result["frames"] == 6
    assert len(json.dumps(result)) <= 32_000
