"""Console text normalization helpers for the Gemma agent."""

from __future__ import annotations

import re


_INLINE_LATEX_RE = re.compile(r"\$\\([A-Za-z]+)\$")
_INLINE_LATEX_SYMBOLS = {
    "to": "\u2192",
    "rightarrow": "\u2192",
    "Rightarrow": "\u21d2",
    "leftarrow": "\u2190",
    "Leftarrow": "\u21d0",
    "leftrightarrow": "\u2194",
    "Leftrightarrow": "\u21d4",
}


def normalize_inline_latex_symbols(text: str) -> str:
    """Replace simple inline LaTeX arrow commands with Unicode symbols."""
    if not text:
        return ""
    return _INLINE_LATEX_RE.sub(
        lambda match: _INLINE_LATEX_SYMBOLS.get(match.group(1), match.group(0)),
        text,
    )
