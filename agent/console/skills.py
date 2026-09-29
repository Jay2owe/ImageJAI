"""Safe, progressive discovery of local ImageJAI markdown skills.

A skill is guidance stored in ``<skill-name>/SKILL.md``.  Discovery reads only
small YAML frontmatter.  The markdown body is read only by :meth:`load_body`.
Skills never install or execute code; a recipe named by a skill is metadata
that tells the model what it may choose to run through the separate recipe
runner.
"""
from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping
from xml.sax.saxutils import escape as _xml_escape

import yaml

MAX_SKILL_BYTES = 256 * 1024
MAX_BODY_BYTES = 128 * 1024
MAX_DESCRIPTION_CHARS = 1_024
MAX_SKILLS = 128
MAX_ENTRIES_PER_ROOT = 512
MAX_CATALOG_CHARS = 24_000
MAX_RECIPE_CHARS = 128
_NAME_RE = re.compile(r"[a-z0-9-]{1,64}\Z")
_RECIPE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")


class SkillError(Exception):
    """Base error for an on-demand skill load."""


class SkillLoadError(SkillError):
    """The discovered source is no longer safe or valid."""


@dataclass(frozen=True)
class SkillDiagnostic:
    """A rejected skill, collision, or discovery safety limit."""

    code: str
    message: str
    path: Path | None = None
    severity: str = "warning"

    def __str__(self) -> str:
        where = f" ({self.path})" if self.path is not None else ""
        return f"{self.severity}: {self.code}: {self.message}{where}"


@dataclass(frozen=True)
class SkillMetadata:
    """The only skill information suitable for eager prompt injection."""

    name: str
    description: str
    path: Path
    source: str
    disable_model_invocation: bool = False
    recipe: str | None = None

    @property
    def location(self) -> Path:
        """Prime-compatible name for the exact ``SKILL.md`` source."""
        return self.path


@dataclass(frozen=True)
class _Source:
    root: Path
    label: str


@dataclass(frozen=True)
class _Accepted:
    metadata: SkillMetadata
    source_root: Path
    discovered_sha256: str


class _UniqueKeyLoader(yaml.SafeLoader):
    pass


def _construct_mapping(loader: yaml.SafeLoader, node: yaml.nodes.MappingNode,
                       deep: bool = False) -> dict[object, object]:
    result: dict[object, object] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in result
        except TypeError as exc:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping", node.start_mark,
                "found an unhashable key", key_node.start_mark,
            ) from exc
        if duplicate:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping", node.start_mark,
                f"found duplicate key {key!r}", key_node.start_mark,
            )
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


_UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping
)


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _bounded_read(path: Path, limit: int) -> bytes:
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise SkillLoadError(f"cannot stat source: {exc}") from exc
    if size > limit:
        raise SkillLoadError(f"source exceeds {limit} bytes")
    try:
        with path.open("rb") as stream:
            data = stream.read(limit + 1)
    except OSError as exc:
        raise SkillLoadError(f"cannot read source: {exc}") from exc
    if len(data) > limit:
        raise SkillLoadError(f"source exceeds {limit} bytes")
    return data


def _split_frontmatter(data: bytes) -> tuple[Mapping[object, object], str]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise SkillLoadError("SKILL.md must be UTF-8") from exc
    # A BOM or prose before the delimiter is deliberately not accepted.
    lines = text.splitlines(keepends=True)
    if not lines or lines[0].rstrip("\r\n") != "---":
        raise SkillLoadError("SKILL.md must start with YAML frontmatter ('---')")
    closing = None
    for index in range(1, len(lines)):
        if lines[index].rstrip("\r\n") in {"---", "..."}:
            closing = index
            break
    if closing is None:
        raise SkillLoadError("YAML frontmatter has no closing delimiter")
    raw_yaml = "".join(lines[1:closing])
    try:
        value = yaml.load(raw_yaml, Loader=_UniqueKeyLoader)
    except yaml.YAMLError as exc:
        raise SkillLoadError(f"invalid YAML frontmatter: {exc}") from exc
    if not isinstance(value, Mapping):
        raise SkillLoadError("YAML frontmatter must be a mapping")
    return value, "".join(lines[closing + 1 :])


def _required_text(frontmatter: Mapping[object, object], key: str) -> str:
    value = frontmatter.get(key)
    if not isinstance(value, str) or not value.strip():
        raise SkillLoadError(f"frontmatter field {key!r} must be a non-empty string")
    return value.strip()


