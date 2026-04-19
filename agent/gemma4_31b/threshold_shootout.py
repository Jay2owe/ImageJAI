"""Threshold shootout — try several auto-threshold methods side by side.

Phase 6 of docs/ollama/plan.md. Thresholding is heavily image-
dependent and models tend to guess one method and stick with it;
running several in parallel lets the data pick rather than the
agent's default guess. All the work happens on the Fiji side via
the existing explore_thresholds command in TCPCommandServer — this
module is a thin client that collates the results, renders a
labelled montage and drops it in AI_Exports/ next to the image.
"""

from __future__ import annotations

import base64
import datetime
import io
import os
import pathlib
import socket

from PIL import Image, ImageDraw, ImageFont

from . import active_image
from .registry import send, tool


METHODS = ["Otsu", "Li", "Triangle", "Minimum", "Huang"]

_PANEL_SIZE = 256
_LABEL_HEIGHT = 36
_PANEL_PADDING = 4
_BACKGROUND = (24, 24, 24)
_LABEL_BG = (40, 40, 40)
_LABEL_FG = (240, 240, 240)


def _safe_send(command, **payload):
    """Wrap registry.send so connection errors surface as a dict rather than an exception."""
    try:
        return send(command, **payload)
    except (ConnectionRefusedError, socket.timeout, OSError) as exc:
        return {"ok": False, "error": "Fiji TCP server unreachable: {}".format(exc)}


def _decode_thumbnail(b64):
    """Decode a base64 PNG string into a PIL Image, or return None on any failure."""
    if not isinstance(b64, str) or not b64:
        return None
    try:
        raw = base64.b64decode(b64)
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except (ValueError, OSError):
        return None


def _load_font():
    """Return a TrueType font that exists on Windows/mac/linux, falling back to the PIL default."""
    for name in ("arial.ttf", "DejaVuSans.ttf", "Helvetica.ttf"):
        try:
            return ImageFont.truetype(name, 16)
        except (OSError, IOError):
            continue
    return ImageFont.load_default()


def _to_pil(obj):
    """Accept a PIL Image, raw PNG bytes or a 2D/3D numpy array and return a PIL Image."""
    if obj is None:
        return None
    if isinstance(obj, Image.Image):
        return obj.convert("RGB")
    if isinstance(obj, (bytes, bytearray)):
        try:
            return Image.open(io.BytesIO(bytes(obj))).convert("RGB")
        except (ValueError, OSError):
            return None
    try:
        import numpy as np
    except ImportError:
        return None
    if isinstance(obj, np.ndarray):
        arr = obj
        if arr.dtype != np.uint8:
            if arr.size == 0:
                return None
            lo = float(arr.min())
            hi = float(arr.max())
            if hi > lo:
                arr = ((arr - lo) / (hi - lo) * 255.0).astype(np.uint8)
            else:
                arr = np.zeros_like(arr, dtype=np.uint8)
        if arr.ndim == 2:
            return Image.fromarray(arr, mode="L").convert("RGB")
        if arr.ndim == 3 and arr.shape[2] in (3, 4):
            return Image.fromarray(arr).convert("RGB")
    return None


