"""
Context hook for ImageJAI — injects Fiji state into Claude conversations.

Session start:  Fiji connection, installed plugins/commands, reference doc list
Every message:  Open images, active image, results table, memory, dialogs, IJ log

Phase 2: a long-lived background subscriber to the Fiji event bus is opened at
session start and the hook serves every-message queries from a live in-process
snapshot instead of polling TCP. If the subscription drops, the hook falls
back to the original TCP-polling path.
"""

import json
import os
import socket
import sys
import time
import argparse

TCP_HOST = os.environ.get("IMAGEJAI_TCP_HOST", "127.0.0.1")
try:
    TCP_PORT = int(os.environ.get("IMAGEJAI_TCP_PORT", "7746"))
except ValueError:
    TCP_PORT = 7746
TCP_TIMEOUT = 3  # seconds per request
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
REFS_DIR = os.path.join(PROJECT_DIR, "agent", "references")

# Phase 1: per-command hash cache persisted to disk so that each prompt-fire
# of the hook can reuse the previous result when Fiji state is unchanged.
# Stored in agent/.tmp (same dir other hook artefacts live in). Kept in a
# separate file from ij.py's own client cache (agent/.tmp/ij_client_cache.json)
# so the two short-lived clients never last-writer-wins each other's entries.
CACHE_DIR = os.path.join(PROJECT_DIR, "agent", ".tmp")
CACHE_FILE = os.path.join(CACHE_DIR, "context_hook_cache.json")

# Phase 2: the hook is invoked as a subprocess per prompt. Subscribing from a
# short-lived subprocess has no payoff (we'd immediately close), so we persist
# the last-known state snapshot to disk and refresh it out-of-band via the
# Fiji daemon in the `agent/.tmp/` dir. Files are endpoint-specific so a
# snapshot from one Fiji instance/port can never satisfy another.


def _endpoint_slug(host=None, port=None):
    host = host or TCP_HOST
    port = TCP_PORT if port is None else port
    safe_host = "".join(ch if ch.isalnum() else "_" for ch in str(host))
    return "{}_{}".format(safe_host, port)


def _snapshot_file(host=None, port=None):
    return os.path.join(CACHE_DIR, "event_snapshot_{}.json".format(
        _endpoint_slug(host, port)))


def _subscriber_pid_file(host=None, port=None):
    return os.path.join(CACHE_DIR, "event_subscriber_{}.pid".format(
        _endpoint_slug(host, port)))


def _subscriber_log_file(host=None, port=None):
    return os.path.join(CACHE_DIR, "event_subscriber_{}.log".format(
        _endpoint_slug(host, port)))


def _subscriber_stderr_file(host=None, port=None):
    return os.path.join(CACHE_DIR, "event_subscriber_{}_stderr.log".format(
        _endpoint_slug(host, port)))


def _ensure_trace_file(host=None, port=None):
    return os.path.join(CACHE_DIR, "event_ensure_trace_{}.log".format(
        _endpoint_slug(host, port)))

READONLY_COMMANDS = frozenset([
    "ping", "get_state", "get_image_info", "get_log", "get_results_table",
    "get_histogram", "get_open_windows", "get_metadata", "get_dialogs",
    "get_state_context", "get_progress", "get_friction_log",
    "get_friction_patterns",
])


def _load_cache():
    try:
        with open(CACHE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except (IOError, OSError, ValueError):
        pass
    return {}


def _load_snapshot(path=None):
    """Read the event-subscriber snapshot from disk, if present.

    Returns an empty dict if the file is missing, unreadable, or malformed.
    Callers should use ``_snapshot_is_fresh`` to decide whether the data is
    safe to serve.
    """
    path = path or _snapshot_file()
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except (IOError, OSError, ValueError):
        pass
    return {}


def _snapshot_matches_endpoint(snap, host=None, port=None):
    if not isinstance(snap, dict):
        return False
    host = host or TCP_HOST
    port = TCP_PORT if port is None else port
    try:
        snap_port = int(snap.get("tcp_port", -1))
    except (TypeError, ValueError):
        return False
    return (str(snap.get("tcp_host", "")) == str(host)
            and snap_port == int(port))


def _pid_alive(pid):
    """True if a process with the given PID is currently running. Windows-
    friendly — uses ``tasklist`` rather than ``os.kill(pid, 0)`` which has
    spotty behaviour on Windows Python."""
    if pid is None or pid <= 0:
        return False
    try:
        if sys.platform == "win32":
            import subprocess as _sp
            out = _sp.check_output(
                ["tasklist", "/FI", "PID eq " + str(pid), "/NH"],
                stderr=_sp.DEVNULL, timeout=3).decode(errors="ignore")
            return str(pid) in out
        else:
            os.kill(pid, 0)
            return True
    except Exception:
        return False


def _snapshot_is_fresh(snap, max_age_seconds=90):
    """True if the snapshot was written within ``max_age_seconds``.

    The subscriber writes its own wall-clock ``updated_ts`` on every event
    and every heartbeat (~30s). 90s gives a 3-interval tolerance before we
    treat the snapshot as stale and fall back to polling.
    """
    try:
        ts = float(snap.get("updated_ts", 0)) / 1000.0
    except (TypeError, ValueError):
        return False
    if ts <= 0:
        return False
    return (time.time() - ts) < max_age_seconds


def _save_cache(cache):
    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
        tmp = CACHE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cache, f)
        os.replace(tmp, CACHE_FILE)
    except (IOError, OSError):
        pass


