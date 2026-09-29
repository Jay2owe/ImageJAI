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
        "encoding": "base64_float32_le",
        "image_id": "image-123",
        "image_revision": 7,
        "display_revision": 11,
        "x": 0,
        "y": 0,
        "width": width,
        "height": height,
        "sliceStart": 1,
        "sliceEnd": slice_count,
        "sliceCount": slice_count,
        "sliceAxis": "Z",
        "channel": 2,
        "frame": 3,
        "channels": 4,
        "slices": max(slice_count, 7),
        "frames": 5,
        "nPixels": len(values),
        "type": "float32",
        "value_domain": {
            "representation": "raw",
            "pixel_type": "float32",
            "signed": True,
            "density_calibrated": False,
            "acquisition_min_raw": None,
            "acquisition_max_raw": None,
            "acquisition_min_calibrated": None,
            "acquisition_max_calibrated": None,
        },
        "acquisition_min_count": None,
        "acquisition_max_count": None,
        "acquisition_limit_counts_exact": False,
    }
    result.update(overrides)
    return {"ok": True, "result": result}


def rgb_histogram_response(*, image_revision=7, weights=None, n_pixels=3, domain_overrides=None):
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
            "weights": weights or {"red": 0.2, "green": 0.3, "blue": 0.5},
        },
    }
    domain.update(domain_overrides or {})
    return {"ok": True, "result": {
        "image_id": "image-123",
        "image_revision": image_revision,
        "display_revision": 11,
        "sliceStart": 1,
        "sliceEnd": 1,
        "sliceAxis": "Z",
        "channel": 2,
        "frame": 3,
        "channels": 4,
        "slices": 7,
        "frames": 5,
        "scope": "full_plane",
        "nPixels": n_pixels,
        "value_domain": domain,
    }}


def rgb_pixel_response(values, width, height, domain_overrides=None):
    domain = {
        "representation": "raw",
        "pixel_type": "rgb24",
        "signed": None,
        "density_calibrated": False,
        "acquisition_min_raw": None,
        "acquisition_max_raw": None,
        "acquisition_min_calibrated": None,
        "acquisition_max_calibrated": None,
    }
    domain.update(domain_overrides or {})
    return pixel_response(
        values,
        width,
        height,
        type="RGB",
        value_domain=domain,
    )


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


def test_client_fails_closed_when_authenticated_ij_client_is_unavailable(monkeypatch):
    monkeypatch.setattr(pixels, "_ij_imagej_command", None)

    with pytest.raises(RuntimeError, match="authenticated agent/ij.py client"):
        pixels.imagej_command({"command": "ping"})


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
        "image_id": "image-123",
        "image_revision": 7,
        "display_revision": 11,
        "x": 5,
        "y": 6,
        "width": 2,
        "height": 2,
        "sliceStart": 7,
        "sliceEnd": 7,
        "sliceCount": 1,
        "sliceAxis": "Z",
        "channel": 2,
        "frame": 3,
        "channels": 4,
        "slices": 7,
        "frames": 5,
        "nPixels": 4,
        "type": "float32",
        "encoding": "base64_float32_le",
        "value_domain": pixel_response([1.0], 1, 1)["result"]["value_domain"],
        "acquisition_min_count": None,
        "acquisition_max_count": None,
        "acquisition_limit_counts_exact": False,
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


def test_get_pixels_rejects_server_clamping_and_never_echoes_requested_region(monkeypatch):
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: pixel_response(
            [1.0], width=1, height=1, x=9, y=20
        ),
    )

    with pytest.raises(RuntimeError, match="clamped pixel geometry"):
        pixels.get_pixels(x=10, y=20, width=2, height=1)


@pytest.mark.parametrize("bad", [True, 1.0, "1"])
def test_get_pixels_rejects_ambiguous_region_and_plane_integers(monkeypatch, bad):
    monkeypatch.setattr(
        pixels, "send",
        lambda cmd: pytest.fail("invalid requests must not reach Fiji"),
    )

    with pytest.raises(TypeError, match="exact integer"):
        pixels.get_pixels(x=bad, y=0, width=1, height=1)
    with pytest.raises(TypeError, match="exact integer"):
        pixels.get_pixels(slice_num=bad)


