"""Read-only index of knowledge that the ImageJAI package already ships.

The console harness stores *reviewed* knowledge.  This module is its
read-only counterpart: it turns material that is already bundled with the
package -- executable recipes in ``agent/recipes`` and reference documents in
``agent/references`` -- plus the local failure-fix ledger into
:class:`~agent.console.harness.HarnessEntry` rows that retrieval can rank.

Deliberate non-features:

* No writes.  Nothing here creates, promotes, or refines a harness entry.
* No network, no model calls, no clock-dependent behaviour.
* Private, unreviewed material is never read.  ``learnings.md`` and
  ``lab_profile.json`` are skipped even when they sit in a scanned folder.
* Reference *bodies* never reach :meth:`KnowledgeIndex.rows` or
  :meth:`KnowledgeIndex.search`.  Only the title and the section headings are
  indexed; the body is served, bounded, by :meth:`load_reference`.

Malformed input is reported through :attr:`KnowledgeIndex.diagnostics` and
skipped.  Scanning never raises.
"""
from __future__ import annotations

import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Mapping, Optional, Sequence, Tuple, Union

try:  # pragma: no cover - import shape depends on how the console is loaded
    from .harness import HarnessEntry
except ImportError:  # pragma: no cover
    from agent.console.harness import HarnessEntry  # type: ignore

try:
    import yaml
except ImportError:  # pragma: no cover - PyYAML is a console requirement
    yaml = None  # type: ignore

PathLike = Union[str, os.PathLike]
Sanitizer = Callable[[str], str]

#: Files that must never be read, whatever folder they appear in.  These hold
#: unreviewed, site-specific material; only the harness may surface them and
#: only after review.
FORBIDDEN_NAMES = frozenset(("learnings.md", "lab_profile.json"))

#: Index files are navigation aids, not knowledge; they duplicate the rows we
#: build from the documents themselves.
SKIPPED_REFERENCE_NAMES = frozenset(("index.md", "readme.md"))

RECIPE_SUFFIXES = (".yaml", ".yml")
REFERENCE_SUFFIXES = (".md",)

MAX_TITLE = 240
MAX_CONTENT = 2_000
MAX_SUMMARY = 480
MAX_HEADINGS = 40
MAX_TAGS = 24
MAX_DIAGNOSTICS = 200
MAX_LOAD_CHARS = 200_000
EPOCH = "1970-01-01T00:00:00Z"

_HEADING_RE = re.compile(r"^(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")
_TOKEN_RE = re.compile(r"[a-z0-9_]+")

#: Said in plain words on every ledger-derived row.  Confirmation counts say
#: how often a macro fix made ImageJ stop erroring.  They say nothing about
#: whether the science is right.
LEDGER_HONESTY = (
    "These counts show software reliability, not scientific validity: they "
    "record how often this fix made the macro run, not whether the "
    "measurement is biologically correct."
)


def _tokens(text: str) -> frozenset:
    return frozenset(_TOKEN_RE.findall(text.lower()))


def _clip(text: str, maximum: int) -> str:
    text = " ".join(str(text).replace("\x00", "").split())
    if len(text) <= maximum:
        return text
    return text[: max(0, maximum - 1)].rstrip() + "\u2026"


def _iso_mtime(path: Path) -> str:
    try:
        stamp = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
    except OSError:
        return EPOCH
    return stamp.isoformat().replace("+00:00", "Z")


def _is_forbidden(path: Path) -> bool:
    return path.name.lower() in FORBIDDEN_NAMES


