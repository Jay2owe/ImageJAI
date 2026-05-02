"""Phase H budget ceiling — pauses the tool loop between iterations once
``session_cost_usd`` reaches a configured ceiling. Per
``docs/multi_provider/06_tier_safety.md §6``.

Design points:

* In-process state only (06 §6.5 — no telemetry, never persisted).
* Free-tier calls (🟢/🟡) contribute zero (06 §6.3).
* Reads LiteLLM's ``x-litellm-response-cost`` header per call. Falls back
  to local token-count × pricing when the header is absent or zero.
* Ceiling is checked between iterations, never mid-call (06 §6.6).
* The ``Decision`` returned by :meth:`BudgetCeilingTracker.preflight`
  tells the loop whether to continue, pause for ``/resume``, or switch
  models via ``/switch <model>``.

The Python side is the authoritative enforcer. ``CostHeaderListener`` on the
Java side is a long-running mirror used by the Swing UI.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable


# Tier classifications that contribute zero to the running session cost.
FREE_TIERS = frozenset({"free", "free-with-limits"})


@dataclass
class CostBreakdown:
    """One model call's contribution to the running session cost."""

    cost_usd: float
    source: str  # "header" | "fallback" | "free"

    @classmethod
    def free(cls) -> "CostBreakdown":
        return cls(cost_usd=0.0, source="free")

    @classmethod
    def from_header(cls, raw: str | None) -> "CostBreakdown":
        if raw is None or str(raw).strip() == "":
            return cls(cost_usd=0.0, source="fallback")
        try:
            return cls(cost_usd=float(raw), source="header")
        except (TypeError, ValueError):
            return cls(cost_usd=0.0, source="fallback")


@dataclass
class CeilingDecision:
    """What the agent loop should do next."""

    action: str  # "continue" | "pause" | "switch"
    message: str = ""
    new_model: str | None = None


@dataclass
class BudgetCeilingTracker:
    enabled: bool = False
    ceiling_usd: float = 1.00
    session_cost_usd: float = 0.0
    breached: bool = False
    pricing_table: dict[str, dict[str, float]] = field(default_factory=dict)

    def reset_session(self) -> None:
        self.session_cost_usd = 0.0
        self.breached = False

    def add_call_cost(
        self,
        cost_breakdown: CostBreakdown,
    ) -> float:
        """Accumulate one call's cost. Returns the new session total."""

        if cost_breakdown.cost_usd <= 0:
            return self.session_cost_usd
        self.session_cost_usd = round(self.session_cost_usd + cost_breakdown.cost_usd, 6)
        return self.session_cost_usd

    def estimate_fallback_usd(
        self,
        provider_id: str,
        model_id: str,
        input_tokens: int,
        output_tokens: int,
    ) -> CostBreakdown:
        """Compute cost from local token counts × per-million-token pricing.

        Used when LiteLLM does not surface the cost header — see 06 §6.3.
        """

        key = f"{provider_id}/{model_id}"
        pricing = self.pricing_table.get(key)
        if not pricing:
            return CostBreakdown(cost_usd=0.0, source="fallback")
        in_rate = float(pricing.get("input_usd_per_mtok", 0.0))
        out_rate = float(pricing.get("output_usd_per_mtok", 0.0))
        cost = (input_tokens / 1_000_000.0) * in_rate + (output_tokens / 1_000_000.0) * out_rate
        return CostBreakdown(cost_usd=cost, source="fallback")

    def record_call(
        self,
        *,
        provider_id: str,
        model_id: str,
        tier: str,
        cost_header: str | None = None,
        input_tokens: int = 0,
        output_tokens: int = 0,
    ) -> CostBreakdown:
        """Record one model call's contribution to the running total.

        Free-tier calls contribute 0 regardless of token count (06 §6.3).
        """

        if (tier or "").strip().lower() in FREE_TIERS:
            self.add_call_cost(CostBreakdown.free())
            return CostBreakdown.free()
        breakdown = CostBreakdown.from_header(cost_header)
        if breakdown.cost_usd <= 0 and (input_tokens or output_tokens):
            breakdown = self.estimate_fallback_usd(
                provider_id, model_id, input_tokens, output_tokens
            )
        self.add_call_cost(breakdown)
        return breakdown

    def preflight(self) -> CeilingDecision:
        """Run between iterations. Returns 'pause' if the ceiling is breached.

        Per 06 §6.6 the ceiling fires *between* tool-loop iterations, not
        mid-call — the in-flight HTTP request will complete before the next
        check.
        """

        if not self.enabled:
            return CeilingDecision(action="continue")
        if self.session_cost_usd >= self.ceiling_usd:
            self.breached = True
            return CeilingDecision(
                action="pause",
                message=self._pause_message(),
            )
        return CeilingDecision(action="continue")

    def _pause_message(self) -> str:
        # 06 §6.3 canonical pause text.
        return (
            "─────────────────────────────────────────────────────────────────\n"
            f"⏸  Budget ceiling reached: ${self.session_cost_usd:.2f} of ${self.ceiling_usd:.2f}.\n\n"
            "   The agent has paused. Your last action completed; nothing\n"
            "   was charged that you didn't already authorise.\n\n"
            "   Options:\n"
            "     · Type /resume to continue with a higher ceiling\n"
            "     · Type /switch <model> to drop to a free model\n"
            "     · Close this terminal to end the session\n"
            "─────────────────────────────────────────────────────────────────"
        )

    def handle_resume(self, new_ceiling_usd: float | None = None) -> CeilingDecision:
        """Raise the ceiling rather than reset it (06 §6.3 step 5)."""

        if new_ceiling_usd is None or new_ceiling_usd <= self.ceiling_usd:
            new_ceiling_usd = self.ceiling_usd * 2
        self.ceiling_usd = new_ceiling_usd
        self.breached = False
        return CeilingDecision(
            action="continue",
            message=f"Ceiling raised to ${self.ceiling_usd:.2f}; continuing.",
        )

    def handle_switch(self, new_model: str) -> CeilingDecision:
        """Swap to a (presumably free-tier) model. Caller mutates the loop's model."""

        if not new_model:
            return CeilingDecision(action="pause", message="/switch needs a model id")
        return CeilingDecision(
            action="switch",
            message=f"Switching to {new_model}.",
            new_model=new_model,
        )


