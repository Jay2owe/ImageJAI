"""Core Fiji tools — thin wrappers over the TCP commands in
TCPCommandServer.dispatchCore. One function per command; the
docstring is the schema Ollama sees, so keep it short and
declarative.
"""

import base64
import datetime
import os

from . import auto_probe
from . import events
from . import lint
from . import safety
from . import visual_diff
from .registry import send, tool


def _response_failed(resp: object) -> bool:
    """Return True if a send() reply represents a failure (TCP- or macro-level)."""
    if not isinstance(resp, dict):
        return True
    if not resp.get("ok", False):
        return True
    result = resp.get("result")
    if isinstance(result, dict) and result.get("success") is False:
        return True
    return False


def _error_text(resp: object) -> str:
    """Pull a compact error string out of a send() reply for logging."""
    if not isinstance(resp, dict):
        return str(resp)
    if isinstance(resp.get("error"), str):
        return resp["error"]
    result = resp.get("result")
    if isinstance(result, dict) and isinstance(result.get("error"), str):
        return result["error"]
    return "unknown error"


def _handle_dialog_aftermath(resp, code):
    """Auto-dismiss any modal dialog left on screen by a hung macro.

    The Java side already detects blocking modal dialogs in ≤150 ms and
    cancels the macro future, but it does not dismiss the dialog itself —
    so it sits there blocking every subsequent macro until the agent
    manually calls close_dialogs. That's the slowdown loop Gemma gets
    stuck in.

    When the response carries a non-empty `dialogs` array, we fire a
    close_dialogs command ourselves and rewrite the error string so the
    agent sees a single clear corrective signal: "dialog was dismissed;
    probe and pass explicit args next time" — instead of the vague
    "macro paused" message that would leave it unsure whether another
    run_macro will hang again.
    """
    if not isinstance(resp, dict):
        return resp
    result = resp.get("result")
    if not isinstance(result, dict):
        return resp
    # Only fire on the server's blocking-dialog signature. Error dialogs
    # carry diagnostic text the agent needs to see, so leave them attached.
    error_text = result.get("error")
    if not isinstance(error_text, str) or "Macro paused on modal dialog" not in error_text:
        return resp
    # If the server already auto-dismissed (newer builds), honour that and skip.
    if result.get("dialogsAutoDismissed"):
        return resp
    dialogs = result.get("dialogs")
    if not isinstance(dialogs, list) or not dialogs:
        return resp
    try:
        send("close_dialogs")
    except Exception:
        pass
    titles = []
    for dlg in dialogs:
        if isinstance(dlg, dict):
            title = dlg.get("title")
            if isinstance(title, str) and title.strip():
                titles.append(title.strip())
    titles_text = ", ".join(titles) if titles else "a modal dialog"
    safety.friction_log({
        "event": "dialog_auto_dismiss",
        "code": code,
        "dialog_titles": titles,
    })
    prior = result.get("error")
    prior_text = prior if isinstance(prior, str) and prior.strip() else "macro paused on modal dialog"
    new_resp = dict(resp)
    new_result = dict(result)
    new_result["success"] = False
    new_result["error"] = (
        "Macro blocked on modal dialog ({}) which has been auto-dismissed. "
        "Root cause: {}. Call probe_plugin to get the real argument names "
        "for that plugin and pass them explicitly as run(\"Plugin...\", "
        "\"key=value ...\"), or drive the dialog with interact_dialog, "
        "then re-run."
    ).format(titles_text, prior_text)
    new_resp["result"] = new_result
    return new_resp


def _prepend_warning_to_response(resp, warning):
    """Return a copy of resp with `warning` prepended to result.output (or a top-level key).

    Gemma reads the tool reply verbatim, so the warning must appear on a text
    field it already inspects. We edit result.output when present and fall
    back to a top-level "warning" key otherwise.
    """
    if not isinstance(resp, dict):
        return resp
    new = dict(resp)
    result = new.get("result")
    if isinstance(result, dict):
        new_result = dict(result)
        existing = new_result.get("output")
        if isinstance(existing, str) and existing:
            new_result["output"] = warning + "\n" + existing
        else:
            new_result["output"] = warning
        new["result"] = new_result
    else:
        new["warning"] = warning
    return new


