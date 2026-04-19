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

import base64
import json
import os
import queue
import re
import sys
import threading
import time
from collections import deque
from io import BytesIO
from pathlib import Path

import ollama
from PIL import Image

try:
    from prompt_toolkit import prompt as _pt_prompt
    from prompt_toolkit.application.current import get_app_or_none
    from prompt_toolkit.completion import WordCompleter
    from prompt_toolkit.formatted_text import ANSI
    from prompt_toolkit.history import FileHistory
    from prompt_toolkit.patch_stdout import patch_stdout
    from prompt_toolkit.styles import Style
except ImportError:  # pragma: no cover - optional dependency
    _pt_prompt = None
    get_app_or_none = None
    WordCompleter = None
    ANSI = None
    FileHistory = None
    patch_stdout = None
    Style = None

# Import the tool modules so their @tool decorators fire and the
# REGISTRY list is populated before we read it below.
from . import describe_image  # noqa: F401
from . import events
from . import safety
from . import threshold_shootout  # noqa: F401
from . import tools_bioformats  # noqa: F401
from . import tools_dialogs  # noqa: F401
from . import tools_fiji  # noqa: F401
from . import tools_jobs  # noqa: F401
from . import tools_plugins  # noqa: F401
from . import tools_python  # noqa: F401
from . import tools_recipes
from . import tools_shell  # noqa: F401
from . import triage_image  # noqa: F401
from .console_text import normalize_inline_latex_symbols
from .registry import REGISTRY, _rebuild_tool_map

