"""Exercise the console's TCP client against an existing, owned Fiji sandbox.

No JAR installation, user Fiji control, model traffic or physical input.
The optional external test harness owns the isolated JVM and its teardown.
"""
import argparse
import asyncio
import json
import os
import sys
from contextlib import ExitStack
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness-root", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--sandbox-fiji", type=Path,
                        help="Use another provisioned, harness-owned Fiji for this run")
    parser.add_argument("--prime-ports", action="store_true")
    args = parser.parse_args()
    sys.path.insert(0, str(args.harness_root.resolve() / "src"))
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from imagej_plugin_test_harness.config import HarnessConfig
    from imagej_plugin_test_harness.orchestrator import Orchestrator
    from imagej_plugin_test_harness.provision import SandboxFiji, resolve_launcher, SANDBOX_MARKER
    from imagej_plugin_test_harness.workspace import RunWorkspace

    config = HarnessConfig.load(args.harness_root / "harness.yaml")
    if args.sandbox_fiji is not None:
        config.sandbox.sandbox_fiji = args.sandbox_fiji.resolve()
    root = config.sandbox.sandbox_fiji.resolve()
    assert (root / SANDBOX_MARKER).is_file(), "Provision the owned sandbox first"
    config.bridge.requested_port = 0
    config.bridge.privacy_posture = "STANDARD"
    # Keep the external harness's configured cold-start deadline. Its local
    # contract allows launcher startup on this machine to exceed one minute.
    config.timeouts.graceful_stop_s = 2
    config.timeouts.forced_stop_s = 5
    orchestrator = Orchestrator(config)
    orchestrator.workspace = RunWorkspace.create(config.sandbox.runs_root, run_id=orchestrator.run_id)
    workspace = orchestrator.workspace
    sandbox = SandboxFiji(root, resolve_launcher(root, config.sandbox.launcher), None, "existing", 0)
    receipt = {"status": "NOT_RUN", "scope": "live TCP client and macro journal; no physical input",
               "checks": [], "owned_process_stopped": False}
    try:
        with ExitStack() as stack:
            orchestrator._acquire_sandbox(stack)
            owned, readiness, client, bridge = orchestrator._start_and_connect(sandbox, stack)
            receipt["checks"].append("owned Fiji ready and authenticated")
            os.environ["USERPROFILE"] = str(workspace.home)
            os.environ["IMAGEJAI_HOME"] = str(workspace.home / ".imagej-ai")
            os.environ["IMAGEJAI_MODELS_LOCAL"] = str(workspace.home / "models-local.yaml")
            os.environ["IMAGEJAI_AGENT_WORKSPACE"] = str(Path(__file__).resolve().parents[1] / "agent")
            from agent.console.fiji import FijiConnection
            connection = FijiConnection(config.bridge.host, readiness.port)
            ij = connection._module()
            ij._CACHE_DIR = str(workspace.root / "console-cache")
            ij._CACHE_FILE = str(Path(ij._CACHE_DIR) / "ij_client_cache.json")
            ij._READONLY_CACHE.clear()
            ij._READONLY_CACHE_DIRTY = False
            assert connection.installation_root() == root
            connection.select_installation(root)
            for name, call in [("ping", connection.ping), ("state", connection.get_state),
                               ("results", connection.results), ("ROIs", connection.rois),
                               ("console", connection.console_tail)]:
                assert call().get("ok")
                receipt["checks"].append(name)
            from agent.console.tui import ConsoleApp
            from agent.console.config import ConsoleConfig
            class OfflineProviderConsole(ConsoleApp):
                def _poll_fiji(self): pass
                def _start_event_thread(self): pass
                def action_login(self): pass
            async def macro():
                app = OfflineProviderConsole(ConsoleConfig(auto_start_fiji=False))
                app.fiji = connection
                code = 'print("ImageJAI console audit: macro completed");'
                async with app.run_test(size=(120, 40)) as pilot:
                    await pilot.pause()
                    app._fiji_macro(code)
                    await app.workers.wait_for_complete()
                    await pilot.pause()
                    assert any(entry.code == code for entry in app.macro_journal.snapshot())
                    reply = connection.command({"command": "get_log"})
                    assert "ImageJAI console audit: macro completed" in json.dumps(reply)
                receipt["checks"].extend(["macro executed through console worker", "macro saved in session journal", "macro completion in Fiji log"])
            asyncio.run(macro())
            if args.prime_ports:
                import time
                import numpy as np
                from PIL import Image
                from agent.console.kernel import PythonWorkspace
                from agent.console.jobs import JobTracker
                from agent.console.validation import file_hash, load_manifest, run_validation, save_receipt, FijiValidationRunner
                fixtures = workspace.root / "validation-fixtures"
                fixtures.mkdir()
                procedure = fixtures / "measure.ijm"
                procedure.write_text('getStatistics(area, mean, minimum, maximum, std); setResult("Mean", 0, mean); updateResults();', encoding="utf-8")
                cases = []
                for number, split in enumerate(("development", "held_out"), 1):
                    path = fixtures / f"constant-{number}.tif"
                    Image.fromarray(np.full((32,32), number, dtype=np.uint8)).save(path)
                    cases.append({"id":str(number),"path":path.name,"sha256":file_hash(path),"split":split,
                        "expected":{"results.rows.0.Mean":{"value":number,"tolerance":0},"image.width":{"value":32}}})
                connection.open_image(str(fixtures / cases[0]["path"]))
                python = PythonWorkspace(connection)
                try:
                    answer = python.execute("a = pixels(0,0,4,4); float(a.mean())")
                    assert answer.get("ok"), answer
                    assert answer["output"].strip() == "1.0"
                    assert python.execute("int(a.sum())")["output"].strip() == "16"
                    connection.execute_macro('run("Invert");')
                    assert not python.execute("a.mean()")["ok"]
                finally:
                    python.close()
                receipt["checks"].extend(["live pixel arrays persist between cells", "changed Fiji pixels invalidate Python variables", "owned Python child stopped"])
                tracker = JobTracker()
                async_reply = connection.command({"command":"execute_macro_async","code":'print("Owned console job complete");'})
                tracker.observe("run_macro_async",{},True,json.dumps(async_reply),str(root))
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline and tracker.snapshot()[0]["state"] not in {"completed","failed"}:
                    tracker.poll(connection,str(root))
                    time.sleep(.1)
                notices = tracker.take_notices()
                assert len(notices) == 1 and "completed" in notices[0] and not tracker.take_notices()
                receipt["checks"].append("owned asynchronous macro completion delivered once")
                manifest = fixtures / "validation.json"
                manifest.write_text(json.dumps({"schema":1,"reviewer":"generated software fixtures; no biological claim",
                    "procedure":{"path":procedure.name,"sha256":file_hash(procedure)},"cases":cases}),encoding="utf-8")
                validation = run_validation(load_manifest(manifest),FijiValidationRunner(connection))
                saved = save_receipt(fixtures,validation)
                receipt["validation"] = {"passed":validation["passed"], "cases":validation["cases"]}
                assert validation["passed"], validation
                assert saved.is_file()
                receipt["checks"].extend(["live frozen development and held-out fixture checks passed", "validation receipt saved beside fixtures", "fixture hashes unchanged after Fiji analysis"])
            receipt["status"] = "PASS"
    except Exception as exc:
        receipt["status"] = "FAIL" if receipt["checks"] else "NOT_RUN"
        receipt["error_type"] = type(exc).__name__
        receipt["error_code"] = getattr(exc, "code", "live_check_failed")
    finally:
        teardown = orchestrator.manifest.integrity.get("process_teardown", {})
        receipt["owned_process_stopped"] = bool(teardown) and not teardown.get("leaks")
        if receipt["status"] == "PASS" and not receipt["owned_process_stopped"]:
            receipt["status"] = "FAIL"
            receipt["error_code"] = "owned_process_cleanup_unproven"
        receipt["harness_run"] = orchestrator.run_id
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2), encoding="utf-8")
        print(json.dumps(receipt, indent=2))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
