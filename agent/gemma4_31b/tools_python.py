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
import math
import socket

import numpy as np

from .registry import send, tool


_MAX_LONG_EDGE = 2048
_SERVER_PIXEL_CAP = 4_000_000
_MAX_SERVER_CROP_SIDE = int(math.isqrt(_SERVER_PIXEL_CAP))


def _error(msg) -> dict:
    return {"error": str(msg)}


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
    resp = _safe_send("get_image_info")
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = resp.get("error") if isinstance(resp, dict) else "no reply from Fiji"
        return _error(err or "get_image_info failed")
    result = resp.get("result")
    if not isinstance(result, dict):
        return _error("get_image_info returned no result")
    return result


def _decode_pixels(resp):
    """Decode a get_pixels reply into (float32 2D ndarray, meta dict).

    Returns (None, error_dict) on failure. If the reply holds a
    stack, the first plane is returned — these tools operate on a
    single 2D slice.
    """
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = resp.get("error") if isinstance(resp, dict) else "no reply from Fiji"
        return None, _error(err or "get_pixels failed")
    result = resp.get("result") or {}
    b64 = result.get("data")
    if not isinstance(b64, str) or not b64:
        return None, _error("get_pixels reply missing data field")
    try:
        raw = base64.b64decode(b64)
    except (ValueError, TypeError) as exc:
        return None, _error("base64 decode failed: {}".format(exc))
    w = int(result.get("width", 0))
    h = int(result.get("height", 0))
    n_slices = int(result.get("sliceCount", 1))
    if w <= 0 or h <= 0:
        return None, _error("get_pixels returned zero-size region")
    plane = np.frombuffer(raw, dtype="<f4", count=w * h).reshape((h, w))
    meta = {
        "x": int(result.get("x", 0)),
        "y": int(result.get("y", 0)),
        "width": w,
        "height": h,
        "slice_start": int(result.get("sliceStart", 0)),
        "slice_end": int(result.get("sliceEnd", 0)),
        "slice_count": n_slices,
        "type": result.get("type", ""),
    }
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
        arr, meta = _decode_pixels(_safe_send("get_pixels"))
        if arr is None:
            return None, meta
        if factor > 1:
            arr = arr[::factor, ::factor]
        meta["downsample_factor"] = int(factor)
        meta["bit_depth"] = int(bit_depth)
        meta["source"] = "full"
        return arr, meta

    # Image too large to fetch whole: take a centred crop that still
    # respects the server's 4M-pixel hard limit.
    crop = min(max_side, w, h, _MAX_SERVER_CROP_SIDE)
    cx = max(0, (w - crop) // 2)
    cy = max(0, (h - crop) // 2)
    cw = min(crop, w - cx)
    ch = min(crop, h - cy)
    arr, meta = _decode_pixels(_safe_send("get_pixels", x=cx, y=cy, width=cw, height=ch))
    if arr is None:
        return None, meta
    meta["downsample_factor"] = int(factor)
    meta["bit_depth"] = int(bit_depth)
    meta["source"] = "center_crop"
    meta["note"] = (
        "image {}x{} exceeds the 4M-pixel server cap; "
        "analysed a centred {}x{} crop instead of a full-image downsample"
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


def _triangle_threshold(arr) -> float:
    """Zack's triangle method on a 256-bin histogram of arr."""
    flat = arr.ravel()
    lo = float(flat.min())
    hi = float(flat.max())
    if hi <= lo:
        return lo
    hist, edges = np.histogram(flat, bins=256, range=(lo, hi))
    peak = int(np.argmax(hist))
    if peak < 128:
        end = len(hist) - 1
        while end > peak and hist[end] == 0:
            end -= 1
    else:
        end = 0
        while end < peak and hist[end] == 0:
            end += 1
    x0, y0 = float(peak), float(hist[peak])
    x1, y1 = float(end), float(hist[end])
    dx = x1 - x0
    dy = y1 - y0
    denom = math.sqrt(dx * dx + dy * dy)
    if denom == 0:
        return float(edges[peak])
    lo_i, hi_i = min(peak, end), max(peak, end)
    best = peak
    max_d = -1.0
    for i in range(lo_i, hi_i + 1):
        d = abs(dy * float(i) - dx * float(hist[i]) + x1 * y0 - y1 * x0) / denom
        if d > max_d:
            max_d = d
            best = i
    return float(edges[best])


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
def get_pixels_array(slice: int, region: list) -> list:
    """Pull raw pixel values from the active image as a 2D list of floats.

    Args:
        slice: Z-slice index, 1-based; use 0 for the currently displayed slice.
        region: Rectangle as [x, y, width, height], or empty list for the whole image.
    """
    kwargs: dict = {}
    try:
        slice_val = int(slice)
    except (TypeError, ValueError):
        slice_val = 0
    if slice_val > 0:
        kwargs["slice"] = slice_val
    if isinstance(region, list) and len(region) == 4:
        try:
            rx, ry, rw, rh = (int(v) for v in region)
        except (TypeError, ValueError):
            return _error("region must be [x, y, width, height] with integer values")
        if rw <= 0 or rh <= 0:
            return _error("region width and height must be positive")
        kwargs["x"] = rx
        kwargs["y"] = ry
        kwargs["width"] = rw
        kwargs["height"] = rh
    elif isinstance(region, list) and len(region) != 0:
        return _error("region must be [x, y, width, height] or an empty list")
    arr, meta = _decode_pixels(_safe_send("get_pixels", **kwargs))
    if arr is None:
        return meta
    return arr.tolist()


@tool
def region_stats(x: int, y: int, width: int, height: int) -> dict:
    """Return mean, median, min, max and standard deviation for a rectangle on the active image.

    Args:
        x: Left edge of the rectangle in pixels.
        y: Top edge of the rectangle in pixels.
        width: Rectangle width in pixels.
        height: Rectangle height in pixels.
    """
    try:
        x_i = int(x)
        y_i = int(y)
        w_i = int(width)
        h_i = int(height)
    except (TypeError, ValueError):
        return _error("x, y, width, height must all be integers")
    if w_i <= 0 or h_i <= 0:
        return _error("width and height must be positive")
    arr, meta = _decode_pixels(_safe_send("get_pixels", x=x_i, y=y_i, width=w_i, height=h_i))
    if arr is None:
        return meta
    flat = arr.ravel()
    return {
        "x": x_i,
        "y": y_i,
        "width": w_i,
        "height": h_i,
        "count": int(flat.size),
        "mean": float(flat.mean()),
        "median": float(np.median(flat)),
        "min": float(flat.min()),
        "max": float(flat.max()),
        "std": float(flat.std()),
    }


@tool
def line_profile(x1: int, y1: int, x2: int, y2: int) -> list:
    """Sample intensity along a straight line between two pixel coordinates using bilinear interpolation.

    Args:
        x1: Start point x in pixels.
        y1: Start point y in pixels.
        x2: End point x in pixels.
        y2: End point y in pixels.
    """
    try:
        x1_i = int(x1)
        y1_i = int(y1)
        x2_i = int(x2)
        y2_i = int(y2)
    except (TypeError, ValueError):
        return _error("x1, y1, x2, y2 must all be integers")
    bx = min(x1_i, x2_i)
    by = min(y1_i, y2_i)
    bw = max(x1_i, x2_i) - bx + 1
    bh = max(y1_i, y2_i) - by + 1
    if bw <= 0 or bh <= 0:
        return _error("line endpoints are degenerate")
    arr, meta = _decode_pixels(_safe_send("get_pixels", x=bx, y=by, width=bw, height=bh))
    if arr is None:
        return meta
    dx = float(x2_i - x1_i)
    dy = float(y2_i - y1_i)
    length = math.hypot(dx, dy)
    if length == 0:
        return []
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
    return profile


@tool
def quick_object_count(threshold_method: str) -> int:
    """Return a rough count of bright blobs on the active image using a numpy threshold plus 4-connected components.

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
    thr = _THRESHOLD_METHODS[key](arr)
    mask = arr > thr
    count = _count_components_4(mask)
    factor = int(meta.get("downsample_factor", 1))
    if factor > 1 or meta.get("source") == "center_crop":
        result = {
            "count": int(count),
            "threshold_method": key,
            "threshold_value": float(thr),
            "downsample_factor": factor,
        }
        if "note" in meta:
            result["note"] = meta["note"]
        return result
    return int(count)


@tool
def histogram_summary() -> dict:
    """Return 1st/50th/99th intensity percentiles, the saturated-pixel fraction and the bit depth of the active image.

    Args:
        None.
    """
    arr, meta = _fetch_full_downsampled()
    if arr is None:
        return meta
    flat = arr.ravel()
    bit_depth = int(meta.get("bit_depth", 0))
    if bit_depth in (8, 16):
        ceiling = float((1 << bit_depth) - 1)
    elif bit_depth == 32:
        ceiling = float(flat.max())
    else:
        ceiling = float(flat.max())
    saturated_fraction = float((flat >= ceiling).mean()) if flat.size else 0.0
    out = {
        "p01": float(np.percentile(flat, 1)),
        "p50": float(np.percentile(flat, 50)),
        "p99": float(np.percentile(flat, 99)),
        "saturated_fraction": saturated_fraction,
        "saturation_ceiling": ceiling,
        "bit_depth": bit_depth,
        "downsample_factor": int(meta.get("downsample_factor", 1)),
    }
    if "note" in meta:
        out["note"] = meta["note"]
    return out


@tool
def count_bright_regions(min_intensity: int, min_area_pixels: int) -> int:
    """Count 4-connected regions brighter than an absolute cutoff with at least a minimum area, on the active image.

    Args:
        min_intensity: Absolute intensity cutoff; pixels strictly above this are foreground.
        min_area_pixels: Minimum region size in pixels after downsampling.
    """
    try:
        cutoff = float(min_intensity)
        min_area = int(min_area_pixels)
    except (TypeError, ValueError):
        return _error("min_intensity and min_area_pixels must be numbers")
    if min_area < 1:
        min_area = 1
    arr, meta = _fetch_full_downsampled()
    if arr is None:
        return meta
    mask = arr > cutoff
    count = _count_components_4(mask, min_size=min_area)
    factor = int(meta.get("downsample_factor", 1))
    if factor > 1 or meta.get("source") == "center_crop":
        result = {
            "count": int(count),
            "min_intensity": cutoff,
            "min_area_pixels": min_area,
            "downsample_factor": factor,
        }
        if "note" in meta:
            result["note"] = meta["note"]
        return result
    return int(count)