def parse_slash_command(line: str) -> tuple[str, str]:
    """Split ``/cmd arg`` into ``(cmd, arg)``. Empty arg when no whitespace."""

    if not line or not line.startswith("/"):
        return "", ""
    parts = line.strip().split(None, 1)
    cmd = parts[0]
    arg = parts[1] if len(parts) > 1 else ""
    return cmd, arg


def process_pause_input(
    tracker: BudgetCeilingTracker,
    user_line: str,
) -> CeilingDecision:
    """Resolve user input received while the loop is paused at the ceiling."""

    cmd, arg = parse_slash_command(user_line)
    if cmd == "/resume":
        try:
            new_ceiling = float(arg) if arg else None
        except ValueError:
            new_ceiling = None
        return tracker.handle_resume(new_ceiling)
    if cmd == "/switch":
        return tracker.handle_switch(arg)
    return CeilingDecision(
        action="pause",
        message=("Unrecognised command. Use /resume [new_ceiling] "
                 "or /switch <model>."),
    )


# A callable used by tests to install pricing fallbacks dynamically.
PricingLoader = Callable[[], dict[str, dict[str, float]]]


def load_pricing_from_models_yaml(path: str) -> dict[str, dict[str, float]]:
    """Read pricing entries from the unified models.yaml.

    Returns a dict keyed by ``provider/model_id`` mapping to
    ``{input_usd_per_mtok, output_usd_per_mtok}``. Entries without pricing are
    skipped silently.
    """

    import yaml  # local import — only used by the fallback path.
    with open(path, "r", encoding="utf-8") as fh:
        raw = yaml.safe_load(fh) or {}
    out: dict[str, dict[str, float]] = {}
    for entry in raw.get("models", []) or []:
        provider = (entry.get("provider") or "").strip()
        model_id = (entry.get("model_id") or "").strip()
        pricing = entry.get("pricing")
        if not provider or not model_id or not isinstance(pricing, dict):
            continue
        try:
            in_rate = float(pricing.get("input_usd_per_mtok", 0.0))
            out_rate = float(pricing.get("output_usd_per_mtok", 0.0))
        except (TypeError, ValueError):
            continue
        out[f"{provider}/{model_id}"] = {
            "input_usd_per_mtok": in_rate,
            "output_usd_per_mtok": out_rate,
        }
    return out
