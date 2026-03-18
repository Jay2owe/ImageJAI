#!/usr/bin/env python
"""
ImageJAI Agent Training Tool

Train the ImageJAI agent on your lab's specific images and workflows.
Opens each image, characterizes it, tests standard analysis approaches,
and records what works best for your data.

Usage:
    python train_agent.py /path/to/lab/images                  # train on a directory
    python train_agent.py /path/to/lab/images --domain neuro   # specify domain
    python train_agent.py --profile                            # show current lab profile
    python train_agent.py --reset                              # clear lab-specific learnings

Results are saved to:
    agent/lab_profile.json   — structured characterization of your images
    agent/learnings.md       — appended with lab-specific tips
"""

import argparse
import json
import math
import os
import random
import sys
import time
from datetime import datetime

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROFILE_PATH = os.path.join(SCRIPT_DIR, "lab_profile.json")
LEARNINGS_PATH = os.path.join(SCRIPT_DIR, "learnings.md")
TMP_DIR = os.path.join(SCRIPT_DIR, ".tmp")

IMAGE_EXTENSIONS = {".tif", ".tiff", ".nd2", ".lif", ".czi", ".png", ".jpg", ".jpeg"}
BIOFORMATS_EXTENSIONS = {".nd2", ".lif", ".czi"}
MAX_IMAGES = 20

THRESHOLD_METHODS = ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy", "Yen", "Default"]
SEGMENTATION_BLUR_SIGMAS = [0.5, 1.0, 1.5, 2.0, 3.0]
PARTICLE_MIN_SIZES = [20, 50, 100, 200, 500]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _import_ij():
    """Import ij.py functions from the same directory."""
    sys.path.insert(0, SCRIPT_DIR)
    import ij
    return ij


def _connect_or_die(ij):
    """Test TCP connection; exit with instructions if unavailable."""
    try:
        resp = ij.ping()
        if resp.get("ok"):
            return True
    except Exception:
        pass
    print("ERROR: Cannot connect to ImageJAI TCP server on localhost:7746.")
    print()
    print("To use this tool:")
    print("  1. Open Fiji")
    print("  2. Launch Plugins > AI Assistant")
    print("  3. Enable TCP server in Settings > Advanced > 'Enable TCP command server'")
    print("  4. Re-run this script")
    sys.exit(1)


def _close_all(ij):
    """Close all open images (but not Fiji toolbar)."""
    ij.execute_macro('close("*");')
    time.sleep(0.3)


def _open_image(ij, filepath):
    """Open an image, using Bio-Formats for proprietary formats."""
    filepath_escaped = filepath.replace("\\", "/")
    ext = os.path.splitext(filepath)[1].lower()
    if ext in BIOFORMATS_EXTENSIONS:
        macro = (
            'run("Bio-Formats Importer", '
            '"open=[' + filepath_escaped + '] '
            'color_mode=Default view=Hyperstack stack_order=XYCZT");'
        )
    else:
        macro = 'open("' + filepath_escaped + '");'
    resp = ij.execute_macro(macro)
    if not resp.get("ok"):
        return False
    result = resp.get("result", {})
    if isinstance(result, dict) and not result.get("success", True):
        return False
    time.sleep(0.5)
    return True


def _safe_float(val, default=0.0):
    try:
        return float(val)
    except (TypeError, ValueError):
        return default


def _safe_int(val, default=0):
    try:
        return int(val)
    except (TypeError, ValueError):
        return default


def _most_common(lst):
    """Return the most common element in a list."""
    if not lst:
        return None
    counts = {}
    for item in lst:
        counts[item] = counts.get(item, 0) + 1
    return max(counts, key=counts.get)


def _median(values):
    """Compute median of a list of numbers."""
    if not values:
        return 0
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) / 2.0


def _guess_modality(image_records):
    """Guess imaging modality from image characteristics."""
    bit_depths = [r.get("type", "") for r in image_records]
    channels_list = [r.get("channels", 1) for r in image_records]
    has_multi_channel = any(c > 1 for c in channels_list)
    has_16bit = any("16" in b for b in bit_depths)
    has_zstacks = any(r.get("slices", 1) > 1 for r in image_records)
    avg_snr = _median([r.get("snr", 0) for r in image_records if r.get("snr", 0) > 0])

    if has_16bit and has_multi_channel and has_zstacks:
        return "confocal_fluorescence"
    elif has_16bit and has_multi_channel:
        return "widefield_fluorescence"
    elif has_16bit and has_zstacks:
        return "confocal_single_channel"
    elif has_16bit:
        return "fluorescence"
    elif not has_16bit and avg_snr < 3:
        return "brightfield"
    else:
        return "unknown"


