"""Editable settings for the standalone console's own configuration."""
from __future__ import annotations

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, Select, Static, Switch, TabbedContent, TabPane

from .config import ConsoleConfig
from .fiji_startup import fiji_root
from .cost_ui import positive_limit


class SettingsScreen(ModalScreen[dict | None]):
    """Keep edits local until Save, then return a validated settings snapshot."""
    BINDINGS = [Binding("escape", "cancel_settings", "Cancel", priority=True)]

    CSS = """
    SettingsScreen { align: center middle; }
    #settings-box {
        width: 82; max-width: 96%; height: 36; max-height: 95%;
        border: round $accent; background: $surface; padding: 1 2;
    }
    #settings-title { text-style: bold; margin-bottom: 1; }
    #settings-tabs { height: 1fr; }
    .settings-tab { padding: 1 2; overflow-y: auto; }
    .settings-row { height: auto; margin: 1 0; }
    .settings-row Label { width: 26; padding-top: 1; }
    .settings-row Input, .settings-row Select { width: 1fr; }
    .settings-row Switch { width: auto; }
    #settings-status { height: 2; color: $warning; }
    #settings-actions { height: auto; align-horizontal: right; }
    #settings-actions Button { margin-left: 1; }
    """

    def __init__(self, config: ConsoleConfig) -> None:
        super().__init__()
        self.config = config

    def compose(self) -> ComposeResult:
        with Vertical(id="settings-box"):
            yield Label("Console settings", id="settings-title")
            with TabbedContent(id="settings-tabs"):
                with TabPane("Agent", id="settings-agent", classes="settings-tab"):
                    yield Static(
                        f"Current: {self.config.provider or 'none'} / "
                        f"{self.config.model or 'none'}  ({self.config.effort} effort)"
                    )
                    yield Static("Saved chats retain their own provider and model.")
                    yield Button("Choose model and effort…", id="settings-model")
                    yield Button("Change provider or key…", id="settings-login")
                    yield Static("Provider keys stay in the protected credential store.")
                with TabPane("Fiji", id="settings-fiji", classes="settings-tab"):
                    with Horizontal(classes="settings-row"):
                        yield Label("Fiji installation")
                        yield Input(
                            value=self.config.fiji_path or "",
                            placeholder="blank = detect Fiji automatically",
                            id="settings-fiji-path",
                        )
                    with Horizontal(classes="settings-row"):
                        yield Label("Start Fiji on launch")
                        yield Switch(value=self.config.auto_start_fiji, id="settings-auto-start")
                    with Horizontal(classes="settings-row"):
                        yield Label("Close startup errors")
                        yield Switch(value=self.config.close_fiji_startup_error,
                                     id="settings-close-startup-error")
                    yield Static("Automatic during console launches; turn off to inspect errors.")
                    yield Static(
                        f"TCP connection: {self.config.host}:{self.config.port}. "
                        "Use --host or --port at launch to change it."
                    )
                with TabPane("Privacy", id="settings-privacy", classes="settings-tab"):
                    with Horizontal(classes="settings-row"):
                        yield Label("Privacy posture")
                        yield Select(
                            [("Standard", "STANDARD"),
                             ("Pseudonymised", "PSEUDONYMISED"),
                             ("On premises", "ON_PREMISES")],
                            value=self.config.posture or "STANDARD",
                            allow_blank=False, id="settings-posture",
                        )
                    with Horizontal(classes="settings-row"):
                        yield Label("Send image captures")
                        yield Select(
                            [("Never", "never"),
                             ("Standard posture only", "standard-only"),
                             ("When posture allows", "always")],
                            value=self.config.attach_images,
                            allow_blank=False, id="settings-images",
                        )
                    yield Static("On premises blocks cloud providers even when captures are enabled.")
                with TabPane("Budget", id="settings-budget", classes="settings-tab"):
                    with Horizontal(classes="settings-row"):
                        yield Label("Pause at spending limit")
                        yield Switch(value=self.config.budget_enabled, id="settings-budget-enabled")
                    with Horizontal(classes="settings-row"):
                        yield Label("Session limit (US dollars)")
                        yield Input(value=str(self.config.budget_ceiling_usd), id="settings-budget-limit", type="number")
                    yield Static("Checks before each model request. Estimates can drift; a request already sent can exceed the limit. Unknown costs require your choice before continuing.")
                with TabPane("Display", id="settings-display", classes="settings-tab"):
                    with Horizontal(classes="settings-row"):
                        yield Label("Show session rail")
                        yield Switch(value=self.config.show_left_rail, id="settings-left-rail")
                    with Horizontal(classes="settings-row"):
                        yield Label("Show Fiji panel")
                        yield Switch(value=self.config.show_right_panel, id="settings-right-panel")
            yield Static("", id="settings-status")
            with Horizontal(id="settings-actions"):
                yield Button("Cancel", id="settings-cancel")
                yield Button("Save", variant="primary", id="settings-save")

    def _submit(self, next_action: str | None = None) -> None:
        try:
            ceiling = positive_limit(self.query_one("#settings-budget-limit", Input).value)
        except ValueError as exc:
            self.query_one("#settings-status", Static).update(str(exc))
            return
        raw_path = self.query_one("#settings-fiji-path", Input).value.strip().strip('"')
        root = fiji_root(raw_path) if raw_path else None
        if raw_path and root is None:
            self.query_one("#settings-status", Static).update(
                "No Fiji launcher found at that path.")
            return
        values = {
            "budget_enabled": self.query_one("#settings-budget-enabled", Switch).value,
            "budget_ceiling_usd": ceiling,
            "fiji_path": str(root) if root is not None else None,
            "auto_start_fiji": self.query_one("#settings-auto-start", Switch).value,
            "close_fiji_startup_error": self.query_one(
                "#settings-close-startup-error", Switch).value,
            "posture": self.query_one("#settings-posture", Select).value,
            "attach_images": self.query_one("#settings-images", Select).value,
            "show_left_rail": self.query_one("#settings-left-rail", Switch).value,
            "show_right_panel": self.query_one("#settings-right-panel", Switch).value,
        }
        self.dismiss({"values": values, "next": next_action})

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button = event.button.id
        if button == "settings-cancel":
            self.dismiss(None)
        elif button == "settings-save":
            self._submit()
        elif button == "settings-model":
            self._submit("model")
        elif button == "settings-login":
            self._submit("login")

    def key_escape(self) -> None:
        self.dismiss(None)

    action_cancel_settings = key_escape
