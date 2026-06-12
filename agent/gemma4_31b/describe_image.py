"""describe_image — template-assembled substitute for seeing pixels.

Gemma cannot look at images. This tool condenses the active Fiji
image into one paragraph where every adjective has a number next
to it. The paragraph is built by dropping numeric fragments into
a fixed template — never LLM-generated — so the reported values
are always the ones these helpers actually measured.

Each measurement listed in Phase 4 of docs/ollama/plan.md is a
short pure helper that returns a formatted fragment (a string)
or None when the measurement could not be taken. describe_image()
calls them in order and joins the non-None fragments into the
final paragraph. If a helper fails — for instance get_histogram
errors — the corresponding sentence is simply dropped rather
than faked.

Pixel decoding (base64 float32) and the 4-connected flood-fill
mirror the patterns in agent/pixels.py and tools_python.py. The
Phase 4 brief says to copy those patterns, not import across
modules, so each is reimplemented here.
"""

from __future__ import annotations

import base64
import json
import math
import re
import socket

import numpy as np

from .registry import send, tool


_THUMB_MAX_SIDE = 512
_SERVER_PIXEL_CAP = 4_000_000
_MAX_SERVER_CROP_SIDE = int(math.isqrt(_SERVER_PIXEL_CAP))
_SKEW_STD_RATIO = 0.4


# --------------------------------------------------------------------------
# Low-level helpers
# --------------------------------------------------------------------------

def _safe_send(command: str, **payload) -> dict:
    """Wrap registry.send so connection errors become an error dict."""
    try:
        return send(command, **payload)
    except (ConnectionRefusedError, socket.timeout, OSError) as exc:
        return {"ok": False, "error": "Fiji TCP server unreachable: {}".format(exc)}


def _bit_depth_from_type(type_str) -> int:
    """Parse an ImageJ type label like '8-bit' / '16-bit' / '32-bit'."""
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


def _ceiling_for_bit_depth(bit_depth: int):
    """Max pixel value for an integer bit depth; None for float / RGB."""
    if bit_depth in (8, 16):
        return float((1 << bit_depth) - 1)
    return None


def _fmt_int(value) -> str:
    """Round to nearest int and format with thousand separators."""
    try:
        return "{:,}".format(int(round(float(value))))
    except (TypeError, ValueError):
        return str(value)


def _fmt_pct(value: float) -> str:
    """2 decimals below 1%, 1 decimal otherwise — matches both plan examples."""
    if value < 1.0:
        return "{:.2f}%".format(value)
    return "{:.1f}%".format(value)


# --------------------------------------------------------------------------
# Measurement 1 + 2: title, bit depth, dimensions, axes
# --------------------------------------------------------------------------

def _format_axes(info: dict) -> str:
    """Comma-joined axis phrase. Always reports all three axes so the
    header sentence matches the form used in the Phase 4 worked examples."""
    channels = int(info.get("channels", 1) or 1)
    slices = int(info.get("slices", 1) or 1)
    frames = int(info.get("frames", 1) or 1)
    return "{}, {}, {}".format(
        "single channel" if channels == 1 else "{} channels".format(channels),
        "single z" if slices == 1 else "{} z-slices".format(slices),
        "single frame" if frames == 1 else "{} frames".format(frames),
    )


def _fragment_header(info: dict):
    """Sentence 1: title, bit depth, dimensions, axes."""
    if not info:
        return None
    title = str(info.get("title", "") or "unknown")
    bit_label = str(info.get("type", "") or "unknown type")
    try:
        width = int(info.get("width", 0))
        height = int(info.get("height", 0))
    except (TypeError, ValueError):
        return None
    if width <= 0 or height <= 0:
        return None
    return 'Active image "{t}" is {b}, {w}\u00d7{h} pixels, {ax}.'.format(
        t=title, b=bit_label, w=width, h=height, ax=_format_axes(info),
    )


