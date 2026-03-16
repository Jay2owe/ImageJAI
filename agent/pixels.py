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

import socket
import json
import base64
import struct
import sys
import os
import math

HOST = "localhost"
PORT = 7746
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
TMP_DIR = os.path.join(SCRIPT_DIR, ".tmp")


def send(cmd):
    """Send JSON command to ImageJ TCP server."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(60)
    s.connect((HOST, PORT))
    s.sendall((json.dumps(cmd) + "\n").encode("utf-8"))
    data = b""
    while True:
        try:
            chunk = s.recv(65536)
            if not chunk:
                break
            data += chunk
        except socket.timeout:
            break
    s.close()
    return json.loads(data.decode("utf-8"))


def get_pixels(x=None, y=None, width=None, height=None, slice_num=None, all_slices=False):
    """
    Fetch raw pixel data from ImageJ.

    Returns:
        (pixels, meta) where pixels is a list of lists (2D) or list of 2D (3D),
        and meta is a dict with width, height, sliceCount, type, etc.
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
    if not resp.get("ok"):
        raise RuntimeError("get_pixels failed: " + resp.get("error", "unknown"))

    result = resp["result"]
    b64 = result["data"]
    raw = base64.b64decode(b64)
    w = result["width"]
    h = result["height"]
    n_slices = result["sliceCount"]
    n_pixels = result["nPixels"]

    # Decode little-endian float32
    floats = struct.unpack("<" + str(n_pixels) + "f", raw)

    # Reshape into 2D or 3D list
    if n_slices == 1:
        pixels = []
        for row in range(h):
            pixels.append(list(floats[row * w:(row + 1) * w]))
    else:
        pixels = []
        for s in range(n_slices):
            plane = []
            offset = s * w * h
            for row in range(h):
                plane.append(list(floats[offset + row * w:offset + (row + 1) * w]))
            pixels.append(plane)

    meta = {
        "x": result["x"],
        "y": result["y"],
        "width": w,
        "height": h,
        "sliceStart": result["sliceStart"],
        "sliceEnd": result["sliceEnd"],
        "sliceCount": n_slices,
        "type": result["type"],
    }
    return pixels, meta


def compute_stats(pixels_2d):
    """Compute basic statistics for a 2D pixel array."""
    flat = []
    for row in pixels_2d:
        flat.extend(row)

    n = len(flat)
    if n == 0:
        return {"count": 0}

    mean = sum(flat) / n
    sorted_vals = sorted(flat)
    median = sorted_vals[n // 2]
    min_val = sorted_vals[0]
    max_val = sorted_vals[-1]
    variance = sum((x - mean) ** 2 for x in flat) / n
    std = math.sqrt(variance)

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


def main():
    os.makedirs(TMP_DIR, exist_ok=True)

    if len(sys.argv) < 2:
        # Default: stats for current slice
        pixels, meta = get_pixels()
        stats = compute_stats(pixels)
        print("Image: {}x{}, {}, slice {}-{}".format(
            meta["width"], meta["height"], meta["type"],
            meta["sliceStart"], meta["sliceEnd"]))
        print("Stats:", json.dumps(stats, indent=2))
        return

    cmd = sys.argv[1].lower()

    try:
        if cmd == "slice":
            s = int(sys.argv[2]) if len(sys.argv) > 2 else 1
            pixels, meta = get_pixels(slice_num=s)
            stats = compute_stats(pixels)
            print("Slice {}: {}".format(s, json.dumps(stats)))

        elif cmd == "region":
            if len(sys.argv) < 6:
                print("Usage: python pixels.py region X Y WIDTH HEIGHT")
                sys.exit(1)
            x, y, w, h = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
            pixels, meta = get_pixels(x=x, y=y, width=w, height=h)
            stats = compute_stats(pixels)
            print("Region ({},{} {}x{}): {}".format(x, y, w, h, json.dumps(stats)))

        elif cmd == "profile":
            if len(sys.argv) < 6:
                print("Usage: python pixels.py profile X1 Y1 X2 Y2")
                sys.exit(1)
            x1, y1, x2, y2 = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
            pixels, meta = get_pixels()
            prof = line_profile(pixels, x1, y1, x2, y2, meta)
            for p in prof:
                print("{}\t{:.1f}".format(p["pos"], p["value"]))

        elif cmd == "find_cells":
            factor = float(sys.argv[2]) if len(sys.argv) > 2 else 2.0
            pixels, meta = get_pixels()
            objects = find_bright_objects(pixels, meta, threshold_factor=factor)
            print("Found {} objects (threshold = mean + {}*std):".format(len(objects), factor))
            for obj in objects[:20]:
                print("  label={}: pos=({},{}) area={} mean={:.0f}".format(
                    obj["label"], obj["x"], obj["y"], obj["area"], obj["mean_intensity"]))

        elif cmd == "stack_stats":
            # Get info first to know slice count
            info_resp = send({"command": "get_image_info"})
            if not info_resp.get("ok"):
                print("ERROR:", info_resp.get("error"))
                sys.exit(1)
            n_slices = info_resp["result"]["slices"]
            print("Slice  Mean      Std       Min    Max")
            for s in range(1, n_slices + 1):
                pixels, meta = get_pixels(slice_num=s)
                st = compute_stats(pixels)
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
