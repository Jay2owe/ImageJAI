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


def _exact_int(value, name, minimum=None):
    """Return a true integer, rejecting bools, floats and numeric strings."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError("{} must be an exact integer".format(name))
    if minimum is not None and value < minimum:
        raise ValueError("{} must be at least {}".format(name, minimum))
    return value


def _add_image_binding(
    command, *, image_id=None, image_revision=None, display_revision=None,
    channel=None, slice=None, frame=None,
):
    if (image_id is None) != (image_revision is None):
        raise ValueError("image_id and image_revision must be provided together")
    if image_id is not None:
        if not isinstance(image_id, str) or not image_id.strip():
            raise TypeError("image_id must be a non-empty string")
        command["image_id"] = image_id.strip()
        command["image_revision"] = _exact_int(
            image_revision, "image_revision", minimum=1
        )
    if display_revision is not None:
        if image_id is None:
            raise ValueError("display_revision requires an image snapshot binding")
        command["display_revision"] = _exact_int(
            display_revision, "display_revision", minimum=1
        )
    for key, value in (("channel", channel), ("slice", slice), ("frame", frame)):
        if value is not None:
            command[key] = _exact_int(value, key, minimum=1)
    return command


def _optional_finite_number(value, name):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RuntimeError("get_pixels failed: {} must be numeric or null".format(name))
    value = float(value)
    if not math.isfinite(value):
        raise RuntimeError("get_pixels failed: {} must be finite".format(name))
    return value


def _value_domain(result):
    domain = result.get("value_domain")
    if not isinstance(domain, dict) or domain.get("representation") != "raw":
        raise RuntimeError("get_pixels failed: missing raw value-domain metadata")
    pixel_type = domain.get("pixel_type")
    if pixel_type not in ("uint8", "uint16", "float32", "rgb24", "indexed8", "unknown"):
        raise RuntimeError("get_pixels failed: invalid pixel type metadata")
    if pixel_type == "rgb24" and not {
        "representation", "pixel_type", "signed", "density_calibrated",
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    }.issubset(domain):
        raise RuntimeError("get_pixels failed: incomplete rgb24 value-domain metadata")
    signed = domain.get("signed")
    if signed is not None and not isinstance(signed, bool):
        raise RuntimeError("get_pixels failed: invalid signed metadata")
    if not isinstance(domain.get("density_calibrated"), bool):
        raise RuntimeError("get_pixels failed: invalid calibration-domain metadata")
    normalized = dict(domain)
    for key in (
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    ):
        normalized[key] = _optional_finite_number(domain.get(key), key)
    return normalized


def _pixel_metadata(result, width, height, slice_count, pixel_count):
    """Validate and preserve the server's geometry and C/Z/T attribution."""
    try:
        image_id = str(result["image_id"])
        image_revision = _exact_int(result["image_revision"], "image_revision", 1)
        display_revision = _exact_int(result["display_revision"], "display_revision", 1)
        x = _exact_int(result["x"], "x", 0)
        y = _exact_int(result["y"], "y", 0)
        slice_start = _exact_int(result["sliceStart"], "sliceStart", 1)
        slice_end = _exact_int(result["sliceEnd"], "sliceEnd", 1)
        slice_axis = str(result["sliceAxis"])
        channel = _exact_int(result["channel"], "channel", 1)
        frame = _exact_int(result["frame"], "frame", 1)
        channels = _exact_int(result["channels"], "channels", 1)
        slices = _exact_int(result["slices"], "slices", 1)
        frames = _exact_int(result["frames"], "frames", 1)
        image_type = str(result["type"])
        value_domain = _value_domain(result)
        counts_exact = result["acquisition_limit_counts_exact"]
        if not isinstance(counts_exact, bool):
            raise TypeError("acquisition_limit_counts_exact must be bool")
        min_count_raw = result["acquisition_min_count"]
        max_count_raw = result["acquisition_max_count"]
        min_count = (
            None if min_count_raw is None
            else _exact_int(min_count_raw, "acquisition_min_count", 0)
        )
        max_count = (
            None if max_count_raw is None
            else _exact_int(max_count_raw, "acquisition_max_count", 0)
        )
    except (KeyError, TypeError, ValueError, RuntimeError) as exc:
        raise RuntimeError(
            "get_pixels failed: missing or malformed C/Z/T metadata"
        ) from exc

    if (
        not image_id
        or image_revision <= 0
        or x < 0
        or y < 0
        or slice_axis != "Z"
        or channels <= 0
        or slices <= 0
        or frames <= 0
        or not 1 <= channel <= channels
        or not 1 <= frame <= frames
        or not 1 <= slice_start <= slice_end <= slices
        or slice_count != slice_end - slice_start + 1
        or (counts_exact and (min_count is None or max_count is None))
        or (min_count is not None and min_count > pixel_count)
        or (max_count is not None and max_count > pixel_count)
    ):
        raise RuntimeError("get_pixels failed: inconsistent C/Z/T metadata")

    return {
        "image_id": image_id,
        "image_revision": image_revision,
        "display_revision": display_revision,
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
        "value_domain": value_domain,
        "acquisition_min_count": min_count,
        "acquisition_max_count": max_count,
        "acquisition_limit_counts_exact": counts_exact,
    }