DEFAULT_MODEL = "gemma4:31b-cloud"
NUM_CTX = 131072
TEMPERATURE = 0.2
SAMPLING_PROFILES: dict[str, dict] = {
    "tool": {"temperature": 0.25, "top_p": 0.90, "top_k": 30, "thinking": False},
    "plan": {"temperature": 0.60, "top_p": 0.92, "top_k": 50, "thinking": True},
    "recover": {"temperature": 0.30, "top_p": 0.90, "top_k": 35, "thinking": True},
    "explain": {"temperature": 0.60, "top_p": 0.95, "top_k": 64, "thinking": False},
    "recipe": {"temperature": 0.40, "top_p": 0.92, "top_k": 40, "thinking": False},
}
DEFAULT_MODE = "tool"
MAX_IDENTICAL_TOOL_REPEATS = 8
BUNDLED_CCOMMANDS_DIR = Path(__file__).resolve().parent / "ccommands"
_PROMPT_STYLE = (
    Style.from_dict(
        {
            "": "ansigreen",
            "bottom-toolbar": "noinherit noreverse bg:default default",
            "bottom-toolbar.text": "noinherit noreverse bg:default default",
        }
    )
    if Style is not None
    else None
)
_THINKING_STATUS = "Thinking"
_INSPECTING_STATUS = "Inspecting image"
_WRITING_STATUS = "Writing macro/script"
_RUNNING_FIJI_STATUS = "Running in Fiji"
_STATUS_REFRESH_S = 0.16
_STATUS_ANIMATION_FRAME_S = 0.5
_STATUS_TEXT_FRAME_S = 0.16
_STATUS_PULSE_REST_FRAMES = 4
_THINKING_DELAY_S = 1.0
_INSPECTING_DELAY_S = 2.0
_WRITING_DELAY_S = 1.0
_RUNNING_FIJI_DELAY_S = 4.0
_INSPECT_TOOLS = frozenset(
    {
        "capture_image",
        "click_dialog_button",
        "close_dialogs",
        "count_bright_regions",
        "describe_image",
        "get_histogram",
        "get_image_info",
        "get_log",
        "get_metadata",
        "get_open_windows",
        "get_pixels_array",
        "get_results",
        "get_state",
        "histogram_summary",
        "line_profile",
        "list_dialog_components",
        "probe_plugin",
        "quick_object_count",
        "region_stats",
        "set_dialog_checkbox",
        "set_dialog_dropdown",
        "set_dialog_text",
        "triage_image",
    }
)
_SCRIPT_TOOLS = frozenset({"run_macro", "run_macro_async", "run_script"})
_RUN_IN_FIJI_TOOLS = frozenset({"threshold_shootout"})
_TOOL_ICONS = {
    "describe_image": "🔎",
    "triage_image": "🔎",
    "get_image_info": "🔎",
    "get_state": "🔎",
    "get_metadata": "🔎",
    "get_open_windows": "🔎",
    "get_log": "🔎",
    "capture_image": "📸",
    "get_pixels_array": "🔬",
    "region_stats": "🔬",
    "line_profile": "🔬",
    "get_histogram": "▁▃█▃▁",
    "histogram_summary": "▁▃█▃▁",
    "get_results": "💡",
    "quick_object_count": "∑",
    "count_bright_regions": "∑",
    "threshold_shootout": "🪄",
    "close_dialogs": "✖️",
    "list_dialog_components": "🧾",
    "click_dialog_button": "🖱️",
    "set_dialog_text": "📝",
    "set_dialog_checkbox": "✔️",
    "set_dialog_dropdown": "▾",
    "probe_plugin": "🧩",
    "run_macro": "🪄",
    "run_script": "🪄",
    "run_macro_async": "🪄",
    "job_status": "⏱️",
    "cancel_job": "🛑",
    "offer_recipe_save": "💾",
    "save_recipe": "💾",
    "run_shell": "💻",
}
_TOOL_ICON_PALETTES = {
    "get_histogram": (
        (92, 214, 255),
        (102, 236, 173),
        (255, 205, 82),
        (255, 142, 92),
        (206, 126, 255),
    ),
    "histogram_summary": (
        (92, 214, 255),
        (102, 236, 173),
        (255, 205, 82),
        (255, 142, 92),
        (206, 126, 255),
    ),
}
_TOOL_ICON_RGB = {
    "quick_object_count": (88, 224, 255),
    "count_bright_regions": (88, 224, 255),
}
_THRESHOLD_TRIGGER_RE = re.compile(
    r"\b(threshold(?:ing)?|segment(?:ation|ing)?|mask(?:ing)?|binary|binar(?:ise|ize|isation|ization))\b",
    re.IGNORECASE,
)
_FILTER_TRIGGER_RE = re.compile(
    r"\b(filter(?:s|ing)?|smoothing)\b",
    re.IGNORECASE,
)
_MULTISERIES_TRIGGER_RE = re.compile(
    r"\.(lif|czi|nd2|lsm)\b",
    re.IGNORECASE,
)
_SCRIPT_LANG_TRIGGER_RE = re.compile(
    r"\b(groovy|jython|javascript|run[_-]?script)\b",
    re.IGNORECASE,
)
_HALLUCINATED_FILTER_TRIGGER_RE = re.compile(
    r"\b(?:\d+|ten|several|different|various|compare|many|multiple)\b"
    r"[^.?!\n]{0,40}?"
    r"\b(?:filter(?:s|ing)?|filter\s+sets?)\b",
    re.IGNORECASE,
)
_SAMPLE_IMAGE_NAME_RE = re.compile(
    r"\b(blobs?|cell\s+colony|mri\s+stack|mri|fluorescent\s+cells?|"
    r"t1\s+head|embryos?|leafs?|boats?|bridge|clown|hela(?:\s+cells?)?|"
    r"m51\s+galaxy|m51)\b",
    re.IGNORECASE,
)
_SAMPLE_IMAGE_VERB_RE = re.compile(
    r"\b(open|load|use|show|get|give\s+me)\b",
    re.IGNORECASE,
)
_RECIPE_LOOP_TRIGGER_RE = re.compile(
    r"\b(?:\d+|several|each|all|every|multiple|many)\s+"
    r"(?:different\s+|distinct\s+|\w+\s+){0,2}"
    r"(?:filter|threshold|method|image|slice|series|condition)s?\b",
    re.IGNORECASE,
)
_3D_RENDER_TRIGGER_RE = re.compile(
    r"\b(3d\s+render(?:ing)?|3d\s+view(?:er)?|"
    r"rotate\s+(?:the\s+)?(?:image|stack|cell|volume)|"
    r"3dscript|batch\s+animation|volume\s+render|animation)\b",
    re.IGNORECASE,
)
_ACTIVE_IMAGE_VERB_RE = re.compile(
    r"\b(measure|threshold|segment|duplicate|save\s+(?:as|the)|crop|"
    r"analy[sz]e|mask|histogram|count\s+particles)\b",
    re.IGNORECASE,
)
_DECISION_MODE_RE = re.compile(
    r"\b(?:should|which|compare|better|recommend|choose\s+between)\b",
    re.IGNORECASE,
)
_EXPLAIN_MODE_RE = re.compile(
    r"\b(?:why|explain|what\s+does|how\s+does|what\s+is\s+the)\b",
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
    ("/think [on|off|auto]", "Force thinking mode on/off, or return to auto. No arg = show state."),
    ("/mode [<name>|auto]", "Lock sampling mode (tool/plan/recover/explain/recipe) or return to auto."),
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


def _encode_capture_for_vision(path_str: str) -> str | None:
    """Convert a capture PNG into a bounded JPEG base64 payload for Gemma vision."""
    try:
        if path_str.startswith('ERROR:'):
            return None
        path = Path(path_str)
        if not path.is_file() or not os.access(path, os.R_OK):
            return None
        with Image.open(path) as src:
            image = src.copy()
        if image.mode != 'RGB':
            image = image.convert('RGB')
        if max(image.size) > 896:
            scale = 896.0 / float(max(image.size))
            image = image.resize(
                (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
                Image.Resampling.LANCZOS,
            )
        while True:
            buf = BytesIO()
            image.save(buf, format='JPEG', quality=85)
            data = buf.getvalue()
            if len(data) <= 500_000:
                return base64.b64encode(data).decode('ascii')
            if max(image.size) < 256:
                return None
            image = image.resize(
                (max(1, round(image.width * 0.75)), max(1, round(image.height * 0.75))),
                Image.Resampling.LANCZOS,
            )
    except Exception:
        return None


def _prune_older_capture_images(messages: list[dict]) -> None:
    """Drop any older tool-attached images before adding a fresh capture."""
    for msg in reversed(messages):
        if isinstance(msg, dict) and msg.get('role') == 'tool':
            msg.pop('images', None)


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
    """True when prompt_toolkit is active and can render live prompt status."""
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
    """Return the current live working status."""
    with _PROMPT_STATUS_LOCK:
        return (
            str(_PROMPT_STATUS.get("label") or ""),
            float(_PROMPT_STATUS.get("started_at") or 0.0),
        )


def _rgb_text(text: str, rgb: tuple[int, int, int]) -> str:
    """Wrap text in a true-color ANSI foreground sequence."""
    red, green, blue = rgb
    return "\033[38;2;{};{};{}m{}".format(red, green, blue, text)


def _colorize_chars(text: str, colors: tuple[tuple[int, int, int], ...]) -> str:
    """Apply a per-character palette to one animation frame."""
    if not text:
        return ""
    colored: list[str] = []
    for color_index, char in enumerate(text):
        rgb = colors[color_index % len(colors)]
        if char == " ":
            colored.append(char)
            continue
        colored.append(_rgb_text(char, rgb))
    return "".join(colored)


def _status_frame_index(elapsed_s: float, frame_s: float) -> int:
    """Return the frame index for the current elapsed time."""
    return max(0, int(elapsed_s / frame_s))


def _status_animation_index(elapsed_s: float) -> int:
    """Return the steady animation frame index."""
    return _status_frame_index(elapsed_s, _STATUS_ANIMATION_FRAME_S)


def _status_text_pulse_slot(elapsed_s: float, active_slots: int) -> int | None:
    """Return the active text-pulse slot, or None during the resting gap."""
    cycle = max(1, active_slots) + _STATUS_PULSE_REST_FRAMES
    frame = _status_frame_index(elapsed_s, _STATUS_TEXT_FRAME_S) % cycle
    if frame >= active_slots:
        return None
    return frame


def _status_animation_frame(label: str, elapsed_s: float) -> str:
    """Return a one-frame ImageJ-themed activity animation."""
    frames: tuple[str, ...]
    animation_index = _status_animation_index(elapsed_s)
    if label == _THINKING_STATUS:
        frames = (
            "•···•",
            "·•·•·",
            "··●··",
            "·•·•·",
        )
        palette = (
            (255, 82, 82),
            (255, 166, 92),
            (255, 232, 92),
            (168, 236, 96),
            (88, 255, 132),
        )
    elif label == _INSPECTING_STATUS:
        frames = ("●○○", "○●○", "○○●", "○●○")
        palette = (
            (92, 163, 255),
            (92, 227, 159),
            (215, 112, 255),
        )
    elif label == _WRITING_STATUS:
        frames = ("[>__]", "[_>_]", "[__>]", "[_>_]")
        palette = (
            (92, 163, 255),
            (255, 179, 71),
            (255, 92, 141),
            (92, 227, 159),
            (92, 163, 255),
        )
    elif label == _RUNNING_FIJI_STATUS:
        frames = ("[▮  ]", "[ ▮ ]", "[  ▮]", "[ ▮ ]")
        palette = (
            (92, 163, 255),
            (255, 92, 141),
            (255, 179, 71),
            (92, 227, 159),
            (92, 163, 255),
        )
    else:
        return ""
    return _colorize_chars(frames[animation_index % len(frames)], palette)


def _status_label_wave(label: str, elapsed_s: float) -> str:
    """Render the activity label with a moving highlight wave."""
    chars = list(str(label or ""))
    wave_positions = [index for index, char in enumerate(chars) if not char.isspace()]
    if not wave_positions:
        return "\033[90m{}\033[0m".format(label)

    pulse_slot = _status_text_pulse_slot(elapsed_s, len(wave_positions))
    highlighted: list[str] = []

    if pulse_slot is None:
        for char in chars:
            if char.isspace():
                highlighted.append("\033[90m ")
            else:
                highlighted.append(_rgb_text(char, (128, 138, 156)))
        return "".join(highlighted)

    center_index = wave_positions[pulse_slot]

    for index, char in enumerate(chars):
        if char.isspace():
            highlighted.append("\033[90m ")
            continue
        distance = abs(index - center_index)
        if distance == 0:
            rgb = (255, 255, 255)
        elif distance == 1:
            rgb = (214, 225, 255)
        elif distance == 2:
            rgb = (176, 190, 230)
        else:
            rgb = (128, 138, 156)
        highlighted.append(_rgb_text(char, rgb))

    return "".join(highlighted)


def _format_status_line(label: str, elapsed_s: float) -> str:
    """Render one complete status line with colored animation."""
    animation = _status_animation_frame(label, elapsed_s)
    label_text = _status_label_wave(label, elapsed_s)
    elapsed_display = max(0, int(elapsed_s))
    if animation:
        return "  {} {} \033[90m({}s)\033[0m".format(animation, label_text, elapsed_display)
    return "  {} \033[90m({}s)\033[0m".format(label_text, elapsed_display)


def _prompt_status_text() -> str:
    """Render the live working status line shown above the prompt."""
    label, started_at = _get_prompt_status()
    if label:
        elapsed = max(0.0, time.time() - started_at)
        return _format_status_line(label, elapsed)
    return ""


def _render_prompt_message(prompt_text: str) -> object:
    """Render the optional working line plus the main prompt."""
    status_line = _prompt_status_text()
    text = "{}\n{}".format(status_line, prompt_text) if status_line else prompt_text
    if ANSI is None:
        return text
    return ANSI(text)


def _render_prompt_toolbar(ctx_state: dict | None) -> object:
    """Render the single-line context footer under the prompt."""
    text = ""
    if isinstance(ctx_state, dict):
        text = "  " + _format_ctx_bar(
            int(ctx_state.get("used", 0)),
            int(ctx_state.get("limit", NUM_CTX)),
        )
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


class _ActivityTicker:
    """Turn-level activity ticker with coarse status changes."""

    def __init__(self):
        self._phase_initial = ""
        self._phase_transitions: tuple[tuple[float, str], ...] = ()
        self._phase_started_at = 0.0
        self._visible_label = ""
        self._visible_started_at = 0.0
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None

    def start(self, label: str):
        self._stop.clear()
        now = time.time()
        label = _sanitize_status_label(label)
        with self._lock:
            self._phase_initial = label
            self._phase_transitions = ()
            self._phase_started_at = now
            self._visible_label = label
            self._visible_started_at = now if label else 0.0
        _set_prompt_status(label, now if label else 0.0)
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def current_label(self) -> str:
        """Return the currently visible status label."""
        with self._lock:
            return self._visible_label

    def set_phase(
        self,
        initial_label: str,
        transitions: tuple[tuple[float, str], ...] = (),
    ) -> None:
        """Set a new phase. Delayed transitions keep quick work from thrashing."""
        now = time.time()
        initial_label = _sanitize_status_label(initial_label)
        clean_transitions = tuple(
            (max(0.0, float(delay_s)), _sanitize_status_label(label))
            for delay_s, label in transitions
            if _sanitize_status_label(label)
        )
        update_label = ""
        update_started = 0.0
        with self._lock:
            self._phase_initial = initial_label
            self._phase_transitions = clean_transitions
            self._phase_started_at = now
            if initial_label and initial_label != self._visible_label:
                self._visible_label = initial_label
                self._visible_started_at = now
                update_label = initial_label
                update_started = now
        if update_label:
            _set_prompt_status(update_label, update_started)
        else:
            _invalidate_prompt()

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join()
            self._thread = None
        _set_prompt_status()
        if not _prompt_status_enabled():
            with _CONSOLE_LOCK:
                sys.stdout.write("\r\033[K")
                sys.stdout.flush()

    def _run(self):
        while not self._stop.is_set():
            now = time.time()
            line = ""
            with self._lock:
                elapsed = max(0.0, now - self._phase_started_at)
                target_label = self._phase_initial
                target_started_at = self._phase_started_at if target_label else 0.0
                for delay_s, label in self._phase_transitions:
                    if elapsed >= delay_s:
                        target_label = label
                        target_started_at = self._phase_started_at + delay_s
                    else:
                        break
                if target_label != self._visible_label:
                    self._visible_label = target_label
                    self._visible_started_at = target_started_at
                    _set_prompt_status(target_label, target_started_at)
                elif target_label:
                    elapsed_s = max(0.0, now - self._visible_started_at)
                    line = _format_status_line(target_label, elapsed_s)
            if _prompt_status_enabled():
                _invalidate_prompt()
            elif line:
                with _CONSOLE_LOCK:
                    sys.stdout.write("\r\033[K{}".format(line))
                    sys.stdout.flush()
            elif not _prompt_status_enabled():
                with _CONSOLE_LOCK:
                    sys.stdout.write("\r\033[K")
                    sys.stdout.flush()
            self._stop.wait(_STATUS_REFRESH_S)


def _status_plan_for_model_round(
    round_n: int,
    current_label: str,
    async_job_active: bool,
) -> tuple[str, tuple[tuple[float, str], ...]]:
    """Return the coarse status plan for one model call."""
    if round_n <= 1:
        return _THINKING_STATUS, ()
    if async_job_active and current_label in {_WRITING_STATUS, _RUNNING_FIJI_STATUS}:
        return current_label, ()
    base = current_label or _THINKING_STATUS
    if base == _THINKING_STATUS:
        return _THINKING_STATUS, ()
    return base, ((_THINKING_DELAY_S, _THINKING_STATUS),)


def _status_plan_for_tool(
    tool_name: str,
    current_label: str,
    async_job_active: bool,
) -> tuple[str, tuple[tuple[float, str], ...]]:
    """Return the coarse status plan for one tool call."""
    base = current_label or _THINKING_STATUS
    if tool_name in _SCRIPT_TOOLS:
        return base, (
            (_WRITING_DELAY_S, _WRITING_STATUS),
            (_RUNNING_FIJI_DELAY_S, _RUNNING_FIJI_STATUS),
        )
    if tool_name in _RUN_IN_FIJI_TOOLS:
        return base, ((_INSPECTING_DELAY_S, _RUNNING_FIJI_STATUS),)
    if tool_name in _INSPECT_TOOLS:
        return base, ((_INSPECTING_DELAY_S, _INSPECTING_STATUS),)
    if tool_name == "job_status" and async_job_active:
        return base, ((_THINKING_DELAY_S, _RUNNING_FIJI_STATUS),)
    return base, ()


def _update_async_job_state(tool_name: str, result, async_job_active: bool) -> bool:
    """Track whether a background Fiji macro job is still running."""
    if tool_name == "run_macro_async":
        return isinstance(result, str) and bool(result.strip()) and not result.startswith("ERROR:")
    if tool_name == "job_status":
        if not isinstance(result, dict):
            return async_job_active
        payload = result.get("result") if isinstance(result.get("result"), dict) else result
        state = str(payload.get("state") or "").strip().lower() if isinstance(payload, dict) else ""
        if state in {"queued", "running", "started"}:
            return True
        if state in {"completed", "failed", "cancelled"}:
            return False
        return async_job_active
    if tool_name == "cancel_job":
        if not isinstance(result, dict):
            return async_job_active
        payload = result.get("result") if isinstance(result.get("result"), dict) else result
        if isinstance(payload, dict) and payload.get("cancelled") is True:
            return False
    return async_job_active


def _tool_icon(tool_name: str) -> str:
    """Return the display icon for one tool call."""
    return _TOOL_ICONS.get(str(tool_name or "").strip(), "⚡")


def _tool_icon_display(tool_name: str) -> str:
    """Return one tool icon, with standalone coloring when needed."""
    tool_key = str(tool_name or "").strip()
    icon = _TOOL_ICONS.get(tool_key, "⚡")
    palette = _TOOL_ICON_PALETTES.get(tool_key)
    if palette:
        return _colorize_chars(icon, palette) + "\033[0m"
    rgb = _TOOL_ICON_RGB.get(tool_key)
    if rgb:
        return _rgb_text(icon, rgb) + "\033[0m"
    return icon


def _chat_interruptible(abort_event: threading.Event | None = None, **kwargs):
    """Run ollama.chat() in a worker thread so Ctrl-C on the main thread is responsive.

    On Windows the ollama client's blocking socket read swallows
    SIGINT, so signals never reach us. Running the call on a daemon
    thread and polling lets the main thread handle KeyboardInterrupt
    the normal way.
    """
    return _run_interruptible(ollama.chat, abort_event=abort_event, **kwargs)


def _format_assistant_reply(text: str) -> str:
    """Normalize assistant text before writing it to the console."""
    return normalize_inline_latex_symbols(text or "")


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


def _format_tool_args_for_display(value, indent: int = 0) -> str:
    """Pretty-print tool arguments, expanding multiline strings for readability."""
    pad = " " * indent
    child_pad = " " * (indent + 2)

    if isinstance(value, dict):
        if not value:
            return "{}"
        lines = ["{"]
        items = list(value.items())
        for index, (key, item) in enumerate(items):
            rendered = _format_tool_args_for_display(item, indent + 2)
            rendered_lines = rendered.splitlines() or [""]
            entry = '{}: {}'.format(
                json.dumps(str(key), ensure_ascii=False),
                rendered_lines[0],
            )
            lines.append(child_pad + entry)
            for extra_line in rendered_lines[1:]:
                lines.append(extra_line)
            if index < len(items) - 1:
                lines[-1] += ","
        lines.append(pad + "}")
        return "\n".join(lines)

    if isinstance(value, list):
        if not value:
            return "[]"
        lines = ["["]
        for index, item in enumerate(value):
            rendered = _format_tool_args_for_display(item, indent + 2)
            rendered_lines = rendered.splitlines() or [""]
            lines.append(child_pad + rendered_lines[0])
            for extra_line in rendered_lines[1:]:
                lines.append(extra_line)
            if index < len(value) - 1:
                lines[-1] += ","
        lines.append(pad + "]")
        return "\n".join(lines)

    if isinstance(value, str):
        text = value.replace("\r\n", "\n").replace("\r", "\n")
        if "\n" not in text:
            return json.dumps(text, ensure_ascii=False)
        lines = ["|"]
        block_pad = " " * (indent + 2)
        for raw_line in text.split("\n"):
            lines.append(block_pad + raw_line)
        return "\n".join(lines)

    try:
        return json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        return str(value)


def _canonical_json(value) -> str:
    """Stable JSON form used for exact-tool-repeat detection."""
    try:
        return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    except (TypeError, ValueError):
        return str(value)


def _sampling_profile_for_mode(mode: str) -> tuple[dict, bool]:
    """Return one mode's sampling settings and default thinking flag."""
    profile = SAMPLING_PROFILES.get(str(mode or ""))
    if not isinstance(profile, dict):
        return {"temperature": TEMPERATURE}, False
    sampling = {key: value for key, value in profile.items() if key != "thinking"}
    if not sampling:
        sampling = {"temperature": TEMPERATURE}
    return sampling, bool(profile.get("thinking"))


def _auto_turn_mode(user_text: str, last_turn_had_failure: bool) -> str:
    """Pick the automatic turn mode from text and carry-over failure state."""
    if last_turn_had_failure:
        return "recover"
    text = str(user_text or "")
    if _PHASE9_SLASH_RE.search(text):
        return "recipe"
    if _EXPLAIN_MODE_RE.search(text):
        return "explain"
    if _DECISION_MODE_RE.search(text):
        return "plan"
    return DEFAULT_MODE


def _resolve_turn_config(
    user_text: str,
    mode_lock: str | None,
    think_lock: bool | None,
    last_turn_had_failure: bool,
) -> dict:
    """Pick mode + thinking once at the start of a user turn."""
    auto_mode = _auto_turn_mode(user_text, last_turn_had_failure)
    locked_mode = str(mode_lock or "").strip().lower() or None
    if locked_mode not in SAMPLING_PROFILES:
        locked_mode = None
    mode = locked_mode or auto_mode
    source = "lock" if locked_mode is not None else ("recover" if auto_mode == "recover" else "auto")
    sampling, default_thinking = _sampling_profile_for_mode(mode)
    thinking = bool(default_thinking if think_lock is None else think_lock)
    return {
        "mode": mode,
        "thinking": thinking,
        "sampling": sampling,
        "source": source,
        "mode_lock": locked_mode,
        "think_lock": think_lock,
        "auto_mode": auto_mode,
        "recover_flipped": False,
        "had_failure_state": {"value": False},
    }


def _turn_had_failure(turn_config: dict) -> bool:
    """Return whether the current turn hit a failure path."""
    state = turn_config.get("had_failure_state")
    if isinstance(state, dict):
        return bool(state.get("value"))
    return False


def _mark_turn_failure(turn_config: dict) -> None:
    """Mark the turn as failed so the next turn can resolve to recover mode."""
    state = turn_config.get("had_failure_state")
    if not isinstance(state, dict):
        state = {"value": False}
        turn_config["had_failure_state"] = state
    state["value"] = True


def _log_turn_config(mode: str, thinking: bool, source: str) -> None:
    """Log the current mode/thinking choice for later tuning."""
    safety.friction_log(
        {
            "event": "turn_config",
            "mode": str(mode or DEFAULT_MODE),
            "thinking": bool(thinking),
            "source": str(source or "auto"),
        }
    )


def _apply_mode_to_turn_config(turn_config: dict, mode: str, source: str) -> None:
    """Mutate an existing turn config to a new mode, respecting think locks."""
    sampling, default_thinking = _sampling_profile_for_mode(mode)
    think_lock = turn_config.get("think_lock")
    turn_config["mode"] = mode
    turn_config["source"] = source
    turn_config["sampling"] = sampling
    turn_config["thinking"] = bool(default_thinking if think_lock is None else think_lock)


def _flip_turn_config_to_recover(turn_config: dict) -> bool:
    """Switch the current turn to recover mode once, after a failure."""
    _mark_turn_failure(turn_config)
    if str(turn_config.get("mode") or "") == "recover":
        return False
    if bool(turn_config.get("recover_flipped")):
        return False
    _apply_mode_to_turn_config(turn_config, "recover", "recover")
    turn_config["recover_flipped"] = True
    _log_turn_config(turn_config["mode"], turn_config["thinking"], "recover")
    return True


def _looks_like_think_option_rejection(exc: BaseException) -> bool:
    """True when Ollama rejected the native think option."""
    text = "{}: {}".format(type(exc).__name__, exc).lower()
    if "think" not in text:
        return False
    return any(
        needle in text
        for needle in (
            "unknown",
            "unexpected",
            "invalid",
            "unsupported",
            "not allowed",
            "not recognised",
            "not recognized",
        )
    )


def _mode_lock_label(mode_lock: str | None) -> str:
    """Render one mode-lock label for slash-command status output."""
    return str(mode_lock or "auto")


def _think_lock_label(think_lock: bool | None) -> str:
    """Render one think-lock label for slash-command status output."""
    if think_lock is None:
        return "auto"
    return "on" if think_lock else "off"


def _mode_profiles_summary() -> str:
    """Render the list of available sampling profiles."""
    return ", ".join(SAMPLING_PROFILES.keys())


def _handle_think_command(
    command_arg: str,
    mode_state: dict,
    think_state: dict,
    last_turn_had_failure: bool,
) -> str:
    """Apply /think and return the one-line console confirmation."""
    arg = str(command_arg or "").strip().lower()
    if not arg:
        preview = _resolve_turn_config(
            "",
            mode_state.get("lock"),
            think_state.get("lock"),
            last_turn_had_failure,
        )
        return "(thinking lock: {}; next turn: {} via {})".format(
            _think_lock_label(think_state.get("lock")),
            "on" if preview["thinking"] else "off",
            preview["mode"],
        )
    if arg == "auto":
        think_state["lock"] = None
        return "(thinking lock: auto)"
    if arg == "on":
        think_state["lock"] = True
        return "(thinking lock: on)"
    if arg == "off":
        think_state["lock"] = False
        return "(thinking lock: off)"
    return "(unknown think mode: {}; choose on, off, or auto)".format(arg)


def _handle_mode_command(command_arg: str, mode_state: dict) -> str:
    """Apply /mode and return the one-line console confirmation."""
    arg = str(command_arg or "").strip().lower()
    if not arg:
        return "(mode lock: {}; profiles: {})".format(
            _mode_lock_label(mode_state.get("lock")),
            _mode_profiles_summary(),
        )
    if arg == "auto":
        mode_state["lock"] = None
        return "(mode lock: auto)"
    if arg in SAMPLING_PROFILES:
        mode_state["lock"] = arg
        return "(mode lock: {})".format(arg)
    return "(unknown mode: {}; choose auto or {})".format(arg, _mode_profiles_summary())


def _turn_banner_text(
    turn_config: dict,
    mode_state: dict,
    think_state: dict,
    previous_auto_mode: str | None,
) -> str:
    """Build the per-turn mode/thinking banner, if needed."""
    lines: list[str] = []
    mode_lock = mode_state.get("lock")
    think_lock = think_state.get("lock")
    if mode_lock is not None:
        lines.append("[mode] {} (locked)".format(turn_config["mode"]))
    elif previous_auto_mode is not None and turn_config["mode"] != previous_auto_mode:
        lines.append("[mode] {}".format(turn_config["mode"]))
    if think_lock is not None:
        lines.append("[thinking] {} (locked)".format("on" if turn_config["thinking"] else "off"))
    return "\n".join(lines)


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
        line = input("\033[32myou> ")
        sys.stdout.write("\033[0m")
        sys.stdout.flush()
        return line

    kwargs = {
        "bottom_toolbar": lambda: _render_prompt_toolbar(ctx_state),
        "complete_while_typing": True,
        "message": lambda: _render_prompt_message(prompt_text),
        "style": _PROMPT_STYLE,
    }
    if completer is not None:
        kwargs["completer"] = completer
    if FileHistory is not None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        kwargs["history"] = FileHistory(str(PROMPT_HISTORY_PATH))
    if patch_stdout is not None:
        with patch_stdout(raw=True):
            return _pt_prompt(**kwargs)
    return _pt_prompt(**kwargs)


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
    turn_config: dict,
    think_capability_state: dict | None = None,
    abort_event: threading.Event | None = None,
) -> tuple[str, bool]:
    """Run one user turn through ollama.chat(), dispatching tools as needed."""
    ticker = _ActivityTicker()
    turn_start = time.time()
    round_n = 0
    last_tool_signature = None
    identical_tool_repeats = 0
    async_job_active = False
    think_capability_state = think_capability_state or {"supported": True, "logged": False}
    # Scratchpad shared across post-tool injectors within this turn.
    # Injectors can read/write any key; example: _stale_error_loop_injector
    # uses turn_state["last_tool_error"] to compare consecutive errors.
    turn_state: dict = {}

    ticker.start(_THINKING_STATUS)
    try:
        while True:
            round_n += 1
            model_initial, model_transitions = _status_plan_for_model_round(
                round_n,
                ticker.current_label(),
                async_job_active,
            )
            ticker.set_phase(model_initial, model_transitions)
            options = {
                **turn_config["sampling"],
                "num_ctx": NUM_CTX,
            }
            if turn_config["thinking"] and think_capability_state.get("supported", True):
                options["think"] = True
            try:
                try:
                    resp = _chat_interruptible(
                        abort_event=abort_event,
                        model=model,
                        messages=messages,
                        tools=tools,
                        stream=False,
                        options=options,
                    )
                except Exception as exc:
                    if not (
                        options.get("think") is True
                        and _looks_like_think_option_rejection(exc)
                    ):
                        raise
                    think_capability_state["supported"] = False
                    if not think_capability_state.get("logged", False):
                        safety.friction_log(
                            {
                                "event": "ollama_think_unsupported",
                                "error": "{}: {}".format(type(exc).__name__, exc),
                            }
                        )
                        think_capability_state["logged"] = True
                    fallback_options = dict(options)
                    fallback_options.pop("think", None)
                    resp = _chat_interruptible(
                        abort_event=abort_event,
                        model=model,
                        messages=messages,
                        tools=tools,
                        stream=False,
                        options=fallback_options,
                    )
            finally:
                _invalidate_prompt()

            if abort_event is not None and abort_event.is_set():
                raise _TurnAborted()

            msg = resp.message
            messages.append(msg)
            eval_tokens = getattr(resp, "eval_count", 0) or 0

            tool_calls = getattr(msg, "tool_calls", None) or []
            if not tool_calls:
                elapsed = time.time() - turn_start
                tok_s = eval_tokens / elapsed if elapsed > 0 and eval_tokens else 0
                stats = "\033[90mWorked for {:.1f}s".format(elapsed)
                if eval_tokens:
                    stats += " ({} tok, {:.1f} tok/s)".format(eval_tokens, tok_s)
                stats += "\033[0m"
                content = (getattr(msg, "content", "") or "").strip()
                return "{}\n  {}".format(
                    _format_assistant_reply(content),
                    stats,
                ), _turn_had_failure(turn_config)

            for call in tool_calls:
                name = call.function.name
                args = call.function.arguments or {}
                if name not in tool_map:
                    result_text = "ERROR: unknown tool '{}'".format(name)
                    _console_emit("  \033[31m✗ {}\033[0m".format(result_text), reserve_status_line=True)
                    messages.append({"role": "tool", "content": result_text})
                    _flip_turn_config_to_recover(turn_config)
                    continue
                _console_emit(
                    "  {} \033[33m{}({})\033[0m".format(
                        _tool_icon_display(name),
                        name,
                        _format_tool_args_for_display(args)
                    ),
                    reserve_status_line=True,
                )
                pre_dispatch_note = _pre_dispatch_abort_note(name, args)
                if pre_dispatch_note is not None:
                    _console_emit(
                        "  \033[31m⛔ pre-dispatch abort: {}\033[0m".format(
                            pre_dispatch_note[:200]
                        ),
                        reserve_status_line=True,
                    )
                    messages.append({
                        "role": "tool",
                        "content": "ABORTED: {}".format(pre_dispatch_note),
                        "tool_name": name,
                    })
                    messages.append({"role": "system", "content": pre_dispatch_note})
                    _flip_turn_config_to_recover(turn_config)
                    continue
                tool_initial, tool_transitions = _status_plan_for_tool(
                    name,
                    ticker.current_label(),
                    async_job_active,
                )
                ticker.set_phase(tool_initial, tool_transitions)
                try:
                    result = _run_interruptible(tool_map[name], abort_event=abort_event, **args)
                except _TurnAborted:
                    raise _TurnAborted()
                except Exception as exc:  # surface to the model, not the user
                    result = "ERROR: {}: {}".format(type(exc).__name__, exc)
                finally:
                    _invalidate_prompt()
                previous_async_state = async_job_active
                async_job_active = _update_async_job_state(name, result, async_job_active)
                if async_job_active and not previous_async_state:
                    ticker.set_phase(_RUNNING_FIJI_STATUS)
                result_text = _format_tool_result(result)
                if result_text.startswith("ERROR:"):
                    _flip_turn_config_to_recover(turn_config)
                display_text = result_text
                if len(display_text) > 20480:
                    hidden = len(display_text) - 20480
                    display_text = display_text[:20480] + "\n… [truncated, {} chars hidden]".format(hidden)
                _console_emit("  \033[90m→ {}\033[0m".format(display_text), reserve_status_line=True)
                msg = {'role': 'tool', 'content': result_text, 'tool_name': name}
                if name == 'capture_image':
                    _b64 = _encode_capture_for_vision(result_text)
                    if _b64:
                        msg['images'] = [_b64]
                        _prune_older_capture_images(messages)
                messages.append(msg)
                for post_note in _post_tool_system_notes(name, args, result_text, turn_state):
                    messages.append({"role": "system", "content": post_note})
                    preview_len = 140
                    preview = post_note[:preview_len] + (
                        "…" if len(post_note) > preview_len else ""
                    )
                    _console_emit(
                        "  \033[95m↯ post-tool note: {}\033[0m".format(preview),
                        reserve_status_line=True,
                    )
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
                    ), _turn_had_failure(turn_config)
    except _TurnAborted:
        _mark_turn_failure(turn_config)
        elapsed = time.time() - turn_start
        return "\033[31m(aborted by user after {:.1f}s)\033[0m".format(elapsed), True
    finally:
        ticker.stop()


