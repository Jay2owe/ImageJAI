"""Regression tests for the multi-provider verifier-loop fixes.

Covers two defects found in the sequential codex verifier pass:

* the proxy client hard-coded ``localhost:4000`` and ignored the dynamic port
  the Java sidecar actually bound (``default_base_url`` / ``IMAGEJAI_LITELLM_PORT``);
* the native (Anthropic/Gemini) agent loop never enforced a spend ceiling, so a
  paid run kept issuing calls past the budget (``agent_cli._BudgetGuard``).
"""
from __future__ import annotations

import base64
import copy
import json
import os
import threading
import time
import types

import pytest
import yaml
from PIL import Image

import agent.providers.agent_cli as agent_cli
import agent.providers.litellm_proxy as litellm_proxy
import agent.providers.proxy as proxy_runtime
import agent.providers.router as router
from agent.gemma4_31b import loop as rich_loop
from agent.gemma4_31b import tools_dialogs, tools_fiji, tools_python
from agent.ollama_agent.budget_ceiling import CostBreakdown
from agent.providers.anthropic_native import AnthropicNativeClient
from agent.providers.base import ToolCall, fn_to_json_schema
from agent.providers.gemini_native import GeminiNativeClient


# --- #2: dynamic proxy port -------------------------------------------------

def test_default_base_url_falls_back_to_4000(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_LITELLM_PORT", raising=False)
    monkeypatch.delenv("IMAGEJAI_LITELLM_BASE_URL", raising=False)
    monkeypatch.setattr(litellm_proxy, "_scan_live_proxy_port", lambda: None)
    assert litellm_proxy.default_base_url() == "http://localhost:4000/v1"


def test_default_base_url_honours_dynamic_port(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_LITELLM_BASE_URL", raising=False)
    monkeypatch.setenv("IMAGEJAI_LITELLM_PORT", "4007")
    assert litellm_proxy.default_base_url() == "http://localhost:4007/v1"


def test_default_base_url_explicit_url_wins(monkeypatch):
    monkeypatch.setenv("IMAGEJAI_LITELLM_PORT", "4007")
    monkeypatch.setenv("IMAGEJAI_LITELLM_BASE_URL", "http://localhost:9999/v1")
    assert litellm_proxy.default_base_url() == "http://localhost:9999/v1"


def test_default_base_url_ignores_garbage_port_and_scans(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_LITELLM_BASE_URL", raising=False)
    monkeypatch.setenv("IMAGEJAI_LITELLM_PORT", "not-a-port")
    monkeypatch.setattr(litellm_proxy, "_scan_live_proxy_port", lambda: None)
    assert litellm_proxy.default_base_url() == "http://localhost:4000/v1"


def test_default_base_url_scans_when_port_unconfirmed(monkeypatch):
    # Java exports no port until the sidecar is ready (#3-1); the client then
    # scans 4000-4010 for the live proxy rather than trusting a stale 4000.
    monkeypatch.delenv("IMAGEJAI_LITELLM_BASE_URL", raising=False)
    monkeypatch.delenv("IMAGEJAI_LITELLM_PORT", raising=False)
    monkeypatch.setattr(litellm_proxy, "_scan_live_proxy_port", lambda: 4005)
    assert litellm_proxy.default_base_url() == "http://localhost:4005/v1"


def test_router_proxy_client_uses_dynamic_port(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_LITELLM_BASE_URL", raising=False)
    monkeypatch.setenv("IMAGEJAI_LITELLM_PORT", "4003")
    client = router.get_client("groq", "llama-3.3-70b-versatile")
    assert client.base_url == "http://localhost:4003/v1"


# --- #1: native-path budget ceiling -----------------------------------------

def test_ceiling_from_env_parsing(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_BUDGET_CEILING_USD", raising=False)
    assert agent_cli._ceiling_from_env() == 0.0
    monkeypatch.setenv("IMAGEJAI_BUDGET_CEILING_USD", "1.50")
    assert agent_cli._ceiling_from_env() == 1.50
    monkeypatch.setenv("IMAGEJAI_BUDGET_CEILING_USD", "-3")
    assert agent_cli._ceiling_from_env() == 0.0
    monkeypatch.setenv("IMAGEJAI_BUDGET_CEILING_USD", "junk")
    assert agent_cli._ceiling_from_env() == 0.0


def test_budget_guard_trips_at_ceiling():
    guard = agent_cli._BudgetGuard(1.00)
    assert guard.enabled()
    assert not guard.exceeded()
    guard._on_cost("0.40")
    guard._on_cost("0.40")
    assert not guard.exceeded()
    guard._on_cost("0.30")  # total 1.10 >= 1.00
    assert guard.exceeded()
    guard.raise_ceiling()
    assert guard.ceiling_usd >= 2.0  # max(1.0*2, 1.10*2)
    assert not guard.exceeded()


def test_budget_guard_disabled_when_no_ceiling():
    guard = agent_cli._BudgetGuard(0.0)
    assert not guard.enabled()
    guard._on_cost("99.0")
    assert not guard.exceeded()


def test_run_turn_pauses_when_budget_exceeded():
    """A guard already over the ceiling stops the turn before any model call."""
    guard = agent_cli._BudgetGuard(1.0)
    guard.total_usd = 5.0  # already over

    class _Boom:
        def chat(self, *a, **k):  # must never be called
            raise AssertionError("model was called despite budget breach")

    emitted: list[str] = []
    out = agent_cli.run_turn(_Boom(), "m", [], emit=emitted.append, guard=guard)
    assert out == ""
    assert any("budget" in line.lower() for line in emitted)


def test_proxy_cost_header_trips_agent_loop_budget_guard():
    """Proxy spend reaches the same in-loop guard as native provider spend."""
    import httpx
    import respx

    from agent.providers.litellm_proxy import COST_HEADER

    guard = agent_cli._BudgetGuard(0.01)
    guard.register()
    try:
        client = router.get_client("groq", "llama-3.3-70b-versatile",
                                   base_url="http://localhost:4000",
                                   api_key="dummy")
        with respx.mock(assert_all_called=True) as mock:
            mock.post("http://localhost:4000/v1/chat/completions").mock(
                return_value=httpx.Response(
                    200,
                    headers={COST_HEADER: "0.02"},
                    json={
                        "id": "chatcmpl-text",
                        "object": "chat.completion",
                        "created": 1,
                        "model": "test",
                        "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "ok"},
                            "finish_reason": "stop",
                        }],
                    },
                )
            )
            client.chat([{"role": "user", "content": "hi"}], [],
                        "llama-3.3-70b-versatile")
    finally:
        guard.unregister()

    assert guard.exceeded()


def test_registry_contract_defaults_and_real_array_schema():
    close_schema = fn_to_json_schema(tools_dialogs.close_dialogs)["schema"]
    capture_schema = fn_to_json_schema(tools_fiji.capture_image)["schema"]
    pixels_schema = fn_to_json_schema(tools_python.get_pixels_array)["schema"]

    assert "pattern" not in close_schema["required"]
    assert close_schema["properties"]["pattern"]["default"] == ""
    assert "max_size" not in capture_schema["required"]
    assert capture_schema["properties"]["max_size"]["default"] == 1024
    assert pixels_schema["properties"]["region"] == {
        "type": "array",
        "items": {"type": "integer"},
        "description": "Rectangle as [x, y, width, height], or empty list for the whole image.",
    }


def test_every_provider_adapter_attaches_a_bounded_capture(tmp_path):
    path = tmp_path / "capture.png"
    Image.new("RGB", (32, 24), "red").save(path)
    call = ToolCall(id="capture-1", name="capture_image", args={})

    anthropic_messages: list[dict] = []
    AnthropicNativeClient.__new__(AnthropicNativeClient).append_tool_result(
        anthropic_messages, call, str(path)
    )
    assert anthropic_messages[-1]["content"][1]["type"] == "image"

    gemini = GeminiNativeClient.__new__(GeminiNativeClient)
    gemini_messages: list[dict] = []
    gemini.append_tool_result(gemini_messages, call, str(path))
    parts = gemini._messages_to_contents(gemini_messages)[0]["parts"]
    assert "inline_data" in parts[1]

    proxy_messages: list[dict] = []
    litellm_proxy.LiteLLMProxyClient.__new__(
        litellm_proxy.LiteLLMProxyClient
    ).append_tool_result(proxy_messages, call, str(path))
    assert proxy_messages[-1]["content"][1]["type"] == "image_url"


def test_interrupt_waits_for_owned_worker_to_finish():
    abort = threading.Event()
    abort.set()
    finished = threading.Event()

    def work():
        time.sleep(0.02)
        finished.set()

    with pytest.raises(rich_loop._TurnAborted):
        rich_loop._run_interruptible(work, abort_event=abort)
    assert finished.is_set()


def test_governed_event_bridge_recovers_after_initial_protocol_failure(monkeypatch):
    stop = threading.Event()
    factory_calls = 0
    handled = []

    class Session:
        def __init__(self, fail):
            self.fail = fail

        def events(self, topics, reconnect=True):
            assert reconnect is True
            if self.fail:
                yield {"ok": False, "error": {"code": "AUTH_REQUIRED"}}
                return
            yield {"event": "image.opened", "data": {"image_id": "stable-1"}}
            stop.set()

    def factory():
        nonlocal factory_calls
        factory_calls += 1
        return Session(factory_calls == 1)

    monkeypatch.setattr(rich_loop.events, "_handle_frame", handled.append)
    sleeps = []
    rich_loop._consume_governed_events(
        ["image.*"],
        session_factory=factory,
        stop_event=stop,
        sleep_fn=sleeps.append,
    )

    assert factory_calls == 2
    assert handled[0]["data"]["image_id"] == "stable-1"
    assert sleeps == [rich_loop._EVENT_RECONNECT_INITIAL_S]


def test_history_and_pixel_payloads_are_bounded(monkeypatch):
    monkeypatch.setattr(rich_loop, "MAX_HISTORY_MESSAGES", 6)
    messages = [{"role": "system", "content": "base"}]
    for index in range(4):
        messages.extend(
            [
                {"role": "user", "content": f"turn-{index}"},
                {"role": "assistant", "content": "ok"},
            ]
        )
    rich_loop._bound_history(messages)
    assert len(messages) <= 6
    assert messages[0]["content"] == "base"
    bounded = rich_loop._bounded_tool_result(
        "get_pixels_array", "1" * (rich_loop.MAX_PIXEL_RESULT_CHARS + 10)
    )
    assert "raw pixel history is capped" in bounded
    assert len(bounded) < rich_loop.MAX_PIXEL_RESULT_CHARS + 200


def test_single_tool_heavy_turn_stays_strictly_bounded_and_structurally_valid(monkeypatch):
    monkeypatch.setattr(rich_loop, "MAX_HISTORY_MESSAGES", 20)
    monkeypatch.setattr(rich_loop, "MAX_HISTORY_CHARS", 30_000)
    messages = [
        {"role": "system", "content": "base"},
        {"role": "user", "content": "analyse"},
    ]
    for index in range(50):
        messages.extend(
            [
                {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {
                            "id": f"call-{index}",
                            "type": "function",
                            "function": {"name": "inspect", "arguments": "{}"},
                        }
                    ],
                },
                {
                    "role": "tool",
                    "tool_call_id": f"call-{index}",
                    "content": "x" * 10_000,
                },
                {"role": "system", "content": "post-tool note"},
            ]
        )

    rich_loop._bound_history(messages)

    assert len(messages) <= rich_loop.MAX_HISTORY_MESSAGES
    assert sum(rich_loop._serialised_size(item) for item in messages) <= rich_loop.MAX_HISTORY_CHARS
    assistant_ids = {
        call["id"]
        for message in messages
        if isinstance(message, dict) and message.get("role") == "assistant"
        for call in message.get("tool_calls", [])
    }
    assert all(
        message.get("tool_call_id") in assistant_ids
        for message in messages
        if isinstance(message, dict) and message.get("role") == "tool"
    )


def test_history_never_truncates_opaque_provider_payloads(monkeypatch):
    opaque_data = base64.b64encode(b"image-bytes" * 4_000).decode("ascii")
    opaque_arguments = json.dumps({"code": "x" * 20_000})
    opaque_thinking = "private-reasoning-state" * 1_000
    opaque_signature = "signed-thinking-block-v1"
    messages = [
        {"role": "system", "content": "base"},
        {"role": "user", "content": "inspect the current image"},
        {
            "role": "assistant",
            "content": [
                {
                    "type": "thinking",
                    "thinking": opaque_thinking,
                    "signature": opaque_signature,
                },
                {"type": "text", "text": ""},
            ],
            "tool_calls": [
                {
                    "id": "call-stable",
                    "type": "function",
                    "function": {
                        "name": "run_script",
                        "arguments": opaque_arguments,
                    },
                }
            ],
        },
        {"role": "tool", "tool_call_id": "call-stable", "content": "ok"},
        {
            "role": "user",
            "content": [
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": "image/jpeg",
                        "data": opaque_data,
                    },
                }
            ],
        },
    ]

    compacted = copy.deepcopy(messages)
    rich_loop._compact_message_text(compacted)
    assert compacted[2]["tool_calls"][0]["function"]["arguments"] == opaque_arguments
    assert compacted[2]["content"][0]["thinking"] == opaque_thinking
    assert compacted[2]["content"][0]["signature"] == opaque_signature
    assert compacted[4]["content"][0]["source"]["data"] == opaque_data

    monkeypatch.setattr(rich_loop, "MAX_HISTORY_MESSAGES", 20)
    monkeypatch.setattr(rich_loop, "MAX_HISTORY_CHARS", 10_000)
    bounded = copy.deepcopy(messages)
    rich_loop._bound_history(bounded)

    assert sum(rich_loop._serialised_size(item) for item in bounded) <= 10_000

    def retained_values(value, key):
        if isinstance(value, dict):
            for item_key, item in value.items():
                if item_key == key:
                    yield item
                yield from retained_values(item, key)
        elif isinstance(value, list):
            for item in value:
                yield from retained_values(item, key)

    for arguments in retained_values(bounded, "arguments"):
        assert arguments == opaque_arguments
        assert json.loads(arguments)["code"] == "x" * 20_000
    for data in retained_values(bounded, "data"):
        assert data == opaque_data
        assert base64.b64decode(data, validate=True).startswith(b"image-bytes")
    for thinking in retained_values(bounded, "thinking"):
        assert thinking == opaque_thinking
    for signature in retained_values(bounded, "signature"):
        assert signature == opaque_signature


