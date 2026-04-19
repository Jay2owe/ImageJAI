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
        err = resp.get("error") if isinstance(resp, dict) else "no reply from Fiji"
        return None, {"error": err or "get_pixels failed"}
    result = resp.get("result") or {}
    b64 = result.get("data")
    if not isinstance(b64, str) or not b64:
        return None, {"error": "get_pixels reply missing data field"}
    try:
        raw = base64.b64decode(b64)
    except (ValueError, TypeError) as exc:
        return None, {"error": "base64 decode failed: {}".format(exc)}
    w = int(result.get("width", 0))
    h = int(result.get("height", 0))
    if w <= 0 or h <= 0:
        return None, {"error": "get_pixels returned zero-size region"}
    plane = np.frombuffer(raw, dtype="<f4", count=w * h).reshape((h, w))
    meta = {
        "width": w,
        "height": h,
        "type": result.get("type", ""),
    }
    return plane, meta


def _fetch_thumbnail_array(max_side: int):
    """Fetch the active image and numpy-downsample so the long edge is at most max_side.

    Args:
        max_side: Maximum length of the long edge after downsampling.
    """
    resp = _safe_send("get_image_info")
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = resp.get("error") if isinstance(resp, dict) else "no reply from Fiji"
        return None, {"error": err or "get_image_info failed"}
    info = resp.get("result") or {}
    w = int(info.get("width", 0))
    h = int(info.get("height", 0))
    if w <= 0 or h <= 0:
        return None, {"error": "active image has zero size"}
    bit_depth = _bit_depth_from_type(info.get("type", ""))
    long_edge = max(w, h)
    factor = 1 if long_edge <= max_side else (long_edge + max_side - 1) // max_side

    if w * h <= _SERVER_PIXEL_CAP:
        arr, meta = _decode_pixels(_safe_send("get_pixels"))
        if arr is None:
            return None, meta
        if factor > 1:
            arr = arr[::factor, ::factor]
        meta["bit_depth"] = int(bit_depth)
        meta["downsample_factor"] = int(factor)
        return arr, meta

    crop = min(max_side, w, h, _MAX_SERVER_CROP_SIDE)
    cx = max(0, (w - crop) // 2)
    cy = max(0, (h - crop) // 2)
    cw = min(crop, w - cx)
    ch = min(crop, h - cy)
    arr, meta = _decode_pixels(_safe_send("get_pixels", x=cx, y=cy, width=cw, height=ch))
    if arr is None:
        return None, meta
    meta["bit_depth"] = int(bit_depth)
    meta["downsample_factor"] = int(factor)
    meta["note"] = (
        "image {}x{} exceeds the 4M-pixel server cap; "
        "analysed a centred {}x{} crop instead of a full-image downsample"
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
