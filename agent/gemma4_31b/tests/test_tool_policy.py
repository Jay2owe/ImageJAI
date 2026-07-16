from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from agent.gemma4_31b import loop, tools_fiji, tools_recipes, tools_shell
from agent.gemma4_31b.registry import is_host_code_tool, tools_for_policy
from agent.providers import router
from agent.providers.base import HOST_CODE_CAPABILITY, ProviderToolPolicy, fn_to_json_schema


def _tool_names(policy: ProviderToolPolicy, *, elevated: bool = False) -> set[str]:
    return {fn.__name__ for fn in tools_for_policy(policy, cloud_elevation=elevated)}


def test_cloud_schema_excludes_host_code_by_default() -> None:
    policy = router.get_client("groq", "llama-3.3-70b-versatile").tool_policy
    names = _tool_names(policy)
    assert "run_shell" not in names
    assert "run_script" not in names
    assert "run_saved_recipe" not in names
    assert "run_macro" in names


def test_local_schema_requires_explicit_host_code_capability() -> None:
    default_policy = router.get_client("ollama", "llama3.2:3b").tool_policy
    enabled_policy = router.get_client(
        "ollama",
        "llama3.2:3b",
        capabilities={HOST_CODE_CAPABILITY},
    ).tool_policy

    assert default_policy.is_local is True
    assert "run_shell" not in _tool_names(default_policy)
    assert "run_saved_recipe" not in _tool_names(default_policy)
    assert {"run_shell", "run_script", "run_saved_recipe"} <= _tool_names(enabled_policy)


def test_loopback_cloud_proxy_is_not_misclassified_as_local() -> None:
    policy = router.get_client(
        "groq",
        "llama-3.3-70b-versatile",
        base_url="http://localhost:4000",
        capabilities={HOST_CODE_CAPABILITY},
    ).tool_policy
    assert policy.is_local is False
    assert "run_shell" not in _tool_names(policy)
    assert "run_shell" in _tool_names(policy, elevated=True)


def test_cloud_elevation_requires_capability_and_live_callback() -> None:
    no_capability = ProviderToolPolicy(provider="groq", is_local=False)
    elevated = ProviderToolPolicy(
        provider="groq",
        is_local=False,
        capabilities=frozenset({HOST_CODE_CAPABILITY}),
    )
    assert "run_shell" not in _tool_names(no_capability, elevated=True)
    assert "run_shell" not in _tool_names(elevated, elevated=False)
    assert "run_shell" in _tool_names(elevated, elevated=True)
    assert "run_saved_recipe" not in _tool_names(elevated, elevated=False)
    assert "run_saved_recipe" in _tool_names(elevated, elevated=True)


def test_recipe_runner_is_host_code_but_safe_macro_tool_is_not() -> None:
    assert is_host_code_tool("run_saved_recipe") is True
    assert is_host_code_tool("run_macro") is False


def test_safe_macro_schema_remains_available_without_recipe_runner() -> None:
    policy = ProviderToolPolicy(provider="groq", is_local=False)
    names = _tool_names(policy)
    schema = fn_to_json_schema(tools_fiji.run_macro)["schema"]

    assert "run_macro" in names
    assert "run_saved_recipe" not in names
    assert schema["properties"]["code"]["type"] == "string"
    assert "code" in schema["required"]
    assert fn_to_json_schema(tools_recipes.run_saved_recipe)["name"] == "run_saved_recipe"


class _Ticker:
    def start(self, label: str) -> None:
        del label

    def current_label(self) -> str:
        return ""

    def set_phase(self, initial_label: str, transitions=()) -> None:
        del initial_label, transitions

    def stop(self) -> None:
        return None


class _CloudClient:
    def __init__(self, calls: list[object]) -> None:
        self.tool_policy = ProviderToolPolicy(
            provider="groq",
            is_local=False,
            capabilities=frozenset({HOST_CODE_CAPABILITY}),
        )
        self.calls = calls
        self.round = 0
        self.results: list[str] = []

    def chat(self, messages, tools, model, **opts):
        del messages, tools, model, opts
        self.round += 1
        return ("tools", self.calls) if self.round == 1 else ("text", "done")

    def append_assistant(self, messages, response) -> None:
        messages.append({"role": "assistant", "content": ""})

    def extract_tool_calls(self, response):
        return response[1] if response[0] == "tools" else []

    def extract_text(self, response):
        return response[1] if response[0] == "text" else ""

    def append_tool_result(self, messages, call, result) -> None:
        del call
        self.results.append(result)
        messages.append({"role": "tool", "content": result})


def _call(name: str, args: dict) -> object:
    return SimpleNamespace(id="call", name=name, args=args, error=None)


