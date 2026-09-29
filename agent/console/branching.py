"""Copy conversation evidence without claiming to restore Fiji's image state."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
from collections import Counter
from .evidence import EvidenceJournal

def check_cut(messages, count):
    if type(count) is not int or count < 0 or count > len(messages):
        raise ValueError("Branch position must be a valid message count")
    pending = set()
    functions = Counter()
    for message in messages[:count]:
        for call in message.get("tool_calls", []):
            if isinstance(call, dict):
                pending.add(call.get("id"))
        for call in message.get("function_calls", []):
            functions[call.get("name")] += 1
        response = message.get("function_response")
        if isinstance(response, dict):
            name = response.get("name")
            if functions[name] <= 0:
                raise ValueError("Cannot branch malformed function history")
            functions[name] -= 1
        for block in message.get("content") if isinstance(message.get("content"), list) else []:
            if not isinstance(block, dict):
                continue
            if block.get("type") == "tool_use":
                pending.add(block.get("id"))
            elif block.get("type") == "tool_result":
                ident = block.get("tool_use_id")
                if ident not in pending:
                    raise ValueError("Cannot branch malformed tool history")
                pending.remove(ident)
        if message.get("role") == "tool":
            ident = message.get("tool_call_id")
            if ident not in pending:
                raise ValueError("Cannot branch malformed tool history")
            pending.remove(ident)
    if pending or any(functions.values()):
        raise ValueError("Cannot branch between a tool request and its result")
    if count and count < len(messages):
        last = messages[count-1]
        from .wrapped_tools import parse_action
        if last.get("role") != "assistant" or last.get("tool_calls") or last.get("function_calls") or parse_action(str(last.get("content", ""))):
            raise ValueError("Choose the end of a completed assistant reply")

def copy_evidence(root: Path, source_id: str, target_id: str, messages, count):
    root = root.resolve()
    source = (root / source_id).resolve()
    target = (root / target_id).resolve()
    if source.parent != root or target.parent != root or target.exists():
        raise ValueError("Invalid or occupied branch directory")
    if not source.exists():
        return
    events = EvidenceJournal(root, source_id).read_events()
    if count < len(messages):
        from .wrapped_tools import parse_action
        answers = sum(1 for m in messages[:count] if m.get("role") == "assistant" and not m.get("tool_calls") and not parse_action(str(m.get("content", ""))))
        kept, seen = [], 0
        for event in events:
            if seen >= answers:
                break
            kept.append(event)
            if event.get("type", event.get("event_type")) == "assistant":
                seen += 1
        if seen != answers:
            raise ValueError("Saved evidence cannot identify that completed reply; branch the complete conversation instead")
        events = kept
    artifacts = source / "artifacts"
    files = list(artifacts.rglob("*")) if artifacts.is_dir() else []
    if any(path.is_symlink() or not path.resolve().is_relative_to(source) for path in files):
        raise ValueError("Branch artifacts must stay inside their session")
    size = sum(path.stat().st_size for path in files if path.is_file())
    if shutil.disk_usage(root).free < size + 16 * 1024 * 1024:
        raise ValueError("Not enough free space to copy the branch artifacts")
    with tempfile.TemporaryDirectory(prefix="branch-", dir=root) as temporary:
        staging = Path(temporary) / target_id
        staging.mkdir()
        if artifacts.is_dir():
            shutil.copytree(artifacts, staging / "artifacts")
            for path in files:
                if path.is_file():
                    copied = staging / path.relative_to(source)
                    from .validation import file_hash as digest
                    if digest(path) != digest(copied):
                        raise ValueError("An artifact changed while creating the branch")
        journal = EvidenceJournal(Path(temporary), target_id)
        for event in events:
            kind = event.get("type", event.get("event_type"))
            journal.append(kind, event.get("payload", {}), event.get("artifact_refs", []))
        journal.append("decision", {"kind": "session_forked", "parent_session_id": source_id, "source_message_count": count,
                                    "fiji_state_restored": False})
        if staging.parent.resolve() != Path(temporary).resolve() or target.parent != root:
            raise ValueError("Branch destination leaves the session root")
        staging.rename(target)
