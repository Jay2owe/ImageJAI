from __future__ import annotations

import base64
import importlib.util
import struct
from pathlib import Path

import pytest


PIXELS_PATH = Path(__file__).with_name("pixels.py")
SPEC = importlib.util.spec_from_file_location("pixels_under_test", PIXELS_PATH)
pixels = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(pixels)


def pixel_response(values, width, height, slice_count=1, **overrides):
    raw = struct.pack("<" + str(len(values)) + "f", *values)
    result = {
        "data": base64.b64encode(raw).decode("ascii"),
        "x": 0,
        "y": 0,
        "width": width,
        "height": height,
        "sliceStart": 1,
        "sliceEnd": slice_count,
        "sliceCount": slice_count,
        "nPixels": len(values),
        "type": "float32",
    }
    result.update(overrides)
    return {"ok": True, "result": result}


def test_public_api_is_explicit():
    for name in [
        "imagej_command",
        "send",
        "get_pixels",
        "compute_stats",
        "line_profile",
        "find_bright_objects",
        "get_current_stats",
        "get_slice_stats",
        "get_region_stats",
        "get_line_profile",
        "find_cells",
        "get_stack_stats",
    ]:
        assert name in pixels.__all__
        assert hasattr(pixels, name)


def test_imagej_command_prefers_ij_client_when_available(monkeypatch):
    calls = []

    def fake_ij_command(cmd, host, port, timeout):
        calls.append((cmd, host, port, timeout))
        return {"ok": True, "result": "pong"}

    monkeypatch.setattr(pixels, "_ij_imagej_command", fake_ij_command)

    resp = pixels.imagej_command(
        {"command": "ping"},
        host="127.0.0.1",
        port=1234,
        timeout=5,
    )

    assert resp == {"ok": True, "result": "pong"}
    assert calls == [({"command": "ping"}, "127.0.0.1", 1234, 5)]


def test_send_is_compatibility_alias(monkeypatch):
    calls = []

    def fake_imagej_command(cmd):
        calls.append(cmd)
        return {"ok": True, "result": cmd}

    monkeypatch.setattr(pixels, "imagej_command", fake_imagej_command)

    assert pixels.send({"command": "get_image_info"}) == {
        "ok": True,
        "result": {"command": "get_image_info"},
    }
    assert calls == [{"command": "get_image_info"}]


def test_get_pixels_builds_payload_and_decodes_2d(monkeypatch):
    calls = []

    def fake_send(cmd):
        calls.append(cmd)
        return pixel_response(
            [1.0, 2.0, 3.0, 4.0],
            width=2,
            height=2,
            x=5,
            y=6,
            sliceStart=7,
            sliceEnd=7,
        )

    monkeypatch.setattr(pixels, "send", fake_send)

    data, meta = pixels.get_pixels(
        x=5,
        y=6,
        width=2,
        height=2,
        slice_num=7,
        all_slices=True,
    )

    assert calls == [{
        "command": "get_pixels",
        "x": 5,
        "y": 6,
        "width": 2,
        "height": 2,
        "slice": 7,
        "allSlices": True,
    }]
    assert data == [[1.0, 2.0], [3.0, 4.0]]
    assert meta == {
        "x": 5,
        "y": 6,
        "width": 2,
        "height": 2,
        "sliceStart": 7,
        "sliceEnd": 7,
        "sliceCount": 1,
        "type": "float32",
    }


def test_get_pixels_decodes_3d_stack(monkeypatch):
    def fake_send(cmd):
        assert cmd == {"command": "get_pixels", "allSlices": True}
        return pixel_response(
            [1.0, 2.0, 3.0, 4.0],
            width=1,
            height=2,
            slice_count=2,
        )

    monkeypatch.setattr(pixels, "send", fake_send)

    data, meta = pixels.get_pixels(all_slices=True)

    assert data == [[[1.0], [2.0]], [[3.0], [4.0]]]
    assert meta["sliceCount"] == 2


