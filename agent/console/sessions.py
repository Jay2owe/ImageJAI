"""Shared, provider-neutral chat sessions stored one JSON file per session."""
from __future__ import annotations

import json
import os
import time
import threading
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .config import ConsoleConfig


def sessions_dir() -> Path:
    from . import config
    return config.CONFIG_DIR / "sessions"


@dataclass
class Session:
    id: str
    title: str = "New session"
    created: float = field(default_factory=time.time)
    updated: float = field(default_factory=time.time)
    provider: str | None = None
    model: str | None = None
    effort: str = "default"
    messages: list[dict[str, Any]] = field(default_factory=list)
    action_receipts: dict[str, Any] = field(default_factory=dict)
    external_session_id: str | None = None
    resume_vendor_latest: bool = False
    cost_notice_acknowledged: list[str] = field(default_factory=list)
    usage: list[dict[str, Any]] = field(default_factory=list)
    pending_turns: list[dict[str, Any]] = field(default_factory=list)
    background_jobs: list[dict[str, Any]] = field(default_factory=list)
    parent_session_id: str | None = None
    source_message_count: int | None = None

    def touch(self) -> None:
        self.updated = time.time()

    def derive_title(self, first_user_text: str) -> None:
        if self.title == "New session" and first_user_text:
            title = " ".join(first_user_text.split())
            self.title = (title[:48] + "...") if len(title) > 48 else title


class SessionStore:
    """Atomic CRUD over ``~/.imagej-ai/sessions/*.json``."""

    def __init__(self, config: ConsoleConfig) -> None:
        self.config = config
        self._save_lock = threading.RLock()
        self.sessions: dict[str, Session] = {}
        self._load_files()
        self._migrate_embedded_sessions()

    def _load_files(self) -> None:
        folder = sessions_dir()
        if not folder.is_dir():
            return
        for path in folder.glob("*.json"):
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                session = Session(**raw)
            except (OSError, ValueError, TypeError):
                continue
            self.sessions[session.id] = session

    def _migrate_embedded_sessions(self) -> None:
        changed = False
        for raw in list(self.config.sessions or []):
            try:
                session = Session(**raw)
            except TypeError:
                continue
            if session.id not in self.sessions:
                self.sessions[session.id] = session
                changed = True
        if self.config.sessions:
            self.config.sessions = []
            self.config.save()
        if changed:
            self.save()

    def list(self) -> list[Session]:
        return sorted(self.sessions.values(), key=lambda item: item.updated, reverse=True)

    def get(self, session_id: str) -> Session | None:
        return self.sessions.get(session_id)

    def create(self, provider: str | None = None, model: str | None = None,
               effort: str = "default") -> Session:
        session = Session(id=uuid.uuid4().hex[:12], provider=provider, model=model,
                          effort=effort)
        self.sessions[session.id] = session
        self.save_session(session)
        return session

    def delete(self, session_id: str) -> None:
        self.sessions.pop(session_id, None)
        (sessions_dir() / f"{session_id}.json").unlink(missing_ok=True)

    def fork(self, source: Session, message_count: int | None = None) -> Session:
        import copy
        from .branching import check_cut, copy_evidence
        count = len(source.messages) if message_count is None else message_count
        check_cut(source.messages, count)
        child = Session(id=uuid.uuid4().hex[:12], title=source.title + " (branch)",
                        provider=source.provider, model=source.model, effort=source.effort,
                        messages=copy.deepcopy(source.messages[:count]),
                        parent_session_id=source.id, source_message_count=count,
                        cost_notice_acknowledged=list(source.cost_notice_acknowledged))
        copy_evidence(sessions_dir(), source.id, child.id, source.messages, count)
        self.save_session(child)
        self.sessions[child.id] = child
        return child

    def save_session(self, session: Session) -> None:
        with self._save_lock:
            folder = sessions_dir()
            folder.mkdir(parents=True, exist_ok=True)
            target = folder / f"{session.id}.json"
            temp = target.with_suffix(".json.tmp")
            temp.write_text(json.dumps(asdict(session), indent=2), encoding="utf-8")
            os.replace(temp, target)

    def save(self) -> None:
        for session in self.sessions.values():
            self.save_session(session)
