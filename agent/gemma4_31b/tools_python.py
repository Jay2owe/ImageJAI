"""Python-side image analysis tools — numpy-powered, no macro needed.

Thin wrappers over the existing get_pixels TCP command plus
numpy. Docstrings are the schema Ollama sees, so they stay one
sentence followed by an Args: block with primitive types only.
Patterns are copied from agent/pixels.py (base64 float32 decode,
flood-fill connected components, thresholded object count) — the
Phase 1c brief names that file as the authoritative reference.

Every tool follows the same contract:
- uses @tool from registry.py
- handles the active image via get_pixels (no new TCP commands)
- returns a dict with an "error" key if Fiji is unreachable or
  has no image open, instead of raising
- keeps payloads under a few megabytes by downsampling the full
  image to a max 2048 long-edge when a whole-image analysis is
  required, and records the downsample factor in the return value
"""

import base64
import binascii
import math
import socket

import numpy as np

from .registry import send, tool


_MAX_LONG_EDGE = 2048
_SERVER_PIXEL_CAP = 4_000_000
_MAX_SERVER_CROP_SIDE = int(math.isqrt(_SERVER_PIXEL_CAP))
# Keep the successful 2D-list result below the chat loop's 32,000-character
# pixel-result budget even for long float32 spellings. Larger requests are
# rejected before get_pixels, so they cannot expand into millions of Python
# float objects merely to be truncated after JSON serialisation.
MAX_RAW_PIXEL_VALUES = 1_024

_PIXEL_METADATA_FIELDS = (
    "image_id",
    "image_revision",
    "display_revision",
    "x",
    "y",
    "width",
    "height",
    "sliceStart",
    "sliceEnd",
    "sliceCount",
    "sliceAxis",
    "channel",
    "frame",
    "channels",
    "slices",
    "frames",
    "nPixels",
    "type",
    "encoding",
    "value_domain",
    "acquisition_min_count",
    "acquisition_max_count",
    "acquisition_limit_counts_exact",
)


def _error(msg) -> dict:
    return {"error": str(msg)}


