import threading
import time
import pytest
from agent.console.kernel import PythonWorkspace
from agent.console.agent_loop import AbortFlag

@pytest.fixture
def workspace():
    value = PythonWorkspace()
    yield value
    value.close()

def test_lazy_persistent_namespace_and_output_bound(workspace):
    assert workspace._process is None
    assert workspace.execute("a = np.arange(5)")["ok"]
    assert workspace.execute("int(a.sum())")["output"].strip() == "10"
    result = workspace.execute("print('x' * 100000)")
    assert result["ok"] and len(result["output"]) == 16384
    assert result["omitted_output_chars"] > 80000

@pytest.mark.parametrize("code", ["import os", "open('x')", "np.load('x')", "np.__dict__", "import socket"])
def test_unavailable_host_helpers_fail(workspace, code):
    assert not workspace.execute(code)["ok"]

def test_timeout_clears_namespace_and_process(workspace):
    result = workspace.execute("while True: pass", timeout=.5)
    assert not result["ok"] and workspace._process is None
    assert workspace.execute("2+3")["output"].strip() == "5"

def test_interrupt_stops_process(workspace):
    flag = AbortFlag()
    threading.Timer(.5, flag.set).start()
    start = time.monotonic()
    assert not workspace.execute("while True: pass", abort=flag)["ok"]
    assert time.monotonic() - start < 2
    assert workspace._process is None

def test_stale_pixels_fail_closed(workspace):
    workspace._image_binding = (1, 2, 3, 1, 1, 1, 1)
    class Fiji:
        def command(self, payload, **kwargs):
            return {"ok": True, "result": {"image_id":1, "image_revision":3}}
    workspace.connection = Fiji()
    assert "changed" in workspace.execute("a.mean()")["error"]