def _guess_density(particle_count, image_area):
    """Classify object density."""
    if image_area <= 0:
        return "unknown"
    density = particle_count / (image_area / 1e6)  # objects per megapixel
    if density < 5:
        return "sparse"
    elif density < 50:
        return "moderate"
    else:
        return "dense"


def _lab_name_from_path(image_dir):
    """Try to extract a lab/project name from the directory path."""
    parts = image_dir.replace("\\", "/").split("/")
    # Walk backwards looking for meaningful names
    skip = {"images", "data", "raw", "tif", "tiff", "output", "results", "analysis"}
    for part in reversed(parts):
        if part and part.lower() not in skip and not part.startswith("."):
            return part
    return "unknown_lab"


# ---------------------------------------------------------------------------
# Phase 1: Image Characterization
# ---------------------------------------------------------------------------

def characterize_image(ij, filepath):
    """Open one image, gather metadata, histogram, SNR estimate. Returns dict."""
    filename = os.path.basename(filepath)
    record = {"file": filename, "path": filepath}

    if not _open_image(ij, filepath):
        record["error"] = "Failed to open"
        return record

    # Image info
    info_resp = ij.get_image_info()
    if info_resp.get("ok") and info_resp.get("result"):
        info = info_resp["result"]
        record["type"] = info.get("type", "unknown")
        record["width"] = _safe_int(info.get("width"))
        record["height"] = _safe_int(info.get("height"))
        record["channels"] = _safe_int(info.get("channels", 1))
        record["slices"] = _safe_int(info.get("slices", 1))
        record["frames"] = _safe_int(info.get("frames", 1))

    # Metadata / calibration
    meta_resp = ij.get_metadata()
    if meta_resp.get("ok") and meta_resp.get("result"):
        meta = meta_resp["result"]
        cal = meta.get("calibration", {})
        if cal:
            record["pixel_width"] = _safe_float(cal.get("pixelWidth", 1.0))
            record["pixel_height"] = _safe_float(cal.get("pixelHeight", 1.0))
            record["pixel_unit"] = cal.get("unit", "pixel")
            record["z_spacing"] = _safe_float(cal.get("pixelDepth", 1.0))
        record["has_calibration"] = cal.get("unit", "pixel") not in ("pixel", "")

    # Histogram
    hist_resp = ij.get_histogram()
    if hist_resp.get("ok") and hist_resp.get("result"):
        h = hist_resp["result"]
        record["hist_mean"] = _safe_float(h.get("mean"))
        record["hist_std"] = _safe_float(h.get("stdDev"))
        record["hist_min"] = _safe_float(h.get("min"))
        record["hist_max"] = _safe_float(h.get("max"))
        record["n_pixels"] = _safe_int(h.get("nPixels"))

        # SNR estimate: mean / stdDev (rough approximation)
        std = _safe_float(h.get("stdDev"))
        mean = _safe_float(h.get("mean"))
        if std > 0:
            record["snr"] = round(mean / std, 2)
        else:
            record["snr"] = 0

        # Check for saturation
        bins = h.get("bins", [])
        if bins:
            total = sum(bins)
            last_bin = bins[-1] if bins else 0
            if total > 0:
                record["saturation_pct"] = round(100.0 * last_bin / total, 3)

    # File format
    record["format"] = os.path.splitext(filename)[1].lower()

    return record


# ---------------------------------------------------------------------------
# Phase 2: Threshold Discovery
# ---------------------------------------------------------------------------

