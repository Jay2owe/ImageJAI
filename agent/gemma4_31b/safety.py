"""Pre-flight safety checks and audit logging for macros and scripts.

Every macro the agent proposes is passed through check_macro()
before it reaches Fiji. The check scans for save-family calls and
rejects any path that does not live inside the current
AI_Exports/ folder. Every macro and script that actually runs is
appended to audit.log; failures are appended to friction.log for
development review.

None of the logging functions raise: a broken filesystem or
missing export folder must never take down the chat loop.
"""

from __future__ import annotations

import collections
import datetime
import json
import os
import re
import threading

from . import active_image


_log_lock = threading.Lock()
_SESSION_ID = "gemma-{}-p{}".format(
    datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S"),
    os.getpid(),
)
_RECENT_EXECUTIONS = collections.deque(maxlen=4)


# Macro calls that may touch the host filesystem. Calls are located on a
# string/comment-masked source view, then their arguments are parsed from the
# comment-free view at the same offsets. This prevents documentation such as
# ``print("File.delete(...)")`` from becoming a false positive.
_FILESYSTEM_CALL_START = re.compile(
    r"(?<![\w.])((?:File|IJ)\s*\.\s*[A-Za-z_][A-Za-z0-9_]*|"
    r"saveAs|save|getFileList|getDirectory|openVirtual|open|run|doCommand)\s*\(",
    re.IGNORECASE,
)
_FILESYSTEM_PROPERTY = re.compile(
    r"\bFile\s*\.\s*(directory|nameWithoutExtension|name|separator)\b(?!\s*\()",
    re.IGNORECASE,
)

# Macro execution is not a host-file-reading capability. This set covers the
# File.* surface documented in agent/references/file-formats-saving-reference,
# plus the equivalent global input and directory primitives.
_FILESYSTEM_READ_CALLS = frozenset(
    {
        "open",
        "openvirtual",
        "getfilelist",
        "getdirectory",
        "file.openasstring",
        "file.openasrawstring",
        "file.openurlasstring",
        "file.openurl",
        "file.opensequence",
        "file.opendialog",
        "file.exists",
        "file.isfile",
        "file.isdirectory",
        "file.length",
        "file.getlength",
        "file.lastmodified",
        "file.datelastmodified",
        "file.getdefaultdir",
        "file.getabsolutepath",
        "file.directory",
        "file.name",
        "file.namewithoutextension",
    }
)

# Copy is categorical because it reads an arbitrary source even when its
# destination is safe. setDefaultDir mutates process-wide path resolution.
_FILESYSTEM_DESTRUCTIVE_CALLS = frozenset(
    {"file.delete", "file.rename", "file.copy", "file.setdefaultdir"}
)

# Path argument (zero-based) for output writes retained when the argument is
# one literal resolved beneath the current AI_Exports directory. IJ.saveAs
# accepts either (format, path) or (image, format, path), hence -1.
_FILESYSTEM_WRITE_PATH_ARGS = {
    "saveas": 1,
    "save": 0,
    "ij.saveas": -1,
    "ij.save": -1,
    "file.savestring": 1,
    "file.append": 1,
    "file.makedirectory": 0,
    "file.mkdir": 0,
    "file.open": 0,
}

# Pure path-string helpers and writer-handle operations do not themselves
# inspect or select a host path. File.open remains the path gate for print/
# File.write output handles.
_FILESYSTEM_BENIGN_CALLS = frozenset(
    {
        "file.getname",
        "file.getnamewithoutextension",
        "file.getdirectory",
        "file.getparent",
        "file.close",
        "file.separator",
    }
)

_READ_MENU_COMMANDS = frozenset(
    {
        "open",
        "open...",
        "open next",
        "open samples",
        "image sequence...",
        "url...",
        "bio-formats importer",
        "bio-formats remote importer",
    }
)
_DESTRUCTIVE_MENU_COMMANDS = frozenset({"save", "save as...", "revert"})
_RUN_SAVE_PATH = re.compile(
    r"(?:^|\s)save\s*=\s*(?:\[([^\]]+)\]|([^\s]+))", re.IGNORECASE
)


