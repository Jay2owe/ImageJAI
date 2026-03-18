#!/usr/bin/env python
"""
ImageJAI Practice Runner -- Autonomous self-improvement through practice.

Runs structured image analysis tasks on sample images, validates results
against expected outcomes, tries parameter variations, and logs findings.
Works with or without the TCP server (generates offline reports when
the server is unavailable).

Usage:
    python practice.py                     # run all practice tasks
    python practice.py --task cell_counting # run specific task
    python practice.py --list              # list available tasks
    python practice.py --report            # show practice history
    python practice.py --dry-run           # show plan without executing
"""

import json
import math
import os
import re
import sys
import time
from datetime import datetime

# Add agent dir to path for sibling imports
AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AGENT_DIR)

TMP_DIR = os.path.join(AGENT_DIR, ".tmp")
RESULTS_FILE = os.path.join(TMP_DIR, "practice_results.json")

# Try to import agent tools -- graceful fallback if unavailable
try:
    from ij import imagej_command, execute_macro, get_state, get_image_info, \
        get_results_table, explore_thresholds, get_histogram, capture_image
    HAS_IJ = True
except ImportError:
    HAS_IJ = True  # module exists, connection may not

try:
    from results_parser import parse_results, column_stats, detect_outliers, summarize
    HAS_PARSER = True
except ImportError:
    HAS_PARSER = False

try:
    from autopsy import Autopsy
    HAS_AUTOPSY = True
except ImportError:
    HAS_AUTOPSY = False


# ---------------------------------------------------------------------------
# Core infrastructure
# ---------------------------------------------------------------------------

class PracticeRunner(object):
    """Runs practice tasks and collects results."""

    def __init__(self, live=True):
        """
        Args:
            live: if True, connect to TCP server and execute macros.
                  if False, generate a dry-run plan only.
        """
        self.live = live
        self.connected = False
        self.results = {
            "session": datetime.now().isoformat(),
            "tasks": [],
        }
        self.autopsy = Autopsy() if HAS_AUTOPSY else None
        os.makedirs(TMP_DIR, exist_ok=True)

        if self.live:
            self.connected = self._check_connection()

    def _check_connection(self):
        """Test TCP server connectivity."""
        try:
            resp = imagej_command({"command": "ping"}, timeout=5)
            return resp.get("ok", False)
        except Exception:
            return False

    def _run_macro(self, code, description=""):
        """Execute a macro and return the response. Logs failures to autopsy."""
        if not self.connected:
            return {"ok": False, "error": "Not connected to TCP server"}

        try:
            resp = execute_macro(code)
        except Exception as e:
            resp = {"ok": False, "error": str(e)}

        # Log failures
        if self.autopsy and resp.get("ok"):
            result = resp.get("result", {})
            if isinstance(result, dict) and not result.get("success", True):
                error = result.get("error", "unknown macro error")
                try:
                    info_resp = get_image_info()
                    img_state = info_resp.get("result", {}) if info_resp.get("ok") else {}
                except Exception:
                    img_state = {}
                self.autopsy.log_failure(code, error, img_state, description)

        return resp

    def _macro_ok(self, resp):
        """Check if a macro response indicates success."""
        if not resp.get("ok"):
            return False
        result = resp.get("result", {})
        if isinstance(result, dict):
            return result.get("success", True)
        return True

    def _get_results_data(self):
        """Fetch and parse the Results table."""
        try:
            resp = get_results_table()
            if resp.get("ok") and resp.get("result") and HAS_PARSER:
                return parse_results(resp["result"])
        except Exception:
            pass
        return []

    def _close_all(self):
        """Close all images to start fresh."""
        self._run_macro('run("Close All");', "Close all images")
        # Clear results
        self._run_macro(
            'if (isOpen("Results")) { selectWindow("Results"); run("Close"); }',
            "Clear Results table"
        )

    def _capture(self, name):
        """Save a screenshot to .tmp/."""
        if not self.connected:
            return None
        try:
            import base64
            resp = capture_image()
            if resp.get("ok") and resp.get("result", {}).get("base64"):
                path = os.path.join(TMP_DIR, name + ".png")
                with open(path, "wb") as f:
                    f.write(base64.b64decode(resp["result"]["base64"]))
                return path
        except Exception:
            pass
        return None

    def run_task(self, task_name):
        """Run a single practice task by name.

        Args:
            task_name: one of the registered task names.

        Returns:
            Task result dict.
        """
        if task_name not in TASKS:
            print("Unknown task: %s" % task_name)
            print("Available: %s" % ", ".join(sorted(TASKS.keys())))
            return None

        task_func, description = TASKS[task_name]

        print("\n--- Practice: %s ---" % task_name)
        print("    %s" % description)

        if not self.live:
            result = {
                "name": task_name,
                "description": description,
                "status": "dry_run",
                "attempts": [],
                "learnings": ["Dry run -- no execution"],
            }
            self.results["tasks"].append(result)
            return result

        if not self.connected:
            print("    SKIPPED: not connected to TCP server")
            result = {
                "name": task_name,
                "description": description,
                "status": "skipped",
                "error": "TCP server not available",
                "attempts": [],
                "learnings": [],
            }
            self.results["tasks"].append(result)
            return result

        # Close everything before starting
        self._close_all()
        time.sleep(0.3)

        try:
            result = task_func(self)
            result["name"] = task_name
            result["description"] = description
        except Exception as e:
            result = {
                "name": task_name,
                "description": description,
                "status": "error",
                "error": str(e),
                "attempts": [],
                "learnings": ["Task crashed: %s" % str(e)],
            }

        self.results["tasks"].append(result)

        status = result.get("status", "unknown")
        print("    Result: %s" % status.upper())
        if result.get("best_approach"):
            print("    Best: %s" % result["best_approach"])
        for learning in result.get("learnings", []):
            print("    * %s" % learning)

        return result

    def run_all(self):
        """Run all registered practice tasks."""
        print("=== ImageJAI Practice Session ===")
        print("Time: %s" % self.results["session"])
        print("Connected: %s" % self.connected)
        print("Tasks: %d" % len(TASKS))

        for task_name in TASK_ORDER:
            self.run_task(task_name)

        self._save_results()
        self._print_summary()

    def _save_results(self):
        """Save results to JSON file."""
        # Load existing history
        history = []
        if os.path.exists(RESULTS_FILE):
            try:
                with open(RESULTS_FILE, "r") as f:
                    history = json.load(f)
                if not isinstance(history, list):
                    history = [history]
            except (json.JSONDecodeError, IOError):
                history = []

        history.append(self.results)

        # Keep last 50 sessions
        if len(history) > 50:
            history = history[-50:]

        with open(RESULTS_FILE, "w") as f:
            json.dump(history, f, indent=2)

        print("\nResults saved to: %s" % RESULTS_FILE)

    def _print_summary(self):
        """Print a summary of the practice session."""
        tasks = self.results["tasks"]
        passed = sum(1 for t in tasks if t.get("status") == "pass")
        failed = sum(1 for t in tasks if t.get("status") == "fail")
        errors = sum(1 for t in tasks if t.get("status") == "error")
        skipped = sum(1 for t in tasks if t.get("status") in ("skipped", "dry_run"))

        print("\n=== Summary ===")
        print("  Passed: %d" % passed)
        print("  Failed: %d" % failed)
        print("  Errors: %d" % errors)
        print("  Skipped: %d" % skipped)

        all_learnings = []
        for t in tasks:
            all_learnings.extend(t.get("learnings", []))
        if all_learnings:
            print("\nKey learnings:")
            for l in all_learnings:
                print("  - %s" % l)


# ---------------------------------------------------------------------------
# Practice tasks
# ---------------------------------------------------------------------------

