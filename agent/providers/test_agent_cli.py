"""Tests for the multi-provider Fiji agent entry point.

These exercise argument/env handling, the Fiji tool registry, tool dispatch,
and the per-turn tool loop with a fake ProviderClient — no live model or Fiji
required.
"""
from __future__ import annotations

import agent.providers.agent_cli as cli
from agent.providers.base import (
    HOST_CODE_CAPABILITY,
    ProviderToolPolicy,
    ToolCall,
    to_anthropic_tool,
    to_gemini_tool,
    to_openai_tool,
)


def _clear_provider_env(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_PROVIDER", raising=False)
    monkeypatch.delenv("IMAGEJAI_MODEL", raising=False)
    monkeypatch.delenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", raising=False)
    monkeypatch.delenv("OLLAMA_HOST", raising=False)


def test_tool_map_has_core_fiji_tools():
    for name in ["run_macro", "get_state", "get_image_info",
                 "get_results", "probe_plugin", "get_console", "close_dialogs"]:
        assert name in cli.TOOL_MAP
    assert "run_script" not in cli.TOOL_MAP


def test_every_tool_converts_to_all_three_schemas():
    for fn in cli.FIJI_TOOLS:
        assert to_openai_tool(fn)["function"]["name"] == fn.__name__
        assert to_anthropic_tool(fn)["name"] == fn.__name__
        assert to_gemini_tool(fn)["name"] == fn.__name__


def test_main_requires_provider_and_model(monkeypatch, capsys):
    _clear_provider_env(monkeypatch)
    assert cli.main([]) == 2
    assert cli.main(["--provider", "groq"]) == 2
    assert cli.main(["--model", "llama-3.3-70b-versatile"]) == 2
    assert "required" in capsys.readouterr().err


def test_main_rejects_unknown_provider(monkeypatch, capsys):
    _clear_provider_env(monkeypatch)
    assert cli.main(["--provider", "not-a-provider", "--model", "x"]) == 2
    assert "unknown provider" in capsys.readouterr().err


def test_main_routes_ollama_to_gemma_wrapper(monkeypatch):
    """ollama / ollama-cloud picks must run the animated gemma4_31b wrapper,
    not the thin provider REPL. Regression guard for the picker routing the
    user tuned via the gemma improvement loop."""
    _clear_provider_env(monkeypatch)
    seen: list[tuple[str, str, dict]] = []

    def fake_wrapper(provider, model, opts):
        seen.append((provider, model, opts))
        return 0

    def boom_rich(*args, **kwargs):  # the provider-client loop must NOT be reached
        raise AssertionError("ollama must not fall through to rich provider wrapper")

    monkeypatch.setattr(cli, "_run_ollama_wrapper", fake_wrapper)
    monkeypatch.setattr(cli, "_run_rich_provider_wrapper", boom_rich)

    assert cli.main(["--provider", "ollama-cloud", "--model", "gemma4:31b-cloud"]) == 0
    assert cli.main(["--provider", "ollama", "--model", "gemma3:27b"]) == 0
    assert seen == [
        ("ollama-cloud", "gemma4:31b-cloud", {}),
        ("ollama", "gemma3:27b", {}),
    ]


def test_main_ollama_wrapper_importerror_falls_back(monkeypatch, capsys):
    """If the wrapper (or its ollama/PIL deps) can't be imported, fall back to
    the generic provider loop instead of crashing."""
    _clear_provider_env(monkeypatch)

    def missing_wrapper(provider, model, opts):
        del provider, model, opts
        raise ImportError("no module named ollama")

    fell_back: list[tuple[str, str]] = []

    def fake_rich(provider, model, opts, **kwargs):
        fell_back.append((provider, model))
        return 0

    monkeypatch.setattr(cli, "_run_ollama_wrapper", missing_wrapper)
    monkeypatch.setattr(cli, "_run_rich_provider_wrapper", fake_rich)

    assert cli.main(["--provider", "ollama-cloud", "--model", "gemma4:31b-cloud"]) == 0
    assert fell_back == [("ollama-cloud", "gemma4:31b-cloud")]
    assert "wrapper unavailable" in capsys.readouterr().err


def test_main_routes_non_ollama_to_rich_wrapper(monkeypatch):
    _clear_provider_env(monkeypatch)
    seen: list[tuple[str, str]] = []

    def fake_rich(provider, model, opts):
        seen.append((provider, model))
        return 0

    def boom_run(*args, **kwargs):
        raise AssertionError("main must not use the thin provider REPL")

    monkeypatch.setattr(cli, "_run_rich_provider_wrapper", fake_rich)
    monkeypatch.setattr(cli, "run", boom_run)

    assert cli.main(["--provider", "groq", "--model", "llama-3.3-70b-versatile"]) == 0
    assert seen == [("groq", "llama-3.3-70b-versatile")]


def test_explicit_local_host_code_grant_reaches_ollama_wrapper(monkeypatch):
    _clear_provider_env(monkeypatch)
    seen = []

    def fake_wrapper(provider, model, opts):
        seen.append((provider, model, opts))
        return 0

    monkeypatch.setattr(cli, "_run_ollama_wrapper", fake_wrapper)

    assert cli.main([
        "--provider", "ollama",
        "--model", "gemma3:27b",
        "--allow-local-host-code",
    ]) == 0
    assert seen == [(
        "ollama",
        "gemma3:27b",
        {"capabilities": [HOST_CODE_CAPABILITY]},
    )]


def test_local_host_code_env_is_strict_and_local_only(monkeypatch, capsys):
    _clear_provider_env(monkeypatch)
    monkeypatch.setenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", "yes")
    seen = []
    monkeypatch.setattr(
        cli,
        "_run_ollama_wrapper",
        lambda provider, model, opts: seen.append((provider, opts)) or 0,
    )

    assert cli.main(["--provider", "ollama", "--model", "gemma3:27b"]) == 0
    assert seen == [("ollama", {"capabilities": [HOST_CODE_CAPABILITY]})]

    seen.clear()
    assert cli.main([
        "--provider", "ollama-cloud", "--model", "gemma4:31b-cloud",
    ]) == 2
    assert seen == []
    assert "cannot be granted to cloud provider" in capsys.readouterr().err


def test_cloud_cli_host_code_grant_fails_before_launch(monkeypatch, capsys):
    _clear_provider_env(monkeypatch)
    monkeypatch.setattr(
        cli,
        "_run_rich_provider_wrapper",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("cloud grant must fail before launch")
        ),
    )

    assert cli.main([
        "--provider", "groq",
        "--model", "llama-3.3-70b-versatile",
        "--allow-local-host-code",
    ]) == 2
    assert "cannot be granted to cloud provider" in capsys.readouterr().err


