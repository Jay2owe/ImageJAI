import asyncio
import threading
import time
from types import SimpleNamespace

import pytest
from textual.app import App
from textual.widgets import Button, Input, Static

from agent.console.agent_loop import AbortFlag, TurnCallbacks
from agent.console.cost_ui import BudgetCeilingScreen, BillingFailureScreen, failure_kind, interruptible_choice, positive_limit
from agent.console.usage import UsageLedger, request_usage


@pytest.mark.parametrize("invalid", ["0", "-1", "nan", "inf", "invalid"])
def test_limit_rejects_invalid_amount(invalid):
    with pytest.raises(ValueError):
        positive_limit(invalid)


def test_billing_auth_rate_limit_classification():
    assert failure_kind("HTTP 402 payment required") == "billing"
    assert failure_kind("insufficient_quota") == "billing"
    assert failure_kind("401 unauthorized") == "authentication"
    assert failure_kind("429 rate_limit_exceeded") == "rate limit"
    assert failure_kind("Macro line 5 failed") is None


def test_rejected_client_has_short_summary_with_complete_details_available():
    from agent.console.cost_ui import failure_summary
    error = "Error authenticating: IneligibleTierError: This client is no longer supported\n" + "native stack trace\n" * 1000
    assert failure_kind(error) == "authentication"
    summary = failure_summary(error)
    assert "Gemini API" in summary and len(summary) <= 160
    assert len(failure_summary("x" * 1000)) == 160


def test_abort_releases_choice_wait_immediately():
    flag, done = AbortFlag(), threading.Event()
    result = []
    worker = threading.Thread(target=lambda: result.append(interruptible_choice(done, flag)))
    worker.start()
    started = time.monotonic()
    flag.set()
    worker.join(timeout=.5)
    assert result == [False] and time.monotonic() - started < .5


def test_budget_modal_validates_raise_and_preserves_draft():
    class Host(App):
        def compose(self):
            yield Input(value="keep draft", id="draft")
    async def run():
        app, choices = Host(), []
        async with app.run_test(size=(80, 24)) as pilot:
            app.push_screen(BudgetCeilingScreen(2, 1), choices.append)
            await pilot.pause()
            app.screen.query_one("#budget-new-limit", Input).value = "1"
            app.screen.query_one("#budget-raise", Button).press()
            await pilot.pause()
            assert not choices and "must exceed" in str(app.screen.query_one("#budget-error", Static).render())
            app.screen.query_one("#budget-new-limit", Input).value = "3"
            app.screen.query_one("#budget-raise", Button).press()
            await pilot.pause()
            assert choices == [("raise", 3)]
            app.push_screen(BudgetCeilingScreen(0, 1, unknown=True), choices.append)
            await pilot.pause()
            assert app.screen.query_one(Input).disabled
            await pilot.press("escape")
            assert choices[-1] == ("stop", None)
            assert app.query_one(Input).value == "keep draft"
    asyncio.run(run())


def test_no_second_model_call_after_limit_and_tool_receipt_survives():
    from agent.console.tests.test_wrapped_tools import wrapped_agent, action
    agent, macros = wrapped_agent("openai")
    class Client:
        def __init__(self): self.calls = 0
        def chat(self, *args, **kwargs):
            self.calls += 1
            return {"text": action(arguments={"code": 'run("Blobs");'}), "usage": {"input_tokens": 100, "output_tokens": 20}, "total_cost_usd": 2}
        def extract_text(self, reply): return reply["text"]
        def extract_tool_calls(self, reply): return []
        def append_assistant(self, messages, reply): messages.append({"role": "assistant", "content": reply["text"]})
    agent.client = Client()
    ledger = UsageLedger()
    cb = TurnCallbacks(before_model_call=lambda _: ledger.total_usd < 1,
                       on_usage=ledger.record)
    assert not agent.turn("Open Blobs", cb)
    assert agent.client.calls == 1 and macros == ['run("Blobs");']
    assert agent.action_receipts["a1"]["ok"]
    assert "imagejai-result" in agent.messages[-1]["content"]
    assert len(ledger.rows) == 1


def test_billing_recovery_never_retries_and_opens_only_known_account(monkeypatch):
    opened = []
    monkeypatch.setattr("agent.console.cost_ui.webbrowser.open", opened.append)
    class Host(App):
        def compose(self): yield Input(value="draft")
    async def run():
        app, choices = Host(), []
        async with app.run_test(size=(80, 24)) as pilot:
            app.push_screen(BillingFailureScreen("openai", "402 billing [red] literal"), choices.append)
            await pilot.pause()
            app.screen.query_one("#billing-account", Button).press()
            await pilot.pause()
            assert len(opened) == 1 and opened[0].startswith("https://platform.openai.com/")
            assert not choices
            app.screen.query_one("#billing-free", Button).press()
            await pilot.pause()
            assert choices == ["free"] and app.query_one(Input).value == "draft"
    asyncio.run(run())


