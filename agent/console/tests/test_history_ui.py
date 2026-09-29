"""Executed-code history preserves source and exposes safe, explicit actions."""
import asyncio

from textual.app import App, ComposeResult
from textual.widgets import Button, Checkbox, Input, OptionList, Static, TextArea

from agent.console.history_ui import HistoryEntryScreen, HistorySection
from agent.console.macros import SessionCodeJournal


class Host(App):
    def __init__(self):
        super().__init__()
        self.journal = SessionCodeJournal()
        self.choices, self.preferences, self.copied = [], {}, []

    def compose(self) -> ComposeResult:
        yield HistorySection(lambda: self.journal, self.choose, lambda: self.choices.append("clear"),
                             lambda k, v: self.preferences.update({k: v}))
        yield Input(id="draft")

    def choose(self, ident):
        self.push_screen(HistoryEntryScreen(self.journal.find(ident)), self.choices.append)

    def copy_to_clipboard(self, source):
        self.copied.append(source)


def test_history_live_list_copy_action_and_draft_preserved():
    async def run():
        app = Host()
        async with app.run_test(size=(100, 35)) as pilot:
            draft = app.query_one("#draft", Input)
            draft.value = "keep my draft"
            entry = app.journal.record('run("Blobs");', success=False, failure_message="Macro failed")
            section = app.query_one(HistorySection)
            section.refresh_entries()
            rows = app.query_one("#history-list", OptionList)
            assert rows.option_count == 1
            assert rows.get_option_at_index(0).id == str(entry.id)
            app.choose(entry.id)
            await pilot.pause()
            assert app.screen.query_one(TextArea).text == entry.code
            await pilot.press("ctrl+c")
            assert app.copied == [entry.code]
            assert "Copied" in str(app.screen.query_one("#history-copy-status", Static).render())
            await pilot.click("#history-remove")
            await pilot.pause()
            assert app.choices == [("remove", entry.id)]
            assert draft.value == "keep my draft"
    asyncio.run(run())


def test_history_filter_collapse_and_session_switch():
    async def run():
        app = Host()
        app.journal.record('run("Close All");')
        entry = app.journal.record('run("Measure");')
        async with app.run_test(size=(80, 25)) as pilot:
            section = app.query_one(HistorySection)
            assert app.query_one(OptionList).option_count == 2
            app.query_one(Checkbox).value = True
            await pilot.pause()
            assert app.query_one(OptionList).option_count == 1
            assert app.query_one(OptionList).get_option_at_index(0).id == str(entry.id)
            assert app.preferences["history_exclude_plumbing"]
            await pilot.click("#history-toggle")
            assert not app.query_one("#history-body").display
            assert app.preferences["history_collapsed"]
            app.journal = SessionCodeJournal()
            section.refresh_entries()
            assert app.query_one(OptionList).get_option_at_index(0).disabled
    asyncio.run(run())


def test_history_details_actions_and_escape_at_small_size():
    async def run():
        app = Host()
        entry = app.journal.record('run("Blobs");')
        async with app.run_test(size=(80, 24)) as pilot:
            for action in ("run", "edit", "save"):
                app.choose(entry.id)
                await pilot.pause()
                app.screen.query_one(f"#history-{action}", Button).press()
                await pilot.pause()
                assert app.choices[-1] == (action, entry.id)
            app.choose(entry.id)
            await pilot.pause()
            await pilot.press("escape")
            assert app.choices[-1] is None
    asyncio.run(run())


def test_history_removal_and_clear_persist_without_deleting_evidence(tmp_path, monkeypatch):
    from agent.console import config, tui
    from agent.console.config import ConsoleConfig
    from agent.console.sessions import sessions_dir
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config, "CONFIG_PATH", tmp_path / "console.json")
    app = tui.ConsoleApp(ConsoleConfig())
    session = app.store.create()
    app._select_session(session)
    app._record_tool_evidence("one", "run_macro", {"code": 'run("Blobs");'}, True, "ok")
    entry = app.macro_journal.snapshot()[0]
    app.macro_journal.remove_from_ring(entry.id)
    app._save_macro_journal()
    app._select_session(session)
    assert app.macro_journal.snapshot() == []
    assert len(app.evidence.query("tool_result")) == 1
    app._record_macro_payload({"code": 'run("Measure");'}, True, "")
    app.macro_journal.clear_ring()
    app._save_macro_journal()
    restored = SessionCodeJournal()
    restored.load_from_index_if_present(sessions_dir() / session.id / "macros")
    assert restored.snapshot() == []
    assert (sessions_dir() / session.id / "evidence.jsonl").is_file()
