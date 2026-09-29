"""Safe, agent-agnostic access to reviewed ImageJAI harness knowledge.

This facade deliberately has one write operation: :func:`propose_candidate`.
Review and lifecycle operations remain administrator/Console concerns.  The
module can also be run as a small JSON CLI (``python harness_access.py``).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

try:  # Package import and direct ``python agent/harness_access.py`` both work.
    from .console import harness as _harness
    from .console import skills as _skills
except ImportError:  # pragma: no cover - exercised by CLI subprocess tests
    from console import harness as _harness
    from console import skills as _skills


class HarnessAccessError(ValueError):
    """A request cannot be served without weakening the safe facade."""


_SESSION_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")
# A drive root, UNC root, or POSIX root at a normal text boundary.  The default
# deliberately redacts to end-of-line: over-redaction is safer than leaking a
# path containing spaces because a guessed path boundary was too short.
_ABSOLUTE_PATH_RE = re.compile(
    r"(?<![A-Za-z0-9_.-])(?:[A-Za-z]:[\\/]|\\\\|/)[^\r\n]*"
)
# Bare microscopy sample names are sensitive even when their directory was
# omitted.  They must be removed by a caller's sanitizer rather than persisted.
_SAMPLE_FILE_RE = re.compile(
    r"(?i)(?<![A-Za-z0-9_.-])[A-Za-z0-9_. -]{1,160}\."
    r"(?:ome\.(?:tif|tiff)|tif|tiff|czi|lif|nd2|lsm|ims|vsi|oib|oif|lei|zvi|png|jpe?g|bmp|gif|webp|dcm|dicom)"
    r"(?![A-Za-z0-9_.-])"
)
_PSEUDONYM_TOKEN_RE = re.compile(
    r"(?i)image-[0-9a-f]{4,32}(?:\.[A-Za-z0-9.]+)?(?::\d+)?"
)
_STORE_FILES = ("harness_state.json", "refinements.jsonl")
_SCOPE_BY_STORE = {
    "session": frozenset(("session",)),
    "project": frozenset(("project", "instrument")),
    "global": frozenset(("user", "shared")),
}


def _default_sanitizer(text: str) -> str:
    return _ABSOLUTE_PATH_RE.sub("[REDACTED_PATH]", text)


def _safe_text(value: Any, sanitizer: Callable[[str], str] | None, *, field: str) -> str:
    raw = str(value)
    clean = (sanitizer or _default_sanitizer)(raw)
    if not isinstance(clean, str):
        raise HarnessAccessError("privacy_sanitizer must return str")
    clean = clean.replace("\x00", "")
    # A custom sanitizer replaces the conservative default, but never bypasses
    # the final leak check.  Thus a weak/identity sanitizer fails closed.
    if _ABSOLUTE_PATH_RE.search(clean):
        raise HarnessAccessError(f"{field} contains an unsanitized absolute path")
    sample_check = _PSEUDONYM_TOKEN_RE.sub("[PSEUDONYM_TOKEN]", clean)
    if _SAMPLE_FILE_RE.search(sample_check):
        raise HarnessAccessError(f"{field} contains an unsanitized sample filename")
    return clean


def _safe_json_value(value: Any, sanitizer: Callable[[str], str] | None, field: str) -> Any:
    if isinstance(value, str):
        return _safe_text(value, sanitizer, field=field)
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, Mapping):
        result: dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str):
                raise HarnessAccessError(f"{field} keys must be strings")
            # Mapping keys carry semantics, so do not silently rewrite them.
            safe_key = _safe_text(key, sanitizer, field=f"{field} key")
            if safe_key != key:
                raise HarnessAccessError(f"{field} contains a sensitive mapping key")
            result[key] = _safe_json_value(item, sanitizer, f"{field}.{key}")
        return result
    if isinstance(value, (list, tuple)):
        return [_safe_json_value(item, sanitizer, f"{field}[]") for item in value]
    raise HarnessAccessError(f"{field} must contain only JSON values")


def _home_path(home: str | os.PathLike[str] | None, *, required: bool) -> Path | None:
    supplied = home if home is not None else os.environ.get("IMAGEJAI_HOME")
    if supplied is None or not str(supplied).strip():
        if required:
            raise HarnessAccessError("home is required (pass home or set IMAGEJAI_HOME)")
        return None
    return Path(supplied).resolve()


def _project_path(project_dir: str | os.PathLike[str] | None, *, required: bool) -> Path | None:
    if project_dir is None or not str(project_dir).strip():
        if required:
            raise HarnessAccessError("project_dir is required for project/instrument scope")
        return None
    return Path(project_dir).resolve()


def _session_name(session_id: str | None, *, required: bool) -> str | None:
    if session_id is None or not session_id.strip():
        if required:
            raise HarnessAccessError("session_id is required for session scope")
        return None
    if not _SESSION_RE.fullmatch(session_id) or session_id in {".", ".."}:
        raise HarnessAccessError("invalid session_id")
    return session_id


def _store(root: Path):
    return _harness.HarnessStore(root / _STORE_FILES[0], root / _STORE_FILES[1])


def _routed_store(scope: str, *, project_dir: str | os.PathLike[str] | None,
                  session_id: str | None, home: str | os.PathLike[str] | None):
    if scope == "session":
        base = _home_path(home, required=True)
        sid = _session_name(session_id, required=True)
        return _store(base / "sessions" / sid / "harness")
    if scope in {"project", "instrument"}:
        project = _project_path(project_dir, required=True)
        return _store(project / "AI_Exports" / ".imagejai-harness")
    if scope in {"user", "shared"}:
        base = _home_path(home, required=True)
        return _store(base / "harness")
    raise HarnessAccessError(f"invalid scope: {scope!r}")


def _digest_limits(model_profile: Mapping[str, Any]) -> tuple[int, int]:
    try:
        reliability = model_profile.get("reliability", "medium")
        context = int(model_profile.get("context_window", model_profile.get("context_chars", 32_000)))
        low = reliability == "low" or isinstance(reliability, (int, float)) and reliability < 0.6
        small = context <= 8_000
        default_count = 3 if low or small else 6
        default_chars = 2_000 if low or small else 5_000
        count = max(0, min(default_count, int(model_profile.get("max_entries", default_count)), 12))
        budget = max(1, min(default_chars, int(model_profile.get("max_digest_chars", default_chars)),
                            _harness.HarnessStore.MAX_DIGEST_CHARS))
    except (TypeError, ValueError) as exc:
        raise HarnessAccessError(f"invalid model_profile: {exc}") from exc
    return count, budget


def reviewed_context(query: str, state: Mapping[str, Any], model_profile: Mapping[str, Any],
                     project_dir: str | os.PathLike[str] | None = None,
                     session_id: str | None = None,
                     privacy_sanitizer: Callable[[str], str] | None = None,
                     home: str | os.PathLike[str] | None = None) -> str:
    """Return a deterministic, bounded digest of reviewed entries only.

    Stores are read in decreasing locality: session, project, then user/shared.
    Missing optional locations are simply omitted.  ``session_id`` requires a
    home because session storage is rooted below ``IMAGEJAI_HOME``.
    """
    if not isinstance(query, str) or not isinstance(state, Mapping) or not isinstance(model_profile, Mapping):
        raise HarnessAccessError("query must be str; state and model_profile must be mappings")
    base = _home_path(home, required=session_id is not None)
    project = _project_path(project_dir, required=False)
    sid = _session_name(session_id, required=False)
    count, budget = _digest_limits(model_profile)
    stores: list[tuple[str, Any]] = []
    if sid is not None:
        stores.append(("session", _store(base / "sessions" / sid / "harness")))
    if project is not None:
        stores.append(("project", _store(project / "AI_Exports" / ".imagejai-harness")))
    if base is not None:
        stores.append(("global", _store(base / "harness")))

    # HarnessStore.digest is the review gate: it allows only the three reviewed
    # statuses and omits evidence.  The wrapper sanitizer adds a final leak
    # check after either the default or injected sanitizer.
    def sanitizer(text: str) -> str:
        return _safe_text(text, privacy_sanitizer, field="reviewed context")

    lines: list[str] = ["[harness-digest] schema_version=1 reviewed_only=true"]
    used = 0
    for label, store in stores:
        profile = dict(model_profile)
        profile["max_entries"] = count
        profile["max_digest_chars"] = budget
        rendered = store.digest(query, state, profile, sanitizer)
        allowed = _SCOPE_BY_STORE[label]
        prefix = tuple(f"- [{status}/{scope}]" for status in
                       ("session_confirmed", "project_approved", "validated")
                       for scope in sorted(allowed))
        for line in rendered.splitlines():
            if not line.startswith(prefix):
                continue
            if used >= count:
                break
            candidate = line
            projected = len("\n".join(lines + [candidate]))
            if projected > budget:
                break
            lines.append(candidate)
            used += 1
        if used >= count:
            break
    if used == 0:
        empty = "No confirmed entries matched this task and image state."
        if len("\n".join(lines + [empty])) <= budget:
            lines.append(empty)
    return "\n".join(lines)[:budget]


def propose_candidate(kind: str, scope: str, title: str, content: str, *,
                      path: str = "", reference: Mapping[str, Any] | None = None,
                      metadata: Mapping[str, Any] | None = None,
                      evidence: Sequence[str] | None = None,
                      applicability: Mapping[str, Any] | None = None,
                      conflicts: Sequence[str] | None = None,
                      expires_at: str | datetime | None = None, source: str = "",
                      project_dir: str | os.PathLike[str] | None = None,
                      session_id: str | None = None,
                      privacy_sanitizer: Callable[[str], str] | None = None,
                      home: str | os.PathLike[str] | None = None) -> dict[str, Any]:
    """Sanitize and persist one candidate in the canonical scope store."""
    store = _routed_store(scope, project_dir=project_dir, session_id=session_id, home=home)
    safe_evidence = _safe_json_value(list(evidence or []), privacy_sanitizer, "evidence")
    safe_conflicts = _safe_json_value(list(conflicts or []), privacy_sanitizer, "conflicts")
    entry = store.propose(
        kind=kind, scope=scope,
        title=_safe_text(title, privacy_sanitizer, field="title"),
        content=_safe_text(content, privacy_sanitizer, field="content"),
        path=_safe_text(path, privacy_sanitizer, field="path"),
        reference=_safe_json_value(dict(reference or {}), privacy_sanitizer, "reference"),
        metadata=_safe_json_value(dict(metadata or {}), privacy_sanitizer, "metadata"),
        evidence=safe_evidence,
        applicability=_safe_json_value(dict(applicability or {}), privacy_sanitizer, "applicability"),
        conflicts=safe_conflicts, expires_at=expires_at,
        source=_safe_text(source, privacy_sanitizer, field="source"),
    )
    # A plain copy is portable across every Python agent and JSON CLI.
    return entry.to_dict()


def _catalog(project_dir: str | os.PathLike[str], home: str | os.PathLike[str] | None,
             bundled_skills_dir: str | os.PathLike[str] | None,
             repo_dir: str | os.PathLike[str] | None):
    project = _project_path(project_dir, required=True)
    base = _home_path(home, required=False)
    bundled = (Path(__file__).resolve().parent / "skills" if bundled_skills_dir is None
               else Path(bundled_skills_dir).resolve())
    return _skills.discover_skills(
        project, repo_root=repo_dir,
        user_skills_dir=None if base is None else base / "skills",
        bundled_skills_dir=bundled,
    )


def skill_catalog(project_dir: str | os.PathLike[str], *,
                  home: str | os.PathLike[str] | None = None,
                  bundled_skills_dir: str | os.PathLike[str] | None = None,
                  repo_dir: str | os.PathLike[str] | None = None,
                  max_chars: int | None = None) -> dict[str, Any]:
    """Discover bounded skill metadata without reading any skill body."""
    catalog = _catalog(project_dir, home, bundled_skills_dir, repo_dir)
    skills = [{
        "name": item.name, "description": item.description,
        "source": _default_sanitizer(item.source),
        "disable_model_invocation": item.disable_model_invocation,
        "recipe": item.recipe,
    } for item in catalog.skills]
    diagnostics = [{"code": item.code, "message": _default_sanitizer(item.message),
                    "severity": item.severity} for item in catalog.diagnostics]
    return {"catalog": catalog.prompt_catalog(max_chars=max_chars),
            "skills": skills, "diagnostics": diagnostics}


def load_skill(name: str, project_dir: str | os.PathLike[str], *,
               home: str | os.PathLike[str] | None = None,
               bundled_skills_dir: str | os.PathLike[str] | None = None,
               repo_dir: str | os.PathLike[str] | None = None,
               explicit_user: bool = False) -> dict[str, Any]:
    """Load one skill body on demand, respecting its model-disable flag."""
    if not isinstance(explicit_user, bool):
        raise HarnessAccessError("explicit_user must be bool")
    catalog = _catalog(project_dir, home, bundled_skills_dir, repo_dir)
    metadata = catalog.get(name)
    if metadata is None:
        raise KeyError(name)
    body = catalog.load_body(name, model_invoked=not explicit_user)
    return {
        "name": metadata.name, "description": metadata.description,
        "source": _default_sanitizer(metadata.source),
        "disable_model_invocation": metadata.disable_model_invocation,
        "recipe": metadata.recipe, "body": body,
    }


def _json_object(value: str, label: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as exc:
        raise HarnessAccessError(f"{label} must be valid JSON: {exc.msg}") from exc
    if not isinstance(parsed, dict):
        raise HarnessAccessError(f"{label} must be a JSON object")
    return parsed


class _JSONArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        print(json.dumps({"ok": False, "error": {"type": "ArgumentError",
                                                   "message": message}},
                         ensure_ascii=False, sort_keys=True), file=sys.stderr)
        raise SystemExit(2)


def _parser() -> argparse.ArgumentParser:
    parser = _JSONArgumentParser(description="Safe ImageJAI harness/skill facade")
    commands = parser.add_subparsers(dest="command", required=True)
    common: dict[str, argparse.ArgumentParser] = {}
    for name in ("context", "propose", "skills", "skill"):
        common[name] = commands.add_parser(name)
        common[name].add_argument("--home", default=None)
        common[name].add_argument("--project", default=None)
    p = common["context"]
    p.add_argument("--query", required=True)
    p.add_argument("--state", default="{}")
    p.add_argument("--model-profile", default="{}")
    p.add_argument("--session-id")
    p = common["propose"]
    p.add_argument("--kind", required=True)
    p.add_argument("--scope", required=True)
    p.add_argument("--title", required=True)
    p.add_argument("--content", required=True)
    p.add_argument("--session-id")
    p.add_argument("--path", default="")
    p.add_argument("--source", default="")
    p.add_argument("--reference", default="{}")
    p.add_argument("--metadata", default="{}")
    p.add_argument("--applicability", default="{}")
    p = common["skills"]
    p.add_argument("--max-chars", type=int)
    p = common["skill"]
    p.add_argument("--name", required=True)
    p.add_argument("--explicit-user", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = _parser().parse_args(argv)
        project = args.project
        if args.command == "context":
            result: Any = {"context": reviewed_context(
                args.query, _json_object(args.state, "state"),
                _json_object(args.model_profile, "model-profile"),
                project_dir=project, session_id=args.session_id, home=args.home)}
        elif args.command == "propose":
            result = {"candidate": propose_candidate(
                args.kind, args.scope, args.title, args.content,
                path=args.path, source=args.source,
                reference=_json_object(args.reference, "reference"),
                metadata=_json_object(args.metadata, "metadata"),
                applicability=_json_object(args.applicability, "applicability"),
                project_dir=project, session_id=args.session_id, home=args.home)}
        elif args.command == "skills":
            result = skill_catalog(project, home=args.home, max_chars=args.max_chars)
        else:
            result = load_skill(args.name, project, home=args.home,
                                explicit_user=args.explicit_user)
        print(json.dumps({"ok": True, **result}, ensure_ascii=False, sort_keys=True))
        return 0
    except SystemExit:
        raise
    except Exception as exc:
        print(json.dumps({"ok": False, "error": {"type": type(exc).__name__,
                                                   "message": str(exc)}},
                         ensure_ascii=False, sort_keys=True), file=sys.stderr)
        return 2


__all__ = ["HarnessAccessError", "reviewed_context", "propose_candidate",
           "skill_catalog", "load_skill", "main"]

if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