def discover_thresholds(ij, image_records, image_dir):
    """Try threshold methods on a subset of images. Returns per-image results."""
    # Pick up to 5 representative images
    candidates = [r for r in image_records if "error" not in r]
    if len(candidates) > 5:
        candidates = random.sample(candidates, 5)

    threshold_results = []
    for i, rec in enumerate(candidates):
        filepath = rec.get("path", "")
        filename = rec.get("file", "unknown")
        print("  Threshold testing {}/{}: {}".format(i + 1, len(candidates), filename))

        _close_all(ij)

        if not _open_image(ij, filepath):
            threshold_results.append({"file": filename, "error": "Failed to open"})
            continue

        # Duplicate so we don't modify the original
        ij.execute_macro('run("Duplicate...", "title=thresh_test duplicate");')
        time.sleep(0.3)

        # For multi-channel, select channel 1
        if rec.get("channels", 1) > 1:
            ij.execute_macro(
                'run("Duplicate...", "title=thresh_ch1 duplicate channels=1");'
                'selectWindow("thresh_test"); close();'
            )
            time.sleep(0.3)

        # Run explore_thresholds
        resp = ij.explore_thresholds(THRESHOLD_METHODS)
        entry = {"file": filename}
        if resp.get("ok") and resp.get("result"):
            result = resp["result"]
            entry["recommended"] = result.get("recommended", "unknown")
            entry["methods"] = {}
            for method_result in result.get("results", []):
                name = method_result.get("method", "?")
                entry["methods"][name] = {
                    "foreground_pct": _safe_float(method_result.get("foregroundPercent")),
                    "count": _safe_int(method_result.get("particleCount")),
                }
        else:
            entry["error"] = resp.get("error", "explore failed")

        threshold_results.append(entry)
        _close_all(ij)

    return threshold_results


# ---------------------------------------------------------------------------
# Phase 3: Segmentation Testing
# ---------------------------------------------------------------------------

