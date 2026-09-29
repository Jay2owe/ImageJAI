"""Model and effort choices must reach the actual provider call."""
from __future__ import annotations

import json

import pytest

from agent.console.model_choice import (
    api_effort_kwargs, codex_models, effort_levels, normalise_effort,
    subscription_entries,
)


def test_codex_picker_uses_installed_clients_supported_efforts(tmp_path, monkeypatch):
    monkeypatch.setenv("CODEX_HOME", str(tmp_path))
    (tmp_path / "models_cache.json").write_text(json.dumps({"models": [{
        "slug": "gpt-test", "display_name": "GPT Test",
        "supported_reasoning_levels": [
            {"effort": "low"}, {"effort": "high"}, {"effort": "max"},
        ],
    }, {"slug": "internal-test", "visibility": "hide"}]}), encoding="utf-8")
    assert codex_models()[0]["slug"] == "gpt-test"
    entries = subscription_entries()
    assert all(entry.model_id != "internal-test" for entry in entries)
    chosen = next(e for e in entries if e.provider == "codex-subscription"
                  and e.model_id == "gpt-test")
    assert effort_levels(chosen.provider, chosen.model_id, dict(chosen.features)) == (
        "low", "high", "max")
    with pytest.raises(ValueError):
        normalise_effort(chosen.provider, chosen.model_id, "medium")


def test_native_and_proxy_effort_options_are_forwarded():
    assert api_effort_kwargs("anthropic", "claude-test", "medium") == {
        "thinking_budget": 4096}
    assert api_effort_kwargs("gemini", "gemini-test", "high") == {
        "thinking_budget": 8192}
    assert api_effort_kwargs("openai", "gpt-test", "low") == {
        "reasoning_effort": "low"}
    assert api_effort_kwargs("ollama", "local-test", "default") == {}
    with pytest.raises(ValueError):
        api_effort_kwargs("ollama", "local-test", "high")


def test_subscription_commands_receive_selected_model_and_effort(monkeypatch):
    from agent.console import subscriptions

    monkeypatch.setattr(subscriptions, "_executable", lambda provider: provider)
    monkeypatch.setattr(subscriptions, "subscription_status", lambda provider: (True, "ready"))
    monkeypatch.setattr(subscriptions, "ensure_importable", lambda: __import__("pathlib").Path.cwd())

    codex = subscriptions.SubscriptionAgent("codex-subscription", "gpt-test",
                                         effort="high")
    seen = []
    monkeypatch.setattr(codex, "_run", lambda command, workspace, stdin=None:
                        (seen.append(command) or (0, "", "")))
    codex._codex_turn("hello")
    assert ["--model", "gpt-test"] == seen[0][seen[0].index("--model"):][:2]
    assert 'model_reasoning_effort="high"' in seen[0]

    claude = subscriptions.SubscriptionAgent("claude-subscription", "opus",
                                          effort="xhigh")
    seen.clear()
    monkeypatch.setattr(claude, "_run", lambda command, workspace, stdin=None:
                        (seen.append(command) or (0, '{"result":"ok"}', "")))
    assert claude._claude_turn("hello") == "ok"
    assert ["--model", "opus"] == seen[0][seen[0].index("--model"):][:2]
    assert ["--effort", "xhigh"] == seen[0][seen[0].index("--effort"):][:2]


def test_subscription_latest_resume_captures_vendor_session(monkeypatch):
    from pathlib import Path
    from agent.console import subscriptions

    monkeypatch.setattr(subscriptions, "_executable", lambda provider: provider)
    monkeypatch.setattr(subscriptions, "subscription_status", lambda provider: (True, "ready"))
    monkeypatch.setattr(subscriptions, "ensure_importable", lambda: Path.cwd())

    codex = subscriptions.SubscriptionAgent(
        "codex-subscription", resume_vendor_latest=True)
    seen = []
    monkeypatch.setattr(codex, "_run", lambda command, workspace, stdin=None:
                        (seen.append(command) or (0,
                         '{"type":"thread.started","thread_id":"codex-42"}', "")))
    codex._codex_turn("continue")
    assert seen[0][seen[0].index("resume"):][:2] == ["resume", "--last"]
    assert codex.external_session_id == "codex-42"
    assert codex.resume_vendor_latest is False

    claude = subscriptions.SubscriptionAgent(
        "claude-subscription", resume_vendor_latest=True)
    seen.clear()
    monkeypatch.setattr(claude, "_run", lambda command, workspace, stdin=None:
                        (seen.append(command) or (0,
                         '{"session_id":"claude-42","result":"continued"}', "")))
    assert claude._claude_turn("continue") == "continued"
    assert "--continue" in seen[0]
    assert "--session-id" not in seen[0]
    assert claude.external_session_id == "claude-42"
    assert claude.resume_vendor_latest is False
