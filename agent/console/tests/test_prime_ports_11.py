from agent.console.instrument import InstrumentProfile, InstrumentDetector, revalidate_entry
from agent.console.harness import HarnessStore

def test_upgrade_changes_signature_and_requires_review(tmp_path):
    root = tmp_path / "Fiji.app"
    (root / "plugins").mkdir(parents=True)
    jar = root / "plugins" / "test.jar"
    jar.write_bytes(b"version1")
    detector = InstrumentDetector()
    facts = detector.facts(root, {"active":{"title":"patient", "calibration":{"unit":"um"}}})
    assert "patient" not in str(facts)
    profile = InstrumentProfile(tmp_path / "profile.json")
    assert not profile.inspect(facts)["reviewed"]
    assert profile.approve(facts, "Verified against instrument metadata")["reviewed"]
    jar.write_bytes(b"version2")
    changed = detector.facts(root, {"active":{"calibration":{"unit":"um"}}})
    status = profile.inspect(changed)
    assert not status["reviewed"] and "plugins" in status["changed"]

def test_stable_jars_are_hashed_once(tmp_path, monkeypatch):
    import os
    from agent.console import instrument
    root = tmp_path / "Fiji.app"
    (root / "plugins").mkdir(parents=True)
    jar = root / "plugins" / "old.jar"
    jar.write_bytes(b"settled")
    os.utime(jar, (1_000_000_000, 1_000_000_000))
    hashed = []
    real_hash = instrument.file_hash
    monkeypatch.setattr(instrument, "file_hash", lambda path: hashed.append(path) or real_hash(path))
    detector = InstrumentDetector()
    detector.facts(root, {})
    detector.facts(root, {})
    assert hashed == [jar]

def test_instrument_memory_withheld_until_profile_and_entry_revalidated(tmp_path):
    store = HarnessStore(tmp_path / "state.json", tmp_path / "events.jsonl")
    entry = store.propose(kind="fact",scope="instrument",title="Pixel size",content="Use instrument calibration")
    entry = store.promote(entry.id,expected_version=entry.version,reviewer="user",evidence="Confirmed")
    state = {"_instrument_reviewed":True, "_instrument_fingerprint":"v1"}
    assert not store.retrieve(state=state)
    updated = revalidate_entry(store,entry,"v1","Checked current instrument")
    assert store.retrieve(state=state)[0].id == updated.id
    assert not store.retrieve(state={"_instrument_reviewed":False,"_instrument_fingerprint":"v1"})
    assert not store.retrieve(state={"_instrument_reviewed":True,"_instrument_fingerprint":"v2"})

def test_console_detects_z_calibration_change_when_display_label_is_unchanged(tmp_path):
    from types import SimpleNamespace
    from unittest.mock import Mock
    from agent.console.tui import ConsoleApp
    from agent.console.config import ConsoleConfig
    root=tmp_path/"Fiji.app"
    root.mkdir()
    calibration={"pixelWidth":1,"pixelHeight":1,"pixelDepth":2,"unit":"um","frameInterval":1,"timeUnit":"s"}
    app=ConsoleApp(ConsoleConfig(auto_start_fiji=False))
    app.fiji=SimpleNamespace(expected_root=root,command=lambda *a,**k:{"ok":True,"result":{"calibration":dict(calibration)}})
    app._fiji_state={"active":{"title":"generated fixture","calibration":"1 um/px"}}
    app.call_from_thread=lambda fn,*a:fn(*a)
    app._event_log=Mock()
    app._refresh_instrument(force=True)
    original=app.instrument_fingerprint
    assert app.instrument_status["facts"]["calibration"]["pixelDepth"]==2
    app._instrument_profile.approve(app.instrument_status["facts"],"Reviewed current voxel geometry")
    app._refresh_instrument(force=True)
    assert app.instrument_status["reviewed"]
    calibration["pixelDepth"]=3
    app._refresh_instrument(force=True)
    assert app.instrument_fingerprint != original and not app.instrument_status["reviewed"]
