"""Phase C — opt-in native-feature tests for GeminiNativeClient.

Tests use ``monkeypatch`` to intercept ``client._client.models.generate_content``
so the resolved ``GenerateContentConfig`` (Tool objects, ThinkingConfig) is
asserted directly. Phase B's HTTP-level tests live in ``test_gemini_native.py``.
"""
from __future__ import annotations

import types

import pytest
from google.genai import types as genai_types

from agent.providers import router
from agent.providers.gemini_native import GeminiNativeClient
from agent.providers.test_base import multiply


def _build_client(monkeypatch: pytest.MonkeyPatch) -> tuple[GeminiNativeClient, dict]:
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    client = GeminiNativeClient(api_key="AIza-test-not-real")
    captured: dict = {}

    def fake_generate_content(**kwargs):
        captured.update(kwargs)
        return _gemini_sdk_text_response("ok")

    monkeypatch.setattr(client._client.models, "generate_content", fake_generate_content)
    return client, captured


def test_google_search_grounding_attaches_tool(monkeypatch) -> None:
    client, captured = _build_client(monkeypatch)
    client.chat(
        [{"role": "user", "content": "what is the latest fiji version?"}],
        [],
        "gemini-2.5-pro",
        enable_google_search=True,
    )

    config = captured["config"]
    tool_kinds = [type(t).__name__ for t in (config.tools or [])]
    assert "Tool" in tool_kinds
    assert any(getattr(t, "google_search", None) is not None for t in config.tools)


def test_grounding_metadata_surfaced() -> None:
    metadata = {
        "web_search_queries": ["fiji latest version"],
        "grounding_chunks": [{"web": {"uri": "https://example.com/x", "title": "X"}}],
    }
    candidate = types.SimpleNamespace(
        content=types.SimpleNamespace(parts=[types.SimpleNamespace(text="grounded")]),
        grounding_metadata=metadata,
    )
    response = types.SimpleNamespace(candidates=[candidate], text="grounded")
    surfaced = GeminiNativeClient.extract_grounding_metadata(response)
    assert surfaced == metadata
    assert surfaced["web_search_queries"] == ["fiji latest version"]
    assert surfaced["grounding_chunks"][0]["web"]["uri"] == "https://example.com/x"


def test_grounding_metadata_absent_returns_none() -> None:
    candidate = types.SimpleNamespace(
        content=types.SimpleNamespace(parts=[types.SimpleNamespace(text="ungrounded")]),
        grounding_metadata=None,
    )
    response = types.SimpleNamespace(candidates=[candidate], text="ungrounded")
    assert GeminiNativeClient.extract_grounding_metadata(response) is None


def test_code_execution_attaches_tool(monkeypatch) -> None:
    client, captured = _build_client(monkeypatch)
    client.chat(
        [{"role": "user", "content": "compute factorial of 6"}],
        [],
        "gemini-2.5-pro",
        enable_code_execution=True,
    )

    config = captured["config"]
    assert config.tools, "expected at least one Tool when code_execution enabled"
    assert any(getattr(t, "code_execution", None) is not None for t in config.tools)


def test_code_execution_parts_surfaced() -> None:
    parts = [
        types.SimpleNamespace(
            executable_code=types.SimpleNamespace(language="PYTHON", code="print(2 + 2)"),
            code_execution_result=None,
            function_call=None,
            text=None,
            thought=False,
        ),
        types.SimpleNamespace(
            executable_code=None,
            code_execution_result=types.SimpleNamespace(outcome="OUTCOME_OK", output="4\n"),
            function_call=None,
            text=None,
            thought=False,
        ),
    ]
    candidate = types.SimpleNamespace(content=types.SimpleNamespace(parts=parts))
    response = types.SimpleNamespace(candidates=[candidate], text="")
    surfaced = GeminiNativeClient.extract_code_execution(response)
    assert surfaced[0]["type"] == "executable_code"
    assert surfaced[0]["language"] == "PYTHON"
    assert surfaced[0]["code"] == "print(2 + 2)"
    assert surfaced[1]["type"] == "code_execution_result"
    assert surfaced[1]["output"] == "4\n"


def test_thinking_budget_forwarded_via_thinking_config(monkeypatch) -> None:
    client, captured = _build_client(monkeypatch)
    client.chat(
        [{"role": "user", "content": "reason carefully"}],
        [],
        "gemini-2.5-pro",
        thinking_budget=4096,
    )

    config = captured["config"]
    thinking_config = config.thinking_config
    assert isinstance(thinking_config, genai_types.ThinkingConfig)
    assert thinking_config.thinking_budget == 4096
    assert thinking_config.include_thoughts is True


