#!/usr/bin/env python
"""
Measurement Auditor — validates ImageJ results tables for sanity.

Usage:
    python auditor.py                          # audit current results table
    python auditor.py --csv results.csv        # audit a CSV file
    python auditor.py --check area --unit um   # check specific measurement

From Python:
    from auditor import audit_results
    warnings = audit_results(csv_string, pixel_size=0.284, unit="um")
"""

import csv
import io
import json
import math
import os
import sys


# ---------------------------------------------------------------------------
# Parsing helpers (reuse logic from results_parser but keep auditor standalone)
# ---------------------------------------------------------------------------

def _parse_csv(csv_string):
    """Parse CSV string into list of dicts with numeric conversion."""
    if not csv_string or not csv_string.strip():
        return [], []
    reader = csv.DictReader(io.StringIO(csv_string.strip()))
    rows = []
    columns = None
    for row in reader:
        if columns is None:
            columns = [k.strip() for k in row.keys() if k is not None]
        parsed = {}
        for key, val in row.items():
            if key is None:
                continue
            key = key.strip()
            if val is None:
                parsed[key] = None
                continue
            val = val.strip()
            if val == "" or val.lower() == "nan":
                parsed[key] = None
                continue
            try:
                parsed[key] = float(val)
            except (ValueError, TypeError):
                parsed[key] = val
        rows.append(parsed)
    return rows, columns or []


def _col_values(rows, col):
    """Extract numeric values for a column, returning (values, indices)."""
    vals, idxs = [], []
    for i, row in enumerate(rows):
        v = row.get(col)
        if isinstance(v, (int, float)) and not math.isnan(v):
            vals.append(float(v))
            idxs.append(i)
    return vals, idxs


def _mean(vals):
    return sum(vals) / len(vals) if vals else 0.0


def _std(vals, m=None):
    if len(vals) < 2:
        return 0.0
    if m is None:
        m = _mean(vals)
    return math.sqrt(sum((v - m) ** 2 for v in vals) / len(vals))


def _median(vals):
    if not vals:
        return 0.0
    s = sorted(vals)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) / 2.0


# ---------------------------------------------------------------------------
# Individual check functions — each returns a dict with check/status/message
# ---------------------------------------------------------------------------

def _check_count(rows):
    """Validate object count."""
    n = len(rows)
    if n == 0:
        return {
            "check": "count",
            "status": "fail",
            "message": "No objects detected. Threshold may be wrong, or no image was open.",
            "affected_rows": [],
        }
    if n == 1:
        return {
            "check": "count",
            "status": "warning",
            "message": "Only 1 object detected — the entire foreground may be one blob (threshold too low?).",
            "affected_rows": [0],
        }
    if n > 10000:
        return {
            "check": "count",
            "status": "warning",
            "message": "%d objects detected — likely noise or debris. Increase min_size or blur more." % n,
            "affected_rows": [],
        }
    return {
        "check": "count",
        "status": "pass",
        "message": "%d objects detected." % n,
        "affected_rows": [],
    }


def _check_missing_data(rows, columns):
    """Flag NaN or missing values."""
    missing = []
    for i, row in enumerate(rows):
        for col in columns:
            v = row.get(col)
            if v is None:
                missing.append((i, col))
    if missing:
        affected = sorted(set(r for r, _ in missing))
        cols_affected = sorted(set(c for _, c in missing))
        return {
            "check": "missing_data",
            "status": "warning",
            "message": "%d missing/NaN values in columns: %s" % (len(missing), ", ".join(cols_affected)),
            "affected_rows": affected[:50],
        }
    return {
        "check": "missing_data",
        "status": "pass",
        "message": "No missing values.",
        "affected_rows": [],
    }


