from __future__ import annotations

import sys
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b import loop  # noqa: E402


def test_tool_is_default_for_plain_text():
    config = loop._resolve_turn_config("please measure this image", None, None, False)

    assert config["mode"] == "tool"
    assert config["thinking"] is False
    assert config["sampling"] == {"temperature": 0.25, "top_p": 0.90, "top_k": 30}


def test_plan_mode_matches_decision_words():
    config = loop._resolve_turn_config("which method should I use?", None, None, False)

    assert config["mode"] == "plan"
    assert config["thinking"] is True


def test_explain_mode_matches_explain_words():
    config = loop._resolve_turn_config("why does this look noisy?", None, None, False)

    assert config["mode"] == "explain"
    assert config["thinking"] is False


def test_recipe_mode_matches_save_recipe_command():
    config = loop._resolve_turn_config("/save-recipe", None, None, False)

    assert config["mode"] == "recipe"
    assert config["thinking"] is False


def test_recover_mode_overrides_text_after_failure():
    config = loop._resolve_turn_config("which method should I use?", None, None, True)

    assert config["mode"] == "recover"
    assert config["thinking"] is True
    assert config["source"] == "recover"


def test_mode_lock_overrides_auto_mode():
    config = loop._resolve_turn_config("plain request", "plan", None, False)

    assert config["mode"] == "plan"
    assert config["source"] == "lock"


def test_think_lock_true_forces_thinking_on():
    config = loop._resolve_turn_config("plain request", None, True, False)

    assert config["mode"] == "tool"
    assert config["thinking"] is True


def test_think_lock_false_forces_thinking_off():
    config = loop._resolve_turn_config("which method should I use?", None, False, False)

    assert config["mode"] == "plan"
    assert config["thinking"] is False