def test_invalid_host_code_env_value_fails_closed(monkeypatch, capsys):
    _clear_provider_env(monkeypatch)
    monkeypatch.setenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", "treu")

    assert cli.main([
        "--provider", "ollama", "--model", "gemma3:27b",
    ]) == 2
    assert "must be one of" in capsys.readouterr().err


def test_remote_ollama_endpoint_cannot_receive_local_host_code(monkeypatch, capsys):
    _clear_provider_env(monkeypatch)
    monkeypatch.setenv("OLLAMA_HOST", "https://ollama.example.invalid:11434")

    assert cli.main([
        "--provider", "ollama",
        "--model", "gemma3:27b",
        "--allow-local-host-code",
    ]) == 2
    assert "requires a loopback OLLAMA_HOST" in capsys.readouterr().err


def test_host_code_prompt_describes_optional_tool_surface():
    prompt = cli._build_system_prompt("groq", "llama-3.3-70b-versatile")
    assert "Use only tools present in the current schema" in prompt
    assert "cloud providers never receive this grant" in prompt
    assert "`run_script(code, language)` *(optional)*" in prompt


def test_native_opts_parses_boolean_env(monkeypatch):
    monkeypatch.setenv("IMAGEJAI_NATIVE_GOOGLE_SEARCH", "true")
    monkeypatch.setenv("IMAGEJAI_NATIVE_CODE_EXECUTION", "false")
    monkeypatch.delenv("IMAGEJAI_NATIVE_SERVER_TOOLS", raising=False)
    assert cli._native_opts() == {"server_tools": ["google_search"]}