def test_segmentation(ij, image_records, image_dir):
    """Test classical and plugin-based segmentation on representative images."""
    candidates = [r for r in image_records if "error" not in r]
    if len(candidates) > 3:
        candidates = random.sample(candidates, 3)

    seg_results = []
    for i, rec in enumerate(candidates):
        filepath = rec.get("path", "")
        filename = rec.get("file", "unknown")
        print("  Segmentation testing {}/{}: {}".format(i + 1, len(candidates), filename))

        entry = {"file": filename, "approaches": {}}

        # --- Classical: Otsu + Analyze Particles ---
        _close_all(ij)
        if _open_image(ij, filepath):
            # Duplicate and convert to single channel if needed
            dup_macro = 'run("Duplicate...", "title=seg_test duplicate");'
            if rec.get("channels", 1) > 1:
                dup_macro = 'run("Duplicate...", "title=seg_test duplicate channels=1");'
            ij.execute_macro(dup_macro)
            time.sleep(0.3)

            # Blur + threshold + watershed + analyze
            macro = (
                'run("Gaussian Blur...", "sigma=1.5");'
                'setAutoThreshold("Otsu dark");'
                'run("Convert to Mask");'
                'run("Watershed");'
                'run("Set Measurements...", "area mean min centroid shape redirect=None decimal=3");'
                'run("Analyze Particles...", "size=50-Infinity display summarize");'
            )
            resp = ij.execute_macro(macro)
            classical = {"method": "Otsu+Watershed", "sigma": 1.5, "min_size": 50}
            if resp.get("ok"):
                result = resp.get("result", {})
                table = result.get("resultsTable", "")
                if table:
                    lines = [l for l in table.strip().split("\n") if l.strip()]
                    classical["count"] = max(0, len(lines) - 1)  # subtract header
                else:
                    classical["count"] = 0
                classical["success"] = True
                classical["time_ms"] = _safe_int(result.get("executionTimeMs"))
            else:
                classical["success"] = False
                classical["error"] = resp.get("error", "")
            entry["approaches"]["classical_otsu"] = classical

        # --- StarDist 2D (if available) ---
        _close_all(ij)
        if _open_image(ij, filepath):
            dup_macro = 'run("Duplicate...", "title=sd_test duplicate");'
            if rec.get("channels", 1) > 1:
                dup_macro = 'run("Duplicate...", "title=sd_test duplicate channels=1");'
            ij.execute_macro(dup_macro)
            time.sleep(0.3)

            # For z-stacks, make a max projection first (StarDist 2D needs 2D)
            if rec.get("slices", 1) > 1:
                ij.execute_macro(
                    'run("Z Project...", "projection=[Max Intensity]");'
                    'selectWindow("sd_test"); close();'
                )
                time.sleep(0.3)

            resp = ij.execute_macro(
                'run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], '
                'args=[\'modelChoice\':\'Versatile (fluorescent nuclei)\', '
                '\'normalizeInput\':\'true\', \'percentileBottom\':\'1.0\', '
                '\'percentileTop\':\'99.8\', \'probThresh\':\'0.5\', '
                '\'nmsThresh\':\'0.4\', \'outputType\':\'Both\', '
                '\'nTiles\':\'1\', \'excludeBoundary\':\'2\', '
                '\'verbose\':\'false\', \'showCsb498de498works498702702hProgress\':\'false\'], '
                'process=[false]");'
            )
            stardist = {"method": "StarDist2D"}
            # StarDist may not be available or may fail
            if resp.get("ok"):
                result = resp.get("result", {})
                if result.get("success"):
                    # Count ROIs in ROI Manager
                    roi_resp = ij.execute_macro(
                        'count = roiManager("count"); print(count);'
                    )
                    log_resp = ij.get_log()
                    if log_resp.get("ok") and log_resp.get("result"):
                        log_text = log_resp["result"].strip()
                        # Last line should be the count
                        last_line = log_text.strip().split("\n")[-1].strip()
                        stardist["count"] = _safe_int(last_line)
                    stardist["success"] = True
                else:
                    stardist["success"] = False
                    stardist["error"] = result.get("error", "execution failed")
            else:
                stardist["success"] = False
                stardist["error"] = resp.get("error", "not available")
            entry["approaches"]["stardist"] = stardist

        # --- 3D Objects Counter (if z-stack) ---
        if rec.get("slices", 1) > 1:
            _close_all(ij)
            if _open_image(ij, filepath):
                dup_macro = 'run("Duplicate...", "title=obj3d_test duplicate");'
                if rec.get("channels", 1) > 1:
                    dup_macro = 'run("Duplicate...", "title=obj3d_test duplicate channels=1");'
                ij.execute_macro(dup_macro)
                time.sleep(0.3)

                macro = (
                    'run("Gaussian Blur 3D...", "x=2 y=2 z=1");'
                    'setAutoThreshold("Otsu dark");'
                    'run("Convert to Mask", "method=Otsu background=Dark");'
                    'run("3D Objects Counter", '
                    '"threshold=128 min.=100 max.=9999999 objects");'
                )
                resp = ij.execute_macro(macro)
                obj3d = {"method": "3D_Objects_Counter"}
                if resp.get("ok"):
                    result = resp.get("result", {})
                    if result.get("success"):
                        # Check results table for object count
                        table_resp = ij.get_results_table()
                        if table_resp.get("ok") and table_resp.get("result"):
                            lines = [l for l in table_resp["result"].strip().split("\n") if l.strip()]
                            obj3d["count"] = max(0, len(lines) - 1)
                        obj3d["success"] = True
                    else:
                        obj3d["success"] = False
                        obj3d["error"] = result.get("error", "")
                else:
                    obj3d["success"] = False
                    obj3d["error"] = resp.get("error", "")
                entry["approaches"]["3d_objects_counter"] = obj3d

        seg_results.append(entry)
        _close_all(ij)

    return seg_results


# ---------------------------------------------------------------------------
# Phase 4: Parameter Tuning
# ---------------------------------------------------------------------------