def _format_triage_note(frame: dict) -> str | None:
    """Run phase-5 triage for a newly opened image and return a system note."""
    if str(frame.get("event") or "") != "image.opened":
        return None
    data = frame.get("data") if isinstance(frame.get("data"), dict) else {}
    title = str(data.get("title") or "new image")
    # Plugin-output titles ("Objects map of X", "Summary of X", "Labels", ...)
    # are success signals, not input images. Emit a dominant marker even when
    # the calibration/saturation checklist is quiet, so a dialog-pause error
    # can't mask the fact that the plugin actually produced output.
    if events.is_plugin_output(title):
        return (
            "PLUGIN OUTPUT: Fiji opened '{}' — a plugin just produced this. "
            "The last macro's work landed; do NOT retry run_macro. "
            "Call get_results / get_state to read the result."
        ).format(title)
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
            if formatter is _format_macro_failure_note and active_turn is not None:
                _flip_turn_config_to_recover(active_turn.get("turn_config") or {})
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


def _filter_vocab_system_note(user_text: str) -> str | None:
    """Re-surface the filter-vs-threshold vocabulary when the user mentions filters."""
    if not isinstance(user_text, str) or not _FILTER_TRIGGER_RE.search(user_text):
        return None
    return (
        "The user mentioned filters. Filter = intensity operation (Gaussian, "
        "Median, Mean, Unsharp, Convolve, Variance, Bandpass). Threshold = "
        "binarisation method (Otsu, Li, Default). 'N filters' never means "
        "'N threshold methods' — do not substitute threshold_shootout."
    )


