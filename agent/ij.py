#!/usr/bin/env python
"""
ImageJAI TCP command helper.

Usage:
    python ij.py ping
    python ij.py state
    python ij.py info
    python ij.py results
    python ij.py capture [name]
    python ij.py macro "run('Blobs (25K)');"
    # The `run` subcommand takes a single string argument containing a
    # |||-delimited chain. You MUST quote the whole chain so your shell doesn't
    # word-split on spaces or mangle the embedded quotes:
    #   bash/zsh:  python ij.py run 'open("x.tif") ||| run("Measure") ||| capture done'
    #   cmd.exe:   python ij.py run "open(""x.tif"") ||| run(""Measure"") ||| capture done"
    #   powershell: python ij.py run 'open("x.tif") ||| run("Measure") ||| capture done'
    python ij.py run 'open("x.tif") ||| run("Measure") ||| capture done'
    python ij.py explore Otsu Triangle Li
    python ij.py log
    python ij.py histogram
    python ij.py windows
    python ij.py metadata
    python ij.py probe "Gaussian Blur..."
    python ij.py friction              # recent failures
    python ij.py friction patterns     # recurring failure patterns
    python ij.py friction clear        # clear the log
    python ij.py intent "rolling ball 50"                     # Phase 5: resolve + run
    python ij.py teach "rolling ball <N>" 'run("Subtract Background...", "rolling=<N>");' --description "..."
    python ij.py intents               # list mappings, most-used first
    python ij.py forget "rolling ball <N>"   # remove a mapping
    python ij.py subscribe [topic...]  # stream JSONL events (Phase 2)
    python ij.py events [topic...]     # alias for subscribe
    python ij.py async '<macro>'       # Phase 3: submit async job, print job id
    python ij.py job <id>              # Phase 3: print job status JSON
    python ij.py wait <id> [--timeout SEC]   # Phase 3: block until terminal
    python ij.py jobs                  # Phase 3: list active + recent jobs
    python ij.py run_patient '<macro>' [--timeout SEC]
                                       # Phase 3: async + wait with auto-reconnect
    # Phase 7: GUI sentinels — drive the plugin's chat panel from the agent.
    python ij.py toast "Done" [--level info|warn|error]
    python ij.py inline /abs/path/to/image.png
    python ij.py focus "Image Title"
    python ij.py markdown "**bold** *italic* `code` text"
    python ij.py confirm "Proceed?" --options "Yes,No"
    # Phase 8: reactive rules engine — inspect and toggle rules loaded from
    # ~/.imagej-ai/reactive/*.json. See docs/reactive_rules_format.md.
    python ij.py reactive list
    python ij.py reactive enable <name>
    python ij.py reactive disable <name>
    python ij.py reactive reload
    python ij.py reactive stats
    python ij.py capabilities           # send hello, show server-enabled features
    python ij.py raw '{"command": "ping"}'

Can also be imported:
    from ij import imagej_command
    result = imagej_command({"command": "ping"})
    for event in imagej_events(["dialog.*", "macro.completed"]): ...
"""

import socket
import json
import sys
import os
import base64

HOST = os.environ.get("IMAGEJAI_TCP_HOST", "localhost")
try:
    PORT = int(os.environ.get("IMAGEJAI_TCP_PORT", "7746"))
except ValueError:
    PORT = 7746
TIMEOUT = 60

# Step 01 (docs/tcp_upgrade): capabilities Claude's ij.py declares on first
# contact. Claude Code hooks already inject per-turn session state, so pulse
# is disabled here — a server-side pulse would duplicate what the hook feeds.
# Step 05: state_delta=True keeps the grouped reply shape (diff fields nested
# under "stateDelta") for Claude — the hook consumes the nested form.
_HELLO_CAPS = {
    "vision": True,
    "output_format": "markdown",
    "token_budget": 20000,
    "verbose": True,
    "pulse": False,
    "state_delta": True,
    "accept_events": ["macro.*", "image.*", "dialog.*"],
}
# Cache of the last hello response so `ij.py capabilities` can show the
# server's enabled features without re-hitting the socket. Best-effort: a
# failed hello leaves this at None and every command still runs unchanged.
_HELLO_RESULT = None
_HELLO_SENT = False

# Phase 1: commands eligible for hash-based dedup. Server echoes a "hash"
# field; we persist (hash, payload) per command and attach if_none_match on
# the next call. Unchanged responses come back as {"ok": true, "unchanged": true, "hash": ...}.
READONLY_COMMANDS = frozenset([
    "ping", "get_state", "get_image_info", "get_log", "get_results_table",
    "get_histogram", "get_open_windows", "get_metadata", "get_dialogs",
    "get_state_context", "get_progress", "get_friction_log",
    "get_friction_patterns", "intent_list",
    "job_status", "job_list",
])

# Disk-backed cache so one-shot `python ij.py <cmd>` invocations benefit from
# dedup across invocations, not just within a single process. Kept in a
# separate file from the context-hook cache to avoid last-writer-wins races
# between the two clients. Failures to read/write are silently ignored —
# the in-memory layer is authoritative during a single run.
_CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp")
_CACHE_FILE = os.path.join(_CACHE_DIR, "ij_client_cache.json")