# ImageJ macro primitives that escape the macro sandbox and execute host/JVM
# code. Direct calls are matched against a comment/string-masked view so words
# in documentation and print statements remain harmless. ``run(...)`` needs a
# second pass with string contents retained because the command name is its
# first string argument.
_HOST_CODE_DIRECT = [
    (
        "exec",
        re.compile(r"(?<![\w.])exec\s*\(", re.IGNORECASE),
    ),
    (
        "eval",
        re.compile(r"(?<![\w.])eval\s*\(", re.IGNORECASE),
    ),
    (
        "call",
        re.compile(r"(?<![\w.])call\s*\(", re.IGNORECASE),
    ),
    (
        "runMacro",
        re.compile(r"(?<![\w.])runMacro(?:File)?\s*\(", re.IGNORECASE),
    ),
    (
        "IJ.runMacro",
        re.compile(r"\bIJ\s*\.\s*runMacro(?:File)?\s*\(", re.IGNORECASE),
    ),
    (
        "Ext.*",
        re.compile(r"\bExt\s*\.\s*[A-Za-z_][A-Za-z0-9_]*\s*\(", re.IGNORECASE),
    ),
]
_COMMAND_CALL_START = re.compile(
    r"(?<![\w.])(run|doCommand)\s*\(",
    re.IGNORECASE,
)
_HOST_COMMAND_TOKENS = frozenset(
    {
        "script",
        "interpreter",
        "groovy",
        "beanshell",
        "javascript",
        "jython",
        "clojure",
    }
)
_HOST_COMMAND_PHRASES = ("compile and run", "run macro")


def check_macro(code: str) -> str | None:
    """Scan a macro for host-code escapes and filesystem access."""
    if not isinstance(code, str) or not code:
        return None

    host_code_error = check_host_code(code)
    if host_code_error is not None:
        return host_code_error
    return check_filesystem(code)


def check_filesystem(code: str, export_folder: str | None = None) -> str | None:
    """Reject reads/destruction and constrain output writes to AI_Exports.

    ``export_folder`` is optional for deterministic callers/tests. Unknown
    File.* calls and malformed/dynamic path arguments fail closed so a new
    ImageJ filesystem primitive cannot silently inherit macro permission.
    """
    if not isinstance(code, str) or not code:
        return None

    comments_removed, masked = _macro_views(code)
    write_sites: list[tuple[str, str | None]] = []

    for match in _FILESYSTEM_PROPERTY.finditer(masked):
        if match.group(1).casefold() != "separator":
            return _filesystem_rejection(
                "File.{}".format(match.group(1)),
                "reveals the last selected host path",
            )

    for match in _FILESYSTEM_CALL_START.finditer(masked):
        raw_name = match.group(1)
        name = re.sub(r"\s+", "", raw_name).casefold()
        args = _parse_call_arguments(comments_removed, match.end() - 1)

        if name in _FILESYSTEM_READ_CALLS:
            return _filesystem_rejection(raw_name, "reads or enumerates host data")
        if name in _FILESYSTEM_DESTRUCTIVE_CALLS:
            return _filesystem_rejection(raw_name, "can modify or remove host files")

        if name in ("run", "docommand"):
            if args is None or not args:
                return _filesystem_rejection(raw_name, "has malformed arguments")
            command = _literal_string(args[0])
            if command is None:
                # check_host_code already rejects this, but keep this public
                # policy independently fail-closed.
                return _filesystem_rejection(raw_name, "uses a dynamic command")
            normalized_command = re.sub(r"\s+", " ", command).strip().casefold()
            if normalized_command in _READ_MENU_COMMANDS:
                return _filesystem_rejection(
                    '{}("{}")'.format(raw_name, command),
                    "opens or imports host data",
                )
            if normalized_command in _DESTRUCTIVE_MENU_COMMANDS:
                return _filesystem_rejection(
                    '{}("{}")'.format(raw_name, command),
                    "can overwrite the current host file",
                )
            if len(args) >= 2:
                options = _literal_string(args[1])
                if options is None:
                    if re.search(r"\bsave\s*=", args[1], re.IGNORECASE):
                        return _filesystem_rejection(
                            raw_name, "uses a dynamic save path"
                        )
                    continue
                save_match = _RUN_SAVE_PATH.search(options)
                if save_match is not None:
                    write_sites.append(
                        ('{}(..., "save=...")'.format(raw_name),
                         save_match.group(1) or save_match.group(2))
                    )
            continue

        if name in _FILESYSTEM_WRITE_PATH_ARGS:
            arg_index = _FILESYSTEM_WRITE_PATH_ARGS[name]
            if args is None or not args:
                write_sites.append((raw_name, None))
                continue
            index = arg_index if arg_index >= 0 else len(args) - 1
            raw_path = _literal_string(args[index]) if index < len(args) else None
            write_sites.append((raw_name, raw_path))
            continue

        if name.startswith("file.") and name not in _FILESYSTEM_BENIGN_CALLS:
            return _filesystem_rejection(
                raw_name, "is an unrecognised File.* primitive"
            )

    if not write_sites:
        return None

    folder = export_folder
    if folder is None:
        folder = active_image.current_export_folder()
    if folder is None:
        if not active_image.is_any_image_open():
            return (
                "No image is open. Open an image first, or launch the agent "
                "with --export-dir PATH to set a fallback folder for macro "
                "outputs."
            )
        return (
            "The active image has no file on disk (a sample like 'Blobs', a "
            "File > New image, or an unsaved duplicate), so there is no folder "
            "to sit AI_Exports/ next to. The image IS open — save it to disk "
            "first, or launch the agent with --export-dir PATH to set a "
            "fallback output folder."
        )
    folder_abs = os.path.realpath(os.path.abspath(folder))

    for call_name, raw_path in write_sites:
        if raw_path is None:
            return (
                "{} output path is not one plain string literal. Refusing to "
                "run; write only beneath {}."
            ).format(call_name, folder_abs)
        resolved = os.path.realpath(os.path.abspath(raw_path))
        if not _is_within(resolved, folder_abs):
            return (
                "{} path '{}' is outside the current AI_Exports folder "
                "({}). Move the output into that folder and try again."
                .format(call_name, resolved, folder_abs)
            )
    return None


