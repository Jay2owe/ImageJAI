"""Lossless, provider-neutral evidence and artifact persistence.

This module deliberately depends only on the Python standard library.  A
journal stores small, JSON-safe facts.  Large or binary values belong in the
:class:`ArtifactStore` and are referenced by their metadata.
"""
from __future__ import annotations

import contextlib
import copy
import hashlib
import json
import math
import mimetypes
import os
import re
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence

EVENT_TYPES = frozenset({
    "user", "assistant", "thinking", "tool_call", "tool_result", "image_capture",
    "results_table", "approval", "decision", "checkpoint", "compaction",
})

_SAFE_SESSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
_SAFE_EXTENSION = re.compile(r"^[A-Za-z0-9]{1,16}$")
_LOCAL_LOCKS: dict[str, threading.RLock] = {}
_LOCAL_LOCKS_GUARD = threading.Lock()

DEFAULT_MAX_EVENT_BYTES = 1_048_576
DEFAULT_MAX_STRING_CHARS = 262_144
DEFAULT_MAX_ITEMS = 10_000
DEFAULT_MAX_DEPTH = 32
DEFAULT_MAX_ARTIFACT_BYTES = 256 * 1024 * 1024


class EvidenceError(ValueError):
    """Invalid or unsafe evidence input."""


class ArtifactTooLargeError(EvidenceError):
    """An artifact exceeds the configured size limit."""


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _safe_session_id(session_id: str) -> str:
    if not isinstance(session_id, str) or not _SAFE_SESSION.fullmatch(session_id):
        raise EvidenceError("session_id must be 1-64 safe ASCII letters, digits, '_' or '-'")
    return session_id


def _json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise EvidenceError(f"value is not strict JSON: {exc}") from exc


def _validate_json(value: Any, *, max_depth: int = DEFAULT_MAX_DEPTH,
                   max_items: int = DEFAULT_MAX_ITEMS,
                   max_string_chars: int = DEFAULT_MAX_STRING_CHARS,
                   _depth: int = 0) -> None:
    """Validate strict, bounded JSON without coercing types or values."""
    if _depth > max_depth:
        raise EvidenceError(f"JSON nesting exceeds {max_depth}")
    if value is None or isinstance(value, bool) or isinstance(value, int):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise EvidenceError("NaN and infinity are not valid evidence numbers")
        return
    if isinstance(value, str):
        if len(value) > max_string_chars:
            raise EvidenceError(f"JSON string exceeds {max_string_chars} characters")
        return
    if isinstance(value, (bytes, bytearray, memoryview)):
        raise EvidenceError("binary data must be saved in ArtifactStore, not embedded in the journal")
    if isinstance(value, list):
        if len(value) > max_items:
            raise EvidenceError(f"JSON list exceeds {max_items} items")
        for item in value:
            _validate_json(item, max_depth=max_depth, max_items=max_items,
                           max_string_chars=max_string_chars, _depth=_depth + 1)
        return
    if isinstance(value, dict):
        if len(value) > max_items:
            raise EvidenceError(f"JSON object exceeds {max_items} entries")
        for key, item in value.items():
            if not isinstance(key, str):
                raise EvidenceError("JSON object keys must be strings")
            if len(key) > max_string_chars:
                raise EvidenceError("JSON object key is too long")
            _validate_json(item, max_depth=max_depth, max_items=max_items,
                           max_string_chars=max_string_chars, _depth=_depth + 1)
        return
    raise EvidenceError(f"unsupported JSON type: {type(value).__name__}")


