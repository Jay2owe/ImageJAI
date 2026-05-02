#!/usr/bin/env python3
"""
Interactive Ollama chat with tool calling.

Usage:
    python -m agent_console.ollama.ollama_chat
    python -m agent_console.ollama.ollama_chat --model gemma4:31b-cloud
    python -m agent_console.ollama.ollama_chat --launcher claude --model gemma4:31b-cloud
    python -m agent_console.ollama.ollama_chat --launcher codex --model gemma4:31b-cloud
    python -m agent_console.ollama.ollama_chat --tools
    python -m agent_console.ollama.ollama_chat --status
    python -m agent_console.ollama.ollama_chat --setup

Type naturally. Tools execute automatically. Ctrl+C to quit.
"""

import argparse
import atexit
import json
import re
import signal
import socket
import subprocess
import sys
import os
import shutil
import threading
import time
from pathlib import Path

import ollama

try:
    from prompt_toolkit import prompt as _pt_prompt
    from prompt_toolkit.completion import WordCompleter
    from prompt_toolkit.formatted_text import ANSI
    from prompt_toolkit.history import FileHistory
except ImportError:  # pragma: no cover - optional dependency
    _pt_prompt = None
    WordCompleter = None
    ANSI = None
    FileHistory = None

# Fix Windows console encoding for Unicode characters
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    # Enable ANSI escape codes on Windows 10+
    os.system("")

SCRIPT_DIR = Path(__file__).resolve().parent


def _find_bridge_commander_dir() -> Path | None:
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "Bridge" / "commander"
        if candidate.exists():
            return candidate
    return None


def _default_data_dir() -> Path:
    if os.name == "nt":
        base = os.environ.get("APPDATA", os.path.expanduser("~"))
    else:
        base = os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config"))
    return Path(base) / "agent-console" / "ollama"


def _normalize_launcher(value: str | None) -> str:
    launcher = str(value or "wrapper").strip().lower() or "wrapper"
    if launcher not in {"wrapper", "claude", "codex"}:
        raise ValueError(f"unsupported launcher: {launcher}")
    return launcher


def _launch_via_ollama_surface(launcher: str, model: str) -> int:
    launcher = _normalize_launcher(launcher)
    if launcher == "wrapper":
        return 0
    cmd = ["ollama", "launch", launcher, "--model", model]
    return subprocess.call(cmd)


COMMANDER_DIR = _find_bridge_commander_dir()
DATA_DIR = _default_data_dir()
_NO_WINDOW = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
IOT_SCRIPT = str(COMMANDER_DIR / "iot") if COMMANDER_DIR is not None else ""
PROMPT_HISTORY_PATH = DATA_DIR / "history.txt"
BUNDLED_COMMANDS_DIR = SCRIPT_DIR / "commands"

_SLASH_COMMANDS: tuple[tuple[str, str], ...] = (
    ("/help", "Show available slash commands."),
    ("/clear", "Reset the conversation history."),
    ("/compact", "Replace the chat history with a summary."),
    ("/prompt <name>", "Load and submit a prompt template."),
    ("/macro <name>", "Alias for /prompt."),
    ("/save <file>", "Save the last assistant response to a file."),
    ("/copy", "Copy the last assistant response to the clipboard."),
    ("/tools", "List built-in and learned tools."),
    ("/forget", "Remove a learned tool."),
    ("/status", "Show Ollama and model status."),
    ("/setup", "Create the local commander model."),
    ("/resume [new_ceiling]", "Resume from a budget-ceiling pause (06 §6)."),
    ("/switch <model>", "Drop to a free model after a budget-ceiling pause."),
)
_SLASH_COMMAND_NAMES = [item[0].split()[0] for item in _SLASH_COMMANDS]
_COMPACT_SUMMARY_PROMPT = (
    "Summarize this conversation for continued work. Keep the user's goals, facts, "
    "decisions, file paths, commands, outputs, and unfinished tasks. Stay concise "
    "and concrete. Do not add new facts."
)

MODEL_NAME = os.environ.get("OLLAMA_COMMANDER_MODEL", "commander-gemma4")
BASE_MODEL = os.environ.get("OLLAMA_BASE_MODEL", "gemma4:e4b")
CLOUD_MODEL = os.environ.get("OLLAMA_CLOUD_MODEL", "gemma4:31b-cloud")
_CLOUD_SUFFIX = "-cloud"

# Known cloud-model context windows. Ollama's cloud proxy does not forward
# model_info / parameters from the upstream host, so ollama.show() returns
# nulls for cloud variants. _get_ctx_limit() consults this map as a
# last-resort fallback so the context bar reports the real window instead
# of the 8192 default. See AgentConsole
# docs/friction/ollama-cloud-show-returns-null-params-fixed.md.
_CLOUD_CTX_LIMITS: dict[str, int] = {
    "gemma4:31b-cloud": 131072,
}


def _is_cloud_model(model: str) -> bool:
    """True if the model name is an Ollama cloud variant (e.g. gemma4:31b-cloud)."""
    return bool(model) and model.endswith(_CLOUD_SUFFIX)

# ── TCP helpers ──────────────────────────────────────────────────────────

def _tcp(port: int, cmd: str, timeout: float = 5) -> str:
    """Send a line to a local TCP service. Returns raw response, or an
    explicit marker if the service accepted the command silently."""
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall(f"{cmd}\n".encode())
            chunks = []
            while True:
                try:
                    data = s.recv(8192)
                    if not data:
                        break
                    chunks.append(data.decode(errors="replace"))
                except socket.timeout:
                    break
            reply = "".join(chunks).strip()
            if reply:
                return reply
            return f"ACCEPTED_NO_REPLY (port {port}) — service accepted '{cmd[:60]}' but returned no data."
    except (ConnectionRefusedError, OSError) as e:
        return f"ERROR: service on port {port} not reachable ({e})"


def _load_ac_token() -> str:
    """Load AgentConsole TCP auth token."""
    paths = [
        Path(os.environ.get("APPDATA", "")) / "agent-console" / "config" / "tcp_auth_token.txt",
        Path.home() / ".config" / "agent-console" / "tcp_auth_token.txt",
    ]
    for p in paths:
        try:
            return p.read_text().strip()
        except (OSError, FileNotFoundError):
            continue
    return ""


def _ac_tcp(cmd: str, timeout: float = 15) -> str:
    """Send authenticated command to AgentConsole (port 7745)."""
    token = _load_ac_token()
    try:
        with socket.create_connection(("127.0.0.1", 7745), timeout=timeout) as s:
            if token:
                s.sendall(f"{token}\n".encode())
            s.sendall(f"{cmd}\n".encode())
            chunks = []
            while True:
                try:
                    data = s.recv(65536)
                    if not data:
                        break
                    chunks.append(data)
                    if b"\n" in b"".join(chunks):
                        break
                except socket.timeout:
                    break
            raw = b"".join(chunks).decode("utf-8", errors="replace").strip()
            try:
                return json.loads(raw).get("result", raw)
            except (json.JSONDecodeError, ValueError):
                return raw
    except (ConnectionRefusedError, OSError) as e:
        return f"ERROR: AgentConsole not reachable ({e})"

# ── Tool definitions ─────────────────────────────────────────────────────
# Each function's signature + docstring becomes the tool schema automatically.

def trigger_scene(scene_name: str) -> str:
    """Trigger a home automation scene.
    Args:
        scene_name: One of: morning, bedtime, focus, movie, leaving,
                    arriving, all_off, kettle, desk, alert_door,
                    arriving_quiet, arriving_comfort, night_path, wake_pc
    """
    reply = _tcp(7751, f"scene {scene_name}")
    if reply.startswith("ACCEPTED_NO_REPLY"):
        return f"SCENE_TRIGGERED: '{scene_name}' dispatched to scene engine (no reply text)."
    return reply


def control_device(device_name: str, action: str) -> str:
    """Control a smart home device (light, plug, TV, etc.).
    Args:
        device_name: Device name, e.g. kettle-plug, desk-plug, charger-plug,
                     yeelight, colour-bulb, ir-blaster, midea, hive
        action: Action to perform, e.g. on, off, toggle, status,
                brightness 50, volume 30, ir_send vol_up
    """
    if not IOT_SCRIPT:
        return "ERROR: Bridge commander iot script not found"
    cmd = [sys.executable, IOT_SCRIPT, device_name] + action.split()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           creationflags=_NO_WINDOW, timeout=15)
        out = (r.stdout or r.stderr).strip()
        if out:
            return out
        # iot scripts print nothing on success — be explicit.
        return (
            f"ACCEPTED (exit {r.returncode}): sent '{action}' to {device_name}. "
            f"No confirmation text — call control_device('{device_name}', 'status') to verify."
        )
    except Exception as e:
        return f"ERROR: {type(e).__name__}: {e}"


def get_house_status(query: str) -> str:
    """Get house state, presence, or device health.
    Args:
        query: One of: status, state, devices, history
    """
    reply = _tcp(7750, query)
    if reply.startswith("ACCEPTED_NO_REPLY"):
        return f"NO_DATA: network monitor accepted query '{query}' but returned nothing."
    return reply


# ── Bus subscription helpers ─────────────────────────────────────────────

_SID_HEX_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f-]+)?$", re.IGNORECASE)
# Matches lines like "  #1 [+] agent-2 (abcc58ef)" — name is group 1, sid in parens is group 2.
_LIST_LINE_RE = re.compile(
    r"^\s*#\d+\s*(?:\[[^\]]*\])?\s*(\S.*?)\s*\(([0-9a-f-]{6,})\)\s*$",
    re.IGNORECASE,
)


def _looks_like_sid(value: str) -> bool:
    """True only if the string is plausibly a raw session ID (UUID-ish hex).
    Display names like 'agent-2', 'claude-1', 'jon' must NOT match."""
    return bool(_SID_HEX_RE.match(value or ""))


def _iter_list_entries() -> list[tuple[str, str]]:
    """Parse the orchestrator `list` output into (name, sid) tuples.

    The live format is e.g. `  #1 [+] agent-2 (abcc58ef)` — the leading
    `#N` is a display index, NOT a session ID. The real SID is the hex
    in parentheses at end-of-line.
    """
    entries: list[tuple[str, str]] = []
    try:
        result = _ac_tcp("list")
    except Exception:
        return entries
    for line in result.split("\n"):
        m = _LIST_LINE_RE.match(line)
        if not m:
            continue
        name = m.group(1).strip()
        sid = m.group(2).strip()
        if name and sid:
            entries.append((name, sid))
    return entries


