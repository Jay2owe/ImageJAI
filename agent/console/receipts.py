"""Outbound receipts, audit-CSV reading, statement and friction summaries.

The console never performs redaction - the plugin's ``PseudonymisationFilter``
does that inside the JVM before bytes reach any agent. This module is the
read/record side of that contract:

* a live, append-only record of what the console itself sent out
  (``imagejai/ui/ReceiptsPane.java``),
* a reader for the plugin's append-only ``AI_Exports/imagejai_audit.csv``
  (``imagejai/engine/security/AuditLog.java`` + ``AuditRow.java``),
* the Data Handling Statement, as Markdown rather than PDF
  (``imagejai/engine/security/DataHandlingStatementGenerator.java``),
* a reader for ``~/.imagej-ai/friction.jsonl``
  (``imagejai/engine/FrictionLogJournal.java`` + ``FrictionLog.java``).

Everything is headless and injectable: paths, clocks and roots are arguments
so tests never touch a real home directory or the network.
"""
from __future__ import annotations

import json
import os
import re
import time
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Callable, Iterable, Sequence

from .posture import Posture

__all__ = [
    "RedactionStatus", "Receipt", "ReceiptsLog", "ReceiptsSummary",
    "MAX_REDACTED_PAYLOAD_BYTES", "cap_redacted_payload",
    "AUDIT_FILE_NAME", "AUDIT_HEADER", "AUDIT_COLUMNS", "MAX_SUMMARY_BYTES",
    "AuditRow", "AuditRowError", "AuditLogError", "AuditSummary",
    "audit_csv_path", "parse_csv_line", "split_csv_records",
    "read_audit_rows", "summarise_audit",
    "VendorTerm", "VENDOR_TERMS", "generate_data_handling_statement",
    "write_data_handling_statement", "statement_path",
    "FrictionEntry", "FrictionPattern", "FrictionSummary",
    "friction_journal_paths", "read_friction_entries", "summarise_friction",
    "normalise_error", "NO_PAYLOAD_NOTE",
]


# --------------------------------------------------------------------------
# live receipts (ReceiptsPane.java)
# --------------------------------------------------------------------------

MAX_REDACTED_PAYLOAD_BYTES = 8 * 1024  # AuditRow.java:19
RECENT_RECEIPT_LIMIT = 50  # ReceiptsPane.java:143 - subscribeRecent(50, this)
NO_PAYLOAD_NOTE = (  # ReceiptsPane.java:290
    "Full response body was not cached; this receipt stores redacted audit "
    "metadata only."
)


def cap_redacted_payload(value: "str | None") -> str:
    """Trim a cached payload to 8 KiB with the plugin's truncation marker.

    Receipts are a transparency aid, not a second copy of the data; an
    unbounded payload would quietly turn the console into a data store.
    """
    text = value or ""
    encoded = text.encode("utf-8")
    if len(encoded) <= MAX_REDACTED_PAYLOAD_BYTES:
        return text
    suffix = "\n...(truncated)..."
    limit = max(0, MAX_REDACTED_PAYLOAD_BYTES - len(suffix.encode("utf-8")))
    return encoded[:limit].decode("utf-8", errors="ignore") + suffix


class RedactionStatus(Enum):
    """What the plugin reported about redaction for one outbound call."""

    APPLIED = "applied"
    NOT_APPLIED = "not_applied"
    FAILED = "failed"

    @property
    def applied(self) -> bool:
        return self is RedactionStatus.APPLIED


@dataclass(frozen=True)
class Receipt:
    """One outbound call, as the user will be asked to explain it in an ethics form."""

    timestamp: datetime
    provider: str
    model: str
    posture: Posture
    command: str = ""
    tokens_in: int = 0
    tokens_out: int = 0
    bytes_out: int = 0
    bytes_in: int = 0
    categories: tuple[str, ...] = ()
    redaction: RedactionStatus = RedactionStatus.NOT_APPLIED
    fields_redacted: tuple[str, ...] = ()
    redacted_payload: str = ""
    notes: str = ""

    @property
    def time_text(self) -> str:
        return self.timestamp.astimezone(timezone.utc).strftime("%H:%M:%S")

    @property
    def is_visual_override(self) -> bool:
        """ConfigurationPane.java:336 counts these separately from redactions."""
        return self.command.startswith("visual.") or self.command == "request_visual"

    def as_row(self) -> tuple[str, str, str, str]:
        """The ReceiptsPane columns: time, command, bytes_out, redacted."""
        return (self.time_text, self.command or self.model,
                str(self.bytes_out), "yes" if self.redaction.applied else "no")

    @property
    def detail_text(self) -> str:
        return self.redacted_payload or NO_PAYLOAD_NOTE


