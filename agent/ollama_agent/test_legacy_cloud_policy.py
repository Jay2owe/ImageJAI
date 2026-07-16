from __future__ import annotations

from types import SimpleNamespace

import pytest

from agent.ollama_agent import legacy_tool_policy, ollama_chat, ollama_router


def _tool_names(tools):
    return {item.__name__ for item in tools}


def _clear_grant(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", raising=False)
    monkeypatch.delenv("OLLAMA_HOST", raising=False)


def test_cloud_schemas_exclude_host_file_agentconsole_iot_and_delegation(monkeypatch):
    _clear_grant(monkeypatch)

    chat_names = _tool_names(
        legacy_tool_policy.allowed_tools_for_model(
            "gemma4:31b-cloud", ollama_chat.ALL_TOOLS + [ollama_chat.learn_new_tool]
        )
    )
    router_names = _tool_names(
        legacy_tool_policy.allowed_tools_for_model(
            "gemma4:31b-cloud", ollama_router.ALL_TOOLS
        )
    )

    assert chat_names == {"get_weather", "get_datetime"}
    assert router_names == set()
    assert {
        "run_shell",
        "read_file",
        "agent_command",
        "control_device",
        "delegate_to_codex",
        "learn_new_tool",
    }.isdisjoint(chat_names)


def test_forged_cloud_host_tool_call_is_denied_before_execution(monkeypatch):
    _clear_grant(monkeypatch)
    calls = []

    result = legacy_tool_policy.dispatch_tool_for_model(
        "gemma4:31b-cloud",
        "run_shell",
        {"command": "whoami"},
        {"run_shell": lambda command: calls.append(command) or "executed"},
    )

    assert result.startswith("ERROR:")
    assert "not permitted" in result
    assert calls == []


def test_local_host_tools_require_explicit_grant_and_loopback(monkeypatch):
    _clear_grant(monkeypatch)
    assert not legacy_tool_policy.host_tools_allowed("gemma4:e4b")

    monkeypatch.setenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", "yes")
    monkeypatch.setenv("OLLAMA_HOST", "http://127.0.0.1:11434")
    assert legacy_tool_policy.host_tools_allowed("gemma4:e4b")
    assert "run_shell" in _tool_names(
        legacy_tool_policy.allowed_tools_for_model(
            "gemma4:e4b", ollama_chat.ALL_TOOLS
        )
    )

    monkeypatch.setenv("OLLAMA_HOST", "http://remote-host:11434")
    try:
        legacy_tool_policy.host_tools_allowed("gemma4:e4b")
    except ValueError as exc:
        assert "loopback" in str(exc)
    else:
        raise AssertionError("remote Ollama host must not receive host tools")


def test_router_cloud_loop_sends_no_tools_and_rejects_forged_call(monkeypatch):
    _clear_grant(monkeypatch)
    calls = []
    model_calls = []

    def forbidden_agent_command(command):
        calls.append(command)
        return "executed"

    monkeypatch.setitem(ollama_router.TOOL_MAP, "agent_command", forbidden_agent_command)

    def fake_chat(**kwargs):
        model_calls.append(kwargs)
        if len(model_calls) == 1:
            tool_call = SimpleNamespace(
                function=SimpleNamespace(
                    name="agent_command", arguments={"command": "list"}
                )
            )
            return SimpleNamespace(
                message=SimpleNamespace(content="", tool_calls=[tool_call])
            )
        return SimpleNamespace(
            message=SimpleNamespace(content="denied safely", tool_calls=[])
        )

    monkeypatch.setattr(ollama_router.ollama, "chat", fake_chat)

    handled, response, error = ollama_router._run_tool_loop(
        "gemma4:31b-cloud", "list agents"
    )

    assert handled is True
    assert response == "denied safely"
    assert error is None
    assert model_calls[0]["tools"] == []
    assert calls == []
    assert any(
        isinstance(item, dict)
        and item.get("role") == "tool"
        and item.get("content", "").startswith("ERROR:")
        for item in model_calls[1]["messages"]
    )


def test_chat_cloud_loop_excludes_and_rejects_host_tool(monkeypatch):
    _clear_grant(monkeypatch)
    calls = []
    model_calls = []
    monkeypatch.setitem(
        ollama_chat.TOOL_MAP,
        "run_shell",
        lambda command: calls.append(command) or "executed",
    )

    def fake_chat(**kwargs):
        model_calls.append(kwargs)
        if len(model_calls) == 1:
            tool_call = SimpleNamespace(
                function=SimpleNamespace(
                    name="run_shell", arguments={"command": "whoami"}
                )
            )
            return SimpleNamespace(
                message=SimpleNamespace(content="", tool_calls=[tool_call])
            )
        return SimpleNamespace(
            message=SimpleNamespace(content="denied safely", tool_calls=[])
        )

    monkeypatch.setattr(ollama_chat.ollama, "chat", fake_chat)

    handled, response, error = ollama_chat._run_tool_loop(
        "gemma4:31b-cloud", "read a local file"
    )

    assert handled is True
    assert response == "denied safely"
    assert error is None
    assert _tool_names(model_calls[0]["tools"]) == {"get_weather", "get_datetime"}
    assert calls == []
    assert any(
        isinstance(item, dict)
        and item.get("role") == "tool"
        and item.get("content", "").startswith("ERROR:")
        for item in model_calls[1]["messages"]
    )


def test_cloud_model_cannot_launch_host_capable_cli_surface(monkeypatch):
    _clear_grant(monkeypatch)
    monkeypatch.setattr(
        ollama_chat.sys,
        "argv",
        ["ollama_chat", "--cloud", "--launcher", "codex"],
    )
    monkeypatch.setattr(ollama_chat, "_pick_model", lambda requested: requested)
    monkeypatch.setattr(ollama_chat, "_install_shutdown_hooks", lambda: None)
    monkeypatch.setattr(
        ollama_chat,
        "_launch_via_ollama_surface",
        lambda launcher, model: (_ for _ in ()).throw(
            AssertionError("cloud launch must fail before spawning Codex")
        ),
    )

    with pytest.raises(SystemExit, match="cloud models cannot launch"):
        ollama_chat.main()
