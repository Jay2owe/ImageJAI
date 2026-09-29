import asyncio
from datetime import date

from textual.app import App
from textual.widgets import Checkbox, Input

from agent.console.catalog import CatalogEntry, Tier, snapshot_of
from agent.console.cost_notices import cost_variant, changed_notices, CostNoticeScreen


def test_cost_routes_distinguish_local_cloud_subscription_and_unknown():
    assert cost_variant("ollama", "gemma4:31b") is None
    assert cost_variant("ollama", "gemma4:31b-cloud") == "unverified"
    assert cost_variant("codex-subscription", "default") == "subscription"
    assert cost_variant("gemini-cli", "default") == "unverified"
    assert cost_variant("openai", "model", CatalogEntry("openai", "model", tier=Tier.PAID)) == "paid"
    assert cost_variant("groq", "model", CatalogEntry("groq", "model", tier=Tier.FREE)) is None


def test_price_dismissal_does_not_hide_a_later_change():
    old = CatalogEntry("openai", "model", tier=Tier.PAID, pinned=True,
                       features={"pricing": {"input_usd_per_mtok": 1, "output_usd_per_mtok": 2}})
    new = CatalogEntry("openai", "model", tier=Tier.PAID, pinned=True,
                       features={"pricing": {"input_usd_per_mtok": 2, "output_usd_per_mtok": 3}})
    notices = changed_notices(snapshot_of([old]), [new], today=date(2026, 9, 28))
    assert len(notices) == 1 and notices[0].severity == "high"
    assert changed_notices(snapshot_of([old]), [new], [notices[0].id]) == []
    newer = CatalogEntry("openai", "model", tier=Tier.FREE, pinned=True)
    assert changed_notices(snapshot_of([new]), [newer], [notices[0].id])


def test_notice_is_keyboard_accessible_and_escape_preserves_request():
    class Host(App):
        def compose(self):
            yield Input(value="Open Blobs")
    async def run():
        app = Host()
        decisions = []
        async with app.run_test(size=(80, 24)) as pilot:
            app.push_screen(CostNoticeScreen("provider", "Literal [red] prices"), decisions.append)
            await pilot.pause()
            app.screen.query_one(Checkbox).value = True
            await pilot.press("enter")
            assert decisions == [("continue", True)]
            app.push_screen(CostNoticeScreen("provider", "prices"), decisions.append)
            await pilot.pause()
            await pilot.press("escape")
            assert decisions[-1] is None and app.query_one(Input).value == "Open Blobs"
    asyncio.run(run())


def test_acknowledgment_scoped_to_session_and_provider_persists(tmp_path, monkeypatch):
    from agent.console import config
    from agent.console.sessions import SessionStore
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    store = SessionStore(config.ConsoleConfig())
    first, second = store.create(), store.create()
    first.cost_notice_acknowledged.append("provider:openai:paid")
    store.save()
    loaded = SessionStore(config.ConsoleConfig())
    assert loaded.get(first.id).cost_notice_acknowledged == ["provider:openai:paid"]
    assert loaded.get(second.id).cost_notice_acknowledged == []