class KnowledgeIndex:
    """Index bundled recipes, reference documents, and confirmed macro fixes.

    Parameters
    ----------
    recipes_dir, references_dir:
        Explicit folders.  Nothing is discovered from ``$HOME`` or the
        environment; callers pass what they want scanned.
    ledger_lookup:
        Optional callable used by :meth:`fixes`.  It is called with the three
        failure keys and must return a sequence of mappings shaped like ledger
        entries.  Injected so tests never need a real ledger.
    ledger_path:
        Optional read-only JSON fallback used when ``ledger_lookup`` is absent.
    max_files, max_file_bytes:
        Scan bounds.  A folder with more files is truncated; a file larger than
        ``max_file_bytes`` is skipped.  Both add a diagnostic.
    """

    def __init__(
        self,
        recipes_dir: Optional[PathLike],
        references_dir: Optional[PathLike],
        *,
        ledger_lookup: Optional[Callable[..., Any]] = None,
        ledger_path: Optional[PathLike] = None,
        max_files: int = 500,
        max_file_bytes: int = 1_000_000,
    ) -> None:
        if not isinstance(max_files, int) or isinstance(max_files, bool) or max_files < 1:
            raise ValueError("max_files must be a positive int")
        if not isinstance(max_file_bytes, int) or isinstance(max_file_bytes, bool) or max_file_bytes < 1:
            raise ValueError("max_file_bytes must be a positive int")
        self.recipes_dir = None if recipes_dir is None else Path(recipes_dir)
        self.references_dir = None if references_dir is None else Path(references_dir)
        self.ledger_lookup = ledger_lookup
        self.ledger_path = None if ledger_path is None else Path(ledger_path)
        self.max_files = max_files
        self.max_file_bytes = max_file_bytes
        self.diagnostics: List[str] = []
        self._rows: Optional[List[HarnessEntry]] = None

    # ------------------------------------------------------------------
    # diagnostics
    # ------------------------------------------------------------------
    def _note(self, message: str) -> None:
        if len(self.diagnostics) < MAX_DIAGNOSTICS:
            self.diagnostics.append(_clip(message, 300))
        elif len(self.diagnostics) == MAX_DIAGNOSTICS:
            self.diagnostics.append("diagnostics truncated")

    # ------------------------------------------------------------------
    # scanning
    # ------------------------------------------------------------------
    def _candidate_files(self, folder: Optional[Path], suffixes: Sequence[str], label: str) -> List[Path]:
        if folder is None:
            return []
        try:
            if not folder.is_dir():
                self._note(f"{label} folder missing: {folder.name}")
                return []
            names = sorted(p for p in folder.iterdir())
        except OSError as exc:
            self._note(f"{label} folder unreadable: {exc.__class__.__name__}")
            return []
        chosen: List[Path] = []
        for path in names:
            if len(chosen) >= self.max_files:
                self._note(f"{label} folder truncated at max_files={self.max_files}")
                break
            try:
                if not path.is_file():
                    continue
            except OSError:
                continue
            if path.suffix.lower() not in suffixes:
                continue
            if _is_forbidden(path):
                # Unreviewed local material: never opened, not even to size it.
                continue
            chosen.append(path)
        return chosen

    def _read_text(self, path: Path, label: str) -> Optional[str]:
        if _is_forbidden(path):
            return None
        try:
            size = path.stat().st_size
        except OSError as exc:
            self._note(f"{label} unreadable {path.name}: {exc.__class__.__name__}")
            return None
        if size > self.max_file_bytes:
            self._note(f"{label} too large, skipped: {path.name} ({size} bytes)")
            return None
        try:
            return path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            self._note(f"{label} unreadable {path.name}: {exc.__class__.__name__}")
            return None

    # ------------------------------------------------------------------
    # recipes
    # ------------------------------------------------------------------
    def _recipe_rows(self) -> List[HarnessEntry]:
        rows: List[HarnessEntry] = []
        seen_ids = set()
        for path in self._candidate_files(self.recipes_dir, RECIPE_SUFFIXES, "recipe"):
            text = self._read_text(path, "recipe")
            if text is None:
                continue
            if yaml is None:
                self._note("PyYAML is unavailable; recipes were not indexed")
                break
            try:
                data = yaml.safe_load(text)
            except Exception as exc:  # yaml raises several error classes
                self._note(f"malformed recipe {path.name}: {exc.__class__.__name__}")
                continue
            if not isinstance(data, Mapping):
                self._note(f"malformed recipe {path.name}: not a mapping")
                continue
            recipe_id = data.get("id")
            name = data.get("name")
            if not isinstance(recipe_id, str) or not recipe_id.strip():
                self._note(f"malformed recipe {path.name}: missing id")
                continue
            recipe_id = recipe_id.strip()
            if recipe_id in seen_ids:
                self._note(f"duplicate recipe id skipped: {recipe_id}")
                continue
            seen_ids.add(recipe_id)
            if not isinstance(name, str) or not name.strip():
                name = recipe_id
            rows.append(self._recipe_entry(path, recipe_id, name.strip(), data))
        return rows

    def _recipe_entry(self, path: Path, recipe_id: str, name: str, data: Mapping[str, Any]) -> HarnessEntry:
        description = data.get("description")
        description = description if isinstance(description, str) else ""
        steps = data.get("steps")
        step_count = len(steps) if isinstance(steps, list) else 0
        params = data.get("parameters")
        param_names = []
        if isinstance(params, list):
            for item in params:
                if isinstance(item, Mapping) and isinstance(item.get("name"), str):
                    param_names.append(item["name"])
        tags = self._string_list(data.get("tags"))
        difficulty = data.get("difficulty") if isinstance(data.get("difficulty"), str) else ""
        domain = data.get("domain") if isinstance(data.get("domain"), str) else ""

        # A short summary only.  The recipe file stays the single source of
        # truth for steps and macro code.
        summary = _clip(description or name, MAX_SUMMARY)
        content = (
            f"{summary} Recipe '{recipe_id}' has {step_count} step(s)"
            + (f" and {len(param_names)} parameter(s)" if param_names else "")
            + (f"; domain {domain}" if domain else "")
            + (f"; difficulty {difficulty}" if difficulty else "")
            + f". Full steps live in {path.name}; load the recipe file to run it."
        )

        applicability: Dict[str, Any] = {}
        if domain:
            applicability["domain"] = domain
        if tags:
            applicability["tags"] = tags
        pre = data.get("preconditions")
        if isinstance(pre, Mapping):
            image_type = self._string_list(pre.get("image_type"))
            if image_type:
                applicability["image_type"] = image_type
            if isinstance(pre.get("min_channels"), int) and not isinstance(pre.get("min_channels"), bool):
                applicability["min_channels"] = int(pre["min_channels"])
            for key in ("needs_stack", "needs_time", "needs_calibration"):
                if isinstance(pre.get(key), bool):
                    applicability[key] = bool(pre[key])
        elif pre is not None:
            self._note(f"recipe {path.name}: preconditions ignored (not a mapping)")

        metadata: Dict[str, Any] = {
            "recipe_id": recipe_id,
            "step_count": step_count,
            "parameters": param_names[:MAX_TAGS],
        }
        if difficulty:
            metadata["difficulty"] = difficulty
        known_issues = data.get("known_issues")
        if isinstance(known_issues, list):
            metadata["known_issue_count"] = len(known_issues)

        stamp = _iso_mtime(path)
        return HarnessEntry(
            id=f"bundled-recipe-{recipe_id}",
            kind="procedure",
            scope="shared",
            status="validated",
            title=_clip(name, MAX_TITLE),
            content=_clip(content, MAX_CONTENT),
            path=path.name,
            reference={"recipe_file": path.name, "recipe_id": recipe_id},
            metadata=metadata,
            evidence=[],
            applicability=applicability,
            conflicts=[],
            expires_at=None,
            source=f"bundled:recipe:{recipe_id}",
            version=1,
            created_at=stamp,
            updated_at=stamp,
        )

    @staticmethod
    def _string_list(value: Any) -> List[str]:
        if isinstance(value, str):
            items = [value]
        elif isinstance(value, (list, tuple)):
            items = [v for v in value if isinstance(v, str)]
        else:
            return []
        out = []
        for item in items:
            item = item.strip()
            if item and item not in out:
                out.append(item)
            if len(out) >= MAX_TAGS:
                break
        return out

    # ------------------------------------------------------------------
    # references
    # ------------------------------------------------------------------
    def _reference_rows(self) -> List[HarnessEntry]:
        rows: List[HarnessEntry] = []
        for path in self._candidate_files(self.references_dir, REFERENCE_SUFFIXES, "reference"):
            if path.name.lower() in SKIPPED_REFERENCE_NAMES:
                continue
            text = self._read_text(path, "reference")
            if text is None:
                continue
            title, headings = self._outline(text)
            if not title and not headings:
                self._note(f"malformed reference {path.name}: no headings found")
                continue
            rows.append(self._reference_entry(path, title or path.stem, headings))
        return rows

    @staticmethod
    def _outline(text: str) -> Tuple[str, List[str]]:
        title = ""
        headings: List[str] = []
        for line in text.splitlines():
            match = _HEADING_RE.match(line)
            if not match:
                continue
            level, heading = len(match.group(1)), match.group(2).strip()
            if not heading:
                continue
            if level == 1 and not title:
                title = heading
                continue
            if len(headings) < MAX_HEADINGS and heading not in headings:
                headings.append(heading)
        return title, headings

    def _reference_entry(self, path: Path, title: str, headings: Sequence[str]) -> HarnessEntry:
        # Pointer only.  The body is never copied into an entry; callers ask
        # for it explicitly through load_reference().
        shown = [_clip(h, 120) for h in headings[:MAX_HEADINGS]]
        content = (
            f"Bundled reference document '{path.name}'. "
            + (f"Sections: {'; '.join(shown)}. " if shown else "")
            + f"Body not indexed: call load_reference('{path.name}', section=...) to read it."
        )
        stamp = _iso_mtime(path)
        return HarnessEntry(
            id=f"bundled-reference-{path.stem}",
            kind="fact",
            scope="shared",
            status="validated",
            title=_clip(title, MAX_TITLE),
            content=_clip(content, MAX_CONTENT),
            path=path.name,
            reference={"document": path.name, "sections": shown},
            metadata={"section_count": len(headings), "document": path.name},
            evidence=[],
            applicability={},
            conflicts=[],
            expires_at=None,
            source=f"bundled:reference:{path.name}",
            version=1,
            created_at=stamp,
            updated_at=stamp,
        )

    # ------------------------------------------------------------------
    # public surface
    # ------------------------------------------------------------------
    def rows(self) -> List[HarnessEntry]:
        """Return every bundled row: recipes first, then reference pointers."""
        if self._rows is None:
            rows = self._recipe_rows() + self._reference_rows()
            rows.sort(key=lambda e: (0 if e.kind == "procedure" else 1, e.id))
            self._rows = rows
        return [HarnessEntry.from_dict(entry.to_dict()) for entry in self._rows]

    def search(
        self,
        query: str,
        state: Optional[Mapping[str, Any]] = None,
        limit: int = 10,
        sanitizer: Optional[Sanitizer] = None,
    ) -> List[Tuple[str, HarnessEntry]]:
        """Rank bundled rows for ``query`` and an optional image ``state``.

        Returns ``(why, entry)`` pairs.  ``why`` is a short, deterministic
        explanation of the match.  Every returned string passes through
        ``sanitizer`` when one is given.
        """
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 0:
            raise ValueError("limit must be a non-negative int")
        terms = _tokens(query or "")
        state_map = dict(state or {})
        ranked = []
        for entry in self.rows():
            applies, app_score, reasons = self._applies(entry, state_map)
            if not applies:
                continue
            haystack = _tokens(" ".join((entry.title, entry.content, entry.path, entry.source)))
            overlap = sorted(terms & haystack)
            if terms and not overlap and app_score == 0:
                continue
            why_parts = []
            if overlap:
                why_parts.append("query terms: " + ", ".join(overlap[:6]))
            why_parts.extend(reasons)
            if not why_parts:
                why_parts.append("bundled knowledge")
            why = f"{entry.kind} match \u2014 " + "; ".join(why_parts)
            score = (len(overlap), app_score, 1 if entry.kind == "procedure" else 0)
            ranked.append((tuple(-v for v in score), entry.id, why, entry))
        ranked.sort(key=lambda item: (item[0], item[1]))
        out = []
        for _score, _id, why, entry in ranked[:limit]:
            out.append((self._clean_text(why, sanitizer), self._clean_entry(entry, sanitizer)))
        return out

    def _applies(self, entry: HarnessEntry, state: Mapping[str, Any]) -> Tuple[bool, int, List[str]]:
        rules = entry.applicability
        if not rules or not state:
            return True, 0, []
        score = 0
        reasons: List[str] = []
        domain = rules.get("domain")
        if domain and isinstance(state.get("domain"), str):
            if domain.casefold() != state["domain"].casefold():
                return False, 0, []
            score += 3
            reasons.append(f"domain {domain}")
        image_type = rules.get("image_type")
        if image_type and isinstance(state.get("image_type"), str):
            if state["image_type"] not in image_type:
                return False, 0, []
            score += 2
            reasons.append(f"image type {state['image_type']}")
        minimum = rules.get("min_channels")
        channels = state.get("channels")
        if isinstance(minimum, int) and isinstance(channels, int) and not isinstance(channels, bool):
            if channels < minimum:
                return False, 0, []
            score += 1
        for rule_key, state_key in (("needs_stack", "is_stack"), ("needs_time", "is_time"),
                                    ("needs_calibration", "is_calibrated")):
            required = rules.get(rule_key)
            available = state.get(state_key)
            if required is True and isinstance(available, bool):
                if not available:
                    return False, 0, []
                score += 1
        tags = rules.get("tags") or []
        state_tags = state.get("tags")
        if isinstance(state_tags, (list, tuple)) and tags:
            shared = sorted({t for t in state_tags if isinstance(t, str)} & set(tags))
            if shared:
                score += min(len(shared), 3)
                reasons.append("tags " + ", ".join(shared[:3]))
        return True, score, reasons

    def load_reference(self, name: str, section: Optional[str] = None, max_chars: int = 8_000) -> str:
        """Return reference text, bounded.  Empty string when unavailable."""
        if not isinstance(max_chars, int) or isinstance(max_chars, bool) or max_chars < 1:
            raise ValueError("max_chars must be a positive int")
        max_chars = min(max_chars, MAX_LOAD_CHARS)
        path = self._resolve_reference(name)
        if path is None:
            return ""
        text = self._read_text(path, "reference")
        if text is None:
            return ""
        if section is not None:
            text = self._section(text, section, path.name)
            if not text:
                return ""
        return self._bound(text, max_chars)

    def _resolve_reference(self, name: Any) -> Optional[Path]:
        if self.references_dir is None:
            self._note("reference request ignored: no references folder")
            return None
        if not isinstance(name, str) or not name.strip():
            self._note("reference request ignored: empty name")
            return None
        candidate = name.strip()
        if "/" in candidate or "\\" in candidate or candidate.startswith(".") or ":" in candidate:
            self._note(f"unsafe reference name refused: {candidate}")
            return None
        stem = candidate[:-3] if candidate.lower().endswith(".md") else candidate
        filename = f"{stem}.md"
        if filename.lower() in FORBIDDEN_NAMES or candidate.lower() in FORBIDDEN_NAMES:
            self._note(f"private file refused: {candidate}")
            return None
        path = self.references_dir / filename
        try:
            resolved = path.resolve()
            root = self.references_dir.resolve()
            if root != resolved.parent:
                self._note(f"reference outside folder refused: {candidate}")
                return None
            if not resolved.is_file():
                self._note(f"reference not found: {filename}")
                return None
        except OSError as exc:
            self._note(f"reference unreadable {filename}: {exc.__class__.__name__}")
            return None
        return path

    def _section(self, text: str, section: Any, filename: str) -> str:
        if not isinstance(section, str) or not section.strip():
            self._note(f"empty section requested in {filename}")
            return ""
        wanted = section.strip().casefold()
        lines = text.splitlines()
        start = None
        level = 6
        for index, line in enumerate(lines):
            match = _HEADING_RE.match(line)
            if not match:
                continue
            heading = match.group(2).strip().casefold()
            if heading == wanted or heading.startswith(wanted) or wanted in heading:
                start, level = index, len(match.group(1))
                break
        if start is None:
            self._note(f"section not found in {filename}: {_clip(section, 80)}")
            return ""
        end = len(lines)
        for index in range(start + 1, len(lines)):
            match = _HEADING_RE.match(lines[index])
            if match and len(match.group(1)) <= level:
                end = index
                break
        return "\n".join(lines[start:end]).strip()

    @staticmethod
    def _bound(text: str, max_chars: int) -> str:
        if len(text) <= max_chars:
            return text
        marker = "\n[truncated]"
        if max_chars <= len(marker):
            return text[:max_chars]
        return text[: max_chars - len(marker)].rstrip() + marker

    # ------------------------------------------------------------------
    # confirmed macro fixes
    # ------------------------------------------------------------------
    def fixes(
        self,
        error_code: Optional[str],
        error_fragment: Optional[str],
        macro_prefix: Optional[str],
        limit: int = 3,
    ) -> List[HarnessEntry]:
        """Return agent-confirmed macro fixes for a failure.

        Status is ``session_confirmed``, never ``validated``: a fix is software
        evidence that a macro stopped failing, not reviewed science.
        Contradicted entries (``confirmationsFalse >= confirmationsTrue``) are
        dropped.
        """
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 0:
            raise ValueError("limit must be a non-negative int")
        records = self._ledger_records(error_code, error_fragment, macro_prefix)
        stamp = _iso_mtime(self.ledger_path) if self.ledger_path else EPOCH
        scored = []
        for fingerprint, record in records:
            entry = self._fix_entry(fingerprint, record, stamp)
            if entry is None:
                continue
            true_count = int(record.get("confirmationsTrue") or 0)
            times = int(record.get("timesSeen") or 0)
            scored.append(((-true_count, -times, entry.id), entry))
        scored.sort(key=lambda item: item[0])
        return [entry for _score, entry in scored[:limit]]

    def _ledger_records(
        self,
        error_code: Optional[str],
        error_fragment: Optional[str],
        macro_prefix: Optional[str],
    ) -> List[Tuple[str, Mapping[str, Any]]]:
        if self.ledger_lookup is not None:
            try:
                try:
                    raw = self.ledger_lookup(
                        error_code=error_code,
                        error_fragment=error_fragment,
                        macro_prefix=macro_prefix,
                    )
                except TypeError:
                    raw = self.ledger_lookup(error_code, error_fragment, macro_prefix)
            except Exception as exc:
                self._note(f"ledger lookup failed: {exc.__class__.__name__}")
                return []
            return self._normalise_records(raw, matched=True,
                                           keys=(error_code, error_fragment, macro_prefix))
        if self.ledger_path is None:
            return []
        text = self._read_text(self.ledger_path, "ledger")
        if text is None:
            return []
        try:
            data = json.loads(text)
        except (ValueError, TypeError) as exc:
            self._note(f"malformed ledger: {exc.__class__.__name__}")
            return []
        if not isinstance(data, Mapping):
            self._note("malformed ledger: not a mapping")
            return []
        if data.get("version") not in (1, "1", None):
            self._note(f"unsupported ledger version: {data.get('version')!r}")
            return []
        entries = data.get("entries")
        if not isinstance(entries, Mapping):
            self._note("malformed ledger: entries is not a mapping")
            return []
        return self._normalise_records(entries, matched=False,
                                       keys=(error_code, error_fragment, macro_prefix))

    def _normalise_records(self, raw: Any, *, matched: bool, keys) -> List[Tuple[str, Mapping[str, Any]]]:
        pairs: List[Tuple[str, Mapping[str, Any]]] = []
        if isinstance(raw, Mapping):
            items = list(raw.items())
        elif isinstance(raw, (list, tuple)):
            items = [(None, value) for value in raw]
        elif raw is None:
            return []
        else:
            self._note("malformed ledger records: unsupported shape")
            return []
        for fingerprint, record in items:
            if not isinstance(record, Mapping):
                self._note("malformed ledger entry skipped: not a mapping")
                continue
            if fingerprint is None:
                fingerprint = record.get("fingerprint") or record.get("id") or ""
            if not isinstance(fingerprint, str) or not fingerprint.strip():
                self._note("malformed ledger entry skipped: missing fingerprint")
                continue
            if not matched and not self._matches(record, keys):
                continue
            pairs.append((fingerprint.strip(), record))
        pairs.sort(key=lambda item: item[0])
        return pairs

    @staticmethod
    def _matches(record: Mapping[str, Any], keys) -> bool:
        error_code, error_fragment, macro_prefix = keys
        code = record.get("errorCode")
        if error_code and isinstance(code, str) and code.casefold() != str(error_code).casefold():
            return False
        fragment = record.get("errorFragment")
        if error_fragment and isinstance(fragment, str) and fragment:
            a, b = fragment.casefold(), str(error_fragment).casefold()
            if a not in b and b not in a:
                return False
        prefix = record.get("macroPrefix")
        if macro_prefix and isinstance(prefix, str) and prefix:
            a, b = prefix.casefold(), str(macro_prefix).casefold()
            if not a.startswith(b) and not b.startswith(a):
                return False
        return True

    def _fix_entry(self, fingerprint: str, record: Mapping[str, Any], stamp: str) -> Optional[HarnessEntry]:
        fix = record.get("confirmedFix")
        if not isinstance(fix, str) or not fix.strip():
            self._note(f"ledger entry skipped, no confirmed fix: {fingerprint}")
            return None
        counts = {}
        for key in ("timesSeen", "confirmationsTrue", "confirmationsFalse"):
            value = record.get(key, 0)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                self._note(f"ledger entry skipped, bad {key}: {fingerprint}")
                return None
            counts[key] = value
        if counts["confirmationsFalse"] >= counts["confirmationsTrue"]:
            # Contradicted at least as often as confirmed: not usable advice.
            return None
        code = record.get("errorCode") if isinstance(record.get("errorCode"), str) else ""
        fragment = record.get("errorFragment") if isinstance(record.get("errorFragment"), str) else ""
        prefix = record.get("macroPrefix") if isinstance(record.get("macroPrefix"), str) else ""
        example = record.get("exampleMacro") if isinstance(record.get("exampleMacro"), str) else ""
        title = f"Confirmed fix for {code or 'macro error'}"
        if fragment:
            title += f": {_clip(fragment, 80)}"
        content = (
            f"{_clip(fix, 900)} Seen {counts['timesSeen']} time(s); confirmed "
            f"{counts['confirmationsTrue']}, contradicted {counts['confirmationsFalse']}. "
            f"{LEDGER_HONESTY}"
        )
        metadata: Dict[str, Any] = dict(counts)
        metadata.update({
            "fingerprint": fingerprint,
            "errorCode": code,
            "errorFragment": _clip(fragment, 200),
            "macroPrefix": _clip(prefix, 200),
        })
        if example:
            metadata["exampleMacro"] = _clip(example, 400)
        return HarnessEntry(
            id=f"ledger-fix-{fingerprint}",
            kind="failure_fix",
            scope="shared",
            status="session_confirmed",
            title=_clip(title, MAX_TITLE),
            content=_clip(content, MAX_CONTENT),
            path="",
            reference={"fingerprint": fingerprint},
            metadata=metadata,
            evidence=[],
            applicability={},
            conflicts=[],
            expires_at=None,
            source=f"ledger:fix:{fingerprint}",
            version=1,
            created_at=stamp,
            updated_at=stamp,
        )

    # ------------------------------------------------------------------
    # sanitising
    # ------------------------------------------------------------------
    @staticmethod
    def _clean_text(value: str, sanitizer: Optional[Sanitizer]) -> str:
        if sanitizer is None:
            return value
        cleaned = sanitizer(value)
        if not isinstance(cleaned, str):
            raise ValueError("sanitizer must return str")
        return cleaned

    def _clean_entry(self, entry: HarnessEntry, sanitizer: Optional[Sanitizer]) -> HarnessEntry:
        if sanitizer is None:
            return entry

        def walk(value):
            if isinstance(value, str):
                return self._clean_text(value, sanitizer)
            if isinstance(value, Mapping):
                # Field names stay untouched; only the values are sanitised.
                return {k: walk(v) for k, v in value.items()}
            if isinstance(value, list):
                return [walk(v) for v in value]
            return value

        payload = entry.to_dict()
        return HarnessEntry.from_dict({k: walk(v) for k, v in payload.items()})


__all__ = ["KnowledgeIndex", "LEDGER_HONESTY", "FORBIDDEN_NAMES"]
