"""Readable, bounded view of Fiji's live event stream."""
from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from rich.markup import escape

MAX_EVENTS = 1000
_QUIET_TOPICS = {"heartbeat", "subscribed"}


@dataclass(frozen=True)
class EventLine:
    received_ms: int
    topic: str
    category: str
    message: str
    color: str

    def markup(self) -> str:
        stamp = datetime.fromtimestamp(self.received_ms / 1000).strftime("%H:%M:%S")
        return (f"[dim]{stamp}[/dim] "
                f"[{self.color}]{escape(self.message)}[/{self.color}]")


def _value(data: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = data.get(key)
        if isinstance(value, (str, int, float)) and str(value).strip():
            return str(value).strip()[:100]
    return ""


def describe_event(frame: Any, *, now_ms: int | None = None) -> EventLine | None:
    """Summarise one server frame without showing raw JSON in the panel."""
    if not isinstance(frame, dict):
        return None
    topic = str(frame.get("event") or frame.get("topic") or frame.get("type") or "").strip()
    if not topic or topic in _QUIET_TOPICS:
        return None
    data = frame.get("data")
    if isinstance(data, str):
        data = {"message": data}
    if not isinstance(data, dict):
        data = {"message": frame.get("message", "")}
    title = _value(data, "title", "image_title", "name")
    category = topic.split(".", 1)[0]
    color = "cyan"

    if topic == "image.opened":
        title = title or "image"
        dims = data.get("dims") if isinstance(data.get("dims"), dict) else {}
        size = f" ({dims['width']} × {dims['height']})" if dims.get("width") and dims.get("height") else ""
        message, color = f"Opened image: {title}{size}", "green"
    elif topic == "image.closed":
        message, color = f"Closed image: {title or 'image'}", "dim"
    elif topic == "image.updated":
        title = title or "image"
        message = f"Active image: {title}" if data.get("reason") == "active_changed" else f"Image updated: {title}"
    elif topic == "macro.started":
        message, color = "Macro started", "cyan"
    elif topic == "macro.completed":
        success = data.get("success") is not False
        message = "Macro finished" if success else f"Macro failed: {_value(data, 'error') or 'unknown error'}"
        color = "green" if success else "red"
    elif topic == "dialog.appeared":
        message, color = f"Dialog opened: {title or 'dialog'}", "yellow"
    elif topic == "dialog.closed":
        message, color = f"Dialog closed: {title or 'dialog'}", "dim"
    elif topic == "results.changed":
        rows = _value(data, "rows") or "?"
        cols = _value(data, "cols") or "?"
        message = f"Results table: {rows} rows, {cols} columns"
    elif topic == "memory.pressure":
        pct = _value(data, "used_pct") or "?"
        message, color = f"Fiji memory is {pct}% full", "yellow"
    elif topic == "event_dropped":
        message, category, color = "Some live events were missed", "warning", "yellow"
    elif topic.startswith("job."):
        job = _value(data, "job_id", "id")
        prefix = f"Job {job}" if job else "Job"
        phase = topic.split(".", 1)[1].replace("_", " ")
        progress = _value(data, "percent", "progress_pct")
        message = f"{prefix}: {phase}" + (f" ({progress}%)" if progress else "")
        color = "red" if phase in {"failed", "cancelled"} else "green" if phase == "completed" else "cyan"
    elif topic.startswith("data_governance.posture."):
        category = "privacy"
        target = _value(data, "to").replace("_", " ").title()
        phase = topic.rsplit(".", 1)[-1]
        if phase in {"requested", "downshifted", "defaulted"} and target:
            message = f"Fiji privacy posture: {target}"
        elif phase == "upshift_refused":
            message = "Fiji kept the stronger privacy posture"
        else:
            message = f"Privacy posture {phase.replace('_', ' ')}"
        color = "yellow" if phase in {"downshifted", "defaulted", "upshift_refused"} else "cyan"
    elif topic.startswith(("safe_mode.", "data_governance.")):
        category = "warning"
        reason = _value(data, "message", "reason", "error")
        message = topic.replace(".", " ").replace("_", " ").capitalize()
        if reason:
            message += f": {reason}"
        color = "red" if "blocked" in topic else "yellow"
    else:
        message = topic.replace(".", " ").replace("_", " ").capitalize()
        detail = _value(data, "message", "title", "reason", "status")
        if detail:
            message += f": {detail}"
        category = category if category in {
            "image", "macro", "dialog", "results", "job", "memory"} else "other"
    category = "warning" if category == "memory" else category
    return EventLine(now_ms if now_ms is not None else int(time.time() * 1000),
                     topic, category, message, color)


class EventFeed:
    def __init__(self, limit: int = MAX_EVENTS) -> None:
        self.lines: deque[EventLine] = deque(maxlen=limit)

    def add(self, frame: Any, *, now_ms: int | None = None) -> EventLine | None:
        line = describe_event(frame, now_ms=now_ms)
        if line is None:
            return None
        if (self.lines and self.lines[-1].topic == line.topic
                and self.lines[-1].message == line.message
                and line.received_ms - self.lines[-1].received_ms < 1000):
            return None
        self.lines.append(line)
        return line

    def visible(self, category: str = "all", period: str = "all",
                search: str = "", *, now_ms: int | None = None) -> list[EventLine]:
        cutoff, needle = self._filter(period, search, now_ms)
        return [line for line in self.lines if self._matches(line, category, cutoff, needle)]

    def matches(self, line: EventLine, category: str = "all", period: str = "all",
                search: str = "", *, now_ms: int | None = None) -> bool:
        """Check one arrival without scanning the whole retained history."""
        cutoff, needle = self._filter(period, search, now_ms)
        return self._matches(line, category, cutoff, needle)

    @staticmethod
    def _filter(period, search, now_ms):
        now = now_ms if now_ms is not None else int(time.time() * 1000)
        cutoff = None
        if period == "5m":
            cutoff = now - 5 * 60_000
        elif period == "1h":
            cutoff = now - 60 * 60_000
        elif period == "today":
            current = datetime.fromtimestamp(now / 1000)
            cutoff = int(current.replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)
        needle = search.casefold().strip()
        return cutoff, needle

    @staticmethod
    def _matches(line, category, cutoff, needle):
        return ((category == "all" or line.category == category)
                and (cutoff is None or line.received_ms >= cutoff)
                and (not needle or needle in line.message.casefold() or needle in line.topic.casefold()))
