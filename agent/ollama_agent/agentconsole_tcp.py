"""Fail-closed authenticated client for the local AgentConsole TCP service."""

from __future__ import annotations

import json
import os
import socket
from pathlib import Path

try:
    from .tcp_frames import recv_bounded
except ImportError:
    from tcp_frames import recv_bounded


AGENTCONSOLE_PORT = 7745
MAX_COMMAND_CHARS = 64 * 1024
MAX_TOKEN_CHARS = 4096


def _token_candidates(appdata=None, xdg_config_home=None, home=None):
    candidates = []
    appdata_value = os.environ.get("APPDATA") if appdata is None else appdata
    if appdata_value:
        candidates.append(
            Path(appdata_value) / "agent-console" / "config" / "tcp_auth_token.txt"
        )
    xdg_value = (
        os.environ.get("XDG_CONFIG_HOME")
        if xdg_config_home is None
        else xdg_config_home
    )
    home_value = Path.home() if home is None else Path(home)
    config_base = Path(xdg_value) if xdg_value else home_value / ".config"
    candidates.append(
        config_base / "agent-console" / "config" / "tcp_auth_token.txt"
    )
    return list(dict.fromkeys(candidates))


def load_agentconsole_token(appdata=None, xdg_config_home=None, home=None):
    """Load one non-empty, single-line token or raise before any connection."""
    for path in _token_candidates(
        appdata=appdata,
        xdg_config_home=xdg_config_home,
        home=home,
    ):
        try:
            raw = path.read_text(encoding="utf-8")
        except (OSError, FileNotFoundError):
            continue
        token = raw.strip()
        if (
            not token
            or len(token) > MAX_TOKEN_CHARS
            or "\r" in token
            or "\n" in token
        ):
            raise RuntimeError("AgentConsole TCP auth token is invalid")
        return token
    raise RuntimeError("AgentConsole TCP auth token is unavailable")


def send_agentconsole(
    command,
    timeout=15,
    *,
    token_loader=load_agentconsole_token,
    connector=socket.create_connection,
):
    """Authenticate, send one command frame, and return one bounded result."""
    if not isinstance(command, str) or not command.strip():
        return "ERROR: AgentConsole command must be a non-empty string"
    if len(command) > MAX_COMMAND_CHARS or "\r" in command or "\n" in command:
        return "ERROR: AgentConsole command contains an invalid frame boundary"
    try:
        token = token_loader()
    except (OSError, RuntimeError, ValueError) as exc:
        return "ERROR: {}".format(exc)

    try:
        with connector(("127.0.0.1", AGENTCONSOLE_PORT), timeout=timeout) as sock:
            sock.sendall((token + "\n" + command + "\n").encode("utf-8"))
            reply = recv_bounded(sock, newline=True)
        raw = reply.decode("utf-8", errors="replace").strip()
        if not raw:
            return "ERROR: AgentConsole returned an empty reply"
        try:
            decoded = json.loads(raw)
        except (json.JSONDecodeError, ValueError):
            return "ERROR: AgentConsole returned a non-JSON reply"
        if not isinstance(decoded, dict):
            return "ERROR: AgentConsole returned a non-object reply"
        if "ok" not in decoded or type(decoded["ok"]) is not bool:
            return "ERROR: AgentConsole reply has no valid ok field"
        if "result" not in decoded or not isinstance(decoded["result"], str):
            return "ERROR: AgentConsole reply has no valid result field"
        if decoded["ok"] is not True:
            detail = decoded["result"].strip() or "unspecified command failure"
            return "ERROR: AgentConsole command failed: {}".format(detail)
        return decoded["result"]
    except ValueError as exc:
        return "ERROR: AgentConsole returned an invalid reply ({})".format(exc)
    except (ConnectionRefusedError, OSError, socket.timeout) as exc:
        return "ERROR: AgentConsole not reachable ({})".format(exc)