def test_find_bright_objects_uses_full_precision_threshold_moments():
    # Rounded presentation moments are both 0.00, which would incorrectly
    # classify 0.01 as bright.  The exact threshold is 0.01 and membership is
    # strict greater-than, so there is no object.
    values = [[0.0, 0.0, 0.0, 0.0, 0.01]]

    assert pixels.compute_stats(values)["mean"] == 0.0
    assert pixels.compute_stats(values)["std"] == 0.0
    assert pixels.find_bright_objects(values, threshold_factor=2.0, min_size=1) == []


def test_rgb_stats_preserve_raw_pixels_and_use_bound_imagej_scalarization(monkeypatch):
    calls = []

    def fake_send(cmd):
        calls.append(cmd)
        if cmd["command"] == "get_pixels":
            return rgb_pixel_response([0x00ff0000, 0x0000ff00, 0x000000ff], 3, 1)
        if cmd["command"] == "get_histogram":
            return rgb_histogram_response()
        raise AssertionError(cmd)

    monkeypatch.setattr(pixels, "send", fake_send)

    raw, meta = pixels.get_pixels()
    assert list(raw[0]) == [float(0x00ff0000), float(0x0000ff00), 255.0]
    assert pixels.compute_stats(raw, meta) == {
        "count": 3,
        "mean": 85.33,
        "std": 31.98,
        "min": 51.0,
        "max": 128.0,
        "median": 77.0,
    }
    assert calls[1] == {
        "command": "get_histogram",
        "image_id": "image-123",
        "image_revision": 7,
        "display_revision": 11,
        "channel": 2,
        "slice": 1,
        "frame": 3,
        "scope": "full_plane",
        "force": True,
    }

    derived = pixels.get_current_stats()
    assert derived["meta"]["value_domain"]["pixel_type"] == "rgb24"
    assert derived["analysis_value_domain"]["pixel_type"] == "uint8"
    assert derived["analysis_value_domain"]["scalarization"]["weights"] == {
        "red": 0.2,
        "green": 0.3,
        "blue": 0.5,
    }


def test_rgb_profile_and_threshold_objects_use_half_up_scalar_plane(monkeypatch):
    meta = pixels._pixel_metadata(
        rgb_pixel_response([0.0], 1, 1)["result"], 1, 1, 1, 1
    )
    monkeypatch.setattr(pixels, "send", lambda cmd: rgb_histogram_response())
    raw = [[0x00000000, 0x00ff0000, 0x00000000]]

    profile = pixels.line_profile(raw, 0, 0, 2, 0, meta)
    objects = pixels.find_bright_objects(
        raw, meta, threshold_factor=0.5, min_size=1
    )

    assert [point["value"] for point in profile] == [0.0, 51.0, 0.0]
    assert all(
        point["analysis_value_domain"]["pixel_type"] == "uint8"
        for point in profile
    )
    object_domain = objects[0].pop("analysis_value_domain")
    assert object_domain["scalarization"]["rounding"] == "nearest_integer_half_up"
    assert objects == [{
        "label": 1,
        "x": 1.0,
        "y": 0.0,
        "area": 1,
        "mean_intensity": 51.0,
        "abs_x": 1.0,
        "abs_y": 0.0,
    }]


@pytest.mark.parametrize(
    "histogram",
    [
        rgb_histogram_response(image_revision=8),
        rgb_histogram_response(weights={"red": 0.2, "green": 0.3, "blue": 0.4}),
        rgb_histogram_response(weights={"red": 0.2, "green": float("nan"), "blue": 0.8}),
        rgb_histogram_response(n_pixels=0),
        rgb_histogram_response(
            domain_overrides={"density_calibrated": True}
        ),
        rgb_histogram_response(
            domain_overrides={"acquisition_min_calibrated": 0.0}
        ),
    ],
)
def test_rgb_analysis_fails_closed_on_mismatched_or_unsafe_histogram(monkeypatch, histogram):
    pixel_result = rgb_pixel_response([0x00ff0000], 1, 1)["result"]
    meta = pixels._pixel_metadata(pixel_result, 1, 1, 1, 1)
    monkeypatch.setattr(pixels, "send", lambda cmd: histogram)

    with pytest.raises(RuntimeError, match="did not match|unsafe|cardinality"):
        pixels.compute_stats([[0x00ff0000]], meta)


