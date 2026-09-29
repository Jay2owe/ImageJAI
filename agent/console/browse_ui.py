"""Local Browse Files screen for selecting image tokens safely."""
from __future__ import annotations

from typing import Any, Sequence

from rich.markup import escape as rich_escape
from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, SelectionList, Static

from .browse import (
    SeriesEntry,
    browse_rows,
    filter_rows,
    load_tag_rules,
    preview_text,
    suggest_tag,
)


class BrowseFilesScreen(ModalScreen[dict[str, Any] | None]):
    """Show real labels locally, but return selected token-bearing entries."""

    CSS = """
    BrowseFilesScreen { align: center middle; }
    #browse-box { width: 110; max-width: 96%; height: 88%; border: round $accent;
                  background: $surface; padding: 1 2; }
    #browse-title { text-style: bold; }
    #browse-filter, #browse-tag { border: round $accent; margin-top: 1; }
    #browse-list { height: 1fr; border: round $panel; margin: 1 0; }
    #browse-preview { height: auto; max-height: 5; color: $text-muted; }
    #browse-buttons { height: 3; dock: bottom; background: $surface; align-horizontal: right; }
    #browse-buttons Button { width: auto; min-width: 8; margin-left: 1; }
    """
    BINDINGS = [Binding("escape", "cancel_browse", "Cancel")]

    def __init__(self, folder: str, entries: Sequence[SeriesEntry],
                 tag_rules: Sequence[Any] | None = None) -> None:
        super().__init__()
        self.folder = folder
        self.entries = list(entries)
        # Per-folder rules (.imagejai-tags.yml) parse the user's own naming
        # convention. They read one local file and never leave the machine.
        self.tag_rules = list(tag_rules) if tag_rules is not None else load_tag_rules(folder)
        self.rows = browse_rows(self.entries, self.tag_rules)
        self._visible: list[dict[str, Any]] = []
        self._selected_tokens: set[str] = set()

    def compose(self) -> ComposeResult:
        with VerticalScroll(id="browse-box"):
            yield Label("Browse Files", id="browse-title")
            yield Static(rich_escape(self.folder), id="browse-folder")
            yield Input(placeholder="filter locally by file name", id="browse-filter")
            yield SelectionList(id="browse-list")
            yield Input(placeholder="optional experiment tag", id="browse-tag")
            yield Static("No selection.", id="browse-preview")
            with Horizontal(id="browse-buttons"):
                yield Button("Select all", id="browse-all")
                yield Button("Clear", id="browse-clear")
                yield Button("Cancel", id="browse-cancel")
                yield Button("Insert into chat", id="browse-insert", variant="primary")

    def on_mount(self) -> None:
        self._redraw()
        self.query_one("#browse-list", SelectionList).focus()

    def _remember_selection(self) -> None:
        selection = self.query_one("#browse-list", SelectionList)
        visible_tokens = {str(row["Token"]) for row in self._visible}
        self._selected_tokens.difference_update(visible_tokens)
        self._selected_tokens.update(str(value) for value in selection.selected)

    def _redraw(self) -> None:
        selection = self.query_one("#browse-list", SelectionList)
        if self._visible:
            self._remember_selection()
        query = self.query_one("#browse-filter", Input).value
        self._visible = filter_rows(self.rows, query)
        selection.clear_options()
        options = []
        for row in self._visible:
            entry = row["entry"]
            tags = "  ".join(
                f"{name.lower()}={row[name]}"
                for name in ("Timepoint", "Genotype", "Sex", "Condition")
                if row.get(name)
            )
            label = (
                f"{row['Label']}  ·  C{row['Channels'] or '?'}  "
                f"{row['Dimensions'] or 'dimensions unknown'}  ·  {row['Status']}"
            )
            if tags:
                label += f"  ·  {tags}"
            options.append((Text(label), row["Token"], row["Token"] in self._selected_tokens))
        selection.add_options(options)
        self._update_preview()

    def _update_preview(self) -> None:
        selected = self._selected_entries()
        tag_input = self.query_one("#browse-tag", Input)
        tag = tag_input.value.strip()
        suggestion = ""
        if not tag and selected:
            # Only the tag leaves the machine, so it is derived from local
            # labels and shown before the user accepts it.
            suggestion = suggest_tag([entry.label for entry in selected], self.tag_rules)
            tag_input.placeholder = (
                f"optional experiment tag (suggested: {suggestion})"
                if suggestion else "optional experiment tag")
        self.query_one("#browse-preview", Static).update(
            rich_escape(preview_text([entry.token for entry in selected], tag or suggestion)))
        self._suggested_tag = suggestion

    def _selected_entries(self) -> list[SeriesEntry]:
        selected = set(str(value) for value in self.query_one(
            "#browse-list", SelectionList).selected)
        selected.update(self._selected_tokens)
        by_token = {entry.token: entry for entry in self.entries}
        return [by_token[token] for token in by_token if token in selected and by_token[token].readable]

    def on_input_changed(self, event: Input.Changed) -> None:
        if event.input.id == "browse-filter":
            self._redraw()
        elif event.input.id == "browse-tag":
            self._update_preview()

    def on_selection_list_selected_changed(self, event: SelectionList.SelectedChanged) -> None:
        self._remember_selection()
        self._update_preview()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        bid = event.button.id
        selection = self.query_one("#browse-list", SelectionList)
        if bid == "browse-all":
            selection.select_all()
            self._remember_selection()
            self._update_preview()
        elif bid == "browse-clear":
            self._selected_tokens.clear()
            selection.deselect_all()
            self._update_preview()
        elif bid == "browse-cancel":
            self.dismiss(None)
        elif bid == "browse-insert":
            entries = self._selected_entries()
            if not entries:
                self.query_one("#browse-preview", Static).update(
                    "[yellow]Select at least one readable file or series.[/yellow]")
                return
            typed = self.query_one("#browse-tag", Input).value.strip()
            self.dismiss({
                "entries": entries,
                "tag": typed or getattr(self, "_suggested_tag", ""),
            })

    def action_cancel_browse(self) -> None:
        self.dismiss(None)
