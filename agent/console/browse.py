"""Pseudonymised file/series browser model for the console.

Port of ``src/main/java/imagejai/ui/BrowseFilesDialog.java``,
``engine/security/SeriesScanner.java``, ``engine/security/PathTokenMap.java``,
``engine/security/TagSuggestionEngine.java``, ``engine/security/Brief.java``
and ``engine/security/BriefNudger.java``.

Why this exists: the user must be able to pick files by their real names while
the agent only ever learns opaque tokens. The listing, the tag parsing, and the
per-folder rule file therefore all stay local, and only
:func:`build_brief` output is allowed to leave the machine.

Feature ids: S1.34 / S2.27 (Browse Files), S2.28 (per-folder tag rules),
S6.13 (token shapes), S6.31 (brief), S6.32 (tag suggestions), S6.33
(metadata-only scanning).
"""
from __future__ import annotations

import hashlib
import os
import re
import secrets
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

__all__ = [
    "PathTokenMap",
    "ResolvedTarget",
    "TagRule",
    "default_rules",
    "parse_label",
    "suggest_tag",
    "load_tag_rules",
    "SeriesEntry",
    "ScanError",
    "MetadataUnavailable",
    "scan_file",
    "scan_folder",
    "default_metadata_reader",
    "BROWSE_COLUMNS",
    "browse_rows",
    "filter_rows",
    "preview_text",
    "entry_display",
    "requires_pseudonym",
    "build_brief",
    "compose_nudge",
    "DELIVERY_CHOICES",
    "MentionCandidate",
    "mention_candidates",
    "mention_replacement",
]


# --------------------------------------------------------------------------
# S6.13 token map — PathTokenMap.java:26, :188, :209
# --------------------------------------------------------------------------

PATH_TOKEN_HEX_CHARS = 32
TEXT_TOKEN_HEX_CHARS = 24
MAX_PATH_TOKENS = 2048
MAX_SENSITIVE_TOKENS = 4096
MAX_PATH_CHARS = 4096
MAX_SENSITIVE_CHARS = 4096
MAX_SENSITIVE_TOTAL_CHARS = 262_144
MAX_PREFIX_CHARS = 32

TOKEN_PATTERN = re.compile(
    r"image-[0-9a-f]{4,32}(?:\.[A-Za-z0-9.]+)?(?::\d+)?", re.IGNORECASE
)
_PREFIX_BAD = re.compile(r"[^A-Za-z0-9_-]")


@dataclass(frozen=True)
class ResolvedTarget:
    """Real path (and 1-based series) a token points at."""

    path: Path
    series: int = -1


