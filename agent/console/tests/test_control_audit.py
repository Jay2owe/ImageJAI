"""Console control contract: actual clicks, keyboard selection and dispatch.

Network, browsers and installed agents are replaced at their external boundary.
The existing subsystem tests check the operations behind each dispatch route.
"""
import asyncio
from pathlib import Path
from unittest.mock import Mock

import pytest
from textual.app import App
from textual.widgets import Button, Input, Static, TextArea

from agent.console import tui
from agent.console.config import ConsoleConfig
from agent.console.confirm_ui import ConfirmationScreen
from agent.console.cost_notices import CostNoticeScreen
from agent.console.cost_ui import BillingFailureScreen, BudgetCeilingScreen
from agent.console.governance_ui import GovernanceScreen, ReceiptsScreen
from agent.console.history_ui import HistoryEntryScreen
from agent.console.macros import MacroItem, SessionCodeJournal
from agent.console.picklist_ui import ListPickerScreen, MacroNameScreen
from agent.console.safety_ui import SafetyEventsScreen, SafetyState
from agent.console.settings_ui import SettingsScreen
from agent.console.tool_results_ui import ToolResultDetail, ToolResultScreen


# Explicit user-visible contracts: a newly advertised command requires a case.
COMMAND_ROUTES = {
    "/queue": "_queue_command", "/python": "_python_command", "/jobs": "_jobs_command",
    "/reload": "_reload_resources", "/fork": "_fork_command", "/btw": "_btw_command",
    "/validate": "_validate_command", "/memory-review": "_memory_review_command",
    "/instrument": "_instrument_command", "/steer": "_steer_command",
    "/commands": "action_commands", "/help": "action_help", "/login": "action_login",
    "/model": "action_switch_model", "/effort": "action_effort",
    "/fiji": "_fiji_command", "/settings": "action_settings", "/new": "action_new_session",
    "/resume": "_resume_command", "/rename": "_rename_session",
    "/delete-session": "_delete_session_command", "/state": "get_state", "/capture": "capture",
    "/browse": "action_browse_files", "/results": "results", "/rois": "rois",
    "/console": "console_tail", "/friction": "friction_patterns", "/safety": "action_safety",
    "/tools": "action_list_tools", "/tool-results": "action_tool_results",
    "/budget": "_set_budget", "/cost-notices": "action_cost_notices",
    "/compact": "_compact_session", "/memory": "_memory_command",
    "/remember": "_remember_command", "/refine": "_refine_command",
    "/knowledge": "_knowledge_command", "/reference": "_reference_command",
    "/fixes": "_fixes_command", "/skills": "_skills_command", "/skill": "_skill_command",
    "/export": "_export_session", "/posture": "_show_posture", "/images": "_images_command",
    "/roi": "_roi_command", "/focus": "_focus_command", "/receipts": "_show_receipts",
    "/governance": "_open_governance", "/statement": "_write_statement",
    "/audit": "_show_audit", "/clear": "action_clear_conversation", "/quit": "exit",
}


def test_every_advertised_command_has_a_contract():
    assert {command for command, _ in tui.SLASH_COMMANDS} == set(COMMAND_ROUTES)


@pytest.mark.parametrize("command,route", COMMAND_ROUTES.items())
def test_every_command_dispatches_exactly_once(command, route):
    host = Mock()
    host._fiji_call.side_effect = lambda name, label, call: call()
    tui.ConsoleApp._slash(host, command)
    target = getattr(host.fiji if command in {"/state", "/capture", "/results", "/rois", "/console", "/friction"} else host, route)
    target.assert_called_once()


@pytest.mark.parametrize("command,route", [(c, r) for c, r in COMMAND_ROUTES.items()
    if c in {"/fiji", "/resume", "/rename", "/delete-session", "/budget", "/memory", "/remember",
             "/refine", "/knowledge", "/reference", "/fixes", "/skills", "/skill", "/posture", "/images", "/roi", "/focus",
             "/queue", "/python", "/fork", "/btw", "/validate", "/memory-review", "/instrument", "/steer"}])
