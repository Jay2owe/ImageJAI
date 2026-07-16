from __future__ import annotations

import base64

import numpy as np

from agent.gemma4_31b import describe_image


UINT8_DOMAIN = {
    "representation": "raw",
    "pixel_type": "uint8",
    "signed": False,
    "density_calibrated": False,
    "acquisition_min_raw": 0.0,
    "acquisition_max_raw": 255.0,
    "acquisition_min_calibrated": None,
    "acquisition_max_calibrated": None,
}

RGB_HISTOGRAM_DOMAIN = {
    **UINT8_DOMAIN,
    "scalarization": {
        "method": "imagej_weighted_rgb_intensity",
        "source_pixel_type": "rgb24",
        "weights": {"red": 0.2, "green": 0.3, "blue": 0.5},
        "rounding": "nearest_integer_half_up",
    },
}

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


def _bins_at(*indices: int) -> list[int]:
    bins = [0] * 256
    for index in indices:
        bins[index] += 1
    return bins


def _histogram(domain: dict, bins: list[int], *, minimum: float, maximum: float) -> dict:
    return {
        "min": minimum,
        "max": maximum,
        "mean": float(sum(index * count for index, count in enumerate(bins))) / sum(bins),
        "stdDev": 31.5,
        "nPixels": sum(bins),
        "bins": bins,
        "value_domain": domain,
        "acquisition_min_count": bins[0],
        "acquisition_max_count": bins[255],
        "acquisition_limit_counts_exact": True,
        "scope": "full_plane",
    }


def _pixel_response(binding: dict, values: list[int], *, width: int, height: int) -> dict:
    pixels = np.asarray(values, dtype="<f4")
    return {
        "ok": True,
        "result": {
            **binding,
            "x": 0,
            "y": 0,
            "width": width,
            "height": height,
            "sliceCount": 1,
            "nPixels": width * height,
            "type": "RGB",
            "encoding": "base64_float32_le",
            "data": base64.b64encode(pixels.tobytes()).decode("ascii"),
            "value_domain": RGB_PIXEL_DOMAIN,
            "acquisition_min_count": None,
            "acquisition_max_count": None,
            "acquisition_limit_counts_exact": False,
        },
    }


def _signed_java_int(value: int) -> int:
    return value - (1 << 32) if value >= (1 << 31) else value


def test_uint8_absolute_bins_decode_median_without_observed_range_rescaling():
    stats = describe_image._hist_stats(
        _histogram(UINT8_DOMAIN, _bins_at(51, 77, 128), minimum=51.0, maximum=128.0)
    )

    assert stats is not None
    assert stats["median"] == 77.0
    assert np.array_equal(stats["bin_values"], np.arange(256, dtype=np.float64))


def test_even_uint8_histogram_uses_the_statistical_midpoint_of_middle_values():
    stats = describe_image._hist_stats(
        _histogram(UINT8_DOMAIN, _bins_at(10, 20), minimum=10.0, maximum=20.0)
    )

    assert stats is not None
    assert stats["median"] == 15.0


def test_histogram_stats_reject_malformed_count_vectors_without_raising():
    valid = _histogram(UINT8_DOMAIN, _bins_at(10), minimum=10.0, maximum=10.0)
    fractional = [0.0] * 256
    fractional[10] = 0.5
    fractional[20] = 0.5
    negative = [0] * 256
    negative[10] = 2
    negative[20] = -1
    nonfinite = [0] * 256
    nonfinite[10] = float("nan")
    wrong_sum = _bins_at(10, 20)
    short_byte_histogram = _bins_at(10)[:-1]

    malformed = (
        ["not-a-count"] + [0] * 255,
        [_bins_at(10)],
        fractional,
        negative,
        nonfinite,
        wrong_sum,
        short_byte_histogram,
    )
    for bins in malformed:
        payload = dict(valid)
        payload["bins"] = bins
        assert describe_image._hist_stats(payload) is None

    endpoint_mismatch = dict(valid)
    endpoint_mismatch["acquisition_min_count"] = 1
    assert describe_image._hist_stats(endpoint_mismatch) is None

    uint16_domain = {
        **UINT8_DOMAIN,
        "pixel_type": "uint16",
        "acquisition_max_raw": 65535.0,
        "acquisition_max_calibrated": 65535.0,
    }
    impossible_limits = _histogram(
        uint16_domain, _bins_at(10), minimum=10.0, maximum=10.0
    )
    impossible_limits["acquisition_min_count"] = 1
    impossible_limits["acquisition_max_count"] = 1
    assert describe_image._hist_stats(impossible_limits) is None