def test_thinking_parts_surfaced_separately_not_in_extract_text() -> None:
    parts = [
        types.SimpleNamespace(
            text="my hidden thought",
            thought=True,
            function_call=None,
            executable_code=None,
            code_execution_result=None,
        ),
        types.SimpleNamespace(
            text="visible answer",
            thought=False,
            function_call=None,
            executable_code=None,
            code_execution_result=None,
        ),
    ]
    candidate = types.SimpleNamespace(content=types.SimpleNamespace(parts=parts))
    # SDK text getter often includes everything; our extractor must filter thoughts.
    response = types.SimpleNamespace(candidates=[candidate], text=None)

    client = GeminiNativeClient.__new__(GeminiNativeClient)
    assert client.extract_text(response) == "visible answer"
    assert GeminiNativeClient.extract_thinking(response) == ["my hidden thought"]

    messages: list[dict] = []
    client.append_assistant(messages, response)
    assert messages[-1]["content"] == "visible answer"
    assert messages[-1]["thinking"] == ["my hidden thought"]


def test_features_default_off_preserves_phase_b_behaviour(monkeypatch) -> None:
    client, captured = _build_client(monkeypatch)
    client.chat([{"role": "user", "content": "hi"}], [multiply], "gemini-2.5-flash")

    config = captured["config"]
    # Only the function-declarations Tool from the multiply function — no
    # google_search, no code_execution, no thinking.
    assert len(config.tools) == 1
    only_tool = config.tools[0]
    assert only_tool.function_declarations is not None
    assert only_tool.google_search is None
    assert only_tool.code_execution is None
    assert config.thinking_config is None


def test_router_server_tools_off_by_default(monkeypatch) -> None:
    """Phase C acceptance: server tools must be off when router opt-in absent."""
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    client = router.get_client("gemini", "gemini-2.5-pro")
    assert isinstance(client, GeminiNativeClient)
    assert client._default_server_tools == set()

    captured: dict = {}

    def fake_generate_content(**kwargs):
        captured.update(kwargs)
        return _gemini_sdk_text_response("ok")

    monkeypatch.setattr(client._client.models, "generate_content", fake_generate_content)
    client.chat([{"role": "user", "content": "hi"}], [], "gemini-2.5-pro")

    config = captured["config"]
    # No server-side tools attached when nothing was opted in via router.
    if config and getattr(config, "tools", None):
        for tool in config.tools:
            assert getattr(tool, "google_search", None) is None
            assert getattr(tool, "code_execution", None) is None


def test_router_server_tools_code_execution_threads_through(monkeypatch) -> None:
    """Phase C acceptance: ``router.get_client('gemini', model, server_tools=['code_execution'])``
    enables sandbox code execution on every chat() call without per-call kwargs.
    """
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    client = router.get_client(
        "gemini", "gemini-2.5-pro", server_tools=["code_execution"]
    )
    assert client._default_server_tools == {"code_execution"}

    captured: dict = {}

    def fake_generate_content(**kwargs):
        captured.update(kwargs)
        return _gemini_sdk_text_response("ok")

    monkeypatch.setattr(client._client.models, "generate_content", fake_generate_content)
    client.chat([{"role": "user", "content": "compute 21*2"}], [], "gemini-2.5-pro")

    config = captured["config"]
    assert config.tools, "expected at least one Tool when code_execution opted in via router"
    assert any(getattr(t, "code_execution", None) is not None for t in config.tools)


def test_router_server_tools_google_search_threads_through(monkeypatch) -> None:
    """Phase C acceptance: same pattern enables Google Search grounding."""
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    client = router.get_client(
        "gemini", "gemini-2.5-pro", server_tools=["google_search"]
    )

    captured: dict = {}

    def fake_generate_content(**kwargs):
        captured.update(kwargs)
        return _gemini_sdk_text_response("ok")

    monkeypatch.setattr(client._client.models, "generate_content", fake_generate_content)
    client.chat([{"role": "user", "content": "what is fiji?"}], [], "gemini-2.5-pro")

    config = captured["config"]
    assert any(getattr(t, "google_search", None) is not None for t in config.tools)


def test_router_server_tools_unknown_name_raises(monkeypatch) -> None:
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    with pytest.raises(ValueError, match="unknown Gemini server tool"):
        router.get_client("gemini", "gemini-2.5-pro", server_tools=["bogus"])


def test_vision_png_inline_data_round_trips(monkeypatch) -> None:
    """Phase C acceptance: PNG screenshots from ``python ij.py capture`` survive.

    A list-shaped user content payload carrying a Gemini ``inline_data`` part
    must reach ``generate_content`` unchanged so ImageJAI's screenshot
    workflow ("capture, look, decide") works on the native Gemini path.
    """
    client, captured = _build_client(monkeypatch)
    image_part = {
        "inline_data": {
            "mime_type": "image/png",
            "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        }
    }
    text_part = {"text": "what's in this microscopy capture?"}
    client.chat(
        [{"role": "user", "content": [text_part, image_part]}],
        [],
        "gemini-2.5-pro",
    )

    forwarded_contents = captured["contents"]
    assert len(forwarded_contents) == 1
    parts = forwarded_contents[0]["parts"]
    assert parts == [text_part, image_part]
    assert parts[1]["inline_data"]["mime_type"] == "image/png"


def _gemini_sdk_text_response(text: str):
    part = types.SimpleNamespace(
        function_call=None,
        text=text,
        thought=False,
        executable_code=None,
        code_execution_result=None,
    )
    content = types.SimpleNamespace(parts=[part], role="model")
    return types.SimpleNamespace(candidates=[types.SimpleNamespace(content=content)], text=text)
