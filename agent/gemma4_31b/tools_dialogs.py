"""Dialog interaction tools.

Each of these wraps a single action on the server's interact_dialog
command (plus close_dialogs). Split into separate single-purpose
tools because the Gemma 4 cookbook in agent/ollama_agent/CLAUDE.md
says multi-axis parameters cause wrong-slot argument calls.
"""

from .registry import send, tool


@tool
def close_dialogs(pattern: str) -> dict:
    """Dismiss open dialogs, optionally filtered by title.

    Args:
        pattern: Case-insensitive substring of the dialog title to match.
            Pass an empty string to close every open dialog.
    """
    if pattern:
        return send("close_dialogs", pattern=pattern)
    return send("close_dialogs")


@tool
def list_dialog_components() -> dict:
    """List every button, checkbox, text field and dropdown in the topmost open dialog."""
    return send("interact_dialog", action="list_components")


@tool
def click_dialog_button(label: str) -> dict:
    """Click a dialog button by its label.

    Args:
        label: Visible text on the button, e.g. "OK", "Cancel", "Apply".
    """
    return send("interact_dialog", action="click_button", target=label)


@tool
def set_dialog_text(label: str, value: str) -> dict:
    """Type into a dialog text field identified by its nearest label.

    Args:
        label: The label shown next to the text field, e.g. "sigma".
        value: The text to put in the field.
    """
    return send("interact_dialog", action="set_text", target=label, value=value)


@tool
def set_dialog_checkbox(label: str, value: bool) -> dict:
    """Tick or untick a dialog checkbox by its label.

    Args:
        label: The checkbox label, e.g. "Create Background".
        value: True to tick, False to untick.
    """
    return send("interact_dialog", action="set_checkbox", target=label, value=bool(value))


@tool
def set_dialog_dropdown(label: str, value: str) -> dict:
    """Pick an option from a dialog dropdown.

    Args:
        label: The dropdown label, e.g. "Method".
        value: The option text exactly as it appears in the menu, e.g. "Otsu".
    """
    return send("interact_dialog", action="set_dropdown", target=label, value=value)
