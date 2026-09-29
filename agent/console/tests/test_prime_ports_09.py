import json
import pytest
from agent.console.validation import load_manifest, run_validation, save_receipt, file_hash

@pytest.fixture
def frozen(tmp_path):
    macro = tmp_path / "test.ijm"
    macro.write_text('print("checked");')
    images = []
    for n, split in enumerate(("development", "held_out")):
        path = tmp_path / f"image{n}.tif"
        path.write_bytes(bytes([n])*100)
        images.append({"id":str(n), "path":path.name, "sha256":file_hash(path), "split":split, "expected":{"count":{"value":10+n, "tolerance":0}}})
    manifest = tmp_path / "validation.json"
    manifest.write_text(json.dumps({"schema":1,"reviewer":"test fixture", "procedure":{"path":macro.name,"sha256":file_hash(macro)},"cases":images}))
    return load_manifest(manifest)

def test_frozen_replay_and_held_out_receipt(frozen):
    receipt = run_validation(frozen, lambda path,macro,case:{"count":10+int(case["id"])})
    assert receipt["passed"] and len(receipt["cases"]) == 2
    assert receipt["cases"][1]["split"] == "held_out"
    assert save_receipt(frozen.path.parent, receipt).is_file()
    assert "Human review" in receipt["disclosure"]

def test_changed_inputs_and_wrong_counts_fail(frozen):
    assert not run_validation(frozen, lambda *args:{"count":0})["passed"]
    (frozen.path.parent / "image0.tif").write_bytes(b"changed")
    with pytest.raises(ValueError, match="changed"): frozen.verify_inputs()

def test_overlap_missing_checks_and_path_escape_refused(frozen):
    for mutate in [lambda spec:spec["cases"][1].update(sha256=spec["cases"][0]["sha256"]),
                   lambda spec:spec["cases"][1].update(expected={}),
                   lambda spec:spec["cases"][1].update(path="../outside.tif")]:
        spec = json.loads(frozen.path.read_text())
        mutate(spec)
        path = frozen.path.parent / "bad.json"
        path.write_text(json.dumps(spec))
        with pytest.raises((ValueError, FileNotFoundError)): load_manifest(path)

def test_mutation_during_run_invalidates_receipt(frozen):
    def runner(path, macro, case):
        path.write_bytes(b"changed")
        return {"count":10+int(case["id"])}
    assert not run_validation(frozen, runner)["passed"]

def test_nonfinite_failed_measurements_still_save_a_receipt(frozen):
    receipt = run_validation(frozen, lambda *args:{"count":float("nan")})
    assert not receipt["passed"]
    assert save_receipt(frozen.path.parent, receipt).is_file()

def test_real_fiji_envelopes_and_opaque_identity():
    from agent.console.validation import FijiValidationRunner, _field
    class Connection:
        def __init__(self): self.calls=[]
        def open_image(self, path): return {"ok":True,"result":{}}
        def image_info(self, **kwargs):
            self.calls.append(kwargs)
            return {"ok":True,"result":{"image_id":"image-opaque", "image_revision":7,"title":"source.tif", "width":32}}
        def results(self): return {"ok":True,"result":"Area,Mean\n10,2.5\n"}
        def command(self, payload, **kwargs):
            self.calls.append(payload)
            return {"ok":True,"result":{"job_id":"owned", "state":"completed", "workerExited":True,"result":{"success":True}}}
    connection=Connection()
    result=FijiValidationRunner(connection)("source.tif", 'print("verified");', {})
    assert _field(result, "results.rows.0.Mean") == 2.5
    assert result["results"]["count"] == 1
    assert {"image_id":"image-opaque", "image_revision":7} in connection.calls
    assert 'selectImage("source.tif")' in next(c["code"] for c in connection.calls if "code" in c)

def test_fiji_image_binding_is_forwarded_by_name(monkeypatch):
    from unittest.mock import Mock
    from agent.console.fiji import FijiConnection
    connection=FijiConnection("127.0.0.1",1)
    connection.command=Mock(return_value={"ok":True,"result":{}})
    connection.image_info(image_id="opaque",image_revision=7)
    connection.command.assert_called_once_with({"command":"get_image_info","image_id":"opaque","image_revision":7})

def test_interrupt_cancels_only_the_validation_job():
    import threading
    import time
    from agent.console.validation import FijiValidationRunner
    from agent.console.agent_loop import AbortFlag
    class Connection:
        def __init__(self): self.cancelled=[]
        def command(self, payload, **kwargs):
            if payload["command"] == "job_cancel": self.cancelled.append(payload["job_id"])
            return {"ok":True,"result":{"job_id":"validation-owned", "state":"running"}}
    connection,flag=Connection(),AbortFlag()
    timer=threading.Timer(.1,flag.set)
    timer.start()
    with pytest.raises(ValueError,match="interrupted"):
        FijiValidationRunner(connection,flag)._macro("while(true) {}")
    timer.join()
    deadline=time.monotonic()+1
    while not connection.cancelled and time.monotonic()<deadline: time.sleep(.01)
    assert connection.cancelled == ["validation-owned"]
