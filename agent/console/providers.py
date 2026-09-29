"""Provider catalog, model registry, and login validation.

Reads ``agent/providers/models.yaml`` (the same single source of truth the
Java picker and Python clients use) and drives the login flow:

    pick provider -> (api key if needed) -> validate -> pick model

Validation performs a real one-token chat round-trip through
``agent.providers.router.get_client`` so a bad key fails at login time,
not mid-experiment.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import load_secret
from .workspace import ensure_importable

# Providers that talk to a local endpoint and need no API key.
LOCAL_PROVIDERS = frozenset({
    "ollama", "lmstudio", "jan", "llamacpp", "vllm",
})

from .installed_agents import CLI_PROVIDERS

SUBSCRIPTION_PROVIDERS = frozenset({
    "codex-subscription", "claude-subscription",
}) | CLI_PROVIDERS

# Picker order: cloud majors first, then locals, then the rest.
PROVIDER_KEYS = (
    "codex-subscription",
    "claude-subscription",
    "gemini-cli", "aider-cli", "copilot-cli", "cline-cli", "interpreter-cli",
    "anthropic",
    "gemini",
    "openai",
    "ollama",
    "ollama-cloud",
    "openrouter",
    "groq",
    "deepseek",
    "mistral",
    "xai",
    "cerebras",
    "together",
    "huggingface",
    "perplexity",
    "github-models",
    "lmstudio",
    "jan",
    "llamacpp",
    "vllm",
)

# Friendly labels for the picker, ordered as in the Java dropdown.
PROVIDER_LABELS = {
    "codex-subscription": "OpenAI Codex (ChatGPT subscription)",
    "claude-subscription": "Claude Code (Claude subscription)",
    "gemini-cli": "Gemini CLI (installed program)",
    "aider-cli": "Aider (installed program)",
    "copilot-cli": "GitHub Copilot CLI (installed program)",
    "cline-cli": "Cline (installed program)",
    "interpreter-cli": "Open Interpreter (installed program)",
    "anthropic": "Anthropic (Claude)",
    "gemini": "Google Gemini",
    "openai": "OpenAI",
    "openrouter": "OpenRouter",
    "groq": "Groq",
    "cerebras": "Cerebras",
    "mistral": "Mistral",
    "together": "Together AI",
    "huggingface": "Hugging Face",
    "deepseek": "DeepSeek",
    "xai": "xAI (Grok)",
    "perplexity": "Perplexity",
    "github-models": "GitHub Models",
    "ollama": "Ollama (local)",
    "ollama-cloud": "Ollama Cloud",
    "lmstudio": "LM Studio (local)",
    "jan": "Jan (local)",
    "llamacpp": "llama.cpp (local)",
    "vllm": "vLLM (local)",
}


@dataclass
class ModelEntry:
    provider: str
    model_id: str
    display_name: str
    description: str = ""
    tier: str = ""
    context_window: int = 0
    vision_capable: bool = False

    @property
    def label(self) -> str:
        bits = [self.display_name or self.model_id]
        if self.tier:
            bits.append(self.tier)
        if self.context_window:
            bits.append(f"{self.context_window // 1000}k ctx")
        if self.vision_capable:
            bits.append("vision")
        return "  |  ".join(bits)


def _workspace_file(*parts: str) -> Path | None:
    ws = ensure_importable()
    p = ws.joinpath(*parts)
    return p if p.is_file() else None


def load_models_yaml() -> list[ModelEntry]:
    """Parse models.yaml; returns [] when unavailable (login falls back to free text)."""
    path = _workspace_file("providers", "models.yaml")
    if path is None:
        return []
    try:
        from .yaml_io import safe_load
        data = safe_load(path.read_text(encoding="utf-8")) or {}
    except Exception:
        return []
    entries: list[ModelEntry] = []
    for m in data.get("models", []):
        if not isinstance(m, dict):
            continue
        entries.append(ModelEntry(
            provider=str(m.get("provider", "")),
            model_id=str(m.get("model_id", "")),
            display_name=str(m.get("display_name", "") or m.get("model_id", "")),
            description=str(m.get("description", "") or "").strip(),
            tier=str(m.get("tier", "") or ""),
            context_window=int(m.get("context_window", 0) or 0),
            vision_capable=bool(m.get("vision_capable", False)),
        ))
    return entries


def provider_needs_key(provider: str) -> bool:
    return provider not in LOCAL_PROVIDERS | SUBSCRIPTION_PROVIDERS


def provider_has_key(provider: str) -> bool:
    if not provider_needs_key(provider):
        return True
    return bool(load_secret(provider))


def models_for(provider: str) -> list[ModelEntry]:
    if provider in SUBSCRIPTION_PROVIDERS:
        return [ModelEntry(
            provider=provider,
            model_id="default",
            display_name="Subscription default",
            description="Uses the model selected by the official provider client.",
            tier="subscription",
        )]
    return [e for e in load_models_yaml() if e.provider == provider]


def _get_client(provider: str, model: str, api_key: str | None = None) -> Any:
    """Build a ProviderClient the way agent_cli does."""
    ensure_importable()
    from agent.providers.router import get_client
    opts: dict[str, Any] = {}
    if api_key:
        opts["api_key"] = api_key
    return get_client(provider, model, **opts)


NATIVE_PROVIDERS = frozenset({"anthropic", "gemini"})


def validate_login(provider: str, model: str, api_key: str | None = None,
                   save_key: bool = False) -> tuple[bool, str]:
    """One-token chat round-trip. Returns (ok, message).

    Native providers (anthropic, gemini) validate directly with the key.
    Subscription providers use their official CLI login. Other providers use
    the LiteLLM sidecar, which reads the shared protected credential store.
    """
    if not model:
        return False, "no model selected"
    if provider in SUBSCRIPTION_PROVIDERS:
        from .subscriptions import subscription_status
        ok, message = subscription_status(provider)
        if not ok:
            alias = "codex" if provider.startswith("codex") else "claude"
            message += f"; exit and run `imagejai login {alias}`"
        return ok, message
    restore_key: str | None = None
    saved_new_key = False
    if save_key and api_key:
        try:
            from .config import load_secret
            restore_key = load_secret(provider)
            save_provider_key(provider, api_key)
            saved_new_key = True
        except Exception as exc:
            return False, f"could not save key: {exc}"

    def _rollback() -> None:
        """A key that does not work must not stay on disk."""
        if not saved_new_key:
            return
        from .config import forget_secret
        try:
            if restore_key:
                save_provider_key(provider, restore_key)
            else:
                forget_secret(provider)
        except Exception:
            pass

    try:
        if provider not in NATIVE_PROVIDERS:
            try:
                ensure_proxy_running()
            except Exception as exc:
                _rollback()
                return False, str(exc)
        client = _get_client(provider, model, api_key)
    except Exception as exc:
        _rollback()
        return False, f"could not build client: {exc}"
    try:
        reply = client.chat(
            [{"role": "user", "content": "Reply with the single word: ready"}],
            [],
            model,
            max_tokens=8,
        )
        text = client.extract_text(reply) or ""
    except Exception as exc:
        _rollback()
        return False, str(exc)
    if not text.strip():
        # Some providers reply with empty text on trivial prompts — a
        # non-error round-trip is still a valid key.
        return True, "connected"
    return True, text.strip()[:80]


# ---------------------------------------------------------------------------
# LiteLLM proxy sidecar management (proxy providers only)
# ---------------------------------------------------------------------------

def save_provider_key(provider: str, api_key: str) -> None:
    """Persist a key in the store shared with the Java plugin and proxy."""
    from .config import save_secret
    save_secret(provider, api_key)


def _proxy_alive(base_url: str) -> bool:
    import urllib.request
    try:
        with urllib.request.urlopen(base_url.rstrip("/") + "/health/readiness", timeout=1.0) as r:
            return r.status == 200
    except Exception:
        return False


def ensure_proxy_running(verbose: bool = False) -> str:
    """Return the proxy base URL, starting the sidecar when needed."""
    ensure_importable()
    try:
        from agent.providers.litellm_proxy import default_base_url
    except ImportError:
        from litellm_proxy import default_base_url
    base = default_base_url()
    if _proxy_alive(base):
        return base
    # start the sidecar
    ws = ensure_importable()
    config_path = ws / "providers" / "litellm.config.yaml"
    if not config_path.is_file():
        raise RuntimeError(f"litellm.config.yaml not found at {config_path}")
    try:
        try:
            from agent.providers import proxy as proxy_mod
        except ImportError:
            import proxy as proxy_mod
    except ImportError as exc:
        raise RuntimeError(
            "LiteLLM proxy dependencies missing. Install with:\n"
            "  pip install -r agent/providers/requirements.txt"
        ) from exc
    proxy_mod.start(config_path)
    if not proxy_mod.wait_healthy(timeout=15.0):
        raise RuntimeError("LiteLLM proxy did not become healthy in 15s")
    return default_base_url()
