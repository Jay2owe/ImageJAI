"""Regression tests for the multi-provider verifier-loop fixes.

Covers two defects found in the sequential codex verifier pass:

* the proxy client hard-coded ``localhost:4000`` and ignored the dynamic port
  the Java sidecar actually bound (``default_base_url`` / ``IMAGEJAI_LITELLM_PORT``);
* the native (Anthropic/Gemini) agent loop never enforced a spend ceiling, so a
  paid run kept issuing calls past the budget (``agent_cli._BudgetGuard``).
"""
from __future__ import annotations

import agent.providers.agent_cli as agent_cli
import agent.providers.litellm_proxy as litellm_proxy
import agent.providers.router as router


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