def _check_area_plausibility(rows, pixel_size, unit):
    """Check if areas are physically plausible."""
    vals, idxs = _col_values(rows, "Area")
    if not vals:
        return None  # no Area column

    # Convert pixel areas to physical units if calibration is provided
    if pixel_size and pixel_size > 0 and unit and unit.lower() in ("um", "µm", "micron", "microns"):
        # Areas are already in calibrated units from ImageJ if calibration was set
        areas_um2 = vals
    elif pixel_size and pixel_size > 0:
        areas_um2 = None  # non-micron units, skip plausibility
    else:
        areas_um2 = None  # no calibration, skip physical check

    warnings = []

    if areas_um2 is not None:
        large = [(idxs[j], v) for j, v in enumerate(areas_um2) if v > 5000]
        tiny = [(idxs[j], v) for j, v in enumerate(areas_um2) if v < 5]

        if large:
            warnings.append({
                "check": "area_plausibility",
                "status": "warning",
                "message": "%d objects have area > 5000 %s%s — possible merged cells or background." % (
                    len(large), unit or "px", "\u00B2"),
                "affected_rows": [r for r, _ in large[:50]],
            })
        if tiny:
            warnings.append({
                "check": "area_plausibility",
                "status": "warning",
                "message": "%d objects have area < 5 %s%s — likely noise or debris." % (
                    len(tiny), unit or "px", "\u00B2"),
                "affected_rows": [r for r, _ in tiny[:50]],
            })

    if not warnings:
        return {
            "check": "area_plausibility",
            "status": "pass",
            "message": "Area values look reasonable (n=%d, range %.1f–%.1f)." % (
                len(vals), min(vals), max(vals)),
            "affected_rows": [],
        }
    return warnings


def _check_circularity(rows):
    """Check circularity values."""
    vals, idxs = _col_values(rows, "Circ.")
    if not vals:
        # Try alternate column name
        vals, idxs = _col_values(rows, "Circularity")
    if not vals:
        return None

    out_of_range = [(idxs[j], v) for j, v in enumerate(vals) if v < 0 or v > 1]
    if out_of_range:
        return {
            "check": "circularity",
            "status": "fail",
            "message": "%d objects have circularity outside 0-1 range — data corruption?" % len(out_of_range),
            "affected_rows": [r for r, _ in out_of_range],
        }

    all_low = all(v < 0.1 for v in vals) and len(vals) > 3
    if all_low:
        return {
            "check": "circularity",
            "status": "warning",
            "message": "All circularity values < 0.1 — objects are extremely elongated. Likely segmentation artifacts.",
            "affected_rows": [],
        }

    return {
        "check": "circularity",
        "status": "pass",
        "message": "Circularity looks reasonable (mean=%.3f, range %.3f–%.3f)." % (
            _mean(vals), min(vals), max(vals)),
        "affected_rows": [],
    }


def _check_aspect_ratio(rows):
    """Flag extreme aspect ratios."""
    # ImageJ calls it "AR" in results
    vals, idxs = _col_values(rows, "AR")
    if not vals:
        vals, idxs = _col_values(rows, "Aspect Ratio")
    if not vals:
        return None

    extreme = [(idxs[j], v) for j, v in enumerate(vals) if v > 10]
    if extreme:
        return {
            "check": "aspect_ratio",
            "status": "warning",
            "message": "%d objects have aspect ratio > 10 — likely elongated artifacts or merged objects." % len(extreme),
            "affected_rows": [r for r, _ in extreme[:50]],
        }
    return {
        "check": "aspect_ratio",
        "status": "pass",
        "message": "Aspect ratios look normal (max=%.1f)." % max(vals),
        "affected_rows": [],
    }


