"""Visual diff — catch silent macro failures by comparing before/after pixel stats.

Phase 7 of the Gemma agent plan. The chat loop runs normally; around
every destructive macro, run_macro auto-captures a thumbnail before and
after, computes a small set of pixel-change statistics, and flags
anything inconsistent with what the macro claims to do — Median
filters that touched 91% of pixels, Subtract Background calls that
raised mean intensity, Convert to Mask runs that did not produce a
two-value histogram.

Pixel-fetch and histogram-summary patterns follow tools_python.py (base64
float32 decode via get_pixels, numpy downsample). The before/after
comparison pattern — flat pixel-difference fraction, mean shift,
histogram distance — follows agent/image_diff.py in spirit, but stays
pure-numpy because Fiji can deliver 32-bit pixel data that a PNG
roundtrip would truncate.
"""

from __future__ import annotations

import base64
import math
import re

import numpy as np

from .registry import send


_MAX_LONG_EDGE = 512
_SERVER_PIXEL_CAP = 4_000_000
_MAX_SERVER_CROP_SIDE = int(math.isqrt(_SERVER_PIXEL_CAP))
_HIST_BINS = 64


# Operations that modify pixel values on the active image. Substring /
# word-boundary match is deliberately loose: the goal is to err on the
# side of running a diff, not to parse macro syntax. setAutoThreshold
# starts with set so \bsetAutoThreshold\b is needed separately from the
# \bThreshold\b rule; \b only matches at word boundaries so "Measure"
# will never be caught by \bThreshold\b.
_DESTRUCTIVE_PATTERNS = [
    r'Subtract Background',
    r'Gaussian Blur',
    r'\bMedian\b',
    r'Convert to Mask',
    r'\bThreshold\b',
    r'\bsetAutoThreshold\b',
    r'\bsetOption\b',
    r'Enhance Contrast',
    r'\bSmooth\b',
    r'\bSharpen\b',
    r'\bInvert\b',
    r'Make Binary',
    r'run\s*\(\s*"8-bit"',
    r'run\s*\(\s*"16-bit"',
    r'run\s*\(\s*"32-bit"',
]

_WRITE_PATTERNS = [
    r'\bsaveAs\s*\(',
    r'\bIJ\.saveAs\s*\(',
    r'\bFile\.saveString\s*\(',
    r'\bFile\.append\s*\(',
    r'(?<![\w.])save\s*\(',
    r'run\s*\(\s*"Tiff\.\.\."',
]


def is_destructive(code: str) -> bool:
    """Return True when the macro contains an operation that rewrites pixel values.

    Text sniff only — no Fiji round-trip. Matches the destructive-ops list in
    the Phase 7 brief plus a Duplicate-followed-by-write pairing.

    Args:
        code: ImageJ macro source to inspect.
    """
    if not isinstance(code, str) or not code:
        return False
    for pat in _DESTRUCTIVE_PATTERNS:
        if re.search(pat, code):
            return True
    if re.search(r'run\s*\(\s*"Duplicate\.\.\."', code):
        for wpat in _WRITE_PATTERNS:
            if re.search(wpat, code):
                return True
    return False


# --- thumbnail fetch ------------------------------------------------------


def _safe_send(command: str, **payload) -> dict:
    """Send a TCP command and fold any exception into an ok=False reply.

    Args:
        command: TCP command name.
        **payload: Flat keyword arguments sent alongside the command.
    """
    try:
        return send(command, **payload)
    except Exception as exc:
        return {"ok": False, "error": "Fiji TCP send failed: {}".format(exc)}


def _error_text(resp, fallback: str) -> str:
    error = resp.get("error") if isinstance(resp, dict) else None
    if isinstance(error, dict):
        code = str(error.get("code") or "").strip()
        message = str(error.get("message") or "").strip()
        if code and message:
            return "{}: {}".format(code, message)
        return code or message or fallback
    return str(error or fallback)


def _exact_int(value, name: str, minimum: int = 1) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError("{} must be an exact integer >= {}".format(name, minimum))
    return value


