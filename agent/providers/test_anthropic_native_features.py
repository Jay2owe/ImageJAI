"""Phase C — opt-in native-feature tests for AnthropicNativeClient.

Each test mocks the Messages API at the HTTP layer with respx so the request
body, including ``cache_control`` markers and ``thinking`` config, can be
asserted directly. The Phase B baseline tests live in
``test_anthropic_native.py``; this file only covers the new opt-in surface.
"""
from __future__ import annotations

import json

import httpx
import pytest
import respx

from agent.providers import router
from agent.providers.anthropic_native import AnthropicNativeClient
from agent.providers.test_base import CALL_LOG, multiply, run_loop


def test_prompt_caching_marks_last_tool_and_system_block(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(200, json=_anthropic_text_json("ok"))

    messages = [
        {"role": "system", "content": "you are an image analysis agent"},
        {"role": "user", "content": "hi"},
    ]
    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        client.chat(messages, [multiply], "claude-sonnet-4-6")

    body = seen[0]
    assert body["tools"][-1]["cache_control"] == {"type": "ephemeral"}
    assert isinstance(body["system"], list)
    assert body["system"][-1]["cache_control"] == {"type": "ephemeral"}
    assert body["system"][-1]["text"] == "you are an image analysis agent"


def test_prompt_caching_disabled_when_opt_off(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(200, json=_anthropic_text_json("ok"))

    messages = [
        {"role": "system", "content": "be brief"},
        {"role": "user", "content": "hi"},
    ]
    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        client.chat(messages, [multiply], "claude-sonnet-4-6", enable_prompt_caching=False)

    body = seen[0]
    assert body["system"] == "be brief"
    assert "cache_control" not in body["tools"][-1]


def test_thinking_budget_forwarded_and_blocks_threaded(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "id": "msg_thinking",
                "type": "message",
                "role": "assistant",
                "model": "claude-test",
                "content": [
                    {"type": "thinking", "thinking": "let me reason", "signature": "sig"},
                    {"type": "text", "text": "answer is yes"},
                ],
                "stop_reason": "end_turn",
                "stop_sequence": None,
                "usage": {"input_tokens": 1, "output_tokens": 1},
            },
        )

    messages = [{"role": "user", "content": "decide"}]
    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        response = client.chat(messages, [], "claude-opus-4-7", thinking_budget=2048)

    assert seen[0]["thinking"] == {"type": "enabled", "budget_tokens": 2048}

    thinking_blocks = AnthropicNativeClient.extract_thinking(response)
    assert thinking_blocks == [
        {"type": "thinking", "thinking": "let me reason", "signature": "sig"}
    ]
    assert client.extract_text(response) == "answer is yes"

    client.append_assistant(messages, response)
    threaded = messages[-1]["content"]
    assert any(block.get("type") == "thinking" for block in threaded)


def test_server_tools_added_and_beta_header_for_code_execution(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    seen_requests: list[httpx.Request] = []
    seen_bodies: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_requests.append(request)
        seen_bodies.append(json.loads(request.content))
        return httpx.Response(200, json=_anthropic_text_json("ok"))

    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        client.chat(
            [{"role": "user", "content": "research and compute"}],
            [multiply],
            "claude-opus-4-7",
            enable_server_tools=["web_search", "code_execution"],
        )

    body = seen_bodies[0]
    types_in_request = [tool.get("type") or tool.get("name") for tool in body["tools"]]
    assert "web_search_20250305" in types_in_request
    assert "code_execution_20250522" in types_in_request
    # User-defined tool still present
    assert any(tool.get("name") == "multiply" for tool in body["tools"])

    beta_header = seen_requests[0].headers.get("anthropic-beta", "")
    assert "code-execution-2025-05-22" in beta_header


def test_unknown_server_tool_raises(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    with pytest.raises(ValueError, match="unknown Anthropic server tool"):
        client.chat(
            [{"role": "user", "content": "hi"}],
            [],
            "claude-opus-4-7",
            enable_server_tools=["bogus"],
        )


def test_citations_surfaced_from_grounded_text(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "id": "msg_cite",
                "type": "message",
                "role": "assistant",
                "model": "claude-test",
                "content": [
                    {
                        "type": "text",
                        "text": "the answer is grounded",
                        "citations": [
                            {
                                "type": "web_search_result_location",
                                "url": "https://example.com/a",
                                "title": "A",
                                "cited_text": "evidence A",
                            },
                            {
                                "type": "web_search_result_location",
                                "url": "https://example.com/b",
                                "title": "B",
                                "cited_text": "evidence B",
                            },
                        ],
                    }
                ],
                "stop_reason": "end_turn",
                "stop_sequence": None,
                "usage": {"input_tokens": 1, "output_tokens": 1},
            },
        )

    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        response = client.chat(
            [{"role": "user", "content": "ground it"}],
            [],
            "claude-opus-4-7",
            enable_server_tools=["web_search"],
        )

    citations = AnthropicNativeClient.extract_citations(response)
    assert [c["url"] for c in citations] == ["https://example.com/a", "https://example.com/b"]
    assert client.extract_text(response) == "the answer is grounded"


def test_integration_caching_plus_thinking_through_tool_loop(monkeypatch) -> None:
    """Caching + thinking_budget + a real tool round-trip must all interlock."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        if len(seen) == 1:
            return httpx.Response(
                200,
                json={
                    "id": "msg_t",
                    "type": "message",
                    "role": "assistant",
                    "model": "claude-test",
                    "content": [
                        {"type": "thinking", "thinking": "compute", "signature": "s"},
                        {"type": "tool_use", "id": "t1", "name": "multiply", "input": {"a": 6, "b": 7}},
                    ],
                    "stop_reason": "tool_use",
                    "stop_sequence": None,
                    "usage": {"input_tokens": 1, "output_tokens": 1},
                },
            )
        return httpx.Response(200, json=_anthropic_text_json("Forty-two."))

    CALL_LOG.clear()
    messages = [
        {"role": "system", "content": "be concise"},
        {"role": "user", "content": "What is 6 * 7?"},
    ]

    def loop_with_features() -> str:
        for _ in range(3):
            response = client.chat(
                messages,
                [multiply],
                "claude-opus-4-7",
                enable_prompt_caching=True,
                thinking_budget=2048,
            )
            client.append_assistant(messages, response)
            calls = client.extract_tool_calls(response)
            if not calls:
                return client.extract_text(response)
            for call in calls:
                result = multiply(**call.args)
                client.append_tool_result(messages, call, str(result))
        raise AssertionError("loop did not terminate")

    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(side_effect=handler)
        out = loop_with_features()

    assert out == "Forty-two."
    assert CALL_LOG == [("multiply", {"a": 6, "b": 7})]
    # Both turns request thinking + caching
    for body in seen:
        assert body["thinking"] == {"type": "enabled", "budget_tokens": 2048}
        assert body["tools"][-1]["cache_control"] == {"type": "ephemeral"}
        assert isinstance(body["system"], list)
        assert body["system"][-1]["cache_control"] == {"type": "ephemeral"}
    # Second turn re-threads the prior assistant turn including the thinking block
    second_messages = seen[1]["messages"]
    assistant_turn = next(m for m in second_messages if m["role"] == "assistant")
    assert any(block["type"] == "thinking" for block in assistant_turn["content"])
    assert any(block["type"] == "tool_use" for block in assistant_turn["content"])


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