@dataclass(frozen=True)
class ReceiptsSummary:
    total: int = 0
    pseudonymised: int = 0
    visual_overrides: int = 0
    tokens_in: int = 0
    tokens_out: int = 0
    bytes_out: int = 0
    bytes_in: int = 0
    redaction_failures: int = 0
    by_provider: dict[str, int] = field(default_factory=dict)
    by_category: dict[str, int] = field(default_factory=dict)
    by_posture: dict[str, int] = field(default_factory=dict)

    @property
    def counter_text(self) -> str:
        """The Data Governance counter line, verbatim (ConfigurationPane.java:336)."""
        return (f"{self.total} ({self.pseudonymised} pseudonymised, "
                f"{self.visual_overrides} visual overrides)")


class ReceiptsLog:
    """Append-only in-memory record of outbound calls made by this console.

    Append-only because a receipt the user can delete proves nothing. The ring
    keeps ``limit`` rows for display, while the counters keep totalling every
    call that was ever appended.
    """

    def __init__(self, limit: int = RECENT_RECEIPT_LIMIT,
                 clock: "Callable[[], datetime] | None" = None) -> None:
        self._limit = max(1, int(limit))
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._rows: list[Receipt] = []
        self._summary = ReceiptsSummary()

    def __len__(self) -> int:
        return self._summary.total

    def append(self, provider: str, model: str, posture: Posture, *,
               command: str = "", tokens_in: int = 0, tokens_out: int = 0,
               bytes_out: int = 0, bytes_in: int = 0,
               categories: Sequence[str] = (),
               redaction: RedactionStatus = RedactionStatus.NOT_APPLIED,
               fields_redacted: Sequence[str] = (),
               redacted_payload: str = "", notes: str = "",
               timestamp: "datetime | None" = None) -> Receipt:
        receipt = Receipt(
            timestamp=timestamp or self._clock(),
            provider=provider or "",
            model=model or "",
            posture=posture or Posture.default(),
            command=command or "",
            tokens_in=max(0, int(tokens_in)),
            tokens_out=max(0, int(tokens_out)),
            bytes_out=max(0, int(bytes_out)),
            bytes_in=max(0, int(bytes_in)),
            categories=tuple(str(c) for c in categories if str(c).strip()),
            redaction=redaction,
            fields_redacted=tuple(str(f).strip() for f in fields_redacted if str(f).strip()),
            redacted_payload=cap_redacted_payload(redacted_payload),
            notes=notes or "",
        )
        self._rows.append(receipt)
        while len(self._rows) > self._limit:
            self._rows.pop(0)
        self._accumulate(receipt)
        return receipt

    def recent(self, limit: "int | None" = None) -> list[Receipt]:
        """Most recent last, matching the pane's top-to-bottom table order."""
        rows = list(self._rows)
        if limit is not None:
            rows = rows[-max(0, int(limit)):]
        return rows

    def summary(self) -> ReceiptsSummary:
        return self._summary

    def _accumulate(self, receipt: Receipt) -> None:
        s = self._summary
        by_provider = dict(s.by_provider)
        by_provider[receipt.provider] = by_provider.get(receipt.provider, 0) + 1
        by_category = dict(s.by_category)
        for category in receipt.categories:
            by_category[category] = by_category.get(category, 0) + 1
        by_posture = dict(s.by_posture)
        by_posture[receipt.posture.label] = by_posture.get(receipt.posture.label, 0) + 1
        self._summary = ReceiptsSummary(
            total=s.total + 1,
            pseudonymised=s.pseudonymised + (1 if receipt.redaction.applied else 0),
            visual_overrides=s.visual_overrides + (1 if receipt.is_visual_override else 0),
            tokens_in=s.tokens_in + receipt.tokens_in,
            tokens_out=s.tokens_out + receipt.tokens_out,
            bytes_out=s.bytes_out + receipt.bytes_out,
            bytes_in=s.bytes_in + receipt.bytes_in,
            redaction_failures=s.redaction_failures
            + (1 if receipt.redaction is RedactionStatus.FAILED else 0),
            by_provider=by_provider,
            by_category=by_category,
            by_posture=by_posture,
        )


# --------------------------------------------------------------------------
# audit CSV reader (AuditLog.java / AuditRow.java)
# --------------------------------------------------------------------------

