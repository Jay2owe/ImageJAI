"""Pure planning helpers for image-analysis-aware conversation compaction.

This module does not call a model and does not mutate the supplied messages.
It prepares a provider-neutral :class:`CompactionPlan` that a caller can feed
into its own summariser.  Large binary/pixel payloads become explicit markers,
while exact scientific facts are copied into a separate verbatim channel.
"""
from __future__ import annotations

import base64
import copy
import hashlib
import inspect
import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

CHARS_PER_TOKEN = 4
LONG_PAYLOAD_CHARS = 4000


@dataclass(frozen=True)
class EvictedArtifact:
    """A payload removed from the planned model context (not from history)."""

    kind: str
    message_index: int
    location: str
    marker: str
    sha256: str
    original_chars: int
    reference: str
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class CompactionPlan:
    """Deterministic output of :func:`plan_compaction`.

    ``critical_facts`` is verbatim material that must bypass prose summary.
    ``prose_to_summarize`` is the non-critical old prose a later model call may
    summarise.  ``head_messages`` and ``recent_messages`` contain explicit
    artifact markers, never silently missing payloads.
    """

    head_messages: list[dict[str, Any]]
    recent_messages: list[dict[str, Any]]
    critical_facts: list[str]
    evicted_artifacts: list[EvictedArtifact]
    warnings: list[str]
    estimated_tokens_before: int
    estimated_tokens_after: int
    prose_to_summarize: list[str] = field(default_factory=list)

    @property
    def malformed(self) -> bool:
        return any("malformed tool pairing" in item for item in self.warnings)


ArtifactWriter = Callable[[str | bytes, Mapping[str, Any]], str | Path | None]


def _stable_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, default=str)


def estimate_tokens(value: Any) -> int:
    """Return a deterministic, provider-neutral chars/4 token estimate.

    Dictionary keys are sorted, so equivalent message dictionaries produce the
    same estimate regardless of insertion order.  This is deliberately an
    estimate rather than a provider tokenizer and therefore needs no network or
    optional package.
    """
    if value is None:
        return 0
    if isinstance(value, bytes):
        size = len(value)
    elif isinstance(value, str):
        size = len(value)
    else:
        size = len(_stable_json(value))
    return int(math.ceil(size / CHARS_PER_TOKEN)) if size else 0


def should_compact(context: int | Sequence[Mapping[str, Any]],
                   context_window: int, reserve_tokens: int = 16_384) -> bool:
    """Whether estimated context exceeds ``context_window - reserve_tokens``.

    ``context`` can be an already computed token count or a message sequence.
    The threshold is strict, matching Prime Agent: equality does not compact.
    """
    if context_window <= 0:
        raise ValueError("context_window must be positive")
    if reserve_tokens < 0 or reserve_tokens >= context_window:
        raise ValueError("reserve_tokens must be >= 0 and < context_window")
    tokens = context if isinstance(context, int) and not isinstance(context, bool) else estimate_tokens(context)
    if tokens < 0:
        raise ValueError("context token count cannot be negative")
    return tokens > context_window - reserve_tokens


def _tool_calls(message: Mapping[str, Any], index: int) -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    raw = message.get("tool_calls")
    if isinstance(raw, list):
        for pos, call in enumerate(raw):
            if not isinstance(call, Mapping):
                continue
            ident = call.get("id") or call.get("call_id") or call.get("tool_call_id")
            fn = call.get("function") if isinstance(call.get("function"), Mapping) else {}
            name = call.get("name") or fn.get("name") or "?"
            found.append((str(ident) if ident else f"@anon:{index}:{pos}", str(name)))
    top = message.get("function_call")
    if isinstance(top, Mapping):
        ident = top.get("id") or top.get("call_id")
        found.append((str(ident) if ident else f"@anon:{index}:function", str(top.get("name") or "?")))
    content = message.get("content")
    if isinstance(content, list):
        for pos, block in enumerate(content):
            if not isinstance(block, Mapping):
                continue
            if str(block.get("type", "")).lower() in {"tool_use", "tool_call", "function_call"}:
                ident = block.get("id") or block.get("call_id") or block.get("tool_call_id")
                name = block.get("name") or (block.get("function") or {}).get("name") if isinstance(block.get("function"), Mapping) else block.get("name")
                found.append((str(ident) if ident else f"@anon:{index}:content:{pos}", str(name or "?")))
    return found