def test_rgb_region_requires_full_plane_histogram_to_cover_requested_pixels(monkeypatch):
    pixel_result = rgb_pixel_response([0x00ff0000, 0x00000000], 2, 1)["result"]
    meta = pixels._pixel_metadata(pixel_result, 2, 1, 1, 2)
    monkeypatch.setattr(
        pixels, "send", lambda cmd: rgb_histogram_response(n_pixels=1)
    )

    with pytest.raises(RuntimeError, match="fewer pixels"):
        pixels.compute_stats([[0x00ff0000, 0x00000000]], meta)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("representation", "calibrated"),
        ("signed", False),
        ("density_calibrated", True),
        ("acquisition_min_raw", 0.0),
        ("acquisition_max_raw", 255.0),
        ("acquisition_min_calibrated", 0.0),
        ("acquisition_max_calibrated", 255.0),
        ("scalarization", {"method": "already_scalar"}),
    ],
)
def test_rgb_analysis_rejects_mutated_packed_source_domains_before_histogram(
    monkeypatch, field, value
):
    pixel_result = rgb_pixel_response([0x00ff0000], 1, 1)["result"]
    meta = pixels._pixel_metadata(pixel_result, 1, 1, 1, 1)
    meta["value_domain"] = dict(meta["value_domain"])
    meta["value_domain"][field] = value
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: pytest.fail("unsafe source must fail before histogram fetch"),
    )

    with pytest.raises(RuntimeError, match="unsafe pixel domain"):
        pixels.compute_stats([[0x00ff0000]], meta)


@pytest.mark.parametrize("type_label", ["RGB", "  rGb   CoLoR  ", "24-BIT"])
def test_rgb_image_type_cannot_bypass_packed_validation_with_uint8_domain(
    monkeypatch, type_label
):
    pixel_result = rgb_pixel_response(
        [0x00ff0000], 1, 1, {"pixel_type": "uint8"}
    )["result"]
    pixel_result["type"] = type_label
    meta = pixels._pixel_metadata(pixel_result, 1, 1, 1, 1)
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: pytest.fail("type/domain mismatch must fail before histogram"),
    )

    with pytest.raises(RuntimeError, match="unsafe pixel domain"):
        pixels.compute_stats([[0x00ff0000]], meta)


def test_get_pixels_rejects_structured_errors_and_malformed_payloads(monkeypatch):
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: {"ok": False, "error": {"code": "NO_IMAGE", "message": "No image open"}},
    )
    with pytest.raises(RuntimeError, match="No image open"):
        pixels.get_pixels()

    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: pixel_response([1.0], width=2, height=2),
    )
    with pytest.raises(RuntimeError, match="inconsistent dimensions"):
        pixels.get_pixels()

    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: pixel_response([1.0], width=1, height=1, sliceAxis="T"),
    )
    with pytest.raises(RuntimeError, match="inconsistent C/Z/T metadata"):
        pixels.get_pixels()

    missing_axis = pixel_response([1.0], width=1, height=1)
    del missing_axis["result"]["channel"]
    monkeypatch.setattr(pixels, "send", lambda cmd: missing_axis)
    with pytest.raises(RuntimeError, match="missing or malformed C/Z/T metadata"):
        pixels.get_pixels()


@pytest.mark.parametrize(
    "encoding",
    [None, True, 7, "", "base64_float32_be", " base64_float32_le"],
)
def test_get_pixels_rejects_noncanonical_encoding_before_decoding(monkeypatch, encoding):
    response = pixel_response([1.0], width=1, height=1, encoding=encoding)
    # This is deliberately invalid base64.  The encoding boundary must reject
    # the response before attempting to decode the data field.
    response["result"]["data"] = "not base64!"
    monkeypatch.setattr(pixels, "send", lambda cmd: response)

    with pytest.raises(RuntimeError, match="encoding must be exactly base64_float32_le"):
        pixels.get_pixels()