def _multiseries_note(user_text: str) -> str | None:
    """Warn about .lif/.czi/.nd2/.lsm multi-series container files."""
    if not isinstance(user_text, str) or not _MULTISERIES_TRIGGER_RE.search(user_text):
        return None
    return (
        "The user mentioned a .lif / .czi / .nd2 / .lsm file. These bundle many "
        "series — the macro importer with `series_1=true series_2=true ...` "
        "SILENTLY opens only series 1. Use list_lif_series(path) to inspect, then "
        "open_lif_series(path, indices) to open pixels. See "
        "bioformats-multiseries-reference.md."
    )


def _groovy_jython_note(user_text: str) -> str | None:
    """Pre-empt common Groovy/Jython hallucinations."""
    if not isinstance(user_text, str) or not _SCRIPT_LANG_TRIGGER_RE.search(user_text):
        return None
    return (
        "The user asked for Groovy / Jython / JavaScript. These do NOT exist: "
        "IJ.setAutoThreshold, imp.setDirty, imp.isDirty, ij.plugin.filter.BlurFilter "
        "/ MeanFilter / MedianFilter / VarianceFilter / UnsharpMaskFilter. "
        "Delegate UI steps via IJ.runMacro(\"...\"). Set imp.changes = false BEFORE "
        "imp.close() to silence 'Save changes?'. On ImportError / AttributeError / "
        "MissingMethod, pivot to run_macro — do not rename-and-retry."
    )


