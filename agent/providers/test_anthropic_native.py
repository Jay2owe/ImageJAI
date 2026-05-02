from __future__ import annotations

import json

import httpx
import respx

from agent.providers import router
from agent.providers.test_base import CALL_LOG, run_loop


def test_anthropic_native_loop(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    calls_seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls_seen.append(json.loads(request.content))
        if len(calls_seen) == 1:
            return httpx.Response(200, json=_anthropic_tool_json("toolu_01", "multiply", {"a": 6, "b": 7}))
        return httpx.Response(200, json=_anthropic_text_json("Forty-two."))

    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        out = run_loop(client, "claude-opus-4-7", "What is 6 * 7?")

    assert out == "Forty-two."
    assert CALL_LOG == [("multiply", {"a": 6, "b": 7})]
    assert calls_seen[0]["max_tokens"] == 4096
    assert calls_seen[0]["tools"][0]["name"] == "multiply"
    assert "input_schema" in calls_seen[0]["tools"][0]

    second_messages = calls_seen[1]["messages"]
    assert len(second_messages) == 3
    assert second_messages[1]["role"] == "assistant"
    assert any(block["type"] == "tool_use" and block["id"] == "toolu_01" for block in second_messages[1]["content"])
    tool_result = second_messages[2]["content"][0]
    assert tool_result["type"] == "tool_result"
    assert tool_result["tool_use_id"] == "toolu_01"
    assert "tool_call_id" not in tool_result
    assert tool_result["content"] == "42"


def test_anthropic_splits_system_and_preserves_full_content_blocks(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                **_anthropic_tool_json("toolu_01", "multiply", {"a": 2, "b": 4}),
                "content": [
                    {"type": "thinking", "thinking": "keep this block", "signature": "sig"},
                    {"type": "tool_use", "id": "toolu_01", "name": "multiply", "input": {"a": 2, "b": 4}},
                ],
            },
        )

    messages = [
        {"role": "system", "content": "be concise"},
        {"role": "user", "content": "2 * 4"},
    ]
    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        # Opt out of Phase C prompt caching so the legacy string-shaped system
        # prompt is asserted exactly. Caching is covered in
        # test_anthropic_native_features.py.
        response = client.chat(messages, [], "claude-sonnet-4-6", enable_prompt_caching=False)

    assert seen[0]["system"] == "be concise"
    assert [message["role"] for message in seen[0]["messages"]] == ["user"]
    client.append_assistant(messages, response)
    assert messages[-1]["content"][0]["type"] == "thinking"
    assert messages[-1]["content"][1]["type"] == "tool_use"


def _anthropic_tool_json(call_id: str, name: str, args: dict) -> dict:
    return {
        "id": "msg_tool",
        "type": "message",
        "role": "assistant",
        "model": "claude-test",
        "content": [{"type": "tool_use", "id": call_id, "name": name, "input": args}],
        "stop_reason": "tool_use",
        "stop_sequence": None,
        "usage": {"input_tokens": 1, "output_tokens": 1},
    }


def _anthropic_text_json(text: str) -> dict:
    return {
        "id": "msg_text",
        "type": "message",
        "role": "assistant",
        "model": "claude-test",
        "content": [{"type": "text", "text": text}],
        "stop_reason": "end_turn",
        "stop_sequence": None,
        "usage": {"input_tokens": 1, "output_tokens": 1},
    }
