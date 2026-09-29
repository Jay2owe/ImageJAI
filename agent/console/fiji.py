"""Fiji connection manager for the console.

Wraps the workspace's ``agent.ij`` facade (JSON-over-TCP, port 7746) in a
thread-safe object the TUI can call from workers. All methods block and
belong on worker threads; results are plain dicts (``{"ok": ...}``).

The module-level ``agent.ij`` cache keeps one authenticated session per
process, which is exactly what the console wants: one Fiji, one console.
Address overrides ride on IMAGEJAI_TCP_HOST / IMAGEJAI_TCP_PORT, which
``agent.ij`` reads at import time.
"""
from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse
from urllib.request import url2pathname

from .workspace import ensure_importable


class FijiError(RuntimeError):
    """Fiji unreachable or a command failed at the protocol level."""


class FijiConnection:
    """Blocking facade over ``agent.ij`` with connect/status tracking."""

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self._lock = threading.RLock()
        self._ij: Any = None
        self.connected = False
        self.last_error: str | None = None
        self.expected_root: Path | None = None
        self._verified_root: Path | None = None
        self.on_status: Callable[[bool, str | None], None] | None = None
        # Set by the app to the session PathTokenMap; see resolve_tokens().
        self.token_map: Any = None
        # agent.ij reads these at import time — set them before _module()
        os.environ["IMAGEJAI_TCP_HOST"] = host
        os.environ["IMAGEJAI_TCP_PORT"] = str(port)

    # ------------------------------------------------------------- plumbing
    def _module(self) -> Any:
        """Import agent.ij lazily (workspace may not be importable yet)."""
        with self._lock:
            if self._ij is None:
                ensure_importable()
                try:
                    import agent.ij as _ij  # repo layout: agent/ package
                except ImportError:
                    import ij as _ij  # workspace layout: agent/ dir on sys.path
                self._ij = _ij
            return self._ij

    def _emit(self, connected: bool, error: str | None = None) -> None:
        self.connected = connected
        self.last_error = error
        if self.on_status:
            try:
                self.on_status(connected, error)
            except Exception:
                pass

    def resolve_tokens(self, value: Any) -> Any:
        """Turn console pseudonyms back into real paths before calling Fiji.

        The chat input inserts a token for a file name when the posture
        pseudonymises, because the model must not see the real path. Fiji is
        on this machine and needs the real one, so every console-initiated
        call reverses its own tokens here. The plugin does the same on its
        side for text the model sends directly (PseudonymisationFilter).
        """
        token_map = self.token_map
        if token_map is None:
            return value
        if isinstance(value, str):
            if not value:
                return value
            target = token_map.resolve(value.strip())
            if target is not None:
                return str(target.path)
            # tokens can also sit inside a longer string (a macro line)
            text = value
            for token, path in token_map.mapping().items():
                if token in text:
                    text = text.replace(token, str(path))
            return text
        if isinstance(value, dict):
            return {k: self.resolve_tokens(v) for k, v in value.items()}
        if isinstance(value, (list, tuple)):
            return type(value)(self.resolve_tokens(v) for v in value)
        return value

    def _require_selected(self) -> None:
        """Prevent commands from reaching another Fiji on the same port."""
        if self.expected_root is not None and self._verified_root != self.expected_root:
            actual = self.installation_root()
            if actual != self.expected_root:
                error = f"TCP port {self.port} belongs to a different Fiji installation: {actual}"
                self._emit(False, error)
                raise FijiError(error)
            self._verified_root = actual

    def _call(self, fn_name: str, *args: Any) -> Any:
        """Call an agent.ij facade function; host/port ride on env vars."""
        self._require_selected()
        args = tuple(self.resolve_tokens(a) for a in args)
        ij = self._module()
        fn = getattr(ij, fn_name, None)
        if fn is None:
            raise FijiError(f"agent.ij has no function {fn_name!r}")
        try:
            resp = fn(*args)
        except FijiError:
            raise
        except Exception as exc:  # connection refused, timeouts, protocol
            self._verified_root = None
            self._emit(False, str(exc))
            raise FijiError(str(exc)) from exc
        if isinstance(resp, dict) and resp.get("ok") is False:
            self._verified_root = None
            err = resp.get("error") or resp.get("message") or "command failed"
            self._emit(True, str(err))
            raise FijiError(str(err))
        self._emit(True, None)
        return resp

    # ------------------------------------------------------------ commands
    def ping(self) -> dict:
        return self._call("ping")

    def select_installation(self, root: Path) -> None:
        """Require the next TCP response to come from this Fiji installation."""
        with self._lock:
            self.expected_root = root.resolve()
            self._verified_root = None
            self._emit(False, None)

    def clear_installation(self) -> None:
        """Forget an explicitly selected Fiji when automatic detection is chosen."""
        with self._lock:
            self.expected_root = None
            self._verified_root = None
            self._emit(False, None)

    def installation_root(self) -> Path:
        """Probe the authenticated server without reusing a cached handshake."""
        ij = self._module()
        response = ij.hello(host=self.host, port=self.port, force=True)
        if not isinstance(response, dict) or not response.get("ok"):
            self._emit(False, "Fiji is not accepting an authenticated connection")
            raise FijiError("Fiji is not accepting an authenticated connection")
        result = response.get("result")
        uri = result.get("installation_uri") if isinstance(result, dict) else None
        if not isinstance(uri, str) or not uri.startswith("file:"):
            self._emit(False, "Fiji needs the current ImageJAI plugin for console startup")
            raise FijiError("Fiji needs the current ImageJAI plugin for console startup")
        parsed = urlparse(uri)
        location = f"//{parsed.netloc}{parsed.path}" if parsed.netloc else parsed.path
        root = Path(url2pathname(location)).resolve()
        self._emit(True, None)
        return root

    def get_state(self) -> dict:
        return self._call("get_state")

    def privacy_posture(self) -> str:
        """Read Fiji's authoritative posture from its in-process controller."""
        return self._posture_script(
            "return imagejai.engine.PostureController.getInstance().current().name()")

    def set_privacy_posture(self, posture: str) -> str:
        """Apply an explicit console choice through Fiji's posture controller.

        The controller updates its live response/event policy, any active
        folder record, audit trail and embedded UI together. The name is
        allowlisted before it enters the Groovy script; a model never calls
        this method.
        """
        name = str(posture).strip().upper()
        if name not in {"STANDARD", "PSEUDONYMISED", "ON_PREMISES"}:
            raise ValueError(f"invalid privacy posture: {posture}")
        return self._posture_script(
            "imagejai.engine.PostureController.getInstance().requestPosture("
            f"imagejai.config.PrivacyPosture.{name}, null, "
            "'User changed posture in standalone ImageJAI console'); "
            "return imagejai.engine.PostureController.getInstance().current().name()")

    def _posture_script(self, code: str) -> str:
        response = self.run_script(code, "groovy", 10)
        result = response.get("result") if isinstance(response, dict) else None
        if not isinstance(result, dict) or result.get("success") is not True:
            error = result.get("error") if isinstance(result, dict) else None
            raise FijiError(f"Fiji posture change failed: {error or 'no script result'}")
        name = str(result.get("output") or "").strip().upper()
        if name not in {"STANDARD", "PSEUDONYMISED", "ON_PREMISES"}:
            raise FijiError(f"Fiji returned an unknown privacy posture: {name or 'empty'}")
        return name

    def pseudonymise_paths(self, paths: Any) -> dict:
        """Mint path tokens in Fiji's authoritative session token map.

        This deliberately bypasses :meth:`resolve_tokens`: these are real
        local paths being registered, not pseudonyms coming back from chat.
        """
        self._require_selected()
        ij = self._module()
        fn = getattr(ij, "pseudonymise_paths", None)
        if fn is None:
            raise FijiError("agent.ij has no function 'pseudonymise_paths'")
        try:
            response = fn(paths)
        except Exception as exc:
            self._emit(False, str(exc))
            raise FijiError(str(exc)) from exc
        if isinstance(response, dict) and response.get("ok") is False:
            error = response.get("error") or response.get("message") or "command failed"
            if isinstance(error, dict):
                error = error.get("message") or error.get("code") or str(error)
            self._emit(True, str(error))
            raise FijiError(str(error))
        self._emit(True, None)
        return response

    def open_image(self, path: str) -> dict:
        return self._call("open_image", path)

    def execute_macro(self, code: str) -> dict:
        return self._call("execute_macro", code)

    def capture(self) -> dict:
        return self._call("capture_image")

    def results(self) -> dict:
        return self._call("get_results_table")

    def rois(self) -> dict:
        return self._call("get_roi_state")

    def console_tail(self, n: int = 2000) -> dict:
        return self._call("get_console", n)

    def friction_patterns(self) -> dict:
        return self._call("get_friction_patterns")

    def close_dialogs(self) -> dict:
        return self._call("close_dialogs")

    def image_info(self, **kwargs: Any) -> dict:
        """Full metadata for the active image (rail Z-project, audit, chips)."""
        if kwargs:
            return self.command({"command": "get_image_info", **kwargs})
        return self._call("get_image_info")

    def run_script(self, code: str, language: str = "groovy",
                   timeout: int = 180) -> dict:
        """Run a Groovy/Jython script; the macro language cannot do everything."""
        return self._call("run_script", code, language, timeout)

    def command(self, payload: dict, timeout: float | None = None,
                *, raise_on_error: bool = True) -> dict:
        """Send one raw command dict for tools the facade does not wrap yet."""
        self._require_selected()
        ij = self._module()
        fn = getattr(ij, "imagej_command", None)
        if fn is None:
            raise FijiError("agent.ij has no imagej_command")
        name = payload.get("command") or payload.get("cmd")
        if not name:
            raise FijiError("command payload needs a 'command' key")
        payload = self.resolve_tokens(dict(payload))
        try:
            options = {"host": self.host, "port": self.port}
            if timeout is not None:
                options["timeout"] = timeout
            resp = fn(dict(payload), **options)
        except Exception as exc:
            self._emit(False, str(exc))
            raise FijiError(str(exc)) from exc
        if isinstance(resp, dict) and resp.get("ok") is False:
            err = resp.get("error") or resp.get("message") or "command failed"
            self._emit(True, str(err))
            if raise_on_error:
                raise FijiError(str(err))
            return resp
        self._emit(True, None)
        return resp

    # --------------------------------------------------------------- events
    def safe_mode_status(self) -> bool | None:
        """Read negotiated safety capabilities from the cached authenticated hello."""
        self._require_selected()
        reply = self._module().hello(host=self.host, port=self.port)
        body = reply.get("result") if isinstance(reply, dict) and reply.get("ok") else None
        if not isinstance(body, dict):
            return None
        enabled = body.get("enabled", body.get("capabilities"))
        if isinstance(enabled, list):
            return "safe_mode" in enabled
        if isinstance(enabled, dict) and isinstance(enabled.get("safe_mode"), bool):
            return enabled["safe_mode"]
        return None

    def event_stream(self, topics: list[str] | None = None):
        """Yield raw event dicts forever; reconnecting is handled by agent.ij."""
        self._require_selected()
        ij = self._module()
        stream = ij.imagej_events(topics or ["*"])
        self._emit(True, None)
        return stream


def summarize_state(state: dict) -> dict:
    """Pull the fields the right panel shows out of a get_state reply."""
    result = state.get("result", state) if isinstance(state, dict) else {}
    if not isinstance(result, dict):
        result = {}
    images = result.get("allImages") or result.get("images") or result.get("windows") or []
    active = (result.get("activeImage") or result.get("active_image")
              or result.get("active_window") or {})
    if not isinstance(active, dict):
        active = {}
    return {
        "n_images": len(images) if isinstance(images, list) else 0,
        "active_title": active.get("title") or (images[0].get("title") if isinstance(images, list) and images and isinstance(images[0], dict) else None),
        "active": active,
        "active_path": active.get("path") or active.get("file_path") or active.get("source_path"),
        "active_id": active.get("id") or active.get("image_id"),
        "active_revision": active.get("revision") or active.get("image_revision"),
        "channel": active.get("channel") or active.get("c"),
        "slice": active.get("slice") or active.get("z"),
        "frame": active.get("frame") or active.get("t"),
        "calibration": active.get("calibration") or result.get("calibration") or {},
        "memory": result.get("memory") or result.get("mem") or {},
        "raw": result,
    }
