"""Read Fiji helper invocations for display; never execute source or read files."""
from __future__ import annotations

import json
import re
import shlex
from dataclasses import dataclass


@dataclass(frozen=True)
class FijiAction:
    name: str
    args: dict


_CLI_NAMES = {
    "ping": "ping", "state": "get_state", "info": "get_image_info",
    "open": "open_image", "results": "get_results", "context": "get_state_context",
    "capture": "capture_image", "macro": "run_macro", "script": "run_script",
    "async": "run_macro_async", "run_patient": "run_macro_async",
    "explore": "threshold_shootout", "log": "get_log", "console": "get_console",
    "histogram": "get_histogram", "windows": "get_open_windows",
    "metadata": "get_metadata", "rois": "get_roi_state", "display": "get_display_state",
    "dialogs": "get_dialogs", "close_dialogs": "close_dialogs",
    "probe": "probe_plugin", "ui": "interact_dialog", "3d": "3d_viewer",
    "friction": "get_friction_log", "progress": "get_progress",
    "capabilities": "hello", "hello": "hello", "job": "job_status", "cancel": "cancel_job",
    "wait": "wait_for_job", "jobs": "job_list", "run": "run_sequence",
    "intent": "resolve_intent", "teach": "teach_intent", "intents": "list_intents",
    "forget": "forget_intent", "subscribe": "subscribe", "events": "subscribe",
    "toast": "gui_toast", "inline": "gui_inline", "focus": "gui_focus",
    "markdown": "gui_markdown", "highlight": "gui_highlight_roi",
    "confirm": "gui_confirm", "reactive": "reactive_rules",
}
_PYTHON = re.compile(r"(?:python(?:\d+(?:\.\d+)*)?|py)(?:\.exe)?$", re.I)
_SHELLS = {"powershell", "powershell.exe", "pwsh", "pwsh.exe", "bash", "bash.exe", "cmd", "cmd.exe"}
_POWERSHELL_STDIN = re.compile(
    r"(?m)^\s*@(?P<quote>['\"])\r?\n(?P<body>[\s\S]*?)\r?\n"
    r"(?P=quote)@\s*\|\s*(?P<command>[^\r\n]+)"
)
_BASH_STDIN = re.compile(
    r"(?m)^(?P<command>[^\r\n]+?)\s*<<\s*['\"]?(?P<tag>\w+)['\"]?\s*\r?\n"
    r"(?P<body>[\s\S]*?)\r?\n(?P=tag)[ \t]*(?:\r?\n|$)"
)


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


def _basename(value: str) -> str:
    return _unquote(value).replace("\\", "/").rsplit("/", 1)[-1].lower()


def _quoted_shell_script(command: str) -> str | None:
    """Decode one quoted shell script before parsing its inner here-string.

    POSIX quote concatenation and escaped double quotes occur in the command
    events emitted by vendor agents, including Windows PowerShell invocations.
    Splitting only for display never expands variables or runs shell source.
    """
    try:
        words = shlex.split(command, posix=True)
    except ValueError:
        return None
    if words and words[0] == "&":
        words = words[1:]
    if not words or _basename(words[0]) not in _SHELLS:
        return None
    for index, word in enumerate(words[1:], 1):
        if word.lower() in {"-command", "-c", "-lc", "/c"}:
            return words[index + 1] if len(words) == index + 2 else None
    return None


def _result_formatter(words: list[str]) -> bool:
    """Recognise a narrow set of output-only PowerShell pipeline stages."""
    if not words:
        return False
    command = words[0].lower()
    if command in {"convertfrom-json", "convertto-json", "format-list", "format-table", "out-string"}:
        return len(words) == 1
    if command != "foreach-object":
        return False
    match = re.fullmatch(
        r"ForEach-Object\s*\{\s*\[pscustomobject\]\s*@\{([\s\S]*?)\}\s*\}",
        " ".join(words), re.I,
    )
    if not match:
        return False
    # Only property reads, literals and -join are allowed. An arbitrary
    # scriptblock (including host commands/method calls) stays visible.
    try:
        lexer = shlex.shlex(match[1], posix=False, punctuation_chars=";=()")
        lexer.whitespace_split = True
        tokens = [
            part for token in lexer
            for part in (list(token) if token and all(c in ";=()" for c in token) else [token])
        ]
    except ValueError:
        return False
    value = r'''(?:\$_(?:\.\w+)*|\$PSItem(?:\.\w+)*|\$(?:null|true|false)|\d+(?:\.\d+)?|'(?:''|[^'])*'|"[^"$`]*")'''
    index = 0
    while index < len(tokens):
        if (not re.fullmatch(r"\w+", tokens[index]) or index + 1 >= len(tokens)
                or tokens[index + 1] != "="):
            return False
        index += 2
        expression = []
        while index < len(tokens) and tokens[index] != ";":
            expression.append(tokens[index])
            index += 1
        text = " ".join(expression)
        if not re.fullmatch(r"[\s(]*" + value + r"(?:\s+-join\s+" + value + r")?[\s)]*", text, re.I):
            return False
        parentheses = [token for token in expression if token and all(c in "()" for c in token)]
        if sum(token.count("(") - token.count(")") for token in parentheses):
            return False
        index += 1
    return True