def _is_rgb_image_type(value):
    if not isinstance(value, str):
        return False
    normalized = " ".join(value.strip().casefold().split())
    return normalized in ("rgb", "rgb color", "24-bit")


def _rgb24_analysis_weights(meta):
    """Fetch and validate ImageJ's scalar-intensity contract for one RGB plane."""
    if meta is None:
        return None
    if not isinstance(meta, dict):
        raise RuntimeError("RGB intensity analysis requires pixel metadata")
    domain = meta.get("value_domain")
    packed_source = (
        _is_rgb_image_type(meta.get("type"))
        or (isinstance(domain, dict) and domain.get("pixel_type") == "rgb24")
    )
    if not packed_source:
        return None
    if not isinstance(domain, dict) or domain.get("pixel_type") != "rgb24":
        raise RuntimeError("RGB intensity analysis received an unsafe pixel domain")
    required_source_fields = {
        "representation", "pixel_type", "signed", "density_calibrated",
        "acquisition_min_raw", "acquisition_max_raw",
        "acquisition_min_calibrated", "acquisition_max_calibrated",
    }
    if (
        not required_source_fields.issubset(domain)
        or domain.get("representation") != "raw"
        or domain.get("signed") is not None
        or domain.get("density_calibrated") is not False
        or domain.get("acquisition_min_raw") is not None
        or domain.get("acquisition_max_raw") is not None
        or domain.get("acquisition_min_calibrated") is not None
        or domain.get("acquisition_max_calibrated") is not None
        or domain.get("scalarization") is not None
    ):
        raise RuntimeError("RGB intensity analysis received an unsafe pixel domain")

    try:
        image_id_raw = meta["image_id"]
        if not isinstance(image_id_raw, str) or not image_id_raw.strip():
            raise ValueError("image_id must be a non-empty string")
        image_id = image_id_raw.strip()
        image_revision = _exact_int(meta["image_revision"], "image_revision", 1)
        display_revision = _exact_int(meta["display_revision"], "display_revision", 1)
        channel = _exact_int(meta["channel"], "channel", 1)
        slice_start = _exact_int(meta["sliceStart"], "sliceStart", 1)
        slice_end = _exact_int(meta["sliceEnd"], "sliceEnd", 1)
        frame = _exact_int(meta["frame"], "frame", 1)
        channels = _exact_int(meta["channels"], "channels", 1)
        slices = _exact_int(meta["slices"], "slices", 1)
        frames = _exact_int(meta["frames"], "frames", 1)
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError(
            "RGB intensity analysis requires complete snapshot/C/Z/T metadata"
        ) from exc
    if (
        not image_id
        or meta.get("sliceAxis") != "Z"
        or slice_start != slice_end
        or not 1 <= channel <= channels
        or not 1 <= slice_start <= slices
        or not 1 <= frame <= frames
    ):
        raise RuntimeError("RGB intensity analysis received inconsistent pixel metadata")

    request = {
        "command": "get_histogram",
        "image_id": image_id,
        "image_revision": image_revision,
        "display_revision": display_revision,
        "channel": channel,
        "slice": slice_start,
        "frame": frame,
        "scope": "full_plane",
        "force": True,
    }
    response = send(request)
    if not isinstance(response, dict) or not response.get("ok"):
        raise RuntimeError(
            "RGB intensity analysis cannot obtain scalarization metadata: {}".format(
                _error_message(response)
            )
        )
    result = response.get("result")
    if not isinstance(result, dict):
        raise RuntimeError("RGB histogram scalarization result is not an object")

    try:
        metadata_matches = (
            isinstance(result["image_id"], str)
            and result["image_id"].strip() == image_id
            and _exact_int(result["image_revision"], "image_revision", 1)
            == image_revision
            and _exact_int(result["display_revision"], "display_revision", 1)
            == display_revision
            and result["sliceAxis"] == "Z"
            and _exact_int(result["sliceStart"], "sliceStart", 1) == slice_start
            and _exact_int(result["sliceEnd"], "sliceEnd", 1) == slice_start
            and _exact_int(result["channel"], "channel", 1) == channel
            and _exact_int(result["frame"], "frame", 1) == frame
            and _exact_int(result["channels"], "channels", 1) == channels
            and _exact_int(result["slices"], "slices", 1) == slices
            and _exact_int(result["frames"], "frames", 1) == frames
            and result["scope"] == "full_plane"
        )
    except (KeyError, TypeError, ValueError):
        metadata_matches = False
    if not metadata_matches:
        raise RuntimeError(
            "RGB histogram scalarization metadata did not match the pixel snapshot/plane"
        )

    histogram_domain = result.get("value_domain")
    scalarization = (
        histogram_domain.get("scalarization")
        if isinstance(histogram_domain, dict)
        else None
    )
    if (
        not isinstance(histogram_domain, dict)
        or histogram_domain.get("representation") != "raw"
        or histogram_domain.get("pixel_type") != "uint8"
        or histogram_domain.get("signed") is not False
        or histogram_domain.get("density_calibrated") is not False
        or histogram_domain.get("acquisition_min_raw") != 0.0
        or histogram_domain.get("acquisition_max_raw") != 255.0
        or histogram_domain.get("acquisition_min_calibrated") is not None
        or histogram_domain.get("acquisition_max_calibrated") is not None
        or not isinstance(scalarization, dict)
        or scalarization.get("method") != "imagej_weighted_rgb_intensity"
        or scalarization.get("source_pixel_type") != "rgb24"
        or scalarization.get("rounding") != "nearest_integer_half_up"
        or not isinstance(scalarization.get("weights"), dict)
    ):
        raise RuntimeError("RGB histogram returned unsafe scalarization metadata")
    weights = scalarization["weights"]
    normalized = []
    for component in ("red", "green", "blue"):
        value = weights.get(component)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise RuntimeError("RGB histogram returned unsafe scalarization weights")
        value = float(value)
        if not math.isfinite(value) or value < 0.0:
            raise RuntimeError("RGB histogram returned unsafe scalarization weights")
        normalized.append(value)
    if not math.isclose(sum(normalized), 1.0, rel_tol=0.0, abs_tol=1e-9):
        raise RuntimeError("RGB histogram returned unsafe scalarization weights")
    try:
        histogram_pixels = _exact_int(result["nPixels"], "nPixels", 1)
        requested_pixels = _exact_int(meta["nPixels"], "nPixels", 1)
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError("RGB histogram returned invalid pixel cardinality") from exc
    if histogram_pixels < requested_pixels:
        raise RuntimeError(
            "RGB full-plane histogram contains fewer pixels than the requested region"
        )

    normalized_domain = dict(histogram_domain)
    normalized_scalarization = dict(scalarization)
    normalized_scalarization["weights"] = {
        component: value
        for component, value in zip(("red", "green", "blue"), normalized)
    }
    normalized_domain["scalarization"] = normalized_scalarization
    return {
        "weights": tuple(normalized),
        "value_domain": normalized_domain,
        "histogram_nPixels": histogram_pixels,
    }


