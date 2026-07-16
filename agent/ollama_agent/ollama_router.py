#!/usr/bin/env python3
"""
Gemma 4 Ollama tool-calling router for AI Commander.

Sits between local intent matching (regex/registry) and Bridge fallback (Claude CLI).
Handles the 80% case (simple home automation commands) at zero API cost.

Usage as module:
    from agent_console.ollama.ollama_router import route, is_available
    if is_available():
        handled, response = route("turn on the kettle")

Usage standalone:
    python -m agent_console.ollama.ollama_router "turn on the lights"
    python -m agent_console.ollama.ollama_router --status
    python -m agent_console.ollama.ollama_router --setup
"""

import json
import logging
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

try:
    from .tcp_frames import recv_bounded
    from .agentconsole_tcp import send_agentconsole
    from .legacy_tool_policy import allowed_tools_for_model, dispatch_tool_for_model
except ImportError:
    from tcp_frames import recv_bounded
    from agentconsole_tcp import send_agentconsole
    from legacy_tool_policy import allowed_tools_for_model, dispatch_tool_for_model  # type: ignore

try:
    import ollama
except ImportError:
    ollama = None

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

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


COMMANDER_DIR = _find_bridge_commander_dir()
DATA_DIR = _default_data_dir()
_NO_WINDOW = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
IOT_SCRIPT = str(COMMANDER_DIR / "iot") if COMMANDER_DIR is not None else ""

MODEL_NAME = os.environ.get("OLLAMA_COMMANDER_MODEL", "commander-gemma4")
BASE_MODEL = os.environ.get("OLLAMA_BASE_MODEL", "gemma4:e4b")
CLOUD_MODEL = os.environ.get("OLLAMA_CLOUD_MODEL", "gemma4:31b-cloud")
OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434")
MAX_ROUNDS = 5
_CLOUD_SUFFIX = "-cloud"


def _is_cloud_model(model: str) -> bool:
    """True if the model name is an Ollama cloud variant (e.g. gemma4:31b-cloud)."""
    return bool(model) and model.endswith(_CLOUD_SUFFIX)

log = logging.getLogger("ollama_router")

# ---------------------------------------------------------------------------
# TCP helper (shared pattern from ollama_chat.py)
# ---------------------------------------------------------------------------

def _tcp(port: int, cmd: str, timeout: float = 5) -> str:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall(f"{cmd}\n".encode())
            try:
                reply = recv_bounded(s)
            except socket.timeout:
                reply = b""
            return reply.decode(errors="replace").strip() or "OK"
    except ValueError as e:
        return f"ERROR: service on port {port} returned an invalid reply ({e})"
    except (ConnectionRefusedError, OSError) as e:
        return f"ERROR: service on port {port} not reachable ({e})"


# ---------------------------------------------------------------------------
# Tool definitions — maps 1:1 to existing TCP daemons
# ---------------------------------------------------------------------------

def trigger_scene(scene_name: str) -> str:
    """Trigger a home automation scene.
    Args:
        scene_name: One of: morning, bedtime, focus, movie, leaving,
                    arriving, all_off, kettle, desk, alert_door,
                    arriving_quiet, arriving_comfort, night_path, wake_pc
    """
    return _tcp(7751, f"scene {scene_name}")


def control_device(device_name: str, action: str) -> str:
    """Control a smart home device (light, plug, TV, etc.).
    Args:
        device_name: Device name, e.g. kettle-plug, desk-plug, charger-plug,
                     yeelight, colour-bulb, ir-blaster, midea, hive
        action: Action to perform, e.g. on, off, toggle, status,
                brightness 50, volume 30, ir_send vol_up
    """
    cmd = [sys.executable, IOT_SCRIPT, device_name] + action.split()
    if not IOT_SCRIPT:
        return "ERROR: Bridge commander iot script not found"
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           creationflags=_NO_WINDOW, timeout=15)
        return (r.stdout or r.stderr).strip() or "OK"
    except Exception as e:
        return f"ERROR: {e}"


def get_house_status(query: str) -> str:
    """Get house state, presence, or device health.
    Args:
        query: One of: status, state, devices, history
    """
    return _tcp(7750, query)


def agent_command(command: str) -> str:
    """Send a command to AgentConsole (agent orchestrator).
    Args:
        command: e.g. list, status, cost, spawn 1 claude, kill agent-1,
                 send 'hello' to agent-1
    """
    return send_agentconsole(command)


def tv_control(command: str) -> str:
    """Control the TV via IR blaster.
    Args:
        command: One of: on, off, vol_up, vol_down, mute, ch_up, ch_down,
                 source, up, down, left, right, ok, back, menu,
                 play, pause, stop
    """
    cmd = [sys.executable, IOT_SCRIPT, "ir-blaster", "ir_send", command]
    if not IOT_SCRIPT:
        return "ERROR: Bridge commander iot script not found"
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           creationflags=_NO_WINDOW, timeout=15)
        return (r.stdout or r.stderr).strip() or "OK"
    except Exception as e:
        return f"ERROR: {e}"


def wake_control(action: str) -> str:
    """Wake, sleep, or shut down the PC.
    Args:
        action: One of: wake, sleep, shutdown, status
    """
    return _tcp(7752, action)


ALL_TOOLS = [
    trigger_scene, control_device, get_house_status,
    agent_command, tv_control, wake_control,
]
TOOL_MAP = {f.__name__: f for f in ALL_TOOLS}

# ---------------------------------------------------------------------------
# Availability check
# ---------------------------------------------------------------------------

