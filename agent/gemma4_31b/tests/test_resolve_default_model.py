from __future__ import annotations

import sys
from pathlib import Path

import pytest


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b import loop  # noqa: E402


def test_no_hardcoded_model_default_constant():
    # The old DEFAULT_MODEL = "gemma4:31b-cloud" hardcode is gone — the wrapper
    # drives any Ollama model, so there is no baked-in model tag.
    assert not hasattr(loop, "DEFAULT_MODEL")


def test_resolve_prefers_imagejai_model_env(monkeypatch):
    monkeypatch.setenv("IMAGEJAI_MODEL", "llama3.1:8b")
    monkeypatch.delenv("OLLAMA_MODEL", raising=False)
    assert loop._resolve_default_model() == "llama3.1:8b"


def test_resolve_falls_back_to_ollama_model_env(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_MODEL", raising=False)
    monkeypatch.setenv("OLLAMA_MODEL", "qwen2.5-coder:7b")
    assert loop._resolve_default_model() == "qwen2.5-coder:7b"


def test_resolve_queries_local_daemon_when_no_env(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_MODEL", raising=False)
    monkeypatch.delenv("OLLAMA_MODEL", raising=False)

    class _Entry:
        def __init__(self, model):
            self.model = model

    class _Listing:
        models = [_Entry("mistral-small:latest"), _Entry("llama3.2:3b")]

    monkeypatch.setattr(loop.ollama, "list", lambda: _Listing())
    # First model the daemon reports — not any gemma tag.
    assert loop._resolve_default_model() == "mistral-small:latest"


def test_resolve_handles_legacy_dict_listing_shape(monkeypatch):
    # Older ollama clients return {"models": [{"name": ...}]} instead of a typed
    # ListResponse with .models entries carrying .model.
    monkeypatch.delenv("IMAGEJAI_MODEL", raising=False)
    monkeypatch.delenv("OLLAMA_MODEL", raising=False)
    monkeypatch.setattr(
        loop.ollama, "list",
        lambda: {"models": [{"name": "phi4:latest"}, {"name": "llama3.2:3b"}]},
    )
    assert loop._resolve_default_model() == "phi4:latest"


def test_resolve_raises_clear_error_when_nothing_available(monkeypatch):
    monkeypatch.delenv("IMAGEJAI_MODEL", raising=False)
    monkeypatch.delenv("OLLAMA_MODEL", raising=False)

    def _boom():
        raise RuntimeError("daemon down")

    monkeypatch.setattr(loop.ollama, "list", _boom)
    with pytest.raises(RuntimeError, match="no model specified"):
        loop._resolve_default_model()
