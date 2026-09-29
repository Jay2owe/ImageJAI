"""Typed, local persistence for reviewed ImageJAI harness knowledge.

This module contains no model calls and never promotes or refines entries on
its own.  Callers must inject both persistence paths (and may inject a clock
and id factory for hermetic tests).
"""
from __future__ import annotations

import copy
import json
import os
import re
import tempfile
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Literal, Mapping, Optional, Sequence, Tuple, Union
from uuid import uuid4

HarnessKind = Literal[
    "fact", "preference", "constraint", "failure_fix", "procedure",
    "validation_rule", "environment_capability", "prompt_policy",
]
HarnessScope = Literal["session", "project", "instrument", "user", "shared"]
HarnessStatus = Literal[
    "candidate", "session_confirmed", "project_approved", "validated", "deprecated"
]

KINDS = frozenset(("fact", "preference", "constraint", "failure_fix", "procedure",
                   "validation_rule", "environment_capability", "prompt_policy"))
SCOPES = frozenset(("session", "project", "instrument", "user", "shared"))
STATUSES = frozenset(("candidate", "session_confirmed", "project_approved",
                      "validated", "deprecated"))
ACTIVE_STATUSES = frozenset(STATUSES - {"deprecated"})
PROMOTION_ORDER = ("candidate", "session_confirmed", "project_approved", "validated")
STATUS_STRENGTH = {name: i for i, name in enumerate(PROMOTION_ORDER)}
STATUS_STRENGTH["deprecated"] = -1
SCHEMA_VERSION = 1


class HarnessError(Exception):
    """Base harness exception."""


class HarnessCorruptionError(HarnessError):
    """The on-disk state is unsafe or does not match schema 1."""


class HarnessValidationError(HarnessError, ValueError):
    """An entry or API argument violates a bound or invariant."""


class HarnessConflictError(HarnessError):
    """An optimistic version check failed."""


class HarnessNotFoundError(HarnessError, KeyError):
    """An entry id was not found."""


@dataclass
class HarnessEntry:
    id: str
    kind: HarnessKind
    scope: HarnessScope
    status: HarnessStatus
    title: str
    content: str
    path: str = ""
    reference: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    evidence: List[str] = field(default_factory=list)
    applicability: Dict[str, Any] = field(default_factory=dict)
    conflicts: List[str] = field(default_factory=list)
    expires_at: Optional[str] = None
    source: str = ""
    version: int = 1
    created_at: str = ""
    updated_at: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return copy.deepcopy(asdict(self))

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "HarnessEntry":
        try:
            return cls(**copy.deepcopy(dict(value)))
        except (TypeError, ValueError) as exc:
            raise HarnessCorruptionError(f"invalid harness entry: {exc}") from exc


@dataclass
class RetrievalResult(Sequence[HarnessEntry]):
    """Ranked entries plus explicit conflict disclosures."""

    entries: List[HarnessEntry]
    conflicts: List[Dict[str, Any]]

    def __len__(self) -> int:
        return len(self.entries)

    def __getitem__(self, index):
        return self.entries[index]

    def __iter__(self) -> Iterator[HarnessEntry]:
        return iter(self.entries)