def _normalise_description(value: str, maximum: int) -> str:
    if any(ord(char) < 32 and char not in "\r\n\t" for char in value):
        raise SkillLoadError("description contains control characters")
    description = " ".join(value.split())
    if len(description) > maximum:
        raise SkillLoadError(f"description exceeds {maximum} characters")
    return description


def _recipe_reference(frontmatter: Mapping[object, object]) -> str | None:
    # Accept the small ImageJAI extension in either conventional metadata.recipe
    # or top-level recipe form.  No other metadata key has any effect.
    value: object = frontmatter.get("recipe")
    metadata = frontmatter.get("metadata")
    if value is None and isinstance(metadata, Mapping):
        value = metadata.get("recipe")
    if value is None:
        return None
    if not isinstance(value, str) or not _RECIPE_RE.fullmatch(value.strip()):
        raise SkillLoadError(
            "recipe reference must be a simple recipe id (letters, digits, '.', '_' or '-')"
        )
    return value.strip()


def _parse_metadata(data: bytes, path: Path, source: str,
                    max_description_chars: int) -> tuple[SkillMetadata, str]:
    frontmatter, body = _split_frontmatter(data)
    name = _required_text(frontmatter, "name")
    if not _NAME_RE.fullmatch(name):
        raise SkillLoadError("name must be 1-64 lowercase a-z, 0-9 or '-' characters")
    if name != path.parent.name:
        raise SkillLoadError(
            f"name {name!r} does not match directory {path.parent.name!r}"
        )
    description = _normalise_description(
        _required_text(frontmatter, "description"), max_description_chars
    )
    disabled = frontmatter.get("disable-model-invocation", False)
    if not isinstance(disabled, bool):
        raise SkillLoadError("disable-model-invocation must be a YAML boolean")
    return SkillMetadata(
        name=name,
        description=description,
        path=path,
        source=source,
        disable_model_invocation=disabled,
        recipe=_recipe_reference(frontmatter),
    ), body