def _tool_results(message: Mapping[str, Any]) -> list[tuple[str | None, str | None]]:
    found: list[tuple[str | None, str | None]] = []
    role = str(message.get("role", "")).lower()
    if role in {"tool", "function"}:
        ident = message.get("tool_call_id") or message.get("call_id")
        # Some provider-neutral adapters put the call id in ``id``.
        if ident is None and role == "tool":
            ident = message.get("id")
        found.append((str(ident) if ident else None,
                      str(message.get("name")) if message.get("name") else None))
    content = message.get("content")
    blocks = content if isinstance(content, list) else [content] if isinstance(content, Mapping) else []
    for block in blocks:
        if not isinstance(block, Mapping):
            continue
        typ = str(block.get("type", "")).lower()
        if typ in {"tool_result", "function_response", "tool_response"}:
            ident = block.get("tool_use_id") or block.get("tool_call_id") or block.get("call_id") or block.get("id")
            found.append((str(ident) if ident else None,
                          str(block.get("name")) if block.get("name") else None))
    return found


def _atomic_ranges(messages: Sequence[Mapping[str, Any]]) -> tuple[list[tuple[int, int]], list[str]]:
    """Return half-open ranges whose boundaries cannot split call/results."""
    ranges: list[tuple[int, int]] = []
    warnings: list[str] = []
    pending: list[tuple[str, str, int]] = []
    open_at: int | None = None
    standalone_at = 0

    for i, message in enumerate(messages):
        calls = _tool_calls(message, i)
        results = _tool_results(message)
        if calls and open_at is None:
            if standalone_at < i:
                ranges.extend((j, j + 1) for j in range(standalone_at, i))
            open_at = i
        pending.extend((ident, name, i) for ident, name in calls)

        for result_id, result_name in results:
            match = None
            if result_id is not None:
                match = next((p for p, item in enumerate(pending) if item[0] == result_id), None)
            elif result_name:
                match = next((p for p, item in enumerate(pending) if item[1] == result_name), None)
            elif pending:
                # IDs are optional in a few provider-neutral adapters. FIFO is
                # unambiguous only when exactly one call remains.
                match = 0 if len(pending) == 1 else None
            if match is None:
                warnings.append(
                    f"malformed tool pairing: orphan or mismatched result at message {i}"
                )
                if open_at is None:
                    if standalone_at < i:
                        ranges.extend((j, j + 1) for j in range(standalone_at, i))
                    ranges.append((i, i + 1))
                    standalone_at = i + 1
            else:
                pending.pop(match)

        if open_at is not None and not pending:
            ranges.append((open_at, i + 1))
            standalone_at = i + 1
            open_at = None

    if pending:
        ids = ", ".join(item[0] for item in pending)
        warnings.append(f"malformed tool pairing: missing result(s) for {ids}")
        # Keep the malformed suffix atomic.  This flags it without risking a
        # provider-invalid split.
        ranges.append((open_at if open_at is not None else standalone_at, len(messages)))
        standalone_at = len(messages)
    if standalone_at < len(messages):
        ranges.extend((j, j + 1) for j in range(standalone_at, len(messages)))

    # Defensive normalisation: malformed provider input can create overlap.
    clean: list[tuple[int, int]] = []
    cursor = 0
    for start, end in sorted(ranges):
        start = max(start, cursor)
        if end > start:
            clean.append((start, end))
            cursor = end
    if cursor < len(messages):
        clean.extend((j, j + 1) for j in range(cursor, len(messages)))
    return clean, warnings