@tool
def run_macro(code: str) -> dict:
    """Run a short ImageJ macro and wait for the result.

    Args:
        code: ImageJ macro source, e.g. 'run("Blobs (25K)"); run("Measure");'.
    """
    error = safety.check_macro(code)
    if error is not None:
        safety.note_execution(False)
        return {"ok": False, "error": error}
    lint_result = lint.lint_macro(code)
    if isinstance(lint_result, str) and not lint_result.startswith("WARNING"):
        safety.friction_log({"event_type": "lint_reject", "code": code, "error": lint_result})
        safety.note_execution(False)
        return {"ok": False, "error": lint_result}

    probe_hint = auto_probe.check_macro(code)
    if probe_hint is not None:
        safety.friction_log({"event_type": "auto_probe_reject", "code": code, "error": probe_hint})
        safety.note_execution(False)
        return {"ok": False, "error": probe_hint, "type": "auto_probe_reject"}
    schema_hints = auto_probe.collect_schema_hints(code)

    before_thumb = None
    if visual_diff.is_destructive(code):
        try:
            candidate = visual_diff.capture_thumbnail()
        except Exception:
            candidate = None
        if isinstance(candidate, dict) and "error" not in candidate:
            before_thumb = candidate

    resp = send("execute_macro", code=code)
    resp = _handle_dialog_aftermath(resp, code)
    # Mark any images this macro just created so auto-triage on image.opened
    # events skips them — the agent's intermediate masks should never fire
    # "saturation" or "clipped blacks" warnings at the chat.
    if isinstance(resp, dict):
        _result = resp.get("result")
        if isinstance(_result, dict):
            _new = _result.get("newImages")
            if isinstance(_new, list):
                for _title in _new:
                    if _title is not None:
                        events.mark_image_created_by_agent(str(_title))
    if isinstance(lint_result, str) and lint_result.startswith("WARNING"):
        if isinstance(resp, dict):
            resp = dict(resp)
            resp["lint_warnings"] = lint_result
    if _response_failed(resp):
        safety.audit_log("macro", code, success=False)
        safety.friction_log({"event": "macro_failed", "code": code, "error": _error_text(resp)})
        safety.note_execution(False)
        return resp
    safety.audit_log("macro", code, success=True)
    safety.note_execution(True)
    if schema_hints and isinstance(resp, dict):
        resp = dict(resp)
        resp["plugin_schema"] = schema_hints

    if before_thumb is not None:
        try:
            after_thumb = visual_diff.capture_thumbnail()
        except Exception:
            after_thumb = None
        if isinstance(after_thumb, dict) and "error" not in after_thumb:
            report = visual_diff.diff_report(before_thumb, after_thumb, code)
            if isinstance(resp, dict):
                resp = dict(resp)
                resp["visual_diff"] = report
                if isinstance(report, dict) and report.get("consistent") is False:
                    reason = report.get("reason", "inconsistency detected")
                    warning = "VISUAL DIFF WARNING: {}".format(reason)
                    resp = _prepend_warning_to_response(resp, warning)
                    safety.friction_log({
                        "event": "visual_diff_inconsistent",
                        "code": code,
                        "reason": reason,
                        "numbers": report.get("numbers", {}),
                    })
    return resp


@tool
def run_script(code: str, language: str) -> dict:
    """Run a Groovy or Jython script inside Fiji's Java runtime.

    Args:
        code: Script source code.
        language: Script language. Use "groovy" or "jython".
    """
    resp = send("run_script", code=code, language=language)
    if _response_failed(resp):
        safety.audit_log("script", code, success=False, metadata={"language": language})
        safety.friction_log({
            "event": "script_failed",
            "language": language,
            "code": code,
            "error": _error_text(resp),
        })
        safety.note_execution(False)
    else:
        safety.audit_log("script", code, success=True, metadata={"language": language})
        safety.note_execution(True)
    return resp


@tool
def get_state() -> dict:
    """Return what Fiji has open right now — images, windows, results table, memory."""
    return send("get_state")


@tool
def get_image_info() -> dict:
    """Return details of the currently active image: size, bit depth, calibration."""
    return send("get_image_info")


@tool
def get_log() -> str:
    """Read the ImageJ Log window as a single string."""
    resp = send("get_log")
    if isinstance(resp, dict):
        result = resp.get("result")
        if isinstance(result, dict):
            for key in ("log", "text", "content"):
                value = result.get(key)
                if isinstance(value, str):
                    return value
        if isinstance(result, str):
            return result
    return str(resp)


@tool
def get_results() -> str:
    """Return the Results table as comma-separated text."""
    resp = send("get_results_table")
    if isinstance(resp, dict):
        result = resp.get("result")
        if isinstance(result, dict):
            for key in ("csv", "text", "table"):
                value = result.get(key)
                if isinstance(value, str):
                    return value
        if isinstance(result, str):
            return result
    return str(resp)


@tool
def capture_image(max_size: int) -> str:
    """Capture a screenshot of the active image, save it into AI_Exports, and return the absolute file path.

    Args:
        max_size: Longest thumbnail edge in pixels. Use 1024 for normal checks.
    """
    resp = send("capture_image", maxSize=int(max_size))
    if not isinstance(resp, dict) or not resp.get("ok"):
        return "ERROR: {}".format(_error_text(resp))

    result = resp.get("result")
    if not isinstance(result, dict):
        return "ERROR: capture_image returned no result payload"
    b64 = result.get("base64")
    if not isinstance(b64, str) or not b64:
        return "ERROR: capture_image returned no image bytes"

    import pathlib

    from . import active_image

    folder = active_image.current_export_folder()
    if folder is None:
        tmp = pathlib.Path(__file__).resolve().parent.parent / ".tmp" / "captures"
        try:
            tmp.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            return "ERROR: could not create fallback capture folder: {}".format(exc)
        folder = str(tmp)

    try:
        png = base64.b64decode(b64)
    except (TypeError, ValueError) as exc:
        return "ERROR: capture_image base64 decode failed: {}".format(exc)

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    path = os.path.abspath(os.path.join(folder, "capture_{}.png".format(stamp)))
    try:
        with open(path, "wb") as fh:
            fh.write(png)
    except OSError as exc:
        return "ERROR: failed to write screenshot '{}': {}".format(path, exc)
    return path


@tool
def get_open_windows() -> dict:
    """List every open window by type (images, dialogs, plots, tables)."""
    return send("get_open_windows")


@tool
def get_metadata() -> dict:
    """Return Bio-Formats metadata and pixel-size calibration for the active image."""
    return send("get_metadata")


@tool
def get_histogram() -> dict:
    """Return the intensity distribution for the active image."""
    return send("get_histogram")
