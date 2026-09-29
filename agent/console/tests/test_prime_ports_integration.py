"""Provider-independent action tools, real child pixels, input and review UI."""
import asyncio
import base64
import json
from types import SimpleNamespace
import pytest
from textual.app import App
from textual.widgets import Input, Button
from agent.console.agent_loop import AbortFlag, ConsoleAgent, TurnCallbacks
from agent.console.kernel import PythonWorkspace
from agent.console.runtime_tools import attach_runtime_tools
from agent.console.wrapped_tools import ActionRequest, execute_action, ACTION_OPEN, ACTION_CLOSE
from agent.console.review_ui import MemoryReviewScreen
from agent.console.harness import HarnessStore
from agent.console import tui
from agent.console.config import ConsoleConfig
from unittest.mock import Mock

class PixelFiji:
    def __init__(self): self.revision, self.calls = 1, []
    def command(self, payload, **kwargs):
        import numpy as np
        self.calls.append(payload)
        w, h = payload.get("width",2), payload.get("height",2)
        values = np.arange(1,w*h+1,dtype="<f4")
        return {"ok":True, "result":{"data":base64.b64encode(values.tobytes()).decode(), "width":w,"height":h,
            "image_id":1,"image_revision":self.revision,"display_revision":1,"encoding":"base64_float32_le",
            "channel":1,"frame":1,"sliceStart":1,"sliceEnd":1}}

@pytest.mark.parametrize("provider", ["ollama", "openai", "codex-subscription", "claude-subscription", "gemini-cli"])
def test_every_route_uses_the_same_approved_python_tool(provider):
    from agent.console.workspace import ensure_importable
    ensure_importable()
    from agent.console.subscriptions import SubscriptionAgent
    cls = SubscriptionAgent if provider in {"codex-subscription", "claude-subscription", "gemini-cli"} else ConsoleAgent
    agent = cls.__new__(cls)
    agent.provider, agent.model = provider, "test"
    agent.tools, agent.host_code_tools = [], frozenset()
    agent.abort, agent.messages, agent.action_receipts = AbortFlag(), [], {}
    agent.fiji_connection, agent.tool_result_filter = PixelFiji(), None
    workspace = PythonWorkspace(agent.fiji_connection)
    try:
        attach_runtime_tools(agent, workspace=workspace, skill_loader=lambda name:"Use calibrated units")
        request = ActionRequest("p1", "python_cell", {"code":"a = pixels(0,0,2,2); print(float(a.mean()))", "timeout":10})
        assert not execute_action(agent,request,TurnCallbacks())["ok"]
        receipt = execute_action(agent,ActionRequest("p2",request.tool,request.arguments),TurnCallbacks(on_approval=lambda *a:True))
        assert receipt["ok"], receipt
        assert "2.5" in receipt["result"]
        assert execute_action(agent,ActionRequest("p3", "load_skill", {"name":"measure"}),TurnCallbacks())["ok"]
        assert workspace.execute("int(a.sum())")["output"].strip() == "10"
        agent.fiji_connection.revision = 2
        assert not workspace.execute("a.mean()")["ok"]
        assert all(row["command"] == "get_pixels" for row in agent.fiji_connection.calls)
    finally:
        workspace.close()

def test_actual_turn_suppresses_old_action_when_correction_arrives():
    from agent.console.tests.test_wrapped_tools import wrapped_agent, action
    agent, calls = wrapped_agent("ollama")
    pending, seen = [], []
    class Client:
        def __init__(self): self.round=0
        def chat(self, messages, tools, model, **kwargs):
            self.round+=1
            if self.round == 1:
                pending.append("Use channel 2 instead")
                return action(arguments={"code":"wrong macro"})
            assert any("channel 2" in str(m.get("content")) for m in messages)
            return "I will use channel 2."
        def extract_text(self, reply): return reply
        def extract_tool_calls(self, reply): return []
        def append_assistant(self, messages, reply): messages.append({"role":"assistant","content":reply})
    agent.client=Client()
    def take():
        out=list(pending)
        pending.clear()
        return out
    assert agent.turn("open image",TurnCallbacks(take_steering=take,on_user=seen.append))
    assert not calls and seen == ["open image","Use channel 2 instead"]

