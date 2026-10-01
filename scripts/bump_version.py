#!/usr/bin/env python
"""Move ImageJAI to a new version in one step.

pom.xml is the single source: Java reads it at runtime through the filtered
version.properties resource. This script rewrites the few files that must
carry the number as text (the Python-side manifest, citation metadata and the
user documents), then regenerates the command documentation.

Usage:  python scripts/bump_version.py 0.6.2 [--date 2026-10-01] [--dry-run]
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEMVER = re.compile(r"\d+\.\d+\.\d+")
# Files whose every mention of the old version names this product's version.
TEXT_FILES = ("README.md", "docs/DEVELOPER.md", "docs/USER_GUIDE.md")


def current_version(root: Path) -> str:
    pom = (root / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<artifactId>imagej-ai</artifactId>\s*<version>([^<]+)</version>", pom)
    if not match:
        raise SystemExit("pom.xml: could not find the imagej-ai project version")
    return match.group(1).strip()


def _rewrite(root: Path, name: str, transform, changes: list[str], dry_run: bool) -> None:
    path = root / name
    raw = path.read_bytes().decode("utf-8")
    updated = transform(raw)
    if updated != raw:
        changes.append(name)
        if not dry_run:
            path.write_bytes(updated.encode("utf-8"))


def bump(root: Path, new: str, date: str, dry_run: bool = False) -> list[str]:
    if not SEMVER.fullmatch(new):
        raise SystemExit(f"version must look like 1.2.3: {new!r}")
    old = current_version(root)
    if old == new:
        raise SystemExit(f"already at {new}")
    changes: list[str] = []

    def pom(text: str) -> str:
        return re.sub(r"(<artifactId>imagej-ai</artifactId>\s*<version>)[^<]+(</version>)",
                      lambda m: m.group(1) + new + m.group(2), text, count=1)

    def manifest(text: str) -> str:
        return re.sub(r'("product_version":\s*")[^"]+(")',
                      lambda m: m.group(1) + new + m.group(2), text, count=1)

    def citation(text: str) -> str:
        text = re.sub(r'(?m)^(version:\s*")[^"]+(")', lambda m: m.group(1) + new + m.group(2), text)
        return re.sub(r"(?m)^(date-released:\s*)\S+", lambda m: m.group(1) + date, text)

    def docs(text: str) -> str:
        return text.replace(old, new)

    _rewrite(root, "pom.xml", pom, changes, dry_run)
    _rewrite(root, "agent/command_manifest.json", manifest, changes, dry_run)
    _rewrite(root, "CITATION.cff", citation, changes, dry_run)
    for name in TEXT_FILES:
        _rewrite(root, name, docs, changes, dry_run)

    if not dry_run:
        generator = root / "agent" / "generate_command_docs.py"
        if generator.is_file():
            subprocess.run([sys.executable, str(generator)], cwd=root, check=True)
    return [f"{old} -> {new}"] + changes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("version")
    parser.add_argument("--date", default=dt.date.today().isoformat(),
                        help="CITATION.cff date-released (default: today)")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    for line in bump(args.root.resolve(), args.version, args.date, args.dry_run):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