def _scalarize_rgb24_plane(pixels_2d, weights):
    """Apply ImageJ's published weighted RGB intensity and half-up rounding."""
    height = len(pixels_2d)
    width = len(pixels_2d[0]) if height else 0
    scalar = array("f")
    red_weight, green_weight, blue_weight = weights
    for row in pixels_2d:
        if len(row) != width:
            raise ValueError("pixel data is not rectangular")
        for sample in row:
            numeric = float(sample)
            if not math.isfinite(numeric) or numeric != math.floor(numeric):
                raise ValueError("RGB pixel data contains a non-integral value")
            if 0.0 <= numeric <= 0x00ffffff:
                packed = int(numeric)
            elif -0x01000000 <= numeric <= -1.0:
                packed = int(numeric) & 0x00ffffff
            else:
                raise ValueError("RGB pixel data is outside the packed rgb24 domain")
            red = (packed >> 16) & 0xff
            green = (packed >> 8) & 0xff
            blue = packed & 0xff
            intensity = math.floor(
                red * red_weight
                + green * green_weight
                + blue * blue_weight
                + 0.5
            )
            if not 0 <= intensity <= 255:
                raise ValueError("RGB scalar intensity is outside the uint8 domain")
            scalar.append(float(intensity))
    return _CompactPlane(scalar, width, height)


