"""Streaming tests: chat_stream() on all three provider clients.

SSE wire formats are mocked with respx (same pattern as the non-streaming
tests). Each test asserts: (a) on_delta saw the text chunks, (b) the merged
response works with the client's own extract_* helpers, (c) tool calls
streamed as deltas reconstruct correctly.
"""
from __future__ import annotations

import json

import httpx
import respx

from agent.providers import router


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _sse(*events: str) -> httpx.Response:
    body = "".join(f"{e}\n\n" for e in events)
    return httpx.Response(
        200,
        content=body.encode("utf-8"),
        headers={"content-type": "text/event-stream"},
    )


# ---------------------------------------------------------------------------
# LiteLLM proxy (OpenAI-compatible SSE)
# ---------------------------------------------------------------------------


_OPENAI_TEXT_STREAM = [
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}',
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}',
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}',
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}',
    "data: [DONE]",
]

_OPENAI_TOOL_STREAM = [
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"calling"},"finish_reason":null}]}',
    r'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"multiply","arguments":"{\"a\""}}]},"finish_reason":null}]}',
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]},"finish_reason":null}]}',
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}',
    'data: {"id":"c1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":8,"completion_tokens":4,"total_tokens":12}}',
    "data: [DONE]",
]


def test_litellm_chat_stream_text() -> None:
    client = router.get_client("groq", base_url="http://localhost:4000", api_key="dummy")
    deltas: list[str] = []

    with respx.mock(assert_all_called=True) as mock:
        mock.post("http://localhost:4000/v1/chat/completions").mock(
            return_value=_sse(*_OPENAI_TEXT_STREAM)
        )
        response = client.chat_stream(
            [{"role": "user", "content": "hi"}], [], "llama-3.3-70b-versatile",
            on_delta=deltas.append,
        )

    assert deltas == ["Hel", "lo"]
    assert client.extract_text(response) == "Hello"
    assert client.extract_tool_calls(response) == []
    usage = response.usage
    assert usage.prompt_tokens == 10 and usage.completion_tokens == 2


def test_litellm_chat_stream_tool_calls() -> None:
    client = router.get_client("groq", base_url="http://localhost:4000", api_key="dummy")
    deltas: list[str] = []
    preparing: list[str] = []

    with respx.mock(assert_all_called=True) as mock:
        mock.post("http://localhost:4000/v1/chat/completions").mock(
            return_value=_sse(*_OPENAI_TOOL_STREAM)
        )
        response = client.chat_stream(
            [{"role": "user", "content": "six times seven"}], [], "llama-3.3-70b-versatile",
            on_delta=deltas.append,
            on_tool_preparing=preparing.append,
        )

    assert deltas == ["calling"]
    calls = client.extract_tool_calls(response)
    assert len(calls) == 1
    assert calls[0].name == "multiply"
    assert calls[0].args == {"a": 1}
    assert preparing == ["multiply"]

    # append helpers accept the synthetic response
    messages: list = []
    client.append_assistant(messages, response)
    assert messages[0]["tool_calls"][0]["function"]["arguments"] == '{"a":1}'


# ---------------------------------------------------------------------------
# Anthropic native SSE
# ---------------------------------------------------------------------------


_ANTHROPIC_TEXT_STREAM = [
    'event: message_start\ndata: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-4-7","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}',
    'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"","citations":null,"thinking":null,"signature":null}}',
    'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}',
    'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}',
    'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}',
    'event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}',
    'event: message_stop\ndata: {"type":"message_stop"}',
]

_ANTHROPIC_TOOL_STREAM = [
    'event: message_start\ndata: {"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-4-7","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}',
    'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"","citations":null,"thinking":null,"signature":null}}',
    'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"calling"}}',
    'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}',
    'event: content_block_start\ndata: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"multiply","input":{},"citations":null,"thinking":null,"signature":null}}',
    'event: content_block_delta\ndata: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\\"a\\\": 6, \\\"b\\\": 7}"}}',
    'event: content_block_stop\ndata: {"type":"content_block_stop","index":1}',
    'event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":9}}',
    'event: message_stop\ndata: {"type":"message_stop"}',
]