def test_command_arguments_are_preserved(command, route):
    host = Mock()
    tui.ConsoleApp._slash(host, command + " quoted value [literal]")
    getattr(host, route).assert_called_once_with("quoted value [literal]")


class ModalHost(App):
    def compose(self):
        yield Input(value="preserve this draft", id="draft")


def run_modal(screen, action, expected, *, size=(80, 24), stays=False):
    async def run():
        app, choices = ModalHost(), []
        async with app.run_test(size=size) as pilot:
            app.push_screen(screen, choices.append)
            await pilot.pause()
            button = screen.query_one("#" + action, Button)
            button.scroll_visible(animate=False, immediate=True)
            await pilot.pause()
            assert await pilot.click("#" + action), f"{action} cannot be clicked at {size}"
            await pilot.pause()
            if stays:
                assert app.screen is screen and not choices
            else:
                assert choices == [expected]
                assert app.query_one("#draft", Input).value == "preserve this draft"
    asyncio.run(run())


@pytest.mark.parametrize("action,result", [("btn-allow", "once"), ("btn-always", "always"), ("btn-deny", "deny")])
def test_approval_buttons(action, result):
    run_modal(tui.ApprovalScreen("run_shell", {"command": "owned test"}), action, result)


@pytest.mark.parametrize("action,result", [("cost-continue", ("continue", False)),
    ("cost-free", ("free", False)), ("cost-cancel", ("cancel", False))])
def test_cost_buttons(action, result):
    run_modal(CostNoticeScreen("openai", "Registry price changed"), action, result)


@pytest.mark.parametrize("action,result", [("budget-raise", ("raise", 2.0)),
    ("budget-free", ("free", None)), ("budget-stop", ("stop", None))])
def test_budget_buttons(action, result):
    run_modal(BudgetCeilingScreen(1, 1), action, result)


def test_unknown_budget_continue_once():
    run_modal(BudgetCeilingScreen(1, 1, unknown=True), "budget-raise", ("once", None))


@pytest.mark.parametrize("action", ["model", "free", "close"])
def test_billing_buttons(action):
    run_modal(BillingFailureScreen("openai", "insufficient_quota"), "billing-" + action, action)


def test_billing_account_opens_known_url_only(monkeypatch):
    opened = Mock()
    monkeypatch.setattr("agent.console.cost_ui.webbrowser.open", opened)
    run_modal(BillingFailureScreen("openai", "insufficient_quota"), "billing-account", None, stays=True)
    opened.assert_called_once_with("https://platform.openai.com/settings/organization/billing/overview")


@pytest.mark.parametrize("action,result", [("gov-close", None), ("gov-audit", {"action": "audit"}),
    ("gov-statement", {"action": "statement"})])
def test_governance_buttons(action, result):
    run_modal(GovernanceScreen({"can_generate_statement": True, "posture_choices": ["Standard"], "posture": "Standard"}), action, result)


def test_statement_button_refuses_without_project_folder():
    screen = GovernanceScreen({"can_generate_statement": False, "generate_warning": "Open an image first"})
    run_modal(screen, "gov-statement", None, stays=True)


def test_receipts_close():
    run_modal(ReceiptsScreen([], "No outbound calls"), "rcp-close", None)


def test_safety_close():
    run_modal(SafetyEventsScreen(SafetyState()), "safety-close", None)


@pytest.mark.parametrize("action", ["run", "edit", "save", "remove", "close", "copy"])
def test_history_buttons(action, monkeypatch):
    journal = SessionCodeJournal()
    entry = journal.record('run("Blobs");')
    copied = Mock()
    monkeypatch.setattr(ModalHost, "copy_to_clipboard", copied)
    result = None if action in {"close", "copy"} else (action, entry.id)
    run_modal(HistoryEntryScreen(entry), "history-" + action, result, stays=action == "copy")
    if action == "copy":
        copied.assert_called_once_with(entry.code)