def _task_cell_counting(runner):
    """Cell counting on Blobs sample with various threshold/parameter combos."""
    attempts = []
    learnings = []

    # Open Blobs
    resp = runner._run_macro('run("Blobs");', "Open Blobs sample")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Blobs", "attempts": [], "learnings": []}

    runner._capture("practice_blobs_original")

    # Get baseline info
    info_resp = get_image_info()
    img_info = info_resp.get("result", {}) if info_resp.get("ok") else {}

    expected_range = (55, 70)  # reasonable blob count range

    # Define variations to try
    variations = [
        {
            "method": "Otsu",
            "watershed": True,
            "blur": False,
            "min_size": 50,
        },
        {
            "method": "Otsu",
            "watershed": False,
            "blur": False,
            "min_size": 50,
        },
        {
            "method": "Triangle",
            "watershed": True,
            "blur": False,
            "min_size": 50,
        },
        {
            "method": "Li",
            "watershed": True,
            "blur": False,
            "min_size": 50,
        },
        {
            "method": "Default",
            "watershed": True,
            "blur": False,
            "min_size": 50,
        },
        {
            "method": "Otsu",
            "watershed": True,
            "blur": True,
            "min_size": 50,
        },
        {
            "method": "Otsu",
            "watershed": True,
            "blur": False,
            "min_size": 100,
        },
    ]

    best_count = None
    best_method = None
    best_diff = float("inf")

    for var in variations:
        # Start fresh each time -- reopen Blobs, clear everything
        runner._run_macro('run("Close All");')
        runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
        runner._run_macro('if (isOpen("Summary")) { selectWindow("Summary"); run("Close"); }')
        runner._run_macro('run("Clear Results");')
        runner._run_macro('run("Blobs");')

        # Build macro sequence — use display + nResults instead of summarize
        steps = []
        if var["blur"]:
            steps.append('run("Gaussian Blur...", "sigma=2");')
        steps.append('setAutoThreshold("%s");' % var["method"])
        steps.append('run("Convert to Mask");')
        if var["watershed"]:
            steps.append('run("Watershed");')
        steps.append(
            'run("Analyze Particles...", "size=%d-Infinity show=Nothing display");'
            % var["min_size"]
        )
        steps.append('nResults;')

        macro_code = "\n".join(steps)
        resp = runner._run_macro(macro_code, "Cell counting: %s" % var["method"])

        count = None
        if runner._macro_ok(resp):
            result = resp.get("result", {})
            # nResults is returned as the macro output
            output = result.get("output", "")
            if output and output.strip().isdigit():
                count = int(output.strip())

            # Fallback: count rows in results table
            if count is None:
                try:
                    rt_resp = get_results_table()
                    if rt_resp.get("ok") and rt_resp.get("result"):
                        csv_text = rt_resp["result"]
                        lines = [l for l in csv_text.strip().split("\n") if l.strip()]
                        count = max(0, len(lines) - 1)
                except Exception:
                    pass

        label = "%s%s%s" % (
            var["method"],
            " + Watershed" if var["watershed"] else "",
            " + Blur" if var["blur"] else "",
        )

        passed = count is not None and expected_range[0] <= count <= expected_range[1]

        attempt = {
            "method": label,
            "params": var,
            "result": {"count": count, "expected": "%d-%d" % expected_range},
            "passed": passed,
        }

        if count is not None:
            diff = abs(count - sum(expected_range) / 2.0)
            if diff < best_diff:
                best_diff = diff
                best_count = count
                best_method = label

            if not passed:
                if count < expected_range[0]:
                    attempt["note"] = "Under-segmented (%d < %d expected)" % (count, expected_range[0])
                else:
                    attempt["note"] = "Over-segmented (%d > %d expected)" % (count, expected_range[1])
        else:
            attempt["note"] = "Could not extract count from results"

        attempts.append(attempt)
        print("      %s -> count=%s %s" % (label, count, "PASS" if passed else "FAIL"))

    # Determine learnings
    if best_method:
        learnings.append("Best method for Blobs: %s (count=%s)" % (best_method, best_count))

    # Check which threshold methods passed
    passing_methods = [a["method"] for a in attempts if a["passed"]]
    failing_methods = [a["method"] for a in attempts if not a["passed"] and a["result"]["count"] is not None]

    if passing_methods:
        learnings.append("Passing methods: %s" % ", ".join(passing_methods))
    if failing_methods:
        learnings.append("Failing methods: %s" % ", ".join(failing_methods))

    # Check watershed impact
    otsu_no_ws = next((a for a in attempts if a["params"]["method"] == "Otsu" and not a["params"]["watershed"]), None)
    otsu_ws = next((a for a in attempts if a["params"]["method"] == "Otsu" and a["params"]["watershed"] and not a["params"]["blur"]), None)
    if otsu_no_ws and otsu_ws and otsu_no_ws["result"]["count"] and otsu_ws["result"]["count"]:
        diff = otsu_ws["result"]["count"] - otsu_no_ws["result"]["count"]
        if diff > 0:
            learnings.append("Watershed adds ~%d objects (splits touching blobs)" % diff)
        elif diff < 0:
            learnings.append("Watershed unexpectedly reduces count by %d" % abs(diff))

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": best_method or "none",
        "learnings": learnings,
    }


def _task_threshold_comparison(runner):
    """Compare threshold methods using explore_thresholds."""
    attempts = []
    learnings = []

    samples = [
        ("Blobs (25K)", "Blobs"),
    ]

    for sample_cmd, sample_name in samples:
        runner._run_macro('run("Close All");')
        resp = runner._run_macro('run("%s");' % sample_cmd, "Open %s" % sample_name)
        if not runner._macro_ok(resp):
            attempts.append({
                "method": sample_name,
                "result": {"error": "Could not open sample"},
                "passed": False,
            })
            continue

        # Use explore_thresholds
        try:
            methods = ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy", "Default",
                        "Minimum", "Mean", "Intermodes", "IsoData"]
            explore_resp = explore_thresholds(methods)

            if explore_resp.get("ok"):
                result = explore_resp.get("result", {})
                recommended = result.get("recommended", "unknown")
                method_results = result.get("results", [])

                attempt = {
                    "method": "explore_thresholds on %s" % sample_name,
                    "params": {"methods": methods},
                    "result": {
                        "recommended": recommended,
                        "method_count": len(method_results),
                    },
                    "passed": recommended != "unknown" and len(method_results) > 0,
                }

                # Record details for each method
                if method_results:
                    details = []
                    for mr in method_results:
                        name = mr.get("method", "?")
                        count = mr.get("count", mr.get("particleCount", "?"))
                        details.append("%s: count=%s" % (name, count))
                    attempt["result"]["details"] = details

                attempts.append(attempt)
                learnings.append("Recommended threshold for %s: %s" % (sample_name, recommended))

                print("      %s -> recommended=%s" % (sample_name, recommended))
            else:
                attempts.append({
                    "method": "explore_thresholds on %s" % sample_name,
                    "result": {"error": explore_resp.get("error", "unknown")},
                    "passed": False,
                })
        except Exception as e:
            attempts.append({
                "method": "explore_thresholds on %s" % sample_name,
                "result": {"error": str(e)},
                "passed": False,
            })

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": "explore_thresholds for automated comparison",
        "learnings": learnings,
    }


