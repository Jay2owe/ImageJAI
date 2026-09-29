from __future__ import annotations

import json
import socket
import threading
import time
from pathlib import Path

import pytest

from agent import ij, probe_plugin, scan_plugins


class AuthenticatedLoopbackServer:
    def __init__(self):
        self.requests = []
        self.errors = []
        self._done = threading.Event()
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(2)
        self._listener.settimeout(3)
        self.port = self._listener.getsockname()[1]
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        try:
            for index in range(2):
                conn, _ = self._listener.accept()
                with conn:
                    request_data = bytearray()
                    while not request_data.endswith(b"\n"):
                        chunk = conn.recv(65536)
                        if not chunk:
                            break
                        request_data.extend(chunk)
                    request = json.loads(bytes(request_data).decode("utf-8"))
                    self.requests.append(request)
                    if index == 0:
                        assert request["command"] == "hello"
                        assert request["token"] == "scan-secret"
                        response = {
                            "ok": True,
                            "result": {
                                "session_id": "scan-session",
                                "expires_at": int(time.time() * 1000) + 60_000,
                                "enabled": [],
                            },
                        }
                    else:
                        assert request["command"] == "execute_macro"
                        assert request["session_id"] == "scan-session"
                        assert request["token"] == "scan-secret"
                        response = {"ok": True, "result": {"success": True}}
                    conn.sendall((json.dumps(response) + "\n").encode("utf-8"))
        except Exception as exc:
            self.errors.append(exc)
        finally:
            self._listener.close()
            self._done.set()

    def finish(self):
        assert self._done.wait(5), "authenticated loopback server did not finish"
        self._thread.join(timeout=1)
        assert self.errors == []


def test_scan_plugins_uses_authenticated_shared_session(monkeypatch):
    server = AuthenticatedLoopbackServer()
    monkeypatch.setattr(scan_plugins, "HOST", "127.0.0.1")
    monkeypatch.setattr(scan_plugins, "PORT", server.port)
    monkeypatch.setattr(ij, "_read_server_token", lambda: "scan-secret")
    ij._SESSIONS.clear()

    try:
        response = scan_plugins.send({
            "command": "execute_macro",
            "code": "print('authenticated');",
        })
        server.finish()
    finally:
        ij._SESSIONS.clear()

    assert response == {"ok": True, "result": {"success": True}}
    assert len(server.requests) == 2


def test_scan_inventory_returns_over_tcp_without_macro_file_access(
    monkeypatch, tmp_path
):
    requests = []

    def fake_send(request):
        requests.append(request)
        return {
            "ok": True,
            "result": {
                "success": True,
                "output": "Gaussian Blur...=ij.plugin.filter.GaussianBlur\n"
                "Median...=ij.plugin.filter.RankFilters\n",
            },
        }

    monkeypatch.setattr(scan_plugins, "TMP_DIR", str(tmp_path))
    monkeypatch.setattr(scan_plugins, "send", fake_send)

    commands = scan_plugins.scan_commands()

    assert commands == [
        "Gaussian Blur...=ij.plugin.filter.GaussianBlur",
        "Median...=ij.plugin.filter.RankFilters",
    ]
    assert requests[0]["command"] == "execute_macro"
    assert "File." not in requests[0]["code"]
    assert "return List.getList" in requests[0]["code"]
    assert (tmp_path / "commands.raw.txt").read_text(encoding="utf-8").endswith(
        "RankFilters\n"
    )


def test_probe_plugin_fails_closed_without_shared_client(monkeypatch):
    monkeypatch.setattr(probe_plugin, "_load_ij_imagej_command", lambda: None)

    with pytest.raises(RuntimeError, match="authenticated agent/ij.py client"):
        probe_plugin.send({"command": "probe_command", "plugin": "Example"})


def test_imagej_scripts_do_not_reimplement_raw_socket_protocols():
    agent_dir = Path(__file__).resolve().parent
    paths = [
        agent_dir / "scan_plugins.py",
        agent_dir / "probe_plugin.py",
        agent_dir / "pixels.py",
        agent_dir / "cluster_cells.py",
        agent_dir / "cluster_expand.py",
        agent_dir / "segment_comparison.py",
        agent_dir / "work_in_progress" / "amyloid_pixel_identification"
        / "_flash_recreate" / "run_objects.py",
        agent_dir / "work_in_progress" / "amyloid_pixel_identification"
        / "_flash_recreate" / "run_groovy.py",
        agent_dir / "work_in_progress" / "amyloid_pixel_identification"
        / "_3doc" / "run_cohort.py",
        agent_dir / "work_in_progress" / "amyloid_pixel_identification"
        / "roc" / "run_groovy_long.py",
        agent_dir / "work_in_progress" / "amyloid_pixel_identification"
        / "percentile_gate" / "run_groovy_long.py",
    ]

    for path in paths:
        source = path.read_text(encoding="utf-8")
        assert "socket.socket" not in source, path
        assert "struct.unpack" not in source, path
