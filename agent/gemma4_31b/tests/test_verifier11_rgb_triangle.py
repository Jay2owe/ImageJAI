from __future__ import annotations

import base64
import copy

import numpy as np
import pytest

from agent.gemma4_31b import describe_image, tools_python


RGB_PIXEL_DOMAIN = {
    "representation": "raw",
    "pixel_type": "rgb24",
    "signed": None,
    "density_calibrated": False,
    "acquisition_min_raw": None,
    "acquisition_max_raw": None,
    "acquisition_min_calibrated": None,
    "acquisition_max_calibrated": None,
}

RGB_SCALAR_DOMAIN = {
    "representation": "raw",
    "pixel_type": "uint8",
    "signed": False,
    "density_calibrated": False,
    "acquisition_min_raw": 0.0,
    "acquisition_max_raw": 255.0,
    "acquisition_min_calibrated": None,
    "acquisition_max_calibrated": None,
    "scalarization": {
        "method": "imagej_weighted_rgb_intensity",
        "source_pixel_type": "rgb24",
        "weights": {"red": 0.2, "green": 0.3, "blue": 0.5},
        "rounding": "nearest_integer_half_up",
    },
}


def _info() -> dict:
    return {
        "image_id": "rgb-image",
        "image_revision": 7,
        "display_revision": 11,
        "title": "rgb.tif",
        "width": 2,
        "height": 1,
        "type": "RGB",
        "channel": 1,
        "sliceStart": 2,
        "sliceEnd": 2,
        "sliceAxis": "Z",
        "frame": 3,
        "channels": 1,
        "slices": 4,
        "frames": 5,
        "value_domain": copy.deepcopy(RGB_PIXEL_DOMAIN),
    }


def _binding() -> dict:
    return {
        key: _info()[key]
        for key in (
            "image_id", "image_revision", "display_revision", "channel",
            "sliceStart", "sliceEnd", "sliceAxis", "frame", "channels",
            "slices", "frames",
        )
    }


def _pixel_response(values=(0x00FF0000, 0x0000FF00)) -> dict:
    pixels = np.asarray(values, dtype="<f4")
    return {
        "ok": True,
        "result": {
            **_binding(),
            "x": 0,
            "y": 0,
            "width": 2,
            "height": 1,
            "sliceCount": 1,
            "nPixels": 2,
            "type": "RGB",
            "encoding": "base64_float32_le",
            "value_domain": copy.deepcopy(RGB_PIXEL_DOMAIN),
            "acquisition_min_count": None,
            "acquisition_max_count": None,
            "acquisition_limit_counts_exact": False,
            "data": base64.b64encode(pixels.tobytes()).decode("ascii"),
        },
    }


def _histogram_response(domain=None) -> dict:
    return {
        "ok": True,
        "result": {
            **_binding(),
            "scope": "full_plane",
            "nPixels": 2,
            "value_domain": copy.deepcopy(
                RGB_SCALAR_DOMAIN if domain is None else domain
            ),
        },
    }