def test_indexed8_byte_histogram_also_uses_absolute_bin_indices():
    domain = {**UINT8_DOMAIN, "pixel_type": "indexed8"}
    stats = describe_image._hist_stats(
        _histogram(domain, _bins_at(51, 77, 128), minimum=51.0, maximum=128.0)
    )

    assert stats is not None
    assert stats["median"] == 77.0
    assert np.array_equal(
        describe_image._hist_bin_centers(stats),
        np.arange(256, dtype=np.float64),
    )


def test_histograms_without_bin_origin_and_width_do_not_invent_medians():
    for pixel_type, signed, acquisition_max in (
        ("uint16", False, 65535.0),
        ("float32", True, None),
    ):
        domain = {
            "representation": "raw",
            "pixel_type": pixel_type,
            "signed": signed,
            "density_calibrated": False,
            "acquisition_min_raw": 0.0 if acquisition_max is not None else None,
            "acquisition_max_raw": acquisition_max,
            "acquisition_min_calibrated": None,
            "acquisition_max_calibrated": None,
        }
        payload = _histogram(
            domain, _bins_at(51, 77, 128), minimum=1000.0, maximum=5000.0
        )
        if acquisition_max is None:
            payload["acquisition_min_count"] = None
            payload["acquisition_max_count"] = None
            payload["acquisition_limit_counts_exact"] = False

        stats = describe_image._hist_stats(payload)

        assert stats is not None
        assert stats["median"] is None
        assert stats["bin_values"] is None
        assert describe_image._hist_bin_centers(stats).size == 0
        assert "does not define an exact median" in describe_image._fragment_intensity(
            stats, bit_depth=16
        )


def test_sparse_uint8_valley_and_threshold_use_absolute_bin_values(monkeypatch):
    stats = describe_image._hist_stats(
        _histogram(UINT8_DOMAIN, _bins_at(51, 77, 128), minimum=51.0, maximum=128.0)
    )
    assert stats is not None

    monkeypatch.setattr(
        describe_image,
        "_classify_shape",
        lambda bins, n_pixels: {"shape": "bimodal", "valley_bin": 77, "peaks": [51, 128]},
    )

    assert "valley at intensity 77" in describe_image._fragment_histogram_shape(stats)
    assert describe_image._otsu_threshold_from_hist(stats) == 77.0
    # Triangle selects absolute bin 52 for this sparse distribution. Mapping
    # through observed 51..128 would incorrectly report about 67 instead.
    assert describe_image._triangle_threshold_from_hist(stats) == 52.0


def test_otsu_handles_extremes_constants_and_count_scale_invariance():
    extremes = describe_image._hist_stats(
        _histogram(UINT8_DOMAIN, _bins_at(0, 255), minimum=0.0, maximum=255.0)
    )
    constant = describe_image._hist_stats(
        _histogram(UINT8_DOMAIN, _bins_at(77), minimum=77.0, maximum=77.0)
    )
    scaled_bins = [0] * 256
    for index in (51, 77, 128):
        scaled_bins[index] = 9
    scaled = describe_image._hist_stats(
        _histogram(UINT8_DOMAIN, scaled_bins, minimum=51.0, maximum=128.0)
    )

    assert describe_image._otsu_threshold_from_hist(extremes) == 0.0
    assert describe_image._otsu_threshold_from_hist(constant) == 77.0
    assert describe_image._otsu_threshold_from_hist(scaled) == 77.0


def test_rgb_thumbnail_scalarization_accepts_canonical_and_legacy_rgb24():
    stats = describe_image._hist_stats(
        _histogram(
            RGB_HISTOGRAM_DOMAIN,
            _bins_at(0, 51, 128),
            minimum=0.0,
            maximum=128.0,
        )
    )
    assert stats is not None
    legacy_signed_pixels = np.asarray(
        [[
            _signed_java_int(0xffff0000),
            _signed_java_int(0xff000000),
            _signed_java_int(0xff0000ff),
        ]],
        dtype=np.float32,
    )

    scalar = describe_image._threshold_thumbnail(
        legacy_signed_pixels, stats, {"value_domain": RGB_PIXEL_DOMAIN}
    )

    assert np.array_equal(scalar, np.asarray([[51.0, 0.0, 128.0]]))
    canonical_pixels = np.asarray(
        [[0x00ffffff, 0x00123456]], dtype=np.float32
    )
    canonical_scalar = describe_image._threshold_thumbnail(
        canonical_pixels, stats, {"value_domain": RGB_PIXEL_DOMAIN}
    )
    assert np.array_equal(canonical_scalar, np.asarray([[255.0, 62.0]]))

    for invalid in (-0x01000001, 0x01000000, 0x01123456, 1.5):
        assert describe_image._threshold_thumbnail(
            np.asarray([[invalid]], dtype=np.float64),
            stats,
            {"value_domain": RGB_PIXEL_DOMAIN},
        ) is None


