#!/usr/bin/env python
"""
Compare two captured images and compute difference metrics.

Takes two PNG file paths and computes pixel difference percentage and
structural similarity (correlation coefficient). Can save a visual diff.

Uses PIL if available, otherwise falls back to raw PNG parsing via stdlib.

Usage:
    from image_diff import compare_images
    result = compare_images(".tmp/before.png", ".tmp/after.png")
    print(result)  # {"changed_pixels_pct": 12.3, "correlation": 0.95, "identical": False}
"""

import math
import os
import struct
import zlib

TMP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp")


# ---------------------------------------------------------------------------
# Image loading: PIL preferred, raw PNG fallback
# ---------------------------------------------------------------------------

_HAS_PIL = False
try:
    from PIL import Image
    _HAS_PIL = True
except ImportError:
    pass


def _load_png_raw(path):
    """Parse a PNG file using only stdlib. Returns (width, height, pixels).

    pixels is a flat list of grayscale values (averaged across RGB channels).
    This is a minimal parser — handles 8-bit RGB/RGBA/grayscale PNGs.
    """
    with open(path, "rb") as f:
        data = f.read()

    # Verify PNG signature
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Not a valid PNG file: %s" % path)

    # Parse chunks
    pos = 8
    ihdr = None
    idat_chunks = []

    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        chunk_type = data[pos + 4:pos + 8]
        chunk_data = data[pos + 8:pos + 8 + length]
        pos += 12 + length  # 4 len + 4 type + data + 4 crc

        if chunk_type == b"IHDR":
            width = struct.unpack(">I", chunk_data[0:4])[0]
            height = struct.unpack(">I", chunk_data[4:8])[0]
            bit_depth = chunk_data[8]
            color_type = chunk_data[9]
            ihdr = (width, height, bit_depth, color_type)
        elif chunk_type == b"IDAT":
            idat_chunks.append(chunk_data)
        elif chunk_type == b"IEND":
            break

    if ihdr is None:
        raise ValueError("No IHDR chunk found in: %s" % path)

    width, height, bit_depth, color_type = ihdr
    if bit_depth != 8:
        raise ValueError("Only 8-bit PNGs supported (got %d-bit)" % bit_depth)

    # Decompress pixel data
    raw = zlib.decompress(b"".join(idat_chunks))

    # Determine bytes per pixel
    if color_type == 0:    # grayscale
        bpp = 1
    elif color_type == 2:  # RGB
        bpp = 3
    elif color_type == 4:  # grayscale + alpha
        bpp = 2
    elif color_type == 6:  # RGBA
        bpp = 4
    else:
        raise ValueError("Unsupported color type: %d" % color_type)

    stride = 1 + width * bpp  # 1 byte filter + pixel data per row

    # Reconstruct pixels (handle filter type 0=None, 1=Sub, 2=Up only)
    pixels = []
    prev_row = [0] * (width * bpp)

    for y in range(height):
        row_start = y * stride
        filter_type = raw[row_start]
        row_data = list(raw[row_start + 1:row_start + stride])

        # Apply PNG filters
        if filter_type == 1:  # Sub
            for i in range(len(row_data)):
                left = row_data[i - bpp] if i >= bpp else 0
                row_data[i] = (row_data[i] + left) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(len(row_data)):
                row_data[i] = (row_data[i] + prev_row[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(len(row_data)):
                left = row_data[i - bpp] if i >= bpp else 0
                up = prev_row[i]
                row_data[i] = (row_data[i] + (left + up) // 2) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(len(row_data)):
                left = row_data[i - bpp] if i >= bpp else 0
                up = prev_row[i]
                up_left = prev_row[i - bpp] if i >= bpp else 0
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                if pa <= pb and pa <= pc:
                    pred = left
                elif pb <= pc:
                    pred = up
                else:
                    pred = up_left
                row_data[i] = (row_data[i] + pred) & 0xFF

        # Convert to grayscale values
        for x in range(width):
            offset = x * bpp
            if color_type == 0:  # grayscale
                pixels.append(row_data[offset])
            elif color_type == 2:  # RGB
                r, g, b = row_data[offset], row_data[offset + 1], row_data[offset + 2]
                pixels.append((r + g + b) // 3)
            elif color_type == 4:  # grayscale + alpha
                pixels.append(row_data[offset])
            elif color_type == 6:  # RGBA
                r, g, b = row_data[offset], row_data[offset + 1], row_data[offset + 2]
                pixels.append((r + g + b) // 3)

        prev_row = row_data

    return width, height, pixels


def _load_image(path):
    """Load image and return (width, height, grayscale_pixels_list).

    Uses PIL if available, otherwise raw PNG parsing.
    """
    if _HAS_PIL:
        img = Image.open(path).convert("L")
        w, h = img.size
        pixels = list(img.getdata())
        return w, h, pixels
    else:
        return _load_png_raw(path)


# ---------------------------------------------------------------------------
# Comparison functions
# ---------------------------------------------------------------------------

def compare_images(path_a, path_b, threshold=10, save_diff=False, diff_path=None):
    """Compare two images and return difference metrics.

    Args:
        path_a: path to first PNG image.
        path_b: path to second PNG image.
        threshold: pixel difference threshold to count as "changed" (0-255).
        save_diff: if True, save a visual diff image.
        diff_path: path for diff image. Defaults to .tmp/diff.png.

    Returns:
        Dict with keys:
            changed_pixels_pct: percentage of pixels that differ by > threshold
            correlation: Pearson correlation coefficient (0-1 similarity)
            identical: True if images are exactly the same
            dimensions_match: True if both images are the same size
            mean_absolute_diff: average absolute pixel difference
    """
    w1, h1, px1 = _load_image(path_a)
    w2, h2, px2 = _load_image(path_b)

    result = {
        "changed_pixels_pct": 100.0,
        "correlation": 0.0,
        "identical": False,
        "dimensions_match": (w1 == w2 and h1 == h2),
        "mean_absolute_diff": 255.0,
    }

    if not result["dimensions_match"]:
        # Can't compare pixel-by-pixel if sizes differ
        return result

    n = len(px1)
    if n == 0:
        return result

    # Pixel difference
    diffs = [abs(px1[i] - px2[i]) for i in range(n)]
    changed = sum(1 for d in diffs if d > threshold)
    mean_diff = sum(diffs) / n

    result["changed_pixels_pct"] = round(100.0 * changed / n, 2)
    result["mean_absolute_diff"] = round(mean_diff, 2)
    result["identical"] = all(d == 0 for d in diffs)

    # Pearson correlation
    mean1 = sum(px1) / n
    mean2 = sum(px2) / n

    sum_cross = 0.0
    sum_sq1 = 0.0
    sum_sq2 = 0.0
    for i in range(n):
        d1 = px1[i] - mean1
        d2 = px2[i] - mean2
        sum_cross += d1 * d2
        sum_sq1 += d1 * d1
        sum_sq2 += d2 * d2

    denom = math.sqrt(sum_sq1 * sum_sq2)
    if denom > 0:
        result["correlation"] = round(sum_cross / denom, 4)
    else:
        result["correlation"] = 1.0 if sum_sq1 == 0 and sum_sq2 == 0 else 0.0

    # Save visual diff
    if save_diff:
        _save_diff_image(w1, h1, diffs, diff_path)

    return result


def _save_diff_image(width, height, diffs, diff_path=None):
    """Save a visual diff image (brighter = more different).

    Args:
        width: image width.
        height: image height.
        diffs: flat list of absolute pixel differences.
        diff_path: output path. Defaults to .tmp/diff.png.
    """
    if diff_path is None:
        diff_path = os.path.join(TMP_DIR, "diff.png")
    os.makedirs(os.path.dirname(os.path.abspath(diff_path)), exist_ok=True)

    # Scale diffs to 0-255 for visibility
    max_diff = max(diffs) if diffs else 1
    if max_diff == 0:
        max_diff = 1
    scaled = [min(255, int(d * 255.0 / max_diff)) for d in diffs]

    if _HAS_PIL:
        img = Image.new("L", (width, height))
        img.putdata(scaled)
        img.save(diff_path)
    else:
        # Write a minimal grayscale PNG using stdlib
        _write_raw_png(diff_path, width, height, scaled)


def _write_raw_png(path, width, height, pixels):
    """Write a minimal 8-bit grayscale PNG using only stdlib."""
    def _chunk(chunk_type, data):
        c = chunk_type + data
        crc = struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
        return struct.pack(">I", len(data)) + c + crc

    # IHDR
    ihdr_data = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    ihdr = _chunk(b"IHDR", ihdr_data)

    # IDAT — build raw image data with filter type 0 (None) per row
    raw_rows = []
    for y in range(height):
        row_start = y * width
        row = bytes([0] + pixels[row_start:row_start + width])
        raw_rows.append(row)
    compressed = zlib.compress(b"".join(raw_rows))
    idat = _chunk(b"IDAT", compressed)

    # IEND
    iend = _chunk(b"IEND", b"")

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(ihdr)
        f.write(idat)
        f.write(iend)


if __name__ == "__main__":
    import sys

    if len(sys.argv) >= 3:
        result = compare_images(sys.argv[1], sys.argv[2], save_diff=True)
        print(result)
    else:
        # Demo: create two tiny test images and compare them
        os.makedirs(TMP_DIR, exist_ok=True)

        # Create two small test images
        w, h = 8, 8
        px_a = [128] * (w * h)
        px_b = [128] * (w * h)
        # Make some pixels different
        for i in range(0, 20):
            px_b[i] = 200

        path_a = os.path.join(TMP_DIR, "test_a.png")
        path_b = os.path.join(TMP_DIR, "test_b.png")
        _write_raw_png(path_a, w, h, px_a)
        _write_raw_png(path_b, w, h, px_b)

        result = compare_images(path_a, path_b, save_diff=True)
        print("Comparison result:")
        for k, v in result.items():
            print("  %s: %s" % (k, v))

        # Cleanup test files
        os.remove(path_a)
        os.remove(path_b)
        diff_path = os.path.join(TMP_DIR, "diff.png")
        if os.path.exists(diff_path):
            os.remove(diff_path)
