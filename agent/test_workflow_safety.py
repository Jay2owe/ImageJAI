from __future__ import annotations

import json
import re
from concurrent.futures import ThreadPoolExecutor

import pytest

import adviser
import macro_lint
import practice
import session_log
import train_agent


class FakeFiji:
    """Small stateful stand-in for transactional workflow tests."""

    def __init__(self):
        self.images = ["User image"]
        self.active = "User image"
        self.results = "Area\n12.0\n"
        self.rois = ["User ROI"]
        self.black_background = False
        self.measurements = 123
        self.precision = 4
        self.redirect = "User image"
        self.calls = []
        self._snapshot = None

    def get_state(self):
        return {
            "ok": True,
            "result": {
                "activeImage": {"title": self.active} if self.active else None,
                "allImages": [{"title": title} for title in self.images],
                "resultsTable": self.results,
            },
        }

    def get_display_state(self):
        return {
            "ok": True,
            "result": {"activeImage": self.active, "c": 1, "z": 1, "t": 1},
        }

    def run_groovy(self, code):
        self.calls.append(("groovy", code))
        if "JsonOutput.toJson" in code:
            self._snapshot = {
                "images": list(self.images),
                "active": self.active,
                "results": self.results,
                "rois": list(self.rois),
                "black_background": self.black_background,
                "measurements": self.measurements,
                "precision": self.precision,
                "redirect": self.redirect,
            }
            settings = {
                "blackBackground": self.black_background,
                "measurements": self.measurements,
                "precision": self.precision,
                "redirectTitle": self.redirect,
                "roiManagerPresent": True,
                "roiCount": len(self.rois),
                "roiSelectedIndex": 0,
            }
            return {"ok": True, "result": {"success": True, "output": json.dumps(settings)}}
        if 'return "restored"' in code:
            assert self._snapshot is not None
            snapshot = self._snapshot
            self.results = snapshot["results"]
            self.rois = list(snapshot["rois"])
            self.black_background = snapshot["black_background"]
            self.measurements = snapshot["measurements"]
            self.precision = snapshot["precision"]
            self.redirect = snapshot["redirect"]
            return {"ok": True, "result": {"success": True, "output": "restored"}}
        if "ImageReader" in code:
            return {"ok": True, "result": {"success": True, "output": "3"}}
        return {"ok": True, "result": {"success": True, "output": ""}}

    def execute_macro(self, code):
        self.calls.append(("macro", code))
        if 'run("Clear Results")' in code:
            self.results = ""
        if 'roiManager("reset")' in code:
            self.rois = []
        if 'run("Blobs")' in code:
            self.images.append("Blobs")
            self.active = "Blobs"
        if 'setOption("BlackBackground", true)' in code:
            self.black_background = True
        for title in re.findall(r'selectWindow\("((?:\\.|[^"\\])*)"\);\s*close\(\)', code):
            title = title.replace('\\"', '"')
            if title in self.images:
                self.images.remove(title)
                self.active = self.images[-1] if self.images else None
        selected = re.findall(r'selectWindow\("((?:\\.|[^"\\])*)"\)', code)
        if selected and selected[-1] in self.images:
            self.active = selected[-1]
        return {"ok": True, "result": {"success": True, "output": "", "newImages": []}}

    def open_image(self, path, series=None, timeout=120):
        self.calls.append(("open_image", path, series, timeout))
        self.images.append(path)
        self.active = path
        return {"ok": True, "result": {"success": True, "opened": True}}


def assert_user_state(fake):
    assert fake.images == ["User image"]
    assert fake.active == "User image"
    assert fake.results == "Area\n12.0\n"
    assert fake.rois == ["User ROI"]
    assert fake.black_background is False
    assert fake.measurements == 123
    assert fake.precision == 4
    assert fake.redirect == "User image"


