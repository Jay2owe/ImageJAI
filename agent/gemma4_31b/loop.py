"""Chat loop for the Gemma 4 31B agent.

Copied in spirit from agent/ollama_agent/ollama_chat.py, then
trimmed down for the standalone Gemma 4 31B agent. What stays:

- a single ollama.chat() call with stream=False, temperature=0.2
  and num_ctx=131072 (the model is gemma4:31b-cloud, which the
  Ollama cloud proxy does not expose num_ctx for via ollama.show,
  so we hard-code it — see agent/ollama_agent/CLAUDE.md);
- a stdin read loop that appends to message history, sends, prints
  the reply;
- tool dispatch using TOOL_MAP built from the REGISTRY populated
  by every tools_*.py module's @tool decorators;
- a Ctrl-C handler that prints "bye" and returns;
- a worker-thread wrapper around ollama.chat() so Ctrl-C remains
  responsive on Windows — signal delivery during the blocking
  socket read inside the ollama client is unreliable there.
"""

from __future__ import annotations

import json
import os
import queue
import re
import sys
import threading
import time
from collections import deque
from pathlib import Path

import ollama

try:
    from prompt_toolkit import prompt as _pt_prompt
    from prompt_toolkit.application.current import get_app_or_none
    from prompt_toolkit.completion import WordCompleter
    from prompt_toolkit.formatted_text import ANSI
    from prompt_toolkit.history import FileHistory
    from prompt_toolkit.patch_stdout import patch_stdout
except ImportError:  # pragma: no cover - optional dependency
    _pt_prompt = None
    get_app_or_none = None
    WordCompleter = None
    ANSI = None
    FileHistory = None
    patch_stdout = None

# Import the tool modules so their @tool decorators fire and the
# REGISTRY list is populated before we read it below.
from . import describe_image  # noqa: F401
from . import events
from . import safety
from . import threshold_shootout  # noqa: F401
from . import tools_dialogs  # noqa: F401
from . import tools_fiji  # noqa: F401
from . import tools_jobs  # noqa: F401
from . import tools_plugins  # noqa: F401
from . import tools_python  # noqa: F401
from . import tools_recipes
from . import tools_shell  # noqa: F401
from . import triage_image  # noqa: F401
from .registry import REGISTRY, _rebuild_tool_map

DEFAULT_MODEL = "gemma4:31b-cloud"
NUM_CTX = 131072
TEMPERATURE = 0.2
MAX_IDENTICAL_TOOL_REPEATS = 8
BUNDLED_CCOMMANDS_DIR = Path(__file__).resolve().parent / "ccommands"
_THRESHOLD_TRIGGER_RE = re.compile(
    r"\b(threshold(?:ing)?|segment(?:ation|ing)?|mask(?:ing)?|binary|binar(?:ise|ize|isation|ization))\b",
    re.IGNORECASE,
)
_PHASE9_POSITIVE_RE = re.compile(
    r"(thanks|thank\s+you|done|looks\s+good|perfect|that\s+worked|nailed\s+it)",
    re.IGNORECASE,
)
_PHASE9_SLASH_RE = re.compile(r"/save[-_]recipe\b", re.IGNORECASE)
_SLASH_COMMANDS: tuple[tuple[str, str], ...] = (
    ("/help", "Show available slash commands."),
    ("/clear", "Reset the conversation history."),
    ("/queue <text>", "Queue a prompt to run after the current turn."),
    ("/interrupt [text]", "Abort the current turn and optionally queue replacement text."),
    ("/ccommands [name]", "List or load custom command prompts."),
    ("/save-recipe", "Offer to save the current workflow as a reusable recipe."),
)


if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass
    os.system("")


class _TurnAborted(Exception):
    """Raised when the user hits Ctrl-C during a turn."""


def _default_data_dir() -> Path:
    """Return the per-user data directory for prompt history."""
    if os.name == "nt":
        base = os.environ.get("APPDATA", os.path.expanduser("~"))
    else:
        base = os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config"))
    return Path(base) / "imagej-ai" / "gemma4_31b"