class PathTokenMap:
    """Process-local pseudonym map for image paths and short text values.

    Token shapes follow PathTokenMap: ``image-<32 hex><ext>`` for a path,
    ``<prefix>-<24 hex>`` for text, and ``<path token>:<series>`` for one
    series inside a container file. The salt is random per instance, so tokens
    never leak a path across runs, and nothing is ever written to disk.
    """

    def __init__(self, salt: bytes | None = None) -> None:
        self._salt = salt if salt is not None else secrets.token_bytes(32)
        self._path_to_token: dict[str, str] = {}
        self._token_to_path: dict[str, Path] = {}
        self._sensitive_to_token: dict[str, str] = {}
        self._token_to_sensitive: dict[str, str] = {}
        self._sensitive_chars = 0
        self.rejected = 0

    # ---- minting ----

    def token_for_path(self, path: str | Path) -> str:
        if path is None:
            raise ValueError("path is required")
        raw = str(path)
        normalised = self._normalise(Path(raw))
        key = str(normalised)
        self._check_path_length(key)
        self._check_path_length(raw)

        aliases = [key]
        if raw and raw != key:
            aliases.append(raw)
        self._check_sensitive_capacity(aliases)

        token = self._path_to_token.get(key)
        if token is None:
            if len(self._path_to_token) >= MAX_PATH_TOKENS:
                self._reject(f"Path token capacity reached (max {MAX_PATH_TOKENS})")
            token = self._unique_path_token(key, normalised.suffix)
            self._path_to_token[key] = token
            self._token_to_path[token] = normalised
        for alias in aliases:
            self._register_sensitive(alias, token)
        return token

    def token_for_series(self, path: str | Path, series_index: int) -> str:
        return f"{self.token_for_path(path)}:{int(series_index)}"

    def token_for_sensitive_text(self, original: str, prefix: str = "value") -> str:
        if not original:
            return original
        existing = self._sensitive_to_token.get(original)
        if existing is not None:
            return existing
        self._check_sensitive_length(original)
        self._check_sensitive_capacity([original])
        cleaned = _PREFIX_BAD.sub("", prefix or "").lower() or "value"
        cleaned = cleaned[:MAX_PREFIX_CHARS]
        token = self._unique_text_token(cleaned, original)
        self._register_sensitive(original, token)
        return token

    # ---- reversing (local only) ----

    def resolve(self, token: str | None) -> ResolvedTarget | None:
        """Real target behind a token, or None when it is unknown.

        The series suffix is split off before the lookup so
        ``image-...lif:3`` resolves to the same file as ``image-...lif``.
        """
        if not token or not token.strip():
            return None
        trimmed = token.strip()
        series = -1
        base = trimmed
        colon = trimmed.rfind(":")
        if 0 < colon < len(trimmed) - 1:
            try:
                series = int(trimmed[colon + 1:])
                base = trimmed[:colon]
            except ValueError:
                series = -1
                base = trimmed
        path = self._token_to_path.get(base)
        if path is None:
            return None
        return ResolvedTarget(path=path, series=series)

    def adopt(self, token: str, path: "str | Path") -> str:
        """Record a token minted somewhere else, so this map can reverse it.

        The Fiji plugin mints the authoritative token (TCP command
        ``pseudonymise_paths``) because only the plugin can reverse one when
        the model sends it back inside a macro. The console still needs the
        pair locally to turn its own calls back into real paths.
        """
        clean_token = str(token or "").strip()
        if not clean_token:
            raise ValueError("token is required")
        raw = str(path)
        normalised = self._normalise(Path(raw))
        key = str(normalised)
        self._check_path_length(key)
        self._check_path_length(raw)
        aliases = [key] + ([raw] if raw and raw != key else [])
        self._check_sensitive_capacity(aliases)
        old_path = self._token_to_path.get(clean_token)
        if old_path is not None and old_path != normalised:
            raise ValueError("token is already mapped to another path")
        old_token = self._path_to_token.get(key)
        if old_token is not None and old_token != clean_token:
            raise ValueError("path is already mapped to another token")
        if old_token is None and len(self._path_to_token) >= MAX_PATH_TOKENS:
            self._reject(f"Path token capacity reached (max {MAX_PATH_TOKENS})")
        self._path_to_token[key] = clean_token
        self._token_to_path[clean_token] = normalised
        for alias in aliases:
            self._register_sensitive(alias, clean_token)
        return clean_token

    def mapping(self) -> dict[str, Path]:
        """Token -> path, for local reversal in the console UI only."""
        return dict(self._token_to_path)

    def redact(self, text: str) -> str:
        """Replace every known sensitive local value with its opaque token.

        Used before harness content enters a model prompt. Unknown prose is not
        guessed at; durable-memory proposals must still pass explicit review.
        """
        out = str(text or "")
        for original in sorted(self._sensitive_to_token, key=len, reverse=True):
            if original:
                out = out.replace(original, self._sensitive_to_token[original])
        return out

    def reverse(self, text: str) -> str:
        """Replace known tokens in text with real paths (longest token first).

        Needed when the agent sends a macro back that mentions a token
        (S6.15): the console reverses it before Fiji ever sees it.
        """
        out = text or ""
        for token in sorted(self._token_to_path, key=len, reverse=True):
            out = out.replace(token, str(self._token_to_path[token]))
        return out

    @property
    def path_entry_count(self) -> int:
        return len(self._path_to_token)

    @property
    def sensitive_entry_count(self) -> int:
        return len(self._sensitive_to_token)

    # ---- internals ----

    @staticmethod
    def _normalise(path: Path) -> Path:
        return path.resolve() if path.is_absolute() else Path(os.path.normpath(str(path)))

    def _hex_digest(self, value: str, chars: int) -> str:
        digest = hashlib.sha256(self._salt + value.encode("utf-8")).hexdigest()
        return digest[:chars]

    def _unique_path_token(self, key: str, extension: str) -> str:
        for attempt in range(1000):
            token = f"image-{self._hex_digest(f'{key}#{attempt}', PATH_TOKEN_HEX_CHARS)}{extension}"
            existing = self._token_to_path.get(token)
            if existing is None or str(existing) == key:
                return token
        raise RuntimeError("Could not allocate a unique path token")

    def _unique_text_token(self, prefix: str, original: str) -> str:
        for attempt in range(1000):
            token = f"{prefix}-{self._hex_digest(f'{original}#{attempt}', TEXT_TOKEN_HEX_CHARS)}"
            existing = self._token_to_sensitive.get(token)
            if existing is None or existing == original:
                return token
        raise RuntimeError("Could not allocate a unique sensitive token")

    def _check_path_length(self, value: str | None) -> None:
        if value is not None and len(value) > MAX_PATH_CHARS:
            self.rejected += 1
            raise ValueError(f"Path exceeds {MAX_PATH_CHARS} characters")

    def _check_sensitive_length(self, value: str | None) -> None:
        if value is not None and len(value) > MAX_SENSITIVE_CHARS:
            self.rejected += 1
            raise ValueError(f"Sensitive value exceeds {MAX_SENSITIVE_CHARS} characters")

    def _check_sensitive_capacity(self, candidates: Iterable[str]) -> None:
        additional_entries = 0
        additional_chars = 0
        seen: set[str] = set()
        for candidate in candidates:
            if not candidate or candidate in seen or candidate in self._sensitive_to_token:
                continue
            seen.add(candidate)
            self._check_sensitive_length(candidate)
            additional_entries += 1
            additional_chars += len(candidate)
        if (
            len(self._sensitive_to_token) + additional_entries > MAX_SENSITIVE_TOKENS
            or self._sensitive_chars + additional_chars > MAX_SENSITIVE_TOTAL_CHARS
        ):
            self._reject(
                f"Sensitive token capacity reached (max {MAX_SENSITIVE_TOKENS} entries / "
                f"{MAX_SENSITIVE_TOTAL_CHARS} characters)"
            )

    def _register_sensitive(self, original: str, token: str) -> None:
        if not original or original in self._sensitive_to_token:
            return
        self._sensitive_to_token[original] = token
        self._token_to_sensitive.setdefault(token, original)
        self._sensitive_chars += len(original)

    def _reject(self, message: str) -> None:
        self.rejected += 1
        raise RuntimeError(message)


