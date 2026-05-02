"""End-to-end shape test for the multi-provider spike.

Goal: prove the same provider-agnostic tool loop drives all three paths.
We do NOT hit real APIs — each provider's SDK method is monkey-patched
to return canned responses. Each test asserts:

  1. The loop sends a tool spec the SDK accepts (via the schema converter).
  2. The model's "tool_calls" come out of `extract_tool_calls` as
     normalised `ToolCall(id, name, args)` objects.
  3. Re-threading `append_assistant` + `append_tool_result` produces a
     message list the next call would accept.
  4. The loop terminates when the model returns a final text answer.

Implementation note: mock side_effects deep-copy the captured kwargs
because the loop mutates `messages` in place. Without snapshotting,
`mock.call_args_list[i]` aliases the *final* state of the list and
makes per-iteration assertions impossible.

Run:  python -m pytest agent/providers/_spike/test_spike.py -v
  or: python agent/providers/_spike/test_spike.py
"""
from __future__ import annotations

import json
import os
import sys
import types
from pathlib import Path
from typing import Callable
from unittest.mock import patch

import pytest

# Allow running as `python test_spike.py` from this directory or as
# `python -m pytest agent/providers/_spike/test_spike.py` from repo root.
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from base import ToolCall, fn_to_json_schema, to_openai_tool, to_anthropic_tool, to_gemini_tool  # noqa: E402
from router import get_client  # noqa: E402


# ── The fake tool ────────────────────────────────────────────────────────

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


def make_snapshotting_side_effect(responses):
    """Wrap an iterator of canned responses so kwargs get deep-copied.

    Returns (side_effect, captured) — captured[i] is the kwargs as they
    looked at call time, not the post-mutation alias.
    """
    import copy
    captured: list[dict] = []

    def _side(**kw):
        captured.append(copy.deepcopy(kw))
        return next(responses)

    return _side, captured


# ── Provider-agnostic loop (mirrors what ollama_chat.py becomes) ─────────

def run_loop(client, model: str, prompt: str, max_rounds: int = 4) -> str:
    """Tool-dispatch loop, one shape, three providers."""
    messages: list = [{"role": "user", "content": prompt}]
    tools: list[Callable] = [multiply]

    for _ in range(max_rounds):
        resp = client.chat(messages, tools, model)
        client.append_assistant(messages, resp)
        calls = client.extract_tool_calls(resp)
        if not calls:
            return client.extract_text(resp)
        for tc in calls:
            assert tc.name == "multiply", f"unexpected tool: {tc.name}"
            result = multiply(**tc.args)
            client.append_tool_result(messages, tc, str(result))
    raise AssertionError("loop did not terminate")


# ── Schema-converter unit checks ─────────────────────────────────────────

def test_schema_converter_openai():
    spec = to_openai_tool(multiply)
    assert spec["type"] == "function"
    fn = spec["function"]
    assert fn["name"] == "multiply"
    assert fn["parameters"]["type"] == "object"
    assert set(fn["parameters"]["properties"]) == {"a", "b"}
    assert fn["parameters"]["properties"]["a"]["type"] == "integer"
    assert fn["parameters"]["required"] == ["a", "b"]


def test_schema_converter_anthropic():
    spec = to_anthropic_tool(multiply)
    assert spec["name"] == "multiply"
    assert "input_schema" in spec
    assert spec["input_schema"]["properties"]["b"]["type"] == "integer"


def test_schema_converter_gemini():
    spec = to_gemini_tool(multiply)
    assert spec["name"] == "multiply"
    assert spec["parameters"]["properties"]["a"]["type"] == "integer"


def test_no_arg_tool_omits_parameters_for_gemini():
    """Gemini rejects empty `properties: {}`; converter must drop it."""

    def ping() -> str:
        """Test connection."""
        return "pong"

    g = to_gemini_tool(ping)
    assert "parameters" not in g
    # OpenAI/Anthropic accept the empty schema, no special-case needed.
    assert "parameters" in to_openai_tool(ping)["function"]
    assert "input_schema" in to_anthropic_tool(ping)


# ── Stub builders ────────────────────────────────────────────────────────