def test_rgb_thresholds_fail_closed_when_thumbnail_domain_is_not_rgb24():
    stats = describe_image._hist_stats(
        _histogram(
            RGB_HISTOGRAM_DOMAIN,
            _bins_at(0, 51, 128),
            minimum=0.0,
            maximum=128.0,
        )
    )
    assert stats is not None

    text = describe_image._fragment_thresholds(
        np.asarray([[0x00ff0000, 0, 0x000000ff]], dtype=np.float32),
        stats,
        {"value_domain": UINT8_DOMAIN},
    )

    assert "value domains cannot be aligned" in text


def test_describe_image_reports_exact_rgb_scalar_histogram_median(monkeypatch):
    binding = {
        "image_id": "rgb-image",
        "image_revision": 4,
        "display_revision": 6,
        "channel": 1,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceAxis": "Z",
        "frame": 1,
        "channels": 1,
        "slices": 1,
        "frames": 1,
    }
    info = {
        **binding,
        "title": "rgb.tif",
        "width": 3,
        "height": 1,
        "type": "RGB",
        "calibration": "",
        "value_domain": RGB_PIXEL_DOMAIN,
    }
    histogram = {
        **binding,
        **_histogram(
            RGB_HISTOGRAM_DOMAIN,
            _bins_at(51, 77, 128),
            minimum=51.0,
            maximum=128.0,
        ),
    }

    def fake_send(command: str, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": info}
        if command == "get_pixels":
            return {"ok": False, "error": "thumbnail intentionally unavailable"}
        if command == "get_histogram":
            assert payload["scope"] == "full_plane"
            return {"ok": True, "result": histogram}
        if command == "get_display_state":
            return {
                "ok": True,
                "result": {**binding, "hasRoi": False, "hasOverlay": False},
            }
        raise AssertionError(command)

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)

    text = describe_image.describe_image()

    assert "Intensity ranges from 51 to 128" in text
    assert "median 77" in text
    assert "median 68" not in text


def test_describe_image_scalarizes_real_rgb_thumbnail_before_component_counts(monkeypatch):
    binding = {
        "image_id": "rgb-spatial",
        "image_revision": 8,
        "display_revision": 9,
        "channel": 1,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceAxis": "Z",
        "frame": 1,
        "channels": 1,
        "slices": 1,
        "frames": 1,
    }
    info = {
        **binding,
        "title": "rgb-spatial.tif",
        "width": 3,
        "height": 1,
        "type": "RGB",
        "calibration": "",
        "value_domain": RGB_PIXEL_DOMAIN,
    }
    histogram = {
        **binding,
        **_histogram(
            RGB_HISTOGRAM_DOMAIN,
            _bins_at(0, 51, 128),
            minimum=0.0,
            maximum=128.0,
        ),
        "acquisition_min_count": None,
        "acquisition_max_count": None,
        "acquisition_limit_counts_exact": False,
    }

    def fake_send(command: str, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": info}
        if command == "get_pixels":
            # Red and blue are separated by black. With weights 0.2/0.3/0.5,
            # ImageJ scalarizes these packed values to [51, 0, 128].
            return _pixel_response(
                binding,
                [
                    _signed_java_int(0xffff0000),
                    _signed_java_int(0xff000000),
                    _signed_java_int(0xff0000ff),
                ],
                width=3,
                height=1,
            )
        if command == "get_histogram":
            return {"ok": True, "result": histogram}
        if command == "get_display_state":
            return {
                "ok": True,
                "result": {**binding, "hasRoi": False, "hasOverlay": False},
            }
        raise AssertionError(command)

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)

    text = describe_image.describe_image()

    assert (
        "Auto-thresholds produce 1 connected components with Otsu, 1 with Li and 2 "
        "with Triangle" in text
    )


def test_describe_image_accepts_server_canonical_lower24_rgb_pixels(monkeypatch):
    binding = {
        "image_id": "rgb-canonical",
        "image_revision": 10,
        "display_revision": 11,
        "channel": 1,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceAxis": "Z",
        "frame": 1,
        "channels": 1,
        "slices": 1,
        "frames": 1,
    }
    info = {
        **binding,
        "title": "rgb-canonical.tif",
        "width": 2,
        "height": 1,
        "type": "RGB",
        "calibration": "",
        "value_domain": RGB_PIXEL_DOMAIN,
    }
    histogram = {
        **binding,
        **_histogram(
            RGB_HISTOGRAM_DOMAIN,
            _bins_at(62, 255),
            minimum=62.0,
            maximum=255.0,
        ),
        "acquisition_min_count": None,
        "acquisition_max_count": None,
        "acquisition_limit_counts_exact": False,
    }

    def fake_send(command: str, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": info}
        if command == "get_pixels":
            # The server canonicalizes ColorProcessor ints to 0x00RRGGBB.
            return _pixel_response(
                binding,
                [0x00ffffff, 0x00123456],
                width=2,
                height=1,
            )
        if command == "get_histogram":
            return {"ok": True, "result": histogram}
        if command == "get_display_state":
            return {
                "ok": True,
                "result": {**binding, "hasRoi": False, "hasOverlay": False},
            }
        raise AssertionError(command)

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)

    text = describe_image.describe_image()

    assert "Auto-thresholds produce" in text
    assert "value domains cannot be aligned" not in text
    assert "stripe-pattern check unavailable for packed RGB values" not in text
    assert "no stripe pattern" in text