def tune_parameters(ij, image_records, best_threshold):
    """Vary blur sigma and min particle size to find optimal parameters."""
    candidates = [r for r in image_records if "error" not in r]
    if len(candidates) > 3:
        candidates = random.sample(candidates, 3)

    best_params = {"sigma": 1.5, "min_size": 50, "threshold": best_threshold}
    all_runs = []

    for i, rec in enumerate(candidates):
        filepath = rec.get("path", "")
        filename = rec.get("file", "unknown")
        print("  Tuning on {}/{}: {}".format(i + 1, len(candidates), filename))

        for sigma in SEGMENTATION_BLUR_SIGMAS:
            for min_size in PARTICLE_MIN_SIZES:
                _close_all(ij)
                if not _open_image(ij, filepath):
                    continue

                dup_macro = 'run("Duplicate...", "title=tune_test duplicate");'
                if rec.get("channels", 1) > 1:
                    dup_macro = 'run("Duplicate...", "title=tune_test duplicate channels=1");'
                ij.execute_macro(dup_macro)
                time.sleep(0.2)

                # For z-stacks, max-project first for 2D particle analysis
                if rec.get("slices", 1) > 1:
                    ij.execute_macro(
                        'run("Z Project...", "projection=[Max Intensity]");'
                        'selectWindow("tune_test"); close();'
                    )
                    time.sleep(0.2)

                macro = (
                    'run("Gaussian Blur...", "sigma={sigma}");'
                    'setAutoThreshold("{thresh} dark");'
                    'run("Convert to Mask");'
                    'run("Watershed");'
                    'run("Set Measurements...", "area mean min centroid shape redirect=None decimal=3");'
                    'run("Analyze Particles...", "size={min_size}-Infinity display");'
                ).format(sigma=sigma, thresh=best_threshold, min_size=min_size)

                resp = ij.execute_macro(macro)
                count = 0
                if resp.get("ok"):
                    result = resp.get("result", {})
                    table = result.get("resultsTable", "")
                    if table:
                        lines = [l for l in table.strip().split("\n") if l.strip()]
                        count = max(0, len(lines) - 1)

                all_runs.append({
                    "file": filename,
                    "sigma": sigma,
                    "min_size": min_size,
                    "count": count,
                })

    # Find the parameter combo with the most consistent count across images
    # Group by (sigma, min_size) and compute coefficient of variation
    if all_runs:
        param_groups = {}
        for run in all_runs:
            key = (run["sigma"], run["min_size"])
            if key not in param_groups:
                param_groups[key] = []
            param_groups[key].append(run["count"])

        best_cv = float("inf")
        best_key = (1.5, 50)
        for key, counts in param_groups.items():
            if len(counts) < 2:
                continue
            # Skip combos that find nothing
            mean_count = sum(counts) / len(counts)
            if mean_count < 1:
                continue
            std_count = (sum((c - mean_count) ** 2 for c in counts) / len(counts)) ** 0.5
            cv = std_count / mean_count if mean_count > 0 else float("inf")
            if cv < best_cv:
                best_cv = cv
                best_key = key

        best_params["sigma"] = best_key[0]
        best_params["min_size"] = best_key[1]
        best_params["cv"] = round(best_cv, 3) if best_cv != float("inf") else None

    _close_all(ij)
    return best_params


# ---------------------------------------------------------------------------
# Phase 5: Write Learnings
# ---------------------------------------------------------------------------

def build_profile(image_dir, image_records, threshold_results, seg_results,
                  tuned_params, domain=None):
    """Assemble the lab_profile.json structure."""
    valid = [r for r in image_records if "error" not in r]

    # Aggregate statistics
    formats = [r.get("format", "") for r in valid]
    bit_depths = [r.get("type", "") for r in valid]
    channel_counts = [r.get("channels", 1) for r in valid]
    slice_counts = [r.get("slices", 1) for r in valid]
    snr_values = [r.get("snr", 0) for r in valid if r.get("snr", 0) > 0]

    # Best threshold per image
    best_thresholds = [t.get("recommended", "unknown") for t in threshold_results
                       if "error" not in t and t.get("recommended")]

    # Best segmentation approach
    seg_summary = {}
    for seg in seg_results:
        for name, approach in seg.get("approaches", {}).items():
            if approach.get("success"):
                if name not in seg_summary:
                    seg_summary[name] = []
                seg_summary[name].append(approach.get("count", 0))

    profile = {
        "lab_name": _lab_name_from_path(image_dir),
        "training_date": datetime.now().strftime("%Y-%m-%d"),
        "training_dir": image_dir,
        "domain": domain or "general",
        "image_count": len(valid),
        "errors": len(image_records) - len(valid),
        "common_format": _most_common(formats) or "unknown",
        "common_bit_depth": _most_common(bit_depths) or "unknown",
        "common_channels": _safe_int(_most_common(channel_counts)),
        "has_z_stacks": any(s > 1 for s in slice_counts),
        "typical_z_slices": _safe_int(_median([s for s in slice_counts if s > 1])),
        "has_timelapse": any(r.get("frames", 1) > 1 for r in valid),
        "calibration": {},
        "typical_snr": round(_median(snr_values), 2) if snr_values else 0,
        "modality": _guess_modality(valid),
        "saturation_warning": any(r.get("saturation_pct", 0) > 0.5 for r in valid),
        "best_threshold": _most_common(best_thresholds) or "Otsu",
        "threshold_results": best_thresholds,
        "segmentation": {},
        "optimal_params": tuned_params,
        "images": valid,
    }

    # Calibration from first calibrated image
    for r in valid:
        if r.get("has_calibration"):
            profile["calibration"] = {
                "pixel_size": r.get("pixel_width", 1.0),
                "unit": r.get("pixel_unit", "pixel"),
                "z_spacing": r.get("z_spacing", 1.0),
            }
            break

    # Object density estimate from tuning results
    if tuned_params and valid:
        avg_area = _median([r.get("width", 0) * r.get("height", 0) for r in valid])
        # Use the count from the tuned parameters' typical run
        sample_counts = [run.get("count", 0) for run in seg_results
                         for a in run.get("approaches", {}).values()
                         if a.get("success") and a.get("count", 0) > 0]
        if sample_counts:
            avg_count = _median(sample_counts)
            profile["object_density"] = _guess_density(avg_count, avg_area)
        else:
            profile["object_density"] = "unknown"

    # Segmentation comparison
    for name, counts in seg_summary.items():
        profile["segmentation"][name] = {
            "avg_count": round(sum(counts) / len(counts), 1) if counts else 0,
            "images_tested": len(counts),
        }

    return profile