class Host(App):
    def compose(self): yield Input(id="main-draft", value="preserve")

@pytest.mark.parametrize("button,expected", [("memory-review-renew","renew"), ("memory-review-deprecate","deprecate"),
    ("memory-review-attach","attach"), ("memory-review-revalidate","revalidate"), ("memory-review-close",None)])
def test_every_review_button_is_clickable_and_returns_the_selected_action(tmp_path,button,expected):
    store=HarnessStore(tmp_path/"state.json",tmp_path/"events.jsonl")
    entry=store.propose(kind="fact",scope="user",title="Calibration",content="Use verified units")
    async def run():
        app,answers=Host(),[]
        async with app.run_test(size=(80,24)) as pilot:
            screen=MemoryReviewScreen(entry)
            app.push_screen(screen,answers.append)
            await pilot.pause()
            screen.query_one("#memory-review-evidence",Input).value="Verified current metadata"
            screen.query_one("#memory-review-receipt",Input).value="receipt.json"
            target=screen.query_one("#"+button,Button)
            target.scroll_visible(animate=False,immediate=True)
            await pilot.pause()
            assert await pilot.click("#"+button)
            await pilot.pause()
            assert len(answers)==1
            assert (answers[0]["action"] if answers[0] else None)==expected
            assert app.query_one("#main-draft",Input).value == "preserve"
    asyncio.run(run())

def test_queue_survives_a_postponed_send_and_consumes_only_on_start(monkeypatch):
    app=tui.ConsoleApp(ConsoleConfig(auto_start_fiji=False))
    app._log=Mock()
    app.action_login=Mock()
    app.show_clarifications=Mock()
    app._select_session(app.store.create())
    row=app.prompt_queue.put("measure channel two", "follow_up")
    app._deliver_next_prompt()
    assert app.prompt_queue.snapshot()[0]["id"] == row["id"]
    app.agent=SimpleNamespace(abort=AbortFlag())
    app._cost_notice_before_send=lambda *a,**k:False
    app._run_turn=Mock()
    app._deliver_next_prompt()
    assert app.turn_running and not app.prompt_queue.snapshot()
    assert app.session.pending_turns == []
    app._run_turn.assert_called_once()

