import json
from pathlib import Path

import pytest

from agent.console.agent_loop import TurnCallbacks
from agent.console import installed_agents as installed


def test_discovery_never_launches_and_does_not_mistake_gh_for_copilot(monkeypatch):
    monkeypatch.setattr(installed.shutil, "which", lambda n: "gemini.cmd" if n == "gemini" else None)
    assert [e.provider for e in installed.installed_entries()] == ["gemini-cli"]
    assert installed.executable("copilot-cli") is None


@pytest.mark.parametrize("provider", sorted(installed.CLI_PROVIDERS))
def test_prompt_never_in_argv_and_no_permission_bypass(provider, tmp_path):
    prompt = 'run("Blobs");\n' + "private & $(text)" * 10000
    argv, stdin = installed.command(provider, "program.cmd", "chosen-model", "default", tmp_path, prompt)
    assert not any("private" in arg for arg in argv)
    assert "--yolo" not in argv and "--allow-all" not in argv
    assert "chosen-model" in argv
    if provider == "aider-cli":
        assert Path(argv[argv.index("--message-file") + 1]).read_text(encoding="utf-8") == prompt
    else:
        assert stdin == prompt


def test_gemini_stream_is_text_only_and_final_usage_retained():
    seen = []
    output = installed.AgentOutput("gemini-cli", TurnCallbacks(on_text_delta=seen.append))
    for event in [{"type": "init", "session_id": "native"},
                  {"type": "message", "role": "user", "content": "private catalogue"},
                  {"type": "message", "role": "assistant", "content": "Ready"},
                  {"type": "result", "status": "success", "stats": {"total_tokens": 25}}]:
        output.feed(json.dumps(event))
    assert seen == ["Ready"] and output.response() == "Ready"
    assert output.usage == {"total_tokens": 25}


@pytest.mark.parametrize("provider", sorted(installed.CLI_PROVIDERS))
def test_all_routes_share_direct_fiji_action_execution_and_saved_context(provider, monkeypatch):
    from agent.console import subscriptions
    monkeypatch.setattr(subscriptions, "_executable", lambda _: "program")
    monkeypatch.setattr(subscriptions, "subscription_status", lambda _: (True, "installed"))
    calls = []
    def macro(code):
        calls.append(code)
        return "opened"
    macro.__name__ = "run_macro"
    monkeypatch.setattr(subscriptions, "_import_registry", lambda: ([macro], set()))
    agent = subscriptions.SubscriptionAgent(provider)
    agent.messages = [{"role": "user", "content": "Earlier context"}]
    answers = iter(['<imagejai-action>{"id":"one","tool":"run_macro","arguments":{"code":"run(\\\"Blobs\\\");"}}</imagejai-action>', "Opened Blobs"])
    contexts = []
    usage = []
    def run(argv, workspace, stdin=None, on_stdout=None):
        contexts.append(stdin or Path(argv[argv.index("--message-file")+1]).read_text(encoding="utf-8"))
        answer = next(answers)
        if provider == "gemini-cli":
            on_stdout(json.dumps({"type": "message", "role": "assistant", "content": answer}))
        elif provider == "cline-cli":
            on_stdout(json.dumps({"type": "say", "say": "text", "text": answer}))
        elif provider == "interpreter-cli":
            on_stdout(json.dumps({"type": "item.completed", "item": {"type": "agent_message", "text": answer}}))
        elif provider == "aider-cli":
            path = Path(argv[argv.index("--llm-history-file")+1])
            path.write_text("LLM RESPONSE 2026-09-28T12:00:00\n" + "\n".join("ASSISTANT " + line for line in answer.splitlines()), encoding="utf-8")
        else:
            on_stdout(answer)
        return 0, "", ""
    monkeypatch.setattr(agent, "_run", run)
    monkeypatch.setattr(agent, "_execute_tool", lambda name, args: (calls.append(args["code"]) is None, "Opened"))
    assert agent.turn("Open Blobs", TurnCallbacks(on_usage=usage.append))
    assert calls == ['run("Blobs");']
    assert all("Earlier context" in text for text in contexts)
    assert "imagejai-result" in contexts[-1]
    assert [row["input_tokens"] for row in usage] == [len(text) // 4 for text in contexts]
    assert agent.external_session_id.startswith("console-context:")


def test_prose_and_examples_never_become_executable_action_messages(tmp_path):
    from agent.console.wrapped_tools import parse_action
    example = 'Example: <imagejai-action>{"id":"one","tool":"get_state","arguments":{}}</imagejai-action>'
    output = installed.AgentOutput("copilot-cli", TurnCallbacks())
    output.feed(example)
    assert output.response() == example and parse_action(output.response()) is None
    log = tmp_path / "response.log"
    log.write_text("TO LLM 2026-09-28T12:00:00\nUSER private catalogue\nLLM RESPONSE 2026-09-28T12:00:01\nASSISTANT " + example, encoding="utf-8")
    assert installed.aider_response(log) == example
    assert parse_action(installed.aider_response(log)) is None
