"""ImageJAI Console test suite.

Run with the repo root as cwd:

    pytest agent/console/tests -q

Requires: textual (TUI tests skip when it is missing). The tests never
touch the real ~/.imagej-ai — IMAGEJAI_HOME is redirected to a temp dir.
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

os.environ.setdefault("IMAGEJAI_AGENT_WORKSPACE", str(REPO_ROOT / "agent"))


@pytest.fixture()
def isolated_home(monkeypatch):
    tmp = tempfile.mkdtemp(prefix="imagejai-console-test-")
    monkeypatch.setenv("IMAGEJAI_HOME", tmp)
    # The pin/hide override file defaults to the plugin's own AppData path so
    # Fiji and the console share it. Tests must never write there.
    monkeypatch.setenv("IMAGEJAI_MODELS_LOCAL", str(Path(tmp) / "models_local.yaml"))
    # FijiConnection and ConsoleConfig both consult these env vars; clear any
    # leaked values so tests exercise the saved-config path deterministically.
    monkeypatch.delenv("IMAGEJAI_TCP_HOST", raising=False)
    monkeypatch.delenv("IMAGEJAI_TCP_PORT", raising=False)
    from agent.console.config import _ENV_KEYS
    for env_name in set(_ENV_KEYS.values()):
        if env_name:
            monkeypatch.delenv(env_name, raising=False)
    # config module binds CONFIG_DIR at import — reload to rebind.
    import importlib
    from agent.console import config as cfg_mod
    importlib.reload(cfg_mod)
    yield tmp
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------------------
# workspace
# ---------------------------------------------------------------------------


def test_find_workspace_from_repo():
    from agent.console.workspace import find_workspace
    ws = find_workspace()
    assert ws is not None
    assert (ws / "ij.py").is_file() or (ws / "providers").is_dir()


def test_ensure_importable_sets_path():
    from agent.console.workspace import ensure_importable
    ws = ensure_importable()
    parent = str(ws.parent)
    assert parent in sys.path


# ---------------------------------------------------------------------------
# config
# ---------------------------------------------------------------------------


def test_config_roundtrip(isolated_home):
    from agent.console.config import ConsoleConfig
    cfg = ConsoleConfig.load()
    cfg.provider = "groq"
    cfg.model = "llama-3.3-70b-versatile"
    cfg.port = 8000
    cfg.save()

    cfg2 = ConsoleConfig.load()
    assert cfg2.provider == "groq"
    assert cfg2.model == "llama-3.3-70b-versatile"
    assert cfg2.port == 8000


def test_console_config_adds_startup_default_to_old_file(isolated_home):
    from agent.console.config import ConsoleConfig
    old = Path(isolated_home) / "console.json"
    old.write_text('{"provider":"ollama","model":"qwen"}', encoding="utf-8")
    cfg = ConsoleConfig.load()
    assert cfg.auto_start_fiji is True
    assert cfg.close_fiji_startup_error is True
    cfg.auto_start_fiji = False
    cfg.close_fiji_startup_error = False
    cfg.save()
    assert ConsoleConfig.load().auto_start_fiji is False
    assert ConsoleConfig.load().close_fiji_startup_error is False
    assert not old.with_suffix(".json.tmp").exists()


def test_secret_save_load_forget(isolated_home):
    from agent.console.config import save_secret, load_secret, forget_secret
    save_secret("openai", "sk-test")
    assert load_secret("openai") == "sk-test"
    forget_secret("openai")
    assert load_secret("openai") is None


def test_provider_key_uses_shared_protected_store(isolated_home):
    from agent.console.providers import save_provider_key
    from agent.console.config import load_secret, forget_secret
    save_provider_key("groq", "gsk-abc")
    assert load_secret("groq") == "gsk-abc"
    credential_file = Path(isolated_home) / "secrets" / "groq.cred"
    assert credential_file.is_file()
    assert b"gsk-abc" not in credential_file.read_bytes()
    forget_secret("groq")


def test_legacy_plaintext_key_is_migrated(isolated_home):
    from agent.console.config import load_secret
    legacy = Path(isolated_home) / "secrets" / "openai.env"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("OPENAI_API_KEY=sk-legacy\n", encoding="utf-8")
    assert load_secret("openai") == "sk-legacy"
    assert not legacy.exists()
    assert (legacy.parent / "openai.cred").is_file()


def test_posix_fallback_is_owner_only(tmp_path, monkeypatch):
    from agent.console import credentials
    monkeypatch.setattr(credentials.os, "name", "posix")
    credentials.save_entries("groq", {"GROQ_API_KEY": "gsk-test"}, tmp_path)
    stored = tmp_path / "groq.env"
    assert stored.read_text(encoding="utf-8") == "GROQ_API_KEY=gsk-test\n"
    assert credentials.load_entries("groq", tmp_path) == {"GROQ_API_KEY": "gsk-test"}


def test_sessions_are_one_file_each(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console.sessions import SessionStore
    store = SessionStore(ConsoleConfig.load())
    session = store.create("ollama", "qwen")
    session.messages.append({"role": "user", "content": "hello"})
    session.external_session_id = "vendor-session"
    store.save()
    saved = Path(isolated_home) / "sessions" / f"{session.id}.json"
    assert saved.is_file()
    restored = SessionStore(ConsoleConfig.load()).get(session.id)
    assert restored is not None
    assert restored.messages[-1]["content"] == "hello"
    assert restored.external_session_id == "vendor-session"


# ---------------------------------------------------------------------------
# providers catalog
# ---------------------------------------------------------------------------


def test_models_yaml_loads():
    from agent.console.providers import load_models_yaml, PROVIDER_KEYS
    models = load_models_yaml()
    assert len(models) >= 40
    providers_seen = {m.provider for m in models}
    assert {"anthropic", "gemini"} <= providers_seen
    # every catalog model id is non-empty
    assert all(m.model_id for m in models)


def test_models_for_provider():
    from agent.console.providers import models_for
    ollama_models = models_for("ollama")
    assert len(ollama_models) > 0
    assert all(m.provider == "ollama" for m in ollama_models)


def test_local_providers_need_no_key():
    from agent.console.providers import provider_needs_key
    assert not provider_needs_key("ollama")
    assert not provider_needs_key("lmstudio")
    assert not provider_needs_key("codex-subscription")
    assert provider_needs_key("anthropic")


def test_login_parser_accepts_subscription_alias():
    from agent.console.__main__ import build_parser
    args = build_parser().parse_args(["login", "codex"])
    assert args.cmd == "login"
    assert args.provider == "codex"


def test_integration_parsers_forward_remaining_arguments():
    from agent.console.__main__ import parse_args

    use = parse_args(["use", "--doctor"])
    harness = parse_args(
        ["harness", "--json", "doctor", "--source", "Fiji.app"]
    )
    assert use.integration_args == ["--doctor"]
    assert harness.integration_args == [
        "--json", "doctor", "--source", "Fiji.app"
    ]


def test_harness_falls_back_to_external_sandbox_runner(monkeypatch):
    from agent.console import integrations

    real_import = __import__

    def without_harness(name, *args, **kwargs):
        if name == "imagej_plugin_test_harness.cli":
            raise ImportError("not in private environment")
        return real_import(name, *args, **kwargs)

    called = {}

    def record(command):
        called["command"] = command
        return 0

    monkeypatch.setattr("builtins.__import__", without_harness)
    monkeypatch.setattr(integrations.shutil, "which", lambda name: "C:/bin/imagej-test-auto.exe")
    monkeypatch.setattr(integrations.subprocess, "call", record)

    assert integrations.run_harness(["doctor"]) == 0
    assert called["command"] == ["C:/bin/imagej-test-auto.exe", "doctor"]


def test_subscription_status_uses_official_client(monkeypatch):
    import subprocess
    from agent.console import subscriptions
    monkeypatch.setattr(subscriptions, "_executable", lambda provider: "codex")
    monkeypatch.setattr(
        subscriptions.subprocess,
        "run",
        lambda *args, **kwargs: subprocess.CompletedProcess(args[0], 0, "Logged in", ""),
    )
    assert subscriptions.subscription_status("codex-subscription") == (
        True, "subscription login ready"
    )


# ---------------------------------------------------------------------------
# agent loop (fake client, no network)
# ---------------------------------------------------------------------------


class _FakeClient:
    """Mimics agent.providers.base.ProviderClient's surface."""

    def __init__(self, script):
        self.script = list(script)  # replies per model call

    def chat(self, messages, tools, model, **opts):
        return self.script.pop(0)

    def extract_text(self, response):
        return response.get("text", "")

    def extract_tool_calls(self, response):
        return response.get("calls", [])

    def append_assistant(self, messages, response):
        messages.append({"role": "assistant", "content": response.get("text", "")})

    def append_tool_result(self, messages, call, result):
        messages.append({"role": "user", "content": f"tool {call.name}: {result}"})


class _Call:
    def __init__(self, name, args):
        self.id = "t1"
        self.name = name
        self.args = args
        self.error = None


def _events():
    ev: dict[str, list] = {}
    from agent.console.agent_loop import TurnCallbacks
    return ev, TurnCallbacks(
        on_user=lambda t: ev.setdefault("user", []).append(t),
        on_assistant=lambda t: ev.setdefault("assistant", []).append(t),
        on_tool_start=lambda n, a: ev.setdefault("start", []).append((n, a)),
        on_tool_result=lambda n, ok, s: ev.setdefault("result", []).append((n, ok, s)),
        on_error=lambda e: ev.setdefault("error", []).append(e),
        on_done=lambda ok: ev.setdefault("done", []).append(ok),
        on_approval=lambda n, a: True,
    )