AUDIT_FILE_NAME = "imagejai_audit.csv"  # AuditLog.java:52
AUDIT_COLUMNS = (  # AuditLog.java:53
    "timestamp_utc", "session_id", "command", "posture", "model_endpoint",
    "capture_source", "bytes_out", "bytes_in", "image_hash",
    "redaction_applied", "fields_redacted", "notes",
)
AUDIT_HEADER = ",".join(AUDIT_COLUMNS)
MAX_SUMMARY_BYTES = 16 * 1024 * 1024  # AuditLog.java:62
MAX_MALFORMED_DIAGNOSTICS = 100  # AuditLog.java:465


class AuditLogError(RuntimeError):
    """The audit file as a whole cannot be summarised (too big, not a file)."""


class AuditRowError(ValueError):
    """One CSV line is malformed; the message names the offending column."""


def audit_csv_path(path: "str | os.PathLike[str]") -> Path:
    """Resolve a project folder, an AI_Exports folder or a CSV to the CSV path.

    Ported from AuditLog.resolveSummaryPath (AuditLog.java:796) so the console
    finds the same file the plugin writes.
    """
    normalised = Path(os.path.abspath(os.path.normpath(str(path))))
    name = normalised.name
    if name.lower().endswith(".csv"):
        return normalised
    if name.lower() == "ai_exports":
        return normalised / AUDIT_FILE_NAME
    return normalised / "AI_Exports" / AUDIT_FILE_NAME


def parse_csv_line(line: "str | None") -> list[str]:
    """Strict RFC-4180-ish split, ported from AuditRow.parseCsvLine.

    Strict on purpose: a quote in the middle of an unquoted field means the
    file was edited or corrupted, and a silently accepted row would understate
    what left the machine.
    """
    cells: list[str] = []
    cell: list[str] = []
    quoted = False
    quote_closed = False
    at_cell_start = True
    text = line or ""
    i = 0
    while i < len(text):
        c = text[i]
        if quoted:
            if c == '"':
                if i + 1 < len(text) and text[i + 1] == '"':
                    cell.append('"')
                    i += 1
                else:
                    quoted = False
                    quote_closed = True
            else:
                cell.append(c)
        elif quote_closed:
            if c == ",":
                cells.append("".join(cell))
                cell.clear()
                quote_closed = False
                at_cell_start = True
            elif c not in "\r\n":
                raise AuditRowError("unexpected character after closing quote")
        elif c == '"' and at_cell_start:
            quoted = True
            at_cell_start = False
        elif c == '"':
            raise AuditRowError("quote inside unquoted field")
        elif c == ",":
            cells.append("".join(cell))
            cell.clear()
            at_cell_start = True
        elif c not in "\r\n":
            cell.append(c)
            at_cell_start = False
        i += 1
    if quoted:
        raise AuditRowError("unterminated quoted field")
    cells.append("".join(cell))
    return cells


def split_csv_records(text: str) -> list[tuple[int, str]]:
    """Split into (start_line, record) honouring quoted newlines (AuditLog.java:685)."""
    records: list[tuple[int, str]] = []
    current: list[str] = []
    quoted = False
    line = 1
    start_line = 1
    i = 0
    while i < len(text):
        c = text[i]
        if c == '"':
            if quoted and i + 1 < len(text) and text[i + 1] == '"':
                current.append('""')
                i += 2
                continue
            quoted = not quoted
            current.append(c)
        elif c in "\r\n" and not quoted:
            if c == "\r" and i + 1 < len(text) and text[i + 1] == "\n":
                i += 1
            records.append((start_line, "".join(current)))
            current.clear()
            line += 1
            start_line = line
        else:
            current.append(c)
            if c == "\n":
                line += 1
        i += 1
    if current or quoted:
        records.append((start_line, "".join(current)))
    return records


def _unprotect_formula(value: str) -> str:
    """Undo the leading quote the writer adds to ``=+-@`` cells (AuditRow.java:316)."""
    if len(value) > 1 and value[0] == "'" and value[1] in "=+-@":
        return value[1:]
    return value


def _parse_instant(value: str) -> datetime:
    text = (value or "").strip()
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise AuditRowError("invalid timestamp") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _parse_int(value: str) -> int:
    try:
        parsed = int((value or "").strip())
        if parsed < 0:
            raise ValueError("negative")
    except ValueError as exc:
        raise AuditRowError(f"invalid non-negative integer: {value}") from exc
    return parsed


def _parse_bool(value: str) -> bool:
    text = (value or "").strip().lower()
    if text == "true":
        return True
    if text == "false":
        return False
    raise AuditRowError(f"invalid boolean: {value}")


