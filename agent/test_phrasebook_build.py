"""Offline contract tests for tools/phrasebook_build.py."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "phrasebook_build.py"
SPEC = importlib.util.spec_from_file_location("phrasebook_build", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
phrasebook_build = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = phrasebook_build
SPEC.loader.exec_module(phrasebook_build)


def _write_inputs(tmp_path: Path) -> tuple[Path, Path]:
    intents = tmp_path / "intents.yaml"
    intents.write_text(
        "- id: generated.unique\n"
        "  description: Report a deliberately unique generated value\n"
        "  seeds: [\"generated unique value\"]\n",
        encoding="utf-8",
    )
    output = tmp_path / "phrasebook.json"
    output.write_text(
        json.dumps(
            {
                "version": 1,
                "intents": [
                    {
                        "id": "canonical.preserved",
                        "description": "A canonical entry absent from the YAML input",
                        "phrases": ["canonical preserved phrase"],
                    }
                ],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return intents, output


def _run(intents: Path, output: Path, *extra: str) -> int:
    return phrasebook_build.main(
        [
            "--intents-file",
            str(intents),
            "--output",
            str(output),
            "--provider",
            "mock",
            "--minimum",
            "1",
            *extra,
        ]
    )


def test_default_generation_preserves_ids_and_is_byte_stable(tmp_path: Path) -> None:
    intents, output = _write_inputs(tmp_path)

    assert _run(intents, output) == 0
    first = output.read_bytes()
    ids = phrasebook_build.intent_ids(json.loads(first.decode("utf-8")))
    assert ids == {"canonical.preserved", "generated.unique"}

    assert _run(intents, output) == 0
    assert output.read_bytes() == first


def test_pruning_requires_explicit_destructive_flag(tmp_path: Path) -> None:
    intents, output = _write_inputs(tmp_path)

    assert _run(intents, output, "--prune-unlisted") == 0
    ids = phrasebook_build.intent_ids(json.loads(output.read_text(encoding="utf-8")))
    assert ids == {"generated.unique"}


def test_preservation_failure_reports_exact_removed_ids() -> None:
    existing = {
        "version": 1,
        "intents": [
            {"id": "z.last", "description": "z", "phrases": ["z"]},
            {"id": "a.first", "description": "a", "phrases": ["a"]},
        ],
    }
    generated = {"version": 1, "intents": []}

    with pytest.raises(ValueError) as error:
        phrasebook_build.validate_id_preservation(existing, generated)

    assert str(error.value).endswith("a.first, z.last")