def _resolve_worker_sid(name_or_id: str) -> str:
    """Resolve a worker name to its session ID via 'list' command.

    Always tries the name lookup first. Only treats the input as a raw SID
    if it matches a strict hex/UUID shape AND the name lookup failed — so
    display names like 'agent-2' or 'claude-1' can never short-circuit.
    """
    if not name_or_id:
        return ""
    target = name_or_id.lower()
    # 1. Exact name match first, then substring, then sid-prefix match.
    entries = _iter_list_entries()
    for name, sid in entries:
        if name.lower() == target:
            return sid
    for name, sid in entries:
        if target in name.lower():
            return sid
    for name, sid in entries:
        if sid.lower().startswith(target) and len(target) >= 6:
            return sid
    # 2. Last resort: accept only if it actually LOOKS like a SID.
    if _looks_like_sid(name_or_id):
        return name_or_id
    return ""


_NON_WORKER_TOKENS = {
    "watch", "when", "any", "notify", "alert", "tell", "me", "for",
    "on", "of", "to", "the", "a", "an", "is", "are", "goes", "going",
    "finishes", "finished", "done", "idle", "busy", "working", "active",
    "status", "activity", "lifecycle", "presence", "reactive", "failure",
    "failed", "event", "events", "changes", "change",
    "subscribe", "watching", "updates", "update",
}


def _parse_subscribe_description(description: str) -> dict:
    """Parse natural-language subscription description into exec parameters."""
    desc = description.lower().strip()
    result = {"topics": [], "worker": "", "prompt_template": ""}

    # Pick the first word that isn't a trigger verb / intent token.
    for tok in re.findall(r"\b([a-zA-Z0-9_-]+)\b", desc):
        if tok in _NON_WORKER_TOKENS:
            continue
        result["worker"] = tok
        break

    if "reactive" in desc and "fail" in desc:
        result["topics"] = ["event.reactive.failed"]
        result["prompt_template"] = "Reactive failure: {payload.reason}"
    elif "presence" in desc:
        result["topics"] = [f"agent.<{result['worker']}>.presence"] if result["worker"] else ["agent.*.presence"]
        result["prompt_template"] = "Agent {payload.status}."
    elif "lifecycle" in desc:
        result["topics"] = [f"agent.<{result['worker']}>.lifecycle"] if result["worker"] else ["agent.*.lifecycle"]
    elif "activity" in desc:
        result["topics"] = [f"agent.<{result['worker']}>.activity"] if result["worker"] else ["agent.*.activity"]
        result["prompt_template"] = "{payload.description}"
    elif "status" in desc or "idle" in desc or "working" in desc or "finish" in desc or "done" in desc:
        result["topics"] = [f"agent.<{result['worker']}>.status"] if result["worker"] else ["agent.*.status"]
        if "idle" in desc or "finish" in desc or "done" in desc:
            result["prompt_template"] = "Worker is idle. Check output and decide."
        elif "working" in desc or "busy" in desc:
            result["prompt_template"] = "Worker is working on: {payload.activity}"
        else:
            result["prompt_template"] = "Worker {payload.status}. Read output and decide next step."
    elif "any" in desc and "fail" in desc:
        result["topics"] = ["event.reactive.failed"]
        result["prompt_template"] = "Failure: {payload.reason}"
    elif "event" in desc:
        result["topics"] = ["event.*"]

    if not result["topics"]:
        result["topics"] = ["event.*"]
    return result


_SELF_ALIASES = {"self", "me", "myself", "i"}
_COMMANDER_ALIASES = {"commander", "ai-commander", "__ai_commander__", "ac"}

# Cached self-sid so we only run the PID-discovery exec once per process.
_SELF_SID_CACHE: dict[str, str] = {}


def _discover_self_sid_via_pid() -> str:
    """Find our own AgentConsole session ID by matching process IDs.

    Used when AC_SESSION_ID isn't in env (e.g. the adapter change hasn't
    propagated yet, or the gemma process was spawned via a tool other than
    AgentConsole's normal start path). Walks our own PID and its parents
    against `manager.sessions[*].pid` inside AgentConsole's exec namespace.
    Returns "" if no match.
    """
    if "sid" in _SELF_SID_CACHE:
        return _SELF_SID_CACHE["sid"]

    # Collect our own PID plus a handful of ancestor PIDs. psutil is
    # optional — if unavailable, fall back to getpid + getppid only.
    candidate_pids: list[int] = [os.getpid()]
    try:
        candidate_pids.append(os.getppid())
    except Exception:
        pass
    try:
        import psutil  # type: ignore
        try:
            p = psutil.Process()
            for _ in range(4):
                parent = p.parent()
                if parent is None:
                    break
                candidate_pids.append(parent.pid)
                p = parent
        except Exception:
            pass
    except ImportError:
        pass

    pids_literal = "(" + ",".join(str(p) for p in candidate_pids) + ",)"
    exec_code = (
        f"_pids = {pids_literal}; "
        f"_match = next((sid for sid, s in manager.sessions.items() "
        f"if getattr(s, 'pid', None) in _pids), ''); "
        f"print('SELF_SID:', _match)"
    )
    raw = _ac_tcp(f"exec {exec_code}")
    sid = ""
    if "SELF_SID:" in raw:
        tail = raw.split("SELF_SID:", 1)[1].strip()
        # `print` emits a trailing newline; strip whitespace and any trailing
        # shell-framing characters. The value is a bare sid string (no quotes).
        sid = tail.split()[0] if tail.split() else ""
        if sid == "''" or sid == '""':
            sid = ""
    _SELF_SID_CACHE["sid"] = sid
    return sid


def _resolve_subscriber_sid(notify: str) -> tuple[str, str]:
    """Resolve who should RECEIVE the subscription events.

    Returns (sid, label). `sid` may be an empty string if unresolved;
    `label` is a human-readable fallback hint for error messages.

    Special values:
      ""                         -> commander (default)
      "self" / "me" / "myself"   -> this agent's own session (env var first,
                                     then PID-match fallback)
      "commander" / "ai-commander" -> commander.session_id (handled by exec
                                     namespace, so sid="" signals "use commander")
      anything else              -> looked up via the AgentConsole `list` command
    """
    key = (notify or "").strip().lower()
    if not key or key in _COMMANDER_ALIASES:
        return "", "commander"
    if key in _SELF_ALIASES:
        # 1. Fast path: adapter-injected env var.
        own = os.environ.get("AC_SESSION_ID", "").strip()
        if own:
            return own, "self"
        # 2. Fallback: ask AgentConsole to match our PID against its sessions.
        own = _discover_self_sid_via_pid()
        if own:
            return own, "self (via PID match)"
        return "", "self (could not auto-resolve — no AC_SESSION_ID and PID match failed)"
    # Try literal lookup.
    sid = _resolve_worker_sid(notify)
    return sid, notify


def _setup_bus_subscription(description: str, notify: str = "") -> str:
    """Set up a bus subscription.

    Args:
        description: What to watch (topic + template parsed from plain English).
        notify: Who should be notified when the event fires.
                "" or "commander" -> AI Commander (default).
                "self" / "me"     -> this agent (requires AC_SESSION_ID env var).
                "<agent-name>"    -> any other agent, resolved via `list`.
    """
    parsed = _parse_subscribe_description(description)
    worker = parsed.get("worker", "")
    topics = parsed.get("topics", ["event.*"])
    prompt_template = parsed.get("prompt_template", "")

    if worker:
        sid = _resolve_worker_sid(worker)
        if not sid:
            return f"ERROR: Worker '{worker}' not found. Try: agent_command('list')"
        topics = [t.replace(f"<{worker}>", sid) for t in topics]

    subscriber_sid, subscriber_label = _resolve_subscriber_sid(notify)
    if notify and not subscriber_sid and subscriber_label not in ("commander",):
        return (
            f"ERROR: Subscriber '{notify}' could not be resolved "
            f"({subscriber_label}). Try: agent_command('list')."
        )

    # subscriber_sid="" means "use the commander namespace binding"
    session_expr = repr(subscriber_sid) if subscriber_sid else "commander.session_id"

    escaped_topics = json.dumps(topics)
    escaped_prompt = json.dumps(prompt_template)

    # IMPORTANT: Do NOT repr() the exec code. The orchestrator's exec handler
    # slices the string after "exec " and passes it straight to Python's exec().
    # Wrapping it in quotes would make Python evaluate a string literal and
    # silently no-op — which is exactly the bug the previous version shipped.
    exec_code = (
        f"_res = self._session_bus_registrar.subscribe_session("
        f"{session_expr}, "
        f"topics={escaped_topics}, "
        f"forward_system_events=True, "
        f"submit_system_events=True, "
        f"prompt_template={escaped_prompt}, "
        f"submit_prompt=True"
        f"); "
        f"print('SUBSCRIBE_RESULT:', repr(_res))"
    )
    raw = _ac_tcp(f"exec {exec_code}")
    label = subscriber_label if subscriber_label else "commander"
    # Be honest about what the bus registrar actually returned.
    if "SUBSCRIBE_RESULT:" not in raw:
        return (
            f"WARN (notifies: {label}) — exec ran but no subscribe result was "
            f"captured. Raw: {raw[:300]}"
        )
    return f"SUBSCRIBED (notifies: {label}, topics: {topics}) — {raw}"


_SUBSCRIBE_VERBS = ("subscribe", "watch ", "notify ", "alert when", "tell me when")


def agent_command(command: str) -> str:
    """Send a one-shot command to the AgentConsole orchestrator.
    Use this for immediate actions that return a result now.
    Do NOT use this for watching/subscribing to events — use agent_subscribe instead.
    Args:
        command: Orchestrator command, e.g. "list", "status", "cost",
                 "spawn 1 claude", "kill agent-1", "send 'hello' to agent-1".
    """
    # Safety net: if the model fed a subscribe-shaped phrase here, route it.
    lowered = command.lower().strip()
    if any(lowered.startswith(v) for v in _SUBSCRIBE_VERBS):
        return _setup_bus_subscription(command)
    return _ac_tcp(command)


def agent_subscribe(description: str, notify: str = "") -> str:
    """Subscribe an agent to AgentConsole bus events (idle, status, failures, activity).
    Fires a notification LATER when the matching event occurs.
    Does NOT return live data now — use agent_command for that.
    Args:
        description: What to watch, e.g. "when codex goes idle",
                     "watch jon activity", "any reactive failure",
                     "presence changes for claude-2", "when agent-1 finishes".
        notify: WHO gets the notification. Defaults to the AI Commander.
                Use "self" (or "me") to subscribe yourself.
                Use an agent name (e.g. "jon", "claude-2") to route the
                event-driven prompt to that agent instead.
                Examples:
                  agent_subscribe("when jon goes idle")                 # Commander gets pinged
                  agent_subscribe("when jon goes idle", "self")         # You get pinged
                  agent_subscribe("when jon goes idle", "claude-2")     # claude-2 gets pinged
    """
    return _setup_bus_subscription(description, notify=notify)


