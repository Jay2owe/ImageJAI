"""Phase 5 — triage a newly opened image.

Runs a short, non-blocking checklist on the active Fiji image and
returns a list of one-line warning strings. Empty list means nothing
looks wrong. The agent decides whether to ask the user or press on.

Every check has the same shape: it takes the already-fetched
get_image_info / get_histogram payload (never re-queries Fiji) and
returns either None (pass) or a short warning string with a measured
number baked in where possible. Checks do not mutate state and never
raise — a check that cannot compute its answer returns None so the
triage is always advisory, never a blocker.
"""

from __future__ import annotations

import re

from .registry import send, tool


# --- helpers --------------------------------------------------------------


def _ok_result(resp: object) -> dict | None:
    """Return resp['result'] when it is a dict-shaped success, else None."""
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None
    result = resp.get("result")
    return result if isinstance(result, dict) else None


def _bit_depth_max(type_str: object) -> int | None:
    """Return the integer bit-depth maximum for known raster types, else None.

    32-bit float and RGB images have no single "saturation ceiling", so we
    skip them rather than produce a misleading number.
    """
    if not isinstance(type_str, str):
        return None
    t = type_str.strip().lower()
    if t.startswith("8-bit") and "color" not in t:
        return 255
    if t.startswith("16-bit"):
        return 65535
    return None


def _is_uncalibrated(calibration: object) -> bool:
    """True when the image has no real pixel-size scale.

    StateInspector returns an empty string when Calibration.scaled() is
    false (the default "1 pixel" state). We also accept the literal
    "1 pixel/px" form as uncalibrated, in case the calibration round-trips
    through text at some point.
    """
    if not isinstance(calibration, str):
        return True
    s = calibration.strip().lower()
    if not s:
        return True
    if "pixel" in s and re.search(r"(^|[^0-9])1(\.0+)?\s*(pixel|pixels)", s):
        return True
    return False


_Z_HINT = re.compile(r"(?:zstack|z[-_]stack|(?<![a-z])stack(?![a-z])|(?<![a-z])z\d)", re.IGNORECASE)
_T_HINT = re.compile(r"(?:timelapse|time[-_]lapse|(?<![a-z])time(?![a-z])|(?<![a-z])t\d)", re.IGNORECASE)


def _title_suggests_zstack(title: object) -> bool:
    return isinstance(title, str) and bool(_Z_HINT.search(title))


def _title_suggests_timelapse(title: object) -> bool:
    return isinstance(title, str) and bool(_T_HINT.search(title))


# --- checks ---------------------------------------------------------------


def _check_uncalibrated(info: dict) -> str | None:
    if _is_uncalibrated(info.get("calibration")):
        return "No pixel-size calibration — set with Image > Properties before measuring."
    return None


def _check_saturated(info: dict, hist: dict | None) -> str | None:
    if not hist:
        return None
    ceiling = _bit_depth_max(info.get("type"))
    if ceiling is None:
        return None
    n_pixels = hist.get("nPixels")
    bins = hist.get("bins")
    max_value = hist.get("max")
    if not isinstance(n_pixels, (int, float)) or n_pixels <= 0:
        return None
    if not isinstance(bins, list) or not bins:
        return None
    if not isinstance(max_value, (int, float)) or max_value < ceiling - 0.5:
        return None
    try:
        top = float(bins[-1])
    except (TypeError, ValueError):
        return None
    fraction = top / float(n_pixels)
    if fraction > 0.01:
        return "Saturated pixels are {:.1%} of the image — lower exposure or flag the data as clipped.".format(fraction)
    return None


def _check_clipped_blacks(info: dict, hist: dict | None) -> str | None:
    if not hist:
        return None
    if _bit_depth_max(info.get("type")) is None:
        return None
    n_pixels = hist.get("nPixels")
    bins = hist.get("bins")
    min_value = hist.get("min")
    if not isinstance(n_pixels, (int, float)) or n_pixels <= 0:
        return None
    if not isinstance(bins, list) or not bins:
        return None
    if not isinstance(min_value, (int, float)) or min_value > 0.5:
        return None
    try:
        bottom = float(bins[0])
    except (TypeError, ValueError):
        return None
    fraction = bottom / float(n_pixels)
    if fraction > 0.01:
        return "Clipped blacks at {:.1%} of pixels — background is pinned to zero, consider offset correction.".format(fraction)
    return None


def _check_collapsed_zstack(info: dict) -> str | None:
    slices = info.get("slices")
    if not isinstance(slices, int) or slices != 1:
        return None
    if _title_suggests_zstack(info.get("title")):
        return "Filename suggests a z-stack but the image has only 1 slice — did the stack get collapsed on open?"
    return None


def _check_collapsed_timelapse(info: dict) -> str | None:
    frames = info.get("frames")
    if not isinstance(frames, int) or frames != 1:
        return None
    if _title_suggests_timelapse(info.get("title")):
        return "Filename suggests a time-lapse but the image has only 1 frame — did the time axis get dropped on open?"
    return None


def _check_underexposed_8bit(info: dict, hist: dict | None) -> str | None:
    if _bit_depth_max(info.get("type")) != 255:
        return None
    if not hist:
        return None
    max_value = hist.get("max")
    if not isinstance(max_value, (int, float)):
        return None
    usage = max_value / 255.0
    if usage <= 0.20:
        return "8-bit image uses only {:.0%} of its dynamic range (max {:.0f}/255) — likely under-exposed.".format(usage, max_value)
    return None


def _check_many_channels(info: dict) -> str | None:
    channels = info.get("channels")
    if not isinstance(channels, int) or channels <= 4:
        return None
    if not info.get("isHyperstack"):
        return None
    return "Hyperstack reports {} channels — unusually many, the channel axis may have been mis-identified.".format(channels)


# --- public tool ----------------------------------------------------------


@tool
def triage_image() -> list:
    """Run quick setup checks on the active image and return a list of warnings (empty means nothing looks wrong).

    Args:
    """
    try:
        info_resp = send("get_image_info")
    except Exception as exc:
        return ["Cannot triage: Fiji TCP server not reachable ({}: {}).".format(type(exc).__name__, exc)]

    info = _ok_result(info_resp)
    if info is None:
        return ["Cannot triage: no active image in Fiji."]

    try:
        hist_resp = send("get_histogram")
    except Exception:
        hist_resp = None
    hist = _ok_result(hist_resp) if hist_resp is not None else None

    warnings: list = []
    for check in (
        _check_uncalibrated(info),
        _check_saturated(info, hist),
        _check_clipped_blacks(info, hist),
        _check_collapsed_zstack(info),
        _check_collapsed_timelapse(info),
        _check_underexposed_8bit(info, hist),
        _check_many_channels(info),
    ):
        if check is not None:
            warnings.append(check)
    return warnings
