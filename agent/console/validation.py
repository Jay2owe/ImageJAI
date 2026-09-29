"""Frozen, declared image checks; passing software checks never auto-promote memory."""
from __future__ import annotations
import argparse
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import shutil
import time
import threading
import uuid

def file_hash(path: Path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate manifest key: " + key)
        result[key] = value
    return result

def strict_json(text):
    def nonfinite(value):
        raise ValueError("Non-finite manifest number: " + value)
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=nonfinite)

def local_file(root, name):
    if not isinstance(name, str) or Path(name).is_absolute() or ".." in Path(name).parts:
        raise ValueError("Manifest files must be relative to its image folder")
    path = (root / name).resolve(strict=True)
    if not path.is_relative_to(root) or not path.is_file():
        raise ValueError("Manifest file leaves its image folder")
    return path

@dataclass
class FrozenValidation:
    path: Path
    manifest: dict
    manifest_hash: str
    macro: str

    def verify_inputs(self):
        root = self.path.parent
        if file_hash(self.path) != self.manifest_hash:
            raise ValueError("Validation manifest changed")
        files = [self.manifest["procedure"], *self.manifest["cases"]]
        peak = sum(local_file(root, row["path"]).stat().st_size for row in files)
        if shutil.disk_usage(root).free < peak + 32 * 1024 * 1024:
            raise ValueError("Insufficient free space for input hydration and validation outputs")
        for row in files:
            if file_hash(local_file(root, row["path"])) != row["sha256"]:
                raise ValueError("A frozen validation input changed: " + row["path"])

def load_manifest(path: Path) -> FrozenValidation:
    path = path.resolve(strict=True)
    if path.stat().st_size > 256 * 1024:
        raise ValueError("Validation manifest exceeds 256 KiB")
    raw = path.read_text(encoding="utf-8-sig")
    spec = strict_json(raw)
    if not isinstance(spec, dict) or spec.get("schema") != 1:
        raise ValueError("Validation manifest needs schema 1")
    if not isinstance(spec.get("reviewer"), str) or not spec["reviewer"].strip():
        raise ValueError("Declare who supplied the trusted expected measurements")
    cases = spec.get("cases")
    procedure = spec.get("procedure")
    if not isinstance(cases, list) or not 2 <= len(cases) <= 128 or not isinstance(procedure, dict):
        raise ValueError("Declare a procedure and 2–128 development/held-out cases")
    hashes, ids, splits = set(), set(), set()
    for row in [procedure, *cases]:
        if not isinstance(row, dict) or not isinstance(row.get("sha256"), str) or len(row["sha256"]) != 64 or any(c not in "0123456789abcdef" for c in row["sha256"]):
            raise ValueError("Every input needs its SHA-256 hash")
        local_file(path.parent, row.get("path"))
    for row in cases:
        if not isinstance(row.get("id"), str) or not row["id"] or len(row["id"]) > 128 or row["id"] in ids:
            raise ValueError("Every case needs a unique short id")
        if row["sha256"] in hashes:
            raise ValueError("Development and held-out inputs must be distinct frozen images")
        ids.add(row["id"])
        hashes.add(row["sha256"])
        if row.get("split") not in {"development", "held_out"}:
            raise ValueError("Every case needs development or held_out split")
        splits.add(row["split"])
        expected = row.get("expected")
        if not isinstance(expected, dict) or not expected or len(expected) > 32:
            raise ValueError("Every case needs 1–32 trusted measurement checks")
        for key, check in expected.items():
            if not isinstance(key, str) or not key or not isinstance(check, dict) or "value" not in check:
                raise ValueError("Expected checks need a field, value and optional numeric tolerance")
            value, tolerance = check["value"], check.get("tolerance", 0)
            if not isinstance(value, (str, int, float, bool)) or not isinstance(tolerance, (int, float)) or isinstance(tolerance, bool) or not math.isfinite(tolerance) or tolerance < 0:
                raise ValueError("Expected values/tolerances must be finite scalars")
            if isinstance(value, float) and not math.isfinite(value):
                raise ValueError("Expected measurements must be finite")
            if tolerance and (not isinstance(value, (int, float)) or isinstance(value, bool)):
                raise ValueError("Tolerance only applies to numerical checks")
    if splits != {"development", "held_out"}:
        raise ValueError("Include both development and held-out cases")
    macro_path = local_file(path.parent, procedure["path"])
    if macro_path.stat().st_size > 65536:
        raise ValueError("Validation macro exceeds 65,536 bytes")
    macro = macro_path.read_text(encoding="utf-8-sig")
    result = FrozenValidation(path, spec, file_hash(path), macro)
    result.verify_inputs()
    return result

def _field(result, key):
    value = result
    for part in key.split("."):
        if isinstance(value, list) and part.isdigit() and int(part) < len(value):
            value = value[int(part)]
            continue
        if not isinstance(value, dict) or part not in value:
            raise ValueError("Measurement field missing: " + key)
        value = value[part]
    return value

