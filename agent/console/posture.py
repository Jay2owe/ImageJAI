"""Privacy posture state, per-folder sidecar, launch gating and lamp state.

Headless port of the Fiji plugin's Data Governance core so the standalone
console reaches the same decisions as the Swing panel without importing any
UI toolkit:

* ``imagejai/config/PrivacyPosture.java``     - the three postures
* ``imagejai/config/FolderPostureStore.java`` - the ``.imagejai-posture.json`` sidecar
* ``imagejai/engine/PostureController.java``  - downshift / upshift / override rules
* ``imagejai/engine/LaunchPolicy.java`` and ``imagejai/engine/AgentLauncher.java``
  - which provider may be launched under which posture
* ``imagejai/ui/PostureBadge.java`` / ``imagejai/ui/EgressIndicator.java``
  - the values the UI paints, returned here as plain data

The sidecar file name, its keys and its value spelling match the plugin
exactly, because both programs read the same folder: a console decision must
still be honoured when the biologist next opens the same images in Fiji.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Callable, Iterable

__all__ = [
    "Posture", "SIDECAR_NAME", "PostureRecord", "FolderPostureStore",
    "PostureStoreError", "PostureReadError", "PostureWriteError",
    "PostureEvent", "PostureController", "OverrideRecord", "FolderTokenMap",
    "LaunchDecision", "PostureViolation", "evaluate_launch",
    "filter_providers_for_posture", "is_local_provider",
    "is_local_provider_endpoint", "is_loopback_endpoint", "is_cloud_ollama_tag",
    "BadgeState", "badge_for", "footer_text", "EgressLamp", "LampState",
]


# --------------------------------------------------------------------------
# the three postures (PrivacyPosture.java:6)
# --------------------------------------------------------------------------

class Posture(Enum):
    """The three Data Governance levels, in increasing strictness.

    Declaration order *is* the strictness order: the Java code compares enum
    ordinals (``PrivacyPosture.java:33``), so anything that reorders these
    members silently changes who may talk to a cloud model.
    """

    STANDARD = ("Standard", "Cloud agents allowed, no pseudonymisation.")
    PSEUDONYMISED = (
        "Pseudonymised",
        "Cloud agents allowed; identifiers tokenised before send (UK GDPR Art. 4(5)).",
    )
    ON_PREMISES = (
        "On-premises",
        "Local agents only; pseudonymisation also applied.",
    )

    def __init__(self, label: str, description: str) -> None:
        self.label = label
        self.description = description

    @property
    def rank(self) -> int:
        """Strictness rank; the Python stand-in for the Java enum ordinal."""
        return list(Posture).index(self)

    def is_stricter_than(self, other: "Posture | None") -> bool:
        return other is not None and self.rank > other.rank

    @classmethod
    def default(cls) -> "Posture":
        """PrivacyPosture.defaultPosture() - Standard on first use."""
        return cls.STANDARD

    @classmethod
    def parse(cls, value: "str | Posture | None") -> "Posture":
        """Accept a label ("On-premises") or an enum name ("ON_PREMISES").

        The sidecar is written by Gson (enum *name*) while the audit CSV holds
        the *label*, so both spellings must round-trip (``AuditRow.java``
        ``parsePosture``).
        """
        if isinstance(value, Posture):
            return value
        text = (value or "").strip()
        if text:
            for posture in cls:
                if posture.label.lower() == text.lower():
                    return posture
                if posture.name.lower() == text.replace("-", "_").lower():
                    return posture
        raise ValueError(f"invalid privacy posture: {value}")


# Operator-facing strings, copied verbatim so the console and the plugin say
# the same thing about the same event.
SIDECAR_NAME = ".imagejai-posture.json"  # FolderPostureStore.java:24
SELECTION_PROMPT = "Choose a Privacy Posture for this folder."
FOLDER_PROMPT_GUIDANCE = (  # PostureBanner.java:107
    "Recommended workflow in Pseudonymised mode: open images via Fiji "
    "File > Open or the Browse Files dialog; do not paste filenames into "
    "the agent chat."
)
READ_WARNING = (  # PostureController.java:147
    "Could not read Privacy Posture for {folder}; using Pseudonymised for this session."
)
WRITE_WARNING = (  # PostureController.java:188
    f"Could not write {SIDECAR_NAME}; Privacy Posture is in-memory only for this session."
)
REVOKE_WARNING = f"Could not revoke {SIDECAR_NAME}."  # PostureController.java:218
DOWNSHIFT_NOTICE = "Posture downshifted: {from_label} to {to_label}"  # PostureBanner.java:137
OVERRIDE_PROMPT = "Reason for overriding the folder Privacy Posture:"  # PostureBanner.java:152
OVERRIDE_REASON_REQUIRED = "A reason is required for a logged override."  # PostureBanner.java:164
NEVER_AUTO_UPSHIFT = "Never auto-upshift between folders"  # PostureController.java:235

# Event ids published by PostureController.java; the TUI subscribes to these.
EVENT_REQUESTED = "data_governance.posture.requested"
EVENT_FOLDER_SAVED = "data_governance.posture.folder_saved"
EVENT_FOLDER_REVOKED = "data_governance.posture.folder_revoked"
EVENT_DOWNSHIFTED = "data_governance.posture.downshifted"
EVENT_UPSHIFT_REFUSED = "data_governance.posture.upshift_refused"
EVENT_OVERRIDE_LOGGED = "data_governance.posture.override_logged"
EVENT_LOADED = "data_governance.posture.loaded"
EVENT_DEFAULTED = "data_governance.posture.defaulted"
EVENT_SELECTION_NEEDED = "data_governance.posture.selection_needed"
EVENT_WARNING = "data_governance.posture.warning"


def _normalise_folder(folder: "str | os.PathLike[str]") -> Path:
    """Absolute + normalised, but never symlink-resolved (Java ``normalize()``)."""
    return Path(os.path.abspath(os.path.normpath(str(folder))))


def _utc_now_iso(clock: "Callable[[], datetime] | None" = None) -> str:
    now = clock() if clock else datetime.now(timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    return now.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


# --------------------------------------------------------------------------
# sidecar store (FolderPostureStore.java)
# --------------------------------------------------------------------------

class PostureStoreError(OSError):
    """Sidecar access failed; carries the exact warning the user must see."""


class PostureReadError(PostureStoreError):
    pass


class PostureWriteError(PostureStoreError):
    pass


@dataclass(frozen=True)
class PostureRecord:
    """One ``.imagejai-posture.json`` payload."""

    posture: Posture
    set_by: str = "user"
    set_at: str = ""
    notes: str = ""

    def to_json(self) -> dict:
        # Gson serialises the enum by name, so the plugin expects e.g.
        # "ON_PREMISES" and not the human label.
        return {
            "posture": self.posture.name,
            "set_by": self.set_by or "user",
            "set_at": self.set_at,
            "notes": self.notes or "",
        }

    @classmethod
    def from_json(cls, data: dict) -> "PostureRecord":
        if not isinstance(data, dict):
            raise ValueError("Privacy Posture file is not a JSON object")
        return cls(
            # An existing record with a missing posture is malformed; keep
            # the protective fallback separate from the first-use default.
            posture=Posture.parse(data.get("posture")) if data.get("posture") else Posture.PSEUDONYMISED,
            set_by=str(data.get("set_by") or "user"),
            set_at=str(data.get("set_at") or ""),
            notes=str(data.get("notes") or ""),
        )


class FolderPostureStore:
    """Reads, writes and clears the per-folder sidecar next to the images."""

    def __init__(self, clock: "Callable[[], datetime] | None" = None) -> None:
        self._clock = clock

    def sidecar_path(self, folder: "str | os.PathLike[str]") -> Path:
        return _normalise_folder(folder) / SIDECAR_NAME

    def read(self, folder: "str | os.PathLike[str]") -> "PostureRecord | None":
        """Return the stored record, or ``None`` when the folder has no decision.

        Raises :class:`PostureReadError` for an unreadable or corrupt file so
        the caller applies the protective fallback *and warns* instead of pretending the
        folder was never configured.
        """
        path = self.sidecar_path(folder)
        if not path.exists():
            return None
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise PostureReadError(f"Could not read {path}: {exc}") from exc
        if not text.strip():
            raise PostureReadError(f"Privacy Posture file is empty: {path}")
        try:
            return PostureRecord.from_json(json.loads(text))
        except (ValueError, TypeError) as exc:
            raise PostureReadError(f"Invalid Privacy Posture file: {path}") from exc

    def write(self, folder: "str | os.PathLike[str]", posture: Posture,
              set_by: str = "user", notes: str = "") -> PostureRecord:
        """Replace the sidecar atomically (tmp file + rename).

        A half-written posture file is worse than none at all: the plugin would
        fall back to a *weaker* default. FolderPostureStore.java:101 uses
        ATOMIC_MOVE with a plain-replace fallback; ``os.replace`` gives the same
        guarantee on Windows and POSIX.
        """
        directory = _normalise_folder(folder)
        record = PostureRecord(
            posture=posture or Posture.default(),
            set_by=set_by or "user",
            set_at=_utc_now_iso(self._clock),
            notes=notes or "",
        )
        target = directory / SIDECAR_NAME
        tmp = directory / (SIDECAR_NAME + ".tmp")
        try:
            directory.mkdir(parents=True, exist_ok=True)
            tmp.write_text(json.dumps(record.to_json(), indent=2), encoding="utf-8")
            os.replace(tmp, target)
        except OSError as exc:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass
            raise PostureWriteError(f"{WRITE_WARNING} ({exc})") from exc
        return record

    def clear(self, folder: "str | os.PathLike[str]") -> bool:
        """Delete the sidecar. Returns False when there was nothing to delete."""
        path = self.sidecar_path(folder)
        try:
            if not path.exists():
                return False
            path.unlink()
            return True
        except OSError as exc:
            raise PostureWriteError(f"{REVOKE_WARNING} ({exc})") from exc


# --------------------------------------------------------------------------
# folder tokens for the override record
# --------------------------------------------------------------------------

class FolderTokenMap:
    """Session-local pseudonyms for folder paths.

    The plugin's ``PathTokenMap`` salts its digests with per-JVM randomness
    (``PathTokenMap.java`` ``uniquePathToken``), so the console cannot and must
    not try to reproduce a plugin token. What is reproduced is the rule that
    matters: an override record never stores the raw folder path
    (PostureController.java:369).
    """

    TOKEN_HEX_CHARS = 32  # PathTokenMap.PATH_TOKEN_HEX_CHARS

    def __init__(self, salt: "bytes | None" = None) -> None:
        self._salt = salt if salt is not None else secrets.token_bytes(16)
        self._tokens: dict[str, str] = {}

    def token_for(self, folder: "str | os.PathLike[str]") -> str:
        key = str(_normalise_folder(folder))
        token = self._tokens.get(key)
        if token is None:
            digest = hashlib.sha256(self._salt + key.encode("utf-8")).hexdigest()
            token = "folder-" + digest[: self.TOKEN_HEX_CHARS]
            self._tokens[key] = token
        return token


@dataclass(frozen=True)
class OverrideRecord:
    """An audit row for a user who deliberately weakened the posture.

    Mirrors the ``posture.override`` row built in PostureController.java:357:
    posture labels, a folder *token*, and the reason - never the raw path.
    """

    at: str
    from_posture: "Posture | None"
    to_posture: Posture
    folder_token: str
    reason: str
    command: str = "posture.override"

    @property
    def notes(self) -> str:
        parts: list[str] = []
        if self.from_posture is not None:
            parts.append(f"from={self.from_posture.label}")
        parts.append(f"to={self.to_posture.label}")
        if self.folder_token:
            parts.append(f"folder_token={self.folder_token}")
        reason = self.reason.replace("\r", " ").replace("\n", " ").strip()
        if reason:
            parts.append(f"reason='{reason}'")
        return " ".join(parts)


# --------------------------------------------------------------------------
# controller (PostureController.java)
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class PostureEvent:
    """One published posture transition; the TUI renders these as notices."""

    event_type: str
    from_posture: "Posture | None"
    to_posture: "Posture | None"
    folder: "Path | None"
    reason: str = ""

    @property
    def message(self) -> str:
        if self.event_type == EVENT_DOWNSHIFTED and self.from_posture and self.to_posture:
            return DOWNSHIFT_NOTICE.format(
                from_label=self.from_posture.label, to_label=self.to_posture.label)
        return self.reason


class PostureController:
    """Session source of truth for the current posture.

    Pure logic: methods return the events they published so a caller can draw
    them; nothing here knows about widgets. ``prompt_needed`` tells the caller
    to ask the user, which is what ``Presenter.showFolderPosturePrompt`` does
    in Java.
    """

    def __init__(self, store: "FolderPostureStore | None" = None, *,
                 posture: "Posture | None" = None,
                 clock: "Callable[[], float] | None" = None,
                 token_map: "FolderTokenMap | None" = None,
                 timestamps: "Callable[[], datetime] | None" = None) -> None:
        self._store = store if store is not None else FolderPostureStore(timestamps)
        self._current = posture or Posture.default()
        self._clock = clock or time.time
        self._tokens = token_map or FolderTokenMap()
        self._timestamps = timestamps
        self._active_folder: Path | None = None
        self._consulted: dict[str, int] = {}
        self._listeners: list[Callable[[PostureEvent], None]] = []
        self.events: list[PostureEvent] = []
        self.overrides: list[OverrideRecord] = []
        self.prompt_needed: Path | None = None

    # -- state ------------------------------------------------------------
    @property
    def current(self) -> Posture:
        return self._current

    @property
    def active_folder(self) -> "Path | None":
        return self._active_folder

    def add_listener(self, listener: Callable[[PostureEvent], None]) -> None:
        if listener not in self._listeners:
            self._listeners.append(listener)

    def remove_listener(self, listener: Callable[[PostureEvent], None]) -> None:
        if listener in self._listeners:
            self._listeners.remove(listener)

    # -- transitions ------------------------------------------------------
    def on_folder_opened(self, folder: "str | os.PathLike[str]") -> list[PostureEvent]:
        """Apply a folder's stored posture when images from it are opened.

        Downshift is automatic; upshift never is. Repeat opens inside the same
        wall-clock second are debounced exactly as PostureController.java:384
        does, so a multi-series import does not spam the user.
        """
        normalised = _normalise_folder(folder)
        self._active_folder = normalised
        if self._is_debounced(normalised):
            return []

        try:
            record = self._store.read(normalised)
        except PostureReadError:
            message = READ_WARNING.format(folder=normalised.name or str(normalised))
            published = self._apply_default(normalised, message, Posture.PSEUDONYMISED)
            published += self._warn(normalised, message)
            return published

        if record is not None:
            return self._apply_stored(normalised, record.posture)

        published = self._apply_default(normalised, SELECTION_PROMPT)
        self.prompt_needed = normalised
        return published

    def request_posture(self, posture: Posture, folder: "str | os.PathLike[str] | None" = None,
                        reason: str = "") -> list[PostureEvent]:
        """User-driven change. Weakening is allowed but always logged."""
        target = posture or Posture.default()
        normalised = _normalise_folder(folder) if folder is not None else self._active_folder
        previous = self._current
        weakens = previous is not None and previous.is_stricter_than(target)

        published = self._set_current(target, normalised, EVENT_REQUESTED, reason)
        if normalised is not None:
            try:
                self._store.write(normalised, target, "user", reason or "")
                published += self._publish(
                    EVENT_FOLDER_SAVED, previous, target, normalised, reason)
            except PostureWriteError:
                published += self._warn(normalised, WRITE_WARNING)

        if weakens:
            published += self._publish(
                EVENT_OVERRIDE_LOGGED, previous, target, normalised, reason)
            self.overrides.append(OverrideRecord(
                at=_utc_now_iso(self._timestamps),
                from_posture=previous,
                to_posture=target,
                folder_token=self._tokens.token_for(normalised) if normalised else "",
                reason=reason or "",
            ))
        self.prompt_needed = None
        return published

    def sync_from_fiji(self, posture: Posture) -> list[PostureEvent]:
        """Adopt Fiji's live decision without writing a second folder record."""
        target = Posture.parse(posture)
        if target is self._current:
            return []
        return self._set_current(target, self._active_folder, EVENT_LOADED,
                                 "Fiji reported its current privacy posture")

    def override_downshift(self, posture: Posture, folder: "str | os.PathLike[str] | None",
                           reason: str) -> list[PostureEvent]:
        """Weaken the posture after a downshift, with a mandatory reason.

        The banner refuses an empty reason (PostureBanner.java:164); refusing it
        here too keeps the audit trail meaningful for any front end.
        """
        if not (reason or "").strip():
            raise ValueError(OVERRIDE_REASON_REQUIRED)
        return self.request_posture(posture, folder, reason.strip())

    def revoke_folder_posture(self, folder: "str | os.PathLike[str] | None" = None,
                              reason: str = "") -> list[PostureEvent]:
        """Delete the folder decision and fall back to the default posture."""
        normalised = _normalise_folder(folder) if folder is not None else self._active_folder
        if normalised is None:
            return []
        try:
            self._store.clear(normalised)
        except PostureWriteError:
            return self._warn(normalised, REVOKE_WARNING)
        return self._set_current(
            Posture.default(), normalised, EVENT_FOLDER_REVOKED, reason)

    # -- internals --------------------------------------------------------
    def _apply_stored(self, folder: Path, folder_posture: Posture) -> list[PostureEvent]:
        target = folder_posture or Posture.default()
        previous = self._current
        if target.is_stricter_than(previous):
            return self._set_current(
                target, folder, EVENT_DOWNSHIFTED, "Loaded stricter folder Privacy Posture")
        if previous is not None and previous.is_stricter_than(target):
            # PostureController.java:234 - a weaker folder never relaxes the session.
            return self._publish(
                EVENT_UPSHIFT_REFUSED, previous, target, folder, NEVER_AUTO_UPSHIFT)
        return self._publish(EVENT_LOADED, previous, target, folder, "")

    def _apply_default(self, folder: Path, message: str,
                       target: "Posture | None" = None) -> list[PostureEvent]:
        target = target or Posture.default()
        previous = self._current
        if target.is_stricter_than(previous):
            return self._set_current(target, folder, EVENT_DEFAULTED, message)
        return self._publish(EVENT_SELECTION_NEEDED, previous, target, folder, message)

    def _set_current(self, target: Posture, folder: "Path | None",
                     event_type: str, reason: str) -> list[PostureEvent]:
        previous = self._current
        self._current = target or Posture.default()
        if folder is not None:
            self._active_folder = folder
        return self._publish(event_type, previous, self._current, folder, reason)

    def _warn(self, folder: "Path | None", message: str) -> list[PostureEvent]:
        return self._publish(EVENT_WARNING, self._current, self._current, folder, message)

    def _publish(self, event_type: str, from_posture: "Posture | None",
                 to_posture: "Posture | None", folder: "Path | None",
                 reason: str) -> list[PostureEvent]:
        event = PostureEvent(event_type, from_posture, to_posture, folder, reason or "")
        self.events.append(event)
        for listener in list(self._listeners):
            try:
                listener(event)
            except Exception:
                # A broken listener must not break governance (Java swallows
                # listener throwables in notifyEvent).
                pass
        return [event]

    def _is_debounced(self, folder: Path) -> bool:
        second = int(self._clock())
        key = str(folder)
        if self._consulted.get(key) == second:
            return True
        self._consulted[key] = second
        return False