def _openai_tool_call_response(call_id: str, name: str, args: dict):
    """Fake `ChatCompletion` shaped enough for our extractors."""
    msg = types.SimpleNamespace(
        role="assistant",
        content=None,
        tool_calls=[types.SimpleNamespace(
            id=call_id,
            type="function",
            function=types.SimpleNamespace(
                name=name,
                arguments=json.dumps(args),
            ),
        )],
    )
    choice = types.SimpleNamespace(message=msg, finish_reason="tool_calls", index=0)
    return types.SimpleNamespace(choices=[choice])


def _openai_text_response(text: str):
    msg = types.SimpleNamespace(role="assistant", content=text, tool_calls=None)
    choice = types.SimpleNamespace(message=msg, finish_reason="stop", index=0)
    return types.SimpleNamespace(choices=[choice])


def _anthropic_tool_use_response(call_id: str, name: str, args: dict):
    block = types.SimpleNamespace(type="tool_use", id=call_id, name=name, input=args)
    return types.SimpleNamespace(content=[block], stop_reason="tool_use", role="assistant")


def _anthropic_text_response(text: str):
    block = types.SimpleNamespace(type="text", text=text)
    return types.SimpleNamespace(content=[block], stop_reason="end_turn", role="assistant")


def _gemini_tool_call_response(name: str, args: dict):
    fc = types.SimpleNamespace(name=name, args=args)
    part = types.SimpleNamespace(function_call=fc, text=None)
    content = types.SimpleNamespace(parts=[part], role="model")
    cand = types.SimpleNamespace(content=content, finish_reason="STOP")
    return types.SimpleNamespace(candidates=[cand], text="")


def _gemini_text_response(text: str):
    part = types.SimpleNamespace(function_call=None, text=text)
    content = types.SimpleNamespace(parts=[part], role="model")
    cand = types.SimpleNamespace(content=content, finish_reason="STOP")
    return types.SimpleNamespace(candidates=[cand], text=text)


# ── Path A: LiteLLM Proxy (openai SDK) ───────────────────────────────────

def test_litellm_proxy_loop():
    reset_log()
    # Get a real client. We bypass the proxy by patching the SDK method
    # on this client's resource. base_url is fake — never actually hit.
    client = get_client("groq", base_url="http://localhost:65535", api_key="dummy")

    responses = iter([
        _openai_tool_call_response("call_abc", "multiply", {"a": 6, "b": 7}),
        _openai_text_response("The answer is 42."),
    ])
    side, captured = make_snapshotting_side_effect(responses)

    with patch.object(client._client.chat.completions, "create",
                      side_effect=side) as mock_create:
        out = run_loop(client, "groq/llama-3.3-70b-versatile", "What is 6 * 7?")

    assert out == "The answer is 42."
    assert CALL_LOG == [("multiply", {"a": 6, "b": 7})]
    assert mock_create.call_count == 2

    # Verify the tool spec the SDK saw matches the OpenAI format.
    tools = captured[0]["tools"]
    assert tools[0]["type"] == "function"
    assert tools[0]["function"]["name"] == "multiply"

    # Second call must include both the assistant tool_call and the tool result.
    second_msgs = captured[1]["messages"]
    roles = [m["role"] for m in second_msgs]
    assert roles == ["user", "assistant", "tool"]
    assert second_msgs[2]["tool_call_id"] == "call_abc"
    assert second_msgs[2]["content"] == "42"


# ── Path B: Anthropic native ─────────────────────────────────────────────

