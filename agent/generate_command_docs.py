#!/usr/bin/env python
"""Validate the canonical TCP command manifest and generate API documentation."""

from __future__ import annotations

import argparse
import ast
import json
import os
import re
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "agent" / "command_manifest.json"
SERVER_PATH = ROOT / "src" / "main" / "java" / "imagejai" / "engine" / "TCPCommandServer.java"
PYTHON_CLIENT_PATH = ROOT / "agent" / "ij.py"
API_DOC_PATH = ROOT / "docs" / "COMMAND_API.md"
README_PATH = ROOT / "README.md"
BASE_CONTEXT_PATH = ROOT / "agent" / "contexts" / "base.md"
README_START = "<!-- BEGIN GENERATED COMMAND SUMMARY -->"
README_END = "<!-- END GENERATED COMMAND SUMMARY -->"
CONTEXT_START = "<!-- BEGIN GENERATED COMMAND COUNT -->"
CONTEXT_END = "<!-- END GENERATED COMMAND COUNT -->"
_VALID_TRANSPORT = frozenset(("request_response", "stream"))
_VALID_CLASSIFICATION = frozenset(("read_only", "mutation", "administrative"))
_VALID_AUTH = frozenset(("public", "session_optional", "session_required"))
_VALID_COVERAGE = frozenset(("convenience", "raw"))


def _read_bounded(path: Path, limit: int = 2 * 1024 * 1024) -> str:
    size = path.stat().st_size
    if size > limit:
        raise ValueError(f"refusing to read oversized input {path}: {size} > {limit} bytes")
    with path.open("rb") as handle:
        payload = handle.read(limit + 1)
    if len(payload) > limit:
        raise ValueError(
            f"refusing to read oversized input {path}: more than {limit} bytes"
        )
    return payload.decode("utf-8")


def load_manifest(path: Path = MANIFEST_PATH) -> dict:
    data = json.loads(_read_bounded(path))
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        raise ValueError("command manifest schema_version must be 1")
    if data.get("product_version") != "0.3.0":
        raise ValueError("command manifest product_version must be 0.3.0")
    if data.get("protocol") != "ImageJAI TCP JSONL":
        raise ValueError("command manifest protocol must be ImageJAI TCP JSONL")
    commands = data.get("commands")
    if not isinstance(commands, list) or not commands:
        raise ValueError("command manifest must contain a non-empty commands list")
    names = [entry.get("name") for entry in commands if isinstance(entry, dict)]
    if len(names) != len(commands) or any(not isinstance(name, str) or not name for name in names):
        raise ValueError("every command must be an object with a non-empty name")
    if names != sorted(names):
        raise ValueError("manifest commands must be sorted by name")
    if len(names) != len(set(names)):
        raise ValueError("manifest command names must be unique")

    for entry in commands:
        name = entry["name"]
        if entry.get("transport") not in _VALID_TRANSPORT:
            raise ValueError(f"{name}: invalid transport")
        if entry.get("classification") not in _VALID_CLASSIFICATION:
            raise ValueError(f"{name}: invalid classification")
        if entry.get("authentication") not in _VALID_AUTH:
            raise ValueError(f"{name}: invalid authentication")
        request = entry.get("request")
        if not isinstance(request, dict):
            raise ValueError(f"{name}: request must be an object")
        for key in ("required", "optional"):
            values = request.get(key)
            if not isinstance(values, list) or any(not isinstance(item, str) for item in values):
                raise ValueError(f"{name}: request.{key} must be a string list")
            if any(not item.strip() for item in values) or len(values) != len(set(values)):
                raise ValueError(
                    f"{name}: request.{key} must contain unique non-blank fields"
                )
        overlap = set(request["required"]).intersection(request["optional"])
        if overlap:
            raise ValueError(
                f"{name}: request fields cannot be both required and optional: "
                + ", ".join(sorted(overlap))
            )
        any_of = request.get("required_any_of", [])
        if (not isinstance(any_of, list)
                or any(not isinstance(group, list) or len(group) < 2
                       or any(not isinstance(item, str) or not item.strip()
                              for item in group)
                       or len(group) != len(set(group))
                       for group in any_of)):
            raise ValueError(
                f"{name}: request.required_any_of must contain unique non-blank string groups"
            )
        if not isinstance(entry.get("reply"), dict) or not entry["reply"].get("type"):
            raise ValueError(f"{name}: reply.type is required")
        if not isinstance(entry.get("capabilities"), list):
            raise ValueError(f"{name}: capabilities must be a list")
        python = entry.get("python")
        if not isinstance(python, dict) or python.get("coverage") not in _VALID_COVERAGE:
            raise ValueError(f"{name}: invalid Python coverage")
        helpers = python.get("helpers")
        if not isinstance(helpers, list) or any(not isinstance(item, str) for item in helpers):
            raise ValueError(f"{name}: python.helpers must be a string list")
        if python["coverage"] == "convenience" and not helpers:
            raise ValueError(f"{name}: convenience coverage requires a helper")
        if python["coverage"] == "raw":
            if helpers:
                raise ValueError(f"{name}: raw coverage cannot claim convenience helpers")
            if "imagej_command" not in entry.get("summary", ""):
                raise ValueError(f"{name}: raw coverage must document imagej_command")
        if "dedup_hash" in entry and not isinstance(entry["dedup_hash"], bool):
            raise ValueError(f"{name}: dedup_hash must be boolean")
        if entry.get("dedup_hash") and entry["classification"] != "read_only":
            raise ValueError(f"{name}: only read-only commands can use hash deduplication")
    return data