# --------------------------------------------------------------------------
# launch gating (LaunchPolicy.java / AgentLauncher.java)
# --------------------------------------------------------------------------

class PostureViolation(RuntimeError):
    """Raised by :meth:`LaunchDecision.enforce` - the Java PostureViolation."""


_PROVIDER_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")  # LaunchPolicy.java:39
_MODEL_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/@+~-]{0,255}$")  # LaunchPolicy.java:41

ON_PREMISES_REFUSAL = (  # LaunchPolicy.java:176
    "On-premises posture blocks this provider from sending data off this machine."
)
CLOUD_OLLAMA_REFUSAL = (  # AgentLauncher.java:124
    "On-premises mode cannot use cloud-hosted Ollama models. "
    "Switch to a local tag (e.g. gemma3:27b) or change the posture for this folder."
)

# LaunchPolicy.isLocalProvider (LaunchPolicy.java) plus the console-only ids
# that also speak to a loopback endpoint.
LOCAL_PROVIDERS = frozenset({
    "local-assistant", "ollama", "lmstudio", "jan", "llamacpp", "vllm",
})


def is_local_provider(provider: str) -> bool:
    return (provider or "").strip().lower() in LOCAL_PROVIDERS


def is_loopback_endpoint(endpoint: "str | None") -> bool:
    """True for localhost/127.0.0.1/::1 URLs (LaunchPolicy.isLoopbackEndpoint)."""
    text = (endpoint or "").strip()
    if not text:
        return False
    if "://" not in text:
        text = "http://" + text
    try:
        from urllib.parse import urlsplit
        host = urlsplit(text).hostname
    except ValueError:
        return False
    return host is not None and host.lower() in ("localhost", "127.0.0.1", "::1")