def test_describe_image_reports_invalid_histogram_payload_explicitly(monkeypatch):
    binding = {
        "image_id": "invalid-histogram",
        "image_revision": 3,
        "display_revision": 5,
        "channel": 1,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceAxis": "Z",
        "frame": 1,
        "channels": 1,
        "slices": 1,
        "frames": 1,
    }
    info = {
        **binding,
        "title": "invalid.tif",
        "width": 1,
        "height": 1,
        "type": "8-bit",
        "calibration": "",
        "value_domain": UINT8_DOMAIN,
    }
    histogram = {
        **binding,
        **_histogram(UINT8_DOMAIN, _bins_at(10), minimum=10.0, maximum=10.0),
    }
    histogram["bins"] = _bins_at(10, 20)  # Sum 2 disagrees with nPixels 1.

    def fake_send(command: str, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": info}
        if command == "get_pixels":
            return {"ok": False, "error": "thumbnail intentionally unavailable"}
        if command == "get_histogram":
            return {"ok": True, "result": histogram}
        if command == "get_display_state":
            return {
                "ok": True,
                "result": {**binding, "hasRoi": False, "hasOverlay": False},
            }
        raise AssertionError(command)

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)

    text = describe_image.describe_image()

    assert "Fiji returned an invalid histogram payload" in text
    assert "Intensity ranges" not in text


def test_describe_image_renders_structured_histogram_error_readably(monkeypatch):
    binding = {
        "image_id": "rgb-error",
        "image_revision": 2,
        "display_revision": 4,
        "channel": 1,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceAxis": "Z",
        "frame": 1,
        "channels": 1,
        "slices": 1,
        "frames": 1,
    }
    info = {
        **binding,
        "title": "rgb-error.tif",
        "width": 1,
        "height": 1,
        "type": "RGB",
        "calibration": "",
        "value_domain": RGB_PIXEL_DOMAIN,
    }

    def fake_send(command: str, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": info}
        if command == "get_pixels":
            return {"ok": False, "error": "thumbnail intentionally unavailable"}
        if command == "get_histogram":
            return {
                "ok": False,
                "error": {
                    "code": "unsupported_rgb_weights",
                    "message": "RGB histogram weights are unsafe.",
                },
            }
        if command == "get_display_state":
            return {
                "ok": True,
                "result": {**binding, "hasRoi": False, "hasOverlay": False},
            }
        raise AssertionError(command)

    monkeypatch.setattr(describe_image, "_safe_send", fake_send)

    text = describe_image.describe_image()

    assert "unsupported_rgb_weights: RGB histogram weights are unsafe." in text
    assert "{'code'" not in text


def test_describe_image_renders_structured_info_error_readably(monkeypatch):
    monkeypatch.setattr(
        describe_image,
        "_safe_send",
        lambda command, **payload: {
            "ok": False,
            "error": {"code": "auth_required", "message": "Call hello first."},
        },
    )

    text = describe_image.describe_image()

    assert "auth_required: Call hello first." in text
    assert "{'code'" not in text


def test_rgb_artifact_stripes_require_the_aligned_scalar_thumbnail():
    packed = np.asarray(
        [
            [_signed_java_int(0xff000000)] * 4,
            [_signed_java_int(0xffff0000)] * 4,
            [_signed_java_int(0xff00ff00)] * 4,
            [_signed_java_int(0xff0000ff)] * 4,
        ],
        dtype=np.float32,
    )
    meta = {"value_domain": RGB_PIXEL_DOMAIN}

    unavailable = describe_image._fragment_artifacts(packed, 24, meta)
    assert "stripe-pattern check unavailable for packed RGB values" in unavailable

    scalar = np.asarray(
        [
            [0, 1, 0, 1],
            [50, 51, 50, 51],
            [150, 151, 150, 151],
            [250, 251, 250, 251],
        ],
        dtype=np.float64,
    )
    aligned = describe_image._fragment_artifacts(
        packed, 24, meta, analysis_thumb=scalar
    )
    assert "Horizontal stripes detected." in aligned