def extract_server_commands(path: Path = SERVER_PATH) -> set[str]:
    source = _read_bounded(path, 4 * 1024 * 1024)
    dispatch = _java_method_body(source, "dispatchCore")
    request_response = set(re.findall(
        r'"([a-z0-9_]+)"\.equals\(command\)', dispatch))
    stream = set(re.findall(r'"([a-z0-9_]+)"\.equals\(commandName\)', source))
    return request_response | stream


def _java_method_body(source: str, method_name: str) -> str:
    match = re.search(
        r"\b" + re.escape(method_name) + r"\s*\([^)]*\)\s*\{", source)
    if match is None:
        raise ValueError(f"server source has no {method_name} method")
    depth = 1
    index = match.end()
    start = index
    state = "code"
    escaped = False
    while index < len(source) and depth:
        char = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if state == "line_comment":
            if char in "\r\n":
                state = "code"
        elif state == "block_comment":
            if char == "*" and following == "/":
                state = "code"
                index += 1
        elif state in ("string", "character"):
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif ((state == "string" and char == '"')
                  or (state == "character" and char == "'")):
                state = "code"
        elif char == "/" and following == "/":
            state = "line_comment"
            index += 1
        elif char == "/" and following == "*":
            state = "block_comment"
            index += 1
        elif char == '"':
            state = "string"
        elif char == "'":
            state = "character"
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        index += 1
    if depth:
        raise ValueError(f"server source has an unterminated {method_name} method")
    return source[start:index - 1]


def extract_python_api(path: Path = PYTHON_CLIENT_PATH) -> tuple[set[str], set[str]]:
    tree = ast.parse(_read_bounded(path, 4 * 1024 * 1024), filename=str(path))
    functions = {
        node.name for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    }
    exported: set[str] = set()
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        if any(isinstance(target, ast.Name) and target.id == "__all__" for target in node.targets):
            value = ast.literal_eval(node.value)
            if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
                raise ValueError("agent/ij.py __all__ must be a literal string list")
            exported = set(value)
            break
    if not exported:
        raise ValueError("agent/ij.py has no literal __all__ declaration")
    return functions, exported


