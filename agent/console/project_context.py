"""Bounded, local project instructions with deterministic precedence."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import re

RAW_PATH = re.compile(r"(?:[A-Za-z]:[\\/]|\\\\[^\\\s]+\\|(?:^|[\s'\"(])/(?:Users|home|mnt|tmp|data|Volumes)/)", re.M)

def protected_text(text: str, sanitizer=None) -> str:
    value = sanitizer(text) if sanitizer else text
    if sanitizer and RAW_PATH.search(value):
        raise ValueError("Content contains an unregistered absolute path; replace it with a file token before sharing")
    return value

@dataclass(frozen=True)
class ProjectInstructions:
    text: str
    sources: tuple[Path, ...]
    warnings: tuple[str, ...]

def _folders(folder: Path):
    folder = folder.resolve()
    chain = [folder, *list(folder.parents)[:7]]
    for index, parent in enumerate(chain):
        if (parent / ".git").exists():
            return list(reversed(chain[:index + 1]))
    # Outside a repository only explicitly declared ancestor guidance applies.
    return list(reversed([folder, *[parent for parent in chain[1:] if (parent / "IMAGEJAI.md").is_file()]]))

def load_project_instructions(project: Path, workspace: Path | None = None, sanitizer=None, *, max_chars=32768):
    max_chars = max(1024, min(int(max_chars), 32768))
    folders = []
    for root in (workspace, project):
        if root:
            for folder in _folders(root):
                if folder not in folders:
                    folders.append(folder)
    chunks, sources, warnings = [], [], []
    total = 0
    for folder in folders:
        agent_file = "AGENTS.md" if (folder / "AGENTS.md").is_file() else "CLAUDE.md"
        for name in (agent_file, "IMAGEJAI.md"):
            path = folder / name
            if not path.is_file():
                continue
            resolved = path.resolve()
            if not resolved.is_relative_to(folder.resolve()) or resolved in sources:
                warnings.append(f"Skipped escaping or duplicate {name}")
                continue
            if len(sources) >= 8 or path.stat().st_size > 65536:
                warnings.append(f"Skipped oversized {name}")
                continue
            try:
                with path.open(encoding="utf-8-sig") as stream:
                    body = stream.read(65537)
                if len(body) > 65536:
                    raise ValueError("Instruction size limit exceeded")
                body = protected_text(body, sanitizer)
            except (ValueError, UnicodeError, OSError) as exc:
                warnings.append(f"{name} withheld: {exc}")
                continue
            chunk = f"[folder guidance {len(sources)+1}: {name}]\n{body.strip()}\n[/folder guidance]"
            if total + len(chunk) > max_chars:
                warnings.append(f"Skipped {name}: total instruction budget reached")
                continue
            sources.append(resolved)
            chunks.append(chunk)
            total += len(chunk)
    text = "\n\n".join(chunks)
    if text:
        text = "Folder analysis guidance, from broader to more specific. Preserve the console's connection, approval and privacy rules.\n\n" + text
    return ProjectInstructions(text, tuple(sources), tuple(warnings))