# --------------------------------------------------------------------------
# Measurement 3: pixel-size calibration
# --------------------------------------------------------------------------

_CAL_UNCAL_PATTERN = re.compile(r"^\s*1(?:\.0+)?\s*(pixel|pixels)\s*/?px?\s*$", re.IGNORECASE)


def _fragment_calibration(info: dict) -> str:
    """Sentence 2: pixel-size calibration. Always returned (never None).

    StateInspector emits '' when the Calibration is not scaled, and
    '<pixelWidth> <unit>/px' otherwise. An uncalibrated image is
    spelled as 'reads as "1 pixel"' to match the Phase 4 examples
    — regardless of whether the raw field is empty or literally
    '1.0 pixel/px'.
    """
    raw = str(info.get("calibration", "") or "").strip()
    if not raw or _CAL_UNCAL_PATTERN.match(raw):
        return 'Pixel size is **uncalibrated** (reads as "1 pixel").'
    parts = raw.split(" ", 1)
    if len(parts) == 2:
        scale_token, unit_token = parts[0], parts[1].strip()
        if unit_token.lower().endswith("/px"):
            unit_token = unit_token[:-3].strip()
        if not unit_token or unit_token.lower() in ("pixel", "pixels"):
            return 'Pixel size is **uncalibrated** (reads as "1 pixel").'
        return "Pixel size is calibrated at {} {} per pixel.".format(scale_token, unit_token)
    return "Pixel size is calibrated at {}.".format(raw.replace("/px", " per pixel"))


# --------------------------------------------------------------------------
# Measurement 4-7: histogram-derived facts
# --------------------------------------------------------------------------

def _median_from_bins(bins: np.ndarray, n_pixels: int, lo: float, hi: float) -> float:
    """Walk the cumulative bin count; return the intensity of the median bin."""
    if n_pixels <= 0 or hi <= lo or bins.size == 0:
        return lo
    half = n_pixels / 2.0
    cumulative = 0.0
    n = int(bins.size)
    for i in range(n):
        cumulative += float(bins[i])
        if cumulative >= half:
            return lo + (hi - lo) * i / max(n - 1, 1)
    return hi


def _smooth_bins(bins: np.ndarray, window: int = 5) -> np.ndarray:
    """5-bin rolling mean. Input with fewer than `window` entries is
    returned unchanged so the peak finder still sees the raw shape."""
    b = np.asarray(bins, dtype=np.float64)
    if b.size == 0 or b.size < window:
        return b
    kernel = np.ones(window, dtype=np.float64) / float(window)
    return np.convolve(b, kernel, mode="same")


def _find_local_peaks(values: np.ndarray, min_height: float) -> list:
    """Strict interior local maxima above min_height. Endpoint bins
    are ignored so a saturation spike at bin 255 isn't mistaken for
    a histogram mode — the saturation fragment reports that."""
    peaks = []
    n = int(values.size)
    for i in range(1, n - 1):
        v = float(values[i])
        if v < min_height:
            continue
        if v > float(values[i - 1]) and v > float(values[i + 1]):
            peaks.append(i)
    return peaks