@pytest.mark.parametrize("action,result", [("macro-name-cancel", None), ("macro-name-save", "example.ijm")])
def test_macro_name_buttons(action, result):
    run_modal(MacroNameScreen("example.ijm"), action, result)


@pytest.mark.parametrize("action", ["run", "edit", "folder"])
def test_macro_picker_buttons(action):
    item = MacroItem(source="session", name="Sample", code='run("Blobs");')
    result = item if action == "run" else {"macro_action": "open_in_script_editor" if action == "edit" else "open_folder", "item": item}
    run_modal(ListPickerScreen("Macros", [item], macro_actions=True), "picklist-" + action, result)


@pytest.mark.parametrize("action,result", [("settings-cancel", None), ("settings-save", None),
    ("settings-model", "model"), ("settings-login", "login")])
def test_settings_buttons_commit_or_cancel(action, result):
    async def run():
        cfg, app, choices = ConsoleConfig(auto_start_fiji=False), ModalHost(), []
        original = vars(cfg).copy()
        async with app.run_test(size=(80, 24)) as pilot:
            screen = SettingsScreen(cfg)
            app.push_screen(screen, choices.append)
            await pilot.pause()
            screen.query_one("#settings-left-rail").value = False
            button = screen.query_one("#" + action, Button)
            button.scroll_visible(animate=False, immediate=True)
            await pilot.pause()
            assert await pilot.click("#" + action)
            await pilot.pause()
            assert vars(cfg) == original  # caller applies a returned snapshot
            if action == "settings-cancel":
                assert choices == [None]
            else:
                assert choices[0]["next"] == result
                assert choices[0]["values"]["show_left_rail"] is False
    asyncio.run(run())


def test_full_return_buttons_toggle_without_losing_raw():
    async def run():
        app = ModalHost()
        raw = '{"ok":true,"result":{"rows":3}}'
        async with app.run_test(size=(80, 24)) as pilot:
            screen = ToolResultScreen(ToolResultDetail("Results", "3 rows", raw=raw))
            app.push_screen(screen)
            await pilot.pause()
            assert await pilot.click("#tool-result-original")
            assert screen.query_one(TextArea).text == raw
            await pilot.pause(0.35)  # Textual suppresses clicks during its button animation
            assert await pilot.click("#tool-result-original")
            assert screen.query_one(TextArea).text == screen.formatted
            assert await pilot.click("#tool-result-close")
            assert app.query_one(Input).value == "preserve this draft"
    asyncio.run(run())


@pytest.mark.parametrize("index", [0, 7, 15])
def test_all_confirmation_choices_are_reachable(index):
    run_modal(ConfirmationScreen("test", "Choose a route", [f"Option {i}" for i in range(16)]),
              f"confirm-option-{index}", f"Option {index}")


def test_confirmation_cancel_remains_reachable():
    run_modal(ConfirmationScreen("test", "Choose a route", [f"Option {i}" for i in range(16)]),
              "confirm-cancel", None)


def test_long_confirmation_is_scrollable_without_hiding_cancel_or_choices():
    run_modal(ConfirmationScreen("test", "Long prompt. " * 300, [f"Option {i}" for i in range(16)]),
              "confirm-option-15", "Option 15")


def test_picker_enter_runs_highlighted_row_and_groups_are_skipped():
    async def run():
        app, choices = ModalHost(), []
        model = {"groups": [{"title": "Samples", "items": [{"label": "First"}, {"label": "Second"}]}]}
        async with app.run_test(size=(80, 24)) as pilot:
            screen = ListPickerScreen("Items", model)
            app.push_screen(screen, choices.append)
            await pilot.pause()
            await pilot.press("down", "enter")
            assert choices == [{"label": "Second"}]
    asyncio.run(run())


