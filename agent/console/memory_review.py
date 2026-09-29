"""Human review of conflicting/expired knowledge and frozen validation evidence."""
from __future__ import annotations
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import time
import math
from .validation import file_hash, load_manifest, strict_json

def content_hash(entry):
    return hashlib.sha256((entry.title + "\n" + entry.content).encode()).hexdigest()

def review_rows(stores, now=None, instrument_fingerprint=None):
    now = now or datetime.now(timezone.utc)
    result = []
    for label, store in stores:
        for entry in store.list(include_expired=True):
            if entry.status == "deprecated":
                continue
            reasons = []
            if entry.expires_at and datetime.fromisoformat(entry.expires_at.replace("Z", "+00:00")) <= now:
                reasons.append("Expired")
            if entry.conflicts:
                reasons.append("Conflicting knowledge")
            if entry.metadata.get("needs_validation"):
                reasons.append("Procedure changed; validation needs review")
            if entry.scope == "instrument" and not entry.applicability.get("instrument_fingerprint"):
                reasons.append("Instrument identity needs review")
            if entry.metadata.get("needs_revalidation"):
                reasons.append("Instrument changed")
            elif instrument_fingerprint is not None and entry.applicability.get("instrument_fingerprint") and entry.applicability["instrument_fingerprint"] != instrument_fingerprint:
                reasons.append("Instrument changed")
            if reasons:
                result.append({"label":entry.title + " — " + ", ".join(reasons), "id":entry.id,
                               "scope_label":label, "reasons":reasons, "version":entry.version})
    return result

def renew(store, entry, *, days: int, evidence: str, reviewer="console-user"):
    if type(days) is not int or not 1 <= days <= 365 or not evidence.strip():
        raise ValueError("Renewal requires 1–365 days and written review evidence")
    expiry = (datetime.now(timezone.utc) + timedelta(days=days)).isoformat().replace("+00:00", "Z")
    metadata = dict(entry.metadata, expiry_review={"reviewer":reviewer, "evidence":evidence, "timestamp":time.time()})
    return store.update(entry.id, expected_version=entry.version, expires_at=expiry,
                        metadata=metadata, evidence=entry.evidence + [evidence])

def attach_validation(store, entry, path: Path):
    path = path.resolve(strict=True)
    if path.stat().st_size > 1024 * 1024:
        raise ValueError("Validation receipt exceeds 1 MiB")
    receipt = strict_json(path.read_text(encoding="utf-8"))
    _check_receipt(receipt)
    # Receipts are generated in <image-folder>/AI_Exports/validation/.
    if path.parent.name != "validation" or path.parent.parent.name != "AI_Exports":
        raise ValueError("Use a receipt saved in the image folder's AI_Exports/validation directory")
    manifest_name = receipt.get("manifest_file")
    if not isinstance(manifest_name, str) or Path(manifest_name).name != manifest_name:
        raise ValueError("Receipt has no local manifest filename")
    manifest_path = path.parent.parent.parent / manifest_name
    frozen = load_manifest(manifest_path)
    if frozen.manifest_hash != receipt["manifest_sha256"] or frozen.manifest["procedure"]["sha256"] != receipt["procedure_sha256"]:
        raise ValueError("Receipt does not match the current frozen procedure")
    expected_ids = {row["id"]: row for row in frozen.manifest["cases"]}
    if set(expected_ids) != {row["id"] for row in receipt["cases"]}:
        raise ValueError("Receipt does not cover the declared cases")
    for row in receipt["cases"]:
        source = expected_ids[row["id"]]
        if row["input_sha256"] != source["sha256"] or row["split"] != source["split"]:
            raise ValueError("Receipt case lineage differs from the manifest")
        if {c["field"] for c in row["checks"]} != set(source["expected"]):
            raise ValueError("Receipt checks differ from the manifest")
        for check in row["checks"]:
            spec = source["expected"][check["field"]]
            if check["expected"] != spec["value"] or check.get("tolerance", 0) != spec.get("tolerance", 0):
                raise ValueError("Receipt expectations differ from the manifest")
    binding = {"path":str(path), "sha256":file_hash(path), "entry_sha256":content_hash(entry),
               "manifest_path":str(manifest_path), "manifest_sha256":frozen.manifest_hash}
    return store.update(entry.id, expected_version=entry.version,
                        metadata=dict(entry.metadata, validation_receipt=binding, needs_validation=False),
                        evidence=entry.evidence + ["Frozen procedure receipt " + binding["sha256"]])

def _check_receipt(receipt):
    if not isinstance(receipt, dict) or receipt.get("schema") != 1 or receipt.get("passed") is not True or receipt.get("error"):
        raise ValueError("A passing validation receipt is required")
    if not isinstance(receipt.get("timestamp"), (int, float)) or not 0 <= time.time() - receipt["timestamp"] <= 30*86400:
        raise ValueError("Validation evidence must be from the last 30 days")
    cases = receipt.get("cases", [])
    if len(cases) < 2 or {row.get("split") for row in cases} != {"development", "held_out"}:
        raise ValueError("Passing development and separate held-out checks are required")
    hashes = set()
    for row in cases:
        if row.get("passed") is not True or not row.get("checks") or not all(check.get("passed") is True for check in row["checks"]):
            raise ValueError("Every case and measurement check must have passed")
        for check in row["checks"]:
            actual, expected = check.get("actual"), check.get("expected")
            tolerance = check.get("tolerance", 0)
            numeric = isinstance(expected, (int, float)) and not isinstance(expected, bool)
            if numeric:
                if not isinstance(actual, (int, float)) or isinstance(actual, bool) or not math.isfinite(actual) or not isinstance(tolerance, (int, float)) or isinstance(tolerance, bool) or not math.isfinite(tolerance) or tolerance < 0 or abs(actual - expected) > tolerance:
                    raise ValueError("A receipt measurement does not satisfy its declared tolerance")
            elif type(actual) is not type(expected) or actual != expected:
                raise ValueError("A receipt value does not match its declared expectation")
        value = row.get("input_sha256")
        if not value or value in hashes:
            raise ValueError("Validation images must be distinct")
        hashes.add(value)

def require_current_validation(entry):
    binding = entry.metadata.get("validation_receipt", {})
    if not binding or binding.get("entry_sha256") != content_hash(entry):
        raise ValueError("Attach passing frozen procedure evidence with /memory validation <id> <receipt.json> before scientific promotion")
    path = Path(binding["path"])
    if file_hash(path) != binding["sha256"]:
        raise ValueError("Validation receipt changed after review")
    receipt = strict_json(path.read_text(encoding="utf-8"))
    _check_receipt(receipt)
    frozen = load_manifest(Path(binding["manifest_path"]))
    if frozen.manifest_hash != binding["manifest_sha256"]:
        raise ValueError("Frozen procedure evidence changed after review")
    instrument = entry.applicability.get("instrument_fingerprint")
    if instrument and receipt.get("instrument_fingerprint") != instrument:
        raise ValueError("Validation receipt belongs to a different instrument configuration")
