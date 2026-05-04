"""Compose per-model agent context from base + overlay files.

Reads agent/providers/models.yaml; composes
base + harness + capabilities + reliability + family overlays
keyed off each entry's `harness`, `vision_capable`, `context_size`,
`tool_call_reliability`, and `family` fields. See
docs/multi_provider/04_context_strategy.md for the design.
"""
from __future__ import annotations

from pathlib import Path

import yaml

CTX_DIR = Path(__file__).parent
REGISTRY = CTX_DIR.parent / "providers" / "models.yaml"
SEPARATOR = "\n\n---\n\n"
_VALID_HARNESSES = ("cli_shell", "tool_loop")


def _read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(
            f"context overlay missing: {path} (relative to agent/contexts/)"
        )
    return path.read_text(encoding="utf-8").rstrip()


def load_context(model_id: str) -> str:
    """Compose the full agent context for ``model_id`` ("provider/model_id")."""
    cfg = yaml.safe_load(REGISTRY.read_text(encoding="utf-8"))
    provider, _, mid = model_id.partition("/")
    if not provider or not mid:
        raise KeyError(f"model_id must be 'provider/model_id', got {model_id!r}")
    by_key = {(e["provider"], e["model_id"]): e for e in cfg["models"]}
    spec = by_key.get((provider, mid))
    if spec is None:
        raise KeyError(f"unknown model id: {model_id}")

    harness = spec.get("harness")
    if harness not in _VALID_HARNESSES:
        raise ValueError(
            f"{model_id}: harness must be one of {_VALID_HARNESSES}, got {harness!r}"
        )

    parts = [_read(CTX_DIR / "base.md"),
             _read(CTX_DIR / "harness" / f"{harness}.md")]
    if not spec.get("vision_capable", True):
        parts.append(_read(CTX_DIR / "capabilities" / "no_vision.md"))
    if spec.get("context_size") == "small":
        parts.append(_read(CTX_DIR / "capabilities" / "small_context.md"))
    rel = spec.get("tool_call_reliability", "high")
    if rel == "medium":
        parts.append(_read(CTX_DIR / "reliability" / "good_tools.md"))
    elif rel == "low":
        parts.append(_read(CTX_DIR / "reliability" / "weak_tools.md"))
    family = spec.get("family")
    if family:
        family_path = CTX_DIR / "families" / f"{family}.md"
        if family_path.exists():
            parts.append(family_path.read_text(encoding="utf-8").rstrip())
    return SEPARATOR.join(parts) + "\n"


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 2:
        sys.stderr.write("usage: python -m agent.contexts.loader <provider>/<model_id>\n")
        raise SystemExit(2)
    # Composed context contains characters (μ, em-dash, …) that the default
    # Windows cp1252 console codec rejects. Reconfigure stdout to utf-8.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    print(load_context(sys.argv[1]), end="")