def _exact_int(value, name, minimum=None) -> int:
    """Return a true integer, rejecting bools, floats and numeric strings."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError("{} must be an exact integer".format(name))
    if minimum is not None and value < minimum:
        raise ValueError("{} must be at least {}".format(name, minimum))
    return value


def _optional_finite_number(value, name):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError("{} must be numeric or null".format(name))
    value = float(value)
    if not math.isfinite(value):
        raise ValueError("{} must be finite".format(name))
    return value


def _is_rgb_image_type(value) -> bool:
    return isinstance(value, str) and value.strip().lower() in (
        "rgb", "rgb color", "24-bit"
    )


def _is_exact_rgb24_source_domain(domain) -> bool:
    """Recognize only the uncalibrated packed domain emitted by get_pixels."""
    required = (
        "representation", "pixel_type", "signed", "density_calibrated",
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    )
    return (
        isinstance(domain, dict)
        and all(key in domain for key in required)
        and domain.get("representation") == "raw"
        and domain.get("pixel_type") == "rgb24"
        and domain.get("signed") is None
        and domain.get("density_calibrated") is False
        and all(
            domain.get(key) is None
            for key in (
                "acquisition_min_raw", "acquisition_max_raw",
                "acquisition_min_calibrated", "acquisition_max_calibrated",
            )
        )
        and domain.get("scalarization") is None
    )


def _is_exact_byte_scalar_domain(domain) -> bool:
    """Recognize an exact uncalibrated ImageJ byte-intensity domain."""
    required = (
        "representation", "pixel_type", "signed", "density_calibrated",
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    )
    return (
        isinstance(domain, dict)
        and all(key in domain for key in required)
        and domain.get("representation") == "raw"
        and domain.get("pixel_type") in ("uint8", "indexed8")
        and domain.get("signed") is False
        and domain.get("density_calibrated") is False
        and isinstance(domain.get("acquisition_min_raw"), (int, float))
        and not isinstance(domain.get("acquisition_min_raw"), bool)
        and float(domain["acquisition_min_raw"]) == 0.0
        and isinstance(domain.get("acquisition_max_raw"), (int, float))
        and not isinstance(domain.get("acquisition_max_raw"), bool)
        and float(domain["acquisition_max_raw"]) == 255.0
        and domain.get("acquisition_min_calibrated") is None
        and domain.get("acquisition_max_calibrated") is None
    )


def _is_exact_rgb_scalar_domain(domain) -> bool:
    """Recognize the raw uint8 domain ImageJ publishes for weighted RGB intensity."""
    required = (
        "representation", "pixel_type", "signed", "density_calibrated",
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
        "scalarization",
    )
    return (
        isinstance(domain, dict)
        and all(key in domain for key in required)
        and domain.get("representation") == "raw"
        and domain.get("pixel_type") == "uint8"
        and domain.get("signed") is False
        and domain.get("density_calibrated") is False
        and isinstance(domain.get("acquisition_min_raw"), (int, float))
        and not isinstance(domain.get("acquisition_min_raw"), bool)
        and float(domain["acquisition_min_raw"]) == 0.0
        and isinstance(domain.get("acquisition_max_raw"), (int, float))
        and not isinstance(domain.get("acquisition_max_raw"), bool)
        and float(domain["acquisition_max_raw"]) == 255.0
        and domain.get("acquisition_min_calibrated") is None
        and domain.get("acquisition_max_calibrated") is None
        and isinstance(domain.get("scalarization"), dict)
    )


def _decode_value_domain(result: dict):
    domain = result.get("value_domain")
    if not isinstance(domain, dict) or domain.get("representation") != "raw":
        raise ValueError("missing raw value-domain metadata")
    if domain.get("pixel_type") not in (
        "uint8", "uint16", "float32", "rgb24", "indexed8", "unknown"
    ):
        raise ValueError("invalid pixel type metadata")
    if domain.get("signed") is not None and not isinstance(domain.get("signed"), bool):
        raise ValueError("invalid signed metadata")
    if not isinstance(domain.get("density_calibrated"), bool):
        raise ValueError("invalid density-calibrated metadata")
    normalized = dict(domain)
    for key in (
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    ):
        normalized[key] = _optional_finite_number(domain.get(key), key)
    scalarization = domain.get("scalarization")
    if (
        scalarization is None
        and (domain.get("pixel_type") == "rgb24" or _is_rgb_image_type(result.get("type")))
        and not _is_exact_rgb24_source_domain(domain)
    ):
        raise ValueError("invalid packed RGB24 source value-domain metadata")
    if scalarization is not None:
        if (
            not _is_exact_rgb_scalar_domain(domain)
            or not isinstance(scalarization, dict)
            or scalarization.get("method") != "imagej_weighted_rgb_intensity"
            or scalarization.get("source_pixel_type") != "rgb24"
            or scalarization.get("rounding") != "nearest_integer_half_up"
            or not isinstance(scalarization.get("weights"), dict)
        ):
            raise ValueError("invalid RGB scalarization metadata")
        weights = scalarization["weights"]
        normalized_weights = {}
        for component in ("red", "green", "blue"):
            value = _optional_finite_number(weights.get(component), component + " weight")
            if value is None or value < 0.0:
                raise ValueError("invalid RGB scalarization weights")
            normalized_weights[component] = value
        if not math.isclose(
            sum(normalized_weights.values()), 1.0, rel_tol=0.0, abs_tol=1e-9
        ):
            raise ValueError("invalid RGB scalarization weights")
        normalized["scalarization"] = dict(scalarization)
        normalized["scalarization"]["weights"] = normalized_weights
    return normalized


def _safe_send(command: str, **payload) -> dict:
    """Wrap registry.send so connection errors become an error dict instead of an
    exception — Phase 1c tools must fail cleanly when Fiji is unreachable
    rather than crash the chat loop.
    """
    try:
        return send(command, **payload)
    except (ConnectionRefusedError, socket.timeout, OSError) as exc:
        return {"ok": False, "error": "Fiji TCP server unreachable: {}".format(exc)}


def _bit_depth_from_type(type_str) -> int:
    """Parse an ImageJ type label like '8-bit' / '16-bit' / '32-bit'.

    Returns 0 when the label is RGB, empty, or otherwise not a
    gray bit-depth we can reason about.
    """
    if not isinstance(type_str, str):
        return 0
    s = type_str.strip().lower()
    if s.startswith("8-"):
        return 8
    if s.startswith("16-"):
        return 16
    if s.startswith("32-"):
        return 32
    return 0


def _get_image_info() -> dict:
    """Return the active image's info dict, or {'error': ...}."""
    resp = _safe_send("get_image_info", force=True)
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = resp.get("error") if isinstance(resp, dict) else "no reply from Fiji"
        return _error(err or "get_image_info failed")
    result = resp.get("result")
    if not isinstance(result, dict):
        return _error("get_image_info returned no result")
    try:
        width = _exact_int(result["width"], "width", 1)
        height = _exact_int(result["height"], "height", 1)
        channels = _exact_int(result["channels"], "channels", 1)
        slices = _exact_int(result["slices"], "slices", 1)
        frames = _exact_int(result["frames"], "frames", 1)
        image_id = result["image_id"]
        image_revision = _exact_int(result["image_revision"], "image_revision", 1)
        display_revision = _exact_int(result["display_revision"], "display_revision", 1)
        channel = _exact_int(result["channel"], "channel", 1)
        slice_start = _exact_int(result["sliceStart"], "sliceStart", 1)
        slice_end = _exact_int(result["sliceEnd"], "sliceEnd", 1)
        slice_axis = result["sliceAxis"]
        frame = _exact_int(result["frame"], "frame", 1)
        value_domain = _decode_value_domain(result)
        if not isinstance(image_id, str) or not isinstance(slice_axis, str):
            raise TypeError("image_id and sliceAxis must be strings")
    except (KeyError, TypeError, ValueError) as exc:
        return _error(
            "get_image_info returned incomplete image-axis metadata: {}".format(exc)
        )
    if (
        min(width, height, channels, slices, frames) <= 0
        or not image_id.strip()
        or image_revision <= 0
        or slice_axis != "Z"
        or slice_start != slice_end
        or not 1 <= channel <= channels
        or not 1 <= slice_start <= slices
        or not 1 <= frame <= frames
    ):
        return _error("get_image_info returned invalid image-axis metadata")
    normalized = dict(result)
    normalized.update({
        "width": width,
        "height": height,
        "channels": channels,
        "slices": slices,
        "frames": frames,
        "image_id": image_id,
        "image_revision": image_revision,
        "display_revision": display_revision,
        "channel": channel,
        "sliceStart": slice_start,
        "sliceEnd": slice_end,
        "sliceAxis": slice_axis,
        "frame": frame,
        "value_domain": value_domain,
    })
    return normalized


def _snapshot_payload(info: dict, requested_slice: int | None = None) -> dict:
    """Bind a follow-up read to one immutable image revision and C/Z/T plane."""
    return {
        "image_id": info["image_id"],
        "image_revision": int(info["image_revision"]),
        "display_revision": int(info["display_revision"]),
        "channel": int(info["channel"]),
        "slice": int(info["sliceStart"] if requested_slice is None else requested_slice),
        "frame": int(info["frame"]),
        "force": True,
    }


