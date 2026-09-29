"""User command files become bounded, editable console prompts."""
from __future__ import annotations

from pathlib import Path

import pytest

from agent.console.command_files import (
    CommandFileError, PromptCommand, discover_commands, load_prompt, match_command,
)


def test_discovery_includes_console_and_claude_files(tmp_path):
    console = tmp_path / "home" / "console" / "commands"
    console.mkdir(parents=True)
    (console / "check.md").write_text("Check the active image", encoding="utf-8")
    workspace = tmp_path / "agent"
    claude = workspace / ".claude" / "commands"
    claude.mkdir(parents=True)
    (claude / "review.md").write_text("Review $ARGUMENTS", encoding="utf-8")

    found = discover_commands("claude-subscription", "opus", workspace, tmp_path / "home")
    assert [(entry.source, entry.command) for entry in found.commands] == [
        ("Console", "/check"), ("Claude", "/review")]
    assert found.errors == ()
    selected, arguments = match_command("/review image A", found.commands)
    assert selected.source == "Claude" and arguments == "image A"
    assert load_prompt(selected, arguments) == "Review image A"


def test_prompt_file_metadata_is_removed_and_arguments_are_appended(tmp_path):
    root = tmp_path / "commands"
    root.mkdir()
    path = root / "measure.md"
    path.write_text("---\ndescription: Count cells\n---\nCount the cells.", encoding="utf-8")
    command = PromptCommand("/measure", path, root, "Console")
    assert load_prompt(command, "in the current image") == (
        "Count the cells.\n\nUser arguments: in the current image")


def test_prompt_file_cannot_read_outside_its_folder(tmp_path):
    root = tmp_path / "commands"
    root.mkdir()
    outside = tmp_path / "private.md"
    outside.write_text("private", encoding="utf-8")
    with pytest.raises(CommandFileError, match="outside"):
        load_prompt(PromptCommand("/private", outside, root, "Console"))


def test_prompt_file_limit_is_checked_before_submission(tmp_path):
    root = tmp_path / "commands"
    root.mkdir()
    path = root / "long.md"
    path.write_text("x" * 20, encoding="utf-8")
    with pytest.raises(CommandFileError, match="exceeds"):
        load_prompt(PromptCommand("/long", path, root, "Console"), max_chars=10)


def test_command_file_cannot_shadow_builtin_slash_command(tmp_path):
    folder = tmp_path / "console" / "commands"
    folder.mkdir(parents=True)
    (folder / "help.md").write_text("This should not replace help", encoding="utf-8")
    found = discover_commands(None, None, None, tmp_path,
                              reserved_commands=frozenset({"/help"}))
    assert found.commands == ()
    assert "conflicts" in found.errors[0]


def test_gemma_command_folder_is_offered_for_the_catalog_model(tmp_path):
    folder = (tmp_path / ".config" / "imagej-ai" / "gemma4_31b"
              / ".ccommands")
    folder.mkdir(parents=True)
    (folder / "compare.txt").write_text("Compare images", encoding="utf-8")
    found = discover_commands("ollama-cloud", "gemma4:31b-cloud", None,
                              tmp_path / "imagejai", home=tmp_path)
    assert [(item.source, item.command) for item in found.commands] == [
        ("Gemma", "/ccommands compare")]