def test_anthropic_chat_stream_text(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    deltas: list[str] = []

    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(
            return_value=_sse(*_ANTHROPIC_TEXT_STREAM)
        )
        response = client.chat_stream(
            [{"role": "user", "content": "hi"}], [], "claude-opus-4-7",
            on_delta=deltas.append,
        )

    assert deltas == ["Hello", " world"]
    assert client.extract_text(response) == "Hello world"
    assert client.extract_tool_calls(response) == []


def test_anthropic_chat_stream_tool_calls(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = router.get_client("anthropic")
    deltas: list[str] = []
    preparing: list[str] = []

    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(
            return_value=_sse(*_ANTHROPIC_TOOL_STREAM)
        )
        response = client.chat_stream(
            [{"role": "user", "content": "six times seven"}], [], "claude-opus-4-7",
            on_delta=deltas.append,
            on_tool_preparing=preparing.append,
        )

    assert deltas == ["calling"]
    calls = client.extract_tool_calls(response)
    assert len(calls) == 1
    assert calls[0].name == "multiply"
    assert calls[0].args == {"a": 6, "b": 7}
    assert preparing == ["multiply"]

    messages: list = []
    client.append_assistant(messages, response)
    assert any(block["type"] == "tool_use" for block in messages[0]["content"])


# ---------------------------------------------------------------------------
# Gemini native SSE
# ---------------------------------------------------------------------------


_GEMINI_TEXT_STREAM = [
    'data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"index":0}],"modelVersion":"gemini-2.5-pro"}',
    'data: {"candidates":[{"content":{"parts":[{"text":" world"}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7},"modelVersion":"gemini-2.5-pro"}',
]

_GEMINI_TOOL_STREAM = [
    'data: {"candidates":[{"content":{"parts":[{"text":"calling"}],"role":"model"},"index":0}],"modelVersion":"gemini-2.5-pro"}',
    'data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"multiply","args":{"a":6,"b":7}}}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"totalTokenCount":8},"modelVersion":"gemini-2.5-pro"}',
]


def test_gemini_chat_stream_text(monkeypatch) -> None:
    monkeypatch.setenv("GOOGLE_API_KEY", "test-not-real")
    client = router.get_client("gemini")
    deltas: list[str] = []

    with respx.mock(assert_all_called=True) as mock:
        mock.post(url__regex=r".*/models/gemini-2\.5-pro:streamGenerateContent.*").mock(
            return_value=_sse(*_GEMINI_TEXT_STREAM)
        )
        response = client.chat_stream(
            [{"role": "user", "content": "hi"}], [], "gemini-2.5-pro",
            on_delta=deltas.append,
        )

    assert deltas == ["Hello", " world"]
    assert client.extract_text(response) == "Hello world"
    assert client.extract_tool_calls(response) == []


def test_gemini_chat_stream_tool_calls(monkeypatch) -> None:
    monkeypatch.setenv("GOOGLE_API_KEY", "test-not-real")
    client = router.get_client("gemini")
    deltas: list[str] = []
    preparing: list[str] = []

    with respx.mock(assert_all_called=True) as mock:
        mock.post(url__regex=r".*/models/gemini-2\.5-pro:streamGenerateContent.*").mock(
            return_value=_sse(*_GEMINI_TOOL_STREAM)
        )
        response = client.chat_stream(
            [{"role": "user", "content": "six times seven"}], [], "gemini-2.5-pro",
            on_delta=deltas.append,
            on_tool_preparing=preparing.append,
        )

    assert deltas == ["calling"]
    calls = client.extract_tool_calls(response)
    assert len(calls) == 1
    assert calls[0].name == "multiply"
    assert calls[0].args == {"a": 6, "b": 7}

    messages: list = []
    client.append_assistant(messages, response)
    assert messages[0]["role"] == "assistant"
    assert preparing == ["multiply"]


def test_anthropic_thinking_stream_is_separate_and_preserves_signature(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-not-real")
    client = router.get_client("anthropic")
    text, thinking = [], []
    stream = [
        _ANTHROPIC_TEXT_STREAM[0],
        'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}',
        'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Inspect the pixels"}}',
        'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque-signature"}}',
        _ANTHROPIC_TEXT_STREAM[4],
        *[line.replace('"index":0', '"index":1') for line in _ANTHROPIC_TEXT_STREAM[1:]],
    ]
    with respx.mock as mock:
        mock.post("https://api.anthropic.com/v1/messages").mock(return_value=_sse(*stream))
        reply = client.chat_stream([], [], "claude-opus-4-7", on_delta=text.append,
                                   on_thinking=thinking.append)
    assert thinking == ["Inspect the pixels"]
    assert "".join(text) == "Hello world"
    assert client.extract_text(reply) == "Hello world"
    assert client.extract_thinking(reply)[0]["signature"] == "opaque-signature"


def test_gemini_thoughts_are_streamed_separately(monkeypatch):
    monkeypatch.setenv("GOOGLE_API_KEY", "test-not-real")
    client = router.get_client("gemini")
    text, thinking = [], []
    thought = 'data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"Inspect the pixels"}],"role":"model"},"index":0}]}'
    with respx.mock as mock:
        mock.post(url__regex=r".*/models/gemini-2\.5-pro:streamGenerateContent.*").mock(
            return_value=_sse(thought, *_GEMINI_TEXT_STREAM))
        reply = client.chat_stream([{"role": "user", "content": "hi"}], [], "gemini-2.5-pro", on_delta=text.append,
                                   on_thinking=thinking.append)
    assert thinking == ["Inspect the pixels"]
    assert "".join(text) == "Hello world"
    assert client.extract_text(reply) == "Hello world"


def test_proxy_reasoning_is_streamed_separately():
    client = router.get_client("groq", base_url="http://localhost:4000", api_key="dummy")
    text, thinking = [], []
    thought = 'data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"reasoning_content":"Inspect the pixels"}}]}'
    with respx.mock as mock:
        route = mock.post("http://localhost:4000/v1/chat/completions").mock(
            return_value=_sse(thought, *_OPENAI_TEXT_STREAM))
        reply = client.chat_stream([], [], "llama-3.3-70b-versatile", on_delta=text.append,
                                   on_thinking=thinking.append)
        assert b"on_thinking" not in route.calls[0].request.content
    assert thinking == ["Inspect the pixels"]
    assert "".join(text) == "Hello"
    assert client.extract_text(reply) == "Hello"
