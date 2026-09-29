"""One generic list screen for the rail popups.

The Swing rail opened a JPopupMenu for macros, recipes, saved chats and the
agent command palette. In a terminal the same job is a small modal list, so
all four share this screen: grouped rows, a title, an empty state, and one
selection callback. Keeping it generic means a new popup needs no new screen.
"""
from __future__ import annotations

from typing import Any, Sequence

from rich.markup import escape as rich_escape
from rich.text import Text
from textual import events
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, OptionList, Static
from textual.widgets.option_list import Option

from .macros import MacroItem, macro_context_menu
from .list_ui import NavigableOptionList


def rows_from_model(model: Any) -> "tuple[list[tuple[str, Any]], str]":
    """Flatten a popup model into (label, payload) rows plus an empty message.

    Accepts the grouped dict the rail builds, a plain sequence, or anything
    with ``rows``/``items``, because the four callers do not agree on a shape.
    """
    empty = ""
    rows: list[tuple[str, Any]] = []
    if model is None:
        return rows, "nothing to show"
    if isinstance(model, dict):
        empty = str(model.get("empty") or "")
        for group in model.get("groups") or []:
            title = group.get("title")
            if title:
                rows.append((f"__group__{title}", None))
            for item in group.get("items") or []:
                rows.append((_label_of(item), item))
        if rows or "groups" in model:
            return rows, empty
        model = model.get("rows") or model.get("items") or []
    sequence = model if isinstance(model, (list, tuple)) else getattr(model, "rows", None)
    for item in sequence or []:
        rows.append((_label_of(item), item))
    return rows, empty


def _label_of(item: Any) -> str:
    for attribute in ("menu_text", "label", "title", "name", "text"):
        value = getattr(item, attribute, None)
        if isinstance(value, str) and value.strip():
            return value
    if isinstance(item, dict):
        if isinstance(item.get("command"), str):
            description = str(item.get("description") or "").strip()
            return f"{item['command']}  {description}".rstrip()
        for key in ("label", "title", "name", "text", "id"):
            value = item.get(key)
            if isinstance(value, str) and value.strip():
                return value
    return str(item)