def _render_panel(image, name, count, font):
    """Render one labelled panel with the thumbnail on top and 'Name — count=N' below."""
    panel = Image.new("RGB", (_PANEL_SIZE, _PANEL_SIZE + _LABEL_HEIGHT), _BACKGROUND)
    if image is not None:
        fit = image.copy()
        fit.thumbnail((_PANEL_SIZE, _PANEL_SIZE))
        ox = (_PANEL_SIZE - fit.width) // 2
        oy = (_PANEL_SIZE - fit.height) // 2
        panel.paste(fit, (ox, oy))
    else:
        draw_empty = ImageDraw.Draw(panel)
        draw_empty.rectangle(
            [(0, 0), (_PANEL_SIZE - 1, _PANEL_SIZE - 1)], outline=(80, 80, 80)
        )
        draw_empty.text(
            (_PANEL_SIZE // 2 - 40, _PANEL_SIZE // 2 - 10),
            "no thumbnail",
            fill=(160, 160, 160),
            font=font,
        )

    draw = ImageDraw.Draw(panel)
    draw.rectangle(
        [(0, _PANEL_SIZE), (_PANEL_SIZE - 1, _PANEL_SIZE + _LABEL_HEIGHT - 1)],
        fill=_LABEL_BG,
    )
    count_text = "n={}".format(count) if count is not None else "n=?"
    label = "{}  {}".format(name, count_text)
    draw.text((8, _PANEL_SIZE + 8), label, fill=_LABEL_FG, font=font)
    return panel


def _compose_montage(panels, out_path):
    """Assemble labelled panels into a single horizontal montage PNG and save it.

    Args:
        panels: list of dicts with keys 'name', 'count' and 'image'
            (PIL.Image, PNG bytes or 2D numpy array; None draws a
            placeholder).
        out_path: absolute PNG path to write.
    Returns:
        The absolute path that was written.
    """
    font = _load_font()
    rendered = []
    for p in panels:
        rendered.append(
            _render_panel(_to_pil(p.get("image")), p.get("name", "?"), p.get("count"), font)
        )
    if not rendered:
        raise ValueError("no panels to render")

    panel_w = rendered[0].width
    panel_h = rendered[0].height
    total_w = panel_w * len(rendered) + _PANEL_PADDING * (len(rendered) + 1)
    total_h = panel_h + _PANEL_PADDING * 2
    montage = Image.new("RGB", (total_w, total_h), _BACKGROUND)
    x = _PANEL_PADDING
    for panel in rendered:
        montage.paste(panel, (x, _PANEL_PADDING))
        x += panel_w + _PANEL_PADDING

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    montage.save(out_path, format="PNG")
    return out_path


def _coverage_pct(value):
    """Convert the server's coverage fraction (0..1) to a rounded percentage."""
    try:
        pct = float(value) * 100.0
    except (TypeError, ValueError):
        return None
    return round(pct, 1)


def _derive_coverage_pct(count, mean_area, total_pixels):
    """Compute coverage percentage from count × meanArea / total_pixels.

    The Fiji server currently returns coverage=0.0 for every method —
    a Java-side bug. Falling back to this client-side estimate keeps
    the agent's verdict table useful until the server is fixed.

    Args:
        count: number of objects detected at this threshold.
        mean_area: mean area per object (pixels).
        total_pixels: total image area (pixels).
    """
    try:
        c = float(count)
        m = float(mean_area)
        t = float(total_pixels)
    except (TypeError, ValueError):
        return None
    if t <= 0:
        return None
    pct = (c * m / t) * 100.0
    if pct < 0:
        return 0.0
    if pct > 100:
        return 100.0
    return round(pct, 1)


def _mean_size(value):
    """Round the server's meanArea to one decimal place, or None when missing."""
    try:
        return round(float(value), 1)
    except (TypeError, ValueError):
        return None


def _object_count(value):
    """Coerce the server's objectCount to int when possible."""
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


@tool
def threshold_shootout() -> dict:
    """Run Otsu, Li, Triangle, Minimum and Huang on the active image side by side and save a labelled montage to AI_Exports/.

    Args:
        None.
    """
    export_folder = active_image.current_export_folder()
    if not export_folder:
        # Sample images (e.g. Blobs) have no file on disk so there is no
        # AI_Exports/ to target. The shootout montage is diagnostic and
        # throwaway, so fall back to the repo-local temp folder rather
        # than refusing the run.
        tmp = pathlib.Path(__file__).resolve().parent.parent / ".tmp" / "shootout"
        try:
            tmp.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            return {
                "ok": False,
                "error": "could not create fallback shootout folder: {}".format(exc),
            }
        export_folder = str(tmp)

    resp = _safe_send("explore_thresholds", methods=METHODS)
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = resp.get("error") if isinstance(resp, dict) else "no reply from Fiji"
        return {"ok": False, "error": err or "explore_thresholds failed"}

    result = resp.get("result") or {}
    server_results = result.get("results")
    if not isinstance(server_results, list) or not server_results:
        return {"ok": False, "error": "explore_thresholds returned no results"}

    # Fetch image dimensions once so we can derive coverage client-side
    # (the server's coverage field is currently 0.0 for every method).
    info_resp = _safe_send("get_image_info")
    info_result = info_resp.get("result", {}) if isinstance(info_resp, dict) else {}
    try:
        total_pixels = int(info_result.get("width", 0)) * int(info_result.get("height", 0))
    except (TypeError, ValueError):
        total_pixels = 0

    by_name = {}
    for entry in server_results:
        if not isinstance(entry, dict):
            continue
        name = entry.get("method") or "?"
        by_name[name] = entry

    panels = []
    methods_summary = []
    missing_methods = []
    for method_name in METHODS:
        entry = by_name.get(method_name)
        if not isinstance(entry, dict):
            missing_methods.append(method_name)
            panels.append({"name": method_name, "count": None, "image": None})
            methods_summary.append(
                {
                    "name": method_name,
                    "success": False,
                    "count": None,
                    "coverage_pct": None,
                    "mean_size": None,
                    "summary": "missing from Fiji reply",
                }
            )
            continue
        count = _object_count(entry.get("objectCount"))
        mean_sz = _mean_size(entry.get("meanArea"))
        # Prefer client-side coverage (server returns 0.0). Fall back to
        # the server value only if we can't compute locally.
        derived = _derive_coverage_pct(count, mean_sz, total_pixels)
        coverage_pct = derived if derived is not None else _coverage_pct(entry.get("coverage"))
        thumb = _decode_thumbnail(entry.get("thumbnail"))
        panels.append({"name": method_name, "count": count, "image": thumb})
        methods_summary.append(
            {
                "name": method_name,
                "success": bool(entry.get("success")),
                "count": count,
                "coverage_pct": coverage_pct,
                "mean_size": mean_sz,
                "summary": entry.get("summary"),
            }
        )

    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = os.path.join(
        export_folder, "threshold_shootout_{}.png".format(timestamp)
    )
    try:
        _compose_montage(panels, out_path)
    except (OSError, ValueError) as exc:
        return {"ok": False, "error": "montage write failed: {}".format(exc)}

    out = {
        "montage_path": os.path.abspath(out_path),
        "methods": methods_summary,
        "reasoning": result.get("reasoning"),
    }
    recommended = result.get("recommended")
    if isinstance(recommended, str) and recommended:
        out["recommended"] = recommended
    if missing_methods:
        out["missing_methods"] = missing_methods
    return out