def _normalized_source_value_domain(domain):
    """Validate and normalize a non-RGB raw pixel domain for comparisons."""
    required = {
        "representation", "pixel_type", "signed", "density_calibrated",
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    }
    if not isinstance(domain, dict) or not required.issubset(domain):
        return None
    if (
        domain.get("representation") != "raw"
        or domain.get("pixel_type") not in (
            "uint8", "uint16", "float32", "indexed8", "unknown"
        )
        or not (
            domain.get("signed") is None
            or isinstance(domain.get("signed"), bool)
        )
        or not isinstance(domain.get("density_calibrated"), bool)
        or domain.get("scalarization") is not None
    ):
        return None
    normalized = {
        "representation": "raw",
        "pixel_type": domain["pixel_type"],
        "signed": domain["signed"],
        "density_calibrated": domain["density_calibrated"],
        "scalarization": None,
    }
    for key in (
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    ):
        value = domain[key]
        if value is not None:
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                return None
            value = float(value)
            if not math.isfinite(value):
                return None
        normalized[key] = value
    return normalized


def _snapshot_from_info(info: dict):
    """Validate one active-image snapshot and its exact C/Z/T plane."""
    try:
        image_id = info["image_id"]
        if not isinstance(image_id, str) or not image_id.strip():
            raise ValueError("image_id must be a non-empty string")
        snapshot = {
            "image_id": image_id.strip(),
            "image_revision": _exact_int(info["image_revision"], "image_revision"),
            "display_revision": _exact_int(info["display_revision"], "display_revision"),
            "width": _exact_int(info["width"], "width"),
            "height": _exact_int(info["height"], "height"),
            "channel": _exact_int(info["channel"], "channel"),
            "sliceStart": _exact_int(info["sliceStart"], "sliceStart"),
            "sliceEnd": _exact_int(info["sliceEnd"], "sliceEnd"),
            "sliceAxis": str(info["sliceAxis"]),
            "frame": _exact_int(info["frame"], "frame"),
            "channels": _exact_int(info["channels"], "channels"),
            "slices": _exact_int(info["slices"], "slices"),
            "frames": _exact_int(info["frames"], "frames"),
        }
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("get_image_info returned incomplete snapshot metadata") from exc
    if (
        not snapshot["image_id"]
        or snapshot["sliceAxis"] != "Z"
        or snapshot["sliceStart"] != snapshot["sliceEnd"]
        or not 1 <= snapshot["channel"] <= snapshot["channels"]
        or not 1 <= snapshot["sliceStart"] <= snapshot["slices"]
        or not 1 <= snapshot["frame"] <= snapshot["frames"]
    ):
        raise ValueError("get_image_info returned inconsistent snapshot metadata")
    return snapshot


def _snapshot_payload(snapshot: dict) -> dict:
    return {
        "image_id": snapshot["image_id"],
        "image_revision": snapshot["image_revision"],
        "display_revision": snapshot["display_revision"],
        "channel": snapshot["channel"],
        "slice": snapshot["sliceStart"],
        "frame": snapshot["frame"],
        "force": True,
    }


def _metadata_matches_snapshot(meta: dict, snapshot: dict) -> bool:
    try:
        return (
            isinstance(meta["image_id"], str)
            and meta["image_id"].strip() == snapshot["image_id"]
            and _exact_int(meta["image_revision"], "image_revision")
            == snapshot["image_revision"]
            and _exact_int(meta["display_revision"], "display_revision")
            == snapshot["display_revision"]
            and meta["sliceAxis"] == "Z"
            and _exact_int(meta["sliceStart"], "sliceStart")
            == snapshot["sliceStart"]
            and _exact_int(meta["sliceEnd"], "sliceEnd")
            == snapshot["sliceStart"]
            and _exact_int(meta["channel"], "channel") == snapshot["channel"]
            and _exact_int(meta["frame"], "frame") == snapshot["frame"]
            and _exact_int(meta["channels"], "channels") == snapshot["channels"]
            and _exact_int(meta["slices"], "slices") == snapshot["slices"]
            and _exact_int(meta["frames"], "frames") == snapshot["frames"]
        )
    except (KeyError, TypeError, ValueError):
        return False


def _bit_depth_from_type(type_str: str) -> int:
    """Parse an ImageJ type label like '8-bit' / 'GRAY16' into an integer bit depth.

    Args:
        type_str: The "type" field from get_image_info; may be missing or RGB.
    """
    if not isinstance(type_str, str):
        return 0
    s = type_str.strip().lower()
    if s.startswith("8-") or "gray8" in s:
        return 8
    if s.startswith("16-") or "gray16" in s:
        return 16
    if s.startswith("32-") or "gray32" in s or "float" in s:
        return 32
    return 0