def _validate_region_against_info(info: dict, x: int, y: int, width: int, height: int):
    """Validate one zero-based rectangle against one image-info snapshot."""
    image_width = int(info.get("width", 0))
    image_height = int(info.get("height", 0))
    if image_width <= 0 or image_height <= 0:
        return _error("active image has zero size")
    if x < 0 or y < 0 or x + width > image_width or y + height > image_height:
        return _error(
            "region [{}, {}, {}, {}] is outside active image bounds {}x{}".format(
                x, y, width, height, image_width, image_height
            )
        )
    return None


def _validate_region(x: int, y: int, width: int, height: int):
    """Validate one zero-based rectangle against the current image bounds."""
    info = _get_image_info()
    if "error" in info:
        return info
    return _validate_region_against_info(info, x, y, width, height)


def _geometry_matches(meta: dict, x: int, y: int, width: int, height: int) -> bool:
    """Return whether Fiji returned exactly the requested pixel rectangle."""
    return all(
        int(meta.get(key, -1)) == value
        for key, value in (
            ("x", x),
            ("y", y),
            ("width", width),
            ("height", height),
        )
    )


def _slice_matches(meta: dict, requested_slice: int) -> bool:
    """Return whether Fiji supplied exactly one requested/current plane."""
    start = int(meta.get("sliceStart", 0))
    end = int(meta.get("sliceEnd", 0))
    count = int(meta.get("sliceCount", 0))
    if requested_slice > 0:
        return start == requested_slice and end == requested_slice and count == 1
    return start >= 1 and end == start and count == 1


def _raw_pixel_limit_error(requested_values: int) -> dict:
    """Return a small, actionable result without fetching an oversized array."""
    return {
        "error": (
            "raw pixel request contains {} values; maximum is {}. "
            "Request a smaller region, or use region_stats, histogram_summary, "
            "or line_profile for a bounded result."
        ).format(requested_values, MAX_RAW_PIXEL_VALUES),
        "requested_values": int(requested_values),
        "max_values": MAX_RAW_PIXEL_VALUES,
    }


def _decode_pixel_metadata(result: dict, width: int, height: int):
    """Return validated server metadata, including exact C/Z/T attribution."""
    try:
        meta = {
            "image_id": result["image_id"],
            "image_revision": _exact_int(result["image_revision"], "image_revision", 1),
            "display_revision": _exact_int(result["display_revision"], "display_revision", 1),
            "x": _exact_int(result["x"], "x", 0),
            "y": _exact_int(result["y"], "y", 0),
            "width": width,
            "height": height,
            "sliceStart": _exact_int(result["sliceStart"], "sliceStart", 1),
            "sliceEnd": _exact_int(result["sliceEnd"], "sliceEnd", 1),
            "sliceCount": _exact_int(result["sliceCount"], "sliceCount", 1),
            "sliceAxis": result["sliceAxis"],
            "channel": _exact_int(result["channel"], "channel", 1),
            "frame": _exact_int(result["frame"], "frame", 1),
            "channels": _exact_int(result["channels"], "channels", 1),
            "slices": _exact_int(result["slices"], "slices", 1),
            "frames": _exact_int(result["frames"], "frames", 1),
            "nPixels": _exact_int(result["nPixels"], "nPixels", 1),
            "type": result["type"],
            "encoding": result["encoding"],
            "value_domain": _decode_value_domain(result),
        }
        if (
            not isinstance(meta["image_id"], str)
            or not isinstance(meta["sliceAxis"], str)
            or not isinstance(meta["type"], str)
            or not isinstance(meta["encoding"], str)
        ):
            raise TypeError("image_id, sliceAxis, type and encoding must be strings")
        counts_exact = result["acquisition_limit_counts_exact"]
        if not isinstance(counts_exact, bool):
            raise TypeError("acquisition_limit_counts_exact must be bool")
        min_count_raw = result["acquisition_min_count"]
        max_count_raw = result["acquisition_max_count"]
        meta["acquisition_min_count"] = (
            None if min_count_raw is None
            else _exact_int(min_count_raw, "acquisition_min_count", 0)
        )
        meta["acquisition_max_count"] = (
            None if max_count_raw is None
            else _exact_int(max_count_raw, "acquisition_max_count", 0)
        )
        meta["acquisition_limit_counts_exact"] = counts_exact
    except (KeyError, TypeError, ValueError) as exc:
        return None, _error(
            "get_pixels reply missing or malformed C/Z/T metadata: {}".format(exc)
        )

    if (
        not meta["image_id"].strip()
        or meta["image_revision"] <= 0
        or meta["x"] < 0
        or meta["y"] < 0
        or meta["sliceAxis"] != "Z"
        or meta["encoding"] != "base64_float32_le"
        or meta["channels"] <= 0
        or meta["slices"] <= 0
        or meta["frames"] <= 0
        or not 1 <= meta["channel"] <= meta["channels"]
        or not 1 <= meta["frame"] <= meta["frames"]
        or not 1 <= meta["sliceStart"] <= meta["sliceEnd"] <= meta["slices"]
        or meta["sliceCount"] != meta["sliceEnd"] - meta["sliceStart"] + 1
        or meta["nPixels"] != width * height * meta["sliceCount"]
        or (
            meta["acquisition_limit_counts_exact"]
            and (
                meta["acquisition_min_count"] is None
                or meta["acquisition_max_count"] is None
            )
        )
        or (
            meta["acquisition_min_count"] is not None
            and meta["acquisition_min_count"] > meta["nPixels"]
        )
        or (
            meta["acquisition_max_count"] is not None
            and meta["acquisition_max_count"] > meta["nPixels"]
        )
    ):
        return None, _error("get_pixels returned inconsistent C/Z/T metadata")
    if meta["sliceCount"] != 1:
        return None, _error("pixel analysis tools require exactly one Z plane")
    return meta, None


