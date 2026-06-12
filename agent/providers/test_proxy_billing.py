"""Tests for the proxy's billing-failure sentinel (Phase H §3.7).

The proxy's response-cost middleware also handles LiteLLM failure events. On a
billing-class status (401/402/429) it prints a ``[ImageJAI-LiteLLM-Billing]``
line that LiteLlmProxyService parses into a Swing dialog. These tests drive the
async failure hook directly and assert the sentinel shape.
"""
from __future__ import annotations

import asyncio
import io
import json
from contextlib import redirect_stdout

from agent.providers import proxy

BILLING_PREFIX = "[ImageJAI-LiteLLM-Billing] "


class _ApiError(Exception):
    def __init__(self, status_code: int, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.message = message


def _run_failure(kwargs: dict, response_obj=None) -> str:
    middleware = proxy.ResponseCostMiddleware()
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        asyncio.run(middleware.async_log_failure_event(kwargs, response_obj, 0.0, 1.0))
    return buffer.getvalue()


def _billing_payload(output: str) -> dict:
    lines = [ln for ln in output.splitlines() if ln.startswith(BILLING_PREFIX)]
    assert lines, f"no billing sentinel in output: {output!r}"
    return json.loads(lines[0][len(BILLING_PREFIX):])


def test_emits_sentinel_on_401_with_explicit_provider():
    out = _run_failure(
        {"exception": _ApiError(401, "no credit balance"), "custom_llm_provider": "anthropic"}
    )
    payload = _billing_payload(out)
    assert payload["status"] == 401
    assert payload["provider"] == "anthropic"
    assert "no credit balance" in payload["message"]
    assert payload["event"] == "billing"


def test_provider_derived_from_model_prefix():
    out = _run_failure({"exception": _ApiError(429, "rate limited"), "model": "groq/llama-3.3-70b"})
    payload = _billing_payload(out)
    assert payload["status"] == 429
    assert payload["provider"] == "groq"


def test_402_payment_required_emits():
    out = _run_failure({"exception": _ApiError(402, "payment required"), "custom_llm_provider": "openai"})
    assert _billing_payload(out)["status"] == 402


def test_non_billing_status_is_silent():
    out = _run_failure({"exception": _ApiError(500, "server boom"), "custom_llm_provider": "openai"})
    assert BILLING_PREFIX not in out


def test_status_parsed_from_message_when_attribute_missing():
    out = _run_failure(
        {"exception": RuntimeError("AuthenticationError: 401 invalid api key"),
         "custom_llm_provider": "openai"}
    )
    payload = _billing_payload(out)
    assert payload["status"] == 401
    assert payload["provider"] == "openai"


def test_message_is_truncated():
    long_message = "x" * 1000
    out = _run_failure({"exception": _ApiError(401, long_message), "custom_llm_provider": "mistral"})
    payload = _billing_payload(out)
    assert len(payload["message"]) == 240


def test_code_embedded_in_larger_number_is_not_matched():
    # "req_4021" contains "402" but must not be misread as a 402 billing error.
    out = _run_failure(
        {"exception": RuntimeError("request id req_4021 failed validation"),
         "custom_llm_provider": "openai"}
    )
    assert BILLING_PREFIX not in out


def test_word_boundary_code_still_matched():
    out = _run_failure(
        {"exception": RuntimeError("Error 402: payment required"),
         "custom_llm_provider": "openai"}
    )
    assert _billing_payload(out)["status"] == 402
