"""Summarise paired console samples without treating profiler timings as claims."""
import argparse
import json
import math
import random
import statistics
from pathlib import Path


def operation_label(key):
    groups = {"picker_20": "Menu with 20 rows", "picker_1000": "Menu with 1,000 rows",
              "app_1": "Console with one chat", "app_100": "Console with 100 chats",
              "model_cached": "Cached model picker", "browse_2048": "File picker with 2,048 rows",
              "receipts_500": "Receipts with 500 rows", "tool_return_75KB": "Full return, about 75 KB"}
    actions = {"open_ms": "open", "filter_ms": "filter", "scroll_ms": "scroll frame",
               "ready_ms": "initial usable frame", "collapse_ms": "collapse sidebar",
               "events_1000_ms": "1,000 event arrivals", "event_filter_ms": "filter events",
               "typing_frame_ms": "typing frame"}
    if "." in key:
        group, action = key.split(".", 1)
        if group in groups:
            return f"{groups[group]}: {actions[action]}"
    return {"data.mentions_7_prefixes_ms": "File completion: seven fresh scans over 2,048 files",
            "data.save_3MB_ms": "Atomic session save, about 3 MB",
            "data.load_sessions_ms": "Load saved sessions",
            "data.catalog_ms": "Parse and assemble model registry",
            "data.replay_500_ms": "Prepare 500 replay entries (no drawing)",
            "tool_return_prepare_ms": "Prepare full tool return",
            "stream_120KB_ms": "Stream 125,000 characters in 5,000 fragments",
            "interrupt_dispatch_ms": "Set interruption flag",
            "idle_1000_ticks_ms": "1,000 unchanged status ticks",
            "replay_500_frame_ms": "Draw 500 replay entries"}.get(key, key)


def flatten(value, prefix=""):
    out = {}
    for key, item in value.items():
        name = prefix + key
        if isinstance(item, dict):
            out.update(flatten(item, name + "."))
        elif isinstance(item, (float, int)) and key.endswith("_ms"):
            out[name] = item
    return out


def summarise(path, seed=7746):
    data = json.loads(path.read_text(encoding="utf-8"))
    pairs = [(flatten(p["baseline"]), flatten(p["candidate"])) for p in data["pairs"]]
    out = {}
    rng = random.Random(seed)
    for key in pairs[0][0]:
        before = [a[key] for a, _ in pairs]
        after = [b[key] for _, b in pairs]
        logs = [math.log(b / a) for a, b in zip(before, after)]
        ratio = math.exp(statistics.mean(logs))
        bootstrap = sorted(math.exp(statistics.mean(rng.choices(logs, k=len(logs)))) for _ in range(10000))
        lower, upper = bootstrap[250], bootstrap[9750]
        out[key] = {"n_pairs": len(pairs), "baseline_median_ms": statistics.median(before),
                    "candidate_median_ms": statistics.median(after),
                    "candidate_p95_ms": sorted(after)[math.ceil(.95 * len(after)) - 1],
                    "time_ratio_geomean": ratio, "ratio_interval_95": [lower, upper],
                    "speedup_geomean": 1 / ratio,
                    "decision": "clear gain" if upper < .95 else "clear regression" if lower > 1.05 else "inconclusive"}
    return data, out


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    rows, all_results = [], {}
    for filename, label in (("raw.json", "Primary"), ("workflows.json", "Additional workflows")):
        data, metrics = summarise(args.folder / filename)
        all_results[label] = metrics
        rows += [f"## {label}", "", "| Operation | Before median | After median | Paired speedup | Time-ratio 95% interval | Evidence |",
                 "| --- | ---: | ---: | ---: | --- | --- |"]
        for key, item in metrics.items():
            lo, hi = item["ratio_interval_95"]
            rows.append(f"| {operation_label(key)} | {item['baseline_median_ms']:.1f} ms | {item['candidate_median_ms']:.1f} ms | {item['speedup_geomean']:.2f}× | {lo:.3f}–{hi:.3f} | {item['decision']} |")
        rows += ["", "Python allocations (whole sequential workload, separately instrumented):", "",
                 "| Build | Peak | Retained after collection |", "| --- | ---: | ---: |"]
        for version in ("baseline", "candidate"):
            mem = data[version + "_memory"]
            rows.append(f"| {version} | {mem['peak_bytes']/1048576:.1f} MiB | {mem['retained_bytes']/1048576:.1f} MiB |")
        rows += [""]
    (args.folder / "comparison.json").write_text(json.dumps(all_results, indent=2), encoding="utf-8")
    (args.folder / "RESULTS.md").write_text("# Measured console comparison\n\n" + "\n".join(rows) +
        "\nSpeedup is the geometric mean of the before/after ratio in each pair; it need not equal the ratio of the two medians. "
        "Intervals describe the remaining fraction of baseline time (candidate/baseline). They are exploratory paired bootstrap intervals "
        "(10,000 resamples, fixed seed), not adjusted for multiple comparisons. "
        "The reported 95th percentile is the slowest of these small trial sets; it does not establish a production tail-latency guarantee. "
        "Dialog measurements include the test driver's settled-screen barrier. Typing/scroll frame measurements use a refresh callback. "
        "Native terminal transport/compositor and real provider/Fiji execution are outside this timing boundary.\n", encoding="utf-8")
    print("Saved comparison.json and RESULTS.md")