def extract_python_helper_commands(
        path: Path = PYTHON_CLIENT_PATH) -> dict[str, set[str]]:
    """Map helper names to command literals they send, including delegation."""
    tree = ast.parse(_read_bounded(path, 4 * 1024 * 1024), filename=str(path))
    direct: dict[str, set[str]] = {}
    calls: dict[str, set[str]] = {}
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        direct.setdefault(node.name, set())
        calls.setdefault(node.name, set())
        for child in ast.walk(node):
            if isinstance(child, ast.Dict):
                for key, value in zip(child.keys, child.values):
                    if (_literal_string(key) == "command"
                            and _literal_string(value) is not None):
                        direct[node.name].add(_literal_string(value))
            elif isinstance(child, ast.Assign):
                value = _literal_string(child.value)
                if value is None:
                    continue
                for target in child.targets:
                    if (isinstance(target, ast.Subscript)
                            and _literal_string(target.slice) == "command"):
                        direct[node.name].add(value)
            elif isinstance(child, ast.Call):
                if isinstance(child.func, ast.Name):
                    calls[node.name].add(child.func.id)
                elif isinstance(child.func, ast.Attribute):
                    calls[node.name].add(child.func.attr)

    resolved = {name: set(values) for name, values in direct.items()}
    changed = True
    while changed:
        changed = False
        for name, callees in calls.items():
            before = len(resolved[name])
            for callee in callees:
                resolved[name].update(resolved.get(callee, ()))
            changed = changed or len(resolved[name]) != before
    return resolved


def _literal_string(node):
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None


def validate_coverage(data: dict) -> dict[str, int]:
    commands = data["commands"]
    manifest_names = {entry["name"] for entry in commands}
    server_names = extract_server_commands()
    if manifest_names != server_names:
        raise ValueError(
            "server/manifest command mismatch: manifest-only=%s server-only=%s"
            % (sorted(manifest_names - server_names), sorted(server_names - manifest_names))
        )
    functions, exported = extract_python_api()
    helper_commands = extract_python_helper_commands()
    for entry in commands:
        for helper in entry["python"]["helpers"]:
            if helper not in functions:
                raise ValueError(f"{entry['name']}: missing Python helper {helper}")
            if helper not in exported:
                raise ValueError(f"{entry['name']}: helper {helper} is not exported by __all__")
        if (entry["python"]["coverage"] == "convenience"
                and not any(entry["name"] in helper_commands.get(helper, set())
                            for helper in entry["python"]["helpers"])):
            raise ValueError(
                f"{entry['name']}: declared helpers do not send this command"
            )
    return {
        "total": len(commands),
        "request_response": sum(entry["transport"] == "request_response" for entry in commands),
        "stream": sum(entry["transport"] == "stream" for entry in commands),
        "convenience": sum(entry["python"]["coverage"] == "convenience" for entry in commands),
        "raw": sum(entry["python"]["coverage"] == "raw" for entry in commands),
    }


def _request_text(entry: dict) -> str:
    request = entry["request"]
    parts = []
    if request["required"]:
        parts.append("required: " + ", ".join(f"`{item}`" for item in request["required"]))
    for group in request.get("required_any_of", []):
        parts.append("one of: " + "/".join(f"`{item}`" for item in group))
    if request["optional"]:
        parts.append("optional: " + ", ".join(f"`{item}`" for item in request["optional"]))
    if entry.get("dedup_hash"):
        parts.append("hash cache: optional `if_none_match`")
    return "; ".join(parts) if parts else "none"