@pytest.mark.parametrize("action", ["btn-connect", "btn-forget", "btn-cancel"])
def test_login_buttons_are_reachable_and_work_at_standard_terminal(action, monkeypatch):
    from agent.console.config import save_secret, load_secret
    monkeypatch.setattr(tui.LoginScreen, "_probe_subscriptions", lambda self: None)
    validate = Mock(return_value=(True, "ready"))
    monkeypatch.setattr(tui, "validate_login", validate)
    save_secret("openai", "owned-test-key")
    async def run():
        app, choices = ModalHost(), []
        async with app.run_test(size=(80, 24)) as pilot:
            screen = tui.LoginScreen(ConsoleConfig())
            app.push_screen(screen, choices.append)
            await pilot.pause()
            screen.chosen_provider = "openai"
            screen._refresh_stages()
            await pilot.pause()
            button = screen.query_one("#" + action, Button)
            button.scroll_visible(animate=False, immediate=True)
            await pilot.pause()
            assert await pilot.click("#" + action)
            await pilot.pause(.35)
            if action == "btn-connect":
                assert choices and choices[0]["provider"] == "openai"
                validate.assert_called_once()
            elif action == "btn-forget":
                assert load_secret("openai") is None and not choices
                assert screen.query_one("#btn-forget", Button).disabled
            else:
                assert choices == [None] and load_secret("openai") == "owned-test-key"
    asyncio.run(run())


def test_browse_selection_buttons_preserve_hidden_selection_and_clear_everything(tmp_path):
    from agent.console.browse import SeriesEntry
    from agent.console.browse_ui import BrowseFilesScreen
    from textual.widgets import SelectionList
    async def run():
        app, choices = ModalHost(), []
        entries = [SeriesEntry(tmp_path / f"{name}.tif", 0, f"token{i}", name)
                   for i, name in enumerate(("Alpha", "Beta"))]
        async with app.run_test(size=(80, 24)) as pilot:
            screen = BrowseFilesScreen(str(tmp_path), entries, tag_rules=[])
            app.push_screen(screen, choices.append)
            await pilot.pause()
            assert await pilot.click("#browse-insert")
            assert not choices and "Select at least" in str(screen.query_one("#browse-preview", Static).content)
            assert await pilot.click("#browse-all")
            screen.query_one("#browse-filter", Input).value = "Alpha"
            await pilot.pause()
            assert len(screen._selected_entries()) == 2
            assert await pilot.click("#browse-clear")
            assert not screen._selected_entries()
            screen.query_one("#browse-filter", Input).value = ""
            await pilot.pause(.35)
            assert not screen.query_one(SelectionList).selected
            assert await pilot.click("#browse-all")
            screen.query_one("#browse-tag", Input).value = "owned test"
            await pilot.pause(.35)
            assert await pilot.click("#browse-insert")
            assert choices[0] == {"entries": entries, "tag": "owned test"}
    asyncio.run(run())


def test_browse_cancel_preserves_input(tmp_path):
    from agent.console.browse_ui import BrowseFilesScreen
    run_modal(BrowseFilesScreen(str(tmp_path), [], tag_rules=[]), "browse-cancel", None)


MAIN_BUTTONS = {
    "toggle-left": "action_toggle_left", "toggle-right": "action_toggle_right",
    "safety-indicator": "action_safety", "qa-open": "action_open_image",
    "qa-settings": "action_settings", "qa-capture": "capture", "qa-state": "get_state",
    "qa-results": "results", "qa-rois": "rois", "qa-dialogs": "close_dialogs",
}


@pytest.mark.parametrize("button,route", MAIN_BUTTONS.items())
def test_main_button_dispatch(button, route):
    host = Mock()
    host._fiji_call.side_effect = lambda name, label, call: call()
    tui.ConsoleApp.on_button_pressed(host, Button.Pressed(Button(id=button)))
    getattr(host.fiji if button in {"qa-capture", "qa-state", "qa-results", "qa-rois", "qa-dialogs"} else host, route).assert_called_once()


@pytest.mark.parametrize("item", __import__("agent.console.rail", fromlist=["rail_items"]).rail_items())
def test_each_rail_button_dispatches_its_own_action(item):
    host = Mock()
    tui.ConsoleApp.on_button_pressed(host, Button.Pressed(Button(id="rail-" + item.id.replace(".", "-"))))
    host._run_rail_item.assert_called_once_with(item.id)


