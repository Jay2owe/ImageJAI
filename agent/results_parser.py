#!/usr/bin/env python
"""
Parse and analyze ImageJ Results table data.

Parses CSV output from get_results_table into structured data, computes
summary statistics, detects outliers, and can export back to CSV.

Usage:
    from results_parser import parse_results, summarize
    data = parse_results(csv_string)
    print(summarize(data))
"""

import csv
import io
import math
import os


def parse_results(csv_string):
    """Parse CSV string from get_results_table into a list of dicts.

    Args:
        csv_string: CSV text with header row, as returned by get_results_table.

    Returns:
        List of dicts, one per row, with column names as keys and values
        converted to float where possible.
    """
    if not csv_string or not csv_string.strip():
        return []

    reader = csv.DictReader(io.StringIO(csv_string.strip()))
    rows = []
    for row in reader:
        parsed = {}
        for key, val in row.items():
            if key is None:
                continue
            key = key.strip()
            if val is None:
                parsed[key] = None
                continue
            val = val.strip()
            try:
                parsed[key] = float(val)
            except (ValueError, TypeError):
                parsed[key] = val
        rows.append(parsed)
    return rows


def numeric_columns(data):
    """Return list of column names that contain numeric data.

    Args:
        data: list of dicts as returned by parse_results.

    Returns:
        List of column name strings.
    """
    if not data:
        return []

    cols = []
    for key in data[0]:
        for row in data:
            val = row.get(key)
            if isinstance(val, float):
                cols.append(key)
                break
    return cols


def column_values(data, column):
    """Extract numeric values for a column, skipping None/non-numeric.

    Args:
        data: list of dicts.
        column: column name string.

    Returns:
        List of floats.
    """
    vals = []
    for row in data:
        v = row.get(column)
        if isinstance(v, (int, float)) and not math.isnan(v):
            vals.append(float(v))
    return vals


def _median(values):
    """Compute median of a sorted list of numbers."""
    if not values:
        return 0.0
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    else:
        return (s[n // 2 - 1] + s[n // 2]) / 2.0


def column_stats(data, column):
    """Compute summary statistics for a single column.

    Args:
        data: list of dicts.
        column: column name string.

    Returns:
        Dict with keys: count, mean, std, min, max, median.
        Returns None if no numeric values found.
    """
    vals = column_values(data, column)
    if not vals:
        return None

    n = len(vals)
    mean = sum(vals) / n
    variance = sum((v - mean) ** 2 for v in vals) / n if n > 1 else 0.0
    std = math.sqrt(variance)

    return {
        "count": n,
        "mean": round(mean, 4),
        "std": round(std, 4),
        "min": round(min(vals), 4),
        "max": round(max(vals), 4),
        "median": round(_median(vals), 4),
    }


def detect_outliers(data, column, threshold=3.0):
    """Find rows where column value is more than threshold SDs from mean.

    Args:
        data: list of dicts.
        column: column name string.
        threshold: number of standard deviations (default 3.0).

    Returns:
        List of dicts with keys: row_index, value, z_score.
    """
    vals = column_values(data, column)
    if len(vals) < 3:
        return []

    mean = sum(vals) / len(vals)
    variance = sum((v - mean) ** 2 for v in vals) / len(vals)
    std = math.sqrt(variance)

    if std == 0:
        return []

    outliers = []
    for i, row in enumerate(data):
        v = row.get(column)
        if not isinstance(v, (int, float)):
            continue
        z = abs(v - mean) / std
        if z > threshold:
            outliers.append({
                "row_index": i,
                "value": v,
                "z_score": round(z, 2),
            })
    return outliers


def summarize(data):
    """Return a human-readable summary string of the results table.

    Args:
        data: list of dicts as returned by parse_results.

    Returns:
        Formatted string with row count, column stats, and outliers.
    """
    if not data:
        return "No data (empty results table)."

    lines = []
    lines.append("Results: %d rows, %d columns" % (len(data), len(data[0])))
    lines.append("")

    num_cols = numeric_columns(data)
    for col in num_cols:
        stats = column_stats(data, col)
        if stats is None:
            continue
        lines.append("  %s:" % col)
        lines.append("    mean=%.4f  std=%.4f  min=%.4f  max=%.4f  median=%.4f  (n=%d)" % (
            stats["mean"], stats["std"], stats["min"], stats["max"],
            stats["median"], stats["count"]
        ))
        outliers = detect_outliers(data, col)
        if outliers:
            lines.append("    outliers (>3 SD): %d rows — indices %s" % (
                len(outliers),
                ", ".join(str(o["row_index"]) for o in outliers[:10])
            ))
    return "\n".join(lines)


def to_csv(data, path):
    """Save parsed data back to a CSV file.

    Args:
        data: list of dicts.
        path: output file path.
    """
    if not data:
        return

    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)

    columns = list(data[0].keys())
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=columns)
        writer.writeheader()
        writer.writerows(data)


if __name__ == "__main__":
    # Demo with sample data
    sample_csv = """\
Label,Area,Mean,StdDev,Min,Max,IntDen
1,523.0,128.5,45.2,12,255,67190.5
2,612.0,135.2,38.7,25,250,82742.4
3,480.0,122.8,52.1,8,248,58944.0
4,1890.0,200.1,15.3,180,240,378189.0
5,550.0,130.0,42.0,15,252,71500.0
6,498.0,126.3,48.5,10,253,62897.4
"""

    data = parse_results(sample_csv)
    print("Parsed %d rows\n" % len(data))
    print(summarize(data))

    # Save to CSV
    tmp_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp")
    out_path = os.path.join(tmp_dir, "results_demo.csv")
    to_csv(data, out_path)
    print("\nSaved to: %s" % out_path)