def _is_rgb_image_type(value) -> bool:
    if not isinstance(value, str):
        return False
    normalized = " ".join(value.strip().casefold().split())
    return normalized in ("rgb", "rgb color", "24-bit")


def _ceiling_for(bit_depth: int, flat: np.ndarray) -> float:
    """Return the saturation ceiling for a given bit depth.

    Falls back to the flattened array's max when the bit depth is unknown
    (e.g. RGB images, 32-bit floats). Never returns zero.

    Args:
        bit_depth: 8, 16, 32, or 0 when the type is unrecognised.
        flat: Flattened pixel array used only when bit_depth is not 8 or 16.
    """
    if bit_depth == 8:
        return 255.0
    if bit_depth == 16:
        return 65535.0
    if flat.size == 0:
        return 1.0
    m = float(flat.max())
    return m if m > 0.0 else 1.0


def _decode_pixels(resp):
    """Decode a get_pixels reply into (float32 2D ndarray, meta dict) or (None, err).

    Args:
        resp: Raw TCP reply from a get_pixels call.
    """
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None, {"error": _error_text(resp, "get_pixels failed")}
    result = resp.get("result")
    if not isinstance(result, dict):
        return None, {"error": "get_pixels result is not an object"}
    b64 = result.get("data")
    if not isinstance(b64, str) or not b64:
        return None, {"error": "get_pixels reply missing data field"}
    try:
        raw = base64.b64decode(b64, validate=True)
    except (ValueError, TypeError) as exc:
        return None, {"error": "base64 decode failed: {}".format(exc)}
    try:
        w = _exact_int(result["width"], "width")
        h = _exact_int(result["height"], "height")
        n_pixels = _exact_int(result["nPixels"], "nPixels")
        slice_count = _exact_int(result["sliceCount"], "sliceCount")
        x = _exact_int(result["x"], "x", 0)
        y = _exact_int(result["y"], "y", 0)
        value_domain = result["value_domain"]
        if not isinstance(value_domain, dict):
            raise ValueError("value_domain must be an object")
        meta = {
            key: result[key]
            for key in (
                "image_id", "image_revision", "display_revision",
                "sliceStart", "sliceEnd", "sliceAxis", "channel", "frame",
                "channels", "slices", "frames",
            )
        }
    except (KeyError, TypeError, ValueError) as exc:
        return None, {"error": "get_pixels returned malformed metadata: {}".format(exc)}
    if (
        slice_count != 1
        or n_pixels != w * h
        or len(raw) != n_pixels * 4
        or value_domain.get("representation") != "raw"
    ):
        return None, {"error": "get_pixels returned inconsistent dimensions/value domain"}
    plane = np.frombuffer(raw, dtype="<f4").reshape((h, w))
    if not np.isfinite(plane).all():
        return None, {"error": "get_pixels returned non-finite values"}
    meta.update({
        "x": x,
        "y": y,
        "width": w,
        "height": h,
        "nPixels": n_pixels,
        "sliceCount": slice_count,
        "type": result.get("type", ""),
        "value_domain": value_domain,
    })
    return plane, meta


