"""Small, deterministic descriptions of tool returns, for people reading chat.

Only the display is condensed. Callers retain the original return separately.
"""
from __future__ import annotations

import csv
import io
import json
import re
from dataclasses import dataclass


MAX_SUMMARY_CHARS = 160


@dataclass(frozen=True)
class ResultSummary:
    text: str
    kind: str = "success"


def _short(value: str, limit: int = MAX_SUMMARY_CHARS) -> str:
    text = re.sub(r"[\x00-\x1f\x7f-\x9f]", " ", value)
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit - 1].rstrip() + "…"


def _message(value) -> str:
    if isinstance(value, str):
        return _short(next((line for line in value.splitlines() if line.strip()), ""))
    if isinstance(value, dict):
        for key in ("message", "error", "reason", "detail"):
            text = _message(value.get(key))
            if text:
                return text
        code = value.get("code")
        if isinstance(code, str):
            return _short(code.replace("_", " ").capitalize())
    return ""


def _number(value) -> str:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return ""
    return f"{value:.4g}" if isinstance(value, float) else str(value)


def _count(n: int, singular: str, plural: str | None = None) -> str:
    return f"{n} {singular if n == 1 else plural or singular + 's'}"


def _names(items: list) -> str:
    names = []
    for item in items[:2]:
        title = item.get("title") or item.get("name") if isinstance(item, dict) else item
        if isinstance(title, str) and title:
            names.append(_short(title, 45))
    suffix = f" and {len(items) - 2} more" if len(items) > 2 else ""
    return ", ".join(names) + suffix


def _table(text: str) -> str:
    if not text.strip():
        return "No measurements"
    # Fiji returns either CSV or tab-separated measurements. Respect quoted
    # newlines instead of counting physical lines as measurements.
    delimiter = "\t" if "\t" in text.splitlines()[0] else ","
    try:
        rows = csv.reader(io.StringIO(text), delimiter=delimiter)
        header = next(rows, [])
        count = sum(1 for row in rows if any(cell.strip() for cell in row))
        columns = len(header)
        return f"Found {_count(count, 'measurement')} · {_count(columns, 'column')}"
    except (csv.Error, StopIteration):
        return "Measurements returned"


def _image(image: object) -> str:
    if not isinstance(image, dict):
        return ""
    title = image.get("title") or image.get("name")
    parts = [_short(title, 60)] if isinstance(title, str) and title else []
    width, height = _number(image.get("width")), _number(image.get("height"))
    if width and height:
        parts.append(f"{width} × {height} pixels")
    frames = image.get("frames", image.get("nFrames"))
    if isinstance(frames, int) and frames > 1:
        parts.append(_count(frames, "frame"))
    return " · ".join(parts)


