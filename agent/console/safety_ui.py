"""Confirmed Fiji tool safety status and a bounded recent-events view."""
from __future__ import annotations
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Label, Static

_RULES = {"file_delete": "File deletion was blocked", "roi_wipe": "Removing all regions was blocked",
          "bit_depth": "Reducing pixel precision was blocked", "calibration_loss": "Losing image calibration was blocked",
          "queue_storm": "Too many image changes were queued", "saveas_overwrite": "Overwriting a file was blocked"}

@dataclass(frozen=True)
class SafetyEvent:
    topic: str
    timestamp: float
    message: str
    color: str

@dataclass
class SafetyState:
    connected: bool = False
    enabled: bool | None = None
    recent: deque = field(default_factory=lambda: deque(maxlen=3))
    last_event_at: float = 0.0

    def update_status(self, connected: bool, enabled: bool | None = None) -> None:
        self.connected = connected
        self.enabled = enabled if connected else None

    def add(self, frame, now: float | None = None) -> bool:
        if not isinstance(frame, dict):
            return False
        topic = str(frame.get("event") or frame.get("topic") or "")
        if not topic.startswith("safe_mode."):
            return False
        data = frame.get("data") if isinstance(frame.get("data"), dict) else {}
        stamp = time.time() if now is None else now
        blocked = "blocked" in topic or topic.endswith("snapshot.committed")
        passed = topic in {"safe_mode.passed", "safe_mode.snapshot.released"}
        label = "Blocked" if blocked else "Passed" if passed else "Warning"
        reason = str(data.get("message") or data.get("reason") or data.get("error") or
                     _RULES.get(data.get("rule_id")) or topic.removeprefix("safe_mode.").replace("_", " ").replace(".", " "))
        detail = " ".join(reason.split())[:400]
        if data.get("target"):
            detail += "; target: " + str(data["target"])[:160]
        if data.get("line"):
            detail += "; macro line " + str(data["line"])
        self.recent.appendleft(SafetyEvent(topic, stamp, f"{label}: {detail}",
                                          "red" if blocked else "green" if passed else "yellow"))
        self.last_event_at = stamp
        return True

    def label(self, now: float | None = None) -> tuple[str, str]:
        if not self.connected:
            return "Safe mode: offline", "dim"
        if self.enabled is None:
            return "Safe mode: unconfirmed", "yellow"
        if not self.enabled:
            return "Safe mode: off", "yellow"
        stamp = time.time() if now is None else now
        if self.recent and stamp - self.last_event_at < 60:
            event = self.recent[0]
            return "Safe mode: " + event.message.split(":", 1)[0].lower(), event.color
        return "Safe mode: on", "green"

class SafetyEventsScreen(ModalScreen[None]):
    CSS = """
    SafetyEventsScreen { align: center middle; }
    #safety-box { width: 90%; height: auto; max-height: 85%; border: round $accent; background: $surface; padding: 1; }
    #safety-events { height: auto; max-height: 18; }
    #safety-events Static { height: auto; margin: 1 0; }
    """
    BINDINGS = [Binding("escape", "close", "Close", priority=True)]

    def __init__(self, state: SafetyState):
        super().__init__()
        self.state = state
        self._signature = None

    def compose(self) -> ComposeResult:
        with Vertical(id="safety-box"):
            yield Label("Fiji safe mode — recent events")
            yield Static("", id="safety-status")
            with VerticalScroll(id="safety-events"):
                yield Static("", id="safety-recent")
            yield Static("Status describes the negotiated Fiji tool connection. This view does not change Fiji's safety policy.")
            yield Button("Close (Esc)", id="safety-close")

    def on_mount(self) -> None:
        self.refresh_events()
        self.set_interval(0.5, self.refresh_events)
        self.query_one("#safety-close", Button).focus()

    def refresh_events(self) -> None:
        label, color = self.state.label()
        signature = (label, tuple(self.state.recent))
        if signature == self._signature:
            return
        self._signature = signature
        self.query_one("#safety-status", Static).update(Text(label, style=color))
        text = Text()
        for event in self.state.recent:
            text.append(datetime.fromtimestamp(event.timestamp).strftime("%H:%M:%S") + "  ", style="dim")
            text.append(event.message + "\n\n", style=event.color)
        if not self.state.recent:
            text.append("No safe-mode events received yet")
        self.query_one("#safety-recent", Static).update(text)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        event.stop()
        self.action_close()

    def action_close(self) -> None:
        self.dismiss(None)