def _metadata_matches_info(
    meta: dict, info: dict, requested_slice: int | None = None
) -> bool:
    """Reject a response from a different image revision or C/Z/T plane."""
    try:
        expected_slice = _exact_int(
            info["sliceStart"] if requested_slice is None else requested_slice,
            "expected slice",
            1,
        )
        meta_image_id = meta["image_id"]
        info_image_id = info["image_id"]
        if (
            not isinstance(meta_image_id, str)
            or not meta_image_id.strip()
            or not isinstance(info_image_id, str)
            or not info_image_id.strip()
            or not isinstance(meta["sliceAxis"], str)
            or not isinstance(info["sliceAxis"], str)
        ):
            return False
        return (
            meta_image_id == info_image_id
            and _exact_int(meta["image_revision"], "image_revision", 1)
            == _exact_int(info["image_revision"], "info image_revision", 1)
            and _exact_int(meta["display_revision"], "display_revision", 1)
            == _exact_int(info["display_revision"], "info display_revision", 1)
            and meta["sliceAxis"] == info["sliceAxis"] == "Z"
            and _exact_int(meta["sliceStart"], "sliceStart", 1) == expected_slice
            and _exact_int(meta["sliceEnd"], "sliceEnd", 1) == expected_slice
            and _exact_int(meta["channel"], "channel", 1)
            == _exact_int(info["channel"], "info channel", 1)
            and _exact_int(meta["frame"], "frame", 1)
            == _exact_int(info["frame"], "info frame", 1)
            and all(
                _exact_int(meta[key], key, 1)
                == _exact_int(info[key], "info " + key, 1)
                for key in ("channels", "slices", "frames")
            )
        )
    except (KeyError, TypeError, ValueError):
        return False


def _decode_rgb24_samples(samples: np.ndarray):
    """Return strict lower-24-bit RGB integers, including legacy signed 0xff forms."""
    values = np.asarray(samples, dtype=np.float64)
    if not np.isfinite(values).all() or not np.equal(values, np.floor(values)).all():
        return None
    canonical_lower24 = (values >= 0) & (values <= 0x00ffffff)
    legacy_signed_ff = (values >= -0x01000000) & (values <= -1)
    if not np.all(canonical_lower24 | legacy_signed_ff):
        return None
    return np.bitwise_and(values.astype(np.int64), 0x00ffffff)


def _scalarize_rgb24_measurement(arr: np.ndarray, meta: dict, info: dict):
    """Align packed RGB samples to ImageJ's exact snapshot-bound intensity domain."""
    pixel_domain = meta.get("value_domain")
    packed_source = (
        isinstance(pixel_domain, dict) and pixel_domain.get("pixel_type") == "rgb24"
    ) or _is_rgb_image_type(meta.get("type"))
    if not packed_source:
        return arr, meta
    if not _is_exact_rgb24_source_domain(pixel_domain):
        return None, _error("invalid packed RGB24 source value-domain metadata")

    payload = _snapshot_payload(info)
    payload["scope"] = "full_plane"
    response = _safe_send("get_histogram", **payload)
    result = response.get("result") if isinstance(response, dict) else None
    if not isinstance(response, dict) or not response.get("ok"):
        detail = response.get("error") if isinstance(response, dict) else None
        return None, _error(
            "RGB intensity analysis requires a snapshot-bound full-plane histogram{}".format(
                ": {}".format(detail) if detail else ""
            )
        )
    if (
        not isinstance(result, dict)
        or result.get("scope") != "full_plane"
        or not _metadata_matches_info(result, info)
    ):
        return None, _error(
            "RGB intensity analysis histogram did not match the image snapshot and C/Z/T plane"
        )
    try:
        histogram_pixels = _exact_int(result["nPixels"], "nPixels", 1)
        histogram_domain = _decode_value_domain(result)
        expected_pixels = int(info["width"]) * int(info["height"])
    except (KeyError, TypeError, ValueError, OverflowError) as exc:
        return None, _error("invalid RGB histogram scalarization metadata: {}".format(exc))
    scalarization = histogram_domain.get("scalarization")
    if (
        histogram_pixels != expected_pixels
        or histogram_domain.get("representation") != "raw"
        or histogram_domain.get("pixel_type") != "uint8"
        or histogram_domain.get("signed") is not False
        or histogram_domain.get("density_calibrated") is not False
        or histogram_domain.get("acquisition_min_raw") != 0.0
        or histogram_domain.get("acquisition_max_raw") != 255.0
        or histogram_domain.get("acquisition_min_calibrated") is not None
        or histogram_domain.get("acquisition_max_calibrated") is not None
        or not isinstance(scalarization, dict)
    ):
        return None, _error("invalid RGB histogram scalarization metadata")

    # _decode_value_domain has already checked the method, source, rounding,
    # finite/non-negative weights, and a normalized sum within 1e-9.
    weights = scalarization["weights"]
    packed = _decode_rgb24_samples(arr)
    if packed is None:
        return None, _error("get_pixels returned invalid packed RGB24 samples")
    red = ((packed >> 16) & 0xff).astype(np.float64)
    green = ((packed >> 8) & 0xff).astype(np.float64)
    blue = (packed & 0xff).astype(np.float64)
    scalar = np.floor(
        red * weights["red"]
        + green * weights["green"]
        + blue * weights["blue"]
        + 0.5
    )
    if not np.isfinite(scalar).all() or np.any(scalar < 0) or np.any(scalar > 255):
        return None, _error("RGB scalarization produced invalid uint8 intensities")
    scalar_meta = dict(meta)
    scalar_meta["value_domain"] = histogram_domain
    return scalar, scalar_meta