def _intensity_plane_and_contract(pixels_2d, meta):
    """Return the analysis plane and its additive RGB scalar contract, if any."""
    contract = _rgb24_analysis_weights(meta)
    if contract is None:
        return pixels_2d, None
    return _scalarize_rgb24_plane(pixels_2d, contract["weights"]), contract


def _intensity_plane(pixels_2d, meta):
    """Return a scientifically valid scalar plane, fetching RGB metadata as needed."""
    return _intensity_plane_and_contract(pixels_2d, meta)[0]


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


def get_pixels(
    x=None, y=None, width=None, height=None, slice_num=None, all_slices=False,
    *, image_id=None, image_revision=None, display_revision=None,
    channel=None, slice=None, frame=None,
):
    """
    Fetch raw pixel data from ImageJ.

    Returns:
        (pixels, meta) where pixels is a row/slice-addressable sequence backed
        by one compact float32 buffer, and meta contains dimensions and type.
    """
    if slice_num is not None and slice is not None:
        raise ValueError("slice_num and slice cannot both be provided")
    requested_geometry = (x, y, width, height)
    if any(value is not None for value in requested_geometry) and not all(
        value is not None for value in requested_geometry
    ):
        raise ValueError("x, y, width and height must be provided together")
    cmd = {"command": "get_pixels"}
    expected_geometry = None
    if all(value is not None for value in requested_geometry):
        expected_geometry = (
            _exact_int(x, "x", 0),
            _exact_int(y, "y", 0),
            _exact_int(width, "width", 1),
            _exact_int(height, "height", 1),
        )
        for key, value in zip(("x", "y", "width", "height"), expected_geometry):
            cmd[key] = value
    if not isinstance(all_slices, bool):
        raise TypeError("all_slices must be bool")
    if all_slices:
        cmd["allSlices"] = True
    selected_slice = slice if slice is not None else slice_num
    cmd = _add_image_binding(
        cmd, image_id=image_id, image_revision=image_revision,
        display_revision=display_revision, channel=channel,
        slice=selected_slice, frame=frame,
    )

    resp = send(cmd)
    if not isinstance(resp, dict) or not resp.get("ok"):
        raise RuntimeError("get_pixels failed: " + _error_message(resp))

    result = resp.get("result")
    if not isinstance(result, dict):
        raise RuntimeError("get_pixels failed: result is not an object")
    try:
        b64 = result["data"]
        w = _exact_int(result["width"], "width", 1)
        h = _exact_int(result["height"], "height", 1)
        n_slices = _exact_int(result["sliceCount"], "sliceCount", 1)
        n_pixels = _exact_int(result["nPixels"], "nPixels", 1)
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
    if expected_geometry is not None:
        actual = (meta["x"], meta["y"], meta["width"], meta["height"])
        if actual != expected_geometry:
            raise RuntimeError(
                "get_pixels failed: Fiji returned clamped pixel geometry "
                "{} instead of requested {}; image state changed".format(
                    actual, expected_geometry
                )
            )
    return pixels, meta


def compute_stats(pixels_2d, meta=None):
    """Compute basic statistics for a 2D pixel array."""
    pixels_2d = _intensity_plane(pixels_2d, meta)
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
    pixels_2d, contract = _intensity_plane_and_contract(pixels_2d, meta)
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
            point = {"pos": i, "x": px, "y": py, "value": pixels_2d[py][px]}
            if contract is not None:
                point["analysis_value_domain"] = contract["value_domain"]
            profile.append(point)

    return profile


def find_bright_objects(pixels_2d, meta=None, threshold_factor=2.0, min_size=10):
    """
    Find bright objects in the image using simple thresholding.
    Threshold = mean + threshold_factor * stddev.
    Returns list of objects with centroid, area, mean intensity.
    """
    pixels_2d, contract = _intensity_plane_and_contract(pixels_2d, meta)

    # Object membership must use the unrounded moments.  compute_stats keeps
    # its historical two-decimal presentation contract, but those display
    # values can move a threshold across a real pixel value.
    if isinstance(pixels_2d, _CompactPlane):
        raw_values = pixels_2d.iter_values()
    else:
        raw_values = (value for row in pixels_2d for value in row)
    values = array("d")
    for value in raw_values:
        numeric = float(value)
        if not math.isfinite(numeric):
            raise ValueError("pixel data contains a non-finite value")
        values.append(numeric)
    if not values:
        return []
    mean = math.fsum(values) / len(values)
    variance = math.fsum((value - mean) ** 2 for value in values) / len(values)
    threshold = mean + float(threshold_factor) * math.sqrt(variance)
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
                    if contract is not None:
                        obj["analysis_value_domain"] = contract["value_domain"]
                    objects.append(obj)

    # Sort by area descending
    objects.sort(key=lambda o: o["area"], reverse=True)
    return objects