def _check_intensity(rows, bit_depth=None):
    """Check mean intensity for issues."""
    vals, idxs = _col_values(rows, "Mean")
    if not vals:
        return None

    sat_max = 255.0 if bit_depth == 8 else (65535.0 if bit_depth == 16 else None)

    warnings = []

    zero_intensity = [(idxs[j], v) for j, v in enumerate(vals) if v == 0]
    if zero_intensity:
        warnings.append({
            "check": "intensity_zero",
            "status": "warning",
            "message": "%d objects have mean intensity = 0 — wrong channel or empty ROI?" % len(zero_intensity),
            "affected_rows": [r for r, _ in zero_intensity[:50]],
        })

    if sat_max is not None:
        saturated = [(idxs[j], v) for j, v in enumerate(vals) if v >= sat_max * 0.99]
        if saturated:
            warnings.append({
                "check": "intensity_saturated",
                "status": "warning",
                "message": "%d objects have mean intensity near saturation (%.0f) — image may be overexposed." % (
                    len(saturated), sat_max),
                "affected_rows": [r for r, _ in saturated[:50]],
            })

    if not warnings:
        return {
            "check": "intensity",
            "status": "pass",
            "message": "Intensity values look reasonable (mean=%.1f, range %.1f–%.1f)." % (
                _mean(vals), min(vals), max(vals)),
            "affected_rows": [],
        }
    return warnings


def _check_outliers(rows, columns):
    """Flag values > 3 SD from mean for each numeric column."""
    results = []
    numeric_cols = [c for c in columns if c not in ("Label", " ", "")]

    total_outliers = 0
    all_affected = []

    for col in numeric_cols:
        vals, idxs = _col_values(rows, col)
        if len(vals) < 5:
            continue

        m = _mean(vals)
        s = _std(vals, m)
        if s == 0:
            continue

        outliers = [(idxs[j], v, abs(v - m) / s) for j, v in enumerate(vals) if abs(v - m) / s > 3.0]
        if outliers:
            total_outliers += len(outliers)
            all_affected.extend([r for r, _, _ in outliers])

    n_rows = len(rows)
    unique_affected = sorted(set(all_affected))
    pct = (len(unique_affected) / float(n_rows) * 100) if n_rows > 0 else 0

    if pct > 20:
        return {
            "check": "outlier_detection",
            "status": "warning",
            "message": "%.0f%% of objects are outliers (>3 SD) — possible systematic issue with segmentation." % pct,
            "affected_rows": unique_affected[:50],
        }
    elif total_outliers > 0:
        return {
            "check": "outlier_detection",
            "status": "warning",
            "message": "%d outlier values across %d rows (>3 SD from mean)." % (total_outliers, len(unique_affected)),
            "affected_rows": unique_affected[:50],
        }
    return {
        "check": "outlier_detection",
        "status": "pass",
        "message": "No significant outliers detected (all within 3 SD).",
        "affected_rows": [],
    }


def _check_edge_bias(rows):
    """Check if objects near borders have different measurements (edge truncation)."""
    # Need X, Y centroids and Area
    x_vals, x_idxs = _col_values(rows, "X")
    y_vals, y_idxs = _col_values(rows, "Y")
    area_vals, area_idxs = _col_values(rows, "Area")

    if not x_vals or not y_vals or not area_vals or len(area_vals) < 10:
        return None

    # Build a row-indexed area lookup
    area_by_row = {}
    for j, idx in enumerate(area_idxs):
        area_by_row[idx] = area_vals[j]

    # Find image extent from centroids
    x_min, x_max = min(x_vals), max(x_vals)
    y_min, y_max = min(y_vals), max(y_vals)
    x_range = x_max - x_min
    y_range = y_max - y_min

    if x_range == 0 or y_range == 0:
        return None

    # Define edge as within 5% of the centroid range
    margin_x = x_range * 0.05
    margin_y = y_range * 0.05

    edge_areas = []
    center_areas = []

    for j, idx in enumerate(x_idxs):
        x = x_vals[j]
        # Find corresponding Y
        if idx not in area_by_row:
            continue
        y_val = None
        for k, yi in enumerate(y_idxs):
            if yi == idx:
                y_val = y_vals[k]
                break
        if y_val is None:
            continue

        a = area_by_row[idx]
        is_edge = (x - x_min < margin_x or x_max - x < margin_x or
                   y_val - y_min < margin_y or y_max - y_val < margin_y)
        if is_edge:
            edge_areas.append(a)
        else:
            center_areas.append(a)

    if len(edge_areas) < 3 or len(center_areas) < 3:
        return None

    edge_mean = _mean(edge_areas)
    center_mean = _mean(center_areas)

    if center_mean == 0:
        return None

    ratio = edge_mean / center_mean

    if ratio < 0.5:
        return {
            "check": "edge_bias",
            "status": "warning",
            "message": "Edge objects are %.0f%% smaller on average than center objects — likely truncated at borders. "
                       "Consider 'exclude edges' in Analyze Particles." % ((1 - ratio) * 100),
            "affected_rows": [],
        }
    return {
        "check": "edge_bias",
        "status": "pass",
        "message": "No significant edge truncation bias detected.",
        "affected_rows": [],
    }