_META_KEYS = {
    "dimensions", "width", "height", "size_x", "size_y", "source", "source_image",
    "image", "image_id", "dataset", "dataset_id", "revision", "c", "z", "t",
    "channel", "slice", "frame", "hash", "sha256", "title", "path",
}


def _metadata_for(message: Mapping[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    def visit(value: Any) -> None:
        if isinstance(value, Mapping):
            for key, item in value.items():
                low = str(key).lower()
                if low in _META_KEYS and not isinstance(item, (dict, list)) and item is not None:
                    text = str(item)
                    if len(text) <= 300 and not text.startswith("data:"):
                        out[low] = item
                if low not in {"data", "base64", "pixels", "pixel_data", "image_base64"}:
                    visit(item)
        elif isinstance(value, list):
            for item in value[:50]:
                visit(item)
    visit(message)
    if "dimensions" not in out:
        width = out.get("width", out.get("size_x"))
        height = out.get("height", out.get("size_y"))
        if width is not None and height is not None:
            out["dimensions"] = f"{width}x{height}"
    return out


def _looks_base64(text: str) -> bool:
    if len(text) < 80:
        return False
    sample = re.sub(r"\s+", "", text[:1000])
    return bool(sample) and re.fullmatch(r"[A-Za-z0-9+/=_-]+", sample) is not None


def _payload_text(value: Any) -> str:
    if isinstance(value, bytes):
        return base64.b64encode(value).decode("ascii")
    if isinstance(value, str):
        return value
    return _stable_json(value)


def _writer_reference(writer: ArtifactWriter | None, payload: str | bytes,
                      metadata: dict[str, Any], fallback: str,
                      warnings: list[str]) -> str:
    if writer is None:
        return fallback
    try:
        # The public contract is writer(payload, metadata).  Supporting a
        # one-argument callable makes small test and CLI writers convenient.
        try:
            params = inspect.signature(writer).parameters
            result = writer(payload) if len(params) == 1 else writer(payload, dict(metadata))
        except (TypeError, ValueError):
            result = writer(payload, dict(metadata))
        return str(result) if result is not None else fallback
    except Exception as exc:
        warnings.append(f"artifact writer failed for {fallback}: {type(exc).__name__}: {exc}")
        return fallback


def _make_marker(kind: str, payload: Any, message_index: int, location: str,
                 metadata: dict[str, Any], writer: ArtifactWriter | None,
                 artifacts: list[EvictedArtifact], warnings: list[str]) -> str:
    text = _payload_text(payload)
    digest = hashlib.sha256(text.encode("utf-8", errors="surrogatepass")).hexdigest()
    fallback = f"original-message:{message_index}:{location}"
    writer_meta = dict(metadata)
    writer_meta.update({"kind": kind, "sha256": digest, "message_index": message_index,
                        "location": location})
    reference = _writer_reference(writer, payload if isinstance(payload, (str, bytes)) else text,
                                  writer_meta, fallback, warnings)
    fields = [f"kind={kind}", f"source=message[{message_index}].{location}",
              f"sha256={digest}", f"chars={len(text)}"]
    preferred = ("dimensions", "source_image", "source", "image_id", "image", "dataset_id",
                 "dataset", "revision", "c", "z", "t", "channel", "slice", "frame",
                 "hash", "sha256", "title", "path", "duplicate_of")
    for key in preferred:
        if key in metadata:
            label = key.upper() if key in {"c", "z", "t"} else key
            fields.append(f"{label}={metadata[key]}")
    fields.append(f"ref={reference}")
    marker = "[COMPACTION_EVICTED " + " ".join(fields) + "]"
    artifacts.append(EvictedArtifact(kind, message_index, location, marker, digest,
                                     len(text), reference, dict(metadata)))
    return marker


def _transform_message(message: dict[str, Any], message_index: int,
                       seen_images: dict[str, str], writer: ArtifactWriter | None,
                       artifacts: list[EvictedArtifact], warnings: list[str]) -> dict[str, Any]:
    metadata = _metadata_for(message)
    role = str(message.get("role", "")).lower()

    def walk(value: Any, path: str, key_hint: str = "") -> Any:
        low = key_hint.lower()
        if isinstance(value, Mapping):
            return {key: walk(item, f"{path}.{key}" if path else str(key), str(key))
                    for key, item in value.items()}
        if isinstance(value, list):
            if low in {"pixels", "pixel_data", "pixel_values", "voxel_data", "array"} and value:
                return _make_marker("pixel_payload", value, message_index, path, metadata,
                                    writer, artifacts, warnings)
            return [walk(item, f"{path}[{pos}]", key_hint) for pos, item in enumerate(value)]
        if isinstance(value, bytes):
            return _make_marker("inline_image" if "image" in low else "binary_payload", value,
                                message_index, path, metadata, writer, artifacts, warnings)
        if not isinstance(value, str):
            return value

        inline = value.startswith("data:image/") and ";base64," in value[:100]
        base64_field = ("base64" in low or low in {"data", "bytes", "image"}) and _looks_base64(value)
        pixel_field = low in {"pixels", "pixel_data", "pixel_values", "voxel_data"}
        if inline or base64_field or pixel_field:
            payload_kind = "pixel_payload" if pixel_field else "inline_image"
            digest = hashlib.sha256(value.encode("utf-8", errors="surrogatepass")).hexdigest()
            kind = "duplicate_screenshot" if digest in seen_images else payload_kind
            marker = _make_marker(kind, value, message_index, path, metadata,
                                  writer, artifacts, warnings)
            if digest not in seen_images:
                seen_images[digest] = artifacts[-1].reference
            return marker

        image_ref = (low in {"url", "image_url", "screenshot", "capture"} or
                     "screenshot" in path.lower() or "capture" in path.lower())
        if image_ref and value:
            digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
            if digest in seen_images:
                dup_meta = dict(metadata)
                dup_meta["duplicate_of"] = seen_images[digest]
                return _make_marker("duplicate_screenshot", value, message_index, path,
                                    dup_meta, writer, artifacts, warnings)
            seen_images[digest] = f"message[{message_index}].{path}"

        log_field = low in {"log", "logs", "stdout", "stderr", "state", "state_dump", "trace"}
        tool_dump = role in {"tool", "function"} and low == "content"
        if len(value) > LONG_PAYLOAD_CHARS and (log_field or tool_dump):
            kind = "state_dump" if "state" in low or value.lstrip().startswith(("{", "[")) else "long_log"
            return _make_marker(kind, value, message_index, path, metadata,
                                writer, artifacts, warnings)
        return value

    return walk(message, "")


_CRITICAL_WORDS = re.compile(
    r"(?i)(error|exception|failed|approval|approved|refused|decision|pending|job|lineage|"
    r"calibrat|pixel[ _-]?(?:width|height|depth)|roi|result|measurement|mean|median|area|"
    r"integrated|intensity|threshold|parameter|channel|slice|frame|\bC\s*=|\bZ\s*=|\bT\s*=|"
    r"sha256|checksum|macro|run\s*\(|\.csv\b|AI_Exports|image-[0-9a-f]+|dataset-[0-9a-f]+)"
)
_NUMBER = re.compile(r"(?<![A-Za-z])[-+]?\d+(?:[.,]\d+)?(?:[eE][-+]?\d+)?")
_PATH = re.compile(r"(?:[A-Za-z]:[\\/]|/[^ ]+/|[A-Za-z0-9_.-]+\.(?:csv|tif|tiff|lif|czi|nd2|png|json)\b)", re.I)
_CODE_FENCE = re.compile(r"```(?:ijm|imagej|macro)?\s*\n.*?```", re.I | re.S)
_MACRO_SOURCE = re.compile(
    r"(?im)^\s*(?:macro\s+[^\n{]+\{|run|setThreshold|setAutoThreshold|setOption|"
    r"roiManager|selectWindow|saveAs|open|close|getDimensions|Stack\.[A-Za-z]+|"
    r"Dialog\.[A-Za-z]+|Table\.[A-Za-z]+|File\.[A-Za-z]+)\s*\("
)


def _text_parts(value: Any, path: str = "") -> list[tuple[str, str]]:
    parts: list[tuple[str, str]] = []
    if isinstance(value, str):
        parts.append((path, value))
    elif isinstance(value, Mapping):
        for key, item in value.items():
            parts.extend(_text_parts(item, f"{path}.{key}" if path else str(key)))
    elif isinstance(value, list):
        for i, item in enumerate(value):
            parts.extend(_text_parts(item, f"{path}[{i}]"))
    return parts


def _critical_and_prose(messages: Sequence[Mapping[str, Any]], head_count: int) -> tuple[list[str], list[str]]:
    critical: list[str] = []
    prose: list[str] = []
    seen: set[str] = set()

    def add(text: str) -> None:
        if text and text not in seen:
            seen.add(text)
            critical.append(text)

    def structured(value: Any, path: str = "") -> None:
        if isinstance(value, Mapping):
            # Preserve value+unit and complete result rows as one exact JSON fact.
            keys = {str(key).lower() for key in value}
            if ({"value", "unit"} <= keys or "measurement" in keys or
                    ("result" in path.lower() and any(isinstance(v, (int, float)) and not isinstance(v, bool)
                                                      for v in value.values()))):
                add(_stable_json(value))
            for key, item in value.items():
                low = str(key).lower()
                child = f"{path}.{key}" if path else str(key)
                if isinstance(item, (int, float)) and not isinstance(item, bool):
                    add(f"{child}={_stable_json(item)}")
                elif low in {"image_id", "dataset_id", "roi_id", "revision", "c", "z", "t",
                             "channel", "slice", "frame", "calibration", "hash", "sha256",
                             "csv_ref", "lineage", "status", "decision", "approval", "error",
                             "errors", "pending", "jobs", "parameters", "results", "result_rows"}:
                    add(f"{child}={_stable_json(item)}")
                if low not in {"data", "base64", "pixels", "pixel_data", "image_base64"}:
                    structured(item, child)
        elif isinstance(value, list):
            for pos, item in enumerate(value):
                structured(item, f"{path}[{pos}]")

    for index, message in enumerate(messages):
        # The current system prompt is retained verbatim outside the compacted
        # history; copying its examples into the scientific-facts lane would
        # waste context and can mislabel documentation as performed work.
        if str(message.get("role", "")).lower() == "system":
            continue
        calls = message.get("tool_calls")
        if calls:
            # Tool arguments can contain exact IJ macros and unit-bearing params.
            add(_stable_json(calls))
            for call_path, call_text in _text_parts(calls):
                if call_path.lower().endswith(("arguments", "input", "macro", "script")):
                    add(call_text)
        if isinstance(message.get("function_call"), Mapping):
            add(_stable_json(message["function_call"]))
            for call_path, call_text in _text_parts(message["function_call"]):
                if call_path.lower().endswith(("arguments", "input", "macro", "script")):
                    add(call_text)
        structured(message)
        for path, text in _text_parts(message):
            if text.startswith("[COMPACTION_EVICTED "):
                continue
            path_low = path.lower()
            if any(word in path_low for word in (
                    "macro", "script", "parameters", "calibration", "roi", "error", "approval",
                    "decision", "pending", "jobs", "lineage", "results", "result_rows", "csv_ref")):
                add(text)
                continue
            if _MACRO_SOURCE.search(text):
                add(text)
            fenced = list(_CODE_FENCE.finditer(text))
            for match in fenced:
                add(match.group(0))
            noncritical_lines: list[str] = []
            for line in text.splitlines():
                stripped = line.strip()
                if not stripped:
                    continue
                # Do not promote an enormous base64 line merely because it has digits.
                is_payload = stripped.startswith("data:image/") or (len(stripped) > 500 and _looks_base64(stripped))
                if not is_payload and (_NUMBER.search(line) or _PATH.search(line) or _CRITICAL_WORDS.search(line)):
                    add(line)
                else:
                    noncritical_lines.append(line)
            if index < head_count and noncritical_lines:
                remaining = "\n".join(noncritical_lines).strip()
                # Code is already in the verbatim lane; don't ask a summariser
                # to paraphrase the same block.
                for match in reversed(fenced):
                    remaining = remaining.replace(match.group(0), "")
                if remaining.strip():
                    prose.append(f"[message {index} {message.get('role', '?')}] {remaining.strip()}")
    return critical, prose


def plan_compaction(messages: Sequence[Mapping[str, Any]], keep_recent_tokens: int,
                    artifact_writer: ArtifactWriter | None = None) -> CompactionPlan:
    """Create a pure, deterministic compaction plan.

    The cut is made only between atomic units.  An assistant tool call and every
    matching result always remain on the same side.  If the newest atomic unit
    alone exceeds ``keep_recent_tokens``, it is retained whole and a warning is
    emitted.  Malformed tool pairing is flagged and conservatively kept atomic.

    ``artifact_writer``, when supplied, is called as ``writer(payload,
    metadata)`` and should return a stable path/reference.  Writer failure is a
    warning; the marker still points back to the original message.
    """
    if keep_recent_tokens < 0:
        raise ValueError("keep_recent_tokens must be non-negative")
    if isinstance(messages, (str, bytes)) or not isinstance(messages, Sequence):
        raise TypeError("messages must be a sequence of message mappings")
    if any(not isinstance(message, Mapping) for message in messages):
        raise TypeError("every message must be a mapping")

    original = copy.deepcopy(list(messages))
    estimated_before = estimate_tokens(original)
    warnings: list[str] = []
    artifacts: list[EvictedArtifact] = []
    seen_images: dict[str, str] = {}
    transformed = [
        _transform_message(copy.deepcopy(dict(message)), i, seen_images,
                           artifact_writer, artifacts, warnings)
        for i, message in enumerate(original)
    ]

    ranges, pair_warnings = _atomic_ranges(original)
    warnings.extend(pair_warnings)

    selected_start = len(transformed)
    running = 0
    if ranges:
        # Retain at least the newest atomic unit, including when the caller asks
        # for a zero-token recent tail.
        for start, end in reversed(ranges):
            unit_tokens = estimate_tokens(transformed[start:end])
            if (selected_start < len(transformed)
                    and running + unit_tokens > keep_recent_tokens):
                break
            selected_start = start
            running += unit_tokens
            if running >= keep_recent_tokens:
                break
        newest_start, newest_end = ranges[-1]
        newest_tokens = estimate_tokens(transformed[newest_start:newest_end])
        if newest_tokens > keep_recent_tokens:
            warnings.append(
                f"oversized recent atomic unit: messages {newest_start}:{newest_end} "
                f"estimate {newest_tokens} > keep_recent_tokens {keep_recent_tokens}"
            )

    head = transformed[:selected_start]
    recent = transformed[selected_start:]
    # Extract the verbatim lane from the originals before logs/state are replaced.
    # Prose comes from the sanitized head, so it can never re-introduce a binary payload.
    critical, _ = _critical_and_prose(original, 0)
    _, prose = _critical_and_prose(transformed, len(head))
    estimated_after = estimate_tokens(transformed)
    priority = {"inline_image": 0, "pixel_payload": 0, "binary_payload": 0,
                "duplicate_screenshot": 1, "long_log": 2, "state_dump": 2}
    artifacts.sort(key=lambda item: (priority.get(item.kind, 3), item.message_index, item.location))
    return CompactionPlan(
        head_messages=head,
        recent_messages=recent,
        critical_facts=critical,
        evicted_artifacts=artifacts,
        warnings=warnings,
        estimated_tokens_before=estimated_before,
        estimated_tokens_after=estimated_after,
        prose_to_summarize=prose,
    )


__all__ = [
    "CHARS_PER_TOKEN", "LONG_PAYLOAD_CHARS", "CompactionPlan", "EvictedArtifact",
    "estimate_tokens", "should_compact", "plan_compaction",
]
