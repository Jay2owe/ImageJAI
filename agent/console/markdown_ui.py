"""Render model replies as readable terminal markdown.

The transcript escaped everything, so a returned ImageJ macro arrived as one
grey wall of text. Code must stand out and stay copyable, while ordinary prose
from a model is still untrusted input and must never be interpreted as Rich
markup.
"""
from __future__ import annotations

import re
from typing import Iterable

from rich.markup import escape as rich_escape

FENCE = re.compile(r"^\s*```([A-Za-z0-9_+-]*)\s*$")
INLINE_CODE = re.compile(r"`([^`\n]{1,200})`")
BOLD = re.compile(r"\*\*([^*\n]{1,200})\*\*")
BULLET = re.compile(r"^(\s*)([-*+])\s+(.*)$")
HEADING = re.compile(r"^(#{1,6})\s+(.*)$")

# ImageJ macro and Groovy are the languages this console actually sees.
LANGUAGE_LABELS = {
    "": "code", "ijm": "ImageJ macro", "imagej": "ImageJ macro",
    "macro": "ImageJ macro", "groovy": "Groovy", "python": "Python",
    "py": "Python", "java": "Java", "javascript": "JavaScript",
    "js": "JavaScript", "json": "JSON", "bash": "shell", "sh": "shell",
    "text": "text", "csv": "CSV", "yaml": "YAML",
}

MAX_CODE_LINES = 400


def _inline(text: str) -> str:
    """Escape untrusted text, then re-apply a small safe subset of markdown."""
    safe = rich_escape(text)
    safe = INLINE_CODE.sub(lambda m: f"[bold cyan]{m.group(1)}[/bold cyan]", safe)
    safe = BOLD.sub(lambda m: f"[bold]{m.group(1)}[/bold]", safe)
    return safe


def render_markdown(text: str) -> str:
    """Return Rich markup for one model reply.

    Everything is escaped first, so nothing a model writes can inject markup.
    Fenced blocks become bordered code with a language label and no markdown
    processing inside, because a macro may legitimately contain * and `.
    """
    if not text:
        return ""
    lines = str(text).splitlines()
    out: list[str] = []
    code: list[str] | None = None
    language = ""
    for raw in lines:
        fence = FENCE.match(raw)
        if fence is not None:
            if code is None:
                code, language = [], fence.group(1).lower()
            else:
                out.append(_code_block(code, language))
                code, language = None, ""
            continue
        if code is not None:
            code.append(raw)
            continue
        heading = HEADING.match(raw)
        if heading is not None:
            out.append(f"[bold]{_inline(heading.group(2))}[/bold]")
            continue
        bullet = BULLET.match(raw)
        if bullet is not None:
            indent = bullet.group(1)
            out.append(f"{indent}  [cyan]·[/cyan] {_inline(bullet.group(3))}")
            continue
        out.append(_inline(raw))
    if code is not None:
        # An unterminated fence is still code; show it rather than lose it.
        out.append(_code_block(code, language, truncated_fence=True))
    return "\n".join(out)


def _code_block(lines: Iterable[str], language: str, truncated_fence: bool = False) -> str:
    body = list(lines)
    dropped = 0
    if len(body) > MAX_CODE_LINES:
        dropped = len(body) - MAX_CODE_LINES
        body = body[:MAX_CODE_LINES]
    label = LANGUAGE_LABELS.get(language, language or "code")
    if truncated_fence:
        label += " (unclosed block)"
    rendered = [f"[dim]┌─ {rich_escape(label)}[/dim]"]
    for line in body:
        rendered.append(f"[dim]│[/dim] [bold white]{rich_escape(line)}[/bold white]")
    if dropped:
        rendered.append(f"[dim]│ … {dropped} more line(s) not shown[/dim]")
    rendered.append("[dim]└─[/dim]")
    return "\n".join(rendered)


def code_blocks(text: str) -> list[tuple[str, str]]:
    """Return ``(language, code)`` pairs, for copy or save actions."""
    blocks: list[tuple[str, str]] = []
    code: list[str] | None = None
    language = ""
    for raw in str(text or "").splitlines():
        fence = FENCE.match(raw)
        if fence is not None:
            if code is None:
                code, language = [], fence.group(1).lower()
            else:
                blocks.append((language, "\n".join(code)))
                code, language = None, ""
            continue
        if code is not None:
            code.append(raw)
    if code:
        blocks.append((language, "\n".join(code)))
    return blocks