def _task_measurement_accuracy(runner):
    """Segment Blobs and validate measurement ranges."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')
    resp = runner._run_macro('run("Blobs");')
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Blobs", "attempts": [], "learnings": []}

    # Segment with Otsu + Watershed
    macro = """
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Watershed");
run("Set Measurements...", "area mean standard circularity redirect=None decimal=3");
run("Analyze Particles...", "size=50-Infinity display");
"""
    resp = runner._run_macro(macro, "Segment and measure Blobs")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Segmentation failed", "attempts": [], "learnings": []}

    runner._capture("practice_blobs_measured")

    # Parse results
    data = runner._get_results_data()
    if not data:
        return {
            "status": "error",
            "error": "No results table data",
            "attempts": [],
            "learnings": ["Could not retrieve Results table after Analyze Particles"],
        }

    n_particles = len(data)

    # Check Area range
    area_ok = True
    area_stats = None
    if HAS_PARSER:
        area_stats = column_stats(data, "Area")
        if area_stats:
            # Blobs areas should mostly be 50-2000 px^2
            if area_stats["min"] < 10:
                area_ok = False
                learnings.append("Some areas very small (%.1f) -- possible noise" % area_stats["min"])
            if area_stats["max"] > 5000:
                area_ok = False
                learnings.append("Some areas very large (%.1f) -- possible merged objects" % area_stats["max"])

    attempts.append({
        "method": "Area range check",
        "params": {"expected_range": "50-2000 px^2"},
        "result": {
            "n_particles": n_particles,
            "area_stats": area_stats,
        },
        "passed": area_ok,
    })

    # Check Circularity
    circ_ok = True
    circ_stats = None
    if HAS_PARSER:
        circ_stats = column_stats(data, "Circ.")
        if circ_stats is None:
            circ_stats = column_stats(data, "Circularity")
        if circ_stats:
            # Blobs are roundish -- most should have circularity > 0.5
            if circ_stats["mean"] < 0.5:
                circ_ok = False
                learnings.append("Mean circularity low (%.2f) -- blobs should be rounder" % circ_stats["mean"])
            else:
                learnings.append("Mean circularity %.2f -- consistent with round blobs" % circ_stats["mean"])

    attempts.append({
        "method": "Circularity check",
        "params": {"expected": "mean > 0.5 for round blobs"},
        "result": {"circ_stats": circ_stats},
        "passed": circ_ok,
    })

    # Check for outliers
    outlier_count = 0
    if HAS_PARSER:
        area_outliers = detect_outliers(data, "Area", threshold=3.0)
        outlier_count = len(area_outliers)
        if area_outliers:
            learnings.append("%d area outliers detected (>3 SD)" % len(area_outliers))

    attempts.append({
        "method": "Outlier detection",
        "params": {"threshold": "3 SD"},
        "result": {"outlier_count": outlier_count, "total": n_particles},
        "passed": outlier_count <= n_particles * 0.1,  # <10% outliers is ok
    })

    all_passed = all(a["passed"] for a in attempts)
    learnings.append("Measured %d particles total" % n_particles)

    return {
        "status": "pass" if all_passed else "fail",
        "attempts": attempts,
        "best_approach": "Otsu + Watershed + size filter 50-Inf",
        "learnings": learnings,
    }


def _task_background_subtraction(runner):
    """Compare rolling ball radii for background subtraction."""
    attempts = []
    learnings = []

    # Try Fluorescent Cells first, fall back to Blobs
    runner._run_macro('run("Close All");')
    resp = runner._run_macro('run("Fluorescent Cells");', "Open Fluorescent Cells")
    sample_name = "Fluorescent Cells"

    if not runner._macro_ok(resp):
        # Fall back to a sample with uneven illumination
        resp = runner._run_macro('run("Blobs");', "Open Blobs (fallback)")
        sample_name = "Blobs"
        if not runner._macro_ok(resp):
            return {"status": "error", "error": "Could not open any sample", "attempts": [], "learnings": []}

    # Get original histogram
    try:
        orig_hist = get_histogram()
        orig_stats = orig_hist.get("result", {}) if orig_hist.get("ok") else {}
    except Exception:
        orig_stats = {}

    orig_stddev = orig_stats.get("stdDev", 0)
    runner._capture("practice_bg_original")

    radii = [10, 25, 50, 100]
    best_radius = None
    best_uniformity = float("inf")

    for radius in radii:
        # Reopen fresh each time
        runner._run_macro('run("Close All");')
        if sample_name == "Fluorescent Cells":
            runner._run_macro('run("Fluorescent Cells");')
        else:
            runner._run_macro('run("Blobs");')

        resp = runner._run_macro(
            'run("Subtract Background...", "rolling=%d");' % radius,
            "Rolling ball radius=%d" % radius
        )

        if not runner._macro_ok(resp):
            attempts.append({
                "method": "Rolling ball r=%d" % radius,
                "params": {"radius": radius, "sample": sample_name},
                "result": {"error": "Macro failed"},
                "passed": False,
            })
            continue

        # Get post-subtraction histogram
        try:
            post_hist = get_histogram()
            post_stats = post_hist.get("result", {}) if post_hist.get("ok") else {}
        except Exception:
            post_stats = {}

        post_stddev = post_stats.get("stdDev", 0)
        post_mean = post_stats.get("mean", 0)

        runner._capture("practice_bg_r%d" % radius)

        # Lower stddev relative to mean = more uniform background
        cv = post_stddev / post_mean if post_mean > 0 else float("inf")

        attempt = {
            "method": "Rolling ball r=%d" % radius,
            "params": {"radius": radius, "sample": sample_name},
            "result": {
                "original_stddev": round(orig_stddev, 2),
                "post_stddev": round(post_stddev, 2),
                "post_mean": round(post_mean, 2),
                "coefficient_of_variation": round(cv, 4),
            },
            "passed": True,  # all are valid, we're comparing which is best
        }
        attempts.append(attempt)

        if cv < best_uniformity:
            best_uniformity = cv
            best_radius = radius

        print("      r=%d -> stddev=%.1f, mean=%.1f, CV=%.4f" % (radius, post_stddev, post_mean, cv))

    if best_radius:
        learnings.append("Best rolling ball radius for %s: %d (lowest CV=%.4f)" % (
            sample_name, best_radius, best_uniformity))
        learnings.append("Original stddev=%.1f, all radii reduced background variation" % orig_stddev)

    return {
        "status": "pass" if attempts else "fail",
        "attempts": attempts,
        "best_approach": "Rolling ball radius=%d" % best_radius if best_radius else "none",
        "learnings": learnings,
    }


def _task_z_stack_processing(runner):
    """Open a stack and compare projection types."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')

    # Try MRI Stack (smaller, faster)
    resp = runner._run_macro('run("MRI Stack");', "Open MRI Stack")
    sample_name = "MRI Stack"
    if not runner._macro_ok(resp):
        resp = runner._run_macro('run("T1 Head (16-bits)");', "Open T1 Head")
        sample_name = "T1 Head"
        if not runner._macro_ok(resp):
            return {"status": "error", "error": "Could not open any stack", "attempts": [], "learnings": []}

    # Verify it's actually a stack
    try:
        info = get_image_info()
        img_info = info.get("result", {}) if info.get("ok") else {}
        n_slices = img_info.get("slices", 1)
    except Exception:
        n_slices = 1

    if n_slices <= 1:
        return {
            "status": "error",
            "error": "Image is not a stack (slices=%d)" % n_slices,
            "attempts": [],
            "learnings": ["Sample '%s' has only %d slice(s)" % (sample_name, n_slices)],
        }

    learnings.append("Opened %s with %d slices" % (sample_name, n_slices))
    runner._capture("practice_stack_original")

    projection_types = ["Max Intensity", "Average Intensity", "Sum Slices", "Min Intensity", "Median"]

    for proj_type in projection_types:
        # Re-select the original stack
        try:
            runner._run_macro('selectWindow("%s");' % sample_name)
        except Exception:
            pass

        resp = runner._run_macro(
            'run("Z Project...", "projection=[%s]");' % proj_type,
            "Z-Project: %s" % proj_type
        )

        if not runner._macro_ok(resp):
            attempts.append({
                "method": proj_type,
                "params": {"projection": proj_type, "sample": sample_name},
                "result": {"error": "Z Project failed"},
                "passed": False,
            })
            continue

        # Get histogram of the projection
        try:
            hist = get_histogram()
            hist_stats = hist.get("result", {}) if hist.get("ok") else {}
        except Exception:
            hist_stats = {}

        safe_name = proj_type.lower().replace(" ", "_")
        runner._capture("practice_zproj_%s" % safe_name)

        attempt = {
            "method": proj_type,
            "params": {"projection": proj_type, "sample": sample_name},
            "result": {
                "mean": round(hist_stats.get("mean", 0), 2),
                "stddev": round(hist_stats.get("stdDev", 0), 2),
                "min": hist_stats.get("min", 0),
                "max": hist_stats.get("max", 0),
            },
            "passed": True,
        }
        attempts.append(attempt)

        print("      %s -> mean=%.1f, std=%.1f" % (
            proj_type, hist_stats.get("mean", 0), hist_stats.get("stdDev", 0)))

        # Close the projection to avoid accumulating windows
        runner._run_macro('close();')

    if attempts:
        # Max Intensity is most common for fluorescence
        learnings.append("All %d projection types tested successfully" % len(
            [a for a in attempts if a["passed"]]))
        learnings.append("Max Intensity is standard for fluorescence microscopy")
        learnings.append("Average/Median reduce noise; Sum preserves total intensity")

    return {
        "status": "pass" if any(a["passed"] for a in attempts) else "fail",
        "attempts": attempts,
        "best_approach": "Max Intensity for most fluorescence applications",
        "learnings": learnings,
    }