def render_api(data: dict, counts: dict[str, int]) -> str:
    lines = [
        "# ImageJAI TCP Command API",
        "",
        "<!-- Auto-generated by agent/generate_command_docs.py. Do not edit by hand. -->",
        "",
        (f"Version {data['product_version']} exposes {counts['total']} commands: "
         f"{counts['request_response']} request/response and {counts['stream']} streaming. "
         f"The Python client has convenience helpers for {counts['convenience']}; "
         f"the remaining {counts['raw']} are explicitly available through "
         "`imagej_command({...})`."),
        "",
        "The canonical machine-readable contract is "
        "[`agent/command_manifest.json`](../agent/command_manifest.json) and is packaged "
        "inside the plugin jar as `imagejai/command_manifest.json`.",
        "",
        ("The table lists command-specific fields. Every request also carries `command`; "
         "authenticated sessions carry `session_id` and `token`. The Python client adds "
         "those session fields automatically after `hello`."),
        "",
        "| Command | Class | Request fields | Reply | Auth/capabilities | Python | Description |",
        "|---|---|---|---|---|---|---|",
    ]
    for entry in data["commands"]:
        auth = entry["authentication"]
        if entry["capabilities"]:
            auth += "; " + ", ".join(f"`{item}`" for item in entry["capabilities"])
        py = entry["python"]
        python_text = (", ".join(f"`{item}`" for item in py["helpers"])
                       if py["coverage"] == "convenience"
                       else "raw: `imagej_command`")
        classification = entry["classification"].replace("_", " ")
        if entry["transport"] == "stream":
            classification += " (stream)"
        lines.append(
            "| `%s` | %s | %s | `%s` | %s | %s | %s |" % (
                entry["name"], classification, _request_text(entry),
                entry["reply"]["type"], auth.replace("_", " "), python_text,
                entry["summary"].replace("|", "\\|"),
            )
        )
    return "\n".join(lines) + "\n"


def render_readme_summary(data: dict, counts: dict[str, int]) -> str:
    return "\n".join([
        README_START,
        (f"ImageJAI {data['product_version']} exposes **{counts['total']} TCP commands** "
         f"({counts['request_response']} request/response plus {counts['stream']} live stream). "
         f"`agent/ij.py` provides convenience helpers for {counts['convenience']}; "
         f"the other {counts['raw']} are explicitly available through "
         "`imagej_command({...})`. See the generated "
         "[`docs/COMMAND_API.md`](docs/COMMAND_API.md) or the canonical "
         "[`agent/command_manifest.json`](agent/command_manifest.json)."),
        README_END,
    ])


def render_context_summary(counts: dict[str, int]) -> str:
    return "\n".join([
        CONTEXT_START,
        (f"JSON out. The {counts['total']}-command surface covers macro execution, state "
         "inspection, plugin probing, screenshot capture, results-table reads, dialog "
         "interaction, undo branches, and a live event stream. Python convenience helpers "
         f"cover {counts['convenience']} commands; use `imagej_command({{...}})` for the "
         f"{counts['raw']} commands documented as raw-only."),
        CONTEXT_END,
    ])


def replace_marked(text: str, start: str, end: str, replacement: str, path: Path) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError(f"{path} must contain exactly one {start}/{end} generated section")
    before, rest = text.split(start, 1)
    _old, after = rest.split(end, 1)
    return before + replacement + after


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def generate(check: bool = False) -> list[Path]:
    data = load_manifest()
    counts = validate_coverage(data)
    expected = {API_DOC_PATH: render_api(data, counts)}
    readme = _read_bounded(README_PATH)
    expected[README_PATH] = replace_marked(
        readme, README_START, README_END, render_readme_summary(data, counts), README_PATH
    )
    base = _read_bounded(BASE_CONTEXT_PATH)
    expected[BASE_CONTEXT_PATH] = replace_marked(
        base, CONTEXT_START, CONTEXT_END, render_context_summary(counts), BASE_CONTEXT_PATH
    )
    changed = []
    for path, content in expected.items():
        current = _read_bounded(path) if path.exists() else None
        if current != content:
            changed.append(path)
            if not check:
                _atomic_write(path, content)
    if check and changed:
        raise SystemExit("generated command documentation is stale: " + ", ".join(str(p) for p in changed))
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail instead of rewriting stale outputs")
    args = parser.parse_args()
    changed = generate(check=args.check)
    if args.check:
        print("command manifest and generated documentation are current")
    else:
        print("updated %d generated file(s)" % len(changed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
