"""Regression tests for side-effect-free default test discovery."""

from __future__ import annotations

import importlib.util
import os
import subprocess
import time
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[1]


def test_zero_polling_script_import_has_no_side_effects(monkeypatch):
    script = PROJECT_DIR / "test-scripts" / "zero_polling_test.py"
    environment_before = dict(os.environ)

    def unexpected(*_args, **_kwargs):
        raise AssertionError("zero_polling_test performed work during import")

    monkeypatch.setattr(subprocess, "Popen", unexpected)
    monkeypatch.setattr(subprocess, "run", unexpected)
    monkeypatch.setattr(subprocess, "check_output", unexpected)
    monkeypatch.setattr(time, "sleep", unexpected)
    monkeypatch.setattr(os, "remove", unexpected)

    spec = importlib.util.spec_from_file_location("imagejai_zero_polling_test", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    assert callable(module.main)
    assert dict(os.environ) == environment_before