_CACHE = _load_cache()
_CACHE_DIRTY = False


# ---------------------------------------------------------------------------
# TCP helpers
# ---------------------------------------------------------------------------

def tcp_send(command_obj):
    """Send a JSON command to the Fiji TCP server and return parsed response.
    For readonly commands, attaches if_none_match from the disk cache and
    resolves unchanged responses transparently.

    B9 — timeout dialog fallback: if the request hits ``socket.timeout``
    before any data comes back, the EDT is most likely blocked by a modal
    dialog. Rather than raising and letting the caller print "Fiji state:
    error", we open a fresh short-timeout socket and ask ``get_dialogs`` so
    the hook can surface the blocking dialog in the injected context. The
    fallback is tight (``_FALLBACK_TIMEOUT`` seconds) and wrapped in its own
    try/except so a second failure doesn't cascade.

    Mid-stream reads (some bytes received, then a timeout while more were
    expected) do NOT trigger the fallback — those are long-payload responses
    that we'd rather truncate-fail than double-query.
    """
    global _CACHE_DIRTY
    cmd_name = command_obj.get("command") if isinstance(command_obj, dict) else None

    # Attach hash if we have one cached and the caller didn't already set one.
    out_cmd = command_obj
    if cmd_name in READONLY_COMMANDS and "if_none_match" not in command_obj:
        cached = _CACHE.get(cmd_name)
        if isinstance(cached, dict) and "hash" in cached:
            out_cmd = dict(command_obj)
            out_cmd["if_none_match"] = cached["hash"]

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(TCP_TIMEOUT)
    got_any_bytes = False
    try:
        try:
            sock.connect((TCP_HOST, TCP_PORT))
            payload = json.dumps(out_cmd) + "\n"
            sock.sendall(payload.encode("utf-8"))
            chunks = []
            while True:
                data = sock.recv(8192)
                if not data:
                    break
                got_any_bytes = True
                chunks.append(data)
            raw = b"".join(chunks).decode("utf-8").strip()
            resp = json.loads(raw)
        except socket.timeout:
            # Clean up the dead socket before opening a fresh one.
            try:
                sock.close()
            except Exception:
                pass
            sock = None
            # Mid-stream cut — don't trigger the dialog fallback, just re-raise
            # so the caller sees a clean timeout rather than a half-baked
            # response stitched together with dialog-probe data.
            if got_any_bytes:
                raise
            # Connection-level timeout — EDT likely blocked by a dialog. Probe
            # get_dialogs on a tight, independent socket. Any exception during
            # the probe is swallowed; we still synthesise a best-effort error
            # response so the caller can render something useful.
            dialogs = _probe_dialogs_with_timeout(_FALLBACK_TIMEOUT)
            return {
                "ok": False,
                "error": ("TCP timeout after " + str(TCP_TIMEOUT)
                          + "s — may be blocked by a dialog"),
                "dialogs": dialogs,
            }
    finally:
        if sock is not None:
            try:
                sock.close()
            except Exception:
                pass

    # Resolve unchanged responses from the cached payload.
    if (cmd_name in READONLY_COMMANDS
            and isinstance(resp, dict)
            and resp.get("ok")):
        if resp.get("unchanged"):
            cached = _CACHE.get(cmd_name)
            if isinstance(cached, dict) and "result" in cached:
                return {
                    "ok": True,
                    "result": cached["result"],
                    "hash": cached.get("hash"),
                    "cached": True,
                }
        h = resp.get("hash")
        if h:
            _CACHE[cmd_name] = {"hash": h, "result": resp.get("result")}
            _CACHE_DIRTY = True

    return resp


# B9: tight timeout for the dialog fallback probe. Must be << TCP_TIMEOUT so
# the fallback can't itself block the hook past its overall budget (the hook
# has ~3s total per prompt).
_FALLBACK_TIMEOUT = 2.0


def _probe_dialogs_with_timeout(timeout_s):
    """Open a fresh short-timeout socket and ask the server for open dialogs.

    Returns a list of dialog dicts (possibly empty), or an empty list on any
    error. Never raises. Used by ``tcp_send`` when the primary request hits a
    connection-level timeout, to help the user see the blocking dialog in
    the hook's injected context on the next prompt.
    """
    probe_sock = None
    try:
        probe_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe_sock.settimeout(timeout_s)
        probe_sock.connect((TCP_HOST, TCP_PORT))
        probe_sock.sendall((json.dumps({"command": "get_dialogs"}) + "\n").encode("utf-8"))
        chunks = []
        while True:
            data = probe_sock.recv(8192)
            if not data:
                break
            chunks.append(data)
        raw = b"".join(chunks).decode("utf-8").strip()
        if not raw:
            return []
        resp = json.loads(raw)
        if not isinstance(resp, dict) or not resp.get("ok"):
            return []
        result = resp.get("result")
        # get_dialogs returns either a list directly or {"dialogs": [...]}
        if isinstance(result, list):
            return result
        if isinstance(result, dict):
            d = result.get("dialogs")
            if isinstance(d, list):
                return d
        return []
    except Exception:
        return []
    finally:
        if probe_sock is not None:
            try:
                probe_sock.close()
            except Exception:
                pass