def is_local_provider_endpoint(provider: str, endpoint: "str | None" = None) -> bool:
    """Locality comes from the endpoint, never from a caller-supplied flag (6.11)."""
    key = (provider or "").strip().lower()
    if key == "local-assistant":
        return True
    if not is_local_provider(key):
        return False
    return not (endpoint or "").strip() or is_loopback_endpoint(endpoint)


def is_cloud_ollama_tag(model: "str | None") -> bool:
    """``*-cloud`` / ``*:cloud`` tags run on Ollama's servers (AgentLauncher.java:890)."""
    tag = (model or "").strip().lower()
    if not tag:
        return False
    return tag.endswith("-cloud") or tag.endswith(":cloud")


@dataclass(frozen=True)
class LaunchDecision:
    """Typed answer to "may this provider be launched under this posture?"."""

    allowed: bool
    reason: str
    provider: str = ""
    model: str = ""
    posture: "Posture | None" = None
    local: bool = False

    def enforce(self) -> "LaunchDecision":
        if not self.allowed:
            raise PostureViolation(self.reason)
        return self

    def __bool__(self) -> bool:  # `if decision:` reads naturally at call sites
        return self.allowed


def evaluate_launch(provider: str, model: "str | None", posture: "Posture | None", *,
                    endpoint: "str | None" = None,
                    egress: "bool | None" = None) -> LaunchDecision:
    """Single decision point for launching an agent, ported from LaunchPolicy.

    ``egress`` defaults to "anything that is not a local endpoint sends data
    off this machine", which is how AgentLauncher.java:898 derives it.
    """
    effective = posture or Posture.default()
    candidate_provider = (provider or "").strip()
    candidate_model = (model or "").strip()
    if not _PROVIDER_ID.match(candidate_provider):
        return LaunchDecision(False, "Invalid provider identifier.",
                              candidate_provider, candidate_model, effective, False)
    if candidate_model and not _MODEL_ID.match(candidate_model):
        return LaunchDecision(False, "Invalid model identifier.",
                              candidate_provider, candidate_model, effective, False)

    cloud_ollama = is_cloud_ollama_tag(candidate_model) or candidate_provider.lower() == "ollama-cloud"
    local = is_local_provider_endpoint(candidate_provider, endpoint) and not cloud_ollama
    sends_data_out = (not local) if egress is None else bool(egress)

    if effective is Posture.ON_PREMISES and cloud_ollama:
        # Checked before the generic rule so the user gets the actionable
        # message about switching to a local tag (AgentLauncher.java:904).
        return LaunchDecision(False, CLOUD_OLLAMA_REFUSAL, candidate_provider,
                              candidate_model, effective, False)
    if effective is Posture.ON_PREMISES and sends_data_out and not local:
        return LaunchDecision(False, ON_PREMISES_REFUSAL, candidate_provider,
                              candidate_model, effective, False)
    return LaunchDecision(True, "", candidate_provider, candidate_model, effective, local)


