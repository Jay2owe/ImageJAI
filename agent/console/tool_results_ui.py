"""On-demand inspection of the complete return behind a chat summary."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Label, Static, TextArea

from .tool_results import formatted_return


@dataclass(frozen=True)
class ToolResultDetail:
    name: str
    summary: str
    raw: str = ""
    artifact: Path | None = None

    def read(self) -> str:
        if self.artifact:
            with self.artifact.open(encoding="utf-8", newline="") as stream:
                return stream.read()
        return self.raw


class ToolResultScreen(ModalScreen[None]):
    """Escape dismisses this screen, including during a running agent turn."""

    CSS = """
    ToolResultScreen { align: center middle; }
    #tool-result-box { width: 90%; height: 90%; border: round $accent; background: $surface; padding: 1 2; }
    #tool-result-title { text-style: bold; margin-bottom: 1; }
    #tool-result-summary { color: $text-muted; margin-bottom: 1; }
    #tool-result-preview { height: auto; max-height: 40%; margin-bottom: 1; }
    #tool-result-content { height: 1fr; }
    #tool-result-buttons { height: 3; margin-top: 1; align-horizontal: right; }
    #tool-result-buttons Button { margin-left: 1; }
    """
    BINDINGS = [Binding("escape", "close", "Close", priority=True)]

    def __init__(self, detail: ToolResultDetail) -> None:
        super().__init__()
        self.detail = detail
        self.raw = detail.read()
        self.formatted = formatted_return(self.raw)
        self.show_original = False
        self.preview: Text | None = None

    def compose(self) -> ComposeResult:
        with Vertical(id="tool-result-box"):
            yield Label(Text(f"{self.detail.name} · Full return"), id="tool-result-title")
            yield Label(Text(self.detail.summary), id="tool-result-summary")
            if self.preview is not None:
                with VerticalScroll(id="tool-result-preview"):
                    yield Static(self.preview)
            yield TextArea(self.formatted, read_only=True, soft_wrap=False,
                           show_line_numbers=True, show_cursor=False, id="tool-result-content")
            with Horizontal(id="tool-result-buttons"):
                if self.formatted != self.raw:
                    yield Button("Show original", id="tool-result-original")
                yield Button("Close (Esc)", id="tool-result-close")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "tool-result-original":
            self.show_original = not self.show_original
            self.query_one("#tool-result-content", TextArea).load_text(
                self.raw if self.show_original else self.formatted)
            event.button.label = "Show formatted" if self.show_original else "Show original"
        elif event.button.id == "tool-result-close":
            self.action_close()

    def action_close(self) -> None:
        self.dismiss(None)