def _truncated_payload(payload: Any, encoded: bytes, limit: int) -> dict[str, Any]:
    """Return an explicit, machine-readable disclosure; never silently clip."""
    preview_size = min(4096, max(0, limit // 8))
    text = encoded.decode("utf-8", errors="replace")
    return {
        "_truncated": True,
        "reason": "event_size_limit",
        "original_bytes": len(encoded),
        "sha256": hashlib.sha256(encoded).hexdigest(),
        "utf8_head": text[:preview_size],
        "utf8_tail": text[-preview_size:] if preview_size else "",
        "disclosure": "Original payload exceeded the journal event bound; head and tail are previews.",
    }


def _thread_lock(path: Path) -> threading.RLock:
    key = str(path.absolute())
    with _LOCAL_LOCKS_GUARD:
        return _LOCAL_LOCKS.setdefault(key, threading.RLock())


@contextlib.contextmanager
def _file_lock(lock_path: Path) -> Iterator[None]:
    """Small cross-platform advisory lock, paired with an in-process lock."""
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    local = _thread_lock(lock_path)
    with local:
        fh = open(lock_path, "a+b")
        try:
            fh.seek(0)
            if fh.read(1) == b"":
                fh.seek(0)
                fh.write(b"0")
                fh.flush()
            fh.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(fh.fileno(), msvcrt.LK_LOCK, 1)
            else:
                import fcntl
                fcntl.flock(fh.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fh.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(fh.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
        finally:
            fh.close()


class ArtifactStore:
    """Atomic content storage below ``root/session_id/artifacts``."""

    def __init__(self, root: str | os.PathLike[str], session_id: str,
                 *, max_bytes: int = DEFAULT_MAX_ARTIFACT_BYTES) -> None:
        self.root = Path(root)
        self.session_id = _safe_session_id(session_id)
        if not isinstance(max_bytes, int) or max_bytes < 1:
            raise EvidenceError("max_bytes must be a positive integer")
        self.max_bytes = max_bytes
        self.session_dir = self.root / self.session_id
        self.artifacts_dir = self.session_dir / "artifacts"
        self.artifacts_dir.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def _extension(extension: str | None, media_type: str) -> str:
        ext = (extension or mimetypes.guess_extension(media_type) or ".bin").lstrip(".")
        if not _SAFE_EXTENSION.fullmatch(ext):
            raise EvidenceError("extension must contain only 1-16 ASCII letters or digits")
        return ext.lower()

    def save_bytes(self, data: bytes | bytearray | memoryview, *,
                   extension: str | None = None,
                   media_type: str = "application/octet-stream") -> dict[str, Any]:
        if not isinstance(data, (bytes, bytearray, memoryview)):
            raise EvidenceError("data must be bytes-like")
        raw = bytes(data)
        if len(raw) > self.max_bytes:
            raise ArtifactTooLargeError(f"artifact is {len(raw)} bytes; limit is {self.max_bytes}")
        if not isinstance(media_type, str) or not media_type or any(c in media_type for c in "\r\n"):
            raise EvidenceError("invalid media_type")
        ext = self._extension(extension, media_type)
        artifact_id = uuid.uuid4().hex
        filename = f"{artifact_id}.{ext}"
        target = self.artifacts_dir / filename
        temp = self.artifacts_dir / f".{filename}.{uuid.uuid4().hex}.tmp"
        try:
            with open(temp, "xb") as fh:
                fh.write(raw)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(temp, target)
        finally:
            temp.unlink(missing_ok=True)
        return {
            "artifact_id": artifact_id,
            "path": f"artifacts/{filename}",
            "sha256": hashlib.sha256(raw).hexdigest(),
            "media_type": media_type,
            "size": len(raw),
            "created_at": _utc_now(),
        }

    def save_text(self, text: str, *, extension: str = "txt",
                  media_type: str = "text/plain; charset=utf-8") -> dict[str, Any]:
        if not isinstance(text, str):
            raise EvidenceError("text must be str")
        return self.save_bytes(text.encode("utf-8"), extension=extension, media_type=media_type)

    def save_json(self, value: Any, *, extension: str = "json",
                  media_type: str = "application/json") -> dict[str, Any]:
        _validate_json(value)
        return self.save_bytes(_json_bytes(value), extension=extension, media_type=media_type)

    def path_for(self, ref: Mapping[str, Any]) -> Path:
        rel = ref.get("path") if isinstance(ref, Mapping) else None
        if not isinstance(rel, str):
            raise EvidenceError("artifact reference has no path")
        candidate = (self.session_dir / rel).resolve()
        base = self.artifacts_dir.resolve()
        try:
            candidate.relative_to(base)
        except ValueError as exc:
            raise EvidenceError("artifact path escapes the session artifact directory") from exc
        if candidate.parent != base or not re.fullmatch(r"[0-9a-f]{32}\.[a-z0-9]{1,16}", candidate.name):
            raise EvidenceError("artifact path is not a generated artifact path")
        return candidate

    def verify(self, ref: Mapping[str, Any]) -> dict[str, Any]:
        """Verify size and SHA-256.  Returns a diagnostic dictionary."""
        try:
            path = self.path_for(ref)
            raw = path.read_bytes()
        except (OSError, EvidenceError) as exc:
            return {"ok": False, "error": str(exc)}
        actual = hashlib.sha256(raw).hexdigest()
        expected = ref.get("sha256")
        expected_size = ref.get("size")
        return {
            "ok": actual == expected and len(raw) == expected_size,
            "artifact_id": ref.get("artifact_id"),
            "sha256": actual,
            "size": len(raw),
            "expected_sha256": expected,
            "expected_size": expected_size,
        }

    verify_hash = verify
    verify_artifact = verify


class EvidenceJournal:
    """Bounded, append-only JSONL evidence for one safe session id."""

    def __init__(self, root: str | os.PathLike[str], session_id: str, *,
                 max_event_bytes: int = DEFAULT_MAX_EVENT_BYTES,
                 max_string_chars: int = DEFAULT_MAX_STRING_CHARS,
                 max_items: int = DEFAULT_MAX_ITEMS,
                 max_depth: int = DEFAULT_MAX_DEPTH) -> None:
        self.root = Path(root)
        self.session_id = _safe_session_id(session_id)
        self.session_dir = self.root / self.session_id
        self.session_dir.mkdir(parents=True, exist_ok=True)
        self.path = self.session_dir / "evidence.jsonl"
        self.lock_path = self.session_dir / ".evidence.lock"
        for name, value in (("max_event_bytes", max_event_bytes), ("max_string_chars", max_string_chars),
                            ("max_items", max_items), ("max_depth", max_depth)):
            if not isinstance(value, int) or value < 1:
                raise EvidenceError(f"{name} must be a positive integer")
        self.max_event_bytes = max_event_bytes
        self.max_string_chars = max_string_chars
        self.max_items = max_items
        self.max_depth = max_depth
        self.last_read_report: dict[str, Any] = {"malformed_lines": [], "skipped": 0}

    def _next_sequence_locked(self) -> int:
        highest = 0
        if not self.path.exists():
            return 1
        with open(self.path, "rb") as fh:
            for raw in fh:
                try:
                    row = json.loads(raw)
                    sequence = row.get("sequence")
                    if isinstance(sequence, int) and not isinstance(sequence, bool):
                        highest = max(highest, sequence)
                except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
                    continue
        return highest + 1

    def append(self, event_type: str, payload: Any,
               artifact_refs: Sequence[Mapping[str, Any]] | None = None) -> dict[str, Any]:
        if event_type not in EVENT_TYPES:
            raise EvidenceError(f"unsupported event type: {event_type!r}")
        _validate_json(payload, max_depth=self.max_depth, max_items=self.max_items,
                       max_string_chars=self.max_string_chars)
        refs = copy.deepcopy(list(artifact_refs or []))
        _validate_json(refs, max_depth=self.max_depth, max_items=self.max_items,
                       max_string_chars=self.max_string_chars)
        payload_copy = copy.deepcopy(payload)
        with _file_lock(self.lock_path):
            sequence = self._next_sequence_locked()
            # A crash can leave a partial final record.  Keep it as reportable
            # evidence, but terminate that line before adding the next record.
            if self.path.exists() and self.path.stat().st_size:
                with open(self.path, "rb+") as tail:
                    tail.seek(-1, os.SEEK_END)
                    if tail.read(1) != b"\n":
                        tail.seek(0, os.SEEK_END)
                        tail.write(b"\n")
                        tail.flush()
                        os.fsync(tail.fileno())
            event = {
                "sequence": sequence,
                "timestamp": _utc_now(),
                "type": event_type,
                "payload": payload_copy,
            }
            if refs:
                event["artifact_refs"] = refs
            encoded = _json_bytes(event)
            if len(encoded) + 1 > self.max_event_bytes:
                event["payload"] = _truncated_payload(payload_copy, _json_bytes(payload_copy), self.max_event_bytes)
                encoded = _json_bytes(event)
                if len(encoded) + 1 > self.max_event_bytes:
                    # The configured bound may be extremely small.  Disclose with
                    # the irreducible fields, or reject if even those cannot fit.
                    event["payload"] = {
                        "_truncated": True,
                        "reason": "event_size_limit",
                        "original_bytes": len(_json_bytes(payload_copy)),
                        "sha256": hashlib.sha256(_json_bytes(payload_copy)).hexdigest(),
                    }
                    encoded = _json_bytes(event)
                if len(encoded) + 1 > self.max_event_bytes:
                    raise EvidenceError("max_event_bytes is too small for a truncation disclosure")
            with open(self.path, "ab", buffering=0) as fh:
                fh.write(encoded + b"\n")
                os.fsync(fh.fileno())
        return copy.deepcopy(event)

    def read_events(self, *, event_types: Iterable[str] | None = None,
                    since_sequence: int | None = None,
                    limit: int | None = None) -> list[dict[str, Any]]:
        wanted = set(event_types) if event_types is not None else None
        if wanted is not None and not wanted <= EVENT_TYPES:
            raise EvidenceError("query contains an unsupported event type")
        if limit is not None and (not isinstance(limit, int) or isinstance(limit, bool) or limit < 0):
            raise EvidenceError("limit must be a non-negative integer")
        events: list[dict[str, Any]] = []
        malformed: list[dict[str, Any]] = []
        if self.path.exists():
            with _file_lock(self.lock_path):
                with open(self.path, "rb") as fh:
                    for line_number, raw in enumerate(fh, 1):
                        try:
                            row = json.loads(raw)
                            if (not isinstance(row, dict)
                                    or not isinstance(row.get("sequence"), int)
                                    or isinstance(row.get("sequence"), bool)):
                                raise ValueError("not an evidence event")
                        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
                            malformed.append({"line": line_number, "error": str(exc), "bytes": len(raw)})
                            continue
                        if wanted is not None and row.get("type") not in wanted:
                            continue
                        if since_sequence is not None and row["sequence"] <= since_sequence:
                            continue
                        events.append(row)
        if limit is not None:
            events = events[:limit]
        self.last_read_report = {"malformed_lines": malformed, "skipped": len(malformed)}
        return events

    read = read_events

    def read_with_report(self, **query: Any) -> dict[str, Any]:
        events = self.read_events(**query)
        return {"events": events, "report": copy.deepcopy(self.last_read_report)}

    def query(self, event_type: str | None = None, *,
              since_sequence: int | None = None,
              limit: int | None = None) -> list[dict[str, Any]]:
        return self.read_events(event_types=None if event_type is None else [event_type],
                                since_sequence=since_sequence, limit=limit)

    def record_tool_pair(self, tool_name: str, arguments: Any, result: Any, *,
                         ok: bool = True, correlation_id: str | None = None,
                         call_artifacts: Sequence[Mapping[str, Any]] | None = None,
                         result_artifacts: Sequence[Mapping[str, Any]] | None = None
                         ) -> tuple[dict[str, Any], dict[str, Any]]:
        return record_tool_pair(self, tool_name, arguments, result, ok=ok,
                                correlation_id=correlation_id,
                                call_artifacts=call_artifacts,
                                result_artifacts=result_artifacts)


def record_tool_pair(journal: EvidenceJournal, tool_name: str, arguments: Any,
                     result: Any, *, ok: bool = True,
                     correlation_id: str | None = None,
                     call_artifacts: Sequence[Mapping[str, Any]] | None = None,
                     result_artifacts: Sequence[Mapping[str, Any]] | None = None
                     ) -> tuple[dict[str, Any], dict[str, Any]]:
    """Append a correlated ``tool_call`` / ``tool_result`` pair."""
    if not isinstance(tool_name, str) or not tool_name:
        raise EvidenceError("tool_name must be a non-empty string")
    cid = correlation_id or uuid.uuid4().hex
    if not isinstance(cid, str) or not cid or len(cid) > 128:
        raise EvidenceError("correlation_id must be a non-empty string of at most 128 characters")
    call = journal.append("tool_call", {
        "correlation_id": cid, "tool": tool_name, "arguments": arguments,
    }, call_artifacts)
    outcome = journal.append("tool_result", {
        "correlation_id": cid, "tool": tool_name, "ok": bool(ok), "result": result,
    }, result_artifacts)
    return call, outcome


def build_scientific_checkpoint(*, image_id: Any, image_revision: Any,
                                dataset_tokens: Sequence[Any], c: Any, z: Any, t: Any,
                                calibration: Mapping[str, Any], roi: Any,
                                macros: Sequence[Mapping[str, Any]],
                                result_artifacts: Sequence[Mapping[str, Any]],
                                approvals: Sequence[Any], pending_jobs: Sequence[Any],
                                decisions: Sequence[Any]) -> dict[str, Any]:
    """Build a lossless, bounded checkpoint for resuming scientific work.

    Macro entries are retained verbatim and must include ``macro``, ``params``
    and ``units`` so code, parameter values, and scientific units cannot be
    accidentally omitted during compaction.
    """
    if not isinstance(macros, (list, tuple)):
        raise EvidenceError("macros must be a sequence")
    for index, macro in enumerate(macros):
        if not isinstance(macro, Mapping):
            raise EvidenceError(f"macros[{index}] must be an object")
        missing = {"macro", "params", "units"} - set(macro)
        if missing:
            raise EvidenceError(f"macros[{index}] is missing: {', '.join(sorted(missing))}")
    checkpoint = {
        "image": {"id": image_id, "revision": image_revision},
        "dataset_tokens": list(dataset_tokens),
        "position": {"c": c, "z": z, "t": t},
        "calibration": dict(calibration),
        "roi": copy.deepcopy(roi),
        "macros": copy.deepcopy(list(macros)),
        "result_artifacts": copy.deepcopy(list(result_artifacts)),
        "approvals": copy.deepcopy(list(approvals)),
        "pending_jobs": copy.deepcopy(list(pending_jobs)),
        "decisions": copy.deepcopy(list(decisions)),
    }
    _validate_json(checkpoint)
    # Require hashes on result artifacts: a path alone is not immutable evidence.
    for index, ref in enumerate(checkpoint["result_artifacts"]):
        if not isinstance(ref, dict) or not isinstance(ref.get("sha256"), str):
            raise EvidenceError(f"result_artifacts[{index}] must contain sha256")
    return checkpoint


scientific_checkpoint = build_scientific_checkpoint

__all__ = [
    "EVENT_TYPES", "EvidenceError", "ArtifactTooLargeError", "ArtifactStore",
    "EvidenceJournal", "record_tool_pair", "build_scientific_checkpoint",
    "scientific_checkpoint",
]