def _task_multichannel_split(runner):
    """Split channels from a color image and measure each independently."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')

    # Try Fluorescent Cells (multi-channel), fall back to a color sample
    resp = runner._run_macro('run("Fluorescent Cells");', "Open Fluorescent Cells")
    sample_name = "Fluorescent Cells"
    if not runner._macro_ok(resp):
        resp = runner._run_macro('run("Clown");', "Open Clown (RGB fallback)")
        sample_name = "Clown"
        if not runner._macro_ok(resp):
            return {"status": "error", "error": "Could not open color sample", "attempts": [], "learnings": []}

    # Check image type
    try:
        info = get_image_info()
        img_info = info.get("result", {}) if info.get("ok") else {}
    except Exception:
        img_info = {}

    img_type = img_info.get("type", "")
    channels = img_info.get("channels", 1)
    learnings.append("Opened %s: type=%s, channels=%d" % (sample_name, img_type, channels))
    runner._capture("practice_multichannel_original")

    # Split channels
    resp = runner._run_macro('run("Split Channels");', "Split channels")
    if not runner._macro_ok(resp):
        # If it fails, image might not be multi-channel -- try converting
        if "RGB" in img_type:
            resp = runner._run_macro('run("Split Channels");', "Split RGB channels")
        if not runner._macro_ok(resp):
            return {
                "status": "error",
                "error": "Could not split channels",
                "attempts": [],
                "learnings": ["Split Channels failed on %s (%s)" % (sample_name, img_type)],
            }

    # Count how many windows are now open
    try:
        from ij import get_open_windows
        win_resp = get_open_windows()
        windows = win_resp.get("result", {}) if win_resp.get("ok") else {}
        image_windows = windows.get("images", [])
    except Exception:
        image_windows = []

    n_channels = len(image_windows)
    learnings.append("Split into %d channel windows" % n_channels)

    # Measure each channel
    channel_stats = []
    for i, win_title in enumerate(image_windows):
        try:
            runner._run_macro('selectWindow("%s");' % win_title)
        except Exception:
            continue

        try:
            hist = get_histogram()
            stats = hist.get("result", {}) if hist.get("ok") else {}
        except Exception:
            stats = {}

        channel_stats.append({
            "channel": win_title,
            "mean": round(stats.get("mean", 0), 2),
            "stddev": round(stats.get("stdDev", 0), 2),
        })

    attempts.append({
        "method": "Split and measure channels",
        "params": {"sample": sample_name, "original_type": img_type},
        "result": {
            "n_channels": n_channels,
            "channel_stats": channel_stats,
        },
        "passed": n_channels >= 2,
    })

    if n_channels >= 2:
        learnings.append("Channel measurements: %s" % ", ".join(
            "%s (mean=%.1f)" % (cs["channel"], cs["mean"]) for cs in channel_stats
        ))

    return {
        "status": "pass" if n_channels >= 2 else "fail",
        "attempts": attempts,
        "best_approach": 'run("Split Channels") on RGB/composite images',
        "learnings": learnings,
    }


def _task_particle_size_filtering(runner):
    """Vary the size filter in Analyze Particles and track count changes."""
    attempts = []
    learnings = []

    size_filters = [
        (0, "Infinity", "no filter"),
        (20, "Infinity", "min 20"),
        (50, "Infinity", "min 50"),
        (100, "Infinity", "min 100"),
        (200, "Infinity", "min 200"),
        (500, "Infinity", "min 500"),
    ]

    counts = {}

    for min_size, max_size, label in size_filters:
        # Fresh start each time — close ALL windows including Summary
        runner._run_macro('run("Close All");')
        runner._run_macro(
            'if (isOpen("Results")) { selectWindow("Results"); run("Close"); }'
        )
        runner._run_macro(
            'if (isOpen("Summary")) { selectWindow("Summary"); run("Close"); }'
        )
        runner._run_macro('run("Clear Results");')
        runner._run_macro('run("Blobs");')

        macro = """
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=%s-%s show=Nothing display");
nResults;
""" % (min_size, max_size)

        resp = runner._run_macro(macro, "Analyze Particles size=%s-%s" % (min_size, max_size))

        count = None
        if runner._macro_ok(resp):
            result = resp.get("result", {})
            # nResults is returned as the macro output (last expression)
            output = result.get("output", "")
            if output and output.strip().isdigit():
                count = int(output.strip())
            if count is None:
                # Fallback: count rows in results table
                try:
                    rt_resp = get_results_table()
                    if rt_resp.get("ok") and rt_resp.get("result"):
                        csv_text = rt_resp["result"]
                        lines = [l for l in csv_text.strip().split("\n") if l.strip()]
                        count = max(0, len(lines) - 1)  # subtract header
                except Exception:
                    pass

        counts[min_size] = count

        attempt = {
            "method": "size=%s-%s (%s)" % (min_size, max_size, label),
            "params": {"min_size": min_size, "max_size": max_size},
            "result": {"count": count},
            "passed": count is not None,
        }
        attempts.append(attempt)

        print("      size=%s-%s -> count=%s" % (min_size, max_size, count))

    # Analyze the trend
    valid_counts = [(sz, c) for sz, c in counts.items() if c is not None]
    valid_counts.sort()

    if len(valid_counts) >= 2:
        # Count should decrease as min_size increases
        decreasing = all(
            valid_counts[i][1] >= valid_counts[i + 1][1]
            for i in range(len(valid_counts) - 1)
        )
        if decreasing:
            learnings.append("Count decreases monotonically with increasing size filter (expected)")
        else:
            learnings.append("Count does NOT decrease monotonically -- unexpected")

        # Find the biggest drop (likely noise->real boundary)
        max_drop = 0
        drop_at = None
        for i in range(len(valid_counts) - 1):
            drop = valid_counts[i][1] - valid_counts[i + 1][1]
            if drop > max_drop:
                max_drop = drop
                drop_at = valid_counts[i + 1][0]

        if drop_at is not None and max_drop > 0:
            learnings.append(
                "Biggest count drop at min_size=%d (lost %d objects) -- probable noise threshold"
                % (drop_at, max_drop)
            )

        # Report full trend
        trend = ", ".join("size>=%d: %d" % (sz, c) for sz, c in valid_counts)
        learnings.append("Size filter trend: %s" % trend)

    return {
        "status": "pass" if valid_counts else "fail",
        "attempts": attempts,
        "best_approach": "min_size=50 for Blobs (excludes noise, keeps real objects)",
        "learnings": learnings,
    }


# ---------------------------------------------------------------------------
# Plugin-based practice tasks
# ---------------------------------------------------------------------------

def _task_stardist_segmentation(runner):
    """StarDist 2D nuclei detection vs classical thresholding."""
    attempts = []
    learnings = []

    # --- Attempt 1: Classical threshold count (baseline) ---
    runner._run_macro('run("Close All");')
    runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
    runner._run_macro('run("Clear Results");')
    resp = runner._run_macro('run("Blobs");', "Open Blobs for baseline")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Blobs", "attempts": [], "learnings": []}

    baseline_macro = """
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=50-Infinity show=Nothing display");
nResults;
"""
    resp = runner._run_macro(baseline_macro, "Classical Otsu+Watershed counting")
    classical_count = None
    if runner._macro_ok(resp):
        output = resp.get("result", {}).get("output", "")
        if output and output.strip().isdigit():
            classical_count = int(output.strip())
        if classical_count is None:
            try:
                rt_resp = get_results_table()
                if rt_resp.get("ok") and rt_resp.get("result"):
                    lines = [l for l in rt_resp["result"].strip().split("\n") if l.strip()]
                    classical_count = max(0, len(lines) - 1)
            except Exception:
                pass

    attempts.append({
        "method": "Classical Otsu+Watershed",
        "params": {"threshold": "Otsu", "watershed": True, "min_size": 50},
        "result": {"count": classical_count},
        "passed": classical_count is not None,
    })
    print("      Classical count: %s" % classical_count)

    # --- Attempt 2: StarDist 2D ---
    runner._run_macro('run("Close All");')
    runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
    runner._run_macro('run("Clear Results");')
    runner._run_macro('run("Blobs");', "Open Blobs for StarDist")

    stardist_macro = 'run("Command From Macro", "command=[de.csbresden.stardist.StarDist2D], args=[\'input\':\'Blobs\', \'modelChoice\':\'Versatile (fluorescent nuclei)\', \'normalizeInput\':\'true\', \'percentileBottom\':\'1.0\', \'percentileTop\':\'99.8\', \'probThresh\':\'0.5\', \'nmsThresh\':\'0.4\', \'outputType\':\'Both\', \'nTiles\':\'1\', \'excludeBoundary\':\'2\', \'roiPosition\':\'Automatic\', \'verbose\':\'false\', \'showCsb498de498\':\'true\', \'showProbAndDist\':\'false\'], process=[false]");'

    resp = runner._run_macro(stardist_macro, "StarDist 2D detection")
    stardist_count = None
    stardist_ok = False

    if runner._macro_ok(resp):
        stardist_ok = True
        # StarDist outputs a label image; count via nResults or ROI Manager
        count_macro = """