def write_learnings(profile):
    """Append lab-specific learnings to learnings.md."""
    lab_name = profile.get("lab_name", "Unknown")
    section_header = "### Lab-Specific: {} Image Profile".format(lab_name)

    lines = []
    lines.append("")
    lines.append(section_header)
    lines.append("- Training date: {}".format(profile.get("training_date")))
    lines.append("- Domain: {}".format(profile.get("domain", "general")))
    lines.append("- Images: {} {}, {}-channel{}".format(
        profile.get("common_bit_depth", "?"),
        profile.get("modality", "unknown"),
        profile.get("common_channels", 1),
        ", z-stacks ({} slices)".format(profile.get("typical_z_slices"))
        if profile.get("has_z_stacks") else "",
    ))

    cal = profile.get("calibration", {})
    if cal.get("unit") and cal["unit"] not in ("pixel", ""):
        lines.append("- Pixel size: {} {}/px".format(cal.get("pixel_size", "?"), cal["unit"]))
        if profile.get("has_z_stacks"):
            lines.append("- Z spacing: {} {}".format(cal.get("z_spacing", "?"), cal["unit"]))

    lines.append("- Typical SNR: {}".format(profile.get("typical_snr", "?")))

    if profile.get("saturation_warning"):
        lines.append("- WARNING: Some images show saturation (>0.5% at max intensity)")

    lines.append("- Best threshold: {}".format(profile.get("best_threshold", "Otsu")))

    params = profile.get("optimal_params", {})
    if params:
        lines.append("- Optimal blur sigma: {}".format(params.get("sigma", "?")))
        lines.append("- Optimal min particle size: {} px".format(params.get("min_size", "?")))

    seg = profile.get("segmentation", {})
    for name, info in seg.items():
        lines.append("- {}: avg {} objects ({} images tested)".format(
            name, info.get("avg_count", "?"), info.get("images_tested", "?")))

    lines.append("")

    # Read existing learnings and check if this lab section already exists
    existing = ""
    if os.path.exists(LEARNINGS_PATH):
        with open(LEARNINGS_PATH, "r", encoding="utf-8") as f:
            existing = f.read()

    # Remove old section for this lab if present
    if section_header in existing:
        # Find the section and remove it (up to next ### or end)
        start = existing.index(section_header)
        # Find next section or end
        rest = existing[start + len(section_header):]
        next_section = rest.find("\n### ")
        if next_section >= 0:
            end = start + len(section_header) + next_section
        else:
            end = len(existing)
        existing = existing[:start].rstrip() + existing[end:]

    # Append new section
    with open(LEARNINGS_PATH, "w", encoding="utf-8") as f:
        f.write(existing.rstrip() + "\n" + "\n".join(lines))

    print("  Updated {}".format(LEARNINGS_PATH))