def filter_providers_for_posture(providers: Iterable[str], posture: "Posture | None", *,
                                 models: "dict[str, str] | None" = None,
                                 endpoints: "dict[str, str] | None" = None) -> list[str]:
    """Drop the providers a launch would refuse (AgentLauncher.filterAgentsForPosture)."""
    models = models or {}
    endpoints = endpoints or {}
    kept: list[str] = []
    for provider in providers:
        decision = evaluate_launch(provider, models.get(provider, ""), posture,
                                   endpoint=endpoints.get(provider))
        if decision.allowed:
            kept.append(provider)
    return kept


# --------------------------------------------------------------------------
# badge + lamp state for the UI (PostureBadge.java, EgressIndicator.java)
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class BadgeState:
    """Everything a front end needs to paint the posture chip, as data."""

    label: str
    glyph: str
    color_role: str
    background: str
    foreground: str
    tooltip: str


_BADGE_TOOLTIP = (  # PostureBadge.java:52
    "Privacy Posture: {label}\n{description}\n\n"
    "Data Governance steward signal: this folder's posture governs "
    "pseudonymisation, local-agent allowlisting, and the audit trail."
)

# Glyphs show strictness as fill level; colours are the Java chip colours
# (PostureBadge.java:17-22) so both consoles look like the same product.
_BADGES = {
    Posture.STANDARD: ("\u25CB", "posture-standard", "#6F7782", "#FFFFFF"),
    Posture.PSEUDONYMISED: ("\u25D0", "posture-pseudonymised", "#F5A524", "#1F2328"),
    Posture.ON_PREMISES: ("\u25CF", "posture-on-premises", "#2F9E44", "#FFFFFF"),
}


