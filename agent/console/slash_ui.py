"""Inline completion for console and prompt-file slash commands."""
from __future__ import annotations

from rich.markup import escape
from textual.containers import Vertical
from textual.widgets import Input, Label, ListItem, ListView

MAX_SLASH_ROWS = 8


def matching_commands(text: str, caret: int,
                      commands: list[tuple[str, str]]) -> list[tuple[str, str]]:
    if caret < 1 or caret > len(text) or not text.startswith("/"):
        return []
    typed = text[:caret].casefold()
    if not typed:
        return []
    matches = [item for item in commands if item[0].casefold().startswith(typed)]
    # Complete /skill and /roi must precede /skills and /rois, even at the cap.
    matches.sort(key=lambda item: item[0].casefold() != typed)
    return matches[:MAX_SLASH_ROWS]


class SlashOverlay(Vertical):
    DEFAULT_CSS = """
    SlashOverlay {
        height: auto; max-height: 12; display: none;
        background: $surface; border: round $accent; margin: 0 1;
    }
    SlashOverlay > #slash-hint { color: $text-muted; padding: 0 1; }
    SlashOverlay > #slash-list { height: auto; max-height: 9; }
    """

    def __init__(self) -> None:
        super().__init__(id="slash-overlay")
        self.choices: list[tuple[str, str]] = []

    def compose(self):
        yield Label("Tab completes · Enter runs an exact command", id="slash-hint")
        yield ListView(id="slash-list")

    @property
    def open(self) -> bool:
        return self.styles.display == "block"

    def close(self) -> None:
        self.styles.display = "none"
        self.choices = []

    def update_for(self, text: str, caret: int,
                   commands: list[tuple[str, str]]) -> bool:
        self.choices = matching_commands(text, caret, commands)
        if not self.choices:
            self.close()
            return False
        rows = self.query_one("#slash-list", ListView)
        rows.clear()
        for name, description in self.choices:
            rows.append(ListItem(Label(f"[b]{escape(name)}[/b]  [dim]{escape(description)}[/dim]")))
        rows.index = 0
        self.styles.display = "block"
        return True

    def selected(self) -> str | None:
        rows = self.query_one("#slash-list", ListView)
        index = rows.index
        if index is None or index < 0 or index >= len(self.choices):
            return None
        return self.choices[index][0]

    def move(self, delta: int) -> None:
        rows = self.query_one("#slash-list", ListView)
        if self.choices:
            rows.index = max(0, min(len(self.choices) - 1, (rows.index or 0) + delta))

    def apply_to(self, chat_input: Input) -> bool:
        choice = self.selected()
        if choice is None:
            return False
        caret = chat_input.cursor_position
        chat_input.value = choice + chat_input.value[caret:]
        chat_input.cursor_position = len(choice)
        chat_input.focus()
        self.close()
        return True