def _load_cache_from_disk():
    try:
        with open(_CACHE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            return {}
        out = {}
        for k, v in data.items():
            if isinstance(v, list) and len(v) == 2:
                out[k] = (v[0], v[1])
        return out
    except (IOError, OSError, ValueError):
        return {}


def _save_cache_to_disk(cache):
    try:
        os.makedirs(_CACHE_DIR, exist_ok=True)
        tmp = _CACHE_FILE + ".tmp"
        serial = {k: [v[0], v[1]] for k, v in cache.items()}
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(serial, f)
        os.replace(tmp, _CACHE_FILE)
    except (IOError, OSError):
        pass


# In-memory cache: cmd_name -> (hash, result). Seeded from disk so a fresh
# subprocess can reuse a previous call's hash on its very first request.
_READONLY_CACHE = _load_cache_from_disk()
_READONLY_CACHE_DIRTY = False


def imagej_command(cmd, host=HOST, port=PORT, timeout=TIMEOUT):
    """Send a JSON command to ImageJAI TCP server and return parsed response.
    On timeout, automatically checks for open dialogs before returning.

    For readonly commands, auto-attaches the last-seen hash as if_none_match.
    When the server replies unchanged, returns the cached result with
    {"cached": true} so callers can't tell the difference."""
    # Phase 1: hash dedup — only for well-formed readonly requests that don't
    # already carry an explicit if_none_match (respect caller override).
    cmd_name = None
    if isinstance(cmd, dict):
        cmd_name = cmd.get("command")
    if cmd_name in READONLY_COMMANDS and "if_none_match" not in cmd:
        cached = _READONLY_CACHE.get(cmd_name)
        if cached is not None:
            cmd = dict(cmd)  # don't mutate caller's dict
            cmd["if_none_match"] = cached[0]

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        payload = json.dumps(cmd) + "\n"
        s.sendall(payload.encode("utf-8"))
        # Read response
        data = b""
        while True:
            try:
                chunk = s.recv(65536)
                if not chunk:
                    break
                data += chunk
                # Check if we have a complete JSON response (ends with newline)
                if data.endswith(b"\n"):
                    break
            except socket.timeout:
                # TCP timeout — command may have opened a blocking dialog.
                # Check for dialogs immediately.
                s.close()
                dialogs = _check_dialogs_fallback(host, port)
                return {
                    "ok": False,
                    "error": "TCP timeout after {}s — command may be blocked by a dialog".format(timeout),
                    "dialogs": dialogs,
                }
        resp = json.loads(data.decode("utf-8"))
    finally:
        try:
            s.close()
        except Exception:
            pass

    # Phase 1: resolve unchanged responses from cache; refresh cache on hash.
    global _READONLY_CACHE_DIRTY
    if cmd_name in READONLY_COMMANDS and isinstance(resp, dict) and resp.get("ok"):
        if resp.get("unchanged"):
            cached = _READONLY_CACHE.get(cmd_name)
            if cached is not None:
                return {
                    "ok": True,
                    "result": cached[1],
                    "hash": cached[0],
                    "cached": True,
                }
            # Cache miss on unchanged shouldn't happen in normal flow, but be
            # defensive: drop the if_none_match and re-request.
            retry_cmd = dict(cmd)
            retry_cmd.pop("if_none_match", None)
            return imagej_command(retry_cmd, host=host, port=port, timeout=timeout)
        h = resp.get("hash")
        if h:
            prev = _READONLY_CACHE.get(cmd_name)
            if prev is None or prev[0] != h:
                _READONLY_CACHE[cmd_name] = (h, resp.get("result"))
                _READONLY_CACHE_DIRTY = True

    return resp


def _flush_cache():
    """Persist in-memory cache mutations to disk. Safe to call multiple times."""
    global _READONLY_CACHE_DIRTY
    if _READONLY_CACHE_DIRTY:
        _save_cache_to_disk(_READONLY_CACHE)
        _READONLY_CACHE_DIRTY = False


# Flush on interpreter exit so long-running imports persist too.
import atexit as _atexit
_atexit.register(_flush_cache)


def hello(host=HOST, port=PORT, timeout=10):
    """Send the step 01 handshake. Records caps on the server side and
    returns {server_version, session_id, enabled[], server_time_ms}.
    On any failure returns the error response — callers can fall through
    to the legacy no-handshake path without changing behaviour."""
    global _HELLO_RESULT, _HELLO_SENT
    req = {"command": "hello", "agent": "claude-code",
           "capabilities": _HELLO_CAPS}
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(timeout)
        try:
            s.connect((host, port))
            s.sendall((json.dumps(req) + "\n").encode("utf-8"))
            data = b""
            while True:
                chunk = s.recv(65536)
                if not chunk:
                    break
                data += chunk
                if data.endswith(b"\n"):
                    break
            resp = json.loads(data.decode("utf-8"))
        finally:
            try:
                s.close()
            except Exception:
                pass
    except (socket.error, OSError, ValueError) as e:
        return {"ok": False, "error": "hello failed: {}".format(e)}
    _HELLO_SENT = True
    if isinstance(resp, dict) and resp.get("ok"):
        _HELLO_RESULT = resp.get("result")
    return resp


# ---------------------------------------------------------------------------
# Structured-error normalisation (docs/tcp_upgrade/02_structured_errors.md)
# ---------------------------------------------------------------------------

def normalize_error(err):
    """Return a dict with a uniform error shape regardless of input.

    The TCP server emits either:
      - a plain string (legacy; for clients that never said hello), or
      - a typed object {code, category, retry_safe, message, recovery_hint,
        suggested, sideEffects} (for clients that negotiated
        structured_errors via hello).

    Agent code that wants to branch on retry_safe / category shouldn't have
    to care which shape arrived. Passing either form through this helper
    yields the object shape; legacy strings are labelled with code 'LEGACY'
    and a conservative retry_safe=True so agents can still choose to retry.

    Examples:
        >>> normalize_error("No image open")
        {'code': 'LEGACY', 'category': 'runtime', 'retry_safe': True,
         'message': 'No image open'}
        >>> normalize_error({'code': 'IMAGE_NOT_OPEN', 'category': 'state',
        ...                  'retry_safe': True, 'message': 'No image open'})
        {'code': 'IMAGE_NOT_OPEN', 'category': 'state', 'retry_safe': True,
         'message': 'No image open'}
        >>> normalize_error(None)
        {'code': 'UNKNOWN', 'category': 'runtime', 'retry_safe': False,
         'message': ''}
    """
    if isinstance(err, dict):
        out = dict(err)
        out.setdefault("code", "UNKNOWN")
        out.setdefault("category", "runtime")
        out.setdefault("retry_safe", False)
        out.setdefault("message", "")
        return out
    if isinstance(err, str):
        return {
            "code": "LEGACY",
            "category": "runtime",
            "retry_safe": True,
            "message": err,
        }
    return {
        "code": "UNKNOWN",
        "category": "runtime",
        "retry_safe": False,
        "message": "",
    }


def extract_error(resp):
    """Pull the error payload out of a TCP response in any shape.

    Errors can live at:
      - resp['error']              (top-level, on ok==false responses)
      - resp['result']['error']    (nested, on macro/script failures where
                                    ok==true but result.success==false)

    Returns the normalised error dict (per ``normalize_error``), or None if
    the response doesn't carry an error.
    """
    if not isinstance(resp, dict):
        return None
    if "error" in resp and resp["error"] is not None:
        return normalize_error(resp["error"])
    result = resp.get("result")
    if isinstance(result, dict) and result.get("error") is not None:
        return normalize_error(result["error"])
    return None


def _check_dialogs_fallback(host=HOST, port=PORT):
    """Emergency dialog check after a timeout. Uses a short timeout."""
    try:
        s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s2.settimeout(5)
        s2.connect((host, port))
        s2.sendall((json.dumps({"command": "get_dialogs"}) + "\n").encode("utf-8"))
        data = b""
        while True:
            try:
                chunk = s2.recv(65536)
                if not chunk:
                    break
                data += chunk
                if data.endswith(b"\n"):
                    break
            except socket.timeout:
                break
        s2.close()
        resp = json.loads(data.decode("utf-8"))
        if resp.get("ok") and resp.get("result", {}).get("dialogs"):
            return resp["result"]["dialogs"]
    except Exception:
        pass
    return []


def ping():
    return imagej_command({"command": "ping"})


def get_state():
    return imagej_command({"command": "get_state"})


def get_image_info():
    return imagej_command({"command": "get_image_info"})


def get_results_table():
    return imagej_command({"command": "get_results_table"})


def get_state_context():
    return imagej_command({"command": "get_state_context"})


def execute_macro(code):
    return imagej_command({"command": "execute_macro", "code": code})


def capture_image(max_size=1024):
    return imagej_command({"command": "capture_image", "maxSize": max_size})


def run_pipeline(steps):
    return imagej_command({"command": "run_pipeline", "steps": steps})


def explore_thresholds(methods):
    return imagej_command({"command": "explore_thresholds", "methods": methods})


def get_log():
    return imagej_command({"command": "get_log"})


def get_histogram():
    return imagej_command({"command": "get_histogram"})


def get_open_windows():
    return imagej_command({"command": "get_open_windows"})


def get_metadata():
    return imagej_command({"command": "get_metadata"})


def get_pixels(x=None, y=None, width=None, height=None, slice_num=None, all_slices=False):
    cmd = {"command": "get_pixels"}
    if x is not None: cmd["x"] = x
    if y is not None: cmd["y"] = y
    if width is not None: cmd["width"] = width
    if height is not None: cmd["height"] = height
    if slice_num is not None: cmd["slice"] = slice_num
    if all_slices: cmd["allSlices"] = True
    return imagej_command(cmd)


def viewer3d(action="status", **kwargs):
    cmd = {"command": "3d_viewer", "action": action}
    cmd.update(kwargs)
    return imagej_command(cmd)


def get_dialogs():
    return imagej_command({"command": "get_dialogs"})


def close_dialogs(pattern=None):
    return imagej_command({"command": "close_dialogs", "pattern": pattern})


def run_chain(chain, halt_on_error=True):
    """Phase 4: execute a |||-delimited chain as a single TCP round-trip."""
    return imagej_command({
        "command": "run",
        "chain": chain,
        "halt_on_error": halt_on_error,
    })


def intent(phrase):
    """Phase 5: resolve a phrase via the server-side intent router and execute
    the mapped macro. Returns ``{miss: true}`` in ``result`` on no match."""
    return imagej_command({"command": "intent", "phrase": phrase})


def intent_teach(phrase, macro, description=None):
    cmd = {"command": "intent_teach", "phrase": phrase, "macro": macro}
    if description:
        cmd["description"] = description
    return imagej_command(cmd)


def intent_list():
    return imagej_command({"command": "intent_list"})


def intent_forget(phrase):
    return imagej_command({"command": "intent_forget", "phrase": phrase})


def _expand_shorthand_pattern(pattern):
    """Translate the user-friendly ``<N>`` placeholder into the ``(\\d+)``
    regex capture the router actually uses. Idempotent on patterns that
    already contain ``(\\d+)`` or no placeholder at all."""
    return pattern.replace("<N>", r"(\d+)")


def _expand_shorthand_macro(macro, pattern):
    """If the pattern used the ``<N>`` shorthand, rewrite matching ``<N>``
    occurrences in the macro template to ``$1`` so the server substitutes
    the capture group. Only triggers when the pattern actually has ``<N>``
    — a raw regex pattern with ``$1`` inside the macro is left untouched."""
    if "<N>" not in pattern:
        return macro
    return macro.replace("<N>", "$1")


def get_friction_log(limit=20):
    return imagej_command({"command": "get_friction_log", "limit": limit})


def get_friction_patterns():
    return imagej_command({"command": "get_friction_patterns"})


def clear_friction_log():
    return imagej_command({"command": "clear_friction_log"})


# ---------------------------------------------------------------------------
# Phase 3: async job helpers
# ---------------------------------------------------------------------------

_JOB_TERMINAL_STATES = frozenset(("completed", "failed", "cancelled"))


def submit_async(code):
    """Submit macro code for async execution. Returns the server response;
    {"ok": true, "result": {"job_id": ..., "state": "running", ...}} on success."""
    return imagej_command({"command": "execute_macro_async", "code": code})


def job_status(job_id):
    return imagej_command({"command": "job_status", "job_id": job_id})


def job_cancel(job_id):
    return imagej_command({"command": "job_cancel", "job_id": job_id})


def job_list():
    return imagej_command({"command": "job_list"})


def _terminal_status(resp):
    """Return the status dict if resp describes a terminal state, else None."""
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None
    # Unchanged-cache replay: result lives where we cached it
    result = resp.get("result")
    if not isinstance(result, dict):
        return None
    if result.get("state") in _JOB_TERMINAL_STATES:
        return resp
    return None


def wait_for_job(job_id, timeout=None, host=HOST, port=PORT,
                 poll_interval=0.5, reconnect=True):
    """Block until a job reaches a terminal state (completed/failed/cancelled).

    Prefers the Phase 2 subscription channel: subscribes to ``job.*`` and
    filters on job_id. If the subscription socket fails or drops, falls back
    to polling ``job_status`` every ``poll_interval`` seconds.

    Returns the final ``job_status`` response, or
    ``{"ok": false, "error": "timeout", "job_id": ...}`` if ``timeout`` elapses.
    """
    import time as _time
    deadline = (_time.time() + timeout) if timeout is not None else None

    # Fast path: if the job is already terminal, skip the subscription dance.
    initial = job_status(job_id)
    term = _terminal_status(initial)
    if term is not None:
        return term
    if not initial.get("ok"):
        return initial  # unknown job — surface error immediately

    # Try the subscription channel first.
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(None)
        s.connect((host, port))
        req = json.dumps({"command": "subscribe", "topics": ["job.*"]}) + "\n"
        s.sendall(req.encode("utf-8"))

        # Re-check status after subscribing to avoid a TOCTOU miss where the
        # job completed between our initial poll and the subscribe ack.
        recheck = job_status(job_id)
        term = _terminal_status(recheck)
        if term is not None:
            return term

        buf = b""
        while True:
            if deadline is not None:
                remaining = deadline - _time.time()
                if remaining <= 0:
                    return {"ok": False, "error": "timeout", "job_id": job_id}
                s.settimeout(remaining)
            try:
                chunk = s.recv(8192)
            except socket.timeout:
                return {"ok": False, "error": "timeout", "job_id": job_id}
            if not chunk:
                break  # socket dropped — fall back to polling
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    frame = json.loads(line.decode("utf-8"))
                except Exception:
                    continue
                ev = frame.get("event") or ""
                if not ev.startswith("job."):
                    continue
                data = frame.get("data") or {}
                if data.get("job_id") != job_id:
                    continue
                if ev in ("job.completed", "job.failed"):
                    return job_status(job_id)
    except (socket.error, OSError, ConnectionError):
        if not reconnect:
            return {"ok": False, "error": "connection failed"}
    finally:
        if s is not None:
            try:
                s.close()
            except Exception:
                pass

    # Polling fallback — also reached if the subscription socket dropped.
    while True:
        if deadline is not None and _time.time() >= deadline:
            return {"ok": False, "error": "timeout", "job_id": job_id}
        try:
            cur = job_status(job_id)
        except Exception:
            cur = {"ok": False, "error": "poll failed"}
        term = _terminal_status(cur)
        if term is not None:
            return term
        try:
            _time.sleep(poll_interval)
        except KeyboardInterrupt:
            return {"ok": False, "error": "interrupted", "job_id": job_id}


# ---------------------------------------------------------------------------
# Phase 7: GUI_ACTION sentinels — drive the plugin's chat panel from the agent
# ---------------------------------------------------------------------------

def gui_toast(message, level="info"):
    """Show a transient toast in the plugin's chat panel."""
    return imagej_command({
        "command": "gui_action",
        "type": "toast",
        "message": message,
        "level": level,
    })


def gui_inline(path):
    """Append an inline image preview to the plugin's chat panel."""
    return imagej_command({
        "command": "gui_action",
        "type": "inline_preview",
        "path": os.path.abspath(path),
    })


def gui_focus(image_title):
    """Bring the named image window to the front."""
    return imagej_command({
        "command": "gui_action",
        "type": "focus_image",
        "title": image_title,
    })


def gui_markdown(content):
    """Render a small markdown snippet inline in the plugin's chat panel."""
    return imagej_command({
        "command": "gui_action",
        "type": "show_markdown",
        "content": content,
    })


def gui_highlight_roi(image_title, x, y, width, height):
    """Briefly flash a rectangular ROI on the named image."""
    return imagej_command({
        "command": "gui_action",
        "type": "highlight_roi",
        "title": image_title,
        "roi": [x, y, width, height],
    })


def gui_confirm(prompt, options, timeout=300):
    """Ask the user via the plugin's chat panel; block until they click.

    Tries the Phase 2 subscribe channel first (push-based, low latency). If
    the subscribe stream isn't available — e.g. the subscriber cap is hit, or
    the server is older than Phase 2 — falls back to friction-log polling at
    1 Hz for up to 60 s.

    Returns the dict {"id", "choice"}; on timeout returns
    {"id", "choice": None, "timed_out": True}.
    """
    if isinstance(options, str):
        options = [o.strip() for o in options.split(",") if o.strip()]
    req = {
        "command": "gui_action",
        "type": "confirm",
        "prompt": prompt,
        "options": list(options),
    }
    resp = imagej_command(req)
    if not (isinstance(resp, dict) and resp.get("ok")):
        return {"id": None, "choice": None, "error": resp}
    confirm_id = resp.get("id")

    # Preferred path: subscribe to the resolved event.
    try:
        import threading
        result_holder = {"choice": None, "got": False}
        stop_flag = {"stop": False}

        def listen():
            try:
                for event in imagej_events(
                        ["gui_action.confirm.resolved"], reconnect=False):
                    if stop_flag["stop"]:
                        return
                    if not isinstance(event, dict):
                        continue
                    data = event.get("data") or {}
                    if data.get("id") == confirm_id:
                        result_holder["choice"] = data.get("choice")
                        result_holder["got"] = True
                        return
            except Exception:
                pass

        t = threading.Thread(target=listen, daemon=True)
        t.start()
        t.join(timeout=timeout)
        stop_flag["stop"] = True
        if result_holder["got"]:
            return {"id": confirm_id, "choice": result_holder["choice"]}
    except Exception:
        # subscribe path is best-effort — fall through to polling
        pass

    # 60s polling fallback (spec): no subscribe support? Just sleep-poll the
    # friction log as a heartbeat ping. If the user clicks during this window
    # we won't see the response without subscribe — surface a timed_out so the
    # caller can decide whether to retry.
    import time as _time
    deadline = _time.time() + 60
    while _time.time() < deadline:
        _time.sleep(1)
    return {"id": confirm_id, "choice": None, "timed_out": True}


# ---------------------------------------------------------------------------
# Phase 2: event-bus subscription helper
# ---------------------------------------------------------------------------

def imagej_events(topics=None, host=HOST, port=PORT, reconnect=True, reconnect_delay=2.0):
    """Generator that yields event dicts from the Fiji TCP event bus.

    Usage:
        for event in imagej_events(["dialog.*", "macro.completed"]):
            print(event["event"], event["data"])

    Topics default to ``["*"]``. The helper maintains a long-lived socket
    and yields one decoded JSON object per frame. If ``reconnect`` is True
    (default), a dropped connection triggers a retry after ``reconnect_delay``
    seconds. Set ``reconnect=False`` to exit the generator on first drop.
    """
    import time as _time
    if not topics:
        topics = ["*"]
    elif isinstance(topics, str):
        topics = [topics]

    while True:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # Disable read timeout — heartbeats keep the socket alive.
        s.settimeout(None)
        try:
            s.connect((host, port))
            req = json.dumps({"command": "subscribe", "topics": list(topics)}) + "\n"
            s.sendall(req.encode("utf-8"))
            buf = b""
            while True:
                chunk = s.recv(8192)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        yield json.loads(line.decode("utf-8"))
                    except Exception:
                        # Malformed line — skip but keep streaming.
                        continue
        except (socket.error, OSError, ConnectionError):
            pass
        finally:
            try:
                s.close()
            except Exception:
                pass
        if not reconnect:
            return
        try:
            _time.sleep(reconnect_delay)
        except KeyboardInterrupt:
            return


def imagej_events(topics=None, host=HOST, port=PORT, reconnect=True, reconnect_delay=2.0):
    """Generator that yields event dicts from the Fiji TCP event bus."""
    import time as _time
    if not topics:
        topics = ["*"]
    elif isinstance(topics, str):
        topics = [topics]

    while True:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(None)
        try:
            s.connect((host, port))
            req = json.dumps({"command": "subscribe", "topics": list(topics)}) + "\n"
            s.sendall(req.encode("utf-8"))
            buf = b""
            while True:
                chunk = s.recv(8192)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        frame = json.loads(line.decode("utf-8"))
                    except Exception:
                        continue
                    yield frame
                    if isinstance(frame, dict) and frame.get("ok") is False:
                        return
        except (socket.error, OSError, ConnectionError):
            pass
        finally:
            try:
                s.close()
            except Exception:
                pass
        if not reconnect:
            return
        try:
            _time.sleep(reconnect_delay)
        except KeyboardInterrupt:
            return


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1].lower()

    try:
        if cmd == "ping":
            print(json.dumps(ping(), indent=2))

        elif cmd == "state":
            print(json.dumps(get_state(), indent=2))

        elif cmd == "info":
            print(json.dumps(get_image_info(), indent=2))

        elif cmd == "results":
            resp = get_results_table()
            if resp.get("ok") and resp.get("result"):
                print(resp["result"])
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "context":
            resp = get_state_context()
            if resp.get("ok") and resp.get("result"):
                print(resp["result"])
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "capture":
            resp = capture_image()
            if resp.get("ok") and resp.get("result", {}).get("base64"):
                # Default to .tmp/ dir so captures don't pollute the workspace
                tmp_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp")
                os.makedirs(tmp_dir, exist_ok=True)
                name = sys.argv[2] if len(sys.argv) > 2 else "capture"
                outfile = os.path.join(tmp_dir, name + ".png")
                img_data = base64.b64decode(resp["result"]["base64"])
                with open(outfile, "wb") as f:
                    f.write(img_data)
                print("Saved to " + outfile)
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "macro":
            if len(sys.argv) < 3:
                print("Usage: python ij.py macro \"run('Blobs (25K)');\"")
                sys.exit(1)
            code = " ".join(sys.argv[2:])
            resp = execute_macro(code)
            print(json.dumps(resp, indent=2))
            # Auto-warn about dialogs — check EVERYWHERE they might be
            dlgs = []
            if resp.get("ok") and resp.get("result", {}).get("dialogs"):
                dlgs = resp["result"]["dialogs"]
            elif resp.get("dialogs"):
                dlgs = resp["dialogs"]
            if dlgs:
                print("\n*** DIALOGS DETECTED ({}) ***".format(len(dlgs)))
                for d in dlgs:
                    print("  [{}] {}: {}".format(
                        d.get("type", "?").upper(),
                        d.get("title", ""),
                        d.get("text", "").strip()[:200]
                    ))
                    if d.get("buttons"):
                        print("    Buttons: {}".format(", ".join(d["buttons"])))

        elif cmd == "explore":
            methods = sys.argv[2:] if len(sys.argv) > 2 else ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy"]
            print(json.dumps(explore_thresholds(methods), indent=2))

        elif cmd == "log":
            resp = get_log()
            if resp.get("ok"):
                print(resp["result"])
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "progress":
            print(json.dumps(imagej_command({"command": "get_progress"}), indent=2))

        elif cmd == "histogram":
            print(json.dumps(get_histogram(), indent=2))

        elif cmd == "windows":
            print(json.dumps(get_open_windows(), indent=2))

        elif cmd == "metadata":
            print(json.dumps(get_metadata(), indent=2))

        elif cmd == "3d":
            # Sub-commands: status, add, list, snapshot, close
            sub = sys.argv[2] if len(sys.argv) > 2 else "status"
            kwargs = {}
            if sub == "add" and len(sys.argv) > 3:
                kwargs["image"] = sys.argv[3]
                if len(sys.argv) > 4: kwargs["type"] = sys.argv[4]
                if len(sys.argv) > 5: kwargs["threshold"] = int(sys.argv[5])
            elif sub == "snapshot":
                kwargs["width"] = int(sys.argv[3]) if len(sys.argv) > 3 else 512
                kwargs["height"] = int(sys.argv[4]) if len(sys.argv) > 4 else 512
            print(json.dumps(viewer3d(sub, **kwargs), indent=2))

        elif cmd == "dialogs":
            print(json.dumps(get_dialogs(), indent=2))

        elif cmd == "close_dialogs":
            pattern = sys.argv[2] if len(sys.argv) > 2 else None
            print(json.dumps(close_dialogs(pattern), indent=2))

        elif cmd == "probe":
            if len(sys.argv) < 3:
                print("Usage: python ij.py probe \"Plugin Name...\"")
                sys.exit(1)
            plugin_name = " ".join(sys.argv[2:])
            print(json.dumps(imagej_command(
                {"command": "probe_command", "plugin": plugin_name}), indent=2))

        elif cmd == "script":
            # Run a Groovy (or other language) script inside Fiji's JVM
            if len(sys.argv) < 3:
                print("Usage: python ij.py script 'groovy code'")
                print("       python ij.py script --file path/to/script.groovy")
                print("       python ij.py script --lang jython 'python code'")
                sys.exit(1)
            language = "groovy"
            code = None
            i = 2
            while i < len(sys.argv):
                if sys.argv[i] == "--lang" and i + 1 < len(sys.argv):
                    language = sys.argv[i + 1]
                    i += 2
                elif sys.argv[i] == "--file" and i + 1 < len(sys.argv):
                    with open(sys.argv[i + 1], "r") as f:
                        code = f.read()
                    i += 2
                else:
                    code = " ".join(sys.argv[i:])
                    break
            if not code:
                print("No code provided")
                sys.exit(1)
            resp = imagej_command(
                {"command": "run_script", "language": language, "code": code},
                timeout=180)
            print(json.dumps(resp, indent=2))
            # Show dialogs if any
            dlgs = []
            if resp.get("ok") and resp.get("result", {}).get("dialogs"):
                dlgs = resp["result"]["dialogs"]
            if dlgs:
                print("\n*** DIALOGS DETECTED ({}) ***".format(len(dlgs)))
                for d in dlgs:
                    print("  [{}] {}: {}".format(
                        d.get("type", "?").upper(),
                        d.get("title", ""),
                        d.get("text", "").strip()[:200]
                    ))

        elif cmd == "ui":
            # Dialog interaction: list components, click buttons, set values
            if len(sys.argv) < 3:
                print("Usage:")
                print("  python ij.py ui list                              # list all dialog components")
                print("  python ij.py ui list \"Dialog Title\"                # list components in specific dialog")
                print("  python ij.py ui click \"OK\"                        # click a button by text")
                print("  python ij.py ui check \"3D Object Analysis\" true   # set checkbox on/off")
                print("  python ij.py ui toggle \"Create Bin File\"          # flip checkbox state")
                print("  python ij.py ui text \"sigma\" 2.5                  # set text field by label")
                print("  python ij.py ui texti 0 hello                     # set text field by index")
                print("  python ij.py ui dropdown \"Method\" Otsu            # select dropdown value")
                print("  python ij.py ui slider 0 128                      # set slider by index")
                print("  python ij.py ui spinner 0 42                      # set spinner by index")
                print("  python ij.py ui scroll 0 50                       # set scrollbar by index")
                print("  python ij.py ui tab \"Advanced\"                    # focus a tab")
                sys.exit(1)

            sub = sys.argv[2]
            req = {"command": "interact_dialog"}

            if sub == "list":
                req["action"] = "list_components"
                if len(sys.argv) > 3:
                    req["dialog"] = sys.argv[3]
                resp = imagej_command(req)
                # Pretty-print the component list
                if resp.get("ok") and resp.get("result", {}).get("dialogs"):
                    for dlg in resp["result"]["dialogs"]:
                        print("=== {} ===".format(dlg.get("title", "(untitled)")))
                        for c in dlg.get("components", []):
                            line = "  [{:>3}] {:10s}".format(c["index"], c["type"])
                            if c.get("label"):
                                line += '  "{}"'.format(c["label"])
                            if c.get("nearestLabel"):
                                line += '  (near: "{}")'.format(c["nearestLabel"])
                            if "checked" in c:
                                line += "  [{}]".format("ON" if c["checked"] else "OFF")
                            elif "selected" in c:
                                line += "  [{}]".format("ON" if c["selected"] else "OFF")
                            elif "value" in c:
                                line += "  = {}".format(c["value"])
                            if "options" in c:
                                line += "  options={}".format(c["options"])
                            if "min" in c:
                                line += "  ({}-{})".format(c["min"], c["max"])
                            if not c.get("enabled", True):
                                line += "  [DISABLED]"
                            print(line)
                else:
                    print(json.dumps(resp, indent=2))

            elif sub == "click":
                req["action"] = "click_button"
                req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                if len(sys.argv) > 4 and sys.argv[4].isdigit():
                    req["index"] = int(sys.argv[4])
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "check":
                req["action"] = "set_checkbox"
                req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                if len(sys.argv) > 4:
                    req["value"] = sys.argv[4].lower() in ("true", "1", "on", "yes")
                else:
                    req["value"] = True
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "toggle":
                req["action"] = "toggle_checkbox"
                req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                if len(sys.argv) > 4 and sys.argv[4].isdigit():
                    req["index"] = int(sys.argv[4])
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "text":
                req["action"] = "set_text"
                req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                req["value"] = sys.argv[4] if len(sys.argv) > 4 else ""
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "texti":
                req["action"] = "set_text"
                req["index"] = int(sys.argv[3]) if len(sys.argv) > 3 else 0
                req["value"] = sys.argv[4] if len(sys.argv) > 4 else ""
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "dropdown":
                req["action"] = "set_dropdown"
                req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                req["value"] = sys.argv[4] if len(sys.argv) > 4 else ""
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "slider":
                req["action"] = "set_slider"
                if len(sys.argv) > 3 and sys.argv[3].isdigit():
                    req["index"] = int(sys.argv[3])
                else:
                    req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                req["value"] = int(sys.argv[4]) if len(sys.argv) > 4 else 0
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "spinner":
                req["action"] = "set_spinner"
                if len(sys.argv) > 3 and sys.argv[3].isdigit():
                    req["index"] = int(sys.argv[3])
                else:
                    req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                req["value"] = sys.argv[4] if len(sys.argv) > 4 else "0"
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "scroll":
                req["action"] = "set_scrollbar"
                if len(sys.argv) > 3 and sys.argv[3].isdigit():
                    req["index"] = int(sys.argv[3])
                else:
                    req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                req["value"] = int(sys.argv[4]) if len(sys.argv) > 4 else 0
                print(json.dumps(imagej_command(req), indent=2))

            elif sub == "tab":
                req["action"] = "focus_tab"
                if len(sys.argv) > 3 and sys.argv[3].isdigit():
                    req["index"] = int(sys.argv[3])
                else:
                    req["target"] = sys.argv[3] if len(sys.argv) > 3 else None
                print(json.dumps(imagej_command(req), indent=2))

            else:
                print("Unknown ui subcommand: " + sub)
                sys.exit(1)

        elif cmd == "run":
            # Phase 4: execute a |||-delimited chain in one TCP round-trip.
            if len(sys.argv) < 3:
                print("Usage: python ij.py run 'cmd1 ||| cmd2 ||| cmd3'")
                print("       python ij.py run --continue-on-error 'a ||| b ||| c'")
                print("")
                print("IMPORTANT: quote the whole chain as ONE argument so your")
                print("shell doesn't word-split on spaces or mangle embedded")
                print("quotes. Argv tokens past argv[2] are re-joined with a")
                print("single space, which loses fidelity if the shell has")
                print("already split your chain up.")
                print("")
                print("  bash/zsh/git-bash:")
                print("    python ij.py run 'run(\"Gaussian Blur...\", \"sigma=2\") ||| run(\"Measure\")'")
                print("  cmd.exe (Windows):")
                print("    python ij.py run \"run(\"\"Gaussian Blur...\"\", \"\"sigma=2\"\") ||| run(\"\"Measure\"\")\"")
                print("  PowerShell:")
                print("    python ij.py run 'run(\"Gaussian Blur...\", \"sigma=2\") ||| run(\"Measure\")'")
                print("")
                print("If you need a literal '|||' inside a macro string, escape it")
                print("as '\\|||' so the server parser treats it as data, not a")
                print("delimiter.")
                sys.exit(1)
            args = sys.argv[2:]
            halt = True
            if args and args[0] == "--continue-on-error":
                halt = False
                args = args[1:]
            # Re-join remaining argv with single spaces. Callers who single-quote
            # the whole chain land here as len(args) == 1 and fidelity is preserved.
            # Callers who let the shell word-split get the best-effort re-join,
            # which is lossy for multiple spaces and impossible for stripped
            # quotes — the docstring and this usage block warn about that.
            chain = " ".join(args) if len(args) > 1 else (args[0] if args else "")
            resp = run_chain(chain, halt_on_error=halt)
            print(json.dumps(resp, indent=2))

        elif cmd == "friction":
            # Phase 6: friction log inspection.
            sub = sys.argv[2] if len(sys.argv) > 2 else "log"
            if sub == "patterns":
                resp = get_friction_patterns()
                if resp.get("ok"):
                    res = resp.get("result", {})
                    patterns = res.get("patterns", [])
                    if not patterns:
                        print("No recurring friction patterns in the last {}s".format(
                            res.get("windowMs", 600000) // 1000))
                    else:
                        print("{} recurring pattern(s):".format(len(patterns)))
                        for p in patterns:
                            print("  [{}x] {}: {}".format(
                                p.get("count", 0),
                                p.get("command", ""),
                                p.get("sample", "")))
                else:
                    print(json.dumps(resp, indent=2))
            elif sub == "clear":
                print(json.dumps(clear_friction_log(), indent=2))
            else:
                # default: list recent failures
                limit = 20
                for a in sys.argv[2:]:
                    if a.startswith("--limit="):
                        try:
                            limit = int(a.split("=", 1)[1])
                        except ValueError:
                            pass
                    elif a.isdigit():
                        limit = int(a)
                resp = get_friction_log(limit)
                if resp.get("ok"):
                    res = resp.get("result", {})
                    entries = res.get("entries", [])
                    if not entries:
                        print("Friction log is empty")
                    else:
                        print("{} recent failures (total {}):".format(
                            res.get("returned", 0), res.get("total", 0)))
                        for e in entries:
                            print("  [{}] {}: {}".format(
                                e.get("command", ""),
                                (e.get("args") or "-")[:60],
                                (e.get("error") or "")[:140]))
                else:
                    print(json.dumps(resp, indent=2))

        elif cmd == "subscribe" or cmd == "events":
            # Phase 2: stream event-bus frames to stdout as JSONL.
            # Usage: python ij.py subscribe image.* dialog.* macro.completed
            topics = sys.argv[2:] if len(sys.argv) > 2 else ["*"]
            # Flag to disable auto-reconnect (for testing)
            reconnect = True
            if "--no-reconnect" in topics:
                reconnect = False
                topics = [t for t in topics if t != "--no-reconnect"]
            if not topics:
                topics = ["*"]
            try:
                for event in imagej_events(topics, reconnect=reconnect):
                    sys.stdout.write(json.dumps(event) + "\n")
                    sys.stdout.flush()
            except KeyboardInterrupt:
                pass

        elif cmd == "intent":
            # Phase 5: resolve a phrase and execute.
            if len(sys.argv) < 3:
                print("Usage: python ij.py intent \"<phrase>\"")
                sys.exit(1)
            phrase = " ".join(sys.argv[2:])
            resp = intent(phrase)
            if resp.get("ok") is False and resp.get("miss"):
                print("MISS: no mapping for \"{}\"".format(phrase))
                sys.exit(2)
            if resp.get("ok"):
                mapped = resp.get("mapped_to", {})
                if mapped:
                    desc = mapped.get("description") or "(no description)"
                    print("HIT: {} — pattern={!r} hits={}".format(
                        desc, mapped.get("pattern", ""), mapped.get("hits", "?")))
                    print("  macro: " + str(mapped.get("macro", ""))[:200])
                res = resp.get("result", {})
                if isinstance(res, dict):
                    if res.get("success") is False:
                        print("EXECUTE FAILED: " + str(res.get("error", "")))
                    if res.get("output"):
                        print("output: " + str(res["output"]))
                    if res.get("dialogs"):
                        print("\n*** DIALOGS DETECTED ({}) ***".format(len(res["dialogs"])))
                        for d in res["dialogs"]:
                            print("  [{}] {}: {}".format(
                                d.get("type", "?").upper(),
                                d.get("title", ""),
                                (d.get("text", "") or "").strip()[:200]))
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "teach":
            # Phase 5: teach a new mapping.
            # Usage: python ij.py teach "<pattern>" '<macro>' [--description "..."]
            args = sys.argv[2:]
            description = None
            if "--description" in args:
                idx = args.index("--description")
                if idx + 1 < len(args):
                    description = args[idx + 1]
                    args = args[:idx] + args[idx + 2:]
            if len(args) < 2:
                print("Usage: python ij.py teach \"<pattern>\" '<macro template>' "
                      "[--description \"...\"]")
                print("  <N> in the pattern expands to (\\d+) and <N> in the macro to $1.")
                sys.exit(1)
            raw_pattern = args[0]
            raw_macro = args[1] if len(args) == 2 else " ".join(args[1:])
            pattern = _expand_shorthand_pattern(raw_pattern)
            macro = _expand_shorthand_macro(raw_macro, raw_pattern)
            resp = intent_teach(pattern, macro, description=description)
            print(json.dumps(resp, indent=2))

        elif cmd == "intents":
            # Phase 5: list mappings, most-used first.
            resp = intent_list()
            if not resp.get("ok"):
                print(json.dumps(resp, indent=2))
            else:
                result = resp.get("result", {})
                mappings = result.get("mappings", []) or []
                quarantined = result.get("quarantined", []) or []
                mappings_sorted = sorted(
                    mappings,
                    key=lambda m: (-int(m.get("hits", 0) or 0),
                                   str(m.get("pattern", ""))))
                print("{} mapping(s) @ {}".format(
                    len(mappings_sorted), result.get("path", "?")))
                for m in mappings_sorted:
                    desc = m.get("description", "")
                    line = "  [{:>4}x] {}".format(m.get("hits", 0), m.get("pattern", ""))
                    if desc:
                        line += " — " + desc
                    print(line)
                    macro = m.get("macro", "")
                    if macro:
                        print("          -> " + str(macro)[:200])
                if quarantined:
                    print("\n{} quarantined (invalid regex):".format(len(quarantined)))
                    for q in quarantined:
                        print("  {} — {}".format(
                            q.get("pattern", ""),
                            q.get("error", "unknown error")))

        elif cmd == "forget":
            if len(sys.argv) < 3:
                print("Usage: python ij.py forget \"<pattern>\"")
                sys.exit(1)
            pattern = sys.argv[2]
            # If the caller used the <N> shorthand, expand so the stored regex
            # matches the one teach saved.
            pattern = _expand_shorthand_pattern(pattern)
            resp = intent_forget(pattern)
            print(json.dumps(resp, indent=2))

        elif cmd == "async":
            # Phase 3: submit a macro for async execution. Prints only the job_id
            # on success so callers can `JOB=$(python ij.py async '...')`.
            if len(sys.argv) < 3:
                print("Usage: python ij.py async '<macro code>'")
                sys.exit(1)
            code = " ".join(sys.argv[2:])
            resp = submit_async(code)
            if resp.get("ok") and resp.get("result", {}).get("job_id"):
                print(resp["result"]["job_id"])
            else:
                print(json.dumps(resp, indent=2))
                sys.exit(1)

        elif cmd == "job":
            if len(sys.argv) < 3:
                print("Usage: python ij.py job <id>")
                sys.exit(1)
            print(json.dumps(job_status(sys.argv[2]), indent=2))

        elif cmd == "cancel":
            if len(sys.argv) < 3:
                print("Usage: python ij.py cancel <id>")
                sys.exit(1)
            print(json.dumps(job_cancel(sys.argv[2]), indent=2))

        elif cmd == "wait":
            if len(sys.argv) < 3:
                print("Usage: python ij.py wait <id> [--timeout SEC]")
                sys.exit(1)
            job_id = sys.argv[2]
            timeout = None
            args = list(sys.argv[3:])
            while args:
                a = args.pop(0)
                if a.startswith("--timeout="):
                    try:
                        timeout = float(a.split("=", 1)[1])
                    except ValueError:
                        pass
                elif a == "--timeout" and args:
                    try:
                        timeout = float(args.pop(0))
                    except ValueError:
                        pass
            print(json.dumps(wait_for_job(job_id, timeout=timeout), indent=2))

        elif cmd == "jobs":
            resp = job_list()
            if resp.get("ok"):
                for j in resp.get("result", {}).get("jobs", []):
                    elapsed = j.get("elapsedMs", 0) / 1000.0
                    prog = j.get("progress", 0.0) or 0.0
                    preview = (j.get("preview") or "").replace("\n", " ")[:60]
                    print("  {}  {:<9s}  {:.1f}s  {:>4.0%}  {}".format(
                        j.get("job_id", "?"), j.get("state", "?"),
                        elapsed, prog, preview))
                if not resp.get("result", {}).get("jobs"):
                    print("(no jobs)")
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "run_patient":
            # Phase 3: submit then wait, auto-reconnecting if the wait socket drops.
            # The default UX for "run this and tell me when it's done".
            if len(sys.argv) < 3:
                print("Usage: python ij.py run_patient '<macro code>' [--timeout SEC]")
                sys.exit(1)
            timeout = None
            rest = []
            args = list(sys.argv[2:])
            while args:
                a = args.pop(0)
                if a.startswith("--timeout="):
                    try:
                        timeout = float(a.split("=", 1)[1])
                    except ValueError:
                        pass
                elif a == "--timeout" and args:
                    try:
                        timeout = float(args.pop(0))
                    except ValueError:
                        pass
                else:
                    rest.append(a)
            code = " ".join(rest)
            sub = submit_async(code)
            if not (sub.get("ok") and sub.get("result", {}).get("job_id")):
                print(json.dumps(sub, indent=2))
                sys.exit(1)
            job_id = sub["result"]["job_id"]
            sys.stderr.write("[run_patient] submitted " + job_id + "\n")
            sys.stderr.flush()
            final = wait_for_job(job_id, timeout=timeout, reconnect=True)
            print(json.dumps(final, indent=2))
            if not final.get("ok"):
                sys.exit(1)

        elif cmd == "toast":
            # Phase 7: drive a toast in the plugin's chat panel.
            if len(sys.argv) < 3:
                print("Usage: python ij.py toast \"message\" [--level info|warn|error]")
                sys.exit(1)
            level = "info"
            args = sys.argv[2:]
            cleaned = []
            i = 0
            while i < len(args):
                if args[i] == "--level" and i + 1 < len(args):
                    level = args[i + 1]
                    i += 2
                else:
                    cleaned.append(args[i])
                    i += 1
            message = " ".join(cleaned)
            print(json.dumps(gui_toast(message, level=level), indent=2))

        elif cmd == "inline":
            # Phase 7: send an inline image preview to the chat panel.
            if len(sys.argv) < 3:
                print("Usage: python ij.py inline /path/to/image.png")
                sys.exit(1)
            path = sys.argv[2]
            print(json.dumps(gui_inline(path), indent=2))

        elif cmd == "focus":
            # Phase 7: focus a named image window.
            if len(sys.argv) < 3:
                print("Usage: python ij.py focus \"Image Title\"")
                sys.exit(1)
            title = " ".join(sys.argv[2:])
            print(json.dumps(gui_focus(title), indent=2))

        elif cmd == "markdown":
            # Phase 7: render a small markdown snippet inline.
            if len(sys.argv) < 3:
                print("Usage: python ij.py markdown \"**bold** text\"")
                sys.exit(1)
            content = " ".join(sys.argv[2:])
            print(json.dumps(gui_markdown(content), indent=2))

        elif cmd == "highlight":
            # Phase 7: flash an ROI on a named image.
            if len(sys.argv) < 7:
                print("Usage: python ij.py highlight \"Image Title\" X Y W H")
                sys.exit(1)
            title = sys.argv[2]
            x = int(sys.argv[3]); y = int(sys.argv[4])
            w = int(sys.argv[5]); h = int(sys.argv[6])
            print(json.dumps(gui_highlight_roi(title, x, y, w, h), indent=2))

        elif cmd == "confirm":
            # Phase 7: ask the user via the chat panel; block until they click.
            # Usage: python ij.py confirm "prompt" --options "Yes,No" [--timeout 300]
            if len(sys.argv) < 3:
                print("Usage: python ij.py confirm \"prompt\" --options \"Yes,No\"")
                sys.exit(1)
            prompt = sys.argv[2]
            options_csv = "Yes,No"
            timeout = 300
            args = sys.argv[3:]
            i = 0
            while i < len(args):
                if args[i] == "--options" and i + 1 < len(args):
                    options_csv = args[i + 1]; i += 2
                elif args[i] == "--timeout" and i + 1 < len(args):
                    try:
                        timeout = int(args[i + 1])
                    except ValueError:
                        pass
                    i += 2
                else:
                    i += 1
            result = gui_confirm(prompt, options_csv, timeout=timeout)
            choice = result.get("choice")
            if choice is None and result.get("timed_out"):
                print(json.dumps(result, indent=2))
                sys.exit(2)
            if choice is None:
                print(json.dumps(result, indent=2))
                sys.exit(1)
            # Bare choice on stdout for shell consumers; full payload to stderr.
            sys.stderr.write(json.dumps(result) + "\n")
            print(choice)

        elif cmd == "capabilities" or cmd == "hello":
            # Step 01 (docs/tcp_upgrade/01_hello_handshake.md): run the
            # handshake and print what the server says it will emit for this
            # client. Calling this is optional — the server falls back to the
            # legacy reply shape for any client that never says hello.
            print(json.dumps(hello(), indent=2))

        elif cmd == "raw":
            if len(sys.argv) < 3:
                print("Usage: python ij.py raw '{\"command\": \"ping\"}'")
                sys.exit(1)
            raw_cmd = json.loads(sys.argv[2])
            print(json.dumps(imagej_command(raw_cmd), indent=2))

        elif cmd == "reactive":
            # Phase 8: inspect / toggle / reload the reactive rules engine.
            sub = sys.argv[2] if len(sys.argv) > 2 else "list"
            if sub == "list":
                resp = imagej_command({"command": "list_reactive_rules"})
                if not resp.get("ok"):
                    print(json.dumps(resp, indent=2))
                    sys.exit(1)
                res = resp.get("result", {})
                rules = res.get("rules", [])
                qu = res.get("quarantined", [])
                if res.get("locked"):
                    print("[ENGINE LOCKED — reactive.lock present, no rules will fire]")
                if not rules:
                    print("No reactive rules loaded.")
                else:
                    # Sort by priority asc (server already sorts, but belt-and-braces).
                    rules = sorted(rules, key=lambda r: (r.get("priority", 100), r.get("name", "")))
                    print("{} rule(s):".format(len(rules)))
                    for r in rules:
                        flag = "on " if r.get("enabled") else "off"
                        print("  [{}] p={:<3}  {:<28}  hits={:<4}  {}".format(
                            flag,
                            r.get("priority", 100),
                            r.get("name", ""),
                            r.get("hits", 0),
                            r.get("event", "")))
                        desc = r.get("description")
                        if desc:
                            print("      {}".format(desc))
                if qu:
                    print("\n{} quarantined:".format(len(qu)))
                    for q in qu:
                        print("  {}: {}".format(q.get("path", ""), q.get("error", "")))

            elif sub == "enable":
                if len(sys.argv) < 4:
                    print("Usage: python ij.py reactive enable <name>")
                    sys.exit(1)
                print(json.dumps(imagej_command(
                    {"command": "reactive_enable", "name": sys.argv[3]}), indent=2))

            elif sub == "disable":
                if len(sys.argv) < 4:
                    print("Usage: python ij.py reactive disable <name>")
                    sys.exit(1)
                print(json.dumps(imagej_command(
                    {"command": "reactive_disable", "name": sys.argv[3]}), indent=2))

            elif sub == "reload":
                print(json.dumps(imagej_command(
                    {"command": "reactive_reload"}), indent=2))

            elif sub == "stats":
                resp = imagej_command({"command": "reactive_stats"})
                if resp.get("ok"):
                    res = resp.get("result", {})
                    hits = res.get("hits", {})
                    if not hits:
                        print("No rules firing (total=0)")
                    else:
                        print("Total hits: {}".format(res.get("total", 0)))
                        for name in sorted(hits, key=lambda n: -hits[n]):
                            print("  {:>6}  {}".format(hits[name], name))
                    if res.get("quarantined"):
                        print("Quarantined: {}".format(res["quarantined"]))
                else:
                    print(json.dumps(resp, indent=2))

            else:
                print("Usage: python ij.py reactive <list|enable <name>|disable <name>|reload|stats>")
                sys.exit(1)

        else:
            print("Unknown command: " + cmd)
            print(__doc__)
            sys.exit(1)

    except ConnectionRefusedError:
        print("ERROR: Cannot connect to ImageJAI on localhost:" + str(PORT))
        print("Make sure Fiji is running with AI Assistant open and TCP server enabled.")
        sys.exit(1)
    except Exception as e:
        print("ERROR: " + str(e))
        sys.exit(1)


if __name__ == "__main__":
    main()