def test_all_rgb_intensity_consumers_use_exact_full_plane_scalarization(monkeypatch):
    calls = []

    def fake_send(command, **payload):
        calls.append((command, payload))
        if command == "get_pixels":
            return _pixel_response()
        if command == "get_histogram":
            assert payload == {
                "image_id": "rgb-image",
                "image_revision": 7,
                "display_revision": 11,
                "channel": 1,
                "slice": 2,
                "frame": 3,
                "force": True,
                "scope": "full_plane",
            }
            return _histogram_response()
        raise AssertionError(command)

    monkeypatch.setattr(tools_python, "_get_image_info", _info)
    monkeypatch.setattr(tools_python, "_safe_send", fake_send)

    raw = tools_python.get_pixels_array(0, [])
    assert raw["pixels"] == [[float(0x00FF0000), float(0x0000FF00)]]
    assert not any(command == "get_histogram" for command, _ in calls)

    stats = tools_python.region_stats(0, 0, 2, 1)
    profile = tools_python.line_profile(0, 0, 1, 0)
    objects = tools_python.quick_object_count("triangle")
    summary = tools_python.histogram_summary()
    bright = tools_python.count_bright_regions(60, 1)

    assert stats["mean"] == 64.0
    assert stats["median"] == 64.0
    assert profile["profile"] == [51.0, 77.0]
    assert objects["value_domain"]["scalarization"]["rounding"] == "nearest_integer_half_up"
    assert summary["p50"] == 64.0
    assert summary["bit_depth"] == 8
    assert bright["count"] == 1
    derived = (stats, profile, objects, summary, bright)
    assert all(item["value_domain"]["pixel_type"] == "uint8" for item in derived)
    assert sum(command == "get_histogram" for command, _ in calls) == len(derived)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("method", "unweighted_rgb"),
        ("source_pixel_type", "uint8"),
        ("rounding", "nearest_even"),
    ],
)
def test_rgb_scalarization_rejects_wrong_method_source_or_rounding(
    monkeypatch, field, value
):
    domain = copy.deepcopy(RGB_SCALAR_DOMAIN)
    domain["scalarization"][field] = value
    monkeypatch.setattr(
        tools_python, "_safe_send", lambda command, **payload: _histogram_response(domain)
    )
    arr, error = tools_python._scalarize_rgb24_measurement(
        np.asarray([[0x00FF0000]], dtype=np.float64),
        {"value_domain": RGB_PIXEL_DOMAIN},
        _info(),
    )
    assert arr is None
    assert "invalid RGB histogram scalarization metadata" in error["error"]


@pytest.mark.parametrize(
    "weights",
    [
        {"red": float("inf"), "green": 0.0, "blue": 0.0},
        {"red": -0.1, "green": 0.6, "blue": 0.5},
        {"red": 0.2, "green": 0.3, "blue": 0.4},
    ],
)
def test_rgb_scalarization_rejects_nonfinite_negative_or_unnormalized_weights(
    monkeypatch, weights
):
    domain = copy.deepcopy(RGB_SCALAR_DOMAIN)
    domain["scalarization"]["weights"] = weights
    monkeypatch.setattr(
        tools_python, "_safe_send", lambda command, **payload: _histogram_response(domain)
    )
    arr, error = tools_python._scalarize_rgb24_measurement(
        np.asarray([[0x00FF0000]], dtype=np.float64),
        {"value_domain": RGB_PIXEL_DOMAIN},
        _info(),
    )
    assert arr is None
    assert "invalid RGB histogram scalarization metadata" in error["error"]


def test_rgb_scalarization_fails_closed_on_snapshot_mismatch(monkeypatch):
    response = _histogram_response()
    response["result"]["image_revision"] += 1
    monkeypatch.setattr(tools_python, "_safe_send", lambda command, **payload: response)
    arr, error = tools_python._scalarize_rgb24_measurement(
        np.asarray([[0x00FF0000]], dtype=np.float64),
        {"value_domain": RGB_PIXEL_DOMAIN},
        _info(),
    )
    assert arr is None
    assert "did not match the image snapshot and C/Z/T plane" in error["error"]


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("representation", "calibrated"),
        ("pixel_type", "uint8"),
        ("signed", False),
        ("density_calibrated", True),
        ("acquisition_min_raw", 0.0),
        ("acquisition_max_raw", 255.0),
        ("acquisition_min_calibrated", 0.0),
        ("acquisition_max_calibrated", 255.0),
        ("scalarization", {}),
    ],
)
def test_packed_rgb_source_domain_mutations_fail_closed(monkeypatch, field, value):
    source = copy.deepcopy(RGB_PIXEL_DOMAIN)
    source[field] = value
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda *args, **kwargs: pytest.fail("invalid source must fail before histogram"),
    )
    arr, error = tools_python._scalarize_rgb24_measurement(
        np.asarray([[0x00FF0000]], dtype=np.float64),
        {"type": "RGB", "value_domain": source},
        _info(),
    )
    assert arr is None
    assert "invalid packed RGB24 source" in error["error"]
    stats = {"value_domain": copy.deepcopy(RGB_SCALAR_DOMAIN)}
    assert describe_image._threshold_thumbnail(
        np.asarray([[0x00FF0000]], dtype=np.float64),
        stats,
        {"type": "RGB", "value_domain": source},
    ) is None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("representation", "calibrated"),
        ("pixel_type", "uint16"),
        ("signed", True),
        ("density_calibrated", True),
        ("acquisition_min_raw", 1.0),
        ("acquisition_max_raw", 254.0),
        ("acquisition_min_calibrated", 0.0),
        ("acquisition_max_calibrated", 255.0),
    ],
)
def test_rgb_scalar_histogram_domain_mutations_fail_closed(monkeypatch, field, value):
    scalar_domain = copy.deepcopy(RGB_SCALAR_DOMAIN)
    scalar_domain[field] = value
    monkeypatch.setattr(
        tools_python,
        "_safe_send",
        lambda command, **payload: _histogram_response(scalar_domain),
    )
    arr, error = tools_python._scalarize_rgb24_measurement(
        np.asarray([[0x00FF0000]], dtype=np.float64),
        {"type": "RGB", "value_domain": copy.deepcopy(RGB_PIXEL_DOMAIN)},
        _info(),
    )
    assert arr is None
    assert "invalid RGB histogram scalarization metadata" in error["error"]
    assert describe_image._threshold_thumbnail(
        np.asarray([[0x00FF0000]], dtype=np.float64),
        {"value_domain": scalar_domain},
        {"type": "RGB", "value_domain": copy.deepcopy(RGB_PIXEL_DOMAIN)},
    ) is None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("image_id", ""),
        ("image_id", 7),
        ("image_revision", "7"),
        ("display_revision", 11.0),
        ("sliceStart", True),
        ("sliceEnd", 2.0),
        ("sliceAxis", 7),
        ("channel", "1"),
        ("frame", 3.0),
        ("channels", True),
        ("slices", "4"),
        ("frames", 5.0),
    ],
)
def test_snapshot_matchers_reject_coercible_or_empty_response_fields(field, value):
    info = _info()
    meta = {**_binding(), "image_id": "rgb-image"}
    meta[field] = value
    assert tools_python._metadata_matches_info(meta, info) is False
    assert describe_image._metadata_matches_info(meta, info) is False


