"""Bounded, persisted steering and follow-up input, owned by one chat."""
from __future__ import annotations
import copy
import threading
import uuid


class PromptQueue:
    def __init__(self, rows=None):
        self._lock = threading.RLock()
        self._rows = []
        for row in (rows or [])[:32]:
            if (isinstance(row, dict) and row.get("mode") in {"steer", "follow_up"}
                    and isinstance(row.get("text"), str) and 0 < len(row["text"]) <= 65536):
                self._rows.append({"id":str(row.get("id") or uuid.uuid4().hex), "text":row["text"],
                                   "mode":row["mode"], "source":row.get("source", "user")})

    def put(self, text: str, mode="steer", *, source="user"):
        text = text.strip()
        if mode not in {"steer", "follow_up"} or not text or len(text) > 65536:
            raise ValueError("Provide a message of 1–65,536 characters and a valid delivery mode")
        with self._lock:
            if len(self._rows) >= 32:
                raise ValueError("The message queue is full (32 messages); use /queue clear")
            row = {"id": uuid.uuid4().hex, "text": text, "mode": mode, "source": source}
            self._rows.append(row)
            return dict(row)

    def snapshot(self):
        with self._lock:
            return copy.deepcopy(self._rows)

    def take(self, mode, *, all_matches=False):
        with self._lock:
            found = []
            for row in list(self._rows):
                if row["mode"] == mode:
                    self._rows.remove(row)
                    found.append(dict(row))
                    if not all_matches:
                        break
            return found

    def clear(self):
        with self._lock:
            self._rows.clear()

    def remove(self, ident):
        with self._lock:
            self._rows = [row for row in self._rows if row["id"] != ident]


def inject_steering(agent, callbacks) -> list[str]:
    """Call only before a request or after *all* tool results were appended."""
    rows = callbacks.take_steering()
    for text in rows:
        agent.messages.append({"role": "user", "content": text})
        callbacks.on_user(text)
    return rows
