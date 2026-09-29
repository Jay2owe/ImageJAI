"""Console preferences and shared provider credentials under ``~/.imagej-ai``."""
from __future__ import annotations

import json
import os
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("IMAGEJAI_HOME", Path.home() / ".imagej-ai"))
CONFIG_PATH = CONFIG_DIR / "console.json"
SECRETS_PATH = CONFIG_DIR / "console_secrets.json"  # prototype migration source
PROVIDER_SECRETS_DIR = CONFIG_DIR / "secrets"


@dataclass
class ConsoleConfig:
    provider: str | None = None
    model: str | None = None
    effort: str = "default"
    host: str = "localhost"
    port: int = 7746
    fiji_path: str | None = None
    auto_start_fiji: bool = True
    close_fiji_startup_error: bool = True
    posture: str | None = None
    # Image attachments: "never", "standard-only" (default) or "always".
    # A capture can show the sample itself, so it follows the privacy posture
    # rather than being sent whenever a model happens to support vision.
    attach_images: str = "standard-only"
    show_left_rail: bool = True
    show_right_panel: bool = True
    history_collapsed: bool = False
    history_exclude_plumbing: bool = False
    model_cost_observations: dict = field(default_factory=dict)
    dismissed_cost_notices: list[str] = field(default_factory=list)
    budget_enabled: bool = False
    budget_ceiling_usd: float = 1.0
    theme: str = "imagejai-dark"
    sessions: list[dict] = field(default_factory=list)  # legacy migration source

    def save(self) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        temp = CONFIG_PATH.with_name(f"console.json.{uuid.uuid4().hex}.tmp")
        try:
            temp.write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")
            os.replace(temp, CONFIG_PATH)
        finally:
            temp.unlink(missing_ok=True)

    @classmethod
    def load(cls) -> "ConsoleConfig":
        try:
            data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        cfg = cls()
        for key, value in data.items():
            if hasattr(cfg, key):
                setattr(cfg, key, value)
        host = os.environ.get("IMAGEJAI_TCP_HOST")
        if host:
            cfg.host = host
        try:
            cfg.port = int(os.environ.get("IMAGEJAI_TCP_PORT", cfg.port))
        except ValueError:
            pass
        return cfg


def load_secret(provider: str) -> str | None:
    """Return a key from the environment or the plugin's shared secure store."""
    env_name = _ENV_KEYS.get(provider, "")
    from_env = os.environ.get(env_name) if env_name else None
    if from_env:
        return from_env
    _migrate_console_secrets()
    from .credentials import load_entries
    entries = load_entries(provider, PROVIDER_SECRETS_DIR)
    return entries.get(env_name) if env_name else next(iter(entries.values()), None)


def save_secret(provider: str, secret: str) -> None:
    from .credentials import load_entries, save_entries
    env_name = _ENV_KEYS.get(provider) or (
        provider.upper().replace("-", "_") + "_API_KEY"
    )
    entries = load_entries(provider, PROVIDER_SECRETS_DIR)
    entries[env_name] = secret.strip()
    save_entries(provider, entries, PROVIDER_SECRETS_DIR)
    os.environ[env_name] = secret.strip()


def forget_secret(provider: str) -> None:
    from .credentials import clear_entries
    clear_entries(provider, PROVIDER_SECRETS_DIR)
    env_name = _ENV_KEYS.get(provider)
    if env_name:
        os.environ.pop(env_name, None)


def _migrate_console_secrets() -> None:
    """Move the prototype's plaintext JSON keys into the shared store once."""
    if not SECRETS_PATH.is_file():
        return
    try:
        data = json.loads(SECRETS_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return
    if not isinstance(data, dict):
        return
    for provider, value in data.items():
        if isinstance(value, str) and value:
            save_secret(str(provider), value)
    SECRETS_PATH.unlink(missing_ok=True)


_ENV_KEYS = {
    "anthropic": "ANTHROPIC_API_KEY",
    "gemini": "GEMINI_API_KEY",
    "google": "GOOGLE_API_KEY",
    "openai": "OPENAI_API_KEY",
    "openrouter": "OPENROUTER_API_KEY",
    "groq": "GROQ_API_KEY",
    "cerebras": "CEREBRAS_API_KEY",
    "mistral": "MISTRAL_API_KEY",
    "together": "TOGETHER_API_KEY",
    "huggingface": "HUGGINGFACE_API_KEY",
    "deepseek": "DEEPSEEK_API_KEY",
    "xai": "XAI_API_KEY",
    "perplexity": "PERPLEXITY_API_KEY",
    "github-models": "GITHUB_TOKEN",
    "ollama": "OLLAMA_HOST",
    "ollama-cloud": "OLLAMA_API_KEY",
    "lmstudio": "LMSTUDIO_API_KEY",
    "jan": "JAN_API_KEY",
    "llamacpp": "LLAMACPP_API_KEY",
    "vllm": "VLLM_API_KEY",
}