def run_validation(frozen: FrozenValidation, runner, *, abort=None, fingerprint=""):
    frozen.verify_inputs()
    receipt = {"schema": 1, "manifest_sha256": frozen.manifest_hash,
        "manifest_file": frozen.path.name,
        "procedure_sha256": frozen.manifest["procedure"]["sha256"],
        "reviewer": frozen.manifest["reviewer"], "instrument_fingerprint": fingerprint,
        "timestamp": time.time(), "passed": False, "cases": [],
        "disclosure": "Checks compare supplied expectations only. Human review is required; no automatic scientific promotion."}
    for case in frozen.manifest["cases"]:
        if abort and abort.set_flag:
            receipt["error"] = "Validation interrupted"
            break
        try:
            actual = runner(local_file(frozen.path.parent, case["path"]), frozen.macro, case)
            checks = []
            for field, spec in case["expected"].items():
                value = _field(actual, field)
                expected = spec["value"]
                numeric = isinstance(expected, (int, float)) and not isinstance(expected, bool)
                if numeric:
                    passed = isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and abs(value - expected) <= spec.get("tolerance", 0)
                else:
                    passed = type(value) is type(expected) and value == expected
                recorded = value if isinstance(value, (str, int, float, bool)) or value is None else "<non-scalar measurement>"
                if isinstance(recorded, float) and not math.isfinite(recorded):
                    recorded = str(recorded)
                checks.append({"field": field, "actual": recorded, "expected": expected, "tolerance": spec.get("tolerance", 0), "passed": passed})
            row = {"id":case["id"], "split":case["split"], "input_sha256":case["sha256"], "checks":checks, "passed":all(c["passed"] for c in checks)}
        except Exception as exc:
            row = {"id":case["id"], "split":case["split"], "passed":False, "error":str(exc)}
        receipt["cases"].append(row)
    try:
        frozen.verify_inputs()
        receipt["passed"] = not (abort and abort.set_flag) and len(receipt["cases"]) == len(frozen.manifest["cases"]) and all(row["passed"] for row in receipt["cases"])
    except Exception as exc:
        receipt["error"] = str(exc)
    return receipt

def save_receipt(root: Path, receipt):
    folder = root / "AI_Exports" / "validation"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"validation_{time.time_ns()}.json"
    path.write_text(json.dumps(receipt, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return path

class FijiValidationRunner:
    def __init__(self, connection, abort=None):
        self.connection, self.abort = connection, abort
    def _macro(self, code):
        reply = self.connection.command({"command":"execute_macro_async", "code":code, "source":"console:validation"}, timeout=10)
        body = reply.get("result", {})
        ident = body.get("job_id") if isinstance(body, dict) else None
        if not reply.get("ok") or not isinstance(ident, str):
            raise ValueError("Validation macro was refused: " + str(body))
        cancel = lambda:self.connection.command({"command":"job_cancel", "job_id":ident}, timeout=3)
        if self.abort:
            self.abort.register(cancel)
        deadline = time.monotonic() + 120
        try:
            while True:
                if self.abort and self.abort.set_flag:
                    raise ValueError("Validation interrupted; cancellation sent for its own macro job")
                if time.monotonic() > deadline:
                    cancel()
                    raise ValueError("Validation macro exceeded 120 seconds; cancellation sent")
                status = self.connection.command({"command":"job_status", "job_id":ident}, timeout=3).get("result", {})
                if status.get("state") in {"completed", "failed", "cancelled", "timed_out"}:
                    result = status.get("result") or {}
                    if status["state"] != "completed" or status.get("error") or (isinstance(result, dict) and (result.get("success") is False or result.get("error"))):
                        raise ValueError("Validation macro failed: " + str(status.get("error") or result))
                    return result
                threading.Event().wait(.1)
        finally:
            if self.abort:
                self.abort.unregister(cancel)
    def __call__(self, path, macro, case):
        if self.abort and self.abort.set_flag:
            raise ValueError("Validation interrupted")
        opened = self.connection.open_image(str(path))
        if not opened.get("ok"):
            raise ValueError("Validation input could not be opened")
        info = self.connection.image_info()
        body = info.get("result", {})
        ident = body.get("image_id")
        if not isinstance(ident, str) or not ident or not isinstance(body.get("title"), str):
            raise ValueError("Opened validation image has no authoritative id")
        title = "ImageJAI Validation " + uuid.uuid4().hex
        # A private duplicate protects the frozen source pixels. User-authored
        # macros still require the explicit confirmation shown by the console.
        # image_id is an opaque revision identity, not ImageJ's numeric ID.
        # Verify it immediately before duplicating the exact active image.
        verified = self.connection.image_info(image_id=ident, image_revision=body["image_revision"])
        if not verified.get("ok"):
            raise ValueError("Validation input changed before duplication")
        source_title = body["title"].replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "\\r")
        self._macro(f'selectImage("{source_title}"); run("Duplicate...", "title=[{title}] duplicate");\n' + macro)
        csv_text = self.connection.results().get("result", "")
        if not isinstance(csv_text, str):
            raise ValueError("Fiji returned an unsupported results table")
        from agent.results_parser import parse_results
        rows = parse_results(csv_text)
        measured = {"count":len(rows), "rows":rows, "csv":csv_text}
        current = self.connection.image_info().get("result", {})
        return {"results": measured, "image": current}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["check", "run"])
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    frozen = load_manifest(args.manifest)
    if args.action == "check":
        print(json.dumps({"ok":True, "cases":len(frozen.manifest["cases"]), "manifest_sha256":frozen.manifest_hash}))
        return
    from .fiji import FijiConnection
    from .config import ConsoleConfig
    config = ConsoleConfig.load()
    connection = FijiConnection(config.host, config.port)
    receipt = run_validation(frozen, FijiValidationRunner(connection))
    print(save_receipt(frozen.path.parent, receipt))
    raise SystemExit(0 if receipt["passed"] else 1)

if __name__ == "__main__":
    main()