def _parse_posture(value: str) -> Posture:
    try:
        return Posture.parse(value)
    except ValueError as exc:
        raise AuditRowError(f"invalid privacy posture: {value}") from exc


@dataclass(frozen=True)
class AuditRow:
    """One parsed row of the plugin's audit CSV."""

    timestamp_utc: datetime
    session_id: str
    command: str
    posture: Posture
    model_endpoint: str
    capture_source: str
    bytes_out: int
    bytes_in: int
    image_hash: str
    redaction_applied: bool
    fields_redacted: tuple[str, ...]
    notes: str

    @classmethod
    def from_csv_line(cls, line: str) -> "AuditRow":
        cells = parse_csv_line(line)
        if len(cells) != len(AUDIT_COLUMNS):
            raise AuditRowError(
                f"Expected {len(AUDIT_COLUMNS)} audit columns, got {len(cells)}")
        cells = [_unprotect_formula(c) for c in cells]
        fields = tuple(part.strip() for part in cells[10].split(";") if part.strip())
        return cls(
            timestamp_utc=_parse_instant(cells[0]),
            session_id=cells[1],
            command=cells[2],
            posture=_parse_posture(cells[3]),
            model_endpoint=cells[4],
            capture_source=cells[5],
            bytes_out=_parse_int(cells[6]),
            bytes_in=_parse_int(cells[7]),
            image_hash=cells[8],
            redaction_applied=_parse_bool(cells[9]),
            fields_redacted=fields,
            notes=cells[11],
        )

    @property
    def category(self) -> str:
        """Coarse grouping used by the counters: the command family."""
        return self.command.split(".", 1)[0] if self.command else "(none)"


@dataclass(frozen=True)
class AuditSummary:
    """Counts behind section 5 of the statement (AuditSummary.java)."""

    source_path: "Path | None" = None
    total_rows: int = 0
    first: "datetime | None" = None
    last: "datetime | None" = None
    total_bytes_out: int = 0
    total_bytes_in: int = 0
    redacted_rows: int = 0
    visual_grant_rows: int = 0
    visual_consume_rows: int = 0
    posture_event_rows: int = 0
    downshift_rows: int = 0
    command_counts: dict[str, int] = field(default_factory=dict)
    posture_counts: dict[str, int] = field(default_factory=dict)
    category_counts: dict[str, int] = field(default_factory=dict)
    fields_redacted: tuple[str, ...] = ()
    malformed_rows: int = 0
    malformed_diagnostics: tuple[str, ...] = ()

    @property
    def date_range(self) -> str:
        if self.first is None or self.last is None:
            return "none"
        if self.first == self.last:
            return _iso(self.first)
        return f"{_iso(self.first)} — {_iso(self.last)}"


def _iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _read_audit_text(path: Path) -> str:
    if not path.is_file():
        raise AuditLogError(f"Audit log is not a regular readable file: {path}")
    size = path.stat().st_size
    if size > MAX_SUMMARY_BYTES:
        raise AuditLogError(
            f"Audit log exceeds summary limit of {MAX_SUMMARY_BYTES} bytes")
    return path.read_text(encoding="utf-8", errors="replace")


def read_audit_rows(path: "str | os.PathLike[str]", *, strict: bool = False
                    ) -> tuple[list[AuditRow], list[str]]:
    """Parse the CSV into rows plus per-line diagnostics.

    Returns ``(rows, diagnostics)``. With ``strict=True`` the first bad line
    raises :class:`AuditRowError` instead - the house rule is that a broken
    audit file must be reported, never quietly skipped.
    """
    csv_path = audit_csv_path(path)
    if not csv_path.exists():
        return [], []
    text = _read_audit_text(csv_path)
    rows: list[AuditRow] = []
    diagnostics: list[str] = []
    for start_line, record in split_csv_records(text):
        stripped = record.strip()
        if not stripped or stripped == AUDIT_HEADER:
            continue
        try:
            rows.append(AuditRow.from_csv_line(record))
        except AuditRowError as bad:
            if strict:
                raise AuditRowError(f"line {start_line}: {bad}") from bad
            if len(diagnostics) < MAX_MALFORMED_DIAGNOSTICS:
                diagnostics.append(f"line {start_line}: {bad}")
    return rows, diagnostics


