from __future__ import annotations

from agent.gemma4_31b import __main__ as entrypoint
from agent.providers.base import HOST_CODE_CAPABILITY


def _stub_runtime(monkeypatch):
    monkeypatch.setattr(entrypoint.events, "start_subscriber", lambda topics: None)
    monkeypatch.setattr(entrypoint.loop, "_resolve_default_model", lambda: "gemma3:27b")


def test_direct_entrypoint_defaults_to_no_host_code(monkeypatch) -> None:
    _stub_runtime(monkeypatch)
    monkeypatch.delenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", raising=False)
    monkeypatch.delenv("OLLAMA_HOST", raising=False)
    seen = {}
    monkeypatch.setattr(
        entrypoint.loop,
        "run",
        lambda **kwargs: seen.update(kwargs) or 0,
    )

    assert entrypoint.main(["--provider", "ollama", "--model", "gemma3:27b"]) == 0
    assert seen["provider_opts"] == {}


def test_direct_entrypoint_passes_explicit_local_capability(monkeypatch) -> None:
    _stub_runtime(monkeypatch)
    monkeypatch.delenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", raising=False)
    monkeypatch.delenv("OLLAMA_HOST", raising=False)
    seen = {}
    monkeypatch.setattr(
        entrypoint.loop,
        "run",
        lambda **kwargs: seen.update(kwargs) or 0,
    )

    assert entrypoint.main([
        "--provider", "ollama",
        "--model", "gemma3:27b",
        "--allow-local-host-code",
    ]) == 0
    assert seen["provider_opts"] == {"capabilities": [HOST_CODE_CAPABILITY]}


def test_direct_entrypoint_rejects_cloud_grant(monkeypatch, capsys) -> None:
    _stub_runtime(monkeypatch)
    monkeypatch.delenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", raising=False)
    monkeypatch.delenv("OLLAMA_HOST", raising=False)
    monkeypatch.setattr(
        entrypoint.loop,
        "run",
        lambda **kwargs: (_ for _ in ()).throw(
            AssertionError("cloud grant must fail before loop startup")
        ),
    )

    assert entrypoint.main([
        "--provider", "ollama-cloud",
        "--model", "gemma4:31b-cloud",
        "--allow-local-host-code",
    ]) == 2
    assert "cannot be granted to cloud provider" in capsys.readouterr().err


def test_direct_entrypoint_accepts_strict_local_env_grant(monkeypatch) -> None:
    _stub_runtime(monkeypatch)
    monkeypatch.setenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", "on")
    monkeypatch.delenv("OLLAMA_HOST", raising=False)
    seen = {}
    monkeypatch.setattr(
        entrypoint.loop,
        "run",
        lambda **kwargs: seen.update(kwargs) or 0,
    )

    assert entrypoint.main(["--provider", "ollama", "--model", "gemma3:27b"]) == 0
    assert seen["provider_opts"] == {"capabilities": [HOST_CODE_CAPABILITY]}


def test_direct_entrypoint_rejects_invalid_permission_env(monkeypatch, capsys) -> None:
    _stub_runtime(monkeypatch)
    monkeypatch.setenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", "enabled-ish")

    assert entrypoint.main(["--provider", "ollama", "--model", "gemma3:27b"]) == 2
    assert "must be one of" in capsys.readouterr().err


def test_direct_entrypoint_rejects_remote_ollama_host(monkeypatch, capsys) -> None:
    _stub_runtime(monkeypatch)
    monkeypatch.delenv("IMAGEJAI_ALLOW_LOCAL_HOST_CODE", raising=False)
    monkeypatch.setenv("OLLAMA_HOST", "https://ollama.example.invalid:11434")

    assert entrypoint.main([
        "--provider", "ollama",
        "--model", "gemma3:27b",
        "--allow-local-host-code",
    ]) == 2
    assert "requires a loopback OLLAMA_HOST" in capsys.readouterr().err