def _classify_shape(bins, n_pixels: int) -> dict:
    """Histogram-shape classifier used by Phase 4 verification step 4.

    Smooths with a 5-bin rolling mean, picks strict interior peaks
    whose smoothed count exceeds 5% of n_pixels, then looks for a
    valley between the two tallest peaks ≤ 70% of the smaller peak.

    Returns:
        {"shape": "bimodal"|"unimodal"|"flat",
         "valley_bin": int|None,
         "peaks": [int, ...]}
    """
    arr = np.asarray(bins, dtype=np.float64)
    if arr.size == 0 or n_pixels <= 0:
        return {"shape": "flat", "valley_bin": None, "peaks": []}
    smoothed = _smooth_bins(arr, window=5)
    peak_threshold = 0.05 * float(n_pixels)
    peaks = _find_local_peaks(smoothed, peak_threshold)
    if len(peaks) < 2:
        shape = "unimodal" if peaks else "flat"
        return {"shape": shape, "valley_bin": None, "peaks": peaks}
    heights = sorted(((p, float(smoothed[p])) for p in peaks), key=lambda hp: hp[1], reverse=True)
    p1, h1 = heights[0]
    p2, h2 = heights[1]
    lo_idx, hi_idx = (p1, p2) if p1 < p2 else (p2, p1)
    between = smoothed[lo_idx + 1:hi_idx]
    if between.size == 0:
        return {"shape": "unimodal", "valley_bin": None, "peaks": peaks}
    valley_val = float(between.min())
    smaller_peak = min(h1, h2)
    if valley_val <= 0.7 * smaller_peak:
        valley_bin = int(lo_idx + 1 + int(np.argmin(between)))
        return {"shape": "bimodal", "valley_bin": valley_bin, "peaks": [lo_idx, hi_idx]}
    return {"shape": "unimodal", "valley_bin": None, "peaks": peaks}


def _hist_stats(hist_result: dict):
    """Pull numeric fields out of a get_histogram payload; median is
    walked from the cumulative bin count since the server does not
    return it directly. Returns None if the payload is unusable."""
    if not isinstance(hist_result, dict):
        return None
    try:
        lo = float(hist_result.get("min"))
        hi = float(hist_result.get("max"))
        mean = float(hist_result.get("mean"))
        std = float(hist_result.get("stdDev"))
        n_pixels = int(hist_result.get("nPixels", 0))
    except (TypeError, ValueError):
        return None
    bins_raw = hist_result.get("bins") or []
    bins = np.asarray(bins_raw, dtype=np.float64)
    median = _median_from_bins(bins, n_pixels, lo, hi)
    return {
        "min": lo, "max": hi, "mean": mean, "std": std,
        "median": median, "n_pixels": n_pixels, "bins": bins,
    }


def _fragment_intensity(stats: dict, bit_depth: int) -> str:
    """Sentence 3: intensity range + dynamic range used. Combined into
    one sentence (semicolon-joined) to match the Phase 4 examples."""
    first = "Intensity ranges from {mn} to {mx} with mean {me}, median {md} and standard deviation {sd}".format(
        mn=_fmt_int(stats["min"]), mx=_fmt_int(stats["max"]),
        me=_fmt_int(stats["mean"]), md=_fmt_int(stats["median"]),
        sd=_fmt_int(stats["std"]),
    )
    ceiling = _ceiling_for_bit_depth(bit_depth)
    if ceiling is None or ceiling <= 0:
        return first + "."
    pct = float(stats["max"]) / ceiling * 100.0
    return "{}; dynamic range used is {:.1f}% of the {}-bit maximum.".format(first, pct, bit_depth)


def _fragment_saturation(stats: dict, bit_depth: int) -> str:
    """Sentence 4: saturated-pixel fraction at the top histogram bin."""
    bins = stats["bins"]
    n_pixels = stats["n_pixels"]
    if bins.size == 0 or n_pixels <= 0:
        return "Saturated-pixel fraction is unavailable."
    ceiling = _ceiling_for_bit_depth(bit_depth)
    if ceiling is None:
        sat_count = float(bins[-1])
    elif float(stats["max"]) < ceiling:
        sat_count = 0.0
    else:
        sat_count = float(bins[-1])
    frac = sat_count / float(n_pixels) * 100.0
    if frac >= 5.0 and ceiling is not None:
        return (
            "Saturated-pixel fraction is {} \u2014 a large share of pixels are pinned at {}."
        ).format(_fmt_pct(frac), int(ceiling))
    return "Saturated-pixel fraction is {}.".format(_fmt_pct(frac))