# --------------------------------------------------------------------------
# S6.32 / S2.28 tag rules — TagSuggestionEngine.java:23, BrowseFilesDialog:411
# --------------------------------------------------------------------------

TAG_ORDER = ("timepoint", "genotype", "sex", "condition")
TAG_RULES_FILENAME = ".imagejai-tags.yml"
NO_TAG_FALLBACK = "selected series"

_FRIENDLY_GENOTYPE = {
    "wt": "wild-type",
    "ko": "knockout",
    "het": "heterozygous",
    "tg": "transgenic",
}
_FRIENDLY_SEX = {"m": "male", "f": "female"}
_MULTISPACE = re.compile(r"\s+")


@dataclass(frozen=True)
class TagRule:
    """One local regex rule that turns a label fragment into a tag value."""

    name: str
    pattern: str
    format: str = "{1}"
    _compiled: Any = field(default=None, compare=False, repr=False)

    @classmethod
    def create(cls, name: str, pattern: str, format: str = "") -> "TagRule":
        return cls(
            name=(name or "").strip().lower(),
            pattern=pattern,
            format=format or "",
            _compiled=re.compile(pattern),
        )

    def match(self, text: str):
        compiled = self._compiled or re.compile(self.pattern)
        return compiled.search(text or "")


# TagSuggestionEngine.buildDefaultRules (TagSuggestionEngine.java:152).
_DEFAULT_RULES = (
    TagRule.create(
        "timepoint",
        r"(?i)(?<![A-Za-z0-9])(\d+)\s*([wd])(?:k|eeks?|ays?)?(?![A-Za-z0-9])",
        "{1} {unit}",
    ),
    TagRule.create(
        "genotype", r"(?i)(?<![A-Za-z0-9])(wt|ko|het|cre|flox|tg)(?![A-Za-z0-9])", "{1}"
    ),
    TagRule.create(
        "sex", r"(?i)(?<![A-Za-z0-9])(male|female|m|f)(?![A-Za-z0-9])", "{1}"
    ),
    TagRule.create(
        "condition",
        r"(?i)(?<![A-Za-z0-9])(control|treated|vehicle|drug)(?![A-Za-z0-9])",
        "{1}",
    ),
)


def default_rules() -> list[TagRule]:
    return list(_DEFAULT_RULES)


def _usable(rules: Sequence[TagRule] | None) -> list[TagRule]:
    return list(rules) if rules else default_rules()


def _unit_for(match: "re.Match[str]") -> str:
    # TagSuggestionEngine.unitFor — a "d" anywhere in the match means days.
    return "days" if "d" in match.group(0).lower() else "weeks"


def _safe_group(match: "re.Match[str]", index: int) -> str:
    """Group text, or "" when a user rule has fewer groups than its format."""
    try:
        return match.group(index) or ""
    except (IndexError, re.error):
        return ""


