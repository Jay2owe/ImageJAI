"""Safe preloaded helpers for the ``imagej-use-auto`` stdin runner."""

from __future__ import annotations

import base64
import importlib.util
import os
from pathlib import Path
from types import MappingProxyType
from typing import Any, Dict, Mapping, Optional
from uuid import uuid4


WORKSPACE_ENV = "IMAGEJAI_AGENT_WORKSPACE"
WORKSPACE_HELPERS = "imagej_helpers.py"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024


class ImageJUseError(RuntimeError):
    """Base error for the ImageJ stdin runner."""


class WorkspaceError(ImageJUseError):
    """The explicit agent workspace is absent or unsafe."""


class ScreenshotError(ImageJUseError):
    """A screenshot response or destination is invalid."""


def workspace_from_env(
    environ: Optional[Mapping[str, str]] = None,
) -> Optional[Path]:
    """Resolve the workspace only from its explicit launcher environment.

    The current working directory is deliberately not a fallback: doing so
    would make helper loading and screenshot writes depend on ambient state.
    """
    source = os.environ if environ is None else environ
    raw = (source.get(WORKSPACE_ENV) or "").strip()
    if not raw:
        return None
    candidate = Path(raw).expanduser()
    if not candidate.is_absolute():
        raise WorkspaceError("{} must be an absolute path".format(WORKSPACE_ENV))
    try:
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise WorkspaceError("workspace is unavailable: {}".format(exc))
    if not resolved.is_dir():
        raise WorkspaceError("workspace is not a directory: {}".format(resolved))
    return resolved


def _within(path: Path, root: Path) -> bool:
    try:
        return os.path.commonpath((str(path), str(root))) == str(root)
    except ValueError:
        return False


def _safe_workspace_path(path: Any, workspace: Optional[Path]) -> Path:
    if workspace is None:
        raise WorkspaceError(
            "{} is required for file output".format(WORKSPACE_ENV))
    try:
        root = Path(workspace).resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise WorkspaceError("workspace is unavailable: {}".format(exc))
    requested = Path(os.fspath(path)).expanduser()
    candidate = requested if requested.is_absolute() else root / requested

    # Resolve before creating anything so ``..`` and existing directory
    # symlinks cannot make us create folders outside the workspace.
    try:
        resolved = candidate.resolve(strict=False)
    except (OSError, RuntimeError) as exc:
        raise WorkspaceError("screenshot path is unavailable: {}".format(exc))
    if not _within(resolved, root):
        raise WorkspaceError("path escapes the agent workspace: {}".format(path))
    if resolved.suffix.lower() != ".png":
        raise WorkspaceError("screenshot path must end in .png")
    if candidate.is_symlink():
        raise WorkspaceError("screenshot destination cannot be a symlink")
    try:
        resolved.parent.mkdir(parents=True, exist_ok=True)
        resolved_parent = resolved.parent.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise WorkspaceError("screenshot directory is unavailable: {}".format(exc))
    resolved = resolved_parent / resolved.name
    if not _within(resolved, root):
        raise WorkspaceError("path escapes the agent workspace: {}".format(path))
    return resolved


