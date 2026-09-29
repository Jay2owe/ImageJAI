"""Inline "@" file completion for the chat input.

Typing ``@`` in the prompt opens a short list of files in the working folder so
the user can point the agent at an image without typing a path. The candidate
list, the ordering, and the outbound text (a pseudonym token when the posture
requires one) all come from `agent.console.browse`; this module is only the
overlay widget and the caret handling.
"""
from __future__ import annotations

from pathlib import Path

from rich.markup import escape as rich_escape
from textual.containers import Vertical
from textual.widgets import Input, Label, ListItem, ListView

from .browse import (
    MentionCandidate,
    MentionFileIndex,
    PathTokenMap,
    ScanError,
    mention_candidates,
    mention_replacement,
)

MENTION_ROWS = 8


def active_mention(text: str, caret: int) -> "tuple[int, str] | None":
    """Return (index of '@', typed prefix) when the caret sits in a mention.

    A mention only starts at the beginning of a word, so an e-mail address or
    a macro that contains '@' does not open the list.
    """
    if not text or caret <= 0 or caret > len(text):
        return None
    index = caret - 1
    while index >= 0 and not text[index].isspace():
        if text[index] == "@":
            if index > 0 and not text[index - 1].isspace():
                return None
            return index, text[index + 1:caret]
        index -= 1
    return None


class MentionOverlay(Vertical):
    """Small list above the input showing the current "@" completions."""

    DEFAULT_CSS = """
    MentionOverlay {
        height: auto; max-height: 12; display: none;
        background: $surface; border: round $accent; margin: 0 1;
    }
    MentionOverlay > #mention-hint { color: $text-muted; padding: 0 1; }
    MentionOverlay > #mention-list { height: auto; max-height: 9; }
    """

    def __init__(self, token_map: "PathTokenMap | None" = None) -> None:
        super().__init__(id="mention-overlay")
        self.candidates: list[MentionCandidate] = []
        self.at_index: int = -1
        # Share the session map so a token inserted here is the same token
        # FijiConnection reverses on the way out.
        self.token_map = token_map or PathTokenMap()
        self.file_index = MentionFileIndex()

    def compose(self):
        yield Label("", id="mention-hint")
        yield ListView(id="mention-list")

    @property
    def open(self) -> bool:
        return self.styles.display == "block"

    def close(self) -> None:
        self.styles.display = "none"
        self.candidates = []
        self.at_index = -1

    def update_for(self, text: str, caret: int, folder: "Path | str | None",
                   posture: str) -> bool:
        """Recompute the list; returns True when the overlay is showing."""
        found = active_mention(text, caret)
        if found is None or folder is None:
            self.close()
            return False
        self.at_index, prefix = found
        try:
            self.candidates = mention_candidates(
                folder, prefix, MENTION_ROWS,
                token_map=self.token_map, posture=posture,
                file_index=self.file_index,
            )
        except ScanError as exc:
            self.candidates = []
            self._render_hint(f"[red]{rich_escape(str(exc))}[/red]")
            self.styles.display = "block"
            return True
        if not self.candidates:
            self.close()
            return False
        self._render_rows(folder)
        self.styles.display = "block"
        return True

    def _render_hint(self, markup: str) -> None:
        self.query_one("#mention-hint", Label).update(markup)

    def _render_rows(self, folder) -> None:
        listview = self.query_one("#mention-list", ListView)
        listview.clear()
        for candidate in self.candidates:
            glyph = "\U0001f5bc" if candidate.is_image else "\u25cf"
            listview.append(ListItem(Label(f"{glyph} {rich_escape(candidate.label)}")))
        listview.index = 0
        self._render_hint(
            f"[dim]{rich_escape(str(folder))} — enter inserts, esc closes[/dim]")

    def selected(self) -> "MentionCandidate | None":
        listview = self.query_one("#mention-list", ListView)
        index = listview.index
        if index is None or index >= len(self.candidates):
            return None
        return self.candidates[index]

    def move(self, delta: int) -> None:
        listview = self.query_one("#mention-list", ListView)
        if not self.candidates:
            return
        current = listview.index or 0
        listview.index = max(0, min(len(self.candidates) - 1, current + delta))

    def apply_to(self, chat_input: Input) -> bool:
        """Insert the highlighted candidate into the input. True when applied."""
        candidate = self.selected()
        if candidate is None or self.at_index < 0:
            return False
        new_text = mention_replacement(chat_input.value, self.at_index, candidate)
        chat_input.value = new_text
        chat_input.cursor_position = min(
            len(new_text), self.at_index + len(candidate.insert_text))
        self.close()
        return True