def _check_intensity_gradient(rows):
    """Check for spatial gradient in intensity (uneven illumination)."""
    x_vals, x_idxs = _col_values(rows, "X")
    mean_vals, mean_idxs = _col_values(rows, "Mean")

    if not x_vals or not mean_vals or len(mean_vals) < 10:
        return None

    # Build lookup by row index
    mean_by_row = {}
    for j, idx in enumerate(mean_idxs):
        mean_by_row[idx] = mean_vals[j]

    # Pair X with Mean
    pairs = []
    for j, idx in enumerate(x_idxs):
        if idx in mean_by_row:
            pairs.append((x_vals[j], mean_by_row[idx]))

    if len(pairs) < 10:
        return None

    pairs.sort(key=lambda p: p[0])
    n = len(pairs)
    q = n // 4

    left_mean = _mean([p[1] for p in pairs[:q]])
    right_mean = _mean([p[1] for p in pairs[-q:]])

    overall_mean = _mean([p[1] for p in pairs])
    if overall_mean == 0:
        return None

    diff_pct = abs(left_mean - right_mean) / overall_mean * 100

    if diff_pct > 30:
        return {
            "check": "intensity_gradient",
            "status": "warning",
            "message": "%.0f%% intensity difference between left and right sides of image — "
                       "possible uneven illumination. Consider background subtraction." % diff_pct,
            "affected_rows": [],
        }
    return {
        "check": "intensity_gradient",
        "status": "pass",
        "message": "No significant spatial intensity gradient detected.",
        "affected_rows": [],
    }


