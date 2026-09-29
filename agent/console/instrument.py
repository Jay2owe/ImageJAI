"""Local instrument fingerprints and explicit evidence-backed revalidation."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import time
import shutil
import threading
from .validation import file_hash

def fingerprint(facts):
    return hashlib.sha256(json.dumps(facts, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()

class InstrumentProfile:
    def __init__(self, path: Path):
        self.path = path
    def inspect(self, facts):
        signature = fingerprint(facts)
        try:
            stored = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            stored = {}
        except (ValueError, OSError):
            stored = {}
        previous = stored.get("facts", {})
        changed = sorted(key for key in set(previous) | set(facts) if previous.get(key) != facts.get(key))
        return {"fingerprint":signature, "reviewed":stored.get("fingerprint") == signature and bool(stored.get("evidence")),
                "changed":changed, "facts":facts}
    def approve(self, facts, evidence):
        if not evidence.strip():
            raise ValueError("Instrument review requires written evidence")
        value = {"schema":1, "fingerprint":fingerprint(facts), "facts":facts,
                 "reviewer":"console-user", "evidence":evidence, "timestamp":time.time()}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        temporary.replace(self.path)
        return self.inspect(facts)

class InstrumentDetector:
    def __init__(self):
        self.root = None
        self._plugins = {}
        self._lock = threading.RLock()
    def facts(self, root: Path, state):
        with self._lock:
            return self._facts(root, state)
    def _facts(self, root: Path, state):
        root = root.resolve()
        if root != self.root:
            self.root, self._plugins = root, {}
        plugins = {}
        candidates = sorted((root / "plugins").rglob("*.jar")) + sorted((root / "jars").glob("*.jar"))
        if len(candidates) > 4096:
            raise ValueError("Instrument plugin inventory exceeds its 4,096-file bound")
        if any(path.is_symlink() or not path.resolve().is_relative_to(root) for path in candidates):
            raise ValueError("Instrument JAR inventory leaves the selected installation")
        if shutil.disk_usage(root).free < sum(path.stat().st_size for path in candidates) + 16*1024*1024:
            raise ValueError("Insufficient free space to hydrate the instrument JAR inventory")
        for path in candidates:
            stat = path.stat()
            signature = (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
            previous = self._plugins.get(str(path))
            digest = previous[1] if previous and previous[0] == signature else file_hash(path)
            self._plugins[str(path)] = (signature, digest)
            plugins[str(path.relative_to(root)).replace("\\", "/")] = digest
        self._plugins = {name:value for name,value in self._plugins.items() if Path(name) in candidates}
        active = state.get("active") or {}
        raw = state.get("raw") or {}
        calibration = state.get("instrument_calibration") or active.get("calibration") or raw.get("calibration") or {}
        calibration = {key: value for key, value in calibration.items()
            if key in {"pixelWidth", "pixelHeight", "pixelDepth", "frameInterval", "unit", "timeUnit",
                       "pixel_width", "pixel_height", "pixel_depth", "frame_interval", "time_unit", "xOrigin", "yOrigin", "zOrigin"}
            and isinstance(value, (str, int, float, bool))} if isinstance(calibration, dict) else (
                {"display_calibration":calibration[:1024]} if isinstance(calibration, str) else {})
        # Store only configuration and calibration, never sample title/path.
        return {"installation_key":hashlib.sha256(str(root).encode()).hexdigest(),
                "plugins":plugins, "calibration":calibration}

def revalidate_entry(store, entry, signature, evidence):
    if not signature or not evidence.strip():
        raise ValueError("Revalidation requires a current instrument fingerprint and written evidence")
    metadata = dict(entry.metadata, needs_revalidation=False,
                    instrument_review={"fingerprint":signature, "evidence":evidence, "reviewer":"console-user", "timestamp":time.time()})
    return store.update(entry.id, expected_version=entry.version, metadata=metadata,
        applicability=dict(entry.applicability, instrument_fingerprint=signature),
        evidence=entry.evidence + [evidence])
