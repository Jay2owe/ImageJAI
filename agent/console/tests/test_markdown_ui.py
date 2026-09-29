from __future__ import annotations

from agent.console.markdown_ui import code_blocks, render_markdown


def test_macro_code_block_is_bordered_and_not_markdown_processed():
    reply = "Run this:\n\n```ijm\nsetThreshold(100, 255);\nrun(\"Measure\");\n```\n\nDone."
    out = render_markdown(reply)
    assert "ImageJ macro" in out
    assert 'run(\\"Measure\\");' in out or 'run("Measure");' in out.replace("\\", "")
    assert "setThreshold(100, 255);" in out.replace("\\", "")
    assert "┌─" in out and "└─" in out


def test_model_text_cannot_inject_markup():
    out = render_markdown("danger [red]not a colour[/red] and [bold]no[/bold]")
    # Rich reads a backslash-escaped bracket as a literal, so no tag is live.
    assert "\\[red]" in out and "\\[/red]" in out
    assert "\\[bold]" in out
    from rich.text import Text
    assert "[red]" in Text.from_markup(out).plain


def test_inline_code_and_bold_and_bullets_render():
    out = render_markdown("- use `Otsu` first\n- then **measure**\n# Heading")
    assert "[bold cyan]Otsu[/bold cyan]" in out
    assert "[bold]measure[/bold]" in out
    assert "·" in out
    assert "[bold]Heading[/bold]" in out


def test_code_inside_fence_keeps_stars_and_backticks():
    out = render_markdown("```groovy\ndef x = a ** b // `note`\n```")
    assert "a ** b" in out
    assert "[bold]" not in out.split("┌─")[1].split("└─")[0].replace("[bold white]", "")


def test_unclosed_fence_is_shown_not_lost():
    out = render_markdown("```ijm\nrun(\"Close All\");")
    assert "unclosed block" in out
    assert "Close All" in out.replace("\\", "")


def test_long_code_is_bounded_with_a_notice():
    body = "\n".join(f"line{i}" for i in range(500))
    out = render_markdown(f"```\n{body}\n```")
    assert "more line(s) not shown" in out
    assert out.count("line") < 500


def test_code_blocks_extracts_language_and_source():
    blocks = code_blocks("text\n```ijm\nrun(\"Blobs (25K)\");\n```\nmore\n```groovy\nprintln 1\n```")
    assert [b[0] for b in blocks] == ["ijm", "groovy"]
    assert "Blobs (25K)" in blocks[0][1].replace("\\", "")
    assert blocks[1][1] == "println 1"