def _rgb_scalarization(meta: dict, snapshot: dict):
    """Fetch the exact-plane histogram contract used to scalarize RGB pixels."""
    domain = meta.get("value_domain") if isinstance(meta, dict) else None
    packed_source = (
        isinstance(meta, dict)
        and (
            _is_rgb_image_type(meta.get("type"))
            or (isinstance(domain, dict) and domain.get("pixel_type") == "rgb24")
        )
    )
    if not packed_source:
        return None, None
    if not isinstance(domain, dict) or domain.get("pixel_type") != "rgb24":
        return None, {"error": "RGB get_pixels returned an unsafe source domain"}
    required_source_fields = {
        "representation", "pixel_type", "signed", "density_calibrated",
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    }
    if (
        not required_source_fields.issubset(domain)
        or domain.get("representation") != "raw"
        or domain.get("signed") is not None
        or domain.get("density_calibrated") is not False
        or domain.get("acquisition_min_raw") is not None
        or domain.get("acquisition_max_raw") is not None
        or domain.get("acquisition_min_calibrated") is not None
        or domain.get("acquisition_max_calibrated") is not None
        or domain.get("scalarization") is not None
    ):
        return None, {"error": "RGB get_pixels returned an unsafe source domain"}
    payload = _snapshot_payload(snapshot)
    payload["scope"] = "full_plane"
    response = _safe_send("get_histogram", **payload)
    if not isinstance(response, dict) or not response.get("ok"):
        return None, {
            "error": "RGB scalarization histogram failed: {}".format(
                _error_text(response, "unknown error")
            )
        }
    result = response.get("result")
    if (
        not isinstance(result, dict)
        or result.get("scope") != "full_plane"
        or not _metadata_matches_snapshot(result, snapshot)
    ):
        return None, {"error": "RGB histogram did not match the pixel snapshot/plane"}
    histogram_domain = result.get("value_domain")
    scalarization = (
        histogram_domain.get("scalarization")
        if isinstance(histogram_domain, dict)
        else None
    )
    if (
        not isinstance(histogram_domain, dict)
        or histogram_domain.get("representation") != "raw"
        or histogram_domain.get("pixel_type") != "uint8"
        or histogram_domain.get("signed") is not False
        or histogram_domain.get("density_calibrated") is not False
        or histogram_domain.get("acquisition_min_raw") != 0.0
        or histogram_domain.get("acquisition_max_raw") != 255.0
        or histogram_domain.get("acquisition_min_calibrated") is not None
        or histogram_domain.get("acquisition_max_calibrated") is not None
        or not isinstance(scalarization, dict)
        or scalarization.get("method") != "imagej_weighted_rgb_intensity"
        or scalarization.get("source_pixel_type") != "rgb24"
        or scalarization.get("rounding") != "nearest_integer_half_up"
        or not isinstance(scalarization.get("weights"), dict)
    ):
        return None, {"error": "RGB histogram returned unsafe scalarization metadata"}
    normalized_weights = {}
    for component in ("red", "green", "blue"):
        value = scalarization["weights"].get(component)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return None, {"error": "RGB histogram returned unsafe scalarization weights"}
        value = float(value)
        if not math.isfinite(value) or value < 0.0:
            return None, {"error": "RGB histogram returned unsafe scalarization weights"}
        normalized_weights[component] = value
    if not math.isclose(
        sum(normalized_weights.values()), 1.0, rel_tol=0.0, abs_tol=1e-9
    ):
        return None, {"error": "RGB histogram returned unsafe scalarization weights"}
    try:
        histogram_pixels = _exact_int(result["nPixels"], "nPixels")
    except (KeyError, TypeError, ValueError):
        return None, {"error": "RGB histogram returned invalid pixel cardinality"}
    if histogram_pixels != snapshot["width"] * snapshot["height"]:
        return None, {
            "error": "RGB full-plane histogram pixel count did not match the image snapshot"
        }
    normalized = {
        "method": scalarization["method"],
        "source_pixel_type": scalarization["source_pixel_type"],
        "rounding": scalarization["rounding"],
        "weights": normalized_weights,
    }
    normalized_domain = {
        "representation": "raw",
        "pixel_type": "uint8",
        "signed": False,
        "density_calibrated": False,
        "acquisition_min_raw": 0.0,
        "acquisition_max_raw": 255.0,
        "acquisition_min_calibrated": None,
        "acquisition_max_calibrated": None,
        "scalarization": normalized,
    }
    return {
        "scalarization": normalized,
        "value_domain": normalized_domain,
    }, None


def _scalarize_rgb24(arr: np.ndarray, scalarization: dict):
    samples = np.asarray(arr, dtype=np.float64)
    if not np.equal(samples, np.floor(samples)).all():
        return None, {"error": "RGB get_pixels values were not integral rgb24 samples"}
    canonical = (samples >= 0) & (samples <= 0x00ffffff)
    legacy_signed = (samples >= -0x01000000) & (samples <= -1)
    if not np.all(canonical | legacy_signed):
        return None, {"error": "RGB get_pixels values were outside the rgb24 domain"}
    packed = np.bitwise_and(samples.astype(np.int64), 0x00ffffff)
    weights = scalarization["weights"]
    red = ((packed >> 16) & 0xff).astype(np.float64)
    green = ((packed >> 8) & 0xff).astype(np.float64)
    blue = (packed & 0xff).astype(np.float64)
    intensity = np.floor(
        red * weights["red"]
        + green * weights["green"]
        + blue * weights["blue"]
        + 0.5
    )
    if np.any(intensity < 0) or np.any(intensity > 255):
        return None, {"error": "RGB scalar intensities fell outside uint8"}
    return intensity.astype(np.float32), None