def fiji_available():
    """Check if Fiji TCP server is reachable."""
    try:
        resp = tcp_send({"command": "ping"})
        return resp.get("ok", False)
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Phase 2: background subscriber (sidecar) + snapshot consumer
# ---------------------------------------------------------------------------

def _ensure_subscriber_running():
    """Spawn the event-bus subscriber sidecar if it's not already running.

    Freshness is checked via the snapshot file's ``updated_ts``. If the
    snapshot hasn't been touched within the heartbeat window, we assume the
    previous sidecar died and spawn a replacement. The sidecar is fully
    detached so it outlives this hook subprocess.
    """
    # Always leave a breadcrumb so we can confirm this function actually ran.
    trace_file = _ensure_trace_file()
    snapshot_path = _snapshot_file()
    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(trace_file, "a") as _f:
            _f.write("[ensure] pid={} port={} {}\n".format(
                os.getpid(), TCP_PORT, time.time()))
    except Exception:
        pass
    # If the snapshot is fresh for this exact endpoint, a subscriber is already
    # writing to it.
    snap = _load_snapshot(snapshot_path)
    if (_snapshot_is_fresh(snap, max_age_seconds=60)
            and _snapshot_matches_endpoint(snap)):
        return
    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
    except OSError:
        return

    import subprocess
    script = os.path.abspath(__file__)
    python_exe = sys.executable or "python"
    try:
        # Trace so we can see where Popen blew up if it did.
        with open(trace_file, "a") as _f:
            _f.write("[popen_about_to_start] python={} script={} cwd={}\n".format(
                python_exe, script, PROJECT_DIR))
        # Redirect daemon stderr to a log so startup failures are visible.
        try:
            os.makedirs(CACHE_DIR, exist_ok=True)
        except OSError:
            pass
        err_log = open(_subscriber_stderr_file(), "ab", buffering=0)

        kwargs = {
            "stdin": subprocess.DEVNULL,
            "stdout": err_log,
            "stderr": err_log,
            # On Windows Python 3.7+, close_fds=True is the default and plays
            # nicely with explicit stdout/stderr redirects. close_fds=False
            # caused the parent subprocess.run() in tests to block waiting for
            # inherited pipe handles to close even though we'd detached.
            "close_fds": True,
        }
        if sys.platform == "win32":
            # Detached console, no console window, new process group.
            kwargs["creationflags"] = (
                0x00000008  # DETACHED_PROCESS
                | 0x00000200  # CREATE_NEW_PROCESS_GROUP
                | 0x08000000  # CREATE_NO_WINDOW
            )
        else:
            kwargs["start_new_session"] = True

        p = subprocess.Popen(
            [python_exe, script, "--subscriber-daemon"],
            cwd=PROJECT_DIR,
            env=os.environ.copy(),  # explicit passthrough
            **kwargs,
        )
        with open(trace_file, "a") as _f:
            _f.write("[popen_spawned] pid={}\n".format(p.pid))
        # Our parent process doesn't need the shared file handle after the
        # daemon has been dup()'d it. Closing the parent's copy frees
        # subprocess.run() in the caller from waiting on this handle.
        try:
            err_log.close()
        except Exception:
            pass
    except Exception as _e:
        # Sidecar is an optimisation; failure just means we fall back to polling.
        try:
            with open(_subscriber_stderr_file(), "a") as f:
                f.write("[spawn failed] " + repr(_e) + "\n")
        except Exception:
            pass


def _serve_state_from_snapshot(snap):
    """Render the same 'Fiji state' line as fiji_state(), but from the snapshot."""
    if not isinstance(snap, dict):
        return None
    st = snap.get("state")
    if not isinstance(st, dict):
        return None
    parts = []

    def _bit_depth_label(value):
        if value is None:
            return "?"
        if isinstance(value, (int, float)):
            return str(int(value)) + "-bit"
        text = str(value).strip()
        if not text:
            return "?"
        return text if "bit" in text.lower() else text + "-bit"

    active = st.get("activeImage")
    if active and isinstance(active, dict):
        title = str(active.get("title", "?"))
        w = active.get("width", "?")
        h = active.get("height", "?")
        slices = active.get("nSlices", active.get("slices", 1)) or 1
        channels = active.get("nChannels", active.get("channels", 1)) or 1
        frames = active.get("nFrames", active.get("frames", 1)) or 1
        stack_info = ""
        if (slices and slices > 1) or (channels and channels > 1) or (frames and frames > 1):
            stack_info = " [C=" + str(channels) + " Z=" + str(slices) + " T=" + str(frames) + "]"
        cal_info = ""
        cal = active.get("calibration")
        if isinstance(cal, dict):
            unit = cal.get("unit")
            if unit and unit != "pixel":
                cal_info = " cal=" + str(cal.get("pixelWidth", "?")) + " " + str(unit)
        elif cal:
            cal_info = " cal=" + str(cal)
        bit_depth = _bit_depth_label(active.get("bitDepth", active.get("type", "?")))
        parts.append('Active: "' + title + '" ' + str(w) + "x" + str(h) + " " +
                     str(bit_depth) + stack_info + cal_info)
    else:
        parts.append("Active: none")

    all_imgs = st.get("allImages") or []
    if isinstance(all_imgs, list):
        if len(all_imgs) > 1:
            titles = [str(i.get("title", "?")) for i in all_imgs if isinstance(i, dict)]
            parts.append("Open images (" + str(len(titles)) + "): " + ", ".join(titles))
        elif len(all_imgs) == 1:
            parts.append("Open images: 1")
        else:
            parts.append("Open images: 0")

    csv = st.get("resultsTable", "") or ""
    if csv and csv.strip():
        lines = csv.strip().split("\n")
        if lines:
            header = lines[0]
            cols = [c.strip() for c in header.split(",") if c.strip()]
            row_count = len(lines) - 1
            parts.append("Results table: " + str(row_count) + " rows, columns=[" + ", ".join(cols) + "]")
    else:
        parts.append("Results table: empty")

    mem = st.get("memory") or {}
    if mem:
        parts.append("JVM memory: " + str(mem.get("usedMB", "?")) + "/" +
                     str(mem.get("maxMB", "?")) + " MB used, " +
                     str(mem.get("freeMB", "?")) + " MB free, " +
                     str(mem.get("openImageCount", "?")) + " images")

    # Tag the line so the validation check can see this came from the snapshot.
    parts.append("(from event snapshot)")
    return " | ".join(parts)