def summarise_audit(path: "str | os.PathLike[str]", *, strict: bool = False) -> AuditSummary:
    """Summarise the audit CSV for a project folder (AuditLog.summaryFor)."""
    csv_path = audit_csv_path(path)
    rows, diagnostics = read_audit_rows(csv_path, strict=strict)
    command_counts: Counter[str] = Counter()
    posture_counts: Counter[str] = Counter()
    category_counts: Counter[str] = Counter()
    fields: set[str] = set()
    first: datetime | None = None
    last: datetime | None = None
    bytes_out = bytes_in = 0
    redacted = visual_grants = visual_consumes = posture_events = downshifts = 0

    for row in rows:
        if first is None or row.timestamp_utc < first:
            first = row.timestamp_utc
        if last is None or row.timestamp_utc > last:
            last = row.timestamp_utc
        bytes_out += row.bytes_out
        bytes_in += row.bytes_in
        redacted += 1 if row.redaction_applied else 0
        command_counts[row.command] += 1
        posture_counts[row.posture.label] += 1
        category_counts[row.category] += 1
        fields.update(row.fields_redacted)
        if row.command == "visual.granted":
            visual_grants += 1
        if row.command == "visual.consumed":
            visual_consumes += 1
        if row.command.startswith("posture."):
            posture_events += 1
        if row.command == "posture.downshift":
            downshifts += 1

    return AuditSummary(
        source_path=csv_path,
        total_rows=len(rows),
        first=first,
        last=last,
        total_bytes_out=bytes_out,
        total_bytes_in=bytes_in,
        redacted_rows=redacted,
        visual_grant_rows=visual_grants,
        visual_consume_rows=visual_consumes,
        posture_event_rows=posture_events,
        downshift_rows=downshifts,
        command_counts=dict(sorted(command_counts.items())),
        posture_counts=dict(sorted(posture_counts.items())),
        category_counts=dict(sorted(category_counts.items())),
        fields_redacted=tuple(sorted(fields)),
        malformed_rows=len(diagnostics),
        malformed_diagnostics=tuple(diagnostics),
    )


# --------------------------------------------------------------------------
# vendor terms + Data Handling Statement
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class VendorTerm:
    vendor: str
    statement: str
    url: str = ""

    @property
    def display_url(self) -> str:
        out = self.url
        for prefix in ("https://", "http://"):
            if out.startswith(prefix):
                out = out[len(prefix):]
        if out.startswith("www."):
            out = out[4:]
        return out.rstrip("/")


# VendorTermsRegistry.java:13 - keep in step with the plugin; the free Gemini
# tier warning is upper case there because it is the one that loses data.
VENDOR_TERMS: tuple[VendorTerm, ...] = (
    VendorTerm("Anthropic Claude API / Claude Code",
               "No training on API inputs/outputs; abuse logs retained 7 days.",
               "https://www.anthropic.com/legal/commercial-terms"),
    VendorTerm("OpenAI API / Codex CLI",
               "No training on business API data; 30-day abuse logs.",
               "https://openai.com/enterprise-privacy/"),
    VendorTerm("Google Gemini API (paid)",
               "No training; 55-day abuse logs.",
               "https://cloud.google.com/gemini/docs/discover/data-governance"),
    VendorTerm("Google Gemini API / AI Studio (free tier)",
               "PROMPTS AND OUTPUTS ARE USED FOR TRAINING. NOT RECOMMENDED FOR RESEARCH DATA.",
               "https://ai.google.dev/gemini-api/terms"),
    VendorTerm("Ollama (local)",
               "No outbound network traffic. Recommended for Restricted data.",
               ""),
    VendorTerm("Ollama Cloud (*-cloud model tags)",
               "Routes inference to Ollama's US-hosted servers. Not a local execution.",
               "https://ollama.com/privacy"),
)

REPO_URL = "github.com/Jay2owe/ImageJAI"  # DataHandlingStatementGenerator.java:39
DEFAULT_VERSION = "0.5.0"  # Constants.java:11


def _project_name(folder: Path) -> str:
    cleaned = re.sub(r'[\\/:*?"<>|]+', "_", folder.name).strip()
    return cleaned or "project"


def statement_path(project_folder: "str | os.PathLike[str]",
                   today: "datetime | None" = None) -> Path:
    """AI_Exports/DataHandlingStatement_<project>_<YYYYMMDD>.md.

    Markdown, not PDF: the console has no PDF toolkit and inventing one would
    add a heavy dependency for a document the user can print from Markdown.
    """
    folder = Path(os.path.abspath(os.path.normpath(str(project_folder))))
    when = today or datetime.now(timezone.utc)
    return (folder / "AI_Exports"
            / f"DataHandlingStatement_{_project_name(folder)}_{when:%Y%m%d}.md")