def test_native_opts_parses_comma_joined_env(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_NATIVE_GOOGLE_SEARCH", raising=False)
    monkeypatch.delenv("IMAGEJAI_NATIVE_CODE_EXECUTION", raising=False)
    monkeypatch.setenv("IMAGEJAI_NATIVE_SERVER_TOOLS", "google_search, code_execution")
    assert cli._native_opts() == {"server_tools": ["google_search", "code_execution"]}


def test_native_opts_empty_when_unset(monkeypatch):
    for name in ("IMAGEJAI_NATIVE_GOOGLE_SEARCH", "IMAGEJAI_NATIVE_CODE_EXECUTION",
                 "IMAGEJAI_NATIVE_SERVER_TOOLS"):
        monkeypatch.delenv(name, raising=False)
    assert cli._native_opts() == {}


def test_dispatch_unknown_tool_returns_error():
    call = ToolCall(id="1", name="does_not_exist", args={})
    assert cli._dispatch(call).startswith("ERROR: unknown tool")


def test_dispatch_runs_tool(monkeypatch):
    monkeypatch.setattr(cli.ij, "get_state", lambda: {"images": 0, "memory": "ok"})
    out = cli._dispatch(ToolCall(id="1", name="get_state", args={}))
    assert "images" in out


def test_dispatch_catches_tool_exception(monkeypatch):
    def boom():
        raise RuntimeError("kaboom")
    monkeypatch.setattr(cli.ij, "get_state", boom)
    result = cli._dispatch(ToolCall(id="1", name="get_state", args={}))
    assert "ERROR" in result and "kaboom" in result


def test_dispatch_reports_malformed_args():
    call = ToolCall(id="1", name="run_macro", args={}, error="bad json")
    assert "malformed tool arguments" in cli._dispatch(call)


def test_legacy_cloud_dispatch_rejects_injected_run_script(monkeypatch):
    executed = []
    monkeypatch.setattr(cli.ij, "run_script", lambda *args, **kwargs: executed.append(args))
    call = ToolCall(id="1", name="run_script", args={"code": "println 1"})
    assert cli._dispatch(call).startswith("ERROR: unknown tool")
    assert executed == []


def test_legacy_local_host_code_requires_explicit_capability():
    class Client:
        tool_policy = ProviderToolPolicy(
            provider="ollama",
            is_local=True,
            capabilities=frozenset({HOST_CODE_CAPABILITY}),
        )

    assert "run_script" in {fn.__name__ for fn in cli._tools_for_client(Client())}


def test_legacy_cloud_tools_exclude_run_script_even_with_capability():
    class Client:
        tool_policy = ProviderToolPolicy(
            provider="groq",
            is_local=False,
            capabilities=frozenset({HOST_CODE_CAPABILITY}),
        )

    assert "run_script" not in {fn.__name__ for fn in cli._tools_for_client(Client())}


def test_run_turn_dispatches_tool_then_returns_text(monkeypatch):
    monkeypatch.setattr(cli.ij, "execute_macro", lambda code: "ran: " + code)
    threaded: list[tuple[str, str]] = []

    class FakeClient:
        def __init__(self):
            self.step = 0

        def chat(self, messages, tools, model):
            self.step += 1
            if self.step == 1:
                return ("tool", ToolCall(id="a", name="run_macro",
                                         args={"code": 'run("Blobs (25K)");'}))
            return ("text", "all done")

        def append_assistant(self, messages, response):
            messages.append({"role": "assistant"})

        def extract_tool_calls(self, response):
            return [response[1]] if response[0] == "tool" else []

        def extract_text(self, response):
            return response[1] if response[0] == "text" else ""

        def append_tool_result(self, messages, call, result):
            threaded.append((call.name, result))

    emitted: list[str] = []
    text = cli.run_turn(FakeClient(), "m", [{"role": "system", "content": "s"}],
                        emit=emitted.append)
    assert text == "all done"
    assert threaded and threaded[0][0] == "run_macro"
    assert "ran:" in threaded[0][1]
    assert any("[tool] run_macro" in line for line in emitted)


def test_run_turn_reports_model_error():
    class BoomClient:
        def chat(self, *args):
            raise RuntimeError("api down")

        def append_assistant(self, *args):
            pass

        def extract_tool_calls(self, response):
            return []

        def extract_text(self, response):
            return ""

    emitted: list[str] = []
    cli.run_turn(BoomClient(), "m", [{"role": "system", "content": "s"}],
                 emit=emitted.append)
    assert any("model call failed" in line for line in emitted)


def test_run_turn_stops_at_round_limit(monkeypatch):
    monkeypatch.setattr(cli.ij, "get_state", lambda: "{}")

    class LoopingClient:
        def chat(self, messages, tools, model):
            return ("tool", ToolCall(id="x", name="get_state", args={}))

        def append_assistant(self, messages, response):
            pass

        def extract_tool_calls(self, response):
            return [response[1]]

        def extract_text(self, response):
            return ""

        def append_tool_result(self, messages, call, result):
            pass

    emitted: list[str] = []
    cli.run_turn(LoopingClient(), "m", [{"role": "system", "content": "s"}],
                 emit=emitted.append, max_rounds=3)
    assert any("tool-round limit" in line for line in emitted)


def test_build_system_prompt_falls_back(monkeypatch):
    monkeypatch.setattr(cli, "load_context", None)
    prompt = cli._build_system_prompt("groq", "llama-3.3-70b-versatile")
    assert "Fiji" in prompt and "AI_Exports" in prompt


def test_run_preflight_failure_happens_before_ready(monkeypatch):
    class MissingModelClient:
        def require_model_available(self, model):
            raise RuntimeError("missing alias")

    monkeypatch.setattr(cli, "get_client", lambda *args, **kwargs: MissingModelClient())
    emitted: list[str] = []

    assert cli.run(
        "ollama-cloud",
        "gemma4:31b-cloud",
        emit=emitted.append,
        read_line=lambda prompt: "/exit",
    ) == 1
    assert any("model unavailable before chat" in line for line in emitted)
    assert not any("agent ready" in line for line in emitted)


def test_run_preflight_success_allows_ready(monkeypatch):
    class AvailableModelClient:
        def require_model_available(self, model):
            return "ollama-cloud/" + model

    monkeypatch.setattr(cli, "get_client", lambda *args, **kwargs: AvailableModelClient())
    monkeypatch.setattr(cli, "_build_system_prompt", lambda provider, model: "system")
    emitted: list[str] = []

    assert cli.run(
        "ollama-cloud",
        "gemma4:31b-cloud",
        emit=emitted.append,
        read_line=lambda prompt: "/exit",
    ) == 0
    assert any("ImageJAI agent ready" in line for line in emitted)
