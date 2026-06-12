"""Active-image path cache for the Gemma 4 31B agent.

The agent writes files, so it needs to know which folder to write
them into. The answer is "AI_Exports/ next to the image the user
is currently looking at". The subscriber in events.py keeps the
cached path updated in the background; safety.py reads the derived
export folder before every macro.

All reads and writes of the module-level path go through a lock
because the subscriber runs on a daemon thread while the safety
checks run on the main chat-loop thread.
"""

from __future__ import annotations

import os
import threading

from . import registry


_lock = threading.Lock()
_path: str | None = None
_export_dir_override: str | None = None
_fallback_tried = False


def _set_active_image_path(path: str | None) -> None:
    """Cache the file path of the currently active Fiji image.

    Called by events.py when an event carries a concrete on-disk
    path for the active image. Pass None to clear the cache.
    """
    global _path, _fallback_tried
    with _lock:
        _path = path if (isinstance(path, str) and path) else None
        _fallback_tried = _path is not None


def _mark_active_image_unknown() -> None:
    """Forget the cached path and force a synchronous re-resolve on next read."""
    global _path, _fallback_tried
    with _lock:
        _path = None
        _fallback_tried = False


def set_export_dir_override(path: str | None) -> None:
    """Register a fallback AI_Exports folder for images with no file path.

    Called once at startup from __main__.py when the user passes
    --export-dir. The override is used whenever the active image
    has no on-disk path (e.g. Blobs sample, File > New).
    """
    global _export_dir_override
    with _lock:
        if isinstance(path, str) and path:
            _export_dir_override = os.path.abspath(path)
        else:
            _export_dir_override = None


def current_active_image() -> str | None:
    """Return the cached absolute path of the currently active image.

    Returns None if the active image has no on-disk path, or if no
    image is active yet. The first call falls back to a synchronous
    TCP query so startup is not blocked on the first event arriving.
    """
    global _fallback_tried, _path
    with _lock:
        cached = _path
        already_tried = _fallback_tried
    if cached is not None:
        return cached
    if already_tried:
        return None
    resolved = _resolve_path_via_tcp()
    with _lock:
        _fallback_tried = True
        if resolved is not None:
            _path = resolved
    return resolved


def is_any_image_open() -> bool:
    """Return True when Fiji has at least one image window open.

    Independent of the path cache: an image can be open while
    current_active_image() returns None because the image has no
    on-disk path (a sample like 'Blobs', File > New, or an unsaved
    duplicate). Callers use this to tell "nothing is open" apart
    from "something is open but has no folder to write next to".
    Queries get_open_windows over TCP; any failure returns False so
    callers fall back to the no-image message.
    """
    try:
        resp = registry.send("get_open_windows")
    except Exception:
        return False
    if not isinstance(resp, dict) or not resp.get("ok"):
        return False
    result = resp.get("result")
    if not isinstance(result, dict):
        return False
    images = result.get("images")
    return isinstance(images, list) and len(images) > 0


def current_export_folder() -> str | None:
    """Return the absolute AI_Exports/ folder for the current session.

    Creates the folder with os.makedirs(..., exist_ok=True). Returns
    the folder next to the currently active image when one is on
    disk. Falls back to the override set by set_export_dir_override
    when there is no active image or the active image has no path.
    Returns None when neither a path nor an override is available.
    """
    image_path = current_active_image()
    with _lock:
        override = _export_dir_override
    base: str | None
    if image_path is not None:
        base = os.path.dirname(os.path.abspath(image_path))
    elif override is not None:
        base = override
    else:
        return None
    folder = os.path.join(base, "AI_Exports")
    try:
        os.makedirs(folder, exist_ok=True)
    except OSError:
        return None
    return os.path.abspath(folder)


def _resolve_path_via_tcp() -> str | None:
    """Best-effort synchronous lookup of the active image path.

    The plan specifies a get_state fallback. get_state does not
    currently include the file path, so we also try a small Groovy
    snippet via run_script that reads ImagePlus.getOriginalFileInfo.
    Any network or TCP error is swallowed — startup must never
    crash because Fiji is unreachable.
    """
    try:
        resp = registry.send("get_state")
        path = _extract_path_from_state(resp)
        if path:
            return path
    except Exception:
        pass
    try:
        code = (
            "def imp = ij.WindowManager.getCurrentImage();"
            "if (imp == null) return \"\";"
            "def fi = imp.getOriginalFileInfo();"
            "if (fi == null || fi.directory == null || fi.fileName == null) return \"\";"
            "return fi.directory + fi.fileName;"
        )
        resp = registry.send("run_script", code=code, language="groovy")
        if isinstance(resp, dict) and resp.get("ok"):
            result = resp.get("result")
            if isinstance(result, dict):
                out = result.get("output")
                if isinstance(out, str) and out.strip():
                    return os.path.abspath(out.strip())
    except Exception:
        pass
    return None


def _extract_path_from_state(resp: object) -> str | None:
    """Dig a file path out of a get_state response if present.

    The shape is best-effort: get_state may or may not carry a path,
    depending on server version. We look in a few common spots and
    return the first absolute-looking match.
    """
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None
    result = resp.get("result")
    if not isinstance(result, dict):
        return None
    active = result.get("activeImage")
    if isinstance(active, dict):
        for key in ("path", "filePath", "file_path"):
            value = active.get(key)
            if isinstance(value, str) and value:
                return os.path.abspath(value)
    return None