def _asymmetric_triangle_histogram() -> np.ndarray:
    """Peak is right of centre, yet its farther occupied endpoint is on the right."""
    hist = np.zeros(256, dtype=np.float64)
    hist[170] = 1
    hist[200] = 100
    for index in range(201, 256):
        hist[index] = round(100 + (1 - 100) * (index - 200) / 55)
    hist[220] = 44  # Largest signed distance below the peak-to-tail chord.
    hist[254] = 90  # Larger absolute distance, but on the wrong side of the chord.
    hist[255] = 1
    return hist


def test_triangle_histogram_uses_farther_endpoint_and_signed_orientation():
    hist = _asymmetric_triangle_histogram()
    values = np.arange(256, dtype=np.float64)

    threshold = describe_image._triangle_threshold_from_hist(
        {"bins": hist, "bin_values": values}
    )
    mirrored = describe_image._triangle_threshold_from_hist(
        {"bins": hist[::-1].copy(), "bin_values": values}
    )

    assert threshold == 221.0
    assert mirrored == 34.0
    assert mirrored == 255.0 - threshold


def test_tools_triangle_matches_asymmetric_and_mirrored_histograms(monkeypatch):
    hist = _asymmetric_triangle_histogram()
    edges = np.arange(257, dtype=np.float64)
    samples = np.asarray([0.0, 255.0], dtype=np.float64)

    monkeypatch.setattr(
        tools_python.np, "histogram", lambda *args, **kwargs: (hist.copy(), edges)
    )
    threshold = tools_python._triangle_threshold(samples)
    monkeypatch.setattr(
        tools_python.np,
        "histogram",
        lambda *args, **kwargs: (hist[::-1].copy(), edges),
    )
    mirrored = tools_python._triangle_threshold(samples)

    assert threshold == 221.0
    assert mirrored == 34.0
    assert mirrored == 255.0 - threshold


def _longer_left_triangle_histogram() -> np.ndarray:
    """Occupied support 0..120 with its unique peak at 100 (longer left tail)."""
    hist = np.zeros(256, dtype=np.float64)
    for index in range(101):
        hist[index] = index + 1
    hist[1] = 90   # Wrong side of the endpoint-to-peak line.
    hist[70] = 40  # Largest signed distance; ImageJ returns the prior split bin.
    for index in range(101, 121):
        hist[index] = max(1, 101 - 5 * (index - 100))
    return hist


