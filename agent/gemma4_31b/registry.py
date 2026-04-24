"""Tool registry for the Gemma 4 31B agent.

Each @tool function has its signature and docstring read by
ollama.chat() to build the tool schema, so the docstring IS the
contract. The decorator guards against empty docstrings because a
tool with no docstring registers with a useless schema and Gemma
will ignore it or call it with garbage arguments.

Also hosts the tiny JSON-over-TCP helper that every tools_*.py
module uses to talk to the Fiji server. Host and port follow the
same IMAGEJAI_TCP_HOST / IMAGEJAI_TCP_PORT environment variables as
agent/ij.py, so the standalone agent works when the plugin uses a
non-default TCP port.
"""

import json
import os
import socket

HOST = os.environ.get("IMAGEJAI_TCP_HOST", "localhost")
try:
    PORT = int(os.environ.get("IMAGEJAI_TCP_PORT", "7746"))
except ValueError:
    PORT = 7746
TIMEOUT_S = 60.0

# Step 01 (docs/tcp_upgrade/01_hello_handshake.md): Gemma's handshake caps.
# Gemma is text-only (vision=False), has a tight context budget, and has no
# external hook injecting session state, so pulse=True — later steps will rely
# on the server-side one-line pulse in lieu of hook feeds.
# Step 05 (docs/tcp_upgrade/05_state_delta_and_pulse.md): state_delta=True
# groups diff keys (newImages / resultsTable / logDelta / dismissedDialogs)
# under a single "stateDelta" sub-object — tighter replies for the 4k budget.
GEMMA_CAPS = {
    "vision": False,
    "output_format": "json",
    "token_budget": 4000,
    "verbose": False,
    "pulse": True,
    "state_delta": True,
    "accept_events": ["macro.*", "image.*", "dialog.*"],
}

REGISTRY: list = []


def tool(func):
    """Register a function as a tool exposed to Ollama.

    Raises ValueError at import time if the function has no
    docstring — ollama.chat() reads the docstring to build the tool
    schema, so an empty one produces a silent bug.
    """
    if not (func.__doc__ and func.__doc__.strip()):
        raise ValueError(
            "tool '{}' has no docstring — ollama.chat() uses the "
            "docstring as the tool's schema, so an empty one is a "
            "bug.".format(func.__name__)
        )
    REGISTRY.append(func)
    return func


def all_tools() -> list:
    """Return the list of registered tool callables."""
    return list(REGISTRY)


TOOL_MAP: dict = {}


def _rebuild_tool_map() -> dict:
    TOOL_MAP.clear()
    for fn in REGISTRY:
        TOOL_MAP[fn.__name__] = fn
    return TOOL_MAP


def send(command: str, **payload) -> dict:
    """Send one JSON command to the Fiji TCP server and return the parsed reply.

    Wire format matches agent/ij.py: a single JSON object terminated
    by a newline, with {"command": ..., <args>} keys at the top
    level (flat, not nested under "args"). The server replies with a
    single newline-terminated JSON object shaped like
    {"ok": true, "result": {...}} on success or
    {"ok": false, "error": "..."} on failure.
    """
    request = {"command": command}
    request.update(payload)
    data = (json.dumps(request) + "\n").encode("utf-8")

    timed_out = False
    with socket.create_connection((HOST, PORT), timeout=TIMEOUT_S) as s:
        s.settimeout(TIMEOUT_S)
        s.sendall(data)
        chunks = []
        while True:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                timed_out = True
                break
            if not chunk:
                break
            chunks.append(chunk)
            if chunk.endswith(b"\n"):
                break

    raw = b"".join(chunks).decode("utf-8", errors="replace").strip()
    if not raw:
        if timed_out:
            return {
                "ok": False,
                "timeout": True,
                "error": (
                    "timed out waiting for reply from Fiji TCP server "
                    "(the macro may be blocked by a dialog or hung inside Fiji)"
                ),
            }
        return {"ok": False, "error": "empty reply from Fiji TCP server"}
    try:
        return json.loads(raw)
    except ValueError as exc:
        return {"ok": False, "error": "invalid JSON reply: {}".format(exc), "raw": raw[:500]}


def hello() -> dict:
    """Send the step 01 handshake on a fresh socket and return the server
    response. Records Gemma's capabilities (see GEMMA_CAPS) so later-step
    handlers on the server side can shape their replies for a small-context,
    vision-free model. On any failure returns the error dict and the caller
    can fall through to the legacy no-handshake path."""
    return send("hello", agent="gemma-31b", capabilities=GEMMA_CAPS)
