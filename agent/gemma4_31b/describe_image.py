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
import binascii
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


def _response_error_text(response, fallback: str) -> str:
    """Render legacy string and structured protocol errors without dict reprs."""
    if not isinstance(response, dict):
        return fallback
    error = response.get("error")
    if isinstance(error, dict):
        code = error.get("code")
        message = error.get("message")
        if isinstance(message, str) and message.strip():
            if isinstance(code, str) and code.strip():
                return "{}: {}".format(code.strip(), message.strip())
            return message.strip()
        if isinstance(code, str) and code.strip():
            return code.strip()
        return fallback
    if isinstance(error, str) and error.strip():
        return error.strip()
    return fallback


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


def _exact_int(value, name, minimum=None) -> int:
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


def _snapshot_payload(info: dict) -> dict:
    """Bind one follow-up read to the exact image revision and active plane."""
    return {
        "image_id": info["image_id"],
        "image_revision": int(info["image_revision"]),
        "display_revision": int(info["display_revision"]),
        "channel": int(info["channel"]),
        "slice": int(info["sliceStart"]),
        "frame": int(info["frame"]),
        "force": True,
    }


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


def _fragment_plane_attribution(meta: dict) -> str:
    """Identify the exact C/Z/T plane used for numeric pixel measurements."""
    return (
        "Pixel measurements are from channel {channel} of {channels}, "
        "Z slice {slice} of {slices}, frame {frame} of {frames} "
        "(slice axis {axis})."
    ).format(
        channel=meta["channel"],
        channels=meta["channels"],
        slice=meta["sliceStart"],
        slices=meta["slices"],
        frame=meta["frame"],
        frames=meta["frames"],
        axis=meta["sliceAxis"],
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

def _histogram_bin_values(bins: np.ndarray, value_domain: dict):
    """Return exact values represented by histogram bins, or ``None``.

    ImageJ's 256-bin byte histogram is absolute: bin ``i`` counts raw value
    ``i``. This covers grayscale uint8, indexed8, and RGB histograms because the
    server explicitly publishes RGB weighted scalarization as a uint8 0..255
    value domain. Other ImageJ histograms can be display-range/adaptive bins.
    The current TCP response does not publish their bin origin and width, so
    observed min/max are not enough to reconstruct values without inventing
    precision.
    """
    counts = np.asarray(bins, dtype=np.float64)
    if counts.ndim != 1 or counts.size != 256 or not isinstance(value_domain, dict):
        return None
    if (
        value_domain.get("pixel_type") in ("uint8", "indexed8")
        and value_domain.get("signed") is False
        and value_domain.get("acquisition_min_raw") == 0.0
        and value_domain.get("acquisition_max_raw") == 255.0
    ):
        return np.arange(256, dtype=np.float64)
    return None


def _median_from_bins(bins: np.ndarray, n_pixels: int, bin_values) -> float | None:
    """Return the exact median when the response defines every bin value."""
    counts = np.asarray(bins, dtype=np.float64)
    values = None if bin_values is None else np.asarray(bin_values, dtype=np.float64)
    if (
        n_pixels <= 0
        or counts.ndim != 1
        or counts.size == 0
        or values is None
        or values.ndim != 1
        or values.size != counts.size
        or not np.isfinite(counts).all()
        or np.any(counts < 0)
        or not np.equal(counts, np.floor(counts)).all()
        or int(counts.sum()) != n_pixels
    ):
        return None

    lower_rank = (n_pixels - 1) // 2
    upper_rank = n_pixels // 2
    cumulative = 0
    lower = None
    upper = None
    for index, count in enumerate(counts):
        cumulative += int(count)
        if lower is None and cumulative > lower_rank:
            lower = float(values[index])
        if cumulative > upper_rank:
            upper = float(values[index])
            break
    if lower is None or upper is None:
        return None
    return (lower + upper) / 2.0


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
        n_pixels = _exact_int(hist_result["nPixels"], "nPixels", 1)
        value_domain = _decode_value_domain(hist_result)
        counts_exact = hist_result["acquisition_limit_counts_exact"]
        if not isinstance(counts_exact, bool):
            raise TypeError("acquisition_limit_counts_exact must be bool")
        min_count_raw = hist_result["acquisition_min_count"]
        max_count_raw = hist_result["acquisition_max_count"]
        min_count = (
            None if min_count_raw is None
            else _exact_int(min_count_raw, "acquisition_min_count", 0)
        )
        max_count = (
            None if max_count_raw is None
            else _exact_int(max_count_raw, "acquisition_max_count", 0)
        )
    except (TypeError, ValueError):
        return None
    bins_raw = hist_result.get("bins")
    try:
        bins = np.asarray(bins_raw, dtype=np.float64)
    except (TypeError, ValueError, OverflowError):
        return None
    absolute_byte_domain = (
        value_domain.get("pixel_type") in ("uint8", "indexed8")
        and value_domain.get("signed") is False
        and value_domain.get("acquisition_min_raw") == 0.0
        and value_domain.get("acquisition_max_raw") == 255.0
    )
    bin_total = float(bins.sum())
    if (
        not all(math.isfinite(value) for value in (lo, hi, mean, std))
        or bins.ndim != 1
        or bins.size == 0
        or (absolute_byte_domain and bins.size != 256)
        or not np.isfinite(bins).all()
        or np.any(bins < 0)
        or not np.equal(bins, np.floor(bins)).all()
        or not math.isfinite(bin_total)
        or int(bin_total) != n_pixels
        or (counts_exact and (min_count is None or max_count is None))
        or (min_count is not None and min_count > n_pixels)
        or (max_count is not None and max_count > n_pixels)
        or (
            min_count is not None
            and max_count is not None
            and min_count + max_count > n_pixels
        )
        or (
            counts_exact
            and absolute_byte_domain
            and (
                min_count != int(bins[0])
                or max_count != int(bins[255])
            )
        )
    ):
        return None
    bin_values = _histogram_bin_values(bins, value_domain)
    median = _median_from_bins(bins, n_pixels, bin_values)
    return {
        "min": lo, "max": hi, "mean": mean, "std": std,
        "median": median, "n_pixels": n_pixels, "bins": bins,
        "bin_values": bin_values,
        "value_domain": value_domain,
        "acquisition_min_count": min_count,
        "acquisition_max_count": max_count,
        "acquisition_limit_counts_exact": counts_exact,
        "scope": hist_result.get("scope"),
    }


def _fragment_intensity(stats: dict, bit_depth: int) -> str:
    """Sentence 3: intensity range + dynamic range used. Combined into
    one sentence (semicolon-joined) to match the Phase 4 examples."""
    if stats.get("median") is None:
        first = "Intensity ranges from {mn} to {mx} with mean {me} and standard deviation {sd}; the histogram contract does not define an exact median".format(
            mn=_fmt_int(stats["min"]), mx=_fmt_int(stats["max"]),
            me=_fmt_int(stats["mean"]), sd=_fmt_int(stats["std"]),
        )
    else:
        first = "Intensity ranges from {mn} to {mx} with mean {me}, median {md} and standard deviation {sd}".format(
            mn=_fmt_int(stats["min"]), mx=_fmt_int(stats["max"]),
            me=_fmt_int(stats["mean"]), md=_fmt_int(stats["median"]),
            sd=_fmt_int(stats["std"]),
        )
    domain = stats.get("value_domain") or {}
    floor = domain.get("acquisition_min_raw")
    ceiling = domain.get("acquisition_max_raw")
    if floor is None or ceiling is None or ceiling <= floor:
        return first + "."
    pct = (float(stats["max"]) - float(stats["min"])) / (ceiling - floor) * 100.0
    return "{}; observed span uses {:.1f}% of the raw acquisition range {} to {}.".format(
        first, pct, _fmt_int(floor), _fmt_int(ceiling)
    )


def _fragment_saturation(stats: dict, bit_depth: int) -> str:
    """Sentence 4: exact acquisition-maximum fraction when supplied."""
    n_pixels = stats["n_pixels"]
    ceiling = (stats.get("value_domain") or {}).get("acquisition_max_raw")
    sat_count = stats.get("acquisition_max_count")
    if (
        n_pixels <= 0
        or ceiling is None
        or not stats.get("acquisition_limit_counts_exact")
        or sat_count is None
    ):
        return "Saturated-pixel fraction is unavailable."
    frac = sat_count / float(n_pixels) * 100.0
    if frac >= 5.0:
        return (
            "Saturated-pixel fraction is {} \u2014 a large share of pixels are pinned at "
            "the raw acquisition maximum {}."
        ).format(_fmt_pct(frac), _fmt_int(ceiling))
    return "Saturated-pixel fraction is {}.".format(_fmt_pct(frac))


def _fragment_histogram_shape(stats: dict) -> str:
    """Sentence 5: bimodal / unimodal / (strongly) skewed."""
    classification = _classify_shape(stats["bins"], stats["n_pixels"])
    shape = classification["shape"]
    if shape == "bimodal":
        valley_bin = classification["valley_bin"]
        bin_values = stats.get("bin_values")
        if bin_values is None or valley_bin >= len(bin_values):
            return (
                "The histogram is bimodal with a valley at bin {}, but the response does not "
                "define that bin's exact intensity."
            ).format(valley_bin)
        intensity = float(bin_values[valley_bin])
        return (
            "The histogram is bimodal with a valley at intensity {}, consistent with a clear "
            "separation between dim background and bright foreground."
        ).format(_fmt_int(intensity))
    mean = stats["mean"]
    median = stats.get("median")
    std = stats["std"]
    if median is None:
        if shape == "flat":
            return "The histogram has no peak reaching 5% of pixels."
        return "The histogram is unimodal; exact median-based skew is unavailable."
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
    """Return exact bin values; never infer them from observed extrema."""
    bins = np.asarray(stats["bins"], dtype=np.float64)
    values = stats.get("bin_values")
    if values is None:
        values = _histogram_bin_values(bins, stats.get("value_domain") or {})
    if values is None:
        return np.asarray([], dtype=np.float64)
    values = np.asarray(values, dtype=np.float64)
    if values.ndim != 1 or values.size != bins.size:
        return np.asarray([], dtype=np.float64)
    return values


def _otsu_threshold_from_hist(stats: dict) -> float | None:
    """Otsu threshold derived from the Fiji histogram bins."""
    counts = np.asarray(stats["bins"], dtype=np.float64)
    centers = _hist_bin_centers(stats)
    total = counts.sum()
    if counts.size == 0 or centers.size != counts.size or total <= 0:
        return None
    omega = np.cumsum(counts)
    mu = np.cumsum(counts * centers)
    mu_t = mu[-1]
    valid = (omega > 0) & (omega < total)
    if not np.any(valid):
        return float(centers[int(np.argmax(counts))])
    sigma_b_sq = np.full(counts.shape, -np.inf, dtype=np.float64)
    denom = omega[valid] * (total - omega[valid])
    numerator = mu_t * omega[valid] - mu[valid] * total
    sigma_b_sq[valid] = numerator ** 2 / denom
    return float(centers[int(np.argmax(sigma_b_sq))])


def _li_threshold_from_hist(stats: dict) -> float | None:
    """Li's iterative minimum cross-entropy threshold on histogram bins."""
    counts = np.asarray(stats["bins"], dtype=np.float64)
    values = _hist_bin_centers(stats)
    total = counts.sum()
    if counts.size == 0 or values.size != counts.size or total <= 0:
        return None
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


def _triangle_threshold_from_hist(stats: dict) -> float | None:
    """ImageJ 1.54c's generalized Triangle method using Fiji histogram bins."""
    hist = np.asarray(stats["bins"], dtype=np.float64)
    values = _hist_bin_centers(stats)
    if (
        hist.ndim != 1
        or hist.size == 0
        or values.size != hist.size
        or not np.isfinite(hist).all()
        or np.any(hist < 0)
    ):
        return None
    occupied = np.flatnonzero(hist)
    if occupied.size == 0:
        return None
    if occupied.size == 1:
        return float(values[int(occupied[0])])
    peak = int(np.argmax(hist))
    first = int(occupied[0])
    last = int(occupied[-1])
    line_end = first - 1 if first > 0 else first
    far_end = last + 1 if last < hist.size - 1 else last
    inverted = peak - line_end < far_end - peak
    working = hist[::-1].copy() if inverted else hist
    if inverted:
        line_end = hist.size - 1 - far_end
        peak = hist.size - 1 - peak
    if line_end == peak:
        return float(values[int(occupied[0])])

    nx = float(working[peak])
    ny = float(line_end - peak)
    denom = math.sqrt(nx * nx + ny * ny)
    if denom == 0:
        return float(values[peak])
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
    split = max(0, min(int(split), hist.size - 1))
    return float(values[split])


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
        raw = base64.b64decode(b64, validate=True)
    except (binascii.Error, ValueError, TypeError) as exc:
        return None, {"error": "base64 decode failed: {}".format(exc)}
    try:
        w = _exact_int(result["width"], "width", 1)
        h = _exact_int(result["height"], "height", 1)
        meta = {
            "image_id": result["image_id"],
            "image_revision": _exact_int(result["image_revision"], "image_revision", 1),
            "display_revision": _exact_int(result["display_revision"], "display_revision", 1),
            "x": _exact_int(result["x"], "x", 0),
            "y": _exact_int(result["y"], "y", 0),
            "width": w,
            "height": h,
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
        return None, {
            "error": "get_pixels reply missing or malformed C/Z/T metadata: {}".format(exc)
        }
    if w <= 0 or h <= 0:
        return None, {"error": "get_pixels returned zero-size region"}
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
        or meta["sliceCount"] != 1
        or meta["sliceEnd"] != meta["sliceStart"]
        or meta["nPixels"] != w * h
        or len(raw) != meta["nPixels"] * 4
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
        return None, {"error": "get_pixels returned inconsistent C/Z/T metadata"}
    plane = np.frombuffer(raw, dtype="<f4").reshape((h, w))
    if not np.isfinite(plane).all():
        return None, {"error": "get_pixels returned non-finite pixel values"}
    return plane, meta


def _metadata_matches_info(meta: dict, info: dict) -> bool:
    """Return whether a reply belongs to the exact info snapshot and plane."""
    try:
        expected_slice = _exact_int(info["sliceStart"], "info sliceStart", 1)
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


def _geometry_matches(meta: dict, x: int, y: int, width: int, height: int) -> bool:
    """Return whether Fiji supplied the exact requested image rectangle."""
    try:
        return all(
            int(meta[key]) == expected
            for key, expected in (
                ("x", x),
                ("y", y),
                ("width", width),
                ("height", height),
            )
        )
    except (KeyError, TypeError, ValueError):
        return False


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
        arr, meta = _decode_pixels(
            _safe_send("get_pixels", **_snapshot_payload(info))
        )
        if arr is None:
            return None, meta
        if not _geometry_matches(meta, 0, 0, w, h):
            return None, {"error": "active image geometry changed during pixel fetch"}
        if not _metadata_matches_info(meta, info):
            return None, {"error": "active image axis sizes changed during pixel fetch"}
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
    payload = _snapshot_payload(info)
    payload.update({"x": cx, "y": cy, "width": cw, "height": ch})
    arr, meta = _decode_pixels(_safe_send("get_pixels", **payload))
    if arr is None:
        return None, meta
    if not _geometry_matches(meta, cx, cy, cw, ch):
        return None, {"error": "active image geometry changed during pixel fetch"}
    if not _metadata_matches_info(meta, info):
        return None, {"error": "active image axis sizes changed during pixel fetch"}
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


def _decode_rgb24_samples(samples: np.ndarray):
    """Return strict lower-24-bit RGB integers, including legacy signed 0xff forms."""
    values = np.asarray(samples, dtype=np.float64)
    if not np.isfinite(values).all() or not np.equal(values, np.floor(values)).all():
        return None

    # Current get_pixels replies publish ColorProcessor pixels as canonical
    # 0x00RRGGBB values. Older replies exposed Java's signed 0xffRRGGBB int;
    # accept exactly that sign-extension range, but no other high-byte values.
    canonical_lower24 = (values >= 0) & (values <= 0x00ffffff)
    legacy_signed_ff = (values >= -0x01000000) & (values <= -1)
    if not np.all(canonical_lower24 | legacy_signed_ff):
        return None
    return np.bitwise_and(values.astype(np.int64), 0x00ffffff)


def _threshold_thumbnail(thumb: np.ndarray, stats: dict, thumb_meta: dict | None):
    """Align thumbnail samples to the histogram's raw numeric domain."""
    samples = np.asarray(thumb, dtype=np.float64)
    if samples.ndim != 2 or not np.isfinite(samples).all():
        return None
    histogram_domain = stats.get("value_domain")
    thumbnail_domain = (
        thumb_meta.get("value_domain") if isinstance(thumb_meta, dict) else None
    )
    if not isinstance(histogram_domain, dict) or not isinstance(thumbnail_domain, dict):
        return None

    scalarization = histogram_domain.get("scalarization")
    if scalarization is not None:
        if (
            not _is_exact_rgb_scalar_domain(histogram_domain)
            or not _is_exact_rgb24_source_domain(thumbnail_domain)
            or scalarization.get("method") != "imagej_weighted_rgb_intensity"
            or scalarization.get("source_pixel_type") != "rgb24"
            or scalarization.get("rounding") != "nearest_integer_half_up"
        ):
            return None
        weights = scalarization.get("weights")
        if not isinstance(weights, dict):
            return None
        try:
            red_weight = float(weights["red"])
            green_weight = float(weights["green"])
            blue_weight = float(weights["blue"])
        except (KeyError, TypeError, ValueError, OverflowError):
            return None
        rgb_weights = np.asarray(
            [red_weight, green_weight, blue_weight], dtype=np.float64
        )
        if (
            not np.isfinite(rgb_weights).all()
            or np.any(rgb_weights < 0)
            or not math.isclose(
                float(rgb_weights.sum()), 1.0, rel_tol=0.0, abs_tol=1e-9
            )
        ):
            return None
        packed = _decode_rgb24_samples(samples)
        if packed is None:
            return None
        red = ((packed >> 16) & 0xff).astype(np.float64)
        green = ((packed >> 8) & 0xff).astype(np.float64)
        blue = (packed & 0xff).astype(np.float64)
        # ImageJ ColorProcessor uses (int)(weighted + 0.5). The server admits
        # only non-negative normalized weights, so floor exactly matches that
        # Java cast and its published nearest-half-up contract.
        scalar = np.floor(
            red * red_weight + green * green_weight + blue * blue_weight + 0.5
        )
        if np.any(scalar < 0) or np.any(scalar > 255):
            return None
        return scalar

    for key in (
        "representation",
        "pixel_type",
        "signed",
        "density_calibrated",
        "acquisition_min_raw",
        "acquisition_max_raw",
        "acquisition_min_calibrated",
        "acquisition_max_calibrated",
    ):
        if histogram_domain.get(key) != thumbnail_domain.get(key):
            return None
    return samples


def _fragment_thresholds(
    thumb: np.ndarray, stats: dict | None, thumb_meta: dict | None = None
) -> str:
    """Sentence 6: Otsu / Li / Triangle object counts on the thumbnail."""
    if stats is None:
        return "Auto-threshold counts are unavailable because no valid Fiji histogram is available."
    threshold_thumb = _threshold_thumbnail(thumb, stats, thumb_meta)
    if threshold_thumb is None:
        return (
            "Auto-threshold counts are unavailable because the histogram and thumbnail "
            "value domains cannot be aligned."
        )
    otsu_t = _otsu_threshold_from_hist(stats)
    li_t = _li_threshold_from_hist(stats)
    tri_t = _triangle_threshold_from_hist(stats)
    # Non-uint8 histogram responses currently omit bin origin/width. In that
    # case derive thresholds from the actual thumbnail samples instead of
    # pretending observed min/max define the histogram bins.
    if otsu_t is None:
        otsu_t = _otsu_threshold(threshold_thumb)
    if li_t is None:
        li_t = _li_threshold(threshold_thumb)
    if tri_t is None:
        tri_t = _triangle_threshold(threshold_thumb)
    n_otsu = _count_4_connected(threshold_thumb > otsu_t)
    n_li = _count_4_connected(threshold_thumb > li_t)
    n_tri = _count_4_connected(threshold_thumb > tri_t)
    return (
        "Auto-thresholds produce {} connected components with Otsu, {} with Li and {} "
        "with Triangle on a 512-pixel thumbnail."
    ).format(n_otsu, n_li, n_tri)


def _fragment_artifacts(
    thumb: np.ndarray,
    bit_depth: int,
    meta: dict | None = None,
    analysis_thumb: np.ndarray | None = None,
) -> str:
    """Sentence 7: clipped blacks + quadrant saturation + stripe detection.

    All three sub-checks always contribute a phrase — either an issue
    warning or a "no X" clean note. Clean notes are merged into a
    single closing sentence; issues become their own sentences so the
    numbers stand out.
    """
    h, w = thumb.shape
    issues = []
    clean = []

    meta = meta or {}
    domain = meta.get("value_domain") or {}
    floor = domain.get("acquisition_min_raw")
    ceiling = domain.get("acquisition_max_raw")
    counts_exact = bool(meta.get("acquisition_limit_counts_exact"))
    n_pixels = meta.get("nPixels")
    min_count = meta.get("acquisition_min_count")
    if (
        floor is None
        or not counts_exact
        or not isinstance(n_pixels, int)
        or n_pixels <= 0
        or not isinstance(min_count, int)
    ):
        clean.append("acquisition-minimum clipping check unavailable")
    else:
        clipped_frac = float(min_count) / float(n_pixels) * 100.0
        if clipped_frac > 1.0:
            issues.append(
                "pixels at the raw acquisition minimum {} account for {}".format(
                    _fmt_int(floor), _fmt_pct(clipped_frac)
                )
            )
        else:
            clean.append("no substantial acquisition-minimum clipping")

    if ceiling is None:
        clean.append("saturation-location check unavailable without a raw acquisition maximum")
    else:
        mid_y = h // 2
        mid_x = w // 2
        quadrants = [
            ("top-left", thumb[:mid_y, :mid_x]),
            ("top-right", thumb[:mid_y, mid_x:]),
            ("bottom-left", thumb[mid_y:, :mid_x]),
            ("bottom-right", thumb[mid_y:, mid_x:]),
        ]
        if any(
            q.size < 16 or q.shape[0] < 2 or q.shape[1] < 2
            for _, q in quadrants
        ):
            clean.append("quadrant saturation localization unavailable at this sampling")
        else:
            q_rates = [float((q >= ceiling).mean()) for _, q in quadrants]
            max_idx = int(np.argmax(q_rates))
            others = [rate for i, rate in enumerate(q_rates) if i != max_idx]
            mean_others = float(sum(others)) / len(others)
            dominant = (
                q_rates[max_idx] > 0
                and (
                    q_rates[max_idx] > 5.0 * mean_others
                    if mean_others > 0
                    else True
                )
            )
            if dominant:
                corner = quadrants[max_idx][0]
                issues.append(
                    "the {} quadrant has {:.1f}% of pixels at the raw acquisition maximum "
                    "versus {:.1f}% across other quadrants, suggesting a saturated patch"
                    .format(corner, q_rates[max_idx] * 100.0, mean_others * 100.0)
                )
            else:
                clean.append("no quadrant saturation")

    stripe_thumb = None
    if analysis_thumb is not None:
        candidate = np.asarray(analysis_thumb, dtype=np.float64)
        if candidate.shape == thumb.shape and np.isfinite(candidate).all():
            stripe_thumb = candidate
    elif domain.get("pixel_type") != "rgb24":
        stripe_thumb = thumb

    if stripe_thumb is None:
        clean.append("stripe-pattern check unavailable for packed RGB values")
    elif h >= 2 and w >= 2:
        # Packed RGB integers have no meaningful scalar ordering. For RGB,
        # callers pass the same weighted scalar thumbnail used by histogram
        # thresholding; other image types continue to use their raw samples.
        row_means = stripe_thumb.mean(axis=1)
        col_means = stripe_thumb.mean(axis=0)
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

def _fetch_roi_overlay(info: dict):
    """Read ROI/overlay facts bound to the same immutable image snapshot."""
    resp = _safe_send("get_display_state", **_snapshot_payload(info))
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None
    data = resp.get("result") or {}
    if not isinstance(data, dict) or not _metadata_matches_info(data, info):
        return None
    return data


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
    info_resp = _safe_send("get_image_info", force=True)
    if not isinstance(info_resp, dict) or not info_resp.get("ok"):
        err = _response_error_text(info_resp, "no reply from Fiji")
        return "describe_image: cannot read active image info ({}).".format(err or "unknown error")
    info = info_resp.get("result") or {}
    if not isinstance(info, dict) or not info:
        return "describe_image: no active image."
    try:
        image_id = info["image_id"]
        width = _exact_int(info["width"], "width", 1)
        height = _exact_int(info["height"], "height", 1)
        image_revision = _exact_int(info["image_revision"], "image_revision", 1)
        display_revision = _exact_int(info["display_revision"], "display_revision", 1)
        channel = _exact_int(info["channel"], "channel", 1)
        slice_start = _exact_int(info["sliceStart"], "sliceStart", 1)
        slice_end = _exact_int(info["sliceEnd"], "sliceEnd", 1)
        slice_axis = info["sliceAxis"]
        frame = _exact_int(info["frame"], "frame", 1)
        channels = _exact_int(info["channels"], "channels", 1)
        slices = _exact_int(info["slices"], "slices", 1)
        frames = _exact_int(info["frames"], "frames", 1)
        value_domain = _decode_value_domain(info)
        if not isinstance(image_id, str) or not isinstance(slice_axis, str):
            raise TypeError("image_id and sliceAxis must be strings")
    except (KeyError, TypeError, ValueError) as exc:
        return "describe_image: cannot attribute image axes ({}).".format(exc)
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
        return "describe_image: cannot attribute image axes (invalid axis sizes)."

    bit_depth = _bit_depth_from_type(info.get("type", ""))

    thumb_arr, thumb_meta = _fetch_thumbnail(info)
    plane_meta = info

    hist_stats = None
    hist_error = None
    histogram_payload = _snapshot_payload(info)
    histogram_payload["scope"] = "full_plane"
    hist_resp = _safe_send("get_histogram", **histogram_payload)
    hist_result = hist_resp.get("result") if isinstance(hist_resp, dict) else None
    if (
        isinstance(hist_resp, dict)
        and hist_resp.get("ok")
        and isinstance(hist_result, dict)
        and hist_result.get("scope") == "full_plane"
        and _metadata_matches_info(hist_result, info)
    ):
        candidate_stats = _hist_stats(hist_result)
        if (
            candidate_stats is None
            or candidate_stats["n_pixels"] != width * height
        ):
            hist_error = "Fiji returned an invalid histogram payload"
        else:
            hist_stats = candidate_stats
    elif isinstance(hist_resp, dict) and hist_resp.get("ok"):
        hist_error = "histogram snapshot/plane did not match image info"
    elif isinstance(hist_resp, dict):
        hist_error = _response_error_text(hist_resp, "unknown histogram error")
    else:
        hist_error = "no reply from Fiji"
    roi_data = _fetch_roi_overlay(info)

    fragments = [_fragment_header(info), _fragment_calibration(info)]
    fragments.append(_fragment_plane_attribution(plane_meta))
    if hist_stats is not None:
        fragments.append(_fragment_intensity(hist_stats, bit_depth))
        fragments.append(_fragment_saturation(hist_stats, bit_depth))
        fragments.append(_fragment_histogram_shape(hist_stats))
    elif hist_error:
        fragments.append(
            "Histogram-derived intensity statistics are unavailable ({}).".format(hist_error)
        )
    if thumb_arr is not None:
        aligned_thumb = (
            _threshold_thumbnail(thumb_arr, hist_stats, thumb_meta)
            if hist_stats is not None
            else None
        )
        fragments.append(_fragment_thresholds(thumb_arr, hist_stats, thumb_meta))
        fragments.append(
            _fragment_artifacts(
                thumb_arr,
                bit_depth,
                thumb_meta,
                analysis_thumb=aligned_thumb,
            )
        )
    else:
        fragments.append("Thumbnail-based threshold and artifact checks are unavailable.")
    fragments.append(_fragment_roi_overlay(roi_data))

    return " ".join(f for f in fragments if f)
