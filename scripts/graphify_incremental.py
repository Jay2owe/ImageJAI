#!/usr/bin/env python3
"""Run one bounded changed-path Graphify rebuild for the project hook.

Graphify 0.8.33 exposes changed-source eviction through the private
``graphify.watch._rebuild_code`` API, but not through its public ``update``
CLI.  This small subprocess adapter keeps that version-sensitive boundary out
of the hook scheduler and fails loudly if the API contract changes.
"""

from __future__ import annotations

import argparse
import inspect
import json
import sys
from pathlib import Path, PurePosixPath
from typing import Callable, Sequence


MAX_PATH_FILE_BYTES = 4_000_000
_REQUIRED_KEYWORDS = frozenset({"changed_paths", "force", "block_on_lock"})


def _normalise_relative_path(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("changed paths must be strings")
    text = value.strip().replace("\\", "/")
    candidate = PurePosixPath(text)
    if (
        not text
        or candidate.is_absolute()
        or any(part in ("", ".", "..") for part in candidate.parts)
        or (candidate.parts and candidate.parts[0].endswith(":"))
    ):
        raise ValueError("changed path must be a normalized repository-relative path")
    return candidate.as_posix()


def _load_paths(root: Path, path_file: Path) -> list[Path]:
    state = (root / ".git" / "graphify-hook-state").resolve()
    resolved = path_file.resolve()
    if state != resolved.parent and state not in resolved.parents:
        raise ValueError("changed-path file must be under .git/graphify-hook-state")
    size = resolved.stat().st_size
    if size <= 0 or size > MAX_PATH_FILE_BYTES:
        raise ValueError(
            "changed-path file size {} is outside the 1..{} byte limit".format(
                size, MAX_PATH_FILE_BYTES
            )
        )
    payload = json.loads(resolved.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or payload.get("version") != 1:
        raise ValueError("changed-path file has an unsupported schema")
    values = payload.get("paths")
    if not isinstance(values, list) or not values:
        raise ValueError("incremental rebuild requires at least one changed path")
    normalized = sorted({_normalise_relative_path(value) for value in values})
    return [Path(value) for value in normalized]


def _invoke_rebuild(
    root: Path,
    paths: Sequence[Path],
    rebuild: Callable[..., bool] | None = None,
) -> bool:
    if rebuild is None:
        try:
            from graphify.watch import _rebuild_code as rebuild
        except (ImportError, AttributeError) as error:
            raise RuntimeError(
                "Graphify incremental API is unavailable; expected "
                "graphify.watch._rebuild_code from Graphify 0.8.33"
            ) from error

    signature = inspect.signature(rebuild)
    missing = sorted(_REQUIRED_KEYWORDS.difference(signature.parameters))
    if missing:
        raise RuntimeError(
            "Graphify incremental API signature is incompatible; missing {} in {}".format(
                ", ".join(missing), signature
            )
        )
    return bool(
        rebuild(
            root,
            changed_paths=list(paths),
            force=True,
            block_on_lock=True,
        )
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--paths-file", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    root = args.root.resolve()
    try:
        paths = _load_paths(root, args.paths_file)
        success = _invoke_rebuild(root, paths)
    except (OSError, ValueError, RuntimeError, TypeError) as error:
        print("[graphify incremental] ERROR: {}".format(error), file=sys.stderr)
        return 2
    if not success:
        print("[graphify incremental] ERROR: Graphify rebuild returned false", file=sys.stderr)
        return 1
    print(
        "[graphify incremental] rebuilt {} changed source(s)".format(len(paths))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