def screenshot_to_path(
    session: Any,
    path: Any,
    max_size: int = 1024,
    workspace: Optional[Path] = None,
) -> str:
    """Capture a PNG through ``session`` and atomically write it in workspace."""
    destination = _safe_workspace_path(path, workspace)
    response = session.capture_image(max_size=max_size)
    if not isinstance(response, dict) or not response.get("ok"):
        raise ScreenshotError("ImageJAI refused the screenshot: {!r}".format(response))
    result = response.get("result")
    encoded = result.get("base64") if isinstance(result, dict) else None
    if not isinstance(encoded, str) or not encoded:
        raise ScreenshotError("screenshot response did not contain base64 PNG data")
    if len(encoded) > ((MAX_SCREENSHOT_BYTES + 2) // 3) * 4 + 4:
        raise ScreenshotError("screenshot exceeds the 32 MiB safety limit")
    try:
        payload = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as exc:
        raise ScreenshotError("screenshot base64 is invalid: {}".format(exc))
    if len(payload) > MAX_SCREENSHOT_BYTES:
        raise ScreenshotError("screenshot exceeds the 32 MiB safety limit")
    if not payload.startswith(PNG_SIGNATURE):
        raise ScreenshotError("screenshot payload is not a PNG")

    temporary = destination.with_name(
        ".{}.{}.tmp".format(destination.name, uuid4().hex))
    try:
        with temporary.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(str(temporary), str(destination))
    except (OSError, RuntimeError) as exc:
        raise ScreenshotError("could not save screenshot: {}".format(exc))
    finally:
        try:
            temporary.unlink()
        except OSError:
            pass
    try:
        saved_size = destination.stat().st_size
    except OSError as exc:
        raise ScreenshotError("saved screenshot could not be verified: {}".format(exc))
    if saved_size != len(payload):
        raise ScreenshotError("saved screenshot failed size verification")
    return str(destination)


def make_core_namespace(
    session: Any,
    workspace: Optional[Path] = None,
) -> Dict[str, Any]:
    """Return the fixed preloaded runner API bound to one session object."""

    def screenshot(path: Any, max_size: int = 1024) -> str:
        return screenshot_to_path(
            session, path, max_size=max_size, workspace=workspace)

    namespace: Dict[str, Any] = {
        "session": session,
        "ping": session.ping,
        "get_state": session.get_state,
        "get_image_info": session.get_image_info,
        "get_results_table": session.get_results_table,
        "run_macro": session.run_macro,
        "run_script": session.run_script,
        "capture_image": session.capture_image,
        "screenshot": screenshot,
        "screenshot_to_path": screenshot,
        "wait_for_event": session.wait_for_event,
        "wait_for_operation": session.wait_for_operation,
        "get_dialogs": session.get_dialogs,
        "interact_dialog": session.interact_dialog,
    }
    return namespace


def load_workspace_helpers(
    workspace: Optional[Path],
    core_namespace: Mapping[str, Any],
) -> Dict[str, Any]:
    """Load non-shadowing helpers from an explicit workspace, if present."""
    if workspace is None:
        return {}
    try:
        root = Path(workspace).resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise WorkspaceError("workspace is unavailable: {}".format(exc))
    helper_path = root / WORKSPACE_HELPERS
    if not helper_path.exists():
        return {}
    try:
        resolved = helper_path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise WorkspaceError("workspace helper is unavailable: {}".format(exc))
    if not _within(resolved, root) or not resolved.is_file():
        raise WorkspaceError("workspace helper escapes the explicit workspace")

    module_name = "_imagej_workspace_helpers_{}".format(uuid4().hex)
    spec = importlib.util.spec_from_file_location(module_name, str(resolved))
    if spec is None or spec.loader is None:
        raise WorkspaceError("could not load workspace helper module")
    module = importlib.util.module_from_spec(spec)
    module.core = MappingProxyType(dict(core_namespace))
    spec.loader.exec_module(module)

    requested = getattr(module, "__all__", None)
    if requested is None:
        names = [name for name in vars(module) if not name.startswith("_")]
        names.remove("core")
    else:
        if not isinstance(requested, (list, tuple)) or not all(
                isinstance(name, str) for name in requested):
            raise WorkspaceError("imagej_helpers.__all__ must be a list of names")
        names = list(requested)

    reserved = set(core_namespace)
    collisions = sorted(reserved.intersection(names))
    if collisions:
        raise WorkspaceError(
            "workspace helpers cannot shadow core names: {}".format(
                ", ".join(collisions)))
    exports: Dict[str, Any] = {}
    for name in names:
        if name.startswith("_") or not hasattr(module, name):
            raise WorkspaceError("invalid workspace helper export: {}".format(name))
        exports[name] = getattr(module, name)
    return exports