def generate_data_handling_statement(
        project_folder: "str | os.PathLike[str]",
        posture: "Posture | None" = None, *,
        summary: "AuditSummary | None" = None,
        posture_history: Sequence = (),
        receipts: "ReceiptsSummary | None" = None,
        version: str = DEFAULT_VERSION,
        today: "datetime | None" = None) -> str:
    """Build the statement text; same seven sections as the plugin PDF.

    Section numbering is deliberately identical to
    DataHandlingStatementGenerator.java:63 so a reviewer can compare a console
    statement with a plugin statement paragraph by paragraph. The posture
    history is appended inside section 5, where the audit evidence lives.
    """
    folder = Path(os.path.abspath(os.path.normpath(str(project_folder))))
    effective = posture or Posture.default()
    when = today or datetime.now(timezone.utc)
    audit = summary if summary is not None else summarise_audit(folder)

    out: list[str] = []
    out.append("# ImageJAI — Data Handling Statement")
    out.append("")
    out.append(f"Project: {_project_name(folder)}")
    out.append(f"Generated: {when:%Y-%m-%d}    Posture in force: {effective.label}")
    out.append(f"ImageJAI version: {version}")
    out.append("")

    out.append("## 1. Purpose")
    out.append("This statement summarises the data-handling posture of the ImageJAI "
               "plugin for the named project. Suitable for Research Ethics Committee "
               "amendments and Data Management Plans.")
    out.append("")

    out.append("## 2. Data flow")
    out.extend([
        f"- Images are read from {folder} on the local machine.",
        "- The user selects files (and series-within-files) via the Browse Files "
        "dialog or Fiji's File menu. Identifiable filenames remain on the local machine.",
        "- The ImageJAI TCP server (localhost:7746) exposes commands to the selected agent CLI.",
        "- Outbound responses pass through the PseudonymisationFilter before reaching "
        "the agent process.",
        "- The agent CLI communicates with the configured model endpoint (see section 4).",
    ])
    out.append("")

    out.append("## 3. Pseudonymisation scheme (UK GDPR Art. 4(5))")
    out.append("The following are tokenised before leaving the JVM in Pseudonymised "
               "and On-premises postures:")
    out.extend([
        "- File and folder paths (whole-file and series-within-file)",
        "- OME-XML elements: Experimenter, Description, StageLabel, AcquisitionDate, "
        "InstrumentRef, InstrumentSerialNumber, Annotation",
        "- Results-table columns: Label, Slice (when non-numeric)",
        "- Dialog and window titles",
        "- Error and log messages",
    ])
    out.append("")
    out.append("Image-pixel handling is differentiated by source:")
    out.extend([
        "- Microscopy image content: downsampled to ≤512×512 and text burn-ins "
        "(Incucyte timestamps, scanner labels) masked before transmission.",
        "- GUI / dialog / window screenshots: refused and replaced with a hash placeholder.",
        "- On-demand visual override: the agent may request full-resolution pixel access "
        "for one call; the user must explicitly grant; burn-in mask still applied; the "
        "grant and consumption are logged in the audit trail.",
    ])
    out.append("")
    out.append("The token map is held in JVM memory only and destroyed at session end.")
    out.append("")

    out.append("## 4. Vendor contractual posture")
    for term in VENDOR_TERMS:
        line = term.statement
        if term.vendor.startswith(("Anthropic", "OpenAI")) or term.vendor == "Google Gemini API (paid)":
            line = f'"{line}"'
        if term.display_url:
            line = f"{line} — {term.display_url}"
        out.append(f"- **{term.vendor}:** {line}")
    out.append("")

    out.append("## 5. Audit trail")
    out.append("Location: AI_Exports/imagejai_audit.csv")
    out.append(f"- Rows in this project to date: {audit.total_rows}")
    out.append(f"- Date range: {audit.date_range}")
    out.append(f"- Pseudonymised calls: {audit.redacted_rows}")
    out.append(f"- Visual override grants: {audit.visual_grant_rows}")
    out.append(f"- Posture downshifts: {audit.downshift_rows}")
    if audit.category_counts:
        out.append("- Calls by category: "
                   + ", ".join(f"{name}={count}"
                               for name, count in audit.category_counts.items()))
    if audit.malformed_rows:
        out.append(f"- Malformed audit lines skipped: {audit.malformed_rows} "
                   f"(first: {audit.malformed_diagnostics[0]})")
    if receipts is not None:
        out.append(f"- Console session receipts: {receipts.counter_text}")
    out.append("")
    out.append("### Posture history")
    if posture_history:
        for item in posture_history:
            out.append(f"- {_history_line(item)}")
    else:
        out.append("- No posture change was recorded for this project.")
    out.append("")

    out.append("## 6. Opt-out and escalation")
    out.append("Set the Privacy Posture to On-premises via the folder banner or the "
               "launcher Configuration Pane. In this mode the agent dropdown is filtered "
               "to local-binary agents only, *-cloud Ollama tags are refused, and the "
               "visual override is unavailable.")
    out.append("")

    out.append("## 7. Limitations")
    out.extend([
        "- Pseudonymisation remains reversible inside the live JVM session so ImageJAI "
        "can resolve tokens back to local files.",
        "- The pseudonymisation filter governs TCP responses. For external CLIs "
        "(Claude Code in a separate terminal), filenames typed directly into the agent "
        "chat are NOT intercepted. The Browse Files dialog and the embedded terminal's "
        "outbound prompt scrubber mitigate this.",
        "- Burn-in text detection uses a fast heuristic catching ~90% of cases; "
        "configurable per-format masks cover known microscope vendors (Incucyte, Aperio).",
        "- This statement is generated automatically from posture and audit metadata at "
        "the moment of generation.",
    ])
    out.append("")
    out.append(f"Generated by ImageJAI v{version} — {REPO_URL}")
    out.append("")
    return "\n".join(out)


