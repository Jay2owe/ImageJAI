"""Background subscriber for the Fiji TCP event bus.

A daemon thread opens a long-lived socket to the server,
subscribes to a list of event patterns, and streams newline-
framed JSON frames. On image.opened / image.updated / image.closed
events the cached active-image path in active_image.py is
refreshed or invalidated. All other events are dropped into a
module-level queue so future phases (e.g. tools_events.py) can
expose them to the agent.

Mirrors the reconnect-on-drop pattern from imagej_events in
agent/ij.py without importing it, so this agent remains
standalone.
"""

from __future__ import annotations

import json
import queue
import socket
import threading
import time

from . import active_image
from .registry import HOST, PORT


EVENT_QUEUE: "queue.Queue" = queue.Queue()

_started = False
_started_lock = threading.Lock()

RECONNECT_DELAY_S = 1.0
RECV_CHUNK = 8192

# Short-lived registry of image titles the agent's own macros created.
# Populated from run_macro's newImages field; read by loop._format_triage_note
# so auto-triage skips the binary masks Gemma produced itself (Duplicate +
# Convert to Mask), which otherwise yield nonsense warnings like
# "33% saturated / 67% clipped blacks" that pollute the chat context.
_AGENT_CREATED_IMAGES: dict[str, float] = {}
_AGENT_IMAGE_TTL_S = 5.0
_MASK_TITLE_KEYWORDS = (
    "mask", "temp", "otsu", "li", "triangle", "huang", "minimum",
    "default", "gauss", "med", "binary", "duplicate",
)


def mark_image_created_by_agent(title: str) -> None:
    """Record that an agent macro just created an image with this title."""
    if isinstance(title, str) and title.strip():
        _AGENT_CREATED_IMAGES[title.strip()] = time.time()


def is_likely_agent_created_mask(title: str) -> bool:
    """True when the title was just created by a macro or matches mask-like naming."""
    if not isinstance(title, str):
        return False
    now = time.time()
    expired = [k for k, t in _AGENT_CREATED_IMAGES.items() if now - t > _AGENT_IMAGE_TTL_S]
    for k in expired:
        _AGENT_CREATED_IMAGES.pop(k, None)
    if title.strip() in _AGENT_CREATED_IMAGES:
        return True
    lower = title.lower()
    return any(kw in lower for kw in _MASK_TITLE_KEYWORDS)


def start_subscriber(topics: list[str]) -> None:
    """Start the background subscriber thread if not already running.

    Safe to call more than once — repeated calls are no-ops. The
    thread is a daemon so it disappears automatically when the
    chat loop exits.
    """
    global _started
    with _started_lock:
        if _started:
            return
        _started = True
    thread = threading.Thread(
        target=_run,
        args=(list(topics) if topics else ["*"],),
        name="gemma4_31b-event-subscriber",
        daemon=True,
    )
    thread.start()


def _run(topics: list[str]) -> None:
    """Subscriber loop: connect, stream frames, reconnect on drop.

    Any error is swallowed silently so a bad socket can never take
    down the chat REPL. On disconnect we wait RECONNECT_DELAY_S
    seconds and try again, matching the helper in agent/ij.py.
    """
    request = (json.dumps({"command": "subscribe", "topics": topics}) + "\n").encode("utf-8")
    while True:
        try:
            _stream_once(request)
        except Exception:
            pass
        try:
            time.sleep(RECONNECT_DELAY_S)
        except Exception:
            return


def _stream_once(request: bytes) -> None:
    """Open one subscription socket and drain it until it closes.

    Reads newline-delimited JSON frames, decodes each, dispatches
    to _handle_frame. Returns when the socket closes or errors;
    the outer loop decides whether to reconnect.
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(None)
    try:
        s.connect((HOST, PORT))
        s.sendall(request)
        buf = b""
        while True:
            chunk = s.recv(RECV_CHUNK)
            if not chunk:
                return
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    frame = json.loads(line.decode("utf-8"))
                except ValueError:
                    continue
                try:
                    _handle_frame(frame)
                except Exception:
                    pass
    finally:
        try:
            s.close()
        except Exception:
            pass


def _handle_frame(frame: dict) -> None:
    """Dispatch one event frame.

    Image lifecycle events update or invalidate the cached
    active-image path. All frames go into EVENT_QUEUE for later
    phases. Malformed or non-dict frames are silently dropped.
    """
    if not isinstance(frame, dict):
        return
    name = frame.get("event")
    if name in ("image.activated", "image.opened", "image.updated"):
        _update_active_path(frame)
    elif name == "image.closed":
        active_image._mark_active_image_unknown()
    try:
        EVENT_QUEUE.put_nowait(frame)
    except queue.Full:
        pass


def _update_active_path(frame: dict) -> None:
    """Refresh the cached active-image path from an event payload.

    The server may put the path directly in frame, in frame["data"]
    or omit it entirely. We check the common spots; when the
    payload only carries a title, we discard the stale cache and
    ask active_image to resolve the current path via its TCP
    fallback on next read. Nothing raised escapes the subscriber
    thread.
    """
    path = frame.get("path")
    if not (isinstance(path, str) and path):
        data = frame.get("data")
        if isinstance(data, dict):
            for key in ("path", "filePath", "file_path"):
                value = data.get(key)
                if isinstance(value, str) and value:
                    path = value
                    break
    if isinstance(path, str) and path:
        active_image._set_active_image_path(path)
    else:
        active_image._mark_active_image_unknown()