def _hallucinated_filter_cmds_note(user_text: str) -> str | None:
    """Name the filter commands that don't exist in base Fiji."""
    if not isinstance(user_text, str) or not _HALLUCINATED_FILTER_TRIGGER_RE.search(user_text):
        return None
    return (
        "User asked for multiple filters. These do NOT exist in base Fiji: "
        "\"Laplacian\", \"DoG\", \"Difference of Gaussians\", \"Band-pass Filter\" "
        "(hyphenated). Use Convolve... with a 3x3 kernel for Laplacian; two "
        "Gaussian Blur... + Image Calculator subtract for DoG; "
        "\"Bandpass Filter...\" (no hyphen) for bandpass. probe_plugin() any "
        "filter name you're unsure about BEFORE looping."
    )


def _sample_image_note(user_text: str) -> str | None:
    """Provide exact literal names for built-in sample images."""
    if not isinstance(user_text, str):
        return None
    if not _SAMPLE_IMAGE_NAME_RE.search(user_text):
        return None
    if not _SAMPLE_IMAGE_VERB_RE.search(user_text):
        return None
    return (
        "Sample images load by EXACT literal name. Correct: \"Blobs (25K)\", "
        "\"Cell Colony (31K)\", \"Clown (14K)\", \"Embryos (42K)\", "
        "\"Fluorescent Cells (400K)\", \"HeLa Cells (1.3M)\", \"Leaf (36K)\", "
        "\"M51 Galaxy (177K)\", \"MRI Stack (528K)\", \"T1 Head (2.4M)\", "
        "\"Boats\", \"Bridge (174K)\". Size guesses like \"Blobs (2K)\" fail."
    )


