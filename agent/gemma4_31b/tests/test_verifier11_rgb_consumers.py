from __future__ import annotations

import base64

import numpy as np

from agent.gemma4_31b import visual_diff


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


def _binding(revision: int):
    return {
        "image_id": "rgb-image",
        "image_revision": revision,
        "display_revision": revision + 10,
        "sliceStart": 2,
        "sliceEnd": 2,
        "sliceAxis": "Z",
        "channel": 2,
        "frame": 3,
        "channels": 3,
        "slices": 4,
        "frames": 5,
    }


def _info(revision: int):
    return {
        **_binding(revision),
        "width": 3,
        "height": 1,
        "type": "RGB",
        "value_domain": RGB_PIXEL_DOMAIN,
    }


def _pixels(revision: int, domain_overrides=None, type_label="RGB"):
    packed = np.asarray([0x00FF0000, 0x0000FF00, 0x000000FF], dtype="<f4")
    domain = dict(RGB_PIXEL_DOMAIN)
    domain.update(domain_overrides or {})
    return {
        "ok": True,
        "result": {
            **_binding(revision),
            "x": 0,
            "y": 0,
            "width": 3,
            "height": 1,
            "sliceCount": 1,
            "nPixels": 3,
            "type": type_label,
            "encoding": "base64_float32_le",
            "value_domain": domain,
            "data": base64.b64encode(packed.tobytes()).decode("ascii"),
        },
    }


def test_visual_decode_requires_exact_little_endian_float32_encoding():
    response = _pixels(7)

    plane, meta = visual_diff._decode_pixels(response)

    assert plane is not None
    assert meta["encoding"] == "base64_float32_le"

    missing = _pixels(7)
    del missing["result"]["encoding"]
    invalid = [missing]
    for encoding in (
        None,
        True,
        32,
        "base64_float32_be",
        "BASE64_FLOAT32_LE",
        " base64_float32_le",
    ):
        malformed = _pixels(7)
        malformed["result"]["encoding"] = encoding
        invalid.append(malformed)

    # A declared big-endian transport must be rejected even when its payload
    # has the otherwise valid byte length for the requested dimensions.
    invalid[4]["result"]["data"] = base64.b64encode(
        np.asarray([0x00FF0000, 0x0000FF00, 0x000000FF], dtype=">f4").tobytes()
    ).decode("ascii")

    for malformed in invalid:
        plane, error = visual_diff._decode_pixels(malformed)
        assert plane is None
        assert "encoding" in error["error"]


def _histogram(revision: int, weights=None, *, n_pixels=3, domain_overrides=None):
    domain = {
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
            "rounding": "nearest_integer_half_up",
            "weights": weights or {
                "red": 0.2,
                "green": 0.3,
                "blue": 0.5,
            },
        },
    }
    domain.update(domain_overrides or {})
    return {
        "ok": True,
        "result": {
            **_binding(revision),
            "scope": "full_plane",
            "nPixels": n_pixels,
            "value_domain": domain,
        },
    }


def test_visual_thumbnail_scalarizes_rgb_with_exact_snapshot_bound_histogram(monkeypatch):
    calls = []

    def fake_send(command, **payload):
        calls.append((command, payload))
        if command == "get_image_info":
            return {"ok": True, "result": _info(7)}
        if command == "get_pixels":
            return _pixels(7)
        if command == "get_histogram":
            return _histogram(7)
        raise AssertionError(command)

    monkeypatch.setattr(visual_diff, "_safe_send", fake_send)

    result = visual_diff.capture_thumbnail()

    assert result["mean"] == 85.33333333333333
    assert result["median"] == 77.0
    assert result["min"] == 51.0
    assert result["max"] == 128.0
    assert result["bit_depth"] == 8
    assert result["ceiling"] == 255.0
    assert result["_pixels"].tolist() == [[51.0, 77.0, 128.0]]
    binding_payload = {
        "image_id": "rgb-image",
        "image_revision": 7,
        "display_revision": 17,
        "channel": 2,
        "slice": 2,
        "frame": 3,
        "force": True,
    }
    assert calls == [
        ("get_image_info", {"force": True}),
        ("get_pixels", binding_payload),
        ("get_histogram", {**binding_payload, "scope": "full_plane"}),
    ]
    comparable = visual_diff.diff_report(
        result, result, 'run("Median...", "radius=2");'
    )
    assert comparable["numbers"]["pixel_change_fraction"] == 0.0


def test_visual_diff_keeps_separate_before_after_rgb_contracts(monkeypatch):
    revision = {"value": 1}

    def fake_send(command, **payload):
        current = revision["value"]
        if command == "get_image_info":
            return {"ok": True, "result": _info(current)}
        if command == "get_pixels":
            return _pixels(current)
        if command == "get_histogram":
            response = _histogram(
                current,
                {"red": 0.2, "green": 0.3, "blue": 0.5}
                if current == 1
                else {"red": 0.3, "green": 0.3, "blue": 0.4},
            )
            revision["value"] += 1
            return response
        raise AssertionError(command)

    monkeypatch.setattr(visual_diff, "_safe_send", fake_send)

    before = visual_diff.capture_thumbnail()
    after = visual_diff.capture_thumbnail()
    report = visual_diff.diff_report(before, after, 'run("Median...", "radius=2");')

    assert before["_rgb_scalarization"]["weights"] != after["_rgb_scalarization"]["weights"]
    assert report == {
        "consistent": True,
        "reason": "thumbnail RGB scalarization domains differ; diff skipped",
        "numbers": {},
    }