_available_cache: dict[str, tuple[bool, float]] = {}
_CACHE_TTL = 30  # recheck every 30s


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

    if ollama is None:
        _available_cache[cache_key] = (False, now)
        return False

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
    """Return the model to use — CLI/kwarg override > custom Modelfile > base."""
    if override:
        return override
    try:
        names = _list_model_names()
        if MODEL_NAME in names or f"{MODEL_NAME}:latest" in names:
            return MODEL_NAME
    except Exception:
        pass
    return BASE_MODEL


# ---------------------------------------------------------------------------
# Core routing function
# ---------------------------------------------------------------------------

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
    """Route a natural language command through Gemma tool calling.

    Args:
        text: User utterance.
        model: Optional model override. If None, auto-picks (commander-gemma4 → e4b).
               Pass e.g. "gemma4:31b-cloud" to force the Ollama cloud tier.
        fallback: If True and the chosen model is a cloud variant that gets
                  throttled, retry once on the local BASE_MODEL before giving up.

    Returns:
        (handled, response) — handled=True if Gemma produced a meaningful
        response (tool call executed or conversational reply that isn't a
        cop-out). handled=False means the caller should fall through to
        Bridge/Claude.
    """
    chosen = model or _pick_model()
    if not is_available(chosen if model else None):
        return False, ""

    handled, response, exc = _run_tool_loop(chosen, text)
    if exc is not None and fallback and _is_cloud_model(chosen) and _is_throttle_error(exc):
        local = BASE_MODEL
        if is_available(local):
            log.warning("Cloud model %s throttled (%s); falling back to %s", chosen, exc, local)
            handled, response, exc = _run_tool_loop(local, text)
    if exc is not None:
        log.warning("Ollama route error: %s", exc)
        return False, ""
    return handled, response


def _run_tool_loop(model: str, text: str) -> tuple[bool, str, Exception | None]:
    """Execute the chat+tool-dispatch loop against one model.

    Returns (handled, response, exception). exception is non-None only when
    the loop aborted before producing a meaningful answer.
    """
    messages = [{"role": "user", "content": text}]
    schema_tools = allowed_tools_for_model(model, ALL_TOOLS)
    try:
        for _ in range(MAX_ROUNDS):
            resp = ollama.chat(
                model=model,
                messages=messages,
                tools=schema_tools,
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
                log.info("Tool call: %s(%s)", name, json.dumps(args))
                result = dispatch_tool_for_model(model, name, args, TOOL_MAP)
                log.info("Tool result: %s", result[:200])
                messages.append({"role": "tool", "content": str(result)})

        return True, "Done.", None
    except Exception as exc:
        return False, "", exc


def _is_meaningful_response(text: str) -> bool:
    """Filter out cop-out responses where Gemma admits it can't help."""
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


# ---------------------------------------------------------------------------
# Modelfile management
# ---------------------------------------------------------------------------

MODELFILE_PATH = DATA_DIR / "commander-gemma4.Modelfile"

# System prompt shared between Modelfile (reference) and ollama.create() call
_SYSTEM_PROMPT = (
    "You are a home automation assistant. You control smart home devices "
    "and scenes by calling the provided tools.\n\n"
    "RULES:\n"
    "1. Always use a tool when the user wants to control a device or trigger a scene.\n"
    "2. For greetings, questions, or conversation, respond in natural language without tools.\n"
    "3. Keep responses under 2 sentences. The user hears your response via text-to-speech.\n"
    "4. If unsure which device the user means, pick the most likely match.\n"
    "5. Never explain what you're about to do -- just do it.\n\n"
    "AVAILABLE SCENES: morning, bedtime, focus, movie, leaving, arriving, all_off, "
    "kettle, desk, alert_door, arriving_quiet, arriving_comfort, night_path, wake_pc\n"
    "AVAILABLE DEVICES: kettle-plug, desk-plug, charger-plug, yeelight, colour-bulb, "
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
    """Create the commander-gemma4 custom model from the Modelfile."""
    if ollama is None:
        print("ERROR: ollama package not installed")
        return False

    try:
        print(f"Creating model '{MODEL_NAME}' from {BASE_MODEL}...")
        ollama.create(model=MODEL_NAME, from_=BASE_MODEL,
                      system=_SYSTEM_PROMPT, parameters=_MODEL_PARAMS)
        print(f"Model '{MODEL_NAME}' created successfully.")
        # Invalidate cache
        _available_cache.clear()
        return True
    except Exception as e:
        print(f"ERROR creating model: {e}")
        return False


def status():
    """Print Ollama and model status."""
    if ollama is None:
        print("ollama package: NOT INSTALLED")
        return

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
    print(f"Tools: {len(ALL_TOOLS)} ({', '.join(TOOL_MAP.keys())})")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Gemma 4 Commander Router")
    parser.add_argument("text", nargs="*", help="Text to route")
    parser.add_argument("--status", action="store_true", help="Show status")
    parser.add_argument("--setup", action="store_true", help="Create custom model")
    parser.add_argument("--model", default=None,
                        help="Override model (e.g. gemma4:31b-cloud). Auto-picks if omitted.")
    parser.add_argument("--cloud", action="store_true",
                        help=f"Use the cloud model {CLOUD_MODEL} (shortcut for --model <cloud>).")
    parser.add_argument("--no-fallback", action="store_true",
                        help="If cloud is throttled, don't retry on the local model.")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(message)s")

    if args.status:
        status()
        return

    if args.setup:
        create_model()
        return

    if not args.text:
        parser.print_help()
        return

    model = args.model or (CLOUD_MODEL if args.cloud else None)
    text = " ".join(args.text)
    handled, response = route(text, model=model, fallback=not args.no_fallback)
    if handled:
        print(f"[Gemma] {response}")
    else:
        print(f"[Fallthrough] Not handled by Gemma — would go to Bridge/Claude")


if __name__ == "__main__":
    main()
