"""Safety status requires server confirmation and preserves readable events."""
import asyncio

from textual.app import App
from textual.widgets import Input, Static

from agent.console.safety_ui import SafetyEventsScreen, SafetyState


def test_safety_distinguishes_offline_unknown_enabled_and_disabled():
    state = SafetyState()
    assert state.label()[0].endswith("offline")
    state.update_status(True)
    assert state.label()[0].endswith("unconfirmed")
    state.update_status(True, True)
    assert state.label() == ("Safe mode: on", "green")
    state.update_status(True, False)
    assert state.label() == ("Safe mode: off", "yellow")
    state.update_status(False)
    assert state.enabled is None


def test_recent_blocks_keep_reason_line_and_target_then_decay_without_losing_history():
    state = SafetyState()
    state.update_status(True, True)
    frame = {"event": "safe_mode.blocked", "data": {"rule_id": "file_delete", "line": 2, "target": "data.tif"}}
    state.add(frame, now=100)
    assert "File deletion was blocked" in state.recent[0].message
    assert "macro line 2" in state.recent[0].message and "data.tif" in state.recent[0].message
    assert state.label(now=110) == ("Safe mode: blocked", "red")
    assert state.label(now=161) == ("Safe mode: on", "green")
    assert len(state.recent) == 1
    for i in range(5):
        state.add({"topic": "safe_mode.warned", "data": {"reason": str(i)}}, now=200 + i)
    assert len(state.recent) == 3 and state.recent[0].message == "Warning: 4"
    assert not state.add({"event": "image.opened"})


def test_events_do_not_claim_unconfirmed_mode_is_enabled():
    state = SafetyState(connected=True)
    state.add({"event": "safe_mode.passed"})
    assert state.enabled is None and "unconfirmed" in state.label()[0]


def test_safe_mode_reads_selected_host_and_cached_hello(monkeypatch):
    from agent.console.fiji import FijiConnection
    connection = FijiConnection("fixture-host", 12345)
    seen = []
    class Module:
        def hello(self, **args):
            seen.append(args)
            return {"ok": True, "result": {"enabled": ["safe_mode", "vision"]}}
    monkeypatch.setattr(connection, "_require_selected", lambda: None)
    monkeypatch.setattr(connection, "_module", lambda: Module())
    assert connection.safe_mode_status() is True
    assert seen == [{"host": "fixture-host", "port": 12345}]


def test_recent_events_view_updates_and_escape_preserves_draft():
    class Host(App):
        def compose(self):
            yield Input(value="existing draft", id="draft")
    async def run():
        app = Host()
        state = SafetyState(connected=True, enabled=True)
        async with app.run_test(size=(80, 24)) as pilot:
            app.push_screen(SafetyEventsScreen(state))
            await pilot.pause()
            assert "No safe-mode" in str(app.screen.query_one("#safety-recent", Static).render())
            state.add({"event": "safe_mode.blocked", "data": {"reason": "literal [red] text"}})
            app.screen.refresh_events()
            assert "literal [red] text" in str(app.screen.query_one("#safety-recent", Static).render())
            await pilot.press("escape")
            assert app.query_one(Input).value == "existing draft"
    asyncio.run(run())
