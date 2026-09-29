"""Choose the Fiji installation used when the console opens a connection."""
from __future__ import annotations

from pathlib import Path

from rich.markup import escape as rich_escape
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical
from textual.screen import ModalScreen
from textual.widgets import Input, Label, ListItem, ListView, Static

from .fiji_startup import candidates, fiji_root


class FijiPathScreen(ModalScreen[Path | None]):
    CSS = """
    FijiPathScreen { align: center middle; }
    #fiji-path-box { width: 92; max-width: 96%; height: auto; max-height: 85%;
                     border: round $accent; background: $surface; padding: 1 2; }
    #fiji-path-list { height: auto; max-height: 12; border: round $panel; }
    #fiji-path-input { border: round $accent; margin-top: 1; }
    #fiji-path-error { color: $error; height: auto; }
    """
    BINDINGS = [Binding("escape", "cancel", "Cancel", priority=True)]

    def __init__(self, saved: str | None = None,
                 roots: list[Path] | None = None) -> None:
        super().__init__()
        self.saved = saved
        self.roots = candidates(saved) if roots is None else roots

    def compose(self) -> ComposeResult:
        with Vertical(id="fiji-path-box"):
            yield Label("Choose the Fiji installation", id="fiji-path-title")
            yield Static("[dim]Select a detected copy, or enter its Fiji.app folder below.[/dim]")
            yield ListView(id="fiji-path-list")
            yield Input(value=self.saved or "", placeholder="path to Fiji.app",
                        id="fiji-path-input")
            yield Static("", id="fiji-path-error")

    def on_mount(self) -> None:
        rows = self.query_one("#fiji-path-list", ListView)
        for root in self.roots:
            marker = "  ✓ saved" if self.saved and fiji_root(self.saved) == root else ""
            rows.append(ListItem(Label(rich_escape(str(root) + marker)), name=str(root)))
        if self.roots:
            rows.index = 0
            rows.focus()
        else:
            self.query_one("#fiji-path-input", Input).focus()

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        root = fiji_root(event.item.name)
        if root is not None:
            self.dismiss(root)

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id != "fiji-path-input":
            return
        root = fiji_root(event.value.strip())
        if root is None:
            self.query_one("#fiji-path-error", Static).update(
                "No Fiji launcher found in that folder.")
            return
        self.dismiss(root)

    def action_cancel(self) -> None:
        self.dismiss(None)