def agent_unsubscribe(who: str = "") -> str:
    """Clear ALL bus subscriptions for an agent.
    Use this to stop event notifications previously set up with agent_subscribe.
    Args:
        who: WHOSE subscriptions to clear. Defaults to the AI Commander.
             "self" / "me"       -> your own subscriptions.
             "commander"         -> the AI Commander (same as default).
             "<agent-name>"      -> any other agent, resolved via `list`.
    """
    sid, label = _resolve_subscriber_sid(who)
    if who and not sid and label != "commander":
        return (
            f"ERROR: Could not resolve '{who}' ({label}). "
            f"Try: agent_command('list')."
        )
    session_expr = repr(sid) if sid else "commander.session_id"
    # Passing topics=[] clears all subscriptions for that session.
    exec_code = (
        f"_res = self._session_bus_registrar.subscribe_session("
        f"{session_expr}, topics=[]); "
        f"print('UNSUBSCRIBE_RESULT:', repr(_res))"
    )
    raw = _ac_tcp(f"exec {exec_code}")
    if "UNSUBSCRIBE_RESULT:" not in raw:
        return f"WARN (target: {label}) — exec ran but no result captured. Raw: {raw[:300]}"
    return f"UNSUBSCRIBED (target: {label}) — {raw}"


def agent_list_subscriptions(who: str = "") -> str:
    """List an agent's active bus subscriptions.
    Use this to see what an agent is currently watching before deciding what to unsubscribe.
    Args:
        who: WHOSE subscriptions to show. Defaults to the AI Commander.
             "self" / "me"       -> your own subscriptions.
             "commander"         -> the AI Commander.
             "<agent-name>"      -> any other agent, resolved via `list`.
    """
    sid, label = _resolve_subscriber_sid(who)
    if who and not sid and label != "commander":
        return (
            f"ERROR: Could not resolve '{who}' ({label}). "
            f"Try: agent_command('list')."
        )
    session_expr = repr(sid) if sid else "commander.session_id"
    exec_code = (
        f"_topics = self._session_bus_registrar.get_subscriptions({session_expr}); "
        f"print('SUBS_RESULT:', repr(_topics))"
    )
    raw = _ac_tcp(f"exec {exec_code}")
    if "SUBS_RESULT:" not in raw:
        return f"WARN (target: {label}) — exec ran but no result captured. Raw: {raw[:300]}"
    return f"SUBSCRIPTIONS (target: {label}) — {raw}"


def tv_control(command: str) -> str:
    """Control the TV via IR blaster.
    Args:
        command: One of: on, off, vol_up, vol_down, mute, ch_up, ch_down,
                 source, up, down, left, right, ok, back, menu,
                 play, pause, stop
    """
    if not IOT_SCRIPT:
        return "ERROR: Bridge commander iot script not found"
    cmd = [sys.executable, IOT_SCRIPT, "ir-blaster", "ir_send", command]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           creationflags=_NO_WINDOW, timeout=15)
        out = (r.stdout or r.stderr).strip()
        if out:
            return out
        # IR blasts are fire-and-forget — say so explicitly.
        return f"IR_SENT (exit {r.returncode}): '{command}'. IR is one-way; no confirmation is possible."
    except Exception as e:
        return f"ERROR: {type(e).__name__}: {e}"


def wake_control(action: str) -> str:
    """Wake, sleep, or shut down the PC.
    Args:
        action: One of: wake, sleep, shutdown, status
    """
    reply = _tcp(7752, action)
    if reply.startswith("ACCEPTED_NO_REPLY"):
        return f"WAKE_ACTION_SENT: '{action}' dispatched; no confirmation text."
    return reply


# ── General-purpose tools ────────────────────────────────────────────────

def get_weather(location: str = "") -> str:
    """Get current weather for a location.
    Args:
        location: City name, e.g. London, Edinburgh, or empty for auto-detect
    """
    import urllib.request
    loc = location.strip().replace(" ", "+") if location else ""
    try:
        url = f"https://wttr.in/{loc}?format=%l:+%C,+%t,+wind+%w"
        req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.read().decode().strip() or "No weather data"
    except Exception as e:
        return f"ERROR fetching weather: {e}"


def get_datetime() -> str:
    """Get the current date, time, and day of week."""
    return time.strftime("%A %d %B %Y, %H:%M:%S")


def web_fetch(url: str) -> str:
    """Fetch text content from a URL (first 2000 chars).
    Args:
        url: The URL to fetch, e.g. https://example.com
    """
    import urllib.request
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as r:
            text = r.read().decode(errors="replace")
            return text[:2000] if text else "Empty response"
    except Exception as e:
        return f"ERROR: {e}"


def run_shell(command: str) -> str:
    """Run a shell command and return its output.
    Args:
        command: Shell command to execute, e.g. dir, ipconfig, python -c "print(1+1)"
    """
    try:
        # Capture bytes so we can decode with errors="replace" — PowerShell and
        # many Windows CLIs emit UTF-16 / CP1252, not UTF-8. text=True would
        # raise UnicodeDecodeError and silently hand back empty output.
        r = subprocess.run(
            command, shell=True, capture_output=True,
            creationflags=_NO_WINDOW, timeout=30,
        )
        stdout = (r.stdout or b"").decode("utf-8", errors="replace").strip()
        stderr = (r.stderr or b"").decode("utf-8", errors="replace").strip()
        out = stdout or stderr
        if not out:
            # Be explicit so the model can't confabulate an answer. Include the
            # exit code so it can report success vs. silent-failure honestly.
            return f"EMPTY_OUTPUT (exit code {r.returncode}) — command produced no stdout or stderr."
        return out[:2000]
    except subprocess.TimeoutExpired:
        return "ERROR: command timed out after 30s"
    except Exception as e:
        return f"ERROR: {type(e).__name__}: {e}"


# ── AI delegation ────────────────────────────────────────────────────────

def notify(message: str, urgency: str = "normal") -> str:
    """Send one message to the AgentConsole Notification Hub.
    Args:
        message: Notification text, e.g. "Build finished", "Door left open"
        urgency: One of: low, normal, high, urgent. Do NOT pass numbers, JSON, or "critical".
    """
    urgency_key = str(urgency or "normal").strip().lower()
    priority_map = {
        "low": "LOW",
        "normal": "NORMAL",
        "high": "HIGH",
        "urgent": "CRITICAL",
    }
    if urgency_key not in priority_map:
        return "ERROR: urgency must be one of low, normal, high, urgent"

    text = str(message or "").strip()
    if not text:
        return "ERROR: message is required"

    exec_code = (
        "from agent_console.core.notification_hub import notify as _hub_notify; "
        f"_entry = _hub_notify(title='Ollama Agent', body={json.dumps(text)}, "
        f"priority={json.dumps(priority_map[urgency_key])}, "
        "source='ollama_chat', category='tool_notify'); "
        "print('NOTIFY_RESULT:', repr(_entry))"
    )
    raw = _ac_tcp(f"exec {exec_code}")
    if raw.startswith("ERROR:"):
        return raw
    if "NOTIFY_RESULT:" not in raw:
        return f"ERROR: notification dispatch did not return a result. Raw: {raw[:300]}"
    return f"NOTIFIED: {urgency_key}"


def whoami() -> str:
    """Return this agent's session ID, process IDs, model, name, and subscriptions."""
    sid = os.environ.get("AC_SESSION_ID", "").strip()
    if not sid:
        sid = _discover_self_sid_via_pid()

    resolved_name = ""
    if sid:
        for name, entry_sid in _iter_list_entries():
            if entry_sid.lower() == sid.lower():
                resolved_name = name
                break

    try:
        parent_pid = os.getppid()
    except Exception:
        parent_pid = -1

    subscriptions = "ERROR: self session_id could not be resolved"
    if sid:
        exec_code = (
            f"_topics = self._session_bus_registrar.get_subscriptions({repr(sid)}); "
            "print('SUBS_RESULT:', repr(_topics))"
        )
        raw = _ac_tcp(f"exec {exec_code}")
        if raw.startswith("ERROR:"):
            subscriptions = raw
        elif "SUBS_RESULT:" in raw:
            subscriptions = raw.split("SUBS_RESULT:", 1)[1].strip()
        else:
            subscriptions = f"ERROR: subscription lookup returned no result. Raw: {raw[:300]}"

    lines = [
        "WHOAMI:",
        f"session_id: {sid or '(unresolved)'}",
        f"pid: {os.getpid()}",
        f"parent_pid: {parent_pid}",
        f"active_model: {_ACTIVE_MODEL or '(unset)'}",
        f"resolved_name: {resolved_name or '(unresolved)'}",
        f"active_subscriptions: {subscriptions}",
    ]
    return "\n".join(lines)


