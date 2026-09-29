"""Rich renderables using the original Gemma tool icons and activity frames."""
from __future__ import annotations

import re

from rich.text import Text
from rich.style import Style

from ..gemma4_31b import presentation as gemma
from .shell_actions import fiji_actions
from .tool_results import summarize_result


THINKING = gemma._THINKING_STATUS
INSPECTING = gemma._INSPECTING_STATUS
WRITING = gemma._WRITING_STATUS
RUNNING = gemma._RUNNING_FIJI_STATUS

_ALIASES = {
    "execute_macro": "run_macro", "execute_macro_async": "run_macro_async",
    "capture_window": "capture_image", "capture_screen": "capture_image",
    "get_dialogs": "list_dialog_components", "interact_dialog": "click_dialog_button",
    "get_results_table": "get_results", "get_rois": "region_stats",
    "job_cancel": "cancel_job", "shell": "run_shell", "bash": "run_shell",
    "exec_command": "run_shell", "write_stdin": "run_shell",
    "read": "get_log", "read_file": "get_log", "glob": "get_open_windows",
    "grep": "get_log", "web search": "describe_image", "websearch": "describe_image",
    "write": "save_recipe", "edit": "save_recipe", "edit files": "save_recipe",
    "apply_patch": "save_recipe",
}
_SCRIPT_NAMES = gemma._SCRIPT_TOOLS | gemma._RUN_IN_FIJI_TOOLS
_KNOWN = set(gemma._TOOL_ICONS) | set(_ALIASES)
_CALL = re.compile(r"\b(" + "|".join(re.escape(n) for n in sorted(_KNOWN, key=len, reverse=True)) + r")\s*\(")
_COMMAND_FIELD = re.compile(r'''["']command["']\s*:\s*["']([a-z_]+)["']''')
_SEND = re.compile(r'''\b(?:send|imagej_command)\s*\(\s*["']([a-z_]+)["']''')


def _shell_source(name: str, args: dict | None) -> str:
    key = _ALIASES.get(name.rsplit("__", 1)[-1].lower(), name.lower())
    if key != "run_shell" or not args:
        return ""
    command = args.get("command") or args.get("cmd") or args.get("code") or ""
    if not command and isinstance(args.get("argv"), list):
        command = " ".join(str(p) for p in args["argv"])
    return command if isinstance(command, str) else ""


def displayed_tool_name(name: str, args: dict | None = None) -> str:
    actions = fiji_actions(_shell_source(name, args))
    if actions:
        return " + ".join(_ALIASES.get(action.name, action.name) for action in actions)
    key = tool_key(name, args)
    return key if _shell_source(name, args) and key != "run_shell" else name


def tool_key(name: str, args: dict | None = None) -> str:
    """Resolve vendor aliases and explicit Fiji calls inside shell commands."""
    key = name.rsplit("__", 1)[-1].lower()
    key = _ALIASES.get(key, key)
    if key == "run_shell" and args:
        command = _shell_source(name, args)
        actions = fiji_actions(command)
        if actions:
            keys = [_ALIASES.get(action.name, action.name) for action in actions if action.name != "Shell"]
            return next((n for n in keys if n in _SCRIPT_NAMES or n == "open_image"), keys[0])
        if isinstance(command, str):
            names = _CALL.findall(command) + _COMMAND_FIELD.findall(command) + _SEND.findall(command)
            keys = [_ALIASES.get(n, n) for n in names if n in _KNOWN]
            # A command that reads state and then runs a macro is an execution.
            return next((n for n in keys if n in _SCRIPT_NAMES), keys[0] if keys else key)
    return key


def tool_activity(name: str, args: dict | None = None) -> str:
    key = tool_key(name, args)
    if key in _SCRIPT_NAMES or key in {"job_status", "open_image", "run_sequence", "gui_focus"}:
        return RUNNING
    if key in gemma._INSPECT_TOOLS or key in {"region_stats", "line_profile", "get_dialogs", "get_roi_state", "get_console", "get_display_state", "get_state_context", "get_friction_log", "get_friction_patterns"}:
        return INSPECTING if name.lower() not in {"read", "read_file", "glob", "grep", "web search", "websearch"} else "Reading"
    if name.lower() in {"write", "edit", "edit files", "apply_patch"}:
        return "Editing files"
    if key == "run_shell":
        return "Running command"
    return "Using " + name


def status_text(label: str, elapsed: float, tool_count: int) -> Text:
    animation_label = {
        "Writing reply": WRITING, "Preparing tool": WRITING,
        "Preparing command": WRITING,
        "Editing files": WRITING, "Reading": INSPECTING,
        "Running command": RUNNING,
    }.get(label, label)
    animation = gemma._status_animation_frame(animation_label, elapsed)
    ansi = "  " + animation + " " + gemma._status_label_wave(label, elapsed)
    ansi += " \033[90m({}s)\033[0m".format(max(0, int(elapsed)))
    result = Text.from_ansi(ansi)
    result.append(f" · {tool_count} tool call(s) · esc to interrupt", style="dim")
    return result


def tool_start_text(name: str, args: dict) -> Text:
    actions = fiji_actions(_shell_source(name, args))
    if actions:
        result = Text()
        for index, action in enumerate(actions):
            if index:
                result.append("\n")
            result.append_text(_tool_call_text(_ALIASES.get(action.name, action.name), action.args))
        return result
    return _tool_call_text(displayed_tool_name(name, args), args)


def _tool_call_text(name: str, args: dict) -> Text:
    key = tool_key(name, args)
    result = Text("  ")
    result.append_text(Text.from_ansi(gemma._tool_icon_display(key)))
    # Show a known Fiji operation even when a vendor wraps it in a shell call.
    label = key if key != "run_shell" and name.lower() in {"shell", "bash", "exec_command"} else name
    result.append(f" {label}(", style="yellow")
    formatted = gemma._format_tool_args_for_display(args)
    if len(formatted) > 20_480:
        formatted = formatted[:20_480] + "\n… [arguments truncated in this view]"
    result.append(formatted, style="yellow")
    result.append(")", style="yellow")
    return result


def tool_result_text(name: str, ok: bool, summary: str, args: dict | None = None,
                     detail_id: int | None = None, description=None) -> Text:
    # Same indented arrow as Gemma, with failures visibly red.
    description = description or summarize_result(tool_key(name, args), ok, summary)
    style = {"success": "bright_black", "error": "red", "pending": "yellow",
             "warning": "yellow"}[description.kind]
    result = Text("  ✗ " if description.kind == "error" else "  → ", style=style)
    result.append(f"{displayed_tool_name(name, args)}: {description.text}")
    if detail_id is not None:
        result.append("  details", style="underline")
        result.stylize(Style(meta={"@click": f"app.tool_result({detail_id})"}))
    return result
