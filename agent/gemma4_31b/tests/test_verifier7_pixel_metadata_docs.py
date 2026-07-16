from __future__ import annotations

import base64
import inspect
import json
import re
import struct
from pathlib import Path

import numpy as np
import yaml

from agent.contexts import loader
from agent.gemma4_31b import describe_image, tools_python
from agent.providers.base import fn_to_json_schema


PROJECT_ROOT = Path(__file__).resolve().parents[3]
AXIS_FIELDS = ("channel", "frame", "sliceAxis", "channels", "slices", "frames")


def _info(width=2, height=2):
    return {
        "title": "hyper.tif",
        "width": width,
        "height": height,
        "type": "16-bit",
        "calibration": "0.5 um/px",
        "channels": 3,
        "slices": 5,
        "frames": 7,
    }


def _pixel_response(values=None, *, width=2, height=2, channel=2, z=4, frame=6):
    values = values or [1.0] * (width * height)
    raw = struct.pack("<{}f".format(len(values)), *values)
    return {
        "ok": True,
        "result": {
            "x": 0,
            "y": 0,
            "width": width,
            "height": height,
            "sliceStart": z,
            "sliceEnd": z,
            "sliceCount": 1,
            "sliceAxis": "Z",
            "channel": channel,
            "frame": frame,
            "channels": 3,
            "slices": 5,
            "frames": 7,
            "nPixels": len(values),
            "type": "16-bit",
            "encoding": "base64_float32_le",
            "data": base64.b64encode(raw).decode("ascii"),
        },
    }


def _assert_axis_attribution(result):
    assert {key: result[key] for key in AXIS_FIELDS} == {
        "channel": 2,
        "frame": 6,
        "sliceAxis": "Z",
        "channels": 3,
        "slices": 5,
        "frames": 7,
    }


def test_decode_rejects_missing_or_inconsistent_axis_metadata():
    missing = _pixel_response()
    del missing["result"]["channel"]
    arr, error = tools_python._decode_pixels(missing)
    assert arr is None
    assert "missing or malformed C/Z/T metadata" in error["error"]

    inconsistent = _pixel_response(channel=4)
    arr, error = tools_python._decode_pixels(inconsistent)
    assert arr is None
    assert "inconsistent C/Z/T metadata" in error["error"]


def test_raw_pixels_and_region_stats_expose_exact_source_plane(monkeypatch):
    monkeypatch.setattr(tools_python, "_get_image_info", _info)
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: _pixel_response([1.0, 2.0, 3.0, 4.0]),
    )

    pixels = tools_python.get_pixels_array(4, [])
    stats = tools_python.region_stats(0, 0, 2, 2)

    assert pixels["pixels"] == [[1.0, 2.0], [3.0, 4.0]]
    assert stats["mean"] == 2.5
    assert pixels["sliceStart"] == stats["sliceStart"] == 4
    _assert_axis_attribution(pixels)
    _assert_axis_attribution(stats)


def test_all_derived_pixel_tools_return_axis_attribution(monkeypatch):
    meta = dict(_pixel_response()["result"])
    meta.pop("data")
    meta.update({"downsample_factor": 1, "bit_depth": 16, "source": "full"})
    arr = np.asarray([[0.0, 10.0], [10.0, 0.0]], dtype=np.float32)
    monkeypatch.setattr(tools_python, "_fetch_full_downsampled", lambda: (arr, dict(meta)))
    monkeypatch.setattr(tools_python, "_get_image_info", _info)
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **kwargs: _pixel_response([0.0, 10.0], width=2, height=1),
    )

    results = [
        tools_python.line_profile(0, 0, 1, 0),
        tools_python.quick_object_count("otsu"),
        tools_python.histogram_summary(),
        tools_python.count_bright_regions(5, 1),
    ]

    assert results[0]["profile"] == [0.0, 10.0]
    for result in results:
        _assert_axis_attribution(result)