def test_non_transient_gemini_error_is_not_retried():
    from google.genai import errors as genai_errors

    client = GeminiNativeClient.__new__(GeminiNativeClient)
    calls = 0

    def fail(**kwargs):
        nonlocal calls
        calls += 1
        raise genai_errors.APIError(400, {"error": {"message": "bad request"}})

    client._client = types.SimpleNamespace(
        models=types.SimpleNamespace(generate_content=fail)
    )
    client.max_retries = 4
    client.retry_backoff = 0.0
    client._default_server_tools = set()
    with pytest.raises(RuntimeError, match="Gemini chat failed"):
        client.chat([{"role": "user", "content": "hi"}], [], "gemini-test")
    assert calls == 1


def test_router_forwards_gemini_timeout(monkeypatch):
    captured = {}

    class FakeGemini:
        def __init__(self, **kwargs):
            captured.update(kwargs)

        def configure_tool_policy(self, policy):
            return self

    monkeypatch.setattr(router, "GeminiNativeClient", FakeGemini)
    router.get_client("gemini", "gemini-2.5-flash", timeout=7.5)
    assert captured["timeout"] == 7.5


def test_proxy_runtime_config_is_private_authenticated_and_unpredictable(tmp_path):
    config = tmp_path / "litellm.config.yaml"
    config.write_text("model_list: []\n", encoding="utf-8")
    first = proxy_runtime._create_private_runtime_dir()
    second = proxy_runtime._create_private_runtime_dir()
    try:
        assert first != second
        key = "sk-imagejai-test-private"
        runtime_config = proxy_runtime._write_runtime_config(config, 4000, first, key)
        parsed = yaml.safe_load(runtime_config.read_text(encoding="utf-8"))
        assert parsed["general_settings"]["master_key"] == key
        if os.name != "nt":
            assert runtime_config.stat().st_mode & 0o777 == 0o600
            assert first.stat().st_mode & 0o777 == 0o700
    finally:
        import shutil

        shutil.rmtree(first, ignore_errors=True)
        shutil.rmtree(second, ignore_errors=True)


