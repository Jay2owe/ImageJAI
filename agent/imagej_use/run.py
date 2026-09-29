"""Command-line entry point for persistent ImageJAI stdin programs."""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence, TextIO

from ij import HOST, PORT, ImageJSession

from .helpers import (
    ScreenshotError,
    WorkspaceError,
    load_workspace_helpers,
    make_core_namespace,
    screenshot_to_path,
    workspace_from_env,
)


SUPPORTED_PROTOCOL_MAJOR = 1
AUTH_ERROR_CODES = frozenset(
    ("auth_required", "invalid_token", "session_required",
     "session_token_mismatch", "session_unknown", "session_revoked",
     "session_expired"))


def _error_code(response: Any) -> Optional[str]:
    if not isinstance(response, dict):
        return None
    error = response.get("error")
    return error.get("code") if isinstance(error, dict) else None


def _error_message(response: Any) -> str:
    if not isinstance(response, dict):
        return repr(response)
    error = response.get("error")
    if isinstance(error, dict):
        return str(error.get("message") or error.get("code") or error)
    return str(error or "unknown error")


def _protocol_major(hello: Mapping[str, Any]) -> Optional[int]:
    result = hello.get("result")
    version = result.get("server_version") if isinstance(result, dict) else None
    if not isinstance(version, str):
        return None
    try:
        return int(version.split(".", 1)[0])
    except ValueError:
        return None


def _check(name: str, status: str, code: str, message: str, **extra: Any) -> Dict[str, Any]:
    item: Dict[str, Any] = {
        "name": name,
        "status": status,
        "code": code,
        "message": message,
    }
    item.update(extra)
    return item


def doctor(
    session: Any,
    environ: Optional[Mapping[str, str]] = None,
) -> Dict[str, Any]:
    """Run categorized transport/auth/protocol/workspace/screenshot checks."""
    checks: Dict[str, Dict[str, Any]] = {}
    workspace: Optional[Path] = None
    workspace_problem: Optional[str] = None
    try:
        workspace = workspace_from_env(environ)
        if workspace is None:
            raise WorkspaceError("IMAGEJAI_AGENT_WORKSPACE is not set")
        checks["workspace"] = _check(
            "workspace", "pass", "workspace_ok", "Explicit workspace is usable.",
            path=str(workspace))
    except WorkspaceError as exc:
        workspace_problem = str(exc)
        checks["workspace"] = _check(
            "workspace", "fail", "workspace_invalid", workspace_problem)

    try:
        hello = session.hello()
    except Exception as exc:
        hello = {
            "ok": False,
            "error": {"code": "transport_unreachable", "message": str(exc)},
        }
    code = _error_code(hello)
    reachable = code != "transport_unreachable"
    if reachable:
        checks["fiji_reachability"] = _check(
            "fiji_reachability", "pass", "fiji_reachable",
            "Fiji ImageJAI TCP server replied.")
    else:
        checks["fiji_reachability"] = _check(
            "fiji_reachability", "fail", "fiji_unreachable",
            _error_message(hello))

    result = hello.get("result") if isinstance(hello, dict) else None
    compatibility = bool(result.get("compatibility")) if isinstance(result, dict) else False
    if not reachable:
        checks["authentication"] = _check(
            "authentication", "skipped", "fiji_unreachable",
            "Authentication was not checked because Fiji is unreachable.")
    elif code in AUTH_ERROR_CODES or compatibility:
        checks["authentication"] = _check(
            "authentication", "fail", "authentication_failed",
            "Authenticated ImageJAI session was not established.")
    elif not (isinstance(hello, dict) and hello.get("ok")):
        checks["authentication"] = _check(
            "authentication", "skipped", "protocol_invalid",
            "Authentication could not be assessed from the server reply.")
    else:
        checks["authentication"] = _check(
            "authentication", "pass", "authentication_ok",
            "Authenticated ImageJAI session established.",
            session_id=getattr(session, "session_id", None))

    major = _protocol_major(hello) if isinstance(hello, dict) else None
    if not reachable:
        checks["protocol"] = _check(
            "protocol", "skipped", "fiji_unreachable",
            "Protocol was not checked because Fiji is unreachable.")
    elif code in AUTH_ERROR_CODES:
        checks["protocol"] = _check(
            "protocol", "skipped", "authentication_failed",
            "Protocol was not checked because authentication failed.")
    elif major != SUPPORTED_PROTOCOL_MAJOR:
        checks["protocol"] = _check(
            "protocol", "fail", "protocol_mismatch",
            "Expected ImageJAI protocol major {}, got {!r}.".format(
                SUPPORTED_PROTOCOL_MAJOR, major))
    else:
        checks["protocol"] = _check(
            "protocol", "pass", "protocol_ok",
            "ImageJAI protocol major {} is supported.".format(major),
            server_version=result.get("server_version"))

    prerequisite_ok = (
        checks["fiji_reachability"]["status"] == "pass"
        and checks["authentication"]["status"] == "pass"
        and checks["protocol"]["status"] == "pass"
        and workspace_problem is None
    )
    if not prerequisite_ok:
        checks["screenshot"] = _check(
            "screenshot", "skipped", "prerequisite_failed",
            "Screenshot was not attempted because another doctor check failed.")
    else:
        screenshot = workspace / ".imagej-use-auto" / "doctor.png"
        try:
            saved = screenshot_to_path(
                session, screenshot, max_size=256, workspace=workspace)
            if not Path(saved).is_file():
                raise ScreenshotError("screenshot file was not created")
            checks["screenshot"] = _check(
                "screenshot", "pass", "screenshot_ok",
                "Screenshot capture and safe workspace write succeeded.")
        except (ScreenshotError, WorkspaceError, OSError) as exc:
            checks["screenshot"] = _check(
                "screenshot", "fail", "screenshot_failed", str(exc))
        finally:
            try:
                screenshot.unlink()
            except (FileNotFoundError, OSError, UnboundLocalError):
                pass

    ordered_names = (
        "fiji_reachability", "authentication", "protocol", "workspace", "screenshot")
    ordered = [checks[name] for name in ordered_names]
    return {
        "ok": all(item["status"] == "pass" for item in ordered),
        "checks": ordered,
    }


