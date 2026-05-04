"""Regression: the curated models.yaml must carry pricing for entries whose
fallback ceiling math depends on it (verification report E.8 / risk #24).

The Opus 4.7 entry needs both ``pricing`` and ``tokenizer_multiplier``.
Without ``pricing``, ``load_pricing_from_models_yaml`` skips the entry
silently and the 1.35× multiplier becomes a no-op — the ceiling would fire
too late and the user is over-charged before the pause kicks in.

Lives under agent/providers/ rather than agent/ollama_agent/ because that
directory is intentionally untouchable for the multi-provider rollout — the
test guards the *yaml* file, which is owned here.
"""

from pathlib import Path

import pytest

from agent.ollama_agent.budget_ceiling import (
    BudgetCeilingTracker,
    load_pricing_from_models_yaml,
)


YAML_PATH = Path(__file__).resolve().parent / "models.yaml"


def test_real_models_yaml_carries_opus_47_pricing():
    table = load_pricing_from_models_yaml(str(YAML_PATH))
    entry = table.get("anthropic/claude-opus-4-7")
    assert entry is not None, (
        "Opus 4.7 must have a pricing block — without it the tokenizer "
        "multiplier never reaches BudgetCeilingTracker.estimate_fallback_usd."
    )
    assert entry["input_usd_per_mtok"] == pytest.approx(15.0)
    assert entry["output_usd_per_mtok"] == pytest.approx(75.0)
    assert entry["tokenizer_multiplier"] == pytest.approx(1.35)


def test_real_models_yaml_opus_47_fallback_applies_multiplier():
    """End-to-end: load the real yaml, run the fallback math, expect the
    multiplier to bake in a 35% bump on the base rate."""

    table = load_pricing_from_models_yaml(str(YAML_PATH))
    tracker = BudgetCeilingTracker(
        enabled=True, ceiling_usd=10.0, pricing_table=table)
    breakdown = tracker.estimate_fallback_usd(
        "anthropic", "claude-opus-4-7",
        input_tokens=10_000, output_tokens=5_000)
    # Base: 0.01M * $15 + 0.005M * $75 = $0.525
    # With 1.35x multiplier: $0.525 * 1.35 = $0.70875
    assert breakdown.cost_usd == pytest.approx(0.70875)
    assert breakdown.source == "fallback"