class ListPickerScreen(ModalScreen[Any]):
    """Pick one row from a rail popup model; returns the payload or None."""

    CSS = """
    ListPickerScreen { align: center middle; }
    #picklist-box { width: 84; max-width: 96%; height: auto; max-height: 85%; border: round $accent;
                    background: $surface; padding: 1 2; }
    #picklist-title { text-style: bold; margin-bottom: 1; }
    #picklist-filter { border: round $accent; margin-bottom: 1; }
    #picklist-list { height: auto; max-height: 20; border: round $panel; margin-bottom: 1; }
    #picklist-empty { color: $text-muted; }
    #picklist-actions { height: auto; align-horizontal: right; }
    #picklist-actions Button { margin-left: 1; }
    """

    BINDINGS = [Binding("escape", "cancel_pick", "Cancel", priority=True),
                Binding("shift+f10", "macro_menu", "Macro actions", priority=True)]

    def __init__(self, title: str, model: Any, *, macro_actions: bool = False) -> None:
        super().__init__()
        self.title_text = title
        self.rows, self.empty_text = rows_from_model(model)
        self._visible: list[tuple[str, Any]] = []
        self.macro_actions = macro_actions
        self._last_filter = None

    def compose(self) -> ComposeResult:
        with Vertical(id="picklist-box"):
            yield Label(rich_escape(self.title_text), id="picklist-title")
            yield Input(placeholder="type to filter", id="picklist-filter")
            yield NavigableOptionList(id="picklist-list")
            yield Static("", id="picklist-empty")
            if self.macro_actions:
                yield Static("Enter runs; Shift+F10 opens actions; Tab reaches Edit/Folder.")
                with Horizontal(id="picklist-actions"):
                    yield Button("Run", id="picklist-run")
                    yield Button("Edit", id="picklist-edit")
                    yield Button("Folder", id="picklist-folder")

    def on_mount(self) -> None:
        self._redraw()
        self.query_one("#picklist-filter", Input).focus()

    def _redraw(self) -> None:
        needle = self.query_one("#picklist-filter", Input).value.strip().lower()
        if needle == self._last_filter:
            return
        self._last_filter = needle
        listview = self.query_one("#picklist-list", NavigableOptionList)
        selected = self._visible[listview.index][1] if listview.index is not None and listview.index < len(self._visible) else None
        listview.clear_options()
        self._visible = []
        options = []
        for label, payload in self.rows:
            if label.startswith("__group__"):
                if needle:
                    continue
                options.append(Option(Text(label[9:], style="bold"), disabled=True))
                self._visible.append((label, None))
                continue
            if needle and needle not in label.lower():
                continue
            options.append(Option(Text(label)))
            self._visible.append((label, payload))
        listview.add_options(options)
        first = next((i for i, (_, p) in enumerate(self._visible) if p is not None), None)
        if first is not None:
            listview.index = next((i for i, (_, p) in enumerate(self._visible) if p is selected and p is not None), first)
        empty = self.query_one("#picklist-empty", Static)
        if any(p is not None for _, p in self._visible):
            empty.update("")
        else:
            empty.update(f"[dim]{rich_escape(self.empty_text or 'nothing to show')}[/dim]")

    def on_input_changed(self, event: Input.Changed) -> None:
        if event.input.id == "picklist-filter":
            self._redraw()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id == "picklist-filter":
            self.query_one("#picklist-list", NavigableOptionList).action_select()

    def on_option_list_option_selected(self, event: OptionList.OptionSelected) -> None:
        event.stop()
        index = event.option_index
        if index is None or index >= len(self._visible):
            return
        payload = self._visible[index][1]
        if payload is not None:
            self.dismiss(payload)

    def _highlighted_macro(self) -> MacroItem | None:
        index = self.query_one("#picklist-list", NavigableOptionList).index
        if index is None or index >= len(self._visible):
            return None
        payload = self._visible[index][1]
        return payload if isinstance(payload, MacroItem) else None

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if not self.macro_actions:
            return
        item = self._highlighted_macro()
        if item is None:
            return
        action = event.button.id
        if action == "picklist-run":
            self.dismiss(item)
        elif action == "picklist-edit":
            self.dismiss({"macro_action": "open_in_script_editor", "item": item})
        elif action == "picklist-folder":
            self.dismiss({"macro_action": "open_folder", "item": item})

    def on_mouse_down(self, event: events.MouseDown) -> None:
        if not self.macro_actions or event.button != 3:
            return
        listview = self.query_one("#picklist-list", NavigableOptionList)
        if event.widget is not listview:
            return
        index = event.style.meta.get("option")
        if not isinstance(index, int) or index >= len(self._visible):
            return
        item = self._visible[index][1]
        if not isinstance(item, MacroItem):
            return
        listview.index = index
        self.action_macro_menu()
        event.stop()

    def on_key(self, event: events.Key) -> None:
        if not self.query_one("#picklist-filter", Input).has_focus:
            return
        action = {"down": "action_cursor_down", "up": "action_cursor_up",
                  "pagedown": "action_page_down", "pageup": "action_page_up"}.get(event.key)
        if action:
            getattr(self.query_one("#picklist-list", NavigableOptionList), action)()
            event.prevent_default()
            event.stop()

    def action_macro_menu(self) -> None:
        if not self.macro_actions:
            return
        item = self._highlighted_macro()
        if item is None:
            return
        actions = [
            {"label": action["label"], "macro_action": action["id"], "item": item}
            for action in macro_context_menu(item)
        ]
        self.app.push_screen(
            ListPickerScreen("Macro actions", {"items": actions}),
            lambda choice: self.dismiss(choice) if choice is not None else None,
        )

    def action_cancel_pick(self) -> None:
        self.dismiss(None)


class MacroNameScreen(ModalScreen[str | None]):
    """Ask where to save a session macro, without running it."""
    BINDINGS = [Binding("escape", "cancel_name", "Cancel", priority=True)]

    CSS = """
    MacroNameScreen { align: center middle; }
    #macro-name-box {
        width: 72; max-width: 96%; height: auto; border: round $accent;
        background: $surface; padding: 1 2;
    }
    #macro-name-input { margin: 1 0; }
    #macro-name-actions { height: auto; align-horizontal: right; }
    #macro-name-actions Button { margin-left: 1; }
    """

    def __init__(self, suggested_name: str) -> None:
        super().__init__()
        self.suggested_name = suggested_name

    def compose(self) -> ComposeResult:
        with Vertical(id="macro-name-box"):
            yield Label("Save session macro as")
            yield Input(value=self.suggested_name, id="macro-name-input")
            with Horizontal(id="macro-name-actions"):
                yield Button("Cancel", id="macro-name-cancel")
                yield Button("Save", variant="primary", id="macro-name-save")

    def on_mount(self) -> None:
        self.query_one("#macro-name-input", Input).focus()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id == "macro-name-input":
            self.dismiss(event.value.strip())

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "macro-name-save":
            self.dismiss(self.query_one("#macro-name-input", Input).value.strip())
        elif event.button.id == "macro-name-cancel":
            self.dismiss(None)

    def key_escape(self) -> None:
        self.dismiss(None)

    action_cancel_name = key_escape
