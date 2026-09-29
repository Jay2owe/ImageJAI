"""Measure model-picker interactions on the saved registry, without network.

For a paired comparison, start with --wait-for-edit before editing picker_ui.py.
The old class stays in memory; no source backup or credentials are written.
Results contain timings and a registry hash, not model names or user paths.
"""
from __future__ import annotations

import argparse
import asyncio
import cProfile
import hashlib
import importlib.metadata
import io
import json
import platform
import pstats
import statistics
import sys
import tempfile
import time
import types
from dataclasses import asdict, replace
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from textual.app import App
from textual.widgets import Input
from agent.console.catalog import CatalogEngine, CatalogEntry
from agent.console.model_choice import subscription_entries


def load_screen(source: str, label: str, subscriptions):
    module = types.ModuleType(f"agent.console._benchmark_picker_{label}")
    module.__package__ = "agent.console"
    module.__file__ = str(ROOT / "agent/console/picker_ui.py")
    sys.modules[module.__name__] = module
    exec(compile(source, module.__file__, "exec"), module.__dict__)
    module.subscription_entries = lambda: list(subscriptions)
    return module.ModelPickerScreen


class FrozenEngine:
    def __init__(self, result):
        self.result = result

    def today(self):
        return date.today()

    def offline(self):
        return self.result


async def trial(screen_class, result, steps: int, profile: bool = False):
    class Host(App):
        def on_mount(self):
            self.push_screen(screen_class(FrozenEngine(result)))

    host = Host()
    profiler = cProfile.Profile() if profile else None
    samples = {"wheel_ms": [], "keyboard_ms": []}
    opened = time.perf_counter()
    async with host.run_test(size=(120, 40)) as pilot:
        await pilot.pause()
        open_ms = (time.perf_counter() - opened) * 1000
        rows = host.screen.query_one("#picker-list")
        widgets = len(list(host.screen.walk_children()))
        row_count = len(host.screen._rows)
        rows.focus()
        rows.scroll_to(y=0, animate=False, immediate=True)
        await pilot.pause()

        async def frame(action):
            done = asyncio.get_running_loop().create_future()
            action()
            host.call_after_refresh(done.set_result, None)
            # Force the requested frame instead of adding the test driver's
            # per-widget barrier or an arbitrary sleep to the timed operation.
            host.screen._on_timer_update()
            await asyncio.wait_for(done, 30)

        for _ in range(3):
            await frame(lambda: rows.scroll_relative(y=3, animate=False, immediate=True))
        if profiler:
            profiler.enable()
        for _ in range(steps):
            started = time.perf_counter()
            await frame(lambda: rows.scroll_relative(y=3, animate=False, immediate=True))
            samples["wheel_ms"].append((time.perf_counter() - started) * 1000)
        rows.index = next(i for i, e in enumerate(host.screen._rows) if e is not None)
        for _ in range(steps):
            started = time.perf_counter()
            await frame(rows.action_cursor_down)
            samples["keyboard_ms"].append((time.perf_counter() - started) * 1000)
        if profiler:
            profiler.disable()
        assert rows.scroll_y > 0
        assert host.screen._selected() is not None
        search = host.screen.query_one("#picker-search", Input)
        started = time.perf_counter()
        search.value = "openrouter"
        await pilot.pause()
        filter_ms = (time.perf_counter() - started) * 1000
        assert all(e is None or e.provider == "openrouter" for e in host.screen._rows)
    measured = {**samples, "open_ms": open_ms, "filter_ms": filter_ms,
                "widgets": widgets, "display_rows": row_count}
    if profiler:
        output = io.StringIO()
        pstats.Stats(profiler, stream=output).strip_dirs().sort_stats("cumulative").print_stats(18)
        measured["profile"] = output.getvalue()
    return measured


async def run(args):
    source_path = ROOT / "agent/console/picker_ui.py"
    original = source_path.read_text(encoding="utf-8")
    subscriptions = tuple(subscription_entries())
    baseline_class = load_screen(original, "baseline", subscriptions)
    with tempfile.TemporaryDirectory(prefix="imagejai-picker-bench-") as temporary:
        result = CatalogEngine(credentials={}, state_path=Path(temporary) / "state.json").offline()
    if args.models:
        # Exercise a large discovered registry, preserving display attributes.
        models = tuple(CatalogEntry("openrouter", f"benchmark-model-{i:04d}",
                                    display_name=f"Benchmark model {i:04d}", context_window=128000)
                       for i in range(args.models))
        result = replace(result, models=models, all_models=models)
    corpus = json.dumps([asdict(entry) for entry in result.models], sort_keys=True, default=str)
    report = {
        "environment": {"os": platform.platform(), "cpu": platform.processor(),
                        "python": platform.python_version(), "textual": importlib.metadata.version("textual")},
        "models": len(result.models), "corpus_sha256": hashlib.sha256(corpus.encode()).hexdigest(),
        "subscription_count": len(subscriptions),
        "subscription_sha256": hashlib.sha256(json.dumps(
            [asdict(entry) for entry in subscriptions], sort_keys=True, default=str).encode()).hexdigest(),
        "baseline_source_sha256": hashlib.sha256(original.encode()).hexdigest(),
        "viewport": [120, 40], "steps": args.steps, "trials": [],
        "boundary": "interaction through painted headless Textual frame; excludes setup/network/terminal I/O",
    }
    initial = await trial(baseline_class, result, args.steps, profile=True)
    mode = "baseline" if args.wait_for_edit else "current"
    report[f"initial_profiled_{mode}"] = initial
    print(json.dumps({"phase": mode, "models": report["models"],
                      "widgets": initial["widgets"],
                      "wheel_median_ms": statistics.median(initial["wheel_ms"]),
                      "profile": initial["profile"]}), flush=True)
    if args.wait_for_edit:
        print("Ready for picker edit; baseline class retained in memory.", flush=True)
        while source_path.read_text(encoding="utf-8") == original:
            await asyncio.sleep(0.2)
        candidate_source = source_path.read_text(encoding="utf-8")
        candidate_class = load_screen(candidate_source, "candidate", subscriptions)
        report["candidate_source_sha256"] = hashlib.sha256(candidate_source.encode()).hexdigest()
        for index in range(args.trials):
            pair = {}
            ordering = [("baseline", baseline_class), ("candidate", candidate_class)]
            if index % 2:
                ordering.reverse()
            for label, screen in ordering:
                pair[label] = await trial(screen, result, args.steps)
            report["trials"].append(pair)
            print(json.dumps({"phase": "paired", "trial": index + 1, **{
                label: {"wheel_median_ms": statistics.median(value["wheel_ms"]),
                        "keyboard_median_ms": statistics.median(value["keyboard_ms"]),
                        "widgets": value["widgets"]} for label, value in pair.items()}}), flush=True)
        report["candidate_profile"] = await trial(candidate_class, result, args.steps, profile=True)
    else:
        for index in range(args.trials):
            measured = await trial(baseline_class, result, args.steps)
            report["trials"].append({"current": measured})
            print(json.dumps({"phase": "current", "trial": index + 1,
                              "wheel_median_ms": statistics.median(measured["wheel_ms"]),
                              "keyboard_median_ms": statistics.median(measured["keyboard_ms"]),
                              "open_ms": measured["open_ms"], "filter_ms": measured["filter_ms"]}), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Saved measurements to {args.output.name}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wait-for-edit", action="store_true")
    parser.add_argument("--models", type=int)
    parser.add_argument("--steps", type=int, default=16)
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--output", type=Path, required=True)
    asyncio.run(run(parser.parse_args()))
