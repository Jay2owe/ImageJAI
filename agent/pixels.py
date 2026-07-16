"""
Pixel data access and analysis for the ImageJAI agent.

Fetches raw pixel data from ImageJ via the TCP server and provides
numpy-like analysis without needing ImageJ for computation.

Usage:
    python pixels.py                          # stats for current slice
    python pixels.py slice 7                  # stats for slice 7
    python pixels.py region 100 100 50 50     # stats for region
    python pixels.py profile 512 0 512 1024   # line profile (x1 y1 x2 y2)
    python pixels.py find_cells               # auto-detect bright objects
    python pixels.py stack_stats              # per-slice statistics

As a module:
    from pixels import get_pixels, find_bright_objects
    data, meta = get_pixels()                  # returns (2D array, metadata dict)
    cells = find_bright_objects(data, meta)     # returns list of {x, y, area, mean}
"""

import json
import base64
import binascii
import struct
import sys
import os
import math
from array import array
from collections.abc import Sequence

HOST = os.environ.get("IMAGEJAI_TCP_HOST", "localhost")
try:
    PORT = int(os.environ.get("IMAGEJAI_TCP_PORT", "7746"))
except ValueError:
    PORT = 7746
TIMEOUT = 60
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
TMP_DIR = os.path.join(SCRIPT_DIR, ".tmp")

try:
    from .ij import imagej_command as _ij_imagej_command
except ImportError:
    try:
        from ij import imagej_command as _ij_imagej_command
    except ImportError:
        _ij_imagej_command = None

# REGRESSION GUARD: Past pixel workflows existed only as CLI branches, so importing agents could not reuse them.
# The fix: add every stable workflow to __all__, route main() through it, and cover it in test_pixels_api.py.
__all__ = [
    "imagej_command",
    "send",
    "get_pixels",
    "compute_stats",
    "line_profile",
    "find_bright_objects",
    "get_current_stats",
    "get_slice_stats",
    "get_region_stats",
    "get_line_profile",
    "find_cells",
    "get_stack_stats",
]


def imagej_command(cmd, host=HOST, port=PORT, timeout=TIMEOUT):
    """Send through ij.py's bounded, authenticated durable session."""
    if _ij_imagej_command is None:
        raise RuntimeError(
            "pixels.py requires the authenticated agent/ij.py client")
    return _ij_imagej_command(cmd, host=host, port=port, timeout=timeout)


def send(cmd):
    """Compatibility alias for older pixels.py callers."""
    return imagej_command(cmd)


def _error_message(resp):
    """Return a stable message for string or structured server errors."""
    error = resp.get("error", "unknown") if isinstance(resp, dict) else resp
    if isinstance(error, dict):
        return str(error.get("message") or error.get("code") or json.dumps(error, sort_keys=True))
    return str(error)


def _pixel_metadata(result, width, height, slice_count, pixel_count):
    """Validate and preserve the server's geometry and C/Z/T attribution."""
    try:
        x = int(result["x"])
        y = int(result["y"])
        slice_start = int(result["sliceStart"])
        slice_end = int(result["sliceEnd"])
        slice_axis = str(result["sliceAxis"])
        channel = int(result["channel"])
        frame = int(result["frame"])
        channels = int(result["channels"])
        slices = int(result["slices"])
        frames = int(result["frames"])
        image_type = str(result["type"])
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError(
            "get_pixels failed: missing or malformed C/Z/T metadata"
        ) from exc

    if (
        x < 0
        or y < 0
        or slice_axis != "Z"
        or channels <= 0
        or slices <= 0
        or frames <= 0
        or not 1 <= channel <= channels
        or not 1 <= frame <= frames
        or not 1 <= slice_start <= slice_end <= slices
        or slice_count != slice_end - slice_start + 1
    ):
        raise RuntimeError("get_pixels failed: inconsistent C/Z/T metadata")

    return {
        "x": x,
        "y": y,
        "width": width,
        "height": height,
        "sliceStart": slice_start,
        "sliceEnd": slice_end,
        "sliceCount": slice_count,
        "sliceAxis": slice_axis,
        "channel": channel,
        "frame": frame,
        "channels": channels,
        "slices": slices,
        "frames": frames,
        "nPixels": pixel_count,
        "type": image_type,
    }