def _format_value(rule: TagRule, match: "re.Match[str]") -> str:
    fmt = rule.format.strip() or "{1}"
    out = fmt
    for index in range(1, (match.re.groups or 0) + 1):
        out = out.replace(f"{{{index}}}", _safe_group(match, index))
    out = out.replace("{unit}", _unit_for(match))
    if rule.name == "timepoint" and fmt == "{1}":
        out = f"{_safe_group(match, 1)} {_unit_for(match)}"
    if rule.name == "genotype":
        value = out.strip().lower()
        out = _FRIENDLY_GENOTYPE.get(value, value)
    elif rule.name == "sex":
        value = out.strip().lower()
        out = _FRIENDLY_SEX.get(value, value)
    return _MULTISPACE.sub(" ", out).strip()


def parse_label(label: str | None, rules: Sequence[TagRule] | None = None) -> dict[str, str]:
    """Tags found in one label. Reads no files (TagSuggestionEngine.java:84)."""
    out: dict[str, str] = {}
    text = label or ""
    for rule in _usable(rules):
        match = rule.match(text)
        if match is None:
            continue
        value = _format_value(rule, match)
        if value:
            out[rule.name] = value
    return out


def suggest_tag(
    labels: Sequence[str] | None, rules: Sequence[TagRule] | None = None
) -> str:
    """Tag common to every selected label (TagSuggestionEngine.java:30).

    Values shared by the whole selection are joined in the fixed order
    timepoint, genotype, sex, condition; a selection that parses to nothing
    still gets the "selected series" fallback so the brief is never untagged.
    """
    if not labels:
        return ""
    usable = _usable(rules)
    common: dict[str, str] | None = None
    for label in labels:
        parsed = parse_label(label, usable)
        if common is None:
            common = dict(parsed)
            continue
        for key in list(common):
            other = parsed.get(key)
            if other is None or common[key].lower() != other.lower():
                del common[key]
    parts = [common[key].strip() for key in TAG_ORDER if (common or {}).get(key, "").strip()]
    if parts:
        return ", ".join(parts)
    return NO_TAG_FALLBACK


def load_tag_rules(
    folder: str | Path | None, loader: Callable[[Path], Any] | None = None
) -> list[TagRule]:
    """Read ``<folder>/.imagejai-tags.yml`` (BrowseFilesDialog.java:411).

    Accepts a top-level list or a mapping with a ``patterns`` key. Any missing
    file, parse failure, or empty rule list falls back to the defaults, because
    a broken override must not silently disable tagging.
    """
    if folder is None:
        return default_rules()
    override = Path(folder) / TAG_RULES_FILENAME
    if not override.is_file():
        return default_rules()
    try:
        if loader is not None:
            document = loader(override)
        else:
            import yaml

            document = yaml.safe_load(override.read_text(encoding="utf-8"))
        rules = _parse_tag_rule_document(document)
    except Exception:  # noqa: BLE001 - mirrors the Java catch-all
        return default_rules()
    return rules or default_rules()


def _parse_tag_rule_document(document: Any) -> list[TagRule]:
    entries = document
    if isinstance(document, Mapping):
        entries = document.get("patterns")
    if not isinstance(entries, (list, tuple)):
        return []
    rules: list[TagRule] = []
    for entry in entries:
        if not isinstance(entry, Mapping):
            continue
        name = str(entry.get("name") or "")
        pattern = str(entry.get("pattern") or "")
        fmt = str(entry.get("format") or "")
        if name and pattern:
            try:
                rules.append(TagRule.create(name, pattern, fmt))
            except re.error:
                # A single bad regex must not kill the whole rule file.
                continue
    return rules


# --------------------------------------------------------------------------
# S6.33 metadata-only scanning — SeriesScanner.java:22
# --------------------------------------------------------------------------

MAX_DIRECTORY_ENTRIES = 4096
MAX_SERIES_PER_FILE = 1024
MAX_FOLDER_SERIES = 16384
MAX_CACHE_ENTRIES = 256

SUPPORTED_IMAGE_SUFFIXES = (
    ".lif",
    ".czi",
    ".nd2",
    ".ome.tif",
    ".ome.tiff",
    ".tif",
    ".tiff",
)
CONTAINER_SUFFIXES = (".lif", ".czi", ".nd2")
_LABEL_KEYS = (
    "Image name",
    "Image Name",
    "Name",
    "Series name",
    "Series Name",
    "Title",
)


