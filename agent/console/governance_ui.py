"""Data Governance and Receipts panes for the console.

Both screens are read-mostly: they show what the posture is, what has actually
left the machine, and where the evidence lives. Changing the posture and
writing a statement are explicit button presses, never side effects of opening
a view.
"""
from __future__ import annotations

from typing import Any, Callable, Sequence

from rich.markup import escape as rich_escape
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Label, ListItem, ListView, OptionList, Static
from textual.widgets.option_list import Option
from rich.text import Text
from .list_ui import NavigableOptionList


class GovernanceScreen(ModalScreen[dict | None]):
    """Posture, outbound counters, audit location and the statement action."""

    CSS = """
    GovernanceScreen { align: center middle; }
    #gov-box { width: 92; max-width: 96%; height: auto; max-height: 90%; border: round $accent;
               background: $surface; padding: 1 2; }
    #gov-title { text-style: bold; margin-bottom: 1; }
    #gov-postures { height: auto; max-height: 9; border: round $panel; margin-bottom: 1; }
    #gov-facts { height: auto; margin-bottom: 1; }
    #gov-status { height: auto; color: $text-muted; }
    #gov-buttons { height: auto; align-horizontal: right; margin-top: 1; }
    #gov-buttons Button { width: auto; margin-left: 1; }
    """
    BINDINGS = [Binding("escape", "close_governance", "Close", priority=True)]

    def __init__(self, model: dict, image_state: str = "") -> None:
        super().__init__()
        self.model = dict(model or {})
        self.image_state = image_state

    def compose(self) -> ComposeResult:
        with Vertical(id="gov-box"):
            yield Label("Data Governance", id="gov-title")
            yield Label("Privacy posture:")
            yield ListView(id="gov-postures")
            yield Static("", id="gov-facts")
            yield Static("", id="gov-status")
            with Horizontal(id="gov-buttons"):
                yield Button("Data Handling Statement", id="gov-statement")
                yield Button("Open audit log", id="gov-audit")
                yield Button("Close", id="gov-close", variant="primary")

    def on_mount(self) -> None:
        choices = list(self.model.get("posture_choices") or [])
        current = str(self.model.get("posture") or "")
        listing = self.query_one("#gov-postures", ListView)
        for index, choice in enumerate(choices):
            mark = "●" if choice == current else "○"
            listing.append(ListItem(Label(f"{mark} {choice}"), name=choice))
            if choice == current:
                listing.index = index
        listing.focus()
        facts = [
            f"Outbound calls: {rich_escape(str(self.model.get('outbound_calls') or '0'))}",
            f"Audit log: {rich_escape(str(self.model.get('audit_path') or 'no project folder yet'))}",
        ]
        if self.image_state:
            facts.append(f"Images to the model: {rich_escape(self.image_state)}")
        warning = self.model.get("generate_warning")
        if warning:
            facts.append(f"[yellow]{rich_escape(str(warning))}[/yellow]")
        self.query_one("#gov-facts", Static).update("\n".join(facts))
        self.query_one("#gov-status", Static).update(
            "[dim]Enter on a posture applies it. Escape closes.[/dim]")

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        chosen = event.item.name
        if chosen:
            self.dismiss({"action": "posture", "posture": chosen})

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "gov-close":
            self.dismiss(None)
        elif event.button.id == "gov-statement":
            if not self.model.get("can_generate_statement"):
                self.query_one("#gov-status", Static).update(
                    f"[yellow]{rich_escape(str(self.model.get('generate_warning') or ''))}[/yellow]")
                return
            self.dismiss({"action": "statement"})
        elif event.button.id == "gov-audit":
            self.dismiss({"action": "audit"})

    def action_close_governance(self) -> None:
        self.dismiss(None)


class ReceiptsScreen(ModalScreen[None]):
    """The last outbound calls, with a redacted detail view per row."""

    CSS = """
    ReceiptsScreen { align: center middle; }
    #rcp-box { width: 100; max-width: 96%; height: 88%; border: round $accent; background: $surface;
               padding: 1 2; }
    #rcp-title { text-style: bold; }
    #rcp-list { height: 1fr; border: round $panel; margin: 1 0; }
    #rcp-detail { height: auto; max-height: 14; border: round $panel; padding: 0 1; }
    #rcp-buttons { height: auto; align-horizontal: right; margin-top: 1; }
    """
    BINDINGS = [Binding("escape", "close_receipts", "Close", priority=True)]

    def __init__(self, rows: Sequence[dict], summary_text: str,
                 detail_for: Callable[[dict], dict] | None = None) -> None:
        super().__init__()
        self.rows = list(rows or [])
        self.summary_text = summary_text
        self.detail_for = detail_for

    def compose(self) -> ComposeResult:
        with Vertical(id="rcp-box"):
            yield Label("Receipts — what actually left this machine", id="rcp-title")
            yield Static(rich_escape(self.summary_text), id="rcp-summary")
            yield NavigableOptionList(id="rcp-list")
            yield VerticalScroll(Static("", id="rcp-detail-text"), id="rcp-detail")
            with Horizontal(id="rcp-buttons"):
                yield Button("Close", id="rcp-close", variant="primary")

    def on_mount(self) -> None:
        listing = self.query_one("#rcp-list", NavigableOptionList)
        listing.add_options([Option(Text.from_markup(self._row_label(row)), id=str(index))
                             for index, row in enumerate(self.rows)])
        if self.rows:
            listing.index = len(self.rows) - 1
        listing.focus()
        self._show_detail(len(self.rows) - 1 if self.rows else -1)

    @staticmethod
    def _row_label(row: dict) -> str:
        redacted = "redacted" if row.get("redacted") else "as typed"
        return (f"{rich_escape(str(row.get('time', '')))}  "
                f"{rich_escape(str(row.get('command', '')))[:36]:<36}  "
                f"{str(row.get('bytes_out', 0)):>8} B  {redacted}")

    def _show_detail(self, index: int) -> None:
        node = self.query_one("#rcp-detail-text", Static)
        if index < 0 or index >= len(self.rows):
            node.update("[dim]no outbound calls yet[/dim]")
            return
        row = self.rows[index]
        detail = self.detail_for(row) if self.detail_for else {"redacted_json": row.get("raw", row)}
        fields = detail.get("fields_redacted")
        lines = [str(detail.get("redacted_json"))]
        if fields:
            lines.append("fields redacted: " + ", ".join(str(f) for f in fields))
        node.update(rich_escape("\n".join(lines)))

    def on_option_list_option_highlighted(self, event: OptionList.OptionHighlighted) -> None:
        if event.option_list.id == "rcp-list":
            event.stop()
            self._show_detail(event.option_index)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "rcp-close":
            self.dismiss(None)

    def action_close_receipts(self) -> None:
        self.dismiss(None)
