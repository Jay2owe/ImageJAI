from agent.console.jobs import JobTracker

class Fiji:
    def __init__(self, state="completed", ok=True): self.state, self.ok, self.calls = state, ok, []
    def command(self, payload, **kwargs):
        self.calls.append(payload)
        return {"ok": self.ok, "result": {"state": self.state, "result": {"success": True}}}

def test_owned_job_finishes_and_notifies_once_across_resume():
    tracker = JobTracker()
    tracker.observe("run_macro_async", {}, True, '"abc"', "fiji1")
    fiji = Fiji()
    tracker.poll(fiji, "fiji1")
    assert len(tracker.take_notices()) == 1
    restored = JobTracker(tracker.snapshot())
    assert restored.take_notices() == []
    restored.poll(fiji, "fiji1")
    assert len(fiji.calls) == 1

def test_other_installation_and_transient_errors_never_finish():
    tracker = JobTracker()
    tracker.observe("run_macro_async", {}, True, "a", "one")
    fiji = Fiji(ok=False)
    tracker.poll(fiji, "two")
    assert not fiji.calls
    tracker.poll(fiji, "one")
    assert not tracker.take_notices() and tracker.snapshot()[0]["state"] == "running"

def test_failed_job_and_duplicate_receipt():
    tracker = JobTracker()
    tracker.observe("run_macro_async", {}, True, "a")
    tracker.observe("run_macro_async", {}, True, "a")
    tracker.poll(Fiji("failed"))
    assert "failed" in tracker.take_notices()[0]
    assert len(tracker.snapshot()) == 1
