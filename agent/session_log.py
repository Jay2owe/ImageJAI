#!/usr/bin/env python
"""
Session logging with macro replay export.

Wraps ij.py's send function to automatically log every command and response
with timestamps. Can export the session as a replayable ImageJ macro (.ijm).

Usage:
    from session_log import SessionLogger
    log = SessionLogger()
    log.send({"command": "execute_macro", "code": "run('Blobs');"})
    log.export_macro(".tmp/replay.ijm")
    log.save()  # saves JSON log to .tmp/session_YYYYMMDD_HHMMSS.json
"""

import copy
import json
import os
import secrets
import sys
import tempfile
import threading
import time
from datetime import datetime

# Add parent dir so we can import ij
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ij import imagej_command


AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
TMP_DIR = os.path.join(AGENT_DIR, ".tmp")
_ATOMIC_PATH_LOCKS = {}


def _response_succeeded(response, error=None):
    """Return true only for an explicitly successful server exchange."""
    if error is not None or not isinstance(response, dict) or response.get("ok") is not True:
        return False
    result = response.get("result")
    if isinstance(result, dict) and result.get("success") is False:
        return False
    return True


def _atomic_write(path, text):
    """Write text with same-directory replace so a failed write keeps the old file."""
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    normalized = os.path.normcase(os.path.abspath(path))
    lock = _ATOMIC_PATH_LOCKS.setdefault(normalized, threading.Lock())
    with lock:
        temp_path = None
        try:
            with tempfile.NamedTemporaryFile(
                    mode="w", encoding="utf-8", dir=directory,
                    prefix=os.path.basename(path) + ".", suffix=".tmp",
                    delete=False) as handle:
                temp_path = handle.name
                handle.write(text)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, path)
            temp_path = None
        finally:
            if temp_path:
                try:
                    os.unlink(temp_path)
                except OSError:
                    pass


