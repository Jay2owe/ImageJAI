"""Slash completion must find commands without sending partial text."""
from __future__ import annotations

from agent.console.slash_ui import matching_commands


def test_prefix_lists_builtins_and_command_files():
    commands = [("/model", "choose model"), ("/macro", "my command"),
                ("/help", "help"), ("/prompt analyse", "my command")]
    assert matching_commands("/mo", 3, commands) == commands[:1]
    assert matching_commands("/m", 2, commands) == commands[:2]
    assert matching_commands("/", 1, commands) == commands
    assert matching_commands("/prompt a", 9, commands) == commands[-1:]
    assert matching_commands("/model ", 7, commands) == []
    assert matching_commands("ask /mo", 7, commands) == []


def test_exact_command_wins_over_a_longer_prefix_and_survives_row_cap():
    commands = [("/skills", "list"), ("/skill", "load"), ("/rois", "manager"), ("/roi", "flash")]
    assert matching_commands("/skill", 6, commands)[0][0] == "/skill"
    assert matching_commands("/roi", 4, commands)[0][0] == "/roi"
    crowded = [(f"/test{i}", "") for i in range(12)] + [("/test", "")]
    assert matching_commands("/test", 5, crowded)[0][0] == "/test"
