"""Plugin probing — open a plugin's dialog, read its real parameters,
then cancel without running. Used to avoid inventing plugin argument
names Gemma would otherwise make up.
"""

from .registry import send, tool


@tool
def probe_plugin(name: str) -> dict:
    """Open a plugin's dialog, read its parameter schema, and cancel without running.

    Args:
        name: Plugin command name exactly as it appears in the Fiji menu,
            e.g. "Gaussian Blur...", "Analyze Particles...".
    """
    return send("probe_command", plugin=name)