class HarnessStore:
    """A bounded schema-1 harness store with optimistic writes."""

    MAX_ENTRIES = 1000
    MAX_STATE_BYTES = 4 * 1024 * 1024
    MAX_LOG_BYTES = 16 * 1024 * 1024
    MAX_TITLE = 240
    MAX_CONTENT = 12_000
    MAX_PATH = 500
    MAX_SOURCE = 1_000
    MAX_ID = 128
    MAX_EVIDENCE_ITEMS = 32
    MAX_EVIDENCE_ITEM = 2_000
    MAX_CONFLICTS = 64
    MAX_MAPPING_BYTES = 64 * 1024
    MAX_DIGEST_CHARS = 8_000

    def __init__(
        self,
        state_path: Union[str, Path],
        refinements_path: Union[str, Path],
        *,
        clock: Optional[Callable[[], datetime]] = None,
        id_factory: Optional[Callable[[], str]] = None,
        max_entries: Optional[int] = None,
    ) -> None:
        if state_path is None or refinements_path is None:
            raise HarnessValidationError("state_path and refinements_path must be explicit")
        self.state_path = Path(state_path)
        self.refinements_path = Path(refinements_path)
        if self.state_path.resolve() == self.refinements_path.resolve():
            raise HarnessValidationError("state and refinement paths must differ")
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._id_factory = id_factory or (lambda: uuid4().hex)
        self.max_entries = self.MAX_ENTRIES if max_entries is None else max_entries
        if not isinstance(self.max_entries, int) or not 1 <= self.max_entries <= self.MAX_ENTRIES:
            raise HarnessValidationError(f"max_entries must be 1..{self.MAX_ENTRIES}")

    def _now(self) -> str:
        value = self._clock()
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _parse_time(value: str, *, field_name: str = "timestamp") -> datetime:
        if not isinstance(value, str) or not value:
            raise HarnessValidationError(f"{field_name} must be an ISO-8601 string")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise HarnessValidationError(f"invalid {field_name}") from exc
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)

    @staticmethod
    def _json_bytes(value: Any) -> bytes:
        try:
            return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise HarnessValidationError(f"value is not JSON serializable: {exc}") from exc

    def _validate_mapping(self, value: Any, name: str) -> None:
        if not isinstance(value, dict):
            raise HarnessValidationError(f"{name} must be a dict")
        if len(self._json_bytes(value)) > self.MAX_MAPPING_BYTES:
            raise HarnessValidationError(f"{name} is too large")

    def _validate_entry(self, entry: HarnessEntry, *, corruption: bool = False) -> None:
        def fail(message: str) -> None:
            exc = HarnessCorruptionError if corruption else HarnessValidationError
            raise exc(message)
        if not isinstance(entry.id, str) or not entry.id or len(entry.id) > self.MAX_ID:
            fail("invalid entry id")
        if entry.kind not in KINDS:
            fail("invalid entry kind")
        if entry.scope not in SCOPES:
            fail("invalid entry scope")
        if entry.status not in STATUSES:
            fail("invalid entry status")
        for name, value, maximum in (("title", entry.title, self.MAX_TITLE),
                                      ("content", entry.content, self.MAX_CONTENT),
                                      ("path", entry.path, self.MAX_PATH),
                                      ("source", entry.source, self.MAX_SOURCE)):
            if not isinstance(value, str) or len(value) > maximum or (name in ("title", "content") and not value.strip()):
                fail(f"invalid or oversized {name}")
        if not isinstance(entry.version, int) or isinstance(entry.version, bool) or entry.version < 1:
            fail("invalid version")
        for name in ("created_at", "updated_at"):
            try:
                self._parse_time(getattr(entry, name), field_name=name)
            except HarnessValidationError as exc:
                fail(str(exc))
        if entry.expires_at is not None:
            try:
                self._parse_time(entry.expires_at, field_name="expires_at")
            except HarnessValidationError as exc:
                fail(str(exc))
        try:
            self._validate_mapping(entry.reference, "reference")
            self._validate_mapping(entry.metadata, "metadata")
            self._validate_mapping(entry.applicability, "applicability")
        except HarnessValidationError as exc:
            fail(str(exc))
        if (not isinstance(entry.evidence, list) or len(entry.evidence) > self.MAX_EVIDENCE_ITEMS
                or any(not isinstance(x, str) or len(x) > self.MAX_EVIDENCE_ITEM for x in entry.evidence)):
            fail("invalid or oversized evidence")
        if (not isinstance(entry.conflicts, list) or len(entry.conflicts) > self.MAX_CONFLICTS
                or any(not isinstance(x, str) or not x or len(x) > self.MAX_ID for x in entry.conflicts)):
            fail("invalid conflicts")

    def _empty_state(self) -> Dict[str, Any]:
        return {"schema_version": SCHEMA_VERSION, "entries": {}}

    def _load_state(self) -> Dict[str, Any]:
        if not self.state_path.exists():
            return self._empty_state()
        try:
            size = self.state_path.stat().st_size
            if size > self.MAX_STATE_BYTES:
                raise HarnessCorruptionError("harness state exceeds size bound")
            raw = self.state_path.read_bytes()
            value = json.loads(raw.decode("utf-8"))
        except HarnessCorruptionError:
            raise
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise HarnessCorruptionError(f"cannot load harness state: {exc}") from exc
        if not isinstance(value, dict) or value.get("schema_version") != SCHEMA_VERSION:
            raise HarnessCorruptionError("unsupported or missing harness schema_version")
        entries = value.get("entries")
        if not isinstance(entries, dict) or len(entries) > self.max_entries:
            raise HarnessCorruptionError("invalid or excessive entries")
        for key, raw_entry in entries.items():
            if not isinstance(key, str) or not isinstance(raw_entry, dict):
                raise HarnessCorruptionError("invalid entry map")
            entry = HarnessEntry.from_dict(raw_entry)
            if entry.id != key:
                raise HarnessCorruptionError("entry id does not match its key")
            self._validate_entry(entry, corruption=True)
        return value

    def _write_state(self, state: Mapping[str, Any]) -> None:
        payload = self._json_bytes(state)
        if len(payload) > self.MAX_STATE_BYTES:
            raise HarnessValidationError("harness state exceeds size bound")
        parent = self.state_path.parent
        parent.mkdir(parents=True, exist_ok=True)
        old_mode = None
        try:
            old_mode = self.state_path.stat().st_mode
        except FileNotFoundError:
            pass
        fd, temp_name = tempfile.mkstemp(prefix=f".{self.state_path.name}.", suffix=".tmp", dir=str(parent))
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            if old_mode is not None:
                os.chmod(temp_name, old_mode)
            os.replace(temp_name, self.state_path)
        except Exception:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass
            raise

    def _event_payload(self, action: str, entry_id: str, before: Optional[HarnessEntry],
                       after: Optional[HarnessEntry], **details: Any) -> Dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "event_id": uuid4().hex,
            "timestamp": self._now(),
            "action": action,
            "entry_id": entry_id,
            "before": None if before is None else before.to_dict(),
            "after": None if after is None else after.to_dict(),
            "details": copy.deepcopy(details),
        }

    def _check_event_capacity(self, event: Mapping[str, Any]) -> bytes:
        try:
            payload = (json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise HarnessValidationError(f"event is not JSON serializable: {exc}") from exc
        current = self.refinements_path.stat().st_size if self.refinements_path.exists() else 0
        if current + len(payload) > self.MAX_LOG_BYTES:
            raise HarnessValidationError("refinement log exceeds size bound")
        return payload

    def _append_event_bytes(self, payload: bytes) -> None:
        self.refinements_path.parent.mkdir(parents=True, exist_ok=True)
        with self.refinements_path.open("ab") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())

    def _commit(self, state: Dict[str, Any], event: Dict[str, Any]) -> None:
        # Refuse to build on a damaged audit trail.  This is deliberately
        # fail-closed even though parsing the bounded log costs a little I/O.
        self._load_events()
        event_bytes = self._check_event_capacity(event)
        self._write_state(state)
        self._append_event_bytes(event_bytes)

    @staticmethod
    def _copy(entry: HarnessEntry) -> HarnessEntry:
        return HarnessEntry.from_dict(entry.to_dict())

    def propose(self, *, kind: HarnessKind, scope: HarnessScope, title: str, content: str,
                path: str = "", reference: Optional[Mapping[str, Any]] = None,
                metadata: Optional[Mapping[str, Any]] = None,
                evidence: Optional[Sequence[str]] = None,
                applicability: Optional[Mapping[str, Any]] = None,
                conflicts: Optional[Sequence[str]] = None, expires_at: Optional[Union[str, datetime]] = None,
                source: str = "") -> HarnessEntry:
        """Create a candidate.  There is deliberately no status argument."""
        state = self._load_state()
        if len(state["entries"]) >= self.max_entries:
            raise HarnessValidationError("maximum entry count reached")
        entry_id = str(self._id_factory())
        if entry_id in state["entries"]:
            raise HarnessConflictError("id factory produced an existing id")
        now = self._now()
        expiry = expires_at
        if isinstance(expiry, datetime):
            if expiry.tzinfo is None:
                expiry = expiry.replace(tzinfo=timezone.utc)
            expiry = expiry.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        entry = HarnessEntry(
            id=entry_id, kind=kind, scope=scope, status="candidate", title=title, content=content,
            path=path, reference=dict(reference or {}), metadata=dict(metadata or {}),
            evidence=list(evidence or []), applicability=dict(applicability or {}),
            conflicts=list(conflicts or []), expires_at=expiry, source=source,
            version=1, created_at=now, updated_at=now,
        )
        self._validate_entry(entry)
        state["entries"][entry.id] = entry.to_dict()
        self._commit(state, self._event_payload("propose", entry.id, None, entry))
        return self._copy(entry)

    def create(self, **fields: Any) -> HarnessEntry:
        """CRUD spelling for :meth:`propose`; all new entries remain candidates."""
        return self.propose(**fields)

    def get(self, entry_id: str) -> HarnessEntry:
        state = self._load_state()
        raw = state["entries"].get(entry_id)
        if raw is None:
            raise HarnessNotFoundError(entry_id)
        return self._copy(HarnessEntry.from_dict(raw))

    def list(self, *, statuses: Optional[Sequence[HarnessStatus]] = None,
             scopes: Optional[Sequence[HarnessScope]] = None,
             include_expired: bool = True) -> List[HarnessEntry]:
        state = self._load_state()
        status_set = None if statuses is None else self._checked_set(statuses, STATUSES, "status")
        scope_set = None if scopes is None else self._checked_set(scopes, SCOPES, "scope")
        now = self._clock_now()
        result = []
        for raw in state["entries"].values():
            entry = HarnessEntry.from_dict(raw)
            if status_set is not None and entry.status not in status_set:
                continue
            if scope_set is not None and entry.scope not in scope_set:
                continue
            if not include_expired and self._expired(entry, now):
                continue
            result.append(self._copy(entry))
        return sorted(result, key=lambda e: (e.created_at, e.id))

    @staticmethod
    def _checked_set(values: Sequence[str], allowed: frozenset, name: str) -> frozenset:
        result = frozenset(values)
        unknown = result - allowed
        if unknown:
            raise HarnessValidationError(f"unknown {name}: {sorted(unknown)}")
        return result

    def update(self, entry_id: str, *, expected_version: int, **changes: Any) -> HarnessEntry:
        blocked = {"id", "version", "created_at", "updated_at", "status", "scope"}
        unknown = set(changes) - set(HarnessEntry.__dataclass_fields__)
        if unknown:
            raise HarnessValidationError(f"unknown fields: {sorted(unknown)}")
        if blocked & set(changes):
            raise HarnessValidationError("identity, version, status and scope use dedicated APIs")
        state = self._load_state()
        before = self._entry_for_mutation(state, entry_id, expected_version)
        raw = before.to_dict()
        for name, value in changes.items():
            raw[name] = copy.deepcopy(value)
        raw["version"] = before.version + 1
        raw["updated_at"] = self._now()
        if before.kind == "procedure" and before.status == "validated" and any(
            name in changes and changes[name] != getattr(before, name) for name in ("title", "content", "applicability", "kind")
        ):
            raw["status"] = "candidate"
            raw["metadata"] = dict(raw.get("metadata") or {}, needs_validation=True)
        after = HarnessEntry.from_dict(raw)
        self._validate_entry(after)
        state["entries"][entry_id] = after.to_dict()
        self._commit(state, self._event_payload("update", entry_id, before, after,
                                                changed_fields=sorted(changes)))
        return self._copy(after)

    def _entry_for_mutation(self, state: Mapping[str, Any], entry_id: str,
                            expected_version: int) -> HarnessEntry:
        raw = state["entries"].get(entry_id)
        if raw is None:
            raise HarnessNotFoundError(entry_id)
        entry = HarnessEntry.from_dict(raw)
        if entry.version != expected_version:
            raise HarnessConflictError(
                f"stale version for {entry_id}: expected {expected_version}, current {entry.version}"
            )
        return entry

    def promote(self, entry_id: str, *, expected_version: int, reviewer: str,
                evidence: Union[str, Sequence[str]], target_status: Optional[HarnessStatus] = None) -> HarnessEntry:
        if not isinstance(reviewer, str) or not reviewer.strip():
            raise HarnessValidationError("promotion requires a reviewer")
        supplied = [evidence] if isinstance(evidence, str) else list(evidence)
        if not supplied or any(not isinstance(x, str) or not x.strip() for x in supplied):
            raise HarnessValidationError("promotion requires non-empty evidence")
        state = self._load_state()
        before = self._entry_for_mutation(state, entry_id, expected_version)
        if before.status not in PROMOTION_ORDER[:-1]:
            raise HarnessValidationError(f"cannot promote status {before.status}")
        expected_target = PROMOTION_ORDER[PROMOTION_ORDER.index(before.status) + 1]
        target = target_status or expected_target
        if target != expected_target:
            raise HarnessValidationError(f"promotion must advance exactly to {expected_target}")
        if target == "validated" and before.kind == "procedure":
            from .memory_review import require_current_validation
            try:
                require_current_validation(before)
            except (ValueError, OSError) as exc:
                raise HarnessValidationError(str(exc)) from exc
        after = self._copy(before)
        after.status = target  # type: ignore[assignment]
        after.evidence = after.evidence + supplied
        after.metadata = copy.deepcopy(after.metadata)
        after.metadata["last_reviewer"] = reviewer
        after.version += 1
        after.updated_at = self._now()
        self._validate_entry(after)
        state["entries"][entry_id] = after.to_dict()
        self._commit(state, self._event_payload("promote", entry_id, before, after,
                                                reviewer=reviewer, evidence=supplied))
        return self._copy(after)

    def transition_scope(self, entry_id: str, new_scope: HarnessScope, *, expected_version: int,
                         reviewer: str, evidence: Union[str, Sequence[str]]) -> HarnessEntry:
        if new_scope not in SCOPES:
            raise HarnessValidationError("invalid scope")
        if not isinstance(reviewer, str) or not reviewer.strip():
            raise HarnessValidationError("scope transition requires a reviewer")
        supplied = [evidence] if isinstance(evidence, str) else list(evidence)
        if not supplied or any(not isinstance(x, str) or not x.strip() for x in supplied):
            raise HarnessValidationError("scope transition requires evidence")
        state = self._load_state()
        before = self._entry_for_mutation(state, entry_id, expected_version)
        if before.scope == new_scope:
            raise HarnessValidationError("scope is unchanged")
        after = self._copy(before)
        after.scope = new_scope  # type: ignore[assignment]
        after.evidence += supplied
        after.metadata = copy.deepcopy(after.metadata)
        after.metadata["last_scope_reviewer"] = reviewer
        after.version += 1
        after.updated_at = self._now()
        self._validate_entry(after)
        state["entries"][entry_id] = after.to_dict()
        self._commit(state, self._event_payload("scope_transition", entry_id, before, after,
                                                reviewer=reviewer, evidence=supplied,
                                                old_scope=before.scope, new_scope=new_scope))
        return self._copy(after)

    def deprecate(self, entry_id: str, *, expected_version: int, reviewer: str, reason: str) -> HarnessEntry:
        if not reviewer.strip() or not reason.strip():
            raise HarnessValidationError("deprecation requires reviewer and reason")
        state = self._load_state()
        before = self._entry_for_mutation(state, entry_id, expected_version)
        if before.status == "deprecated":
            raise HarnessValidationError("entry is already deprecated")
        after = self._copy(before)
        after.status = "deprecated"
        after.metadata = copy.deepcopy(after.metadata)
        after.metadata["deprecation"] = {"reviewer": reviewer, "reason": reason}
        after.version += 1
        after.updated_at = self._now()
        self._validate_entry(after)
        state["entries"][entry_id] = after.to_dict()
        self._commit(state, self._event_payload("deprecate", entry_id, before, after,
                                                reviewer=reviewer, reason=reason))
        return self._copy(after)

    def delete(self, entry_id: str, *, expected_version: int, reviewer: str, reason: str) -> HarnessEntry:
        """Deletion is intentionally a recorded deprecation, never data removal."""
        return self.deprecate(entry_id, expected_version=expected_version, reviewer=reviewer, reason=reason)

    def _load_events(self) -> List[Dict[str, Any]]:
        if not self.refinements_path.exists():
            return []
        if self.refinements_path.stat().st_size > self.MAX_LOG_BYTES:
            raise HarnessCorruptionError("refinement log exceeds size bound")
        events = []
        try:
            with self.refinements_path.open("r", encoding="utf-8") as handle:
                for number, line in enumerate(handle, 1):
                    if not line.strip():
                        continue
                    item = json.loads(line)
                    if not isinstance(item, dict) or item.get("schema_version") != SCHEMA_VERSION:
                        raise HarnessCorruptionError(f"invalid refinement event at line {number}")
                    events.append(item)
        except HarnessCorruptionError:
            raise
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise HarnessCorruptionError(f"cannot load refinement log: {exc}") from exc
        return events

    def rollback(self, entry_id: str, to_version: int, *, expected_version: int,
                 reviewer: str, reason: str) -> HarnessEntry:
        if not reviewer.strip() or not reason.strip():
            raise HarnessValidationError("rollback requires reviewer and reason")
        state = self._load_state()
        before = self._entry_for_mutation(state, entry_id, expected_version)
        snapshot = None
        for event in self._load_events():
            for side in ("after", "before"):
                raw = event.get(side)
                if isinstance(raw, dict) and raw.get("id") == entry_id and raw.get("version") == to_version:
                    snapshot = raw
        if snapshot is None:
            raise HarnessNotFoundError(f"no snapshot for {entry_id} version {to_version}")
        after = HarnessEntry.from_dict(snapshot)
        after.version = before.version + 1
        after.created_at = before.created_at
        after.updated_at = self._now()
        self._validate_entry(after)
        state["entries"][entry_id] = after.to_dict()
        self._commit(state, self._event_payload("rollback", entry_id, before, after,
                                                reviewer=reviewer, reason=reason,
                                                restored_snapshot_version=to_version))
        return self._copy(after)

    def _clock_now(self) -> datetime:
        value = self._clock()
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)

    def _expired(self, entry: HarnessEntry, now: datetime) -> bool:
        return entry.expires_at is not None and self._parse_time(entry.expires_at) <= now

    @staticmethod
    def _tokens(text: str) -> frozenset:
        return frozenset(re.findall(r"[a-z0-9_]+", text.lower()))

    @staticmethod
    def _applicable(entry: HarnessEntry, task: Optional[str], state: Mapping[str, Any]) -> Tuple[bool, int]:
        if "_instrument_reviewed" in state and (entry.scope == "instrument" or entry.applicability.get("instrument_fingerprint")):
            if not state["_instrument_reviewed"] or entry.applicability.get("instrument_fingerprint") != state.get("_instrument_fingerprint"):
                return False, 0
        rules = entry.applicability
        if not rules:
            return True, 0
        matched = 0
        if "task" in rules:
            expected = rules["task"]
            allowed = expected if isinstance(expected, list) else [expected]
            if task is None or task not in allowed:
                return False, 0
            matched += 1
        state_rules = rules.get("state", {})
        if state_rules and not isinstance(state_rules, dict):
            return False, 0
        if isinstance(state_rules, dict):
            for key, expected in state_rules.items():
                if key not in state or state[key] != expected:
                    return False, 0
                matched += 1
        for key, expected in rules.items():
            if key in ("task", "state"):
                continue
            if key == "instrument_fingerprint" and "_instrument_fingerprint" in state:
                if expected != state["_instrument_fingerprint"]:
                    return False, 0
                matched += 1
                continue
            if key not in state or state[key] != expected:
                return False, 0
            matched += 1
        return True, matched

    def retrieve(self, query: str = "", *, task: Optional[str] = None,
                 state: Optional[Mapping[str, Any]] = None,
                 allowed_statuses: Optional[Sequence[HarnessStatus]] = None,
                 scopes: Optional[Sequence[HarnessScope]] = None,
                 include_expired: bool = False, limit: int = 20) -> RetrievalResult:
        if not isinstance(limit, int) or isinstance(limit, bool) or not 0 <= limit <= 100:
            raise HarnessValidationError("limit must be 0..100")
        statuses = ACTIVE_STATUSES if allowed_statuses is None else self._checked_set(allowed_statuses, STATUSES, "status")
        scope_set = None if scopes is None else self._checked_set(scopes, SCOPES, "scope")
        state_map = dict(state or {})
        query_terms = self._tokens(query)
        now = self._clock_now()
        ranked = []
        all_entries = {e.id: e for e in self.list()}
        for entry in all_entries.values():
            if entry.status not in statuses or (scope_set is not None and entry.scope not in scope_set):
                continue
            if not include_expired and self._expired(entry, now):
                continue
            applies, app_score = self._applicable(entry, task, state_map)
            if not applies:
                continue
            terms = self._tokens(" ".join((entry.title, entry.content, entry.path, entry.source)))
            overlap = len(query_terms & terms)
            updated = self._parse_time(entry.updated_at).timestamp()
            score = (STATUS_STRENGTH[entry.status], app_score, overlap, updated)
            ranked.append((score, entry.id, entry))
        ranked.sort(key=lambda item: (tuple(-x for x in item[0]), item[1]))
        selected = [self._copy(item[2]) for item in ranked[:limit]]
        disclosures = []
        disclosed = set()
        for entry in selected:
            for other_id in entry.conflicts:
                pair = tuple(sorted((entry.id, other_id)))
                if pair in disclosed:
                    continue
                disclosed.add(pair)
                other = all_entries.get(other_id)
                disclosures.append({
                    "entry_id": entry.id,
                    "conflicts_with": other_id,
                    "conflicting_entry": None if other is None else other.to_dict(),
                })
        disclosures.sort(key=lambda d: (d["entry_id"], d["conflicts_with"]))
        return RetrievalResult(selected, disclosures)

    def search(self, query: str, *, limit: int = 20, **filters: Any) -> RetrievalResult:
        return self.retrieve(query, limit=limit, **filters)

    def digest(self, query: str = "", state: Optional[Mapping[str, Any]] = None,
               model_profile: Optional[Mapping[str, Any]] = None,
               privacy_sanitizer: Optional[Callable[[str], str]] = None) -> str:
        """Render a small model-aware digest.  Evidence is deliberately omitted."""
        profile = dict(model_profile or {})
        reliability = profile.get("reliability", "medium")
        context = int(profile.get("context_window", profile.get("context_chars", 32_000)))
        low = reliability == "low" or isinstance(reliability, (int, float)) and reliability < 0.6
        small = context <= 8_000
        default_count = 3 if low or small else 6
        default_chars = 2_000 if low or small else 5_000
        count = max(0, min(default_count, int(profile.get("max_entries", default_count)), 12))
        budget = max(1, min(default_chars, int(profile.get("max_digest_chars", default_chars)), self.MAX_DIGEST_CHARS))
        state_map = dict(state or {})
        task = state_map.pop("task", None)
        # Candidate observations are review material, never model guidance.
        # Only confirmed/approved knowledge may steer an analysis.
        result = self.retrieve(
            query, task=task, state=state_map, limit=count,
            allowed_statuses=("session_confirmed", "project_approved", "validated"),
        )
        sanitizer = privacy_sanitizer or (lambda text: text)

        def clean(value: Any, maximum: int) -> str:
            sanitized = sanitizer(str(value))
            if not isinstance(sanitized, str):
                raise HarnessValidationError("privacy sanitizer must return str")
            sanitized = sanitized.replace("\x00", "").replace("\r", " ").replace("\n", " ")
            return sanitized[:maximum]

        lines = ["[harness-digest] schema_version=1"]
        conflict_by_id: Dict[str, List[str]] = {}
        for disclosure in result.conflicts:
            conflict_by_id.setdefault(disclosure["entry_id"], []).append(disclosure["conflicts_with"])
        per_content = 180 if low or small else 420
        for entry in result:
            pieces = [
                f"- [{clean(entry.status, 40)}/{clean(entry.scope, 40)}]",
                f"id={clean(entry.id, self.MAX_ID)}", f"v={entry.version}",
                f"kind={clean(entry.kind, 40)}", f"title={clean(entry.title, 180)}",
                f"content={clean(entry.content, per_content)}",
            ]
            if entry.path:
                pieces.append(f"path={clean(entry.path, 120)}")
            if entry.source:
                pieces.append(f"source={clean(entry.source, 120)}")
            conflict_ids = conflict_by_id.get(entry.id, [])
            if conflict_ids:
                pieces.append("CONFLICTS_WITH=" + clean(",".join(conflict_ids), 300))
            line = " ".join(pieces)
            if sum(len(x) + 1 for x in lines) + len(line) > budget:
                break
            lines.append(line)
        if result.conflicts:
            marker = clean("Conflicts are disclosed above; entries were not merged.", 100)
            if sum(len(x) + 1 for x in lines) + len(marker) <= budget:
                lines.append(marker)
        rendered = "\n".join(lines)
        return rendered[:budget]


# Short alias for callers that prefer ``Harness(...)``.
Harness = HarnessStore

__all__ = [
    "Harness", "HarnessStore", "HarnessEntry", "RetrievalResult",
    "HarnessError", "HarnessCorruptionError", "HarnessValidationError",
    "HarnessConflictError", "HarnessNotFoundError", "HarnessKind",
    "HarnessScope", "HarnessStatus", "SCHEMA_VERSION",
]