def test_anthropic_native_loop():
    reset_log()
    # The Anthropic SDK rejects construction with no API key. Stub it.
    os.environ.setdefault("ANTHROPIC_API_KEY", "sk-test-not-real")
    client = get_client("anthropic")

    responses = iter([
        _anthropic_tool_use_response("toolu_01", "multiply", {"a": 6, "b": 7}),
        _anthropic_text_response("Forty-two."),
    ])
    side, captured = make_snapshotting_side_effect(responses)

    with patch.object(client._client.messages, "create",
                      side_effect=side) as mock_create:
        out = run_loop(client, "claude-opus-4-7", "What is 6 * 7?")

    assert out == "Forty-two."
    assert CALL_LOG == [("multiply", {"a": 6, "b": 7})]
    assert mock_create.call_count == 2

    # First call: tools spec uses Anthropic's input_schema shape, system param split out.
    first_kwargs = captured[0]
    assert "tools" in first_kwargs
    assert first_kwargs["tools"][0]["name"] == "multiply"
    assert "input_schema" in first_kwargs["tools"][0]
    # Second call: assistant message preserves the tool_use block, then a
    # role=user message carries the tool_result block.
    second_msgs = captured[1]["messages"]
    assert len(second_msgs) == 3
    assert second_msgs[0]["role"] == "user"
    assert second_msgs[1]["role"] == "assistant"
    asst_blocks = second_msgs[1]["content"]
    assert any(b["type"] == "tool_use" and b["id"] == "toolu_01" for b in asst_blocks)
    assert second_msgs[2]["role"] == "user"
    tr_block = second_msgs[2]["content"][0]
    assert tr_block["type"] == "tool_result"
    assert tr_block["tool_use_id"] == "toolu_01"
    assert tr_block["content"] == "42"


# ── Path C: Gemini native ────────────────────────────────────────────────

def test_gemini_native_loop():
    reset_log()
    os.environ.setdefault("GOOGLE_API_KEY", "AIza-test-not-real")
    client = get_client("gemini")

    responses = iter([
        _gemini_tool_call_response("multiply", {"a": 6, "b": 7}),
        _gemini_text_response("42!"),
    ])
    # The Gemini SDK passes a GenerateContentConfig pydantic object — deepcopy
    # works on those, but we restore the original config since pydantic copies
    # don't preserve the live `tools` attribute reference.
    import copy
    captured = []

    def side(**kw):
        # contents are JSON-able; config is a pydantic model so we keep it.
        captured.append({
            "model": kw.get("model"),
            "contents": copy.deepcopy(kw.get("contents")),
            "config": kw.get("config"),
        })
        return next(responses)

    with patch.object(client._client.models, "generate_content",
                      side_effect=side) as mock_create:
        out = run_loop(client, "gemini-2.5-pro", "What is 6 * 7?")

    assert out == "42!"
    assert CALL_LOG == [("multiply", {"a": 6, "b": 7})]
    assert mock_create.call_count == 2

    cfg = captured[0]["config"]
    # Tools wrapped in a Tool with function_declarations.
    assert cfg.tools, "expected tools= on first call"
    decls = cfg.tools[0].function_declarations
    assert decls[0].name == "multiply"

    # Second call's contents must include a function_response part.
    second_contents = captured[1]["contents"]
    has_fc_part = False
    has_fr_part = False
    for c in second_contents:
        for p in c.get("parts", []):
            if "function_call" in p:
                has_fc_part = True
            if "function_response" in p:
                has_fr_part = True
    assert has_fc_part, "expected function_call part in re-sent history"
    assert has_fr_part, "expected function_response part in re-sent history"


# ── Cross-provider parity check ──────────────────────────────────────────

def test_all_three_paths_emit_same_normalised_tool_call():
    """ToolCall(id, name, args) is the agreed lingua franca."""
    from anthropic_native import AnthropicNativeClient
    from litellm_proxy import LiteLLMProxyClient
    from gemini_native import GeminiNativeClient

    a = LiteLLMProxyClient.__new__(LiteLLMProxyClient).extract_tool_calls(
        _openai_tool_call_response("x1", "multiply", {"a": 1, "b": 2}))
    b = AnthropicNativeClient.__new__(AnthropicNativeClient).extract_tool_calls(
        _anthropic_tool_use_response("x2", "multiply", {"a": 1, "b": 2}))
    c = GeminiNativeClient.__new__(GeminiNativeClient).extract_tool_calls(
        _gemini_tool_call_response("multiply", {"a": 1, "b": 2}))

    for got in (a, b, c):
        assert len(got) == 1
        assert isinstance(got[0], ToolCall)
        assert got[0].name == "multiply"
        assert got[0].args == {"a": 1, "b": 2}


# ── Smoke: also runnable as a script ─────────────────────────────────────

if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
