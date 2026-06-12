from __future__ import annotations

import json

import httpx
import respx

from agent.providers import router
from agent.providers.gemini_native import GeminiNativeClient
from agent.providers.test_base import CALL_LOG, multiply, run_loop


def test_gemini_native_loop(monkeypatch) -> None:
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    client = router.get_client("gemini")
    calls_seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls_seen.append(json.loads(request.content))
        if len(calls_seen) == 1:
            return httpx.Response(200, json=_gemini_tool_json("multiply", {"a": 6, "b": 7}))
        return httpx.Response(200, json=_gemini_text_json("42!"))

    with respx.mock(assert_all_called=True) as mock:
        mock.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent").mock(
            side_effect=handler
        )
        out = run_loop(client, "gemini-2.5-pro", "What is 6 * 7?")

    assert out == "42!"
    assert CALL_LOG == [("multiply", {"a": 6, "b": 7})]
    assert calls_seen[0]["tools"][0]["functionDeclarations"][0]["name"] == "multiply"
    assert calls_seen[0]["tools"][0]["functionDeclarations"][0]["parameters"]["type"] == "OBJECT"
    second_contents = calls_seen[1]["contents"]
    parts = [part for content in second_contents for part in content.get("parts", [])]
    assert any("functionCall" in part for part in parts)
    assert any("functionResponse" in part for part in parts)


def test_gemini_disables_automatic_function_calling(monkeypatch) -> None:
    monkeypatch.setenv("GOOGLE_API_KEY", "AIza-test-not-real")
    client = GeminiNativeClient(api_key="AIza-test-not-real")
    captured = {}

    def fake_generate_content(**kwargs):
        captured.update(kwargs)
        return _gemini_sdk_text_response("ok")

    monkeypatch.setattr(client._client.models, "generate_content", fake_generate_content)
    client.chat([{"role": "user", "content": "hi"}], [multiply], "gemini-2.5-flash")

    config = captured["config"]
    assert config.automatic_function_calling.disable is True


def test_gemini_synthesises_tool_call_ids() -> None:
    client = GeminiNativeClient.__new__(GeminiNativeClient)
    response = _gemini_sdk_tool_response(
        [
            ("multiply", {"a": 1, "b": 2}),
            ("multiply", {"a": 3, "b": 4}),
        ]
    )
    calls = client.extract_tool_calls(response)
    assert [call.id for call in calls] == ["multiply:0", "multiply:1"]


def _gemini_tool_json(name: str, args: dict) -> dict:
    return {
        "candidates": [
            {
                "content": {
                    "role": "model",
                    "parts": [{"functionCall": {"name": name, "args": args}}],
                },
                "finishReason": "STOP",
            }
        ]
    }


def _gemini_text_json(text: str) -> dict:
    return {
        "candidates": [
            {
                "content": {"role": "model", "parts": [{"text": text}]},
                "finishReason": "STOP",
            }
        ]
    }


def _gemini_sdk_text_response(text: str):
    import types

    part = types.SimpleNamespace(function_call=None, text=text)
    content = types.SimpleNamespace(parts=[part], role="model")
    return types.SimpleNamespace(candidates=[types.SimpleNamespace(content=content)], text=text)


def _gemini_sdk_tool_response(calls: list[tuple[str, dict]]):
    import types

    parts = [
        types.SimpleNamespace(function_call=types.SimpleNamespace(name=name, args=args), text=None)
        for name, args in calls
    ]
    content = types.SimpleNamespace(parts=parts, role="model")
    return types.SimpleNamespace(candidates=[types.SimpleNamespace(content=content)], text="")
