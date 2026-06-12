"""Phase H Python-side acceptance tests for the budget ceiling.

Mirrors docs/multi_provider/06_tier_safety.md §6 — pause-between-iterations,
free-tier exemption, /resume raises rather than resets, fallback pricing path.
"""
from __future__ import annotations

import pytest

from agent.ollama_agent.budget_ceiling import (
    BudgetCeilingTracker,
    CostBreakdown,
    load_pricing_from_models_yaml,
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


def test_fallback_applies_opus_47_tokenizer_multiplier():
    """Verification report E.8 — Opus 4.7 consumes ~35% more tokens than 4.5.

    The fallback path must scale the local token count by tokenizer_multiplier
    before pricing math, otherwise the ceiling fires too late.
    """

    tracker = BudgetCeilingTracker(
        enabled=True,
        ceiling_usd=1.00,
        pricing_table={
            "anthropic/claude-opus-4-7": {
                "input_usd_per_mtok": 15.0,
                "output_usd_per_mtok": 75.0,
                "tokenizer_multiplier": 1.35,
            }
        },
    )
    breakdown = tracker.estimate_fallback_usd(
        "anthropic", "claude-opus-4-7",
        input_tokens=10_000,
        output_tokens=5_000,
    )
    # Without multiplier: 0.01M*$15 + 0.005M*$75 = $0.150 + $0.375 = $0.525
    # With 1.35x multiplier: $0.525 * 1.35 = $0.70875
    assert breakdown.cost_usd == pytest.approx(0.70875)
    assert breakdown.source == "fallback"


def test_fallback_no_multiplier_defaults_to_1x():
    """Models without the tokenizer_multiplier field stay at 1.0 (no scaling)."""

    tracker = BudgetCeilingTracker(
        enabled=True,
        ceiling_usd=1.00,
        pricing_table={
            "anthropic/claude-sonnet-4-6": {
                "input_usd_per_mtok": 3.0,
                "output_usd_per_mtok": 15.0,
                # tokenizer_multiplier intentionally absent — Sonnet 4.6 has the
                # 4.5 tokenizer.
            }
        },
    )
    breakdown = tracker.estimate_fallback_usd(
        "anthropic", "claude-sonnet-4-6",
        input_tokens=10_000,
        output_tokens=5_000,
    )
    # 0.01M*$3 + 0.005M*$15 = $0.03 + $0.075 = $0.105 (unchanged from base test)
    assert breakdown.cost_usd == pytest.approx(0.105)


def test_fallback_invalid_multiplier_falls_back_to_1x():
    tracker = BudgetCeilingTracker(
        enabled=True,
        ceiling_usd=1.00,
        pricing_table={
            "x/y": {
                "input_usd_per_mtok": 1.0,
                "output_usd_per_mtok": 1.0,
                "tokenizer_multiplier": 0.0,  # silly value — must clamp to 1.0
            }
        },
    )
    breakdown = tracker.estimate_fallback_usd("x", "y",
                                              input_tokens=1_000_000,
                                              output_tokens=0)
    assert breakdown.cost_usd == pytest.approx(1.0)


def test_load_pricing_from_models_yaml_picks_up_multiplier(tmp_path):
    yaml_text = """
models:
  - provider: anthropic
    model_id: claude-opus-4-7
    pricing:
      input_usd_per_mtok: 15.0
      output_usd_per_mtok: 75.0
    tokenizer_multiplier: 1.35
  - provider: anthropic
    model_id: claude-sonnet-4-6
    pricing:
      input_usd_per_mtok: 3.0
      output_usd_per_mtok: 15.0
"""
    yaml_path = tmp_path / "models.yaml"
    yaml_path.write_text(yaml_text, encoding="utf-8")
    table = load_pricing_from_models_yaml(str(yaml_path))
    assert table["anthropic/claude-opus-4-7"]["tokenizer_multiplier"] == 1.35
    # Default 1.0 when the field is absent.
    assert table["anthropic/claude-sonnet-4-6"]["tokenizer_multiplier"] == 1.0
