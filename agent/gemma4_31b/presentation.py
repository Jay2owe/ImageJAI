"""Shared Gemma terminal visuals, independent of the model and event loop."""
from __future__ import annotations

import json


_THINKING_STATUS = "Thinking"

_INSPECTING_STATUS = "Inspecting image"

_WRITING_STATUS = "Writing macro/script"

_RUNNING_FIJI_STATUS = "Running in Fiji"

_STATUS_ANIMATION_FRAME_S = 0.5

_STATUS_TEXT_FRAME_S = 0.16

_STATUS_PULSE_REST_FRAMES = 4

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