@pytest.mark.parametrize("fail", [False, True])
def test_training_transaction_restores_user_state_on_success_and_failure(monkeypatch, fail):
    fake = FakeFiji()
    monkeypatch.setattr(train_agent, "_import_ij", lambda: fake)
    monkeypatch.setattr(train_agent, "_connect_or_die", lambda _ij: True)

    def training_body(_ij, _image_dir, domain=None):
        assert fake.results == ""
        assert fake.rois == []
        fake.images.append("Training image")
        fake.active = "Training image"
        fake.results = "Count\n99\n"
        fake.rois = ["Training ROI"]
        fake.black_background = True
        fake.measurements = 999
        if fail:
            raise RuntimeError("training failed")
        return {"domain": domain}

    monkeypatch.setattr(train_agent, "_train_connected", training_body)
    if fail:
        with pytest.raises(RuntimeError, match="training failed"):
            train_agent.train("ignored", domain="test", seed=7)
    else:
        assert train_agent.train("ignored", domain="test", seed=7) == {"domain": "test"}
    assert_user_state(fake)


@pytest.mark.parametrize("fail", [False, True])
def test_practice_transaction_restores_user_state_on_success_and_failure(monkeypatch, fail):
    fake = FakeFiji()
    monkeypatch.setattr(practice, "_ij_module", fake)
    monkeypatch.setattr(practice, "execute_macro", fake.execute_macro)

    def task(runner):
        runner._run_macro('run("Close All");')
        runner._run_macro('run("Blobs");')
        runner._run_macro(
            'setAutoThreshold("Otsu"); run("Convert to Mask");'
            'run("Set Measurements...", "area");'
        )
        fake.results = "Count\n77\n"
        fake.rois = ["Practice ROI"]
        fake.measurements = 888
        if fail:
            raise RuntimeError("practice failed")
        return {"status": "pass", "attempts": [], "learnings": []}

    monkeypatch.setattr(practice, "TASKS", {"transaction": (task, "transaction")})
    runner = practice.PracticeRunner(live=False)
    runner.live = True
    runner.connected = True
    runner.autopsy = None
    result = runner.run_task("transaction")
    assert result["status"] == ("error" if fail else "pass")
    assert_user_state(fake)


def test_guard_refuses_to_start_when_snapshot_is_unavailable(tmp_path):
    fake = FakeFiji()
    fake.run_groovy = lambda _code: {"ok": False, "error": "no Groovy"}
    with pytest.raises(RuntimeError, match="workflow was not started"):
        with train_agent.ImageJWorkflowGuard(fake, temp_root=str(tmp_path)):
            raise AssertionError("must not enter")
    assert_user_state(fake)


def test_multiseries_open_enumerates_metadata_and_opens_only_first_series():
    fake = FakeFiji()
    assert train_agent._open_image(fake, "C:/data/container.lif") is True
    macro_calls = [call[1] for call in fake.calls if call[0] == "macro"]
    assert macro_calls == []
    assert ("open_image", "C:/data/container.lif", 0, 120) in fake.calls
    assert fake._imagejai_last_series_count == 3


def test_session_ids_are_strong_and_failed_macros_never_replay(tmp_path):
    first = session_log.SessionLogger()
    second = session_log.SessionLogger()
    assert first.session_id != second.session_id
    assert len(first.session_id.rsplit("-", 1)[-1]) == 32

    first.entries = [
        {
            "timestamp": "ok",
            "command": {"command": "execute_macro", "code": 'run("Blobs");'},
            "response": {"ok": True, "result": {"success": True}},
            "error": None,
        },
        {
            "timestamp": "failed",
            "command": {"command": "execute_macro", "code": 'run("Must Not Replay");'},
            "response": {"ok": True, "result": {"success": False, "error": "boom"}},
            "error": None,
        },
        {
            "timestamp": "transport",
            "command": {"command": "run_pipeline", "steps": [{"code": 'run("Also No");'}]},
            "response": None,
            "error": "disconnected",
        },
    ]
    path = tmp_path / "replay.ijm"
    first.export_macro(str(path))
    replay = path.read_text(encoding="utf-8")
    assert 'run("Blobs");' in replay
    assert "Must Not Replay" not in replay
    assert "Also No" not in replay


def test_session_log_persists_launcher_correlation_id(monkeypatch, tmp_path):
    monkeypatch.setenv("IMAGEJAI_SESSION_ID", "launch-session-123")
    logger = session_log.SessionLogger()
    path = tmp_path / "session.json"
    logger.save(str(path))
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["client_session_id"] == "launch-session-123"