def test_busy_chat_input_steers_and_queues_without_losing_drafts(tmp_path,monkeypatch):
    monkeypatch.setattr(tui.LoginScreen,"_probe_subscriptions",lambda self:None)
    class OfflineConsole(tui.ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def _mention_folder(self): return tmp_path
    async def run():
        app=OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        async with app.run_test(size=(120,40)) as pilot:
            await pilot.pause()
            while len(app.screen_stack)>1: app.pop_screen()
            app.turn_running=True
            draft=app.query_one("#chat-input",Input)
            for text in ("Use channel 2", "/queue Count cells next"):
                draft.value=text
                await pilot.pause()
                await pilot.press("enter")
                await pilot.pause()
                assert draft.value == "" and not draft.disabled
            assert [(r["mode"],r["text"]) for r in app.prompt_queue.snapshot()] == [("steer","Use channel 2"),("follow_up","Count cells next")]
            app.turn_running=False
            app._persist_session()
            saved=app.store.get(app.session.id)
            assert len(saved.pending_turns)==2
            app._select_session(saved)
            assert len(app.prompt_queue.snapshot())==2
            app.turn_running=True
            draft.value="/queue clear"
            await pilot.pause()
            await pilot.press("enter")
            await pilot.pause()
            assert not app.prompt_queue.snapshot()
            app.turn_running=False
    asyncio.run(run())


@pytest.mark.parametrize("choice", ["/queue", {"command": "/queue"}])
def test_command_palette_places_the_caret_after_the_inserted_command(tmp_path, monkeypatch, choice):
    monkeypatch.setattr(tui.LoginScreen, "_probe_subscriptions", lambda self: None)

    class OfflineConsole(tui.ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def _mention_folder(self): return tmp_path

    async def run():
        app = OfflineConsole(ConsoleConfig(auto_start_fiji=False))
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause()
            while len(app.screen_stack) > 1:
                app.pop_screen()
            draft = app.query_one("#chat-input", Input)
            draft.value = ""
            draft.cursor_position = 0
            app._rail_choice("Commands", choice)
            await pilot.pause()
            await pilot.press("a")
            assert draft.value == "/queue a"
            assert draft.cursor_position == len(draft.value)

    asyncio.run(run())

def test_native_multi_tool_results_remain_paired_before_correction():
    from agent.console.tests.test_wrapped_tools import wrapped_agent
    from agent.providers.base import ToolCall
    agent,calls=wrapped_agent("openai")
    pending=[]
    class Client:
        def __init__(self): self.round=0
        def chat(self,messages,*a,**k):
            self.round+=1
            if self.round==1: pending.append("Cancel those old macros")
            return self.round
        def extract_tool_calls(self,reply): return [ToolCall("one","run_macro",{"code":"old"}),ToolCall("two","run_macro",{"code":"old2"})] if reply==1 else []
        def extract_text(self,reply): return "Changed direction"
        def append_assistant(self,messages,reply): messages.append({"role":"assistant","content":"reply", **({"tool_calls":[{"id":"one"},{"id":"two"}]} if reply==1 else {})})
        def append_tool_result(self,messages,call,text): messages.append({"role":"tool","tool_call_id":call.id,"content":text})
    agent.client=Client()
    def take():
        result=list(pending)
        pending.clear()
        return result
    assert agent.turn("do analysis",TurnCallbacks(take_steering=take))
    assert not calls
    roles=[m["role"] for m in agent.messages]
    index=roles.index("tool")
    assert roles[index:index+3] == ["tool","tool","user"]

def test_validation_confirmation_displays_the_end_of_a_long_macro():
    from agent.console.confirm_ui import ConfirmationScreen
    macro="print(1);\n"*1200+'print("review the end");'
    screen=ConfirmationScreen("procedure-validation",macro,["Run validation","Cancel"],max_prompt_chars=70000)
    assert screen.prompt.endswith('print("review the end");')

def test_interrupt_preserves_all_native_tool_request_result_pairs():
    from agent.console.tests.test_wrapped_tools import wrapped_agent
    from agent.providers.base import ToolCall
    agent,_=wrapped_agent("openai")
    class Client:
        def chat(self,*a,**k): return "native"
        def extract_text(self,reply): return ""
        def extract_tool_calls(self,reply): return [ToolCall("a","read",{}),ToolCall("b","read",{})]
        def append_assistant(self,messages,reply): messages.append({"role":"assistant","tool_calls":[{"id":"a"},{"id":"b"}]})
        def append_tool_result(self,messages,call,text): messages.append({"role":"tool","tool_call_id":call.id,"content":text})
    agent.client=Client()
    executed=[]
    def tool(name,args):
        executed.append(name)
        agent.abort.set()
        return True,"completed first read"
    agent._execute_tool=tool
    assert not agent.turn("read two things",TurnCallbacks())
    assert executed == ["read"]
    replies=[m for m in agent.messages if m["role"]=="tool"]
    assert [m["tool_call_id"] for m in replies] == ["a","b"]
    assert "not executed" in replies[1]["content"]

def test_side_answers_replay_and_export_without_entering_main_history():
    from agent.console.replay import replay_entries
    messages=[{"role":"user","content":"main question"}]
    events=[{"type":"user","payload":{"text":"main question"}},
        {"type":"decision","payload":{"kind":"side_user","text":"explain units"}},
        {"type":"decision","payload":{"kind":"side_assistant","text":"Micrometres"}}]
    entries=replay_entries(events,messages)
    assert [(row.kind,row.text) for row in entries][-2:]==[("side_user","explain units"),("side_assistant","Micrometres")]
    assert messages == [{"role":"user","content":"main question"}]