class ScanError(RuntimeError):
    """A folder or file could not be scanned; ``code`` matches the Java codes."""

    def __init__(self, code: str, path: str | Path | None, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.path = Path(path) if path else None


class MetadataUnavailable(RuntimeError):
    """No local reader can describe this format without decoding pixels.

    Raised instead of guessing: the row is shown with the reason in its Status
    column, exactly like a Bio-Formats failure in the Swing dialog.
    """


@dataclass(frozen=True)
class SeriesEntry:
    """One file or one series inside a container (SeriesScanner.SeriesInfo)."""

    file: Path
    series: int
    token: str
    label: str
    format: str = ""
    size_x: int = 0
    size_y: int = 0
    size_z: int = 0
    size_c: int = 0
    size_t: int = 0
    readable: bool = True
    error: str = ""

    def dimensions_label(self) -> str:
        """``WxH``, ``WxHxZ``, ``..., Nt`` (SeriesScanner.dimensionsLabel)."""
        if not self.readable:
            return "unreadable"
        parts = ""
        if self.size_x > 0 and self.size_y > 0:
            parts = f"{self.size_x}x{self.size_y}"
        if self.size_z > 1:
            parts += f"x{self.size_z}"
        if self.size_t > 1:
            parts = f"{parts}, {self.size_t}t" if parts else f"{self.size_t}t"
        return parts

    def safe_metadata(self) -> dict[str, Any]:
        """Only shape and format — never the path or the label."""
        return {
            "token": self.token,
            "series": self.series,
            "channels": self.size_c,
            "size_x": self.size_x,
            "size_y": self.size_y,
            "size_z": self.size_z,
            "size_t": self.size_t,
            "format": self.format,
        }

    def status(self) -> str:
        return "ready" if self.readable else self.error


def is_supported_image(path: str | Path | None) -> bool:
    name = Path(path).name.lower() if path else ""
    return name.endswith(SUPPORTED_IMAGE_SUFFIXES)


def is_container_format(path: str | Path | None) -> bool:
    name = Path(path).name.lower() if path else ""
    return name.endswith(CONTAINER_SUFFIXES)


def base_name(path: str | Path | None) -> str:
    """File name without extension, ``.ome.tif(f)`` aware."""
    name = Path(path).name if path else "image"
    lower = name.lower()
    for suffix in (".ome.tiff", ".ome.tif"):
        if lower.endswith(suffix):
            return name[: -len(suffix)]
    stem = name.rsplit(".", 1)
    return stem[0] if len(stem) == 2 and stem[0] else name


def label_for(path: Path, zero_based_series: int, metadata: Mapping[str, Any] | None) -> str:
    """Series label from local metadata (SeriesScanner.labelFor)."""
    if metadata:
        for key in _LABEL_KEYS:
            value = metadata.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
    if zero_based_series <= 0:
        return base_name(path)
    return f"{base_name(path)} :{zero_based_series + 1}"


def default_metadata_reader(path: Path) -> list[dict[str, Any]]:
    """Read image shape without decoding pixels.

    Pillow opens a TIFF lazily — the header and IFD chain are enough for size,
    depth, and frame count, and ``load()`` is never called. Container formats
    (.lif/.czi/.nd2) need Bio-Formats, which lives in the JVM, so they raise
    :class:`MetadataUnavailable` and the caller shows that in the Status
    column instead of inventing dimensions.
    """
    if is_container_format(path):
        raise MetadataUnavailable(
            f"no local metadata reader for {Path(path).suffix} (needs Bio-Formats)"
        )
    try:
        from PIL import Image
    except ImportError as exc:  # pragma: no cover - Pillow is a declared dep
        raise MetadataUnavailable("Pillow is not installed") from exc
    with Image.open(path) as image:
        frames = int(getattr(image, "n_frames", 1) or 1)
        channels = len(image.getbands() or ("L",))
        return [
            {
                "metadata": {},
                "format": (image.format or "").upper(),
                "size_x": int(image.width),
                "size_y": int(image.height),
                "size_z": frames,
                "size_c": channels,
                "size_t": 1,
            }
        ]


def scan_file(
    file: str | Path,
    *,
    token_map: PathTokenMap,
    reader: Callable[[Path], list[dict[str, Any]]] | None = None,
) -> list[SeriesEntry]:
    """Describe every series in one file, metadata only (SeriesScanner.scan)."""
    path = Path(file)
    if not is_supported_image(path):
        return []
    if not path.is_file():
        return []
    resolved = path.resolve()
    read = reader or default_metadata_reader
    try:
        raw = read(resolved) or []
        if len(raw) > MAX_SERIES_PER_FILE:
            raise RuntimeError(f"Series count exceeds safety cap of {MAX_SERIES_PER_FILE}.")
        count = max(1, len(raw))
        tokenise_as_series = count > 1 or is_container_format(resolved)
        out: list[SeriesEntry] = []
        for index, series in enumerate(raw):
            public_series = index + 1 if tokenise_as_series else -1
            token = (
                token_map.token_for_series(resolved, public_series)
                if public_series >= 0
                else token_map.token_for_path(resolved)
            )
            out.append(
                SeriesEntry(
                    file=resolved,
                    series=public_series,
                    token=token,
                    label=label_for(resolved, index, series.get("metadata")),
                    format=str(series.get("format") or ""),
                    size_x=max(0, int(series.get("size_x", 0) or 0)),
                    size_y=max(0, int(series.get("size_y", 0) or 0)),
                    size_z=max(0, int(series.get("size_z", 0) or 0)),
                    size_c=max(0, int(series.get("size_c", 0) or 0)),
                    size_t=max(0, int(series.get("size_t", 0) or 0)),
                    readable=True,
                )
            )
        return out
    except Exception as exc:  # noqa: BLE001 - an unreadable row, not a dead dialog
        message = str(exc) or type(exc).__name__
        return [
            SeriesEntry(
                file=resolved,
                series=-1,
                token=token_map.token_for_path(resolved),
                label=base_name(resolved),
                readable=False,
                error=message,
            )
        ]


def scan_folder(
    folder: str | Path | None,
    *,
    token_map: PathTokenMap,
    reader: Callable[[Path], list[dict[str, Any]]] | None = None,
) -> list[SeriesEntry]:
    """Scan one folder, sorted, capped, pixels untouched.

    Caps come straight from SeriesScanner: 4096 directory entries and 16384
    series per folder, so a mistyped path cannot hang the console.
    """
    if folder is None:
        return []
    path = Path(folder)
    try:
        if not path.exists():
            return []
        if not path.is_dir():
            raise ScanError("not_a_folder", path, "Image folder is not a directory.")
    except OSError as exc:
        raise ScanError(
            "folder_unreadable", path, f"Could not inspect image folder: {exc}"
        ) from exc

    files: list[Path] = []
    entries = 0
    try:
        for candidate in sorted(path.iterdir()):
            entries += 1
            if entries > MAX_DIRECTORY_ENTRIES:
                raise ScanError(
                    "directory_entry_cap",
                    path,
                    f"Folder contains more than {MAX_DIRECTORY_ENTRIES} entries.",
                )
            if candidate.is_file() and is_supported_image(candidate):
                files.append(candidate)
    except OSError as exc:
        raise ScanError(
            "folder_unreadable", path, f"Could not read image folder: {exc}"
        ) from exc

    out: list[SeriesEntry] = []
    for file in sorted(files):
        scanned = scan_file(file, token_map=token_map, reader=reader)
        if len(scanned) > MAX_FOLDER_SERIES - len(out):
            raise ScanError(
                "folder_series_cap",
                path,
                f"Folder contains more than {MAX_FOLDER_SERIES} image series.",
            )
        out.extend(scanned)
    return out


# --------------------------------------------------------------------------
# S2.27 dialog model
# --------------------------------------------------------------------------

BROWSE_COLUMNS = (
    "Token",
    "Label",
    "Timepoint",
    "Genotype",
    "Sex",
    "Condition",
    "Channels",
    "Dimensions",
    "Status",
)
DELIVERY_CHOICES = ("Embedded terminal", "Clipboard", "TCP polling")
DEFAULT_DELIVERY = "Clipboard"
NO_SELECTION_PREVIEW = "No selection."
SELECT_SOMETHING = "Select at least one file or series."
SELECTION_READY_TOAST = "Selection ready for the agent."
CLIPBOARD_TOAST = "Selection ready - Ctrl+V into your agent terminal."
MIXED_DIMENSIONS = "mixed dimensions"

PSEUDONYMISING_POSTURES = ("PSEUDONYMISED", "ON_PREMISES")


def requires_pseudonym(posture: str | None) -> bool:
    """True when outbound names must be tokens (PseudonymisationFilter.apply).

    STANDARD is an explicit passthrough posture (S6.12); the other two
    tokenise.
    """
    return str(posture or "PSEUDONYMISED").strip().upper().replace("-", "_") in (
        PSEUDONYMISING_POSTURES
    )


def entry_display(entry: SeriesEntry, posture: str | None = "STANDARD") -> str:
    """Name the agent is allowed to see for this row."""
    return entry.token if requires_pseudonym(posture) else entry.label


def browse_rows(
    entries: Sequence[SeriesEntry], rules: Sequence[TagRule] | None = None
) -> list[dict[str, Any]]:
    """Table rows for the dialog; labels and tags stay local (S2.27)."""
    usable = _usable(rules)
    rows: list[dict[str, Any]] = []
    for entry in entries or ():
        tags = parse_label(entry.label, usable)
        rows.append(
            {
                "Token": entry.token,
                "Label": entry.label,
                "Timepoint": tags.get("timepoint", ""),
                "Genotype": tags.get("genotype", ""),
                "Sex": tags.get("sex", ""),
                "Condition": tags.get("condition", ""),
                "Channels": entry.size_c,
                "Dimensions": entry.dimensions_label(),
                "Status": entry.status(),
                "entry": entry,
            }
        )
    return rows


def filter_rows(rows: Sequence[Mapping[str, Any]], query: str | None) -> list[dict[str, Any]]:
    """Local, literal, case-insensitive search over label plus file name.

    The query is never sent anywhere (BrowseFilesDialog.java:255).
    """
    text = (query or "").strip()
    if not text:
        return [dict(row) for row in rows]
    needle = text.lower()
    out: list[dict[str, Any]] = []
    for row in rows:
        entry = row.get("entry")
        file_name = Path(entry.file).name if entry is not None else ""
        haystack = f"{row.get('Label', '')} {file_name}".lower()
        if needle in haystack:
            out.append(dict(row))
    return out


def preview_text(tokens: Sequence[str], tag: str | None = "") -> str:
    """"The agent will see:" box (BrowseFilesDialog.java:283)."""
    if not tokens:
        return NO_SELECTION_PREVIEW
    text = "tokens: " + ", ".join(tokens)
    cleaned = (tag or "").strip()
    if cleaned:
        text += f"\ntag: {cleaned}"
    return text


def build_brief(
    session_id: str | None, entries: Sequence[SeriesEntry], tag: str | None
) -> dict:
    """Outbound brief: tokens, tag, and shape metadata only (Brief.java:14).

    Tokens that do not match the token shape are dropped, so a real filename
    can never ride along by accident. ``channels`` is 0 and ``dimensions`` is
    "mixed dimensions" when the selection disagrees.
    """
    readable = [entry for entry in entries or () if entry.readable]
    if not readable:
        raise ValueError(SELECT_SOMETHING)
    tokens: list[str] = []
    items: list[dict[str, Any]] = []
    common_channels = -1
    common_dimensions = ""
    for entry in readable:
        token = (entry.token or "").strip()
        if token and TOKEN_PATTERN.fullmatch(token):
            tokens.append(token)
        items.append(entry.safe_metadata())
        if common_channels < 0:
            common_channels = entry.size_c
        elif common_channels != entry.size_c:
            common_channels = 0
        dims = entry.dimensions_label()
        if not common_dimensions:
            common_dimensions = dims
        elif common_dimensions != dims:
            common_dimensions = MIXED_DIMENSIONS
    session = (session_id or "").strip() or "default"
    return {
        "session_id": session,
        "tokens": tokens,
        "tag": (tag or "").strip(),
        "metadata": {
            "count": len(tokens),
            "channels": max(0, common_channels),
            "dimensions": common_dimensions,
            "items": items,
        },
    }


def compose_nudge(brief: Mapping[str, Any]) -> str:
    """The message the agent receives (BriefNudger.composeNudge, verbatim)."""
    tokens = list(brief.get("tokens") or [])
    noun = " image/series" if len(tokens) == 1 else " images/series"
    text = f"I've selected {len(tokens)}{noun} for analysis: " + ", ".join(tokens)
    tag = str(brief.get("tag") or "").strip()
    if tag:
        metadata = brief.get("metadata") or {}
        summary_parts: list[str] = []
        channels = metadata.get("channels")
        if isinstance(channels, (int, float)) and int(channels) > 0:
            summary_parts.append(f"{int(channels)}-channel")
        dimensions = str(metadata.get("dimensions") or "").strip()
        if dimensions:
            summary_parts.append(dimensions)
        summary = ", ".join(summary_parts)
        text += f' (tagged "{tag}"' + (f", {summary}" if summary else "") + ")"
    return text + ". Please call get_pending_brief for details."


# --------------------------------------------------------------------------
# "@" mentions in the chat input
#
# Typing "@" in the console input offers the files in the working directory so
# the user can name an image without typing a path. The listing is local; only
# the inserted text can reach the agent, so it follows the same posture rule as
# the Browse Files brief: a token when the posture pseudonymises (S6.12), the
# real path only under STANDARD.
#
# The offered extensions are the set PseudonymisationFilter already recognises
# as file-like (PseudonymisationFilter.java:39), split into images and data
# files so images sort first.
# --------------------------------------------------------------------------

MENTION_IMAGE_SUFFIXES = (
    ".lif",
    ".czi",
    ".nd2",
    ".lsm",
    ".oib",
    ".oif",
    ".vsi",
    ".svs",
    ".ome.tif",
    ".ome.tiff",
    ".tif",
    ".tiff",
    ".png",
    ".jpg",
    ".jpeg",
)
MENTION_DATA_SUFFIXES = (".csv", ".tsv", ".md", ".pdf", ".json", ".xml", ".txt")
MENTION_LIMIT = 20


@dataclass(frozen=True)
class MentionCandidate:
    """One "@" completion: what to show locally, what to insert outbound."""

    name: str
    path: Path
    kind: str
    label: str
    insert_text: str
    token: str = ""

    @property
    def is_image(self) -> bool:
        return self.kind == "image"


def _mention_kind(path: Path) -> str | None:
    name = path.name.lower()
    if name.endswith(MENTION_IMAGE_SUFFIXES):
        return "image"
    if name.endswith(MENTION_DATA_SUFFIXES):
        return "data"
    return None


class MentionFileIndex:
    """One bounded folder listing, invalidated by directory changes or age."""
    def __init__(self):
        self._signature = None
        self._files = []
        self._scanned_at = 0.0

    def paths(self, folder: Path):
        stat = folder.stat()
        signature = (folder, stat.st_mtime_ns, stat.st_ctime_ns)
        now = time.monotonic()
        if signature != self._signature or now - self._scanned_at >= 2:
            files = _mention_paths(folder)
            self._files, self._signature, self._scanned_at = files, signature, now
        return self._files


def _mention_paths(folder: Path):
    # DirEntry carries Windows directory metadata; Path.is_file would issue
    # another filesystem request for every entry on every keystroke.
    files = []
    with os.scandir(folder) as entries:
        for index, entry in enumerate(entries):
            if index >= MAX_DIRECTORY_ENTRIES:
                break
            if entry.name.startswith("."):
                continue
            path = Path(entry.path)
            kind = _mention_kind(path)
            if kind is not None and entry.is_file():
                files.append((path, kind))
    return files


def mention_candidates(
    directory: str | Path | None,
    prefix: str = "",
    limit: int = MENTION_LIMIT,
    *,
    token_map: PathTokenMap | None = None,
    posture: str | None = "STANDARD",
    file_index: MentionFileIndex | None = None,
) -> list[MentionCandidate]:
    """Files to offer after "@" in the chat input.

    Images come first, then other readable data files, each group sorted by
    name so the list never jumps while the user types. ``prefix`` is matched
    case-insensitively against both the file name and its stem, so "@pat"
    finds ``patient_A.lif``. Hidden files are skipped and at most ``limit``
    rows come back, because this runs on every keystroke.

    ``insert_text`` is the only field that may leave the machine: it is the
    pseudonym token when the posture pseudonymises, and the real path only
    under STANDARD. Minting a token needs a ``PathTokenMap``; one is created
    on demand, but callers should pass the session map so the token matches
    what Browse Files and the TCP replies already use.
    """
    if directory is None:
        return []
    folder = Path(directory)
    if not folder.is_dir():
        return []
    if limit <= 0:
        return []

    needle = (prefix or "").strip().lower()
    if needle.startswith("@"):
        needle = needle[1:]

    matches: list[tuple[int, str, Path, str]] = []
    try:
        files = file_index.paths(folder) if file_index else _mention_paths(folder)
        for candidate, kind in files:
            name = candidate.name
            lowered = name.lower()
            if needle and not (
                lowered.startswith(needle) or base_name(candidate).lower().startswith(needle)
            ):
                continue
            matches.append((0 if kind == "image" else 1, lowered, candidate, kind))
    except OSError as exc:
        raise ScanError(
            "folder_unreadable", folder, f"Could not read folder: {exc}"
        ) from exc

    matches.sort(key=lambda row: (row[0], row[1], str(row[2])))
    mapper = token_map or PathTokenMap()
    pseudonymise = requires_pseudonym(posture)

    out: list[MentionCandidate] = []
    for _group, _key, path, kind in matches[:limit]:
        token = mapper.token_for_path(path)
        insert_text = token if pseudonymise else str(path.resolve())
        out.append(
            MentionCandidate(
                name=path.name,
                path=path.resolve(),
                kind=kind,
                label=path.name,
                insert_text=insert_text,
                token=token,
            )
        )
    return out


def mention_replacement(text: str, at_index: int, candidate: MentionCandidate) -> str:
    """Replace the ``@...`` fragment at ``at_index`` with the chosen insert text.

    Keeps the caret-side text intact so a mention can be completed in the
    middle of a sentence.
    """
    if at_index < 0 or at_index >= len(text or "") or text[at_index] != "@":
        raise ValueError("at_index must point at the '@' that started the mention")
    end = at_index + 1
    while end < len(text) and not text[end].isspace():
        end += 1
    return text[:at_index] + candidate.insert_text + text[end:]
