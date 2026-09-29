"""Load the console suggestion phrasebook.

The phrases are the same reviewed seeds used by the former Swing autocomplete,
but the console only uses them as editable prompt completions. Selecting one
never runs an action directly; Enter still sends it to the configured agent.
"""
from __future__ import annotations

from importlib.resources import files
from typing import Iterable

import yaml

from .rail import SuggestionEngine


def load_suggestion_engine() -> SuggestionEngine:
    """Load reviewed phrases shipped inside the console package."""
    source = files("agent.console").joinpath("intents.yaml").read_text(encoding="utf-8")
    rows = yaml.safe_load(source) or []
    phrases: list[tuple[str, str]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        intent_id = str(row.get("id") or "").strip()
        for phrase in row.get("seeds") or ():
            if intent_id and str(phrase).strip():
                phrases.append((str(phrase).strip(), intent_id))
    return SuggestionEngine(phrases)