def _check_photobleaching(rows):
    """Check if first/last measurements differ (photobleaching in time-lapse)."""
    mean_vals, _ = _col_values(rows, "Mean")
    if not mean_vals or len(mean_vals) < 20:
        return None

    n = len(mean_vals)
    q = max(n // 5, 3)

    first_mean = _mean(mean_vals[:q])
    last_mean = _mean(mean_vals[-q:])

    if first_mean == 0:
        return None

    drop_pct = (first_mean - last_mean) / first_mean * 100

    if drop_pct > 20:
        return {
            "check": "photobleaching",
            "status": "warning",
            "message": "Mean intensity drops %.0f%% from first to last measurements — "
                       "possible photobleaching in time-lapse." % drop_pct,
            "affected_rows": [],
        }
    return {
        "check": "photobleaching",
        "status": "pass",
        "message": "No photobleaching trend detected.",
        "affected_rows": [],
    }


def _check_bimodal(rows, columns):
    """Check if Area distribution is bimodal (two populations)."""
    vals, _ = _col_values(rows, "Area")
    if not vals or len(vals) < 20:
        return None

    # Simple bimodality test: check if there's a gap in the sorted values
    s = sorted(vals)
    n = len(s)
    m = _mean(s)
    sd = _std(s, m)
    if sd == 0:
        return None

    # Compute Sarle's bimodality coefficient: (skewness^2 + 1) / kurtosis
    # Simplified: check if the distribution has two clusters
    median_val = _median(s)

    below = [v for v in s if v < median_val]
    above = [v for v in s if v >= median_val]

    if not below or not above:
        return None

    below_sd = _std(below)
    above_sd = _std(above)

    # If both halves have low variance relative to the gap, it's bimodal
    gap = _mean(above) - _mean(below)
    if gap > 0 and (below_sd + above_sd) > 0:
        separation = gap / (below_sd + above_sd)
        if separation > 3.0:
            return {
                "check": "bimodal_distribution",
                "status": "warning",
                "message": "Area distribution appears bimodal (two distinct size populations). "
                           "This may indicate two cell types or a mix of cells and debris.",
                "affected_rows": [],
            }

    return None  # No bimodality detected, skip the check


# ---------------------------------------------------------------------------
# Main audit function
# ---------------------------------------------------------------------------

def audit_results(csv_string, pixel_size=None, unit=None, bit_depth=None, check=None):
    """Run all sanity checks on a results table.

    Args:
        csv_string: CSV text with header row.
        pixel_size: pixel size in physical units (e.g. 0.284 for 0.284 um/px).
        unit: physical unit string (e.g. "um").
        bit_depth: image bit depth (8, 16, 32) for saturation checks.
        check: if set, only run this specific check (e.g. "area", "outliers").

    Returns:
        Dict with status, checks list, and summary string.
    """
    rows, columns = _parse_csv(csv_string)

    all_checks = []

    # Count validation
    result = _check_count(rows)
    all_checks.append(result)
    if result["status"] == "fail" and len(rows) == 0:
        # No data — return immediately
        return {
            "status": "fail",
            "checks": all_checks,
            "summary": "No objects detected. Nothing to audit.",
        }

    # Missing data
    all_checks.append(_check_missing_data(rows, columns))

    # Area plausibility
    area_result = _check_area_plausibility(rows, pixel_size, unit)
    if area_result is not None:
        if isinstance(area_result, list):
            all_checks.extend(area_result)
        else:
            all_checks.append(area_result)

    # Circularity
    circ_result = _check_circularity(rows)
    if circ_result is not None:
        all_checks.append(circ_result)

    # Aspect ratio
    ar_result = _check_aspect_ratio(rows)
    if ar_result is not None:
        all_checks.append(ar_result)

    # Intensity
    int_result = _check_intensity(rows, bit_depth)
    if int_result is not None:
        if isinstance(int_result, list):
            all_checks.extend(int_result)
        else:
            all_checks.append(int_result)

    # Outliers
    all_checks.append(_check_outliers(rows, columns))

    # Edge bias
    edge_result = _check_edge_bias(rows)
    if edge_result is not None:
        all_checks.append(edge_result)

    # Intensity gradient
    grad_result = _check_intensity_gradient(rows)
    if grad_result is not None:
        all_checks.append(grad_result)

    # Photobleaching
    photo_result = _check_photobleaching(rows)
    if photo_result is not None:
        all_checks.append(photo_result)

    # Bimodal distribution
    bimodal_result = _check_bimodal(rows, columns)
    if bimodal_result is not None:
        all_checks.append(bimodal_result)

    # Filter to specific check if requested
    if check:
        check_lower = check.lower()
        all_checks = [c for c in all_checks if check_lower in c["check"].lower()]

    # Compute overall status
    statuses = [c["status"] for c in all_checks]
    if "fail" in statuses:
        overall = "fail"
    elif "warning" in statuses:
        overall = "warning"
    else:
        overall = "pass"

    n_warnings = statuses.count("warning")
    n_fails = statuses.count("fail")

    # Build summary
    if overall == "pass":
        summary = "All checks passed. Results look valid."
    else:
        parts = []
        if n_fails:
            parts.append("%d failure%s" % (n_fails, "s" if n_fails > 1 else ""))
        if n_warnings:
            parts.append("%d warning%s" % (n_warnings, "s" if n_warnings > 1 else ""))
        problem_messages = [c["message"] for c in all_checks if c["status"] in ("fail", "warning")]
        summary = "%s. %s" % (", ".join(parts).capitalize(), " ".join(problem_messages[:3]))

    return {
        "status": overall,
        "checks": all_checks,
        "summary": summary,
    }


def _format_report(result):
    """Format audit result as readable text."""
    lines = []
    lines.append("")
    lines.append("=" * 60)
    lines.append("  MEASUREMENT AUDIT REPORT")
    lines.append("=" * 60)
    lines.append("")

    status_icon = {"pass": "[PASS]", "warning": "[WARN]", "fail": "[FAIL]"}

    for check in result["checks"]:
        icon = status_icon.get(check["status"], "[????]")
        lines.append("  %s  %s" % (icon, check["check"]))
        lines.append("         %s" % check["message"])
        if check.get("affected_rows"):
            rows_str = ", ".join(str(r) for r in check["affected_rows"][:10])
            if len(check["affected_rows"]) > 10:
                rows_str += " ... (+%d more)" % (len(check["affected_rows"]) - 10)
            lines.append("         Rows: %s" % rows_str)
        lines.append("")

    lines.append("-" * 60)
    lines.append("  Overall: %s" % status_icon.get(result["status"], "???"))
    lines.append("  %s" % result["summary"])
    lines.append("=" * 60)
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main():
    import argparse

    parser = argparse.ArgumentParser(description="Audit ImageJ results table for measurement sanity.")
    parser.add_argument("--csv", help="Path to CSV file to audit")
    parser.add_argument("--check", help="Run only a specific check (e.g. area, outliers, intensity)")
    parser.add_argument("--unit", default=None, help="Physical unit (e.g. um)")
    parser.add_argument("--pixel-size", type=float, default=None, help="Pixel size in physical units")
    parser.add_argument("--bit-depth", type=int, default=None, help="Image bit depth (8, 16, 32)")
    parser.add_argument("--json", action="store_true", help="Output as JSON instead of formatted text")
    args = parser.parse_args()

    csv_string = None

    if args.csv:
        with open(args.csv, "r") as f:
            csv_string = f.read()
    else:
        # Fetch from ImageJ via ij.py
        try:
            script_dir = os.path.dirname(os.path.abspath(__file__))
            sys.path.insert(0, script_dir)
            from ij import imagej_command
            resp = imagej_command({"command": "get_results_table"})
            if resp.get("ok") and resp.get("result"):
                csv_string = resp["result"]
            else:
                print("No results table available in ImageJ.")
                sys.exit(1)

            # Also try to get image info for bit depth and calibration
            if args.bit_depth is None or args.pixel_size is None:
                info_resp = imagej_command({"command": "get_image_info"})
                if info_resp.get("ok") and info_resp.get("result"):
                    info = info_resp["result"]
                    if args.bit_depth is None:
                        type_str = info.get("type", "")
                        if "8" in type_str:
                            args.bit_depth = 8
                        elif "16" in type_str:
                            args.bit_depth = 16
                        elif "32" in type_str:
                            args.bit_depth = 32

                meta_resp = imagej_command({"command": "get_metadata"})
                if meta_resp.get("ok") and meta_resp.get("result"):
                    cal = meta_resp["result"].get("calibration", {})
                    if args.pixel_size is None and cal.get("pixelWidth"):
                        args.pixel_size = cal["pixelWidth"]
                    if args.unit is None and cal.get("unit"):
                        args.unit = cal["unit"]

        except Exception as e:
            print("Could not connect to ImageJ: %s" % e)
            print("Use --csv to audit a CSV file directly.")
            sys.exit(1)

    result = audit_results(
        csv_string,
        pixel_size=args.pixel_size,
        unit=args.unit,
        bit_depth=args.bit_depth,
        check=args.check,
    )

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(_format_report(result))


if __name__ == "__main__":
    main()