def _attach_analysis_provenance(meta: dict, snapshot: dict, factor: int, rgb_contract):
    """Attach stable plane identity and a normalized numeric-domain contract."""
    if rgb_contract is not None:
        meta["rgb_scalarization"] = rgb_contract["scalarization"]
        meta["analysis_value_domain"] = rgb_contract["value_domain"]
    else:
        normalized = _normalized_source_value_domain(meta.get("value_domain"))
        if normalized is None:
            return {"error": "get_pixels returned an unsafe analysis value domain"}
        meta["analysis_value_domain"] = normalized
    meta["plane_identity"] = {
        "image_id": snapshot["image_id"],
        "channel": snapshot["channel"],
        "slice": snapshot["sliceStart"],
        "frame": snapshot["frame"],
        "image_width": snapshot["width"],
        "image_height": snapshot["height"],
        "sample_x": meta["x"],
        "sample_y": meta["y"],
        "sample_width": meta["width"],
        "sample_height": meta["height"],
        "downsample_factor": int(factor),
    }
    return None


def _fetch_thumbnail_array(max_side: int):
    """Fetch the active image and numpy-downsample so the long edge is at most max_side.

    Args:
        max_side: Maximum length of the long edge after downsampling.
    """
    resp = _safe_send("get_image_info", force=True)
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None, {"error": _error_text(resp, "get_image_info failed")}
    info = resp.get("result")
    if not isinstance(info, dict):
        return None, {"error": "get_image_info result is not an object"}
    try:
        snapshot = _snapshot_from_info(info)
        w = _exact_int(info["width"], "width")
        h = _exact_int(info["height"], "height")
    except (KeyError, TypeError, ValueError) as exc:
        return None, {"error": str(exc)}
    if w <= 0 or h <= 0:
        return None, {"error": "active image has zero size"}
    bit_depth = _bit_depth_from_type(info.get("type", ""))
    long_edge = max(w, h)
    factor = 1 if long_edge <= max_side else (long_edge + max_side - 1) // max_side

    if w * h <= _SERVER_PIXEL_CAP:
        arr, meta = _decode_pixels(
            _safe_send("get_pixels", **_snapshot_payload(snapshot))
        )
        if arr is None:
            return None, meta
        if (
            not _metadata_matches_snapshot(meta, snapshot)
            or (meta["x"], meta["y"], meta["width"], meta["height"])
            != (0, 0, w, h)
        ):
            return None, {"error": "get_pixels did not match the requested snapshot/geometry"}
        rgb_contract, scalar_error = _rgb_scalarization(meta, snapshot)
        if scalar_error is not None:
            return None, scalar_error
        if rgb_contract is not None:
            arr, scalar_error = _scalarize_rgb24(
                arr, rgb_contract["scalarization"]
            )
            if scalar_error is not None:
                return None, scalar_error
            bit_depth = 8
        if factor > 1:
            arr = arr[::factor, ::factor]
        meta["bit_depth"] = int(bit_depth)
        meta["downsample_factor"] = int(factor)
        provenance_error = _attach_analysis_provenance(
            meta, snapshot, factor, rgb_contract
        )
        if provenance_error is not None:
            return None, provenance_error
        return arr, meta

    crop = min(max_side, w, h, _MAX_SERVER_CROP_SIDE)
    cx = max(0, (w - crop) // 2)
    cy = max(0, (h - crop) // 2)
    cw = min(crop, w - cx)
    ch = min(crop, h - cy)
    payload = _snapshot_payload(snapshot)
    payload.update({"x": cx, "y": cy, "width": cw, "height": ch})
    arr, meta = _decode_pixels(_safe_send("get_pixels", **payload))
    if arr is None:
        return None, meta
    if (
        not _metadata_matches_snapshot(meta, snapshot)
        or (meta["x"], meta["y"], meta["width"], meta["height"])
        != (cx, cy, cw, ch)
    ):
        return None, {"error": "get_pixels did not match the requested snapshot/geometry"}
    rgb_contract, scalar_error = _rgb_scalarization(meta, snapshot)
    if scalar_error is not None:
        return None, scalar_error
    if rgb_contract is not None:
        arr, scalar_error = _scalarize_rgb24(
            arr, rgb_contract["scalarization"]
        )
        if scalar_error is not None:
            return None, scalar_error
        bit_depth = 8
    meta["bit_depth"] = int(bit_depth)
    # This is a direct server crop.  Keep the crop geometry in plane_identity
    # and do not describe its relationship to the full image as a pixel stride.
    crop_factor = 1
    meta["downsample_factor"] = crop_factor
    provenance_error = _attach_analysis_provenance(
        meta, snapshot, crop_factor, rgb_contract
    )
    if provenance_error is not None:
        return None, provenance_error
    meta["note"] = (
        "image {}x{} exceeds the 4M-pixel server cap; "
        "analysed a direct centred {}x{} crop at native sampling; "
        "no stride downsampling was applied"
    ).format(w, h, cw, ch)
    return arr, meta


def capture_thumbnail() -> dict:
    """Fetch a downsampled copy of the active image and return its histogram summary.

    The returned dict is shaped for diff_report consumption. It also carries a
    private "_pixels" float32 array so diff_report can compute pixel-wise
    change fractions; the caller is expected to strip that key before
    surfacing the dict to the agent or serialising it to JSON.

    Args:
        None.
    """
    arr, meta = _fetch_thumbnail_array(_MAX_LONG_EDGE)
    if arr is None:
        return {"error": meta.get("error", "thumbnail capture failed")}

    flat = arr.ravel().astype(np.float64, copy=False)
    bit_depth = int(meta.get("bit_depth", 0))
    ceiling = _ceiling_for(bit_depth, flat)
    if bit_depth in (8, 16):
        lo = 0.0
        hi = ceiling
    else:
        lo = float(flat.min()) if flat.size else 0.0
        hi = float(flat.max()) if flat.size else 1.0
        if hi <= lo:
            hi = lo + 1.0
    hist, _ = np.histogram(flat, bins=_HIST_BINS, range=(lo, hi))
    total = float(hist.sum())
    bins_norm = (hist.astype(np.float64) / total).tolist() if total > 0 else [0.0] * _HIST_BINS
    saturated_frac = float((flat >= ceiling).mean()) if flat.size else 0.0

    summary = {
        "bins": bins_norm,
        "mean": float(flat.mean()) if flat.size else 0.0,
        "median": float(np.median(flat)) if flat.size else 0.0,
        "min": float(flat.min()) if flat.size else 0.0,
        "max": float(flat.max()) if flat.size else 0.0,
        "saturated_frac": saturated_frac,
        "shape": [int(arr.shape[0]), int(arr.shape[1])],
        "bit_depth": bit_depth,
        "ceiling": float(ceiling),
        "_pixels": arr.astype(np.float32, copy=False),
    }
    if "rgb_scalarization" in meta:
        summary["_rgb_scalarization"] = meta["rgb_scalarization"]
    summary["_analysis_value_domain"] = meta["analysis_value_domain"]
    summary["_plane_identity"] = meta["plane_identity"]
    if "note" in meta:
        summary["note"] = meta["note"]
    return summary


# --- comparison -----------------------------------------------------------


def _bit_range(before: dict, after: dict) -> float:
    """Return the intensity span used to derive a 1%-of-range change threshold.

    Args:
        before: Thumbnail dict from before the macro ran.
        after: Thumbnail dict from after the macro ran.
    """
    bd_before = int(before.get("bit_depth", 0))
    bd_after = int(after.get("bit_depth", 0))
    bd = bd_before or bd_after
    if bd == 8:
        return 255.0
    if bd == 16:
        return 65535.0
    if bd == 32:
        span = max(float(before.get("max", 0.0)), float(after.get("max", 0.0)))
        return span if span > 0.0 else 1.0
    span = max(float(before.get("max", 0.0)), float(after.get("max", 0.0)))
    return span if span > 0.0 else 1.0


def _pixel_change_fraction(before: dict, after: dict, tol: float):
    """Return the fraction of pixels whose absolute difference exceeds tol.

    Returns None when the two thumbnails have incompatible shapes or when
    either pixel array is missing.

    Args:
        before: Thumbnail dict carrying a "_pixels" ndarray.
        after: Thumbnail dict carrying a "_pixels" ndarray.
        tol: Absolute tolerance; pixels whose |after - before| exceeds tol count.
    """
    bp = before.get("_pixels")
    ap = after.get("_pixels")
    if not isinstance(bp, np.ndarray) or not isinstance(ap, np.ndarray):
        return None
    if bp.shape != ap.shape:
        return None
    if bp.size == 0:
        return 0.0
    diff = np.abs(bp.astype(np.float64) - ap.astype(np.float64))
    return float((diff > tol).mean())


def _histogram_distance(before: dict, after: dict):
    """Total-variation distance between two already-normalised bin arrays.

    Returns None when one of the arrays is missing or has a different length.

    Args:
        before: Thumbnail dict with a "bins" list.
        after: Thumbnail dict with a "bins" list.
    """
    bb = np.asarray(before.get("bins", []), dtype=np.float64)
    ba = np.asarray(after.get("bins", []), dtype=np.float64)
    if bb.size == 0 or ba.size == 0 or bb.size != ba.size:
        return None
    s_bb = bb.sum() or 1.0
    s_ba = ba.sum() or 1.0
    return float(0.5 * np.abs(bb / s_bb - ba / s_ba).sum())


def _count_nonzero_bins(after: dict) -> int:
    """Return the number of non-empty histogram bins in an after-thumbnail.

    Args:
        after: Thumbnail dict with a "bins" list.
    """
    arr = np.asarray(after.get("bins", []), dtype=np.float64)
    if arr.size == 0:
        return 0
    return int(np.count_nonzero(arr > 1e-9))


def _fmt_pct(value) -> str:
    """Format a 0..1 fraction as a percent string, or 'unknown' when None.

    Args:
        value: A float in [0, 1], or None.
    """
    if value is None:
        return "unknown"
    return "{:.1%}".format(value)


def _macro_likely_created_new_canvas(code: str) -> bool:
    """True when the macro likely switched the active image to a new canvas."""
    if not isinstance(code, str):
        return False
    patterns = (
        r'run\s*\(\s*"Duplicate\.\.\."',
        r'\bnewImage\s*\(',
        r'\bimageCalculator\s*\(\s*"[^"]*\bcreate\b',
    )
    return any(re.search(pat, code, flags=re.IGNORECASE) for pat in patterns)


def diff_report(before: dict, after: dict, code: str, new_images=None) -> dict:
    """Compute before/after pixel-change metrics and flag inconsistencies with macro intent.

    Always returns a three-key dict {"consistent": bool, "reason": str,
    "numbers": dict}. When no plausibility rule matches the macro, the
    report is marked consistent with a bland reason string so the caller
    can still show the numbers.

    Args:
        before: Thumbnail dict from capture_thumbnail() taken before the macro ran.
        after: Thumbnail dict from capture_thumbnail() taken after the macro ran.
        code: The macro source; used to pick which plausibility rule applies.
        new_images: Optional list of image titles the macro created. When the
            macro made two or more new images, the "after" thumbnail is of a
            different canvas than the "before" thumbnail, so before/after
            filter-plausibility comparisons are meaningless and get suppressed.
    """
    if not isinstance(before, dict) or not isinstance(after, dict):
        return {"consistent": True, "reason": "thumbnails unavailable", "numbers": {}}
    if "error" in before or "error" in after:
        return {
            "consistent": True,
            "reason": "thumbnail capture failed; diff skipped",
            "numbers": {},
        }
    before_identity = before.get("_plane_identity")
    after_identity = after.get("_plane_identity")
    before_domain = before.get("_analysis_value_domain")
    after_domain = after.get("_analysis_value_domain")
    if not all(
        isinstance(value, dict)
        for value in (
            before_identity, after_identity, before_domain, after_domain
        )
    ):
        return {
            "consistent": True,
            "reason": "thumbnail provenance unavailable; diff skipped",
            "numbers": {},
        }
    if before_identity != after_identity:
        return {
            "consistent": True,
            "reason": "thumbnail image/plane geometry differs; diff skipped",
            "numbers": {},
        }
    before_scalarization = before.get("_rgb_scalarization")
    after_scalarization = after.get("_rgb_scalarization")
    if (
        (before_scalarization is None) != (after_scalarization is None)
        or (
            before_scalarization is not None
            and before_scalarization != after_scalarization
        )
    ):
        return {
            "consistent": True,
            "reason": "thumbnail RGB scalarization domains differ; diff skipped",
            "numbers": {},
        }
    if before_domain != after_domain:
        return {
            "consistent": True,
            "reason": "thumbnail analysis value domains differ; diff skipped",
            "numbers": {},
        }

    bit_range = _bit_range(before, after)
    tol = 0.01 * bit_range
    pix_frac = _pixel_change_fraction(before, after, tol)
    mean_shift = float(after.get("mean", 0.0)) - float(before.get("mean", 0.0))
    hist_dist = _histogram_distance(before, after)

    numbers = {
        "pixel_change_fraction": pix_frac,
        "mean_intensity_shift": mean_shift,
        "histogram_distance": hist_dist,
        "bit_range": bit_range,
        "change_tolerance": tol,
    }

    consistent = True
    reason = "no plausibility rule matched this macro"

    code_str = code if isinstance(code, str) else ""

    if re.search(r'Convert to Mask|Make Binary', code_str):
        nz = _count_nonzero_bins(after)
        numbers["nonzero_bins_after"] = nz
        # If the active image is provably untouched (no pixels changed, no
        # mean shift, no histogram drift) then the Convert to Mask in the
        # macro must have hit a different image (typically a Duplicate that
        # was close()d before this diff ran). The "after-thumbnail has N
        # non-empty bins" complaint is then comparing the original grayscale
        # to itself — a false positive that misled Gemma through several
        # iterations in the filter-shootout transcript.
        active_untouched = (
            pix_frac == 0.0
            and abs(mean_shift) < 1e-9
            and (hist_dist is None or hist_dist == 0.0)
        )
        if active_untouched:
            reason = (
                "Convert to Mask did not touch the active image (likely "
                "applied to a temp that was closed); cannot verify mask "
                "histogram post-hoc."
            )
        elif nz > 4:
            consistent = False
            reason = (
                "Convert to Mask / Make Binary should leave a two-value "
                "histogram but the after-thumbnail has {} non-empty bins."
            ).format(nz)
        else:
            reason = "Convert to Mask produced a near-two-value histogram as expected."
    elif re.search(r'Median|Gaussian Blur', code_str):
        created_new_canvas = (
            isinstance(new_images, (list, tuple))
            and len(new_images) >= 1
            and _macro_likely_created_new_canvas(code_str)
        )
        if created_new_canvas:
            shown = [str(t) for t in new_images[:5] if str(t).strip()]
            if shown:
                reason = (
                    "macro created {} new image{} ({}); before/after active-image "
                    "comparison skipped — filter target may have changed between "
                    "snapshots."
                ).format(
                    len(new_images),
                    "" if len(new_images) == 1 else "s",
                    ", ".join(shown),
                )
            else:
                reason = (
                    "macro created a new image; before/after active-image "
                    "comparison skipped — filter target may have changed between "
                    "snapshots."
                )
        elif pix_frac is not None and pix_frac > 0.40:
            consistent = False
            reason = (
                "Median / Gaussian Blur changed {} of pixels — expected "
                "under 40%. Check the sigma/radius or whether the right "
                "image was active."
            ).format(_fmt_pct(pix_frac))
        else:
            reason = "smoothing pixel-change fraction within expected bounds."
    elif re.search(r'Subtract Background', code_str):
        fail_mean = mean_shift >= 0.0
        fail_change = pix_frac is not None and pix_frac > 0.50
        if fail_mean or fail_change:
            consistent = False
            reason = (
                "Subtract Background should lower mean intensity and "
                "change under 50% of pixels; got mean shift {:+.2f} "
                "and {} of pixels changed."
            ).format(mean_shift, _fmt_pct(pix_frac))
        else:
            reason = "Subtract Background shifted mean downwards with bounded pixel change."
    elif re.search(r'Enhance Contrast', code_str) and re.search(r'normalize', code_str):
        if pix_frac is not None and pix_frac < 0.80:
            consistent = False
            reason = (
                "Enhance Contrast with normalize should rewrite almost "
                "every pixel but only {} changed."
            ).format(_fmt_pct(pix_frac))
        elif abs(mean_shift) > 0.30 * bit_range:
            consistent = False
            reason = (
                "Enhance Contrast normalize produced a large mean shift "
                "{:+.2f} (> 30% of bit range); downstream measurements "
                "will be distorted."
            ).format(mean_shift)
        else:
            reason = "Enhance Contrast normalize rewrote pixels with a bounded mean shift."

    return {"consistent": consistent, "reason": reason, "numbers": numbers}
