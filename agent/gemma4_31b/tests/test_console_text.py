from __future__ import annotations

import sys
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b.console_text import normalize_inline_latex_symbols  # noqa: E402


def test_normalize_inline_latex_symbols_replaces_arrow_commands():
    text = "Blur $\\rightarrow$ threshold $\\Rightarrow$ measure"

    assert normalize_inline_latex_symbols(text) == "Blur \u2192 threshold \u21d2 measure"


def test_normalize_inline_latex_symbols_leaves_unknown_commands():
    text = "Keep $\\alpha$ literal"

    assert normalize_inline_latex_symbols(text) == text
