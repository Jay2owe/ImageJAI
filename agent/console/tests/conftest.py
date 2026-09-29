"""Keep every console test away from the user's preferences and credentials."""
import pytest


@pytest.fixture(autouse=True)
def console_test_home(tmp_path, monkeypatch):
    from agent.console import config

    home = tmp_path / "console-home"
    monkeypatch.setenv("IMAGEJAI_HOME", str(home))
    monkeypatch.setenv("IMAGEJAI_MODELS_LOCAL", str(home / "models_local.yaml"))
    for name, value in {
        "CONFIG_DIR": home,
        "CONFIG_PATH": home / "console.json",
        "SECRETS_PATH": home / "console_secrets.json",
        "PROVIDER_SECRETS_DIR": home / "secrets",
    }.items():
        monkeypatch.setattr(config, name, value)