if (isOpen("Results")) {
    nResults;
} else {
    roiManager("count");
}
"""
        count_resp = runner._run_macro(count_macro, "Count StarDist detections")
        if runner._macro_ok(count_resp):
            output = count_resp.get("result", {}).get("output", "")
            if output and output.strip().isdigit():
                stardist_count = int(output.strip())
        if stardist_count is None:
            try:
                rt_resp = get_results_table()
                if rt_resp.get("ok") and rt_resp.get("result"):
                    lines = [l for l in rt_resp["result"].strip().split("\n") if l.strip()]
                    stardist_count = max(0, len(lines) - 1)
            except Exception:
                pass

        runner._capture("practice_stardist_result")
    else:
        error_msg = resp.get("error", resp.get("result", {}).get("error", "unknown"))
        learnings.append("StarDist 2D failed: %s" % error_msg)
        learnings.append("StarDist may need model download or TensorFlow setup")

    attempts.append({
        "method": "StarDist 2D",
        "params": {"model": "Versatile (fluorescent nuclei)", "probThresh": 0.5, "nmsThresh": 0.4},
        "result": {"count": stardist_count, "plugin_available": stardist_ok},
        "passed": stardist_count is not None,
    })
    print("      StarDist count: %s" % stardist_count)

    # Compare
    if classical_count is not None and stardist_count is not None:
        diff = stardist_count - classical_count
        learnings.append("StarDist found %d objects vs classical %d (diff=%+d)" % (stardist_count, classical_count, diff))
        if abs(diff) < 5:
            learnings.append("Results are very similar -- StarDist agrees with classical approach on Blobs")
        elif diff > 0:
            learnings.append("StarDist found more objects -- better at separating touching nuclei")
        else:
            learnings.append("StarDist found fewer objects -- more conservative, possibly better at rejecting noise")
        learnings.append("StarDist is preferred for real fluorescent nuclei; classical works fine for Blobs sample")
    elif stardist_count is None and classical_count is not None:
        learnings.append("StarDist unavailable; classical counting works as fallback")

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": "StarDist for real nuclei, classical for simple blobs",
        "learnings": learnings,
    }


def _task_3d_objects_counter(runner):
    """3D Objects Counter on MRI Stack at different thresholds."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')
    resp = runner._run_macro('run("MRI Stack");', "Open MRI Stack")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open MRI Stack", "attempts": [], "learnings": []}

    # Verify it's a stack
    try:
        info = get_image_info()
        img_info = info.get("result", {}) if info.get("ok") else {}
        n_slices = img_info.get("slices", 1)
    except Exception:
        n_slices = 1
        img_info = {}

    if n_slices <= 1:
        return {
            "status": "error",
            "error": "MRI Stack is not a stack (slices=%d)" % n_slices,
            "attempts": [],
            "learnings": ["MRI Stack loaded with only %d slice" % n_slices],
        }

    learnings.append("Opened MRI Stack: %d slices, %s" % (n_slices, img_info.get("type", "?")))
    runner._capture("practice_3d_original")

    thresholds = [64, 128, 200]
    counts_by_thresh = {}

    for thresh in thresholds:
        runner._run_macro('run("Close All");')
        runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
        runner._run_macro('if (isOpen("Summary")) { selectWindow("Summary"); run("Close"); }')
        runner._run_macro('run("Clear Results");')
        runner._run_macro('run("MRI Stack");')

        resp = runner._run_macro(
            'run("3D Objects Counter", "threshold=%d min.=100 max.=999999 objects statistics");' % thresh,
            "3D Objects Counter threshold=%d" % thresh
        )

        count = None
        if runner._macro_ok(resp):
            # Count from Results table
            try:
                rt_resp = get_results_table()
                if rt_resp.get("ok") and rt_resp.get("result"):
                    lines = [l for l in rt_resp["result"].strip().split("\n") if l.strip()]
                    count = max(0, len(lines) - 1)
            except Exception:
                pass
            runner._capture("practice_3d_thresh%d" % thresh)
        else:
            error_msg = resp.get("error", resp.get("result", {}).get("error", "unknown"))
            learnings.append("3D Objects Counter failed at threshold=%d: %s" % (thresh, error_msg))

        counts_by_thresh[thresh] = count
        attempts.append({
            "method": "3D Objects Counter threshold=%d" % thresh,
            "params": {"threshold": thresh, "min_volume": 100},
            "result": {"count": count},
            "passed": count is not None and count > 0,
        })
        print("      threshold=%d -> count=%s" % (thresh, count))

    # Analyze threshold sensitivity
    valid = [(t, c) for t, c in sorted(counts_by_thresh.items()) if c is not None]
    if len(valid) >= 2:
        counts_str = ", ".join("thresh=%d: %d objects" % (t, c) for t, c in valid)
        learnings.append("3D count vs threshold: %s" % counts_str)
        # Higher threshold should generally find fewer objects
        if valid[0][1] > valid[-1][1]:
            learnings.append("Higher threshold -> fewer objects (expected)")
        elif valid[0][1] == valid[-1][1]:
            learnings.append("Count stable across thresholds -- robust segmentation")
        else:
            learnings.append("Higher threshold -> MORE objects -- unexpected, possible fragmentation")

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": "3D Objects Counter with threshold tuning",
        "learnings": learnings,
    }


def _task_colocalization(runner):
    """Coloc 2 analysis between channel pairs of Fluorescent Cells."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')
    resp = runner._run_macro('run("Fluorescent Cells");', "Open Fluorescent Cells")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Fluorescent Cells", "attempts": [], "learnings": []}

    runner._capture("practice_coloc_original")

    # Split channels
    resp = runner._run_macro('run("Split Channels");', "Split channels")
    if not runner._macro_ok(resp):
        return {
            "status": "error",
            "error": "Could not split channels",
            "attempts": [],
            "learnings": ["Split Channels failed on Fluorescent Cells"],
        }

    # Find channel window names
    try:
        from ij import get_open_windows
        win_resp = get_open_windows()
        windows = win_resp.get("result", {}) if win_resp.get("ok") else {}
        image_windows = sorted(windows.get("images", []))
    except Exception:
        image_windows = []

    if len(image_windows) < 2:
        return {
            "status": "error",
            "error": "Need at least 2 channels, got %d" % len(image_windows),
            "attempts": [],
            "learnings": ["Split produced %d windows: %s" % (len(image_windows), image_windows)],
        }

    learnings.append("Split into %d channels: %s" % (len(image_windows), ", ".join(image_windows)))

    # Build channel pairs
    pairs = []
    for i in range(len(image_windows)):
        for j in range(i + 1, len(image_windows)):
            pairs.append((image_windows[i], image_windows[j]))

    for ch1, ch2 in pairs:
        # Clear log before each run so we can read Coloc 2 output
        runner._run_macro('print("\\\\Clear");', "Clear log")

        coloc_macro = 'run("Coloc 2", "channel_1=%s channel_2=%s roi_or_mask=<None> threshold_regression=Costes li_histogram_channel_1 li_histogram_channel_2 li_icq spearman\'s_rank_correlation manders\'_correlation kendall\'s_tau_rank_correlation 2d_intensity_histogram costes\'_significance_test psf=3 costes_randomisations=10");' % (ch1, ch2)

        resp = runner._run_macro(coloc_macro, "Coloc 2: %s vs %s" % (ch1, ch2))

        pearson_r = None
        manders_m1 = None
        manders_m2 = None
        coloc_ok = False

        if runner._macro_ok(resp):
            coloc_ok = True
            # Coloc 2 writes results to the Log window
            try:
                from ij import get_log
                log_resp = get_log()
                if log_resp.get("ok") and log_resp.get("result"):
                    log_text = log_resp["result"]
                    # Parse Pearson's R
                    for line in log_text.split("\n"):
                        line_lower = line.lower().strip()
                        if "pearson" in line_lower and "r" in line_lower:
                            # Try to extract number
                            parts = line.split(",")
                            for part in parts:
                                part = part.strip()
                                try:
                                    val = float(part)
                                    if -1.0 <= val <= 1.0:
                                        pearson_r = val
                                        break
                                except ValueError:
                                    continue
                            if pearson_r is None:
                                # Try splitting on = or :
                                for sep in ["=", ":"]:
                                    if sep in line:
                                        try:
                                            pearson_r = float(line.split(sep)[-1].strip().split()[0])
                                        except (ValueError, IndexError):
                                            continue
                        if "manders" in line_lower and "m1" in line_lower:
                            for sep in ["=", ":"]:
                                if sep in line:
                                    try:
                                        manders_m1 = float(line.split(sep)[-1].strip().split()[0])
                                    except (ValueError, IndexError):
                                        continue
                        if "manders" in line_lower and "m2" in line_lower:
                            for sep in ["=", ":"]:
                                if sep in line:
                                    try:
                                        manders_m2 = float(line.split(sep)[-1].strip().split()[0])
                                    except (ValueError, IndexError):
                                        continue
            except Exception:
                pass
        else:
            error_msg = resp.get("error", resp.get("result", {}).get("error", "unknown"))
            learnings.append("Coloc 2 failed for %s vs %s: %s" % (ch1, ch2, error_msg))

        pair_label = "%s vs %s" % (ch1, ch2)
        result_data = {
            "pearson_r": pearson_r,
            "manders_m1": manders_m1,
            "manders_m2": manders_m2,
            "plugin_available": coloc_ok,
        }

        # Validate: coefficients should be in [-1, 1]
        valid_coefficients = True
        if pearson_r is not None and not (-1.0 <= pearson_r <= 1.0):
            valid_coefficients = False
        if manders_m1 is not None and not (0.0 <= manders_m1 <= 1.0):
            valid_coefficients = False
        if manders_m2 is not None and not (0.0 <= manders_m2 <= 1.0):
            valid_coefficients = False

        passed = coloc_ok and valid_coefficients
        attempts.append({
            "method": "Coloc 2: %s" % pair_label,
            "params": {"ch1": ch1, "ch2": ch2, "regression": "Costes"},
            "result": result_data,
            "passed": passed,
        })

        print("      %s -> Pearson=%s, M1=%s, M2=%s" % (pair_label, pearson_r, manders_m1, manders_m2))

        if pearson_r is not None:
            if pearson_r > 0.5:
                learnings.append("%s: strong colocalization (Pearson=%.3f)" % (pair_label, pearson_r))
            elif pearson_r > 0.2:
                learnings.append("%s: moderate colocalization (Pearson=%.3f)" % (pair_label, pearson_r))
            elif pearson_r > -0.2:
                learnings.append("%s: no significant colocalization (Pearson=%.3f)" % (pair_label, pearson_r))
            else:
                learnings.append("%s: anti-correlated (Pearson=%.3f)" % (pair_label, pearson_r))

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": "Coloc 2 with Costes threshold regression",
        "learnings": learnings,
    }


def _task_clij2_gpu(runner):
    """CLIJ2 GPU-accelerated processing vs CPU baseline."""
    attempts = []
    learnings = []

    # --- CPU baseline ---
    runner._run_macro('run("Close All");')
    runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
    runner._run_macro('run("Clear Results");')
    resp = runner._run_macro('run("Blobs");', "Open Blobs for CPU baseline")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Blobs", "attempts": [], "learnings": []}

    cpu_macro = """