def _recipe_before_loop_note(user_text: str, is_first_turn: bool) -> str | None:
    """Nudge toward recipe_search on the first turn of a multi-iteration request."""
    if not is_first_turn:
        return None
    if not isinstance(user_text, str) or not _RECIPE_LOOP_TRIGGER_RE.search(user_text):
        return None
    return (
        "Before hand-rolling a loop over N filters / thresholds / images, call "
        "recipe_search(\"<task>\"). If a recipe exists, follow it. If not, write "
        "ONE iteration, run it, verify the count is sane, THEN scale to N — "
        "never submit the whole loop untested."
    )


def _3d_render_prep_note(user_text: str) -> str | None:
    """Inject the 3D rendering prep checklist."""
    if not isinstance(user_text, str) or not _3D_RENDER_TRIGGER_RE.search(user_text):
        return None
    return (
        "User asked for a 3D render / rotation. Checklist: (1) convert to 8-bit "
        "— 3D Viewer and 3Dscript require it. (2) For 3Dscript, scale XY FIRST "
        "(output size = input size) and do NOT Z-interpolate (it drops signal "
        "below the alpha threshold). (3) Isolate objects with a Multiply mask — "
        "crop alone lets neighbours bleed in."
    )


def _selectimage_anchor_note(user_text: str) -> str | None:
    """Remind to anchor macros with selectImage when touching the active image."""
    if not isinstance(user_text, str) or not _ACTIVE_IMAGE_VERB_RE.search(user_text):
        return None
    return (
        "User referenced an active-image operation (Duplicate, setThreshold, "
        "Convert to Mask, saveAs, getPixel, Measure, Analyze Particles). If more "
        "than one image is open, the macro MUST start with "
        "selectImage(\"<exact title>\") or it may land on the wrong image. "
        "Check windows({}) to see what's open if unsure."
    )