def _history_line(item) -> str:
    """Render a posture history item from either a PostureEvent or an OverrideRecord."""
    event_type = getattr(item, "event_type", None)
    if event_type is not None:
        parts = [event_type]
        if getattr(item, "from_posture", None) is not None:
            parts.append(f"from={item.from_posture.label}")
        if getattr(item, "to_posture", None) is not None:
            parts.append(f"to={item.to_posture.label}")
        if getattr(item, "reason", ""):
            parts.append(f"reason='{item.reason}'")
        return " ".join(parts)
    notes = getattr(item, "notes", None)
    if notes is not None:
        return f"{getattr(item, 'at', '')} {getattr(item, 'command', '')} {notes}".strip()
    return str(item)


def write_data_handling_statement(project_folder: "str | os.PathLike[str]",
                                  posture: "Posture | None" = None, **kwargs) -> Path:
    """Write the statement under AI_Exports and return the path.

    Outputs go next to the images, per the project house rule.
    """
    today = kwargs.get("today")
    target = statement_path(project_folder, today)
    text = generate_data_handling_statement(project_folder, posture, **kwargs)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    return target


# --------------------------------------------------------------------------
# friction log (FrictionLog.java / FrictionLogJournal.java)
# --------------------------------------------------------------------------

FRICTION_FILE_NAME = "friction.jsonl"  # FrictionLogJournal.java:40
FRICTION_MAX_GENERATIONS = 5  # FrictionLogJournal.java:39
FRICTION_MAX_LINE_BYTES = 64 * 1024  # FrictionLogJournal.java:41
FRICTION_MAX_ENTRIES = 50_000  # FrictionLogJournal.java:42
FRICTION_WINDOW_MS = 600_000  # FrictionLog.java:29 - 10 minutes
FRICTION_PATTERN_THRESHOLD = 3  # FrictionLog.java:32


def config_dir() -> Path:
    """``~/.imagej-ai`` unless IMAGEJAI_HOME redirects it (as the console does)."""
    override = os.environ.get("IMAGEJAI_HOME")
    return Path(override) if override else Path.home() / ".imagej-ai"


def normalise_error(error: "str | None") -> str:
    """Strip the varying parts of an error so repeats group (FrictionLog.java:240)."""
    text = error or ""
    text = re.sub(r"[A-Za-z]:[\\/][^\s'\"]+", "<path>", text)
    text = re.sub(r"(^|\s)/[^\s'\"]+", r"\1<path>", text)
    text = re.sub(r"0x[0-9a-fA-F]+", "<hex>", text)
    text = re.sub(r"\b\d+\b", "N", text)
    text = re.sub(r"\s+", " ", text).strip().lower()
    return text[:200]


@dataclass(frozen=True)
class FrictionEntry:
    ts: int
    command: str
    error: str
    normalised_error: str = ""
    agent_id: str = ""
    args_summary: str = ""
    outcome: "str | None" = None
    severity: "str | None" = None
    rule_id: "str | None" = None
    target: "str | None" = None

    @classmethod
    def from_json(cls, data: dict) -> "FrictionEntry":
        command = str(data.get("command") or "")
        error = str(data.get("error") or "")
        return cls(
            ts=int(data.get("ts") or 0),
            command=command,
            error=error,
            normalised_error=str(data.get("normalised_error") or normalise_error(error)),
            agent_id=str(data.get("agent_id") or ""),
            args_summary=str(data.get("args_summary") or ""),
            outcome=data.get("outcome"),
            severity=data.get("severity"),
            rule_id=data.get("rule_id"),
            target=data.get("target"),
        )


