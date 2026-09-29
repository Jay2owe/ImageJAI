"""Bounded work and semantic checks; no flaky clock thresholds in unit tests."""
import asyncio
from unittest.mock import Mock

import pytest
from textual.app import App
from textual.widgets import Input

from agent.console import browse
from agent.console.event_feed import EventFeed
from agent.console.picklist_ui import ListPickerScreen
from agent.console.yaml_io import safe_load


def test_folder_index_reuses_one_scan_then_invalidates_add_remove_and_folder(tmp_path, monkeypatch):
    first, second = tmp_path / "first", tmp_path / "second"
    first.mkdir()
    second.mkdir()
    (first / "alpha.tif").touch()
    (second / "beta.tif").touch()
    real = browse._mention_paths
    scan = Mock(wraps=real)
    monkeypatch.setattr(browse, "_mention_paths", scan)
    index, tokens = browse.MentionFileIndex(), browse.PathTokenMap()
    def candidates(folder, prefix="", posture="STANDARD"):
        return browse.mention_candidates(folder, prefix, file_index=index, token_map=tokens, posture=posture)
    assert candidates(first)[0].name == "alpha.tif"
    assert candidates(first, "al")[0].name == "alpha.tif"
    assert scan.call_count == 1
    protected = candidates(first, "a", "PSEUDONYMISED")[0]
    assert protected.insert_text == protected.token and "alpha" not in protected.insert_text
    assert scan.call_count == 1
    (first / "aardvark.tif").touch()
    assert candidates(first)[0].name == "aardvark.tif"
    (first / "aardvark.tif").unlink()
    assert candidates(first)[0].name == "alpha.tif"
    assert candidates(second)[0].name == "beta.tif"
    assert scan.call_count == 4


def test_folder_cache_expires_when_directory_timestamp_is_unchanged(tmp_path, monkeypatch):
    (tmp_path / "alpha.tif").touch()
    scan = Mock(wraps=browse._mention_paths)
    monkeypatch.setattr(browse, "_mention_paths", scan)
    monkeypatch.setattr(browse.time, "monotonic", lambda: 10)
    index = browse.MentionFileIndex()
    browse.mention_candidates(tmp_path, file_index=index)
    monkeypatch.setattr(browse.time, "monotonic", lambda: 13)
    browse.mention_candidates(tmp_path, file_index=index)
    assert scan.call_count == 2


@pytest.mark.parametrize("period", ["all", "5m", "1h", "today"])
@pytest.mark.parametrize("category", ["all", "image", "job", "warning"])
@pytest.mark.parametrize("search", ["", "JOB 1", "absent"])
def test_single_event_match_agrees_with_full_history(period, category, search):
    feed = EventFeed()
    now = 1790607600000
    for i in range(20):
        feed.add({"event": "job.progress", "data": {"job_id": i}}, now_ms=now - i * 600000)
    expected = feed.visible(category, period, search, now_ms=now)
    assert [line for line in feed.lines if feed.matches(line, category, period, search, now_ms=now)] == expected


def test_compiled_and_portable_yaml_parsers_are_safe_and_equivalent(monkeypatch):
    import yaml
    from pathlib import Path
    text = Path("agent/providers/models.yaml").read_text(encoding="utf-8")
    assert safe_load(text) == yaml.safe_load(text)
    with pytest.raises(yaml.YAMLError):
        safe_load("!!python/object/apply:os.system ['echo should-never-execute']")
    monkeypatch.delattr(yaml, "CSafeLoader", raising=False)
    assert safe_load("models: [one, two]") == {"models": ["one", "two"]}


def test_large_generic_picker_has_bounded_widgets_and_selects_after_filter():
    class Host(App):
        def compose(self):
            yield Input(value="draft")
    async def run():
        app, choices = Host(), []
        rows = [{"label": f"Analysis {i:04d}", "id": i} for i in range(2048)]
        async with app.run_test(size=(80, 24)) as pilot:
            screen = ListPickerScreen("Saved analyses", rows)
            app.push_screen(screen, choices.append)
            await pilot.pause()
            assert len(list(screen.walk_children())) <= 8
            screen.query_one("#picklist-filter", Input).value = "2047"
            await pilot.pause()
            await pilot.press("enter")
            assert choices == [rows[-1]]
    asyncio.run(run())


def test_live_chunks_keep_literal_complete_text_and_idle_ticks_do_not_repaint(monkeypatch):
    from agent.console.tui import ConsoleApp
    from agent.console.config import ConsoleConfig
    class OfflineConsole(ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def action_login(self): pass
    async def run():
        app = OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        async with app.run_test(size=(80, 24)) as pilot:
            await pilot.pause()
            body = "literal [red] text " * 5000
            for fragment in [body[i:i+13] for i in range(0, len(body), 13)]:
                app._stream_delta_ui(fragment, "thinking")
            app._paint_live_text()
            assert app._live_text == body and str(app.query_one("#live-text").content).endswith(body)
            nodes = [app.query_one(id) for id in ("#topbar-posture", "#topbar-egress", "#turn-status")]
            spies = [Mock(wraps=node.update) for node in nodes]
            for node, spy in zip(nodes, spies):
                monkeypatch.setattr(node, "update", spy)
            app._render_posture()
            app._tick_turn_status()
            for spy in spies: spy.assert_not_called()
            app._flush_live_text()
            event = next(row for row in app.evidence.read_events() if row["type"] == "thinking")
            artifact = event["artifact_refs"][0]
            assert app.artifacts.path_for(artifact).read_text(encoding="utf-8") == body
    asyncio.run(run())