def test_get_pixels_rejects_missing_encoding_before_decoding(monkeypatch):
    response = pixel_response([1.0], width=1, height=1)
    del response["result"]["encoding"]
    response["result"]["data"] = "not base64!"
    monkeypatch.setattr(pixels, "send", lambda cmd: response)

    with pytest.raises(RuntimeError, match="encoding must be exactly base64_float32_le"):
        pixels.get_pixels()


def test_get_pixels_rejects_big_endian_float_payload(monkeypatch):
    raw = struct.pack(">2f", 1.0, 2.0)
    response = pixel_response(
        [1.0, 2.0], width=2, height=1, encoding="base64_float32_be"
    )
    response["result"]["data"] = base64.b64encode(raw).decode("ascii")
    monkeypatch.setattr(pixels, "send", lambda cmd: response)

    with pytest.raises(RuntimeError, match="encoding must be exactly base64_float32_le"):
        pixels.get_pixels()


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("image_id", 7),
        ("image_id", None),
        ("image_id", True),
        ("image_id", "   "),
        ("sliceAxis", 7),
        ("sliceAxis", None),
        ("type", False),
        ("type", None),
        ("type", "\t"),
    ],
)
def test_get_pixels_rejects_non_string_or_blank_provenance(monkeypatch, field, value):
    response = pixel_response([1.0], width=1, height=1)
    response["result"][field] = value
    monkeypatch.setattr(pixels, "send", lambda cmd: response)

    with pytest.raises(RuntimeError, match="missing or malformed C/Z/T metadata"):
        pixels.get_pixels()


def test_get_pixels_strips_valid_provenance_text(monkeypatch):
    response = pixel_response(
        [1.0], width=1, height=1,
        image_id="  image-123  ", sliceAxis=" Z ", type=" float32 ",
    )
    monkeypatch.setattr(pixels, "send", lambda cmd: response)

    _, meta = pixels.get_pixels()

    assert meta["image_id"] == "image-123"
    assert meta["sliceAxis"] == "Z"
    assert meta["type"] == "float32"


def test_compute_stats_even_median_empty_and_nonfinite_policy():
    assert pixels.compute_stats([[1.0, 2.0], [3.0, 4.0]])["median"] == 2.5
    assert pixels.compute_stats([]) == {
        "count": 0,
        "mean": None,
        "std": None,
        "min": None,
        "max": None,
        "median": None,
    }
    with pytest.raises(ValueError, match="non-finite"):
        pixels.compute_stats([[1.0, float("nan")]])


def test_large_pixel_response_uses_one_compact_float32_backing_buffer(monkeypatch):
    width = 2000
    height = 2000
    values = pixels.array("f", [1.25]) * (width * height)
    raw = values.tobytes()
    response = {
        "ok": True,
        "result": {
            "data": base64.b64encode(raw).decode("ascii"),
            "encoding": "base64_float32_le",
            "image_id": "image-large",
            "image_revision": 1,
            "display_revision": 1,
            "x": 0,
            "y": 0,
            "width": width,
            "height": height,
            "sliceStart": 1,
            "sliceEnd": 1,
            "sliceCount": 1,
            "sliceAxis": "Z",
            "channel": 1,
            "frame": 1,
            "channels": 1,
            "slices": 1,
            "frames": 1,
            "nPixels": width * height,
            "type": "float32",
            "value_domain": pixel_response([1.0], 1, 1)["result"]["value_domain"],
            "acquisition_min_count": None,
            "acquisition_max_count": None,
            "acquisition_limit_counts_exact": False,
        },
    }
    monkeypatch.setattr(pixels, "send", lambda cmd: response)

    data, _ = pixels.get_pixels()

    assert isinstance(data, pixels._CompactPlane)
    assert data._values.itemsize == 4
    assert len(data._values) == width * height
    assert data[1999][1999] == pytest.approx(1.25)


