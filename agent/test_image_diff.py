from __future__ import annotations

import importlib.util
from pathlib import Path


IMAGE_DIFF_PATH = Path(__file__).with_name("image_diff.py")
SPEC = importlib.util.spec_from_file_location("image_diff_under_test", IMAGE_DIFF_PATH)
image_diff = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(image_diff)


def _compare(monkeypatch, first, second, threshold=10):
    images = iter([(1, len(first), first), (1, len(second), second)])
    monkeypatch.setattr(image_diff, "_load_image", lambda _path: next(images))
    return image_diff.compare_images("first.png", "second.png", threshold=threshold)


def test_equal_luminance_colour_change_is_detected(monkeypatch):
    result = _compare(
        monkeypatch,
        [(255, 0, 0, 255)],
        [(0, 255, 0, 255)],
    )

    assert result["identical"] is False
    assert result["changed_pixels_pct"] == 100.0
    assert result["mean_absolute_diff"] > 0


def test_equal_constant_images_have_perfect_correlation(monkeypatch):
    result = _compare(
        monkeypatch,
        [(20, 20, 20, 255)] * 4,
        [(20, 20, 20, 255)] * 4,
    )

    assert result["identical"] is True
    assert result["correlation"] == 1.0


def test_unequal_constant_images_do_not_report_perfect_correlation(monkeypatch):
    result = _compare(
        monkeypatch,
        [(20, 20, 20, 255)] * 4,
        [(30, 30, 30, 255)] * 4,
        threshold=0,
    )

    assert result["identical"] is False
    assert result["correlation"] == 0.0
    assert result["changed_pixels_pct"] == 100.0