def get_current_stats():
    """Return stats and metadata for the current slice."""
    pixels, meta = get_pixels()
    analysis, contract = _intensity_plane_and_contract(pixels, meta)
    result = {"stats": compute_stats(analysis), "meta": meta}
    if contract is not None:
        result["analysis_value_domain"] = contract["value_domain"]
    return result


def get_slice_stats(slice_num):
    """Return stats and metadata for one 1-based stack slice."""
    selected = _exact_int(slice_num, "slice_num", 1)
    pixels, meta = get_pixels(slice_num=selected)
    analysis, contract = _intensity_plane_and_contract(pixels, meta)
    result = {"slice": selected, "stats": compute_stats(analysis), "meta": meta}
    if contract is not None:
        result["analysis_value_domain"] = contract["value_domain"]
    return result


def get_region_stats(x, y, width, height):
    """Return stats and metadata for a rectangular region."""
    x_i = _exact_int(x, "x", 0)
    y_i = _exact_int(y, "y", 0)
    width_i = _exact_int(width, "width", 1)
    height_i = _exact_int(height, "height", 1)
    pixels, meta = get_pixels(x=x_i, y=y_i, width=width_i, height=height_i)
    analysis, contract = _intensity_plane_and_contract(pixels, meta)
    result = {
        "x": meta["x"],
        "y": meta["y"],
        "width": meta["width"],
        "height": meta["height"],
        "stats": compute_stats(analysis),
        "meta": meta,
    }
    if contract is not None:
        result["analysis_value_domain"] = contract["value_domain"]
    return result


def get_line_profile(x1, y1, x2, y2):
    """Return the current image intensity profile between two points."""
    x1 = _exact_int(x1, "x1")
    y1 = _exact_int(y1, "y1")
    x2 = _exact_int(x2, "x2")
    y2 = _exact_int(y2, "y2")
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
    """Return per-slice stats bound to one image revision and C/T position."""
    info_resp = send({"command": "get_image_info", "force": True})
    if not isinstance(info_resp, dict) or not info_resp.get("ok"):
        raise RuntimeError(_error_message(info_resp))
    info = info_resp.get("result")
    if not isinstance(info, dict):
        raise RuntimeError("get_image_info failed: result is not an object")
    try:
        image_id = str(info["image_id"])
        image_revision = _exact_int(info["image_revision"], "image_revision", 1)
        display_revision = _exact_int(info["display_revision"], "display_revision", 1)
        channel = _exact_int(info["channel"], "channel", 1)
        frame = _exact_int(info["frame"], "frame", 1)
        channels = _exact_int(info["channels"], "channels", 1)
        n_slices = _exact_int(info["slices"], "slices", 1)
        frames = _exact_int(info["frames"], "frames", 1)
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError("get_image_info returned malformed snapshot metadata") from exc
    if not image_id or channel > channels or frame > frames:
        raise RuntimeError("get_image_info returned inconsistent snapshot metadata")

    rows = []
    for s in range(1, n_slices + 1):
        plane, meta = get_pixels(
            slice_num=s,
            image_id=image_id,
            image_revision=image_revision,
            display_revision=display_revision,
            channel=channel,
            frame=frame,
        )
        if (
            meta.get("image_id") != image_id
            or meta.get("image_revision") != image_revision
            or meta.get("display_revision") != display_revision
            or meta.get("channel") != channel
            or meta.get("frame") != frame
            or meta.get("channels") != channels
            or meta.get("slices") != n_slices
            or meta.get("frames") != frames
            or meta.get("sliceAxis") != "Z"
            or meta.get("sliceStart") != s
            or meta.get("sliceEnd") != s
            or meta.get("sliceCount") != 1
        ):
            raise RuntimeError(
                "get_pixels failed: stack plane did not match bound image snapshot"
            )
        analysis, contract = _intensity_plane_and_contract(plane, meta)
        row = {"slice": s, "stats": compute_stats(analysis), "meta": meta}
        if contract is not None:
            row["analysis_value_domain"] = contract["value_domain"]
        rows.append(row)
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
