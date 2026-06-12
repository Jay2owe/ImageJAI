"""Regression tests for safety.check_macro's no-image vs pathless-image branch.

Bug (2026-06-10): a macro saving into AI_Exports/ while the Blobs sample was
open was rejected with "No image is open" — even though get_open_windows
listed blobs.gif. current_active_image() returns None both when nothing is
open AND when an open image simply has no on-disk path, so the branch that
should have said "the image IS open, set --export-dir" was unreachable dead
code. The fix routes the branch through active_image.is_any_image_open(),
which queries Fiji directly instead of inferring openness from the path cache.
"""

from __future__ import annotations

import sys
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b import active_image, safety  # noqa: E402


_SAVE_MACRO = 'run("Crop");\nsaveAs("Tiff", "AI_Exports/cropped_cell.tif");'


def test_pathless_open_image_is_not_reported_as_no_image(monkeypatch):
    # No export folder resolvable (sample image, no --export-dir) but an
    # image IS open in Fiji.
    monkeypatch.setattr(safety.active_image, "current_export_folder", lambda: None)
    monkeypatch.setattr(safety.active_image, "is_any_image_open", lambda: True)

    error = safety.check_macro(_SAVE_MACRO)

    assert error is not None
    assert "No image is open" not in error
    assert "The image IS open" in error
    assert "--export-dir" in error


def test_genuinely_no_image_reports_no_image(monkeypatch):
    monkeypatch.setattr(safety.active_image, "current_export_folder", lambda: None)
    monkeypatch.setattr(safety.active_image, "is_any_image_open", lambda: False)

    error = safety.check_macro(_SAVE_MACRO)

    assert error is not None
    assert "No image is open" in error


def test_macro_without_save_call_is_never_blocked(monkeypatch):
    # is_any_image_open / current_export_folder must not even be consulted
    # when there is no save site — a plain macro always passes.
    def _boom():
        raise AssertionError("export folder should not be queried without a save call")

    monkeypatch.setattr(safety.active_image, "current_export_folder", _boom)
    monkeypatch.setattr(safety.active_image, "is_any_image_open", _boom)

    assert safety.check_macro('run("Gaussian Blur...", "sigma=2");') is None


def test_is_any_image_open_reads_get_open_windows(monkeypatch):
    monkeypatch.setattr(
        active_image.registry,
        "send",
        lambda command, **kw: {"ok": True, "result": {"images": ["blobs.gif"]}},
    )
    assert active_image.is_any_image_open() is True


def test_is_any_image_open_false_on_empty_and_on_error(monkeypatch):
    monkeypatch.setattr(
        active_image.registry,
        "send",
        lambda command, **kw: {"ok": True, "result": {"images": []}},
    )
    assert active_image.is_any_image_open() is False

    def _raise(command, **kw):
        raise OSError("socket down")

    monkeypatch.setattr(active_image.registry, "send", _raise)
    assert active_image.is_any_image_open() is False