def _serve_progress_from_snapshot(snap):
    if not isinstance(snap, dict):
        return None
    result = snap.get("progress")
    if not isinstance(result, dict):
        return None
    active = result.get("active", False)
    status = result.get("status", "")
    percent = result.get("percent", 0)
    if active:
        return "Progress bar: " + str(percent) + "% | Status: " + str(status)
    if status and not str(status).startswith("(Fiji Is Just)"):
        return "Status: " + str(status)
    return None


def _serve_log_from_snapshot(snap):
    if not isinstance(snap, dict):
        return None
    log = snap.get("log")
    if not isinstance(log, str) or not log.strip():
        return None
    lines = log.strip().split("\n")
    recent = lines[-10:] if len(lines) > 10 else lines
    return "IJ Log (last " + str(len(recent)) + " of " + str(len(lines)) + " lines):\n" + "\n".join(recent)


def _serve_dialogs_from_snapshot(snap):
    """Format dialogs from the snapshot, mirroring fiji_dialogs()."""
    if not isinstance(snap, dict):
        return None
    dialogs = snap.get("dialogs") or []
    if not isinstance(dialogs, list) or not dialogs:
        return None
    summaries = []
    for d in dialogs:
        if not isinstance(d, dict):
            continue
        dtype = d.get("type") or d.get("kind", "info")
        title = d.get("title", "")
        text = d.get("text", "")
        if isinstance(text, str) and len(text) > 200:
            text = text[:200] + "..."
        buttons = d.get("buttons") or []
        btn_str = " [" + "/".join(buttons) + "]" if buttons else ""
        summaries.append(str(dtype).upper() + ": \"" + str(title) + "\" — " + str(text) + btn_str)
    if not summaries:
        return None
    return "Open dialogs: " + " | ".join(summaries)