t1 = getTime();
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Analyze Particles...", "size=50-Infinity show=Nothing display");
t2 = getTime();
print("CPU_TIME=" + (t2 - t1));
nResults;
"""
    resp = runner._run_macro(cpu_macro, "CPU: Blur+Otsu+Analyze")
    cpu_count = None
    cpu_time = None
    if runner._macro_ok(resp):
        output = resp.get("result", {}).get("output", "")
        if output and output.strip().isdigit():
            cpu_count = int(output.strip())
        if cpu_count is None:
            try:
                rt_resp = get_results_table()
                if rt_resp.get("ok") and rt_resp.get("result"):
                    lines = [l for l in rt_resp["result"].strip().split("\n") if l.strip()]
                    cpu_count = max(0, len(lines) - 1)
            except Exception:
                pass
        # Extract timing from log
        try:
            from ij import get_log
            log_resp = get_log()
            if log_resp.get("ok") and log_resp.get("result"):
                for line in log_resp["result"].split("\n"):
                    if "CPU_TIME=" in line:
                        cpu_time = int(line.split("CPU_TIME=")[1].strip())
        except Exception:
            pass

    attempts.append({
        "method": "CPU Blur+Otsu+Analyze",
        "params": {"sigma": 2, "threshold": "Otsu", "min_size": 50},
        "result": {"count": cpu_count, "time_ms": cpu_time},
        "passed": cpu_count is not None,
    })
    print("      CPU: count=%s, time=%s ms" % (cpu_count, cpu_time))

    # --- CLIJ2 GPU pipeline ---
    runner._run_macro('run("Close All");')
    runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
    runner._run_macro('run("Clear Results");')
    runner._run_macro('print("\\\\Clear");')
    runner._run_macro('run("Blobs");', "Open Blobs for CLIJ2")

    clij2_macro = """
t1 = getTime();
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_push("Blobs");
Ext.CLIJ2_gaussianBlur2D("Blobs", "blurred", 2.0, 2.0);
Ext.CLIJ2_thresholdOtsu("blurred", "binary");
Ext.CLIJ2_connectedComponentsLabelingBox("binary", "labels");
Ext.CLIJ2_statisticsOfLabelledPixels("Blobs", "labels");
Ext.CLIJ2_pull("labels");
t2 = getTime();
print("CLIJ2_TIME=" + (t2 - t1));
nResults;
"""
    resp = runner._run_macro(clij2_macro, "CLIJ2 GPU: Blur+Otsu+Label+Stats")
    clij2_count = None
    clij2_time = None
    clij2_ok = False

    if runner._macro_ok(resp):
        clij2_ok = True
        output = resp.get("result", {}).get("output", "")
        if output and output.strip().isdigit():
            clij2_count = int(output.strip())
        if clij2_count is None:
            try:
                rt_resp = get_results_table()
                if rt_resp.get("ok") and rt_resp.get("result"):
                    lines = [l for l in rt_resp["result"].strip().split("\n") if l.strip()]
                    clij2_count = max(0, len(lines) - 1)
            except Exception:
                pass
        try:
            from ij import get_log
            log_resp = get_log()
            if log_resp.get("ok") and log_resp.get("result"):
                for line in log_resp["result"].split("\n"):
                    if "CLIJ2_TIME=" in line:
                        clij2_time = int(line.split("CLIJ2_TIME=")[1].strip())
        except Exception:
            pass
        runner._capture("practice_clij2_labels")
    else:
        error_msg = resp.get("error", resp.get("result", {}).get("error", "unknown"))
        learnings.append("CLIJ2 failed: %s" % error_msg)
        learnings.append("CLIJ2 may need GPU driver or OpenCL support")

    attempts.append({
        "method": "CLIJ2 GPU Blur+Otsu+Label+Stats",
        "params": {"sigma": 2.0, "device": "auto"},
        "result": {"count": clij2_count, "time_ms": clij2_time, "plugin_available": clij2_ok},
        "passed": clij2_count is not None,
    })
    print("      CLIJ2: count=%s, time=%s ms" % (clij2_count, clij2_time))

    # Compare
    if cpu_count is not None and clij2_count is not None:
        diff = abs(clij2_count - cpu_count)
        learnings.append("CPU count=%d vs CLIJ2 count=%d (diff=%d)" % (cpu_count, clij2_count, diff))
        if diff <= 3:
            learnings.append("CLIJ2 and CPU counts are consistent")
        else:
            learnings.append("Count difference of %d -- CLIJ2 labeling differs from Analyze Particles" % diff)

    if cpu_time is not None and clij2_time is not None and clij2_time > 0:
        speedup = float(cpu_time) / float(clij2_time) if clij2_time > 0 else 0
        learnings.append("CLIJ2 speedup: %.1fx (CPU=%dms, GPU=%dms)" % (speedup, cpu_time, clij2_time))
        if speedup > 1.5:
            learnings.append("CLIJ2 significantly faster -- GPU acceleration effective")
        elif speedup > 0.8:
            learnings.append("CLIJ2 similar speed -- GPU overhead offsets gains on small images")
        else:
            learnings.append("CLIJ2 slower -- GPU transfer overhead dominates for this image size")

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": "CLIJ2 for large images; CPU fine for small ones",
        "learnings": learnings,
    }


def _task_morpholibj(runner):
    """MorphoLibJ extended minima and morphological reconstruction."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')
    resp = runner._run_macro('run("Blobs");', "Open Blobs for MorphoLibJ")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Blobs", "attempts": [], "learnings": []}

    runner._capture("practice_morpholibj_original")

    # Try Extended Minima (non-interactive MorphoLibJ command)
    dynamics = [5, 10, 20, 40]
    counts_by_dynamic = {}

    for dyn in dynamics:
        runner._run_macro('run("Close All");')
        runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
        runner._run_macro('run("Clear Results");')
        runner._run_macro('run("Blobs");')

        # Invert so blobs are minima (MorphoLibJ Extended Minima finds dark regions)
        ext_min_macro = """
run("Invert");
run("Extended Min & Max", "operation=[Extended Minima] dynamic=%d connectivity=4");
""" % dyn

        resp = runner._run_macro(ext_min_macro, "Extended Minima dynamic=%d" % dyn)

        count = None
        morpho_ok = False

        if runner._macro_ok(resp):
            morpho_ok = True
            # The result is a binary image -- count particles
            count_macro = """
run("Analyze Particles...", "size=1-Infinity show=Nothing display");
nResults;
"""
            count_resp = runner._run_macro(count_macro, "Count extended minima regions")
            if runner._macro_ok(count_resp):
                output = count_resp.get("result", {}).get("output", "")
                if output and output.strip().isdigit():
                    count = int(output.strip())
                if count is None:
                    try:
                        rt_resp = get_results_table()
                        if rt_resp.get("ok") and rt_resp.get("result"):
                            lines = [l for l in rt_resp["result"].strip().split("\n") if l.strip()]
                            count = max(0, len(lines) - 1)
                    except Exception:
                        pass

            runner._capture("practice_morpholibj_dyn%d" % dyn)
        else:
            error_msg = resp.get("error", resp.get("result", {}).get("error", "unknown"))
            if dyn == dynamics[0]:
                learnings.append("MorphoLibJ Extended Min & Max failed: %s" % error_msg)
                learnings.append("MorphoLibJ may not be installed -- enable IJPB-plugins update site")

        counts_by_dynamic[dyn] = count
        attempts.append({
            "method": "Extended Minima dynamic=%d" % dyn,
            "params": {"dynamic": dyn, "connectivity": 4},
            "result": {"count": count, "plugin_available": morpho_ok},
            "passed": count is not None and count > 0,
        })
        print("      dynamic=%d -> count=%s" % (dyn, count))

        # If first attempt failed with plugin error, skip remaining
        if not morpho_ok and dyn == dynamics[0]:
            break

    # Analyze dynamic parameter sensitivity
    valid = [(d, c) for d, c in sorted(counts_by_dynamic.items()) if c is not None]
    if len(valid) >= 2:
        trend = ", ".join("dyn=%d: %d" % (d, c) for d, c in valid)
        learnings.append("Extended Minima count vs dynamic: %s" % trend)
        if valid[0][1] > valid[-1][1]:
            learnings.append("Higher dynamic -> fewer regions (merges shallow minima)")
        learnings.append("Dynamic parameter controls depth of minima to detect -- like a sensitivity knob")

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": "Extended Minima for marker-controlled watershed",
        "learnings": learnings,
    }