DATA_DIR = _default_data_dir()
PROMPT_HISTORY_PATH = DATA_DIR / "history.txt"
_CONSOLE_LOCK = threading.Lock()
_PROMPT_STATUS_LOCK = threading.Lock()
_PROMPT_STATUS = {"label": "", "started_at": 0.0}
_ABORT_HINT = " (Ctrl-C to abort)"


def _prompt_status_enabled() -> bool:
    """True when prompt_toolkit is active and we can reserve a status line."""
    return _pt_prompt is not None and ANSI is not None


def _console_emit(text: str = "", reserve_status_line: bool = False) -> None:
    """Write console output in a way that keeps the live prompt readable."""
    del reserve_status_line  # legacy flag from the old ANSI status-line renderer
    payload = str(text or "")
    with _CONSOLE_LOCK:
        if payload:
            sys.stdout.write(payload)
            if not payload.endswith("\n"):
                sys.stdout.write("\n")
        else:
            sys.stdout.write("\n")
        sys.stdout.flush()


def _estimate_tokens(messages: list) -> int:
    """Rough token estimate from conversation history (~4 chars per token)."""
    total_chars = 0
    for message in messages:
        if isinstance(message, dict):
            total_chars += len(message.get("content", "") or "")
        else:
            total_chars += len(getattr(message, "content", "") or "")
            tool_calls = getattr(message, "tool_calls", None)
            if tool_calls:
                total_chars += len(json.dumps([
                    {"name": tc.function.name, "args": tc.function.arguments}
                    for tc in tool_calls
                ]))
    return total_chars // 4 + 100


def _format_ctx_bar(used: int, limit: int, width: int = 24) -> str:
    """Render a context window usage bar."""
    pct = min(used / limit, 1.0) if limit else 0
    filled = int(width * pct)
    empty = width - filled

    if pct < 0.5:
        color = "\033[32m"
    elif pct < 0.8:
        color = "\033[33m"
    else:
        color = "\033[31m"

    def _fmt_k(n: int) -> str:
        return "{:.1f}K".format(n / 1000.0) if n >= 1000 else str(n)

    bar = "{}{}\033[90m{}\033[0m".format(color, "█" * filled, "░" * empty)
    return "\033[90mctx [{}\033[90m] {} / {} ({:.0%})\033[0m".format(
        bar,
        _fmt_k(used),
        _fmt_k(limit),
        pct,
    )


def _sanitize_status_label(label: str) -> str:
    """Drop repeated UI hints from the live working-status text."""
    clean = str(label or "").strip()
    if clean.endswith(_ABORT_HINT):
        clean = clean[: -len(_ABORT_HINT)].rstrip()
    return clean


def _invalidate_prompt() -> None:
    """Ask prompt_toolkit to redraw the active prompt, if there is one."""
    if get_app_or_none is None:
        return
    try:
        app = get_app_or_none()
    except Exception:
        return
    if app is None:
        return
    try:
        app.invalidate()
    except Exception:
        pass


def _set_prompt_status(label: str = "", started_at: float = 0.0) -> None:
    """Update the shared live-status state used by the prompt footer."""
    with _PROMPT_STATUS_LOCK:
        _PROMPT_STATUS["label"] = _sanitize_status_label(label)
        _PROMPT_STATUS["started_at"] = started_at if label else 0.0
    _invalidate_prompt()


def _get_prompt_status() -> tuple[str, float]:
    """Return the current prompt-footer working status."""
    with _PROMPT_STATUS_LOCK:
        return (
            str(_PROMPT_STATUS.get("label") or ""),
            float(_PROMPT_STATUS.get("started_at") or 0.0),
        )


def _render_prompt_toolbar(ctx_state: dict | None) -> object:
    """Render the single-line footer under the prompt."""
    parts: list[str] = []
    if isinstance(ctx_state, dict):
        parts.append(
            "  "
            + _format_ctx_bar(
                int(ctx_state.get("used", 0)),
                int(ctx_state.get("limit", NUM_CTX)),
            )
        )
    label, started_at = _get_prompt_status()
    if label:
        elapsed = max(0, int(time.time() - started_at))
        parts.append("  \033[90mworking: {} ({}s)\033[0m".format(label, elapsed))
    text = "   ".join(parts)
    if ANSI is None:
        return text
    return ANSI(text)


