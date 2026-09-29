"""Model browsing stays cheap as registries grow, without losing controls."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone

from textual.app import App
from textual import events
from textual.widgets import Input

from agent.console import picker_ui
from agent.console.catalog import CatalogEngine, CatalogEntry, ModelsCache, Tier


NOW = datetime(2026, 9, 28, tzinfo=timezone.utc)


def engine_for(tmp_path, entries):
    return CatalogEngine(curated=entries, credentials={}, endpoints={},
                         cache=ModelsCache(tmp_path / "cache"),
                         overrides_path=tmp_path / "overrides.yaml",
                         state_path=tmp_path / "state.json", clock=lambda: NOW)


def subscription_rows(monkeypatch):
    entries = [CatalogEntry("codex-subscription", "fixture-codex", display_name="Fixture Codex",
                            features={"effort_levels": ("minimal", "xhigh")})]
    monkeypatch.setattr(picker_ui, "subscription_entries", lambda: entries)
    return entries


class PickerHost(App):
    def __init__(self, engine, provider=None, model=None):
        super().__init__()
        self.picker = picker_ui.ModelPickerScreen(engine, provider, model)
        self.chosen = []

    def on_mount(self):
        self.push_screen(self.picker, self.chosen.append)


def test_large_registry_scroll_keeps_a_bounded_widget_tree(tmp_path, monkeypatch):
    subscriptions = subscription_rows(monkeypatch)
    entries = [CatalogEntry("openrouter", f"fixture-{i:04d}", context_window=128000)
               for i in range(2048)]
    rendered = []
    original = picker_ui.row_label
    def count_labels(entry, *args):
        rendered.append(entry.model_id)
        return original(entry, *args)
    monkeypatch.setattr(picker_ui, "row_label", count_labels)

    async def run():
        host = PickerHost(engine_for(tmp_path, entries), "openrouter", "fixture-1900")
        async with host.run_test(size=(120, 40)) as pilot:
            await pilot.pause()
            screen = host.picker
            rows = screen.query_one("#picker-list", picker_ui.ModelOptionList)
            assert len([e for e in screen._rows if e]) == len(entries) + len(subscriptions)
            assert rows.option_count == len(screen._rows)
            assert len(list(screen.walk_children())) <= 15
            assert len(rows.children) == 0
            assert screen._selected().model_id == "fixture-1900"
            assert rows.scroll_y > 0, "the current model must be visible even near the end"
            rendered_count = len(rendered)
            assert rendered_count == len(entries) + len(subscriptions)
            for _ in range(8):
                rows.scroll_relative(y=3, animate=False, immediate=True)
                rows.action_cursor_down()
                await pilot.pause(0)
            assert screen._selected().model_id == "fixture-1908"
            assert len(rendered) == rendered_count, "scrolling must not rebuild row labels"
            assert len(list(screen.walk_children())) <= 15
            before = rows.scroll_y
            await pilot._post_mouse_events([events.MouseScrollDown], rows, offset=(2, 2), times=3)
            await pilot.pause()
            assert rows.scroll_y > before
            assert len(rendered) == rendered_count
            # A resize reflows row content without mounting thousands of rows.
            await pilot.resize_terminal(90, 26)
            assert len(list(screen.walk_children())) <= 15
            assert screen._selected().model_id == "fixture-1908"
    asyncio.run(run())


def test_search_keyboard_navigation_and_effort_selection(tmp_path, monkeypatch):
    subscriptions = subscription_rows(monkeypatch)
    entries = [CatalogEntry("openai", "same-name", tier=Tier.FREE),
               CatalogEntry("gemini", "same-name", tier=Tier.PAID)]

    async def run():
        host = PickerHost(engine_for(tmp_path, entries))
        async with host.run_test(size=(120, 40)) as pilot:
            await pilot.pause()
            screen = host.picker
            search = screen.query_one("#picker-search", Input)
            assert search.has_focus
            await pilot.press("down")
            assert search.has_focus
            assert screen._selected().provider != "codex-subscription"
            await pilot.press("up")
            assert screen._selected().model_id == subscriptions[0].model_id
            await pilot.press("pagedown", "pageup")
            assert search.has_focus and screen._selected() is not None
            search.value = "fixture codex"
            await pilot.pause()
            assert screen._selected().model_id == subscriptions[0].model_id
            assert len([e for e in screen._rows if e]) == 1
            await pilot.press("enter")
            assert host.chosen == [{"provider": "codex-subscription", "model": "fixture-codex",
                                    "effort_levels": ["minimal", "xhigh"]}]
    asyncio.run(run())


def test_pin_hide_and_free_filter_preserve_selection_without_discovery(tmp_path, monkeypatch):
    subscription_rows(monkeypatch)
    entries = [CatalogEntry("openai", "same-name", tier=Tier.FREE),
               CatalogEntry("gemini", "same-name", tier=Tier.PAID),
               CatalogEntry("ollama", "local-model", tier=Tier.PAID)]
    engine = engine_for(tmp_path, entries)

    async def run():
        host = PickerHost(engine, "openai", "same-name")
        async with host.run_test(size=(120, 40)) as pilot:
            await pilot.pause()
            screen = host.picker
            rows = screen.query_one("#picker-list", picker_ui.ModelOptionList)
            index = next(i for i, entry in enumerate(screen._rows) if entry and entry.provider == "gemini")
            rows.index = index
            # All following operations must reuse the loaded registry.
            monkeypatch.setattr(engine, "refresh", lambda **_: (_ for _ in ()).throw(AssertionError("discovery during browsing")))
            await pilot.press("ctrl+s")
            assert screen._selected().provider == "gemini" and screen._selected().pinned
            assert engine.overrides.load_as_map()["gemini same-name"].pinned
            assert rows.index == index
            await pilot.press("ctrl+h")
            # Shared registry policy keeps pinned entries visible, even when
            # hidden is saved; unpinning then applies that saved preference.
            assert engine.overrides.load_as_map()["gemini same-name"].hidden
            assert screen._selected().provider == "gemini"
            await pilot.press("ctrl+s")
            assert not any(e and e.provider == "gemini" for e in screen._rows)
            assert any(e and e.provider == "openai" for e in screen._rows)
            await pilot.press("f2")
            assert screen.free_only
            assert {e.provider for e in screen._rows if e} == {"openai", "ollama"}
            assert screen._selected() is not None
            screen.query_one("#picker-search", Input).value = "missing model"
            await pilot.pause()
            assert rows.option_count == 0 and rows.index is None and screen._selected() is None
            await pilot.press("enter")
            assert host.screen is screen and host.chosen == []
    asyncio.run(run())


def test_mouse_selects_model_and_provider_headers_cannot_be_selected(tmp_path, monkeypatch):
    subscriptions = subscription_rows(monkeypatch)

    async def run():
        host = PickerHost(engine_for(tmp_path, []))
        async with host.run_test(size=(120, 40)) as pilot:
            await pilot.pause()
            screen = host.picker
            rows = screen.query_one("#picker-list", picker_ui.ModelOptionList)
            assert rows.get_option_at_index(0).disabled
            rows.scroll_to(y=0, animate=False, immediate=True)
            await pilot.pause()
            x = rows.scrollable_content_region.x - rows.region.x + 2
            y = rows.scrollable_content_region.y - rows.region.y
            await pilot.click(rows, offset=(x, y))
            assert host.chosen == [] and host.screen is screen
            await pilot.click(rows, offset=(x, y + 1))
            await pilot.pause()
            assert host.chosen[0]["provider"] == subscriptions[0].provider
            assert host.chosen[0]["effort_levels"] == ["minimal", "xhigh"]
    asyncio.run(run())
