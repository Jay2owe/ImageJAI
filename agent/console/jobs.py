"""Owned async macro IDs, bounded polling and durable terminal notifications."""
from __future__ import annotations
import copy
import json
import threading
import time

TERMINAL = {"completed", "failed", "cancelled", "timed_out"}

class JobTracker:
    def __init__(self, rows=None):
        self._lock = threading.RLock()
        self._rows = {r["id"]: copy.deepcopy(r) for r in (rows or [])[:64]
                      if isinstance(r, dict) and isinstance(r.get("id"), str)}
    def observe(self, tool, arguments, ok, result, installation=""):
        if tool != "run_macro_async" or not ok:
            return
        try:
            parsed = json.loads(result)
        except (ValueError, TypeError):
            parsed = result.strip()
        if isinstance(parsed, dict):
            body = parsed.get("result", parsed)
            ident = body.get("job_id") if isinstance(body, dict) else None
        else:
            ident = parsed
        if not isinstance(ident, str) or not ident or len(ident) > 128 or ident.startswith("ERROR"):
            return
        with self._lock:
            if ident not in self._rows:
                if len(self._rows) >= 64:
                    done = next((key for key, value in self._rows.items() if value.get("notified")), None)
                    if done:
                        del self._rows[done]
                    else:
                        raise ValueError("64 active jobs are tracked; wait for a job to finish")
                self._rows[ident] = {"id": ident, "state": "running", "notified": False,
                                    "installation": installation, "submitted": time.time()}
    def snapshot(self):
        with self._lock:
            return copy.deepcopy(list(self._rows.values()))
    def poll(self, connection, installation=""):
        for row in self.snapshot():
            if row.get("state") in TERMINAL or row.get("installation", "") != installation:
                continue
            try:
                reply = connection.command({"command": "job_status", "job_id": row["id"]}, timeout=3, raise_on_error=False)
                body = reply.get("result", {})
                if not reply.get("ok") or body.get("state") not in TERMINAL | {"running", "queued"}:
                    continue
            except Exception:
                continue
            with self._lock:
                current = self._rows.get(row["id"])
                if current:
                    current["state"] = body["state"]
                    execution = body.get("result")
                    error = execution.get("error") if isinstance(execution, dict) else None
                    current["summary"] = str(body.get("error") or error or "")[:1000]
                    current["finished"] = time.time() if body["state"] in TERMINAL else None
    def take_notices(self):
        with self._lock:
            values = []
            for row in self._rows.values():
                if row.get("state") in TERMINAL and not row.get("notified"):
                    row["notified"] = True
                    values.append(f"Background macro job {row['id']} {row['state']}. " + row.get("summary", "") + " Read its results before continuing; do not submit the macro again.")
            return values

    def unclaim(self, notice):
        with self._lock:
            for row in self._rows.values():
                if notice.startswith("Background macro job " + row["id"] + " "):
                    row["notified"] = False