@dataclass(frozen=True)
class FrictionPattern:
    """A repeated (command, normalised error) failure - what the agent keeps hitting."""

    command: str
    normalised_error: str
    sample_error: str
    count: int
    first_ts: int
    last_ts: int


@dataclass(frozen=True)
class FrictionSummary:
    entries: int = 0
    files_read: tuple[Path, ...] = ()
    patterns: tuple[FrictionPattern, ...] = ()
    by_command: dict[str, int] = field(default_factory=dict)
    skipped_lines: int = 0

    @property
    def text(self) -> str:
        if not self.entries:
            return "No friction recorded."
        if not self.patterns:
            return f"{self.entries} failures recorded, no repeated pattern."
        top = self.patterns[0]
        return (f"{self.entries} failures recorded; {len(self.patterns)} repeated "
                f"pattern(s). Worst: {top.command} x{top.count} — {top.normalised_error}")


def friction_journal_paths(root: "str | os.PathLike[str] | None" = None) -> list[Path]:
    """Oldest generation first, live journal last (FrictionLogJournal.java:203)."""
    base = Path(root) if root is not None else config_dir()
    paths: list[Path] = []
    for generation in range(FRICTION_MAX_GENERATIONS, 0, -1):
        candidate = base / f"{FRICTION_FILE_NAME}.{generation}"
        if candidate.is_file():
            paths.append(candidate)
    live = base / FRICTION_FILE_NAME
    if live.is_file():
        paths.append(live)
    return paths


def read_friction_entries(root: "str | os.PathLike[str] | None" = None, *,
                          limit: int = FRICTION_MAX_ENTRIES
                          ) -> tuple[list[FrictionEntry], list[Path], int]:
    """Read the JSONL journal oldest-first.

    Returns ``(entries, files_read, skipped_lines)``. Over-long or unparsable
    lines are counted rather than raised: the journal is an append-only
    diagnostic that a crash can truncate, and one bad tail line must not hide
    the rest of the failure history.
    """
    entries: list[FrictionEntry] = []
    files: list[Path] = []
    skipped = 0
    for path in friction_journal_paths(root):
        files.append(path)
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if len(entries) >= limit:
                    return entries, files, skipped
                stripped = line.strip()
                if not stripped:
                    continue
                if len(stripped.encode("utf-8")) > FRICTION_MAX_LINE_BYTES:
                    skipped += 1
                    continue
                try:
                    data = json.loads(stripped)
                except ValueError:
                    skipped += 1
                    continue
                if not isinstance(data, dict):
                    skipped += 1
                    continue
                entries.append(FrictionEntry.from_json(data))
    return entries, files, skipped


def summarise_friction(root: "str | os.PathLike[str] | None" = None, *,
                       now_ms: "int | None" = None,
                       window_ms: int = FRICTION_WINDOW_MS,
                       threshold: int = FRICTION_PATTERN_THRESHOLD,
                       entries: "Iterable[FrictionEntry] | None" = None
                       ) -> FrictionSummary:
    """Group repeated failures inside the recent window (FrictionLog.patterns()).

    ``now_ms`` is injectable so a test can pin the window instead of racing the
    wall clock.
    """
    if entries is None:
        collected, files, skipped = read_friction_entries(root)
    else:
        collected, files, skipped = list(entries), [], 0
    current = int(now_ms if now_ms is not None else time.time() * 1000)
    cutoff = current - max(0, window_ms)

    grouped: dict[tuple[str, str], list] = {}
    by_command: Counter[str] = Counter()
    for entry in collected:
        by_command[entry.command] += 1
        if entry.ts < cutoff:
            continue
        key = (entry.command, entry.normalised_error)
        bucket = grouped.get(key)
        if bucket is None:
            grouped[key] = [1, entry.ts, entry.ts, entry.error]
        else:
            bucket[0] += 1
            bucket[1] = min(bucket[1], entry.ts)
            bucket[2] = max(bucket[2], entry.ts)

    patterns = [
        FrictionPattern(command=key[0], normalised_error=key[1], sample_error=value[3],
                        count=value[0], first_ts=value[1], last_ts=value[2])
        for key, value in grouped.items() if value[0] >= threshold
    ]
    patterns.sort(key=lambda p: (-p.count, p.command))
    return FrictionSummary(
        entries=len(collected),
        files_read=tuple(files),
        patterns=tuple(patterns),
        by_command=dict(sorted(by_command.items())),
        skipped_lines=skipped,
    )
