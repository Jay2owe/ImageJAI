"""Pre-flight safety checks and audit logging for macros and scripts.

Every macro the agent proposes is passed through check_macro()
before it reaches Fiji. The check scans for save-family calls and
rejects any path that does not live inside the current
AI_Exports/ folder. Every macro and script that actually runs is
appended to audit.log; failures are appended to friction.log for
development review.

None of the logging functions raise: a broken filesystem or
missing export folder must never take down the chat loop.
"""

from __future__ import annotations

import collections
import datetime
import json
import os
import re
import threading

from . import active_image


_log_lock = threading.Lock()
_SESSION_ID = "gemma-{}-p{}".format(
    datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S"),
    os.getpid(),
)
_RECENT_EXECUTIONS = collections.deque(maxlen=4)


# Patterns that extract the path literal from a save-family call.
# Each pattern captures the PATH string in group 1. The first arg
# (format / data) may be a literal or a variable; we only require
# the final path argument to be a double-quoted string literal.
_SAVE_EXTRACTORS = [
    ("saveAs", re.compile(r'\bsaveAs\s*\([^,()]*,\s*"([^"]*)"\s*\)')),
    ("save", re.compile(r'(?<![\w.])save\s*\(\s*"([^"]*)"\s*\)')),
    ("File.saveString", re.compile(r'\bFile\.saveString\s*\([^,()]*,\s*"([^"]*)"\s*\)')),
    ("File.append", re.compile(r'\bFile\.append\s*\([^,()]*,\s*"([^"]*)"\s*\)')),
    ("IJ.saveAs", re.compile(r'\bIJ\.saveAs\s*\([^,()]*,\s*"([^"]*)"\s*\)')),
    (
        'run(..., "save=...")',
        re.compile(
            r'\brun\s*\(\s*"[^"]+"\s*,\s*"[^"]*\bsave\s*=\s*(?:\[([^\]]+)\]|([^"\s]+))[^"]*"\s*\)',
            re.IGNORECASE,
        ),
    ),
]


# Detectors used to notice the *presence* of a save call even when
# the extractor regex above cannot pull out a literal path (e.g. the
# path is a variable). Presence + unextractable path = reject.
_SAVE_DETECTORS = [
    ("saveAs", re.compile(r'\bsaveAs\s*\(')),
    ("save", re.compile(r'(?<![\w.])save\s*\(')),
    ("File.saveString", re.compile(r'\bFile\.saveString\s*\(')),
    ("File.append", re.compile(r'\bFile\.append\s*\(')),
    ("IJ.saveAs", re.compile(r'\bIJ\.saveAs\s*\(')),
    (
        'run(..., "save=...")',
        re.compile(r'\brun\s*\(\s*"[^"]+"\s*,\s*"[^"]*\bsave\s*=', re.IGNORECASE),
    ),
]


def check_macro(code: str) -> str | None:
    """Scan a macro for unsafe save paths and reject them before Fiji runs it.

    Returns None when the macro is safe to send (no save calls, or
    every save path resolves under the current AI_Exports/). Returns
    a human-readable error string when the macro must be blocked —
    callers should surface this to the agent as the tool result.
    """
    if not isinstance(code, str) or not code:
        return None

    detected = _detect_save_sites(code)
    if not detected:
        return None

    folder = active_image.current_export_folder()
    if folder is None:
        if active_image.current_active_image() is None:
            return (
                "No image is open (the active-image cache is empty). Open an image "
                "first, or launch the agent with --export-dir PATH to set a "
                "fallback folder for macro outputs."
            )
        return (
            "The active image is a sample (no file on disk, e.g. 'Blobs'). "
            "Sample images cannot run macros that write files into AI_Exports/ "
            "because there is no folder to sit next to. Save the image first, "
            "or launch the agent with --export-dir PATH. The image IS open."
        )
    folder_abs = os.path.abspath(folder)

    extracted = _extract_save_paths(code)
    # If every detected site was extracted we check each path.
    # If at least one detector fired but nothing was extractable,
    # reject the whole macro rather than let it through unchecked.
    if len(extracted) < len(detected):
        return (
            "A save-family call was found but its path argument is not a "
            "string literal. Refusing to run — pass the output path as a "
            "plain double-quoted string under {}.".format(folder_abs)
        )

    for call_name, raw_path in extracted:
        resolved = os.path.abspath(raw_path)
        if not _is_within(resolved, folder_abs):
            return (
                "{} path '{}' is outside the current AI_Exports folder "
                "({}). Move the output into that folder and try again."
                .format(call_name, resolved, folder_abs)
            )

    return None


