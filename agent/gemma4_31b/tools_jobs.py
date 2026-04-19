"""Async macro jobs — submit long-running macros and poll until done.

Wraps the execute_macro_async / job_status / job_cancel commands
in TCPCommandServer.dispatchCore.
"""

from __future__ import annotations

from . import lint
from . import safety
from . import visual_diff
from .registry import send, tool

_ASYNC_TRACK: dict[str, dict] = {}


def _error_text(resp: object) -> str:
    """Extract a compact error string from a TCP reply."""
    if not isinstance(resp, dict):
        return str(resp)
    if isinstance(resp.get("error"), str):
        return resp["error"]
    result = resp.get("result")
    if isinstance(result, dict) and isinstance(result.get("error"), str):
        return result["error"]
    return "unknown error"


def _prepend_warning_to_response(resp, warning):
    """Attach a visual-diff warning to result.output when possible."""
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
def run_macro_async(code: str) -> str:
    """Start a long-running macro in the background and return the job ID.

    Args:
        code: ImageJ macro source. Use this instead of run_macro when the
            macro may take more than a few seconds.
    """
    error = safety.check_macro(code)
    if error is not None:
        safety.friction_log({"event": "macro_rejected", "code": code, "error": error})
        safety.note_execution(False)
        return "ERROR: {}".format(error)
    lint_result = lint.lint_macro(code)
    if isinstance(lint_result, str) and not lint_result.startswith("WARNING"):
        safety.friction_log({"event_type": "lint_reject", "code": code, "error": lint_result})
        safety.note_execution(False)
        return "ERROR: {}".format(lint_result)
    before_thumb = None
    if visual_diff.is_destructive(code):
        try:
            candidate = visual_diff.capture_thumbnail()
        except Exception:
            candidate = None
        if isinstance(candidate, dict) and "error" not in candidate:
            before_thumb = candidate
    resp = send("execute_macro_async", code=code)
    if isinstance(resp, dict):
        result = resp.get("result")
        if isinstance(result, dict):
            job_id = result.get("job_id")
            if isinstance(job_id, str) and job_id:
                _ASYNC_TRACK[job_id] = {
                    "code": code,
                    "before_thumb": before_thumb,
                }
                return job_id
        if isinstance(resp.get("error"), str):
            safety.friction_log({
                "event": "macro_failed", "code": code, "error": resp["error"],
            })
            safety.note_execution(False)
            return "ERROR: {}".format(resp["error"])
    safety.note_execution(False)
    safety.friction_log({"event": "macro_failed", "code": code, "error": str(resp)})
    return str(resp)


@tool
def job_status(job_id: str) -> dict:
    """Poll a background macro job.

    Args:
        job_id: The ID returned by run_macro_async.
    """
    resp = send("job_status", job_id=job_id)
    if not isinstance(resp, dict) or not resp.get("ok"):
        return resp
    result = resp.get("result")
    if not isinstance(result, dict):
        return resp

    state = str(result.get("state") or "")
    tracked = _ASYNC_TRACK.get(job_id)
    if state == "completed" and isinstance(tracked, dict):
        safety.audit_log("macro", tracked.get("code", ""), success=True, metadata={"job_id": job_id, "async": True})
        safety.note_execution(True)
        before_thumb = tracked.get("before_thumb")
        if isinstance(before_thumb, dict):
            try:
                after_thumb = visual_diff.capture_thumbnail()
            except Exception:
                after_thumb = None
            if isinstance(after_thumb, dict) and "error" not in after_thumb:
                _job_new = result.get("newImages") if isinstance(result, dict) else None
                new_images = [str(t) for t in _job_new if t is not None] if isinstance(_job_new, list) else []
                report = visual_diff.diff_report(
                    before_thumb, after_thumb, tracked.get("code", ""), new_images=new_images
                )
                resp = dict(resp)
                resp["visual_diff"] = report
                if isinstance(report, dict) and report.get("consistent") is False:
                    reason = report.get("reason", "inconsistency detected")
                    warning = "VISUAL DIFF WARNING: {}".format(reason)
                    resp = _prepend_warning_to_response(resp, warning)
                    safety.friction_log({
                        "event": "visual_diff_inconsistent",
                        "code": tracked.get("code", ""),
                        "job_id": job_id,
                        "reason": reason,
                        "numbers": report.get("numbers", {}),
                    })
        _ASYNC_TRACK.pop(job_id, None)
    elif state in {"failed", "cancelled"} and isinstance(tracked, dict):
        safety.audit_log(
            "macro",
            tracked.get("code", ""),
            success=False,
            metadata={"job_id": job_id, "async": True, "state": state},
        )
        safety.friction_log({
            "event": "macro_failed",
            "job_id": job_id,
            "code": tracked.get("code", ""),
            "error": _error_text(resp),
            "state": state,
        })
        safety.note_execution(False)
        _ASYNC_TRACK.pop(job_id, None)
    return resp


@tool
def cancel_job(job_id: str) -> dict:
    """Cancel a background macro job that is still running.

    Args:
        job_id: The ID returned by run_macro_async.
    """
    resp = send("job_cancel", job_id=job_id)
    if isinstance(resp, dict) and resp.get("ok"):
        result = resp.get("result")
        if isinstance(result, dict) and result.get("cancelled") is True:
            tracked = _ASYNC_TRACK.pop(job_id, None)
            if isinstance(tracked, dict):
                safety.audit_log(
                    "macro",
                    tracked.get("code", ""),
                    success=False,
                    metadata={"job_id": job_id, "async": True, "state": "cancelled"},
                )
                safety.note_execution(False)
    return resp
