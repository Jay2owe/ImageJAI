"""Additional offline interaction/memory workloads, with a frozen RAM baseline."""
import argparse
import asyncio
import cProfile
import gc
import importlib
import io
import json
import os
import pstats
import tempfile
import time
import tracemalloc
from pathlib import Path

from benchmark_console_broad import ROOT, load, snapshot


async def workflows(modules, profile=False):
    from textual.app import App
    from textual.widgets import Input
    prefix = modules["tui"].__package__
    get = lambda name: importlib.import_module(prefix + "." + name)
    profiler = cProfile.Profile() if profile else None
    if profiler:
        profiler.enable()
    result = {}
    class Host(App):
        def compose(self):
            yield Input(value="draft")
    async def modal(label, screen):
        app = Host()
        async with app.run_test(size=(120, 40)) as pilot:
            start = time.perf_counter()
            app.push_screen(screen)
            await pilot.pause()
            result[label] = {"open_ms": (time.perf_counter() - start) * 1000,
                             "widgets": len(list(screen.walk_children()))}
            await pilot.press("escape")
            assert app.query_one(Input).value == "draft"
        print(label + ": " + json.dumps(result[label]), flush=True)
    picker = get("picker_ui")
    picker.subscription_entries = lambda: []
    engine = modules["catalog"].CatalogEngine(state_path=modules["config"].CONFIG_DIR / "workflow-catalog.json")
    engine.offline()
    await modal("model_cached", picker.ModelPickerScreen(engine))
    entries = [modules["browse"].SeriesEntry(Path(f"image{i}.tif"), 0, f"token{i}", f"Image {i}") for i in range(2048)]
    await modal("browse_2048", get("browse_ui").BrowseFilesScreen("owned-fixture", entries, tag_rules=[]))
    await modal("receipts_500", get("governance_ui").ReceiptsScreen([
        {"time": str(i), "command": "Fiji operation", "bytes_out": 64, "raw": {"id": i}} for i in range(500)], "500 calls"))
    returns = get("tool_results_ui")
    raw = json.dumps({"ok": True, "result": {"rows": [{"value": i, "label": "data" * 10} for i in range(1000)]}})
    start = time.perf_counter()
    screen = returns.ToolResultScreen(returns.ToolResultDetail("Results", "1000 rows", raw=raw))
    result["tool_return_prepare_ms"] = (time.perf_counter() - start) * 1000
    await modal("tool_return_75KB", screen)
    class OfflineConsole(modules["tui"].ConsoleApp):
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def action_login(self): pass
    app = OfflineConsole(modules["config"].ConsoleConfig(auto_start_fiji=False))
    async with app.run_test(size=(120, 40)) as pilot:
        await pilot.pause()
        start = time.perf_counter()
        for i in range(5000):
            app._stream_delta_ui("reasoning or reply text. ", "thinking")
        app._paint_live_text()
        await pilot.pause()
        result["stream_120KB_ms"] = (time.perf_counter() - start) * 1000
        assert len(app._live_text) == 5000 * len("reasoning or reply text. ")
        app._flush_live_text()
        await pilot.pause()
        assert not app._live_text and not app.last_evidence_error
        # A slow provider must not make editing or Escape wait on its thread.
        app.turn_running = True
        app.abort = get("agent_loop").AbortFlag()
        app._turn_started = time.monotonic()
        start = time.perf_counter()
        app.action_interrupt()
        result["interrupt_dispatch_ms"] = (time.perf_counter() - start) * 1000
        assert app.abort.set_flag
        app.turn_running = False
        # Inspect the cost of ticks while idle, without driver barriers.
        start = time.perf_counter()
        for _ in range(1000):
            app._render_posture()
            app._tick_turn_status()
            app.query_one("#macro-history").refresh_entries()
        result["idle_1000_ticks_ms"] = (time.perf_counter() - start) * 1000
        app.session.messages = [{"role": "assistant" if i % 2 else "user", "content": "Synthetic saved analysis. " * 10} for i in range(500)]
        app._reset_conversation_ui()
        start = time.perf_counter()
        app._render_session_messages()
        await pilot.pause()
        result["replay_500_frame_ms"] = (time.perf_counter() - start) * 1000
    if profiler:
        profiler.disable()
        stream = io.StringIO()
        pstats.Stats(profiler, stream=stream).strip_dirs().sort_stats("cumtime").print_stats(35)
        result["profile"] = stream.getvalue()
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--continue-file", type=Path, required=True)
    parser.add_argument("--trials", type=int, default=5)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="imagejai-workflow-perf-") as temporary:
        home = Path(temporary)
        os.environ["IMAGEJAI_HOME"] = str(home)
        os.environ["IMAGEJAI_MODELS_LOCAL"] = str(home / "models-local.yaml")
        os.environ["IMAGEJAI_AGENT_WORKSPACE"] = str(ROOT / "agent")
        before = snapshot()
        # Isolate these two additional transformations in RAM. The primary
        # broad benchmark separately retains the original pre-task package.
        before["browse_ui"] = before["browse_ui"].replace("        options = []\n", "").replace(
            '            options.append((Text(label), row["Token"], row["Token"] in self._selected_tokens))\n        selection.add_options(options)',
            '            selection.add_option((Text(label), row["Token"], row["Token"] in self._selected_tokens))')
        before["governance_ui"] = before["governance_ui"].replace('yield NavigableOptionList(id="rcp-list")', 'yield ListView(id="rcp-list")').replace(
            'listing = self.query_one("#rcp-list", NavigableOptionList)\n        listing.add_options([Option(Text.from_markup(self._row_label(row)), id=str(index))\n                             for index, row in enumerate(self.rows)])',
            'listing = self.query_one("#rcp-list", ListView)\n        for index, row in enumerate(self.rows):\n            listing.append(ListItem(Label(self._row_label(row)), name=str(index)))').replace(
            '    def on_option_list_option_highlighted(self, event: OptionList.OptionHighlighted) -> None:\n        if event.option_list.id == "rcp-list":\n            event.stop()\n            self._show_detail(event.option_index)',
            '    def on_list_view_highlighted(self, event: ListView.Highlighted) -> None:\n        if event.list_view.id == "rcp-list" and event.list_view.index is not None:\n            self._show_detail(int(event.list_view.index))')
        baseline = load("agent.workflows_baseline", before, home / "baseline")
        output = {"baseline_profile": asyncio.run(workflows(baseline, profile=True)), "pairs": []}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
        print("Workflow baseline ready", flush=True)
        while not args.continue_file.exists():
            time.sleep(1)
        after = snapshot()
        candidate = load("agent.workflows_candidate", after, home / "candidate")
        import hashlib
        output["source_sha256"] = {label: hashlib.sha256(json.dumps(source, sort_keys=True).encode()).hexdigest()
                                   for label, source in (("baseline", before), ("candidate", after))}
        for i in range(args.trials):
            pair = {}
            for label, modules in (("baseline", baseline), ("candidate", candidate))[::1 if i % 2 == 0 else -1]:
                pair[label] = asyncio.run(workflows(modules))
            output["pairs"].append(pair)
            args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
            print(f"Workflow pair {i + 1} complete", flush=True)
        output["candidate_profile"] = asyncio.run(workflows(candidate, profile=True))
        for label, modules in (("baseline", baseline), ("candidate", candidate)):
            gc.collect()
            tracemalloc.start()
            asyncio.run(workflows(modules))
            _, peak = tracemalloc.get_traced_memory()
            gc.collect()
            retained, _ = tracemalloc.get_traced_memory()
            tracemalloc.stop()
            output[label + "_memory"] = {"peak_bytes": peak, "retained_bytes": retained}
        args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
