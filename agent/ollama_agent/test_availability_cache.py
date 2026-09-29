from __future__ import annotations

from types import SimpleNamespace

import pytest

from agent.ollama_agent import ollama_chat, ollama_router


@pytest.mark.parametrize("module", [ollama_router, ollama_chat])
def test_create_model_clears_cache_without_corrupting_it(monkeypatch, module):
    class FakeOllama:
        __version__ = "test"

        @staticmethod
        def create(**_kwargs):
            return None

        @staticmethod
        def list():
            return SimpleNamespace(models=[SimpleNamespace(model=module.MODEL_NAME)])

    cache = {"stale": (False, 0.0)}
    monkeypatch.setattr(module, "ollama", FakeOllama())
    monkeypatch.setattr(module, "_available_cache", cache)

    assert module.create_model() is True
    assert module._available_cache is cache
    assert cache == {}
    assert module.is_available() is True
    assert cache["__default__"][0] is True
