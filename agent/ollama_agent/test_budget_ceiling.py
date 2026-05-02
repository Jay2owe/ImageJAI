"""Phase H Python-side acceptance tests for the budget ceiling.

Mirrors docs/multi_provider/06_tier_safety.md §6 — pause-between-iterations,
free-tier exemption, /resume raises rather than resets, fallback pricing path.
"""
from __future__ import annotations

import pytest

from agent.ollama_agent.budget_ceiling import (
    BudgetCeilingTracker,
    CostBreakdown,
    process_pause_input,
    parse_slash_command,
)


def test_disabled_tracker_never_pauses():
    tracker = BudgetCeilingTracker(enabled=False, ceiling_usd=0.10)
    tracker.record_call(
        provider_id="anthropic",
        model_id="claude-sonnet-4-6",
        tier="paid",
        cost_header="0.50",
    )
    decision = tracker.preflight()
    assert decision.action == "continue"


def test_free_tier_calls_do_not_count():
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.01)
    for _ in range(20):
        tracker.record_call(
            provider_id="ollama",
            model_id="llama3.2:3b",
            tier="free",
            cost_header="5.00",
            input_tokens=10_000,
            output_tokens=5_000,
        )
    assert tracker.session_cost_usd == 0.0
    assert tracker.preflight().action == "continue"


def test_paid_call_accumulates_and_pauses():
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.10)
    tracker.record_call(
        provider_id="anthropic",
        model_id="claude-sonnet-4-6",
        tier="paid",
        cost_header="0.05",
    )
    assert tracker.preflight().action == "continue"
    tracker.record_call(
        provider_id="anthropic",
        model_id="claude-sonnet-4-6",
        tier="paid",
        cost_header="0.06",
    )
    decision = tracker.preflight()
    assert decision.action == "pause"
    assert "Budget ceiling reached" in decision.message


def test_ceiling_fires_between_iterations_not_mid_call():
    """The pause is observed by preflight(), called between iterations.

    Recording cost itself never raises or aborts — the in-flight call
    completes (06 §6.6) and is fully accumulated before the next preflight.
    """
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.10)
    # An expensive single call that crosses the ceiling on its own.
    tracker.record_call(
        provider_id="anthropic",
        model_id="claude-opus-4-7",
        tier="paid",
        cost_header="0.50",
    )
    # The cost was recorded — the in-flight call completed.
    assert tracker.session_cost_usd == 0.50
    # The pause is only surfaced when preflight() is called.
    assert tracker.preflight().action == "pause"


def test_resume_raises_rather_than_resets():
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.10)
    tracker.record_call(
        provider_id="anthropic",
        model_id="claude-sonnet-4-6",
        tier="paid",
        cost_header="0.20",
    )
    # User types /resume — ceiling raises, total stays.
    decision = process_pause_input(tracker, "/resume 0.50")
    assert decision.action == "continue"
    assert tracker.ceiling_usd == 0.50
    assert tracker.session_cost_usd == 0.20  # NOT reset


def test_resume_default_doubles_ceiling():
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.50)
    tracker.session_cost_usd = 0.60
    decision = process_pause_input(tracker, "/resume")
    assert decision.action == "continue"
    assert tracker.ceiling_usd == 1.00


def test_switch_returns_switch_decision_with_new_model():
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.10)
    decision = process_pause_input(tracker, "/switch ollama/llama3.2:3b")
    assert decision.action == "switch"
    assert decision.new_model == "ollama/llama3.2:3b"


def test_switch_without_model_pauses_again():
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.10)
    decision = process_pause_input(tracker, "/switch")
    assert decision.action == "pause"


def test_unrecognised_command_keeps_pause():
    tracker = BudgetCeilingTracker(enabled=True, ceiling_usd=0.10)
    decision = process_pause_input(tracker, "/wat")
    assert decision.action == "pause"
    assert "Use /resume" in decision.message


def test_fallback_uses_models_yaml_pricing():
    tracker = BudgetCeilingTracker(
        enabled=True,
        ceiling_usd=0.10,
        pricing_table={
            "anthropic/claude-sonnet-4-6": {
                "input_usd_per_mtok": 3.0,
                "output_usd_per_mtok": 15.0,
            }
        },
    )
    breakdown = tracker.estimate_fallback_usd(
        "anthropic", "claude-sonnet-4-6",
        input_tokens=10_000,
        output_tokens=5_000,
    )
    # 0.01M * $3 + 0.005M * $15 = $0.03 + $0.075 = $0.105
    assert breakdown.cost_usd == pytest.approx(0.105)
    assert breakdown.source == "fallback"


def test_record_call_uses_fallback_when_header_absent():
    tracker = BudgetCeilingTracker(
        enabled=True,
        ceiling_usd=0.50,
        pricing_table={
            "anthropic/claude-sonnet-4-6": {
                "input_usd_per_mtok": 3.0,
                "output_usd_per_mtok": 15.0,
            }
        },
    )
    breakdown = tracker.record_call(
        provider_id="anthropic",
        model_id="claude-sonnet-4-6",
        tier="paid",
        cost_header=None,
        input_tokens=10_000,
        output_tokens=5_000,
    )
    assert breakdown.source == "fallback"
    assert tracker.session_cost_usd == pytest.approx(0.105)


def test_cost_breakdown_from_header_handles_garbage():
    assert CostBreakdown.from_header(None).cost_usd == 0.0
    assert CostBreakdown.from_header("").cost_usd == 0.0
    assert CostBreakdown.from_header("not-a-number").cost_usd == 0.0
    assert CostBreakdown.from_header("0.045").cost_usd == 0.045


def test_parse_slash_command_handles_args():
    assert parse_slash_command("/resume 0.50") == ("/resume", "0.50")
    assert parse_slash_command("/switch ollama/llama3.2") == ("/switch", "ollama/llama3.2")
    assert parse_slash_command("/resume") == ("/resume", "")
    assert parse_slash_command("hi") == ("", "")
