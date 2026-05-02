from __future__ import annotations

import json

import httpx
import respx

from agent.providers import router
from agent.providers.litellm_proxy import LiteLLMProxyClient
from agent.providers.test_base import CALL_LOG, run_loop


def test_litellm_proxy_loop() -> None:
    client = router.get_client("groq", base_url="http://localhost:4000", api_key="dummy")
    calls_seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls_seen.append(json.loads(request.content))
        if len(calls_seen) == 1:
            return httpx.Response(200, json=_openai_tool_json("call_abc", "multiply", {"a": 6, "b": 7}))
        return httpx.Response(200, json=_openai_text_json("The answer is 42."))

    with respx.mock(assert_all_called=True) as mock:
        mock.post("http://localhost:4000/v1/chat/completions").mock(side_effect=handler)
        out = run_loop(client, "llama-3.3-70b-versatile", "What is 6 * 7?")

    assert out == "The answer is 42."
    assert CALL_LOG == [("multiply", {"a": 6, "b": 7})]
    assert calls_seen[0]["model"] == "groq/llama-3.3-70b-versatile"
    assert calls_seen[0]["tools"][0]["type"] == "function"
    assert calls_seen[0]["tools"][0]["function"]["name"] == "multiply"
    roles = [message["role"] for message in calls_seen[1]["messages"]]
    assert roles == ["user", "assistant", "tool"]
    assert calls_seen[1]["messages"][2]["tool_call_id"] == "call_abc"
    assert calls_seen[1]["messages"][2]["content"] == "42"


def test_malformed_json_tool_call_argument_is_reported() -> None:
    client = LiteLLMProxyClient.__new__(LiteLLMProxyClient)
    response = _openai_tool_json("call_bad", "multiply", '{"a": 6,')
    parsed = _response_from_openai_json(response)

    [call] = client.extract_tool_calls(parsed)
    assert call.name == "multiply"
    assert call.args == {}
    assert call.error is not None
    assert "malformed tool arguments JSON" in call.error


def test_multi_tool_parallel_call_within_one_turn() -> None:
    import types

    client = LiteLLMProxyClient.__new__(LiteLLMProxyClient)
    message = types.SimpleNamespace(
        role="assistant",
        content=None,
        tool_calls=[
            types.SimpleNamespace(
                id="call_1",
                type="function",
                function=types.SimpleNamespace(name="multiply", arguments=json.dumps({"a": 2, "b": 3})),
            ),
            types.SimpleNamespace(
                id="call_2",
                type="function",
                function=types.SimpleNamespace(name="multiply", arguments=json.dumps({"a": 4, "b": 5})),
            ),
        ],
    )
    response = types.SimpleNamespace(
        choices=[types.SimpleNamespace(message=message, finish_reason="tool_calls", index=0)]
    )
    messages: list[dict] = [{"role": "user", "content": "two products"}]

    client.append_assistant(messages, response)
    calls = client.extract_tool_calls(response)
    assert [(call.id, call.name, call.args) for call in calls] == [
        ("call_1", "multiply", {"a": 2, "b": 3}),
        ("call_2", "multiply", {"a": 4, "b": 5}),
    ]
    for call, result in zip(calls, ("6", "20"), strict=True):
        client.append_tool_result(messages, call, result)

    assert [message["role"] for message in messages] == ["user", "assistant", "tool", "tool"]
    assert messages[-2]["tool_call_id"] == "call_1"
    assert messages[-1]["tool_call_id"] == "call_2"


def test_malformed_json_response_from_real_sdk() -> None:
    client = router.get_client("openai", base_url="http://localhost:4000", api_key="dummy")
    with respx.mock(assert_all_called=True) as mock:
        mock.post("http://localhost:4000/v1/chat/completions").mock(
            return_value=httpx.Response(
                200,
                json=_openai_tool_json("call_bad", "multiply", '{"a": 6,'),
            )
        )
        response = client.chat([{"role": "user", "content": "bad args"}], [], "gpt-5-mini")

    [call] = client.extract_tool_calls(response)
    assert call.args == {}
    assert call.error


def _openai_tool_json(call_id: str, name: str, args) -> dict:
    arguments = args if isinstance(args, str) else json.dumps(args)
    return {
        "id": "chatcmpl-tool",
        "object": "chat.completion",
        "created": 1,
        "model": "test",
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": call_id,
                            "type": "function",
                            "function": {"name": name, "arguments": arguments},
                        }
                    ],
                },
                "finish_reason": "tool_calls",
            }
        ],
    }


def _openai_text_json(text: str) -> dict:
    return {
        "id": "chatcmpl-text",
        "object": "chat.completion",
        "created": 1,
        "model": "test",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": text},
                "finish_reason": "stop",
            }
        ],
    }


def _response_from_openai_json(payload: dict):
    from openai.types.chat import ChatCompletion

    return ChatCompletion.model_validate(payload)
