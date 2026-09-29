"""Live executed-code history, distinct from the saved-conversation list."""
from __future__ import annotations

from collections.abc import Callable

from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import Button, Checkbox, Label, OptionList, Static, TextArea
from textual.widgets.option_list import Option

from .macros import JournalEntry, SessionCodeJournal


class HistoryEntryScreen(ModalScreen[tuple[str, int] | None]):
    CSS = """
    HistoryEntryScreen { align: center middle; }
    #history-detail { width: 92%; height: 85%; border: round $accent; background: $surface; padding: 1; }
    #history-code { height: 1fr; }
    #history-detail-actions { height: auto; layout: grid; grid-size: 4; grid-columns: 1fr 1fr 1fr 1fr; grid-rows: 3; }
    #history-detail-actions Button { min-width: 7; width: 1fr; }
    """
    BINDINGS = [Binding("escape", "close", "Close", priority=True),
                Binding("ctrl+c", "copy", "Copy", priority=True)]

    def __init__(self, entry: JournalEntry):
        super().__init__()
        self.entry = entry

    def compose(self) -> ComposeResult:
        with Vertical(id="history-detail"):
            yield Label(Text(self.entry.row_text()))
            yield Label(Text(self.entry.failure_message or f"Language: {self.entry.language}"))
            yield TextArea(self.entry.code, read_only=True, soft_wrap=False,
                           show_line_numbers=True, id="history-code")
            yield Static("", id="history-copy-status")
            with Horizontal(id="history-detail-actions"):
                for action, label in (("copy", "Copy"), ("run", "Run"), ("edit", "Edit"),
                                      ("save", "Save"), ("remove", "Remove"), ("close", "Close")):
                    yield Button(label, id=f"history-{action}")

    def action_copy(self) -> None:
        self.app.copy_to_clipboard(self.entry.code)
        self.query_one("#history-copy-status", Static).update("Copied macro source")

    def action_close(self) -> None:
        self.dismiss(None)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        action = (event.button.id or "").removeprefix("history-")
        event.stop()
        if action == "copy":
            self.action_copy()
        elif action == "close":
            self.action_close()
        else:
            self.dismiss((action, self.entry.id))


class HistorySection(Vertical):
    """A bounded virtual list that refreshes only when its entries change."""
    DEFAULT_CSS = """
    HistorySection { height: auto; margin-top: 1; }
    #history-toggle, #history-clear { width: 1fr; min-width: 10; height: 3; }
    #history-body { height: auto; }
    #history-list { height: 9; border: round $panel; }
    #history-filter { height: auto; }
    """

    def __init__(self, journal: Callable[[], SessionCodeJournal],
                 choose: Callable[[int], None], clear: Callable[[], None],
                 preference: Callable[[str, bool], None], *, collapsed=False,
                 exclude_plumbing=False):
        super().__init__(id="macro-history")
        self.journal = journal
        self.choose, self.clear, self.preference = choose, clear, preference
        self.collapsed, self.exclude_plumbing = collapsed, exclude_plumbing
        self._signature = None

    def compose(self) -> ComposeResult:
        yield Button("Macro history", id="history-toggle")
        with Vertical(id="history-body"):
            yield OptionList(id="history-list")
            yield Checkbox("Hide housekeeping", value=self.exclude_plumbing, id="history-filter")
            yield Button("Clear history…", id="history-clear")

    def on_mount(self) -> None:
        self.query_one("#history-body").display = not self.collapsed
        self.refresh_entries()
        self.set_interval(0.5, self.refresh_entries)

    def refresh_entries(self) -> None:
        entries = self.journal().snapshot(exclude_plumbing=self.exclude_plumbing)
        signature = tuple((e.id, e.name, e.last_run_at, e.run_count, e.success) for e in entries)
        if signature == self._signature:
            return
        self._signature = signature
        rows = self.query_one("#history-list", OptionList)
        selected = rows.get_option_at_index(rows.highlighted).id if rows.highlighted is not None else None
        rows.clear_options()
        rows.add_options([Option(Text(e.row_text(), style="red" if not e.success else ""), id=str(e.id))
                          for e in entries] or [Option("No macros run yet", disabled=True)])
        if entries:
            rows.highlighted = next((i for i, e in enumerate(entries) if str(e.id) == selected), 0)

    @on(OptionList.OptionSelected, "#history-list")
    def selected(self, event: OptionList.OptionSelected) -> None:
        event.stop()
        if event.option.id:
            self.choose(int(event.option.id))

    @on(Checkbox.Changed, "#history-filter")
    def filtered(self, event: Checkbox.Changed) -> None:
        event.stop()
        self.exclude_plumbing = event.value
        self.preference("history_exclude_plumbing", event.value)
        self._signature = None
        self.refresh_entries()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        event.stop()
        if event.button.id == "history-toggle":
            self.collapsed = not self.collapsed
            self.query_one("#history-body").display = not self.collapsed
            self.preference("history_collapsed", self.collapsed)
        elif event.button.id == "history-clear":
            self.clear()
