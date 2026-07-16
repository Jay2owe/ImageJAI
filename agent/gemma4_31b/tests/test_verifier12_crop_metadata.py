from __future__ import annotations

import numpy as np

from agent.gemma4_31b import tools_python, visual_diff


UINT16_DOMAIN = {
    "representation": "raw",
    "pixel_type": "uint16",
    "signed": False,
    "density_calibrated": False,
    "acquisition_min_raw": 0.0,
    "acquisition_max_raw": 65535.0,
    "acquisition_min_calibrated": None,
    "acquisition_max_calibrated": None,
}


def _info():
    return {
        "image_id": "large-image",
        "image_revision": 7,
        "display_revision": 11,
        "width": 3000,
        "height": 3000,
        "type": "16-bit",
        "sliceStart": 2,
        "sliceEnd": 2,
        "sliceAxis": "Z",
        "channel": 1,
        "frame": 1,
        "channels": 1,
        "slices": 3,
        "frames": 1,
    }


def _crop_meta(payload):
    width = payload["width"]
    height = payload["height"]
    return {
        "image_id": "large-image",
        "image_revision": 7,
        "display_revision": 11,
        "x": payload["x"],
        "y": payload["y"],
        "width": width,
        "height": height,
        "sliceStart": 2,
        "sliceEnd": 2,
        "sliceCount": 1,
        "sliceAxis": "Z",
        "channel": 1,
        "frame": 1,
        "channels": 1,
        "slices": 3,
        "frames": 1,
        "nPixels": width * height,
        "type": "16-bit",
        "encoding": "base64_float32_le",
        "value_domain": dict(UINT16_DOMAIN),
        "acquisition_min_count": 0,
        "acquisition_max_count": 0,
        "acquisition_limit_counts_exact": False,
    }


def test_tools_large_image_direct_crop_has_native_sampling_metadata(monkeypatch):
    calls = []
    monkeypatch.setattr(tools_python, "_get_image_info", _info)

    def fake_send(command, **payload):
        assert command == "get_pixels"
        calls.append(payload)
        return payload

    def fake_decode(payload):
        meta = _crop_meta(payload)
        return np.zeros((meta["height"], meta["width"]), dtype=np.float32), meta

    monkeypatch.setattr(tools_python, "_safe_send", fake_send)
    monkeypatch.setattr(tools_python, "_decode_pixels", fake_decode)

    array, meta = tools_python._fetch_full_downsampled()

    assert calls == [{
        "image_id": "large-image",
        "image_revision": 7,
        "display_revision": 11,
        "channel": 1,
        "slice": 2,
        "frame": 1,
        "force": True,
        "x": 500,
        "y": 500,
        "width": 2000,
        "height": 2000,
    }]
    assert array.shape == (2000, 2000)
    assert meta["source"] == "center_crop"
    assert meta["downsample_factor"] == 1
    assert (meta["x"], meta["y"], meta["width"], meta["height"]) == (
        500, 500, 2000, 2000
    )
    assert "direct centred 2000x2000 crop at native sampling" in meta["note"]

    # Every public derived tool must retain the direct crop's geometry and
    # factor.  Use a tiny representative array here; the fetch path above
    # already established the real 3000x3000 -> 2000x2000 crop contract.
    small_array = array[:2, :2]
    monkeypatch.setattr(
        tools_python,
        "_fetch_full_downsampled",
        lambda: (small_array, dict(meta)),
    )
    results = (
        tools_python.quick_object_count("otsu"),
        tools_python.histogram_summary(),
        tools_python.count_bright_regions(1, 1),
    )
    for result in results:
        assert result["downsample_factor"] == 1
        assert (result["x"], result["y"], result["width"], result["height"]) == (
            500, 500, 2000, 2000
        )
        assert "native sampling" in result["note"]


def test_visual_diff_large_image_direct_crop_identity_uses_factor_one(monkeypatch):
    calls = []

    def fake_send(command, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": _info()}
        if command == "get_pixels":
            calls.append(payload)
            return payload
        raise AssertionError(command)

    def fake_decode(payload):
        meta = _crop_meta(payload)
        return np.zeros((meta["height"], meta["width"]), dtype=np.float32), meta

    monkeypatch.setattr(visual_diff, "_safe_send", fake_send)
    monkeypatch.setattr(visual_diff, "_decode_pixels", fake_decode)

    summary = visual_diff.capture_thumbnail()

    assert calls == [{
        "image_id": "large-image",
        "image_revision": 7,
        "display_revision": 11,
        "channel": 1,
        "slice": 2,
        "frame": 1,
        "force": True,
        "x": 1244,
        "y": 1244,
        "width": 512,
        "height": 512,
    }]
    assert summary["shape"] == [512, 512]
    assert summary["_plane_identity"] == {
        "image_id": "large-image",
        "channel": 1,
        "slice": 2,
        "frame": 1,
        "image_width": 3000,
        "image_height": 3000,
        "sample_x": 1244,
        "sample_y": 1244,
        "sample_width": 512,
        "sample_height": 512,
        "downsample_factor": 1,
    }
    assert "direct centred 512x512 crop at native sampling" in summary["note"]

    # The correction changes provenance only; intensity comparison still uses
    # one percent of the native bit range, independent of crop metadata.
    report = visual_diff.diff_report(summary, summary, 'run("Median...", "radius=2");')
    assert report["numbers"]["change_tolerance"] == 655.35
    assert report["numbers"]["pixel_change_fraction"] == 0.0