class _CompactPlane(Sequence):
    """A row-addressable float32 plane backed by one compact array."""

    def __init__(self, values, width, height, offset=0):
        self._values = values
        self.width = width
        self.height = height
        self.offset = offset

    def __len__(self):
        return self.height

    def __getitem__(self, index):
        if isinstance(index, slice):
            return [self[i] for i in range(*index.indices(self.height))]
        if index < 0:
            index += self.height
        if index < 0 or index >= self.height:
            raise IndexError(index)
        start = self.offset + index * self.width
        return memoryview(self._values)[start:start + self.width]

    def __eq__(self, other):
        try:
            return len(other) == self.height and all(
                list(self[row]) == list(other[row]) for row in range(self.height)
            )
        except (IndexError, TypeError):
            return False

    def iter_values(self):
        end = self.offset + self.width * self.height
        return iter(memoryview(self._values)[self.offset:end])


class _CompactStack(Sequence):
    """A slice-addressable float32 stack sharing one compact backing array."""

    def __init__(self, values, width, height, slices):
        self._values = values
        self.width = width
        self.height = height
        self.slices = slices

    def __len__(self):
        return self.slices

    def __getitem__(self, index):
        if isinstance(index, slice):
            return [self[i] for i in range(*index.indices(self.slices))]
        if index < 0:
            index += self.slices
        if index < 0 or index >= self.slices:
            raise IndexError(index)
        return _CompactPlane(
            self._values,
            self.width,
            self.height,
            index * self.width * self.height,
        )

    def __eq__(self, other):
        try:
            return len(other) == self.slices and all(
                self[slice_index] == other[slice_index]
                for slice_index in range(self.slices)
            )
        except (IndexError, TypeError):
            return False