def test_visual_thumbnail_fails_closed_on_unsafe_or_mismatched_rgb_histogram(monkeypatch):
    cases = [
        _histogram(8),
        _histogram(7, {"red": 0.2, "green": 0.3, "blue": 0.4}),
        _histogram(7, {"red": float("inf"), "green": 0.0, "blue": 0.0}),
        _histogram(7, n_pixels=2),
        _histogram(7, domain_overrides={"density_calibrated": True}),
        _histogram(7, domain_overrides={"acquisition_max_calibrated": 255.0}),
    ]

    for histogram in cases:
        def fake_send(command, **payload):
            if command == "get_image_info":
                return {"ok": True, "result": _info(7)}
            if command == "get_pixels":
                return _pixels(7)
            if command == "get_histogram":
                return histogram
            raise AssertionError(command)

        monkeypatch.setattr(visual_diff, "_safe_send", fake_send)
        result = visual_diff.capture_thumbnail()
        assert "error" in result
        assert any(
            fragment in result["error"]
            for fragment in ("match", "unsafe", "pixel count")
        )


def test_visual_thumbnail_rejects_mutated_packed_rgb_source_domains(monkeypatch):
    mutations = [
        {"representation": "calibrated"},
        {"signed": False},
        {"density_calibrated": True},
        {"acquisition_min_raw": 0.0},
        {"acquisition_max_raw": 255.0},
        {"acquisition_min_calibrated": 0.0},
        {"acquisition_max_calibrated": 255.0},
        {"scalarization": {"method": "already_scalar"}},
    ]
    for mutation in mutations:
        histogram_called = {"value": False}

        def fake_send(command, **payload):
            if command == "get_image_info":
                return {"ok": True, "result": _info(7)}
            if command == "get_pixels":
                return _pixels(7, mutation)
            if command == "get_histogram":
                histogram_called["value"] = True
                return _histogram(7)
            raise AssertionError(command)

        monkeypatch.setattr(visual_diff, "_safe_send", fake_send)
        result = visual_diff.capture_thumbnail()
        assert "domain" in result["error"]
        assert histogram_called["value"] is False


def test_visual_rgb_type_labels_cannot_bypass_packed_validation(monkeypatch):
    for type_label in ("RGB", "  rGb   CoLoR  ", "24-BIT"):
        histogram_called = {"value": False}

        def fake_send(command, **payload):
            if command == "get_image_info":
                info = _info(7)
                info["type"] = type_label
                return {"ok": True, "result": info}
            if command == "get_pixels":
                return _pixels(7, {"pixel_type": "uint8"}, type_label)
            if command == "get_histogram":
                histogram_called["value"] = True
                return _histogram(7)
            raise AssertionError(command)

        monkeypatch.setattr(visual_diff, "_safe_send", fake_send)
        result = visual_diff.capture_thumbnail()
        assert "unsafe source domain" in result["error"]
        assert histogram_called["value"] is False


def test_visual_diff_skips_cross_plane_and_cross_domain_arithmetic(monkeypatch):
    def fake_send(command, **payload):
        if command == "get_image_info":
            return {"ok": True, "result": _info(7)}
        if command == "get_pixels":
            return _pixels(7)
        if command == "get_histogram":
            return _histogram(7)
        raise AssertionError(command)

    monkeypatch.setattr(visual_diff, "_safe_send", fake_send)
    before = visual_diff.capture_thumbnail()

    changed_image = dict(before)
    changed_image["_plane_identity"] = dict(before["_plane_identity"])
    changed_image["_plane_identity"]["image_id"] = "another-image"
    image_report = visual_diff.diff_report(before, changed_image, "Convert to Mask")
    assert image_report["numbers"] == {}
    assert "image/plane geometry differs" in image_report["reason"]

    for mutation in (
        {"pixel_type": "uint16", "acquisition_max_raw": 65535.0},
        {"pixel_type": "float32", "signed": True},
        {
            "density_calibrated": True,
            "acquisition_min_calibrated": 1.0,
            "acquisition_max_calibrated": 2.0,
        },
    ):
        changed_domain = dict(before)
        changed_domain["_analysis_value_domain"] = dict(
            before["_analysis_value_domain"]
        )
        changed_domain["_analysis_value_domain"].update(mutation)
        domain_report = visual_diff.diff_report(
            before, changed_domain, "Convert to Mask"
        )
        assert domain_report["numbers"] == {}
        assert "domains differ" in domain_report["reason"]