def _measurement_result(meta: dict, **payload) -> dict:
    """Expose pixel values/statistics together with their exact source plane."""
    out = dict(payload)
    out.update({key: meta[key] for key in _PIXEL_METADATA_FIELDS})
    return out


def _decode_pixels(resp):
    """Decode a get_pixels reply into (float32 2D ndarray, meta dict).

    Returns (None, error_dict) on failure. These tools require one
    fully attributed 2D Z plane and reject stacks or missing C/Z/T data.
    """
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = resp.get("error") if isinstance(resp, dict) else "no reply from Fiji"
        return None, _error(err or "get_pixels failed")
    result = resp.get("result") or {}
    b64 = result.get("data")
    if not isinstance(b64, str) or not b64:
        return None, _error("get_pixels reply missing data field")
    try:
        raw = base64.b64decode(b64, validate=True)
    except (binascii.Error, ValueError, TypeError) as exc:
        return None, _error("base64 decode failed: {}".format(exc))
    try:
        w = _exact_int(result["width"], "width", 1)
        h = _exact_int(result["height"], "height", 1)
    except (TypeError, ValueError):
        return None, _error("get_pixels returned malformed dimensions")
    if w <= 0 or h <= 0:
        return None, _error("get_pixels returned zero-size region")
    meta, metadata_error = _decode_pixel_metadata(result, w, h)
    if meta is None:
        return None, metadata_error
    if len(raw) != meta["nPixels"] * 4:
        return None, _error("get_pixels float32 byte length does not match metadata")
    plane = np.frombuffer(raw, dtype="<f4").reshape((h, w))
    if not np.isfinite(plane).all():
        return None, _error("get_pixels returned non-finite pixel values")
    return plane, meta


