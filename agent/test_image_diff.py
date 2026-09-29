from __future__ import annotations

import importlib.util
import struct
import zlib
from pathlib import Path

import pytest


IMAGE_DIFF_PATH = Path(__file__).with_name("image_diff.py")
SPEC = importlib.util.spec_from_file_location("image_diff_under_test", IMAGE_DIFF_PATH)
image_diff = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(image_diff)


def _compare(monkeypatch, first, second, threshold=10):
    images = iter([(1, len(first), first), (1, len(second), second)])
    monkeypatch.setattr(image_diff, "_load_image", lambda _path: next(images))
    return image_diff.compare_images("first.png", "second.png", threshold=threshold)


def _png_bytes(width, height, raw, *, interlace=0):
    def chunk(kind, payload):
        body = kind + payload
        return (
            struct.pack(">I", len(payload))
            + body
            + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, interlace)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


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


def test_raw_fallback_rejects_adam7_instead_of_silently_misdecoding(tmp_path):
    # Valid Adam7 pass data for grayscale pixels [[10, 20], [30, 40]].
    adam7_rows = b"\x00\x0a\x00\x14\x00\x1e\x28"
    path = tmp_path / "adam7.png"
    path.write_bytes(_png_bytes(2, 2, adam7_rows, interlace=1))

    with pytest.raises(ValueError, match="Adam7"):
        image_diff._load_png_raw(path)


def test_raw_fallback_rejects_unknown_filter(tmp_path):
    path = tmp_path / "bad-filter.png"
    path.write_bytes(_png_bytes(1, 1, b"\x05\x2a"))

    with pytest.raises(ValueError, match="filter type"):
        image_diff._load_png_raw(path)


@pytest.mark.parametrize("raw", [b"\x00", b"\x00\x2a\x00"])
def test_raw_fallback_rejects_wrong_scanline_length(tmp_path, raw):
    path = tmp_path / "bad-length.png"
    path.write_bytes(_png_bytes(1, 1, raw))

    with pytest.raises(ValueError, match="scanline length"):
        image_diff._load_png_raw(path)
