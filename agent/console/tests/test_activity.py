"""Gemma's established visuals and event-driven activity choices."""
import pytest
from rich import get_console
from rich.text import Text

from agent.console import activity
from agent.gemma4_31b import presentation as gemma


@pytest.mark.parametrize("label", [activity.THINKING, activity.INSPECTING, activity.WRITING, activity.RUNNING])
def test_status_uses_exact_gemma_frames_colours_and_wave(label):
    for elapsed in (0.0, 0.5, 1.0, 2.5):
        original = Text.from_ansi(gemma._format_status_line(label, elapsed))
        rendered = activity.status_text(label, elapsed, 2)
        assert rendered.plain.startswith(original.plain)
        # ANSI colour spans survive the move into Textual's Rich renderer.
        for index in range(len(original.plain)):
            assert original.get_style_at_offset(get_console(), index) == rendered.get_style_at_offset(get_console(), index)
    assert activity.status_text(label, 0, 0).plain != activity.status_text(label, 0.5, 0).plain


def test_histogram_and_count_keep_their_gemma_colours():
    histogram = activity.tool_start_text("get_histogram", {})
    assert "▁▃█▃▁" in histogram.plain
    assert len({span.style for span in histogram.spans}) >= 5
    count = activity.tool_start_text("quick_object_count", {})
    assert "∑" in count.plain
    assert count.get_style_at_offset(get_console(), count.plain.index("∑")).color.triplet == (88, 224, 255)


def test_script_args_keep_newlines_and_literal_markup():
    code = 'run("Blobs");\nprint("[red]literal[/red]");'
    rendered = activity.tool_start_text("run_macro", {"code": code})
    assert 'run("Blobs");\n' in rendered.plain
    assert '[red]literal[/red]' in rendered.plain
    assert "\\n" not in rendered.plain
    assert "🪄" in rendered.plain
    assert activity.tool_result_text("run_macro", False, "failure").style == "red"


@pytest.mark.parametrize("name,args,label", [
    ("get_state", {}, activity.INSPECTING),
    ("capture_image", {}, activity.INSPECTING),
    ("execute_macro", {}, activity.RUNNING),
    ("mcp__imagejai__run_script", {}, activity.RUNNING),
    ("Shell", {"command": 'python -c "import ij; ij.get_histogram()"'}, activity.INSPECTING),
    ("Bash", {"command": "ij.get_state(); ij.execute_macro(code)"}, activity.RUNNING),
    ("Shell", {"command": "ij.send('execute_macro', code=code)"}, activity.RUNNING),
    ("Bash", {"command": "git status"}, "Running command"),
    ("Read", {"file_path": "notes.txt"}, "Reading"),
    ("Edit", {}, "Editing files"),
])
def test_activity_reflects_the_actual_tool(name, args, label):
    assert activity.tool_activity(name, args) == label
