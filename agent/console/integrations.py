"""Stable console entry points for ImageJ Use and the physical test harness."""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


@dataclass(frozen=True)
class IntegrationStatus:
    available: bool
    detail: str


def imagej_use_status() -> IntegrationStatus:
    try:
        spec = importlib.util.find_spec("imagej_use.run")
    except (ImportError, ModuleNotFoundError):
        spec = None
    if spec is None:
        return IntegrationStatus(False, "missing from the bundled agent workspace")
    return IntegrationStatus(True, "built in; run `imagejai use --doctor`")


def harness_status() -> IntegrationStatus:
    try:
        spec = importlib.util.find_spec("imagej_plugin_test_harness.cli")
    except (ImportError, ModuleNotFoundError):
        spec = None
    if spec is not None:
        return IntegrationStatus(True, "Python package available")
    command = shutil.which("imagej-test-auto")
    if command:
        return IntegrationStatus(True, f"external sandbox runner: {command}")
    return IntegrationStatus(
        False,
        "install ImageJ Plugin Test Harness, then run `imagejai harness doctor`",
    )


def run_imagej_use(argv: Sequence[str]) -> int:
    from imagej_use.run import main

    return int(main(list(argv)))


def run_harness(argv: Sequence[str]) -> int:
    """Run the harness in-process when installed, or through its global CLI."""

    try:
        from imagej_plugin_test_harness.cli import main
    except ImportError:
        command = shutil.which("imagej-test-auto")
        if not command:
            print(
                "[imagejai] ImageJ Plugin Test Harness is not installed.\n"
                "Install it, then retry `imagejai harness doctor`.",
                file=sys.stderr,
            )
            return 2
        return int(subprocess.call([command, *argv]))
    return int(main(list(argv)))