def read_file(path: str, max_lines: int = 100) -> str:
    """Read the last lines of a text file.
    Args:
        path: Relative or absolute path, e.g. README.md, logs/app.log, C:\\temp\\notes.txt
        max_lines: Tail line count, e.g. 20, 100. Do NOT use for binary files.
    """
    raw_path = str(path or "").strip()
    if not raw_path:
        return "ERROR: path is required"

    try:
        line_limit = int(max_lines)
    except (TypeError, ValueError):
        return "ERROR: max_lines must be an integer"
    if line_limit < 1:
        return "ERROR: max_lines must be at least 1"

    candidate = Path(raw_path).expanduser()
    if not candidate.is_absolute():
        candidate = Path.cwd() / candidate
    try:
        resolved = candidate.resolve()
    except OSError:
        resolved = candidate

    if not resolved.exists() or not resolved.is_file():
        return f"ERROR: file not found: {resolved}"

    tail_lines: list[str] = []
    try:
        with open(resolved, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                tail_lines.append(line)
                if len(tail_lines) > line_limit:
                    tail_lines.pop(0)
    except OSError as e:
        return f"ERROR: {type(e).__name__}: {e}"

    body = "".join(tail_lines)
    header = f"READ_FILE: {resolved} ({len(tail_lines)} lines)"
    if len(header) > 4000:
        return header[:3997] + "..."

    response = header if not body else f"{header}\n{body}"
    if len(response) <= 4000:
        return response

    header = f"{header}, truncated"
    budget = 4000 - len(header) - 1
    if budget < 0:
        return header[:3997] + "..."
    if budget <= 3:
        body = body[-budget:] if budget else ""
    else:
        body = "..." + body[-(budget - 3):]
    return header if not body else f"{header}\n{body}"


def _legacy_delegate_to_gemini(task: str) -> str:
    """Delegate a task to a Gemini AI agent and return its response.
    Use when a task needs web access, complex reasoning, or broader knowledge
    than you have locally.
    Args:
        task: What you want the Gemini agent to do, e.g. 'summarize the news today'
    """
    import re
    import shutil

    # Try direct Gemini CLI first (synchronous, returns result)
    gemini = shutil.which("gemini")
    if gemini:
        try:
            r = subprocess.run(
                [gemini, "-p", task, "--yolo"],
                capture_output=True, text=True,
                creationflags=_NO_WINDOW, timeout=120,
            )
            output = (r.stdout or r.stderr).strip()
            # Strip ANSI escape codes from terminal output
            output = re.sub(r"\x1b\[[0-9;]*[a-zA-Z]", "", output)
            return output[:3000] if output else "Gemini completed with no output"
        except subprocess.TimeoutExpired:
            return "Gemini timed out after 120s — task may be too complex"
        except Exception as e:
            pass  # Fall through to AgentConsole

    # Fallback: spawn via AgentConsole (fire-and-forget)
    safe_task = task.replace('"', '\\"')
    result = _ac_tcp(f'spawn gemini agent with "{safe_task}"', timeout=20)
    if "ERROR" not in result:
        return f"Gemini agent spawned: {result}"
    return f"ERROR: Neither gemini CLI nor AgentConsole available. {result}"

_DELEGATE_LABELS = {
    "claude": "Claude",
    "codex": "Codex",
    "gemini": "Gemini",
}


def _strip_ansi(text: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*[a-zA-Z]", "", text)


def _delegate_cli_command(agent_type: str, cli_path: str, task: str, model: str = "") -> list[str]:
    model = str(model or "").strip()
    if agent_type == "claude":
        cmd = [cli_path]
        if model:
            cmd.extend(["--model", model])
        cmd.extend(["-p", "--dangerously-skip-permissions", task])
        return cmd
    if agent_type == "codex":
        cmd = [cli_path, "exec"]
        if model:
            cmd.extend(["--model", model])
        cmd.extend(
            [
                "--dangerously-bypass-approvals-and-sandbox",
                "--skip-git-repo-check",
                task,
            ]
        )
        return cmd
    if agent_type == "gemini":
        cmd = [cli_path]
        if model:
            cmd.extend(["-m", model])
        cmd.extend(["-p", task, "--yolo"])
        return cmd
    raise ValueError(f"unsupported delegate agent: {agent_type}")


def _delegate_spawn_extra_args(agent_type: str) -> list[str]:
    if agent_type == "claude":
        return ["--dangerously-skip-permissions"]
    if agent_type == "codex":
        return ["--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check"]
    if agent_type == "gemini":
        return ["--yolo"]
    raise ValueError(f"unsupported delegate agent: {agent_type}")


def _build_agentconsole_delegate_exec(agent_type: str, task: str, model: str = "") -> str:
    payload = {
        "agent_type": agent_type,
        "task": task,
        "model": str(model or "").strip(),
        "cwd": str(Path.cwd()),
        "extra_args": _delegate_spawn_extra_args(agent_type),
    }
    return (
        "exec "
        "import json; "
        "from agent_console.core.models import AgentType; "
        f"payload = json.loads({json.dumps(json.dumps(payload, ensure_ascii=False))}); "
        "agent_map = {'claude': AgentType.CLAUDE, 'codex': AgentType.CODEX, 'gemini': AgentType.GEMINI}; "
        "sid, session = orchestrator._create_and_start_managed_session("
        "adapter_key=agent_map[payload['agent_type']], "
        "cwd=payload['cwd'], "
        "extra_args=list(payload.get('extra_args') or []), "
        "model=(payload.get('model') or None)"
        "); "
        "prompt = (payload.get('task') or '').strip(); "
        "if prompt: orchestrator._pending_prompts = getattr(orchestrator, '_pending_prompts', {}); "
        "orchestrator._pending_prompts[sid] = prompt; "
        "orchestrator._poll_pending_prompts(); "
        "orchestrator._last_agent_id = sid; "
        "label = payload['agent_type'].capitalize(); "
        "model_text = f\" model={session.model}\" if getattr(session, 'model', '') else ''; "
        "print(f\"{label} agent spawned: {session.name} ({sid[:8]}){model_text}\")"
    )


def _delegate_to_agent(agent_type: str, task: str, model: str = "") -> str:
    label = _DELEGATE_LABELS[agent_type]
    task = str(task or "").strip()
    if not task:
        return "ERROR: task is required"
    model = str(model or "").strip()

    cli_path = shutil.which(agent_type)
    if cli_path:
        try:
            r = subprocess.run(
                _delegate_cli_command(agent_type, cli_path, task, model),
                capture_output=True,
                text=True,
                creationflags=_NO_WINDOW,
                timeout=120,
            )
            output = _strip_ansi((r.stdout or r.stderr).strip())
            return output[:3000] if output else f"{label} completed with no output"
        except subprocess.TimeoutExpired:
            return f"{label} timed out after 120s - task may be too complex"
        except Exception:
            pass

    result = _ac_tcp(_build_agentconsole_delegate_exec(agent_type, task, model), timeout=20)
    cleaned = result[3:].strip() if result.startswith("OK:") else result.strip()
    if not cleaned.lower().startswith("error"):
        return cleaned
    return f"ERROR: Neither {label.lower()} CLI nor AgentConsole available. {cleaned}"


def delegate_to_claude(task: str, model: str = "") -> str:
    """Delegate a task to Claude and return its response.
    Use for broad reasoning, planning, and careful writing.
    Args:
        task: What you want Claude to do.
        model: Optional Claude model alias or full model name.
    """
    return _delegate_to_agent("claude", task, model)


def delegate_to_codex(task: str, model: str = "") -> str:
    """Delegate a task to Codex and return its response.
    Use for coding tasks, code review, or repository work.
    Args:
        task: What you want Codex to do.
        model: Optional Codex model name.
    """
    return _delegate_to_agent("codex", task, model)


def delegate_to_gemini(task: str, model: str = "") -> str:
    """Delegate a task to Gemini and return its response.
    Use when a task needs web access, current information, or broader knowledge.
    Args:
        task: What you want Gemini to do.
        model: Optional Gemini model name.
    """
    return _delegate_to_agent("gemini", task, model)


ALL_TOOLS = [
    trigger_scene, control_device, get_house_status,
    agent_command, agent_subscribe, agent_unsubscribe, agent_list_subscriptions,
    tv_control, wake_control,
    get_weather, get_datetime, web_fetch, run_shell, notify, whoami, read_file,
    delegate_to_claude, delegate_to_codex, delegate_to_gemini,
]
TOOL_MAP = {f.__name__: f for f in ALL_TOOLS}
MAX_ROUNDS = 5

# ── Learned tools (self-improvement) ────────────────────────────────────

LEARNED_TOOLS_PATH = DATA_DIR / "learned_tools.json"


def _load_learned_tools() -> dict:
    """Load learned tool registry from disk."""
    if LEARNED_TOOLS_PATH.exists():
        try:
            return json.loads(LEARNED_TOOLS_PATH.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def _save_learned_tools(registry: dict):
    """Save learned tool registry to disk."""
    LEARNED_TOOLS_PATH.parent.mkdir(parents=True, exist_ok=True)
    LEARNED_TOOLS_PATH.write_text(
        json.dumps(registry, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )


def _exec_learned(entry: dict) -> str:
    """Execute a learned tool entry."""
    action = entry.get("action", "shell")
    command = entry.get("command", "")
    if action == "shell":
        try:
            r = subprocess.run(
                command, shell=True, capture_output=True,
                creationflags=_NO_WINDOW, timeout=30,
            )
            stdout = (r.stdout or b"").decode("utf-8", errors="replace").strip()
            stderr = (r.stderr or b"").decode("utf-8", errors="replace").strip()
            out = stdout or stderr
            if out:
                return out
            return f"EMPTY_OUTPUT (exit {r.returncode}) — ran '{command[:80]}', no stdout/stderr."
        except subprocess.TimeoutExpired:
            return f"ERROR: learned shell command '{command[:60]}' timed out after 30s"
        except Exception as e:
            return f"ERROR: {type(e).__name__}: {e}"
    elif action == "tcp":
        return _tcp(int(entry["port"]), command)
    elif action == "iot":
        args = command.split()
        cmd = [sys.executable, IOT_SCRIPT] + args
        try:
            r = subprocess.run(cmd, capture_output=True, text=True,
                               creationflags=_NO_WINDOW, timeout=15)
            out = (r.stdout or r.stderr).strip()
            if out:
                return out
            return f"ACCEPTED (exit {r.returncode}): iot '{command}'. No confirmation text."
        except Exception as e:
            return f"ERROR: {type(e).__name__}: {e}"
    elif action == "url":
        import webbrowser
        webbrowser.open(command)
        return f"OPENED_URL: {command} (no confirmation — browser does not report back)"
    elif action == "start":
        try:
            os.startfile(command)
            return f"LAUNCHED: {command} (no confirmation — OS does not report back)"
        except Exception as e:
            return f"ERROR launching '{command}': {type(e).__name__}: {e}"
    return f"ERROR: unknown action type '{action}'"


def learn_new_tool(name: str, description: str, action: str, command: str,
                   port: str = "", phrases: str = "") -> str:
    """Learn a new tool so it can be used in future conversations.
    Call this when asked to do something you don't have a tool for.
    Args:
        name: Short snake_case name for the tool, e.g. open_spotify, check_weather
        description: What the tool does in one sentence
        action: One of: shell (run a command), tcp (send to a TCP port),
                iot (run ./iot command), url (open a URL), start (open a file/app)
        command: The command to run. For shell: full command string.
                 For tcp: the message to send. For iot: device + action args.
                 For url: the URL. For start: the path to open.
        port: TCP port number (only needed if action is tcp)
        phrases: Comma-separated natural phrases that should trigger this tool,
                 e.g. "open spotify, play spotify, launch spotify"
    """
    registry = _load_learned_tools()
    entry = {
        "description": description,
        "action": action,
        "command": command,
        "phrases": [p.strip() for p in phrases.split(",") if p.strip()],
        "learned_at": time.strftime("%Y-%m-%d %H:%M"),
    }
    if port:
        entry["port"] = port
    registry[name] = entry
    _save_learned_tools(registry)

    # Hot-load into current session
    _register_learned_tool(name, entry)

    return f"Learned tool '{name}': {description}. Available now and in future sessions."


# Dispatch registration: the schema list built in main() appends learn_new_tool
# to the tools sent to Ollama, but TOOL_MAP (above) is built from ALL_TOOLS and
# doesn't include it — so when the model calls it, the dispatch loop hits
# "unknown tool 'learn_new_tool'". Register it explicitly at module import so
# the name resolves for every spawn, not just after the first learned tool.
TOOL_MAP[learn_new_tool.__name__] = learn_new_tool


def _register_learned_tool(name: str, entry: dict):
    """Register a learned tool into the live TOOL_MAP."""
    def _executor(**kwargs) -> str:
        return _exec_learned(entry)
    _executor.__name__ = name
    _executor.__doc__ = f"""{entry['description']}
    (Learned tool — {entry['action']}: {entry['command']})"""
    TOOL_MAP[name] = _executor


def _load_all_learned():
    """Load all learned tools into TOOL_MAP and ALL_TOOLS at startup."""
    registry = _load_learned_tools()
    for name, entry in registry.items():
        _register_learned_tool(name, entry)
    return registry


def _build_learned_tool_defs(registry: dict) -> list:
    """Build Ollama tool definitions for learned tools."""
    defs = []
    for name, entry in registry.items():
        defs.append({
            "type": "function",
            "function": {
                "name": name,
                "description": entry["description"],
                "parameters": {"type": "object", "properties": {}, "required": []},
            },
        })
    return defs

# ── Availability / model management ─────────────────────────────────────

_available_cache: dict[str, tuple[bool, float]] = {}
_CACHE_TTL = 30


def _list_model_names() -> set[str]:
    """Return all model names currently known to Ollama (with and without :tag)."""
    names: set[str] = set()
    models = ollama.list()
    for m in models.models:
        names.add(m.model)
        if ":" in m.model:
            names.add(m.model.split(":")[0])
    return names


def is_available(model: str | None = None) -> bool:
    """Check if Ollama is running and a usable model exists.

    With no arg, preserves original behaviour (checks MODEL_NAME or BASE_MODEL).
    With an explicit model arg, checks for that specific model.
    """
    cache_key = model or "__default__"
    now = time.time()
    entry = _available_cache.get(cache_key)
    if entry is not None and (now - entry[1]) < _CACHE_TTL:
        return entry[0]

    try:
        names = _list_model_names()
        if model is None:
            result = MODEL_NAME in names or BASE_MODEL in names
        else:
            result = model in names or f"{model}:latest" in names
        _available_cache[cache_key] = (result, now)
        return result
    except Exception:
        _available_cache[cache_key] = (False, now)
        return False


def _pick_model(override: str | None = None) -> str:
    """Return the model to use — CLI override > custom Modelfile > base."""
    if override:
        return override
    try:
        names = _list_model_names()
        if MODEL_NAME in names or f"{MODEL_NAME}:latest" in names:
            return MODEL_NAME
    except Exception:
        pass
    return BASE_MODEL


def _is_meaningful_response(text: str) -> bool:
    """Filter out cop-out responses where the model admits it can't help."""
    if not text:
        return False
    lower = text.lower()
    cop_outs = [
        "i can't", "i cannot", "i don't have", "i'm not able",
        "i am not able", "i don't know how", "i'm unable",
        "beyond my capabilities", "outside my capabilities",
        "i'm sorry, i", "i apologize",
    ]
    return not any(phrase in lower for phrase in cop_outs)


# ── Programmatic routing (same interface as ollama_router) ──────────────

_THROTTLE_STATUSES = {402, 408, 429, 502, 503, 504}


def _is_throttle_error(exc: Exception) -> bool:
    """Heuristic: does this exception look like a cloud rate-limit/throttle?"""
    status = getattr(exc, "status_code", None)
    if isinstance(status, int) and status in _THROTTLE_STATUSES:
        return True
    text = str(exc).lower()
    return any(s in text for s in ("rate limit", "too many requests", "quota", "throttle"))


def route(
    text: str,
    model: str | None = None,
    fallback: bool = True,
) -> tuple[bool, str]:
    """Route a natural language command through Ollama tool calling.

    Args:
        text: User utterance.
        model: Optional model override. If None, auto-picks (commander-gemma4 → e4b).
               Pass e.g. "gemma4:31b-cloud" to force the Ollama cloud tier.
        fallback: If True and the chosen model is a cloud variant that gets
                  throttled, retry once on the local BASE_MODEL before giving up.

    Returns:
        (handled, response) — handled=True if the model produced a meaningful
        response. handled=False means the caller should fall through.
    """
    chosen = model or _pick_model()
    if not is_available(chosen if model else None):
        return False, ""

    handled, response, exc = _run_tool_loop(chosen, text)
    if exc is not None and fallback and _is_cloud_model(chosen) and _is_throttle_error(exc):
        local = BASE_MODEL
        if is_available(local):
            handled, response, exc = _run_tool_loop(local, text)
    if exc is not None:
        return False, ""
    return handled, response


def _run_tool_loop(model: str, text: str) -> tuple[bool, str, Exception | None]:
    """Execute the chat+tool-dispatch loop against one model.

    Returns (handled, response, exception). exception is non-None only when
    the loop aborted before producing a meaningful answer.
    """
    messages = [{"role": "user", "content": text}]
    try:
        for _ in range(MAX_ROUNDS):
            resp = ollama.chat(
                model=model,
                messages=messages,
                tools=ALL_TOOLS,
                stream=False,
                keep_alive="5m",
                options={"temperature": 0.2, "num_predict": 256},
            )
            msg = resp.message
            messages.append(msg)

            if not msg.tool_calls:
                content = (msg.content or "").strip()
                if _is_meaningful_response(content):
                    return True, content, None
                return False, "", None

            for tc in msg.tool_calls:
                name = tc.function.name
                args = tc.function.arguments
                if name not in TOOL_MAP:
                    messages.append({"role": "tool", "content": f"ERROR: unknown tool '{name}'"})
                    continue
                result = TOOL_MAP[name](**args)
                messages.append({"role": "tool", "content": str(result)})

        return True, "Done.", None
    except Exception as exc:
        return False, "", exc


# ── Model management ────────────────────────────────────────────────────

_LEGACY_SYSTEM_PROMPT = (
    "You are a versatile assistant with home automation and general-purpose tools.\n\n"
    "RULES:\n"
    "1. Always use tools when possible — don't just talk about it, do it.\n"
    "2. If no tool exists for a task, use learn_new_tool to create one. "
    "NEVER say 'I can\\'t' or 'I don\\'t have access'.\n"
    "3. Keep responses under 2 sentences. User hears via text-to-speech.\n"
    "4. If unsure which device, pick the most likely match.\n"
    "5. Never explain what you're about to do — just do it.\n\n"
    "AVAILABLE SCENES: morning, bedtime, focus, movie, leaving, arriving, all_off, "
    "kettle, desk, alert_door, arriving_quiet, arriving_comfort, night_path, wake_pc\n"
    "AVAILABLE DEVICES: kettle-plug, desk-plug, charger-plug, yeelight, colour-bulb, "
    "ir-blaster, midea, hive"
)

_LEGACY_CHAT_SYSTEM_PROMPT = (
    "You are a versatile assistant with home automation and general-purpose tools.\n\n"
    "RULES:\n"
    "1. ALWAYS use tools to take action — don't just describe what you would do.\n"
    "2. For weather: use get_weather. For date/time: use get_datetime.\n"
    "3. For web content: use web_fetch. For system tasks: use run_shell.\n"
    "4. For home automation: trigger_scene or control_device.\n"
    "5. For complex tasks needing web access or deep reasoning: use delegate_to_gemini.\n"
    "6. To watch for agent events: use agent_subscribe(description, notify). "
    "To stop watching: agent_unsubscribe(who) clears all subscriptions for that agent. "
    "To check what's active: agent_list_subscriptions(who). "
    "For all three, `who`/`notify` defaults to the AI Commander; "
    "pass 'self' to target yourself, or an agent name (e.g. 'jon', 'claude-2') to target that agent. "
    "Subscriptions fire LATER — they don't return data now. "
    "Never pass 'subscribe ...' as a command to agent_command.\n"
    "7. If NO existing tool can do the task, you MUST use learn_new_tool to create one.\n"
    "   NEVER say 'I can't' or 'I don't have access' — create a tool instead.\n"
    "   learn_new_tool actions: shell (run command), tcp (send to port), "
    "iot (smart device), url (open browser), start (launch app/file).\n"
    "   Examples: check_disk_space (shell: wmic logicaldisk get freespace,caption),\n"
    "   open_spotify (start: path\\to\\spotify.exe), google_it (url: https://google.com/search?q=...)\n"
    "8. Keep responses under 2 sentences (user hears via TTS).\n"
    "9. Don't explain — just do it.\n"
    "10. NEVER invent results. Tools return honest markers — trust them literally: "
    "'EMPTY_OUTPUT' = command ran, produced nothing; "
    "'ACCEPTED_NO_REPLY' / 'ACCEPTED' = service took the command silently; "
    "'IR_SENT' = IR blast fired (one-way, unverifiable); "
    "'OPENED_URL' / 'LAUNCHED' = OS-level launch with no feedback; "
    "'SCENE_TRIGGERED' / 'WAKE_ACTION_SENT' = dispatched, no confirmation; "
    "'NO_DATA' = query returned nothing; "
    "anything starting with 'ERROR:' = the call failed. "
    "Relay these plainly to the user — do not fabricate data.\n\n"
    "SCENES: morning, bedtime, focus, movie, leaving, arriving, all_off, "
    "kettle, desk, alert_door, arriving_quiet, arriving_comfort, night_path, wake_pc\n"
    "DEVICES: kettle-plug, desk-plug, charger-plug, yeelight, colour-bulb, "
    "ir-blaster, midea, hive"
)

_SYSTEM_PROMPT = (
    "You are a versatile assistant with home automation and general-purpose tools.\n\n"
    "RULES:\n"
    "1. Always use tools when possible - don't just talk about it, do it.\n"
    "2. If no tool exists for a task, use learn_new_tool to create one. "
    "NEVER say 'I can't' or 'I don't have access'.\n"
    "3. Keep responses under 2 sentences. User hears via text-to-speech.\n"
    "4. If unsure which device, pick the most likely match.\n"
    "5. For specialist help, delegate: Codex for code, Claude for broader reasoning, Gemini for web/current-info tasks.\n"
    "6. Never explain what you're about to do - just do it.\n\n"
    "AVAILABLE SCENES: morning, bedtime, focus, movie, leaving, arriving, all_off, "
    "kettle, desk, alert_door, arriving_quiet, arriving_comfort, night_path, wake_pc\n"
    "AVAILABLE DEVICES: kettle-plug, desk-plug, charger-plug, yeelight, colour-bulb, "
    "ir-blaster, midea, hive"
)

_CHAT_SYSTEM_PROMPT = (
    "You are a versatile assistant with home automation and general-purpose tools.\n\n"
    "RULES:\n"
    "1. ALWAYS use tools to take action - don't just describe what you would do.\n"
    "2. For weather: use get_weather. For date/time: use get_datetime.\n"
    "3. For web content: use web_fetch. For system tasks: use run_shell.\n"
    "4. For home automation: trigger_scene or control_device.\n"
    "5. For specialist help: use delegate_to_codex for coding, delegate_to_claude for planning/reasoning, and delegate_to_gemini for web/current-info tasks.\n"
    "6. To watch for agent events: use agent_subscribe(description, notify). "
    "To stop watching: agent_unsubscribe(who) clears all subscriptions for that agent. "
    "To check what's active: agent_list_subscriptions(who). "
    "For all three, `who`/`notify` defaults to the AI Commander; "
    "pass 'self' to target yourself, or an agent name (e.g. 'jon', 'claude-2') to target that agent. "
    "Subscriptions fire LATER - they don't return data now. "
    "Never pass 'subscribe ...' as a command to agent_command.\n"
    "7. If NO existing tool can do the task, you MUST use learn_new_tool to create one.\n"
    "   NEVER say 'I can't' or 'I don't have access' - create a tool instead.\n"
    "   learn_new_tool actions: shell (run command), tcp (send to port), "
    "iot (smart device), url (open browser), start (launch app/file).\n"
    "   Examples: check_disk_space (shell: wmic logicaldisk get freespace,caption),\n"
    "   open_spotify (start: path\\to\\spotify.exe), google_it (url: https://google.com/search?q=...)\n"
    "8. Keep responses under 2 sentences (user hears via TTS).\n"
    "9. Don't explain - just do it.\n"
    "10. NEVER invent results. Tools return honest markers - trust them literally: "
    "'EMPTY_OUTPUT' = command ran, produced nothing; "
    "'ACCEPTED_NO_REPLY' / 'ACCEPTED' = service took the command silently; "
    "'IR_SENT' = IR blast fired (one-way, unverifiable); "
    "'OPENED_URL' / 'LAUNCHED' = OS-level launch with no feedback; "
    "'SCENE_TRIGGERED' / 'WAKE_ACTION_SENT' = dispatched, no confirmation; "
    "'NO_DATA' = query returned nothing; "
    "anything starting with 'ERROR:' = the call failed. "
    "Relay these plainly to the user - do not fabricate data.\n\n"
    "SCENES: morning, bedtime, focus, movie, leaving, arriving, all_off, "
    "kettle, desk, alert_door, arriving_quiet, arriving_comfort, night_path, wake_pc\n"
    "DEVICES: kettle-plug, desk-plug, charger-plug, yeelight, colour-bulb, "
    "ir-blaster, midea, hive"
)

_MODEL_PARAMS = {
    "temperature": 0.2,
    "top_p": 0.8,
    "num_ctx": 4096,
    "num_predict": 256,
    "repeat_penalty": 1.1,
}


def create_model():
    """Create the commander-gemma4 custom model."""
    try:
        print(f"Creating model '{MODEL_NAME}' from {BASE_MODEL}...")
        ollama.create(model=MODEL_NAME, from_=BASE_MODEL,
                      system=_SYSTEM_PROMPT, parameters=_MODEL_PARAMS)
        print(f"Model '{MODEL_NAME}' created successfully.")
        global _available_cache
        _available_cache = None
        return True
    except Exception as e:
        print(f"ERROR creating model: {e}")
        return False


def show_status():
    """Print Ollama and model status."""
    _ver = getattr(ollama, "__version__", "unknown")
    print(f"ollama package: v{_ver}")

    try:
        models = ollama.list()
        print(f"Ollama API: running ({len(models.models)} models)")
        for m in models.models:
            marker = ""
            if MODEL_NAME in m.model or BASE_MODEL in m.model:
                marker = " <-- active"
            elif _is_cloud_model(m.model):
                marker = " <-- cloud"
            size_gb = (m.size or 0) / (1024**3)
            print(f"  {m.model} ({size_gb:.1f} GB){marker}")
    except Exception as e:
        print(f"Ollama API: NOT REACHABLE ({e})")
        return

    available = is_available()
    model = _pick_model() if available else "none"
    print(f"Commander model: {model} ({'ready' if available else 'NOT FOUND'})")
    cloud_ready = is_available(CLOUD_MODEL)
    print(f"Cloud model:     {CLOUD_MODEL} ({'ready' if cloud_ready else 'not installed — run: ollama pull ' + CLOUD_MODEL})")

    learned = _load_learned_tools()
    n_builtin = len(ALL_TOOLS) + 1  # +1 for learn_new_tool
    n_learned = len(learned)
    print(f"Tools: {n_builtin} built-in + {n_learned} learned = {n_builtin + n_learned} total")


# ── Context bar ──────────────────────────────────────────────────────────

def _get_ctx_limit(model: str) -> int:
    """Query model's context window size from Ollama."""
    try:
        info = ollama.show(model)
        # Check modelinfo or parameters for num_ctx
        params = getattr(info, "parameters", "") or ""
        for line in params.splitlines():
            if "num_ctx" in line:
                return int(line.split()[-1])
        # Check model_info dict
        mi = getattr(info, "model_info", {}) or {}
        for key, val in mi.items():
            if "context_length" in key:
                return int(val)
    except Exception:
        pass
    # Cloud variants: ollama.show() returns null parameters/model_info for
    # the cloud proxy, so neither loop above can find the ctx size. Fall
    # back to the hard-coded map of known cloud windows before the 8192
    # default — keeps the context bar honest for cloud sessions.
    if _is_cloud_model(model):
        known = _CLOUD_CTX_LIMITS.get(model)
        if known:
            return known
    return 8192  # safe default


def _estimate_tokens(messages: list) -> int:
    """Rough token estimate from conversation history (~4 chars per token)."""
    total_chars = 0
    for m in messages:
        if isinstance(m, dict):
            total_chars += len(m.get("content", "") or "")
        else:
            # ollama Message object
            total_chars += len(getattr(m, "content", "") or "")
            if getattr(m, "tool_calls", None):
                total_chars += len(json.dumps([
                    {"name": tc.function.name, "args": tc.function.arguments}
                    for tc in m.tool_calls
                ]))
    # Add ~100 tokens overhead for tool definitions when tools are active
    return total_chars // 4 + 100


def _message_role(message) -> str:
    """Return the message role from either a dict or an Ollama message object."""
    if isinstance(message, dict):
        return str(message.get("role", "") or "")
    return str(getattr(message, "role", "") or "")


def _message_content(message) -> str:
    """Return message content from either a dict or an Ollama message object."""
    if isinstance(message, dict):
        return str(message.get("content", "") or "")
    return str(getattr(message, "content", "") or "")


def _last_assistant_message(messages: list) -> str:
    """Return the most recent non-empty assistant message content."""
    for message in reversed(messages):
        if _message_role(message) != "assistant":
            continue
        content = _message_content(message).strip()
        if content:
            return content
    return ""


def _reset_conversation(messages: list, assistant_summary: str = "") -> None:
    """Replace the live conversation with the system prompt and an optional summary."""
    messages.clear()
    messages.append({"role": "system", "content": _CHAT_SYSTEM_PROMPT})
    if assistant_summary.strip():
        messages.append({"role": "assistant", "content": assistant_summary.strip()})


def _parse_slash_command(user_input: str) -> tuple[str, str]:
    """Split '/command args' into ('/command', 'args')."""
    parts = user_input.strip().split(None, 1)
    command = parts[0].lower() if parts else ""
    arg = parts[1].strip() if len(parts) > 1 else ""
    return command, arg


def _prompt_command_dirs(cwd: Path | None = None) -> list[Path]:
    """Return prompt-template directories in priority order."""
    root = cwd or Path.cwd()
    return [root / ".ollama" / "commands", BUNDLED_COMMANDS_DIR]


def _list_prompt_macros(cwd: Path | None = None) -> list[tuple[str, Path]]:
    """List available prompt templates, preferring project-local files."""
    found: dict[str, Path] = {}
    for directory in _prompt_command_dirs(cwd):
        if not directory.exists():
            continue
        for pattern in ("*.md", "*.txt"):
            for path in sorted(directory.glob(pattern)):
                found.setdefault(path.stem, path)
    return sorted(found.items())


def _load_prompt_macro(name: str, cwd: Path | None = None) -> tuple[str, Path]:
    """Load a prompt template from .ollama/commands or the bundled commands dir."""
    prompt_name = name.strip()
    if not prompt_name:
        raise ValueError("prompt name required")
    if "/" in prompt_name or "\\" in prompt_name:
        raise ValueError("prompt names must not contain path separators")

    suffix = Path(prompt_name).suffix.lower()
    candidates = [prompt_name] if suffix in {".md", ".txt"} else [f"{prompt_name}.md", f"{prompt_name}.txt"]
    for directory in _prompt_command_dirs(cwd):
        for candidate in candidates:
            path = directory / candidate
            if not path.is_file():
                continue
            content = path.read_text(encoding="utf-8").strip()
            if not content:
                raise ValueError(f"prompt '{prompt_name}' is empty")
            return content, path
    searched = ", ".join(str(path) for path in _prompt_command_dirs(cwd))
    raise ValueError(f"prompt '{prompt_name}' not found in {searched}")


def _resolve_save_path(filename: str, cwd: Path | None = None) -> Path:
    """Resolve a save target, treating relative paths as relative to cwd."""
    raw = filename.strip()
    if not raw:
        raise ValueError("filename required")
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = (cwd or Path.cwd()) / path
    return path


def _save_last_response(messages: list, filename: str, cwd: Path | None = None) -> Path:
    """Save the latest assistant response to disk and return the path."""
    content = _last_assistant_message(messages)
    if not content:
        raise ValueError("no assistant response to save yet")
    path = _resolve_save_path(filename, cwd)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def _copy_to_clipboard(text: str) -> str:
    """Copy text to the system clipboard."""
    if not text.strip():
        raise ValueError("nothing to copy")

    if sys.platform == "win32":
        subprocess.run(
            ["clip.exe"],
            input=text.encode("utf-16le"),
            check=True,
            creationflags=_NO_WINDOW,
            timeout=5,
        )
        return "copied to clipboard"

    clipboard_cmds = [
        (["pbcopy"], text.encode("utf-8")),
        (["wl-copy"], text.encode("utf-8")),
        (["xclip", "-selection", "clipboard"], text.encode("utf-8")),
        (["xsel", "--clipboard", "--input"], text.encode("utf-8")),
    ]
    for cmd, payload in clipboard_cmds:
        if shutil.which(cmd[0]) is None:
            continue
        subprocess.run(cmd, input=payload, check=True, timeout=5)
        return "copied to clipboard"
    raise RuntimeError("no supported clipboard command found")


def _print_help() -> None:
    """Print available slash commands."""
    print("Slash commands:")
    for command, description in _SLASH_COMMANDS:
        print(f"  {command:16s} - {description}")
    if _pt_prompt is not None:
        print("  Tab completes slash commands.")
    print("  exit, quit, q   - Exit chat.")


def _print_tools(learned_registry: dict | None = None) -> None:
    """Print built-in tools plus any learned tools."""
    print("Built-in tools:")
    for func in ALL_TOOLS:
        doc = (func.__doc__ or "").strip().split("\n")[0]
        print(f"  {func.__name__:20s} - {doc}")
    print(f"  {'learn_new_tool':20s} - Learn a new tool for future use")
    registry = learned_registry if learned_registry is not None else _load_learned_tools()
    if registry:
        print(f"\nLearned tools ({len(registry)}):")
        for name, entry in registry.items():
            details = f"[{entry['action']}: {entry['command']}]"
            print(f"  {name:20s} - {entry['description']}  \033[90m{details}\033[0m")


def _build_slash_completer():
    """Return a prompt_toolkit completer for slash commands when available."""
    if WordCompleter is None:
        return None
    return WordCompleter(_SLASH_COMMAND_NAMES, ignore_case=True, sentence=True)


def _read_input(prompt_text: str, completer=None) -> str:
    """Read one line of input, using prompt_toolkit when available."""
    if _pt_prompt is None or ANSI is None:
        return input(prompt_text)

    kwargs = {"complete_while_typing": True}
    if completer is not None:
        kwargs["completer"] = completer
    if FileHistory is not None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        kwargs["history"] = FileHistory(str(PROMPT_HISTORY_PATH))
    return _pt_prompt(ANSI(prompt_text), **kwargs)


def _format_ctx_bar(used: int, limit: int, width: int = 24) -> str:
    """Render a context window usage bar."""
    pct = min(used / limit, 1.0) if limit else 0
    filled = int(width * pct)
    empty = width - filled

    # Color: green < 50%, yellow < 80%, red >= 80%
    if pct < 0.5:
        color = "\033[32m"  # green
    elif pct < 0.8:
        color = "\033[33m"  # yellow
    else:
        color = "\033[31m"  # red

    def _fmt_k(n):
        return f"{n / 1000:.1f}K" if n >= 1000 else str(n)

    bar = f"{color}{'█' * filled}\033[90m{'░' * empty}\033[0m"
    return f"\033[90mctx [{bar}\033[90m] {_fmt_k(used)} / {_fmt_k(limit)} ({pct:.0%})\033[0m"


# ── Spinner ──────────────────────────────────────────────────────────────

class _Spinner:
    """Animated spinner with elapsed time, runs in a background thread."""

    _FRAMES = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]

    def __init__(self, label: str = "thinking"):
        self._label = label
        self._stop = threading.Event()
        self._thread = None
        self._start_time = 0.0

    def start(self):
        self._start_time = time.time()
        self._stop.clear()
        self._thread = threading.Thread(target=self._spin, daemon=True)
        self._thread.start()

    def update(self, label: str):
        self._label = label

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join()
        # Clear the spinner line
        sys.stdout.write("\r\033[K")
        sys.stdout.flush()

    def _spin(self):
        i = 0
        while not self._stop.is_set():
            elapsed = time.time() - self._start_time
            frame = self._FRAMES[i % len(self._FRAMES)]
            sys.stdout.write(
                f"\r  \033[90m{frame} {self._label} ({elapsed:.1f}s)\033[0m"
            )
            sys.stdout.flush()
            i += 1
            self._stop.wait(0.08)

# ── Chat loop ────────────────────────────────────────────────────────────

class _TurnAborted(Exception):
    """Raised when the user hits Ctrl+C mid-turn."""


# ── Clean shutdown ──────────────────────────────────────────────────────
# Every ollama.chat() call sets keep_alive="5m", so when AgentConsole kills
# this process the Ollama server keeps the model resident for up to 5 minutes.
# Repeated spawn/kill cycles leave models hot and chew RAM. On exit we ask
# the server to unload NOW via keep_alive=0.

_ACTIVE_MODEL: str = ""
_SHUTDOWN_DONE = threading.Event()


def _release_ollama_model() -> None:
    """Tell the Ollama server to unload our active model immediately."""
    if _SHUTDOWN_DONE.is_set():
        return
    _SHUTDOWN_DONE.set()
    model = _ACTIVE_MODEL
    if not model:
        return
    try:
        # keep_alive=0 with empty messages forces unload without a real turn.
        ollama.chat(model=model, messages=[], keep_alive=0)
    except Exception:
        # Ollama server may already be gone; that's fine.
        pass


def _install_shutdown_hooks() -> None:
    """Register exit-time model unload via atexit + SIGTERM/SIGBREAK."""
    atexit.register(_release_ollama_model)

    def _signal_handler(signum, frame):
        _release_ollama_model()
        # Re-raise default behaviour by exiting cleanly.
        sys.exit(0)

    # SIGTERM lands on Unix when the parent PTY closes politely.
    # SIGBREAK is Windows' close-signal from the console.
    for sig_name in ("SIGTERM", "SIGBREAK"):
        sig = getattr(signal, sig_name, None)
        if sig is None:
            continue
        try:
            signal.signal(sig, _signal_handler)
        except (ValueError, OSError):
            # Not in main thread or signal not supported — skip silently.
            pass


def _ollama_chat_interruptible(**kwargs):
    """Run ollama.chat in a worker thread so Ctrl+C on the main thread is responsive.

    On Windows, signal delivery during blocking socket reads inside the ollama
    client is unreliable — running it in a background thread lets the main
    thread stay responsive to KeyboardInterrupt.
    """
    result = {"resp": None, "exc": None}

    def _run():
        try:
            result["resp"] = ollama.chat(**kwargs)
        except BaseException as e:  # pragma: no cover - propagate to main
            result["exc"] = e

    t = threading.Thread(target=_run, daemon=True)
    t.start()
    try:
        while t.is_alive():
            t.join(timeout=0.1)
    except KeyboardInterrupt:
        # Thread keeps running in background but we return control to the user.
        raise _TurnAborted()
    if result["exc"]:
        raise result["exc"]
    return result["resp"]


def _run_plain_chat(model: str, messages: list, label: str = "thinking") -> str:
    """Run one non-tool Ollama turn and return plain assistant text."""
    spinner = _Spinner(f"{label} (Ctrl+C to abort)")
    spinner.start()
    try:
        resp = _ollama_chat_interruptible(
            model=model,
            messages=messages,
            stream=False,
            keep_alive="5m",
        )
    finally:
        spinner.stop()
    msg = getattr(resp, "message", None)
    return (getattr(msg, "content", "") or "").strip()


def _compact_history(model: str, messages: list) -> tuple[str, int]:
    """Summarize the conversation and replace history with the summary."""
    before = _estimate_tokens(messages)
    if len(messages) <= 1:
        return "(nothing to compact)", before

    summary_messages = list(messages)
    summary_messages.append({"role": "user", "content": _COMPACT_SUMMARY_PROMPT})
    try:
        summary = _run_plain_chat(model, summary_messages, label="compacting")
    except _TurnAborted:
        return "\033[31m(compact aborted by user)\033[0m", before
    except Exception as exc:
        return f"\033[31m(compact failed: {exc})\033[0m", before

    if not summary:
        return "\033[31m(compact failed: empty summary)\033[0m", before

    _reset_conversation(messages, assistant_summary=summary)
    after = _estimate_tokens(messages)
    return f"\033[90m(compacted context: {before} -> {after} est tokens)\033[0m", after


def chat_turn(model: str, messages: list, tools: list | None,
              extra_tool_defs: list | None = None,
              budget_tracker=None,
              tier: str | None = None) -> tuple[str, int]:
    """Run one user turn, executing tool calls automatically.
    Returns (response_text, ctx_estimate).

    When ``budget_tracker`` is supplied, the loop checks
    ``BudgetCeilingTracker.preflight()`` between iterations and pauses with
    the canonical 06 §6.3 message when the ceiling fires. The Ollama loop is
    always free-tier (🟢/🟡), so the tracker should treat its calls as free
    by passing ``tier='free'`` — paid providers thread their own tier in via
    the future provider-router integration.
    """
    kwargs = {"model": model, "messages": messages, "stream": False, "keep_alive": "5m"}
    if tools:
        # Combine Python function tools with manual JSON defs for learned tools
        kwargs["tools"] = tools + (extra_tool_defs or [])

    spinner = _Spinner("thinking (Ctrl+C to abort)")
    turn_start = time.time()

    _copout_nudged = False
    try:
        for round_n in range(MAX_ROUNDS):
            # Phase H: pause between iterations if the budget ceiling has been
            # breached. Never aborts a call mid-flight — see 06 §6.6.
            if budget_tracker is not None:
                decision = budget_tracker.preflight()
                if decision.action == "pause":
                    print(decision.message)
                    elapsed = time.time() - turn_start
                    ctx_est = _estimate_tokens(messages)
                    return decision.message, ctx_est
            spinner.start()
            try:
                resp = _ollama_chat_interruptible(**kwargs)
            finally:
                spinner.stop()
            msg = resp.message
            messages.append(msg)
            eval_tokens = getattr(resp, "eval_count", 0) or 0
            if budget_tracker is not None:
                # Ollama is always free-tier; record_call short-circuits to
                # zero so accumulation only happens when paid providers wire
                # in their own callback.
                budget_tracker.record_call(
                    provider_id="ollama",
                    model_id=model,
                    tier=tier or "free",
                    cost_header=None,
                    input_tokens=getattr(resp, "prompt_eval_count", 0) or 0,
                    output_tokens=eval_tokens,
                )

            if not msg.tool_calls:
                content = (msg.content or "").strip()
                # If model gave up, nudge it to use tools (once per turn)
                if not _copout_nudged and not _is_meaningful_response(content) and tools:
                    _copout_nudged = True
                    messages.append({
                        "role": "user",
                        "content": (
                            "You MUST use a tool. Options:\n"
                            "- run_shell: run a shell/system command\n"
                            "- learn_new_tool: create a reusable tool for this\n"
                            "- delegate_to_codex / delegate_to_claude / delegate_to_gemini: hand off to a stronger AI\n"
                            "- web_fetch: get info from a URL\n"
                            "Do not respond without calling a tool."
                        ),
                    })
                    spinner = _Spinner("retrying (Ctrl+C to abort)")
                    continue
                elapsed = time.time() - turn_start
                tok_s = eval_tokens / elapsed if elapsed > 0 and eval_tokens else 0
                stats = f"\033[90m({elapsed:.1f}s"
                if eval_tokens:
                    stats += f", {eval_tokens} tok, {tok_s:.1f} tok/s"
                stats += ")\033[0m"
                ctx_est = _estimate_tokens(messages)
                return f"{msg.content or ''}\n  {stats}", ctx_est

            # Execute each tool call
            for tc in msg.tool_calls:
                name = tc.function.name
                args = tc.function.arguments
                if name in TOOL_MAP:
                    print(f"  \033[33m⚡ {name}({json.dumps(args)})\033[0m")
                    try:
                        result = TOOL_MAP[name](**args)
                    except KeyboardInterrupt:
                        raise _TurnAborted()
                    print(f"  \033[90m→ {result[:200]}\033[0m")
                else:
                    result = f"ERROR: unknown tool '{name}'"
                    print(f"  \033[31m✗ {result}\033[0m")

                messages.append({"role": "tool", "content": str(result)})

            # Update spinner label for follow-up round
            spinner = _Spinner(f"round {round_n + 2}/{MAX_ROUNDS} (Ctrl+C to abort)")

        elapsed = time.time() - turn_start
        ctx_est = _estimate_tokens(messages)
        return f"(max tool rounds reached)\n  \033[90m({elapsed:.1f}s)\033[0m", ctx_est
    except _TurnAborted:
        spinner.stop()
        elapsed = time.time() - turn_start
        ctx_est = _estimate_tokens(messages)
        return f"\033[31m(aborted by user after {elapsed:.1f}s)\033[0m", ctx_est


def main():
    parser = argparse.ArgumentParser(description="Interactive Ollama chat with tools")
    parser.add_argument("--model", default=None, help="Ollama model name (auto-picks if omitted)")
    parser.add_argument(
        "--launcher",
        default="wrapper",
        choices=["wrapper", "claude", "codex"],
        help="Launch surface: tool-enabled wrapper, Claude Code via Ollama, or Codex via Ollama.",
    )
    parser.add_argument("--cloud", action="store_true",
                        help=f"Use the cloud model {CLOUD_MODEL} (shortcut for --model <cloud>).")
    parser.add_argument("--tools", action="store_true", help="List available tools and exit")
    parser.add_argument("--no-tools", action="store_true", help="Disable tool calling")
    parser.add_argument("--status", action="store_true", help="Show Ollama/model status")
    parser.add_argument("--setup", action="store_true", help="Create commander-gemma4 custom model")
    args = parser.parse_args()

    if args.cloud and not args.model:
        args.model = CLOUD_MODEL

    if args.status:
        show_status()
        return

    if args.setup:
        create_model()
        return

    # Auto-pick model: CLI override > commander-gemma4 > gemma4:e4b
    model = _pick_model(args.model)
    launcher = _normalize_launcher(args.launcher)

    # Record active model + install exit hooks so the Ollama server unloads
    # it when this process is killed (instead of waiting out the 5-minute
    # keep_alive). See _release_ollama_model.
    global _ACTIVE_MODEL
    _ACTIVE_MODEL = model
    _install_shutdown_hooks()

    if launcher != "wrapper":
        raise SystemExit(_launch_via_ollama_surface(launcher, model))

    # Load learned tools from registry
    learned_registry = _load_all_learned()

    if args.tools:
        print("Built-in tools:")
        for f in ALL_TOOLS:
            doc = (f.__doc__ or "").strip().split("\n")[0]
            print(f"  {f.__name__:20s} — {doc}")
        print(f"  {'learn_new_tool':20s} — Learn a new tool for future use")
        if learned_registry:
            print(f"\nLearned tools ({len(learned_registry)}):")
            for name, entry in learned_registry.items():
                print(f"  {name:20s} — {entry['description']}")
        return

    # Build combined tool list: built-ins + learn_new_tool + learned tools
    builtin_tools = ALL_TOOLS + [learn_new_tool]
    # Learned tools need manual JSON defs (they're not real Python functions with signatures)
    learned_defs = _build_learned_tool_defs(learned_registry)

    tools = None if args.no_tools else builtin_tools
    messages = [{"role": "system", "content": _CHAT_SYSTEM_PROMPT}]
    ctx_used = 0

    mode = "tools enabled" if tools else "plain chat"
    ctx_limit = _get_ctx_limit(model)
    n_learned = len(learned_registry)
    learned_str = f" + {n_learned} learned" if n_learned else ""
    tier = " [CLOUD tier — subject to rate limits]" if _is_cloud_model(model) else ""
    print(f"\033[36mOllama Chat — {model} ({mode}{learned_str}){tier}\033[0m")
    print(f"\033[90mContext window: {ctx_limit:,} tokens\033[0m")
    print("Type naturally or /help. Tools execute automatically. Ctrl+C to quit.\n")

    slash_completer = _build_slash_completer()

    try:
        while True:
            # Show context bar before prompt
            if ctx_used > 0:
                print(f"  {_format_ctx_bar(ctx_used, ctx_limit)}")
            try:
                user_input = _read_input("\033[32myou>\033[0m ", completer=slash_completer).strip()
            except EOFError:
                break
            if not user_input:
                continue
            lower_input = user_input.lower()
            if lower_input in ("exit", "quit", "q"):
                break
            command, command_arg = _parse_slash_command(user_input)
            submitted_input = user_input

            if command == "/help":
                _print_help()
                continue
            if command == "/clear":
                _reset_conversation(messages)
                ctx_used = 0
                print("  (conversation cleared)")
                continue
            if command == "/compact":
                response, ctx_used = _compact_history(model, messages)
                print(f"\033[36mgemma>\033[0m {response}\n")
                continue
            if command in {"/prompt", "/macro"}:
                if not command_arg:
                    macros = _list_prompt_macros()
                    if not macros:
                        print("  (no prompt templates found in .ollama/commands or agent_console/ollama/commands)")
                    else:
                        print("  Prompt templates:")
                        for name, path in macros:
                            print(f"    {name:16s} - {path}")
                    continue
                try:
                    submitted_input, loaded_path = _load_prompt_macro(command_arg)
                except (OSError, ValueError) as exc:
                    print(f"  ({exc})")
                    continue
                print(f"  (loaded prompt '{command_arg}' from {loaded_path})")
            if command == "/save":
                try:
                    saved_path = _save_last_response(messages, command_arg)
                except (OSError, ValueError) as exc:
                    print(f"  ({exc})")
                    continue
                print(f"  (saved last response to {saved_path})")
                continue
            if command == "/copy":
                try:
                    status = _copy_to_clipboard(_last_assistant_message(messages))
                except (RuntimeError, ValueError, subprocess.SubprocessError, OSError) as exc:
                    print(f"  ({exc})")
                    continue
                print(f"  ({status})")
                continue
            if command == "/tools":
                _print_tools()
                continue
            if command == "/status":
                show_status()
                continue
            if command == "/setup":
                create_model()
                model = _pick_model()
                print(f"  (now using: {model})")
                continue
            if command == "/resume":
                from .budget_ceiling import process_pause_input
                # Resume only matters when the loop has been paused by the
                # budget-ceiling tracker. Without a tracker the command is a
                # no-op so users don't see crashes when typing it speculatively.
                tracker = globals().get("_BUDGET_TRACKER")
                if tracker is None:
                    print("  (no active budget ceiling)")
                else:
                    decision = process_pause_input(tracker, user_input)
                    print("  " + decision.message)
                continue
            if command == "/switch":
                from .budget_ceiling import process_pause_input
                tracker = globals().get("_BUDGET_TRACKER")
                if tracker is None:
                    print("  (no active budget ceiling — switching models is a Phase I "
                          "feature on the free path)")
                else:
                    decision = process_pause_input(tracker, user_input)
                    print("  " + decision.message)
                    if decision.action == "switch" and decision.new_model:
                        model = decision.new_model
                continue
            if user_input.lower() == "/clear":
                messages.clear()
                messages.append({"role": "system", "content": _CHAT_SYSTEM_PROMPT})
                ctx_used = 0
                print("  (conversation cleared)")
                continue
            if user_input.lower() == "/tools":
                for f in ALL_TOOLS:
                    doc = (f.__doc__ or "").strip().split("\n")[0]
                    print(f"  {f.__name__:20s} — {doc}")
                print(f"  {'learn_new_tool':20s} — Learn a new tool for future use")
                cur_learned = _load_learned_tools()
                if cur_learned:
                    print(f"\n  \033[33mLearned ({len(cur_learned)}):\033[0m")
                    for name, entry in cur_learned.items():
                        print(f"  {name:20s} — {entry['description']}  "
                              f"\033[90m[{entry['action']}: {entry['command']}]\033[0m")
                continue
            if user_input.lower() == "/forget":
                cur_learned = _load_learned_tools()
                if not cur_learned:
                    print("  (no learned tools)")
                else:
                    print("  Learned tools:")
                    names = list(cur_learned.keys())
                    for i, name in enumerate(names):
                        print(f"    {i + 1}. {name} — {cur_learned[name]['description']}")
                    try:
                        choice = _read_input("  Remove which? (number or name, empty to cancel): ").strip()
                    except EOFError:
                        continue
                    if not choice:
                        continue
                    # Match by number or name
                    target = None
                    if choice.isdigit() and 1 <= int(choice) <= len(names):
                        target = names[int(choice) - 1]
                    elif choice in cur_learned:
                        target = choice
                    if target:
                        del cur_learned[target]
                        _save_learned_tools(cur_learned)
                        TOOL_MAP.pop(target, None)
                        learned_defs[:] = _build_learned_tool_defs(cur_learned)
                        print(f"  \033[31mForgot '{target}'\033[0m")
                    else:
                        print(f"  (not found: {choice})")
                continue

            if user_input.lower() == "/status":
                show_status()
                continue
            if user_input.lower() == "/setup":
                create_model()
                model = _pick_model()
                print(f"  (now using: {model})")
                continue

            if user_input.startswith("/") and command not in {"/prompt", "/macro"}:
                print(f"  (unknown command: {command}. Type /help.)")
                continue

            messages.append({"role": "user", "content": submitted_input})
            try:
                response, ctx_used = chat_turn(model, messages, tools, learned_defs)
            except KeyboardInterrupt:
                # Belt-and-braces: chat_turn should swallow this internally, but
                # if anything escapes (e.g. during tool dispatch), abort the
                # turn rather than exiting the program.
                response = "\033[31m(aborted by user)\033[0m"
                ctx_used = _estimate_tokens(messages)
            # Refresh learned defs in case a new tool was just learned
            learned_defs = _build_learned_tool_defs(_load_learned_tools())
            print(f"\033[36mgemma>\033[0m {response}\n")

    except KeyboardInterrupt:
        print("\n\033[90mbye\033[0m")


if __name__ == "__main__":
    main()
