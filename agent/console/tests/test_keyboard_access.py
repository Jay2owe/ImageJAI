import asyncio

from textual.app import App
from textual.binding import Binding
from textual.widgets import Input, TextArea

from agent.console.picklist_ui import ListPickerScreen
from agent.console.tool_results_ui import ToolResultDetail
from agent.console.tui import ConsoleApp
from agent.console.macros import MacroItem


def test_keyboard_opens_full_return_and_escape_preserves_draft():
    class Host(App):
        BINDINGS = [Binding("ctrl+r", "tool_results", "Returns", priority=True)]
        action_tool_results = ConsoleApp.action_tool_results
        action_tool_result = ConsoleApp.action_tool_result
        def __init__(self):
            super().__init__()
            self._tool_result_details = {1: ToolResultDetail("Macro", "Completed", raw="FULL RETURN")}
        def compose(self):
            yield Input(value="my draft", id="chat-input")
    async def run():
        app = Host()
        async with app.run_test(size=(80, 24)) as pilot:
            await pilot.press("ctrl+r")
            await pilot.pause()
            await pilot.press("enter")
            await pilot.pause()
            assert app.screen.query_one(TextArea).text == "FULL RETURN"
            await pilot.press("escape")
            assert app.query_one(Input).value == "my draft" and app.query_one(Input).has_focus
    asyncio.run(run())


def test_keyboard_macro_context_menu_and_escape(tmp_path):
    class Host(App):
        def compose(self):
            yield Input(value="draft")
    async def run():
        app = Host()
        from pathlib import Path
        path = tmp_path / "sample.ijm"
        path.write_text('run("Blobs");', encoding="utf-8")
        item = MacroItem(source="saved", name="sample", path=path)
        choices = []
        async with app.run_test(size=(80, 24)) as pilot:
            app.push_screen(ListPickerScreen("Macros", [item], macro_actions=True), choices.append)
            await pilot.pause()
            await pilot.press("shift+f10")
            await pilot.pause()
            assert app.screen.title_text == "Macro actions"
            await pilot.press("escape", "escape")
            assert app.query_one(Input).value == "draft" and choices == [None]
    asyncio.run(run())