@pytest.mark.parametrize("kind,index", [("suggestion", 0), ("suggestion", 2), ("clarification", 0), ("clarification", 1)])
def test_dynamic_prompt_buttons_dispatch_correct_choice(kind, index):
    host = Mock()
    tui.ConsoleApp.on_button_pressed(host, Button.Pressed(Button(id=f"{kind}-{index}")))
    getattr(host, "_accept_" + kind).assert_called_once_with(index)


def test_cancel_login_discards_late_validation_result(monkeypatch):
    import threading
    started, release = threading.Event(), threading.Event()
    def validate(*args, **kwargs):
        started.set()
        assert release.wait(5)
        return True, "ready"
    monkeypatch.setattr(tui, "validate_login", validate)
    monkeypatch.setattr(tui.LoginScreen, "_probe_subscriptions", lambda self: None)
    async def run():
        app, choices = ModalHost(), []
        async with app.run_test(size=(80, 24)) as pilot:
            screen = tui.LoginScreen(ConsoleConfig())
            app.push_screen(screen, choices.append)
            await pilot.pause()
            screen.chosen_provider = "ollama"
            screen._refresh_stages()
            await pilot.pause()
            await pilot.click("#btn-connect")
            assert await asyncio.to_thread(started.wait, 5)
            screen._connect()  # repeated Enter must not start another validation
            assert screen.query_one("#btn-connect", Button).disabled
            await pilot.press("escape")
            release.set()
            await pilot.pause(.35)
            assert choices == [None] and len(app.screen_stack) == 1
            assert app.query_one(Input).value == "preserve this draft"
    try:
        asyncio.run(run())
    finally:
        release.set()


def test_picker_refresh_ignores_cancelled_screen_and_old_generation(tmp_path, monkeypatch):
    from agent.console.catalog import CatalogEngine, CatalogEntry
    from agent.console.picker_ui import ModelPickerScreen
    import agent.console.picker_ui as picker
    monkeypatch.setattr(picker, "subscription_entries", lambda: [])
    engine = CatalogEngine(curated=[CatalogEntry("openai", "fixture")], credentials={}, endpoints={},
        cache_root=tmp_path / "cache", overrides_path=tmp_path / "overrides.yaml", state_path=tmp_path / "state.json")
    async def run():
        app = ModalHost()
        async with app.run_test(size=(80, 24)) as pilot:
            screen = ModelPickerScreen(engine)
            app.push_screen(screen)
            await pilot.pause()
            original = screen.result
            screen._refresh_generation = 2
            screen._finish_refresh(1, None, "stale failure")
            assert screen.result is original and "stale failure" not in str(screen.query_one("#picker-status", Static).content)
            await pilot.press("escape")
            screen._finish_refresh(2, original, None)
            assert len(app.screen_stack) == 1
    asyncio.run(run())