def session_id() -> str:
    """Return the current agent session ID used to scope audit records."""
    return _SESSION_ID


def note_execution(success: bool) -> None:
    """Record whether the latest macro/script path ended cleanly."""
    _RECENT_EXECUTIONS.append(bool(success))


def recipe_offer_allowed() -> bool:
    """True when the recent execution window contains success and no failures."""
    recent = list(_RECENT_EXECUTIONS)
    return bool(recent) and any(recent) and not any(not item for item in recent)


def audit_log(source: str, code: str, success: bool, metadata: dict | None = None) -> None:
    """Append one JSON line recording a macro or script the agent ran.

    The record has a UTC ISO 8601 timestamp, source ("macro" or
    "script"), the full code, and whether Fiji reported success.
    Written to audit.log inside the current AI_Exports/ folder. If
    the folder is not available the write is silently skipped —
    auditing is best-effort.
    """
    folder = active_image.current_export_folder()
    if folder is None:
        return
    record = {
        "ts": _utc_now_iso(),
        "session_id": _SESSION_ID,
        "source": source,
        "code": code,
        "success": bool(success),
    }
    if isinstance(metadata, dict):
        for key, value in metadata.items():
            if key not in record:
                record[key] = value
    _append_jsonl(os.path.join(folder, "audit.log"), record)


def friction_log(event: dict) -> None:
    """Record a macro failure or stuck-loop event for later review.

    Controlled by the ENABLED attribute on this function — __main__
    flips it off when --no-friction-log is passed. The event dict
    is stored verbatim alongside a UTC timestamp. Contents must be
    plain text diagnostics: no biology data, no image pixels.
    """
    if not getattr(friction_log, "ENABLED", True):
        return
    if not isinstance(event, dict):
        return
    folder = active_image.current_export_folder()
    if folder is None:
        return
    record = dict(event)
    record.setdefault("ts", _utc_now_iso())
    record.setdefault("session_id", _SESSION_ID)
    _append_jsonl(os.path.join(folder, "friction.log"), record)


# Module-level flag — function attribute form matches the spec
# in docs/ollama/plan.md Phase 1d ("safety.friction_log.ENABLED = False").
friction_log.ENABLED = True  # type: ignore[attr-defined]


# ---- helpers ----------------------------------------------------------


def _detect_save_sites(code: str) -> list[str]:
    """Return the call names whose detector fires in the macro.

    Duplicates are preserved so we can compare detector count
    against extractor count.
    """
    hits: list[str] = []
    for name, detector in _SAVE_DETECTORS:
        for _ in detector.finditer(code):
            hits.append(name)
    return hits


def _extract_save_paths(code: str) -> list[tuple[str, str]]:
    """Pull (call_name, path_literal) pairs from every extractable site."""
    hits: list[tuple[str, str]] = []
    for name, extractor in _SAVE_EXTRACTORS:
        for match in extractor.finditer(code):
            path = match.group(1)
            if path is None and match.lastindex and match.lastindex >= 2:
                path = match.group(2)
            if path is not None:
                hits.append((name, path))
    return hits


def _is_within(path: str, folder: str) -> bool:
    """Return True if path sits inside folder (after absolute-path resolution).

    Uses os.path.commonpath to avoid the prefix-match trap where
    '/tmp/export2/x' would otherwise look like it starts with
    '/tmp/export'.
    """
    try:
        common = os.path.commonpath([path, folder])
    except ValueError:
        # Different drives on Windows — definitely not within.
        return False
    return os.path.normcase(common) == os.path.normcase(folder)


def _utc_now_iso() -> str:
    """Return the current UTC time as an ISO 8601 string with a Z suffix."""
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _append_jsonl(path: str, record: dict) -> None:
    """Append one JSON line to a log file, swallowing filesystem errors."""
    try:
        payload = json.dumps(record, ensure_ascii=False)
    except (TypeError, ValueError):
        payload = json.dumps({"ts": _utc_now_iso(), "note": "unserialisable record"})
    line = payload + "\n"
    try:
        with _log_lock:
            with open(path, "a", encoding="utf-8") as fh:
                fh.write(line)
    except OSError:
        return
