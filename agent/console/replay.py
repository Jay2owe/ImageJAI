"""Read-only conversation replay from evidence, with older-session fallback."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping


@dataclass(frozen=True)
class ReplayEntry:
    kind: str
    text: str = ""
    name: str = ""
    arguments: dict = field(default_factory=dict)
    ok: bool = True
    artifact: dict | None = None
    summary: str | None = None


def _text(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(str(part.get("text", "")) for part in value if isinstance(part, dict))
    return "" if value is None else json.dumps(value, ensure_ascii=False)


def _control(text: str) -> bool:
    return text.lstrip().startswith(("<imagejai-action>", "<imagejai-result>"))


def conversation_for_switch(messages):
    """Portable text context; native vendor tool IDs are not reused elsewhere.

    Images stay local when changing vendors. Persisted action receipts remain
    separate and still prevent repeating completed Fiji mutations.
    """
    out = []
    for message in messages:
        role = message.get("role")
        if role == "system":
            continue
        text = _text(message.get("content"))
        calls = message.get("tool_calls")
        if calls:
            text += "\nPrevious tool requests: " + json.dumps(calls, ensure_ascii=False)
        for part in message.get("content") if isinstance(message.get("content"), list) else ():
            if isinstance(part, dict) and part.get("type") in {"tool_use", "tool_result"}:
                text += "\nPrevious tool evidence: " + json.dumps(part, ensure_ascii=False)
        if role == "tool":
            text = f"Previous {message.get('name', 'tool')} result: " + text
        if text:
            row = {"role": "assistant" if role == "assistant" else "user", "content": text}
            if message.get("name") == "imagejai_tool_result":
                row["name"] = "imagejai_tool_result"
            out.append(row)
    return out


def _ref(event: Mapping, path: str) -> dict:
    return next((dict(ref) for ref in event.get("artifact_refs", [])
                 if isinstance(ref, Mapping) and ref.get("path") == path), {"path": path})


def replay_entries(events: Iterable[Mapping], messages: Iterable[Mapping] = (),
                   receipts: Mapping | None = None) -> list[ReplayEntry]:
    rows = list(events)
    # /clear retains scientific evidence; its previous conversation must not
    # reappear when the same session is opened again.
    cleared = False
    for index, event in enumerate(rows):
        payload = event.get("payload")
        if event.get("type") == "decision" and isinstance(payload, Mapping) and payload.get("kind") == "conversation_cleared":
            start, cleared = index + 1, True
    if cleared:
        rows = rows[start:]
    has_text = any(row.get("type") in {"user", "assistant"} for row in rows)
    output, calls = [], {}
    for event in rows:
        kind, payload = event.get("type"), event.get("payload")
        if not isinstance(payload, Mapping):
            continue
        if kind == "decision" and payload.get("kind") in {"side_user", "side_assistant", "side_thinking"}:
            path = payload.get("text_artifact")
            output.append(ReplayEntry(payload["kind"], text=_text(payload.get("text", payload.get("head", ""))),
                artifact=_ref(event, path) if isinstance(path, str) else None))
        elif kind in {"user", "assistant", "thinking"}:
            text = _text(payload.get("text"))
            path = payload.get("text_artifact")
            if not _control(text):
                output.append(ReplayEntry(kind, text=text,
                                          artifact=_ref(event, path) if isinstance(path, str) else None))
        elif kind == "tool_call":
            args = payload.get("arguments")
            if not isinstance(args, dict):
                continue
            calls[payload.get("correlation_id")] = payload
            output.append(ReplayEntry(kind, name=str(payload.get("tool", "tool")), arguments=args))
        elif kind == "tool_result":
            call = calls.get(payload.get("correlation_id"), {})
            value = payload.get("result", "")
            path = value.get("artifact") if isinstance(value, dict) else None
            output.append(ReplayEntry(kind, name=str(payload.get("tool") or call.get("tool") or "tool"),
                arguments=call.get("arguments", {}), ok=bool(payload.get("ok", True)),
                text=_text(value.get("head", "")) if path else _text(value),
                artifact=_ref(event, path) if isinstance(path, str) else None,
                summary=value.get("summary") if path else None))
    if has_text or cleared:
        return output
    # Pre-evidence sessions retained provider messages and wrapped receipts.
    fallback = []
    evidence_tools = bool(calls)
    for message in messages:
        role = message.get("role")
        text = _text(message.get("content"))
        if message.get("name") == "imagejai_tool_result":
            if evidence_tools:
                continue
            try:
                saved = json.loads(text.removeprefix("<imagejai-result>").removesuffix("</imagejai-result>"))
                receipt = (receipts or {}).get(saved.get("id"), saved)
                args = receipt.get("arguments") or {}
                name = str(receipt["tool"])
                fallback.extend([ReplayEntry("tool_call", name=name, arguments=args),
                    ReplayEntry("tool_result", name=name, arguments=args,
                                ok=bool(receipt.get("ok")), text=_text(receipt.get("result")))])
            except (ValueError, TypeError, KeyError, AttributeError):
                continue
        elif role in {"user", "assistant"} and text and not _control(text):
            fallback.append(ReplayEntry(role, text=text))
        elif role == "tool" and text and not evidence_tools:
            fallback.append(ReplayEntry("tool_result", name=str(message.get("name") or "tool"), text=text))
    return fallback + output