def _select_kth(values, k):
    """Select one order statistic in-place without a Python-object sort copy."""
    left = 0
    right = len(values) - 1
    while left < right:
        pivot = values[(left + right) // 2]
        i = left
        j = right
        while i <= j:
            while values[i] < pivot:
                i += 1
            while values[j] > pivot:
                j -= 1
            if i <= j:
                values[i], values[j] = values[j], values[i]
                i += 1
                j -= 1
        if k <= j:
            right = j
        elif k >= i:
            left = i
        else:
            break
    return values[k]


def get_pixels(x=None, y=None, width=None, height=None, slice_num=None, all_slices=False):
    """
    Fetch raw pixel data from ImageJ.

    Returns:
        (pixels, meta) where pixels is a row/slice-addressable sequence backed
        by one compact float32 buffer, and meta contains dimensions and type.
    """
    cmd = {"command": "get_pixels"}
    if x is not None:
        cmd["x"] = x
    if y is not None:
        cmd["y"] = y
    if width is not None:
        cmd["width"] = width
    if height is not None:
        cmd["height"] = height
    if slice_num is not None:
        cmd["slice"] = slice_num
    if all_slices:
        cmd["allSlices"] = True

    resp = send(cmd)
    if not isinstance(resp, dict) or not resp.get("ok"):
        raise RuntimeError("get_pixels failed: " + _error_message(resp))

    result = resp.get("result")
    if not isinstance(result, dict):
        raise RuntimeError("get_pixels failed: result is not an object")
    try:
        b64 = result["data"]
        w = int(result["width"])
        h = int(result["height"])
        n_slices = int(result["sliceCount"])
        n_pixels = int(result["nPixels"])
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError("get_pixels failed: malformed result metadata") from exc
    if w <= 0 or h <= 0 or n_slices <= 0 or n_pixels != w * h * n_slices:
        raise RuntimeError("get_pixels failed: inconsistent dimensions/pixel count")
    if not isinstance(b64, str):
        raise RuntimeError("get_pixels failed: data is not base64 text")
    try:
        raw = base64.b64decode(b64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise RuntimeError("get_pixels failed: invalid base64 pixel data") from exc
    if len(raw) != n_pixels * 4:
        raise RuntimeError("get_pixels failed: float32 byte length does not match metadata")

    # Decode into one compact float32 buffer instead of materialising millions
    # of Python float objects plus duplicated row lists.
    floats = array("f")
    floats.frombytes(raw)
    if sys.byteorder != "little":
        floats.byteswap()

    if n_slices == 1:
        pixels = _CompactPlane(floats, w, h)
    else:
        pixels = _CompactStack(floats, w, h, n_slices)

    meta = _pixel_metadata(result, w, h, n_slices, n_pixels)
    return pixels, meta


def compute_stats(pixels_2d):
    """Compute basic statistics for a 2D pixel array."""
    if isinstance(pixels_2d, _CompactPlane):
        flat = pixels_2d.iter_values()
    else:
        flat = (value for row in pixels_2d for value in row)

    values = array("d")
    for value in flat:
        numeric = float(value)
        if not math.isfinite(numeric):
            raise ValueError("pixel data contains a non-finite value")
        values.append(numeric)

    n = len(values)
    if n == 0:
        return {
            "count": 0,
            "mean": None,
            "std": None,
            "min": None,
            "max": None,
            "median": None,
        }

    mean = math.fsum(values) / n
    middle = n // 2
    min_val = min(values)
    max_val = max(values)
    variance = math.fsum((x - mean) ** 2 for x in values) / n
    std = math.sqrt(variance)
    if n % 2:
        median = _select_kth(values, middle)
    else:
        upper = _select_kth(values, middle)
        lower = _select_kth(values, middle - 1)
        median = (lower + upper) / 2.0

    return {
        "count": n,
        "mean": round(mean, 2),
        "std": round(std, 2),
        "min": min_val,
        "max": max_val,
        "median": median,
    }


def line_profile(pixels_2d, x1, y1, x2, y2, meta=None):
    """Extract intensity values along a line between two points."""
    h = len(pixels_2d)
    w = len(pixels_2d[0]) if h > 0 else 0

    dx = x2 - x1
    dy = y2 - y1
    length = int(math.sqrt(dx * dx + dy * dy))
    if length == 0:
        return []

    profile = []
    for i in range(length + 1):
        t = i / length
        px = int(x1 + t * dx)
        py = int(y1 + t * dy)
        if 0 <= px < w and 0 <= py < h:
            profile.append({"pos": i, "x": px, "y": py, "value": pixels_2d[py][px]})

    return profile


def find_bright_objects(pixels_2d, meta=None, threshold_factor=2.0, min_size=10):
    """
    Find bright objects in the image using simple thresholding.
    Threshold = mean + threshold_factor * stddev.
    Returns list of objects with centroid, area, mean intensity.
    """
    stats = compute_stats(pixels_2d)
    if stats["count"] == 0:
        return []
    threshold = stats["mean"] + threshold_factor * stats["std"]
    h = len(pixels_2d)
    w = len(pixels_2d[0]) if h > 0 else 0

    # Create binary mask
    mask = []
    for y in range(h):
        row = []
        for x in range(w):
            row.append(1 if pixels_2d[y][x] > threshold else 0)
        mask.append(row)

    # Simple connected component labeling (4-connected flood fill)
    labels = [[0] * w for _ in range(h)]
    label_id = 0
    objects = []

    for y in range(h):
        for x in range(w):
            if mask[y][x] == 1 and labels[y][x] == 0:
                label_id += 1
                # Flood fill
                stack = [(x, y)]
                pixels_in_obj = []
                while stack:
                    cx, cy = stack.pop()
                    if cx < 0 or cx >= w or cy < 0 or cy >= h:
                        continue
                    if mask[cy][cx] == 0 or labels[cy][cx] != 0:
                        continue
                    labels[cy][cx] = label_id
                    pixels_in_obj.append((cx, cy))
                    stack.extend([(cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)])

                if len(pixels_in_obj) >= min_size:
                    # Compute centroid and mean intensity
                    sum_x = sum(p[0] for p in pixels_in_obj)
                    sum_y = sum(p[1] for p in pixels_in_obj)
                    sum_val = sum(pixels_2d[p[1]][p[0]] for p in pixels_in_obj)
                    area = len(pixels_in_obj)
                    obj = {
                        "label": label_id,
                        "x": round(sum_x / area, 1),
                        "y": round(sum_y / area, 1),
                        "area": area,
                        "mean_intensity": round(sum_val / area, 1),
                    }
                    # Add absolute position if meta available
                    if meta:
                        obj["abs_x"] = round(obj["x"] + meta.get("x", 0), 1)
                        obj["abs_y"] = round(obj["y"] + meta.get("y", 0), 1)
                    objects.append(obj)

    # Sort by area descending
    objects.sort(key=lambda o: o["area"], reverse=True)
    return objects


def get_current_stats():
    """Return stats and metadata for the current slice."""
    pixels, meta = get_pixels()
    return {"stats": compute_stats(pixels), "meta": meta}


def get_slice_stats(slice_num):
    """Return stats and metadata for one 1-based stack slice."""
    pixels, meta = get_pixels(slice_num=slice_num)
    return {"slice": slice_num, "stats": compute_stats(pixels), "meta": meta}


def get_region_stats(x, y, width, height):
    """Return stats and metadata for a rectangular region."""
    pixels, meta = get_pixels(x=x, y=y, width=width, height=height)
    return {
        "x": x,
        "y": y,
        "width": width,
        "height": height,
        "stats": compute_stats(pixels),
        "meta": meta,
    }


def get_line_profile(x1, y1, x2, y2):
    """Return the current image intensity profile between two points."""
    pixels, meta = get_pixels()
    return line_profile(pixels, x1, y1, x2, y2, meta)


def find_cells(threshold_factor=2.0, min_size=10):
    """Find bright objects in the current image."""
    pixels, meta = get_pixels()
    return find_bright_objects(
        pixels,
        meta,
        threshold_factor=threshold_factor,
        min_size=min_size,
    )


def get_stack_stats():
    """Return per-slice stats for the active stack."""
    info_resp = send({"command": "get_image_info"})
    if not info_resp.get("ok"):
        raise RuntimeError(info_resp.get("error"))
    n_slices = info_resp["result"]["slices"]

    rows = []
    for s in range(1, n_slices + 1):
        pixels, meta = get_pixels(slice_num=s)
        rows.append({"slice": s, "stats": compute_stats(pixels), "meta": meta})
    return rows


def main():
    os.makedirs(TMP_DIR, exist_ok=True)

    if len(sys.argv) < 2:
        # Default: stats for current slice
        current = get_current_stats()
        meta = current["meta"]
        stats = current["stats"]
        print("Image: {}x{}, {}, slice {}-{}".format(
            meta["width"], meta["height"], meta["type"],
            meta["sliceStart"], meta["sliceEnd"]))
        print("Stats:", json.dumps(stats, indent=2))
        return

    cmd = sys.argv[1].lower()

    try:
        if cmd == "slice":
            s = int(sys.argv[2]) if len(sys.argv) > 2 else 1
            result = get_slice_stats(s)
            stats = result["stats"]
            print("Slice {}: {}".format(s, json.dumps(stats)))

        elif cmd == "region":
            if len(sys.argv) < 6:
                print("Usage: python pixels.py region X Y WIDTH HEIGHT")
                sys.exit(1)
            x, y, w, h = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
            result = get_region_stats(x, y, w, h)
            stats = result["stats"]
            print("Region ({},{} {}x{}): {}".format(x, y, w, h, json.dumps(stats)))

        elif cmd == "profile":
            if len(sys.argv) < 6:
                print("Usage: python pixels.py profile X1 Y1 X2 Y2")
                sys.exit(1)
            x1, y1, x2, y2 = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
            prof = get_line_profile(x1, y1, x2, y2)
            for p in prof:
                print("{}\t{:.1f}".format(p["pos"], p["value"]))

        elif cmd == "find_cells":
            factor = float(sys.argv[2]) if len(sys.argv) > 2 else 2.0
            objects = find_cells(threshold_factor=factor)
            print("Found {} objects (threshold = mean + {}*std):".format(len(objects), factor))
            for obj in objects[:20]:
                print("  label={}: pos=({},{}) area={} mean={:.0f}".format(
                    obj["label"], obj["x"], obj["y"], obj["area"], obj["mean_intensity"]))

        elif cmd == "stack_stats":
            stack = get_stack_stats()
            print("Slice  Mean      Std       Min    Max")
            for row in stack:
                s = row["slice"]
                st = row["stats"]
                print("{:5d}  {:8.1f}  {:8.1f}  {:5.0f}  {:5.0f}".format(
                    s, st["mean"], st["std"], st["min"], st["max"]))

        else:
            print("Unknown command:", cmd)
            print("Commands: slice, region, profile, find_cells, stack_stats")
            sys.exit(1)

    except ConnectionRefusedError:
        print("ERROR: Cannot connect to ImageJAI on localhost:" + str(PORT))
        sys.exit(1)
    except Exception as e:
        print("ERROR:", e)
        sys.exit(1)


if __name__ == "__main__":
    main()