def _fragment_histogram_shape(stats: dict) -> str:
    """Sentence 5: bimodal / unimodal / (strongly) skewed."""
    classification = _classify_shape(stats["bins"], stats["n_pixels"])
    shape = classification["shape"]
    if shape == "bimodal":
        valley_bin = classification["valley_bin"]
        n_bins = int(stats["bins"].size)
        intensity = stats["min"] + (stats["max"] - stats["min"]) * valley_bin / max(n_bins - 1, 1)
        return (
            "The histogram is bimodal with a valley at intensity {}, consistent with a clear "
            "separation between dim background and bright foreground."
        ).format(_fmt_int(intensity))
    mean = stats["mean"]
    median = stats["median"]
    std = stats["std"]
    diff = mean - median
    if std > 0 and abs(diff) > _SKEW_STD_RATIO * std:
        direction = "right-skewed" if diff > 0 else "left-skewed"
        return "The histogram is unimodal and strongly {} (mean {} vs median {}).".format(
            direction, _fmt_int(mean), _fmt_int(median),
        )
    if shape == "flat":
        return "The histogram has no peak reaching 5% of pixels."
    return "The histogram is unimodal."


def _hist_bin_centers(stats: dict) -> np.ndarray:
    """Map histogram bins onto intensity centers across the reported min/max span."""
    bins = np.asarray(stats["bins"], dtype=np.float64)
    if bins.size == 0:
        return bins
    lo = float(stats["min"])
    hi = float(stats["max"])
    if bins.size == 1 or hi <= lo:
        return np.full(bins.shape, lo, dtype=np.float64)
    return np.linspace(lo, hi, int(bins.size), dtype=np.float64)


def _otsu_threshold_from_hist(stats: dict) -> float:
    """Otsu threshold derived from the Fiji histogram bins."""
    counts = np.asarray(stats["bins"], dtype=np.float64)
    centers = _hist_bin_centers(stats)
    total = counts.sum()
    if counts.size == 0 or total <= 0:
        return float(stats["min"])
    omega = np.cumsum(counts)
    mu = np.cumsum(counts * centers)
    mu_t = mu[-1]
    denom = omega * (total - omega)
    denom = np.where(denom <= 0, 1e-12, denom)
    sigma_b_sq = (mu_t * omega - mu) ** 2 / denom
    return float(centers[int(np.argmax(sigma_b_sq))])


def _li_threshold_from_hist(stats: dict) -> float:
    """Li's iterative minimum cross-entropy threshold on histogram bins."""
    counts = np.asarray(stats["bins"], dtype=np.float64)
    values = _hist_bin_centers(stats)
    total = counts.sum()
    if counts.size == 0 or total <= 0:
        return float(stats["min"])
    shift = 0.0
    if float(values.min()) <= 0:
        shift = -float(values.min()) + 1.0
        values = values + shift
    t = float(np.sum(counts * values) / total)
    for _ in range(100):
        fg = values > t
        bg = ~fg
        fg_total = float(counts[fg].sum())
        bg_total = float(counts[bg].sum())
        if fg_total <= 0 or bg_total <= 0:
            break
        mf = float(np.sum(counts[fg] * values[fg]) / fg_total)
        mb = float(np.sum(counts[bg] * values[bg]) / bg_total)
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


def _triangle_threshold_from_hist(stats: dict) -> float:
    """Zack's triangle method using the Fiji histogram bins."""
    hist = np.asarray(stats["bins"], dtype=np.float64)
    values = _hist_bin_centers(stats)
    if hist.size == 0:
        return float(stats["min"])
    peak = int(np.argmax(hist))
    if peak < hist.size / 2:
        end = hist.size - 1
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
        return float(values[peak])
    lo_i, hi_i = min(peak, end), max(peak, end)
    best = peak
    max_d = -1.0
    for i in range(lo_i, hi_i + 1):
        d = abs(dy * float(i) - dx * float(hist[i]) + x1 * y0 - y1 * x0) / denom
        if d > max_d:
            max_d = d
            best = i
    return float(values[best])