# ---------------------------------------------------------------------------
# Post-tool-result injection point
#
# After every tool call, each injector in _POST_TOOL_INJECTORS is run against
# the tool name, the args we sent, the stringified result, and a turn-scoped
# state dict. Each injector returns a note string (or None). Notes are
# appended to the message history as {"role": "system", ...} BEFORE the next
# model round runs, giving the agent just-in-time guidance based on what
# Fiji actually said.
#
# To add a new post-tool injector: write a function matching the signature
# below and append it to _POST_TOOL_INJECTORS. Keep notes 25-60 words,
# imperative, same tone as the pre-turn injectors above.
# ---------------------------------------------------------------------------


def _normalise_tool_error(result_text: str) -> str | None:
    """Normalise a tool error string for same-error comparison."""
    if not isinstance(result_text, str) or not result_text.startswith("ERROR:"):
        return None
    first_line = result_text.splitlines()[0] if result_text else ""
    stripped = re.sub(r"\bline\s+\d+\b", "line <N>", first_line, flags=re.IGNORECASE)
    stripped = re.sub(r"\s+", " ", stripped).strip().lower()
    return stripped or None


def _stale_error_loop_injector(
    tool_name: str,
    args: dict,
    result_text: str,
    turn_state: dict,
) -> str | None:
    """Fire when two macro/script calls in a row returned the same error."""
    if tool_name not in {"run_macro", "run_script", "run_macro_async", "job_status"}:
        return None
    current = _normalise_tool_error(result_text)
    if current is None:
        turn_state["last_tool_error"] = None
        return None
    previous = turn_state.get("last_tool_error")
    turn_state["last_tool_error"] = current
    if previous is None or previous != current:
        return None
    return (
        "Two tool calls in a row returned the same error. STOP submitting "
        "variants. Next calls: close_dialogs({}), then get_open_windows({}), "
        "then get_log({}). If the error's line number doesn't match your last "
        "macro, a previous dialog is still blocking the queue — the error is "
        "stale."
    )


def _hallucination_reflector_injector(
    tool_name: str,
    args: dict,
    result_text: str,
    turn_state: dict,
) -> str | None:
    """Fire when the tool result reports an Unrecognized Fiji command."""
    if not isinstance(result_text, str):
        return None
    match = re.search(r'Unrecognized command:\s*"([^"]+)"', result_text)
    if match is None:
        return None
    hallucinated = match.group(1)
    return (
        "Last error was: Unrecognized command: \"{name}\". This command is not "
        "in Fiji. Do NOT retry a tweaked spelling. "
        "probe_plugin({{\"name\": \"{name}\"}}) to confirm, or grep "
        ".tmp/commands.raw.txt. If it genuinely doesn't exist, rebuild from "
        "primitives (DoG = two Gaussians + Image Calculator; Laplacian = "
        "Convolve with kernel)."
    ).format(name=hallucinated)


def _no_image_reflex_injector(
    tool_name: str,
    args: dict,
    result_text: str,
    turn_state: dict,
) -> str | None:
    """Fire when a macro returned a No Image / No window-with-title error."""
    if not isinstance(result_text, str):
        return None
    if not re.search(r"(?i)no\s+image|no\s+window\s+with\s+the\s+title", result_text):
        return None
    return (
        "A \"No Image\" / \"No window with the title\" error fired. The image "
        "the macro expected is gone or never existed. Your next call MUST be "
        "get_open_windows({}) — not another run_macro. Then "
        "selectImage(\"<exact title from that list>\") before retrying."
    )