def badge_for(posture: "Posture | None") -> BadgeState:
    effective = posture or Posture.default()
    glyph, role, background, foreground = _BADGES[effective]
    return BadgeState(
        label=effective.label,
        glyph=glyph,
        color_role=role,
        background=background,
        foreground=foreground,
        tooltip=_BADGE_TOOLTIP.format(label=effective.label,
                                      description=effective.description),
    )


def footer_text(posture: "Posture | None") -> str:
    """One plain line about where data goes (AiRootPanel.postureFooterText, 6.9)."""
    effective = posture or Posture.default()
    if effective is Posture.ON_PREMISES:
        return "\U0001F512 On-premises — your data stays on this machine"
    if effective is Posture.PSEUDONYMISED:
        return "\U0001F512 Pseudonymised — identifiers tokenised before send"
    return "Standard — cloud agents allowed"


@dataclass(frozen=True)
class LampState:
    """Snapshot of the egress lamp: lit now, plus the session counters."""

    lit: bool
    calls: int
    bytes_out: int
    last_at: float


class EgressLamp:
    """Counter plus blink window for the "bytes just left" lamp.

    EgressIndicator.java:56 turns the lamp red and restarts a one-shot 300 ms
    timer, so the lamp is lit for 300 ms after the *last* call. The clock is
    injected because a test must be able to step time without sleeping.
    """

    BLINK_MS = 300  # EgressIndicator.java:59

    def __init__(self, clock: "Callable[[], float] | None" = None,
                 blink_ms: int = BLINK_MS) -> None:
        self._clock = clock or time.monotonic
        self._blink_s = max(0.0, blink_ms / 1000.0)
        self._calls = 0
        self._bytes = 0
        self._last_at = float("-inf")

    def record(self, bytes_out: int = 0) -> LampState:
        """Register one outbound call and (re)start the blink window."""
        self._calls += 1
        self._bytes += max(0, int(bytes_out))
        self._last_at = float(self._clock())
        return self.state()

    def is_lit(self) -> bool:
        return (float(self._clock()) - self._last_at) < self._blink_s

    def state(self) -> LampState:
        return LampState(lit=self.is_lit(), calls=self._calls,
                         bytes_out=self._bytes,
                         last_at=self._last_at if self._calls else 0.0)

    @property
    def calls(self) -> int:
        return self._calls

    @property
    def bytes_out(self) -> int:
        return self._bytes