def test_turn_text_only(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.client = _FakeClient([{"text": "42 images measured"}])
    ev, cb = _events()
    ok = agent.turn("count cells", cb)
    assert ok is True
    assert ev["assistant"] == ["42 images measured"]
    assert ev["done"] == [True]


def test_turn_with_tool_roundtrip(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    # model asks for a tool, then answers
    agent.client = _FakeClient([
        {"text": "", "calls": [_Call("ping", {})]},
        {"text": "fiji is alive"},
    ])
    ev, cb = _events()
    ok = agent.turn("ping fiji", cb)
    assert ok is True
    assert ("ping", {}) in ev.get("start", [])
    assert ev["assistant"] == ["fiji is alive"]
    # history grew: system, user, assistant(tool-call), tool result, assistant
    assert len(agent.messages) >= 5


def test_turn_host_code_requires_approval(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    if "run_script" not in agent.host_code_tools:
        pytest.skip("host-code tools not loaded in this environment")
    agent.client = _FakeClient([
        {"text": "", "calls": [_Call("run_script", {"code": "print(1)", "language": "groovy"})]},
        {"text": "done"},
    ])
    refused: list[tuple] = []
    from agent.console.agent_loop import TurnCallbacks
    cb = TurnCallbacks(on_approval=lambda n, a: False, on_done=lambda ok: None)
    ok = agent.turn("run a script", cb)
    assert ok is False  # refused call marks the turn failed


# ---------------------------------------------------------------------------
# TUI smoke tests (skip when textual missing)
# ---------------------------------------------------------------------------


textual = pytest.importorskip("textual")


class _FakeIJ:
    def ping(self, **kw):
        return {"ok": True, "result": "pong"}

    def get_state(self, **kw):
        return {"ok": True, "result": {
            "images": [{"title": "blobs.gif"}],
            "active_image": {"title": "blobs.gif"},
            "memory": {"used": 123_000_000},
        }}

    def execute_macro(self, code, **kw):
        return {"ok": True, "result": {"success": True, "output": ""}}

    def imagej_events(self, topics=None, **kw):
        import time
        yield {"topic": "image_opened", "message": "blobs.gif"}
        time.sleep(3600)


@pytest.fixture()
def fake_fiji(monkeypatch):
    from agent.console import fiji as fiji_mod

    class FakeFiji(fiji_mod.FijiConnection):
        def __init__(self, host, port):
            super().__init__(host, port)
            self._fake_posture = "STANDARD"

        def _module(self):
            return _FakeIJ()

        def privacy_posture(self):
            return self._fake_posture

        def set_privacy_posture(self, posture):
            self._fake_posture = posture
            return posture

    return FakeFiji


def _run(app, fake_fiji_cls):
    async def _inner():
        app.fiji = fake_fiji_cls(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.6)
    asyncio.run(_inner())
    return app


def test_app_renders_main_screen(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console.tui import ConsoleApp

    async def _inner():
        app = ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.8)
            main = app.screen_stack[0]
            # sidebars exist
            main.query_one("#left-rail")
            main.query_one("#right-panel")
            main.query_one("#chat-input")
            # fake fiji state reached the right panel
            panel = main.query_one("#fiji-status")
            await pilot.pause(0.5)
            assert "blobs" in str(panel.content) or "Fiji" in str(panel.content)

    asyncio.run(_inner())


def test_login_flow_saves_config(isolated_home, fake_fiji, monkeypatch):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    # tui binds validate_login at import time — patch the binding tui uses
    monkeypatch.setattr(tui_mod, "validate_login", lambda *a, **k: (True, "ready"))

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.5)
            login = app.screen
            assert type(login).__name__ == "LoginScreen"
            prov_list = login.query_one("#provider-list")
            items = list(prov_list.children)
            target = [it for it in items if it.name == "anthropic"][0]
            prov_list.index = items.index(target)
            await pilot.press("enter")
            await pilot.pause(0.3)
            ki = login.query_one("#key-input")
            ki.value = "sk-ant-test"
            ml = login.query_one("#model-list")
            ml.index = 0
            await pilot.pause(0.2)
            await pilot.click("#btn-connect")
            for _ in range(30):
                await pilot.pause(0.4)
                if type(app.screen).__name__ != "LoginScreen":
                    break
            assert app.config.provider == "anthropic"
            assert app.config.model
            assert app.agent is not None
            assert len(app.agent.tools) > 0

    asyncio.run(_inner())


# ---------------------------------------------------------------------------
# usage estimation (/budget)
# ---------------------------------------------------------------------------


def test_login_screen_first_row_is_selectable_by_keyboard(
    isolated_home, fake_fiji, monkeypatch
):
    """Enter must pick a provider on the first screen without pressing Down."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    monkeypatch.setattr(tui_mod, "validate_login", lambda *a, **k: (True, "ready"))

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.5)
            login = app.screen
            assert type(login).__name__ == "LoginScreen"
            prov_list = login.query_one("#provider-list")
            assert prov_list.index == 0, "first provider row must start highlighted"
            items = list(prov_list.children)
            target = [it for it in items if it.name == "ollama"][0]
            prov_list.index = items.index(target)
            await pilot.press("enter")
            await pilot.pause(0.3)
            assert login.chosen_provider == "ollama"
            model_list = login.query_one("#model-list")
            assert model_list.index == 0, "first model row must start highlighted"
            assert app.focused is model_list, "focus must advance to the model list"
            # Enter on the model list connects without reaching for the mouse
            await pilot.press("enter")
            for _ in range(30):
                await pilot.pause(0.3)
                if type(app.screen).__name__ != "LoginScreen":
                    break
            assert app.config.provider == "ollama"
            assert app.config.model

    asyncio.run(_inner())


def test_login_screen_mount_does_not_probe_subscriptions(
    isolated_home, fake_fiji, monkeypatch
):
    """Vendor CLI checks are slow; they must not block the first paint."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console import subscriptions as subs_mod
    from textual.widgets import Label

    calls: list[str] = []

    def _slow_status(provider: str):
        calls.append(provider)
        return False, "not logged in"

    monkeypatch.setattr(subs_mod, "subscription_status", _slow_status)

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            login = app.screen
            assert type(login).__name__ == "LoginScreen"
            rows = {
                it.name: str(it.query_one(Label).content)
                for it in login.query_one("#provider-list").children
            }
            assert "checking" in rows["codex-subscription"] or calls
            # the worker finishes shortly after mount and rewrites the row
            for _ in range(40):
                await pilot.pause(0.1)
                if "codex-subscription" in calls:
                    break
            assert "codex-subscription" in calls
            assert "claude-subscription" in calls

    asyncio.run(_inner())


def test_allow_once_does_not_become_always(isolated_home, fake_fiji):
    """"Allow once" must not grant host-code access for the rest of the session."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.3)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)

            seen = []
            app.push_screen(
                tui_mod.ApprovalScreen("run_shell", {"cmd": "ls"}), seen.append
            )
            await pilot.pause(0.2)
            await pilot.click("#btn-allow")
            await pilot.pause(0.2)
            assert seen == ["once"]
            assert "run_shell" not in app._always_allow_host_code

            seen.clear()
            app.push_screen(
                tui_mod.ApprovalScreen("run_shell", {"cmd": "ls"}), seen.append
            )
            await pilot.pause(0.2)
            await pilot.press("escape")
            await pilot.pause(0.2)
            assert seen == ["deny"], "escape must deny, not approve"

    asyncio.run(_inner())


def test_find_png_path_reads_real_files(tmp_path):
    from agent.console.tui import _find_png_path

    target = tmp_path / "AI_Exports" / "my capture.png"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"not really a png")

    assert _find_png_path(f"saved screenshot to {target}") == str(target)
    assert _find_png_path(f'line one\n  "{target}"  \nline three') == str(target)
    assert _find_png_path("no path here") is None
    assert _find_png_path(str(tmp_path / "missing.png")) is None


def test_layout_flags_are_restored_and_saved(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    cfg = ConsoleConfig.load()
    cfg.show_right_panel = False
    cfg.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.4)
            main = app.screen_stack[0]
            assert main.query_one("#right-panel").has_class("hidden")
            assert not main.query_one("#left-rail").has_class("hidden")
            app.action_toggle_left()
            await pilot.pause(0.2)
            assert ConsoleConfig.load().show_left_rail is False

    asyncio.run(_inner())


def test_slash_model_opens_the_quick_switcher(isolated_home, fake_fiji, monkeypatch):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "ollama", "gemma3:27b"
    cfg.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.4)
            app._slash("/model")
            await pilot.pause(0.3)
            assert type(app.screen).__name__ == "ModelPickerScreen"

    asyncio.run(_inner())


def test_side_panels_click_to_give_chat_full_width_and_restore(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console.tui import ConsoleApp
    from textual.widgets import Button, Input

    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "ollama", "gemma4:31b"
    cfg.show_left_rail = cfg.show_right_panel = True
    cfg.save()

    async def _inner():
        app = ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.3)
            left = app.query_one("#left-rail")
            right = app.query_one("#right-panel")
            chat = app.query_one("#chat-column")
            draft = app.query_one("#chat-input", Input)
            both_open = chat.size.width
            draft.value = "keep my draft"
            assert not left.has_class("hidden") and not right.has_class("hidden")
            await pilot.click("#toggle-left")
            await pilot.pause(0.1)
            assert left.has_class("hidden") and not right.has_class("hidden")
            assert chat.size.width > both_open
            assert "Sessions ▶" in str(app.query_one("#toggle-left", Button).label)
            assert draft.has_focus and draft.value == "keep my draft"
            await pilot.click("#toggle-right")
            await pilot.pause(0.1)
            assert left.has_class("hidden") and right.has_class("hidden")
            assert chat.size.width == 120
            assert "◀ Fiji" in str(app.query_one("#toggle-right", Button).label)
            saved = ConsoleConfig.load()
            assert saved.show_left_rail is False and saved.show_right_panel is False
            await pilot.press("ctrl+f")
            await pilot.pause(0.1)
            assert not left.has_class("hidden") and right.has_class("hidden")
            await pilot.press("ctrl+g")
            await pilot.pause(0.1)
            assert not left.has_class("hidden") and not right.has_class("hidden")
            assert chat.size.width == both_open
            await pilot.click("#toggle-left")
            await pilot.click("#toggle-right")
            await pilot.pause(0.1)
            assert draft.value == "keep my draft"

        reopened = ConsoleApp(ConsoleConfig.load())
        reopened.fiji = fake_fiji(reopened.config.host, reopened.config.port)
        async with reopened.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.3)
            assert reopened.query_one("#left-rail").has_class("hidden")
            assert reopened.query_one("#right-panel").has_class("hidden")
            assert reopened.query_one("#chat-column").size.width == 120
            await pilot.click("#toggle-left")
            await pilot.pause(0.1)
            assert not reopened.query_one("#left-rail").has_class("hidden")

        narrow_config = ConsoleConfig.load()
        narrow_config.show_left_rail = False
        narrow_config.show_right_panel = False
        narrow_config.save()
        narrow = ConsoleApp(narrow_config)
        narrow.fiji = fake_fiji(narrow.config.host, narrow.config.port)
        async with narrow.run_test(size=(80, 30)) as pilot:
            await pilot.pause(0.2)
            left_button = narrow.query_one("#toggle-left", Button)
            right_button = narrow.query_one("#toggle-right", Button)
            assert left_button.size.width > 0 and right_button.size.width > 0
            assert str(left_button.label) in {"◀", "▶"}
            await pilot.click("#toggle-right")
            await pilot.pause(0.1)
            assert not narrow.query_one("#right-panel").has_class("hidden")
            await pilot.click("#toggle-right")
            await pilot.pause(0.1)
            assert narrow.query_one("#right-panel").has_class("hidden")
            assert narrow.query_one("#chat-column").size.width == 80

    asyncio.run(_inner())


def test_settings_cancel_validate_and_save(isolated_home, fake_fiji, monkeypatch):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console.posture import Posture
    from textual.widgets import Input, Select, Switch

    class FakeAgent:
        def __init__(self, provider, model, **kwargs):
            self.provider, self.model = provider, model
            self.effort = kwargs["effort"]
            self.messages, self.tools = [], []
            self.external_session_id = kwargs["external_session_id"]

    monkeypatch.setattr(tui_mod, "create_agent", FakeAgent)
    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "codex-subscription", "default"
    cfg.auto_start_fiji = False
    cfg.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app._start_fiji_worker = lambda: None  # enabling auto-start must not launch Fiji
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            app._slash("/settings")
            await pilot.pause(0.1)
            assert type(app.screen).__name__ == "SettingsScreen"
            app.screen.query_one("#settings-fiji-path", Input).value = "Z:/not-a-fiji"
            app.screen._submit()
            assert type(app.screen).__name__ == "SettingsScreen"
            assert "No Fiji launcher" in str(app.screen.query_one("#settings-status").content)
            app.screen.dismiss(None)
            await pilot.pause(0.1)
            assert app.config.fiji_path is None
            app.action_settings()
            await pilot.pause(0.1)
            settings = app.screen
            settings.query_one("#settings-auto-start", Switch).value = True
            settings.query_one("#settings-close-startup-error", Switch).value = True
            settings.query_one("#settings-posture", Select).value = "ON_PREMISES"
            settings.query_one("#settings-images", Select).value = "never"
            settings.query_one("#settings-left-rail", Switch).value = False
            settings._submit()
            await pilot.pause(0.3)
            assert app.config.auto_start_fiji is True
            assert app.config.close_fiji_startup_error is True
            assert app.config.attach_images == "never"
            assert app.config.show_left_rail is False
            assert app.posture.current is Posture.ON_PREMISES
            assert app.agent is None  # cloud provider blocked by stricter posture
            restored = ConsoleConfig.load()
            assert restored.posture == "ON_PREMISES"
            assert restored.show_left_rail is False
            assert restored.auto_start_fiji is True
            assert restored.close_fiji_startup_error is True
            app._slash("/posture standard")
            await pilot.pause(0.2)
            assert ConsoleConfig.load().posture == "STANDARD"
            app.action_settings()
            await pilot.pause(0.1)
            assert app.screen.query_one("#settings-posture", Select).value == "STANDARD"
            app.screen.dismiss(None)

    asyncio.run(_inner())


def test_settings_selects_fiji_and_starts_it(isolated_home, fake_fiji, monkeypatch, tmp_path):
    from agent.console.config import ConsoleConfig
    from agent.console import settings_ui, tui as tui_mod
    from textual.widgets import Input, Switch

    selected = tmp_path / "Fiji.app"
    monkeypatch.setattr(settings_ui, "fiji_root", lambda value: selected)
    cfg = ConsoleConfig.load()
    cfg.auto_start_fiji = False
    cfg.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        started = []
        app._start_fiji_worker = lambda: started.append(True)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            app.action_settings()
            await pilot.pause(0.1)
            app.screen.query_one("#settings-fiji-path", Input).value = str(selected)
            app.screen.query_one("#settings-auto-start", Switch).value = True
            app.screen._submit()
            await pilot.pause(0.1)
            assert app.config.fiji_path == str(selected)
            assert app.fiji.expected_root == selected.resolve()
            assert started == [True]

    asyncio.run(_inner())


def test_commands_and_model_effort_flow(isolated_home, fake_fiji, monkeypatch):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    class FakeAgent:
        def __init__(self, provider, model, effort):
            self.provider, self.model, self.effort = provider, model, effort
            self.messages = []
            self.tools = []
            self.external_session_id = None

    monkeypatch.setattr(tui_mod, "create_agent",
                        lambda provider, model, **kwargs:
                        FakeAgent(provider, model, kwargs.get("effort", "default")))
    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "codex-subscription", "default"
    cfg.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.4)
            app._slash("/commands")
            # Discovery runs in a worker; wait for the actual screen rather
            # than assuming the disk scan finishes within 100 milliseconds.
            async def wait_for_commands():
                while type(app.screen).__name__ != "ListPickerScreen":
                    await pilot.pause(0.05)
            await asyncio.wait_for(wait_for_commands(), timeout=10)
            assert type(app.screen).__name__ == "ListPickerScreen"
            assert any("/model" in label for label, _ in app.screen.rows)
            app.pop_screen()
            await pilot.pause(0.1)
            app._slash("/model")
            await pilot.pause(0.1)
            assert type(app.screen).__name__ == "ModelPickerScreen"
            assert any(row is not None and row.provider == "codex-subscription"
                       for row in app.screen._rows)
            app.screen.dismiss({"provider": "codex-subscription", "model": "default",
                                "effort_levels": ["low", "medium", "high"]})
            await pilot.pause(0.1)
            assert type(app.screen).__name__ == "EffortPickerScreen"
            app.screen.dismiss("high")
            await pilot.pause(0.1)
            assert app.config.effort == "high"
            assert app.agent.effort == "high"

    asyncio.run(_inner())


def test_clear_resets_saved_context_and_transient_ui(isolated_home, fake_fiji, monkeypatch):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console.agent_loop import AbortFlag
    from agent.console.sessions import SessionStore
    from textual.widgets import Input

    class FakeAgent:
        def __init__(self, provider, model, **kwargs):
            self.provider, self.model = provider, model
            self.effort = kwargs["effort"]
            self.messages = []
            self.tools = []
            self.external_session_id = kwargs["external_session_id"]
            self.resume_vendor_latest = kwargs["resume_vendor_latest"]

    monkeypatch.setattr(tui_mod, "create_agent", FakeAgent)
    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "codex-subscription", "default"
    cfg.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            session_id = app.session.id
            old_agent = app.agent
            old_evidence = app.evidence
            app.agent.messages = [{"role": "user", "content": "old question"}]
            app.agent.external_session_id = "vendor-123"
            app.agent.resume_vendor_latest = False
            app._persist_session()
            app.query_one("#chat-input", Input).value = "draft"
            app._last_code_blocks.append(("python", "print('old')"))
            app.pending_learning_drafts.append({"title": "old"})
            app._always_allow_host_code.add("old_tool")
            app.show_clarifications([{"label": "old choice", "phrase": "old choice"}])
            app._slash("/clear")
            await pilot.pause(0.1)
            assert app.agent is not old_agent
            assert app.agent.messages == []
            assert app.agent.external_session_id is None
            assert app.session.messages == []
            assert app.session.external_session_id is None
            assert app.session.resume_vendor_latest is False
            assert app.session.id == session_id
            assert app.evidence is old_evidence
            assert app.query_one("#chat-input", Input).value == ""
            assert not app._last_code_blocks
            assert not app.pending_learning_drafts
            assert not app._always_allow_host_code
            assert not app.clarification_chips
            saved = SessionStore(ConsoleConfig.load()).get(session_id)
            assert saved.messages == []
            assert saved.external_session_id is None
            app.agent.messages = [{"role": "user", "content": "interrupted"}]
            app.agent.external_session_id = "vendor-late"
            app.turn_running = True
            app.abort = AbortFlag()
            app._slash("/clear")
            assert app.abort.set_flag
            assert app.agent.messages
            app._complete_turn()
            assert app.turn_running is False
            assert app.agent.messages == []
            assert SessionStore(ConsoleConfig.load()).get(session_id).external_session_id is None

    asyncio.run(_inner())


def test_resume_restores_saved_chat_and_vendor_latest_mode(
    isolated_home, fake_fiji, monkeypatch,
):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console.sessions import SessionStore

    class FakeAgent:
        def __init__(self, provider, model, **kwargs):
            self.provider, self.model = provider, model
            self.effort = kwargs["effort"]
            self.messages = []
            self.tools = []
            self.external_session_id = kwargs["external_session_id"]
            self.resume_vendor_latest = kwargs["resume_vendor_latest"]

    monkeypatch.setattr(tui_mod, "create_agent", FakeAgent)
    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "claude-subscription", "default"
    cfg.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            original = app.session
            app.agent.messages = [
                {"role": "user", "content": "hello"},
                {"role": "assistant", "content": "hi"},
            ]
            app.agent.external_session_id = "vendor-abc"
            app._persist_session()
            app._slash("/new")
            assert app.session.id != original.id
            app._slash(f"/resume {original.id}")
            assert app.session.id == original.id
            assert app.agent.messages == original.messages
            assert app.agent.external_session_id == "vendor-abc"
            app._slash("/resume vendor")
            assert app.session.id != original.id
            assert app.session.resume_vendor_latest is True
            assert app.agent.resume_vendor_latest is True
            saved = SessionStore(ConsoleConfig.load()).get(app.session.id)
            assert saved.resume_vendor_latest is True
            app._slash(f"/resume {original.id}")
            assert app.agent.external_session_id == "vendor-abc"
            app.turn_running = True
            app._slash("/new")
            assert app.session.id == original.id
            app.turn_running = False
            await pilot.pause(0.1)

    asyncio.run(_inner())


def test_command_file_picker_and_typed_command_submit_prompt(
    isolated_home, fake_fiji,
):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    folder = Path(isolated_home) / "console" / "commands"
    folder.mkdir(parents=True)
    (folder / "review.md").write_text("Review $ARGUMENTS", encoding="utf-8")
    sent = []

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.4)
            from types import SimpleNamespace
            app.agent = SimpleNamespace(abort=None)
            app._submit_text = lambda text, *, literal=False: sent.append((text, literal))
            app._slash("/commands")
            await pilot.pause(0.4)
            assert type(app.screen).__name__ == "ListPickerScreen"
            choice = next(payload for _, payload in app.screen.rows
                          if isinstance(payload, dict)
                          and payload.get("prompt_command") is not None)
            app.screen.dismiss(choice)
            await pilot.pause(0.3)
            assert sent == [("Review", True)]
            app._slash("/review the active image")
            await pilot.pause(0.3)
            assert sent[-1] == ("Review the active image", True)
            app._submit_text = tui_mod.ConsoleApp._submit_text.__get__(app)
            turns = []
            app._run_turn = lambda text, abort: turns.append((text, abort))
            app._submit_text("/this starts with a slash", literal=True)
            assert turns == [("/this starts with a slash", app.abort)]

    asyncio.run(_inner())


def test_bad_key_is_not_left_on_disk(isolated_home, monkeypatch):
    """A key that fails validation must be rolled back, not persisted."""
    from agent.console import providers as prov
    from agent.console.config import load_secret

    monkeypatch.setattr(prov, "ensure_proxy_running", lambda *a, **k: "http://x")

    def _boom(*a, **k):
        raise RuntimeError("401 invalid api key")

    monkeypatch.setattr(prov, "_get_client", _boom)

    ok, message = prov.validate_login(
        "openai", "gpt-5", api_key="sk-bad", save_key=True)
    assert not ok
    assert "invalid api key" in message
    assert load_secret("openai") is None, "a rejected key must not survive"


def test_estimate_usage_unpriced(isolated_home):
    from agent.console.usage import estimate_usage
    msgs = [
        {"role": "system", "content": "sys " * 100},
        {"role": "user", "content": "hello " * 40},
        {"role": "assistant", "content": "hi there"},
    ]
    est = estimate_usage(msgs, "unknown-provider", "unknown-model")
    assert est.total_tokens > 0
    assert est.priced is False
    assert est.total_usd == 0.0


def test_estimate_usage_priced(isolated_home):
    from agent.console.usage import estimate_usage
    msgs = [
        {"role": "user", "content": "a" * 4000},
        {"role": "assistant", "content": "b" * 2000},
    ]
    est = estimate_usage(msgs, "anthropic", "claude-opus-4-7")
    assert est.priced is True
    assert est.input_tokens > 0 and est.output_tokens > 0
    # 4000 chars / 4 = 1000 tokens (mult 1.35) → 1350 → $15/Mtok → $0.02025
    assert abs(est.input_usd - 1350 / 1e6 * 15.0) < 1e-9
    assert abs(est.output_usd - int(2000 / 4 * est.tokenizer_multiplier) / 1e6 * 75.0) < 1e-9
    assert est.total_usd > 0.01


def test_estimate_usage_vision_blocks(isolated_home):
    from agent.console.usage import estimate_usage
    msgs = [
        {"role": "user", "content": [
            {"type": "text", "text": "describe this"},
            {"type": "image", "source": {...}},  # non-text blocks don't crash
        ]},
    ]
    est = estimate_usage(msgs, "anthropic", "claude-opus-4-7")
    assert est.input_tokens > 0


# ---------------------------------------------------------------------------
# session export (/export)
# ---------------------------------------------------------------------------


def test_export_session_writes_markdown(isolated_home, monkeypatch):
    from agent.console.config import ConsoleConfig
    from agent.console.sessions import SessionStore
    from agent.console.tui import ConsoleApp
    from agent.console import fiji as fiji_mod

    class FakeFiji(fiji_mod.FijiConnection):
        def _module(self):
            return _FakeIJ()

    async def _inner():
        app = ConsoleApp(ConsoleConfig.load())
        app.fiji = FakeFiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.5)
            app.agent = None
            # fabricate a small conversation
            main = app.screen_stack[0]
            from agent.console.agent_loop import ConsoleAgent
            app.agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
            app.agent.messages = [
                {"role": "system", "content": "SYSTEM PROMPT"},
                {"role": "user", "content": "segment the nuclei"},
                {"role": "assistant", "content": "214 nuclei found"},
            ]
            app.session.derive_title("segment the nuclei")
            app._export_session()
            from agent.console.config import CONFIG_DIR
            exports = list((CONFIG_DIR / "console" / "exports").glob("*.md"))
            assert len(exports) == 1
            text = exports[0].read_text(encoding="utf-8")
            assert "segment the nuclei" in text
            assert "214 nuclei found" in text
            assert "SYSTEM PROMPT" not in text  # system prompt stays out

    asyncio.run(_inner())


class _StreamingClient(_FakeClient):
    """FakeClient that also streams text deltas like the native clients."""

    def chat_stream(self, messages, tools, model, on_delta=None, **opts):
        reply = self.script.pop(0)
        text = reply.get("text", "")
        if text and on_delta:
            for word in text.split(" ")[:-1]:
                on_delta(word + " ")
        return reply


def test_turn_uses_streaming_client(isolated_home):
    from agent.console.agent_loop import ConsoleAgent, TurnCallbacks
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.client = _StreamingClient([{"text": "final answer text"}])
    deltas: list[str] = []
    assistants: list[str] = []
    cb = TurnCallbacks(on_text_delta=deltas.append, on_assistant=assistants.append)
    ok = agent.turn("hello", cb)
    assert ok is True
    assert deltas == ["final ", "answer "]  # last word withheld by the fake
    assert assistants == ["final answer text"]
    assert agent.messages[-1] == {"role": "assistant", "content": "final answer text"}


def test_turn_forwards_thinking_separately(isolated_home):
    from agent.console.agent_loop import ConsoleAgent, TurnCallbacks

    class ThinkingClient(_FakeClient):
        def chat_stream(self, messages, tools, model, *, on_thinking, **opts):
            on_thinking("Inspect the image")
            return self.script.pop(0)

    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.client = ThinkingClient([{"text": "Done"}])
    thinking = []
    assert agent.turn("hello", TurnCallbacks(on_thinking_delta=thinking.append))
    assert thinking == ["Inspect the image"]
    assert agent.messages[-1]["content"] == "Done"


def test_live_thinking_is_visible_retained_and_does_not_block_typing(isolated_home, fake_fiji, monkeypatch):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console.agent_loop import AbortFlag
    from textual.widgets import Input, Static, RichLog

    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "ollama", "gemma3:27b"
    cfg.auto_start_fiji = False

    async def run():
        app = tui_mod.ConsoleApp(cfg)
        app.fiji = fake_fiji(cfg.host, cfg.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            app.turn_running = True
            app.abort = AbortFlag()
            text = "Start of thinking [not markup]\n" + "Inspect image pixels.\n" * 80
            app._on_thinking_delta_ui(text)
            await pilot.pause(0.15)
            live = app.query_one("#live-text", Static)
            assert live.styles.display == "block"
            assert "Start of thinking" in str(live.content)  # no 700-character tail
            assert "Thinking" in str(live.content)
            inp = app.query_one("#chat-input", Input)
            inp.focus()
            await pilot.press("n", "e", "x", "t")
            assert inp.value == "next"
            app._on_text_delta_ui("Final answer")
            app._on_assistant_ui("Final answer")
            await pilot.pause(0.1)
            log_text = "\n".join(line.text for line in app.query_one("#chat-log", RichLog).lines)
            assert "Start of thinking [not markup]" in log_text
            assert log_text.count("Final answer") == 1
            thoughts = app.evidence.read_events(event_types=["thinking"])
            assert list(thoughts)[-1]["payload"]["text"] == text
            app._on_thinking_delta_ui("Keep this interrupted thought")
            await pilot.press("escape")
            assert app.abort.set_flag
            app._on_thinking_delta_ui("late text must be dropped")
            await pilot.pause(0.1)
            log_text = "\n".join(line.text for line in app.query_one("#chat-log", RichLog).lines)
            assert "Keep this interrupted thought" in log_text
            assert "late text must be dropped" not in log_text
            app.turn_running = False

    asyncio.run(run())


def test_turn_falls_back_to_non_streaming(isolated_home):
    from agent.console.agent_loop import ConsoleAgent, TurnCallbacks
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.client = _FakeClient([{"text": "plain reply"}])   # no chat_stream attr
    deltas: list[str] = []
    cb = TurnCallbacks(on_text_delta=deltas.append)
    ok = agent.turn("hello", cb)
    assert ok is True
    assert deltas == []   # never streamed


def test_gemma_activity_follows_preparation_execution_and_parallel_tools(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod, activity
    from agent.console.agent_loop import AbortFlag
    from textual.widgets import Static, RichLog, Input

    async def run():
        cfg = ConsoleConfig.load()
        cfg.provider, cfg.model = "ollama", "gemma3:27b"
        cfg.auto_start_fiji = False
        app = tui_mod.ConsoleApp(cfg)
        app.fiji = fake_fiji(cfg.host, cfg.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            app.turn_running = True
            app.abort = AbortFlag()
            app._on_tool_preparing_ui("run_macro")
            app._tick_turn_status()
            assert "Writing macro/script" in str(app.query_one("#turn-status", Static).content)
            assert app._turn_tool_count == 0
            app._on_tool_start_ui("run_macro", {"code": 'run("Blobs");\nprint("done");'})
            assert app._activity_label == activity.RUNNING
            app._on_tool_start_ui("get_histogram", {})
            assert app._activity_label == activity.INSPECTING
            app._on_tool_result_ui("get_histogram", True, "256 bins")
            assert app._activity_label == activity.RUNNING  # other tool still running
            app._on_thinking_delta_ui("Waiting for the image")
            assert app._activity_label == activity.RUNNING
            app._on_tool_result_ui("run_macro", True, "Opened blobs")
            assert app._activity_label == activity.THINKING
            app._on_tool_start_ui("Shell", {"command": "python ij.py state"})
            assert app._activity_label == activity.INSPECTING
            app._on_tool_result_ui("Shell", True, "2 images open")
            app._on_text_delta_ui("Opened")
            assert app._activity_label == "Writing reply"
            await pilot.pause(0.1)
            text = "\n".join(line.text for line in app.query_one("#chat-log", RichLog).lines)
            assert 'run("Blobs");' in text and 'print("done");' in text
            assert "▁▃█▃▁" in text and "→ get_histogram: 256 bins" in text
            assert "get_state(" in text and "→ get_state: 2 images open" in text
            assert "Shell(" not in text and "Shell: 2 images open" not in text
            app.query_one("#chat-input", Input).focus()
            await pilot.press("n", "e", "x", "t", "escape")
            assert app.query_one("#chat-input", Input).value == "next"
            assert app.abort.set_flag
            app._on_tool_start_ui("late tool", {})
            assert app._turn_tool_count == 3
            app.turn_running = False

    asyncio.run(run())


def test_model_picker_switches_model(isolated_home, fake_fiji, monkeypatch):
    """ctrl+p opens the catalog picker and selecting a row switches the model."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    from agent.console.config import save_secret
    save_secret("anthropic", "sk-ant-test")  # a keyless provider routes to login

    async def _inner():
        cfg = ConsoleConfig.load()
        cfg.provider, cfg.model = "anthropic", "claude-opus-4-7"
        cfg.save()
        app = tui_mod.ConsoleApp(cfg)
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.5)
            assert app.agent is not None
            old_model = app.config.model
            await pilot.press("ctrl+p")
            await pilot.pause(0.4)
            assert type(app.screen).__name__ == "ModelPickerScreen"
            screen = app.screen
            # pick another anthropic row so no API key prompt is needed
            rows = [(i, e) for i, e in enumerate(screen._rows)
                    if e is not None and e.provider == "anthropic"
                    and e.model_id != old_model]
            assert rows, "catalog must list more than one anthropic model"
            index, entry = rows[0]
            screen.query_one("#picker-list").index = index
            await pilot.press("enter")
            await pilot.pause(0.2)
            assert type(app.screen).__name__ == "EffortPickerScreen"
            # Keep the provider's default effort for this model.
            await pilot.press("enter")
            await pilot.pause(0.5)
            assert app.config.model == entry.model_id
            assert app.agent is not None and app.agent.model == entry.model_id

    asyncio.run(_inner())


def test_model_picker_pin_and_filter(isolated_home, fake_fiji):
    """Pins persist to the override file and the search box filters rows."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    async def _inner():
        cfg = ConsoleConfig.load()
        cfg.provider, cfg.model = "anthropic", "claude-opus-4-7"
        cfg.save()
        app = tui_mod.ConsoleApp(cfg)
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.5)
            await pilot.press("ctrl+p")
            await pilot.pause(0.4)
            screen = app.screen
            index, entry = next((i, e) for i, e in enumerate(screen._rows) if e is not None)
            was_pinned = entry.pinned
            screen.query_one("#picker-list").index = index
            await pilot.press("ctrl+s")
            await pilot.pause(0.3)
            stored = app.catalog.overrides.load_as_map()
            assert stored[f"{entry.provider} {entry.model_id}"].pinned is (not was_pinned)

            search = screen.query_one("#picker-search")
            search.value = "zzz-no-such-model"
            await pilot.pause(0.3)
            assert all(e is None for e in screen._rows), "filter must empty the list"

    asyncio.run(_inner())


def test_launcher_model_flag_and_vendor_passthrough(isolated_home):
    """`imagejai --claude-opus-4-7 --dangerously-skip-permissions` must work."""
    from agent.console.__main__ import parse_args

    args = parse_args(["--claude-opus-4-7", "--dangerously-skip-permissions"])
    assert args.model_choice == ("claude-subscription", "claude-opus-4-7")
    assert args.vendor_args == ["--dangerously-skip-permissions"]

    # a vendor flag that takes a value must not be read as a subcommand
    args = parse_args(["--model", "claude-sonnet-4-6", "--permission-mode", "auto"])
    assert args.model_choice == ("claude-subscription", "claude-sonnet-4-6")
    assert args.vendor_args == ["--permission-mode", "auto"]

    args = parse_args(["--model", "claude-sonnet-4-6", "--effort", "high"])
    assert args.model_choice == ("claude-subscription", "claude-sonnet-4-6")
    assert args.effort == "high"

    # console options and subcommands still parse normally
    assert parse_args(["status"]).cmd == "status"
    assert parse_args(["use", "--doctor"]).integration_args == ["--doctor"]


def test_vendor_args_reach_the_subscription_command(isolated_home, monkeypatch):
    from agent.console import subscriptions as subs_mod

    monkeypatch.setattr(subs_mod, "_executable", lambda p: "codex.cmd")
    monkeypatch.setattr(subs_mod, "subscription_status", lambda p: (True, "ready"))
    subs_mod.set_vendor_extra_args(["--dangerously-skip-permissions"])
    try:
        agent = subs_mod.SubscriptionAgent("codex-subscription")
        assert agent.extra_args == ["--dangerously-skip-permissions"]

        captured = {}

        def fake_run(command, workspace, stdin=None):
            captured["command"] = command
            return 0, "", ""

        monkeypatch.setattr(agent, "_run", fake_run)
        try:
            agent._codex_turn("hello")
        except Exception:
            pass  # the fake returns no payload; we only care about the command
        assert "--dangerously-skip-permissions" in captured["command"]
    finally:
        subs_mod.set_vendor_extra_args(None)

def test_at_mention_lists_images_and_inserts(isolated_home, fake_fiji, tmp_path, monkeypatch):
    """Typing "@" offers files from the working folder and enter inserts one."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console.mention_ui import active_mention

    (tmp_path / "patient_A.tif").write_bytes(b"x")
    (tmp_path / "notes.txt").write_text("hi", encoding="utf-8")
    monkeypatch.chdir(tmp_path)

    assert active_mention("open @pat", 9) == (5, "pat")
    assert active_mention("mail me@example.com", 19) is None

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app.fiji._fake_posture = "PSEUDONYMISED"
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            chat_input = app.screen_stack[0].query_one("#chat-input")
            chat_input.focus()
            await pilot.pause(0.1)
            for char in "open @pat":
                await pilot.press(char if char != " " else "space")
            await pilot.pause(0.3)
            overlay = app.screen_stack[0].query_one("#mention-overlay")
            assert overlay.open, "the @ list must open"
            assert overlay.candidates[0].name == "patient_A.tif"
            await pilot.press("enter")
            await pilot.pause(0.3)
            assert not overlay.open
            assert chat_input.value.startswith("open ")
            # Pseudonymised mode keeps the model from seeing the
            # real name, but the console must be able to reverse it for Fiji
            inserted = chat_input.value.split(" ", 1)[1]
            assert "patient_A" not in inserted
            assert app.fiji.resolve_tokens(inserted) == str(tmp_path / "patient_A.tif")

    asyncio.run(_inner())


def test_rail_sections_render_and_dispatch(isolated_home, fake_fiji):
    """The ported Fiji rail is on screen and its buttons run real actions."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console import rail

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            main = app.screen_stack[0]
            buttons = main.query(".rail-item")
            assert len(buttons) == len(rail.rail_items())
            # every rail item id maps to a button id and back
            for item in rail.rail_items():
                main.query_one(f"#rail-{item.id.replace('.', '-')}")
            # an action gated on a live agent reports why it is disabled
            app.agent = None
            app._run_rail_item("agent.new_chat")
            await pilot.pause(0.3)
            status = str(main.query_one("#rail-status").content)
            assert status.strip(), "the rail status line must explain the refusal"

    asyncio.run(_inner())


def test_posture_badge_and_egress_lamp(isolated_home, fake_fiji):
    """Posture and egress are visible, and /posture changes the posture."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from agent.console.posture import Posture

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            main = app.screen_stack[0]
            assert app.posture.current is Posture.STANDARD
            assert "Standard" in str(main.query_one("#topbar-posture").content)

            app._slash("/posture pseudonymised")
            await pilot.pause(0.3)
            assert app.posture.current is Posture.PSEUDONYMISED
            assert "Pseudonymised" in str(main.query_one("#topbar-posture").content)

            app._slash("/posture standard")
            await pilot.pause(0.3)
            assert app.posture.current is Posture.STANDARD

            # an outbound turn lights the lamp and records a receipt
            app.config.provider, app.config.model = "anthropic", "claude-opus-4-7"
            app._record_egress("look at this image")
            await pilot.pause(0.2)
            assert app.egress.calls == 1
            assert app.receipts.recent(1)[0].provider == "anthropic"
            assert "●" in str(main.query_one("#topbar-egress").content)

            # a local provider must not light the lamp
            from agent.console.posture import is_local_provider
            assert is_local_provider("ollama")

    asyncio.run(_inner())


def test_rail_command_palette_opens_a_list_and_inserts(isolated_home, fake_fiji):
    """Rail popups are selectable lists, not text dumped into the log."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            app.agent = object()  # the palette needs a live session
            app._run_rail_item("agent.commands")
            await pilot.pause(0.6)
            assert type(app.screen).__name__ == "ListPickerScreen"
            screen = app.screen
            assert any(label.startswith("/help") for label, _ in screen.rows)
            await pilot.press("enter")
            await pilot.pause(0.4)
            chat_input = app.screen_stack[0].query_one("#chat-input")
            assert chat_input.value.startswith("/")

    asyncio.run(_inner())


def test_saved_standard_is_reconciled_with_fiji_before_display(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    config = ConsoleConfig.load()
    config.posture = "STANDARD"
    config.save()

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app.fiji._fake_posture = "PSEUDONYMISED"
        app.action_login = lambda: None
        app._start_event_thread = lambda: None
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.4)
            assert app.posture.current is Posture.PSEUDONYMISED
            assert app.config.posture == "PSEUDONYMISED"
            assert app._fiji_posture_known

    asyncio.run(_inner())


def test_failed_fiji_posture_change_keeps_previous_policy(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console.fiji import FijiError
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    class RefusingFiji(fake_fiji):
        def __init__(self, host, port):
            super().__init__(host, port)
            self._fake_posture = "PSEUDONYMISED"

        def set_privacy_posture(self, posture):
            raise FijiError("change refused")

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = RefusingFiji(app.config.host, app.config.port)
        app.action_login = lambda: None
        app._start_event_thread = lambda: None
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.3)
            app._slash("/posture standard")
            await pilot.pause(0.3)
            assert app.posture.current is Posture.PSEUDONYMISED
            assert app.config.posture == "PSEUDONYMISED"
            assert not app._posture_change_pending

    asyncio.run(_inner())


def test_fiji_stricter_posture_interrupts_active_reply(isolated_home, fake_fiji):
    from agent.console.agent_loop import AbortFlag
    from agent.console.config import ConsoleConfig
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app.action_login = lambda: None
        app._start_event_thread = lambda: None
        app._poll_fiji = lambda: None
        async with app.run_test(size=(120, 40)) as pilot:
            app._adopt_fiji_posture("STANDARD")
            app.turn_running = True
            app.abort = AbortFlag()
            app._render_event({"event": "data_governance.posture.requested",
                               "data": {"from": "STANDARD", "to": "ON_PREMISES"}})
            assert app.posture.current is Posture.ON_PREMISES
            assert app.abort.set_flag

    asyncio.run(_inner())


def test_slash_completion_and_drafting_during_reply(isolated_home, fake_fiji):
    """Typing and command completion stay available until a reply stops."""
    from agent.console.config import ConsoleConfig
    from agent.console.agent_loop import AbortFlag
    from agent.console import tui as tui_mod
    from textual.widgets import Input

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app.action_login = lambda: None
        app._start_event_thread = lambda: None
        app._poll_fiji = lambda: None
        app._load_slash_file_choices = lambda: None
        async with app.run_test(size=(120, 40)) as pilot:
            chat = app.query_one("#chat-input", Input)
            chat.focus()
            chat.value = "/mo"
            await pilot.pause(0.2)
            slash = app.query_one("#slash-overlay")
            assert slash.open
            assert slash.selected() == "/model"
            await pilot.press("tab")
            assert chat.value == "/model"
            chat.value = ""
            app.turn_running = True
            app.abort = AbortFlag()
            app._set_busy(True)
            assert not chat.disabled
            chat.focus()
            for key in "next step":
                await pilot.press("space" if key == " " else key)
            assert chat.value == "next step"
            await pilot.press("enter")
            assert chat.value == "", "Enter sends a correction during a reply"
            assert app.prompt_queue.snapshot()[0]["text"] == "next step"
            await pilot.press("/", "m", "o")
            await pilot.pause(0.1)
            assert slash.open
            await pilot.press("escape")
            assert app.abort.set_flag
            assert app._turn_cancel_requested
            assert not app.prompt_queue.snapshot()
            assert chat.value == "/mo"

    asyncio.run(_inner())


def test_autocomplete_lists_stay_above_input_after_terminal_resize(
    isolated_home, fake_fiji, tmp_path,
):
    """Command and file completion must not overlap the editable input."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from textual.widgets import Input

    for index in range(10):
        (tmp_path / f"image_{index:02d}.tif").write_bytes(b"test")

    async def run():
        cfg = ConsoleConfig.load()
        cfg.provider, cfg.model = "ollama", "gemma3:27b"
        cfg.auto_start_fiji = False
        app = tui_mod.ConsoleApp(cfg)
        app.fiji = fake_fiji(cfg.host, cfg.port)
        app._start_event_thread = lambda: None
        app._poll_fiji = lambda: None
        app._load_slash_file_choices = lambda: None
        app._mention_folder = lambda: tmp_path
        async with app.run_test(size=(120, 40)) as pilot:
            chat = app.query_one("#chat-input", Input)
            for width, height in ((120, 40), (100, 24), (80, 18)):
                await pilot.resize_terminal(width, height)
                for text, selector in (("/", "#slash-overlay"), ("open @", "#mention-overlay")):
                    chat.focus()
                    chat.value = text
                    chat.cursor_position = len(text)
                    await pilot.pause(0.2)
                    popup = app.query_one(selector)
                    assert popup.open
                    assert popup.region.height > 2
                    assert popup.region.bottom <= chat.region.y, (width, height, popup.region, chat.region)
                    assert chat.region.bottom <= app.query_one("#chat-column").region.bottom
                    assert not popup.region.overlaps(chat.region)
                    x, y = chat.content_region.x + 1, chat.content_region.y
                    assert app.screen.get_widget_at(x, y)[0] is chat
                    await pilot.click(chat, offset=(2, 1))
                    assert app.focused is chat
                    await pilot.press("down")
                    assert app.focused is chat, "choosing a completion must leave typing focused"
                    # Closing returns the same space to chat without moving
                    # the typing box away from the bottom of the column.
                    input_region = chat.region
                    chat.value = ""
                    await pilot.pause(0.1)
                    assert not popup.open and chat.region == input_region
    asyncio.run(run())


def test_live_event_panel_filters_readable_history(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod
    from textual.widgets import Input, RichLog, Select

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app.action_login = lambda: None
        app._start_event_thread = lambda: None
        app._poll_fiji = lambda: None
        async with app.run_test(size=(120, 40)) as pilot:
            app._render_event({"event": "image.opened", "data": {"title": "cells.tif"}})
            app._render_event({"event": "dialog.appeared", "data": {"title": "Threshold"}})
            log = app.query_one("#events-log", RichLog)
            assert len(log.lines) == 2
            app.query_one("#events-category", Select).value = "dialog"
            await pilot.pause(0.1)
            assert len(log.lines) == 1
            app.query_one("#events-search", Input).value = "no match"
            await pilot.pause(0.1)
            assert len(log.lines) == 0
            assert [line.message for line in app.event_feed.lines] == [
                "Opened image: cells.tif", "Dialog opened: Threshold"]

            app._render_event({"event": "data_governance.posture.requested",
                               "data": {"from": "PSEUDONYMISED", "to": "STANDARD"}})
            assert app.posture.current.name == "STANDARD"
            assert app.config.posture == "STANDARD"
            assert app.event_feed.lines[-1].message == "Fiji privacy posture: Standard"

    asyncio.run(_inner())


def test_macro_picker_edit_and_context_do_not_run_macro(
    isolated_home, fake_fiji, tmp_path,
):
    from agent.console.config import ConsoleConfig
    from agent.console import macros, tui as tui_mod
    from textual import events
    from textual.widgets import ListView

    macro_file = tmp_path / "count.ijm"
    macro_file.write_text('run("Measure");\nrun("Close All");\n', encoding="utf-8")
    item = macros.MacroItem(
        macros.MacroSource.USER, macro_file.name, path=macro_file)
    sent = []
    folders = []

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app.fiji.command = lambda payload: sent.append(payload) or {"ok": True}
        app._open_macro_folder = folders.append
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            app._show_rail_data("My Macros", macros.macro_popup_model([item]))
            await pilot.pause(0.1)
            assert app.screen.macro_actions
            await pilot.click("#picklist-edit")
            await pilot.pause(0.3)
            assert len(sent) == 1
            assert sent[0]["command"] == "run_script"
            assert sent[0]["source"] == "rail:script-editor"
            assert "execute_macro" not in [payload["command"] for payload in sent]

            failures = []
            app._log = failures.append
            app.fiji.command = lambda payload: sent.append(payload) or {
                "ok": True,
                "result": {"success": False, "error": {"message": "editor unavailable"}},
            }
            app._open_macro_editor(item)
            await pilot.pause(0.3)
            assert any("Could not open Fiji's Script Editor: editor unavailable" in line
                       for line in failures)
            app._fiji_call(
                "count.ijm", "running count.ijm",
                lambda: {"ok": True, "result": {
                    "success": False, "error": {"message": "bad macro"},
                }},
                macro_payload={"code": item.load_code(), "language": "ijm"},
            )
            await pilot.pause(0.3)
            assert app.macro_journal.snapshot()[0].success is False
            assert any("bad macro" in line for line in failures)

            app._show_rail_data("My Macros", macros.macro_popup_model([item]))
            await pilot.pause(0.1)
            outer = app.screen
            row = outer.query_one("#picklist-list")
            outer.on_mouse_down(events.MouseDown(
                widget=row, x=0, y=0, delta_x=0, delta_y=0,
                button=3, shift=False, meta=False, ctrl=False,
                style=__import__("rich.style", fromlist=["Style"]).Style(meta={"option": 0}),
            ))
            await pilot.pause(0.1)
            assert app.screen.title_text == "Macro actions"
            action = app.screen._visible[1][1]
            assert action["macro_action"] == "open_folder"
            app.screen.dismiss(action)
            await pilot.pause(0.2)
            assert folders == [item]
            assert len(sent) == 2

    asyncio.run(_inner())


def test_macro_rail_lists_selected_fiji_and_save_does_not_run(
    isolated_home, fake_fiji, tmp_path,
):
    from agent.console.config import ConsoleConfig
    from agent.console import macros, tui as tui_mod

    root = tmp_path / "Fiji.app"
    (root / "macros").mkdir(parents=True)
    (root / "macros" / "built-in.ijm").write_text("run('Measure');", encoding="utf-8")
    sent = []
    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app.fiji = fake_fiji(app.config.host, app.config.port)
    app.fiji.select_installation(root)
    found = macros.popup_action("my", **app._rail_kwargs("macros.my"))
    assert found.status == "1 macros found"
    assert any(isinstance(payload, macros.MacroItem)
               for group in found.data["groups"] for payload in group["items"])

    async def _inner():
        app.fiji.command = lambda payload: sent.append(payload) or {"ok": True}
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.2)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            session_item = macros.MacroItem(
                macros.MacroSource.SESSION, "count cells", language="ijm",
                code='run("Measure");\nrun("Close All");',
            )
            app._rail_choice("Save Macro", session_item)
            await pilot.pause(0.1)
            assert type(app.screen).__name__ == "MacroNameScreen"
            await pilot.click("#macro-name-save")
            await pilot.pause(0.2)
            saved = root / "ImageJAI" / "macros" / "count_cells.ijm"
            assert saved.read_text(encoding="utf-8") == session_item.code
            assert sent == []
            app._rail_choice("Save Macro", session_item)
            await pilot.pause(0.1)
            await pilot.click("#macro-name-save")
            await pilot.pause(0.1)
            assert type(app.screen).__name__ == "ConfirmationScreen"
            app.screen.dismiss(None)
            await pilot.pause(0.1)
            assert saved.read_text(encoding="utf-8") == session_item.code

    asyncio.run(_inner())


def test_console_macro_journal_feeds_session_picker(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import macros, tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    code = 'run("Blobs");'
    app._record_tool_evidence(
        "tool-1", "execute_macro", {"code": code}, True, "ok")
    result = macros.popup_action("session", **app._rail_kwargs("macros.session"))
    assert result.status == "1 macros found"
    item = result.data["groups"][0]["items"][0]
    assert item.code == code
    assert item.source == macros.MacroSource.SESSION


def test_console_macro_history_is_saved_immediately_and_isolated_by_session(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import macros, tui as tui_mod

    cfg = ConsoleConfig.load()
    app = tui_mod.ConsoleApp(cfg)
    first, second = app.store.create(), app.store.create()
    code = 'run("Blobs");'
    app._select_session(first)
    app._record_tool_evidence("macro-1", "run_macro", {"code": code}, True, "ok")
    app._record_tool_evidence("macro-2", "run_macro", {"code": code}, False, "Macro failed")
    # A fresh console can recover the journal before the turn is persisted.
    restored = tui_mod.ConsoleApp(cfg)
    restored._select_session(restored.store.get(first.id))
    entry, = restored.macro_journal.snapshot()
    assert entry.code == code and entry.run_count == 2
    assert not entry.success and entry.failure_message == "Macro failed"
    app._select_session(second)
    assert app.macro_journal.snapshot() == []
    app._record_macro_payload({"code": "print(1)", "language": "groovy"}, True, "")
    app._select_session(first)
    assert [entry.code for entry in app.macro_journal.snapshot()] == [code]
    app._select_session(second)
    items = macros.popup_action("session", **app._rail_kwargs("macros.session"))
    assert items.data["groups"][0]["items"][0].language == "groovy"
    assert items.data["groups"][0]["items"][0].code == "print(1)"


def test_console_recovers_old_session_macro_history_from_evidence(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console.evidence import EvidenceJournal
    from agent.console.sessions import sessions_dir
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    session = app.store.create()
    journal = EvidenceJournal(sessions_dir(), session.id)
    code = 'run("Blobs");'
    journal.record_tool_pair("execute_macro", {"code": code}, "ok", correlation_id="native")
    journal.record_tool_pair("Shell", {"command": '@\'\n' + code + '\n\'@ | python ij.py macro --stdin'},
                             "ok", correlation_id="vendor")
    journal.record_tool_pair("run_script", {"code": "print(1)", "language": "groovy"},
                             "Refused: requires approval", ok=False, correlation_id="refused")
    app._select_session(session)
    entry, = app.macro_journal.snapshot()
    assert entry.code == code and entry.run_count == 2
    original_timestamp = entry.started_at
    # Selecting again loads the saved index without replaying the same runs.
    app._select_session(session)
    entry, = app.macro_journal.snapshot()
    assert entry.run_count == 2 and entry.started_at == original_timestamp


def test_wrapped_turn_shows_fiji_tool_and_saves_macro_without_control_json(
    isolated_home, fake_fiji, monkeypatch,
):
    from agent.console import macros, tui as tui_mod
    from agent.console.config import ConsoleConfig
    from agent.console.agent_loop import ConsoleAgent
    from agent.console.wrapped_tools import ACTION_OPEN, ACTION_CLOSE, RESULT_OPEN
    from agent.console.sessions import SessionStore
    from textual.widgets import RichLog

    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "anthropic", "claude-opus-4-7"
    cfg.auto_start_fiji = False
    cfg.attach_images = "never"
    calls = []
    def run_macro(code: str):
        """Execute a Fiji macro."""
        calls.append(code)
        return {"ok": True, "result": {"success": True, "newImages": ["blobs.gif"]}}
    code = 'run("Blobs");'
    frame = ACTION_OPEN + json.dumps({"id": "open-blobs", "tool": "run_macro",
                                     "arguments": {"code": code}}) + ACTION_CLOSE
    agent = ConsoleAgent(cfg.provider, cfg.model, api_key="fixture-not-a-real-key")
    agent.tools = [run_macro]
    class StreamClient(_FakeClient):
        def chat_stream(self, messages, tools, model, **kwargs):
            assert tools == []
            reply = self.chat(messages, tools, model)
            if reply["text"].startswith(ACTION_OPEN):
                kwargs["on_thinking"]("Opening the sample using the Fiji macro tool")
            kwargs["on_delta"](reply["text"])
            return reply
    agent.client = StreamClient([{"text": frame}, {"text": "Blobs is open"}])
    monkeypatch.setattr(tui_mod, "create_agent", lambda *args, **kwargs: agent)

    async def run():
        app = tui_mod.ConsoleApp(cfg)
        app.fiji = fake_fiji(cfg.host, cfg.port)
        app._start_event_thread = lambda: None
        async with app.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.3)
            app._submit_text("Open Blobs")
            await pilot.pause()
            from agent.console.cost_notices import CostNoticeScreen
            assert isinstance(app.screen, CostNoticeScreen)
            app.screen.query_one("#cost-continue").press()
            await pilot.pause()
            for _ in range(100):
                await pilot.pause(0.05)
                if not app.turn_running:
                    break
            assert not app.turn_running
            assert agent.fiji_connection is app.fiji
            text = "\n".join(line.text for line in app.query_one("#chat-log", RichLog).lines)
            # One-line arguments display escaped quotes; the journal below
            # must retain the exact unescaped source for saving and rerunning.
            assert "run_macro" in text and "Blobs" in text
            assert "Opening the sample" in text and "Blobs is open" in text
            assert ACTION_OPEN not in text and RESULT_OPEN not in text and "Shell" not in text
            assert "Opened blobs.gif" in text and "details" in text
            assert '"newImages"' not in text and '"success"' not in text
            items = macros.popup_action("session", **app._rail_kwargs("macros.session"))
            assert items.data["groups"][0]["items"][0].code == code
            saved = SessionStore(cfg).get(app.session.id)
            assert saved.action_receipts["open-blobs"]["ok"]
            assert calls == [code]
            # Anthropic stores assistant text as content blocks. Restoring
            # that shape must hide action frames as well as plain strings.
            for message in app.session.messages:
                if message.get("role") == "assistant" and message.get("content") == frame:
                    message["content"] = [{"type": "text", "text": frame}]
            app.query_one("#chat-log", RichLog).clear()
            app._render_session_messages()
            await pilot.pause(0.1)
            restored = "\n".join(line.text for line in app.query_one("#chat-log", RichLog).lines)
            assert ACTION_OPEN not in restored and RESULT_OPEN not in restored
            assert "Blobs is open" in restored
            assert "Opened blobs.gif" in restored and "details" in restored
    asyncio.run(run())


def test_tool_return_click_opens_full_artifact_and_preserves_running_draft(
    isolated_home, fake_fiji, tmp_path,
):
    from agent.console import tui as tui_mod, activity
    from agent.console.config import ConsoleConfig
    from agent.console.agent_loop import AbortFlag
    from agent.console.tool_results_ui import ToolResultScreen
    from textual.widgets import Input, RichLog, TextArea

    raw = json.dumps({"ok": True, "result": {"success": True,
        "newImages": ["blobs.gif"], "output": "hidden raw data " * 4000,
        "tail": "FULL_RETURN_END"}})

    async def run():
        cfg = ConsoleConfig.load()
        cfg.provider, cfg.model = "ollama", "gemma3:27b"
        cfg.auto_start_fiji = False
        app = tui_mod.ConsoleApp(cfg)
        app.fiji = fake_fiji(cfg.host, cfg.port)
        app._start_event_thread = lambda: None
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.2)
            chat = app.query_one("#chat-log", RichLog)
            chat_input = app.query_one("#chat-input", Input)
            chat_input.value = "my next question"
            app.turn_running = True
            app.abort = AbortFlag()
            first_args = {"code": 'run("Blobs");'}
            second_args = {"code": 'print("second");'}
            app._on_tool_start_ui("run_macro", first_args)
            app._on_tool_start_ui("run_macro", second_args)
            # The full evidence callback, rather than the 20,480-character
            # preview, supplies the clicked return. Calls finish out of order.
            await asyncio.to_thread(app._record_and_show_tool_result, "second", "run_macro",
                                    second_args, True, '{"result":{"success":true}}')
            assert app._active_tools == [("run_macro", first_args)]
            await asyncio.to_thread(app._record_and_show_tool_result, "first", "run_macro", first_args, True, raw)
            assert not app._active_tools
            # Subscription clients style a legacy Shell call by its Fiji tool,
            # while evidence still records the original submitted command.
            app._on_tool_start_ui("get_state", {})
            await asyncio.to_thread(app._record_and_show_tool_result, "legacy", "Shell",
                                    {"command": "python ij.py state"}, True, '{"result":{"images":[]}}')
            assert not app._active_tools
            await pilot.pause(0.1)
            visible = "\n".join(line.text for line in chat.lines)
            assert "Opened blobs.gif" in visible and "details" in visible
            assert "hidden raw data" not in visible and "FULL_RETURN_END" not in visible
            detail = next(d for d in app._tool_result_details.values() if "Opened blobs" in d.summary)
            assert detail.artifact is not None and detail.raw == ""
            assert detail.read() == raw
            row = next(i for i, line in enumerate(chat.lines) if "Opened blobs.gif" in line.text)
            x = chat.scrollable_content_region.x - chat.region.x + 5
            y = chat.scrollable_content_region.y - chat.region.y + row - int(chat.scroll_y)
            await pilot.click(chat, offset=(x, y))
            await pilot.pause(0.1)
            assert isinstance(app.screen, ToolResultScreen)
            full = app.screen.query_one("#tool-result-content", TextArea)
            assert json.loads(full.text) == json.loads(raw)
            assert "FULL_RETURN_END" in full.text and len(full.text) > 20_480
            await pilot.click("#tool-result-original")
            assert full.text == raw
            # Agent activity continues behind the detail window.
            app._on_thinking_delta_ui("Checking the sample while details are open")
            app._on_tool_start_ui("get_histogram", {})
            app._on_tool_result_ui("get_histogram", True, '{"result":{"mean":22.5}}')
            assert isinstance(app.screen, ToolResultScreen)
            assert app._activity_label == activity.THINKING
            await pilot.press("escape")
            await pilot.pause(0.1)
            assert not isinstance(app.screen, ToolResultScreen)
            assert app.turn_running and not app.abort.set_flag
            assert chat_input.value == "my next question" and app.focused is chat_input
            assert "Mean: 22.5" in "\n".join(line.text for line in chat.lines)
            # Resetting a conversation invalidates old links, with no ID reuse.
            old_id = app._next_tool_result_detail
            app._reset_conversation_ui()
            app.action_tool_result(old_id)
            assert not isinstance(app.screen, ToolResultScreen)
            app._on_tool_result_ui("get_state", True, '{"result":{"images":[]}}')
            assert app._next_tool_result_detail > old_id
            assert len(app._tool_result_details) == 1
            # The return remains small; the capture preview is available on
            # demand in the same window as the complete saved path.
            from PIL import Image
            capture = tmp_path / "capture.png"
            Image.new("RGB", (8, 8), color="red").save(capture)
            app._on_tool_result_ui("capture_image", True, str(capture))
            assert str(capture) not in "\n".join(line.text for line in chat.lines)
            app.action_tool_result(app._next_tool_result_detail)
            await pilot.pause(0.1)
            assert app.screen.preview is not None
            assert app.screen.query_one("#tool-result-preview")
            assert app.screen.query_one("#tool-result-content", TextArea).text == str(capture)
            await pilot.press("escape")
            await pilot.pause(0.1)
            chat.max_lines = 1
            app._on_tool_result_ui("get_state", True, '{"result":{"images":[]}}')
            assert len(app._tool_result_details) == 1
            app.turn_running = False
    asyncio.run(run())


def test_list_picker_filter_and_empty_state():
    from agent.console.picklist_ui import rows_from_model

    rows, empty = rows_from_model({"empty": "No macros found", "groups": []})
    assert rows == [] and empty == "No macros found"

    model = {"empty": None, "groups": [
        {"title": "Saved", "items": [{"name": "segment.ijm"}, {"name": "count.ijm"}]},
    ]}
    rows, empty = rows_from_model(model)
    assert rows[0][0].startswith("__group__")
    assert [label for label, payload in rows if payload is not None] == [
        "segment.ijm", "count.ijm"]


def test_suggestion_chips_insert_without_sending(isolated_home, fake_fiji):
    """Reviewed intent phrases appear while typing; click only edits the input."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            main = app.screen_stack[0]
            chat_input = main.query_one("#chat-input")
            chat_input.value = "pixel siz"
            await pilot.pause(0.3)
            assert app.suggestion_chips
            assert app.suggestion_chips[0].phrase == "pixel size"
            assert not main.query_one("#suggestion-row").has_class("hidden")

            app.action_accept_suggestion()
            assert chat_input.value == "pixel size"
            assert app.turn_running is False

    asyncio.run(_inner())


def test_clarification_chip_sends_selected_phrase(isolated_home, fake_fiji):
    """Structured clarification choices render at transcript bottom and send."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            main = app.screen_stack[0]
            sent = []
            app._submit_text = sent.append
            app.show_clarifications(["threshold this image", "measure the ROI", "ignored"])
            assert [c.phrase for c in app.clarification_chips] == [
                "threshold this image", "measure the ROI"]
            assert not main.query_one("#clarification-row").has_class("hidden")

            app._accept_clarification(1)
            assert sent == ["measure the ROI"]
            assert main.query_one("#clarification-row").has_class("hidden")

    asyncio.run(_inner())


def test_browse_files_inserts_only_tokens_under_safe_posture(isolated_home, fake_fiji, tmp_path):
    """Real names stay in the modal; the chat receives token + shape only."""
    from PIL import Image
    from agent.console.config import ConsoleConfig
    from agent.console import browse
    from agent.console import tui as tui_mod

    secret = tmp_path / "patient-A-control.tif"
    Image.new("L", (8, 6), color=7).save(secret)

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        app.fiji._fake_posture = "PSEUDONYMISED"
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            entries = browse.scan_folder(tmp_path, token_map=app.path_tokens)
            app._open_browse_screen(tmp_path, entries)
            await pilot.pause(0.4)
            assert type(app.screen).__name__ == "BrowseFilesScreen"
            selection = app.screen.query_one("#browse-list")
            selection.select_all()
            await pilot.pause(0.2)
            await pilot.click("#browse-insert")
            await pilot.pause(0.4)

            chat = app.screen_stack[0].query_one("#chat-input").value
            assert "image-" in chat
            assert "patient-A-control" not in chat
            assert str(tmp_path) not in chat
            assert '"size_x":8' in chat and '"size_y":6' in chat

    asyncio.run(_inner())


def test_browse_standard_posture_may_insert_real_path(isolated_home, fake_fiji, tmp_path):
    from agent.console.browse import SeriesEntry
    from agent.console.config import ConsoleConfig
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    image = tmp_path / "visible.tif"
    entry = SeriesEntry(image, -1, str(image), "visible", size_x=2, size_y=3)

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            app.posture.request_posture(Posture.STANDARD, tmp_path, reason="test")
            app._browse_chosen({"entries": [entry], "tag": "control"})
            chat = app.screen_stack[0].query_one("#chat-input").value
            assert str(image) in chat
            assert "control" in chat

    asyncio.run(_inner())


def test_confirmation_queue_choice_and_cancel(isolated_home, fake_fiji):
    """Confirmations are ID-keyed, bounded, queued, and torn down on cancel."""
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen()
                await pilot.pause(0.1)
            choices = []
            assert app.request_confirmation("first", "Use this threshold?", ["Use it", "No"], choices.append)
            assert app.request_confirmation("second", "Continue?", ["Continue"], choices.append)
            assert not app.request_confirmation("first", "duplicate", ["x"], choices.append)
            await pilot.pause(0.3)
            assert type(app.screen).__name__ == "ConfirmationScreen"
            await pilot.click("#confirm-option-1")
            await pilot.pause(0.4)
            assert choices == ["No"]
            assert app._active_confirmation == "second"
            assert type(app.screen).__name__ == "ConfirmationScreen"

            assert app.cancel_confirmation("second")
            await pilot.pause(0.4)
            assert app._active_confirmation is None
            assert not app._pending_confirmations
            assert type(app.screen).__name__ != "ConfirmationScreen"

    asyncio.run(_inner())


def test_agent_harness_digest_replaces_dynamic_block(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    base = agent.messages[0]["content"]
    agent.set_harness_digest("first memory")
    assert "[imagejai-harness]" in agent.messages[0]["content"]
    assert "first memory" in agent.messages[0]["content"]
    agent.set_harness_digest("second memory")
    assert "first memory" not in agent.messages[0]["content"]
    assert agent.messages[0]["content"].count("[imagejai-harness]") == 1
    agent.set_harness_digest(None)
    assert agent.messages[0]["content"] == base


def test_lossless_tool_callback_is_larger_than_prompt_copy(isolated_home):
    from agent.console.agent_loop import ConsoleAgent, TurnCallbacks, MAX_TOOL_RESULT_CHARS

    def large_result():
        return "x" * (MAX_TOOL_RESULT_CHARS + 2000)

    large_result.__name__ = "large_result"
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.tools = [large_result]
    agent.client = _FakeClient([
        {"text": "", "calls": [_Call("large_result", {})]},
        {"text": "done"},
    ])
    records = []
    cb = TurnCallbacks(
        on_tool_record=lambda cid, name, args, ok, result: records.append(
            (cid, name, args, ok, result)),
    )
    assert agent.turn("run it", cb)
    assert len(records[0][4]) == MAX_TOOL_RESULT_CHARS + 2000
    # Provider history receives a bounded copy, not the large evidence string.
    assert any("incomplete preview" in str(message.get("content", "")) for message in agent.messages)


def test_console_evidence_journal_and_large_artifact(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    session = app.store.create()
    app._select_session(session)
    app._record_text_evidence("user", "analyse image-abc.tif")
    long_result = "row,area\n" + ("1,12.5\n" * 5000)
    app._record_tool_evidence("call-1", "get_results_table", {}, True, long_result)

    events = app.evidence.read_events()
    assert [event["type"] for event in events] == [
        "user", "tool_call", "tool_result"]
    result = events[-1]
    assert result["payload"]["correlation_id"] == "call-1"
    assert result["artifact_refs"]
    ref = result["artifact_refs"][0]
    assert app.artifacts.verify(ref)["ok"] is True
    assert long_result not in app.evidence.path.read_text(encoding="utf-8")


def test_summarize_state_keeps_scientific_identity():
    from agent.console.fiji import summarize_state
    state = summarize_state({"result": {"images": [{"title": "x"}], "active_image": {
        "id": 7, "title": "x", "path": "C:/data/x.tif", "image_revision": 12,
        "channel": 2, "slice": 4, "frame": 3, "calibration": {"pixel_width": 0.3},
    }}})
    assert state["active_path"] == "C:/data/x.tif"
    assert state["active_id"] == 7 and state["active_revision"] == 12
    assert (state["channel"], state["slice"], state["frame"]) == (2, 4, 3)
    assert state["calibration"]["pixel_width"] == 0.3


def test_console_injects_only_confirmed_privacy_sanitized_harness(isolated_home, tmp_path):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    class Agent:
        def __init__(self):
            self.digest = None
        def set_harness_digest(self, value):
            self.digest = value

    image = tmp_path / "patient-secret.tif"
    image.write_bytes(b"x")
    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    from agent.console.posture import Posture
    app.posture.request_posture(Posture.PSEUDONYMISED)
    app.agent = Agent()
    token = app.path_tokens.token_for_path(image)
    candidate = app.global_harness.propose(
        kind="constraint", scope="user", title="Protect the source",
        content=f"Never alter {image}; duplicate it first", source="test",
    )
    app._apply_harness_digest("protect source")
    assert "Protect the source" not in app.agent.digest

    app.global_harness.promote(
        candidate.id, expected_version=candidate.version,
        reviewer="biologist", evidence="confirmed during session",
    )
    app._apply_harness_digest("protect source")
    assert "Protect the source" in app.agent.digest
    assert str(image) not in app.agent.digest
    assert token in app.agent.digest
    assert "reviewed knowledge only" in app.agent.digest


def test_console_agent_compacts_without_changing_evidence_source(isolated_home):
    from copy import deepcopy
    from agent.console.agent_loop import ConsoleAgent

    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.client = _FakeClient([{"text": "Goal: quantify nuclei. Completed preprocessing."}])
    macro = 'run("Measure");'
    agent.messages.extend([
        {"role": "user", "content": "Count nuclei in image-deadbeef.tif"},
        {"role": "assistant", "content": f"Use this exact macro: {macro}"},
        {"role": "user", "content": "Calibration is 0.325 um/pixel and C=2 Z=7 T=4"},
        {"role": "assistant", "content": "The measured mean is 1200.375 AU."},
        {"role": "user", "content": "Now continue with the final check."},
    ])
    original = deepcopy(agent.messages)
    report = agent.compact(keep_recent_tokens=12)
    assert report["compacted"] is True
    assert len(agent.messages) < len(original)
    checkpoint = agent.messages[1]["content"]
    assert "[compaction-summary]" in checkpoint
    assert "quantify nuclei" in checkpoint
    assert macro in checkpoint
    assert "0.325" in checkpoint and "1200.375" in checkpoint
    # The caller's original snapshot remains lossless and untouched.
    assert original[1]["content"] == "Count nuclei in image-deadbeef.tif"


def test_console_agent_compaction_threshold(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    # A fixed context tests the boundary independently of catalogue growth.
    agent.messages = [{"role": "system", "content": "Fiji tools"},
                      {"role": "user", "content": "x" * 2000}]
    assert agent.needs_compaction(600, reserve_tokens=100)
    assert not agent.needs_compaction(10_000, reserve_tokens=100)


def test_memory_commands_require_explicit_promotion(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    session = app.store.create()
    app._select_session(session)
    app._remember_command(
        "session constraint | Preserve raw data | Duplicate before measuring")
    entries = app.session_harness.list()
    assert len(entries) == 1
    candidate = entries[0]
    assert candidate.status == "candidate"

    class Agent:
        def set_harness_digest(self, value):
            self.digest = value
    app.agent = Agent()
    app._apply_harness_digest("measure raw data")
    assert "Preserve raw data" not in app.agent.digest

    app._memory_command(
        f"approve {candidate.id} | explicitly confirmed by the biologist")
    approved = app.session_harness.get(candidate.id)
    assert approved.status == "session_confirmed"
    app._apply_harness_digest("measure raw data")
    assert "Preserve raw data" in app.agent.digest


def test_memory_command_refuses_unknown_raw_path(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app._select_session(app.store.create())
    app._remember_command(
        "session fact | Patient file | source is C:/Patients/Alice/raw.tif")
    assert app.session_harness.list() == []


def test_compaction_report_is_journalled(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    class Agent:
        def compact(self, **kwargs):
            return {"compacted": True, "tokens_before": 1000, "tokens_after": 200,
                    "summary_input_chars": 500, "evicted_artifacts": []}
        def needs_compaction(self, window, reserve):
            return True

    cfg = ConsoleConfig(provider="ollama", model="gemma3:27b")
    app = tui_mod.ConsoleApp(cfg)
    app._select_session(app.store.create())
    app.agent = Agent()
    report = app._run_compaction(force=False)
    assert report["compacted"] is True
    events = app.evidence.query(event_type="compaction")
    assert events[0]["payload"]["tokens_before"] == 1000
    assert events[0]["payload"]["tokens_after"] == 200


def test_console_forced_compaction_preserves_measurement_and_journals(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    class LocalSummary:
        def chat(self, messages, tools, model, **kwargs):
            assert "Summarise the earlier image-analysis conversation" in messages[1]["content"]
            return {"text": "Continue measuring the synthetic image."}

        def extract_text(self, reply):
            return reply["text"]

    app = tui_mod.ConsoleApp(ConsoleConfig(provider="ollama", model="gemma3:27b"))
    app._select_session(app.store.create())
    compactor = ConsoleAgent("ollama", "gemma3:27b")
    compactor.client = LocalSummary()
    compactor.messages.extend([
        {"role": "user", "content": "Calibration is 0.325 um/pixel.\n" + "context " * 20000},
        {"role": "assistant", "content": "The image is a synthetic stack."},
        {"role": "user", "content": "Continue with the projection."},
    ])
    app.agent = compactor

    report = app._run_compaction(force=True)

    assert report["compacted"] is True, report
    assert report["tokens_after"] < report["tokens_before"]
    assert "0.325" in compactor.messages[1]["content"]
    events = app.evidence.query(event_type="compaction")
    assert events[-1]["payload"]["compacted"] is True


def test_refinement_returns_untrusted_candidates_only(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.client = _FakeClient([{"text": json.dumps({"proposals": [{
        "kind": "failure_fix", "title": "Probe first",
        "content": "Probe unfamiliar plugin dialogs before running them",
        "scope": "shared", "status": "validated",
        "applicability": {"task": "plugin"},
    }]})}])
    proposals = agent.propose_learnings("plugin failed, then probing worked")
    assert proposals == [{
        "kind": "failure_fix", "title": "Probe first",
        "content": "Probe unfamiliar plugin dialogs before running them",
        "applicability": {"task": "plugin"},
    }]
    assert "scope" not in proposals[0] and "status" not in proposals[0]


def test_refinement_rejects_non_json(isolated_home):
    from agent.console.agent_loop import ConsoleAgent
    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.client = _FakeClient([{"text": "I think you should remember everything"}])
    with pytest.raises(ValueError, match="valid JSON"):
        agent.propose_learnings("evidence")


def test_refinement_draft_needs_two_explicit_user_steps(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app._select_session(app.store.create())
    app.pending_learning_drafts = [{
        "kind": "validation_rule", "title": "Check the mask",
        "content": "Verify foreground fraction before measuring",
        "applicability": {"task": "segmentation"},
    }]
    app._refine_command("accept 1 session")
    entry = app.session_harness.list()[0]
    assert entry.status == "candidate"
    assert entry.source == "refinement:draft"
    assert app.pending_learning_drafts == []

    app._memory_command(f"approve {entry.id} | biologist reviewed the validation rule")
    assert app.session_harness.get(entry.id).status == "session_confirmed"


def test_refinement_evidence_redacts_unregistered_absolute_paths(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app._select_session(app.store.create())
    app.evidence.append("user", {"text": "Open C:/Patients/Alice/raw.tif now"})
    outgoing = app._refinement_evidence_text()
    assert "C:/Patients" not in outgoing
    assert "Alice" not in outgoing
    assert "[local-path-redacted]" in outgoing


def test_console_skill_catalog_is_progressive_and_explicit(isolated_home, tmp_path):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    image = tmp_path / "image.tif"; image.write_bytes(b"x")
    skill_dir = tmp_path / ".imagejai" / "skills" / "nuclei-check"
    skill_dir.mkdir(parents=True)
    skill_dir.joinpath("SKILL.md").write_text(
        "---\nname: nuclei-check\ndescription: Validate nuclei masks\n"
        "disable-model-invocation: true\nmetadata:\n  recipe: cell_counting\n---\n"
        "PRIVATE BODY: inspect split and merge errors.\n", encoding="utf-8")

    class Agent:
        def set_skill_catalog(self, value): self.catalog = value
        def set_active_skill(self, name, body): self.active = (name, body)

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app.agent = Agent()
    app._fiji_state = {"active_path": str(image)}
    app._apply_skill_catalog("count nuclei")
    assert "Validate nuclei masks" in app.agent.catalog
    assert "PRIVATE BODY" not in app.agent.catalog
    assert str(tmp_path) not in app.agent.catalog
    assert "skill:nuclei-check" in app.agent.catalog

    # Explicit user command may load a skill that disables model invocation.
    app._skill_command("nuclei-check")
    assert app.agent.active[0] == "nuclei-check"
    assert "split and merge" in app.agent.active[1]


def test_image_attachment_follows_posture_and_setting(isolated_home, tmp_path):
    """Pixels are gated by posture and the /images setting, not by model skill."""
    from agent.console.config import ConsoleConfig
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "anthropic", "claude-opus-4-7"
    app = tui_mod.ConsoleApp(cfg)
    app._select_session(app.store.create())
    app._model_harness_profile = lambda: {
        "reliability": "high", "context_window": 200000, "vision": True}

    assert app.posture.current is Posture.STANDARD
    assert app._image_policy_decision()[0] is True

    # The default image setting keeps captures local in Pseudonymised mode.
    app.posture.request_posture(Posture.PSEUDONYMISED, None, reason="test")
    allowed, reason = app._image_policy_decision()
    assert allowed is False and "Pseudonymised" in reason

    app.posture.request_posture(Posture.STANDARD, None, reason="test")

    app._images_command("never")
    assert app._image_policy_decision()[0] is False

    app._images_command("always")
    app.posture.request_posture(Posture.PSEUDONYMISED, None, reason="test")
    assert app._image_policy_decision()[0] is True, "explicit always overrides"

    # on-premises never sends pixels to a cloud model, even on 'always'
    app.posture.request_posture(Posture.ON_PREMISES, tmp_path, reason="test")
    allowed, reason = app._image_policy_decision()
    assert allowed is False and "cloud" in reason

    # a model without vision never receives pixels
    app.posture.request_posture(Posture.STANDARD, None, reason="test")
    app._model_harness_profile = lambda: {
        "reliability": "high", "context_window": 200000, "vision": False}
    assert app._image_policy_decision()[0] is False


def test_image_attachment_is_journalled_and_counted(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "anthropic", "claude-opus-4-7"
    app = tui_mod.ConsoleApp(cfg)
    app._select_session(app.store.create())
    app.call_from_thread = lambda fn, *a, **k: fn(*a, **k)

    app._record_image_attachment("capture_image", 4000)

    events = app.evidence.query(event_type="image_capture")
    assert events[0]["payload"]["attached_to_model"] is True
    assert events[0]["payload"]["approximate_bytes"] == 3000
    assert app.egress.calls == 1
    assert app.receipts.recent(1)[0].command == "image attachment"


def test_provider_client_respects_image_policy(tmp_path):
    from PIL import Image
    from agent.providers.base import ImageAttachmentPolicy, ToolCall, message_image_bytes
    from agent.providers import anthropic_native as an

    capture = tmp_path / "shot.png"
    Image.new("RGB", (120, 90), (5, 90, 160)).save(capture)
    client = an.AnthropicNativeClient.__new__(an.AnthropicNativeClient)
    call = ToolCall(id="t1", name="capture_image", args={"max_size": 1024})

    allowed_messages = []
    client.append_tool_result(allowed_messages, call, str(capture))
    assert message_image_bytes(allowed_messages[0]) > 0

    client.configure_image_policy(ImageAttachmentPolicy(allowed=False, reason="posture"))
    refused_messages = []
    client.append_tool_result(refused_messages, call, str(capture))
    assert message_image_bytes(refused_messages[0]) == 0
    # the path still reaches the model; only the pixels are withheld
    assert refused_messages[0]["content"][0]["content"] == str(capture)


def test_turn_reports_attached_image_bytes(isolated_home, tmp_path):
    from PIL import Image
    from agent.console.agent_loop import ConsoleAgent, TurnCallbacks

    capture = tmp_path / "frame.png"
    Image.new("RGB", (100, 80), (200, 30, 30)).save(capture)

    def capture_image(max_size: int = 1024):
        return str(capture)
    capture_image.__name__ = "capture_image"

    agent = ConsoleAgent("anthropic", "claude-opus-4-7", api_key="sk-fake")
    agent.tools = [capture_image]
    from agent.providers import anthropic_native as an
    real_client = an.AnthropicNativeClient.__new__(an.AnthropicNativeClient)
    fake = _FakeClient([
        {"text": "", "calls": [_Call("capture_image", {"max_size": 1024})]},
        {"text": "the mask looks correct"},
    ])
    fake.append_tool_result = real_client.append_tool_result
    agent.client = fake

    seen = []
    agent.turn("look at the image", TurnCallbacks(on_image_attached=lambda n, b: seen.append((n, b))))
    assert seen and seen[0][0] == "capture_image" and seen[0][1] > 0


def test_roi_and_focus_commands_send_gui_actions(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    sent = []

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app.fiji = fake_fiji(app.config.host, app.config.port)
    app.fiji.command = lambda payload: sent.append(payload) or {"ok": True}
    app.call_from_thread = lambda fn, *a, **k: fn(*a, **k)
    app._fiji_state = {"active_title": "blobs.gif"}
    logs = []
    app._log = logs.append
    app._set_rail_status = lambda text: None

    # run the worker body inline; the UI thread is not involved in this test
    app._fiji_rail_action = lambda label, action: action()
    app._roi_command("10 20 30 40")
    app._focus_command("")

    assert [payload["type"] for payload in sent] == ["highlight_roi", "focus_image"]
    assert sent[0]["command"] == "gui_action"
    assert sent[0]["roi"] == [10, 20, 30, 40]
    assert sent[0]["title"] == "blobs.gif"
    assert sent[1]["title"] == "blobs.gif"


def test_roi_command_rejects_bad_arguments(isolated_home, fake_fiji):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app.fiji = fake_fiji(app.config.host, app.config.port)
    logs = []
    app._log = logs.append
    app._fiji_state = {"active_title": "blobs.gif"}

    app._roi_command("not numbers")
    assert any("usage:" in str(line) for line in logs)

    app._fiji_state = {}
    logs.clear()
    app._focus_command("")
    assert any("no active image" in str(line) for line in logs)


def test_governance_pane_shows_posture_counters_and_image_state(isolated_home, tmp_path):
    from agent.console.config import ConsoleConfig
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    image = tmp_path / "x.tif"; image.write_bytes(b"x")

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen(); await pilot.pause(0.1)
            app._fiji_state = {"active_path": str(image)}
            app._model_harness_profile = lambda: {
                "reliability": "high", "context_window": 100000, "vision": True}
            app._open_governance()
            await pilot.pause(0.4)
            assert type(app.screen).__name__ == "GovernanceScreen"
            facts = str(app.screen.query_one("#gov-facts").content)
            assert "Outbound calls:" in facts
            assert "AI_Exports" in facts and "imagejai_audit.csv" in facts
            assert app.posture.current is Posture.STANDARD
            assert "Images to the model: yes" in facts

    asyncio.run(_inner())


def test_receipts_pane_lists_calls_and_shows_redacted_detail(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    cfg = ConsoleConfig.load(); cfg.provider, cfg.model = "anthropic", "claude-opus-4-7"

    async def _inner():
        app = tui_mod.ConsoleApp(cfg)
        async with app.run_test(size=(140, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen(); await pilot.pause(0.1)
            app._record_egress_bytes(2048, command="chat turn", categories=("prompt",))
            app._show_receipts()
            await pilot.pause(0.4)
            assert type(app.screen).__name__ == "ReceiptsScreen"
            rows = app.screen.rows
            assert rows and rows[-1]["command"] == "chat turn"
            detail = str(app.screen.query_one("#rcp-detail-text").content)
            assert "chat turn" in detail
            assert "receipt_note" in detail or "redaction_applied" in detail

    asyncio.run(_inner())


def test_statement_is_written_next_to_the_images(isolated_home, tmp_path):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    image = tmp_path / "y.tif"; image.write_bytes(b"x")
    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app._select_session(app.store.create())
    app.call_from_thread = lambda fn, *a, **k: fn(*a, **k)
    app._log = lambda text: None
    app._fiji_state = {"active_path": str(image)}

    app._write_statement.__wrapped__(app) if hasattr(app._write_statement, "__wrapped__") else app._write_statement()

    written = list((tmp_path / "AI_Exports").glob("DataHandlingStatement_*.md"))
    assert len(written) == 1
    text = written[0].read_text(encoding="utf-8")
    assert "Data Handling Statement" in text
    assert "Posture in force: Standard" in text
    events = app.evidence.query(event_type="decision")
    assert any(e["payload"].get("kind") == "data_handling_statement" for e in events)


def test_browse_uses_per_folder_tag_rules_and_suggests_a_tag(isolated_home, fake_fiji, tmp_path):
    """Local naming rules fill the tag columns; only tokens and the tag leave."""
    from PIL import Image
    from agent.console.config import ConsoleConfig
    from agent.console import browse
    from agent.console import tui as tui_mod

    # "mouse" is not a default genotype token, so matching it proves the
    # per-folder override replaced the built-in rules.
    (tmp_path / ".imagejai-tags.yml").write_text(
        "patterns:\n"
        "  - name: genotype\n"
        "    pattern: '(?i)(mouse)'\n"
        "    format: '{1}'\n"
        "  - name: timepoint\n"
        "    pattern: '(?i)(?<![A-Za-z0-9])(\\d+)w(?![A-Za-z0-9])'\n"
        "    format: '{1} weeks'\n",
        encoding="utf-8")
    for name in ("mouse_KO_12w_a.tif", "mouse_KO_12w_b.tif"):
        Image.new("L", (6, 4), 3).save(tmp_path / name)

    async def _inner():
        app = tui_mod.ConsoleApp(ConsoleConfig.load())
        app.fiji = fake_fiji(app.config.host, app.config.port)
        async with app.run_test(size=(150, 44)) as pilot:
            await pilot.pause(0.4)
            while len(app.screen_stack) > 1:
                app.pop_screen(); await pilot.pause(0.1)
            entries = browse.scan_folder(tmp_path, token_map=app.path_tokens)
            from agent.console.posture import Posture
            app.posture.request_posture(Posture.PSEUDONYMISED)
            app._open_browse_screen(tmp_path, entries)
            await pilot.pause(0.4)
            screen = app.screen
            assert type(screen).__name__ == "BrowseFilesScreen"
            assert [r["Genotype"] for r in screen.rows] == ["mouse", "mouse"]
            assert [r["Timepoint"] for r in screen.rows] == ["12 weeks", "12 weeks"]

            screen.query_one("#browse-list").select_all()
            await pilot.pause(0.3)
            preview = str(screen.query_one("#browse-preview").content)
            assert "mouse" in preview and "12 weeks" in preview
            assert "mouse_KO_12w_a" not in preview  # only tokens plus the tag

            await pilot.click("#browse-insert")
            await pilot.pause(0.4)
            chat = app.screen_stack[0].query_one("#chat-input").value
            assert "image-" in chat and "mouse_KO_12w" not in chat
            assert "12 weeks" in chat

    asyncio.run(_inner())


def test_browse_falls_back_to_default_rules_on_a_broken_rule_file(isolated_home, tmp_path):
    """A broken override must not silently disable tagging."""
    from PIL import Image
    from agent.console.browse_ui import BrowseFilesScreen
    from agent.console import browse
    from agent.console.browse import PathTokenMap

    (tmp_path / ".imagejai-tags.yml").write_text("patterns: [[[ not yaml", encoding="utf-8")
    Image.new("L", (6, 4), 3).save(tmp_path / "sample_WT_3d.tif")
    entries = browse.scan_folder(tmp_path, token_map=PathTokenMap(salt=b"s" * 32))

    screen = BrowseFilesScreen(str(tmp_path), entries)
    assert screen.tag_rules
    # The ported engine reports genotypes in words, as the Swing dialog did.
    assert screen.rows[0]["Genotype"] == "wild-type"
    assert screen.rows[0]["Timepoint"] == "3 days"


def test_on_premises_refuses_cloud_providers_and_cloud_ollama_tags(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    # Standard does not restrict the provider choice.
    assert app.posture_refusal_for("anthropic", "claude-opus-4-7") == ""

    app.posture.request_posture(Posture.ON_PREMISES, None, reason="test")
    assert "sends data off this machine" in app.posture_refusal_for("anthropic", "claude-opus-4-7")
    # A cloud Ollama tag is cloud even though the provider looks local.
    assert "cloud-hosted Ollama" in app.posture_refusal_for("ollama", "gemma4:31b-cloud")
    assert app.posture_refusal_for("ollama", "gemma3:27b") == ""


def test_on_premises_fails_closed_instead_of_building_a_cloud_agent(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console.posture import Posture
    from agent.console import tui as tui_mod

    cfg = ConsoleConfig.load()
    cfg.provider, cfg.model = "anthropic", "claude-opus-4-7"
    app = tui_mod.ConsoleApp(cfg)
    logs = []
    app._log = logs.append
    app.posture.request_posture(Posture.ON_PREMISES, None, reason="test")
    app.agent = object()

    app._ensure_agent()

    assert app.agent is None
    assert any("On-premises" in str(line) for line in logs)


def test_knowledge_commands_and_digest_use_shipped_content(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    class Agent:
        def set_harness_digest(self, value): self.digest = value

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app._select_session(app.store.create())
    index = app.knowledge()
    assert index is not None, "the shipped recipes/references must be found"
    assert index.diagnostics == []
    rows = index.rows()
    assert sum(1 for r in rows if r.kind == "procedure") >= 20
    assert sum(1 for r in rows if r.kind == "fact") >= 20

    logs = []
    app._log = logs.append
    app._knowledge_command("count nuclei")
    assert any("Bundled knowledge" in str(line) for line in logs)

    app.agent = Agent()
    app._apply_harness_digest("count nuclei in a fluorescence image")
    assert "[shipped with the package, not session-specific]" in app.agent.digest
    assert "bundled/" in app.agent.digest


def test_reference_body_only_arrives_on_demand(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    index = app.knowledge()
    found = index.search("macro", limit=5)
    for _why, entry in found:
        assert len(entry.content) < 2000, "a digest row must not carry a whole document"

    logs = []
    app._log = logs.append
    app._reference_command("macro-reference.md")
    assert logs and len(str(logs[-1])) > 500


def test_session_rename_and_delete_keeps_the_scientific_record(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console.sessions import sessions_dir
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    app._log = lambda text: None
    app._reload_sessions = lambda: None
    session = app.store.create()
    app._select_session(session)
    app.evidence.append("user", {"text": "measured 42 nuclei"})

    app._rename_session("nuclei count run 3")
    assert app.store.get(session.id).title == "nuclei count run 3"

    app._delete_session(session.id, "Delete the chat only")
    assert app.store.get(session.id) is None
    assert (sessions_dir() / session.id / "evidence.jsonl").is_file()

    other = app.store.create()
    app._select_session(other)
    app.evidence.append("user", {"text": "second run"})
    app._delete_session(other.id, "Delete chat and evidence")
    assert not (sessions_dir() / other.id).exists()


def test_input_limit_and_transcript_notice(isolated_home):
    from agent.console.config import ConsoleConfig
    from agent.console import tui as tui_mod

    app = tui_mod.ConsoleApp(ConsoleConfig.load())
    logs = []
    app._log = logs.append
    app._submit_text("x" * (tui_mod.MAX_INPUT_CHARS + 1))
    assert any("limit is" in str(line) for line in logs)

    async def _inner():
        app2 = tui_mod.ConsoleApp(ConsoleConfig.load())
        async with app2.run_test(size=(120, 40)) as pilot:
            await pilot.pause(0.3)
            while len(app2.screen_stack) > 1:
                app2.pop_screen(); await pilot.pause(0.1)
            for index in range(tui_mod.MAX_TRANSCRIPT_ENTRIES + 3):
                app2._log(f"line {index}")
            assert app2._dropped_transcript_entries > 0
            assert app2._transcript_notice_at > 0

    asyncio.run(_inner())