# Future post-tool injectors — keep the stub and the sketch so they slot in
# cleanly when enabled. See docs/ollama/future-injectors.md.
def _nresults_zero_injector(
    tool_name: str,
    args: dict,
    result_text: str,
    turn_state: dict,
) -> str | None:
    """FUTURE: detect Analyze Particles counts returning zero due to summarize trap."""
    return None


_POST_TOOL_INJECTORS: tuple = (
    _stale_error_loop_injector,
    _hallucination_reflector_injector,
    _no_image_reflex_injector,
    # _nresults_zero_injector,  # disabled — see docs/ollama/future-injectors.md
)


def _post_tool_system_notes(
    tool_name: str,
    args: dict,
    result_text: str,
    turn_state: dict,
) -> list[str]:
    """Run every active post-tool injector; return the list of notes to inject."""
    notes: list[str] = []
    for injector in _POST_TOOL_INJECTORS:
        try:
            note = injector(tool_name, args or {}, result_text, turn_state)
        except Exception:
            note = None
        if isinstance(note, str) and note.strip():
            notes.append(note.strip())
    return notes


# ---------------------------------------------------------------------------
# Pre-dispatch injection point
#
# Called BEFORE a tool call is sent to Fiji. If it returns a non-None string,
# the tool call is ABORTED, the string is appended as a system message, and
# the model gets another round to replan. Use sparingly — only for guards
# that would corrupt data (e.g. bitdepth AND on 16-bit images).
#
# No active guards yet; the hook exists so the bitdepth guardian and any
# future pre-dispatch checks can slot in without touching _one_turn.
# See docs/ollama/future-injectors.md.
# ---------------------------------------------------------------------------


def _pre_dispatch_abort_note(tool_name: str, args: dict) -> str | None:
    """Return a note that aborts this tool call, or None to proceed."""
    return None


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
    turn_config: dict,
    think_capability_state: dict,
    abort_event: threading.Event,
    result_queue: "queue.Queue[dict]",
) -> None:
    """Run one turn in a worker thread and push the result back to the main loop."""
    try:
        reply, had_failure = _one_turn(
            model,
            messages,
            tools,
            tool_map,
            turn_config,
            think_capability_state=think_capability_state,
            abort_event=abort_event,
        )
    except Exception as exc:
        result_queue.put(
            {
                "status": "error",
                "error": "ERROR: {}: {}".format(type(exc).__name__, exc),
                "had_failure": True,
            }
        )
        return
    result_queue.put({"status": "ok", "reply": reply, "had_failure": had_failure})


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
    initial_mode_lock: str | None = None,
    initial_think_lock: bool | None = None,
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
        initial_mode_lock: Optional starting lock for the sampling mode.
        initial_think_lock: Optional starting lock for native thinking mode.
    """
    del no_friction_log  # no-op until friction.py lands in a later phase

    tools = list(REGISTRY)
    tool_map = _rebuild_tool_map()
    slash_completer = _build_slash_completer()
    ctx_state = {"used": 0, "limit": NUM_CTX}
    mode_state = {"lock": initial_mode_lock if initial_mode_lock in SAMPLING_PROFILES else None}
    think_state = {"lock": initial_think_lock if isinstance(initial_think_lock, bool) else None}
    think_capability_state = {"supported": True, "logged": False}

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
    last_turn_had_failure = False
    previous_auto_mode: str | None = None
    previous_banner_text = ""

    try:
        while True:
            _drain_fiji_events(active_turn, pending_system_notes, messages)

            if active_turn is None and pending_prompts:
                while pending_system_notes:
                    messages.append({"role": "system", "content": pending_system_notes.popleft()})
                prompt_text = pending_prompts.popleft()
                turn_config = _resolve_turn_config(
                    prompt_text,
                    mode_state.get("lock"),
                    think_state.get("lock"),
                    last_turn_had_failure,
                )
                _log_turn_config(turn_config["mode"], turn_config["thinking"], turn_config["source"])
                is_first_user_turn = not any(
                    isinstance(m, dict) and m.get("role") == "user"
                    for m in messages
                )
                pre_turn_notes = [
                    _phase6_system_note(prompt_text),
                    _filter_vocab_system_note(prompt_text),
                    _multiseries_note(prompt_text),
                    _groovy_jython_note(prompt_text),
                    _hallucinated_filter_cmds_note(prompt_text),
                    _sample_image_note(prompt_text),
                    _recipe_before_loop_note(prompt_text, is_first_user_turn),
                    _3d_render_prep_note(prompt_text),
                    _selectimage_anchor_note(prompt_text),
                    _phase9_system_note(prompt_text),
                ]
                for note in pre_turn_notes:
                    if isinstance(note, str) and note.strip():
                        messages.append({"role": "system", "content": note.strip()})
                banner_text = _turn_banner_text(
                    turn_config,
                    mode_state,
                    think_state,
                    previous_auto_mode,
                )
                if banner_text and banner_text != previous_banner_text:
                    messages.append({"role": "system", "content": banner_text})
                messages.append({"role": "user", "content": prompt_text})
                abort_event = threading.Event()
                result_queue: "queue.Queue[dict]" = queue.Queue(maxsize=1)
                thread = threading.Thread(
                    target=_turn_worker,
                    args=(
                        model,
                        messages,
                        tools,
                        tool_map,
                        turn_config,
                        think_capability_state,
                        abort_event,
                        result_queue,
                    ),
                    daemon=True,
                )
                active_turn = {
                    "thread": thread,
                    "abort_event": abort_event,
                    "result_queue": result_queue,
                    "prompt": prompt_text,
                    "turn_config": turn_config,
                }
                previous_auto_mode = turn_config["auto_mode"]
                previous_banner_text = banner_text
                thread.start()

            if active_turn is not None and not active_turn["thread"].is_alive():
                result = active_turn["result_queue"].get_nowait()
                if result["status"] == "ok":
                    _console_emit("\033[34mgemma>\033[0m {}\n".format(result["reply"]), reserve_status_line=True)
                else:
                    _console_emit("{}\n".format(result["error"]), reserve_status_line=True)
                last_turn_had_failure = bool(result.get("had_failure", False))
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
                last_turn_had_failure = False
                previous_auto_mode = None
                previous_banner_text = ""
                _console_emit("(conversation cleared)", reserve_status_line=True)
                continue
            if command == "/think":
                _console_emit(
                    _handle_think_command(
                        command_arg,
                        mode_state,
                        think_state,
                        last_turn_had_failure,
                    ),
                    reserve_status_line=True,
                )
                continue
            if command == "/mode":
                _console_emit(
                    _handle_mode_command(command_arg, mode_state),
                    reserve_status_line=True,
                )
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