def run_subscriber_daemon():
    """Long-lived event subscriber. Writes a live Fiji-state snapshot to disk.

    This runs as a detached subprocess spawned at session start. It:
    - Takes an initial ``get_state`` snapshot so the first prompt has data.
    - Subscribes to ``*`` on the event bus.
    - Mirrors image/dialog/results/macro state into an in-memory dict.
    - Writes the dict to SNAPSHOT_FILE after every event (atomic rename).
    - Refreshes ``updated_ts`` every heartbeat so staleness checks pass.
    - Exits if its PID file is removed or it receives SIGTERM/KeyboardInterrupt.
    """
    snapshot_path = _snapshot_file()
    pid_path = _subscriber_pid_file()
    log_path = _subscriber_log_file()

    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
    except Exception:
        pass

    try:
        with open(log_path, "a") as f:
            f.write("[start pid={} port={} host={}] {}\n".format(
                os.getpid(), TCP_PORT, TCP_HOST, time.time()))
    except Exception:
        pass

    try:
        if os.path.exists(pid_path):
            with open(pid_path) as f:
                prev_pid = int(f.read().strip() or "0")
            if prev_pid > 0 and prev_pid != os.getpid() and _pid_alive(prev_pid):
                existing = _load_snapshot(snapshot_path)
                if (_snapshot_is_fresh(existing, max_age_seconds=45)
                        and _snapshot_matches_endpoint(existing)):
                    try:
                        with open(log_path, "a") as f:
                            f.write("[exit: prior daemon {} is healthy]\n".format(prev_pid))
                    except Exception:
                        pass
                    return
    except Exception:
        pass

    try:
        with open(pid_path, "w") as f:
            f.write(str(os.getpid()))
    except Exception:
        pass

    # Import after process-spawn so the subscriber has access to the helper.
    sys.path.insert(0, os.path.join(PROJECT_DIR, "agent"))
    try:
        from ij import imagej_events  # noqa: E402
    except Exception:
        try:
            if os.path.exists(pid_path):
                os.remove(pid_path)
        except Exception:
            pass
        return

    snapshot = {
        "tcp_host": TCP_HOST,
        "tcp_port": TCP_PORT,
        "state": None,
        "dialogs": [],
        "last_macro": None,
        "memory_pressure": None,
        "progress": None,
        "log": None,
        "updated_ts": 0,
    }

    def _persist():
        snapshot["updated_ts"] = int(time.time() * 1000)
        tmp = snapshot_path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(snapshot, f)
            os.replace(tmp, snapshot_path)
        except Exception:
            pass

    def _refresh_state():
        try:
            resp = tcp_send({"command": "get_state"})
            if resp.get("ok"):
                snapshot["state"] = resp.get("result")
        except Exception:
            pass

    def _refresh_dialogs():
        try:
            resp = tcp_send({"command": "get_dialogs"})
            if resp.get("ok"):
                result = resp.get("result", {})
                if isinstance(result, dict):
                    snapshot["dialogs"] = result.get("dialogs", [])
                elif isinstance(result, list):
                    snapshot["dialogs"] = result
        except Exception:
            pass

    def _refresh_progress():
        try:
            resp = tcp_send({"command": "get_progress"})
            if resp.get("ok"):
                snapshot["progress"] = resp.get("result", {})
        except Exception:
            pass

    def _refresh_log():
        try:
            resp = tcp_send({"command": "get_log"})
            if resp.get("ok"):
                snapshot["log"] = resp.get("result", "")
        except Exception:
            pass

    def _bootstrap_state():
        _refresh_state()
        _refresh_dialogs()
        _refresh_progress()
        _refresh_log()
        _persist()

    _bootstrap_state()

    try:
        for event in imagej_events(["*"], host=TCP_HOST, port=TCP_PORT,
                                   reconnect=True, reconnect_delay=2.0):
            if not isinstance(event, dict):
                continue
            topic = event.get("event", "")
            data = event.get("data", {}) or {}

            if topic == "heartbeat":
                _persist()
                continue
            if topic == "subscribed":
                snapshot["subscription"] = data
                _persist()
                continue
            if topic == "event_dropped":
                snapshot["last_event_dropped"] = data
                _refresh_state()
                _refresh_dialogs()
                _refresh_log()
                _persist()
                continue

            # Image changes invalidate the cached state — re-fetch.
            if topic.startswith("image.") or topic == "results.changed":
                _refresh_state()
            elif topic == "memory.pressure":
                snapshot["memory_pressure"] = data
                # Update memory sub-field of cached state if present.
                if isinstance(snapshot.get("state"), dict):
                    mem = snapshot["state"].setdefault("memory", {})
                    if "used_pct" in data:
                        mem["usagePercent"] = data.get("used_pct")
                    if "usedMB" in data:
                        mem["usedMB"] = data.get("usedMB")
                    if "maxMB" in data:
                        mem["maxMB"] = data.get("maxMB")
            elif topic == "dialog.appeared":
                existing_dlgs = snapshot.get("dialogs") or []
                dlg = {
                    "title": data.get("title", ""),
                    "kind": data.get("kind", "info"),
                    "type": data.get("kind", "info"),
                    "text": "",
                    "buttons": [],
                }
                new_list = [d for d in existing_dlgs
                            if isinstance(d, dict) and d.get("title") != dlg["title"]]
                new_list.append(dlg)
                snapshot["dialogs"] = new_list
                _refresh_log()
            elif topic == "dialog.closed":
                existing_dlgs = snapshot.get("dialogs") or []
                snapshot["dialogs"] = [
                    d for d in existing_dlgs
                    if isinstance(d, dict) and d.get("title") != data.get("title", "")
                ]
            elif topic == "macro.started":
                snapshot["last_macro"] = {"state": "running", "started": data}
                snapshot["progress"] = {
                    "active": True,
                    "status": "Macro running",
                    "percent": 0,
                }
            elif topic == "macro.completed":
                snapshot["last_macro"] = {"state": "completed", "completed": data}
                status = "Macro completed"
                if not data.get("success", True):
                    status = "Macro failed"
                    if data.get("error"):
                        status += ": " + str(data.get("error"))[:160]
                snapshot["progress"] = {
                    "active": False,
                    "status": status,
                    "percent": 100 if data.get("success", True) else 0,
                }
                _refresh_log()
                _refresh_state()
                _refresh_dialogs()

            _persist()
    except (KeyboardInterrupt, SystemExit):
        return
    except Exception:
        return
    finally:
        try:
            if os.path.exists(pid_path):
                with open(pid_path) as f:
                    owner = f.read().strip()
                if owner == str(os.getpid()):
                    os.remove(pid_path)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Session-start context items
# ---------------------------------------------------------------------------

def fiji_connection_status(available=None):
    """Check if Fiji is running and TCP server is responding."""
    if fiji_available():
        return "Fiji: connected (TCP 7746)"
    else:
        return "Fiji: NOT connected — TCP server on port 7746 is not responding"