def test_triangle_longer_left_0_100_120_case_and_mirror(monkeypatch):
    hist = _longer_left_triangle_histogram()
    values = np.arange(256, dtype=np.float64)
    stats = {"bins": hist, "bin_values": values}
    mirrored_stats = {"bins": hist[::-1].copy(), "bin_values": values}

    assert np.flatnonzero(hist)[[0, -1]].tolist() == [0, 120]
    assert int(np.argmax(hist)) == 100
    assert describe_image._triangle_threshold_from_hist(stats) == 69.0
    assert describe_image._triangle_threshold_from_hist(mirrored_stats) == 186.0

    edges = np.arange(257, dtype=np.float64)
    samples = np.asarray([0.0, 255.0], dtype=np.float64)
    monkeypatch.setattr(
        tools_python.np, "histogram", lambda *args, **kwargs: (hist.copy(), edges)
    )
    assert tools_python._triangle_threshold(samples) == 69.0
    monkeypatch.setattr(
        tools_python.np,
        "histogram",
        lambda *args, **kwargs: (hist[::-1].copy(), edges),
    )
    assert tools_python._triangle_threshold(samples) == 186.0


def test_describe_triangle_sample_fallback_matches_asymmetric_imagej_semantics(
    monkeypatch,
):
    hist = _asymmetric_triangle_histogram()
    edges = np.arange(257, dtype=np.float64)
    samples = np.asarray([0.0, 255.0], dtype=np.float64)

    monkeypatch.setattr(
        describe_image.np,
        "histogram",
        lambda *args, **kwargs: (hist.copy(), edges),
    )
    threshold = describe_image._triangle_threshold(samples)
    monkeypatch.setattr(
        describe_image.np,
        "histogram",
        lambda *args, **kwargs: (hist[::-1].copy(), edges),
    )
    mirrored = describe_image._triangle_threshold(samples)

    assert threshold == 221.0
    assert mirrored == 34.0
    assert mirrored == 255.0 - threshold


def test_threshold_fragment_invokes_sample_triangle_fallback(monkeypatch):
    thumb = np.asarray([[0.0, 1.0], [2.0, 3.0]], dtype=np.float64)
    calls = []

    monkeypatch.setattr(
        describe_image, "_threshold_thumbnail", lambda *args: thumb
    )
    monkeypatch.setattr(
        describe_image, "_otsu_threshold_from_hist", lambda stats: 0.0
    )
    monkeypatch.setattr(
        describe_image, "_li_threshold_from_hist", lambda stats: 0.0
    )
    monkeypatch.setattr(
        describe_image, "_triangle_threshold_from_hist", lambda stats: None
    )

    def sample_triangle(samples):
        calls.append(samples)
        return 0.0

    monkeypatch.setattr(describe_image, "_triangle_threshold", sample_triangle)

    text = describe_image._fragment_thresholds(thumb, {"bins": [4]}, {})

    assert "Auto-thresholds produce" in text
    assert calls == [thumb]


def test_describe_center_crop_reports_direct_sampling_factor(monkeypatch):
    info = _info()
    info.update({"width": 3000, "height": 2000})
    requests = []
    crop_meta = {
        **_binding(),
        "x": 1244,
        "y": 744,
        "width": 512,
        "height": 512,
    }

    def fake_send(command, **payload):
        requests.append((command, payload))
        return {"ok": True}

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)
    monkeypatch.setattr(
        describe_image,
        "_decode_pixels",
        lambda response: (
            np.zeros((512, 512), dtype=np.float32),
            dict(crop_meta),
        ),
    )

    crop, meta = describe_image._fetch_thumbnail(info)

    assert crop.shape == (512, 512)
    assert meta["source"] == "center_crop"
    assert meta["downsample_factor"] == 1
    assert {key: meta[key] for key in ("x", "y", "width", "height")} == {
        "x": 1244,
        "y": 744,
        "width": 512,
        "height": 512,
    }
    assert requests == [
        (
            "get_pixels",
            {
                "image_id": "rgb-image",
                "image_revision": 7,
                "display_revision": 11,
                "channel": 1,
                "slice": 2,
                "frame": 3,
                "force": True,
                "x": 1244,
                "y": 744,
                "width": 512,
                "height": 512,
            },
        )
    ]
