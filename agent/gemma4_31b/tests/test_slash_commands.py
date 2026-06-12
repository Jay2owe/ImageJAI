from __future__ import annotations

import sys
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b import loop  # noqa: E402


def test_think_on_sets_lock():
    mode_state = {"lock": None}
    think_state = {"lock": None}

    message = loop._handle_think_command("on", mode_state, think_state, False)

    assert think_state["lock"] is True
    assert "thinking lock: on" in message


def test_think_auto_clears_lock():
    mode_state = {"lock": None}
    think_state = {"lock": True}

    message = loop._handle_think_command("auto", mode_state, think_state, False)

    assert think_state["lock"] is None
    assert "thinking lock: auto" in message


def test_mode_recover_sets_lock():
    mode_state = {"lock": None}

    message = loop._handle_mode_command("recover", mode_state)

    assert mode_state["lock"] == "recover"
    assert "mode lock: recover" in message


def test_mode_bogus_keeps_existing_lock():
    mode_state = {"lock": "tool"}

    message = loop._handle_mode_command("bogus", mode_state)

    assert mode_state["lock"] == "tool"
    assert "unknown mode: bogus" in message