def fiji_connection_status_current(available=None):
    """Check if Fiji is running and TCP server is responding."""
    if available is None:
        available = fiji_available()
    if available:
        return "Fiji: connected (TCP " + str(TCP_PORT) + ")"
    return "Fiji: NOT connected - TCP server on port " + str(TCP_PORT) + " is not responding"


fiji_connection_status = fiji_connection_status_current


def installed_plugins():
    """Get list of installed Fiji commands/plugins via macro."""
    try:
        resp = tcp_send({
            "command": "execute_macro",
            "code": 'list = getList("commands"); result = ""; for (i = 0; i < list.length; i++) result += list[i] + "\\n"; return result;'
        })
        if resp.get("ok") and resp.get("result", {}).get("success"):
            output = resp["result"].get("output", "")
            commands = [c.strip() for c in output.strip().split("\n") if c.strip()]
            if commands:
                return "Installed Fiji commands (" + str(len(commands)) + "): " + ", ".join(commands)
            return "Fiji commands: (empty list returned)"
        return "Fiji commands: could not retrieve — " + resp.get("result", {}).get("error", "unknown error")
    except Exception as e:
        return "Fiji commands: unavailable — " + str(e)


def known_friction():
    """Phase 6: surface recurring failure patterns at session start so Claude
    sees "you keep failing at X" in the very next conversation."""
    try:
        resp = tcp_send({"command": "get_friction_patterns"})
        if not resp.get("ok"):
            return None
        patterns = resp.get("result", {}).get("patterns", [])
        if not patterns:
            return None
        lines = ["[KNOWN FRICTION] (recurring failures detected by the plugin — "
                 "fix the tool or change approach, don't just retry)"]
        for p in patterns:
            lines.append("  {}x {}: {}".format(
                p.get("count", 0),
                p.get("command", ""),
                (p.get("sample") or "")[:200]))
        return "\n".join(lines)
    except (socket.error, OSError):
        return None
    except Exception:
        return None


def reference_docs():
    """List available reference documents in agent/references/."""
    try:
        if not os.path.isdir(REFS_DIR):
            return "Reference docs: directory not found"
        files = [f.replace("-reference.md", "").replace(".md", "")
                 for f in sorted(os.listdir(REFS_DIR))
                 if f.endswith(".md") and f != "INDEX.md"]
        if files:
            return "Reference docs (" + str(len(files)) + "): " + ", ".join(files)
        return "Reference docs: none found"
    except Exception as e:
        return "Reference docs: error — " + str(e)


# ---------------------------------------------------------------------------
# Every-message context items
# ---------------------------------------------------------------------------

def _render_timeout_dialog_fallback(resp, prefix):
    """When ``tcp_send`` synthesised a timeout response, surface any dialog
    list it attached so the user can see what's likely blocking the EDT.
    Returns the rendered string or ``None`` if the response isn't a timeout
    fallback or has no dialogs to show.
    """
    if not isinstance(resp, dict):
        return None
    if resp.get("ok"):
        return None
    dialogs = resp.get("dialogs")
    if not isinstance(dialogs, list) or not dialogs:
        return None
    summaries = []
    for d in dialogs:
        if not isinstance(d, dict):
            continue
        dtype = str(d.get("type", "info"))
        title = str(d.get("title", ""))
        text = str(d.get("text", ""))
        if len(text) > 200:
            text = text[:200] + "..."
        buttons = d.get("buttons", [])
        btn_str = " [" + "/".join(buttons) + "]" if isinstance(buttons, list) and buttons else ""
        summaries.append(dtype.upper() + ": \"" + title + "\" — " + text + btn_str)
    if not summaries:
        return None
    err = str(resp.get("error", "TCP timeout"))
    return prefix + " (" + err + ") | Open dialogs: " + " | ".join(summaries)