class SkillCatalog:
    """A discovered, read-only catalogue of markdown guidance skills."""

    def __init__(self, accepted: Mapping[str, _Accepted],
                 diagnostics: Iterable[SkillDiagnostic], *,
                 max_skill_bytes: int = MAX_SKILL_BYTES,
                 max_body_bytes: int = MAX_BODY_BYTES,
                 max_description_chars: int = MAX_DESCRIPTION_CHARS,
                 max_catalog_chars: int = MAX_CATALOG_CHARS):
        self._accepted = dict(accepted)
        self.diagnostics = list(diagnostics)
        self.max_skill_bytes = max_skill_bytes
        self.max_body_bytes = max_body_bytes
        self.max_description_chars = max_description_chars
        self.max_catalog_chars = max_catalog_chars

    @property
    def skills(self) -> tuple[SkillMetadata, ...]:
        return tuple(item.metadata for item in self._accepted.values())

    def __len__(self) -> int:
        return len(self._accepted)

    def __iter__(self):
        return iter(self.skills)

    def get(self, name: str) -> SkillMetadata | None:
        item = self._accepted.get(name)
        return item.metadata if item else None

    def search(self, query: str, *, limit: int = 20) -> list[SkillMetadata]:
        """Search only metadata; skill bodies remain unopened."""
        if not isinstance(query, str):
            raise TypeError("query must be a string")
        if limit < 0 or limit > MAX_SKILLS:
            raise ValueError(f"limit must be between 0 and {MAX_SKILLS}")
        needle = query.strip().casefold()
        if not needle or limit == 0:
            return []
        terms = needle.split()
        ranked: list[tuple[int, str, SkillMetadata]] = []
        for skill in self.skills:
            haystack = " ".join(filter(None, (
                skill.name, skill.description, skill.recipe or ""
            ))).casefold()
            if not all(term in haystack for term in terms):
                continue
            score = 0 if skill.name == needle else 1 if skill.name.startswith(needle) else 2
            ranked.append((score, skill.name, skill))
        ranked.sort(key=lambda row: (row[0], row[1]))
        return [row[2] for row in ranked[:limit]]

    def prompt_catalog(self, *, max_chars: int | None = None,
                       include_locations: bool = False) -> str:
        """Return bounded prompt metadata, never a ``SKILL.md`` body.

        Absolute locations are hidden by default so a protected cloud prompt
        does not leak the user's filesystem. Hosts still have ``metadata.path``.
        """
        bound = self.max_catalog_chars if max_chars is None else max_chars
        if bound < 64:
            raise ValueError("max_chars must be at least 64")
        start = "<available_skills>\n"
        end = "</available_skills>"
        chunks: list[str] = []
        omitted = 0
        for skill in self.skills:
            recipe = ""
            if skill.recipe:
                recipe = (
                    f"\n    <recipe>{_xml_escape(skill.recipe)} (reference only; "
                    "guidance does not execute recipes)</recipe>"
                )
            disabled = "true" if skill.disable_model_invocation else "false"
            chunk = (
                "  <skill kind=\"guidance\">\n"
                f"    <name>{_xml_escape(skill.name)}</name>\n"
                f"    <description>{_xml_escape(skill.description)}</description>\n"
                f"    <disable-model-invocation>{disabled}</disable-model-invocation>"
                f"{recipe}\n"
                f"    <location>{_xml_escape(str(skill.path) if include_locations else 'skill:' + skill.name)}</location>\n"
                "  </skill>\n"
            )
            # Reserve the closing tag and a compact omission marker.
            marker = f"  <omitted>{omitted + 1}</omitted>\n"
            if len(start) + sum(map(len, chunks)) + len(chunk) + len(end) > bound:
                omitted += 1
            else:
                chunks.append(chunk)
        if omitted:
            marker = f"  <omitted>{omitted}</omitted>\n"
            while chunks and len(start) + sum(map(len, chunks)) + len(marker) + len(end) > bound:
                chunks.pop()
                omitted += 1
                marker = f"  <omitted>{omitted}</omitted>\n"
            if len(start) + len(marker) + len(end) <= bound:
                chunks.append(marker)
        result = start + "".join(chunks) + end
        # Defensive assertion: never truncate YAML-derived text into the prompt.
        if len(result) > bound:
            raise ValueError("max_chars is too small for catalogue framing")
        return result

    def load_body(self, name: str, *, model_invoked: bool = True) -> str:
        """Load one stripped markdown body from its exact discovered source.

        ``model_invoked=False`` represents an explicit user/host request.  It
        may load a skill marked ``disable-model-invocation``; autonomous model
        loading may not.  A recipe reference remains prose metadata and is not
        run here.
        """
        if not isinstance(name, str) or not _NAME_RE.fullmatch(name):
            raise KeyError(name)
        accepted = self._accepted.get(name)
        if accepted is None:
            raise KeyError(name)
        metadata = accepted.metadata
        if model_invoked and metadata.disable_model_invocation:
            raise PermissionError(
                f"skill {name!r} disables model invocation; an explicit user load is required"
            )
        try:
            resolved_root = accepted.source_root.resolve(strict=True)
            resolved_path = metadata.path.resolve(strict=True)
        except OSError as exc:
            raise SkillLoadError(f"skill source no longer exists: {exc}") from exc
        if not _within(resolved_path, resolved_root) or not resolved_path.is_file():
            raise SkillLoadError("skill source escapes its discovery root")
        data = _bounded_read(resolved_path, self.max_skill_bytes)
        current, body = _parse_metadata(
            data, metadata.path, metadata.source, self.max_description_chars
        )
        # Do not silently load a different skill after a file replacement.
        if current != metadata:
            raise SkillLoadError("skill frontmatter changed after discovery; rediscover skills")
        stripped = body.strip()
        if len(stripped.encode("utf-8")) > self.max_body_bytes:
            raise SkillLoadError(f"skill body exceeds {self.max_body_bytes} bytes")
        return stripped


def _repository_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return current


def _source_roots(project_root: Path, repo_root: Path,
                  user_skills_dir: Path | None,
                  bundled_skills_dir: Path | None) -> list[_Source]:
    project = project_root.resolve()
    repo = repo_root.resolve()
    if not _within(project, repo):
        raise ValueError("project_root must be repo_root or one of its descendants")
    result = [_Source(project / ".imagejai" / "skills", "project")]
    current = project
    while True:
        result.append(_Source(current / ".agents" / "skills", f"agents:{current}"))
        if current == repo:
            break
        current = current.parent
    if user_skills_dir is not None:
        result.append(_Source(Path(user_skills_dir), "user"))
    if bundled_skills_dir is not None:
        result.append(_Source(Path(bundled_skills_dir), "bundled"))
    return result


