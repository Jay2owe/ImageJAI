"""Diff curated agent/providers/models.yaml against each provider's live /models.

Phase G — docs/multi_provider/02_curation_strategy.md §7.

Subcommands
-----------
* ``--diff``    Print new/missing/stale entries (default).
* ``--apply``   Append stub entries for newly-seen-upstream models so a curator
                can fill in description / tier without retyping the boilerplate.
* ``--check``   CI-friendly: exits non-zero if any curated entry is missing
                upstream (i.e. needs curator attention).
* ``--verbose`` Expand the new/missing lists rather than collapsing them.

The two providers without a live /models endpoint (Ollama Cloud, Perplexity)
are listed as warnings but never produce diffs.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import sys
from pathlib import Path
from typing import Any

import yaml

try:  # ``requests`` is the easiest way to talk to fifteen heterogeneous APIs.
    import requests  # type: ignore[import-untyped]
except Exception:  # pragma: no cover - exercised only on machines without requests
    requests = None  # type: ignore[assignment]


CURATED = Path(__file__).resolve().parent / "models.yaml"
STALE_DAYS = 90

# Endpoint catalogue — must match docs/multi_provider/02_curation_strategy.md §6.
# Two providers (ollama-cloud, perplexity) have no live endpoint and are skipped.
ENDPOINTS: dict[str, tuple[str, dict[str, str]]] = {
    "ollama": (
        "http://localhost:11434/api/tags",
        {},
    ),
    "openai": (
        "https://api.openai.com/v1/models",
        {"Authorization": "Bearer $OPENAI_API_KEY"},
    ),
    "anthropic": (
        "https://api.anthropic.com/v1/models",
        {"x-api-key": "$ANTHROPIC_API_KEY", "anthropic-version": "2023-06-01"},
    ),
    "gemini": (
        "https://generativelanguage.googleapis.com/v1beta/models?key=$GEMINI_API_KEY",
        {},
    ),
    "groq": (
        "https://api.groq.com/openai/v1/models",
        {"Authorization": "Bearer $GROQ_API_KEY"},
    ),
    "cerebras": (
        "https://api.cerebras.ai/v1/models",
        {"Authorization": "Bearer $CEREBRAS_API_KEY"},
    ),
    "openrouter": (
        "https://openrouter.ai/api/v1/models",
        {},
    ),
    "github-models": (
        "https://models.github.ai/catalog/models",
        {"Authorization": "Bearer $GITHUB_TOKEN"},
    ),
    "mistral": (
        "https://api.mistral.ai/v1/models",
        {"Authorization": "Bearer $MISTRAL_API_KEY"},
    ),
    "together": (
        "https://api.together.xyz/v1/models",
        {"Authorization": "Bearer $TOGETHER_API_KEY"},
    ),
    "huggingface": (
        "https://router.huggingface.co/v1/models",
        {"Authorization": "Bearer $HF_TOKEN"},
    ),
    "deepseek": (
        "https://api.deepseek.com/v1/models",
        {"Authorization": "Bearer $DEEPSEEK_API_KEY"},
    ),
    "xai": (
        "https://api.x.ai/v1/models",
        {"Authorization": "Bearer $XAI_API_KEY"},
    ),
}

CURATED_ONLY: tuple[str, ...] = ("ollama-cloud", "perplexity")


def _expand_env(value: str) -> str:
    out = value
    while "$" in out:
        idx = out.index("$")
        end = idx + 1
        while end < len(out) and (out[end].isalnum() or out[end] == "_"):
            end += 1
        var = out[idx + 1 : end]
        if not var:
            break
        out = out[:idx] + os.environ.get(var, "") + out[end:]
    return out


def _expand_env_dict(headers: dict[str, str]) -> dict[str, str]:
    return {k: _expand_env(v) for k, v in headers.items()}


def load_curated() -> dict[tuple[str, str], dict[str, Any]]:
    raw = yaml.safe_load(CURATED.read_text(encoding="utf-8"))
    out: dict[tuple[str, str], dict[str, Any]] = {}
    for entry in (raw or {}).get("models", []) or []:
        provider = entry.get("provider")
        model_id = entry.get("model_id")
        if not provider or not model_id:
            continue
        out[(provider, model_id)] = entry
    return out


def fetch_live(provider: str, timeout: float = 10.0) -> set[str]:
    if requests is None:
        raise RuntimeError("python-requests is not installed; pip install requests")
    url, hdrs = ENDPOINTS[provider]
    expanded_url = _expand_env(url)
    expanded_headers = _expand_env_dict(hdrs)
    response = requests.get(expanded_url, headers=expanded_headers, timeout=timeout)
    response.raise_for_status()
    body = response.json()
    items = body.get("data") or body.get("models") or body
    if isinstance(items, dict):
        items = items.get("models", [])
    out: set[str] = set()
    for item in items or []:
        if not isinstance(item, dict):
            continue
        model_id = item.get("id") or item.get("name") or item.get("model")
        if not model_id:
            continue
        # Normalise Ollama "<name>" or Gemini "models/<name>"
        if isinstance(model_id, str) and model_id.startswith("models/"):
            model_id = model_id[len("models/") :]
        out.add(model_id)
    return out


def diff_provider(provider: str, curated: dict[tuple[str, str], dict[str, Any]],
                  live: set[str], today: _dt.date) -> dict[str, list[Any]]:
    cur_ids = {m for (p, m) in curated if p == provider}
    new = sorted(live - cur_ids)
    missing = sorted(cur_ids - live)
    stale: list[tuple[str, int]] = []
    for (p, m), entry in curated.items():
        if p != provider:
            continue
        last_verified = entry.get("last_verified")
        if not last_verified:
            continue
        try:
            verified_date = _dt.date.fromisoformat(str(last_verified))
        except (ValueError, TypeError):
            continue
        age = (today - verified_date).days
        if age > STALE_DAYS:
            stale.append((m, age))
    return {"new": new, "missing": missing, "stale": stale}


def print_report(report: dict[str, dict[str, list[Any]]], *, verbose: bool) -> None:
    today = _dt.date.today().isoformat()
    print(f"ImageJAI model audit — {today}")
    total_new = total_missing = total_stale = 0
    for provider in sorted(report):
        block = report[provider]
        new = block.get("new", [])
        missing = block.get("missing", [])
        stale = block.get("stale", [])
        total_new += len(new)
        total_missing += len(missing)
        total_stale += len(stale)
        if not (new or missing or stale):
            continue
        print(f"\n[{provider}]")
        if new:
            if verbose or len(new) <= 6:
                for m in new:
                    print(f"  + new since last audit: {m}")
            else:
                print(f"  + {len(new)} new models since last audit (use --verbose to list)")
        if missing:
            if verbose or len(missing) <= 6:
                for m in missing:
                    print(f"  - missing since last audit: {m}")
            else:
                print(f"  - {len(missing)} missing since last audit (use --verbose to list)")
        if stale:
            for (m, age) in stale:
                print(f"  ! stale curation (>{STALE_DAYS} days): {m}  (age {age}d)")
    for provider in CURATED_ONLY:
        print(f"\n[{provider}]")
        print("  ! no live endpoint — curated entries assumed authoritative")
    print(f"\nSummary: {total_new} new, {total_missing} missing, {total_stale} stale.")


def append_stubs(report: dict[str, dict[str, list[Any]]], today: _dt.date) -> int:
    appended = 0
    if not any(report[p].get("new") for p in report):
        return 0
    with CURATED.open("a", encoding="utf-8") as fh:
        fh.write("\n")
        fh.write(f"  # ===== Stubs added by audit_models.py on {today.isoformat()} =====\n")
        for provider in sorted(report):
            for model_id in report[provider].get("new", []):
                fh.write(
                    "  - provider: %s\n"
                    "    model_id: %s\n"
                    "    display_name: %s\n"
                    "    description: >\n"
                    "      Auto-detected stub. Curator: fill description / tier.\n"
                    "    tier: free-with-limits\n"
                    "    context_window: 0\n"
                    "    vision_capable: false\n"
                    "    tool_call_reliability: low\n"
                    "    last_verified: %s\n"
                    "    pinned: false\n"
                    "    curated: false\n"
                    "    harness: tool_loop\n"
                    "    family: other\n"
                    "    context_size: large\n\n"
                    % (provider, model_id, model_id, today.isoformat())
                )
                appended += 1
    return appended


def run_audit(*, verbose: bool = False) -> dict[str, dict[str, list[Any]]]:
    today = _dt.date.today()
    curated = load_curated()
    report: dict[str, dict[str, list[Any]]] = {}
    for provider in ENDPOINTS:
        try:
            live = fetch_live(provider)
        except Exception as exc:  # pragma: no cover - exercised manually
            print(f"[{provider}] fetch failed: {exc}", file=sys.stderr)
            continue
        report[provider] = diff_provider(provider, curated, live, today)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diff", action="store_true", help="show diff (default)")
    parser.add_argument("--apply", action="store_true", help="append stubs to models.yaml")
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero if any curated entry missing upstream (CI mode)",
    )
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--report-json",
        type=Path,
        default=None,
        help="write the raw diff as JSON for downstream consumers",
    )
    args = parser.parse_args(argv)

    today = _dt.date.today()
    report = run_audit(verbose=args.verbose)
    print_report(report, verbose=args.verbose)

    if args.report_json is not None:
        args.report_json.write_text(
            json.dumps(report, default=list, indent=2),
            encoding="utf-8",
        )

    if args.apply:
        added = append_stubs(report, today)
        print(f"\nAppended {added} stub entries to {CURATED.name}.")

    if args.check:
        for provider, block in report.items():
            if block.get("missing") or block.get("stale"):
                return 1
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main(sys.argv[1:]))
