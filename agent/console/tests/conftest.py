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

    # Fiji discovery finds the user's real installation, so a test that turns on
    # automatic startup would launch it. Only launchers under tmp_path may run.
    from pathlib import Path
    from agent.console import fiji_startup

    real_launch = fiji_startup.launch_fiji

    def launch_only_test_fijis(root):
        if not Path(root).resolve().is_relative_to(tmp_path.resolve()):
            raise AssertionError(f"a console test tried to launch a real Fiji at {root}")
        return real_launch(root)

    monkeypatch.setattr(fiji_startup, "launch_fiji", launch_only_test_fijis)