def test_atomic_session_save_preserves_original_and_concurrent_files_are_valid(tmp_path):
    path = tmp_path / "session.json"
    path.write_text("original", encoding="utf-8")
    broken = session_log.SessionLogger()
    broken.entries = [{"not_json": object()}]
    with pytest.raises(TypeError):
        broken.save(str(path))
    assert path.read_text(encoding="utf-8") == "original"
    path.unlink()

    def save_one(index):
        logger = session_log.SessionLogger()
        logger.entries = [{"index": index, "command": {"command": "ping"}}]
        logger.save(str(path))

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(save_one, range(32)))
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["schema_version"] == 2
    assert payload["total_commands"] == 1
    assert payload["entries"][0]["index"] in range(32)


def test_corrupt_session_is_quarantined_before_replacement(tmp_path):
    path = tmp_path / "session.json"
    path.write_text("{corrupt", encoding="utf-8")
    logger = session_log.SessionLogger()
    logger.entries = [{"command": {"command": "ping"}, "response": {"ok": True}}]

    with pytest.raises(session_log.SessionLogCorruptionError) as caught:
        logger.save(str(path))

    assert caught.value.code == "CORRUPT_SESSION_QUARANTINED"
    assert not path.exists()
    quarantined = list(tmp_path.glob("session.json.corrupt-*"))
    assert len(quarantined) == 1
    assert quarantined[0].read_text(encoding="utf-8") == "{corrupt"
    with pytest.raises(session_log.SessionLogCorruptionError):
        logger.save(str(path))
    assert not path.exists()


def test_session_bounds_fail_with_structured_error(tmp_path, monkeypatch):
    monkeypatch.setattr(session_log, "MAX_ENTRY_BYTES", 64)
    logger = session_log.SessionLogger()
    logger.entries = [{"command": {"command": "ping", "payload": "x" * 100}}]

    with pytest.raises(session_log.SessionLogLimitError) as caught:
        logger.save(str(tmp_path / "bounded.json"))

    assert caught.value.code == "SESSION_ENTRY_TOO_LARGE"
    assert caught.value.as_dict()["limit"] == 64
    assert not (tmp_path / "bounded.json").exists()


def test_macro_lint_handles_literals_comments_and_documented_hazards():
    parser_safe = '''
/* run("Close All"); { ( */
message = "braces } and // are data";
run("Gaussian Blur...", "sigma=2"); // ) }
'''
    safe_warnings = macro_lint.lint_macro(parser_safe)
    assert not any("Unbalanced" in warning or "unmatched" in warning for warning in safe_warnings)

    hazardous = '''
path = "C:\\data\\image.tif";
run("Convert to Mask");
run("Analyze Particles...", "size=2 add");
run("Measure");
roiManager("Measure");
run("Enhance Contrast...", "saturated=0.35 normalize");
waitForUser("stop");
run("Some Plugin...");
run("X", "a", "b");
'''
    warnings = "\n".join(macro_lint.lint_macro(hazardous))
    for expected in (
        "forward slashes", "explicit threshold", "BlackBackground",
        "add_to_manager", "Clear Results", "stale ROIs", "rewrites pixel values",
        "waitForUser", "can hang automation", "at most a command",
    ):
        assert expected in warnings


def test_density_uses_successful_approach_counts_and_seeded_samples_repeat():
    image_records = [{
        "file": "a.tif", "format": ".tif", "type": "8-bit", "channels": 1,
        "slices": 1, "frames": 1, "width": 100, "height": 100,
    }]
    seg_results = [{
        "file": "a.tif",
        "approaches": {"classical": {"success": True, "count": 100}},
    }]
    profile = train_agent.build_profile(
        ".", image_records, [], seg_results,
        {"sigma": 1.0, "min_size": 20, "threshold": "Otsu"},
    )
    assert profile["object_density"] == "dense"

    population = list(range(50))
    train_agent._TRAINING_RNG.seed(19)
    first = train_agent._TRAINING_RNG.sample(population, 10)
    train_agent._TRAINING_RNG.seed(19)
    assert train_agent._TRAINING_RNG.sample(population, 10) == first


def test_adviser_resolves_packaged_references_and_refuses_traversal():
    assert "analysis" in adviser.load_reference("analysis-landscape.md").lower()
    assert adviser.load_reference("../CLAUDE.md") == ""