def test_describe_image_names_the_measured_hyperstack_plane(monkeypatch):
    def fake_send(command, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": _info()}
        if command == "get_pixels":
            return _pixel_response([0.0, 1.0, 2.0, 3.0])
        if command == "get_histogram":
            return {
                "ok": True,
                "result": {
                    "min": 0.0,
                    "max": 3.0,
                    "mean": 1.5,
                    "stdDev": 1.1,
                    "nPixels": 4,
                    "bins": [1, 1, 1, 1],
                },
            }
        if command == "run_script":
            return {"ok": True, "result": {"success": True, "output": "{}"}}
        raise AssertionError(command)

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)

    text = describe_image.describe_image()

    assert "channel 2 of 3, Z slice 4 of 5, frame 6 of 7" in text
    assert "slice axis Z" in text


def test_describe_image_drops_unattributed_hyperstack_measurements(monkeypatch):
    calls = []
    malformed_pixels = _pixel_response()
    del malformed_pixels["result"]["frame"]

    def fake_send(command, **payload):
        calls.append(command)
        if command == "get_image_info":
            return {"ok": True, "result": _info()}
        if command == "get_pixels":
            return malformed_pixels
        if command == "run_script":
            return {"ok": True, "result": {"success": True, "output": "{}"}}
        if command == "get_histogram":
            raise AssertionError("unattributed hyperstack histogram must not be measured")
        raise AssertionError(command)

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)

    text = describe_image.describe_image()

    assert "hyperstack measurements were not attributed" in text
    assert "get_histogram" not in calls


def test_shipped_pixel_contract_matches_schema_and_runtime_constant():
    signature = inspect.signature(tools_python.get_pixels_array)
    assert list(signature.parameters) == ["slice", "region"]
    schema = fn_to_json_schema(tools_python.get_pixels_array)["schema"]
    assert list(schema["properties"]) == ["slice", "region"]
    assert schema["required"] == ["slice", "region"]

    contract_paths = [
        "agent/contexts/harness/tool_loop.md",
        "agent/gemma4_31b/GEMMA.md",
        "agent/gemma4_31b/GEMMA_CLAUDE.md",
    ]
    for relative in contract_paths:
        text = (PROJECT_ROOT / relative).read_text(encoding="utf-8")
        assert "`get_pixels_array(slice, region)`" in text, relative
        assert "{:,.0f}".format(tools_python.MAX_RAW_PIXEL_VALUES) in text, relative
        assert "get_pixels_array(x, y, w, h)" not in text, relative

    generated_paths = [
        "agent/CLAUDE.md",
        "agent/AGENTS.md",
        "agent/GEMINI.md",
        "agent/.aider.conventions.md",
        "agent/.clinerules",
        "agent/.cursorrules",
    ]
    generated = [PROJECT_ROOT / relative for relative in generated_paths]
    generated.extend(sorted((PROJECT_ROOT / "agent/contexts/_snapshots").glob("*.md")))
    for path in generated:
        text = path.read_text(encoding="utf-8")
        assert "get_pixels_array(x, y, w, h)" not in text, str(path)


def test_every_tracked_context_snapshot_is_byte_current():
    models = yaml.safe_load(
        (PROJECT_ROOT / "agent/providers/models.yaml").read_text(encoding="utf-8")
    )["models"]
    model_by_slug = {
        re.sub(
            r"[^A-Za-z0-9_.-]+",
            "_",
            "{}/{}".format(model["provider"], model["model_id"]),
        ): "{}/{}".format(model["provider"], model["model_id"])
        for model in models
    }
    snapshots = sorted((PROJECT_ROOT / "agent/contexts/_snapshots").glob("*.md"))
    assert snapshots
    for path in snapshots:
        model_id = model_by_slug.get(path.stem)
        assert model_id is not None, "unmapped context snapshot: {}".format(path.name)
        assert path.read_text(encoding="utf-8") == loader.load_context(model_id), path.name
