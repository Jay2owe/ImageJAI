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
_VALID_FAMILIES = frozenset({
    "claude", "deepseek", "gemini", "gemma", "glm", "gpt", "gptoss",
    "grok", "kimi", "llama", "mistral", "other", "phi", "qwen",
})
_VALID_CONTEXT_SIZES = ("small", "medium", "large")
_VALID_RELIABILITY = ("high", "medium", "low")
_ORIGINAL_SAFE_LOAD = yaml.safe_load
_REGISTRY_CACHE_KEY: tuple | None = None
_REGISTRY_CACHE: dict[tuple[str, str], dict] | None = None


def _read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(
            f"context overlay missing: {path} (relative to agent/contexts/)"
        )
    return path.read_text(encoding="utf-8").rstrip()


def load_registry() -> dict[tuple[str, str], dict]:
    """Load and exhaustively validate the context fields in the registry.

    Validation is deliberately global: asking for one model must not conceal a
    broken family or harness declaration elsewhere in the single source of
    truth. Family names are an explicit allow-list, so adding an accidentally
    misspelled overlay file cannot make a typo valid.
    """
    global _REGISTRY_CACHE_KEY, _REGISTRY_CACHE
    cacheable = yaml.safe_load is _ORIGINAL_SAFE_LOAD
    stat = REGISTRY.stat()
    overlay_paths = sorted((CTX_DIR / "harness").glob("*.md"))
    overlay_paths += sorted((CTX_DIR / "families").glob("*.md"))
    overlay_signature = tuple(
        (str(path), path.stat().st_mtime_ns, path.stat().st_size)
        for path in overlay_paths
    )
    cache_key = (
        str(REGISTRY), stat.st_mtime_ns, stat.st_size, str(CTX_DIR),
        overlay_signature,
    )
    if (cacheable and _REGISTRY_CACHE_KEY == cache_key
            and _REGISTRY_CACHE is not None):
        return dict(_REGISTRY_CACHE)

    cfg = yaml.safe_load(REGISTRY.read_text(encoding="utf-8"))
    models = cfg.get("models") if isinstance(cfg, dict) else None
    if not isinstance(models, list):
        raise ValueError("model registry must contain a 'models' list")

    by_key: dict[tuple[str, str], dict] = {}
    for index, spec in enumerate(models):
        if not isinstance(spec, dict):
            raise ValueError(f"models[{index}] must be a mapping")
        provider = spec.get("provider")
        model_id = spec.get("model_id")
        if not isinstance(provider, str) or not provider.strip():
            raise ValueError(f"models[{index}]: provider must be a non-empty string")
        if not isinstance(model_id, str) or not model_id.strip():
            raise ValueError(f"models[{index}]: model_id must be a non-empty string")
        key = (provider, model_id)
        label = f"{provider}/{model_id}"
        if key in by_key:
            raise ValueError(f"duplicate model id: {label}")

        harness = spec.get("harness")
        if harness not in _VALID_HARNESSES:
            raise ValueError(
                f"{label}: harness must be one of {_VALID_HARNESSES}, got {harness!r}"
            )
        family = spec.get("family")
        if family not in _VALID_FAMILIES:
            raise ValueError(
                f"{label}: family must be one of {sorted(_VALID_FAMILIES)}, "
                f"got {family!r}"
            )
        context_size = spec.get("context_size")
        if context_size not in _VALID_CONTEXT_SIZES:
            raise ValueError(
                f"{label}: context_size must be one of {_VALID_CONTEXT_SIZES}, "
                f"got {context_size!r}"
            )
        reliability = spec.get("tool_call_reliability")
        if reliability not in _VALID_RELIABILITY:
            raise ValueError(
                f"{label}: tool_call_reliability must be one of "
                f"{_VALID_RELIABILITY}, got {reliability!r}"
            )

        _read(CTX_DIR / "harness" / f"{harness}.md")
        _read(CTX_DIR / "families" / f"{family}.md")
        by_key[key] = spec
    if cacheable:
        _REGISTRY_CACHE_KEY = cache_key
        _REGISTRY_CACHE = dict(by_key)
    return by_key


def load_context(model_id: str) -> str:
    """Compose the full agent context for ``model_id`` ("provider/model_id")."""
    provider, _, mid = model_id.partition("/")
    if not provider or not mid:
        raise KeyError(f"model_id must be 'provider/model_id', got {model_id!r}")
    by_key = load_registry()
    spec = by_key.get((provider, mid))
    if spec is None:
        raise KeyError(f"unknown model id: {model_id}")

    harness = spec.get("harness")
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
    family = spec["family"]
    parts.append(_read(CTX_DIR / "families" / f"{family}.md"))
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