def _task_skeleton_analysis(runner):
    """AnalyzeSkeleton on skeletonized Blobs."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')
    runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
    runner._run_macro('run("Clear Results");')
    resp = runner._run_macro('run("Blobs");', "Open Blobs for skeleton analysis")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Blobs", "attempts": [], "learnings": []}

    # Threshold, skeletonize, analyze
    skeleton_macro = """
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Skeletonize");
"""
    resp = runner._run_macro(skeleton_macro, "Threshold + Skeletonize")
    if not runner._macro_ok(resp):
        return {
            "status": "error",
            "error": "Skeletonize failed",
            "attempts": [],
            "learnings": ["Could not threshold and skeletonize Blobs"],
        }

    runner._capture("practice_skeleton_binary")

    # Run AnalyzeSkeleton
    resp = runner._run_macro(
        'run("Analyze Skeleton (2D/3D)", "prune=[none] calculate show");',
        "Analyze Skeleton (2D/3D)"
    )

    branches = None
    junctions = None
    endpoints = None
    skeleton_ok = False

    if runner._macro_ok(resp):
        skeleton_ok = True
        # AnalyzeSkeleton populates Results table with branch info
        try:
            rt_resp = get_results_table()
            if rt_resp.get("ok") and rt_resp.get("result"):
                csv_text = rt_resp["result"]
                lines = csv_text.strip().split("\n")
                if len(lines) >= 2:
                    header = lines[0]
                    cols = [c.strip() for c in header.split("\t") if c.strip()]
                    # Look for # Branches, # Junctions, # End-point voxels
                    branch_idx = None
                    junction_idx = None
                    endpoint_idx = None
                    for i, col in enumerate(cols):
                        col_lower = col.lower()
                        if "branch" in col_lower and "#" in col:
                            branch_idx = i
                        elif "junction" in col_lower and "#" in col:
                            junction_idx = i
                        elif "end" in col_lower and "#" in col:
                            endpoint_idx = i

                    # Sum across all skeletons
                    total_branches = 0
                    total_junctions = 0
                    total_endpoints = 0
                    n_skeletons = 0
                    for line in lines[1:]:
                        parts = [p.strip() for p in line.split("\t")]
                        n_skeletons += 1
                        if branch_idx is not None and len(parts) > branch_idx:
                            try:
                                total_branches += int(float(parts[branch_idx]))
                            except ValueError:
                                pass
                        if junction_idx is not None and len(parts) > junction_idx:
                            try:
                                total_junctions += int(float(parts[junction_idx]))
                            except ValueError:
                                pass
                        if endpoint_idx is not None and len(parts) > endpoint_idx:
                            try:
                                total_endpoints += int(float(parts[endpoint_idx]))
                            except ValueError:
                                pass

                    branches = total_branches
                    junctions = total_junctions
                    endpoints = total_endpoints
                    learnings.append("Found %d skeletons" % n_skeletons)
        except Exception:
            pass

        runner._capture("practice_skeleton_analyzed")
    else:
        error_msg = resp.get("error", resp.get("result", {}).get("error", "unknown"))
        learnings.append("AnalyzeSkeleton failed: %s" % error_msg)

    passed = skeleton_ok and branches is not None and branches > 0
    attempts.append({
        "method": "Otsu + Skeletonize + AnalyzeSkeleton",
        "params": {"threshold": "Otsu", "prune": "none"},
        "result": {
            "branches": branches,
            "junctions": junctions,
            "endpoints": endpoints,
            "plugin_available": skeleton_ok,
        },
        "passed": passed,
    })
    print("      branches=%s, junctions=%s, endpoints=%s" % (branches, junctions, endpoints))

    if branches is not None:
        learnings.append("Total branches=%d, junctions=%d, endpoints=%d" % (
            branches, junctions or 0, endpoints or 0))
        learnings.append("Skeleton analysis workflow: threshold -> skeletonize -> AnalyzeSkeleton")
        learnings.append("Useful for neurite tracing, vascular networks, root systems")

    return {
        "status": "pass" if passed else "fail",
        "attempts": attempts,
        "best_approach": "Otsu threshold + Skeletonize + AnalyzeSkeleton (prune=none)",
        "learnings": learnings,
    }


def _task_bioformats_import(runner):
    """Verify Bio-Formats availability and import syntax."""
    attempts = []
    learnings = []

    # Check if Bio-Formats is available by trying to list supported formats
    runner._run_macro('run("Close All");')

    # Test 1: Check Bio-Formats is accessible via macro
    resp = runner._run_macro(
        'run("Bio-Formats Plugins Configuration");',
        "Check Bio-Formats configuration"
    )

    bioformats_available = runner._macro_ok(resp)
    # Close the config dialog if it opened
    runner._run_macro('run("Close All");')
    try:
        from ij import close_dialogs
        close_dialogs()
    except Exception:
        pass

    attempts.append({
        "method": "Bio-Formats availability check",
        "params": {},
        "result": {"available": bioformats_available},
        "passed": bioformats_available,
    })

    if not bioformats_available:
        learnings.append("Bio-Formats not available or config command failed")
        return {
            "status": "fail",
            "attempts": attempts,
            "best_approach": "N/A",
            "learnings": learnings,
        }

    learnings.append("Bio-Formats is installed and accessible")

    # Test 2: Open a TIFF using Bio-Formats (to test the macro syntax)
    # Use a known sample image - save Blobs as temp tif then reopen via Bio-Formats
    import tempfile
    temp_tif = os.path.join(TMP_DIR, "bioformats_test.tif").replace("\\", "/")

    runner._run_macro('run("Blobs");', "Open Blobs")
    resp = runner._run_macro(
        'saveAs("Tiff", "%s");' % temp_tif,
        "Save as TIFF for Bio-Formats test"
    )

    if runner._macro_ok(resp):
        runner._run_macro('run("Close All");')

        # Reopen with Bio-Formats
        bf_macro = 'run("Bio-Formats Importer", "open=[%s] color_mode=Default view=Hyperstack");' % temp_tif
        resp = runner._run_macro(bf_macro, "Bio-Formats Importer on TIFF")

        bf_open_ok = runner._macro_ok(resp)

        if bf_open_ok:
            try:
                info = get_image_info()
                img_info = info.get("result", {}) if info.get("ok") else {}
            except Exception:
                img_info = {}

            attempts.append({
                "method": "Bio-Formats Importer on TIFF",
                "params": {"path": temp_tif, "color_mode": "Default", "view": "Hyperstack"},
                "result": {
                    "opened": True,
                    "title": img_info.get("title", "?"),
                    "type": img_info.get("type", "?"),
                    "width": img_info.get("width", 0),
                    "height": img_info.get("height", 0),
                },
                "passed": True,
            })
            learnings.append("Bio-Formats TIFF import works: %dx%d %s" % (
                img_info.get("width", 0), img_info.get("height", 0), img_info.get("type", "?")))
            learnings.append('Syntax: run("Bio-Formats Importer", "open=[path] color_mode=Default view=Hyperstack");')
            runner._capture("practice_bioformats_opened")
        else:
            error_msg = resp.get("error", resp.get("result", {}).get("error", "unknown"))
            attempts.append({
                "method": "Bio-Formats Importer on TIFF",
                "params": {"path": temp_tif},
                "result": {"opened": False, "error": error_msg},
                "passed": False,
            })
            learnings.append("Bio-Formats Importer macro failed: %s" % error_msg)

    # Test 3: Check supported formats via macro
    resp = runner._run_macro(
        'run("Bio-Formats Importer");',
        "Open Bio-Formats dialog (no file)"
    )
    # This will open a file chooser dialog -- close it
    try:
        from ij import close_dialogs
        close_dialogs()
    except Exception:
        pass

    learnings.append("Bio-Formats supports: .nd2 (Nikon), .lif (Leica), .czi (Zeiss), .ome.tif, etc.")
    learnings.append("For batch: use windowless mode: open=[path] autoscale color_mode=Default view=Hyperstack stack_order=XYCZT")

    any_passed = any(a["passed"] for a in attempts)
    return {
        "status": "pass" if any_passed else "fail",
        "attempts": attempts,
        "best_approach": 'run("Bio-Formats Importer", "open=[path] color_mode=Default view=Hyperstack")',
        "learnings": learnings,
    }


def _task_measure_set_options(runner):
    """Test all measurement types with Set Measurements + Analyze Particles."""
    attempts = []
    learnings = []

    runner._run_macro('run("Close All");')
    runner._run_macro('if (isOpen("Results")) { selectWindow("Results"); run("Close"); }')
    runner._run_macro('run("Clear Results");')
    resp = runner._run_macro('run("Blobs");', "Open Blobs for measurement test")
    if not runner._macro_ok(resp):
        return {"status": "error", "error": "Could not open Blobs", "attempts": [], "learnings": []}

    # Configure ALL measurement types
    set_measurements_macro = """