def _filesystem_rejection(primitive: str, reason: str) -> str:
    return (
        "Macro filesystem primitive '{}' is not allowed because it {}. "
        "Refusing before Fiji; use a separately elevated local run_script "
        "capability when host-file access is genuinely required."
    ).format(primitive, reason)


def check_host_code(code: str) -> str | None:
    """Reject macro-language escapes that can execute host or JVM code.

    This check runs before any Fiji request. Safe ImageJ macro operations stay
    available to default/cloud providers; only the escape primitives require a
    separately elevated host-code tool.
    """
    if not isinstance(code, str) or not code:
        return None

    comments_removed, strings_and_comments_masked = _macro_views(code)
    for label, detector in _HOST_CODE_DIRECT:
        if detector.search(strings_and_comments_masked):
            return _host_code_rejection(label)

    # Locate call sites on the fully masked view so text such as
    # print("run(\"Script...\")") cannot masquerade as an invocation. Parse
    # the first argument from the comment-free source at the same offset.
    for match in _COMMAND_CALL_START.finditer(strings_and_comments_masked):
        command_name = _literal_command_argument(comments_removed, match.end())
        if command_name is None:
            return _host_code_rejection(
                "{}(<dynamic command>)".format(match.group(1))
            )
        if _is_host_command_name(command_name):
            return _host_code_rejection(
                '{}("{}")'.format(match.group(1), command_name)
            )
    return None


def _host_code_rejection(primitive: str) -> str:
    return (
        "Macro host/JVM code primitive '{}' is not allowed. Refusing before "
        "Fiji; use an explicitly elevated host-code tool when host execution "
        "is genuinely required."
    ).format(primitive)


def _literal_command_argument(source: str, offset: int) -> str | None:
    """Return a single literal first argument, or None for dynamic dispatch."""
    i = offset
    while i < len(source) and source[i].isspace():
        i += 1
    if i >= len(source) or source[i] not in ('"', "'"):
        return None
    quote = source[i]
    i += 1
    chars = []
    escaped = False
    while i < len(source):
        char = source[i]
        if escaped:
            chars.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == quote:
            i += 1
            while i < len(source) and source[i].isspace():
                i += 1
            if i < len(source) and source[i] not in (",", ")"):
                return None
            return "".join(chars)
        else:
            chars.append(char)
        i += 1
    return None


def _is_host_command_name(command_name: str) -> bool:
    normalized = re.sub(r"\s+", " ", command_name).strip().casefold()
    tokens = set(re.findall(r"[a-z0-9]+", normalized))
    return bool(tokens & _HOST_COMMAND_TOKENS) or any(
        phrase in normalized for phrase in _HOST_COMMAND_PHRASES
    )


def _macro_views(code: str) -> tuple[str, str]:
    """Return (comments removed, strings+comments masked), preserving offsets."""
    comments_removed = list(code)
    masked = list(code)
    state = "code"
    quote = ""
    escaped = False
    i = 0
    while i < len(code):
        char = code[i]
        nxt = code[i + 1] if i + 1 < len(code) else ""

        if state == "code":
            if char == "/" and nxt == "/":
                comments_removed[i] = masked[i] = " "
                comments_removed[i + 1] = masked[i + 1] = " "
                state = "line_comment"
                i += 2
                continue
            if char == "/" and nxt == "*":
                comments_removed[i] = masked[i] = " "
                comments_removed[i + 1] = masked[i + 1] = " "
                state = "block_comment"
                i += 2
                continue
            if char in ('"', "'"):
                quote = char
                escaped = False
                masked[i] = " "
                state = "string"
            i += 1
            continue

        if state == "string":
            if char not in "\r\n":
                masked[i] = " "
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                state = "code"
                quote = ""
            i += 1
            continue

        # Preserve newlines so all views retain source line/offset structure.
        if char not in "\r\n":
            comments_removed[i] = masked[i] = " "
        if state == "line_comment" and char in "\r\n":
            state = "code"
        elif state == "block_comment" and char == "*" and nxt == "/":
            comments_removed[i] = masked[i] = " "
            comments_removed[i + 1] = masked[i + 1] = " "
            state = "code"
            i += 2
            continue
        i += 1

    return "".join(comments_removed), "".join(masked)