def summarize_result(name: str, ok: bool, raw: str) -> ResultSummary:
    """Describe known Fiji payloads without exposing transport metadata/arrays."""
    try:
        data = json.loads(raw)
    except (ValueError, RecursionError):
        data = raw
    envelope = data if isinstance(data, dict) else {}
    body = envelope.get("result", data)
    fields = body if isinstance(body, dict) else {}
    error = envelope.get("error") or fields.get("error")
    code = error.get("code") if isinstance(error, dict) else None
    operation = envelope.get("operation") or fields.get("operation") or {}
    status = fields.get("status") or (operation.get("status") if isinstance(operation, dict) else None)
    pending = code == "operation_in_progress" or status in ("pending", "running", "in_progress", "queued")
    pending = pending or fields.get("pending") is True
    if pending:
        text = {"open_image": "Image is opening", "run_macro_async": "Macro is running",
                "job_status": "Job is running", "poll_operation": "Fiji is still working"}.get(name, "Fiji is still working")
        return ResultSummary(text, "pending")
    failed = not ok or envelope.get("ok") is False or fields.get("success") is False
    if failed:
        return ResultSummary(_message(error) or _message(fields.get("message")) or
                             (_message(data) if isinstance(data, str) else "Operation failed"), "error")

    text = ""
    if name == "get_state" and fields:
        images = fields.get("allImages", fields.get("images"))
        active = fields.get("activeImage") or fields.get("active_image") or {}
        if isinstance(images, list):
            text = _count(len(images), "image") + " open" if images else "No images open"
        if isinstance(active, dict) and active:
            description = _image(active)
            if description:
                text += (" · Active: " if text else "Active: ") + description
    elif name in ("get_image_info", "get_metadata", "get_display_state"):
        text = _image(fields.get("image", fields)) if fields else ""
        if not text and name == "get_metadata":
            text = "Image metadata read"
    elif name == "get_results":
        table = body if isinstance(body, str) else fields.get("csv") or fields.get("text")
        if isinstance(table, str):
            text = _table(table)
        elif isinstance(fields.get("rows"), list):
            text = "Found " + _count(len(fields["rows"]), "measurement")
    elif name == "get_open_windows":
        parts = []
        for key, label in (("images", "image"), ("nonImages", "other window"), ("dialogs", "dialog")):
            if isinstance(fields.get(key), list):
                parts.append(_count(len(fields[key]), label))
        text = "Open: " + ", ".join(parts) if parts else ""
    elif name in ("get_rois", "region_stats") and isinstance(fields.get("rois"), list):
        text = "Found " + _count(len(fields["rois"]), "region")
    elif name in ("list_dialog_components", "get_dialogs"):
        for key, label in (("dialogs", "dialog"), ("components", "dialog control")):
            if isinstance(fields.get(key), list):
                text = "Found " + _count(len(fields[key]), label)
                break
        if isinstance(body, list):
            text = "Found " + _count(len(body), "dialog control")
    elif name == "capture_image":
        text = "Image captured"
        width, height = _number(fields.get("width")), _number(fields.get("height"))
        if width and height:
            text += f" · {width} × {height} pixels"
    elif name in ("get_log", "get_console"):
        log = body if isinstance(body, str) else fields.get("log") or fields.get("text") or fields.get("combined")
        if isinstance(log, str):
            text = "Log is empty" if not log.strip() else "Read " + _count(len(log.splitlines()), "log line")
    elif name in ("get_histogram", "region_stats", "quick_object_count", "line_profile"):
        stats = fields.get("statistics") or fields.get("stats") or fields
        if isinstance(stats, dict):
            parts = []
            for key, label in (("count", "Count"), ("mean", "Mean"), ("min", "Min"), ("max", "Max")):
                value = _number(stats.get(key))
                if value:
                    parts.append(f"{label}: {value}")
            text = " · ".join(parts[:3])
        bins = fields.get("bins", fields.get("histogram"))
        if not text and isinstance(bins, list):
            text = "Read " + _count(len(bins), "histogram bin")

    if not text:
        delta = envelope.get("stateDelta") or fields.get("stateDelta") or {}
        new_images = fields.get("newImages") or (delta.get("newImages") if isinstance(delta, dict) else None)
        if isinstance(new_images, list) and new_images:
            text = "Opened " + (_names(new_images) or _count(len(new_images), "image"))
        elif name in ("run_macro", "run_macro_async", "run_script"):
            text = "Script completed" if name == "run_script" else "Macro completed"
        elif name == "open_image":
            description = _image(fields)
            text = "Opened " + description if description else "Image opened"
        else:
            text = _message(fields.get("message"))
            if not text and isinstance(body, str):
                # Unknown multiline returns stay in the detail view. Avoid
                # leaking JSON fragments or file contents as a 'summary'.
                if "\n" not in body.strip() and not body.lstrip().startswith(("{", "[")):
                    text = _short(body)
                else:
                    text = "Read " + _count(len(body.splitlines()), "line")
            if not text and isinstance(body, list):
                text = "Returned " + _count(len(body), "item")
    warning = _message(envelope.get("warning") or fields.get("warning"))
    if warning:
        return ResultSummary(_short((text + " · " if text else "") + warning), "warning")
    return ResultSummary(_short(text or "Completed"))


def formatted_return(raw: str) -> str:
    """Pretty-print JSON on demand; leave other tool output intact."""
    try:
        data = json.loads(raw)
        if isinstance(data, (dict, list)):
            return json.dumps(data, indent=2, ensure_ascii=False)
    except (ValueError, RecursionError):
        pass
    return raw