def _run_interruptible(fn, *args, abort_event: threading.Event | None = None, **kwargs):
    """Run a blocking callable in a worker thread so Ctrl-C stays responsive."""
    result = {"resp": None, "exc": None}

    def _run():
        try:
            result["resp"] = fn(*args, **kwargs)
        except BaseException as exc:
            result["exc"] = exc

    thread = threading.Thread(target=_run, daemon=True)
    thread.start()
    try:
        while thread.is_alive():
            thread.join(timeout=0.1)
            if abort_event is not None and abort_event.is_set():
                raise _TurnAborted()
    except KeyboardInterrupt:
        raise _TurnAborted()
    if result["exc"]:
        raise result["exc"]
    return result["resp"]


class _Spinner:
    """One-second working timer. Uses the prompt footer when available."""

    def __init__(self, label: str = "thinking"):
        self._label = label
        self._stop = threading.Event()
        self._thread = None
        self._start_time = 0.0

    def start(self):
        self._start_time = time.time()
        self._stop.clear()
        if _prompt_status_enabled():
            _set_prompt_status(self._label, self._start_time)
            self._thread = threading.Thread(target=self._tick_prompt, daemon=True)
            self._thread.start()
            return
        self._thread = threading.Thread(target=self._spin, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join()
            self._thread = None
        if _prompt_status_enabled():
            _set_prompt_status()
            return
        with _CONSOLE_LOCK:
            sys.stdout.write("\r\033[K")
            sys.stdout.flush()

    def _tick_prompt(self):
        while not self._stop.wait(1.0):
            _invalidate_prompt()

    def _spin(self):
        while not self._stop.is_set():
            elapsed = max(0, int(time.time() - self._start_time))
            line = "  \033[90mworking: {} ({}s)\033[0m".format(
                _sanitize_status_label(self._label),
                elapsed,
            )
            with _CONSOLE_LOCK:
                sys.stdout.write("\r\033[K{}".format(line))
                sys.stdout.flush()
            self._stop.wait(1.0)


def _chat_interruptible(abort_event: threading.Event | None = None, **kwargs):
    """Run ollama.chat() in a worker thread so Ctrl-C on the main thread is responsive.

    On Windows the ollama client's blocking socket read swallows
    SIGINT, so signals never reach us. Running the call on a daemon
    thread and polling lets the main thread handle KeyboardInterrupt
    the normal way.
    """
    return _run_interruptible(ollama.chat, abort_event=abort_event, **kwargs)


def _format_tool_result(value) -> str:
    """Stringify a tool result for the message history."""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        top_error = value.get("error")
        top_dialogs = value.get("dialogs")
        if value.get("ok") is False and isinstance(top_error, str):
            return _format_error_result(top_error, top_dialogs)
        result = value.get("result")
        if isinstance(result, dict):
            nested_error = result.get("error")
            nested_dialogs = result.get("dialogs", top_dialogs)
            if result.get("success") is False and isinstance(nested_error, str):
                return _format_error_result(nested_error, nested_dialogs)
        if isinstance(top_error, str) and set(value.keys()).issubset({"error", "dialogs", "ok"}):
            return _format_error_result(top_error, top_dialogs)
    try:
        return json.dumps(value)
    except (TypeError, ValueError):
        return str(value)


def _canonical_json(value) -> str:
    """Stable JSON form used for exact-tool-repeat detection."""
    try:
        return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    except (TypeError, ValueError):
        return str(value)


def _format_error_result(error_text: str, dialogs) -> str:
    """Return a plain-text tool error with optional dialog details."""
    parts = ["ERROR: {}".format(str(error_text).strip() or "unknown error")]
    dialog_text = _format_dialog_details(dialogs)
    if dialog_text:
        parts.append(dialog_text)
    return "\n".join(parts)


def _format_dialog_details(dialogs) -> str:
    """Render dialog title/text/buttons into compact plain text."""
    if not isinstance(dialogs, list) or not dialogs:
        return ""
    lines = []
    for item in dialogs:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title") or "Untitled dialog").strip()
        text = str(item.get("text") or "").strip()
        buttons = item.get("buttons")
        line = title
        if text:
            line += ": " + text
        if isinstance(buttons, list):
            labels = [str(b).strip() for b in buttons if str(b).strip()]
            if labels:
                line += " [buttons: {}]".format(", ".join(labels))
        lines.append(line)
    if not lines:
        return ""
    return "Dialogs:\n" + "\n".join(lines)


def _parse_slash_command(user_input: str) -> tuple[str, str]:
    """Split '/command args' into ('/command', 'args')."""
    parts = user_input.strip().split(None, 1)
    command = parts[0].lower() if parts else ""
    arg = parts[1].strip() if len(parts) > 1 else ""
    return command, arg


def _custom_command_dirs(cwd: Path | None = None) -> list[Path]:
    """Return custom-command directories in priority order."""
    root = cwd or Path.cwd()
    dirs = [
        root / "ccommands",
        root / "agent" / "gemma4_31b" / "ccommands",
        BUNDLED_CCOMMANDS_DIR,
    ]
    seen: set[Path] = set()
    ordered: list[Path] = []
    for path in dirs:
        key = path.resolve(strict=False)
        if key in seen:
            continue
        seen.add(key)
        ordered.append(path)
    return ordered


def _list_custom_commands(cwd: Path | None = None) -> list[tuple[str, Path]]:
    """List available custom commands, preferring project-local files."""
    found: dict[str, Path] = {}
    for directory in _custom_command_dirs(cwd):
        if not directory.exists():
            continue
        for pattern in ("*.md", "*.txt"):
            for path in sorted(directory.rglob(pattern)):
                name = path.relative_to(directory).with_suffix("").as_posix()
                found.setdefault(name, path)
    return sorted(found.items())


def _load_custom_command(name: str, cwd: Path | None = None) -> tuple[str, Path]:
    """Load one custom command prompt from disk."""
    command_name = str(name or "").strip().replace("\\", "/")
    if not command_name:
        raise ValueError("command name required")
    if command_name.startswith("/"):
        raise ValueError("command names must be relative")
    if any(part == ".." for part in Path(command_name).parts):
        raise ValueError("command names must not contain '..'")

    suffix = Path(command_name).suffix.lower()
    candidates = [command_name] if suffix in {".md", ".txt"} else [
        command_name + ".md",
        command_name + ".txt",
    ]
    for directory in _custom_command_dirs(cwd):
        for candidate in candidates:
            path = directory / Path(candidate)
            if not path.is_file():
                continue
            content = path.read_text(encoding="utf-8").strip()
            if not content:
                raise ValueError("custom command '{}' is empty".format(command_name))
            return content, path
    searched = ", ".join(str(path) for path in _custom_command_dirs(cwd))
    raise ValueError("custom command '{}' not found in {}".format(command_name, searched))


def _build_slash_completer():
    """Return a slash-command completer when prompt_toolkit is available."""
    if WordCompleter is None:
        return None
    names = [item[0].split()[0] for item in _SLASH_COMMANDS]
    return WordCompleter(names, ignore_case=True, sentence=True)


def _read_input(prompt_text: str, ctx_state: dict | None = None, completer=None) -> str:
    """Read one line from stdin, using prompt_toolkit when available."""
    if _pt_prompt is None or ANSI is None:
        if isinstance(ctx_state, dict):
            _console_emit(
                "  "
                + _format_ctx_bar(
                    int(ctx_state.get("used", 0)),
                    int(ctx_state.get("limit", NUM_CTX)),
                )
            )
        return input(prompt_text)

    kwargs = {
        "bottom_toolbar": lambda: _render_prompt_toolbar(ctx_state),
        "complete_while_typing": True,
    }
    if completer is not None:
        kwargs["completer"] = completer
    if FileHistory is not None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        kwargs["history"] = FileHistory(str(PROMPT_HISTORY_PATH))
    if patch_stdout is not None:
        with patch_stdout(raw=True):
            return _pt_prompt(ANSI(prompt_text), **kwargs)
    return _pt_prompt(ANSI(prompt_text), **kwargs)


def _print_help() -> None:
    """Print available slash commands."""
    _console_emit("Slash commands:")
    for command, description in _SLASH_COMMANDS:
        _console_emit("  {:20s} - {}".format(command, description))
    if _pt_prompt is not None:
        _console_emit("  Tab completes slash commands.")
    _console_emit("  Plain text while Gemma is busy is queued automatically.")
    _console_emit("  exit, quit, :q      - Exit chat when idle.")


def _one_turn(
    model: str,
    messages: list,
    tools: list,
    tool_map: dict,
    abort_event: threading.Event | None = None,
) -> str:
    """Run one user turn through ollama.chat(), dispatching tools as needed."""
    spinner = _Spinner("thinking (Ctrl-C to abort)")
    turn_start = time.time()
    round_n = 0
    last_tool_signature = None
    identical_tool_repeats = 0

    try:
        while True:
            round_n += 1
            spinner.start()
            try:
                resp = _chat_interruptible(
                    abort_event=abort_event,
                    model=model,
                    messages=messages,
                    tools=tools,
                    stream=False,
                    options={"temperature": TEMPERATURE, "num_ctx": NUM_CTX},
                )
            finally:
                spinner.stop()

            if abort_event is not None and abort_event.is_set():
                raise _TurnAborted()

            msg = resp.message
            messages.append(msg)
            eval_tokens = getattr(resp, "eval_count", 0) or 0

            tool_calls = getattr(msg, "tool_calls", None) or []
            if not tool_calls:
                elapsed = time.time() - turn_start
                tok_s = eval_tokens / elapsed if elapsed > 0 and eval_tokens else 0
                stats = "\033[90m({:.1f}s".format(elapsed)
                if eval_tokens:
                    stats += ", {} tok, {:.1f} tok/s".format(eval_tokens, tok_s)
                stats += ")\033[0m"
                content = (getattr(msg, "content", "") or "").strip()
                return "{}\n  {}".format(content, stats)

            for call in tool_calls:
                name = call.function.name
                args = call.function.arguments or {}
                if name not in tool_map:
                    result_text = "ERROR: unknown tool '{}'".format(name)
                    _console_emit("  \033[31m✗ {}\033[0m".format(result_text), reserve_status_line=True)
                    messages.append({"role": "tool", "content": result_text})
                    continue
                _console_emit(
                    "  \033[33m⚡ {}({})\033[0m".format(
                        name, json.dumps(args)
                    ),
                    reserve_status_line=True,
                )
                tool_spinner = _Spinner("running {} (Ctrl-C to abort)".format(name))
                tool_spinner.start()
                try:
                    result = _run_interruptible(tool_map[name], abort_event=abort_event, **args)
                except _TurnAborted:
                    raise _TurnAborted()
                except Exception as exc:  # surface to the model, not the user
                    result = "ERROR: {}: {}".format(type(exc).__name__, exc)
                finally:
                    tool_spinner.stop()
                result_text = _format_tool_result(result)
                flat = result_text.replace("\r", " ").replace("\n", " ")
                preview = flat[:200]
                if len(flat) > 200:
                    preview = "{} … [+{:.1f}KB]".format(preview, (len(flat) - 200) / 1024)
                _console_emit("  \033[90m→ {}\033[0m".format(preview), reserve_status_line=True)
                messages.append({"role": "tool", "content": result_text})
                signature = (name, _canonical_json(args), result_text)
                if signature == last_tool_signature:
                    identical_tool_repeats += 1
                else:
                    last_tool_signature = signature
                    identical_tool_repeats = 1
                if identical_tool_repeats >= MAX_IDENTICAL_TOOL_REPEATS:
                    safety.friction_log(
                        {
                            "event": "stuck_tool_loop",
                            "tool": name,
                            "args": _canonical_json(args),
                            "repeat_count": identical_tool_repeats,
                            "result_preview": preview,
                        }
                    )
                    elapsed = time.time() - turn_start
                    return (
                        "(stopped after the same tool repeated {} times: {}({}))\n"
                        "  \033[90m({:.1f}s)\033[0m"
                    ).format(
                        identical_tool_repeats,
                        name,
                        _canonical_json(args),
                        elapsed,
                    )

            spinner = _Spinner(
                "round {} (Ctrl-C to abort)".format(round_n + 1)
            )
    except _TurnAborted:
        spinner.stop()
        elapsed = time.time() - turn_start
        return "\033[31m(aborted by user after {:.1f}s)\033[0m".format(elapsed)


def _format_triage_note(frame: dict) -> str | None:
    """Run phase-5 triage for a newly opened image and return a system note."""
    if str(frame.get("event") or "") != "image.opened":
        return None
    data = frame.get("data") if isinstance(frame.get("data"), dict) else {}
    title = str(data.get("title") or "new image")
    # Skip auto-triage on images the agent's macros just created (Duplicate,
    # Convert to Mask). Triage on a binary mask reports nonsense — "33%
    # saturated / 67% clipped blacks" — that derails the chat.
    if events.is_likely_agent_created_mask(title):
        return None
    warnings = triage_image.triage_image()
    if not isinstance(warnings, list) or not warnings:
        return None
    return "Fiji opened '{}'. Automatic triage warnings: {}".format(
        title,
        " | ".join(str(item) for item in warnings if item),
    )


def _format_dialog_note(frame: dict) -> str | None:
    """Return a note when Fiji reports an error dialog."""
    if str(frame.get("event") or "") != "dialog.appeared":
        return None
    data = frame.get("data") if isinstance(frame.get("data"), dict) else {}
    dialog_type = str(data.get("type") or data.get("kind") or "").lower()
    if dialog_type != "error":
        return None
    title = str(data.get("title") or "Untitled dialog").strip()
    text = str(data.get("text") or "").strip()
    buttons = data.get("buttons")
    suffix = ""
    if isinstance(buttons, list):
        labels = [str(b).strip() for b in buttons if str(b).strip()]
        if labels:
            suffix = " [buttons: {}]".format(", ".join(labels))
    if text:
        return "Fiji showed an error dialog: {} — {}.{}".format(title, text, suffix)
    return "Fiji showed an error dialog: {}.{}".format(title, suffix)


def _format_macro_failure_note(frame: dict) -> str | None:
    """Return a note when Fiji publishes a failed macro.completed event."""
    if str(frame.get("event") or "") != "macro.completed":
        return None
    data = frame.get("data") if isinstance(frame.get("data"), dict) else {}
    if data.get("success") is not False:
        return None
    error = str(data.get("error") or "").strip()
    if not error:
        return "Fiji reported that the last macro failed."
    return "Fiji reported a macro failure: {}.".format(error)


def _drain_fiji_events(
    active_turn: dict | None,
    pending_system_notes: deque[str],
    messages: list,
) -> None:
    """Drain background Fiji events into user-visible notices and next-turn notes."""
    while True:
        try:
            frame = events.EVENT_QUEUE.get_nowait()
        except queue.Empty:
            return
        if not isinstance(frame, dict):
            continue
        for formatter, prefix in (
            (_format_triage_note, "[triage] "),
            (_format_dialog_note, "[Fiji] "),
            (_format_macro_failure_note, "[Fiji] "),
        ):
            note = formatter(frame)
            if note is None:
                continue
            if active_turn is not None:
                messages.append({"role": "system", "content": note})
            else:
                pending_system_notes.append(note)
            _console_emit(prefix + note, reserve_status_line=True)
            break


def _phase6_system_note(user_text: str) -> str | None:
    """Inject the phase-6 rule when the user mentions thresholding work."""
    if not isinstance(user_text, str) or not _THRESHOLD_TRIGGER_RE.search(user_text):
        return None
    return (
        "The user mentioned thresholding, segmenting, or masking. "
        "Before choosing or running a threshold-based segmentation, call "
        "threshold_shootout() and use its montage plus metrics."
    )


def _phase9_system_note(user_text: str) -> str | None:
    """Inject the phase-9 offer when the user signals the workflow worked."""
    if not isinstance(user_text, str) or not user_text:
        return None
    asked_explicitly = _PHASE9_SLASH_RE.search(user_text) is not None
    signalled_success = _PHASE9_POSITIVE_RE.search(user_text) is not None
    if not asked_explicitly and not signalled_success:
        return None
    if not safety.recipe_offer_allowed():
        if asked_explicitly:
            return (
                "Do not offer a recipe save yet. The recent workflow still includes "
                "a failed or rejected macro/script in the last few runs. Ask the "
                "user to finish with one clean successful run first."
            )
        return None
    return tools_recipes.offer_recipe_save()


def _turn_worker(
    model: str,
    messages: list,
    tools: list,
    tool_map: dict,
    abort_event: threading.Event,
    result_queue: "queue.Queue[dict]",
) -> None:
    """Run one turn in a worker thread and push the result back to the main loop."""
    try:
        reply = _one_turn(model, messages, tools, tool_map, abort_event=abort_event)
    except Exception as exc:
        result_queue.put(
            {"status": "error", "error": "ERROR: {}: {}".format(type(exc).__name__, exc)}
        )
        return
    result_queue.put({"status": "ok", "reply": reply})


def _input_worker(
    event_queue: "queue.Queue[dict]",
    stop_event: threading.Event,
    ctx_state: dict,
    completer=None,
) -> None:
    """Read console input continuously so prompts can be queued while Gemma works."""
    while not stop_event.is_set():
        try:
            line = _read_input("\033[32myou>\033[0m ", ctx_state=ctx_state, completer=completer)
        except EOFError:
            event_queue.put({"type": "eof"})
            return
        except KeyboardInterrupt:
            event_queue.put({"type": "keyboard_interrupt"})
            continue
        event_queue.put({"type": "line", "text": line})


def run(
    model: str = DEFAULT_MODEL,
    no_friction_log: bool = False,
    prompt_filename: str = "GEMMA.md",
) -> int:
    """Start the interactive chat loop. Returns a shell-style exit code.

    Args:
        model: Ollama model name. Defaults to gemma4:31b-cloud.
        no_friction_log: Reserved for Phase 1c / friction.py. This loop
            does not yet write a friction log, so the flag is accepted
            and ignored. Keeps the CLI surface stable across phases.
        prompt_filename: Name of the system-prompt markdown file to load
            from this package's directory. Use "GEMMA_CLAUDE.md" to run
            the Claude-style variant for A/B comparison.
    """
    del no_friction_log  # no-op until friction.py lands in a later phase

    tools = list(REGISTRY)
    tool_map = _rebuild_tool_map()
    slash_completer = _build_slash_completer()
    ctx_state = {"used": 0, "limit": NUM_CTX}

    _console_emit("gemma4_31b_agent — model={} — {} tools — prompt={}".format(
        model, len(tools), prompt_filename
    ))
    _console_emit("Context window: {:,} tokens".format(NUM_CTX))
    _console_emit("Type a message and press Enter. /help for slash commands.")
    _console_emit("While Gemma is working, plain text queues and /interrupt aborts the current turn.")

    messages: list = []
    _gemma_md_path = os.path.join(os.path.dirname(__file__), prompt_filename)
    try:
        with open(_gemma_md_path, "r", encoding="utf-8") as _f:
            _gemma_md_text = _f.read().strip()
        if _gemma_md_text:
            messages.append({"role": "system", "content": _gemma_md_text})
    except OSError:
        pass
    pending_prompts: deque[str] = deque()
    pending_system_notes: deque[str] = deque()
    event_queue: "queue.Queue[dict]" = queue.Queue()
    input_stop = threading.Event()
    input_thread = threading.Thread(
        target=_input_worker,
        args=(event_queue, input_stop, ctx_state, slash_completer),
        daemon=True,
    )
    input_thread.start()

    active_turn: dict | None = None
    exiting = False

    try:
        while True:
            _drain_fiji_events(active_turn, pending_system_notes, messages)

            if active_turn is None and pending_prompts:
                while pending_system_notes:
                    messages.append({"role": "system", "content": pending_system_notes.popleft()})
                prompt_text = pending_prompts.popleft()
                phase6_note = _phase6_system_note(prompt_text)
                if phase6_note is not None:
                    messages.append({"role": "system", "content": phase6_note})
                phase9_note = _phase9_system_note(prompt_text)
                if phase9_note is not None:
                    messages.append({"role": "system", "content": phase9_note})
                messages.append({"role": "user", "content": prompt_text})
                abort_event = threading.Event()
                result_queue: "queue.Queue[dict]" = queue.Queue(maxsize=1)
                thread = threading.Thread(
                    target=_turn_worker,
                    args=(model, messages, tools, tool_map, abort_event, result_queue),
                    daemon=True,
                )
                active_turn = {
                    "thread": thread,
                    "abort_event": abort_event,
                    "result_queue": result_queue,
                    "prompt": prompt_text,
                }
                thread.start()

            if active_turn is not None and not active_turn["thread"].is_alive():
                result = active_turn["result_queue"].get_nowait()
                if result["status"] == "ok":
                    _console_emit("gemma> {}\n".format(result["reply"]), reserve_status_line=True)
                else:
                    _console_emit("{}\n".format(result["error"]), reserve_status_line=True)
                ctx_state["used"] = _estimate_tokens(messages)
                active_turn = None
                if exiting and not pending_prompts:
                    break

            try:
                event = event_queue.get(timeout=0.1)
            except queue.Empty:
                continue

            event_type = event.get("type")
            if event_type == "eof":
                _console_emit()
                if active_turn is None:
                    break
                exiting = True
                continue
            if event_type == "keyboard_interrupt":
                if active_turn is not None:
                    active_turn["abort_event"].set()
                    _console_emit("(interrupt requested)", reserve_status_line=True)
                else:
                    _console_emit("bye")
                    break
                continue

            text = str(event.get("text", "")).strip()
            if not text:
                continue

            command, command_arg = _parse_slash_command(text)
            queued_prompt = None

            if command == "/help":
                _print_help()
                continue
            if command == "/clear":
                if active_turn is not None:
                    _console_emit("(cannot clear while a turn is running; use /interrupt first)", reserve_status_line=True)
                    continue
                messages.clear()
                pending_prompts.clear()
                pending_system_notes.clear()
                ctx_state["used"] = 0
                _console_emit("(conversation cleared)", reserve_status_line=True)
                continue
            if command in {"/ccommands", "/ccommand"}:
                if not command_arg:
                    commands = _list_custom_commands()
                    if not commands:
                        _console_emit("(no custom commands found in ccommands/)", reserve_status_line=True)
                    else:
                        _console_emit("Custom commands:", reserve_status_line=True)
                        for name, path in commands:
                            _console_emit("  {:24s} - {}".format(name, path), reserve_status_line=True)
                    continue
                try:
                    queued_prompt, loaded_path = _load_custom_command(command_arg)
                except (OSError, ValueError) as exc:
                    _console_emit("({})".format(exc), reserve_status_line=True)
                    continue
                _console_emit(
                    "(loaded custom command '{}' from {})".format(command_arg, loaded_path),
                    reserve_status_line=True,
                )
            elif command == "/queue":
                if not command_arg:
                    _console_emit("(usage: /queue <text>)", reserve_status_line=True)
                    continue
                queued_prompt = command_arg
            elif command == "/save-recipe":
                queued_prompt = "/save-recipe"
            elif command == "/interrupt":
                if active_turn is None:
                    _console_emit("(no active turn)", reserve_status_line=True)
                    if command_arg:
                        pending_prompts.appendleft(command_arg)
                    continue
                active_turn["abort_event"].set()
                if command_arg:
                    pending_prompts.appendleft(command_arg)
                    _console_emit("(interrupt requested; queued replacement)", reserve_status_line=True)
                else:
                    _console_emit("(interrupt requested)", reserve_status_line=True)
                continue
            elif text.startswith("/"):
                _console_emit("(unknown command: {}. Type /help.)".format(command), reserve_status_line=True)
                continue
            elif text.lower() in {"exit", "quit", ":q"}:
                if active_turn is None:
                    break
                exiting = True
                _console_emit(
                    "(will exit after the current turn; use /interrupt to stop now)",
                    reserve_status_line=True,
                )
                continue
            else:
                queued_prompt = text

            if queued_prompt is None:
                continue

            if active_turn is None and not pending_prompts:
                pending_prompts.appendleft(queued_prompt)
            else:
                pending_prompts.append(queued_prompt)
                _console_emit("(queued {} pending)".format(len(pending_prompts)), reserve_status_line=True)
    except KeyboardInterrupt:
        if active_turn is not None:
            active_turn["abort_event"].set()
            _console_emit("(interrupt requested)", reserve_status_line=True)
        else:
            pass
    finally:
        input_stop.set()

    _console_emit("bye")
    return 0


if __name__ == "__main__":
    sys.exit(run())