def discover_skills(project_root: str | os.PathLike[str], *,
                    repo_root: str | os.PathLike[str] | None = None,
                    user_skills_dir: str | os.PathLike[str] | None = None,
                    bundled_skills_dir: str | os.PathLike[str] | None = None,
                    max_skill_bytes: int = MAX_SKILL_BYTES,
                    max_body_bytes: int = MAX_BODY_BYTES,
                    max_description_chars: int = MAX_DESCRIPTION_CHARS,
                    max_skills: int = MAX_SKILLS,
                    max_entries_per_root: int = MAX_ENTRIES_PER_ROOT,
                    max_catalog_chars: int = MAX_CATALOG_CHARS) -> SkillCatalog:
    """Discover skills in precedence order and return a progressive catalogue.

    Paths for user and bundled skills are injected deliberately.  This function
    never reads ``HOME``, installs a package, imports skill code, or executes a
    referenced recipe.
    """
    if min(max_skill_bytes, max_body_bytes, max_description_chars, max_skills,
           max_entries_per_root, max_catalog_chars) <= 0:
        raise ValueError("all discovery bounds must be positive")
    project = Path(project_root)
    repo = Path(repo_root) if repo_root is not None else _repository_root(project)
    sources = _source_roots(
        project, repo,
        Path(user_skills_dir) if user_skills_dir is not None else None,
        Path(bundled_skills_dir) if bundled_skills_dir is not None else None,
    )
    accepted: dict[str, _Accepted] = {}
    diagnostics: list[SkillDiagnostic] = []
    full_reported = False

    for source in sources:
        root = source.root
        if not root.exists():
            continue
        if not root.is_dir():
            diagnostics.append(SkillDiagnostic(
                "invalid-root", "skill root is not a directory", root, "error"
            ))
            continue
        try:
            entries: list[Path] = []
            with os.scandir(root) as scan:
                for entry in scan:
                    entries.append(Path(entry.path))
                    if len(entries) > max_entries_per_root:
                        break
        except OSError as exc:
            diagnostics.append(SkillDiagnostic(
                "unreadable-root", f"cannot enumerate skill root: {exc}", root, "error"
            ))
            continue
        if len(entries) > max_entries_per_root:
            diagnostics.append(SkillDiagnostic(
                "root-count-limit",
                f"skill root has more than {max_entries_per_root} entries; root skipped",
                root, "error",
            ))
            continue
        for directory in sorted(entries, key=lambda item: item.name):
            try:
                if not directory.is_dir():
                    continue
            except OSError:
                continue
            skill_file = directory / "SKILL.md"
            if not skill_file.exists():
                continue
            try:
                resolved_root = root.resolve(strict=True)
                resolved_file = skill_file.resolve(strict=True)
                if not _within(resolved_file, resolved_root) or not resolved_file.is_file():
                    raise SkillLoadError("SKILL.md escapes its discovery root")
                data = _bounded_read(resolved_file, max_skill_bytes)
                metadata, _body = _parse_metadata(
                    data, skill_file, source.label, max_description_chars
                )
            except (OSError, SkillLoadError) as exc:
                diagnostics.append(SkillDiagnostic(
                    "invalid-skill", str(exc), skill_file, "error"
                ))
                continue
            winner = accepted.get(metadata.name)
            if winner is not None:
                diagnostics.append(SkillDiagnostic(
                    "collision",
                    f"skill {metadata.name!r} ignored; first source wins "
                    f"({winner.metadata.path})",
                    skill_file,
                ))
                continue
            if len(accepted) >= max_skills:
                if not full_reported:
                    diagnostics.append(SkillDiagnostic(
                        "skill-count-limit",
                        f"at most {max_skills} skills are accepted; remaining skills ignored",
                        skill_file, "error",
                    ))
                    full_reported = True
                continue
            accepted[metadata.name] = _Accepted(
                metadata, resolved_root, hashlib.sha256(data).hexdigest()
            )

    return SkillCatalog(
        accepted, diagnostics,
        max_skill_bytes=max_skill_bytes,
        max_body_bytes=max_body_bytes,
        max_description_chars=max_description_chars,
        max_catalog_chars=max_catalog_chars,
    )


# Friendly alternative for callers that think of discovery as a loader.
SkillLoader = SkillCatalog

__all__ = [
    "MAX_BODY_BYTES", "MAX_CATALOG_CHARS", "MAX_DESCRIPTION_CHARS",
    "MAX_ENTRIES_PER_ROOT", "MAX_SKILL_BYTES", "MAX_SKILLS",
    "SkillCatalog", "SkillDiagnostic", "SkillError", "SkillLoadError",
    "SkillLoader", "SkillMetadata", "discover_skills",
]