def _arguments(command: str, words: list[str], stdin: str | None) -> dict:
    if command in {"macro", "async", "run_patient", "script"}:
        args = {}
        source = []
        index = 0
        while index < len(words):
            word = words[index]
            if word == "--stdin":
                args.update({"code": stdin} if stdin is not None else {"source": "stdin"})
            elif word in {"--file", "--lang", "--timeout"} and index + 1 < len(words):
                index += 1
                key = {"--file": "file", "--lang": "language", "--timeout": "timeout"}[word]
                args[key] = words[index]
            elif word.startswith("--timeout="):
                args["timeout"] = word.split("=", 1)[1]
            else:
                source.append(word)
            index += 1
        if source:
            args["code"] = " ".join(source)
        return args
    if command == "open" and words:
        return {"path": words[0], **({"series": words[1]} if len(words) > 1 else {})}
    if command == "probe":
        return {"command": " ".join(words)}
    if command == "capture":
        return {"name": words[0]} if words else {}
    if command in {"ui", "3d", "reactive"}:
        return {"action": words[0], "arguments": words[1:]} if words else {}
    return {"arguments": words} if words else {}


def _invocation(words: list[str], stdin: str | None, depth: int) -> list[FijiAction]:
    if not words:
        return []
    if words[0] == "&":
        words = words[1:]
    if not words:
        return []
    executable = _basename(words[0])
    if executable in _SHELLS:
        for index, word in enumerate(words[1:], 1):
            if word.lower() in {"-command", "-c", "-lc", "/c"}:
                return fiji_actions(" ".join(_unquote(w) for w in words[index + 1:]), _depth=depth + 1)
        return []
    if _PYTHON.fullmatch(executable):
        words = words[1:]
        if words and re.fullmatch(r"-\d(?:\.\d+)?", words[0]):
            words = words[1:]
        if words[:2] == ["-m", "agent.ij"]:
            words = ["ij.py", *words[2:]]
    if not words or _basename(words[0]) != "ij.py":
        return []
    if len(words) < 2:
        return []
    command, args = _unquote(words[1]).lower(), [_unquote(w) for w in words[2:]]
    if command in {"help", "--help", "-h"} or args in (["--help"], ["-h"]):
        return [FijiAction("ImageJAI help", {})]
    if command == "raw" and args:
        try:
            payload = json.loads(" ".join(args))
            if isinstance(payload, dict) and isinstance(payload.get("command"), str):
                return [FijiAction(payload["command"], {k: v for k, v in payload.items() if k != "command"})]
        except ValueError:
            pass
    name = _CLI_NAMES.get(command, "ImageJAI command")
    if command == "friction" and args and args[0] in {"patterns", "clear"}:
        name = "get_friction_patterns" if args[0] == "patterns" else "clear_friction_log"
    arguments = _arguments(command, args, stdin)
    if command not in _CLI_NAMES:
        arguments = {"command": command, **arguments}
    return [FijiAction(name, arguments)]


def fiji_actions(command: str, *, _depth: int = 0) -> list[FijiAction]:
    """Extract submitted helper calls, including literal stdin macro bodies."""
    if not isinstance(command, str) or len(command) > 65_536 or _depth > 3:
        return []
    script = _quoted_shell_script(command)
    if script is not None:
        return fiji_actions(script, _depth=_depth + 1)
    actions = []
    # Preserve execution order and keep source bodies out of shell parsing.
    while command:
        matches = [pattern.search(command) for pattern in (_POWERSHELL_STDIN, _BASH_STDIN)]
        match = min((m for m in matches if m is not None), key=lambda m: m.start(), default=None)
        if match is None:
            actions.extend(_plain_actions(command, None, _depth))
            break
        actions.extend(_plain_actions(command[:match.start()], None, _depth))
        actions.extend(_plain_actions(match["command"], match["body"] + "\n", _depth))
        command = command[match.end():]
    # Keep unrelated steps visible when they accompany a Fiji operation.
    return actions if any(action.name != "Shell" for action in actions) else []


def _plain_actions(command: str, stdin: str | None, depth: int) -> list[FijiAction]:
    try:
        lexer = shlex.shlex(command, posix=False, punctuation_chars=";|&\n")
        lexer.whitespace = " \t\r"
        lexer.whitespace_split = True
        words = list(lexer)
    except ValueError:
        return []
    actions = []
    invocation = []
    braces = 0
    result_pipeline = False
    for word in [*words, ";"]:
        if word and all(c in ";|&\n" for c in word) and (word != "&" or invocation) and braces == 0:
            parsed = _invocation(invocation, stdin, depth)
            formatting = result_pipeline and _result_formatter(invocation)
            if parsed:
                actions.extend(parsed)
            elif invocation and not formatting:
                actions.append(FijiAction("Shell", {"command": " ".join(invocation)}))
            result_pipeline = word == "|" and (bool(parsed) or formatting)
            invocation = []
        else:
            invocation.append(word)
            if _unquote(word) == word:
                braces = max(0, braces + word.count("{") - word.count("}"))
    return actions