def _parse_call_arguments(source: str, open_paren: int) -> list[str] | None:
    """Return top-level argument expressions for one call, or None if broken."""
    if open_paren < 0 or open_paren >= len(source) or source[open_paren] != "(":
        return None
    args: list[str] = []
    start = open_paren + 1
    depth = 1
    quote = ""
    escaped = False
    i = start
    while i < len(source):
        char = source[i]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            i += 1
            continue
        if char in ('"', "'"):
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                final = source[start:i].strip()
                if final or args:
                    args.append(final)
                return args
        elif char == "," and depth == 1:
            args.append(source[start:i].strip())
            start = i + 1
        i += 1
    return None


def _literal_string(expression: str) -> str | None:
    """Decode one whole quoted expression; reject concatenation/variables."""
    value = expression.strip()
    if len(value) < 2 or value[0] not in ('"', "'"):
        return None
    quote = value[0]
    chars: list[str] = []
    escaped = False
    i = 1
    while i < len(value):
        char = value[i]
        if escaped:
            # ImageJ paths commonly contain backslashes. Only consume an
            # escape when it protects a quote or another backslash.
            if char in (quote, "\\"):
                chars.append(char)
            else:
                chars.extend(("\\", char))
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == quote:
            return "".join(chars) if not value[i + 1:].strip() else None
        else:
            chars.append(char)
        i += 1
    return None


def session_id() -> str:
    """Return the current agent session ID used to scope audit records."""
    return _SESSION_ID


def note_execution(success: bool) -> None:
    """Record whether the latest macro/script path ended cleanly."""
    _RECENT_EXECUTIONS.append(bool(success))


def recipe_offer_allowed() -> bool:
    """True when the recent execution window contains success and no failures."""
    recent = list(_RECENT_EXECUTIONS)
    return bool(recent) and any(recent) and not any(not item for item in recent)


def audit_log(source: str, code: str, success: bool, metadata: dict | None = None) -> None:
    """Append one JSON line recording a macro or script the agent ran.

    The record has a UTC ISO 8601 timestamp, source ("macro" or
    "script"), the full code, and whether Fiji reported success.
    Written to audit.log inside the current AI_Exports/ folder. If
    the folder is not available the write is silently skipped —
    auditing is best-effort.
    """
    folder = active_image.current_export_folder()
    if folder is None:
        return
    record = {
        "ts": _utc_now_iso(),
        "session_id": _SESSION_ID,
        "source": source,
        "code": code,
        "success": bool(success),
    }
    if isinstance(metadata, dict):
        for key, value in metadata.items():
            if key not in record:
                record[key] = value
    _append_jsonl(os.path.join(folder, "audit.log"), record)


def friction_log(event: dict) -> None:
    """Record a macro failure or stuck-loop event for later review.

    Controlled by the ENABLED attribute on this function — __main__
    flips it off when --no-friction-log is passed. The event dict
    is stored verbatim alongside a UTC timestamp. Contents must be
    plain text diagnostics: no biology data, no image pixels.
    """
    if not getattr(friction_log, "ENABLED", True):
        return
    if not isinstance(event, dict):
        return
    folder = active_image.current_export_folder()
    if folder is None:
        return
    record = dict(event)
    record.setdefault("ts", _utc_now_iso())
    record.setdefault("session_id", _SESSION_ID)
    _append_jsonl(os.path.join(folder, "friction.log"), record)


# Module-level flag — function attribute form matches the spec
# in docs/ollama/plan.md Phase 1d ("safety.friction_log.ENABLED = False").
friction_log.ENABLED = True  # type: ignore[attr-defined]


# ---- helpers ----------------------------------------------------------


def _is_within(path: str, folder: str) -> bool:
    """Return True if path sits inside folder (after absolute-path resolution).

    Uses os.path.commonpath to avoid the prefix-match trap where
    '/tmp/export2/x' would otherwise look like it starts with
    '/tmp/export'.
    """
    try:
        common = os.path.commonpath([path, folder])
    except ValueError:
        # Different drives on Windows — definitely not within.
        return False
    return os.path.normcase(common) == os.path.normcase(folder)


def _utc_now_iso() -> str:
    """Return the current UTC time as an ISO 8601 string with a Z suffix."""
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _append_jsonl(path: str, record: dict) -> None:
    """Append one JSON line to a log file, swallowing filesystem errors."""
    try:
        payload = json.dumps(record, ensure_ascii=False)
    except (TypeError, ValueError):
        payload = json.dumps({"ts": _utc_now_iso(), "note": "unserialisable record"})
    line = payload + "\n"
    try:
        with _log_lock:
            with open(path, "a", encoding="utf-8") as fh:
                fh.write(line)
    except OSError:
        return
