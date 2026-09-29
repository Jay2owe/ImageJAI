"""Readable cost notices and durable, fingerprinted catalog observations."""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import date

from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Checkbox, Label, Static

from .catalog import Tier, CatalogEntry, detect_changes, snapshot_of
from .cliagents import is_cloud_ollama_tag
from .posture import is_local_provider


def cost_variant(provider, model, entry=None):
    if is_local_provider(provider) and not (provider == "ollama" and is_cloud_ollama_tag(model)):
        return None
    if provider in {"codex-subscription", "claude-subscription", "copilot-cli"}:
        return "subscription"
    tier = entry.tier if entry else Tier.UNCURATED
    if tier in {Tier.FREE, Tier.FREE_WITH_LIMITS}:
        return None
    return "subscription" if tier == Tier.REQUIRES_SUBSCRIPTION else "paid" if tier == Tier.PAID else "unverified"


def notice_text(provider, model, variant, entry=None):
    label = f"{provider} / {model}"
    if variant == "subscription":
        return f"{label} uses the provider's subscription or account allowance. ImageJAI cannot see your remaining allowance or guarantee that overage is disabled."
    if variant == "unverified":
        return f"Pricing and capabilities for {label} are unverified. This program/model may charge through its own account. Check that account before a long run."
    prices = ""
    if entry and entry.input_usd_per_mtok is not None and entry.output_usd_per_mtok is not None:
        prices = f" Registry rates: ${entry.input_usd_per_mtok:g} input / ${entry.output_usd_per_mtok:g} output per million tokens."
    return f"{label} charges through your provider account.{prices} Registry prices are estimates; the provider's bill is authoritative."


@dataclass(frozen=True)
class CostNotice:
    id: str
    title: str
    body: str
    severity: str
    model_key: str = ""


def changed_notices(observations, entries, dismissed=(), today=None):
    watched = {e.key for e in entries if e.pinned or e.key in observations}
    notices = []
    for change in detect_changes(observations, entries, today or date.today(), watched):
        ident = hashlib.sha256((change.key + change.kind + change.title + change.body).encode()).hexdigest()
        if ident not in dismissed:
            notices.append(CostNotice(ident, change.title, change.body, change.severity.value, change.key))
    return notices


class CostNoticeScreen(ModalScreen[tuple[str, bool] | None]):
    CSS = """
    CostNoticeScreen { align: center middle; }
    #cost-box { width: 90%; height: auto; max-height: 90%; border: round $warning; background: $surface; padding: 1 2; }
    #cost-body { height: auto; max-height: 12; }
    #cost-buttons { height: auto; }
    #cost-buttons Button { min-width: 12; margin-right: 1; }
    """
    BINDINGS = [Binding("escape", "cancel_notice", "Cancel", priority=True)]

    def __init__(self, provider, body):
        super().__init__()
        self.provider, self.body = provider, body

    def compose(self) -> ComposeResult:
        with Vertical(id="cost-box"):
            yield Label("Before using this model")
            with VerticalScroll(id="cost-body"):
                yield Static(Text(self.body))
            yield Checkbox(f"Don't ask again for {self.provider} this session", id="cost-remember")
            with Horizontal(id="cost-buttons"):
                yield Button("Continue", id="cost-continue", variant="primary")
                yield Button("Choose free", id="cost-free")
                yield Button("Cancel", id="cost-cancel")

    def on_mount(self):
        self.query_one("#cost-continue", Button).focus()

    def on_button_pressed(self, event):
        event.stop()
        self.dismiss((event.button.id.removeprefix("cost-"), self.query_one(Checkbox).value))

    def action_cancel_notice(self):
        self.dismiss(None)