def test_cross_provider_context_is_portable_and_keeps_receipts():
    from agent.console.replay import conversation_for_switch
    messages = [{"role": "assistant", "content": [{"type": "text", "text": "Checked"},
        {"type": "tool_use", "id": "vendor-id", "name": "get_state", "input": {}}]},
        {"role": "user", "name": "imagejai_tool_result", "content": "<imagejai-result>FULL RECEIPT</imagejai-result>"}]
    converted = conversation_for_switch(messages)
    assert all(isinstance(row["content"], str) for row in converted)
    assert "get_state" in converted[0]["content"]
    assert converted[-1] == messages[-1]


@pytest.mark.parametrize("action,expected,manual_after_interrupt", [
    ("raise", True, False), ("free", False, False), ("escape", False, False), ("raise", True, True)])
def test_worker_cost_notice_then_budget_pause_before_sending(tmp_path, monkeypatch, action, expected, manual_after_interrupt):
    from agent.console import config
    from agent.console.catalog import CatalogEntry, Tier
    from agent.console.cost_notices import CostNoticeScreen
    from agent.console.sessions import Session
    from agent.console.tui import ConsoleApp
    from datetime import date
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config, "CONFIG_PATH", tmp_path / "console.json")
    entry = CatalogEntry("openai", "m", tier=Tier.PAID,
        features={"pricing": {"input_usd_per_mtok": 1, "output_usd_per_mtok": 2}})
    class Host(App):
        _selected_cost_entry = ConsoleApp._selected_cost_entry
        _pending_cost_notice = ConsoleApp._pending_cost_notice
        _accept_cost_notice = ConsoleApp._accept_cost_notice
        _cost_notice_before_model_call = ConsoleApp._cost_notice_before_model_call
        _before_model_call = ConsoleApp._before_model_call
        def compose(self): yield Input(value="keep draft")
        def _evidence_append(self, *_args): pass
        def action_interrupt(self): self.abort.set()
    async def run():
        app = Host()
        app.config = config.ConsoleConfig(provider="openai", model="m", budget_enabled=True, budget_ceiling_usd=1)
        app.catalog = SimpleNamespace(offline=lambda: SimpleNamespace(models=[entry]), today=lambda: date(2026, 9, 28))
        app.session = Session(id="session")
        app.store = SimpleNamespace(save_session=lambda _: None)
        app.abort, app.agent = AbortFlag(), None
        app.turn_running = not manual_after_interrupt
        if manual_after_interrupt:
            app.abort.set()
        app._turn_cancel_requested = app._pending_free_model = False
        app.usage_ledger = UsageLedger([request_usage("openai", "m", {"total_cost_usd": 2})])
        result = []
        async with app.run_test(size=(80, 24)) as pilot:
            worker = threading.Thread(target=lambda: result.append(app._before_model_call({"provider": "openai", "model": "m"})))
            worker.start()
            try:
                for _ in range(50):
                    if isinstance(app.screen, CostNoticeScreen): break
                    await asyncio.sleep(.02)
                await pilot.pause()
                assert isinstance(app.screen, CostNoticeScreen) and not result
                app.screen.query_one("#cost-continue", Button).press()
                for _ in range(50):
                    if isinstance(app.screen, BudgetCeilingScreen): break
                    await asyncio.sleep(.02)
                await pilot.pause()
                assert isinstance(app.screen, BudgetCeilingScreen) and not result
                assert "model:openai/m:paid" in app.session.cost_notice_acknowledged
                if action == "raise":
                    app.screen.query_one("#budget-new-limit", Input).value = "3"
                    app.screen.query_one("#budget-raise", Button).press()
                elif action == "free":
                    app.screen.query_one("#budget-free", Button).press()
                else:
                    await pilot.press("escape")
                for _ in range(50):
                    if result: break
                    await asyncio.sleep(.02)
                assert result == [expected]
                assert app.query_one(Input).value == "keep draft"
                if action == "free": assert app._pending_free_model
                if action == "raise": assert app.config.budget_ceiling_usd == 3
            finally:
                app.abort.set()
                await pilot.pause()
                worker.join(timeout=.5)
    asyncio.run(run())
