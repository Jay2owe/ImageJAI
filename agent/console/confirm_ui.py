"""Bounded semantic confirmation prompt for the console chat surface."""
from __future__ import annotations

from rich.markup import escape as rich_escape
from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Label

MAX_OPTIONS = 16
MAX_OPTION_CHARS = 256
MAX_PROMPT_CHARS = 4096


class ConfirmationScreen(ModalScreen[str | None]):
    """One prompt with bounded option buttons; Escape resolves as cancel."""
    CSS = """
    ConfirmationScreen { align: center middle; }
    #confirm-box { width: 82; max-width: 96%; height: auto; max-height: 88%; border: round $warning;
                   background: $surface; padding: 1 2; }
    #confirm-title { text-style: bold; color: $warning; margin-bottom: 1; }
    #confirm-options Button { width: 1fr; margin-top: 1; }
    #confirm-options { height: auto; max-height: 12; }
    """
    BINDINGS = [Binding("escape", "cancel_confirm", "Cancel", priority=True)]

    def __init__(self, confirmation_id: str, prompt: str, options: list[str], *, max_prompt_chars=MAX_PROMPT_CHARS) -> None:
        super().__init__()
        self.confirmation_id = confirmation_id
        self.prompt = str(prompt or "")[:max(1, min(int(max_prompt_chars), 70000))]
        self.options = [str(option or "")[:MAX_OPTION_CHARS]
                        for option in options[:MAX_OPTIONS] if str(option or "").strip()]

    def compose(self) -> ComposeResult:
        with VerticalScroll(id="confirm-box"):
            yield Label("Agent needs a choice", id="confirm-title")
            yield Label(rich_escape(self.prompt), id="confirm-prompt")
            with VerticalScroll(id="confirm-options"):
                for index, option in enumerate(self.options):
                    yield Button(Text(option), id=f"confirm-option-{index}",
                                 variant="primary" if index == 0 else "default")
            yield Button("Cancel", id="confirm-cancel")

    def on_mount(self) -> None:
        if self.options:
            self.query_one("#confirm-option-0", Button).focus()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        bid = event.button.id or ""
        if bid == "confirm-cancel":
            self.dismiss(None)
        elif bid.startswith("confirm-option-"):
            try:
                index = int(bid.rsplit("-", 1)[1])
            except ValueError:
                return
            if 0 <= index < len(self.options):
                self.dismiss(self.options[index])

    def action_cancel_confirm(self) -> None:
        self.dismiss(None)