def test_stats_helpers_call_get_pixels_with_expected_args(monkeypatch):
    calls = []
    sample = [[1.0, 2.0], [3.0, 4.0]]
    meta = {"width": 2, "height": 2}

    def fake_get_pixels(*args, **kwargs):
        assert args == ()
        calls.append(kwargs)
        return sample, meta

    monkeypatch.setattr(pixels, "get_pixels", fake_get_pixels)

    current = pixels.get_current_stats()
    slice_result = pixels.get_slice_stats(3)
    region_result = pixels.get_region_stats(10, 20, 30, 40)

    assert calls == [
        {},
        {"slice_num": 3},
        {"x": 10, "y": 20, "width": 30, "height": 40},
    ]
    assert current == {"stats": pixels.compute_stats(sample), "meta": meta}
    assert slice_result["slice"] == 3
    assert slice_result["stats"] == pixels.compute_stats(sample)
    assert region_result["x"] == 10
    assert region_result["stats"] == pixels.compute_stats(sample)


def test_line_profile_and_find_cells_helpers_use_current_pixels(monkeypatch):
    sample = [
        [0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 10.0, 10.0, 0.0, 0.0],
        [0.0, 10.0, 10.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0],
    ]
    meta = {"x": 10, "y": 20}

    def fake_get_pixels():
        return sample, meta

    monkeypatch.setattr(pixels, "get_pixels", fake_get_pixels)

    profile = pixels.get_line_profile(0, 0, 2, 2)
    objects = pixels.find_cells(threshold_factor=1.0, min_size=3)

    assert [p["value"] for p in profile] == [0.0, 10.0, 10.0]
    assert objects == [{
        "label": 1,
        "x": 1.5,
        "y": 1.5,
        "area": 4,
        "mean_intensity": 10.0,
        "abs_x": 11.5,
        "abs_y": 21.5,
    }]


def test_get_stack_stats_uses_image_info_then_fetches_each_slice(monkeypatch):
    send_calls = []
    slice_calls = []

    def fake_send(cmd):
        send_calls.append(cmd)
        return {"ok": True, "result": {"slices": 3}}

    def fake_get_pixels(slice_num):
        slice_calls.append(slice_num)
        return [[float(slice_num), float(slice_num + 1)]], {"slice": slice_num}

    monkeypatch.setattr(pixels, "send", fake_send)
    monkeypatch.setattr(pixels, "get_pixels", fake_get_pixels)

    rows = pixels.get_stack_stats()

    assert send_calls == [{"command": "get_image_info"}]
    assert slice_calls == [1, 2, 3]
    assert [row["slice"] for row in rows] == [1, 2, 3]
    assert [row["stats"]["mean"] for row in rows] == [1.5, 2.5, 3.5]


def test_get_stack_stats_reports_image_info_errors(monkeypatch):
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: {"ok": False, "error": "No image open"},
    )

    with pytest.raises(RuntimeError, match="No image open"):
        pixels.get_stack_stats()


def test_main_profile_preserves_tabular_output(monkeypatch, capsys):
    monkeypatch.setattr(
        pixels,
        "get_line_profile",
        lambda x1, y1, x2, y2: [
            {"pos": 0, "value": 1.234},
            {"pos": 1, "value": 9.876},
        ],
    )
    monkeypatch.setattr(pixels.sys, "argv", ["pixels.py", "profile", "0", "0", "1", "1"])

    pixels.main()

    assert capsys.readouterr().out == "0\t1.2\n1\t9.9\n"


def test_main_stack_stats_preserves_table_output(monkeypatch, capsys):
    monkeypatch.setattr(
        pixels,
        "get_stack_stats",
        lambda: [
            {"slice": 1, "stats": {"mean": 1.0, "std": 0.5, "min": 0.0, "max": 2.0}},
            {"slice": 2, "stats": {"mean": 3.0, "std": 1.5, "min": 1.0, "max": 5.0}},
        ],
    )
    monkeypatch.setattr(pixels.sys, "argv", ["pixels.py", "stack_stats"])

    pixels.main()

    assert capsys.readouterr().out == (
        "Slice  Mean      Std       Min    Max\n"
        "    1       1.0       0.5      0      2\n"
        "    2       3.0       1.5      1      5\n"
    )