def test_stats_helpers_call_get_pixels_with_expected_args(monkeypatch):
    calls = []
    sample = [[1.0, 2.0], [3.0, 4.0]]
    meta = {"x": 10, "y": 20, "width": 30, "height": 40}

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
        return {"ok": True, "result": {
            "image_id": "stack-image",
            "image_revision": 4,
            "display_revision": 9,
            "channel": 2,
            "frame": 3,
            "channels": 4,
            "slices": 3,
            "frames": 5,
        }}

    def fake_get_pixels(slice_num, **kwargs):
        slice_calls.append((slice_num, kwargs))
        return [[float(slice_num), float(slice_num + 1)]], {
            "image_id": "stack-image",
            "image_revision": 4,
            "display_revision": 9,
            "channel": 2,
            "frame": 3,
            "channels": 4,
            "slices": 3,
            "frames": 5,
            "sliceAxis": "Z",
            "sliceStart": slice_num,
            "sliceEnd": slice_num,
            "sliceCount": 1,
        }

    monkeypatch.setattr(pixels, "send", fake_send)
    monkeypatch.setattr(pixels, "get_pixels", fake_get_pixels)

    rows = pixels.get_stack_stats()

    assert send_calls == [{"command": "get_image_info", "force": True}]
    expected_binding = {
        "image_id": "stack-image",
        "image_revision": 4,
        "display_revision": 9,
        "channel": 2,
        "frame": 3,
    }
    assert slice_calls == [
        (1, expected_binding), (2, expected_binding), (3, expected_binding)
    ]
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


@pytest.mark.parametrize("image_id", [7, None, False, "   "])
def test_get_stack_stats_rejects_non_string_or_blank_image_id(monkeypatch, image_id):
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: {"ok": True, "result": {
            "image_id": image_id,
            "image_revision": 4,
            "display_revision": 9,
            "channel": 1,
            "frame": 1,
            "channels": 1,
            "slices": 1,
            "frames": 1,
        }},
    )
    monkeypatch.setattr(
        pixels,
        "get_pixels",
        lambda *args, **kwargs: pytest.fail(
            "malformed image provenance must fail before fetching pixels"
        ),
    )

    with pytest.raises(RuntimeError, match="malformed snapshot metadata"):
        pixels.get_stack_stats()


def test_get_stack_stats_strips_valid_image_id_before_binding(monkeypatch):
    calls = []
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: {"ok": True, "result": {
            "image_id": "  stack-image  ",
            "image_revision": 4,
            "display_revision": 9,
            "channel": 1,
            "frame": 1,
            "channels": 1,
            "slices": 1,
            "frames": 1,
        }},
    )

    def fake_get_pixels(slice_num, **kwargs):
        calls.append((slice_num, kwargs))
        return [[1.0]], {
            "image_id": "stack-image",
            "image_revision": 4,
            "display_revision": 9,
            "channel": 1,
            "frame": 1,
            "channels": 1,
            "slices": 1,
            "frames": 1,
            "sliceAxis": "Z",
            "sliceStart": 1,
            "sliceEnd": 1,
            "sliceCount": 1,
        }

    monkeypatch.setattr(pixels, "get_pixels", fake_get_pixels)

    rows = pixels.get_stack_stats()

    assert calls == [(1, {
        "image_id": "stack-image",
        "image_revision": 4,
        "display_revision": 9,
        "channel": 1,
        "frame": 1,
    })]
    assert rows[0]["meta"]["image_id"] == "stack-image"


def test_get_stack_stats_rejects_one_plane_from_another_revision(monkeypatch):
    monkeypatch.setattr(
        pixels,
        "send",
        lambda cmd: {"ok": True, "result": {
            "image_id": "stack-image", "image_revision": 4,
            "display_revision": 9, "channel": 1, "frame": 1,
            "channels": 1, "slices": 2, "frames": 1,
        }},
    )

    def fake_get_pixels(slice_num, **kwargs):
        return [[1.0]], {
            "image_id": "stack-image",
            "image_revision": 5 if slice_num == 2 else 4,
            "display_revision": 9,
            "channel": 1, "frame": 1, "channels": 1,
            "slices": 2, "frames": 1, "sliceAxis": "Z",
            "sliceStart": slice_num, "sliceEnd": slice_num, "sliceCount": 1,
        }

    monkeypatch.setattr(pixels, "get_pixels", fake_get_pixels)

    with pytest.raises(RuntimeError, match="did not match bound image snapshot"):
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