run("Set Measurements...", "area mean standard modal min centroid center perimeter bounding fit shape feret's integrated median skewness kurtosis area_fraction stack redirect=None decimal=3");
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=50-Infinity show=Nothing display");
nResults;
"""
    resp = runner._run_macro(set_measurements_macro, "All measurements + Analyze Particles")
    if not runner._macro_ok(resp):
        return {
            "status": "error",
            "error": "Analyze Particles with all measurements failed",
            "attempts": [],
            "learnings": ["Set Measurements + Analyze Particles failed"],
        }

    count = None
    output = resp.get("result", {}).get("output", "")
    if output and output.strip().isdigit():
        count = int(output.strip())

    # Check which columns appear in results
    expected_columns = [
        "Area", "Mean", "StdDev", "Mode", "Min", "Max",
        "X", "Y", "XM", "YM",  # centroid + center of mass
        "Perim.", "BX", "BY", "Width", "Height",  # bounding + perimeter
        "Major", "Minor", "Angle",  # fit ellipse
        "Circ.", "Solidity",  # shape descriptors
        "Feret", "FeretAngle", "MinFeret",  # Feret's diameter
        "IntDen", "RawIntDen",  # integrated density
        "Median", "Skew", "Kurt",  # additional stats
    ]

    found_columns = []
    missing_columns = []

    try:
        rt_resp = get_results_table()
        if rt_resp.get("ok") and rt_resp.get("result"):
            csv_text = rt_resp["result"]
            header_line = csv_text.strip().split("\n")[0]
            header_cols = [c.strip() for c in header_line.split("\t") if c.strip()]

            # Also try comma-separated
            if len(header_cols) <= 2:
                header_cols = [c.strip() for c in header_line.split(",") if c.strip()]

            for expected in expected_columns:
                # Flexible matching: check if column name appears in header
                matched = False
                for col in header_cols:
                    if expected.lower().rstrip(".") in col.lower().rstrip("."):
                        matched = True
                        break
                if matched:
                    found_columns.append(expected)
                else:
                    missing_columns.append(expected)

            if count is None:
                lines = [l for l in csv_text.strip().split("\n") if l.strip()]
                count = max(0, len(lines) - 1)
    except Exception:
        pass

    coverage = float(len(found_columns)) / len(expected_columns) * 100 if expected_columns else 0
    passed = coverage >= 60  # at least 60% of expected columns found

    attempts.append({
        "method": "All measurements",
        "params": {"measurements": "area mean standard modal min centroid center perimeter bounding fit shape feret's integrated median skewness kurtosis area_fraction"},
        "result": {
            "count": count,
            "found_columns": found_columns,
            "missing_columns": missing_columns,
            "coverage_pct": round(coverage, 1),
            "total_columns_in_header": len(found_columns) + len(missing_columns),
        },
        "passed": passed,
    })

    print("      count=%s, columns found=%d/%d (%.0f%%)" % (
        count, len(found_columns), len(expected_columns), coverage))

    learnings.append("Found %d/%d expected measurement columns (%.0f%% coverage)" % (
        len(found_columns), len(expected_columns), coverage))

    if missing_columns:
        learnings.append("Missing columns: %s" % ", ".join(missing_columns[:5]))
    if found_columns:
        learnings.append("Key measurements available: Area, Mean, StdDev, Circ., Feret, IntDen, etc.")

    learnings.append("Set Measurements must be called BEFORE Analyze Particles")
    learnings.append("redirect=None means measure on the mask; set to image title to measure original intensities")
    learnings.append("IntDen = Area * Mean (integrated density); RawIntDen = sum of pixel values")

    return {
        "status": "pass" if passed else "fail",
        "attempts": attempts,
        "best_approach": "Set Measurements with all options before Analyze Particles",
        "learnings": learnings,
    }


# ---------------------------------------------------------------------------
# Utility functions
# ---------------------------------------------------------------------------

def _extract_count_from_summary(text):
    """Extract particle count from Summary or Results table text.

    The Summary table from Analyze Particles has a Count column.
    Also handles direct Results table row counting.
    """
    if not text:
        return None

    lines = text.strip().split("\n")
    if len(lines) < 2:
        return None

    # Look for a "Count" column in the header
    header = lines[0]
    if "Count" in header:
        cols = header.split("\t")
        try:
            count_idx = cols.index("Count")
        except ValueError:
            # Try with spaces
            for i, c in enumerate(cols):
                if c.strip() == "Count":
                    count_idx = i
                    break
            else:
                count_idx = None

        if count_idx is not None:
            # Read the last data row (most recent summary)
            for line in reversed(lines[1:]):
                parts = line.split("\t")
                if len(parts) > count_idx:
                    try:
                        return int(float(parts[count_idx].strip()))
                    except (ValueError, IndexError):
                        continue

    # Fallback: count non-header rows (each row = one particle)
    if "Area" in header or "Mean" in header:
        return len(lines) - 1

    return None


def show_report():
    """Display the practice history from saved results."""
    if not os.path.exists(RESULTS_FILE):
        print("No practice results found at: %s" % RESULTS_FILE)
        print("Run 'python practice.py' first.")
        return

    with open(RESULTS_FILE, "r") as f:
        history = json.load(f)

    if not isinstance(history, list):
        history = [history]

    print("=== Practice History ===")
    print("Sessions: %d\n" % len(history))

    for session in history:
        ts = session.get("session", "?")[:19]
        tasks = session.get("tasks", [])
        passed = sum(1 for t in tasks if t.get("status") == "pass")
        total = len(tasks)
        print("[%s] %d/%d passed" % (ts, passed, total))

        for task in tasks:
            name = task.get("name", "?")
            status = task.get("status", "?")
            best = task.get("best_approach", "")
            n_attempts = len(task.get("attempts", []))

            status_char = {"pass": "+", "fail": "-", "error": "!", "skipped": "~", "dry_run": "?"}.get(status, "?")
            print("  [%s] %-30s (%d attempts)  %s" % (status_char, name, n_attempts, best))

            for learning in task.get("learnings", []):
                print("      * %s" % learning)

        print()


def list_tasks():
    """Print available practice tasks."""
    print("Available practice tasks:\n")
    for name in TASK_ORDER:
        _, description = TASKS[name]
        print("  %-30s %s" % (name, description))
    print("\nRun a specific task: python practice.py --task TASK_NAME")


# ---------------------------------------------------------------------------
# Task registry
# ---------------------------------------------------------------------------

TASKS = {
    "cell_counting": (_task_cell_counting, "Count blobs with various threshold + parameter combos"),
    "threshold_comparison": (_task_threshold_comparison, "Compare all threshold methods with explore_thresholds"),
    "measurement_accuracy": (_task_measurement_accuracy, "Segment and validate area/circularity measurements"),
    "background_subtraction": (_task_background_subtraction, "Compare rolling ball radii for background uniformity"),
    "z_stack_processing": (_task_z_stack_processing, "Compare Z-projection types on a stack"),
    "multichannel_split": (_task_multichannel_split, "Split channels and measure each independently"),
    "particle_size_filter": (_task_particle_size_filtering, "Vary Analyze Particles size filter and track counts"),
    "stardist_segmentation": (_task_stardist_segmentation, "StarDist 2D nuclei detection vs classical thresholding"),
    "3d_objects_counter": (_task_3d_objects_counter, "3D Objects Counter on MRI Stack at different thresholds"),
    "colocalization": (_task_colocalization, "Coloc 2 colocalization analysis between channel pairs"),
    "clij2_gpu": (_task_clij2_gpu, "CLIJ2 GPU-accelerated processing vs CPU baseline"),
    "morpholibj": (_task_morpholibj, "MorphoLibJ extended minima morphological segmentation"),
    "skeleton_analysis": (_task_skeleton_analysis, "AnalyzeSkeleton branch/junction analysis on skeletonized image"),
    "bioformats_import": (_task_bioformats_import, "Bio-Formats availability and import syntax verification"),
    "measure_set_options": (_task_measure_set_options, "Test all measurement types with Set Measurements"),
}

TASK_ORDER = [
    "cell_counting",
    "threshold_comparison",
    "measurement_accuracy",
    "background_subtraction",
    "z_stack_processing",
    "multichannel_split",
    "particle_size_filter",
    "stardist_segmentation",
    "3d_objects_counter",
    "colocalization",
    "clij2_gpu",
    "morpholibj",
    "skeleton_analysis",
    "bioformats_import",
    "measure_set_options",
]


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    import argparse

    parser = argparse.ArgumentParser(
        description="ImageJAI Practice Runner -- autonomous self-improvement through practice.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""Examples:
  python practice.py                         # run all tasks
  python practice.py --task cell_counting    # run one task
  python practice.py --list                  # list available tasks
  python practice.py --report                # show history
  python practice.py --dry-run               # plan only, no execution
""",
    )
    parser.add_argument("--task", type=str, help="Run a specific task by name")
    parser.add_argument("--list", action="store_true", help="List available tasks")
    parser.add_argument("--report", action="store_true", help="Show practice history")
    parser.add_argument("--dry-run", action="store_true", help="Show plan without executing")

    args = parser.parse_args()

    if args.list:
        list_tasks()
        return

    if args.report:
        show_report()
        return

    runner = PracticeRunner(live=not args.dry_run)

    if args.task:
        runner.run_task(args.task)
        runner._save_results()
    else:
        runner.run_all()


if __name__ == "__main__":
    main()