class SessionLogger(object):
    """Logs all ImageJ TCP commands and responses with timestamps."""

    def __init__(self):
        self.entries = []
        self._lock = threading.RLock()
        self.start_time = datetime.now()
        self.session_id = "%s-%s" % (
            self.start_time.strftime("%Y%m%d_%H%M%S_%f"),
            secrets.token_hex(16),
        )
        os.makedirs(TMP_DIR, exist_ok=True)

    def send(self, cmd):
        """Send a command to ImageJ and log the exchange.

        Args:
            cmd: dict with at least a "command" key (same format as ij.py).

        Returns:
            The parsed JSON response from the TCP server.
        """
        timestamp = datetime.now().isoformat()
        t0 = time.time()

        try:
            response = imagej_command(cmd)
            elapsed = time.time() - t0
            entry = {
                "timestamp": timestamp,
                "elapsed_seconds": round(elapsed, 3),
                "command": cmd,
                "response": response,
                "error": None,
            }
        except Exception as e:
            elapsed = time.time() - t0
            response = None
            entry = {
                "timestamp": timestamp,
                "elapsed_seconds": round(elapsed, 3),
                "command": cmd,
                "response": None,
                "error": str(e),
            }

        entry["entry_id"] = secrets.token_hex(16)
        entry["success"] = _response_succeeded(entry.get("response"), entry.get("error"))
        entry["command"] = copy.deepcopy(entry["command"])
        with self._lock:
            self.entries.append(entry)
        return response

    def save(self, path=None):
        """Save the full session log as JSON.

        Args:
            path: output file path. Defaults to .tmp/session_YYYYMMDD_HHMMSS.json
        """
        if path is None:
            path = os.path.join(TMP_DIR, "session_%s.json" % self.session_id)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)

        with self._lock:
            entries = copy.deepcopy(self.entries)
        log_data = {
            "schema_version": 2,
            "session_id": self.session_id,
            "start_time": self.start_time.isoformat(),
            "end_time": datetime.now().isoformat(),
            "total_commands": len(entries),
            "entries": entries,
        }
        payload = json.dumps(log_data, indent=2, ensure_ascii=False) + "\n"
        _atomic_write(path, payload)

        return path

    def export_macro(self, path=None):
        """Extract all execute_macro calls into a replayable .ijm file.

        Args:
            path: output file path. Defaults to .tmp/replay_YYYYMMDD_HHMMSS.ijm

        Returns:
            The path to the written file, or None if no macros were found.
        """
        if path is None:
            path = os.path.join(TMP_DIR, "replay_%s.ijm" % self.session_id)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)

        lines = []
        lines.append("// ImageJAI session replay")
        lines.append("// Generated: %s" % datetime.now().isoformat())
        lines.append("// Session: %s" % self.session_id)
        lines.append("")

        macro_count = 0
        with self._lock:
            entries = copy.deepcopy(self.entries)
        for entry in entries:
            cmd = entry.get("command", {})
            succeeded = entry.get("success")
            if succeeded is None:
                succeeded = _response_succeeded(entry.get("response"), entry.get("error"))
            if cmd.get("command") == "execute_macro" and "code" in cmd and succeeded:
                macro_count += 1
                lines.append("// Step %d (%s)" % (macro_count, entry.get("timestamp", "?")))
                code = cmd["code"].strip()
                lines.append(code)
                # Ensure trailing newline after code block
                if not code.endswith("\n"):
                    lines.append("")

            elif cmd.get("command") == "run_pipeline" and "steps" in cmd and succeeded:
                macro_count += 1
                lines.append("// Pipeline (%s)" % entry.get("timestamp", "?"))
                for i, step in enumerate(cmd["steps"]):
                    lines.append("// Pipeline step %d: %s" % (i + 1, step.get("description", "")))
                    lines.append(step.get("code", "").strip())
                    lines.append("")

        if macro_count == 0:
            return None

        _atomic_write(path, "\n".join(lines) + "\n")

        return path

    def summary(self):
        """Return a brief text summary of the session."""
        total = len(self.entries)
        macros = sum(1 for e in self.entries if e["command"].get("command") == "execute_macro")
        errors = sum(1 for e in self.entries if e.get("error") is not None)
        failed_macros = 0
        for e in self.entries:
            if e["command"].get("command") == "execute_macro":
                resp = e.get("response")
                if resp and resp.get("ok"):
                    result = resp.get("result", {})
                    if isinstance(result, dict) and not result.get("success", True):
                        failed_macros += 1

        total_time = sum(e.get("elapsed_seconds", 0) for e in self.entries)

        parts = [
            "Session %s" % self.session_id,
            "  Commands: %d (%d macros)" % (total, macros),
            "  Connection errors: %d" % errors,
            "  Failed macros: %d" % failed_macros,
            "  Total time: %.1fs" % total_time,
        ]
        return "\n".join(parts)

    def __len__(self):
        return len(self.entries)


if __name__ == "__main__":
    # Quick demo (won't work without TCP server running)
    print("SessionLogger demo")
    log = SessionLogger()

    # Simulate entries without a live server
    log.entries.append({
        "timestamp": datetime.now().isoformat(),
        "elapsed_seconds": 0.05,
        "command": {"command": "execute_macro", "code": 'run("Blobs (25K)");'},
        "response": {"ok": True, "result": {"success": True, "output": "", "newImages": ["Blobs"]}},
        "error": None,
    })
    log.entries.append({
        "timestamp": datetime.now().isoformat(),
        "elapsed_seconds": 0.12,
        "command": {"command": "execute_macro", "code": 'run("Gaussian Blur...", "sigma=2");'},
        "response": {"ok": True, "result": {"success": True, "output": ""}},
        "error": None,
    })

    print(log.summary())

    macro_path = log.export_macro()
    if macro_path:
        print("\nExported macro to: %s" % macro_path)
        with open(macro_path) as f:
            print(f.read())

    json_path = log.save()
    print("Saved log to: %s" % json_path)