def _run_cloud_calls(
    monkeypatch,
    calls,
    approval_callback,
    tool,
    *,
    tool_name: str = "run_shell",
) -> tuple[_CloudClient, str]:
    client = _CloudClient(calls)
    monkeypatch.setattr(loop, "_ActivityTicker", _Ticker)
    monkeypatch.setattr(loop, "_console_emit", lambda *args, **kwargs: None)
    monkeypatch.setattr(loop, "_invalidate_prompt", lambda: None)
    monkeypatch.setattr(
        loop,
        "_run_interruptible",
        lambda fn, *args, abort_event=None, **kwargs: fn(*args, **kwargs),
    )
    reply, _ = loop._one_turn(
        "llama-3.3-70b-versatile",
        [],
        [tool],
        {tool_name: tool},
        loop._resolve_turn_config("run it", None, None, False),
        provider="groq",
        provider_client=client,
        provider_opts={"host_code_approval": approval_callback},
    )
    return client, reply


def test_cloud_denial_executes_nothing(monkeypatch) -> None:
    executions: list[list[str]] = []

    def tool(argv, cwd=""):
        del cwd
        executions.append(argv)
        return "executed"

    client, reply = _run_cloud_calls(
        monkeypatch,
        [_call("run_shell", {"argv": ["python", "-V"]})],
        lambda request: False,
        tool,
    )
    assert "done" in reply
    assert executions == []
    assert len(client.results) == 1
    assert client.results[0].startswith("ABORTED:")


def test_cloud_approval_is_exact_and_applies_to_one_call(monkeypatch) -> None:
    executions: list[list[str]] = []
    requests = []

    def tool(argv, cwd=""):
        del cwd
        executions.append(argv)
        return "executed"

    def approve_once(request):
        requests.append(request)
        return len(requests) == 1

    calls = [
        _call("run_shell", {"argv": ["python", "-c", "print('one')"]}),
        _call("run_shell", {"argv": ["python", "-c", "print('two')"]}),
    ]
    client, _ = _run_cloud_calls(monkeypatch, calls, approve_once, tool)

    assert executions == [["python", "-c", "print('one')"]]
    assert len(requests) == 2
    assert "print('one')" in requests[0].preview
    assert "print('two')" in requests[1].preview
    assert client.results == ["executed", "ABORTED: host-code call was denied by the one-call approval callback"]


def test_recipe_elevation_is_exact_and_applies_to_one_call(monkeypatch) -> None:
    executions: list[tuple[str, bool]] = []
    requests = []

    def run_saved_recipe(recipe_name, dry_run=False):
        executions.append((recipe_name, dry_run))
        return "executed {}".format(recipe_name)

    def approve_once(request):
        requests.append(request)
        return len(requests) == 1

    calls = [
        _call("run_saved_recipe", {"recipe_name": "first", "dry_run": False}),
        _call("run_saved_recipe", {"recipe_name": "second", "dry_run": False}),
    ]
    client, _ = _run_cloud_calls(
        monkeypatch,
        calls,
        approve_once,
        run_saved_recipe,
        tool_name="run_saved_recipe",
    )

    assert executions == [("first", False)]
    assert len(requests) == 2
    assert json.loads(requests[0].preview)["recipe_name"] == "first"
    assert json.loads(requests[1].preview)["recipe_name"] == "second"
    assert client.results == [
        "executed first",
        "ABORTED: host-code call was denied by the one-call approval callback",
    ]


def test_run_script_preview_contains_exact_source() -> None:
    policy = ProviderToolPolicy(
        provider="groq",
        capabilities=frozenset({HOST_CODE_CAPABILITY}),
    )
    seen = []
    source = "println('exact; source')\n"
    note = loop._host_code_abort_note(
        "run_script",
        {"code": source, "language": "groovy"},
        provider="groq",
        model="model",
        policy=policy,
        approval_callback=lambda request: seen.append(request) or True,
    )
    assert note is None
    assert len(seen) == 1
    assert json.loads(seen[0].preview)["code"] == source
    assert seen[0].working_directory == "Fiji JVM"


def test_run_shell_uses_structured_argv_without_shell(monkeypatch) -> None:
    seen = {}

    def fake_run(argv, **kwargs):
        seen["argv"] = argv
        seen.update(kwargs)
        return SimpleNamespace(stdout=b"ok", stderr=b"", returncode=0)

    monkeypatch.setattr(tools_shell.subprocess, "run", fake_run)
    metacharacter_argument = "hello; this-is-not-a-second-command"
    assert tools_shell.run_shell(["echo", metacharacter_argument]) == "ok"
    assert seen["argv"] == ["echo", metacharacter_argument]
    assert seen["shell"] is False

    seen.clear()
    assert tools_shell.run_shell("echo unsafe") == "ERROR: argv must be a non-empty list of strings"
    assert seen == {}


def test_run_shell_schema_requires_an_argv_array() -> None:
    schema = fn_to_json_schema(tools_shell.run_shell)["schema"]
    assert schema["properties"]["argv"]["type"] == "array"
    assert schema["properties"]["argv"]["items"]["type"] == "string"


@pytest.mark.parametrize(
    "model",
    ["model;calc", "model && calc", "$(calc)", "`calc`", "model\nnext"],
)
def test_model_identifiers_are_rejected_before_client_creation(model: str) -> None:
    with pytest.raises(ValueError, match="invalid model"):
        router.get_client("groq", model)


def test_unknown_capabilities_are_rejected() -> None:
    with pytest.raises(ValueError, match="unknown provider capabilities"):
        router.get_client("ollama", "llama3.2:3b", capabilities={"silent_admin"})
