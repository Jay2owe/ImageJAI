from __future__ import annotations

import json

import pytest

from agent.ollama_agent import agentconsole_tcp, ollama_chat, ollama_router


class FakeSocket:
    def __init__(self, payload):
        self.payload = bytearray(payload)
        self.sent = bytearray()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def sendall(self, data):
        self.sent.extend(data)

    def recv(self, size):
        if not self.payload:
            return b""
        chunk = bytes(self.payload[:size])
        del self.payload[:size]
        return chunk


def test_missing_token_fails_before_connecting_or_sending():
    connections = []

    result = agentconsole_tcp.send_agentconsole(
        "list",
        token_loader=lambda: (_ for _ in ()).throw(RuntimeError("token unavailable")),
        connector=lambda *args, **kwargs: connections.append((args, kwargs)),
    )

    assert result == "ERROR: token unavailable"
    assert connections == []


def test_authenticated_frame_precedes_command_and_reply_is_bounded():
    sock = FakeSocket(
        (json.dumps({"ok": True, "result": "two agents"}) + "\n").encode("utf-8")
    )

    result = agentconsole_tcp.send_agentconsole(
        "list",
        token_loader=lambda: "secret-token",
        connector=lambda address, timeout: sock,
    )

    assert result == "two agents"
    assert bytes(sock.sent) == b"secret-token\nlist\n"


def test_token_loader_rejects_missing_empty_or_multiline_tokens(tmp_path):
    home = tmp_path / "home"
    token_file = (
        home
        / ".config"
        / "agent-console"
        / "config"
        / "tcp_auth_token.txt"
    )

    try:
        agentconsole_tcp.load_agentconsole_token(
            appdata="", xdg_config_home="", home=home
        )
    except RuntimeError as exc:
        assert "unavailable" in str(exc)
    else:
        raise AssertionError("missing token must fail closed")

    token_file.parent.mkdir(parents=True)
    for invalid in ("", "first\nsecond\n"):
        token_file.write_text(invalid, encoding="utf-8")
        try:
            agentconsole_tcp.load_agentconsole_token(
                appdata="", xdg_config_home="", home=home
            )
        except RuntimeError as exc:
            assert "invalid" in str(exc)
        else:
            raise AssertionError("invalid token must fail closed")


def test_command_frame_injection_is_rejected_before_connecting():
    connections = []
    result = agentconsole_tcp.send_agentconsole(
        "list\nkill all",
        token_loader=lambda: "secret-token",
        connector=lambda *args, **kwargs: connections.append((args, kwargs)),
    )

    assert "invalid frame boundary" in result
    assert connections == []


def test_token_loader_honors_xdg_config_home(tmp_path):
    xdg = tmp_path / "xdg"
    token_file = xdg / "agent-console" / "config" / "tcp_auth_token.txt"
    token_file.parent.mkdir(parents=True)
    token_file.write_text("xdg-token\n", encoding="utf-8")

    assert agentconsole_tcp.load_agentconsole_token(
        appdata="", xdg_config_home=xdg, home=tmp_path / "ignored-home"
    ) == "xdg-token"


@pytest.mark.parametrize("detail", ["Authentication failed", "Rate limit exceeded"])
def test_error_envelopes_are_explicit_failures(detail):
    sock = FakeSocket(
        (json.dumps({"ok": False, "result": detail}) + "\n").encode("utf-8")
    )

    result = agentconsole_tcp.send_agentconsole(
        "list",
        token_loader=lambda: "secret-token",
        connector=lambda address, timeout: sock,
    )

    assert result == "ERROR: AgentConsole command failed: {}".format(detail)


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (b"not json\n", "non-JSON reply"),
        (b"[]\n", "non-object reply"),
        (b'{"result":"missing ok"}\n', "valid ok field"),
        (b'{"ok":true}\n', "valid result field"),
        (b'{"ok":true,"result":[]}\n', "valid result field"),
    ],
)
def test_malformed_response_envelopes_fail_closed(payload, message):
    sock = FakeSocket(payload)

    result = agentconsole_tcp.send_agentconsole(
        "list",
        token_loader=lambda: "secret-token",
        connector=lambda address, timeout: sock,
    )

    assert result.startswith("ERROR:")
    assert message in result


def test_ollama_wrappers_delegate_to_authenticated_transport(monkeypatch):
    chat_calls = []
    router_calls = []

    monkeypatch.setattr(
        ollama_chat,
        "send_agentconsole",
        lambda command, **kwargs: chat_calls.append((command, kwargs)) or "chat-ok",
    )
    monkeypatch.setattr(
        ollama_router,
        "send_agentconsole",
        lambda command, **kwargs: router_calls.append((command, kwargs)) or "router-ok",
    )

    assert ollama_chat._ac_tcp("status", timeout=3) == "chat-ok"
    assert ollama_router.agent_command("list") == "router-ok"
    assert chat_calls == [("status", {"timeout": 3, "token_loader": ollama_chat._load_ac_token})]
    assert router_calls == [("list", {})]