# --------------------------------------------------------------------------
# Measurement 8 + 9: thumbnail-derived facts
# --------------------------------------------------------------------------

def _decode_pixels(resp):
    """Decode a get_pixels reply into (float32 2D ndarray, meta) or (None, err)."""
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = resp.get("error") if isinstance(resp, dict) else "no reply"
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
    return plane, {
        "x": int(result.get("x", 0)),
        "y": int(result.get("y", 0)),
        "width": w,
        "height": h,
    }


def _fetch_thumbnail(info: dict):
    """Fetch the active image at ≤ 512 px on its long edge.

    Downsamples in numpy when the whole image is under the server's
    4M-pixel cap, otherwise falls back to a centred crop sized to
    respect both the 512 px target and the cap. Returns (None, err).
    """
    try:
        w = int(info.get("width", 0))
        h = int(info.get("height", 0))
    except (TypeError, ValueError):
        return None, {"error": "image dimensions unavailable"}
    if w <= 0 or h <= 0:
        return None, {"error": "image has zero size"}

    long_edge = max(w, h)
    factor = 1 if long_edge <= _THUMB_MAX_SIDE else (long_edge + _THUMB_MAX_SIDE - 1) // _THUMB_MAX_SIDE

    if w * h <= _SERVER_PIXEL_CAP:
        arr, meta = _decode_pixels(_safe_send("get_pixels"))
        if arr is None:
            return None, meta
        if factor > 1:
            arr = arr[::factor, ::factor]
        meta["downsample_factor"] = int(factor)
        meta["source"] = "full"
        return arr, meta

    crop = min(_THUMB_MAX_SIDE, w, h, _MAX_SERVER_CROP_SIDE)
    cx = max(0, (w - crop) // 2)
    cy = max(0, (h - crop) // 2)
    cw = min(crop, w - cx)
    ch = min(crop, h - cy)
    arr, meta = _decode_pixels(_safe_send("get_pixels", x=cx, y=cy, width=cw, height=ch))
    if arr is None:
        return None, meta
    meta["downsample_factor"] = int(factor)
    meta["source"] = "center_crop"
    return arr, meta


def _otsu_threshold(arr: np.ndarray) -> float:
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
    return float(edges[int(np.argmax(sigma_b_sq))])


def _li_threshold(arr: np.ndarray) -> float:
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


def _triangle_threshold(arr: np.ndarray) -> float:
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


def _count_4_connected(mask: np.ndarray) -> int:
    """Flood-fill 4-connected component count, same pattern as pixels.py."""
    h, w = mask.shape
    visited = np.zeros((h, w), dtype=bool)
    count = 0
    ys, xs = np.where(mask)
    for iy, ix in zip(ys.tolist(), xs.tolist()):
        if visited[iy, ix]:
            continue
        stack = [(ix, iy)]
        found = False
        while stack:
            cx, cy = stack.pop()
            if cx < 0 or cx >= w or cy < 0 or cy >= h:
                continue
            if not mask[cy, cx] or visited[cy, cx]:
                continue
            visited[cy, cx] = True
            found = True
            stack.append((cx + 1, cy))
            stack.append((cx - 1, cy))
            stack.append((cx, cy + 1))
            stack.append((cx, cy - 1))
        if found:
            count += 1
    return count


def _fragment_thresholds(thumb: np.ndarray, stats: dict | None) -> str:
    """Sentence 6: Otsu / Li / Triangle object counts on the thumbnail."""
    if stats is None:
        return "Auto-threshold counts are unavailable because Fiji did not return histogram data."
    otsu_t = _otsu_threshold_from_hist(stats)
    li_t = _li_threshold_from_hist(stats)
    tri_t = _triangle_threshold_from_hist(stats)
    n_otsu = _count_4_connected(thumb > otsu_t)
    n_li = _count_4_connected(thumb > li_t)
    n_tri = _count_4_connected(thumb > tri_t)
    return (
        "Auto-thresholds produce {} connected components with Otsu, {} with Li and {} "
        "with Triangle on a 512-pixel thumbnail."
    ).format(n_otsu, n_li, n_tri)


def _fragment_artifacts(thumb: np.ndarray, bit_depth: int) -> str:
    """Sentence 7: clipped blacks + quadrant saturation + stripe detection.

    All three sub-checks always contribute a phrase — either an issue
    warning or a "no X" clean note. Clean notes are merged into a
    single closing sentence; issues become their own sentences so the
    numbers stand out.
    """
    h, w = thumb.shape
    issues = []
    clean = []

    mn = float(thumb.min())
    clipped_frac = float((thumb <= mn).mean()) * 100.0
    if clipped_frac > 1.0:
        issues.append("clipped blacks at {}".format(_fmt_pct(clipped_frac)))
    else:
        clean.append("no clipped blacks")

    ceiling = _ceiling_for_bit_depth(bit_depth)
    if ceiling is None:
        ceiling = float(thumb.max())
    mid_y = h // 2
    mid_x = w // 2
    quadrants = [
        ("top-left", thumb[:mid_y, :mid_x]),
        ("top-right", thumb[:mid_y, mid_x:]),
        ("bottom-left", thumb[mid_y:, :mid_x]),
        ("bottom-right", thumb[mid_y:, mid_x:]),
    ]
    q_counts = [float((q >= ceiling).sum()) for _, q in quadrants]
    total_sat = sum(q_counts)
    if total_sat > 0:
        max_idx = int(np.argmax(q_counts))
        others = [c for i, c in enumerate(q_counts) if i != max_idx]
        mean_others = float(sum(others)) / max(len(others), 1)
        dominant = q_counts[max_idx] > 5.0 * mean_others if mean_others > 0 else True
        if dominant:
            share = q_counts[max_idx] / total_sat * 100.0
            corner = quadrants[max_idx][0]
            issues.append(
                "the {} quadrant carries {:.0f}% of the saturated pixels, suggesting a "
                "saturated patch in that corner".format(corner, share)
            )
        else:
            clean.append("no quadrant saturation")
    else:
        clean.append("no quadrant saturation")

    if h >= 2 and w >= 2:
        row_means = thumb.mean(axis=1)
        col_means = thumb.mean(axis=0)
        row_var = float(row_means.var())
        col_var = float(col_means.var())
        if row_var > 4.0 * col_var and col_var > 0:
            issues.append("horizontal stripes detected")
        elif col_var > 4.0 * row_var and row_var > 0:
            issues.append("vertical stripes detected")
        else:
            clean.append("no stripe pattern")
    else:
        clean.append("no stripe pattern")

    if not issues:
        joined = ", ".join(clean)
        return joined[:1].upper() + joined[1:] + "." if joined else ""
    sentences = []
    for phrase in issues:
        s = phrase.strip().rstrip(".")
        sentences.append(s[:1].upper() + s[1:] + ".")
    if clean:
        joined = ", ".join(clean)
        sentences.append(joined[:1].upper() + joined[1:] + ".")
    return " ".join(sentences)


# --------------------------------------------------------------------------
# Measurement 10: ROI / overlay
# --------------------------------------------------------------------------

_ROI_SCRIPT = """
import groovy.json.JsonOutput
def imp = ij.WindowManager.getCurrentImage()
def out = [:]
if (imp == null) {
    out.error = "no_image"
} else {
    def roi = imp.getRoi()
    out.hasRoi = (roi != null)
    if (roi != null) {
        def b = roi.getBounds()
        out.roiType = roi.getTypeAsString()
        out.roiWidth = b.width
        out.roiHeight = b.height
    }
    def overlay = imp.getOverlay()
    out.hasOverlay = (overlay != null)
    if (overlay != null) out.overlaySize = overlay.size()
}
JsonOutput.toJson(out)
""".strip()


def _fetch_roi_overlay():
    """One-round Groovy call: reports active ROI shape/size and overlay presence."""
    resp = _safe_send("run_script", code=_ROI_SCRIPT, language="groovy")
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None
    result = resp.get("result") or {}
    if not result.get("success"):
        return None
    output = result.get("output")
    if not isinstance(output, str) or not output.strip():
        return None
    try:
        data = json.loads(output)
    except (ValueError, TypeError):
        return None
    return data if isinstance(data, dict) else None


def _fragment_roi_overlay(data) -> str:
    """Sentence 8: active ROI + overlay presence."""
    if not data:
        return "ROI/overlay status unavailable."
    if data.get("error") == "no_image":
        return "No ROI, no overlay."
    has_roi = bool(data.get("hasRoi"))
    has_overlay = bool(data.get("hasOverlay"))
    roi_phrase = None
    if has_roi:
        roi_type = str(data.get("roiType", "") or "").strip().lower() or "unknown"
        roi_w = int(data.get("roiWidth", 0) or 0)
        roi_h = int(data.get("roiHeight", 0) or 0)
        roi_phrase = "One {} ROI is active ({}\u00d7{} pixels)".format(roi_type, roi_w, roi_h)
    overlay_phrase = None
    if has_overlay:
        size = int(data.get("overlaySize", 0) or 0)
        overlay_phrase = "overlay with {} item{}".format(size, "" if size == 1 else "s")
    if roi_phrase and overlay_phrase:
        return "{}; {}.".format(roi_phrase, overlay_phrase)
    if roi_phrase:
        return "{}; no overlay.".format(roi_phrase)
    if overlay_phrase:
        return "No ROI; {}.".format(overlay_phrase)
    return "No ROI, no overlay."


# --------------------------------------------------------------------------
# Public tool
# --------------------------------------------------------------------------

@tool
def describe_image() -> str:
    """Describe the active Fiji image as one 150-300 word paragraph with a number next to every adjective.

    Args:
        None.
    """
    info_resp = _safe_send("get_image_info")
    if not isinstance(info_resp, dict) or not info_resp.get("ok"):
        err = info_resp.get("error") if isinstance(info_resp, dict) else "no reply from Fiji"
        return "describe_image: cannot read active image info ({}).".format(err or "unknown error")
    info = info_resp.get("result") or {}
    if not isinstance(info, dict) or not info:
        return "describe_image: no active image."

    bit_depth = _bit_depth_from_type(info.get("type", ""))

    hist_stats = None
    hist_error = None
    hist_resp = _safe_send("get_histogram")
    if isinstance(hist_resp, dict) and hist_resp.get("ok"):
        hist_stats = _hist_stats(hist_resp.get("result") or {})
    elif isinstance(hist_resp, dict):
        hist_error = hist_resp.get("error") or "unknown histogram error"

    thumb_arr, _thumb_meta = _fetch_thumbnail(info)
    roi_data = _fetch_roi_overlay()

    fragments = [_fragment_header(info), _fragment_calibration(info)]
    if hist_stats is not None:
        fragments.append(_fragment_intensity(hist_stats, bit_depth))
        fragments.append(_fragment_saturation(hist_stats, bit_depth))
        fragments.append(_fragment_histogram_shape(hist_stats))
    elif hist_error:
        fragments.append(
            "Histogram-derived intensity statistics are unavailable ({}).".format(hist_error)
        )
    if thumb_arr is not None:
        fragments.append(_fragment_thresholds(thumb_arr, hist_stats))
        fragments.append(_fragment_artifacts(thumb_arr, bit_depth))
    else:
        fragments.append("Thumbnail-based threshold and artifact checks are unavailable.")
    fragments.append(_fragment_roi_overlay(roi_data))

    return " ".join(f for f in fragments if f)
