"""Run the real terminal driver with isolated settings and offline boundaries."""
import argparse
import json
import os
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--receipt", type=Path, required=True)
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix="imagejai-terminal-smoke-") as temporary:
    os.environ["IMAGEJAI_HOME"] = temporary
    os.environ["IMAGEJAI_MODELS_LOCAL"] = str(Path(temporary) / "models.yaml")
    os.environ["IMAGEJAI_AGENT_WORKSPACE"] = str(Path(__file__).resolve().parents[1] / "agent")
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from agent.console.tui import ConsoleApp, LoginScreen
    from agent.console.config import ConsoleConfig
    LoginScreen._probe_subscriptions = lambda self: None

    class OfflineConsole(ConsoleApp):
        def __init__(self):
            super().__init__(ConsoleConfig(auto_start_fiji=False))
            self.visited = []
        def _poll_fiji(self): pass
        def _start_event_thread(self): pass
        def _start_fiji_worker(self): pass
        def on_mount(self):
            super().on_mount()
            if isinstance(self.screen, LoginScreen): self.pop_screen()
            self.set_interval(.05, self.record_screen)
        def record_screen(self):
            name = type(self.screen).__name__
            if not self.visited or self.visited[-1] != name: self.visited.append(name)
        def on_unmount(self):
            super().on_unmount()
            args.receipt.parent.mkdir(parents=True, exist_ok=True)
            args.receipt.write_text(json.dumps({"screens": self.visited,
                "sessions": len(self.store.list()), "left_visible": self.config.show_left_rail,
                "right_visible": self.config.show_right_panel, "terminal_driver": "Textual production driver",
                "offline_boundaries": True}, indent=2), encoding="utf-8")

    OfflineConsole().run()