def test_proxy_stop_cleans_owned_private_runtime_state(tmp_path, monkeypatch):
    runtime_dir = proxy_runtime._create_private_runtime_dir()
    auth_file = tmp_path / "proxy.auth"
    state_file = tmp_path / "proxy.runtime.json"
    port_file = tmp_path / "proxy.port"
    key = "sk-imagejai-owned"
    proxy_runtime._write_private_file(auth_file, key + "\n")
    proxy_runtime._write_private_file(
        state_file,
        '{"runtime_dir": ' + repr(str(runtime_dir)).replace("'", '"') + "}\n",
    )
    port_file.write_text("4004", encoding="utf-8")
    monkeypatch.setattr(proxy_runtime, "_LAST_RUNTIME_DIR", runtime_dir)
    monkeypatch.setattr(proxy_runtime, "_LAST_AUTH_FILE", auth_file)
    monkeypatch.setattr(proxy_runtime, "_LAST_STATE_FILE", state_file)
    monkeypatch.setattr(proxy_runtime, "_LAST_PORT_FILE", port_file)
    monkeypatch.setattr(proxy_runtime, "_LAST_MASTER_KEY", key)
    monkeypatch.setattr(proxy_runtime, "_LAST_PORT", 4004)

    proxy_runtime.stop(types.SimpleNamespace(poll=lambda: 0))

    assert not runtime_dir.exists()
    assert not auth_file.exists()
    assert not state_file.exists()
    assert not port_file.exists()


def test_invalid_cost_headers_fail_closed_to_fallback():
    for value in ("nan", "inf", "-1", "0", "junk"):
        breakdown = CostBreakdown.from_header(value)
        assert breakdown.cost_usd == 0.0
        assert breakdown.source == "fallback"
