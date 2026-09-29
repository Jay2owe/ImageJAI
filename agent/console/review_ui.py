"""Local evidence-entry review: no model can approve its own knowledge."""
from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Static

class MemoryReviewScreen(ModalScreen):
    CSS = """
    MemoryReviewScreen { align: center middle; }
    #memory-review-box { width: 88; max-width: 96%; height: auto; max-height: 90%; background: $surface; border: round $accent; padding: 1 2; }
    #memory-review-actions { height: auto; }
    #memory-review-actions Button { min-width: 10; width: 1fr; }
    """
    BINDINGS = [Binding("escape", "close_review", "Cancel", priority=True)]
    def __init__(self, entry):
        super().__init__()
        self.entry = entry
    def compose(self) -> ComposeResult:
        with VerticalScroll(id="memory-review-box"):
            yield Static(Text(self.entry.title + "\n" + self.entry.status + " / " + self.entry.scope + "\n\n" + self.entry.content))
            yield Static(Text("Conflicts: " + (", ".join(self.entry.conflicts) or "none") + "\nExpiry: " + (self.entry.expires_at or "none")))
            yield Input(placeholder="Written review evidence or reason", id="memory-review-evidence")
            yield Input(value="30", placeholder="Renewal days (1–365)", id="memory-review-days")
            yield Input(placeholder="Passing validation receipt path (for Attach)", id="memory-review-receipt")
            yield Static("", id="memory-review-error")
            with Horizontal(id="memory-review-actions"):
                yield Button("Renew", id="memory-review-renew")
                yield Button("Deprecate", id="memory-review-deprecate")
                yield Button("Attach", id="memory-review-attach")
                yield Button("Revalidate", id="memory-review-revalidate")
                yield Button("Close", id="memory-review-close")
    def action_close_review(self):
        self.dismiss(None)
    def on_button_pressed(self, event):
        action = event.button.id.removeprefix("memory-review-")
        if action == "close":
            self.dismiss(None)
            return
        evidence = self.query_one("#memory-review-evidence", Input).value.strip()
        path = self.query_one("#memory-review-receipt", Input).value.strip().strip('"')
        try:
            days = int(self.query_one("#memory-review-days", Input).value) if action == "renew" else 30
            if action == "renew" and (not 1 <= days <= 365 or not evidence):
                raise ValueError("Enter review evidence and 1–365 renewal days")
            if action == "deprecate" and not evidence:
                raise ValueError("Enter the reason for deprecation")
            if action == "revalidate" and not evidence:
                raise ValueError("Enter evidence for the current instrument assumptions")
            if action == "attach" and not path:
                raise ValueError("Enter the passing validation receipt path")
        except ValueError as exc:
            self.query_one("#memory-review-error", Static).update(Text(str(exc), style="red"))
            return
        self.dismiss({"action":action, "evidence":evidence, "days":days, "path":path})
