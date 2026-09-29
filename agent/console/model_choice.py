"""Models and effort levels selectable from the standalone console."""
from __future__ import annotations

import json
import os
from pathlib import Path

from .catalog import CatalogEntry, Tier

DEFAULT_EFFORT = "default"
_CLAUDE_LEVELS = ("low", "medium", "high", "xhigh", "max")
_NATIVE_LEVELS = ("low", "medium", "high", "xhigh")
_BUDGETS = {"low": 1024, "medium": 4096, "high": 8192, "xhigh": 16384}


def _codex_cache_path() -> Path:
    return Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex") / "models_cache.json"


def codex_models() -> list[dict]:
    """Use the installed Codex client's own model list when it is available."""
    path = _codex_cache_path()
    try:
        if path.stat().st_size > 4 * 1024 * 1024:
            return []
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    rows = data.get("models") if isinstance(data, dict) else None
    if not isinstance(rows, list):
        return []
    return [row for row in rows if isinstance(row, dict)
            and isinstance(row.get("slug"), str) and row["slug"]]


def subscription_entries() -> list[CatalogEntry]:
    """Make official CLI subscriptions selectable alongside API providers."""
    entries = [CatalogEntry(
        provider="codex-subscription", model_id="default",
        display_name="Codex default", tier=Tier.REQUIRES_SUBSCRIPTION,
        description="Use the model selected by the installed Codex client.",
    )]
    for row in codex_models():
        if str(row.get("visibility") or "list").lower() == "hide":
            continue
        levels = tuple(str(item.get("effort")) for item in
                       row.get("supported_reasoning_levels", [])
                       if isinstance(item, dict) and item.get("effort"))
        entries.append(CatalogEntry(
            provider="codex-subscription", model_id=row["slug"],
            display_name=str(row.get("display_name") or row["slug"]),
            tier=Tier.REQUIRES_SUBSCRIPTION,
            description=str(row.get("description") or ""),
            features={"effort_levels": levels},
        ))
    entries.append(CatalogEntry(
        provider="claude-subscription", model_id="default",
        display_name="Claude default", tier=Tier.REQUIRES_SUBSCRIPTION,
        description="Use the model selected by the installed Claude client.",
    ))
    for alias in ("opus", "sonnet", "haiku"):
        entries.append(CatalogEntry(
            provider="claude-subscription", model_id=alias,
            display_name=f"Claude {alias.title()}",
            tier=Tier.REQUIRES_SUBSCRIPTION,
            description="Official Claude model alias; the client selects its current version.",
            features={"effort_levels": _CLAUDE_LEVELS},
        ))
    from .installed_agents import installed_entries
    return entries + installed_entries()


def effort_levels(provider: str, model: str, features: dict | None = None) -> tuple[str, ...]:
    """Return only choices the selected route can actually forward."""
    if provider == "codex-subscription":
        levels = (features or {}).get("effort_levels")
        if not levels:
            row = next((r for r in codex_models() if r.get("slug") == model), None)
            levels = [item.get("effort") for item in
                      (row or {}).get("supported_reasoning_levels", [])
                      if isinstance(item, dict)]
        return tuple(dict.fromkeys(str(level) for level in (levels or _NATIVE_LEVELS)
                                   if level))
    if provider == "cline-cli":
        return ("none", "low", "medium", "high", "xhigh")
    if provider == "claude-subscription":
        return _CLAUDE_LEVELS
    if provider in {"anthropic", "gemini"}:
        return _NATIVE_LEVELS
    if provider == "openai":
        return _NATIVE_LEVELS
    return ()


def normalise_effort(provider: str, model: str, effort: str,
                     features: dict | None = None) -> str:
    chosen = str(effort or DEFAULT_EFFORT).strip().lower()
    if chosen == DEFAULT_EFFORT:
        return chosen
    if chosen not in effort_levels(provider, model, features):
        raise ValueError(f"{chosen} is not an available effort level for {provider} / {model}")
    return chosen


def api_effort_kwargs(provider: str, model: str, effort: str) -> dict[str, object]:
    chosen = normalise_effort(provider, model, effort)
    if chosen == DEFAULT_EFFORT:
        return {}
    if provider in {"anthropic", "gemini"}:
        return {"thinking_budget": _BUDGETS[chosen]}
    if provider == "openai":
        return {"reasoning_effort": chosen}
    return {}