def fiji_state():
    """Get open images, active image details, results table, and memory."""
    try:
        resp = tcp_send({"command": "get_state"})
        if not resp.get("ok"):
            # B9: if the failure was a timeout-with-dialogs fallback, surface
            # the blocking dialog in place of the generic error line.
            fallback = _render_timeout_dialog_fallback(resp, "Fiji state: blocked")
            if fallback is not None:
                return fallback
            return "Fiji state: error — " + resp.get("error", "unknown")

        result = resp["result"]
        parts = []

        # Active image
        active = result.get("activeImage")
        if active:
            dims = active.get("width", "?") + "x" + active.get("height", "?") if isinstance(active.get("width"), str) else \
                   str(active.get("width", "?")) + "x" + str(active.get("height", "?"))
            slices = active.get("nSlices", active.get("slices", 1))
            channels = active.get("nChannels", active.get("channels", 1))
            frames = active.get("nFrames", active.get("frames", 1))
            stack_info = ""
            if slices > 1 or channels > 1 or frames > 1:
                stack_info = " [C=" + str(channels) + " Z=" + str(slices) + " T=" + str(frames) + "]"
            cal = active.get("calibration", {})
            cal_info = ""
            if isinstance(cal, dict):
                if cal and cal.get("unit") and cal.get("unit") != "pixel":
                    cal_info = " cal=" + str(cal.get("pixelWidth", "?")) + " " + cal.get("unit", "")
            elif cal:
                cal_info = " cal=" + str(cal)
            bit_depth = active.get("bitDepth", active.get("type", "?"))
            if isinstance(bit_depth, (int, float)):
                bit_depth = str(int(bit_depth)) + "-bit"
            else:
                bit_depth = str(bit_depth)
                if bit_depth and "bit" not in bit_depth.lower():
                    bit_depth += "-bit"
            roi = active.get("roi")
            roi_info = ""
            if roi and roi != "None":
                roi_info = " ROI=" + str(roi)
            parts.append("Active: \"" + str(active.get("title", "?")) + "\" " +
                         dims + " " + str(bit_depth) + stack_info + cal_info + roi_info)
        else:
            parts.append("Active: none")

        # All images
        all_imgs = result.get("allImages", [])
        if len(all_imgs) > 1:
            titles = [str(img.get("title", "?")) for img in all_imgs]
            parts.append("Open images (" + str(len(titles)) + "): " + ", ".join(titles))
        elif len(all_imgs) == 1:
            parts.append("Open images: 1")
        else:
            parts.append("Open images: 0")

        # Results table
        csv = result.get("resultsTable", "")
        if csv and csv.strip():
            lines = csv.strip().split("\n")
            if lines:
                header = lines[0]
                cols = [c.strip() for c in header.split(",") if c.strip()]
                row_count = len(lines) - 1
                parts.append("Results table: " + str(row_count) + " rows, columns=[" + ", ".join(cols) + "]")
        else:
            parts.append("Results table: empty")

        # Memory
        mem = result.get("memory", {})
        if mem:
            used = mem.get("usedMB", "?")
            max_mem = mem.get("maxMB", "?")
            free = mem.get("freeMB", "?")
            img_count = mem.get("openImageCount", "?")
            parts.append("JVM memory: " + str(used) + "/" + str(max_mem) + " MB used, " +
                         str(free) + " MB free, " + str(img_count) + " images")

        return " | ".join(parts)
    except socket.error:
        return "Fiji state: not connected"
    except Exception as e:
        return "Fiji state: error — " + str(e)


def fiji_dialogs():
    """Get any open dialogs (errors, prompts, warnings)."""
    try:
        resp = tcp_send({"command": "get_dialogs"})
        if not resp.get("ok"):
            # B9: timeout-fallback response may have its own dialogs array.
            # If so, render those instead of giving up silently.
            fallback_dialogs = resp.get("dialogs")
            if isinstance(fallback_dialogs, list) and fallback_dialogs:
                dialogs = fallback_dialogs
            else:
                return None
        else:
            dialogs = resp.get("result", {}).get("dialogs", [])
        if not dialogs:
            return None  # No dialogs = nothing to report

        summaries = []
        for d in dialogs:
            dtype = d.get("type", "info")
            title = d.get("title", "")
            text = d.get("text", "")
            # Truncate long dialog text
            if len(text) > 200:
                text = text[:200] + "..."
            buttons = d.get("buttons", [])
            btn_str = " [" + "/".join(buttons) + "]" if buttons else ""
            summaries.append(dtype.upper() + ": \"" + title + "\" — " + text + btn_str)

        return "Open dialogs: " + " | ".join(summaries)
    except socket.error:
        return None
    except Exception:
        return None


def fiji_progress():
    """Get Fiji progress bar status and status line text."""
    try:
        resp = tcp_send({"command": "get_progress"})
        if not resp.get("ok"):
            return None

        result = resp.get("result", {})
        active = result.get("active", False)
        status = result.get("status", "")
        percent = result.get("percent", 0)

        if active:
            return "Progress bar: " + str(percent) + "% | Status: " + status
        elif status and not status.startswith("(Fiji Is Just)"):
            return "Status: " + status
        return None
    except socket.error:
        return None
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Phase 5: intent-first probe (safe, suggestion-only)
# ---------------------------------------------------------------------------
#
# The hook receives the raw user prompt on stdin. If the prompt matches a
# taught intent-router mapping, inject a [INTENT MATCH] hint so Claude knows
# to call `python ij.py intent "<phrase>"` instead of planning a macro.
#
# We deliberately do NOT auto-execute from the hook: Fiji state changes
# triggered by a context hook are invisible to the conversation history and
# hard to reason about. Execution stays an explicit client-side action.
# To change that, call `intent()` here and inject `[INTENT EXECUTED: ...]`
# like the spec suggests — but be aware of the footgun.
#
# Matching happens locally against a cached `intent_list` so the hook stays
# fast even on large dictionaries.

import re as _re


def _intent_list_cached():
    try:
        resp = tcp_send({"command": "intent_list"})
    except Exception:
        return []
    if not isinstance(resp, dict) or not resp.get("ok"):
        return []
    result = resp.get("result", {}) or {}
    return result.get("mappings", []) or []