# ---------------------------------------------------------------------------
# Main pipeline
# ---------------------------------------------------------------------------

def train(image_dir, domain=None):
    """Run all training phases."""
    ij = _import_ij()
    _connect_or_die(ij)

    image_dir = os.path.abspath(image_dir)
    if not os.path.isdir(image_dir):
        print("ERROR: Not a directory: {}".format(image_dir))
        sys.exit(1)

    # Find images
    all_files = []
    for root, dirs, files in os.walk(image_dir):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in IMAGE_EXTENSIONS:
                all_files.append(os.path.join(root, f))
        # Don't recurse into subdirectories more than 1 level deep
        if root != image_dir:
            dirs.clear()

    if not all_files:
        print("ERROR: No images found in {}".format(image_dir))
        print("Supported formats: {}".format(", ".join(sorted(IMAGE_EXTENSIONS))))
        sys.exit(1)

    # Sample if too many
    if len(all_files) > MAX_IMAGES:
        print("Found {} images, sampling {} randomly.".format(len(all_files), MAX_IMAGES))
        all_files = random.sample(all_files, MAX_IMAGES)
    else:
        print("Found {} images.".format(len(all_files)))

    # Ensure clean state
    _close_all(ij)

    # Phase 1: Image Characterization
    print("\n=== Phase 1: Image Characterization ===")
    image_records = []
    for i, filepath in enumerate(all_files):
        filename = os.path.basename(filepath)
        print("  Characterizing {}/{}: {}".format(i + 1, len(all_files), filename))
        record = characterize_image(ij, filepath)
        image_records.append(record)
        _close_all(ij)

    valid_count = sum(1 for r in image_records if "error" not in r)
    print("  Characterized {} images ({} errors).".format(valid_count, len(image_records) - valid_count))

    if valid_count == 0:
        print("ERROR: Could not open any images. Check file formats and Fiji connection.")
        sys.exit(1)

    # Phase 2: Threshold Discovery
    print("\n=== Phase 2: Threshold Discovery ===")
    threshold_results = discover_thresholds(ij, image_records, image_dir)
    best_thresholds = [t.get("recommended") for t in threshold_results if t.get("recommended")]
    best_threshold = _most_common(best_thresholds) or "Otsu"
    print("  Best threshold overall: {}".format(best_threshold))

    # Phase 3: Segmentation Testing
    print("\n=== Phase 3: Segmentation Testing ===")
    seg_results = test_segmentation(ij, image_records, image_dir)
    for seg in seg_results:
        print("  {}: {}".format(seg["file"],
              ", ".join("{} ({})".format(k, v.get("count", "fail"))
                        for k, v in seg.get("approaches", {}).items())))

    # Phase 4: Parameter Tuning
    print("\n=== Phase 4: Parameter Tuning ===")
    tuned_params = tune_parameters(ij, image_records, best_threshold)
    print("  Optimal: sigma={}, min_size={}, threshold={}".format(
        tuned_params.get("sigma"), tuned_params.get("min_size"),
        tuned_params.get("threshold")))

    # Phase 5: Build Profile & Write Learnings
    print("\n=== Phase 5: Writing Results ===")
    profile = build_profile(image_dir, image_records, threshold_results,
                            seg_results, tuned_params, domain)

    with open(PROFILE_PATH, "w", encoding="utf-8") as f:
        json.dump(profile, f, indent=2, default=str)
    print("  Saved {}".format(PROFILE_PATH))

    write_learnings(profile)

    # Clean up
    _close_all(ij)

    # Summary
    print("\n=== Training Complete ===")
    print("  Lab: {}".format(profile.get("lab_name")))
    print("  Images: {} ({})".format(profile.get("image_count"), profile.get("common_format")))
    print("  Modality: {}".format(profile.get("modality")))
    print("  Best threshold: {}".format(profile.get("best_threshold")))
    print("  Optimal sigma: {}".format(tuned_params.get("sigma")))
    print("  Optimal min size: {} px".format(tuned_params.get("min_size")))
    if profile.get("saturation_warning"):
        print("  WARNING: Some images are saturated!")
    print()
    print("Profile saved to: {}".format(PROFILE_PATH))
    print("Learnings updated: {}".format(LEARNINGS_PATH))


