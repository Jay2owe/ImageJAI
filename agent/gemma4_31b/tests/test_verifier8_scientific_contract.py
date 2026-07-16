from __future__ import annotations

import base64
import struct

import numpy as np

from agent.gemma4_31b import describe_image, tools_python


def _plane_meta(bit_depth: int) -> dict:
    integer = bit_depth == 8
    domain = {
        "representation": "raw",
        "pixel_type": "uint8" if integer else "float32",
        "signed": False if integer else True,
        "density_calibrated": False,
        "acquisition_min_raw": 0.0 if integer else None,
        "acquisition_max_raw": 255.0 if integer else None,
        "acquisition_min_calibrated": 0.0 if integer else None,
        "acquisition_max_calibrated": 255.0 if integer else None,
    }
    return {
        "image_id": "image-123",
        "image_revision": 7,
        "display_revision": 11,
        "x": 0,
        "y": 0,
        "width": 2,
        "height": 2,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceCount": 1,
        "sliceAxis": "Z",
        "channel": 1,
        "frame": 1,
        "channels": 1,
        "slices": 1,
        "frames": 1,
        "nPixels": 4,
        "type": "{}-bit".format(bit_depth),
        "encoding": "base64_float32_le",
        "downsample_factor": 1,
        "bit_depth": bit_depth,
        "source": "full",
        "value_domain": domain,
        "acquisition_min_count": 0 if integer else None,
        "acquisition_max_count": 2 if integer else None,
        "acquisition_limit_counts_exact": integer,
    }


def test_float_histogram_reports_observed_extrema_without_false_saturation(monkeypatch):
    arr = np.asarray([[1.5, 1.5], [8.0, 8.0]], dtype=np.float32)
    monkeypatch.setattr(
        tools_python,
        "_fetch_full_downsampled",
        lambda: (arr, _plane_meta(32)),
    )

    result = tools_python.histogram_summary()

    assert result["p01"] >= 1.5
    assert result["p99"] <= 8.0
    assert result["saturation_available"] is False
    assert result["saturation_ceiling"] is None
    assert result["saturated_fraction"] is None


def test_unsigned_integer_histogram_uses_representable_ceiling(monkeypatch):
    arr = np.asarray([[1.0, 1.0], [255.0, 255.0]], dtype=np.float32)
    monkeypatch.setattr(
        tools_python,
        "_fetch_full_downsampled",
        lambda: (arr, _plane_meta(8)),
    )

    result = tools_python.histogram_summary()

    assert result["saturation_available"] is True
    assert result["saturation_ceiling"] == 255.0
    assert result["saturated_fraction"] == 0.5


def test_artifact_text_never_treats_observed_float_extrema_as_sensor_limits():
    thumb = np.asarray([[1.5, 1.5], [8.0, 8.0]], dtype=np.float32)
    text = describe_image._fragment_artifacts(
        thumb, bit_depth=32, meta=_plane_meta(32)
    )
    histogram = {
        "bins": np.asarray([2.0, 2.0]),
        "n_pixels": 4,
        "max": 8.0,
    }

    assert "clipped blacks" not in text.lower()
    assert "saturated patch" not in text.lower()
    assert "unavailable" in text.lower()
    assert "unavailable" in describe_image._fragment_saturation(
        histogram, bit_depth=32
    ).lower()


def test_integer_black_clipping_compares_to_zero_not_observed_minimum():
    thumb = np.asarray([[1.0, 1.0], [2.0, 2.0]], dtype=np.float32)

    meta = _plane_meta(8)
    meta["acquisition_max_count"] = 0
    text = describe_image._fragment_artifacts(thumb, bit_depth=8, meta=meta)

    assert "no substantial acquisition-minimum clipping" in text.lower()
    assert "pixels at the raw acquisition minimum" not in text.lower()


def test_snapshot_match_rejects_same_geometry_with_different_identity():
    info = {
        "image_id": "image-123",
        "image_revision": 7,
        "display_revision": 11,
        "channel": 1,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceAxis": "Z",
        "frame": 1,
        "channels": 1,
        "slices": 1,
        "frames": 1,
        "value_domain": _plane_meta(8)["value_domain"],
    }
    meta = dict(info)
    meta["image_id"] = "same-shaped-replacement"

    assert tools_python._metadata_matches_info(meta, info) is False
    assert describe_image._metadata_matches_info(meta, info) is False


def _pixel_response(value: float) -> dict:
    raw = struct.pack("<f", value)
    meta = _plane_meta(32)
    meta.update({
        "data": base64.b64encode(raw).decode("ascii"),
        "width": 1,
        "height": 1,
        "nPixels": 1,
        "encoding": "base64_float32_le",
    })
    return {"ok": True, "result": meta}


def test_gemma_decoders_reject_non_finite_pixels_centrally():
    for value in (float("nan"), float("inf"), float("-inf")):
        array, error = tools_python._decode_pixels(_pixel_response(value))
        assert array is None
        assert "non-finite" in error["error"]
        array, error = describe_image._decode_pixels(_pixel_response(value))
        assert array is None
        assert "non-finite" in error["error"]


def test_gemma_region_and_plane_arguments_require_exact_integers(monkeypatch):
    monkeypatch.setattr(
        tools_python,
        "_get_image_info",
        lambda: (_ for _ in ()).throw(
            AssertionError("invalid arguments must fail before reading image info")
        ),
    )
    for bad in (True, 1.0, "1"):
        assert "exact integer" in tools_python.get_pixels_array(bad, [])["error"]
        assert "exact integer" in tools_python.region_stats(bad, 0, 1, 1)["error"]
        assert "exact integer" in tools_python.line_profile(0, 0, bad, 1)["error"]


def test_saturation_uses_exact_limit_count_not_adaptive_last_bin():
    stats = {
        "bins": np.asarray([1.0, 99.0]),
        "n_pixels": 100,
        "value_domain": _plane_meta(8)["value_domain"],
        "acquisition_max_count": 1,
        "acquisition_limit_counts_exact": True,
    }

    text = describe_image._fragment_saturation(stats, bit_depth=8)

    assert "1.0%" in text
    assert "99.0%" not in text


def test_quadrant_localization_skips_geometrically_degenerate_samples():
    thumb = np.zeros((2, 100), dtype=np.float32)
    thumb[:, :50] = 255.0
    meta = _plane_meta(8)
    meta["nPixels"] = int(thumb.size)
    meta["acquisition_max_count"] = 100

    text = describe_image._fragment_artifacts(thumb, 8, meta)

    assert "quadrant saturation localization unavailable" in text.lower()
    assert "saturated patch" not in text.lower()