@pytest.mark.parametrize("command", list(COMMAND_ROUTES))
def test_every_command_through_chat_input(command, tmp_path, monkeypatch):
    """Use real screens and command bodies; fake only external Fiji/provider I/O."""
    from agent.console.fiji import FijiConnection
    from agent.console.catalog import CatalogEngine, CatalogEntry
    monkeypatch.setattr(tui.LoginScreen, "_probe_subscriptions", lambda self: None)
    monkeypatch.setattr("agent.console.picker_ui.subscription_entries", lambda: [])
    monkeypatch.setattr("agent.console.fiji_ui.candidates", lambda saved: [])
    monkeypatch.setattr(tui, "fiji_candidates", lambda saved: [])
    class Server:
        def __getattr__(self, name):
            def call(*args, **kwargs):
                return {"ok": True, "result": {"success": True, "images": [], "rows": [], "rois": [], "output": "", "enabled": ["safe_mode"]}}
            return call
    class OfflineConsole(tui.ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def _mention_folder(self): return tmp_path
    async def run():
        app = OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        app.fiji = FijiConnection("127.0.0.1", 1)
        app.fiji._module = lambda: Server()
        app.catalog = CatalogEngine(curated=[CatalogEntry("openai", "fixture")], credentials={}, endpoints={},
            cache_root=tmp_path / "cache", overrides_path=tmp_path / "overrides.yaml", state_path=tmp_path / "state.json")
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause()
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause()
            before_id = app.session.id
            messages = []
            original_log = app._log
            app._log = lambda text: (messages.append(str(text)), original_log(text))
            draft = app.query_one("#chat-input", Input)
            draft.value = command
            draft.cursor_position = len(command)
            await pilot.pause()
            await pilot.press("enter")
            await pilot.pause(.15)
            assert draft.value == ""
            if command == "/new":
                assert app.session.id != before_id
            elif command == "/clear":
                assert app.session.messages == []
            elif command == "/quit":
                assert app._exit
            else:
                assert messages or len(app.screen_stack) > 1, f"{command} gave no visible response"
            for text in messages:
                assert "unknown command" not in text.casefold()
    asyncio.run(run())


def test_export_saved_chat_without_provider_includes_full_evidence_and_never_overwrites():
    from agent.console import config
    app = tui.ConsoleApp(ConsoleConfig(auto_start_fiji=False))
    app._select_session(app.store.create())
    app._log = Mock()
    app._record_text_evidence("user", "Open Blobs")
    thinking = "Inspecting [literal brackets]. " * 1000
    app._record_text_evidence("thinking", thinking)
    app._evidence_append("tool_call", {"correlation_id": "owned", "tool": "run_macro", "arguments": {"code": 'run("Blobs");'}})
    app._record_tool_evidence("owned", "run_macro", {"code": 'run("Blobs");'}, True, "FULL RETURN " * 3000)
    app._record_text_evidence("assistant", "Opened Blobs")
    app._export_session()
    app._export_session()
    paths = list((config.CONFIG_DIR / "console/exports").glob("*.md"))
    assert len(paths) == 2
    text = paths[0].read_text(encoding="utf-8")
    assert thinking in text and "FULL RETURN " * 3000 in text
    assert 'run(\\"Blobs\\");' in text and "Opened Blobs" in text


def test_help_lists_every_command_and_full_return_shortcut():
    async def run():
        app = ModalHost()
        async with app.run_test(size=(80, 24)) as pilot:
            screen = tui.HelpScreen()
            app.push_screen(screen)
            await pilot.pause()
            text = str(screen.query_one(Static).content)
            assert all(command in text for command, _ in tui.SLASH_COMMANDS)
            assert "ctrl+r" in text
            await pilot.press("escape")
            assert app.query_one(Input).value == "preserve this draft"
    asyncio.run(run())


def test_new_buttons_cannot_escape_the_control_inventory():
    import ast
    root = Path(__file__).resolve().parents[3]
    static = set(MAIN_BUTTONS) | {
        "memory-review-renew", "memory-review-deprecate", "memory-review-attach", "memory-review-revalidate", "memory-review-close",
        "browse-all", "browse-clear", "browse-cancel", "browse-insert", "confirm-cancel",
        "cost-continue", "cost-free", "cost-cancel", "budget-raise", "budget-free", "budget-stop",
        "billing-model", "billing-free", "billing-close", "billing-account", "gov-statement", "gov-audit",
        "gov-close", "rcp-close", "history-toggle", "history-clear", "picklist-run", "picklist-edit",
        "picklist-folder", "macro-name-cancel", "macro-name-save", "safety-close", "settings-cancel",
        "settings-save", "settings-model", "settings-login", "tool-result-close", "tool-result-original",
        "btn-forget", "btn-connect", "btn-cancel", "btn-allow", "btn-always", "btn-deny",
    }
    dynamic = {"f'confirm-option-{index}'", "f'history-{action}'", "f'clarification-{index}'",
               "f'suggestion-{index}'", 'f"rail-{item.id.replace(\'.\', \'-\')}"'}
    found_static, found_dynamic = set(), set()
    for path in (root / "agent/console").glob("*.py"):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "Button":
                identifier = next((kw.value for kw in node.keywords if kw.arg == "id"), None)
                assert identifier is not None, f"Unidentified button in {path.name}:{node.lineno}"
                if isinstance(identifier, ast.Constant): found_static.add(identifier.value)
                else: found_dynamic.add(ast.unparse(identifier))
    assert found_static == static and found_dynamic == dynamic


@pytest.mark.parametrize("factory", [
    lambda: tui.PathScreen(), lambda: tui.HelpScreen(), lambda: SettingsScreen(ConsoleConfig()),
    lambda: tui.LoginScreen(ConsoleConfig()), lambda: tui.FijiPathScreen(roots=[]),
    lambda: tui.ModelPickerScreen(tui.CatalogEngine(curated=[], credentials={})),
    lambda: tui.EffortPickerScreen("openai", "fixture", ("low", "high")),
    lambda: tui.BrowseFilesScreen("Owned fixture", [], tag_rules=[]),
    lambda: CostNoticeScreen("openai", "Fixture change"),
    lambda: BudgetCeilingScreen(1, 1), lambda: BillingFailureScreen("openai", "Fixture failure"),
    lambda: HistoryEntryScreen(SessionCodeJournal().record('run("Blobs");')),
    lambda: MacroNameScreen("example.ijm"), lambda: tui.ApprovalScreen("run_shell", {"command": "owned"}),
    lambda: ConfirmationScreen("test", "Choose", ["Yes", "No"]),
    lambda: ReceiptsScreen([], "No calls"), lambda: ToolResultScreen(ToolResultDetail("Results", "Empty", raw="data")),
    lambda: GovernanceScreen({}), lambda: SafetyEventsScreen(SafetyState()),
    lambda: ListPickerScreen("Choices", [{"label": "Choice"}]),
])
def test_modal_escape_under_console_bindings_respects_dialog_choice(factory, monkeypatch):
    from agent.console.agent_loop import AbortFlag
    monkeypatch.setattr(tui.LoginScreen, "_probe_subscriptions", lambda self: None)
    class OfflineConsole(tui.ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def action_login(self): pass
    async def run():
        app = OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        async with app.run_test(size=(80, 24)) as pilot:
            draft = app.query_one("#chat-input", Input)
            draft.value = "keep this draft"
            app.turn_running, app.abort = True, AbortFlag()
            screen, choices = factory(), []
            app.push_screen(screen, choices.append)
            await pilot.pause()
            await pilot.press("escape")
            await pilot.pause()
            assert len(app.screen_stack) == 1 and len(choices) == 1
            assert draft.value == "keep this draft"
            # Spending-stop deliberately interrupts; other dialog dismissal
            # must not fall through to the chat interrupt shortcut.
            assert app.abort.set_flag is isinstance(screen, BudgetCeilingScreen)
            app.turn_running = False
    asyncio.run(run())


def test_event_time_controls_filter_existing_history_and_future_arrivals():
    import time
    from textual.widgets import RichLog, Select
    class OfflineConsole(tui.ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def action_login(self): pass
    async def run():
        app = OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        async with app.run_test(size=(120, 40)) as pilot:
            now = int(time.time() * 1000)
            for title, age in [("NEW", 0), ("OLD", 10 * 60_000), ("PAST", 2 * 60 * 60_000)]:
                app.event_feed.add({"event": "image.opened", "data": {"title": title}}, now_ms=now-age)
            period = app.query_one("#events-period", Select)
            for value, expected in [("5m", {"NEW"}), ("1h", {"NEW", "OLD"}),
                                    ("all", {"NEW", "OLD", "PAST"})]:
                period.value = value
                await pilot.pause()
                text = "\n".join(line.text for line in app.query_one("#events-log", RichLog).lines)
                assert {title for title in ("NEW", "OLD", "PAST") if title in text} == expected
            app.query_one("#events-category", Select).value = "dialog"
            await pilot.pause()
            app._render_event({"event": "image.opened", "data": {"title": "HIDDEN"}})
            app._render_event({"event": "dialog.appeared", "data": {"title": "VISIBLE"}})
            text = "\n".join(line.text for line in app.query_one("#events-log", RichLog).lines)
            assert "VISIBLE" in text and "HIDDEN" not in text
    asyncio.run(run())


def test_login_model_aliases_with_the_same_identifier_remain_selectable(monkeypatch):
    from agent.console.providers import ModelEntry
    from textual.widgets import OptionList
    rows = [ModelEntry("ollama", "same-model", "First alias"),
            ModelEntry("ollama", "same-model", "Second alias")]
    validate = Mock(return_value=(True, "Ready"))
    monkeypatch.setattr(tui, "models_for", lambda provider: rows)
    monkeypatch.setattr(tui, "validate_login", validate)
    monkeypatch.setattr(tui.LoginScreen, "_probe_subscriptions", lambda self: None)
    async def run():
        app, choices = ModalHost(), []
        async with app.run_test(size=(80, 24)) as pilot:
            screen = tui.LoginScreen(ConsoleConfig())
            app.push_screen(screen, choices.append)
            await pilot.pause()
            screen.chosen_provider = "ollama"
            screen._refresh_stages()
            listing = screen.query_one("#model-list", OptionList)
            assert listing.option_count == 2
            listing.highlighted = 1
            await pilot.pause()
            listing.focus()
            await pilot.press("enter")
            await pilot.pause()
            assert choices == [{"provider": "ollama", "model": "same-model"}]
            validate.assert_called_once_with("ollama", "same-model", api_key=None, save_key=False)
    asyncio.run(run())


@pytest.mark.parametrize("size", [(80, 24), (120, 40)])
def test_main_and_sidebar_buttons_are_reachable_by_actual_click(size, monkeypatch):
    from agent.console.rail import rail_items
    class OfflineConsole(tui.ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def action_login(self): pass
    async def run():
        app = OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        async with app.run_test(size=size) as pilot:
            calls = {}
            app._fiji_call = lambda name, label, action: action()
            for button, route in MAIN_BUTTONS.items():
                owner = app.fiji if button in {"qa-capture", "qa-state", "qa-results", "qa-rois", "qa-dialogs"} else app
                calls[button] = Mock()
                monkeypatch.setattr(owner, route, calls[button])
            rail_action = Mock()
            monkeypatch.setattr(app, "_run_rail_item", rail_action)
            for button, callback in calls.items():
                widget = app.query_one("#" + button, Button)
                widget.scroll_visible(animate=False, immediate=True)
                await pilot.pause()
                assert await pilot.click("#" + button), f"{button} is obstructed at {size}"
                await pilot.pause()
                callback.assert_called_once()
            for item in rail_items():
                identifier = "rail-" + item.id.replace(".", "-")
                widget = app.query_one("#" + identifier, Button)
                widget.scroll_visible(animate=False, immediate=True)
                await pilot.pause()
                assert await pilot.click("#" + identifier), f"{identifier} is obstructed at {size}"
                await pilot.pause()
                rail_action.assert_called_with(item.id)
    asyncio.run(run())


def test_every_suggestion_button_can_be_clicked_in_the_normal_chat_layout(monkeypatch):
    class OfflineConsole(tui.ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def action_login(self): pass
    async def run():
        app = OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        async with app.run_test(size=(120, 40)) as pilot:
            app.query_one("#chat-input", Input).value = "open"
            await pilot.pause()
            assert app.suggestion_chips
            choose = Mock()
            monkeypatch.setattr(app, "_accept_suggestion", choose)
            for index in range(len(app.suggestion_chips)):
                assert await pilot.click(f"#suggestion-{index}"), f"Suggestion {index} is obstructed"
                await pilot.pause()
                choose.assert_called_with(index)
    asyncio.run(run())