def fiji_intent_hint(prompt_text):
    """Return a one-line intent hint if the prompt matches a taught mapping.

    Returns ``None`` when there's no match, the prompt is too long to
    plausibly be a shorthand phrase, or Fiji is unreachable. Never raises.
    """
    if not prompt_text:
        return None
    phrase = prompt_text.strip()
    # Longer prompts are almost certainly natural-language plans, not
    # shorthand the user has taught. 120 chars is a generous cap.
    if not phrase or len(phrase) > 120:
        return None
    mappings = _intent_list_cached()
    if not mappings:
        return None
    for m in mappings:
        pat = m.get("pattern", "")
        if not pat:
            continue
        try:
            if _re.match(pat, phrase, _re.IGNORECASE) and _re.fullmatch(pat, phrase, _re.IGNORECASE):
                desc = m.get("description") or "(no description)"
                return ("[INTENT MATCH] phrase matches taught mapping "
                        "{!r} — {}. Run with: python ij.py intent \"{}\"".format(
                            pat, desc, phrase.replace('"', '\\"')))
        except _re.error:
            continue
    return None


def fiji_log():
    """Get the IJ log window contents (last few lines)."""
    try:
        resp = tcp_send({"command": "get_log"})
        if not resp.get("ok"):
            # B9: timeout-fallback response may carry dialogs; surface them
            # as a log entry so the user can see what's blocking the EDT
            # even though the actual log is unreachable.
            fallback = _render_timeout_dialog_fallback(resp, "IJ Log: unavailable")
            if fallback is not None:
                return fallback
            return None

        log = resp.get("result", "")
        if not log or not log.strip():
            return None

        lines = log.strip().split("\n")
        # Only show last 10 lines to keep it concise
        recent = lines[-10:] if len(lines) > 10 else lines
        return "IJ Log (last " + str(len(recent)) + " of " + str(len(lines)) + " lines):\n" + "\n".join(recent)
    except socket.error:
        return None
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--timing", choices=["session-start", "every-message"], required=False)
    parser.add_argument("--subscriber-daemon", action="store_true",
                        help=argparse.SUPPRESS)
    args = parser.parse_args()

    # Phase 2: subscriber daemon mode — long-lived, never returns under normal
    # operation. Spawned detached from session-start.
    if args.subscriber_daemon:
        run_subscriber_daemon()
        return

    if not args.timing:
        parser.error("--timing is required when not running the subscriber daemon")

    # UserPromptSubmit hook passes {"session_id": ..., "prompt": "...", ...}
    # on stdin. Capture the prompt for the Phase 5 intent-hint probe; other
    # timings ignore it. Never raises — a missing/invalid payload just means
    # no prompt to probe.
    _prompt_text = None
    try:
        _stdin_raw = sys.stdin.read()
        if _stdin_raw:
            try:
                _stdin_obj = json.loads(_stdin_raw)
                if isinstance(_stdin_obj, dict):
                    p = _stdin_obj.get("prompt")
                    if isinstance(p, str):
                        _prompt_text = p
            except ValueError:
                pass
    except Exception:
        pass

    context_parts = []

    if args.timing == "session-start":
        available = fiji_available()
        context_parts.append(fiji_connection_status_current(available))
        if available:
            context_parts.append(installed_plugins())
            friction = known_friction()
            if friction:
                context_parts.append(friction)
            # Phase 2: fire up the background subscriber so later prompts can
            # serve state from its snapshot instead of polling TCP.
            _ensure_subscriber_running()
        context_parts.append(reference_docs())

    elif args.timing == "every-message":
        snap = _load_snapshot()
        snap_fresh = (_snapshot_is_fresh(snap)
                      and _snapshot_matches_endpoint(snap))
        if not snap_fresh and not fiji_available():
            context_parts.append("Fiji: not connected")
        else:

            served_from_snapshot = False
            if snap_fresh:
                try:
                    state_line = _serve_state_from_snapshot(snap)
                except Exception:
                    state_line = None
                if state_line:
                    context_parts.append(state_line)
                    served_from_snapshot = True

            if not served_from_snapshot:
                # Subscription dropped or snapshot stale — poll.
                context_parts.append(fiji_state())
                # Opportunistically try to (re)start the subscriber so subsequent
                # prompts go back to the zero-polling path.
                _ensure_subscriber_running()

            progress = None
            if snap_fresh:
                progress = _serve_progress_from_snapshot(snap)
            if progress is None and not served_from_snapshot:
                progress = fiji_progress()
            if progress:
                context_parts.append(progress)

            # Dialogs: prefer the snapshot when fresh; fall back to TCP poll.
            dialog_line = None
            if snap_fresh:
                dialog_line = _serve_dialogs_from_snapshot(snap)
            if dialog_line is None and not served_from_snapshot:
                dialog_line = fiji_dialogs()
            if dialog_line:
                context_parts.append(dialog_line)

            log = None
            if snap_fresh:
                log = _serve_log_from_snapshot(snap)
            if log is None and not served_from_snapshot:
                log = fiji_log()
            if log:
                context_parts.append(log)

            # Phase 5: surface a taught-mapping hint if the prompt matches.
            try:
                hint = fiji_intent_hint(_prompt_text)
                if hint:
                    context_parts.append(hint)
            except Exception:
                pass

    # Persist cache updates (Phase 1: hash dedup across subprocess invocations).
    if _CACHE_DIRTY:
        _save_cache(_CACHE)

    # Skip output entirely if nothing to report
    if not context_parts:
        print(json.dumps({}))
        return

    msg = "\n".join(context_parts)

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "SessionStart" if args.timing == "session-start" else "UserPromptSubmit",
            "additionalContext": msg
        }
    }))


if __name__ == "__main__":
    main()