def _fetch_full_downsampled(max_side: int = _MAX_LONG_EDGE):
    """Fetch the whole active image and numpy-downsample so the long
    edge is at most max_side. Returns (array, meta) or (None, error_dict).

    meta always includes "downsample_factor" and "bit_depth". If the
    image is too big for the server's 4M-pixel cap, fall back to a
    centred max_side square crop and note the fallback.
    """
    info = _get_image_info()
    if "error" in info:
        return None, info
    w = int(info.get("width", 0))
    h = int(info.get("height", 0))
    if w <= 0 or h <= 0:
        return None, _error("active image has zero size")
    long_edge = max(w, h)
    factor = 1 if long_edge <= max_side else (long_edge + max_side - 1) // max_side
    bit_depth = _bit_depth_from_type(info.get("type", ""))

    if w * h <= _SERVER_PIXEL_CAP:
        arr, meta = _decode_pixels(
            _safe_send("get_pixels", **_snapshot_payload(info))
        )
        if arr is None:
            return None, meta
        if not _geometry_matches(meta, 0, 0, w, h):
            return None, _error("active image geometry changed during pixel fetch")
        if not _metadata_matches_info(meta, info):
            return None, _error("active image snapshot or pixel plane changed during fetch")
        arr, meta = _scalarize_rgb24_measurement(arr, meta, info)
        if arr is None:
            return None, meta
        if factor > 1:
            arr = arr[::factor, ::factor]
        meta["downsample_factor"] = int(factor)
        meta["bit_depth"] = (
            8 if meta["value_domain"].get("scalarization") is not None else int(bit_depth)
        )
        meta["source"] = "full"
        return arr, meta

    # Image too large to fetch whole: take a centred crop that still
    # respects the server's 4M-pixel hard limit.
    crop = min(max_side, w, h, _MAX_SERVER_CROP_SIDE)
    cx = max(0, (w - crop) // 2)
    cy = max(0, (h - crop) // 2)
    cw = min(crop, w - cx)
    ch = min(crop, h - cy)
    payload = _snapshot_payload(info)
    payload.update({"x": cx, "y": cy, "width": cw, "height": ch})
    arr, meta = _decode_pixels(_safe_send("get_pixels", **payload))
    if arr is None:
        return None, meta
    if not _geometry_matches(meta, cx, cy, cw, ch):
        return None, _error("active image geometry changed during pixel fetch")
    if not _metadata_matches_info(meta, info):
        return None, _error("active image snapshot or pixel plane changed during fetch")
    arr, meta = _scalarize_rgb24_measurement(arr, meta, info)
    if arr is None:
        return None, meta
    # This branch returns the requested crop verbatim.  Its relationship to
    # the full image is captured by x/y/width/height, not by a stride factor.
    meta["downsample_factor"] = 1
    meta["bit_depth"] = (
        8 if meta["value_domain"].get("scalarization") is not None else int(bit_depth)
    )
    meta["source"] = "center_crop"
    meta["note"] = (
        "image {}x{} exceeds the 4M-pixel server cap; "
        "analysed a direct centred {}x{} crop at native sampling; "
        "no stride downsampling was applied"
    ).format(w, h, cw, ch)
    return arr, meta


def _otsu_threshold(arr) -> float:
    """Otsu's method on a 256-bin histogram of arr, in its native units."""
    flat = arr.ravel()
    lo = float(flat.min())
    hi = float(flat.max())
    if hi <= lo:
        return lo
    hist, edges = np.histogram(flat, bins=256, range=(lo, hi))
    total = hist.sum()
    if total == 0:
        return lo
    probs = hist.astype(np.float64) / float(total)
    idx = np.arange(256, dtype=np.float64)
    omega = np.cumsum(probs)
    mu = np.cumsum(probs * idx)
    mu_t = mu[-1]
    denom = omega * (1.0 - omega)
    denom = np.where(denom <= 0, 1e-12, denom)
    sigma_b_sq = (mu_t * omega - mu) ** 2 / denom
    best = int(np.argmax(sigma_b_sq))
    return float(edges[best])


def _li_threshold(arr) -> float:
    """Li's iterative minimum cross-entropy threshold."""
    flat = arr.ravel().astype(np.float64)
    shift = 0.0
    mn = float(flat.min())
    if mn <= 0:
        shift = -mn + 1.0
        flat = flat + shift
    t = float(flat.mean())
    for _ in range(100):
        fg = flat[flat > t]
        bg = flat[flat <= t]
        if fg.size == 0 or bg.size == 0:
            break
        mf = float(fg.mean())
        mb = float(bg.mean())
        if mf <= 0 or mb <= 0 or mf == mb:
            break
        denom = math.log(mf) - math.log(mb)
        if denom == 0:
            break
        t_new = (mf - mb) / denom
        if abs(t_new - t) < 1e-3:
            t = t_new
            break
        t = t_new
    return float(t - shift)


def _triangle_threshold(arr, *, value_domain=None) -> float:
    """Approximate Triangle on samples, exact for a published byte domain."""
    flat = arr.ravel()
    lo = float(flat.min())
    hi = float(flat.max())
    histogram_range = (
        (0.0, 256.0) if _is_exact_byte_scalar_domain(value_domain) else (lo, hi)
    )
    hist, edges = np.histogram(flat, bins=256, range=histogram_range)
    occupied = np.flatnonzero(hist)
    # AutoThresholder.getThreshold() bypasses Triangle for a bilevel
    # histogram and returns the upper occupied bin minus one.
    if occupied.size == 2:
        return float(edges[int(occupied[1]) - 1])

    # Preserve Triangle's unusual empty/single-bin behavior. Its public
    # wrapper normalizes only a final -1 to zero.
    peak = int(np.argmax(hist))
    first = int(occupied[0]) if occupied.size else 0
    last = int(occupied[-1]) if occupied.size else 0
    line_end = first - 1 if first > 0 else first
    far_end = last + 1 if last < hist.size - 1 else last
    inverted = peak - line_end < far_end - peak
    working = hist[::-1].copy() if inverted else hist
    if inverted:
        line_end = hist.size - 1 - far_end
        peak = hist.size - 1 - peak
    if line_end == peak:
        split = line_end
    else:
        # Match ImageJ AutoThresholder 1.54c: orient the selected tail on the
        # left, extend its support by one zero bin when possible, scan strictly
        # from that endpoint toward the peak, then move the split down one bin.
        nx = float(working[peak])
        ny = float(line_end - peak)
        denom = math.sqrt(nx * nx + ny * ny)
        nx /= denom
        ny /= denom
        line_d = nx * line_end + ny * float(working[line_end])
        split = line_end
        split_distance = 0.0
        for index in range(line_end + 1, peak + 1):
            distance = nx * index + ny * float(working[index]) - line_d
            if distance > split_distance:
                split = index
                split_distance = distance
        split -= 1
    if inverted:
        split = hist.size - 1 - split
    if split == -1:
        split = 0
    return float(edges[split])


_THRESHOLD_METHODS = {
    "otsu": _otsu_threshold,
    "li": _li_threshold,
    "triangle": _triangle_threshold,
}


def _count_components_4(mask, min_size: int = 1) -> int:
    """Count 4-connected components of a boolean 2D mask whose size ≥ min_size.

    Iterative flood-fill mirroring find_bright_objects in
    agent/pixels.py; iterates only over foreground pixels so the
    worst case scales with the mask's true positives, not the full
    image.
    """
    h, w = mask.shape
    visited = np.zeros((h, w), dtype=bool)
    count = 0
    ys, xs = np.where(mask)
    for iy, ix in zip(ys.tolist(), xs.tolist()):
        if visited[iy, ix]:
            continue
        stack = [(ix, iy)]
        size = 0
        while stack:
            cx, cy = stack.pop()
            if cx < 0 or cx >= w or cy < 0 or cy >= h:
                continue
            if not mask[cy, cx] or visited[cy, cx]:
                continue
            visited[cy, cx] = True
            size += 1
            stack.append((cx + 1, cy))
            stack.append((cx - 1, cy))
            stack.append((cx, cy + 1))
            stack.append((cx, cy - 1))
        if size >= min_size:
            count += 1
    return count


@tool
def get_pixels_array(slice: int, region: list) -> dict:
    """Return at most 1,024 raw pixel values plus exact channel, Z-slice and frame metadata.

    Args:
        slice: Z-slice index, 1-based; use 0 for the currently displayed slice.
        region: Rectangle as [x, y, width, height], or empty list for the whole image.
    """
    try:
        slice_val = _exact_int(slice, "slice", 0)
    except (TypeError, ValueError):
        return _error("slice must be the exact integer 0 or a 1-based integer")

    info = _get_image_info()
    if "error" in info:
        return info
    image_width = int(info.get("width", 0))
    image_height = int(info.get("height", 0))
    if image_width <= 0 or image_height <= 0:
        return _error("active image has zero size")

    if slice_val > 0:
        slice_count = int(info.get("slices", 0))
        if slice_count <= 0:
            return _error("active image reported an invalid Z-slice count")
        if slice_val > slice_count:
            return _error(
                "requested Z-slice {} is outside active image range 1..{}".format(
                    slice_val, slice_count
                )
            )

    selected_slice = slice_val if slice_val > 0 else int(info["sliceStart"])
    kwargs: dict = _snapshot_payload(info, selected_slice)
    expected_x = 0
    expected_y = 0
    expected_width = image_width
    expected_height = image_height
    if isinstance(region, list) and len(region) == 4:
        try:
            rx = _exact_int(region[0], "region x")
            ry = _exact_int(region[1], "region y")
            rw = _exact_int(region[2], "region width", 1)
            rh = _exact_int(region[3], "region height", 1)
        except (TypeError, ValueError):
            return _error(
                "region must be [x, y, width, height] with exact integer values"
            )
        bounds_error = _validate_region_against_info(info, rx, ry, rw, rh)
        if bounds_error is not None:
            return bounds_error
        kwargs["x"] = rx
        kwargs["y"] = ry
        kwargs["width"] = rw
        kwargs["height"] = rh
        expected_x, expected_y = rx, ry
        expected_width, expected_height = rw, rh
    elif isinstance(region, list) and len(region) != 0:
        return _error("region must be [x, y, width, height] or an empty list")
    elif not isinstance(region, list):
        return _error("region must be [x, y, width, height] or an empty list")

    requested_values = expected_width * expected_height
    if requested_values > MAX_RAW_PIXEL_VALUES:
        return _raw_pixel_limit_error(requested_values)

    arr, meta = _decode_pixels(_safe_send("get_pixels", **kwargs))
    if arr is None:
        return meta
    if not _geometry_matches(
        meta, expected_x, expected_y, expected_width, expected_height
    ):
        return _error("Fiji returned clamped pixel geometry; image state changed")
    if not _metadata_matches_info(meta, info, selected_slice):
        return _error("active image snapshot or pixel plane changed during fetch")
    if not _slice_matches(meta, slice_val):
        return _error("Fiji returned a different pixel slice; image state changed")
    return _measurement_result(meta, pixels=arr.tolist())


@tool
def region_stats(x: int, y: int, width: int, height: int) -> dict:
    """Return rectangle statistics plus exact channel, Z-slice and frame metadata.

    Args:
        x: Left edge of the rectangle in pixels.
        y: Top edge of the rectangle in pixels.
        width: Rectangle width in pixels.
        height: Rectangle height in pixels.
    """
    try:
        x_i = _exact_int(x, "x")
        y_i = _exact_int(y, "y")
        w_i = _exact_int(width, "width", 1)
        h_i = _exact_int(height, "height", 1)
    except (TypeError, ValueError):
        return _error("x, y, width, height must all be exact integers")
    info = _get_image_info()
    if "error" in info:
        return info
    bounds_error = _validate_region_against_info(info, x_i, y_i, w_i, h_i)
    if bounds_error is not None:
        return bounds_error
    payload = _snapshot_payload(info)
    payload.update({"x": x_i, "y": y_i, "width": w_i, "height": h_i})
    arr, meta = _decode_pixels(_safe_send("get_pixels", **payload))
    if arr is None:
        return meta
    if not _geometry_matches(meta, x_i, y_i, w_i, h_i):
        return _error("Fiji returned clamped pixel geometry; image state changed")
    if not _metadata_matches_info(meta, info):
        return _error("active image snapshot or pixel plane changed during fetch")
    arr, meta = _scalarize_rgb24_measurement(arr, meta, info)
    if arr is None:
        return meta
    flat = arr.ravel()
    return _measurement_result(
        meta,
        x=x_i,
        y=y_i,
        width=w_i,
        height=h_i,
        count=int(flat.size),
        mean=float(flat.mean()),
        median=float(np.median(flat)),
        min=float(flat.min()),
        max=float(flat.max()),
        std=float(flat.std()),
    )


@tool
def line_profile(x1: int, y1: int, x2: int, y2: int) -> dict:
    """Return a bilinear line profile plus exact channel, Z-slice and frame metadata.

    Args:
        x1: Start point x in pixels.
        y1: Start point y in pixels.
        x2: End point x in pixels.
        y2: End point y in pixels.
    """
    try:
        x1_i = _exact_int(x1, "x1")
        y1_i = _exact_int(y1, "y1")
        x2_i = _exact_int(x2, "x2")
        y2_i = _exact_int(y2, "y2")
    except (TypeError, ValueError):
        return _error("x1, y1, x2, y2 must all be exact integers")
    info = _get_image_info()
    if "error" in info:
        return info
    image_width = int(info.get("width", 0))
    image_height = int(info.get("height", 0))
    if image_width <= 0 or image_height <= 0:
        return _error("active image has zero size")
    if not (
        0 <= x1_i < image_width
        and 0 <= x2_i < image_width
        and 0 <= y1_i < image_height
        and 0 <= y2_i < image_height
    ):
        return _error(
            "line endpoints must be inside active image bounds {}x{}".format(
                image_width, image_height
            )
        )
    bx = min(x1_i, x2_i)
    by = min(y1_i, y2_i)
    bw = max(x1_i, x2_i) - bx + 1
    bh = max(y1_i, y2_i) - by + 1
    if bw <= 0 or bh <= 0:
        return _error("line endpoints are degenerate")
    payload = _snapshot_payload(info)
    payload.update({"x": bx, "y": by, "width": bw, "height": bh})
    arr, meta = _decode_pixels(_safe_send("get_pixels", **payload))
    if arr is None:
        return meta
    if not _geometry_matches(meta, bx, by, bw, bh):
        return _error("Fiji returned clamped pixel geometry; image state changed")
    if not _metadata_matches_info(meta, info):
        return _error("active image snapshot or pixel plane changed during fetch")
    arr, meta = _scalarize_rgb24_measurement(arr, meta, info)
    if arr is None:
        return meta
    dx = float(x2_i - x1_i)
    dy = float(y2_i - y1_i)
    length = math.hypot(dx, dy)
    if length == 0:
        return _measurement_result(meta, profile=[float(arr[0, 0])])
    n = int(round(length)) + 1
    arr_h, arr_w = arr.shape
    profile: list = []
    for i in range(n):
        t = i / (n - 1) if n > 1 else 0.0
        fx = (x1_i + t * dx) - bx
        fy = (y1_i + t * dy) - by
        ix0 = int(math.floor(fx))
        iy0 = int(math.floor(fy))
        frx = fx - ix0
        fry = fy - iy0
        ix0c = max(0, min(ix0, arr_w - 1))
        iy0c = max(0, min(iy0, arr_h - 1))
        ix1c = max(0, min(ix0 + 1, arr_w - 1))
        iy1c = max(0, min(iy0 + 1, arr_h - 1))
        v00 = float(arr[iy0c, ix0c])
        v10 = float(arr[iy0c, ix1c])
        v01 = float(arr[iy1c, ix0c])
        v11 = float(arr[iy1c, ix1c])
        top = v00 * (1.0 - frx) + v10 * frx
        bot = v01 * (1.0 - frx) + v11 * frx
        profile.append(top * (1.0 - fry) + bot * fry)
    return _measurement_result(meta, profile=profile)


@tool
def quick_object_count(threshold_method: str) -> dict:
    """Return a rough bright-blob count plus exact channel, Z-slice and frame metadata.

    Args:
        threshold_method: One of "otsu", "li", "triangle".
    """
    if not isinstance(threshold_method, str):
        return _error("threshold_method must be a string: otsu, li, or triangle")
    key = threshold_method.strip().lower()
    if key not in _THRESHOLD_METHODS:
        return _error(
            "unknown threshold_method '{}'; use one of otsu, li, triangle".format(threshold_method)
        )
    arr, meta = _fetch_full_downsampled()
    if arr is None:
        return meta
    if key == "triangle":
        thr = _triangle_threshold(arr, value_domain=meta.get("value_domain"))
    else:
        thr = _THRESHOLD_METHODS[key](arr)
    mask = arr > thr
    count = _count_components_4(mask)
    factor = int(meta.get("downsample_factor", 1))
    result = _measurement_result(
        meta,
        count=int(count),
        threshold_method=key,
        threshold_value=float(thr),
        downsample_factor=factor,
    )
    if "note" in meta:
        result["note"] = meta["note"]
    return result


@tool
def histogram_summary() -> dict:
    """Return intensity percentiles and, for unsigned integer images, saturation plus exact plane metadata.

    Args:
        None.
    """
    arr, meta = _fetch_full_downsampled()
    if arr is None:
        return meta
    flat = arr.ravel()
    bit_depth = int(meta.get("bit_depth", 0))
    domain = meta["value_domain"]
    floor = domain.get("acquisition_min_raw")
    ceiling = domain.get("acquisition_max_raw")
    counts_exact = bool(meta["acquisition_limit_counts_exact"])
    min_count = meta["acquisition_min_count"]
    max_count = meta["acquisition_max_count"]
    limits_known = floor is not None and ceiling is not None
    saturated_fraction = (
        float(max_count) / float(meta["nPixels"])
        if counts_exact and max_count is not None and meta["nPixels"] > 0
        else None
    )
    minimum_fraction = (
        float(min_count) / float(meta["nPixels"])
        if counts_exact and min_count is not None and meta["nPixels"] > 0
        else None
    )
    out = _measurement_result(
        meta,
        p01=float(np.percentile(flat, 1)),
        p50=float(np.percentile(flat, 50)),
        p99=float(np.percentile(flat, 99)),
        saturated_fraction=saturated_fraction,
        acquisition_min_fraction=minimum_fraction,
        acquisition_minimum=floor,
        saturation_ceiling=ceiling,
        saturation_available=counts_exact and limits_known,
        value_domain=domain,
        bit_depth=bit_depth,
        downsample_factor=int(meta.get("downsample_factor", 1)),
    )
    if "note" in meta:
        out["note"] = meta["note"]
    return out


@tool
def count_bright_regions(min_intensity: int, min_area_pixels: int) -> dict:
    """Return a bright-region count plus exact channel, Z-slice and frame metadata.

    Args:
        min_intensity: Absolute intensity cutoff; pixels strictly above this are foreground.
        min_area_pixels: Minimum region size in pixels after downsampling.
    """
    try:
        cutoff = float(min_intensity)
        min_area = _exact_int(min_area_pixels, "min_area_pixels", 1)
    except (TypeError, ValueError):
        return _error(
            "min_intensity must be numeric and min_area_pixels an exact positive integer"
        )
    if not math.isfinite(cutoff):
        return _error("min_intensity must be finite")
    arr, meta = _fetch_full_downsampled()
    if arr is None:
        return meta
    mask = arr > cutoff
    count = _count_components_4(mask, min_size=min_area)
    factor = int(meta.get("downsample_factor", 1))
    result = _measurement_result(
        meta,
        count=int(count),
        min_intensity=cutoff,
        min_area_pixels=min_area,
        downsample_factor=factor,
    )
    if "note" in meta:
        result["note"] = meta["note"]
    return result