def _new_session() -> ImageJSession:
    return ImageJSession(
        host=HOST,
        port=PORT,
        agent="imagej-use-auto",
        capabilities={
            "vision": True,
            "output_format": "json",
            "token_budget": 20000,
            "state_delta": True,
            "safe_mode": os.environ.get("IMAGEJAI_SAFE_MODE", "1") != "0",
            "accept_events": ["*"],
        },
    )


def _execute(
    source: str,
    session: Any,
    environ: Optional[Mapping[str, str]],
    stdout: TextIO,
) -> None:
    hello = session.hello()
    if not isinstance(hello, dict) or not hello.get("ok"):
        raise RuntimeError("ImageJAI hello failed: {}".format(_error_message(hello)))
    result = hello.get("result")
    if isinstance(result, dict) and result.get("compatibility"):
        raise RuntimeError("ImageJAI authentication is required")
    major = _protocol_major(hello)
    if major != SUPPORTED_PROTOCOL_MAJOR:
        raise RuntimeError(
            "Unsupported ImageJAI protocol major: {!r}".format(major))

    workspace = workspace_from_env(environ)
    namespace = make_core_namespace(session, workspace=workspace)
    namespace.update(load_workspace_helpers(workspace, namespace))
    namespace["__name__"] = "__imagej_use_auto__"
    compiled = compile(source, "<imagej-use-auto>", "exec")
    with contextlib.redirect_stdout(stdout):
        exec(compiled, namespace, namespace)


def main(
    argv: Optional[Sequence[str]] = None,
    stdin: Optional[TextIO] = None,
    stdout: Optional[TextIO] = None,
    stderr: Optional[TextIO] = None,
    session: Any = None,
) -> int:
    parser = argparse.ArgumentParser(
        prog="imagej-use-auto",
        description="Run one stdin Python program against one ImageJAI session.")
    parser.add_argument(
        "--doctor", action="store_true",
        help="check Fiji, authentication, protocol, workspace, and screenshots")
    args = parser.parse_args(argv)
    stdin = sys.stdin if stdin is None else stdin
    stdout = sys.stdout if stdout is None else stdout
    stderr = sys.stderr if stderr is None else stderr
    session = _new_session() if session is None else session

    if args.doctor:
        report = doctor(session)
        print(json.dumps(report, indent=2, sort_keys=True), file=stdout)
        return 0 if report["ok"] else 1

    try:
        source = stdin.read()
        if not source.strip():
            raise RuntimeError("no Python program was supplied on stdin")
        _execute(source, session, os.environ, stdout)
        return 0
    except Exception:
        traceback.print_exc(file=stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
