from __future__ import annotations

import json
from typing import Callable

import pytest

from agent.providers.anthropic_native import AnthropicNativeClient
from agent.providers.base import ToolCall, to_anthropic_tool, to_gemini_tool, to_openai_tool
from agent.providers.gemini_native import GeminiNativeClient
from agent.providers.litellm_proxy import LiteLLMProxyClient


CALL_LOG: list[tuple[str, dict]] = []


def multiply(a: int, b: int) -> int:
    """Multiply two integers and return the product.

    Args:
        a: The first number.
        b: The second number.
    """
    CALL_LOG.append(("multiply", {"a": a, "b": b}))
    return a * b


def reset_log() -> None:
    CALL_LOG.clear()


def run_loop(client, model: str, prompt: str, max_rounds: int = 4) -> str:
    CALL_LOG.clear()
    messages: list[dict] = [{"role": "user", "content": prompt}]
    tools: list[Callable] = [multiply]

    for _ in range(max_rounds):
        response = client.chat(messages, tools, model)
        client.append_assistant(messages, response)
        calls = client.extract_tool_calls(response)
        if not calls:
            return client.extract_text(response)
        for call in calls:
            if call.name != "multiply":
                client.append_tool_result(messages, call, f"ERROR: unknown tool '{call.name}'")
                continue
            result = multiply(**call.args)
            client.append_tool_result(messages, call, str(result))
    raise AssertionError("loop did not terminate")


def test_schema_converter_openai() -> None:
    spec = to_openai_tool(multiply)
    assert spec["type"] == "function"
    function = spec["function"]
    assert function["name"] == "multiply"
    assert function["parameters"]["type"] == "object"
    assert set(function["parameters"]["properties"]) == {"a", "b"}
    assert function["parameters"]["properties"]["a"]["type"] == "integer"
    assert function["parameters"]["required"] == ["a", "b"]


def test_schema_converter_anthropic() -> None:
    spec = to_anthropic_tool(multiply)
    assert spec["name"] == "multiply"
    assert "input_schema" in spec
    assert spec["input_schema"]["properties"]["b"]["type"] == "integer"


def test_schema_converter_gemini() -> None:
    spec = to_gemini_tool(multiply)
    assert spec["name"] == "multiply"
    assert spec["parameters"]["properties"]["a"]["type"] == "integer"


def test_no_arg_tool_omits_parameters_for_gemini() -> None:
    def ping() -> str:
        """Test connection."""
        return "pong"

    assert "parameters" not in to_gemini_tool(ping)
    assert "parameters" in to_openai_tool(ping)["function"]
    assert "input_schema" in to_anthropic_tool(ping)


def test_all_three_paths_emit_same_normalised_tool_call() -> None:
    openai_response = _openai_tool_response([("x1", "multiply", {"a": 1, "b": 2})])
    anthropic_response = _anthropic_tool_response([("x2", "multiply", {"a": 1, "b": 2})])
    gemini_response = _gemini_tool_response([("multiply", {"a": 1, "b": 2})])

    all_calls = [
        LiteLLMProxyClient.__new__(LiteLLMProxyClient).extract_tool_calls(openai_response),
        AnthropicNativeClient.__new__(AnthropicNativeClient).extract_tool_calls(anthropic_response),
        GeminiNativeClient.__new__(GeminiNativeClient).extract_tool_calls(gemini_response),
    ]
    for calls in all_calls:
        assert len(calls) == 1
        assert isinstance(calls[0], ToolCall)
        assert calls[0].name == "multiply"
        assert calls[0].args == {"a": 1, "b": 2}


def test_unknown_tool_name_dispatch_threads_error_result() -> None:
    client = LiteLLMProxyClient.__new__(LiteLLMProxyClient)
    messages: list[dict] = [{"role": "user", "content": "do a thing"}]
    response = _openai_tool_response([("call_unknown", "not_a_tool", {"x": 1})])

    client.append_assistant(messages, response)
    [call] = client.extract_tool_calls(response)
    assert call.name == "not_a_tool"
    client.append_tool_result(messages, call, f"ERROR: unknown tool '{call.name}'")

    assert messages[-1] == {
        "role": "tool",
        "tool_call_id": "call_unknown",
        "content": "ERROR: unknown tool 'not_a_tool'",
    }


def _openai_tool_response(calls: list[tuple[str, str, dict]]):
    import types

    tool_calls = [
        types.SimpleNamespace(
            id=call_id,
            type="function",
            function=types.SimpleNamespace(name=name, arguments=json.dumps(args)),
        )
        for call_id, name, args in calls
    ]
    message = types.SimpleNamespace(role="assistant", content=None, tool_calls=tool_calls)
    return types.SimpleNamespace(
        choices=[types.SimpleNamespace(message=message, finish_reason="tool_calls", index=0)]
    )


def _anthropic_tool_response(calls: list[tuple[str, str, dict]]):
    import types

    return types.SimpleNamespace(
        content=[
            types.SimpleNamespace(type="tool_use", id=call_id, name=name, input=args)
            for call_id, name, args in calls
        ],
        role="assistant",
        stop_reason="tool_use",
    )


def _gemini_tool_response(calls: list[tuple[str, dict]]):
    import types

    parts = [
        types.SimpleNamespace(
            function_call=types.SimpleNamespace(name=name, args=args),
            text=None,
        )
        for name, args in calls
    ]
    content = types.SimpleNamespace(parts=parts, role="model")
    return types.SimpleNamespace(
        candidates=[types.SimpleNamespace(content=content, finish_reason="STOP")],
        text="",
    )


@pytest.fixture(autouse=True)
def _clear_call_log() -> None:
    reset_log()
