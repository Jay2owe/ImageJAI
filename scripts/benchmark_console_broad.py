"""Offline console comparison. Baseline source stays in RAM, never in backups.

Start --wait-for-edit before changing code; create --continue-file to compare.
Fixtures, credentials, menus and sessions are isolated. No network or Fiji runs.
"""
from __future__ import annotations

import argparse
import asyncio
import cProfile
import gc
import hashlib
import importlib
import importlib.abc
import importlib.metadata
import importlib.util
import io
import json
import os
import platform
import pstats
import sys
import tempfile
import time
import tracemalloc
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SOURCE = ROOT / "agent/console"


def snapshot():
    return {p.stem: p.read_text(encoding="utf-8") for p in SOURCE.glob("*.py")}


class SnapshotImporter(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    def __init__(self, package, sources):
        self.package, self.sources = package, sources

    def find_spec(self, fullname, path=None, target=None):
        if fullname == self.package:
            return importlib.util.spec_from_loader(fullname, self, is_package=True)
        if fullname.startswith(self.package + ".") and fullname.rsplit(".", 1)[-1] in self.sources:
            return importlib.util.spec_from_loader(fullname, self)

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        name = "__init__" if module.__name__ == self.package else module.__name__.rsplit(".", 1)[-1]
        module.__file__ = str(SOURCE / (name + ".py"))
        if name == "__init__":
            module.__path__ = [str(SOURCE)]
        exec(compile(self.sources[name], module.__file__, "exec"), module.__dict__)


def load(package, sources, home):
    sys.meta_path.insert(0, SnapshotImporter(package, sources))
    cfg = importlib.import_module(package + ".config")
    cfg.CONFIG_DIR, cfg.CONFIG_PATH = home, home / "console.json"
    cfg.SECRETS_PATH, cfg.PROVIDER_SECRETS_DIR = home / "secrets.json", home / "secrets"
    return {name: importlib.import_module(package + "." + name) for name in
            ("config", "sessions", "picklist_ui", "event_feed", "browse", "tui", "catalog", "replay")}


async def picker_trial(module, count):
    from textual.app import App
    from textual.widgets import Input
    class Host(App):
        def on_mount(self):
            self.push_screen(module.ListPickerScreen("Saved items", [
                {"label": f"Analysis {i:04d}", "id": i} for i in range(count)]))
    app = Host()
    start = time.perf_counter()
    async with app.run_test(size=(120, 40)) as pilot:
        await pilot.pause()
        opened = (time.perf_counter() - start) * 1000
        widget_count = len(list(app.screen.walk_children()))
        query = app.screen.query_one(Input)
        start = time.perf_counter()
        query.value = "Analysis 00"
        await pilot.pause()
        filtered = (time.perf_counter() - start) * 1000
        assert len([p for _, p in app.screen._visible if p is not None]) == min(count, 100)
        rows = app.screen.query_one("#picklist-list")
        done = asyncio.get_running_loop().create_future()
        start = time.perf_counter()
        rows.scroll_relative(y=3, animate=False, immediate=True)
        app.call_after_refresh(done.set_result, None)
        app.screen._on_timer_update()
        await asyncio.wait_for(done, 30)
        scroll = (time.perf_counter() - start) * 1000
    return {"open_ms": opened, "filter_ms": filtered, "scroll_ms": scroll, "widgets": widget_count}


async def app_trial(modules, session_count):
    from textual.widgets import Input
    class OfflineConsole(modules["tui"].ConsoleApp):
        def _poll_fiji(self):
            pass
        def _start_event_thread(self):
            pass
        def action_login(self):
            pass
    cfg = modules["config"].ConsoleConfig(auto_start_fiji=False)
    for path in modules["sessions"].sessions_dir().glob("*.json"):
        path.unlink()
    store = modules["sessions"].SessionStore(cfg)
    for i in range(session_count):
        session = modules["sessions"].Session(id=f"session{i:04d}", title=f"Chat {i:04d}",
                    messages=[{"role": "user", "content": "Open Blobs"},
                              {"role": "assistant", "content": "Opened Blobs. " * 20}])
        store.save_session(session)
    start = time.perf_counter()
    app = OfflineConsole(cfg)
    async with app.run_test(size=(120, 40)) as pilot:
        await pilot.pause()
        opened = (time.perf_counter() - start) * 1000
        assert app.query_one("#chat-input", Input).has_focus
        widgets = len(list(app.screen.walk_children()))
        start = time.perf_counter()
        app.action_toggle_left()
        await pilot.pause()
        collapse = (time.perf_counter() - start) * 1000
        # End-to-end events include summarising, filtering, RichLog and repaint.
        start = time.perf_counter()
        for i in range(1000):
            app._render_event({"event": "job.progress", "data": {"job_id": i, "percent": i % 100}})
        await pilot.pause()
        burst = (time.perf_counter() - start) * 1000
        assert len(app.event_feed.lines) == 1000
        start = time.perf_counter()
        app.query_one("#events-search", Input).value = "Job 99"
        await pilot.pause()
        event_filter = (time.perf_counter() - start) * 1000
        # Pure typing versus dialog/per-widget barrier; includes the usable frame.
        done = asyncio.get_running_loop().create_future()
        start = time.perf_counter()
        app.query_one("#chat-input", Input).value = "Please analyse the image"
        app.call_after_refresh(done.set_result, None)
        app.screen._on_timer_update()
        await asyncio.wait_for(done, 30)
        typing = (time.perf_counter() - start) * 1000
    return {"ready_ms": opened, "collapse_ms": collapse, "events_1000_ms": burst,
            "event_filter_ms": event_filter, "typing_frame_ms": typing, "widgets": widgets}


def sync_trial(modules, image_folder):
    def measured(fn):
        start = time.perf_counter()
        value = fn()
        return (time.perf_counter() - start) * 1000, value
    browse = modules["browse"]
    tokens = browse.PathTokenMap()
    elapsed, candidates = measured(lambda: [browse.mention_candidates(image_folder, p, 8,
        token_map=tokens) for p in ("", "i", "im", "ima", "imag", "image", "image_0")])
    assert all(len(c) == 8 for c in candidates)
    cfg = modules["config"].ConsoleConfig(auto_start_fiji=False)
    store = modules["sessions"].SessionStore(cfg)
    session = modules["sessions"].Session(id="large-save", messages=[
        {"role": "user" if i % 2 else "assistant", "content": "value " * 1000} for i in range(500)])
    saved, _ = measured(lambda: store.save_session(session))
    loaded, new_store = measured(lambda: modules["sessions"].SessionStore(cfg))
    assert new_store.get(session.id).messages == session.messages
    session_path = modules["sessions"].sessions_dir() / (session.id + ".json")
    session_path.unlink()
    engine = modules["catalog"].CatalogEngine(state_path=modules["config"].CONFIG_DIR / "catalog.json")
    catalog, result = measured(engine.offline)
    assert result.models
    replay_ms, entries = measured(lambda: modules["replay"].replay_entries([], session.messages, {}))
    assert len(entries) == 500
    return {"mentions_7_prefixes_ms": elapsed, "save_3MB_ms": saved, "load_sessions_ms": loaded,
            "catalog_ms": catalog, "replay_500_ms": replay_ms}


async def trial(modules, folder, profile=False):
    result = {}
    profiler = cProfile.Profile() if profile else None
    if profiler:
        profiler.enable()
    result["picker_20"] = await picker_trial(modules["picklist_ui"], 20)
    print("Measured picker_20: " + json.dumps(result["picker_20"]), flush=True)
    result["picker_1000"] = await picker_trial(modules["picklist_ui"], 1000)
    print("Measured picker_1000: " + json.dumps(result["picker_1000"]), flush=True)
    result["app_1"] = await app_trial(modules, 1)
    print("Measured app_1: " + json.dumps(result["app_1"]), flush=True)
    result["app_100"] = await app_trial(modules, 100)
    print("Measured app_100: " + json.dumps(result["app_100"]), flush=True)
    result["data"] = sync_trial(modules, folder)
    if profiler:
        profiler.disable()
        stream = io.StringIO()
        pstats.Stats(profiler, stream=stream).strip_dirs().sort_stats("cumtime").print_stats(40)
        result["profile"] = stream.getvalue()
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--wait-for-edit", action="store_true")
    parser.add_argument("--continue-file", type=Path)
    parser.add_argument("--trials", type=int, default=7)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="imagejai-perf-") as raw_home:
        home = Path(raw_home)
        os.environ["IMAGEJAI_HOME"] = str(home)
        os.environ["IMAGEJAI_MODELS_LOCAL"] = str(home / "models-local.yaml")
        os.environ["IMAGEJAI_AGENT_WORKSPACE"] = str(ROOT / "agent")
        baseline_sources = snapshot()
        baseline = load("agent.console_baseline", baseline_sources, home / "baseline")
        folder = home / "images"
        folder.mkdir()
        for i in range(2048):
            (folder / f"image_{i:04d}.tif").touch()
        output = {"environment": {"python": sys.version, "platform": platform.platform(),
                  "textual": importlib.metadata.version("textual"), "terminal": [120, 40]},
                  "corpus": {"files": 2048, "events": 1000, "sessions": [1, 100],
                             "menus": [20, 1000], "message_bytes": 3000000},
                  "baseline_source_sha256": hashlib.sha256(json.dumps(baseline_sources, sort_keys=True).encode()).hexdigest()}
        output["baseline_profile"] = asyncio.run(trial(baseline, folder, profile=True))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
        print("Baseline measured and source frozen in memory. " + str(args.output), flush=True)
        if args.wait_for_edit:
            if args.continue_file is None:
                parser.error("--wait-for-edit requires --continue-file")
            while not args.continue_file.exists():
                time.sleep(1)
        candidate_sources = snapshot()
        candidate = load("agent.console_candidate", candidate_sources, home / "candidate")
        output["candidate_source_sha256"] = hashlib.sha256(json.dumps(candidate_sources, sort_keys=True).encode()).hexdigest()
        output["pairs"] = []
        for i in range(args.trials):
            pair = {}
            for name, modules in (("baseline", baseline), ("candidate", candidate))[::1 if i % 2 == 0 else -1]:
                gc.collect()
                pair[name] = asyncio.run(trial(modules, folder))
            output["pairs"].append(pair)
            args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
            print(f"Pair {i + 1}/{args.trials} finished", flush=True)
        for name, modules in (("baseline", baseline), ("candidate", candidate)):
            gc.collect()
            tracemalloc.start()
            asyncio.run(trial(modules, folder))
            current, peak = tracemalloc.get_traced_memory()
            gc.collect()
            retained, _ = tracemalloc.get_traced_memory()
            tracemalloc.stop()
            output[name + "_memory"] = {"current_bytes": current, "peak_bytes": peak, "retained_bytes": retained}
        output["candidate_profile"] = asyncio.run(trial(candidate, folder, profile=True))
        args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
        print("Comparison complete", flush=True)


if __name__ == "__main__":
    main()
