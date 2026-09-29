import json
from types import SimpleNamespace

import pytest

from agent.console.usage import UsageLedger, request_usage, model_request
from agent.console.catalog import CatalogEntry, Tier
from agent.console.agent_loop import TurnCallbacks


@pytest.mark.parametrize("payload,expected", [
    ({"usage": {"prompt_tokens": 100, "completion_tokens": 20}}, (100, 20)),
    (SimpleNamespace(usage=SimpleNamespace(input_tokens=100, output_tokens=20,
        cache_read_input_tokens=30, cache_creation_input_tokens=10)), (140, 20)),
    (SimpleNamespace(usage_metadata=SimpleNamespace(prompt_token_count=100,
        candidates_token_count=20, thoughts_token_count=5)), (100, 25)),
])
def test_provider_usage_shapes(payload, expected):
    row = request_usage("provider", "model", payload)
    assert (row["input_tokens"], row["output_tokens"]) == expected
    assert row["tokens_source"] == "reported" and row["cost_usd"] is None


def test_resume_dedup_and_reported_zero_cost_are_preserved():
    row = request_usage("openai", "m", {"usage": {"input_tokens": 100, "output_tokens": 20}, "total_cost_usd": 0}, request_id="same")
    ledger = UsageLedger()
    ledger.record(row)
    reloaded = UsageLedger(json.loads(json.dumps(ledger.rows)))
    assert reloaded.record(row) is None
    assert len(reloaded.rows) == 1 and reloaded.total_usd == 0
    assert reloaded.rows[0]["cost_source"] == "reported"


def test_registry_pricing_estimate_and_unknown_are_not_a_fake_zero():
    entry = CatalogEntry("openai", "m", tier=Tier.PAID,
                         features={"pricing": {"input_usd_per_mtok": 2, "output_usd_per_mtok": 4}})
    ledger = UsageLedger()
    row = request_usage("openai", "m", {"usage": {"input_tokens": 1000, "output_tokens": 500}})
    assert ledger.record(row, entry)["cost_source"] == "registry estimate"
    assert ledger.total_usd == pytest.approx(.004)
    ledger.record(request_usage("unknown", "m", {}, [], "text"))
    assert ledger.unknown_requests == 1
    assert "unknown cost: 1" in ledger.summary()


def test_model_boundary_emits_once_on_success_and_failure_and_refusal_is_not_sent():
    rows = []
    agent = SimpleNamespace(provider="openai", model="m")
    cb = TurnCallbacks(on_usage=rows.append)
    with model_request(agent, [{"role": "user", "content": "x" * 40}], cb) as req:
        req.text = "x" * 20
    assert rows[0]["input_tokens"] == 10 and rows[0]["output_tokens"] == 5
    with pytest.raises(ValueError), model_request(agent, [], cb):
        raise ValueError("billing")
    assert len(rows) == 2 and rows[-1]["failed"]
    from agent.console.usage import ModelCallStopped
    with pytest.raises(ModelCallStopped), model_request(agent, [], TurnCallbacks(before_model_call=lambda _: False, on_usage=rows.append)):
        pytest.fail("must not run")
    assert len(rows) == 2


def test_vendor_reported_usage_is_captured_without_extra_display():
    from agent.console.vendor_events import VendorEvents
    events = VendorEvents("claude-subscription", TurnCallbacks())
    events.feed(json.dumps({"type": "result", "usage": {"input_tokens": 12, "output_tokens": 4}, "total_cost_usd": .02}))
    assert request_usage("claude-subscription", "default", events.usage)["cost_usd"] == .02
    events = VendorEvents("codex-subscription", TurnCallbacks())
    events.feed(json.dumps({"type": "turn.completed", "usage": {"input_tokens": 12, "output_tokens": 4}}))
    assert request_usage("codex-subscription", "default", events.usage)["input_tokens"] == 12


def test_session_usage_survives_reload_and_old_sessions_default_empty(tmp_path, monkeypatch):
    from agent.console import config
    from agent.console.sessions import SessionStore, Session
    monkeypatch.setattr(config, "CONFIG_DIR", tmp_path)
    store = SessionStore(config.ConsoleConfig())
    session = store.create()
    session.usage = [request_usage("local", "m", {}, request_id="record")]
    store.save_session(session)
    assert SessionStore(config.ConsoleConfig()).get(session.id).usage == session.usage
    assert Session(id="old").usage == []
