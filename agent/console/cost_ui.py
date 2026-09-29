"""Session spending pauses and explicit billing recovery choices."""
from __future__ import annotations

import math
import re
import threading
import time
import webbrowser

from rich.text import Text
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, Static


BILLING_URLS = {
    "openai": "https://platform.openai.com/settings/organization/billing/overview",
    "anthropic": "https://console.anthropic.com/settings/billing",
    "gemini": "https://aistudio.google.com/",
    "openrouter": "https://openrouter.ai/settings/credits",
    "codex-subscription": "https://chatgpt.com/",
    "claude-subscription": "https://claude.ai/settings/billing",
    "gemini-cli": "https://aistudio.google.com/",
    "copilot-cli": "https://github.com/settings/billing",
}


def positive_limit(text):
    try:
        value = float(text)
    except (TypeError, ValueError):
        raise ValueError("Enter a positive amount in US dollars") from None
    if not math.isfinite(value) or value <= 0:
        raise ValueError("Enter a positive amount in US dollars")
    return value


def failure_kind(error):
    text = str(error).lower()
    if any(term in text for term in ("insufficient_quota", "insufficient credits", "credit balance", "payment required", "billing", "exceeded your current quota")) or re.search(r"\b402\b", text):
        return "billing"
    if any(term in text for term in ("authentication", "authenticating", "ineligibletiererror", "invalid_api_key", "invalid api key", "not logged in", "unauthorized")) or re.search(r"\b401\b", text):
        return "authentication"
    if "rate limit" in text or "rate_limit" in text or re.search(r"\b429\b", text):
        return "rate limit"
    return None


def failure_summary(error):
    text = str(error)
    if "IneligibleTierError" in text and "no longer supported" in text:
        return "Gemini CLI sign-in rejected: Google no longer supports this client/account tier. Choose the Gemini API route or another model."
    first = next((line.strip() for line in text.splitlines() if line.strip()), "Provider request failed")
    return first if len(first) <= 160 else first[:159] + "…"


def interruptible_choice(done: threading.Event, abort, timeout=600):
    deadline = time.monotonic() + timeout
    while not abort.set_flag:
        if done.wait(min(.025, max(0, deadline - time.monotonic()))):
            return not abort.set_flag
        if time.monotonic() >= deadline:
            return False
    return False


class BudgetCeilingScreen(ModalScreen[tuple[str, float | None]]):
    CSS = """
    BudgetCeilingScreen { align: center middle; }
    #budget-box { width: 90%; height: auto; max-height: 90%; border: round $warning; background: $surface; padding: 1 2; }
    #budget-actions { height: auto; }
    #budget-actions Button { min-width: 12; margin-right: 1; }
    #budget-message { height: auto; max-height: 10; }
    #budget-error { height: auto; color: $error; }
    """
    BINDINGS = [Binding("escape", "stop_budget", "Stop", priority=True)]

    def __init__(self, total, ceiling, unknown=False):
        super().__init__()
        self.total, self.ceiling, self.unknown = total, ceiling, unknown

    def compose(self) -> ComposeResult:
        with Vertical(id="budget-box"):
            yield Label("Spending limit — agent paused")
            message = (f"Known session cost: ${self.total:.4f}. Limit: ${self.ceiling:g}. "
                       "The next model request has not been sent. A request already sent can exceed the limit; estimates are not a provider-side cap.")
            if self.unknown:
                message += " Some costs or this model's price are unknown. The dollar limit cannot be verified; Continue once approves one additional request."
            with VerticalScroll(id="budget-message"):
                yield Static(Text(message))
            yield Input(value=str(max(self.ceiling * 2, self.total + .01)), id="budget-new-limit", type="number", disabled=self.unknown)
            yield Static("", id="budget-error")
            with Horizontal(id="budget-actions"):
                yield Button("Continue once" if self.unknown else "Raise and continue", id="budget-raise", variant="primary")
                yield Button("Choose free", id="budget-free")
                yield Button("Stop", id="budget-stop")

    def on_mount(self):
        self.query_one("#budget-raise", Button).focus()

    def on_button_pressed(self, event):
        event.stop()
        action = event.button.id.removeprefix("budget-")
        if action == "raise":
            if self.unknown:
                self.dismiss(("once", None))
                return
            try:
                value = positive_limit(self.query_one(Input).value)
                if value <= self.total:
                    raise ValueError("The new limit must exceed the current session cost")
            except ValueError as exc:
                self.query_one("#budget-error", Static).update(Text(str(exc)))
                return
            self.dismiss(("raise", value))
        elif action in {"free", "stop"}:
            self.dismiss((action, None))

    def action_stop_budget(self):
        interrupt = getattr(self.app, "action_interrupt", None)
        if callable(interrupt):
            interrupt()
        self.dismiss(("stop", None))


class BillingFailureScreen(ModalScreen[str | None]):
    CSS = """
    BillingFailureScreen { align: center middle; }
    #billing-box { width: 90%; height: 85%; border: round $error; background: $surface; padding: 1 2; }
    #billing-details { height: 1fr; }
    #billing-actions { height: auto; }
    #billing-actions Button { min-width: 12; margin-right: 1; }
    """
    BINDINGS = [Binding("escape", "close_failure", "Close", priority=True)]

    def __init__(self, provider, error):
        super().__init__()
        self.provider, self.error = provider, str(error)

    def compose(self):
        with Vertical(id="billing-box"):
            yield Label(f"Model request failed: {failure_kind(self.error) or 'provider error'}")
            yield Static("The conversation and completed Fiji actions are saved. Choose a model or fix the account, then send Continue. This request is not retried automatically.")
            with VerticalScroll(id="billing-details"):
                yield Static(Text(self.error))
            with Horizontal(id="billing-actions"):
                yield Button("Choose model", id="billing-model", variant="primary")
                yield Button("Choose free", id="billing-free")
                if self.provider in BILLING_URLS:
                    yield Button("Open account", id="billing-account")
                yield Button("Close", id="billing-close")

    def on_mount(self):
        self.query_one("#billing-model", Button).focus()

    def on_button_pressed(self, event):
        event.stop()
        action = event.button.id.removeprefix("billing-")
        if action == "account":
            webbrowser.open(BILLING_URLS[self.provider])
        else:
            self.dismiss(action)

    def action_close_failure(self):
        self.dismiss(None)