def show_profile():
    """Display the current lab profile."""
    if not os.path.exists(PROFILE_PATH):
        print("No lab profile found. Run training first:")
        print("  python train_agent.py /path/to/images")
        sys.exit(0)

    with open(PROFILE_PATH, "r", encoding="utf-8") as f:
        profile = json.load(f)

    print("=== Lab Profile ===")
    print("  Lab: {}".format(profile.get("lab_name")))
    print("  Trained: {}".format(profile.get("training_date")))
    print("  Domain: {}".format(profile.get("domain", "general")))
    print("  Directory: {}".format(profile.get("training_dir")))
    print()
    print("  Images: {} ({})".format(profile.get("image_count"), profile.get("common_format")))
    print("  Bit depth: {}".format(profile.get("common_bit_depth")))
    print("  Channels: {}".format(profile.get("common_channels")))
    print("  Modality: {}".format(profile.get("modality")))
    if profile.get("has_z_stacks"):
        print("  Z-stacks: {} slices typical".format(profile.get("typical_z_slices")))
    if profile.get("has_timelapse"):
        print("  Time-lapse: yes")
    print("  SNR: {}".format(profile.get("typical_snr")))
    cal = profile.get("calibration", {})
    if cal:
        print("  Pixel size: {} {}/px".format(cal.get("pixel_size", "?"),
                                               cal.get("unit", "pixel")))
    print()
    print("  Best threshold: {}".format(profile.get("best_threshold")))
    params = profile.get("optimal_params", {})
    if params:
        print("  Optimal sigma: {}".format(params.get("sigma")))
        print("  Optimal min size: {} px".format(params.get("min_size")))
    print("  Object density: {}".format(profile.get("object_density", "unknown")))
    print()
    seg = profile.get("segmentation", {})
    if seg:
        print("  Segmentation results:")
        for name, info in seg.items():
            print("    {}: avg {} objects ({} images)".format(
                name, info.get("avg_count"), info.get("images_tested")))

    if profile.get("saturation_warning"):
        print()
        print("  WARNING: Some images are saturated!")


def reset_profile():
    """Delete lab profile and remove lab-specific learnings from learnings.md."""
    removed = False

    if os.path.exists(PROFILE_PATH):
        # Read the lab name before deleting
        with open(PROFILE_PATH, "r", encoding="utf-8") as f:
            profile = json.load(f)
        lab_name = profile.get("lab_name", "")

        os.remove(PROFILE_PATH)
        print("Removed {}".format(PROFILE_PATH))
        removed = True

        # Remove the corresponding section from learnings.md
        if lab_name and os.path.exists(LEARNINGS_PATH):
            with open(LEARNINGS_PATH, "r", encoding="utf-8") as f:
                content = f.read()
            section_header = "### Lab-Specific: {} Image Profile".format(lab_name)
            if section_header in content:
                start = content.index(section_header)
                rest = content[start + len(section_header):]
                next_section = rest.find("\n### ")
                if next_section >= 0:
                    end = start + len(section_header) + next_section
                else:
                    end = len(content)
                content = content[:start].rstrip() + content[end:]
                with open(LEARNINGS_PATH, "w", encoding="utf-8") as f:
                    f.write(content)
                print("Removed lab section from {}".format(LEARNINGS_PATH))
    else:
        print("No lab profile to remove.")

    if removed:
        print("Lab-specific learnings cleared. Run training again to regenerate.")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Train ImageJAI agent on your lab's images",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python train_agent.py /path/to/lab/images
  python train_agent.py /path/to/lab/images --domain neuro
  python train_agent.py --profile
  python train_agent.py --reset
        """,
    )
    parser.add_argument("image_dir", nargs="?", help="Directory containing lab images")
    parser.add_argument("--domain", type=str, default=None,
                        help="Scientific domain (e.g., neuro, immuno, onco, cardio)")
    parser.add_argument("--profile", action="store_true",
                        help="Show current lab profile")
    parser.add_argument("--reset", action="store_true",
                        help="Clear lab-specific learnings")

    args = parser.parse_args()

    if args.profile:
        show_profile()
    elif args.reset:
        reset_profile()
    elif args.image_dir:
        train(args.image_dir, domain=args.domain)
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
